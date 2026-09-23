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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the task is a well-behaved Gradle task rather than merely a correct one: up-to-date checks that
 * track the right inputs, a cache key that survives being moved to another directory, and a configuration
 * that can be serialised and replayed.
 *
 * <p>The configuration cache test is the one that earns its place: a task holding on to a {@code Project}
 * passes every other test in this suite and fails only here.</p>
 */
class IncrementalBuildFunctionalTest extends AbstractFunctionalTest {

    /**
     * The task re-runs when something it reads changes, and only then.
     *
     * @param directory a fresh project directory
     * @throws IOException if the fixture cannot be written
     */
    @Test
    void reRunsOnlyWhenItsInputsChange(@TempDir Path directory) throws IOException {
        writeFixture(directory);

        assertEquals(TaskOutcome.SUCCESS, outcomeOf(build(directory, "micronautRunnerJar"), RUNNER_JAR_TASK));

        BuildResult second = build(directory, "micronautRunnerJar");
        assertEquals(TaskOutcome.UP_TO_DATE, outcomeOf(second, RUNNER_JAR_TASK),
                () -> "nothing changed, so the task should not have run again:\n" + second.getOutput());

        write(directory.resolve("README.md"), "a file the task has no business reading\n");
        BuildResult unrelated = build(directory, "micronautRunnerJar");
        assertEquals(TaskOutcome.UP_TO_DATE, outcomeOf(unrelated, RUNNER_JAR_TASK),
                () -> "an unrelated file must not invalidate the archive:\n" + unrelated.getOutput());

        addMarker(directory, "changed");
        BuildResult changed = build(directory, "micronautRunnerJar");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(changed, RUNNER_JAR_TASK),
                () -> "a changed source file must rebuild the archive:\n" + changed.getOutput());
    }

    /**
     * The same project built in two different directories produces the same cache key, so the second build
     * takes its archive from the cache instead of packaging it again — and the archive it took still runs.
     *
     * @param root a fresh directory to hold both projects and the cache
     * @throws IOException          if the fixtures cannot be written
     * @throws InterruptedException if the forked application is interrupted
     */
    @Test
    void theArchiveIsTakenFromTheBuildCache(@TempDir Path root) throws IOException, InterruptedException {
        Path cache = root.resolve("build-cache");
        String settings = """
                buildCache {
                    local {
                        directory = new File('@cache@')
                    }
                }
                """.replace("@cache@", cache.toAbsolutePath().toString().replace('\\', '/'));

        Path first = writeFixture(root.resolve("first"), "", settings);
        Path second = writeFixture(root.resolve("second"), "", settings);

        BuildResult stored = build(first, "micronautRunnerJar", "--build-cache");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(stored, RUNNER_JAR_TASK));

        BuildResult reused = build(second, "micronautRunnerJar", "--build-cache");
        assertEquals(TaskOutcome.FROM_CACHE, outcomeOf(reused, RUNNER_JAR_TASK),
                () -> "the task is not relocatable: an identical project in another directory missed the"
                        + " cache\n" + reused.getOutput());

        runJarSuccessfully(second.resolve(DEFAULT_ARCHIVE));
    }

    /**
     * Dependency order is part of the local build-cache key, including when two inputs have the same name.
     *
     * @param root a fresh directory to hold both projects and the cache
     * @throws IOException          if the fixtures cannot be written
     * @throws InterruptedException if the forked application is interrupted
     */
    @Test
    void dependencyOrderIsPartOfTheLocalBuildCacheKey(@TempDir Path root)
            throws IOException, InterruptedException {
        Path cache = root.resolve("build-cache");
        String settings = """
                buildCache {
                    local {
                        directory = new File('@cache@')
                    }
                }
                """.replace("@cache@", cache.toAbsolutePath().toString().replace('\\', '/'));

        assertCacheTracksDependencyOrder(root, settings);
    }

    /**
     * Dependency order is part of an HTTP build-cache key, without relying on another Gradle process or
     * shared cache state.
     *
     * @param root a fresh directory to hold the relocated projects
     * @throws IOException          if the fixtures or HTTP cache cannot be written
     * @throws InterruptedException if the forked application is interrupted
     */
    @Test
    void dependencyOrderIsPartOfTheHttpBuildCacheKey(@TempDir Path root)
            throws IOException, InterruptedException {
        try (BuildCacheServer cache = new BuildCacheServer()) {
            String settings = """
                    buildCache {
                        local { enabled = false }
                        remote(HttpBuildCache) {
                            url = uri('@url@')
                            allowInsecureProtocol = true
                            push = true
                        }
                    }
                    """.replace("@url@", cache.url().toString());

            assertCacheTracksDependencyOrder(root, settings);
        }
    }

    /**
     * Coordinates are output metadata and therefore must invalidate the task even when the dependency bytes
     * stay unchanged.
     *
     * @param directory a fresh project directory
     * @throws IOException          if the fixture cannot be written
     * @throws InterruptedException if the archive cannot be inspected or launched
     */
    @Test
    void coordinatesArePartOfTheTaskInputs(@TempDir Path directory) throws IOException, InterruptedException {
        writeFixture(directory, """
                tasks.named('micronautRunnerJar') {
                    coordinates.put(file('libs/alpha.jar').absolutePath,
                        providers.gradleProperty('coordinate').getOrElse('com.example:alpha:1'))
                }
                """, "");

        BuildResult first = build(directory, "micronautRunnerJar", "--info");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(first, RUNNER_JAR_TASK));
        assertEquals(2, occurrences(first.getOutput(), "Dependency stage cache miss"));
        assertIndexContains(directory, "com.example:alpha:1");

        BuildResult unchanged = build(directory, "micronautRunnerJar");
        assertEquals(TaskOutcome.UP_TO_DATE, outcomeOf(unchanged, RUNNER_JAR_TASK));

        BuildResult changed = build(directory, "micronautRunnerJar", "--info",
                "-Pcoordinate=com.example:alpha:2");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(changed, RUNNER_JAR_TASK),
                () -> "changing only coordinates did not rebuild the index:\n" + changed.getOutput());
        assertEquals(2, occurrences(changed.getOutput(), "Dependency stage cache hit"),
                "coordinate changes must rebuild final metadata without repacking dependencies");
        assertIndexContains(directory, "com.example:alpha:2");
        runJarSuccessfully(directory.resolve(DEFAULT_ARCHIVE));
    }

    /**
     * Raw ZIP bytes remain inputs in PRESERVE mode even when names and uncompressed contents do not change.
     *
     * @param directory a fresh project directory
     * @throws IOException if the fixture or archive cannot be read
     */
    @Test
    void rawDependencyBytesArePartOfTheTaskInputs(@TempDir Path directory) throws IOException {
        writeFixture(directory, "micronautRunnerJar { compression = 'PRESERVE' }", "");

        BuildResult first = build(directory, "micronautRunnerJar", "--info");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(first, RUNNER_JAR_TASK));
        assertEquals(2, occurrences(first.getOutput(), "Dependency stage cache miss"));
        byte[] before = nestedJar(directory.resolve(DEFAULT_ARCHIVE), "MICRONAUT-INF/lib/alpha.jar");

        rewriteZipWithTimestamp(directory.resolve("libs/alpha.jar"), 1_000_000_200_000L);
        BuildResult changed = build(directory, "micronautRunnerJar", "--info");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(changed, RUNNER_JAR_TASK),
                () -> "changing raw ZIP bytes did not rebuild PRESERVE output:\n" + changed.getOutput());
        assertEquals(1, occurrences(changed.getOutput(), "Dependency stage cache hit"));
        assertEquals(1, occurrences(changed.getOutput(), "Dependency stage cache miss"));
        byte[] after = nestedJar(directory.resolve(DEFAULT_ARCHIVE), "MICRONAUT-INF/lib/alpha.jar");
        assertFalse(java.util.Arrays.equals(before, after), "the preserved nested jar still has the old bytes");
    }

    /**
     * The build configures under the configuration cache, and a second run reuses the stored entry.
     *
     * <p>A source file is changed between the two runs on purpose: that forces the task to execute from the
     * entry that was deserialised, rather than merely to be skipped as up to date. Anything the task
     * captured at configuration time that cannot be serialised — a {@code Project}, a {@code Configuration},
     * a {@code Task} — fails one of these two runs.</p>
     *
     * @param directory a fresh project directory
     * @throws IOException          if the fixture cannot be written
     * @throws InterruptedException if the forked application is interrupted
     */
    @Test
    void theConfigurationCacheIsStoredAndReused(@TempDir Path directory) throws IOException, InterruptedException {
        writeFixture(directory, """
                tasks.named('micronautRunnerJar') {
                    coordinates.put(file('libs/alpha.jar').absolutePath, 'com.example:alpha:configuration-cache')
                }
                """, "");

        BuildResult stored = build(directory, "micronautRunnerJar", "--configuration-cache", "--info");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(stored, RUNNER_JAR_TASK));
        assertEquals(2, occurrences(stored.getOutput(), "Dependency stage cache miss"));
        assertTrue(stored.getOutput().contains("Configuration cache entry stored"),
                () -> "the first run did not store a configuration cache entry:\n" + stored.getOutput());

        addMarker(directory, "from the configuration cache");

        BuildResult reused = build(directory, "micronautRunnerJar", "--configuration-cache", "--info");
        assertTrue(reused.getOutput().contains("Configuration cache entry reused"),
                () -> "the second run did not reuse the configuration cache entry:\n" + reused.getOutput());
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(reused, RUNNER_JAR_TASK),
                () -> "the task did not execute from the reused entry:\n" + reused.getOutput());
        assertEquals(2, occurrences(reused.getOutput(), "Dependency stage cache hit"),
                "dependency stages must survive configuration-cache reuse");

        String output = runJarSuccessfully(directory.resolve(DEFAULT_ARCHIVE));
        assertTrue(output.contains("marker=from the configuration cache"),
                () -> "the archive was not rebuilt from the reused configuration:\n" + output);
        assertIndexContains(directory, "com.example:alpha:configuration-cache");
    }

    @Test
    void dependencyStagesSurviveApplicationEditsAndBuildCacheRelocation(@TempDir Path root)
            throws IOException, InterruptedException {
        Path cache = root.resolve("build-cache");
        String settings = """
                buildCache {
                    local { directory = new File('@cache@') }
                }
                """.replace("@cache@", cache.toAbsolutePath().toString().replace('\\', '/'));
        Path first = writeFixture(root.resolve("first"), "", settings);
        Path relocated = writeFixture(root.resolve("relocated"), "", settings);

        BuildResult populated = build(first, "micronautRunnerJar", "--build-cache", "--info");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(populated, RUNNER_JAR_TASK));
        assertEquals(2, occurrences(populated.getOutput(), "Dependency stage cache miss"));

        BuildResult restored = build(relocated, "micronautRunnerJar", "--build-cache", "--info");
        assertEquals(TaskOutcome.FROM_CACHE, outcomeOf(restored, RUNNER_JAR_TASK));
        addMarker(relocated, "relocated edit");
        BuildResult appEdit = build(relocated, "micronautRunnerJar", "--build-cache", "--info");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(appEdit, RUNNER_JAR_TASK));
        assertEquals(2, occurrences(appEdit.getOutput(), "Dependency stage cache hit"), appEdit::getOutput);

        rewriteZipWithTimestamp(relocated.resolve("libs/alpha.jar"), 1_000_000_200_000L);
        BuildResult dependencyEdit = build(relocated, "micronautRunnerJar", "--build-cache", "--info");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(dependencyEdit, RUNNER_JAR_TASK));
        assertEquals(1, occurrences(dependencyEdit.getOutput(), "Dependency stage cache hit"));
        assertEquals(1, occurrences(dependencyEdit.getOutput(), "Dependency stage cache miss"));
        Path dependencyStages = relocated.resolve("build/micronaut-runner/dependency-stages");
        assertEquals(2, filesWithSuffix(dependencyStages, ".jar"),
                "obsolete content-addressed stages must not accumulate in Gradle outputs");
        assertEquals(2, filesWithSuffix(dependencyStages, ".sha256"),
                "obsolete stage checksums must not accumulate in Gradle outputs");
        runJarSuccessfully(relocated.resolve(DEFAULT_ARCHIVE));
    }

    private static long filesWithSuffix(Path directory, String suffix) throws IOException {
        try (var files = Files.walk(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(suffix)).count();
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static void assertCacheTracksDependencyOrder(Path root, String settings)
            throws IOException, InterruptedException {
        Path first = writeOrderFixture(root.resolve("first"), settings);
        Path second = writeOrderFixture(root.resolve("second"), settings);

        BuildResult stored = build(first, "micronautRunnerJar", "--build-cache", "--info");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(stored, RUNNER_JAR_TASK));
        assertEquals(4, occurrences(stored.getOutput(), "Dependency stage cache miss"));
        assertOrder(first, "A");

        BuildResult relocated = build(second, "micronautRunnerJar", "--build-cache");
        assertEquals(TaskOutcome.FROM_CACHE, outcomeOf(relocated, RUNNER_JAR_TASK),
                () -> "identical ordered inputs did not survive relocation:\n" + relocated.getOutput());
        assertOrder(second, "A");

        BuildResult reversed = build(second, "micronautRunnerJar", "--build-cache", "--info", "-Preversed=true");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(reversed, RUNNER_JAR_TASK),
                () -> "reordered equal-named dependencies reused stale output:\n" + reversed.getOutput());
        assertEquals(4, occurrences(reversed.getOutput(), "Dependency stage cache hit"),
                "same-basename stages must be reusable independently of final order and destination name");
        assertOrder(second, "B");

        BuildResult unchanged = build(second, "micronautRunnerJar", "--build-cache", "-Preversed=true");
        assertEquals(TaskOutcome.UP_TO_DATE, outcomeOf(unchanged, RUNNER_JAR_TASK));
    }

    private static Path writeOrderFixture(Path directory, String settings) throws IOException {
        writeFixture(directory, """
                def reversed = providers.gradleProperty('reversed').getOrElse('false').toBoolean()
                dependencies {
                    runtimeOnly files(reversed
                        ? ['libs/second/same.jar', 'libs/first/same.jar']
                        : ['libs/first/same.jar', 'libs/second/same.jar'])
                }
                """, settings);
        Path source = directory.resolve("src/main/java/com/example/App.java");
        write(source, Files.readString(source).replace(
                "System.out.println(\"message=\" + resource(\"/message.txt\"));",
                "System.out.println(\"order=\" + resource(\"/value.txt\"));\n"
                        + "        System.out.println(\"message=\" + resource(\"/message.txt\"));"));
        writeResourceJar(directory.resolve("libs/first/same.jar"), "A");
        writeResourceJar(directory.resolve("libs/second/same.jar"), "B");
        return directory;
    }

    private static void assertOrder(Path directory, String expected) throws IOException, InterruptedException {
        String output = runJarSuccessfully(directory.resolve(DEFAULT_ARCHIVE));
        assertTrue(output.contains("order=" + expected),
                () -> "expected dependency " + expected + " to win:\n" + output);
    }

    private static void assertIndexContains(Path directory, String coordinates)
            throws IOException, InterruptedException {
        Forked inspected = runJarInMode(directory.resolve(DEFAULT_ARCHIVE), "inspect");
        assertEquals(0, inspected.status(), inspected::output);
        assertTrue(inspected.output().contains(coordinates),
                () -> "the index does not contain " + coordinates + ":\n" + inspected.output());
    }

    private static void writeResourceJar(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            ZipEntry entry = new ZipEntry("value.txt");
            entry.setTime(1_000_000_000_000L);
            out.putNextEntry(entry);
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static byte[] nestedJar(Path archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            assertTrue(entry != null, () -> name + " is missing from " + archive);
            return zip.getInputStream(entry).readAllBytes();
        }
    }

    private static void rewriteZipWithTimestamp(Path file, long timestamp) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var records = zip.entries();
            while (records.hasMoreElements()) {
                ZipEntry entry = records.nextElement();
                entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry record = new ZipEntry(entry.getKey());
                record.setTime(timestamp);
                out.putNextEntry(record);
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    /**
     * Adds a printed line to the fixture application, which changes a file the task reads.
     *
     * @param directory the project directory
     * @param marker    the text the application will print
     * @throws IOException if the source cannot be rewritten
     */
    private static void addMarker(Path directory, String marker) throws IOException {
        Path source = directory.resolve("src/main/java/com/example/App.java");
        String text = Files.readString(source);
        write(source, text.replace(
                "System.out.println(\"RESULT OK\");",
                "System.out.println(\"marker=" + marker + "\");\n"
                        + "        System.out.println(\"RESULT OK\");"));
    }

    private static final class BuildCacheServer implements AutoCloseable {

        private final Map<String, byte[]> entries = new ConcurrentHashMap<>();
        private final HttpServer server;

        private BuildCacheServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private URI url() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cache/");
        }

        private void handle(HttpExchange exchange) throws IOException {
            String key = exchange.getRequestURI().getPath();
            try (exchange) {
                if ("GET".equals(exchange.getRequestMethod())) {
                    byte[] value = entries.get(key);
                    if (value == null) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        exchange.sendResponseHeaders(200, value.length);
                        exchange.getResponseBody().write(value);
                    }
                } else if ("PUT".equals(exchange.getRequestMethod())) {
                    entries.put(key, exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(405, -1);
                }
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
