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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A project that applies {@code io.micronaut.aot} gets {@code optimizedMicronautRunnerJar}, which packages the
 * archive of Micronaut AOT's {@code optimizedJitJar} into {@code -all-optimized.jar}, while
 * {@code micronautRunnerJar} keeps packaging the plain application and runs no Micronaut AOT task.
 *
 * <p>The Micronaut plugins are not on the test's classpath and would have to be downloaded, so the fixture's
 * {@code buildSrc} stubs them next to the Shadow stubs of {@link ShadowCollisionFunctionalTest}, with the
 * timing that matters to the runner plugin:</p>
 * <ul>
 *     <li>{@code io.micronaut.component} does nothing: it is the id that the Micronaut application and library
 *     plugins apply.</li>
 *     <li>{@code io.micronaut.aot} registers its tasks only once {@code io.micronaut.component} is applied, as
 *     upstream does. {@code optimizedJitJar} is registered in {@code afterEvaluate}, so that it appears after
 *     the runner plugin's callback has run; it reads the {@code jar} archive, as upstream's service-file merge
 *     does, drops {@code logback.xml} and adds a class that {@code prepareJitOptimizations} compiles. With a
 *     Shadow id applied, {@code optimizedJitJarAll} is registered in {@code afterEvaluate} too, with upstream's
 *     classifier {@code all-optimized}.</li>
 * </ul>
 *
 * <p>There is one fixture per order of the plugins, and what else varies is a Gradle property. No test starts
 * an archive: what is under test is the wiring, and the real plugins run in the end-to-end test suite.</p>
 */
class OptimizedRunnerJarFunctionalTest extends AbstractFunctionalTest {

    private static final String OPTIMIZED_TASK = ":optimizedMicronautRunnerJar";

    private static final String OPTIMIZED_ARCHIVE =
            "build/libs/" + PROJECT_NAME + "-" + PROJECT_VERSION + "-all-optimized.jar";

    /** The class the AOT stub generates, as Micronaut AOT generates its configurer. */
    private static final String MARKER = "MICRONAUT-INF/classes/com/example/AotMarker.class";

    private static final String LOGBACK_XML = "MICRONAUT-INF/classes/logback.xml";

    /** The tasks that only the optimized archive needs. */
    private static final List<String> AOT_TASKS = List.of(":prepareJitOptimizations", ":optimizedJitJar", ":jar");

    private static final String COMPONENT_STUB = """
            // The id the Micronaut application and library plugins apply. Nothing of it is looked at.
            """;

    private static final String AOT_STUB = """
            plugins.withId('io.micronaut.component') {
                def prepare = tasks.register('prepareJitOptimizations', JavaCompile) {
                    source = layout.projectDirectory.dir('src/aot/java')
                    classpath = files()
                    destinationDirectory = layout.buildDirectory.dir('aot/classes')
                }
                afterEvaluate {
                    def jar = tasks.named('jar', Jar)
                    def optimizedJitJar = tasks.register('optimizedJitJar', Jar) {
                        archiveClassifier = 'jit'
                        from(zipTree(jar.flatMap { it.archiveFile })) {
                            exclude 'logback.xml', 'META-INF/MANIFEST.MF'
                        }
                        from(prepare.flatMap { it.destinationDirectory })
                    }
                    if (plugins.hasPlugin('com.gradleup.shadow') || plugins.hasPlugin('com.github.johnrengelman.shadow')) {
                        tasks.register('optimizedJitJarAll', Jar) {
                            // A convention, as upstream sets it: a build script's name filter configures the
                            // task before this action runs, and its value has to win.
                            archiveClassifier.convention('all-optimized')
                            from(zipTree(optimizedJitJar.flatMap { it.archiveFile }))
                        }
                    }
                }
            }
            """;

    /**
     * What both fixtures share after their plugins: a manifest attribute that only the {@code jar} task's
     * configuration carries, the optional Shadow stub and the classifiers of its tasks.
     */
    private static final String BUILD_EXTRA = """
            jar {
                manifest {
                    attributes('Implementation-Version': 'from-the-jar-task')
                }
            }

            if (providers.gradleProperty('shadowPlugin').isPresent()) {
                apply plugin: providers.gradleProperty('shadowPlugin').get()
                tasks.named('shadowJar', Jar) {
                    archiveClassifier = providers.gradleProperty('shadowClassifier').get()
                }
                if (providers.gradleProperty('optimizedShadowClassifier').isPresent()) {
                    tasks.named { it == 'optimizedJitJarAll' }.configureEach {
                        archiveClassifier = providers.gradleProperty('optimizedShadowClassifier').get()
                    }
                }
            }
            """;

    /** Applies the Micronaut stubs unless a property says otherwise: AOT before the component plugin. */
    private static final String AOT_THEN_COMPONENT = """
            if (!providers.gradleProperty('withoutAot').isPresent()) {
                apply plugin: 'io.micronaut.aot'
                if (!providers.gradleProperty('aotStubWithoutJar').isPresent()) {
                    apply plugin: 'io.micronaut.component'
                }
            }
            """;

    /** Applies the Micronaut stubs unless a property says otherwise: the component plugin before AOT. */
    private static final String COMPONENT_THEN_AOT = """
            if (!providers.gradleProperty('withoutAot').isPresent()) {
                if (!providers.gradleProperty('aotStubWithoutJar').isPresent()) {
                    apply plugin: 'io.micronaut.component'
                }
                apply plugin: 'io.micronaut.aot'
            }
            """;

    /**
     * With the runner plugin applied first, its callbacks wait for both Micronaut plugins. The same two builds
     * show that the task's inputs survive the configuration cache.
     *
     * @param project a fresh project directory
     * @throws IOException if the fixture cannot be written or the archive cannot be read
     */
    @Test
    void packagesTheOptimizedJarWhenTheRunnerPluginComesFirst(@TempDir Path project) throws IOException {
        writeRunnerFirst(project);

        BuildResult stored = build(project, "optimizedMicronautRunnerJar", "--configuration-cache");
        assertTrue(stored.getOutput().contains("Configuration cache entry stored"),
                () -> "the build stored no configuration cache entry:\n" + stored.getOutput());
        assertPackagesTheOptimizedJar(project, stored);

        BuildResult reused = build(project, "optimizedMicronautRunnerJar", "--configuration-cache");
        assertTrue(reused.getOutput().contains("Configuration cache entry reused"),
                () -> "the second build did not reuse the configuration:\n" + reused.getOutput());
        assertEquals(TaskOutcome.UP_TO_DATE, outcomeOf(reused, OPTIMIZED_TASK));
    }

    /**
     * With the runner plugin applied last, both Micronaut plugins are already there, but
     * {@code optimizedJitJar} is still registered only after the runner plugin's callbacks.
     *
     * @param project a fresh project directory
     * @throws IOException if the fixture cannot be written or the archive cannot be read
     */
    @Test
    void packagesTheOptimizedJarWhenTheRunnerPluginComesLast(@TempDir Path project) throws IOException {
        writeRunnerLast(project);

        assertPackagesTheOptimizedJar(project, build(project, "optimizedMicronautRunnerJar"));
    }

    /**
     * {@code micronautRunnerJar} is the same archive with and without Micronaut AOT, and only the optimized
     * task brings Micronaut AOT's tasks and {@code :jar} into the build. Without a Micronaut component plugin,
     * Micronaut AOT registers no {@code optimizedJitJar}, and the runner plugin no optimized task.
     *
     * @param project a fresh project directory
     * @throws IOException if the fixture cannot be written or the archives cannot be read
     */
    @Test
    void theRunnerJarTaskNeverPackagesOrRunsMicronautAot(@TempDir Path project) throws IOException {
        writeRunnerFirst(project);
        Path archive = project.resolve(DEFAULT_ARCHIVE);

        BuildResult withAot = build(project, "micronautRunnerJar");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(withAot, RUNNER_JAR_TASK));
        for (String task : AOT_TASKS) {
            assertNull(withAot.task(task), () -> "micronautRunnerJar ran " + task + ":\n" + withAot.getOutput());
        }
        List<String> entries = entryNames(archive);
        byte[] bytes = Files.readAllBytes(archive);
        assertTrue(entries.contains(LOGBACK_XML), () -> "the plain application lost its logback.xml: " + entries);
        assertFalse(entries.contains(MARKER), () -> "micronautRunnerJar packaged Micronaut AOT output: " + entries);

        Files.delete(archive);
        BuildResult withoutAot = build(project, "micronautRunnerJar", "-PwithoutAot");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(withoutAot, RUNNER_JAR_TASK));
        assertEquals(entryNames(archive), entries, "applying io.micronaut.aot changed micronautRunnerJar's entries");
        assertArrayEquals(Files.readAllBytes(archive), bytes, "applying io.micronaut.aot changed micronautRunnerJar");

        String plainGraph = build(project, "--dry-run", "micronautRunnerJar").getOutput();
        String optimizedGraph = build(project, "--dry-run", "optimizedMicronautRunnerJar").getOutput();
        assertTrue(inGraph(plainGraph, RUNNER_JAR_TASK), () -> "not a dry run of micronautRunnerJar:\n" + plainGraph);
        for (String task : AOT_TASKS) {
            assertFalse(inGraph(plainGraph, task), () -> "micronautRunnerJar depends on " + task + ":\n" + plainGraph);
            assertTrue(inGraph(optimizedGraph, task),
                    () -> "optimizedMicronautRunnerJar does not depend on " + task + ":\n" + optimizedGraph);
        }

        String tasks = build(project, "tasks", "--all", "-PaotStubWithoutJar").getOutput();
        assertTrue(tasks.contains("micronautRunnerJar"), () -> "the runner task is gone:\n" + tasks);
        assertFalse(tasks.contains("optimizedMicronautRunnerJar"),
                () -> "the optimized task was registered although nothing registers optimizedJitJar:\n" + tasks);
    }

    /**
     * With a Shadow plugin applied, Micronaut AOT's {@code optimizedJitJarAll} writes {@code -all-optimized.jar}
     * too. The check names that pair and fails before either task writes the file, and lets both run once one
     * of them has another classifier.
     *
     * @param project a fresh project directory
     * @throws IOException if the fixture cannot be written
     */
    @Test
    void theOptimizedShadowJarMustNotShareTheOptimizedRunnerArchive(@TempDir Path project) throws IOException {
        writeRunnerLast(project);

        BuildResult collision = buildAndFail(project, "optimizedJitJarAll", "optimizedMicronautRunnerJar",
                "-PshadowPlugin=com.gradleup.shadow", "-PshadowClassifier=shadow");

        String output = collision.getOutput();
        assertTrue(output.contains("from optimizedJitJarAll, which is also the optimizedMicronautRunnerJar output"),
                () -> "the failure does not name the colliding pair:\n" + output);
        assertTrue(output.contains(PROJECT_NAME + "-" + PROJECT_VERSION + "-all-optimized.jar"),
                () -> "the failure does not name the archive they collided on:\n" + output);
        assertTrue(output.contains("optimizedMicronautRunnerJar { archiveClassifier = 'executable-optimized' }"),
                () -> "the failure does not say how to fix it:\n" + output);
        assertFalse(output.contains("from shadowJar,"),
                () -> "the failure blames the plain pair, whose archives are distinct:\n" + output);
        assertTrue(Files.notExists(project.resolve(OPTIMIZED_ARCHIVE)),
                "one of the two tasks wrote the archive before the collision was rejected");

        BuildResult distinct = build(project, "optimizedJitJarAll", "optimizedMicronautRunnerJar",
                "-PshadowPlugin=com.gradleup.shadow", "-PshadowClassifier=shadow",
                "-PoptimizedShadowClassifier=shadow-optimized");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(distinct, ":validateMicronautRunnerShadowOutputs"));
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(distinct, OPTIMIZED_TASK));
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(distinct, ":optimizedJitJarAll"));
        assertTrue(entryNames(project.resolve(OPTIMIZED_ARCHIVE)).contains(MARKER),
                "-all-optimized.jar is not the runner jar");
    }

    private static void assertPackagesTheOptimizedJar(Path project, BuildResult result) throws IOException {
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(result, OPTIMIZED_TASK));
        for (String task : AOT_TASKS) {
            assertEquals(TaskOutcome.SUCCESS, outcomeOf(result, task));
        }
        Path archive = project.resolve(OPTIMIZED_ARCHIVE);
        assertTrue(Files.isRegularFile(archive),
                () -> "the conventions did not put the archive at " + OPTIMIZED_ARCHIVE + "\n" + result.getOutput());
        assertTrue(Files.notExists(project.resolve(DEFAULT_ARCHIVE)),
                "building the optimized archive also built micronautRunnerJar");

        List<String> entries = entryNames(archive);
        assertTrue(entries.contains(MARKER), () -> "the archive is not built from optimizedJitJar: " + entries);
        assertTrue(entries.contains("MICRONAUT-INF/classes/com/example/App.class"),
                () -> "the application classes are not in the archive: " + entries);
        assertFalse(entries.contains(LOGBACK_XML),
                () -> "the archive has the logback.xml that optimizedJitJar leaves out: " + entries);
        assertEquals(List.of("MICRONAUT-INF/lib/beta.jar", "MICRONAUT-INF/lib/alpha.jar"),
                entries.stream().filter(name -> name.startsWith("MICRONAUT-INF/lib/")).toList(),
                () -> "the dependencies are not those of micronautRunnerJar: " + entries);
        // optimizedJitJar does not inherit the jar task's manifest, so the attribute can only have come from the
        // jar task's configuration.
        assertEquals("from-the-jar-task", manifestOf(archive).getValue("Implementation-Version"));
        assertEquals(MAIN_CLASS, manifestOf(archive).getValue("Micronaut-Runner-Start-Class"));
    }

    /**
     * Whether a {@code --dry-run} lists a task of the root project. The line is matched whole, so that
     * {@code :jar} does not also match {@code :buildSrc:jar}.
     *
     * @param dryRun the build output
     * @param task   the task path
     * @return whether the task is in the graph
     */
    private static boolean inGraph(String dryRun, String task) {
        return dryRun.lines().anyMatch(line -> line.equals(task + " SKIPPED"));
    }

    private static void writeRunnerFirst(Path directory) throws IOException {
        writeSettings(directory, "");
        write(directory.resolve("build.gradle"),
                buildScript("id '" + PLUGIN_ID + "'", AOT_THEN_COMPONENT + BUILD_EXTRA));
        writeRest(directory);
    }

    private static void writeRunnerLast(Path directory) throws IOException {
        writeSettings(directory, "");
        write(directory.resolve("build.gradle"), buildScript("id '" + PLUGIN_ID + "' apply false",
                COMPONENT_THEN_AOT + "apply plugin: '" + PLUGIN_ID + "'\n" + BUILD_EXTRA));
        writeRest(directory);
    }

    private static void writeRest(Path directory) throws IOException {
        writeApplication(directory);
        write(directory.resolve("src/main/resources/logback.xml"), "<configuration/>\n");
        write(directory.resolve("src/aot/java/com/example/AotMarker.java"), """
                package com.example;

                public final class AotMarker {
                }
                """);
        write(directory.resolve("buildSrc/build.gradle"), """
                plugins {
                    id 'groovy-gradle-plugin'
                }
                """);
        Path plugins = directory.resolve("buildSrc/src/main/groovy");
        write(plugins.resolve("com.gradleup.shadow.gradle"), ShadowCollisionFunctionalTest.SHADOW_STUB);
        write(plugins.resolve("io.micronaut.component.gradle"), COMPONENT_STUB);
        write(plugins.resolve("io.micronaut.aot.gradle"), AOT_STUB);
    }
}
