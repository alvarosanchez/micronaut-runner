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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Starts the sample applications many times with the bean definition prefetch, looking for a start that hangs.
 *
 * <p>The prefetch runs the static initialisers of Micronaut's bean definitions and converters on common pool
 * threads while the main thread is still in the application's own initialisers, logging configuration and the
 * context builder. One class initialisation cycle between the two sides is known, and the generated configurer
 * closes it. Another one would show up as a start that never becomes ready, and only on some runs: the known
 * cycle hung nine starts in twenty under {@code -Xlog:class+init}, which slows class initialisation down, and
 * none without it. So every sample is started in several legs that shift the timing: by default, with that
 * logging, with C1 only, interpreted, with a JDK AOT cache, and confined to two, three and four processors.
 * benchmark-large is also started from its Micronaut AOT archive, {@code optimizedMicronautRunnerJar}'s
 * {@code -all-optimized.jar}, whose application layer already registers configurers and static optimizations of
 * its own.</p>
 *
 * <p>The legs that confine the JVM use {@code taskset}, which only Linux has. Elsewhere they limit the JVM with
 * {@code -XX:ActiveProcessorCount}, which shrinks the pools without taking processors away. The prefetch needs
 * three common pool threads, which a JVM with two or three processors does not have: the two-processor leg
 * checks that stand-down, and the three-processor leg gives the pool its three threads anyway, so that the
 * prefetch also runs where threads outnumber processors.</p>
 *
 * <p>This is not part of {@code check}: run it with {@code ./gradlew :test-suite:prefetchSoak}. A start that does
 * not answer within a minute fails the test, with a thread dump of the application in the message.</p>
 */
@Timeout(value = 3, unit = TimeUnit.HOURS)
class DefinitionPrefetchSoakTest {

    private static final String TASK = ":micronautRunnerJar";
    private static final String OPTIMIZED_TASK = ":optimizedMicronautRunnerJar";
    /** The sample whose Micronaut AOT archive is started as well. */
    private static final String MAOT_SAMPLE = "benchmark-large";
    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);
    private static final String TRACE = "-Dmicronaut.runner.prefetch.trace=true";
    private static final String HANDED_OVER = "[micronaut-runner] definition prefetch handed over ";
    private static final int LAUNCHES = Integer.getInteger("runner.prefetchSoak.launches", 30);
    private static final boolean LINUX =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");

    @BeforeAll
    static void assumeTheSuiteCanRun() {
        Samples.assumeTheBuildProvidedItsProperties();
        Samples.requireIntegrationScenario();
        Samples.requirePublishedArtifact("io/micronaut/runner/standalone/"
                + "io.micronaut.runner.standalone.gradle.plugin/" + Samples.VERSION
                + "/io.micronaut.runner.standalone.gradle.plugin-" + Samples.VERSION + ".pom");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hello-netty", "benchmark-large"})
    void everyStartBecomesReady(String name) throws Exception {
        Path sample = Samples.sample(name);
        boolean maot = name.equals(MAOT_SAMPLE);
        // The prefetch is off unless a build asks for it; the samples' own build files stay as they are.
        Path init = Files.createTempFile("prefetch-soak", ".init.gradle");
        Files.writeString(init, "allprojects { tasks.matching { it.name == 'micronautRunnerJar'"
                + " || it.name == 'optimizedMicronautRunnerJar' }.configureEach {"
                + " it.options.put('definitionPrefetch', 'true') } }\n");
        try {
            if (maot) {
                Samples.gradle(sample, "clean", TASK, OPTIMIZED_TASK, "--init-script", init.toString());
            } else {
                Samples.gradle(sample, "clean", TASK, "--init-script", init.toString());
            }
        } finally {
            Files.deleteIfExists(init);
        }
        Path archive = sample.resolve("build/libs/" + name + "-0.1-all.jar");
        assertTrue(Files.isRegularFile(archive), () -> "the plugin did not write " + archive);
        Path work = sample.resolve("build/prefetch-soak");
        Files.createDirectories(work);

        int processors = Runtime.getRuntime().availableProcessors();
        List<Leg> legs = new ArrayList<>();
        legs.add(new Leg("default", List.of(), List.of(), processors));
        legs.add(new Leg("class+init logging", List.of(),
                List.of("-Xlog:class+init=info:file=build/prefetch-soak/class-init.log"), processors));
        legs.add(new Leg("C1 only", List.of(), List.of("-XX:TieredStopAtLevel=1"), processors));
        legs.add(new Leg("interpreted", List.of(), List.of("-Xint"), processors));
        Path cache = work.resolve("soak.aot");
        Files.deleteIfExists(cache);
        legs.add(new Leg("AOT cache", List.of(), List.of("-XX:AOTCache=" + cache), processors));
        for (int confined = 2; confined <= 4; confined++) {
            List<String> options = new ArrayList<>();
            int seen = LINUX ? Math.min(confined, processors) : confined;
            if (!LINUX) {
                options.add("-XX:ActiveProcessorCount=" + confined);
            }
            if (confined == 3) {
                options.add("-Djava.util.concurrent.ForkJoinPool.common.parallelism=3");
                // Four, as far as the rule below is concerned: three pool threads.
                seen = 4;
            }
            String legName = (LINUX ? "taskset -c 0-" + (confined - 1) : "-XX:ActiveProcessorCount=" + confined)
                    + (confined == 3 ? ", three pool threads" : "");
            legs.add(new Leg(legName, LINUX ? List.of("taskset", "-c", "0-" + (confined - 1)) : List.of(), options,
                    seen));
        }

        List<String> summary = new ArrayList<>();
        for (Leg leg : legs) {
            if (leg.name().equals("AOT cache")) {
                train(archive, sample, cache);
            }
            summary.add(soak(name, archive, sample, leg));
        }
        if (maot) {
            Path optimized = sample.resolve("build/libs/" + name + "-0.1-all-optimized.jar");
            assertTrue(Files.isRegularFile(optimized), () -> "the plugin did not write " + optimized);
            summary.add(soak(name, optimized, sample,
                    new Leg("runner-maot (optimizedMicronautRunnerJar)", List.of(), List.of(), processors)));
        }
        summary.forEach(line -> System.out.println("[prefetch-soak] " + line));
    }

    /**
     * Starts one archive {@link #LAUNCHES} times in one leg, and checks that each start prefetched exactly when the
     * leg gives the common pool enough threads.
     *
     * @return the leg's line of the report
     */
    private static String soak(String name, Path archive, Path sample, Leg leg) throws Exception {
        int handedOver = 0;
        long slowest = 0;
        for (int launch = 1; launch <= LAUNCHES; launch++) {
            Launch result = launch(archive, sample, leg, launch);
            handedOver += result.handedOver() ? 1 : 0;
            slowest = Math.max(slowest, result.millis());
        }
        // The common pool has one thread fewer than the JVM sees processors, and the prefetch needs three.
        int expected = leg.processors() > 3 ? LAUNCHES : 0;
        int prefetched = handedOver;
        assertEquals(expected, prefetched, () -> name + ", " + leg.name() + ": the prefetch was expected in "
                + expected + " of " + LAUNCHES + " starts on " + leg.processors() + " processors");
        return String.format(Locale.ROOT, "%s, %s: %d starts, %d prefetched, slowest %d ms",
                name, leg.name(), LAUNCHES, prefetched, slowest);
    }

    /**
     * Trains a JDK AOT cache once: start, serve a request, stop in an orderly way so the JVM writes the cache.
     */
    private static void train(Path archive, Path sample, Path cache) throws Exception {
        int port = Samples.freePort();
        ForkedApplication application = ForkedApplication.start(archive, sample,
                Map.of("SERVER_PORT", Integer.toString(port)), List.of("-XX:AOTCacheOutput=" + cache));
        try {
            awaitReady(application, port, "training the AOT cache");
        } finally {
            // The JVM writes the cache while it shuts down, which takes seconds.
            application.stop(Duration.ofMinutes(2));
        }
        application.awaitExit(Duration.ofMinutes(2));
        assertTrue(Files.isRegularFile(cache) && Files.size(cache) > 0,
                () -> "training wrote no AOT cache" + application.describe());
    }

    private static Launch launch(Path archive, Path sample, Leg leg, int number) throws Exception {
        int port = Samples.freePort();
        List<String> options = new ArrayList<>(leg.jvmArguments());
        options.add(TRACE);
        long start = System.nanoTime();
        ForkedApplication application = ForkedApplication.start(leg.prefix(), archive, sample,
                Map.of("SERVER_PORT", Integer.toString(port)), options);
        try {
            awaitReady(application, port, leg.name() + ", start " + number + " of " + LAUNCHES);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return new Launch(application.output().contains(HANDED_OVER), millis);
        } finally {
            application.close();
        }
    }

    /**
     * Polls {@code /hello} until it answers. A start that exits, or does not answer in time, fails the test; a
     * start that is still running then is a hang, and its thread dump says where.
     */
    private static void awaitReady(ForkedApplication application, int port, String what) throws Exception {
        long deadline = System.nanoTime() + READINESS_TIMEOUT.toNanos();
        URI uri = URI.create("http://localhost:" + port + "/hello");
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
            while (System.nanoTime() < deadline) {
                if (!application.alive()) {
                    fail(what + ": the application exited before it answered" + application.describe());
                }
                try {
                    if (client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                        return;
                    }
                } catch (IOException e) {
                    // Not listening yet.
                }
                Thread.sleep(POLL_INTERVAL);
            }
        }
        fail(what + ": not ready within " + READINESS_TIMEOUT.toSeconds() + " s. Thread dump:\n"
                + application.threadDump() + application.describe());
    }

    /**
     * One way of starting the application.
     *
     * @param name         what the report calls it
     * @param prefix       a command put in front of {@code java}, or nothing
     * @param jvmArguments the JVM options
     * @param processors   how many processors the JVM sees, or one more than its common pool has threads
     */
    private record Leg(String name, List<String> prefix, List<String> jvmArguments, int processors) {
    }

    /**
     * One start.
     *
     * @param handedOver whether the context took the prefetched definitions
     * @param millis     how long it took to answer, measured from here
     */
    private record Launch(boolean handedOver, long millis) {
    }
}
