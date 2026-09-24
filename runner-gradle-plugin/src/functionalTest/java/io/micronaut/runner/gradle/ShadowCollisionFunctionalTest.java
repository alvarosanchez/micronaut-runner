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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A runner jar and a shaded jar are different archives, so writing both to one path would silently publish
 * whichever task happened to run last. The plugin refuses that before either producer can touch the path,
 * including when the runner task itself would be skipped as up to date or restored from the build cache.
 *
 * <p>The shadow plugin itself is not on the test's classpath and would have to be downloaded, so each of
 * its two published ids is stubbed by a precompiled script plugin in the fixture's {@code buildSrc} that
 * registers the one thing the runner plugin looks at: a {@code Jar} task called {@code shadowJar}. The
 * plugin finds it through {@code tasks.named("shadowJar", Jar.class)} and reads its archive file, which is
 * exactly what the stub provides, so what is under test here is the collision rule rather than the shadow
 * plugin.</p>
 */
class ShadowCollisionFunctionalTest extends AbstractFunctionalTest {

    /** A stub of a shading plugin: the runner plugin only ever looks for this task. */
    private static final String SHADOW_STUB = """
            plugins {
                id 'java'
            }

            tasks.register('shadowJar', Jar) {
                archiveClassifier = 'all'
                from sourceSets.main.output
            }
            """;

    /** Chooses the shading plugin id and the classifier of its task from the command line. */
    private static final String BUILD_EXTRA = """
            apply plugin: providers.gradleProperty('shadowPlugin').get()

            tasks.named('shadowJar', Jar) {
                archiveClassifier = providers.gradleProperty('shadowClassifier').get()
            }
            """;

    /**
     * Changing only Shadow's output from safe to conflicting must invalidate the guard even though all
     * packaging inputs and the existing runner archive are unchanged.
     *
     * @param project a fresh project directory
     * @throws IOException          if the fixture or archive cannot be read
     * @throws InterruptedException if the runner cannot be launched
     */
    @Test
    void safeOutputBecomingConflictingFailsBeforeUpToDateReuse(@TempDir Path project)
            throws IOException, InterruptedException {
        writeShadowFixture(project, "");
        BuildResult safe = build(project, "micronautRunnerJar",
                "-PshadowPlugin=com.gradleup.shadow", "-PshadowClassifier=shadow");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(safe, RUNNER_JAR_TASK));
        Path runnerJar = project.resolve(DEFAULT_ARCHIVE);
        runJarSuccessfully(runnerJar);
        byte[] original = Files.readAllBytes(runnerJar);

        BuildResult collision = buildAndFail(project, "micronautRunnerJar",
                "-PshadowPlugin=com.gradleup.shadow", "-PshadowClassifier=all");

        assertCollision(collision);
        assertArrayEquals(original, Files.readAllBytes(runnerJar),
                "valid runner archive was changed before the collision was rejected");
    }

    /**
     * A cached runner archive must not bypass collision validation, either in its original directory or in
     * an identical project relocated elsewhere.
     *
     * @param root a fresh directory to hold the fixtures and build cache
     * @throws IOException if the fixtures or archives cannot be read
     */
    @Test
    void collisionFailsBeforeCacheRestorationInPlaceAndAfterRelocation(@TempDir Path root) throws IOException {
        Path cache = root.resolve("build-cache");
        String settings = localCacheSettings(cache);
        Path first = writeShadowFixture(root.resolve("first"), settings);
        Path relocated = writeShadowFixture(root.resolve("relocated"), settings);

        BuildResult stored = build(first, "micronautRunnerJar", "--build-cache",
                "-PshadowPlugin=com.gradleup.shadow", "-PshadowClassifier=shadow");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(stored, RUNNER_JAR_TASK));
        byte[] original = Files.readAllBytes(first.resolve(DEFAULT_ARCHIVE));
        Files.delete(first.resolve(DEFAULT_ARCHIVE));

        BuildResult inPlace = buildAndFail(first, "micronautRunnerJar", "--build-cache",
                "-PshadowPlugin=com.gradleup.shadow", "-PshadowClassifier=all");
        assertCollision(inPlace);
        assertTrue(Files.notExists(first.resolve(DEFAULT_ARCHIVE)),
                "the runner archive was restored before the collision was rejected");

        BuildResult moved = buildAndFail(relocated, "micronautRunnerJar", "--build-cache",
                "-PshadowPlugin=com.gradleup.shadow", "-PshadowClassifier=all");
        assertCollision(moved);
        assertTrue(Files.notExists(relocated.resolve(DEFAULT_ARCHIVE)),
                "the relocated runner archive was restored before the collision was rejected");
        assertTrue(original.length > 0, "the cache was populated from an empty archive");
    }

    /**
     * Requesting {@code shadowJar} before {@code micronautRunnerJar} rejects a collision before Shadow can
     * replace a previously valid runner jar. That order is the only one that exercises the edge from
     * {@code shadowJar} to the validation task, and the legacy id covers that id's {@code withPlugin}
     * registration.
     *
     * @param project a fresh project directory
     * @throws IOException if the fixture or archive cannot be read
     */
    @Test
    void combinedRequestFailsBeforeShadowCanOverwriteTheRunner(@TempDir Path project) throws IOException {
        writeShadowFixture(project, "");
        BuildResult safe = build(project, "micronautRunnerJar",
                "-PshadowPlugin=com.github.johnrengelman.shadow", "-PshadowClassifier=shadow");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(safe, RUNNER_JAR_TASK));
        Path runnerJar = project.resolve(DEFAULT_ARCHIVE);
        byte[] original = Files.readAllBytes(runnerJar);

        BuildResult collision = buildAndFail(project, "shadowJar", "micronautRunnerJar",
                "-PshadowPlugin=com.github.johnrengelman.shadow", "-PshadowClassifier=all");

        assertCollision(collision);
        assertArrayEquals(original, Files.readAllBytes(runnerJar),
                "shadowJar replaced the runner archive before the collision was rejected");
    }

    /**
     * Collision validation remains active after Gradle reloads it from the configuration cache.
     *
     * @param project a fresh project directory
     * @throws IOException if the fixture cannot be written
     */
    @Test
    void collisionCheckSurvivesConfigurationCacheReplay(@TempDir Path project) throws IOException {
        writeShadowFixture(project, "");
        BuildResult stored = build(project, "micronautRunnerJar", "--configuration-cache",
                "-PshadowPlugin=com.gradleup.shadow", "-PshadowClassifier=shadow");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(stored, RUNNER_JAR_TASK));

        BuildResult reused = build(project, "micronautRunnerJar", "--configuration-cache",
                "-PshadowPlugin=com.gradleup.shadow", "-PshadowClassifier=shadow");
        assertTrue(reused.getOutput().contains("Configuration cache entry reused"),
                () -> "the safe build did not reuse configuration:\n" + reused.getOutput());
        assertEquals(TaskOutcome.UP_TO_DATE, outcomeOf(reused, RUNNER_JAR_TASK));

        BuildResult firstCollision = buildAndFail(project, "micronautRunnerJar", "--configuration-cache",
                "-PshadowPlugin=com.gradleup.shadow", "-PshadowClassifier=all");
        assertCollision(firstCollision);
        BuildResult replayedCollision = buildAndFail(project, "micronautRunnerJar", "--configuration-cache",
                "-PshadowPlugin=com.gradleup.shadow", "-PshadowClassifier=all");
        assertCollision(replayedCollision);
        assertTrue(replayedCollision.getOutput().contains("Configuration cache entry reused"),
                () -> "collision validation was not replayed from configuration cache:\n"
                        + replayedCollision.getOutput());
    }

    /**
     * Distinct archive names leave both producers incremental and relocatable, and the cached runner still
     * launches after a move.
     *
     * @param root a fresh directory to hold both projects and their build cache
     * @throws IOException          if the fixtures or archives cannot be read
     * @throws InterruptedException if the runner cannot be launched
     */
    @Test
    void distinctOutputsRemainIncrementalCacheableAndRunnable(@TempDir Path root)
            throws IOException, InterruptedException {
        Path cache = root.resolve("build-cache");
        String settings = localCacheSettings(cache);
        Path first = writeShadowFixture(root.resolve("first"), settings);
        Path relocated = writeShadowFixture(root.resolve("relocated"), settings);

        BuildResult built = build(first, "micronautRunnerJar", "shadowJar", "--build-cache",
                "-PshadowPlugin=com.github.johnrengelman.shadow", "-PshadowClassifier=shadow");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(built, RUNNER_JAR_TASK));
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(built, ":shadowJar"));

        BuildResult unchanged = build(first, "micronautRunnerJar", "shadowJar", "--build-cache",
                "-PshadowPlugin=com.github.johnrengelman.shadow", "-PshadowClassifier=shadow");
        assertEquals(TaskOutcome.UP_TO_DATE, outcomeOf(unchanged, RUNNER_JAR_TASK));
        assertEquals(TaskOutcome.UP_TO_DATE, outcomeOf(unchanged, ":shadowJar"));

        BuildResult cached = build(relocated, "micronautRunnerJar", "shadowJar", "--build-cache",
                "-PshadowPlugin=com.github.johnrengelman.shadow", "-PshadowClassifier=shadow");
        assertEquals(TaskOutcome.FROM_CACHE, outcomeOf(cached, RUNNER_JAR_TASK),
                () -> "validation made the runner cache key non-relocatable:\n" + cached.getOutput());

        Path runnerJar = relocated.resolve(DEFAULT_ARCHIVE);
        Path shadedJar = relocated.resolve(
                "build/libs/" + PROJECT_NAME + "-" + PROJECT_VERSION + "-shadow.jar");
        assertTrue(Files.isRegularFile(runnerJar), () -> "no runner jar:\n" + cached.getOutput());
        assertTrue(Files.isRegularFile(shadedJar), () -> "no shaded jar:\n" + cached.getOutput());
        runJarSuccessfully(runnerJar);
    }

    private static Path writeShadowFixture(Path directory, String settings) throws IOException {
        writeFixture(directory, BUILD_EXTRA, settings);
        write(directory.resolve("buildSrc/build.gradle"), """
                plugins {
                    id 'groovy-gradle-plugin'
                }
                """);
        write(directory.resolve("buildSrc/src/main/groovy/com.gradleup.shadow.gradle"), SHADOW_STUB);
        write(directory.resolve("buildSrc/src/main/groovy/com.github.johnrengelman.shadow.gradle"), SHADOW_STUB);
        return directory;
    }

    private static String localCacheSettings(Path cache) {
        return """
                buildCache {
                    local {
                        directory = new File('@cache@')
                    }
                }
                """.replace("@cache@", cache.toAbsolutePath().toString().replace('\\', '/'));
    }

    private static void assertCollision(BuildResult result) {
        String output = result.getOutput();
        assertTrue(output.contains("The shadow plugin is configured to write"),
                () -> "the failure does not explain the collision:\n" + output);
        assertTrue(output.contains(PROJECT_NAME + "-" + PROJECT_VERSION + "-all.jar"),
                () -> "the failure does not name the archive they collided on:\n" + output);
        assertTrue(output.contains("micronautRunnerJar { archiveClassifier = 'runner' }"),
                () -> "the failure does not say how to fix it:\n" + output);
    }
}
