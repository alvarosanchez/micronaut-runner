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
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code micronautRunnerJdkAotCache} and {@code micronautRunnerLayout} through the builds of one project whose
 * application is a small HTTP server with a dependency: registration and {@code assemble} wiring, the
 * configuration cache and up-to-date checks, the settings reaching {@code app.jvmopts}, and the toolchain's JDK
 * training the cache.
 */
class JdkAotCacheFunctionalTest extends AbstractFunctionalTest {

    private static final String CACHE_TASK = ":micronautRunnerJdkAotCache";

    private static final String LAYOUT_TASK = ":micronautRunnerLayout";

    private static final String CACHE_DIRECTORY = "build/micronaut-runner/jdk-aot-cache";

    /** The fixture application as a server: it uses a dependency's class on {@code /work}. */
    private static final String SERVER_SOURCE = """
            package com.example;

            import com.example.lib.Greeter;
            import com.sun.net.httpserver.HttpExchange;
            import com.sun.net.httpserver.HttpServer;

            import java.net.InetAddress;
            import java.net.InetSocketAddress;
            import java.nio.charset.StandardCharsets;

            public final class App {

                public static void main(String[] args) throws Exception {
                    Thread lifetime = new Thread(() -> {
                        try {
                            Thread.sleep(180_000);
                        } catch (InterruptedException ignored) {
                        }
                        Runtime.getRuntime().halt(99);
                    });
                    lifetime.setDaemon(true);
                    lifetime.start();
                    int port = Integer.parseInt(System.getenv("MICRONAUT_SERVER_PORT"));
                    HttpServer server = HttpServer.create(
                            new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 0);
                    server.createContext("/ready", exchange -> respond(exchange, "ready"));
                    server.createContext("/work", exchange -> respond(exchange, Greeter.greet("world")));
                    server.start();
                }

                private static void respond(HttpExchange exchange, String body) throws java.io.IOException {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                }
            }
            """;

    private static final Pattern TOOLCHAIN_VERSION = Pattern.compile("TOOLCHAIN_RUNTIME_VERSION=(\\S+)");

    /** The Runner JAR's file name, which the layout keeps. */
    private static final String ARCHIVE_NAME = DEFAULT_ARCHIVE.substring("build/libs/".length());

    /** The line the cache task logs: the report's summary, then the output directory and the time it took. */
    private static final Pattern TRAINED = Pattern.compile("Trained and verified the JDK AOT cache for the layout"
            + " target: [\\d.]+% of the classes and \\d+ of \\d+ io\\.micronaut classes from the cache, 0 of 2 strict"
            + " probes failed\\. Launch it from its directory with: java @app\\.jvmopts -jar "
            + Pattern.quote(ARCHIVE_NAME) + " \\(in .+, \\d+\\.\\d s\\)");

    @Test
    void trainsVerifiesAndStaysUpToDate(@TempDir Path directory) throws Exception {
        // The feature version of the JDK that runs the build, which Gradle finds as a toolchain without a download.
        int feature = Runtime.version().feature();
        writeFixture(directory, """
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(%d)
                    }
                }
                micronautRunner {
                    training {
                        readinessPath = '/ready'
                        workloadPaths = ['/work']
                    }
                    jdkAotCache {
                        verifyProbes = 2
                        minCoverage = 0.9
                    }
                }
                def toolchainLauncher = javaToolchains.launcherFor(java.toolchain)
                tasks.register('printToolchainRuntimeVersion') {
                    doLast {
                        println "TOOLCHAIN_RUNTIME_VERSION=" + toolchainLauncher.get().metadata.javaRuntimeVersion
                    }
                }
                """.formatted(feature), "");
        write(directory.resolve("src/main/java/com/example/App.java"), SERVER_SOURCE);
        Path buildFile = directory.resolve("build.gradle");
        Path out = directory.resolve(CACHE_DIRECTORY);

        // (a) Both tasks are registered, and assemble builds the cache only when it is enabled.
        BuildResult tasks = build(directory, "tasks", "--all");
        assertTrue(tasks.getOutput().contains("micronautRunnerJdkAotCache")
                && tasks.getOutput().contains("micronautRunnerLayout"), tasks::getOutput);
        BuildResult defaultAssemble = build(directory, "assemble", "--dry-run");
        assertTrue(defaultAssemble.getOutput().contains(RUNNER_JAR_TASK), defaultAssemble::getOutput);
        assertFalse(defaultAssemble.getOutput().contains(CACHE_TASK), defaultAssemble::getOutput);
        assertFalse(defaultAssemble.getOutput().contains(LAYOUT_TASK), defaultAssemble::getOutput);
        append(buildFile, "micronautRunner { jdkAotCache { enabled = true } }");
        BuildResult enabledAssemble = build(directory, "assemble", "--dry-run");
        assertTrue(enabledAssemble.getOutput().contains(CACHE_TASK), enabledAssemble::getOutput);
        assertFalse(enabledAssemble.getOutput().contains(LAYOUT_TASK), enabledAssemble::getOutput);

        // (b) The configuration cache is stored, then reused, and the second run is up to date.
        BuildResult trained = build(directory, "micronautRunnerJdkAotCache", "--configuration-cache");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(trained, CACHE_TASK), trained::getOutput);
        assertTrue(trained.getOutput().contains("Configuration cache entry stored"), trained::getOutput);
        assertFalse(trained.getOutput().toLowerCase(java.util.Locale.ROOT).contains("configuration cache problem"),
                trained::getOutput);
        // The report's summary, which names the JAR, and what only the task knows: the directory and the time.
        assertTrue(TRAINED.matcher(trained.getOutput()).find(), trained::getOutput);
        for (String file : List.of(ARCHIVE_NAME, "lib/alpha.jar",
                "lib/beta.jar", "app.aot", "app.jvmopts", "app.aot.properties", "aot-report.json")) {
            assertTrue(Files.isRegularFile(out.resolve(file)), () -> "no " + file + " in " + out);
        }
        assertTrue(Files.readString(out.resolve("aot-report.json")).contains("\"verdict\": \"passed\""));
        assertEquals("-XX:AOTCache=app.aot\n", Files.readString(out.resolve("app.jvmopts")));
        BuildResult reused = build(directory, "micronautRunnerJdkAotCache", "--configuration-cache");
        assertTrue(reused.getOutput().contains("Configuration cache entry reused"), reused::getOutput);
        assertEquals(TaskOutcome.UP_TO_DATE, outcomeOf(reused, CACHE_TASK), reused::getOutput);

        // (d) The cache was trained with the toolchain's JDK.
        BuildResult printed = build(directory, "printToolchainRuntimeVersion");
        Matcher version = TOOLCHAIN_VERSION.matcher(printed.getOutput());
        assertTrue(version.find(), printed::getOutput);
        assertEquals(version.group(1), identity(out).getProperty("java.runtime.version"));
        assertEquals("layout", identity(out).getProperty("target"));

        // (c) Other JVM arguments train the cache again, and reach the argfile.
        append(buildFile, "micronautRunner { jdkAotCache { jvmArgs = ['-Xmx256m', '-XX:+UseSerialGC'] } }");
        BuildResult retrained = build(directory, "micronautRunnerJdkAotCache");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(retrained, CACHE_TASK), retrained::getOutput);
        assertEquals("-XX:AOTCache=app.aot\n-Xmx256m\n-XX:+UseSerialGC\n", Files.readString(out.resolve("app.jvmopts")));

        // (e) strict puts -XX:AOTMode=on in the argfile.
        append(buildFile, "micronautRunner { jdkAotCache { strict = true } }");
        BuildResult strict = build(directory, "micronautRunnerJdkAotCache");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(strict, CACHE_TASK), strict::getOutput);
        assertEquals("-XX:AOTCache=app.aot\n-XX:AOTMode=on\n-Xmx256m\n-XX:+UseSerialGC\n",
                Files.readString(out.resolve("app.jvmopts")));

        // training.jvmArgs belong to the startup profile: the cache never uses them, so it is not trained again.
        append(buildFile, "micronautRunner { training { jvmArgs = ['-Dprofile.only=true'] } }");
        BuildResult profileArgs = build(directory, "micronautRunnerJdkAotCache");
        assertEquals(TaskOutcome.UP_TO_DATE, outcomeOf(profileArgs, CACHE_TASK), profileArgs::getOutput);

        // The layout alone, for a cache trained elsewhere.
        BuildResult layout = build(directory, "micronautRunnerLayout");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(layout, LAYOUT_TASK), layout::getOutput);
        Path layoutDirectory = directory.resolve("build/micronaut-runner/layout");
        assertTrue(Files.isRegularFile(layoutDirectory.resolve(ARCHIVE_NAME))
                && Files.isRegularFile(layoutDirectory.resolve("lib/alpha.jar")), layout::getOutput);
        assertFalse(Files.exists(layoutDirectory.resolve("app.aot")));
        assertTrue(layout.getOutput().contains("Wrote the layout " + ARCHIVE_NAME + " with 2 JARs in lib/ to "),
                layout::getOutput);
    }

    @Test
    void anUnknownTargetFailsNamingBoth(@TempDir Path directory) throws IOException {
        writeFixture(directory, "micronautRunner { jdkAotCache { target = 'nested' } }", "");

        BuildResult failed = buildAndFail(directory, "micronautRunnerJdkAotCache");

        assertTrue(failed.getOutput().contains("must be 'layout' or 'singleJar', not 'nested'"), failed::getOutput);
    }

    private static Properties identity(Path out) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(out.resolve("app.aot.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static void append(Path file, String text) throws IOException {
        write(file, Files.readString(file) + "\n" + text + "\n");
    }
}
