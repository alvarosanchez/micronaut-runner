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
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts one packaged application and times how long it takes to answer. It is the only code that spawns and polls
 * an application: {@link #exercise} runs the AOT-cache training and verification launches through the same
 * spawn and readiness code as the timed runs.
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
 *   <li><strong>Launch preparation stays off the clock.</strong> The {@link BeforeLaunch} hook, where the
 *       page-cache modes evict, runs before the clock starts; a CPU limit's {@code taskset} prefix is spawned with
 *       the child and costs the same exec in every variant.</li>
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

    /** The exit status of a JVM ended by SIGTERM: 128 + 15. */
    private static final int SIGTERM_EXIT_STATUS = 143;

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

    /**
     * Runs immediately before the clock of every launch starts (warm-up, measured and diagnostic), after the
     * previous child has exited. The page-cache modes evict here, so eviction is never part of a timed interval.
     */
    interface BeforeLaunch {

        /** Does nothing: the default, and {@code uncontrolled}. */
        BeforeLaunch NONE = new BeforeLaunch() {
            @Override
            public void run(Variant variant) {
                // Nothing to prepare.
            }

            @Override
            public boolean evicts() {
                return false;
            }
        };

        /**
         * Prepares the launch.
         *
         * @param variant the variant about to be started
         * @throws IOException if the preparation fails, which fails the whole benchmark run
         */
        void run(Variant variant) throws IOException;

        /**
         * Whether this hook evicts pages. A child still alive after a forced kill keeps its mapped pages cached,
         * so under an evicting hook that attempt fails instead of skewing the next one.
         *
         * @return {@code true} unless this is {@link #NONE}
         */
        default boolean evicts() {
            return true;
        }
    }

    /** The {@link BeforeLaunch} hook failed: the run cannot establish the page-cache state it promised. */
    static final class LaunchPreparationFailure extends RuntimeException {

        LaunchPreparationFailure(String message, IOException cause) {
            super(message, cause);
        }
    }

    /**
     * Injectable timing, port, environment, probe, command-prefix and launch-hook policy used by deterministic
     * forked tests.
     *
     * @param pollInterval   how often readiness is polled
     * @param pollTimeout    how long one poll may take
     * @param logLineGrace   how long to wait for the startup line after readiness
     * @param shutdownGrace  how long a destroyed child is given before it is killed
     * @param servingGrace   how long a non-200 answer is tolerated
     * @param portSupplier   picks each run's port
     * @param environment    the child's environment, before the ambient JVM options are removed
     * @param readinessProbe reads memory and classes at readiness
     * @param commandPrefix  what the spawned command starts with, for example {@code taskset -c 0}; never part of
     *                       {@link Variant#command()}
     * @param beforeLaunch   runs immediately before the clock of every launch starts
     */
    record Settings(Duration pollInterval,
                    Duration pollTimeout,
                    Duration logLineGrace,
                    Duration shutdownGrace,
                    Duration servingGrace,
                    IntSupplier portSupplier,
                    Map<String, String> environment,
                    ReadinessProbe readinessProbe,
                    List<String> commandPrefix,
                    BeforeLaunch beforeLaunch) {

        Settings {
            environment = Map.copyOf(environment);
            commandPrefix = List.copyOf(commandPrefix);
        }

        static Settings defaults() {
            return new Settings(POLL_INTERVAL, POLL_TIMEOUT, LOG_LINE_GRACE, SHUTDOWN_GRACE, SERVING_GRACE,
                    StartupHarness::freePort, System.getenv(), ReadinessSnapshot::take, List.of(), BeforeLaunch.NONE);
        }

        /**
         * These settings with a command prefix and a launch hook.
         *
         * @param commandPrefix the prefix, for example {@code taskset -c 0}
         * @param beforeLaunch  the hook
         * @return the copy
         */
        Settings with(List<String> commandPrefix, BeforeLaunch beforeLaunch) {
            return new Settings(pollInterval, pollTimeout, logLineGrace, shutdownGrace, servingGrace, portSupplier,
                    environment, readinessProbe, commandPrefix, beforeLaunch);
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

    /**
     * Creates a harness whose children run under a command prefix and whose launches are preceded by a hook.
     *
     * @param readinessPath  the path polled for readiness
     * @param startupTimeout how long an application may take to answer before the run is failed
     * @param commandPrefix  what every spawned command starts with; empty for none
     * @param beforeLaunch   runs immediately before the clock of every launch starts
     */
    StartupHarness(String readinessPath, Duration startupTimeout, List<String> commandPrefix,
                   BeforeLaunch beforeLaunch) {
        this(readinessPath, startupTimeout, Settings.defaults().with(commandPrefix, beforeLaunch));
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
     * The path polled for readiness, which is also part of an AOT cache's identity.
     *
     * @return the path, for example {@code /hello}
     */
    String readinessPath() {
        return readinessPath;
    }

    /**
     * How long an application may take to answer; it also bounds a training launch's exit.
     *
     * @return the startup timeout
     */
    Duration startupTimeout() {
        return startupTimeout;
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
     * @return the run's readiness with logging and its effective command, which the report relocates
     * @throws IOException          if the run fails
     * @throws InterruptedException if the wait is interrupted
     */
    DiagnosticRun diagnose(Variant variant, Path logFile) throws IOException, InterruptedException {
        Files.deleteIfExists(logFile);
        List<String> diagnosticArguments = List.of("-Xlog:class+load=info:file=" + logFile.toAbsolutePath());
        StartupSample sample = run(variant, -1, true, diagnosticArguments);
        return new DiagnosticRun(variant.name(), sample.readinessMillis(),
                processCommand(List.of(), variant.command(), diagnosticArguments));
    }

    /**
     * Training and verification launch. Never a timing run.
     *
     * <p>The child is spawned and polled for readiness by the same code as {@link #run}, so it gets the same
     * environment, the same command prefix and the same failures. Each workload path must then answer HTTP 200.
     * There is no wait for the startup line, no readiness snapshot and no {@link BeforeLaunch} hook, so nothing is
     * evicted. The child is sent SIGTERM once, an orderly shutdown in which the JVM writes an AOT cache, and must
     * exit with status 0 or 143 within the startup timeout, because assembling a cache takes seconds. It is
     * killed only when a failure left it alive.</p>
     *
     * @param variant       the variant to start
     * @param extraJvmArgs  JVM arguments for this launch only, placed directly after the variant's {@code java}
     * @param workloadPaths the paths requested once the child is ready
     * @return the last lines of the child's output, for a caller whose own check of the launch fails
     * @throws RunFailure           if the child fails to answer, a workload request fails, or the child does not
     *                              exit with 0 or 143 in time
     * @throws IOException          if the child cannot be started
     * @throws InterruptedException if a wait is interrupted
     */
    String exercise(Variant variant, List<String> extraJvmArgs, List<String> workloadPaths)
            throws IOException, InterruptedException {
        int port = settings.portSupplier().getAsInt();
        ProcessBuilder builder = processBuilder(variant, extraJvmArgs, port);
        Launch launch = new Launch(URI.create("http://127.0.0.1:" + port + readinessPath), settings.pollTimeout());
        try {
            awaitReadiness(variant, builder, launch);
            for (String path : workloadPaths) {
                URI uri = URI.create("http://127.0.0.1:" + port + path);
                CompletableFuture<HttpResponse<String>> call = client.sendAsync(
                        HttpRequest.newBuilder(uri).timeout(startupTimeout).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                int status;
                try {
                    status = call.get(startupTimeout.toMillis(), TimeUnit.MILLISECONDS).statusCode();
                } catch (ExecutionException | TimeoutException e) {
                    call.cancel(true);
                    throw new RunFailure(variant.name() + " did not answer the workload request " + uri + ": " + e
                            + tail(launch.capture), null);
                }
                if (status != 200) {
                    throw new RunFailure(variant.name() + " answered the workload request " + uri + " with HTTP "
                            + status + tail(launch.capture), null);
                }
            }
            // On Linux and macOS this is SIGTERM. ProcessHandle.destroy(), not Process.destroy(): the latter also
            // closes the output pipe, and a training JVM whose output pipe is closed before SIGTERM still exits 143
            // but writes no cache (JDK 25.0.4.1).
            launch.process.toHandle().destroy();
            if (!launch.process.waitFor(startupTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new RunFailure(variant.name() + " did not exit within " + startupTimeout + " of SIGTERM"
                        + tail(launch.capture), null);
            }
            int exitCode = launch.process.exitValue();
            if (exitCode != 0 && exitCode != SIGTERM_EXIT_STATUS) {
                throw new RunFailure(variant.name() + " exited with status " + exitCode + " after SIGTERM"
                        + tail(launch.capture), exitCode);
            }
            launch.drain.join(settings.shutdownGrace().toMillis());
            return tail(launch.capture);
        } finally {
            if (launch.process != null && launch.process.isAlive()) {
                launch.process.destroyForcibly().waitFor(settings.shutdownGrace().toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private StartupSample run(Variant variant, int iteration, boolean warmup, List<String> extraJvmArgs)
            throws IOException, InterruptedException {
        int port = settings.portSupplier().getAsInt();
        ProcessBuilder builder = processBuilder(variant, extraJvmArgs, port);
        Launch launch = new Launch(URI.create("http://127.0.0.1:" + port + readinessPath), settings.pollTimeout());
        try {
            try {
                settings.beforeLaunch().run(variant);
            } catch (IOException e) {
                throw new LaunchPreparationFailure("could not prepare the launch of " + variant.name() + ": "
                        + e.getMessage(), e);
            }
            awaitReadiness(variant, builder, launch);

            // start, ready and lastFailureEnd are final and the child is alive: the one place where a probe can
            // neither move readinessMillis nor miss the process.
            ReadinessSnapshot atReadiness = probe(launch.process, Path.of(variant.command().get(0)), launch.ready);
            awaitStartupLine(launch.capture, settings.logLineGrace());
            // Read before destroy(), as when destroy() was the sample's last argument: a startup line the drain
            // thread hands over after the grace has ended stays unrecorded instead of getting a late timestamp.
            double logLineMillis = launch.capture.logLineMillis();
            double frameworkMillis = launch.capture.reportedMillis();
            int exitCode = requireGone(variant.name(), destroy(launch.process, launch.drain, settings.shutdownGrace()),
                    settings.beforeLaunch());
            return new StartupSample(iteration, warmup, port,
                    (launch.ready - launch.start) / 1_000_000.0,
                    logLineMillis,
                    frameworkMillis,
                    (launch.ready - launch.lastFailureEnd) / 1_000_000.0,
                    exitCode,
                    atReadiness);
        } finally {
            if (launch.process != null && launch.process.isAlive()) {
                destroy(launch.process, launch.drain, settings.shutdownGrace());
            }
        }
    }

    /**
     * The child process of a run: the command with the prefix and the extra JVM arguments, the settings'
     * environment without the ambient JVM options, and the port.
     */
    private ProcessBuilder processBuilder(Variant variant, List<String> extraJvmArgs, int port) {
        ProcessBuilder builder = new ProcessBuilder(processCommand(settings.commandPrefix(), variant.command(),
                extraJvmArgs))
                .directory(variant.workingDirectory().toFile())
                .redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(settings.environment());
        removeInheritedJvmOptions(builder);
        builder.environment().put("SERVER_PORT", Integer.toString(port));
        return builder;
    }

    /**
     * Spawns the child and polls it until it answers HTTP 200: the timed block of {@link #run}, which
     * {@link #exercise} shares. The clock starts immediately before {@link ProcessBuilder#start()}, and nothing
     * but polling happens between the spawn and the first 200.
     *
     * <p>On JDK 25 a poll's request timeout ends it when no response headers arrive, but not when the headers arrive
     * and the body never ends (from JDK 26 it covers the body too), and a poll once hung for good with the timeout
     * set. A {@link Watchdog}, armed before the clock starts and disarmed once the loop has ended, interrupts such a
     * poll one poll timeout after the startup timeout. Until then its thread only waits, so every poll does exactly
     * what it did without it.</p>
     *
     * @param variant the variant, for failure messages
     * @param builder the child process
     * @param launch  the readiness request, which receives the process, its output and the instants on the run's
     *                clock
     * @throws RunFailure           if the child exits first, serves another status, or never answers
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the wait is interrupted
     */
    private void awaitReadiness(Variant variant, ProcessBuilder builder, Launch launch)
            throws IOException, InterruptedException {
        URI readiness = launch.readiness;
        HttpRequest request = launch.request;
        Watchdog watchdog = Watchdog.arm(startupTimeout.plus(settings.pollTimeout()));
        try {
            long start = System.nanoTime();
            Process process = builder.start();
            launch.process = process;
            Capture capture = new Capture(start);
            launch.capture = capture;
            launch.drain = drain(process, capture);

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
            launch.start = start;
            launch.ready = ready;
            launch.lastFailureEnd = lastFailureEnd;
        } catch (InterruptedException e) {
            if (watchdog.fired()) {
                throw new RunFailure(variant.name() + " did not answer " + readiness + " within " + startupTimeout
                        + ": a poll was still waiting for its response " + settings.pollTimeout() + " later"
                        + tail(launch.capture), null);
            }
            throw e;
        } finally {
            watchdog.disarm();
        }
    }

    /**
     * The command a run spawns: the prefix, then the variant's command with the extra JVM arguments directly after
     * its {@code java}. The arguments go after {@code java}, never after the prefix's own executable, and the
     * prefix never enters {@link Variant#command()}, so the reported commands are the same with or without it.
     *
     * @param prefix         what the spawned command starts with, for example {@code taskset -c 0}; empty for none
     * @param variantCommand the variant's command, {@code java} first
     * @param extraJvmArgs   JVM arguments for this run only, such as a diagnostic {@code -Xlog}
     * @return the spawned command
     */
    static List<String> processCommand(List<String> prefix, List<String> variantCommand, List<String> extraJvmArgs) {
        List<String> command = new ArrayList<>(prefix.size() + variantCommand.size() + extraJvmArgs.size());
        command.addAll(prefix);
        command.add(variantCommand.get(0));
        command.addAll(extraJvmArgs);
        command.addAll(variantCommand.subList(1, variantCommand.size()));
        return List.copyOf(command);
    }

    /**
     * Fails the attempt whose child is still alive after the forced kill when the launch hook evicts: eviction
     * cannot drop pages a live process still maps, so the next launch would start warm. Without an evicting hook a
     * surviving child is recorded as exit status {@code -1}, as it always was.
     *
     * @param variantName  the variant that was launched
     * @param exitCode     what {@code destroy} returned: the child's exit status, or {@code -1} when it is still
     *                     alive
     * @param beforeLaunch the launch hook of this run
     * @return {@code exitCode}
     * @throws RunFailure if the child survived and the hook evicts
     */
    static int requireGone(String variantName, int exitCode, BeforeLaunch beforeLaunch) throws RunFailure {
        if (exitCode == -1 && beforeLaunch.evicts()) {
            throw new RunFailure(variantName + " was still alive after a forced kill; under page-cache"
                    + " eviction the attempt fails rather than leave the next launch's pages cached", null);
        }
        return exitCode;
    }

    /**
     * Takes the readiness snapshot. A failing probe yields an unavailable snapshot, never a failed run.
     *
     * @param process        the ready child
     * @param javaExecutable the variant's {@code java}, not the command prefix's executable
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
     * Picks a port nothing is listening on, on any address, by binding it and letting it go again.
     *
     * <p>The readiness probe connects to {@code 127.0.0.1}. A listener bound to the loopback address alone, such as
     * a local VM's SSH port forward, does not stop a wildcard bind that reuses addresses on macOS: the port looked
     * free, the application bound it too and logged that it was running, and the probe reached the other listener
     * until the run timed out. The port is therefore chosen without address reuse, and kept only if the loopback
     * address can be bound as well.</p>
     *
     * @return a port that was free a moment ago
     */
    static int freePort() {
        return freePort(() -> {
            try {
                return bindExclusively(new InetSocketAddress(0));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not find a free port", e);
            }
        });
    }

    /**
     * The first candidate port that the loopback address can also be bound on.
     *
     * @param candidates ports that were free on the wildcard address a moment ago, one per call
     * @return a candidate that was free on the loopback address a moment ago
     */
    static int freePort(IntSupplier candidates) {
        for (int attempt = 0; attempt < 20; attempt++) {
            int port = candidates.getAsInt();
            if (freeOnLoopback(port)) {
                return port;
            }
        }
        throw new UncheckedIOException(new IOException("Could not find a port that is free on the loopback address"));
    }

    /**
     * Whether nothing listens on a port of the loopback address, which is where the readiness probe connects.
     *
     * @param port the port
     * @return whether the loopback address could be bound on it without address reuse
     */
    static boolean freeOnLoopback(int port) {
        try {
            bindExclusively(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Binds an address without address reuse and lets it go again.
     *
     * <p>With reuse, macOS lets a wildcard bind share a port with a listener on the loopback address alone.</p>
     *
     * @param address the address, port {@code 0} for one the system picks
     * @return the port that was bound
     * @throws IOException if the address could not be bound, for example because something holds it
     */
    static int bindExclusively(InetSocketAddress address) throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(address);
            return socket.getLocalPort();
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
     * One spawned child: its readiness request, built before the launch is prepared, then its process and output,
     * and the instants its readiness poll read on the run's clock.
     */
    private static final class Launch {

        private final URI readiness;
        private final HttpRequest request;
        private Process process;
        private Thread drain;
        private Capture capture;
        private long start;
        private long ready;
        private long lastFailureEnd;

        Launch(URI readiness, Duration pollTimeout) {
            this.readiness = readiness;
            this.request = HttpRequest.newBuilder(readiness).timeout(pollTimeout).GET().build();
        }
    }

    /**
     * Interrupts the thread that armed it once a bound has passed, unless it was disarmed first. Its thread is
     * already waiting on the watchdog's monitor when {@link #arm} returns, before a launch's clock starts, and it
     * wakes only when {@link #disarm()} is called after the readiness loop or when the bound has passed.
     */
    private static final class Watchdog implements Runnable {

        private final Thread poller = Thread.currentThread();
        private final long deadline;
        private boolean waiting;
        private boolean armed = true;
        private boolean fired;

        private Watchdog(Duration bound) {
            this.deadline = System.nanoTime() + bound.toNanos();
        }

        static Watchdog arm(Duration bound) throws InterruptedException {
            Watchdog watchdog = new Watchdog(bound);
            Thread thread = new Thread(watchdog, "startup-benchmark-watchdog");
            thread.setDaemon(true);
            synchronized (watchdog) {
                thread.start();
                try {
                    while (!watchdog.waiting) {
                        watchdog.wait();
                    }
                } catch (InterruptedException e) {
                    watchdog.armed = false;
                    throw e;
                }
            }
            return watchdog;
        }

        @Override
        public synchronized void run() {
            waiting = true;
            notifyAll();
            long remaining = deadline - System.nanoTime();
            while (armed && remaining > 0) {
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException e) {
                    return;
                }
                remaining = deadline - System.nanoTime();
            }
            if (armed) {
                fired = true;
                poller.interrupt();
            }
        }

        synchronized boolean fired() {
            return fired;
        }

        /** Stops the watchdog, and clears the interrupt it sent, if any: the loop it was sent to has ended. */
        void disarm() {
            boolean sent;
            synchronized (this) {
                armed = false;
                notifyAll();
                sent = fired;
            }
            if (sent) {
                Thread.interrupted();
            }
        }
    }

    /**
     * The result of a diagnostic run. Never mixed into the timing statistics.
     *
     * @param variant         the variant that was run
     * @param readinessMillis the readiness time of this run, which is slower than a timing run because of the
     *                        logging and is reported only so the slowdown is visible
     * @param command         the effective diagnostic command, without the command prefix
     */
    record DiagnosticRun(String variant, double readinessMillis, List<String> command) {

        DiagnosticRun {
            command = List.copyOf(command);
        }
    }
}
