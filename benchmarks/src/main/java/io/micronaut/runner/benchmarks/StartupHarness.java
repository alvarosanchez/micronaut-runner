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
 *       {@link ProcessBuilder#start()} and every other instant in the sample is read from the same clock
 *       in the same process. Nothing inside the application is trusted to agree about when anything
 *       happened.</li>
 *   <li><strong>Readiness is a response, not a log line.</strong> The headline metric is the first HTTP
 *       200. A log line saying the framework has started means the framework thinks it has started; a 200
 *       on the wire means a client can be served.</li>
 *   <li><strong>No {@code -Xlog} on a timing run.</strong> Unified logging costs milliseconds and it costs
 *       them unevenly across formats, which is the size of the effect being measured. {@link #diagnose}
 *       exists for class-load counting and everything it produces is labelled a diagnostic.</li>
 *   <li><strong>A free port per run, never a fixed one.</strong></li>
 *   <li><strong>The process is destroyed in a {@code finally} block,</strong> whether the run succeeded,
 *       timed out or threw. A leaked JVM holding a port would poison every run after it.</li>
 * </ul>
 *
 * <h2>Three numbers, not one</h2>
 * <p>Each run records three things, and the difference between them is worth as much as any of them:</p>
 * <ol>
 *   <li>{@link StartupSample#readinessMillis()} - spawn to the first HTTP 200. The headline.</li>
 *   <li>{@link StartupSample#logLineMillis()} - spawn to the moment this process <em>observed</em> the
 *       framework's "Startup completed" line on the child's output. Same clock, still external, and
 *       directly comparable with a measurement someone made by watching the console. It lands earlier than
 *       readiness because serving the very first request on a cold JVM is itself expensive.</li>
 *   <li>{@link StartupSample#frameworkMillis()} - the number the framework printed in that line. It is the
 *       application's own opinion, it starts counting well after the JVM did, and it is recorded so it can
 *       be compared - never so it can be substituted for either of the above.</li>
 * </ol>
 */
final class StartupHarness implements AutoCloseable {

    /** How often readiness is polled. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(2);

    /** How long a single poll may take before it counts as "not yet". */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);

    /** How long to keep waiting for the framework's own startup line after readiness. */
    private static final Duration LOG_LINE_GRACE = Duration.ofSeconds(2);

    /** How long a destroyed process is given to die before it is killed. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(10);

    /**
     * How long an application that <em>is</em> answering, but with the wrong status, is given before the
     * run is failed.
     *
     * <p>Without this the run sits out the whole start-up timeout and the report says "did not answer
     * within two minutes", which reads like a slow start. An application returning 404 on the readiness
     * path has finished starting and is broken - typically its beans were not discovered, which is a fault
     * of the packaging and exactly what this harness should catch loudly.</p>
     */
    private static final Duration SERVING_GRACE = Duration.ofSeconds(5);

    /** Micronaut's own startup line. */
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

        Process process = null;
        Thread drain = null;
        Capture capture = null;
        try {
            long start = System.nanoTime();
            process = builder.start();
            capture = new Capture(start);
            drain = drain(process, capture);

            long deadline = start + startupTimeout.toNanos();
            long lastFailureEnd = start;
            long ready = -1;
            long firstResponse = -1;
            int lastStatus = -1;
            String lastBody = "";
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new IOException(variant.name() + " exited with status " + process.exitValue()
                            + " before answering " + readiness + tail(capture));
                }
                try {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        ready = System.nanoTime();
                        break;
                    }
                    if (firstResponse < 0) {
                        firstResponse = System.nanoTime();
                    }
                    lastStatus = response.statusCode();
                    lastBody = response.body();
                } catch (IOException e) {
                    // Connection refused for most of the poll, because the server is not listening yet;
                    // once in a while a connection that was accepted and then dropped mid-handshake.
                    // Both mean the same thing here: not ready, try again.
                }
                if (firstResponse > 0 && System.nanoTime() - firstResponse > SERVING_GRACE.toNanos()) {
                    throw new IOException(variant.name() + " is serving " + readiness + " with HTTP "
                            + lastStatus + " and has been for " + SERVING_GRACE.toSeconds() + "s."
                            + " The application started; it is not serving the readiness endpoint, which"
                            + " points at the packaging rather than at a slow start. Response body: "
                            + snippet(lastBody) + tail(capture));
                }
                lastFailureEnd = System.nanoTime();
                Thread.sleep(POLL_INTERVAL.toMillis());
            }
            if (ready < 0) {
                throw new IOException(variant.name() + " did not answer " + readiness + " within "
                        + startupTimeout + tail(capture));
            }

            awaitStartupLine(capture);
            return new StartupSample(iteration, warmup, port,
                    (ready - start) / 1_000_000.0,
                    capture.logLineMillis(),
                    capture.reportedMillis(),
                    (ready - lastFailureEnd) / 1_000_000.0,
                    destroy(process, drain));
        } finally {
            if (process != null && process.isAlive()) {
                destroy(process, drain);
            }
        }
    }

    /**
     * Gives the framework's own startup line a moment to arrive after readiness.
     *
     * <p>The HTTP endpoint can start answering just before the line reaches the console appender, so
     * without this the line would occasionally be recorded as missing on a perfectly good run. Not finding
     * it at all is not an error: both of its metrics stay at {@code -1} and the report says so rather than
     * guessing.</p>
     *
     * @param capture the output capture, which timestamps the line as it arrives
     * @throws InterruptedException if the wait is interrupted
     */
    private static void awaitStartupLine(Capture capture) throws InterruptedException {
        long deadline = System.nanoTime() + LOG_LINE_GRACE.toNanos();
        while (capture.logLineMillis() < 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
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

    private static Thread drain(Process process, Capture capture) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream in = process.getInputStream()) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    capture.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                capture.append("\n[output capture stopped: " + e + "]");
            }
        }, "startup-benchmark-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String snippet(String body) {
        if (body == null || body.isEmpty()) {
            return "(empty)";
        }
        String single = body.replace('\n', ' ').replace('\r', ' ');
        return single.length() > 200 ? single.substring(0, 200) + " …" : single;
    }

    private static String tail(Capture capture) {
        String[] lines = capture.text().split("\n");
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
     * The child's output, timestamped as it arrives.
     *
     * <p>The drain thread does the matching rather than a scan after the fact, because the interesting
     * quantity is <em>when</em> the line appeared, not merely that it did. The remaining skew - the time
     * between the child writing into the pipe and this process reading it out - is a pipe read, well under
     * a millisecond, and it is the same skew for every variant.</p>
     */
    private static final class Capture {

        private final StringBuilder text = new StringBuilder(4096);
        private final long startNanos;
        private volatile long startupLineNanos = -1;
        private volatile double reportedMillis = -1;

        Capture(long startNanos) {
            this.startNanos = startNanos;
        }

        void append(String chunk) {
            synchronized (text) {
                text.append(chunk);
                if (startupLineNanos < 0) {
                    Matcher matcher = STARTUP_LINE.matcher(text);
                    if (matcher.find()) {
                        startupLineNanos = System.nanoTime();
                        reportedMillis = Double.parseDouble(matcher.group(1));
                    }
                }
            }
        }

        String text() {
            synchronized (text) {
                return text.toString();
            }
        }

        double logLineMillis() {
            long at = startupLineNanos;
            return at < 0 ? -1 : (at - startNanos) / 1_000_000.0;
        }

        double reportedMillis() {
            return reportedMillis;
        }
    }

    /**
     * The result of a diagnostic run. Never mixed into the timing statistics.
     *
     * @param variant           the variant that was run
     * @param classesLoaded     how many classes the JVM loaded
     * @param fromSharedArchive how many of those came from a CDS or AOT archive
     * @param readinessMillis   the readiness time of this run, which is slower than a timing run because
     *                          of the logging and is reported only so the slowdown is visible
     */
    record ClassLoadCount(String variant, int classesLoaded, int fromSharedArchive, double readinessMillis) {
    }
}
