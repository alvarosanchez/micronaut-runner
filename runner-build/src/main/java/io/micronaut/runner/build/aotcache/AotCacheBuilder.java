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
package io.micronaut.runner.build.aotcache;

import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.runner.build.BuildLogger;
import io.micronaut.runner.build.training.TrainingDriver;
import io.micronaut.runner.build.training.TrainingSettings;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Trains a JDK AOT cache for a JAR in place, and verifies it.
 *
 * <p>The caller stages the program as {@code <directory>/<jarName>}, which is launched with {@code -jar} from
 * that directory, so the cache is trained at the relative layout it runs from. One build is:</p>
 * <ol>
 *     <li><b>Record</b> through the {@link TrainingDriver}: readiness and workload with
 *     {@code -XX:AOTMode=record -XX:AOTConfiguration=app.aotconf -Xlog:aot=info}. The log names the classes the
 *     recording skips, which the gate's coverage check reads.</li>
 *     <li><b>End the recording</b> with {@code jcmd <pid> AOT.end_recording} when the JDK has {@code jcmd} and
 *     knows the command (25.0.4 and later), otherwise with the training stop, which must then be orderly: a
 *     signal on Linux and macOS, the training {@code stopPath} on Windows.</li>
 *     <li><b>Create</b> the cache in its own launch,
 *     {@code -XX:AOTMode=create -XX:AOTConfiguration=app.aotconf -XX:AOTCache=app.aot}, with
 *     {@code -XX:+UnlockDiagnosticVMOptions -XX:+AOTCompatibleOopCompression} when the {@link JdkProbe} finds
 *     the flag.</li>
 *     <li>Write the identity file and the launch argfile ({@link AotLaunchOptions}).</li>
 *     <li>Run the {@link AotCacheGate}. When it fails, the cache and the argfile are deleted and the logs stay.</li>
 * </ol>
 *
 * <p>Every launch carries {@link AotCacheSettings#jvmArgs()} and none of the build's ambient JVM option
 * variables, and each phase is logged with its duration.</p>
 *
 * @since 1.0
 */
@Experimental
public final class AotCacheBuilder {

    /** The recording's output, relative to the output directory. */
    public static final String RECORD_LOG = "aot-record.log";

    /** The creation's output, relative to the output directory. */
    public static final String CREATE_LOG = "aot-create.log";

    /** The recorded configuration, relative to the output directory; deleted once the cache is created. */
    public static final String CONFIGURATION_FILE = "app.aotconf";

    /** What {@code jcmd} prints when the recording has ended and its configuration is written. */
    static final String RECORDING_ENDED = "Recording ended successfully.";

    /** What {@code jcmd} prints for a command the JDK does not know, as JDK 25.0.0 to 25.0.3 say of this one. */
    static final String UNKNOWN_COMMAND = "Unknown diagnostic command";

    /** Whether the recording is ended with {@code jcmd} when the JDK has it; tests turn it off. */
    static volatile boolean useJcmd = true;

    /** How long cache creation may take. */
    private static final Duration CREATE_TIMEOUT = Duration.ofMinutes(10);

    private static final boolean WINDOWS = File.separatorChar == '\\';

    private AotCacheBuilder() {
    }

    /**
     * Trains, creates and verifies a cache in a directory.
     *
     * @param settings  the cache settings
     * @param java      the {@code java} executable to train with; its JDK build is the only one the cache fits. A
     *                  relative path is resolved against this JVM's working directory
     * @param directory the directory that holds the JAR, and receives the cache, the argfile, the identity file,
     *                  the report and the logs
     * @param jarName   the file name of the JAR to launch, in that directory
     * @param training  how to reach, exercise and stop the application; its own {@code jvmArgs} are replaced by
     *                  the settings'
     * @param labels    entries added to the identity file and the report, such as {@code target}
     * @param log       where the phases are reported
     * @return the report of the gate, which passed
     * @throws IOException          if a phase fails or the gate does; the message says which, and the logs stay
     * @throws InterruptedException if the thread is interrupted; every process has been reaped by then
     */
    public static AotCacheReport build(AotCacheSettings settings,
                                       Path java,
                                       Path directory,
                                       String jarName,
                                       TrainingSettings training,
                                       Map<String, String> labels,
                                       BuildLogger log) throws IOException, InterruptedException {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(java, "java");
        Objects.requireNonNull(training, "training");
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(log, "log");
        // Every fork runs in the directory, where a relative path would name another file.
        Path executable = java.toAbsolutePath();
        Path dir = directory.toAbsolutePath().normalize();
        Path jar = dir.resolve(jarName);
        if (!Files.isRegularFile(jar)) {
            throw new IOException("There is no " + jarName + " in " + dir + " to train a JDK AOT cache for");
        }
        for (String file : List.of(AotLaunchOptions.CACHE_FILE, AotLaunchOptions.ARGFILE,
                AotLaunchOptions.IDENTITY_FILE, AotCacheReport.FILE, CONFIGURATION_FILE, RECORD_LOG, CREATE_LOG,
                AotCacheGate.SMOKE_LOG, AotCacheGate.CLASS_LOAD_LOG)) {
            Files.deleteIfExists(dir.resolve(file));
        }
        boolean complete = false;
        try {
            long started = System.nanoTime();
            JdkProbe jdk = JdkProbe.probe(executable);
            List<String> creationFlags = jdk.creationFlags();
            log.info("JDK AOT cache: training with " + jdk.vmVersion() + " on " + jdk.osName() + " "
                    + jdk.osArch() + (creationFlags.isEmpty() ? "" : ", creating with " + String.join(" ",
                    creationFlags)) + " (probe " + millis(started) + ")");

            String recordStop = record(settings, executable, dir, jar, training, log);
            create(settings, executable, dir, jarName, creationFlags, log);

            AotLaunchOptions.writeIdentity(dir.resolve(AotLaunchOptions.IDENTITY_FILE),
                    AotLaunchOptions.identity(jdk, creationFlags, settings.jvmArgs(), labels));
            Files.writeString(dir.resolve(AotLaunchOptions.ARGFILE), AotLaunchOptions.argfile(settings),
                    StandardCharsets.UTF_8);

            AotCacheReport report = AotCacheGate.verify(settings, jdk, executable, dir, jarName, training, recordStop,
                    log);
            complete = true;
            return report;
        } finally {
            Files.deleteIfExists(dir.resolve(CONFIGURATION_FILE));
            if (!complete) {
                Files.deleteIfExists(dir.resolve(AotLaunchOptions.CACHE_FILE));
                Files.deleteIfExists(dir.resolve(AotLaunchOptions.ARGFILE));
            }
        }
    }

    /**
     * Records the configuration and ends the recording.
     *
     * @return how the recording ended
     */
    private static String record(AotCacheSettings settings, Path java, Path dir, Path jar,
                                 TrainingSettings training, BuildLogger log) throws IOException, InterruptedException {
        Path recordLog = dir.resolve(RECORD_LOG);
        Path configuration = dir.resolve(CONFIGURATION_FILE);
        Path jcmd = useJcmd && !training.runToExit() ? jcmdBeside(java) : null;
        String[] stop = new String[1];
        long[] ended = new long[1];
        long started = System.nanoTime();
        TrainingDriver.Outcome outcome = TrainingDriver.run(java, jar,
                List.of("-XX:AOTMode=record", "-XX:AOTConfiguration=" + CONFIGURATION_FILE, "-Xlog:aot=info"),
                AotLaunchOptions.trainingSettings(settings, training), dir, recordLog, application -> {
                    ended[0] = System.nanoTime();
                    if (jcmd != null && application.isAlive()
                            && endRecording(jcmd, application, dir, recordLog, training.stopTimeout(), log)) {
                        stop[0] = AotCacheReport.STOP_JCMD;
                        ended[0] = System.nanoTime();
                    }
                }, log);
        long stopped = System.nanoTime();
        String recordStop;
        if (AotCacheReport.STOP_JCMD.equals(stop[0])) {
            // The recording is complete; how the application then ended does not matter.
            recordStop = AotCacheReport.STOP_JCMD;
        } else if (training.runToExit()) {
            recordStop = AotCacheReport.STOP_EXIT;
        } else {
            try {
                outcome.requireOrderlyExit();
            } catch (IOException e) {
                throw new IOException("The recording ends with the application, which did not stop in an orderly"
                        + " way: " + e.getMessage() + ". Where a process cannot be ended by a signal, as on"
                        + " Windows, set the training stopPath, or train with JDK 25.0.4 or later, whose jcmd ends"
                        + " the recording. The application's output is in " + recordLog, e);
            }
            recordStop = AotCacheReport.STOP_DRIVER;
        }
        requireNonEmpty(configuration, "The recording wrote no AOT configuration", recordLog);
        long recorded = ended[0] == 0 ? stopped : ended[0];
        log.info("JDK AOT cache record: " + TimeUnit.NANOSECONDS.toMillis(recorded - started) + " ms from the"
                + " launch to the end of the recording, " + outcome.readiness().toMillis() + " ms of it to readiness");
        log.info("JDK AOT cache stop: the recording ended by " + recordStop + ", and the application was gone "
                + TimeUnit.NANOSECONDS.toMillis(stopped - recorded) + " ms later");
        return recordStop;
    }

    /**
     * Ends the recording with {@code jcmd <pid> AOT.end_recording}.
     *
     * @return {@code true} when it ended; {@code false} when the JDK does not know the command, so that the
     *         training stop ends it
     * @throws IOException if {@code jcmd} fails otherwise, or ends the recording without a configuration
     */
    private static boolean endRecording(Path jcmd, ProcessHandle application, Path dir, Path recordLog,
                                        Duration timeout, BuildLogger log) throws IOException, InterruptedException {
        List<String> command = List.of(jcmd.toString(), Long.toString(application.pid()), "AOT.end_recording");
        Forks.Result result = Forks.capture(command, dir, timeout);
        Files.writeString(recordLog, "[jcmd] " + String.join(" ", command) + "\n" + result.output()
                        + (result.output().endsWith("\n") ? "" : "\n"), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (result.exitStatus() == 0 && result.output().contains(RECORDING_ENDED)) {
            requireNonEmpty(dir.resolve(CONFIGURATION_FILE), "jcmd ended the recording, but it wrote no AOT"
                    + " configuration", recordLog);
            return true;
        }
        if (result.output().contains(UNKNOWN_COMMAND)) {
            log.info("JDK AOT cache: this JDK's jcmd does not know AOT.end_recording (JDK 25.0.0 to 25.0.3), so"
                    + " the recording ends when the application stops");
            return false;
        }
        throw new IOException(String.join(" ", command) + " exited with status " + result.exitStatus()
                + " and did not end the recording" + Forks.tail(result.output()));
    }

    private static void create(AotCacheSettings settings, Path java, Path dir, String jarName,
                               List<String> creationFlags, BuildLogger log) throws IOException, InterruptedException {
        Path createLog = dir.resolve(CREATE_LOG);
        List<String> command = new ArrayList<>();
        command.add(java.toAbsolutePath().toString());
        command.addAll(settings.jvmArgs());
        command.addAll(creationFlags);
        command.addAll(List.of("-XX:AOTMode=create", "-XX:AOTConfiguration=" + CONFIGURATION_FILE,
                "-XX:AOTCache=" + AotLaunchOptions.CACHE_FILE, "-jar", jarName));
        long started = System.nanoTime();
        int status = Forks.toFile(command, dir, createLog, CREATE_TIMEOUT);
        if (status != 0) {
            throw new IOException("Creating the JDK AOT cache failed with status " + status + ": "
                    + String.join(" ", command) + ". Its output is in " + createLog + Forks.tail(createLog));
        }
        Path cache = dir.resolve(AotLaunchOptions.CACHE_FILE);
        requireNonEmpty(cache, "Creating the JDK AOT cache wrote no cache", createLog);
        log.info("JDK AOT cache create: " + Files.size(cache) + " bytes in " + millis(started));
    }

    /**
     * The {@code jcmd} of the JDK a {@code java} executable belongs to.
     *
     * @param java the executable
     * @return the tool, or {@code null} when the JDK has none, as a JRE does not
     */
    static @Nullable Path jcmdBeside(Path java) {
        String name = WINDOWS ? "jcmd.exe" : "jcmd";
        Path bin = java.toAbsolutePath().getParent();
        if (bin != null && Files.isExecutable(bin.resolve(name))) {
            return bin.resolve(name);
        }
        try {
            Path real = java.toRealPath().getParent();
            if (real != null && Files.isExecutable(real.resolve(name))) {
                return real.resolve(name);
            }
        } catch (IOException ignored) {
            // No such file: there is no jcmd beside it either.
        }
        return null;
    }

    private static void requireNonEmpty(Path file, String message, Path log) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            throw new IOException(message + " at " + file + ". The output is in " + log + Forks.tail(log));
        }
    }

    private static String millis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms";
    }
}
