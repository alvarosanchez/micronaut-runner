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
import java.util.Map;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Kotlin DSL build writes what a build with the Micronaut Gradle plugin's own Runner support will write:
 * {@code micronaut { runner { } }}, with type-safe accessors for it, for {@code micronautRunner} and for
 * {@code tasks.micronautRunnerJar}.
 *
 * <p>The {@code micronaut} extension comes from {@code micronaut-stub}, a precompiled script plugin in the
 * fixture's {@code buildSrc} that creates it as {@code MicronautBasePlugin} does. It is applied after the
 * Runner plugin, so the alias is added when it appears rather than when the Runner plugin is applied.</p>
 */
class KotlinDslFunctionalTest extends AbstractFunctionalTest {

    /** The build file: the fixture's Groovy build, in the Kotlin DSL. */
    private static final String BUILD = """
            plugins {
                application
                id("io.micronaut.runner.standalone")
                id("micronaut-stub")
            }

            group = "com.example"
            version = "@version@"

            application {
                mainClass = "@mainClass@"
            }

            dependencies {
                implementation(files("libs/beta.jar", "libs/alpha.jar"))
            }

            tasks.withType<Jar>().configureEach {
                isPreserveFileTimestamps = false
                isReproducibleFileOrder = true
            }

            micronaut { runner { compression = "PRESERVE" } }
            micronautRunner { manifestAttributes.put("X-Dsl", "kotlin") }
            require(micronautRunner.compression.get() == "PRESERVE")
            tasks.micronautRunnerJar { archiveClassifier = "executable" }
            """;

    /**
     * The settings reach the archive through both names of the extension and the task accessor, and the
     * build is reused from the configuration cache.
     *
     * @param directory a fresh project directory
     * @throws IOException          if the fixture cannot be written or the archive cannot be read
     * @throws InterruptedException if the forked application is interrupted
     */
    @Test
    void theKotlinDslConfiguresThePluginThroughTheUpstreamBlock(@TempDir Path directory)
            throws IOException, InterruptedException {
        writeSettings(directory, "");
        write(directory.resolve("build.gradle.kts"), BUILD
                .replace("@version@", PROJECT_VERSION)
                .replace("@mainClass@", MAIN_CLASS));
        writeApplication(directory);
        writeMicronautStubs(directory);

        BuildResult stored = build(directory, "micronautRunnerJar", "--configuration-cache");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(stored, RUNNER_JAR_TASK));

        Path archive = directory.resolve("build/libs/" + PROJECT_NAME + "-" + PROJECT_VERSION + "-executable.jar");
        assertTrue(Files.isRegularFile(archive), () -> "no archive at " + archive + ":\n" + stored.getOutput());
        assertEquals("kotlin", manifestOf(archive).getValue("X-Dsl"),
                "the attribute set through micronautRunner did not reach the archive");
        Map<String, Integer> nested = nestedEntryMethods(archive, "MICRONAUT-INF/lib/alpha.jar");
        assertEquals(ZipEntry.DEFLATED, nested.get("com/example/lib/Greeter.class"),
                () -> "the compression set through micronaut.runner did not reach the archive: " + nested);
        runJarSuccessfully(archive);

        BuildResult reused = build(directory, "micronautRunnerJar", "--configuration-cache");
        assertTrue(reused.getOutput().contains("Configuration cache entry reused"),
                () -> "the second run did not reuse the configuration cache entry:\n" + reused.getOutput());
        assertEquals(TaskOutcome.UP_TO_DATE, outcomeOf(reused, RUNNER_JAR_TASK),
                () -> "the task did not stay up to date:\n" + reused.getOutput());
    }
}
