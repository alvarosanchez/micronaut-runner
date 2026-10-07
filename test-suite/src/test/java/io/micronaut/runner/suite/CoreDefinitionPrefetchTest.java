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
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds micronaut-core's own bean definition prefetch, which a JVM started with
 * {@code -Dmicronaut.bean-definitions.prefetch=true} runs on micronaut-core 5.3 and later, to what a Runner JAR does
 * without it. Runner had a prefetch of its own, packaged with the {@code definitionPrefetch} option, until
 * micronaut-core shipped this one.
 *
 * <p>The fixture, {@code samples/core-prefetch}, is hello-netty on micronaut-core 5.3 plus
 * {@code com.example.ClinitProbe}: a class registered as a bean definition reference whose static initialiser fails
 * in the way the {@code PROBE_CLINIT} environment variable picks. Micronaut stops the application for two of those
 * ways and skips the reference for the third. The Runner JAR is packaged once with the default options and started
 * with the property and without it: both starts have to end the same way, and every start with the property has to
 * initialise the class micronaut-core's prefetch initialises only once it has started, so that a prefetch that stood
 * down cannot pass for one that ran.</p>
 */
@Timeout(value = 30, unit = TimeUnit.MINUTES)
class CoreDefinitionPrefetchTest {

    private static final String TASK = ":micronautRunnerJar";
    private static final String ARCHIVE = "build/libs/core-prefetch-0.1-all.jar";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);
    private static final String PROPERTY = "-Dmicronaut.bean-definitions.prefetch=true";
    /** The prefetch's class, which {@code Micronaut} initialises whenever the property is {@code true}. */
    private static final String PREFETCH_CLASS = "io/micronaut/runtime/BeanDefinitionPrefetch";
    /**
     * Its first task, which it initialises only when it starts, so never when it stands down. Verifying the
     * prefetch's class can load it either way.
     */
    private static final String STARTED_CLASS = PREFETCH_CLASS + "$Preload";
    private static final String SUBSTITUTE_LOGGER = "org.slf4j.helpers.SubstituteLogger";

    /** The header of an uncaught exception and of each of its causes, as the JVM prints them. */
    private static final Pattern THROWABLE = Pattern.compile("^(?:Exception in thread \"[^\"]*\" |Caused by: )(.+)$");

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*m");

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private static Path sample;
    private static Path archive;

    @BeforeAll
    static void packageTheFixture() {
        Samples.assumeTheBuildProvidedItsProperties();
        Samples.requireIntegrationScenario();
        Samples.requirePublishedArtifact("io/micronaut/runner/standalone/"
                + "io.micronaut.runner.standalone.gradle.plugin/" + Samples.VERSION
                + "/io.micronaut.runner.standalone.gradle.plugin-" + Samples.VERSION + ".pom");
        sample = Samples.sample("core-prefetch");
        archive = sample.resolve(ARCHIVE);
        Samples.gradle(sample, "clean", TASK);
        assertTrue(Files.isRegularFile(archive), () -> "the plugin did not write " + archive);
    }

    /**
     * A reference Micronaut skips is skipped either way: the application starts, serves and has the same bean
     * definitions, and it prints the same lines, all of them logged in its {@code logback.xml} layout by the
     * thread that logs them without the prefetch. No logger was created on a pool thread before logging was
     * configured, which would have left a {@code SubstituteLogger} behind.
     */
    @Test
    void aStartWithThePrefetchServesTheSameApplication() throws IOException {
        Run with = run(archive, "single-on", Map.of(), prefetching(), true);
        Run without = run(archive, "single-off", Map.of(), List.of(), true);

        assertEquals("hello from RunnerClassLoader", with.hello(), with.output());
        assertEquals("hello from RunnerClassLoader", without.hello(), without.output());
        assertTrue(with.definitions().contains("com.example.$HelloController$Definition"), with.definitions());
        assertFalse(with.definitions().contains("ClinitProbe"), with.definitions());
        assertEquals(without.definitions(), with.definitions(), "the prefetch changed the bean definitions");
        assertTrue(with.initialised(STARTED_CLASS), "the prefetch did not run:\n" + with.output());
        assertFalse(without.initialised(PREFETCH_CLASS), "the prefetch was read without the property");
        assertFalse(with.loaded(SUBSTITUTE_LOGGER), "a logger was created before logging was configured");
        assertFalse(without.loaded(SUBSTITUTE_LOGGER), "a logger was created before logging was configured");
        assertEquals(List.of(), throwables(with.output()), with.output());
        assertEquals(comparable(without.output()), comparable(with.output()), "the prefetch changed the output");
        assertTrue(comparable(with.output()).stream().anyMatch(line -> line.contains("[main]")
                        && line.contains("io.micronaut.runtime.Micronaut - Startup completed")),
                () -> "the startup line is not the one logback.xml lays out:\n" + with.output());
    }

    /**
     * A reference whose static initialiser throws an exception, or an error that is not about a missing class,
     * stops the application in the same way with the prefetch and without it: same exit status, same chain of
     * exceptions. The prefetch's thread meets the failure first, and Micronaut rethrows it on the main thread.
     */
    @ParameterizedTest
    @ValueSource(strings = {"runtime", "linkage"})
    void aFailingReferenceStopsTheApplicationTheSameWay(String mode) throws IOException {
        Run with = run(archive, mode + "-on", Map.of("PROBE_CLINIT", mode), prefetching(), false);
        Run without = run(archive, mode + "-off", Map.of("PROBE_CLINIT", mode), List.of(), false);

        assertNotEquals(0, without.exit(), without.output());
        assertEquals(without.exit(), with.exit(), with.output());
        List<String> expected = throwables(without.output());
        assertEquals("runtime".equals(mode)
                ? "java.lang.IllegalStateException: ClinitProbe failed at runtime"
                : "java.lang.NoSuchFieldError: ClinitProbe.missing", expected.get(expected.size() - 1),
                without.output());
        assertEquals(expected, throwables(with.output()),
                () -> "the prefetch changed how the application fails:\n" + with.output());
        assertTrue(with.initialised(STARTED_CLASS), "the prefetch did not run:\n" + with.output());
        for (Run run : List.of(with, without)) {
            assertFalse(run.output().contains("Startup completed"), run.output());
        }
    }

    /**
     * The check above tells a prefetch that ran from one that stood down: with two common pool threads,
     * micronaut-core reads the property but starts nothing, and the application starts as it does without it.
     */
    @Test
    void aPrefetchThatStandsDownIsToldApart() throws IOException {
        Run stoodDown = run(archive, "two-threads", Map.of(),
                List.of(PROPERTY, "-Djava.util.concurrent.ForkJoinPool.common.parallelism=2"), true);

        assertTrue(stoodDown.initialised(PREFETCH_CLASS), "the property was not read");
        assertFalse(stoodDown.initialised(STARTED_CLASS), "the prefetch started with two pool threads");
        assertEquals("hello from RunnerClassLoader", stoodDown.hello(), stoodDown.output());
    }

    /**
     * The extracted layout has no launcher and no entry stub, and the prefetch does not need them: started with
     * the property, its application JAR runs the prefetch and serves the same application as the single JAR.
     */
    @Test
    void theExtractedLayoutGetsThePrefetchToo(@TempDir Path work) throws Exception {
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

        Run with = run(applicationJar, "extracted-on", Map.of(), prefetching(), true);
        Run single = run(archive, "extracted-single", Map.of(), List.of(), true);

        assertTrue(with.initialised(STARTED_CLASS), "the prefetch did not run:\n" + with.output());
        assertFalse(with.loaded(SUBSTITUTE_LOGGER), "a logger was created before logging was configured");
        assertTrue(with.hello().startsWith("hello from "), with.output());
        assertEquals(single.definitions(), with.definitions(), "the extracted layout has other bean definitions");
    }

    /**
     * The options of a start with the prefetch. A machine with fewer than four processors has a common pool with
     * fewer than three threads, where the prefetch stands down; the pool is given three there, because these
     * tests are about what the prefetch does and not about when it pays off.
     */
    private static List<String> prefetching() {
        List<String> options = new ArrayList<>(List.of(PROPERTY));
        if (Runtime.getRuntime().availableProcessors() < 4) {
            options.add("-Djava.util.concurrent.ForkJoinPool.common.parallelism=3");
        }
        return options;
    }

    /**
     * Starts a jar with a class-load and class-initialisation log and sees it through: until it has served {@code /hello} and
     * {@code /definitions} and was stopped, or, for a start that is not meant to serve, until it has exited by
     * itself.
     */
    private static Run run(Path jar, String name, Map<String, String> environment, List<String> jvmArguments,
                           boolean serves) throws IOException {
        String log = "build/core-prefetch-" + name + "-classes.log";
        Files.deleteIfExists(sample.resolve(log));
        List<String> options = new ArrayList<>(jvmArguments);
        // Relative to the sample, the child's working directory: no colon, so -Xlog takes it as a file name.
        options.add("-Xlog:class+load=info,class+init=info:file=" + log);
        Map<String, String> childEnvironment = new HashMap<>(environment);
        int port = Samples.freePort();
        childEnvironment.put("SERVER_PORT", Integer.toString(port));
        ForkedApplication application = ForkedApplication.start(jar, sample, childEnvironment, options);
        String hello = null;
        String definitions = null;
        try {
            if (serves) {
                hello = application.awaitBody(URI.create(Samples.loopback(port) + "/hello"), STARTUP_TIMEOUT);
                definitions = application.awaitBody(URI.create(Samples.loopback(port) + "/definitions"),
                        STARTUP_TIMEOUT);
            } else {
                application.awaitExit(STARTUP_TIMEOUT);
            }
        } finally {
            application.close();
        }
        // Joins the output drain, so everything the process printed is in.
        int exit = application.awaitExit(STARTUP_TIMEOUT);
        String classes = Files.readString(sample.resolve(log), StandardCharsets.ISO_8859_1);
        return new Run(exit, hello, definitions, application.output(), classes);
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

    /** The lines a start printed, without colours, and with times, durations, ports and counters masked. */
    private static List<String> comparable(String output) {
        String plain = ANSI.matcher(output).replaceAll("");
        return plain.lines().map(line -> DIGITS.matcher(line).replaceAll("#")).toList();
    }

    /**
     * One start of the fixture.
     *
     * @param exit        the exit status
     * @param hello       what {@code /hello} answered, or {@code null} when the start was not meant to serve
     * @param definitions what {@code /definitions} answered, or {@code null}
     * @param output      everything the process printed
     * @param classes     the class-load and class-initialisation log
     */
    private record Run(int exit, String hello, String definitions, String output, String classes) {

        /** Whether the JVM loaded a class of this binary name. */
        boolean loaded(String className) {
            return classes.contains(" " + className + " ");
        }

        /** Whether the JVM initialised a class of this internal name. */
        boolean initialised(String internalName) {
            return classes.contains(" Initializing '" + internalName + "'");
        }
    }
}
