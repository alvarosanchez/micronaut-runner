/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.runner.benchmarks;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts one packaged application and times how long it takes to answer.
 *
 * <h2>The rules this class exists to enforce</h2>
 * <ul>
 *   <li><strong>One clock.</strong> {@link System#nanoTime()} is read immediately before
 *       {@link ProcessBuilder#start()} and again the instant the first HTTP 200 comes back. Nothing in
 *       between is measured by anything else, and no clock inside the application is trusted to agree.</li>
 *   <li><strong>Readiness is a response, not a log line.</strong> The application printing "Startup
 *       completed" means the framework thinks it has finished; a 200 on the wire means a client can
 *       actually be served. The log line <em>is</em> recorded, as
 *       {@link StartupSample#frameworkMillis()}, because the gap between the two is interesting - but it
 *       never stands in for the measurement.</li>
 *   <li><strong>No {@code -Xlog} on a timing run.</strong> Unified logging costs milliseconds and it costs
 *       them unevenly across formats, which is precisely the size of the effect being measured.
 *       {@link #diagnose} exists for class-load counting and its results are labelled as diagnostics.</li>
 *   <li><strong>A free port per run, never a fixed one.</strong> A hard-coded port collides with whatever
 *       else is on the machine and turns one unlucky run into a failed benchmark.</li>
 *   <li><strong>The process is destroyed in a {@code finally} block,</strong> whether the run succeeded,
 *       timed out or threw. A leaked JVM holding a port would poison every run after it.</li>
 * </ul>
 */
final class StartupHarness implements AutoCloseable {

    /** How often readiness is polled. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(2);

    /** How long a single poll may take before it counts as "not yet". */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);

    /** How long to keep reading the process output for the framework's own startup line after readiness. */
    private static final Duration FRAMEWORK_LINE_GRACE = Duration.ofSeconds(2);

    /** How long a destroyed process is given to die before it is killed. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(10);

    /** Micronaut's own startup line, recorded as a separate metric. */
    private static final Pattern STARTUP_LINE = Pattern.compile("Startup completed in (\\d+)ms");

    private final HttpClient client;
    private final String readinessPath;
    private final Duration startupTimeout;

    /**
     * Creates a harness with one persistent HTTP client for every poll of every run.
     *
     * @param readinessPath  the path polled for readiness, for example {@code /hello}
     * @param startupTimeout how long an application may take to answer before the run is failed
     */
    StartupHarness(String readinessPath, Duration startupTimeout) {
        this.readinessPath = readinessPath;
        this.startupTimeout = startupTimeout;
        this.client = HttpClient.newBuilder()
                // HTTP/1.1 explicitly: the negotiation an HTTP/2-capable client performs is latency that
                // belongs to the client, not to the application being measured.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(POLL_TIMEOUT)
                .build();
    }

    /**
     * Runs one variant once and times it.
     *
     * @param variant   the variant to start
     * @param iteration the iteration this run belongs to
     * @param warmup    whether this run's result is to be discarded
     * @return the sample
     * @throws IOException          if the process cannot be started, dies early or never answers
     * @throws InterruptedException if the wait is interrupted
     */
    StartupSample run(Variant variant, int iteration, boolean warmup)
            throws IOException, InterruptedException {
        return run(variant, iteration, warmup, List.of());
    }

    /**
     * A diagnostic run, explicitly not a timing run: it carries {@code -Xlog:class+load} and its cost is
     * therefore not comparable with anything {@link #run} produced.
     *
     * @param variant the variant to start
     * @param logFile where the unified log is written
     * @return how many classes the JVM loaded before the application answered
     * @throws IOException          if the run fails
     * @throws InterruptedException if the wait is interrupted
     */
    ClassLoadCount diagnose(Variant variant, Path logFile) throws IOException, InterruptedException {
        Files.deleteIfExists(logFile);
        StartupSample sample = run(variant, -1, true,
                List.of("-Xlog:class+load=info:file=" + logFile.toAbsolutePath()));
        int loaded = 0;
        int shared = 0;
        if (Files.isRegularFile(logFile)) {
            for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                if (line.contains("[class,load]")) {
                    loaded++;
                    if (line.contains("shared objects file")) {
                        shared++;
                    }
                }
            }
        }
        return new ClassLoadCount(variant.name(), loaded, shared, sample.readinessMillis());
    }

    private StartupSample run(Variant variant, int iteration, boolean warmup, List<String> extraJvmArgs)
            throws IOException, InterruptedException {
        int port = freePort();
        List<String> command = new ArrayList<>(variant.command().size() + extraJvmArgs.size());
        command.add(variant.command().get(0));
        command.addAll(extraJvmArgs);
        command.addAll(variant.command().subList(1, variant.command().size()));

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(variant.workingDirectory().toFile())
                .redirectErrorStream(true);
        builder.environment().put("SERVER_PORT", Integer.toString(port));

        URI readiness = URI.create("http://127.0.0.1:" + port + readinessPath);
        HttpRequest request = HttpRequest.newBuilder(readiness).timeout(POLL_TIMEOUT).GET().build();

        StringBuilder output = new StringBuilder();
        Process process = null;
        Thread drain = null;
        try {
            long start = System.nanoTime();
            process = builder.start();
            drain = drain(process, output);

            long deadline = start + startupTimeout.toNanos();
            long lastFailureEnd = start;
            long ready = -1;
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new IOException(variant.name() + " exited with status " + process.exitValue()
                            + " before answering " + readiness + tail(output));
                }
                try {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        ready = System.nanoTime();
                        break;
                    }
                } catch (IOException e) {
                    // Connection refused for most of the poll, because the server is not listening yet;
                    // once in a while a connection that was accepted and then dropped mid-handshake.
                    // Both mean the same thing here: not ready, try again.
                }
                lastFailureEnd = System.nanoTime();
                Thread.sleep(POLL_INTERVAL.toMillis());
            }
            if (ready < 0) {
                throw new IOException(variant.name() + " did not answer " + readiness + " within "
                        + startupTimeout + tail(output));
            }

            double readinessMillis = (ready - start) / 1_000_000.0;
            double pollGapMillis = (ready - lastFailureEnd) / 1_000_000.0;
            double frameworkMillis = awaitFrameworkLine(output);
            return new StartupSample(iteration, warmup, port, readinessMillis, frameworkMillis,
                    pollGapMillis, destroy(process, drain));
        } finally {
            if (process != null && process.isAlive()) {
                destroy(process, drain);
            }
        }
    }

    /**
     * Reads the framework's own startup line out of the captured output, if it turns up.
     *
     * <p>It is looked for <em>after</em> readiness, with a short grace period, because the HTTP endpoint
     * can start answering a moment before the line reaches the console appender. Not finding it is not an
     * error: it is recorded as {@code -1} and the report shows the metric as missing rather than guessing.
     *
     * @param output the captured output so far
     * @return the milliseconds the framework reported, or {@code -1}
     * @throws InterruptedException if the wait is interrupted
     */
    private static double awaitFrameworkLine(StringBuilder output) throws InterruptedException {
        long deadline = System.nanoTime() + FRAMEWORK_LINE_GRACE.toNanos();
        while (true) {
            String text;
            synchronized (output) {
                text = output.toString();
            }
            Matcher matcher = STARTUP_LINE.matcher(text);
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            }
            if (System.nanoTime() >= deadline) {
                return -1;
            }
            Thread.sleep(10);
        }
    }

    /**
     * Picks a port nothing is listening on by binding it and letting it go again.
     *
     * @return a port that was free a moment ago
     */
    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not find a free port", e);
        }
    }

    private static int destroy(Process process, Thread drain) throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
        if (drain != null) {
            drain.join(SHUTDOWN_GRACE.toMillis());
        }
        return process.isAlive() ? -1 : process.exitValue();
    }

    private static Thread drain(Process process, StringBuilder into) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream in = process.getInputStream()) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    synchronized (into) {
                        into.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException e) {
                synchronized (into) {
                    into.append("\n[output capture stopped: ").append(e).append(']');
                }
            }
        }, "startup-benchmark-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String tail(StringBuilder output) {
        String text;
        synchronized (output) {
            text = output.toString();
        }
        String[] lines = text.split("\n");
        int from = Math.max(0, lines.length - 30);
        StringBuilder result = new StringBuilder("\n--- last ").append(lines.length - from)
                .append(" lines of the application's output ---\n");
        for (int i = from; i < lines.length; i++) {
            result.append(lines[i]).append('\n');
        }
        return result.toString();
    }

    @Override
    public void close() {
        client.close();
    }

    /**
     * The result of a diagnostic run. Never mixed into the timing statistics.
     *
     * @param variant          the variant that was run
     * @param classesLoaded    how many classes the JVM loaded
     * @param fromSharedArchive how many of those came from a CDS or AOT archive
     * @param readinessMillis  the readiness time of this run, which is slower than a timing run because of
     *                         the logging and is reported only so the slowdown is visible
     */
    record ClassLoadCount(String variant, int classesLoaded, int fromSharedArchive, double readinessMillis) {
    }
}
