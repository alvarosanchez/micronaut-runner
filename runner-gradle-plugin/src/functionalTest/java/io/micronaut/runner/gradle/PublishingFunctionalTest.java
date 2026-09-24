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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive leaves its project the ways an archive does: through the {@code micronautRunnerElements}
 * configuration to another project of the build, and through a {@code maven-publish} publication with the
 * documented recipe. A Java consumer of the project still gets the thin JAR.
 *
 * <p>The fixture has two projects: {@code app} applies the plugin and publishes to a file repository under its
 * build directory, and {@code consumer} applies {@code java}.</p>
 */
class PublishingFunctionalTest extends AbstractFunctionalTest {

    /** The application project, with the publishing recipe of the quick start. */
    private static final String APP_EXTRA = """
            apply plugin: 'maven-publish'

            base {
                archivesName = 'demo'
            }

            publishing {
                publications {
                    maven(MavenPublication) {
                        artifactId = 'demo'
                        from components.java
                        artifact(tasks.named('micronautRunnerJar').flatMap { it.archiveFile }) {
                            classifier = 'all'
                            builtBy tasks.named('micronautRunnerJar')
                        }
                    }
                }
                repositories {
                    maven {
                        url = layout.buildDirectory.dir('repo')
                    }
                }
            }
            """;

    /** A Java project that takes the archive by configuration name and the application as a library. */
    private static final String CONSUMER = """
            plugins {
                id 'java'
            }

            def runnerScope = configurations.dependencyScope('runner')
            def runnerFiles = configurations.resolvable('runnerFiles') {
                extendsFrom(runnerScope.get())
            }

            dependencies {
                runner project(path: ':app', configuration: 'micronautRunnerElements')
                implementation project(':app')
            }

            tasks.register('resolveRunner') {
                def files = runnerFiles
                inputs.files(files)
                doLast {
                    files.get().files.each { println "runner: ${it.name}" }
                }
            }

            tasks.register('resolveRuntimeClasspath') {
                def files = configurations.runtimeClasspath
                inputs.files(files)
                doLast {
                    files.files.each { println "runtime: ${it.name}" }
                }
            }
            """;

    /** The name of the runner jar the application project writes. */
    private static final String RUNNER_JAR = PROJECT_NAME + "-" + PROJECT_VERSION + "-all.jar";

    /**
     * Another project takes the archive by configuration name and gets it built on demand, a Java consumer
     * of the same project gets the thin JAR alone, and the publication carries the archive under the
     * {@code all} classifier.
     *
     * @param root a fresh directory for the two-project build
     * @throws IOException if the fixture cannot be written or the archive cannot be read
     */
    @Test
    void theArchiveReachesAConsumerAndAPublication(@TempDir Path root) throws IOException {
        writeSettings(root, "include 'app', 'consumer'");
        Path app = root.resolve("app");
        write(app.resolve("build.gradle"), buildScript("id '" + PLUGIN_ID + "'", APP_EXTRA));
        writeApplication(app);
        write(root.resolve("consumer/build.gradle"), CONSUMER);

        BuildResult consumed = build(root, ":consumer:resolveRunner");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(consumed, ":app" + RUNNER_JAR_TASK),
                () -> "resolving micronautRunnerElements did not build the archive:\n" + consumed.getOutput());
        assertEquals(List.of("runner: " + RUNNER_JAR), printed(consumed, "runner: "),
                () -> "micronautRunnerElements does not carry the archive alone:\n" + consumed.getOutput());
        assertTrue(Files.isRegularFile(app.resolve("build/libs/" + RUNNER_JAR)), consumed::getOutput);

        BuildResult library = build(root, ":consumer:resolveRuntimeClasspath");
        List<String> runtime = printed(library, "runtime: ");
        assertTrue(runtime.contains("runtime: " + PROJECT_NAME + "-" + PROJECT_VERSION + ".jar"),
                () -> "the Java consumer did not get the thin JAR: " + runtime);
        assertFalse(runtime.contains("runtime: " + RUNNER_JAR),
                () -> "the Java consumer selected the runner jar: " + runtime);
        assertNull(library.task(":app" + RUNNER_JAR_TASK),
                () -> "the Java consumer built the runner jar:\n" + library.getOutput());

        BuildResult published = build(root, ":app:publish");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(published, ":app:publish"));
        Path artifact = app.resolve("build/repo/com/example/" + PROJECT_NAME + "/" + PROJECT_VERSION + "/"
                + RUNNER_JAR);
        assertTrue(Files.isRegularFile(artifact), () -> "nothing published at " + artifact + ":\n"
                + published.getOutput());
        assertTrue(entryNames(artifact).contains("MICRONAUT-INF/index.bin"),
                () -> "the published archive is not a runner jar: " + artifact);
    }

    private static List<String> printed(BuildResult result, String prefix) {
        return result.getOutput().lines().filter(line -> line.startsWith(prefix)).toList();
    }
}
