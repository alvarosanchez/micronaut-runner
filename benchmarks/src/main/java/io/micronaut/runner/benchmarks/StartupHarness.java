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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
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
 *       exists for per-class inspection and everything it produces is labelled a diagnostic.</li>
 *   <li><strong>Nothing between spawn and readiness but polling.</strong> Memory and loaded classes are read
 *       once, by the {@link ReadinessProbe}, after readiness is final and before any wait or
 *       {@code destroy()}, so the probe can neither move the readiness time nor miss the process.</li>
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
final class StartupHarness implements StartupRunner, AutoCloseable {

    /** A failed start with the child exit status when the process supplied one. */
    static final class RunFailure extends IOException {
        private final Integer exitCode;

        RunFailure(String message, Integer exitCode) {
            super(message);
            this.exitCode = exitCode;
        }

        Integer exitCode() {
            return exitCode;
        }
    }

    /** JVM option environment variables that would make a nominally plain variant use different flags. */
    private static final List<String> INHERITED_JVM_OPTIONS = List.of(
            "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JDK_AOT_VM_OPTIONS");

    /** How often readiness is polled. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(2);

    /** How long a single poll may take before it counts as "not yet". */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);

    /**
     * How long to keep waiting for the framework's own startup line after readiness.
     *
     * <p>The line normally arrives before readiness, because serving the first request on a cold JVM costs
     * more than logging it, so the wait usually ends at once. The grace only covers the race between the
     * first HTTP response and the console appender flushing the line. It also bounds the idle cost of every
     * run when a sample chosen with {@code -Pbenchmarks.sample=...} never prints the line.</p>
     */
    private static final Duration LOG_LINE_GRACE = Duration.ofMillis(500);

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

    /**
     * Reads a child's memory and loaded classes once its readiness is final. It is called while the child is
     * alive, outside the timed interval; a {@link RuntimeException} it throws makes the snapshot unavailable
     * and never fails the run.
     */
    @FunctionalInterface
    interface ReadinessProbe {

        /**
         * Takes the snapshot.
         *
         * @param pid            the child
         * @param javaExecutable the variant's {@code java}
         * @return the snapshot, whose {@code probeMillis} the harness sets
         */
        ReadinessSnapshot take(long pid, Path javaExecutable);
    }

    /** Injectable timing, port, environment and probe policy used by deterministic forked tests. */
    record Settings(Duration pollInterval,
                    Duration pollTimeout,
                    Duration logLineGrace,
                    Duration shutdownGrace,
                    Duration servingGrace,
                    IntSupplier portSupplier,
                    Map<String, String> environment,
                    ReadinessProbe readinessProbe) {

        Settings {
            environment = Map.copyOf(environment);
        }

        static Settings defaults() {
            return new Settings(POLL_INTERVAL, POLL_TIMEOUT, LOG_LINE_GRACE, SHUTDOWN_GRACE, SERVING_GRACE,
                    StartupHarness::freePort, System.getenv(), ReadinessSnapshot::take);
        }
    }

    private final HttpClient client;
    private final String readinessPath;
    private final Duration startupTimeout;
    private final Settings settings;

    /**
     * Creates a harness with one persistent HTTP client for every poll of every run.
     *
     * @param readinessPath  the path polled for readiness, for example {@code /hello}
     * @param startupTimeout how long an application may take to answer before the run is failed
     */
    StartupHarness(String readinessPath, Duration startupTimeout) {
        this(readinessPath, startupTimeout, Settings.defaults());
    }

    StartupHarness(String readinessPath, Duration startupTimeout, Settings settings) {
        this.readinessPath = readinessPath;
        this.startupTimeout = startupTimeout;
        this.settings = settings;
        this.client = HttpClient.newBuilder()
                // HTTP/1.1 explicitly: the negotiation an HTTP/2-capable client performs is latency that
                // belongs to the client, not to the application being measured.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.pollTimeout())
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
    @Override
    public StartupSample run(Variant variant, int iteration, boolean warmup)
            throws IOException, InterruptedException {
        return run(variant, iteration, warmup, List.of());
    }

    /**
     * A diagnostic run, explicitly not a timing run: it carries {@code -Xlog:class+load} and its cost is
     * therefore not comparable with anything {@link #run} produced. The log is for per-class inspection; the
     * only reported class counts are the timing runs' readiness snapshots.
     *
     * @param variant the variant to start
     * @param logFile where the unified log is written
     * @return the run's readiness with logging and its relocatable command
     * @throws IOException          if the run fails
     * @throws InterruptedException if the wait is interrupted
     */
    DiagnosticRun diagnose(Variant variant, Path logFile) throws IOException, InterruptedException {
        Files.deleteIfExists(logFile);
        List<String> diagnosticArguments = List.of("-Xlog:class+load=info:file=" + logFile.toAbsolutePath());
        StartupSample sample = run(variant, -1, true, diagnosticArguments);
        return new DiagnosticRun(variant.name(), sample.readinessMillis(),
                BenchmarkProvenance.relocatableCommand(variant,
                        List.of("-Xlog:class+load=info:file=${diagnostic-log}")));
    }

    private StartupSample run(Variant variant, int iteration, boolean warmup, List<String> extraJvmArgs)
            throws IOException, InterruptedException {
        int port = settings.portSupplier().getAsInt();
        List<String> command = new ArrayList<>(variant.command().size() + extraJvmArgs.size());
        command.add(variant.command().get(0));
        command.addAll(extraJvmArgs);
        command.addAll(variant.command().subList(1, variant.command().size()));

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(variant.workingDirectory().toFile())
                .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(settings.environment());
        removeInheritedJvmOptions(builder);
        builder.environment().put("SERVER_PORT", Integer.toString(port));

        URI readiness = URI.create("http://127.0.0.1:" + port + readinessPath);
        HttpRequest request = HttpRequest.newBuilder(readiness).timeout(settings.pollTimeout()).GET().build();

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
                    int exitCode = process.exitValue();
                    throw new RunFailure(variant.name() + " exited with status " + exitCode
                            + " before answering " + readiness + tail(capture), exitCode);
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
                if (firstResponse > 0 && System.nanoTime() - firstResponse > settings.servingGrace().toNanos()) {
                    throw new RunFailure(variant.name() + " is serving " + readiness + " with HTTP "
                            + lastStatus + " and has been for " + settings.servingGrace().toMillis() + "ms."
                            + " The application started; it is not serving the readiness endpoint, which"
                            + " points at the packaging rather than at a slow start. Response body: "
                            + snippet(lastBody) + tail(capture), null);
                }
                lastFailureEnd = System.nanoTime();
                Thread.sleep(settings.pollInterval().toMillis());
            }
            if (ready < 0) {
                throw new RunFailure(variant.name() + " did not answer " + readiness + " within "
                        + startupTimeout + tail(capture), null);
            }

            // start, ready and lastFailureEnd are final and the child is alive: the one place where a probe can
            // neither move readinessMillis nor miss the process.
            ReadinessSnapshot atReadiness = probe(process, Path.of(variant.command().get(0)), ready);
            awaitStartupLine(capture, settings.logLineGrace());
            return new StartupSample(iteration, warmup, port,
                    (ready - start) / 1_000_000.0,
                    capture.logLineMillis(),
                    capture.reportedMillis(),
                    (ready - lastFailureEnd) / 1_000_000.0,
                    destroy(process, drain, settings.shutdownGrace()),
                    atReadiness);
        } finally {
            if (process != null && process.isAlive()) {
                destroy(process, drain, settings.shutdownGrace());
            }
        }
    }

    /**
     * Takes the readiness snapshot. A failing probe yields an unavailable snapshot, never a failed run.
     *
     * @param process        the ready child
     * @param javaExecutable the variant's {@code java}, even if a prefix is ever put in front of the command
     * @param ready          the readiness instant
     * @return the snapshot, with the time from readiness to its completion
     */
    private ReadinessSnapshot probe(Process process, Path javaExecutable, long ready) {
        ReadinessSnapshot snapshot;
        try {
            snapshot = settings.readinessProbe().take(process.pid(), javaExecutable);
        } catch (RuntimeException e) {
            snapshot = null;
        }
        if (snapshot == null) {
            snapshot = ReadinessSnapshot.UNAVAILABLE;
        }
        return snapshot.withProbeMillis((System.nanoTime() - ready) / 1_000_000.0);
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
    private static void awaitStartupLine(Capture capture, Duration grace) throws InterruptedException {
        long deadline = System.nanoTime() + grace.toNanos();
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

    /**
     * Prevents ambient JVM flags from silently turning a no-cache benchmark row into a cache-enabled row.
     *
     * @param builder the child JVM process
     */
    static void removeInheritedJvmOptions(ProcessBuilder builder) {
        INHERITED_JVM_OPTIONS.forEach(builder.environment()::remove);
    }

    private static int destroy(Process process, Thread drain, Duration grace) throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor(grace.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
        if (drain != null) {
            drain.join(grace.toMillis());
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
     * @param variant         the variant that was run
     * @param readinessMillis the readiness time of this run, which is slower than a timing run because of the
     *                        logging and is reported only so the slowdown is visible
     * @param command         the effective relocatable diagnostic command
     */
    record DiagnosticRun(String variant, double readinessMillis, List<String> command) {

        DiagnosticRun {
            command = List.copyOf(command);
        }
    }
}
