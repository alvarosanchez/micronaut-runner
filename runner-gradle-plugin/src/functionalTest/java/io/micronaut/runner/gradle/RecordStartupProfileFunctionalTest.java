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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code recordStartupProfile} and the convention that embeds its file, through six builds of one project
 * whose application is a small HTTP server: record, embed, reuse, stay out of {@code build}, drop the file,
 * and lose to an explicit list.
 */
class RecordStartupProfileFunctionalTest extends AbstractFunctionalTest {

    private static final String PROFILE = "src/main/micronaut-runner/startup-classes.txt";

    private static final String RECORD_TASK = ":recordStartupProfile";

    /** The fixture application as a server: it uses a dependency's class on {@code /work}. */
    private static final String SERVER_SOURCE = """
            package com.example;

            import com.example.lib.Greeter;
            import com.sun.net.httpserver.HttpExchange;
            import com.sun.net.httpserver.HttpServer;

            import java.net.InetAddress;
            import java.net.InetSocketAddress;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.StandardCopyOption;

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
                    Path pid = Path.of(System.getenv("APP_PID_FILE"));
                    Files.createDirectories(pid.getParent());
                    Path temporary = Files.createTempFile(pid.getParent(), "pid-", ".tmp");
                    Files.writeString(temporary, Long.toString(ProcessHandle.current().pid()));
                    Files.move(temporary, pid, StandardCopyOption.ATOMIC_MOVE);
                }

                private static void respond(HttpExchange exchange, String body) throws java.io.IOException {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                }
            }
            """;

    private static final Pattern PRELOAD = Pattern.compile("Preload classes\\s+(\\S+)");

    @Test
    void recordsAProfileThatTheNextArchiveEmbedsByConvention(@TempDir Path directory) throws Exception {
        writeFixture(directory, """
                micronautRunner {
                    training {
                        readinessPath = '/ready'
                        workloadPaths = ['/work']
                        environment.put('APP_PID_FILE', file('build/app.pid').absolutePath)
                    }
                }
                """, "");
        write(directory.resolve("src/main/java/com/example/App.java"), SERVER_SOURCE);
        Path profile = directory.resolve(PROFILE);
        Path archive = directory.resolve(DEFAULT_ARCHIVE);

        // 1. The recording, with the configuration cache.
        BuildResult recorded = build(directory, "recordStartupProfile", "--configuration-cache");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(recorded, RECORD_TASK));
        assertTrue(recorded.getOutput().contains("Configuration cache entry stored"), recorded::getOutput);
        assertFalse(recorded.getOutput().toLowerCase(java.util.Locale.ROOT).contains("configuration cache problem"),
                recorded::getOutput);
        assertTrue(recorded.getOutput().contains("Recorded " + headerCount(profile) + " startup classes to "
                + PROFILE + " in "), recorded::getOutput);
        List<String> lines = Files.readAllLines(profile);
        assertTrue(lines.contains(MAIN_CLASS) && lines.contains("com.example.lib.Greeter"), lines::toString);
        assertTrue(lines.contains("# re-record: ./gradlew recordStartupProfile"), lines::toString);
        long pid = Long.parseLong(Files.readString(directory.resolve("build/app.pid")).trim());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "the recorded application is gone");

        // 2. The next archive embeds the profile, with no configuration.
        BuildResult embedded = build(directory, "micronautRunnerJar", "--configuration-cache");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(embedded, RUNNER_JAR_TASK), embedded::getOutput);
        assertEquals(Integer.toString(headerCount(profile)), preloadClasses(archive));

        // 3. Nothing changed: the entry is reused and the archive is up to date.
        BuildResult reused = build(directory, "micronautRunnerJar", "--configuration-cache");
        assertTrue(reused.getOutput().contains("Configuration cache entry reused"), reused::getOutput);
        assertEquals(TaskOutcome.UP_TO_DATE, outcomeOf(reused, RUNNER_JAR_TASK), reused::getOutput);

        // 4. Recording launches the application, so it is never part of build.
        BuildResult dryRun = build(directory, "build", "--dry-run");
        assertTrue(dryRun.getOutput().contains(RUNNER_JAR_TASK), dryRun::getOutput);
        assertFalse(dryRun.getOutput().contains(RECORD_TASK), dryRun::getOutput);

        // 5. Without the file, the configuration is computed again and the archive embeds nothing.
        Files.delete(profile);
        BuildResult dropped = build(directory, "micronautRunnerJar", "--configuration-cache");
        assertTrue(dropped.getOutput().contains("Configuration cache entry stored"), dropped::getOutput);
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(dropped, RUNNER_JAR_TASK), dropped::getOutput);
        assertEquals("none", preloadClasses(archive));

        // 6. An explicit list on the extension wins over the convention.
        write(profile, MAIN_CLASS + "\n");
        write(directory.resolve("other.txt"), MAIN_CLASS + "\ncom.example.lib.Greeter\ncom.example.lib.Shouter\n");
        Path buildFile = directory.resolve("build.gradle");
        write(buildFile, Files.readString(buildFile) + "\nmicronautRunner { startupClasses = file('other.txt') }\n");
        BuildResult explicit = build(directory, "micronautRunnerJar");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(explicit, RUNNER_JAR_TASK), explicit::getOutput);
        assertEquals("3", preloadClasses(archive));
    }

    /** The {@code classes:} count of a profile's header. */
    private static int headerCount(Path profile) throws IOException {
        return Files.readAllLines(profile).stream()
                .filter(line -> line.startsWith("# classes: "))
                .mapToInt(line -> Integer.parseInt(line.substring("# classes: ".length())))
                .findFirst().orElseThrow(() -> new AssertionError("no classes line in " + profile));
    }

    /** What {@code inspect} prints as {@code Preload classes}. */
    private static String preloadClasses(Path archive) throws IOException, InterruptedException {
        Forked inspected = runJarInMode(archive, "inspect");
        assertEquals(0, inspected.status(), inspected::output);
        Matcher matcher = PRELOAD.matcher(inspected.output());
        assertTrue(matcher.find(), inspected::output);
        return matcher.group(1);
    }
}
