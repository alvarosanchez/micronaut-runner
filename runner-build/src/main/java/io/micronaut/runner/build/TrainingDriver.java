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
package io.micronaut.runner.build;

import io.micronaut.runner.build.training.TrainingSettings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Launches an application once for a training run and takes it down again, whatever happens in between.
 *
 * <p>One run is: launch the archive with {@code java -jar}, wait until the application is ready, send it the
 * workload, call back, stop it. Every phase has a timeout, all of the application's output goes to a file,
 * and every process the run started is gone when {@link #run} returns or throws, the application's own
 * children included. A build JVM that exits or is stopped in an orderly way while a run is in progress, such as
 * by SIGTERM on Linux and macOS, takes the application down with it through a shutdown hook. One that is killed
 * outright runs no hook and leaves the application running: after SIGKILL, or when Windows terminates it.</p>
 *
 * <p>The driver names no build tool and knows nothing about what the run is for: the caller passes the JVM
 * arguments that make it a recording or a cache training, and reads the result in its
 * {@link AfterWorkload} callback.</p>
 *
 * <p>{@link StartupProfileRecorder} and the JDK AOT-cache code launch through it; build plugins call the
 * recorder with {@link TrainingSettings}.</p>
 *
 * @since 1.0
 */
final class TrainingDriver {

    /**
     * The environment variables through which a JVM takes options nobody passed it. They are removed from the
     * application's environment, so that a class cache or an agent named by the machine the build runs on
     * does not take part in the run. A caller that starts other JVMs of the same run, such as a JDK tool
     * aimed at the application, removes them too.
     */
    static final List<String> AMBIENT_JVM_OPTIONS = List.of(
            "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JDK_AOT_VM_OPTIONS");

    /** The exit status of a JVM ended by SIGTERM: 128 + 15. */
    private static final int SIGTERM_EXIT_STATUS = 143;

    /** How often the application is polled while the driver waits for it. */
    private static final long POLL_MILLIS = 50;

    /** How long one attempt to connect to the application's port may take. */
    private static final int CONNECT_TIMEOUT_MILLIS = 200;

    /** How many lines of the application's output a failure quotes. */
    private static final int LOG_TAIL_LINES = 30;

    /** How much of the end of the log is read to find those lines. */
    private static final int LOG_TAIL_BYTES = 64 * 1024;

    /** How much of one line a failure quotes. */
    private static final int LOG_TAIL_COLUMNS = 500;

    /** How long a process is given to end after {@code destroy()}, before it is killed. */
    private static final long TERMINATE_MILLIS = 2_000;

    /** How long a killed process is given to disappear. */
    private static final long KILL_MILLIS = 5_000;

    /** The waits of the shutdown hook, which must not hold up a JVM that is ending. */
    private static final long HOOK_MILLIS = 500;

    private static final boolean WINDOWS = File.separatorChar == '\\';

    private TrainingDriver() {
    }

    /**
     * Runs the application once.
     *
     * <p>The command is {@code <java> <settings.jvmArgs> <jvmArguments> -jar <jar>}, so the caller's
     * arguments win over the user's. The application runs in {@code workingDirectory} with its standard input
     * closed and its output appended to {@code logFile}. Its environment is the build's, without the
     * {@linkplain #AMBIENT_JVM_OPTIONS ambient JVM option variables}, plus
     * {@link TrainingSettings#environment()} and a free loopback port in
     * {@link TrainingSettings#portVariable()}.</p>
     *
     * <p>The exit status is reported, not judged: a caller that needs an orderly exit asks
     * {@link Outcome#requireOrderlyExit()}.</p>
     *
     * @param java             the {@code java} executable to launch with
     * @param jar              the archive to launch; with a layout, its application jar
     * @param jvmArguments     the caller's JVM arguments, placed after the user's
     * @param settings         how to reach, exercise and stop the application
     * @param workingDirectory the application's working directory, created if it does not exist
     * @param logFile          the file the application's output is appended to
     * @param afterWorkload    what to do between the workload and the stop
     * @param log              where the phases are reported
     * @return how the run ended
     * @throws IOException          if a phase fails or times out; the message names the phase, the time since
     *                              the launch and the log, and quotes the end of the log
     * @throws InterruptedException if the thread is interrupted; every process has been reaped by then
     */
    static Outcome run(Path java,
                       Path jar,
                       List<String> jvmArguments,
                       TrainingSettings settings,
                       Path workingDirectory,
                       Path logFile,
                       AfterWorkload afterWorkload,
                       BuildLogger log) throws IOException, InterruptedException {
        Objects.requireNonNull(java, "java");
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(jvmArguments, "jvmArguments");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(afterWorkload, "afterWorkload");
        Objects.requireNonNull(log, "log");
        Path directory = workingDirectory.toAbsolutePath();
        Path output = logFile.toAbsolutePath();
        Files.createDirectories(directory);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        Map<String, String> parent = System.getenv();
        List<String> ambient = ambientVariables(parent);
        if (!ambient.isEmpty()) {
            log.info("Training launches the application without " + String.join(", ", ambient)
                    + ", which the build's environment sets");
        }
        int port = freePort();
        Map<String, String> environment = childEnvironment(parent, settings, port);

        List<String> command = new ArrayList<>();
        command.add(java.toAbsolutePath().toString());
        command.addAll(settings.jvmArgs());
        command.addAll(jvmArguments);
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        ProcessBuilder builder = redirected(new ProcessBuilder(command), directory, output, environment);

        Reaper reaper = new Reaper();
        Thread hook = new Thread(reaper::reapOnShutdown, "micronaut-runner-training-reaper");
        long launched = System.nanoTime();
        Process application = builder.start();
        reaper.add(application.toHandle());
        Throwable failure = null;
        try {
            // An ending JVM refuses the hook: the run then fails here and the finally block reaps.
            Runtime.getRuntime().addShutdownHook(hook);
            application.getOutputStream().close();
            log.info("Training launched " + jar.getFileName() + " as process " + application.pid() + " on port "
                    + port + "; its output is in " + output);
            return new Run(application, reaper, settings, port, directory, output, environment, afterWorkload,
                    log, launched).execute();
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            List<ProcessHandle> survivors = reaper.reap(TERMINATE_MILLIS, KILL_MILLIS);
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // The JVM is shutting down and the hook is running or has run.
            }
            if (!survivors.isEmpty()) {
                IOException leak = new IOException("Training could not end "
                        + (survivors.size() == 1 ? "process " : "processes ")
                        + survivors.stream().map(handle -> Long.toString(handle.pid()))
                                .collect(Collectors.joining(", "))
                        + ", which it started: end it by hand");
                if (failure == null) {
                    throw leak;
                }
                failure.addSuppressed(leak);
            }
        }
    }

    /**
     * The ambient JVM option variables a parent environment sets, which a training launch does not inherit.
     *
     * @param parent the environment of the build process
     * @return the names as the parent spells them, in its order
     */
    static List<String> ambientVariables(Map<String, String> parent) {
        return parent.keySet().stream().filter(TrainingDriver::ambient).toList();
    }

    /**
     * The environment of the application: the parent's without the ambient JVM option variables, then
     * {@link TrainingSettings#environment()}, then the port.
     *
     * @param parent   the environment of the build process
     * @param settings the training settings
     * @param port     the port the application is to listen on
     * @return the environment, in the order it is applied
     */
    static Map<String, String> childEnvironment(Map<String, String> parent, TrainingSettings settings, int port) {
        Map<String, String> environment = new LinkedHashMap<>();
        parent.forEach((name, value) -> {
            if (!ambient(name)) {
                environment.put(name, value);
            }
        });
        environment.putAll(settings.environment());
        environment.put(settings.portVariable(), Integer.toString(port));
        return environment;
    }

    /**
     * Picks a loopback port nothing listens on, by binding it and letting it go. Another process can take it
     * before the application does; the application then fails to bind, which its log shows.
     *
     * @return the port
     * @throws IOException if no port can be bound
     */
    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /**
     * The last lines of a log, for a failure message.
     *
     * @param logFile the log
     * @return the lines under a heading, or a note that there are none
     */
    static String logTail(Path logFile) {
        byte[] bytes;
        try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
            long length = file.length();
            bytes = new byte[(int) Math.min(length, LOG_TAIL_BYTES)];
            file.seek(length - bytes.length);
            file.readFully(bytes);
        } catch (IOException e) {
            return "\n(the log could not be read: " + e.getMessage() + ")";
        }
        List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        if (lines.isEmpty()) {
            return "\n(the application wrote no output)";
        }
        int first = Math.max(0, lines.size() - LOG_TAIL_LINES);
        StringBuilder tail = new StringBuilder("\n--- last ").append(lines.size() - first)
                .append(" lines of the application's output ---");
        for (int i = first; i < lines.size(); i++) {
            String line = lines.get(i);
            tail.append('\n').append(line.length() > LOG_TAIL_COLUMNS
                    ? line.substring(0, LOG_TAIL_COLUMNS) + " ..." : line);
        }
        return tail.toString();
    }

    private static boolean ambient(String name) {
        // Windows looks a variable up without regard to case.
        return AMBIENT_JVM_OPTIONS.contains(WINDOWS ? name.toUpperCase(Locale.ROOT) : name);
    }

    private static ProcessBuilder redirected(ProcessBuilder builder, Path directory, Path logFile,
                                             Map<String, String> environment) {
        builder.directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        builder.environment().clear();
        builder.environment().putAll(environment);
        return builder;
    }

    private static String millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos) + " ms";
    }

    private static boolean successful(int status) {
        return status >= 200 && status < 300;
    }

    /**
     * What a training run does between its workload and its stop: read what the application recorded, or
     * tell the JVM to finish a recording.
     */
    @FunctionalInterface
    interface AfterWorkload {

        /** The callback of a run that has nothing to do at this point. */
        AfterWorkload NOTHING = _ -> {
        };

        /**
         * Runs while the application is still up and its workload is complete. With
         * {@link TrainingSettings#runToExit()} it runs once the application has exited. The driver stops and
         * reaps only after it returns.
         *
         * @param application the application's process, for a caller that addresses it by process id
         * @throws IOException          if the run cannot be used; the run fails with this exception
         * @throws InterruptedException if the thread is interrupted while it waits
         */
        void run(ProcessHandle application) throws IOException, InterruptedException;
    }

    /**
     * How a training run ended.
     *
     * @param exitStatus the application's exit status
     * @param forced     whether the application did not exit within
     *                   {@link TrainingSettings#stopTimeout()} and was killed
     * @param destroyed  whether the driver stopped the application by destroying its process, which is
     *                   SIGTERM on Linux and macOS, rather than with a stop request or by waiting for it to
     *                   end by itself
     * @param readiness  the time from the launch to readiness; with {@link TrainingSettings#runToExit()}, to
     *                   the exit
     * @param total      the time from the launch to the exit
     */
    record Outcome(int exitStatus, boolean forced, boolean destroyed, Duration readiness, Duration total) {

        /**
         * Fails unless the application shut down in an orderly way: with status 0, or with the status of a
         * JVM that SIGTERM ended when the driver destroyed the process. A run whose stop has to write
         * something, such as a JDK cache, calls this.
         *
         * <p>On Windows a destroyed process is terminated without running its shutdown hooks and exits with
         * status 1, so this fails there unless {@link TrainingSettings#stopPath()} is set.</p>
         *
         * @throws IOException if the application was killed or exited with another status
         */
        void requireOrderlyExit() throws IOException {
            if (forced) {
                throw new IOException("The application did not stop in time and was killed");
            }
            if (exitStatus != 0 && !(destroyed && exitStatus == SIGTERM_EXIT_STATUS)) {
                throw new IOException("The application exited with status " + exitStatus
                        + ", which is not an orderly shutdown"
                        + (destroyed ? ": where a process cannot be ended by a signal, as on Windows, set a"
                        + " stopPath" : ""));
            }
        }
    }

    /** The phases of one run. */
    private static final class Run {

        private final Process application;
        private final Reaper reaper;
        private final TrainingSettings settings;
        private final int port;
        private final Path directory;
        private final Path logFile;
        private final Map<String, String> environment;
        private final AfterWorkload afterWorkload;
        private final BuildLogger log;
        private final long launched;

        private Run(Process application, Reaper reaper, TrainingSettings settings, int port, Path directory,
                    Path logFile, Map<String, String> environment, AfterWorkload afterWorkload, BuildLogger log,
                    long launched) {
            this.application = application;
            this.reaper = reaper;
            this.settings = settings;
            this.port = port;
            this.directory = directory;
            this.logFile = logFile;
            this.environment = environment;
            this.afterWorkload = afterWorkload;
            this.log = log;
            this.launched = launched;
        }

        private Outcome execute() throws IOException, InterruptedException {
            if (settings.runToExit()) {
                return runToExit();
            }
            Duration readiness = awaitReadiness();
            workload();
            afterWorkload.run(application.toHandle());
            return stop(readiness);
        }

        private Outcome runToExit() throws IOException, InterruptedException {
            long deadline = launched + settings.readinessTimeout().toNanos();
            while (!application.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                reaper.snapshot();
                if (System.nanoTime() >= deadline) {
                    throw failure("the run", "the application did not exit within "
                            + settings.readinessTimeout() + ". runToExit is for an application that ends by"
                            + " itself");
                }
            }
            if (application.exitValue() != 0) {
                throw failure("the run", "the application exited with status " + application.exitValue());
            }
            Duration total = Duration.ofNanos(System.nanoTime() - launched);
            log.info("Training: the application ran to its exit in " + total.toMillis() + " ms");
            afterWorkload.run(application.toHandle());
            return new Outcome(0, false, false, total, total);
        }

        private Duration awaitReadiness() throws IOException, InterruptedException {
            long deadline = launched + settings.readinessTimeout().toNanos();
            String path = settings.readinessPath().orElse(null);
            boolean accepted = false;
            String last = null;
            while (true) {
                reaper.snapshot();
                if (!application.isAlive()) {
                    throw failure("readiness", "the application exited with status " + application.exitValue()
                            + " before it was ready");
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                accepted = accepted || accepts(left);
                if (accepted && path == null) {
                    return ready();
                }
                if (accepted) {
                    try {
                        int status = request("GET", path, deadline);
                        if (successful(status)) {
                            return ready();
                        }
                        last = "answered HTTP " + status;
                    } catch (IOException e) {
                        last = "failed with " + e;
                    }
                }
                Thread.sleep(POLL_MILLIS);
            }
            if (!accepted) {
                throw failure("readiness", "nothing accepted a connection on 127.0.0.1:" + port + " within "
                        + settings.readinessTimeout() + ". The port is passed in the environment variable "
                        + settings.portVariable());
            }
            throw failure("readiness", "GET " + path + " did not answer 2xx within "
                    + settings.readinessTimeout() + "; the last request " + last);
        }

        private Duration ready() {
            Duration readiness = Duration.ofNanos(System.nanoTime() - launched);
            log.info("Training: the application was ready after " + readiness.toMillis() + " ms");
            return readiness;
        }

        private void workload() throws IOException, InterruptedException {
            long started = System.nanoTime();
            long deadline = started + settings.workloadTimeout().toNanos();
            int requests = 0;
            if (settings.workloadPaths().isEmpty() && settings.workloadCommand().isEmpty()) {
                log.warn("No training workload is configured, so the application only served one GET /. Set"
                        + " workloadPaths to the paths it serves in production: the workload decides which"
                        + " classes a training run sees.");
                int status = workloadRequest("/", deadline);
                if (status >= 500) {
                    throw failure("the workload", "GET / answered HTTP " + status);
                }
                requests = 1;
            }
            for (int repeat = 0; repeat < settings.workloadRepeat(); repeat++) {
                for (String path : settings.workloadPaths()) {
                    int status = workloadRequest(path, deadline);
                    if (!successful(status)) {
                        throw failure("the workload", "GET " + path + " answered HTTP " + status
                                + "; a workload path must answer 2xx");
                    }
                    requests++;
                }
            }
            if (!settings.workloadCommand().isEmpty()) {
                workloadCommand(deadline);
            }
            reaper.snapshot();
            requireAlive("the workload");
            log.info("Training: the workload of " + requests + (requests == 1 ? " request" : " requests")
                    + (settings.workloadCommand().isEmpty() ? "" : " and the workload command")
                    + " took " + millis(System.nanoTime() - started));
        }

        private int workloadRequest(String path, long deadline) throws IOException, InterruptedException {
            requireAlive("the workload");
            if (System.nanoTime() >= deadline) {
                throw failure("the workload", "the workload did not finish within " + settings.workloadTimeout()
                        + "; GET " + path + " was not sent");
            }
            try {
                return request("GET", path, deadline);
            } catch (IOException e) {
                // A request fails a moment before the process that was serving it is seen to have ended.
                if (application.waitFor(10 * POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    requireAlive("the workload");
                }
                throw failure("the workload", "GET " + path + " failed with " + e);
            }
        }

        private void workloadCommand(long deadline) throws IOException, InterruptedException {
            Map<String, String> commandEnvironment = new LinkedHashMap<>(environment);
            commandEnvironment.put(TrainingSettings.URL_VARIABLE, "http://127.0.0.1:" + port);
            Process command;
            try {
                command = redirected(new ProcessBuilder(settings.workloadCommand()), directory, logFile,
                        commandEnvironment).start();
            } catch (IOException e) {
                throw failure("the workload", "workloadCommand could not be started: " + e.getMessage());
            }
            reaper.add(command.toHandle());
            command.getOutputStream().close();
            while (!command.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                reaper.snapshot();
                requireAlive("the workload command");
                if (System.nanoTime() >= deadline) {
                    throw failure("the workload", "workloadCommand did not exit within "
                            + settings.workloadTimeout() + ", counted from the start of the workload");
                }
            }
            if (command.exitValue() != 0) {
                throw failure("the workload", "workloadCommand exited with status " + command.exitValue());
            }
        }

        private Outcome stop(Duration readiness) throws IOException, InterruptedException {
            long started = System.nanoTime();
            long deadline = started + settings.stopTimeout().toNanos();
            // The last look at the application's children: once it is gone they cannot be found through it.
            reaper.snapshot();
            String path = settings.stopPath().orElse(null);
            if (path == null) {
                // The handle's destroy(), not the Process's, which also closes the streams to the child.
                application.toHandle().destroy();
            } else {
                int status = -1;
                try {
                    status = request("POST", path, deadline);
                } catch (IOException e) {
                    // An application that stops at once may drop the connection instead of answering.
                    log.info("Training: POST " + path + " got no answer (" + e + "); waiting for the application"
                            + " to exit");
                }
                if (status != -1 && !successful(status)) {
                    throw failure("the stop", "POST " + path + " answered HTTP " + status
                            + ", so the application was not asked to stop");
                }
            }
            boolean forced = false;
            while (!application.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                reaper.snapshot();
                if (System.nanoTime() >= deadline) {
                    forced = true;
                    application.toHandle().destroyForcibly();
                    if (!application.waitFor(KILL_MILLIS, TimeUnit.MILLISECONDS)) {
                        throw failure("the stop", "the application did not exit within "
                                + settings.stopTimeout() + " and could not be killed");
                    }
                    break;
                }
            }
            int exitStatus = application.exitValue();
            long ended = System.nanoTime();
            log.info("Training: the application exited with status " + exitStatus + " "
                    + millis(ended - started) + " after "
                    + (path == null ? "its process was destroyed" : "POST " + path)
                    + (forced ? "; it did not stop in time and was killed" : ""));
            return new Outcome(exitStatus, forced, path == null, readiness, Duration.ofNanos(ended - launched));
        }

        private void requireAlive(String phase) throws IOException {
            if (!application.isAlive()) {
                throw failure(phase, "the application exited with status " + application.exitValue());
            }
        }

        private boolean accepts(long leftNanos) {
            int timeout = (int) Math.clamp(TimeUnit.NANOSECONDS.toMillis(leftNanos), 1, CONNECT_TIMEOUT_MILLIS);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port),
                        timeout);
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        /**
         * Sends one request and drains the response. The connect, each read and the whole body are bounded by
         * the deadline of the phase.
         */
        private int request(String method, String path, long deadline) throws IOException {
            int timeout = (int) Math.clamp(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()), 1,
                    Integer.MAX_VALUE);
            // No proxy: a proxy the build is configured with must not be asked for a loopback address.
            HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path)
                    .toURL().openConnection(Proxy.NO_PROXY);
            try {
                connection.setConnectTimeout(timeout);
                connection.setReadTimeout(timeout);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod(method);
                connection.setRequestProperty("Connection", "close");
                if ("POST".equals(method)) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(0);
                    connection.getOutputStream().close();
                }
                int status = connection.getResponseCode();
                try (InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    while (body != null && body.read(buffer) >= 0) {
                        if (System.nanoTime() >= deadline) {
                            throw new SocketTimeoutException("the response body did not end in time");
                        }
                    }
                }
                return status;
            } finally {
                connection.disconnect();
            }
        }

        private IOException failure(String phase, String detail) {
            return new IOException("Training failed during " + phase + ", "
                    + millis(System.nanoTime() - launched) + " after the launch: " + detail
                    + ". The application's output is in " + logFile + logTail(logFile));
        }
    }

    /**
     * Knows every process a run started and ends them. The application and the workload command are the
     * roots; their descendants are recorded while the roots live, because a process whose parent has ended
     * can no longer be found through it.
     */
    private static final class Reaper {

        private final Set<ProcessHandle> roots = ConcurrentHashMap.newKeySet();
        private final Set<ProcessHandle> descendants = ConcurrentHashMap.newKeySet();

        private void add(ProcessHandle root) {
            roots.add(root);
            snapshot();
        }

        /** Records the descendants of every root that is still alive. */
        private void snapshot() {
            record(roots);
        }

        private void record(Collection<ProcessHandle> parents) {
            for (ProcessHandle parent : parents) {
                if (parent.isAlive()) {
                    try (Stream<ProcessHandle> children = parent.descendants()) {
                        children.forEach(descendants::add);
                    } catch (RuntimeException ignored) {
                        // A process table that cannot be read leaves the snapshot as it was.
                    }
                }
            }
        }

        /**
         * Ends every process that is still alive: the roots with {@code destroy()} and then, when they do not
         * go, {@code destroyForcibly()}; their descendants with {@code destroyForcibly()}. The waits are not
         * cut short by an interrupt, which is restored afterwards.
         *
         * @param terminateMillis how long a destroyed root is given to end
         * @param killMillis      how long a killed process is given to disappear
         * @return the processes that are still alive
         */
        private List<ProcessHandle> reap(long terminateMillis, long killMillis) {
            boolean interrupted = Thread.interrupted();
            try {
                snapshot();
                List<ProcessHandle> alive = alive(roots);
                alive.forEach(ProcessHandle::destroy);
                interrupted |= await(alive, terminateMillis);
                alive = alive(alive);
                alive.forEach(ProcessHandle::destroyForcibly);
                interrupted |= await(alive, killMillis);

                // What a descendant started since the last snapshot is reachable through it while it lives.
                record(List.copyOf(descendants));
                List<ProcessHandle> orphans = alive(descendants);
                orphans.forEach(ProcessHandle::destroyForcibly);
                interrupted |= await(orphans, killMillis);

                List<ProcessHandle> survivors = new ArrayList<>(alive(roots));
                survivors.addAll(alive(descendants));
                return survivors;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /** The same, from the shutdown hook of a JVM that is ending: with waits short enough not to delay it. */
        private void reapOnShutdown() {
            reap(HOOK_MILLIS, HOOK_MILLIS);
        }

        private static List<ProcessHandle> alive(Collection<ProcessHandle> handles) {
            return handles.stream().filter(ProcessHandle::isAlive).toList();
        }

        /**
         * Waits until none of the processes is alive.
         *
         * @return whether the thread was interrupted meanwhile
         */
        private static boolean await(Collection<ProcessHandle> handles, long millis) {
            boolean interrupted = false;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            return interrupted;
        }
    }
}
