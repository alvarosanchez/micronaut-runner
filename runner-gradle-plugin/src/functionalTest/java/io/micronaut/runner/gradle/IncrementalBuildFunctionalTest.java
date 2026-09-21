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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        writeFixture(directory);

        BuildResult stored = build(directory, "micronautRunnerJar", "--configuration-cache");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(stored, RUNNER_JAR_TASK));
        assertTrue(stored.getOutput().contains("Configuration cache entry stored"),
                () -> "the first run did not store a configuration cache entry:\n" + stored.getOutput());

        addMarker(directory, "from the configuration cache");

        BuildResult reused = build(directory, "micronautRunnerJar", "--configuration-cache");
        assertTrue(reused.getOutput().contains("Configuration cache entry reused"),
                () -> "the second run did not reuse the configuration cache entry:\n" + reused.getOutput());
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(reused, RUNNER_JAR_TASK),
                () -> "the task did not execute from the reused entry:\n" + reused.getOutput());

        String output = runJarSuccessfully(directory.resolve(DEFAULT_ARCHIVE));
        assertTrue(output.contains("marker=from the configuration cache"),
                () -> "the archive was not rebuilt from the reused configuration:\n" + output);
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
}
