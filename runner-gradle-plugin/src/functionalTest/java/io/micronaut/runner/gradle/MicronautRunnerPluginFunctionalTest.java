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
import java.util.Map;
import java.util.jar.Attributes;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the plugin promises a project that just applies it: a task that exists, is wired into
 * {@code assemble}, takes every one of its inputs from the project, and produces an archive that actually
 * starts.
 *
 * <p>Each test here drives a single Gradle build and then asserts everything that build can be asked about,
 * because a TestKit invocation costs seconds and an assertion costs nothing.</p>
 */
class MicronautRunnerPluginFunctionalTest extends AbstractFunctionalTest {

    /** Main and per-package attributes configured on the {@code jar} task, which the archive must carry. */
    private static final String JAR_MANIFEST = """
            jar {
                manifest {
                    attributes('Implementation-Title': 'app', 'Implementation-Version': '1.2.3',
                            'Specification-Vendor': 'Example')
                    attributes(['Implementation-Version': 'pkg-9', 'Sealed': 'true'], 'com/example/')
                }
            }
            """;

    private static final String PACKAGE_SOURCE = """
            package com.example;

            public final class App {
                public static void main(String[] args) {
                    System.out.println("impl=" + App.class.getPackage().getImplementationVersion());
                    System.out.println("sealed=" + App.class.getPackage().isSealed());
                    System.out.println("RESULT OK");
                }
            }
            """;

    /**
     * The plugin registers the task, describes it, and puts it in the build group — the three things a user
     * sees before ever running it. Without a Shadow plugin, it registers no collision check.
     *
     * @param directory a fresh project directory
     * @throws IOException if the fixture cannot be written
     */
    @Test
    void registersTheRunnerJarTask(@TempDir Path directory) throws IOException {
        writeFixture(directory);

        String output = build(directory, "help", "--task", "micronautRunnerJar", "tasks", "--all").getOutput();

        assertTrue(output.contains(RUNNER_JAR_TASK), () -> "the task was never registered:\n" + output);
        // Named as a string on purpose: this suite tests the published plugin, not the classes this
        // module happens to have compiled, so nothing here links against them.
        assertTrue(output.contains("io.micronaut.runner.gradle.MicronautRunnerJar"),
                () -> "the task has the wrong type:\n" + output);
        assertTrue(output.contains("Packages the application and its dependencies as a runner jar"),
                () -> "the task has no description:\n" + output);
        assertTrue(output.contains("build"), () -> "the task is not in the build group:\n" + output);
        assertFalse(output.contains("validateMicronautRunnerShadowOutputs"),
                () -> "the Shadow collision check was registered without a Shadow plugin:\n" + output);
    }

    /**
     * The whole of the happy path in one build: {@code assemble} alone produces the archive, at the path the
     * conventions choose, containing the layers the format defines, in dependency resolution order — and
     * the archive starts, serves its resources and reaches its own main class.
     *
     * @param directory a fresh project directory
     * @throws IOException          if the fixture cannot be written or the archive cannot be read
     * @throws InterruptedException if the forked application is interrupted
     */
    @Test
    void assembleProducesAnArchiveThatRuns(@TempDir Path directory) throws IOException, InterruptedException {
        writeFixture(directory);

        BuildResult result = build(directory, "assemble");

        // assemble alone is enough: the plugin wires the task into it.
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(result, RUNNER_JAR_TASK));

        Path archive = directory.resolve(DEFAULT_ARCHIVE);
        assertTrue(Files.isRegularFile(archive),
                () -> "the conventions did not put the archive at " + DEFAULT_ARCHIVE + "\n" + result.getOutput());
        assertTrue(result.getOutput().contains("Runner jar written to"),
                () -> "the task did not report what it wrote:\n" + result.getOutput());

        Attributes manifest = manifestOf(archive);
        assertEquals("io.micronaut.runner.Launcher", manifest.getValue("Main-Class"));
        // The main class comes from the application plugin, with no configuration of the runner task.
        assertEquals(MAIN_CLASS, manifest.getValue("Micronaut-Runner-Start-Class"));

        List<String> entries = entryNames(archive);
        assertTrue(entries.contains("MICRONAUT-INF/index.bin"), () -> "no index: " + entries);
        assertTrue(entries.contains("MICRONAUT-INF/classes/com/example/App.class"),
                () -> "the application classes are not in the archive: " + entries);
        assertTrue(entries.contains("MICRONAUT-INF/classes/message.txt"),
                () -> "the application resources are not in the archive: " + entries);
        assertTrue(entries.contains("io/micronaut/runner/Launcher.class"),
                () -> "the launcher is not in the archive: " + entries);
        assertTrue(entries.contains("MICRONAUT-INF/classes/io/micronaut/runner/generated/AppEntry.class"),
                () -> "the default configuration did not generate the entry stub: " + entries);

        // Dependencies keep the order the runtime classpath resolved them, which the fixture declares as
        // beta then alpha precisely because that is not alphabetical.
        assertEquals(List.of("MICRONAUT-INF/lib/beta.jar", "MICRONAUT-INF/lib/alpha.jar"),
                entries.stream().filter(name -> name.startsWith("MICRONAUT-INF/lib/")).toList(),
                () -> "dependencies are not in runtime classpath order: " + entries);

        // STORED is the default: the entries of each nested dependency are re-packed uncompressed.
        Map<String, Integer> nested = nestedEntryMethods(archive, "MICRONAUT-INF/lib/alpha.jar");
        assertTrue(nested.containsKey("com/example/lib/Greeter.class"), () -> "unexpected nested jar: " + nested);
        for (Map.Entry<String, Integer> entry : nested.entrySet()) {
            assertEquals(ZipEntry.STORED, entry.getValue(),
                    () -> entry.getKey() + " should be stored under the default compression");
        }

        String output = runJarSuccessfully(archive, "one", "two");
        assertTrue(output.contains("message=from the application layer"), () -> output);
        assertTrue(output.contains("alpha=from the alpha dependency"), () -> output);
        assertTrue(output.contains("greeting=hello world"), () -> output);
        assertTrue(output.contains("shout=HELLO WORLD"), () -> output);
        assertTrue(output.contains("args=one,two"), () -> output);
        assertTrue(output.contains("loader=io.micronaut.runner.RunnerClassLoader"),
                () -> "the application was not loaded by the runner class loader:\n" + output);
    }

    /**
     * {@code PRESERVE} copies each dependency with its entries left compressed, and the result still runs.
     * The same build checks that {@code addExports} and {@code addOpens} reach the manifest unchanged.
     *
     * @param directory a fresh project directory
     * @throws IOException          if the fixture cannot be written or the archive cannot be read
     * @throws InterruptedException if the forked application is interrupted
     */
    @Test
    void preserveKeepsDependenciesCompressed(@TempDir Path directory) throws IOException, InterruptedException {
        writeFixture(directory, """
                micronautRunnerJar {
                    compression = 'PRESERVE'
                    addExports.add('java.base/sun.nio.ch')
                    addOpens.add('java.base/java.lang')
                }
                """, "");

        BuildResult result = build(directory, "micronautRunnerJar");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(result, RUNNER_JAR_TASK));

        Path archive = directory.resolve(DEFAULT_ARCHIVE);
        Attributes manifest = manifestOf(archive);
        assertEquals("java.base/sun.nio.ch", manifest.getValue("Add-Exports"));
        assertEquals("java.base/java.lang", manifest.getValue("Add-Opens"));
        Map<String, Integer> nested = nestedEntryMethods(archive, "MICRONAUT-INF/lib/alpha.jar");
        assertTrue(nested.containsKey("com/example/lib/Greeter.class"), () -> "unexpected nested jar: " + nested);
        assertEquals(ZipEntry.DEFLATED, nested.get("com/example/lib/Greeter.class"),
                () -> "PRESERVE must leave the dependency's entries as they were: " + nested);

        runJarSuccessfully(archive);
    }

    /**
     * The archive is named as an archive task names it: {@code base} moves and renames it, and the task's own
     * classifier completes the name.
     *
     * @param directory a fresh project directory
     * @throws IOException          if the fixture cannot be written
     * @throws InterruptedException if the forked application is interrupted
     */
    @Test
    void theArchiveFollowsTheBaseNamingConventions(@TempDir Path directory) throws IOException, InterruptedException {
        writeFixture(directory, """
                base {
                    archivesName = 'renamed'
                    libsDirectory = layout.buildDirectory.dir('dist')
                }
                tasks.named('micronautRunnerJar') {
                    archiveClassifier = 'named'
                }
                """, "");

        BuildResult result = build(directory, "micronautRunnerJar");

        assertEquals(TaskOutcome.SUCCESS, outcomeOf(result, RUNNER_JAR_TASK));
        Path archive = directory.resolve("build/dist/renamed-" + PROJECT_VERSION + "-named.jar");
        assertTrue(Files.isRegularFile(archive), () -> "no archive at " + archive + ":\n" + result.getOutput());
        assertFalse(Files.exists(directory.resolve(DEFAULT_ARCHIVE)),
                () -> "the archive was also written under the default name:\n" + result.getOutput());
        runJarSuccessfully(archive);
    }

    /**
     * {@code enabled = false} skips the task, even as part of {@code assemble}, and writes nothing.
     *
     * @param directory a fresh project directory
     * @throws IOException if the fixture cannot be written
     */
    @Test
    void theKillSwitchSkipsTheTask(@TempDir Path directory) throws IOException {
        writeFixture(directory, """
                micronautRunner {
                    enabled = false
                }
                """, "");

        BuildResult result = build(directory, "assemble");

        assertEquals(TaskOutcome.SKIPPED, outcomeOf(result, RUNNER_JAR_TASK));
        assertFalse(Files.exists(directory.resolve(DEFAULT_ARCHIVE)),
                () -> "a disabled plugin wrote the archive:\n" + result.getOutput());
    }

    /**
     * An unusable compression value fails the build with the packaging library's message, which names the
     * value and every supported alternative, rather than with an enum constant error.
     *
     * @param directory a fresh project directory
     * @throws IOException if the fixture cannot be written
     */
    @Test
    void invalidCompressionFailsWithAClearMessage(@TempDir Path directory) throws IOException {
        writeFixture(directory, """
                micronautRunnerJar {
                    compression = 'SQUEEZE'
                }
                """, "");

        String output = buildAndFail(directory, "micronautRunnerJar").getOutput();

        // Compression.parse's message, which lists every constant of the packaging library under test.
        assertTrue(output.contains("Unknown compression 'SQUEEZE'. Supported values are STORED, PRESERVE."),
                () -> "the failure does not name the bad value and the alternatives:\n" + output);
    }

    /**
     * The {@code jar} task's manifest configuration reaches the archive, main and per-package attributes
     * alike, without the thin JAR being built: {@code micronautRunnerJar} reads the configuration, not the
     * archive the {@code jar} task writes.
     *
     * @param directory a fresh project directory
     * @throws IOException          if the fixture cannot be written or the archive cannot be read
     * @throws InterruptedException if the forked application is interrupted
     */
    @Test
    void theJarTaskManifestReachesTheArchiveWithoutRunningJar(@TempDir Path directory)
            throws IOException, InterruptedException {
        writeFixture(directory, JAR_MANIFEST, "");
        write(directory.resolve("src/main/java/com/example/App.java"), PACKAGE_SOURCE);

        BuildResult result = build(directory, "micronautRunnerJar");

        assertEquals(TaskOutcome.SUCCESS, outcomeOf(result, RUNNER_JAR_TASK));
        assertNull(result.task(":jar"), () -> "micronautRunnerJar built the thin JAR:\n" + result.getOutput());
        Path archive = directory.resolve(DEFAULT_ARCHIVE);
        assertInheritedMainAttributes(archive);

        String output = runJarSuccessfully(archive);
        assertTrue(output.contains("impl=pkg-9"), () -> "the package section was lost:\n" + output);
        assertTrue(output.contains("sealed=true"), () -> "the package is not sealed:\n" + output);

        String dryRun = build(directory, "--dry-run", "micronautRunnerJar").getOutput();
        assertTrue(dryRun.lines().anyMatch(line -> line.startsWith(RUNNER_JAR_TASK + " ")), dryRun);
        assertFalse(dryRun.lines().anyMatch(line -> line.startsWith(":jar ")),
                () -> "micronautRunnerJar depends on the thin JAR:\n" + dryRun);
    }

    /**
     * A build that disables the thin JAR, as some fat-JAR builds do, still packages the archive with the
     * {@code jar} task's manifest attributes, including one whose value is a provider.
     *
     * @param directory a fresh project directory
     * @throws IOException if the fixture cannot be written or the archive cannot be read
     */
    @Test
    void packagesWhenTheJarTaskIsDisabled(@TempDir Path directory) throws IOException {
        writeFixture(directory, JAR_MANIFEST + """
                jar {
                    enabled = false
                    manifest {
                        attributes('Implementation-Vendor': providers.provider { 'lazy' })
                    }
                }
                """, "");

        BuildResult result = build(directory, "micronautRunnerJar");

        assertEquals(TaskOutcome.SUCCESS, outcomeOf(result, RUNNER_JAR_TASK));
        Path archive = directory.resolve(DEFAULT_ARCHIVE);
        assertInheritedMainAttributes(archive);
        assertEquals("lazy", manifestOf(archive).getValue("Implementation-Vendor"));
    }

    /**
     * {@code applicationJar} is an explicit override: set to the thin JAR, it takes the manifest from that
     * archive, so an attribute that only exists once the {@code jar} task has run still reaches the archive.
     *
     * @param directory a fresh project directory
     * @throws IOException if the fixture cannot be written or the archive cannot be read
     */
    @Test
    void anExplicitApplicationJarSuppliesTheManifest(@TempDir Path directory) throws IOException {
        writeFixture(directory, """
                micronautRunnerJar {
                    applicationJar = tasks.named('jar').flatMap { it.archiveFile }
                }
                jar {
                    doFirst {
                        manifest.attributes('Implementation-Vendor': 'from-doFirst')
                    }
                }
                """, "");

        BuildResult result = build(directory, "micronautRunnerJar");

        assertEquals(TaskOutcome.SUCCESS, outcomeOf(result, ":jar"));
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(result, RUNNER_JAR_TASK));
        assertEquals("from-doFirst",
                manifestOf(directory.resolve(DEFAULT_ARCHIVE)).getValue("Implementation-Vendor"));
    }

    private static void assertInheritedMainAttributes(Path archive) throws IOException {
        Attributes manifest = manifestOf(archive);
        assertEquals("app", manifest.getValue("Implementation-Title"));
        assertEquals("1.2.3", manifest.getValue("Implementation-Version"));
        assertEquals("Example", manifest.getValue("Specification-Vendor"));
    }
}
