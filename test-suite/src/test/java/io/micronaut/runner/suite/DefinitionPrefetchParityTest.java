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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the bean definition prefetch to what Micronaut does without it, on a packaged application.
 *
 * <p>The fixture, {@code samples/prefetch-parity}, is hello-netty plus {@code com.example.ClinitProbe}: a class
 * registered as a bean definition reference whose static initialiser fails in the way the {@code PROBE_CLINIT}
 * environment variable picks. Micronaut stops the application for two of those ways and skips the reference for
 * the third. The runner jar is packaged once and started with the prefetch and with
 * {@code -Dmicronaut.runner.prefetch=false}; both starts have to end the same way, with the same exit status and
 * the same chain of exceptions.</p>
 *
 * <p>That is the property a prefetch that initialised the reference classes itself would not have: once another
 * thread has run a failing static initialiser, Micronaut only sees a class that cannot be initialised, which it
 * skips, and an application that should not start would start without the bean.</p>
 */
@Timeout(value = 30, unit = TimeUnit.MINUTES)
class DefinitionPrefetchParityTest {

    private static final String TASK = ":micronautRunnerJar";
    private static final String ARCHIVE = "build/libs/prefetch-parity-0.1-all.jar";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);
    private static final String CLASSES = "MICRONAUT-INF/classes/";
    private static final String PREFETCH_PACKAGE = "io/micronaut/runner/generated/prefetch/";
    private static final String TASK_CLASS = "io.micronaut.runner.generated.prefetch.DefinitionPrefetch";
    private static final String CONFIGURER_CLASS = TASK_CLASS + "Configurer";
    private static final String SERVICE_FILE = "META-INF/services/io.micronaut.context.ApplicationContextConfigurer";
    private static final String TRACE = "-Dmicronaut.runner.prefetch.trace=true";
    private static final String OPT_OUT = "-Dmicronaut.runner.prefetch=false";
    private static final String TWO_THREADS = "-Djava.util.concurrent.ForkJoinPool.common.parallelism=2";
    private static final String HANDED_OVER = "[micronaut-runner] definition prefetch handed over ";
    private static final String SERVICE_TABLE = "io/micronaut/runner/generated/services/RunnerServiceTable.class";
    private static final String TRACE_TABLE = "-Dmicronaut.runner.static-services.trace=true";
    private static final String VERIFY_TABLE = "-Dmicronaut.runner.static-services.verify=true";
    private static final String NO_TABLE = "-Dmicronaut.runner.static-services=false";

    /** The static service table's answer to the context builder's lookup of configurers: a count, not a scan. */
    private static final Pattern CONFIGURERS_FROM_TABLE = Pattern.compile(
            "\\[micronaut-runner] static services: get "
                    + "io\\.micronaut\\.context\\.ApplicationContextConfigurer -> [1-9]");

    /** The header of an uncaught exception and of each of its causes, as the JVM prints them. */
    private static final Pattern THROWABLE = Pattern.compile("^(?:Exception in thread \"[^\"]*\" |Caused by: )(.+)$");

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*m");

    private static Path sample;
    private static Path archive;

    @BeforeAll
    static void packageTheFixture() {
        Samples.assumeTheBuildProvidedItsProperties();
        Samples.requireIntegrationScenario();
        Samples.requirePublishedArtifact("io/micronaut/runner/standalone/"
                + "io.micronaut.runner.standalone.gradle.plugin/" + Samples.VERSION
                + "/io.micronaut.runner.standalone.gradle.plugin-" + Samples.VERSION + ".pom");
        sample = Samples.sample("prefetch-parity");
        archive = sample.resolve(ARCHIVE);
        Samples.gradle(sample, "clean", TASK);
        assertTrue(Files.isRegularFile(archive), () -> "the plugin did not write " + archive);
    }

    /**
     * The archive carries the prefetch, and the two classes are the ones runner-build compiled: class files for
     * Java 25 that no build tool rewrote on the way in.
     */
    @Test
    void theArchiveCarriesThePrefetchAsItWasCompiled() throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            for (String name : List.of("DefinitionPrefetch.class", "DefinitionPrefetchConfigurer.class")) {
                JarEntry entry = jar.getJarEntry(CLASSES + PREFETCH_PACKAGE + name);
                assertNotNull(entry, () -> archive + " has no " + name);
                byte[] bytes;
                try (InputStream in = jar.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                assertEquals(69, ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF), name + " major version");
                String constants = new String(bytes, StandardCharsets.ISO_8859_1);
                assertFalse(constants.contains("org/gradle"), name + " was instrumented by Gradle");
                assertFalse(constants.contains("makeConcatWithConstants"), name + " uses invokedynamic");
            }
            assertEquals(CONFIGURER_CLASS + "\n", text(jar, CLASSES + SERVICE_FILE));
            assertNotNull(jar.getJarEntry(CLASSES + "io/micronaut/runner/generated/AppEntry.class"));
            assertNotNull(jar.getJarEntry(CLASSES + SERVICE_TABLE), "the sample has no static service table");
        }
    }

    /**
     * The sample's micronaut-core is one the static service table serves, and the table is generated after the
     * prefetch: it answers the context builder's lookup of configurers, which finds the prefetch's, and what it
     * lists matches Micronaut's scan. Its check stands the prefetch down, so that a mismatch fails on the main
     * thread, where the exception names the differences.
     */
    @Test
    void theStaticServiceTableListsThePrefetchConfigurer() throws IOException {
        List<String> tracing = new ArrayList<>(prefetching());
        tracing.add(TRACE_TABLE);
        Run traced = run(Map.of(), tracing, true);
        Run verified = run(Map.of(), List.of(TRACE, VERIFY_TABLE), true);

        assertTrue(traced.output().contains(HANDED_OVER), traced.output());
        assertTrue(CONFIGURERS_FROM_TABLE.matcher(traced.output()).find(), traced.output());
        assertTrue(verified.output().contains("[micronaut-runner] static services verified: "), verified.output());
        assertTrue(verified.output().contains(" 0 mismatches"), verified.output());
        assertFalse(verified.output().contains("[micronaut-runner] definition prefetch"), verified.output());
        assertEquals(traced.definitions(), verified.definitions(), "the prefetch changed the bean definitions");
    }

    /**
     * A reference whose static initialiser throws an exception, or an error that is not about a missing class,
     * stops the application, in the same way with the prefetch and without it. That holds whether the static
     * service table loads the references, which reports the failure in a plain {@code RuntimeException}, or
     * Micronaut's scan does, which reports it in a {@code ServiceLoadingException}.
     */
    @ParameterizedTest
    @CsvSource({"runtime, table", "linkage, table", "runtime, scan", "linkage, scan"})
    void aFailingReferenceStopsTheApplicationTheSameWay(String mode, String services) throws IOException {
        boolean table = "table".equals(services);
        List<String> prefetching = new ArrayList<>(prefetching());
        List<String> plain = new ArrayList<>(List.of(TRACE, OPT_OUT));
        if (!table) {
            prefetching.add(NO_TABLE);
            plain.add(NO_TABLE);
        }
        Run with = run(Map.of("PROBE_CLINIT", mode), prefetching, false);
        Run without = run(Map.of("PROBE_CLINIT", mode), plain, false);

        assertNotEquals(0, without.exit(), without.output());
        assertEquals(without.exit(), with.exit(), with.output());
        List<String> expected = throwables(without.output());
        assertEquals(table ? "java.lang.RuntimeException"
                        : "io.micronaut.core.io.service.SoftServiceLoader$ServiceLoadingException",
                expected.get(0).split(":")[0], without.output());
        assertEquals("runtime".equals(mode)
                ? "java.lang.IllegalStateException: ClinitProbe failed at runtime"
                : "java.lang.NoSuchFieldError: ClinitProbe.missing", expected.get(expected.size() - 1));
        assertEquals(expected, throwables(with.output()),
                () -> "the prefetch changed how the application fails:\n" + with.output());
        for (Run run : List.of(with, without)) {
            assertFalse(run.output().contains("Startup completed"), run.output());
            assertFalse(run.output().contains(HANDED_OVER), run.output());
        }
    }

    /**
     * A reference Micronaut skips is skipped either way: the application starts, serves, and has the same bean
     * definitions. The output of the prefetching start is the application's own, in its logback.xml layout,
     * logged on the main thread.
     */
    @Test
    void aSkippedReferenceStartsTheSameApplication() throws IOException {
        Run with = run(Map.of(), prefetching(), true);
        Run without = run(Map.of(), List.of(TRACE, OPT_OUT), true);

        assertEquals("hello from RunnerClassLoader", with.hello(), with.output());
        assertEquals("hello from RunnerClassLoader", without.hello(), without.output());
        assertTrue(with.definitions().contains("com.example.$HelloController$Definition"), with.definitions());
        assertFalse(with.definitions().contains("ClinitProbe"), with.definitions());
        assertEquals(without.definitions(), with.definitions(), "the prefetch changed the bean definitions");
        assertEquals(without.exit(), with.exit());
        assertTrue(with.output().contains(HANDED_OVER), with.output());
        assertFalse(without.output().contains("[micronaut-runner]"), without.output());
        assertEquals(List.of(), throwables(with.output()), with.output());

        String plain = ANSI.matcher(with.output()).replaceAll("");
        assertTrue(plain.lines().anyMatch(line -> line.contains("[main]")
                        && line.contains("io.micronaut.runtime.Micronaut - Startup completed")),
                () -> "the startup line is not the one logback.xml lays out:\n" + plain);
    }

    /**
     * An application that builds its context with {@code ApplicationContext.run()} rather than
     * {@code Micronaut.run} goes through the same builder, and gets the same definitions.
     */
    @Test
    void aContextBuiltWithoutTheServerGetsTheSameDefinitions() throws IOException {
        Run with = run(Map.of("PROBE_ENTRY", "context"), prefetching(), false);
        Run without = run(Map.of("PROBE_ENTRY", "context"), List.of(TRACE, OPT_OUT), false);

        assertEquals(0, with.exit(), with.output());
        assertEquals(0, without.exit(), without.output());
        List<String> names = printedDefinitions(with.output());
        assertTrue(names.contains("com.example.$Greeter$Definition"), with.output());
        assertEquals(printedDefinitions(without.output()), names, "the prefetch changed the bean definitions");
        assertTrue(with.output().contains(HANDED_OVER), with.output());
        assertFalse(without.output().contains("[micronaut-runner]"), without.output());
    }

    /**
     * Turned off, by the property or by a common pool with fewer than three threads, the prefetch loads one class
     * more than an archive without it, the configurer Micronaut instantiates as a service, and never the task. Turned
     * on, everything it loads verifies: that start runs with {@code -Xverify:all}.
     */
    @Test
    void thePrefetchClassIsNotLoadedWhenThePrefetchIsOff() throws IOException {
        List<String> verified = new ArrayList<>(prefetching());
        verified.add("-Xverify:all");
        assertTrue(loaded(archive, "on", verified).contains(" " + TASK_CLASS + " "), "the prefetch did not start");
        for (Map.Entry<String, String> off : Map.of("opt-out", OPT_OUT, "two-threads", TWO_THREADS).entrySet()) {
            String classes = loaded(archive, off.getKey(), List.of(off.getValue()));

            assertTrue(classes.contains(" " + CONFIGURER_CLASS + " "), off.getKey());
            assertFalse(classes.contains(" " + TASK_CLASS + " "), () -> off.getKey() + " loaded the prefetch task");
        }
    }

    /**
     * The extracted layout has no entry stub, so nothing starts the prefetch; but its application jar keeps the
     * two classes, because the service file it also keeps names one of them.
     */
    @Test
    void theExtractedLayoutKeepsTheClassesAndNeverStartsThePrefetch(@TempDir Path work) throws Exception {
        Path extracted = work.resolve("extracted");
        Process extract = new ProcessBuilder(Samples.javaExecutable().toString(), "-Dmicronaut.runner.mode=extract",
                "-jar", archive.toString(), "--destination", extracted.toString())
                .redirectErrorStream(true).start();
        String extractOutput = new String(extract.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, extract.waitFor(), extractOutput);
        Path applicationJar;
        try (Stream<Path> files = Files.list(extracted)) {
            List<Path> jars = files.filter(file -> file.getFileName().toString().endsWith(".jar")).toList();
            assertEquals(1, jars.size(), () -> extractOutput + "\n" + jars);
            applicationJar = jars.get(0);
        }

        try (JarFile jar = new JarFile(applicationJar.toFile())) {
            assertNotNull(jar.getJarEntry(PREFETCH_PACKAGE + "DefinitionPrefetch.class"), extractOutput);
            assertNotNull(jar.getJarEntry(PREFETCH_PACKAGE + "DefinitionPrefetchConfigurer.class"), extractOutput);
            assertNull(jar.getJarEntry("io/micronaut/runner/generated/AppEntry.class"), "the entry stub was kept");
            assertEquals(CONFIGURER_CLASS + "\n", text(jar, SERVICE_FILE));
        }
        String classes = loaded(applicationJar, "extracted", List.of(TRACE));
        assertTrue(classes.contains(" " + CONFIGURER_CLASS + " "), "the configurer was not loaded");
        assertFalse(classes.contains(" " + TASK_CLASS + " "), "the extracted layout started the prefetch");
    }

    /**
     * The options of a start with the prefetch. A machine with fewer than four processors has a common pool with
     * fewer than three threads, where the prefetch stands down; the pool is given three there, because these
     * tests are about what the prefetch does and not about when it pays off.
     */
    private static List<String> prefetching() {
        List<String> options = new ArrayList<>(List.of(TRACE));
        if (Runtime.getRuntime().availableProcessors() < 4) {
            options.add("-Djava.util.concurrent.ForkJoinPool.common.parallelism=3");
        }
        return options;
    }

    /**
     * Starts a jar with a class-load log, waits until it serves, stops it and hands back the log.
     */
    private static String loaded(Path jar, String name, List<String> jvmArguments) throws IOException {
        String log = "build/prefetch-" + name + "-classload.log";
        Files.deleteIfExists(sample.resolve(log));
        List<String> options = new ArrayList<>(jvmArguments);
        // Relative to the sample, the child's working directory: no colon, so -Xlog takes it as a file name.
        options.add("-Xlog:class+load=info:file=" + log);
        Run run = run(jar, Map.of(), options, true);
        assertTrue(run.hello().startsWith("hello from "), run.output());
        return Files.readString(sample.resolve(log), StandardCharsets.ISO_8859_1);
    }

    private static Run run(Map<String, String> environment, List<String> jvmArguments, boolean serves)
            throws IOException {
        return run(archive, environment, jvmArguments, serves);
    }

    /**
     * Starts a jar and sees it through: until it has served {@code /hello} and {@code /definitions} and was
     * stopped, or, for a start that is not meant to serve, until it has exited by itself.
     */
    private static Run run(Path jar, Map<String, String> environment, List<String> jvmArguments, boolean serves)
            throws IOException {
        Map<String, String> childEnvironment = new HashMap<>(environment);
        int port = Samples.freePort();
        childEnvironment.put("SERVER_PORT", Integer.toString(port));
        ForkedApplication application = ForkedApplication.start(jar, sample, childEnvironment, jvmArguments);
        String hello = null;
        String definitions = null;
        try {
            if (serves) {
                hello = application.awaitBody(URI.create("http://localhost:" + port + "/hello"), STARTUP_TIMEOUT);
                definitions = application.awaitBody(URI.create("http://localhost:" + port + "/definitions"),
                        STARTUP_TIMEOUT);
            } else {
                application.awaitExit(STARTUP_TIMEOUT);
            }
        } finally {
            application.close();
        }
        // Joins the output drain, so everything the process printed is in.
        int exit = application.awaitExit(STARTUP_TIMEOUT);
        return new Run(exit, hello, definitions, application.output());
    }

    /** The exception an application died of and its causes, each as {@code type: message}, outermost first. */
    private static List<String> throwables(String output) {
        List<String> found = new ArrayList<>();
        for (String line : ANSI.matcher(output).replaceAll("").split("\\R")) {
            Matcher matcher = THROWABLE.matcher(line);
            if (matcher.matches()) {
                found.add(matcher.group(1));
            }
        }
        return found;
    }

    /** The bean definition names the fixture printed between its two markers. */
    private static List<String> printedDefinitions(String output) {
        List<String> lines = output.lines().toList();
        int begin = lines.indexOf("DEFINITIONS-BEGIN");
        int end = lines.indexOf("DEFINITIONS-END");
        assertTrue(begin >= 0 && end > begin, () -> "the fixture printed no definitions:\n" + output);
        return lines.subList(begin + 1, end);
    }

    private static String text(JarFile jar, String name) throws IOException {
        JarEntry entry = jar.getJarEntry(name);
        assertNotNull(entry, () -> jar.getName() + " has no " + name);
        try (InputStream in = jar.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * One start of the fixture.
     *
     * @param exit        the exit status
     * @param hello       what {@code /hello} answered, or {@code null} when the start was not meant to serve
     * @param definitions what {@code /definitions} answered, or {@code null}
     * @param output      everything the process printed
     */
    private record Run(int exit, String hello, String definitions, String output) {
    }
}
