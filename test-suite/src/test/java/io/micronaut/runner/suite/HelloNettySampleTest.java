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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * <p>The sample also applies {@code io.micronaut.aot}, as a Micronaut Launch project does. Once that launch
 * has closed, the same test builds {@code optimizedMicronautRunnerJar} with the real Micronaut AOT plugin and
 * serves the same request from the optimized archive.</p>
 *
 * <p>The sample is built in place rather than in a temporary directory because its {@code settings.gradle}
 * reads the version catalog by a relative path. Its outputs are declared as build outputs of the sample,
 * not of this test.</p>
 */
@Timeout(value = 30, unit = TimeUnit.MINUTES)
class HelloNettySampleTest {

    /** The task the Gradle plugin registers. */
    private static final String TASK = ":micronautRunnerJar";

    /** The interim plugin's experimental task that trains and verifies a JDK AOT cache. */
    private static final String JDK_AOT_CACHE_TASK = ":micronautRunnerJdkAotCache";

    /** Where that task writes, with the classifier the plugin defaults to. */
    private static final String ARCHIVE = "build/libs/hello-netty-0.1-all.jar";

    /** The task the Gradle plugin also registers in a project that applies {@code io.micronaut.aot}. */
    private static final String OPTIMIZED_TASK = ":optimizedMicronautRunnerJar";

    /** Where that task writes: the archive of Micronaut AOT's {@code optimizedJitJar}, packaged by Runner. */
    private static final String OPTIMIZED_ARCHIVE = "build/libs/hello-netty-0.1-all-optimized.jar";

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

    /**
     * The service file that registers a Logback configurator. The names of the classes Micronaut AOT's
     * micronaut-aot-logback generates are not its API, so the tests find them through this file.
     */
    private static final String LOGBACK_SERVICE = "META-INF/services/ch.qos.logback.classic.spi.Configurator";

    /** The configurator Micronaut AOT's logback.xml.to.java optimizer generates into the sample's package. */
    private static final String LOGBACK_XML_TO_JAVA = "com.example.StaticLogbackConfiguration";

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

    /** Compares the static service table with Micronaut's scan on the first lookup. */
    private static final String VERIFY_STATIC_SERVICES = "-Dmicronaut.runner.static-services.verify=true";

    /** Makes a packaged table stand down, so that Micronaut scans as it does without one. */
    private static final String NO_STATIC_SERVICES = "-Dmicronaut.runner.static-services=false";

    /** What a launch prints once the table matched the scan; the counts sit between the two. */
    private static final String VERIFIED = "[micronaut-runner] static services verified: ";
    private static final String NO_MISMATCH = " entries, 0 mismatches";

    /** The classes of the static service table inside an application layer or an extracted application jar. */
    private static final String STATIC_SERVICES_PACKAGE = "io/micronaut/runner/generated/services/";
    private static final String STATIC_SERVICES_REGISTRATION =
            "META-INF/services/io.micronaut.core.optim.StaticOptimizations$Loader";

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
        assertDefaultDependencyTransforms(archive);
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
                    URI.create(Samples.loopback(port) + "/hello"), STARTUP_TIMEOUT);
            assertEquals("hello from RunnerClassLoader", body,
                    () -> "the application answered, but not from the runner class loader"
                            + application.describe());
        } finally {
            application.close();
        }
        assertPreMainClasses(classLoadLog);

        // The sample applies io.micronaut.aot, yet the plain archive above ran none of Micronaut AOT's tasks.
        for (String task : List.of(":prepareJitOptimizations", ":optimizedJitJar", ":jar")) {
            assertNull(result.task(task), () -> TASK + " ran " + task + ":\n" + result.getOutput());
        }
        assertTrue(hasEntry(archive, CLASSES + "logback.xml"), () -> archive + " is not the plain application");

        // The optimized archive, built directly: no clean and no init script, so that it differs from the
        // archive above in one thing, its application layer.
        Path optimized = sample.resolve(OPTIMIZED_ARCHIVE);
        BuildResult optimizedResult = gradle(sample, OPTIMIZED_TASK);
        assertEquals(TaskOutcome.SUCCESS, optimizedResult.task(OPTIMIZED_TASK).getOutcome(),
                () -> "the optimized packaging task did not run:\n" + optimizedResult.getOutput());
        assertTrue(hasEntry(optimized, CLASSES + "com/example/AOTApplicationContextConfigurer.class"),
                () -> optimized + " does not hold Micronaut AOT's generated classes");
        assertFalse(hasEntry(optimized, CLASSES + "logback.xml"),
                () -> optimized + " holds the logback.xml that Micronaut AOT replaces");
        // Micronaut AOT's logback.xml.to.java registers its own Logback configurator, so none is precompiled.
        assertEquals(List.of(LOGBACK_XML_TO_JAVA), configurators(optimized),
                () -> optimized + " does not register Micronaut AOT's configurator alone");
        assertStartsTheApplication(optimized);
        assertEquals("hello from RunnerClassLoader", run(optimized, sample, Map.of(), List.of()).body());
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

        List<String> generated = generatedLogbackEntries(precompiled);
        String configurator = configurators(precompiled).get(0);
        assertEquals(3, generated.size(), () -> precompiled + ": " + generated);
        assertTrue(generated.contains(configurator.replace('.', '/') + ".class"), generated::toString);
        assertEquals(List.of(), configurators(joran), () -> joran + " registers a configurator");

        // The fast path verifies, and neither links Joran nor loads the class that falls back to it.
        Path classLoads = sample.resolve("build/logback-precompiled-classload.log");
        Run fast = run(precompiled, sample, Map.of(), List.of("-Xverify:all",
                "-Xlog:class+load=info:file=build/logback-precompiled-classload.log"));
        assertEquals("hello from RunnerClassLoader", fast.body(), fast.output());
        String loaded = Files.readString(classLoads, StandardCharsets.ISO_8859_1);
        assertTrue(loaded.contains(" " + configurator + " "), fast.output());
        assertFalse(loaded.contains(" ch.qos.logback.classic.joran."), "the fast path loaded Joran");
        String configuratorPackage = configurator.substring(0, configurator.lastIndexOf('.') + 1);
        assertEquals(List.of(configurator), loaded.lines()
                        .filter(line -> line.contains(CLASS_LOAD_TAG + configuratorPackage))
                        .map(line -> line.substring(line.indexOf(CLASS_LOAD_TAG) + CLASS_LOAD_TAG.length()).split(" ")[0])
                        .toList(),
                "the fast path loaded a generated class besides the configurator");
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
                // Micronaut never reads this variable, refresh or not: both archives keep logback.xml.
                new Scenario("LOGBACK_CONFIGURATIONFILE on a refresh",
                        Map.of("LOGBACK_CONFIGURATIONFILE", configurationFile.toString()),
                        List.of("-Dlogger.levels.com.example=DEBUG"), "Setting log level 'DEBUG'"),
                // It does read variables named like the properties themselves, which a container can declare.
                new Scenario("an environment variable named logback.configurationFile on a refresh",
                        Map.of("logback.configurationFile", configurationFile.toString()),
                        List.of("-Dlogger.levels.com.example=DEBUG"), "CONFIGURATION-FILE INFO"),
                new Scenario("an environment variable named logger.config",
                        Map.of("logger.config", loggerConfig.toString()), List.of(), "LOGGER-CONFIG INFO"),
                new Scenario("logback.configurationFile in the environment outranks -Dlogger.config",
                        Map.of("logback.configurationFile", configurationFile.toString()),
                        List.of("-Dlogger.config=" + loggerConfig), "CONFIGURATION-FILE INFO"),
                new Scenario("the opt-out with -Dlogger.config", Map.of(),
                        List.of("-Dmicronaut.logback.precompiled=false", "-Dlogger.config=" + loggerConfig),
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
        run(precompiled, sample, Map.of(), List.of("-Dmicronaut.logback.precompiled=false",
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
        for (String name : generated) {
            assertTrue(hasEntry(applicationJar, name), () -> applicationJar + " lacks " + name + ":\n" + extractOutput);
        }
        Run fromExtracted = run(applicationJar, sample, Map.of(), List.of());
        assertTrue(fromExtracted.body().startsWith("hello from "), fromExtracted.output());
    }

    /**
     * Trains a JDK AOT cache for the sample's extracted layout with the interim plugin's task, then launches the
     * layout the way the docs say to, {@code java @app.jvmopts -jar <jar>} from the cache's directory, in strict mode:
     * the JDK's own class loader answers, from a cache that the launch accepted.
     */
    @Test
    void trainsAJdkAotCacheForTheLayoutAndServesWithIt(@TempDir Path work) throws Exception {
        // hello-netty has no stop endpoint: on Windows only jcmd, on JDK 25.0.4 and later, ends the recording.
        Assumptions.assumeTrue(!System.getProperty("os.name", "").startsWith("Windows")
                        || Runtime.version().compareToIgnoreOptional(Runtime.Version.parse("25.0.4")) >= 0,
                "on Windows the recording needs jcmd's AOT.end_recording, JDK 25.0.4 or later");
        Path sample = Samples.sample("hello-netty");
        Path init = work.resolve("jdk-aot-cache.init.gradle");
        Files.writeString(init, """
                allprojects {
                    pluginManager.withPlugin('io.micronaut.runner.standalone') {
                        micronautRunner {
                            training {
                                readinessPath = '/hello'
                                workloadPaths = ['/hello']
                            }
                        }
                    }
                }
                """, StandardCharsets.UTF_8);
        BuildResult result = gradle(sample, "clean", JDK_AOT_CACHE_TASK, "--init-script", init.toString());
        assertEquals(TaskOutcome.SUCCESS, result.task(JDK_AOT_CACHE_TASK).getOutcome(), result::getOutput);

        Path cache = sample.resolve("build/micronaut-runner/jdk-aot-cache");
        String report = Files.readString(cache.resolve("aot-report.json"), StandardCharsets.UTF_8);
        assertTrue(report.contains("\"verdict\": \"passed\"") && report.contains("\"target\": \"layout\""), report);
        int port = Samples.freePort();
        ForkedApplication application = ForkedApplication.start(cache.resolve("hello-netty-0.1-all.jar"), cache,
                Map.of("SERVER_PORT", Integer.toString(port)), List.of("@app.jvmopts", "-XX:AOTMode=on"));
        try {
            String body = application.awaitBody(URI.create(Samples.loopback(port) + "/hello"), STARTUP_TIMEOUT);
            assertEquals("hello from AppClassLoader", body, application::describe);
        } finally {
            application.close();
        }
        String path = sample.resolve("build/micronaut-runner").toString();
        List<String> left = ProcessHandle.allProcesses()
                .filter(process -> process.info().commandLine().map(line -> line.contains(path)).orElse(false))
                .map(process -> process.pid() + " " + process.info().commandLine().orElse(""))
                .toList();
        assertTrue(left.isEmpty(), () -> "processes left behind: " + left);
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
                body = application.awaitBody(URI.create(Samples.loopback(port) + "/hello"), STARTUP_TIMEOUT);
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
            if (trimmed.startsWith("at ")) {
                // Which of several candidate beans the failed injection went through varies from run to run.
                line = line.replaceAll("\\$\\w+\\$Definition\\.", "\\$BEAN\\$Definition.");
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

    /** The configurators an archive's application layer registers, in the order its service file names them. */
    private static List<String> configurators(Path archive) throws IOException {
        try (JarFile file = new JarFile(archive.toFile())) {
            JarEntry service = file.getJarEntry(CLASSES + LOGBACK_SERVICE);
            if (service == null) {
                return List.of();
            }
            try (InputStream in = file.getInputStream(service)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::trim)
                        .filter(line -> !line.isEmpty()).toList();
            }
        }
    }

    /**
     * What Logback precompilation added to an archive's application layer, by name in that layer: the service file
     * and every entry of its configurator's package.
     */
    private static List<String> generatedLogbackEntries(Path archive) throws IOException {
        List<String> configurators = configurators(archive);
        assertEquals(1, configurators.size(), () -> archive + " registers " + configurators);
        String configurator = configurators.get(0);
        String directory = CLASSES + configurator.substring(0, configurator.lastIndexOf('.') + 1).replace('.', '/');
        List<String> generated = new ArrayList<>(List.of(LOGBACK_SERVICE));
        try (JarFile file = new JarFile(archive.toFile())) {
            file.stream().map(JarEntry::getName).filter(name -> name.startsWith(directory) && !name.endsWith("/"))
                    .map(name -> name.substring(CLASSES.length())).forEach(generated::add);
        }
        return generated;
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
     * Starts the sample four ways: the runner jar and the layout extracted from it, each once with the static
     * service table checked against Micronaut's own scan and once with the table switched off.
     *
     * <p>The sample records the order of its bean definitions in every launch. The table must not change it,
     * and an extracted application must register its beans in the order the jar it came from does.</p>
     */
    @Test
    void theStaticServiceTableAnswersAsTheScanInTheJarAndExtracted() throws Exception {
        Path sample = Samples.sample("hello-netty");
        Path archive = sample.resolve(ARCHIVE);
        BuildResult result = gradle(sample, TASK);
        assertTrue(Files.isRegularFile(archive),
                () -> "the plugin did not write " + archive + ":\n" + result.getOutput());
        try (JarFile jar = new JarFile(archive.toFile())) {
            assertNotNull(jar.getEntry("MICRONAUT-INF/classes/" + STATIC_SERVICES_PACKAGE + "RunnerServiceTable.class"),
                    () -> "the default configuration did not generate the static service table:\n"
                            + result.getOutput());
        }

        List<String> table = beanOrder(archive, sample, "build/bean-order-table.txt", VERIFY_STATIC_SERVICES, true);
        List<String> scan = beanOrder(archive, sample, "build/bean-order-scan.txt", NO_STATIC_SERVICES, false);
        assertFalse(scan.isEmpty(), "the sample recorded no bean definitions");
        assertEquals(scan, table, "the table must register the bean definitions in the order the scan does");

        Path extracted = sample.resolve("build/extracted-static-services");
        extract(archive, extracted, sample);
        Path applicationJar = extracted.resolve(archive.getFileName());
        try (JarFile jar = new JarFile(applicationJar.toFile())) {
            assertNotNull(jar.getEntry(STATIC_SERVICES_PACKAGE + "RunnerStaticServices.class"),
                    "the extracted application keeps the table's classes");
            assertNotNull(jar.getEntry(STATIC_SERVICES_PACKAGE + "RunnerServiceTable.class"));
            assertNotNull(jar.getEntry(STATIC_SERVICES_REGISTRATION), "and the line that registers them");
            assertNull(jar.getEntry("io/micronaut/runner/generated/AppEntry.class"),
                    "the entry stub belongs to the runner format");
        }
        List<String> extractedTable = beanOrder(applicationJar, sample, "build/bean-order-extracted-table.txt",
                VERIFY_STATIC_SERVICES, true);
        beanOrder(applicationJar, sample, "build/bean-order-extracted-scan.txt", NO_STATIC_SERVICES, false);
        assertEquals(table, extractedTable,
                "an extracted application registers its beans in the order its runner jar does");
    }

    /**
     * Starts an application, waits for {@code /hello} and reads back the bean order it recorded.
     *
     * @param jar      the runner jar, or the application jar of an extracted layout
     * @param sample   the working directory
     * @param file     where the sample writes its bean order, relative to the working directory
     * @param option   the static service switch to start with
     * @param verified whether the launch must report that the table matched the scan
     * @return the names of the bean definitions, in registration order
     */
    private static List<String> beanOrder(Path jar, Path sample, String file, String option, boolean verified)
            throws IOException {
        Path order = sample.resolve(file);
        Files.deleteIfExists(order);
        int port = Samples.freePort();
        ForkedApplication application = ForkedApplication.start(jar, sample, Map.of(
                "SERVER_PORT", Integer.toString(port)), List.of(option, "-Drunner.test.bean-order=" + file));
        try {
            String body = application.awaitBody(URI.create(Samples.loopback(port) + "/hello"), STARTUP_TIMEOUT);
            assertTrue(body.startsWith("hello from "), () -> "unexpected answer " + body + application.describe());
            String output = application.output();
            assertEquals(verified, output.contains(VERIFIED) && output.contains(NO_MISMATCH),
                    () -> "with " + option + application.describe());
        } finally {
            application.close();
        }
        assertTrue(Files.isRegularFile(order), () -> "the sample did not write " + order);
        return Files.readAllLines(order, StandardCharsets.UTF_8);
    }

    private static void extract(Path archive, Path destination, Path workingDirectory) throws Exception {
        Path log = workingDirectory.resolve("build/extract-static-services.log");
        Process process = new ProcessBuilder(Samples.javaExecutable().toString(), "-Dmicronaut.runner.mode=extract",
                "-jar", archive.toAbsolutePath().toString(), "--destination", destination.toAbsolutePath().toString(),
                "--force")
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("extraction did not finish: " + Files.readString(log));
        }
        assertEquals(0, process.exitValue(), () -> {
            try {
                return "extraction failed: " + Files.readString(log);
            } catch (IOException e) {
                return "extraction failed, and its log cannot be read: " + e;
            }
        });
    }

    /**
     * Pins what the launcher loads before it enters the application: exactly
     * {@link #PRE_MAIN_LAUNCHER_CLASSES} of its own classes, none of the JDK classes that a pattern
     * switch or the foreign-memory value layouts would bring in, and none of micronaut-core's annotations: the
     * launcher's classes carry {@code @Internal}, which only reflection would load.
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
                    || name.startsWith("jdk.internal.foreign.layout.ValueLayouts$Of")
                    || name.startsWith("io.micronaut.core.annotation.")) {
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

    /**
     * The default build desugars the lambdas of dependency classes and keeps their local-variable tables, and
     * records what it did in {@code MICRONAUT-INF/transforms.txt}: one tab-separated line per nested jar and step,
     * whose second column names the step and whose third counts the classes that step rewrote.
     *
     * @param archive the runner jar
     */
    private static void assertDefaultDependencyTransforms(Path archive) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            JarEntry entry = jar.getJarEntry("MICRONAUT-INF/transforms.txt");
            assertNotNull(entry, () -> archive + " records no build transforms");
            String text;
            try (InputStream in = jar.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            List<String[]> dependencies = text.lines()
                    .filter(line -> line.startsWith("MICRONAUT-INF/lib/"))
                    .map(line -> line.split("\t"))
                    .toList();
            assertTrue(dependencies.stream().noneMatch(fields -> fields[1].equals("stripLocalVariables")),
                    () -> "local-variable tables were stripped by default:\n" + text);
            long desugared = dependencies.stream()
                    .filter(fields -> fields[1].equals("desugarLambdas"))
                    .mapToLong(fields -> Long.parseLong(fields[2]))
                    .sum();
            assertTrue(desugared > 0, () -> "no dependency class was desugared:\n" + text);
        }
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
