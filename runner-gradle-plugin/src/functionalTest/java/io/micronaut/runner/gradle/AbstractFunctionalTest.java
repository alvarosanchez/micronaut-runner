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
package io.micronaut.runner.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared machinery of the Gradle plugin's functional tests: it writes a fixture project, drives it
 * with Gradle TestKit and then looks inside — and runs — what the plugin produced.
 *
 * <p>The fixture is a deliberately small {@code application} project. It needs no Micronaut and no network:
 * its only dependencies are two jars this class compiles once per test JVM with the JDK's own compiler, and
 * the only repository any fixture declares is the aggregated local repository of {@code -DUMMY} publications
 * the build hands over as {@code runner.test.repo}. The plugin under test is therefore resolved exactly the
 * way a user resolves it — through a plugin marker, a POM and its transitive dependencies — rather than
 * through TestKit's injected classpath, which would hide a broken POM.</p>
 *
 * <p>Every fixture writes the same {@code gradle.properties}, so one TestKit daemon serves the whole suite.
 * Anything a single test needs to vary is passed on the command line instead.</p>
 */
abstract class AbstractFunctionalTest {

    /** The last line the fixture application prints when every check inside it held. */
    static final String RESULT_OK = "RESULT OK";

    /** The name every fixture project gives itself. */
    static final String PROJECT_NAME = "demo";

    /** The version every fixture project is built at. */
    static final String PROJECT_VERSION = "1.2.3";

    /** The path, relative to the project directory, at which the conventions place the archive. */
    static final String DEFAULT_ARCHIVE =
            "build/libs/" + PROJECT_NAME + "-" + PROJECT_VERSION + "-all.jar";

    /** The fully qualified name of the fixture application's main class. */
    static final String MAIN_CLASS = "com.example.App";

    /** The path of the task this plugin registers, as Gradle reports it. */
    static final String RUNNER_JAR_TASK = ":micronautRunnerJar";

    /** The aggregated local repository of {@code -DUMMY} publications, as a URI string. */
    private static final String REPOSITORY = requiredProperty("runner.test.repo");

    /** The {@code -DUMMY} version of the plugin under test. */
    private static final String PLUGIN_VERSION = requiredProperty("runner.test.version");

    /**
     * The settings file of every fixture. The test repository is the only plugin repository, so a fixture
     * that accidentally reached for a real plugin would fail rather than quietly download one.
     */
    private static final String SETTINGS = """
            pluginManagement {
                repositories {
                    maven { url = uri('@repository@') }
                }
                plugins {
                    id 'io.micronaut.runner' version '@version@'
                }
            }
            @extra@
            rootProject.name = '@name@'
            """;

    /**
     * The build file of every fixture. Two file dependencies are declared in an order that is not
     * alphabetical, so a test can tell resolution order apart from any incidental sorting. The archives are
     * made reproducible because the build cache test compares the byte-identical outputs of two projects
     * built in different directories.
     */
    private static final String BUILD = """
            plugins {
                id 'application'
                id 'io.micronaut.runner'
            }

            group = 'com.example'
            version = '@version@'

            application {
                mainClass = '@mainClass@'
            }

            dependencies {
                implementation files('libs/beta.jar', 'libs/alpha.jar')
            }

            tasks.withType(Jar).configureEach {
                preserveFileTimestamps = false
                reproducibleFileOrder = true
            }

            @extra@
            """;

    /** The fixture application. Everything it prints is asserted by whichever test forked it. */
    private static final String APP_SOURCE = """
            package com.example;

            import com.example.lib.Greeter;
            import com.example.lib.Shouter;

            import java.io.InputStream;
            import java.nio.charset.StandardCharsets;

            public final class App {

                public static void main(String[] args) throws Exception {
                    System.out.println("message=" + resource("/message.txt"));
                    System.out.println("alpha=" + resource("/alpha.txt"));
                    System.out.println("greeting=" + Greeter.greet("world"));
                    System.out.println("shout=" + Shouter.shout("world"));
                    System.out.println("args=" + String.join(",", args));
                    System.out.println("loader=" + App.class.getClassLoader().getClass().getName());
                    System.out.println("RESULT OK");
                }

                private static String resource(String name) throws Exception {
                    try (InputStream in = App.class.getResourceAsStream(name)) {
                        if (in == null) {
                            throw new IllegalStateException("missing resource " + name);
                        }
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                    }
                }
            }
            """;

    private static final String GREETER_SOURCE = """
            package com.example.lib;

            public final class Greeter {
                public static String greet(String name) {
                    return "hello " + name;
                }
            }
            """;

    private static final String SHOUTER_SOURCE = """
            package com.example.lib;

            public final class Shouter {
                public static String shout(String name) {
                    return "HELLO " + name.toUpperCase(java.util.Locale.ROOT);
                }
            }
            """;

    /** A fixed timestamp for the fixture dependency jars, so rebuilding one changes nothing. */
    private static final long FIXTURE_TIME = 1_000_000_000_000L;

    /** How long a forked application may take before the test gives up on it. */
    private static final Duration FORK_TIMEOUT = Duration.ofSeconds(60);

    /** Maximum time spent terminating a child or finishing its output drain. */
    private static final Duration FORK_CLEANUP_GRACE = Duration.ofSeconds(2);

    /** Maximum output retained from a runaway child; the stream is still drained after this limit. */
    private static final int MAX_FORK_OUTPUT_BYTES = 1024 * 1024;

    /** The directory holding the compiled fixture dependency jars, built once per test JVM. */
    private static Path libraries;

    /**
     * Writes a complete fixture project into an empty directory.
     *
     * @param directory the project directory
     * @return the same directory
     * @throws IOException if the fixture cannot be written
     */
    static Path writeFixture(Path directory) throws IOException {
        return writeFixture(directory, "", "");
    }

    /**
     * Writes a complete fixture project, with extra text appended to its build and settings files.
     *
     * @param directory     the project directory
     * @param buildExtra    Groovy appended to the build file, typically task configuration
     * @param settingsExtra Groovy appended to the settings file, outside the {@code pluginManagement} block
     * @return the same directory
     * @throws IOException if the fixture cannot be written
     */
    static Path writeFixture(Path directory, String buildExtra, String settingsExtra) throws IOException {
        Files.createDirectories(directory);
        write(directory.resolve("settings.gradle"), SETTINGS
                .replace("@repository@", REPOSITORY)
                .replace("@version@", PLUGIN_VERSION)
                .replace("@extra@", settingsExtra)
                .replace("@name@", PROJECT_NAME));
        write(directory.resolve("build.gradle"), BUILD
                .replace("@version@", PROJECT_VERSION)
                .replace("@mainClass@", MAIN_CLASS)
                .replace("@extra@", buildExtra));
        // Identical in every fixture: a differing value would fork a second TestKit daemon.
        write(directory.resolve("gradle.properties"), """
                org.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8
                org.gradle.configuration-cache=false
                org.gradle.caching=false
                org.gradle.parallel=false
                """);
        write(directory.resolve("src/main/java/com/example/App.java"), APP_SOURCE);
        write(directory.resolve("src/main/resources/message.txt"), "from the application layer\n");

        Path libs = directory.resolve("libs");
        Files.createDirectories(libs);
        Files.copy(libraries().resolve("alpha.jar"), libs.resolve("alpha.jar"));
        Files.copy(libraries().resolve("beta.jar"), libs.resolve("beta.jar"));
        return directory;
    }

    /**
     * A {@link GradleRunner} pointed at a fixture, with the arguments every run shares.
     *
     * @param directory the project directory
     * @param arguments the task names and command line options
     * @return the runner
     */
    static GradleRunner runner(Path directory, String... arguments) {
        List<String> all = new ArrayList<>(List.of(arguments));
        all.add("--stacktrace");
        return GradleRunner.create()
                .withProjectDir(directory.toFile())
                .withArguments(all);
    }

    /**
     * Runs a fixture build that is expected to succeed.
     *
     * @param directory the project directory
     * @param arguments the task names and command line options
     * @return the build result
     */
    static BuildResult build(Path directory, String... arguments) {
        return runner(directory, arguments).build();
    }

    /**
     * Runs a fixture build that is expected to fail.
     *
     * @param directory the project directory
     * @param arguments the task names and command line options
     * @return the build result
     */
    static BuildResult buildAndFail(Path directory, String... arguments) {
        return runner(directory, arguments).buildAndFail();
    }

    /**
     * The outcome of a task in a build, failing the test with the whole build output when the task was
     * never part of it.
     *
     * @param result the build result
     * @param path   the task path
     * @return the outcome
     */
    static TaskOutcome outcomeOf(BuildResult result, String path) {
        BuildTask task = result.task(path);
        assertNotNull(task, () -> path + " was not part of the build:\n" + result.getOutput());
        return task.getOutcome();
    }

    /**
     * Starts an archive with {@code java -jar} on the JDK that runs the build, and waits for it to finish.
     *
     * <p>A task that writes a file nobody ever runs is not tested, so every packaging test ends here.</p>
     *
     * @param archive   the archive to run
     * @param arguments the application arguments
     * @return the exit status and the combined output
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the wait is interrupted
     */
    static Forked runJar(Path archive, String... arguments) throws IOException, InterruptedException {
        return runJar(archive, List.of(), FORK_TIMEOUT, arguments);
    }

    /**
     * Runs an archive in one of the launcher's tool modes.
     *
     * @param archive   the archive to run
     * @param mode      the launcher mode
     * @param arguments the tool arguments
     * @return the exit status and the combined output
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the wait is interrupted
     */
    static Forked runJarInMode(Path archive, String mode, String... arguments)
            throws IOException, InterruptedException {
        return runJar(archive, List.of("-Dmicronaut.runner.mode=" + mode), FORK_TIMEOUT, arguments);
    }

    /**
     * Runs an archive with a test-specific deadline.
     *
     * @param archive   the archive to run
     * @param timeout   how long the child may run before cleanup starts
     * @param arguments the application arguments
     * @return the exit status and combined output
     * @throws IOException          if the process fails or exceeds its deadline
     * @throws InterruptedException if the wait is interrupted, after the child is reaped
     */
    static Forked runJar(Path archive, Duration timeout, String... arguments)
            throws IOException, InterruptedException {
        return runJar(archive, List.of(), timeout, arguments);
    }

    private static Forked runJar(Path archive, List<String> jvmArguments, Duration timeout, String... arguments)
            throws IOException, InterruptedException {
        assertTrue(Files.isRegularFile(archive), () -> archive + " was never written");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.addAll(jvmArguments);
        command.add("-jar");
        command.add(archive.toAbsolutePath().toString());
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(archive.toAbsolutePath().getParent().toFile())
                .redirectErrorStream(true);
        for (String variable : List.of("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(variable);
        }
        Process process = builder.start();
        OutputCapture capture = new OutputCapture(process.getInputStream());
        Thread drain = new Thread(capture, "forked-application-output-" + process.pid());
        drain.setDaemon(true);
        drain.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            IOException cleanupFailure = cleanup(process, drain);
            if (cleanupFailure != null) {
                e.addSuppressed(cleanupFailure);
            }
            Thread.currentThread().interrupt();
            throw e;
        }
        if (!finished) {
            IOException cleanupFailure = cleanup(process, drain);
            IOException failure = new IOException("The forked application did not finish within " + timeout
                    + describe(command, capture.output()));
            if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }

        try {
            drain.join(FORK_CLEANUP_GRACE.toMillis());
        } catch (InterruptedException e) {
            IOException cleanupFailure = cleanup(process, drain);
            if (cleanupFailure != null) {
                e.addSuppressed(cleanupFailure);
            }
            Thread.currentThread().interrupt();
            throw e;
        }
        if (drain.isAlive()) {
            IOException cleanupFailure = cleanup(process, drain);
            IOException failure = new IOException("The forked application's output did not finish within "
                    + FORK_CLEANUP_GRACE + describe(command, capture.output()));
            if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        if (capture.failure() != null) {
            throw new IOException("Could not capture the forked application's output"
                    + describe(command, capture.output()), capture.failure());
        }
        return new Forked(process.exitValue(), capture.output());
    }

    private static IOException cleanup(Process process, Thread drain) {
        boolean interrupted = false;
        List<String> failures = new ArrayList<>();
        process.destroy();
        try {
            if (!process.waitFor(FORK_CLEANUP_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            interrupted = true;
            process.destroyForcibly();
        }
        if (process.isAlive()) {
            try {
                if (!process.waitFor(FORK_CLEANUP_GRACE.toMillis(), TimeUnit.MILLISECONDS)
                        && process.isAlive()) {
                    failures.add("child " + process.pid() + " survived forcible termination");
                }
            } catch (InterruptedException e) {
                interrupted = true;
                if (process.isAlive()) {
                    failures.add("child " + process.pid() + " was not reaped before cleanup was interrupted");
                }
            }
        }
        try {
            process.getInputStream().close();
        } catch (IOException e) {
            failures.add("could not close the child output stream: " + e);
        }
        try {
            drain.join(FORK_CLEANUP_GRACE.toMillis());
        } catch (InterruptedException e) {
            interrupted = true;
        }
        if (drain.isAlive()) {
            drain.interrupt();
            try {
                drain.join(FORK_CLEANUP_GRACE.toMillis());
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (drain.isAlive()) {
                failures.add("output drain " + drain.getName() + " did not stop");
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return failures.isEmpty() ? null : new IOException("Cleanup incomplete: " + String.join("; ", failures));
    }

    private static String describe(List<String> command, String output) {
        return "\n--- command ---\n" + String.join(" ", command)
                + "\n--- output ---\n" + (output.isEmpty() ? "(nothing)" : output) + "\n--------------";
    }

    /**
     * Runs an archive and asserts that it exited cleanly having printed {@link #RESULT_OK}.
     *
     * @param archive   the archive to run
     * @param arguments the application arguments
     * @return the combined output, for further assertions
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if the wait is interrupted
     */
    static String runJarSuccessfully(Path archive, String... arguments)
            throws IOException, InterruptedException {
        Forked run = runJar(archive, arguments);
        assertEquals(0, run.status(), () -> "the application exited with " + run.status() + "\n" + run.output());
        assertTrue(run.output().contains(RESULT_OK),
                () -> "the application did not run to completion:\n" + run.output());
        return run.output();
    }

    /**
     * The entry names of an archive, in the order the archive stores them.
     *
     * @param archive the archive
     * @return the entry names
     * @throws IOException if the archive cannot be read
     */
    static List<String> entryNames(Path archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        }
        return names;
    }

    /**
     * The main manifest attributes of an archive.
     *
     * @param archive the archive
     * @return the attributes
     * @throws IOException if the archive cannot be read
     */
    static Attributes manifestOf(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile());
             InputStream in = zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF"))) {
            return new Manifest(in).getMainAttributes();
        }
    }

    /**
     * The compression method of every entry of a jar nested inside an archive, keyed by entry name.
     *
     * <p>This is what the {@code compression} property decides: the entries of the outer archive are always
     * stored, so only the entries of the nested dependencies tell {@code STORED} and {@code PRESERVE}
     * apart.</p>
     *
     * @param archive   the outer archive
     * @param entryName the name of the nested jar
     * @return the compression method of each nested entry, one of {@link ZipEntry#STORED} or
     *         {@link ZipEntry#DEFLATED}
     * @throws IOException if the archive cannot be read
     */
    static Map<String, Integer> nestedEntryMethods(Path archive, String entryName) throws IOException {
        Map<String, Integer> methods = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry nested = zip.getEntry(entryName);
            assertNotNull(nested, () -> entryName + " is not in " + archive);
            try (ZipInputStream in = new ZipInputStream(zip.getInputStream(nested))) {
                ZipEntry entry = in.getNextEntry();
                while (entry != null) {
                    methods.put(entry.getName(), entry.getMethod());
                    entry = in.getNextEntry();
                }
            }
        }
        return methods;
    }

    /**
     * Writes a file, creating its parent directories.
     *
     * @param file    the file
     * @param content the content
     * @throws IOException if the file cannot be written
     */
    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * Reads a system property the build is responsible for setting.
     *
     * @param name the property name
     * @return its value
     */
    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("The system property " + name + " is not set. These tests must"
                    + " be run through the 'functionalTest' task, which publishes the plugin into a local"
                    + " repository and points the fixtures at it.");
        }
        return value;
    }

    /**
     * The {@code java} of the JDK that runs the build, which is the JDK a forked application must use.
     *
     * @return the executable
     */
    private static Path javaExecutable() {
        String home = System.getProperty("runner.test.javaHome", System.getProperty("java.home"));
        Path candidate = Path.of(home, "bin", "java");
        if (!Files.isExecutable(candidate)) {
            candidate = Path.of(home, "bin", "java.exe");
        }
        assertTrue(Files.isExecutable(candidate), () -> "no java executable under " + home);
        return candidate;
    }

    /**
     * Compiles the two fixture dependency jars, once per test JVM.
     *
     * <p>Their entries are deflated, which is what lets a test tell {@code PRESERVE} from {@code STORED}.</p>
     *
     * @return the directory holding them
     * @throws IOException if they cannot be built
     */
    private static synchronized Path libraries() throws IOException {
        if (libraries != null) {
            return libraries;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "this JDK has no java compiler");

        Path root = Files.createTempDirectory("runner-plugin-fixture-libs");
        root.toFile().deleteOnExit();
        Path classes = root.resolve("classes");
        compile(compiler, root.resolve("sources"), classes, Map.of(
                "com/example/lib/Greeter.java", GREETER_SOURCE,
                "com/example/lib/Shouter.java", SHOUTER_SOURCE));

        writeJar(root.resolve("alpha.jar"), Map.of(
                "com/example/lib/Greeter.class",
                Files.readAllBytes(classes.resolve("com/example/lib/Greeter.class")),
                "alpha.txt", "from the alpha dependency\n".getBytes(StandardCharsets.UTF_8)));
        writeJar(root.resolve("beta.jar"), Map.of(
                "com/example/lib/Shouter.class",
                Files.readAllBytes(classes.resolve("com/example/lib/Shouter.class"))));
        libraries = root;
        return libraries;
    }

    private static void compile(JavaCompiler compiler, Path sources, Path classes, Map<String, String> files)
            throws IOException {
        Files.createDirectories(classes);
        List<String> arguments = new ArrayList<>(List.of("--release", "25", "-d", classes.toString()));
        for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
            Path source = sources.resolve(file.getKey());
            write(source, file.getValue());
            arguments.add(source.toString());
        }
        if (compiler.run(null, null, null, arguments.toArray(new String[0])) != 0) {
            throw new IOException("Could not compile the fixture dependency sources under " + sources);
        }
    }

    private static void writeJar(Path file, Map<String, byte[]> entries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            for (Map.Entry<String, byte[]> entry : new TreeMap<>(entries).entrySet()) {
                ZipEntry record = new ZipEntry(entry.getKey());
                record.setTime(FIXTURE_TIME);
                record.setMethod(ZipEntry.DEFLATED);
                out.putNextEntry(record);
                out.write(entry.getValue());
                out.closeEntry();
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static final class OutputCapture implements Runnable {

        private final InputStream input;
        private final byte[] output = new byte[MAX_FORK_OUTPUT_BYTES];
        private int retained;
        private long received;
        private volatile IOException failure;

        private OutputCapture(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (input) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    append(buffer, read);
                }
            } catch (IOException e) {
                failure = e;
            }
        }

        private synchronized void append(byte[] bytes, int length) {
            received += length;
            int copied = Math.min(length, output.length - retained);
            System.arraycopy(bytes, 0, output, retained, copied);
            retained += copied;
        }

        private synchronized String output() {
            String captured = new String(output, 0, retained, StandardCharsets.UTF_8);
            if (received > retained) {
                return captured + "\n[output truncated after " + retained + " of " + received + " bytes]";
            }
            return captured;
        }

        private IOException failure() {
            return failure;
        }
    }

    /**
     * The result of running an archive with {@code java -jar}.
     *
     * @param status the exit status
     * @param output the combined standard output and standard error
     */
    record Forked(int status, String output) {
    }
}
