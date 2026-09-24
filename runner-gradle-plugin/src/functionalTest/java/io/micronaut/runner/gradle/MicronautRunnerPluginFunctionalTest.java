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

    private static final String MODULE_ACCESS_SOURCE = """
            package com.example;

            import java.nio.ByteBuffer;

            public final class App {
                public static void main(String[] args) {
                    long address = ((sun.nio.ch.DirectBuffer) ByteBuffer.allocateDirect(1)).address();
                    if (address == 0) {
                        throw new AssertionError("direct buffer has no address");
                    }
                    System.out.println("MODULE ACCESS OK");
                    System.out.println("RESULT OK");
                }
            }
            """;

    /**
     * The plugin registers the task, describes it, and puts it in the build group — the three things a user
     * sees before ever running it.
     *
     * @param directory a fresh project directory
     * @throws IOException if the fixture cannot be written
     */
    @Test
    void registersTheRunnerJarTask(@TempDir Path directory) throws IOException {
        writeFixture(directory);

        String output = build(directory, "help", "--task", "micronautRunnerJar").getOutput();

        assertTrue(output.contains(RUNNER_JAR_TASK), () -> "the task was never registered:\n" + output);
        // Named as a string on purpose: this suite tests the published plugin, not the classes this
        // module happens to have compiled, so nothing here links against them.
        assertTrue(output.contains("io.micronaut.runner.gradle.MicronautRunnerJar"),
                () -> "the task has the wrong type:\n" + output);
        assertTrue(output.contains("Packages the application and its dependencies as a runner jar"),
                () -> "the task has no description:\n" + output);
        assertTrue(output.contains("build"), () -> "the task is not in the build group:\n" + output);
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
                }
                """, "");

        BuildResult result = build(directory, "micronautRunnerJar");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(result, RUNNER_JAR_TASK));

        Path archive = directory.resolve(DEFAULT_ARCHIVE);
        Map<String, Integer> nested = nestedEntryMethods(archive, "MICRONAUT-INF/lib/alpha.jar");
        assertTrue(nested.containsKey("com/example/lib/Greeter.class"), () -> "unexpected nested jar: " + nested);
        assertEquals(ZipEntry.DEFLATED, nested.get("com/example/lib/Greeter.class"),
                () -> "PRESERVE must leave the dependency's entries as they were: " + nested);

        runJarSuccessfully(archive);
    }

    /**
     * An unusable compression value fails the build with a message that names the value and the alternatives,
     * rather than with an enum constant error from somewhere inside the packaging library.
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

        assertTrue(output.contains("Unknown compression 'SQUEEZE'"),
                () -> "the failure does not name the bad value:\n" + output);
        assertTrue(output.contains("Supported values are STORED and PRESERVE"),
                () -> "the failure does not name the alternatives:\n" + output);
    }

    @Test
    void configuredManifestExportGrantsAccessInAFreshJvm(@TempDir Path directory)
            throws IOException, InterruptedException {
        writeFixture(directory, """
                tasks.withType(JavaCompile).configureEach {
                    options.compilerArgs.addAll(['--add-exports', 'java.base/sun.nio.ch=ALL-UNNAMED'])
                }
                micronautRunnerJar {
                    addExports.add('java.base/sun.nio.ch')
                }
                """, "");
        write(directory.resolve("src/main/java/com/example/App.java"), MODULE_ACCESS_SOURCE);

        BuildResult result = build(directory, "micronautRunnerJar");

        assertEquals(TaskOutcome.SUCCESS, outcomeOf(result, RUNNER_JAR_TASK));
        Path archive = directory.resolve(DEFAULT_ARCHIVE);
        assertEquals("java.base/sun.nio.ch", manifestOf(archive).getValue("Add-Exports"));
        String output = runJarSuccessfully(archive);
        assertTrue(output.contains("MODULE ACCESS OK"), output);
    }

    @Test
    void commandLineManifestSyntaxFailsPackagingWithACorrection(@TempDir Path directory) throws IOException {
        writeFixture(directory, """
                micronautRunnerJar {
                    addExports.add('java.base/sun.nio.ch=ALL-UNNAMED')
                }
                """, "");

        String output = buildAndFail(directory, "micronautRunnerJar").getOutput();

        assertTrue(output.contains("addExports entry 'java.base/sun.nio.ch=ALL-UNNAMED'"), output);
        assertTrue(output.contains("Use 'java.base/sun.nio.ch' in the JAR manifest"), output);
        assertTrue(output.contains("--add-exports java.base/sun.nio.ch=ALL-UNNAMED"), output);
    }
}
