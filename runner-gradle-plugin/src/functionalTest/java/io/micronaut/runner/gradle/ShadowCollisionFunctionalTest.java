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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A runner jar and a shaded jar are different archives, so writing both to one path would silently publish
 * whichever task happened to run last. The plugin refuses that; these tests hold it to the refusal, and to
 * letting the two coexist as soon as their names differ.
 *
 * <p>The shadow plugin itself is not on the test's classpath and would have to be downloaded, so each of
 * its two published ids is stubbed by a precompiled script plugin in the fixture's {@code buildSrc} that
 * registers the one thing the runner plugin looks at: a {@code Jar} task called {@code shadowJar}. The
 * plugin finds it through {@code tasks.named("shadowJar", Jar.class)} and reads its archive file, which is
 * exactly what the stub provides, so what is under test here is the collision rule rather than the shadow
 * plugin.</p>
 *
 * <p>One fixture serves both tests, because building its {@code buildSrc} is the slowest thing in this
 * class; which id is applied and which classifier it uses are chosen on the command line.</p>
 */
class ShadowCollisionFunctionalTest extends AbstractFunctionalTest {

    /** The one fixture both tests share: building its buildSrc is the slowest thing here. */
    @TempDir
    static Path project;

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
     * Writes the fixture and the two stubbed shading plugins once for the whole class.
     *
     * @throws IOException if the fixture cannot be written
     */
    @BeforeAll
    static void writeProject() throws IOException {
        writeFixture(project, BUILD_EXTRA, "");
        write(project.resolve("buildSrc/build.gradle"), """
                plugins {
                    id 'groovy-gradle-plugin'
                }
                """);
        write(project.resolve("buildSrc/src/main/groovy/com.gradleup.shadow.gradle"), SHADOW_STUB);
        write(project.resolve("buildSrc/src/main/groovy/com.github.johnrengelman.shadow.gradle"), SHADOW_STUB);
    }

    /**
     * When the shadow plugin is set to write the runner jar's own file, the build fails and says which two
     * archives collided and how to separate them.
     */
    @Test
    void sharingAFileNameWithTheShadedJarFails() {
        String output = buildAndFail(project, "micronautRunnerJar",
                "-PshadowPlugin=com.gradleup.shadow", "-PshadowClassifier=all").getOutput();

        assertTrue(output.contains("The shadow plugin is configured to write"),
                () -> "the failure does not explain the collision:\n" + output);
        assertTrue(output.contains(PROJECT_NAME + "-" + PROJECT_VERSION + "-all.jar"),
                () -> "the failure does not name the archive they collided on:\n" + output);
        assertTrue(output.contains("micronautRunnerJar { archiveClassifier = 'runner' }"),
                () -> "the failure does not say how to fix it:\n" + output);
    }

    /**
     * With different classifiers the two tasks are simply two tasks: both run, both archives are written,
     * and the runner jar still starts. The legacy plugin id is used here so that both ids the plugin
     * watches for are covered by this class.
     *
     * @throws IOException          if the archive cannot be read
     * @throws InterruptedException if the forked application is interrupted
     */
    @Test
    void differentClassifiersLetBothArchivesCoexist() throws IOException, InterruptedException {
        BuildResult result = build(project, "micronautRunnerJar", "shadowJar",
                "-PshadowPlugin=com.github.johnrengelman.shadow", "-PshadowClassifier=shadow");

        assertEquals(TaskOutcome.SUCCESS, outcomeOf(result, RUNNER_JAR_TASK));
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(result, ":shadowJar"));

        Path runnerJar = project.resolve(DEFAULT_ARCHIVE);
        Path shadedJar = project.resolve(
                "build/libs/" + PROJECT_NAME + "-" + PROJECT_VERSION + "-shadow.jar");
        assertTrue(Files.isRegularFile(runnerJar), () -> "no runner jar:\n" + result.getOutput());
        assertTrue(Files.isRegularFile(shadedJar), () -> "no shaded jar:\n" + result.getOutput());

        runJarSuccessfully(runnerJar);
    }
}
