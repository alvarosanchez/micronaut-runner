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
package io.micronaut.runner.suite;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the {@code hello-netty} sample the way a user would: the real Gradle plugin, resolved by id and
 * version from a repository, packaging a real Micronaut HTTP application, and then {@code java -jar}.
 *
 * <p>The assertion that matters is the last one: a request to the running server answers, and the greeting
 * it answers with names {@code RunnerClassLoader}. That single string proves the archive started, that the
 * launcher installed its own class loader, that bean discovery found a bean inside a nested jar, and that
 * Netty bound a port - none of which a build that merely produced a file would prove.</p>
 *
 * <p>The same launch logs its class loads, and the test pins which launcher classes load before the entry
 * stub, so that a class that drifts onto the pre-{@code main} path fails here rather than going unnoticed.</p>
 *
 * <p>The sample is built in place rather than in a temporary directory because its {@code settings.gradle}
 * reads the version catalog by a relative path. Its outputs are declared as build outputs of the sample,
 * not of this test.</p>
 */
@Timeout(value = 30, unit = TimeUnit.MINUTES)
class HelloNettySampleTest {

    /** The task the Gradle plugin registers. */
    private static final String TASK = ":micronautRunnerJar";

    /** Where that task writes, with the classifier the plugin defaults to. */
    private static final String ARCHIVE = "build/libs/hello-netty-0.1-all.jar";

    /** How long the server is given to come up. Generous: a cold JIT on a loaded CI agent is slow. */
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);

    /**
     * Where the launch logs its class loads, relative to the sample, which is the child's working directory.
     * It contains no colon and no space, so {@code -Xlog} parses it as a plain file name.
     */
    private static final String CLASS_LOAD_LOG = "build/runner-classload.log";

    /** What precedes the class name on a {@code -Xlog:class+load} line. */
    private static final String CLASS_LOAD_TAG = "[class,load] ";

    /** The package of the generated entry stub, whose first class marks the end of the launcher's work. */
    private static final String GENERATED_PACKAGE = "io.micronaut.runner.generated.";

    /** The application layer of a runner jar. */
    private static final String CLASSES = "MICRONAUT-INF/classes/";

    /** The configurator runner-build compiles logback.xml into. */
    private static final String LOGBACK_CONFIGURATOR = "io/micronaut/runner/generated/logback/LogbackConfigurator.class";

    /** Loaded whenever Logback reads an XML file with Joran. */
    private static final String JORAN_CONFIGURATOR = "ch.qos.logback.classic.joran.JoranConfigurator";

    /**
     * The launcher classes that load before the entry stub in a default {@code java -jar} start.
     *
     * <p>Every class on this list is read, parsed and verified on every launch without a cache, so a new
     * entry is a startup cost. Keep a new class off the link path, for example behind a static factory
     * declared to return its supertype, so that verifying its caller does not load it; or justify it here,
     * next to the list.</p>
     */
    private static final Set<String> PRE_MAIN_LAUNCHER_CLASSES = Set.of(
            "io.micronaut.runner.Launcher",
            "io.micronaut.runner.RunnerClassLoader",
            "io.micronaut.runner.Entry",
            "io.micronaut.runner.ArchiveSource",
            // Every resource stream uses these two, so deferring them would save nothing.
            "io.micronaut.runner.ArchiveSource$RegionInputStream",
            "io.micronaut.runner.ArchiveSource$EntryInputStream",
            "io.micronaut.runner.Index",
            "io.micronaut.runner.IndexFormat",
            "io.micronaut.runner.Handlers",
            // The handler has to exist before main to build CodeSource URLs. Verifying it loads, without
            // linking, the connection its openConnection returns.
            "io.micronaut.runner.protocol.jar.Handler",
            "io.micronaut.runner.protocol.jar.RunnerJarURLConnection");

    @BeforeAll
    static void assumeTheSuiteCanRun() {
        Samples.assumeTheBuildProvidedItsProperties();
        Samples.requireIntegrationScenario();
        Samples.requirePublishedArtifact("io/micronaut/runner/standalone/"
                + "io.micronaut.runner.standalone.gradle.plugin/" + Samples.VERSION
                + "/io.micronaut.runner.standalone.gradle.plugin-" + Samples.VERSION + ".pom");
    }

    @Test
    void packagesTheSampleAndServesARequestFromTheRunnerJar() throws Exception {
        Path sample = Samples.sample("hello-netty");
        Path archive = sample.resolve(ARCHIVE);
        Files.deleteIfExists(archive);

        BuildResult result = gradle(sample, "clean", TASK);

        assertEquals(TaskOutcome.SUCCESS, result.task(TASK).getOutcome(),
                () -> "the packaging task did not run:\n" + result.getOutput());
        assertTrue(Files.isRegularFile(archive),
                () -> "the plugin did not write " + archive + ":\n" + result.getOutput());
        Path unicodeArchive = sample.resolve("build/unicode-é/apps with a space/app.jar");
        Files.createDirectories(unicodeArchive.getParent());
        Files.copy(archive, unicodeArchive, StandardCopyOption.REPLACE_EXISTING);
        assertStartsTheApplication(unicodeArchive);

        Path classLoadLog = sample.resolve(CLASS_LOAD_LOG);
        Files.deleteIfExists(classLoadLog);
        int port = Samples.freePort();
        ForkedApplication application = ForkedApplication.start(unicodeArchive, sample, Map.of(
                "SERVER_PORT", Integer.toString(port)),
                List.of("-Xlog:class+load=info:file=" + CLASS_LOAD_LOG));
        try {
            String body = application.awaitBody(
                    URI.create("http://localhost:" + port + "/hello"), STARTUP_TIMEOUT);
            assertEquals("hello from RunnerClassLoader", body,
                    () -> "the application answered, but not from the runner class loader"
                            + application.describe());
        } finally {
            application.close();
        }
        assertPreMainClasses(classLoadLog);
    }

    /**
     * Packages the sample twice, with the default and with {@code precompileLogback=false} set through an init
     * script, and holds the precompiled Logback configuration to Joran's behaviour: the same standard output in
     * every way a user steers logging, the runtime opt-out handing over to Joran, and the extracted layout keeping
     * the configurator.
     */
    @Test
    void thePrecompiledLogbackConfigurationBehavesLikeJoran(@TempDir Path work) throws Exception {
        Path sample = Samples.sample("hello-netty");
        Path archive = sample.resolve(ARCHIVE);
        gradle(sample, "clean", TASK);
        Path precompiled = copy(archive, work.resolve("precompiled/hello-netty.jar"));
        Path init = work.resolve("no-precompile.init.gradle");
        Files.writeString(init, "allprojects { tasks.matching { it.name == 'micronautRunnerJar' }.configureEach {"
                + " it.options.put('precompileLogback', 'false') } }\n", StandardCharsets.UTF_8);
        gradle(sample, "clean", TASK, "--init-script", init.toString());
        Path joran = copy(archive, work.resolve("joran/hello-netty.jar"));

        assertTrue(hasEntry(precompiled, CLASSES + LOGBACK_CONFIGURATOR), () -> precompiled + " has no configurator");
        assertFalse(hasEntry(joran, CLASSES + LOGBACK_CONFIGURATOR), () -> joran + " has a configurator");

        // The fast path verifies, and neither links Joran nor loads its fallback.
        Path classLoads = sample.resolve("build/logback-precompiled-classload.log");
        Run fast = run(precompiled, sample, Map.of(), List.of("-Xverify:all",
                "-Xlog:class+load=info:file=build/logback-precompiled-classload.log"));
        assertEquals("hello from RunnerClassLoader", fast.body(), fast.output());
        String loaded = Files.readString(classLoads, StandardCharsets.ISO_8859_1);
        assertTrue(loaded.contains(" io.micronaut.runner.generated.logback.LogbackConfigurator "), fast.output());
        assertFalse(loaded.contains(" " + JORAN_CONFIGURATOR + " "), "the fast path loaded Joran");
        assertFalse(loaded.contains(" io.micronaut.runner.generated.logback.JoranFallback "),
                "the fast path loaded JoranFallback");
        assertEquals("hello from RunnerClassLoader", run(joran, sample, Map.of(), List.of()).body());

        Path configurationFile = work.resolve("configuration-file.xml");
        Path loggerConfig = work.resolve("logger-config.xml");
        writeLogbackXml(configurationFile, "CONFIGURATION-FILE");
        writeLogbackXml(loggerConfig, "LOGGER-CONFIG");
        record Scenario(String name, Map<String, String> environment, List<String> jvmArguments, String marker) {
        }
        List<Scenario> scenarios = List.of(
                new Scenario("plain start", Map.of(), List.of(), "Startup completed"),
                new Scenario("a start that fails", Map.of("SERVER_PORT", "notaport"), List.of(), "notaport"),
                new Scenario("-Dlogback.configurationFile", Map.of(),
                        List.of("-Dlogback.configurationFile=" + configurationFile), "CONFIGURATION-FILE INFO"),
                new Scenario("-Dlogger.config", Map.of(), List.of("-Dlogger.config=" + loggerConfig),
                        "LOGGER-CONFIG INFO"),
                new Scenario("LOGGER_CONFIG", Map.of("LOGGER_CONFIG", loggerConfig.toString()), List.of(),
                        "LOGGER-CONFIG INFO"),
                new Scenario("-Dlogger.levels", Map.of(), List.of("-Dlogger.levels.com.example=DEBUG"),
                        "Setting log level 'DEBUG'"),
                new Scenario("the opt-out with -Dlogger.config", Map.of(),
                        List.of("-Dmicronaut.runner.logback.precompiled=false", "-Dlogger.config=" + loggerConfig),
                        "LOGGER-CONFIG INFO"));
        for (Scenario scenario : scenarios) {
            Run expected = run(joran, sample, scenario.environment(), scenario.jvmArguments());
            Run actual = run(precompiled, sample, scenario.environment(), scenario.jvmArguments());
            assertTrue(expected.output().contains(scenario.marker()),
                    () -> scenario.name() + " did not do what it is meant to:\n" + expected.output());
            assertEquals(normalise(expected.output()), normalise(actual.output()),
                    () -> scenario.name() + ": the precompiled configuration printed something else");
        }

        // The runtime opt-out hands the configuration to Joran.
        Path optOutLoads = sample.resolve("build/logback-opt-out-classload.log");
        run(precompiled, sample, Map.of(), List.of("-Dmicronaut.runner.logback.precompiled=false",
                "-Xlog:class+load=info:file=build/logback-opt-out-classload.log"));
        assertTrue(Files.readString(optOutLoads, StandardCharsets.ISO_8859_1).contains(" " + JORAN_CONFIGURATOR + " "),
                "the opt-out did not reach Joran");

        // The extracted layout keeps the generated support its service file names.
        Path extracted = work.resolve("extracted");
        Process extract = new ProcessBuilder(Samples.javaExecutable().toString(), "-Dmicronaut.runner.mode=extract",
                "-jar", precompiled.toString(), "--destination", extracted.toString())
                .redirectErrorStream(true).start();
        String extractOutput = new String(extract.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, extract.waitFor(), extractOutput);
        Path applicationJar = extracted.resolve("hello-netty.jar");
        assertTrue(hasEntry(applicationJar, LOGBACK_CONFIGURATOR), extractOutput);
        assertTrue(hasEntry(applicationJar, "META-INF/services/ch.qos.logback.classic.spi.Configurator"));
        Run fromExtracted = run(applicationJar, sample, Map.of(), List.of());
        assertTrue(fromExtracted.body().startsWith("hello from "), fromExtracted.output());
    }

    private static void writeLogbackXml(Path file, String marker) throws IOException {
        Files.writeString(file, """
                <configuration>
                    <appender name="CUSTOM" class="ch.qos.logback.core.ConsoleAppender">
                        <encoder>
                            <pattern>%s %%level %%logger - %%msg%%n</pattern>
                        </encoder>
                    </appender>
                    <root level="INFO">
                        <appender-ref ref="CUSTOM"/>
                    </root>
                </configuration>
                """.formatted(marker), StandardCharsets.UTF_8);
    }

    /**
     * Starts an archive, waits until it answers {@code /hello} or exits, stops it and returns everything it
     * printed.
     */
    private static Run run(Path archive, Path sample, Map<String, String> environment, List<String> jvmArguments)
            throws IOException {
        Map<String, String> childEnvironment = new java.util.HashMap<>(environment);
        int port = Samples.freePort();
        childEnvironment.putIfAbsent("SERVER_PORT", Integer.toString(port));
        ForkedApplication application = ForkedApplication.start(archive, sample, childEnvironment, jvmArguments);
        String body = null;
        try {
            if (Integer.toString(port).equals(childEnvironment.get("SERVER_PORT"))) {
                body = application.awaitBody(URI.create("http://localhost:" + port + "/hello"), STARTUP_TIMEOUT);
            } else {
                // Expected to fail on its own: let it print everything and exit.
                application.awaitExit(STARTUP_TIMEOUT);
            }
        } finally {
            application.close();
        }
        // Joins the output drain, so the shutdown lines are in.
        application.awaitExit(STARTUP_TIMEOUT);
        return new Run(body, application.output());
    }

    /**
     * Normalises what differs between any two runs: times, the startup duration, thread names, ports and the
     * bean path Micronaut prints for a failed injection.
     */
    private static String normalise(String output) {
        StringBuilder normalised = new StringBuilder();
        for (String line : output.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("Path Taken:") || trimmed.startsWith("@") || trimmed.startsWith("\\--->")) {
                continue;
            }
            normalised.append(line.replaceAll("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}", "TIME")
                    .replaceAll("Startup completed in \\d+ms", "Startup completed in Nms")
                    .replaceAll("localhost:\\d+", "localhost:PORT")
                    .replaceAll("\\[[\\w-]*-\\d+\\]", "[THREAD]")).append('\n');
        }
        return normalised.toString();
    }

    private static Path copy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        return Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean hasEntry(Path jar, String name) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getEntry(name) != null;
        }
    }

    /**
     * One run of an archive.
     *
     * @param body   what {@code /hello} answered, or {@code null} when the run was not expected to serve
     * @param output everything the process printed
     */
    private record Run(String body, String output) {
    }

    /**
     * Pins what the launcher loads before it enters the application: exactly
     * {@link #PRE_MAIN_LAUNCHER_CLASSES} of its own classes, and none of the JDK classes that a pattern
     * switch or the foreign-memory value layouts would bring in.
     *
     * @param log the {@code -Xlog:class+load} output of a launch that has exited
     */
    private static void assertPreMainClasses(Path log) throws IOException {
        List<String> beforeStub = new ArrayList<>();
        boolean stubSeen = false;
        // Class names are ASCII; Latin-1 decodes any byte, whatever encoding a source path was written in.
        try (BufferedReader reader = Files.newBufferedReader(log, StandardCharsets.ISO_8859_1)) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                int tag = line.indexOf(CLASS_LOAD_TAG);
                if (tag < 0) {
                    continue;
                }
                int start = tag + CLASS_LOAD_TAG.length();
                int end = line.indexOf(' ', start);
                String name = end < 0 ? line.substring(start) : line.substring(start, end);
                if (name.startsWith(GENERATED_PACKAGE)) {
                    stubSeen = true;
                    break;
                }
                beforeStub.add(name);
            }
        }
        assertTrue(stubSeen, () -> log + " has no " + GENERATED_PACKAGE + " class, so the pre-main set is unknown");

        Set<String> launcher = new TreeSet<>();
        List<String> forbidden = new ArrayList<>();
        for (String name : beforeStub) {
            if (name.startsWith("io.micronaut.runner.")) {
                launcher.add(name);
            }
            if (name.startsWith("java.lang.runtime.SwitchBootstraps") || name.contains("$$TypeSwitch")
                    || name.startsWith("java.lang.foreign.ValueLayout$Of")
                    || name.startsWith("jdk.internal.foreign.layout.ValueLayouts$Of")) {
                forbidden.add(name);
            }
        }
        Set<String> added = new TreeSet<>(launcher);
        added.removeAll(PRE_MAIN_LAUNCHER_CLASSES);
        Set<String> missing = new TreeSet<>(PRE_MAIN_LAUNCHER_CLASSES);
        missing.removeAll(launcher);
        assertTrue(added.isEmpty() && missing.isEmpty(),
                () -> "the launcher classes loaded before the entry stub changed; added " + added
                        + ", missing " + missing + ". See PRE_MAIN_LAUNCHER_CLASSES.");
        assertTrue(forbidden.isEmpty(), () -> "loaded before the entry stub: " + forbidden);
    }

    /** Checks the manifest the JVM will read before anything is started, so a failure names the cause. */
    private static void assertStartsTheApplication(Path archive) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            Manifest manifest = jar.getManifest();
            assertNotNull(manifest, () -> archive + " has no manifest");
            assertEquals("io.micronaut.runner.Launcher", manifest.getMainAttributes().getValue("Main-Class"),
                    () -> archive + " does not start the launcher");
            assertEquals("com.example.Application",
                    manifest.getMainAttributes().getValue("Micronaut-Runner-Start-Class"),
                    () -> archive + " does not name the application's main class");
        }
    }

    private static BuildResult gradle(Path projectDirectory, String... tasks) {
        List<String> arguments = new ArrayList<>(List.of(tasks));
        arguments.add("-Prunner.repo=" + Samples.REPO);
        arguments.add("-Prunner.version=" + Samples.VERSION);
        arguments.add("--stacktrace");

        GradleRunner runner = GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withArguments(arguments)
                .forwardOutput();

        String version = gradleVersion();
        if (version != null) {
            runner = runner.withGradleVersion(version);
        }
        return runner.build();
    }

    /**
     * The Gradle version to build the samples with: the newest entry of the matrix the build passes in
     * {@code runner.test.gradleVersions}, or the distribution running this build when that is empty.
     *
     * <p>The newest rather than the whole matrix, because one run of this test costs a full Micronaut
     * dependency resolution plus a real server start, and what it is checking is the archive, not Gradle
     * compatibility. The version matrix belongs to the Gradle plugin's own functional tests, which
     * configure and run the task without packaging a real application.</p>
     *
     * @return the version, or {@code null} to use the running distribution
     */
    private static String gradleVersion() {
        String[] versions = System.getProperty("runner.test.gradleVersions", "").split(",");
        for (int i = versions.length - 1; i >= 0; i--) {
            String version = versions[i].trim();
            if (!version.isEmpty()) {
                return version;
            }
        }
        return null;
    }
}
