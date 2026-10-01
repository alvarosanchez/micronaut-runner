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

import io.micronaut.core.annotation.Experimental;
import io.micronaut.runner.RunnerClassLoader;
import io.micronaut.runner.build.training.TrainingDriver;
import io.micronaut.runner.build.training.TrainingSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Records the startup profile of an application: the classes a run of its runner jar loaded, in load order,
 * written to a file that is meant to be committed and that the packager embeds for the launcher to preload.
 *
 * <p>One recording is one training run of the archive, as {@link TrainingSettings} describe it, with
 * class-load logging on: the archive is launched once with {@code java -jar}, sent the workload and stopped,
 * and every process the run started is gone when it ends. It gets right what a recipe run by hand gets wrong
 * without any sign of it:</p>
 * <ul>
 *     <li>the log is read once the workload is done and <em>before</em> the application is stopped, so the
 *     classes that only a shutdown loads stay out, and the list does not depend on how the JVM is stopped;</li>
 *     <li>the classes are recorded in an order that two recordings agree on: the launch sets the common
 *     ForkJoin pool's parallelism to zero, so Micronaut's parallel service loading runs on one thread;</li>
 *     <li>no JVM option of the machine the build runs on takes part, so no class cache hides the
 *     application's classes;</li>
 *     <li>an archive that already embeds a profile does not replay it into the new one.</li>
 * </ul>
 *
 * <p>The profile names no build tool: the caller passes the command that records it again, which goes into
 * the header.</p>
 *
 * <h2>Compatibility</h2>
 *
 * <p>This type is stable API for all of 1.x. An input added in a minor release arrives as an overload of
 * {@link #record record} that takes it, and the existing method keeps its signature and passes the input's
 * default. {@code record} returns the number of archive classes; other figures go to the {@link BuildLogger},
 * and a minor release that has to return more adds a method rather than change this one's result.</p>
 *
 * @since 1.0
 */
@Experimental
public final class StartupProfileRecorder {

    /**
     * Where a project keeps its startup profile, relative to the project directory: a build plugin embeds the
     * file at this location when the build sets no other. Projects commit the file there, so the location is
     * fixed for all of 1.x.
     */
    public static final String PROFILE_LOCATION = "src/main/micronaut-runner/startup-classes.txt";

    /**
     * The JVM flag that gives a recording a repeatable order: with a common pool of parallelism zero,
     * Micronaut's parallel service loading runs on the calling thread.
     *
     * <p>Measured for #150 on benchmark-large: two default recordings list the same 3,314 classes with only
     * about 74% of them in the same relative order, and with this flag about 98%, so a committed profile does
     * not churn. The flag costs three classes that only the ForkJoin path loads, which are then loaded on
     * demand. It was chosen by #133's acceptance run (PR #207, 100 pairs on benchmark-large, JDK 25.0.4.1):
     * the archive with the list recorded with the flag was not slower than the one with the default-order
     * list, +1.8 ms (95% CI -2.4 to +4.5), and the rule was to prefer the flag unless its archive was slower
     * with an interval that excludes 0.</p>
     */
    static final String ORDERING_FLAG = "-Djava.util.concurrent.ForkJoinPool.common.parallelism=0";

    /**
     * The file a recording leaves in its work directory. The recorder empties only a directory that is new,
     * empty or carries it, so a work directory pointed at a directory with other content is refused rather
     * than wiped.
     */
    static final String WORK_DIRECTORY_MARKER = ".micronaut-runner-recording";

    /** The launcher's switch: an archive that embeds an older profile must not replay it into the new one. */
    private static final String NO_PRELOAD_FLAG = "-Dmicronaut.runner.preload=false";

    /** The JVM's class-load log, relative to the working directory of the launch. */
    private static final String CLASS_LOAD_LOG = "class-load.log";

    /** The copy of that log taken before the application is stopped, which is what the profile is made of. */
    private static final String CLASS_LOAD_SNAPSHOT = "class-load.snapshot.log";

    /** The application's output. */
    private static final String APPLICATION_LOG = "application.log";

    /**
     * The file name is relative, which keeps the colon of a Windows drive letter out of the option's
     * colon-separated syntax, and {@code filecount=0} turns rotation off.
     */
    private static final String CLASS_LOAD_FLAG = "-Xlog:class+load=info:file=" + CLASS_LOAD_LOG + "::filecount=0";

    /** How long the class-load log has to stay the same size to count as complete. */
    private static final long QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    /** How long to wait for that at most. */
    private static final long QUIET_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);

    private StartupProfileRecorder() {
    }

    /**
     * Launches a runner jar, records the classes it loads up to the end of the training workload and writes
     * them to the profile, replacing it.
     *
     * @param java            the {@code java} executable to record with
     * @param runnerJar       the runner jar to record from, built with the packaging options production uses
     * @param settings        how to reach, exercise and stop the application; its
     *                        {@link TrainingSettings#jvmArgs()} must name no class cache
     * @param workDirectory   a directory of the build's own, which is emptied and becomes the working
     *                        directory of the launch; the logs of the recording stay in it. It must be new,
     *                        empty or left by an earlier recording, and must hold neither the runner jar nor
     *                        the profile
     * @param profile         the profile to write
     * @param rerecordCommand the command that records the profile again in the caller's build, for the header:
     *                        one non-blank line
     * @param log             where progress is reported
     * @return the number of archive classes recorded
     * @throws IOException              if {@code runnerJar} is not a runner jar, the launch fails, the
     *                                  recording holds no class of the archive, or the profile cannot be
     *                                  written
     * @throws InterruptedException     if the thread is interrupted; the application has been reaped by then
     * @throws NullPointerException     if an argument is {@code null}; the message is the name of the
     *                                  parameter, and nothing has been launched or written
     * @throws IllegalArgumentException if {@code rerecordCommand} is blank or has a line break, the profile or
     *                                  the runner jar lies inside the work directory, or the work directory
     *                                  holds files that no earlier recording left there
     */
    public static int record(Path java,
                             Path runnerJar,
                             TrainingSettings settings,
                             Path workDirectory,
                             Path profile,
                             String rerecordCommand,
                             BuildLogger log) throws IOException, InterruptedException {
        Objects.requireNonNull(java, "java");
        Objects.requireNonNull(runnerJar, "runnerJar");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(workDirectory, "workDirectory");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(rerecordCommand, "rerecordCommand");
        Objects.requireNonNull(log, "log");
        requireCommand(rerecordCommand);
        Path directory = workDirectory.toAbsolutePath().normalize();
        Path target = profile.toAbsolutePath().normalize();
        if (target.startsWith(directory)) {
            throw new IllegalArgumentException("The startup profile " + target + " must not be inside the work"
                    + " directory " + directory + ", which is emptied");
        }
        Path archive = runnerJar.toAbsolutePath().normalize();
        if (archive.startsWith(directory)) {
            throw new IllegalArgumentException("The runner jar " + archive + " must not be inside the work"
                    + " directory " + directory + ", which is emptied");
        }
        if (!Files.isRegularFile(runnerJar) || !RunnerJarReader.isRunnerJar(runnerJar)) {
            throw new IOException(runnerJar + " is not a runner jar, so there is nothing to record a startup"
                    + " profile from");
        }
        requireOwnWorkDirectory(directory);

        long started = System.nanoTime();
        empty(directory);
        Files.writeString(directory.resolve(WORK_DIRECTORY_MARKER), "The working directory of a Micronaut"
                + " Runner startup-profile recording. Every recording empties it.\n", StandardCharsets.UTF_8);
        Path classLoadLog = directory.resolve(CLASS_LOAD_LOG);
        Path snapshot = directory.resolve(CLASS_LOAD_SNAPSHOT);
        TrainingDriver.Outcome outcome = TrainingDriver.run(java, runnerJar,
                List.of(NO_PRELOAD_FLAG, ORDERING_FLAG, CLASS_LOAD_FLAG), settings, directory,
                directory.resolve(APPLICATION_LOG), application -> {
                    if (application.isAlive()) {
                        awaitQuiet(classLoadLog);
                    }
                    snapshot(classLoadLog, snapshot);
                }, log);
        // The list was taken before the stop, so it does not depend on how the application ended.
        log.info("The recording launch exited with status " + outcome.exitStatus()
                + (outcome.forced() ? " after it was killed" : ""));

        StartupClassList recorded = StartupClassList.read(snapshot);
        String content = render(recorded, rerecordCommand, jdkVersion(java), settings);
        String changes = changes(target, recorded);
        write(target, content);
        log.info("Recorded " + recorded.classes().size() + " startup classes and " + recorded.jdkClasses().size()
                + " JDK classes in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms"
                + changes);
        return recorded.classes().size();
    }

    /**
     * The content of a profile: the header, the archive's classes in load order, then the JDK classes the
     * same run loaded from the runtime image, each marked {@code jrt:}.
     *
     * @param recorded        the classes of the recording
     * @param rerecordCommand the command that records the profile again
     * @param jdkVersion      the version of the JDK that ran the recording
     * @param settings        the settings of the recording
     * @return the content, with {@code \n} line endings
     * @throws IOException if the recording holds no class of the archive
     */
    static String render(StartupClassList recorded, String rerecordCommand, String jdkVersion,
                         TrainingSettings settings) throws IOException {
        requireCommand(rerecordCommand);
        if (recorded.classes().isEmpty()) {
            throw new IOException("The recording holds no class that RunnerClassLoader defined, so there is"
                    + " nothing to preload. The likely cause is a class cache: a class served from a CDS or AOT"
                    + " cache is not logged as a class of the archive. Remove the cache flags, such as"
                    + " -XX:AOTCache and -XX:SharedArchiveFile, from the training jvmArgs. The other cause is the"
                    + " AOT-cache training property -D" + RunnerClassLoader.AOT_TRAINING_PROPERTY + "=true, which"
                    + " makes every class report the archive's file: URL instead; remove it too.");
        }
        StringBuilder content = new StringBuilder(64 * (recorded.classes().size() + recorded.jdkClasses().size()));
        content.append("# Micronaut Runner startup profile: the classes RunnerClassLoader defined before the"
                        + " training workload\n")
                .append("# finished, in load order, then as jrt: lines the JDK classes the same run loaded from"
                        + " the runtime image.\n")
                .append("# Commit this file. Re-record it after upgrading dependencies, the JDK or the packaging"
                        + " options.\n")
                .append("# re-record: ").append(rerecordCommand).append('\n')
                .append("# jdk: ").append(jdkVersion).append('\n')
                .append("# jvm: ").append(jvmLine(settings)).append('\n')
                .append("# workload: ").append(workloadLine(settings)).append('\n')
                .append("# classes: ").append(recorded.classes().size()).append('\n')
                .append("# jdk-classes: ").append(recorded.jdkClasses().size()).append('\n');
        for (String name : recorded.classes()) {
            content.append(name).append('\n');
        }
        for (String name : recorded.jdkClasses()) {
            content.append("jrt:").append(name).append('\n');
        }
        return content.toString();
    }

    /**
     * The version of the JDK a {@code java} executable belongs to: {@code JAVA_RUNTIME_VERSION} of the JDK's
     * {@code release} file.
     *
     * @param java the executable
     * @return the version, or {@code unknown} when the JDK has no such file or the file no such line
     */
    static String jdkVersion(Path java) {
        try {
            Path bin = java.toRealPath().getParent();
            Path home = bin == null ? null : bin.getParent();
            if (home != null) {
                for (String line : Files.readAllLines(home.resolve("release"), StandardCharsets.UTF_8)) {
                    if (line.startsWith("JAVA_RUNTIME_VERSION=")) {
                        String version = line.substring(line.indexOf('=') + 1).trim().replace("\"", "");
                        return version.isEmpty() ? "unknown" : version;
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A JDK without a readable release file is recorded as unknown.
        }
        return "unknown";
    }

    private static void requireCommand(String rerecordCommand) {
        Objects.requireNonNull(rerecordCommand, "rerecordCommand");
        if (rerecordCommand.isBlank() || rerecordCommand.indexOf('\n') >= 0 || rerecordCommand.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("rerecordCommand, the command that re-records the startup profile,"
                    + " must be one non-blank line, not '" + rerecordCommand + "'");
        }
    }

    private static String jvmLine(TrainingSettings settings) {
        List<String> arguments = new ArrayList<>();
        arguments.add(ORDERING_FLAG);
        arguments.addAll(settings.jvmArgs());
        return String.join(" ", arguments);
    }

    private static String workloadLine(TrainingSettings settings) {
        if (settings.runToExit()) {
            return "none, the application ran to its exit";
        }
        boolean command = !settings.workloadCommand().isEmpty();
        if (settings.workloadPaths().isEmpty()) {
            return command ? "command" : "GET / x1";
        }
        StringBuilder line = new StringBuilder();
        for (String path : settings.workloadPaths()) {
            line.append(line.isEmpty() ? "" : ", ").append("GET ").append(path);
        }
        line.append(" x").append(settings.workloadRepeat());
        return command ? line.append("; command").toString() : line.toString();
    }

    /**
     * Waits until the class-load log has stopped growing, so that the classes of the last request are in it.
     */
    private static void awaitQuiet(Path classLoadLog) throws InterruptedException {
        long deadline = System.nanoTime() + QUIET_TIMEOUT_NANOS;
        long size = size(classLoadLog);
        long since = System.nanoTime();
        while (System.nanoTime() < deadline) {
            Thread.sleep(25);
            long now = size(classLoadLog);
            if (now != size) {
                size = now;
                since = System.nanoTime();
            } else if (System.nanoTime() - since >= QUIET_NANOS) {
                return;
            }
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Copies the whole lines of the class-load log. The JVM may be writing a line at that moment, and a line
     * without its end would be read as a class name.
     */
    private static void snapshot(Path classLoadLog, Path snapshot) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(classLoadLog);
        } catch (NoSuchFileException e) {
            throw new IOException("The recording launch wrote no class-load log at " + classLoadLog
                    + ". It is what -Xlog:class+load writes, which needs a HotSpot JVM.", e);
        }
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] != '\n') {
            end--;
        }
        Files.write(snapshot, Arrays.copyOf(bytes, end));
    }

    /**
     * Says how the new profile differs from the one it replaces.
     *
     * @return a clause for the log line, empty when there was no profile or it cannot be read
     */
    private static String changes(Path profile, StartupClassList recorded) {
        if (!Files.isRegularFile(profile)) {
            return "";
        }
        Set<String> previous;
        try {
            previous = new HashSet<>(StartupClassList.read(profile).classes());
        } catch (IOException e) {
            return "";
        }
        Set<String> current = new HashSet<>(recorded.classes());
        long added = current.stream().filter(name -> !previous.contains(name)).count();
        long removed = previous.stream().filter(name -> !current.contains(name)).count();
        return "; " + added + " added and " + removed + " removed since the previous profile";
    }

    /**
     * Writes the profile through a temporary file in the same directory, so that a build that reads it never
     * sees half of it.
     */
    private static void write(Path profile, String content) throws IOException {
        Path parent = profile.getParent();
        if (parent == null) {
            throw new IOException("The startup profile " + profile + " has no directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".startup-classes-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, profile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | UnsupportedOperationException e) {
                // No atomic move on this file system, or none onto an existing file.
                Files.move(temporary, profile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Refuses a work directory that holds anything an earlier recording did not leave: emptying it could
     * delete build outputs or sources that a mistyped setting pointed at.
     */
    private static void requireOwnWorkDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)
                || Files.isRegularFile(directory.resolve(WORK_DIRECTORY_MARKER), LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        boolean empty;
        try (Stream<Path> entries = Files.list(directory)) {
            empty = entries.findAny().isEmpty();
        }
        if (!empty) {
            throw new IllegalArgumentException("The work directory " + directory + " is not empty and no"
                    + " earlier startup-profile recording left it. A recording empties its work directory, so it"
                    + " takes only a new or empty directory, or one a recording left. Point the work directory"
                    + " at such a directory, or delete this one if nothing in it is needed.");
        }
    }

    /** Creates the directory, or deletes everything in it. Links are removed, never followed. */
    private static void empty(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path visited, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    if (!visited.equals(directory)) {
                        Files.delete(visited);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        Files.createDirectories(directory);
    }
}
