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
package io.micronaut.runner.buildlogic;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRepositoryStagingTest {
    @TempDir
    Path projectDir;

    @Test
    void unchangedPublicationIsUpToDateAndByteIdentical() throws Exception {
        writeFixture();

        BuildResult first = run("publishToTestRepo");
        Map<String, String> initialHashes = repositoryHashes(projectDir);
        BuildResult second = run("publishToTestRepo");

        assertEquals(TaskOutcome.SUCCESS, outcome(first, ":publishLocalMavenPublicationToTestRepoRepository"));
        assertEquals(TaskOutcome.UP_TO_DATE, outcome(second, ":publishLocalMavenPublicationToTestRepoRepository"));
        assertEquals(TaskOutcome.UP_TO_DATE, outcome(second, ":publishToTestRepo"));
        assertEquals(initialHashes, repositoryHashes(projectDir));
    }

    @Test
    void lateGradlePluginMarkerPublicationsAreStagedBeforeAggregation() throws Exception {
        writeGradlePluginFixture(projectDir);

        BuildResult result = run("publishToTestRepo");

        assertEquals(TaskOutcome.SUCCESS,
                outcome(result, ":publishLocalPluginMavenPublicationToTestRepoRepository"));
        assertEquals(TaskOutcome.SUCCESS,
                outcome(result, ":publishLocalSamplePluginMarkerMavenPublicationToTestRepoRepository"));
        assertTrue(Files.isRegularFile(projectDir.resolve(
                "build/test-repo/example/sample/example.sample.gradle.plugin/1.0-DUMMY/"
                        + "example.sample.gradle.plugin-1.0-DUMMY.pom")));
        assertTrue(Files.isRegularFile(projectDir.resolve(
                "build/test-repo/example/staging-fixture/1.0-DUMMY/staging-fixture-1.0-DUMMY.jar")));
    }

    @Test
    void sourceAndCoordinateChangesInvalidateAndRemovedPublicationsLeaveNoStaleFiles() throws Exception {
        writeFixture();
        run("publishToTestRepo");
        Path initialJar = repositoryJar("1.0-DUMMY");
        String initialHash = hash(initialJar);

        Files.writeString(projectDir.resolve("src/main/java/example/Thing.java"),
                "package example; public final class Thing { public static final String VALUE = \"changed\"; }\n");
        BuildResult artifactChange = run("publishToTestRepo");

        assertEquals(TaskOutcome.SUCCESS,
                outcome(artifactChange, ":publishLocalMavenPublicationToTestRepoRepository"));
        assertNotEquals(initialHash, hash(initialJar));

        Files.writeString(projectDir.resolve("settings.gradle"), "\ninclude 'dependency'\n",
                java.nio.file.StandardOpenOption.APPEND);
        Path dependencyBuild = projectDir.resolve("dependency/build.gradle");
        Files.createDirectories(dependencyBuild.getParent());
        Files.writeString(dependencyBuild, "plugins { id 'java-library' }\ngroup = 'example'\nversion = '1.0-SNAPSHOT'\n");
        Files.writeString(projectDir.resolve("build.gradle"), "\ndependencies { api project(':dependency') }\n",
                java.nio.file.StandardOpenOption.APPEND);
        BuildResult metadataChange = run("publishToTestRepo");
        assertEquals(TaskOutcome.SUCCESS,
                outcome(metadataChange, ":publishLocalMavenPublicationToTestRepoRepository"));
        assertTrue(Files.readString(repositoryPom("1.0-DUMMY")).contains("<artifactId>dependency</artifactId>"));

        run("publishToTestRepo", "-PfixtureVersion=2.0-SNAPSHOT");
        assertFalse(Files.exists(initialJar));
        assertTrue(Files.isRegularFile(repositoryJar("2.0-DUMMY")));

        run("publishToTestRepo", "-PskipPublication");
        try (Stream<Path> files = Files.walk(projectDir.resolve("build/test-repo"))) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    private void writeFixture() throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'staging-fixture'\n");
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'java-library'
                    id 'io.micronaut.build.internal.runner-test-repo'
                }

                group = 'example'
                version = providers.gradleProperty('fixtureVersion').getOrElse('1.0-SNAPSHOT')

                if (!providers.gradleProperty('skipPublication').isPresent()) {
                    publishing {
                        publications {
                            maven(MavenPublication) {
                                from components.java
                            }
                        }
                    }
                }
                """);
        Path source = projectDir.resolve("src/main/java/example/Thing.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example; public final class Thing {}\n");
    }

    private static void writeGradlePluginFixture(Path fixtureDir) throws IOException {
        Files.createDirectories(fixtureDir);
        Files.writeString(fixtureDir.resolve("settings.gradle"), "rootProject.name = 'staging-fixture'\n");
        Files.writeString(fixtureDir.resolve("build.gradle"), """
                plugins {
                    id 'java-gradle-plugin'
                    id 'io.micronaut.build.internal.runner-test-repo'
                }

                group = 'example'
                version = '1.0-SNAPSHOT'

                gradlePlugin {
                    plugins {
                        sample {
                            id = 'example.sample'
                            implementationClass = 'example.SamplePlugin'
                        }
                    }
                }
                """);
        Path source = fixtureDir.resolve("src/main/java/example/SamplePlugin.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;
                import org.gradle.api.Plugin;
                import org.gradle.api.Project;
                public final class SamplePlugin implements Plugin<Project> {
                    @Override public void apply(Project project) { }
                }
                """);
    }

    private BuildResult run(String... tasks) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(tasks)
                .withPluginClasspath()
                .build();
    }

    private static TaskOutcome outcome(BuildResult result, String taskPath) {
        assertNotNull(result.task(taskPath), () -> "Missing task " + taskPath + " in:\n" + result.getOutput());
        return result.task(taskPath).getOutcome();
    }

    private Path repositoryJar(String version) {
        return repositoryArtifact(projectDir, version, "jar");
    }

    private Path repositoryPom(String version) {
        return repositoryArtifact(projectDir, version, "pom");
    }

    private static Path repositoryArtifact(Path fixtureDir, String version, String extension) {
        return fixtureDir.resolve("build/test-repo/example/staging-fixture/" + version
                + "/staging-fixture-" + version + "." + extension);
    }

    private static String hash(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static Map<String, String> repositoryHashes(Path fixtureDir)
            throws IOException, NoSuchAlgorithmException {
        Path repository = fixtureDir.resolve("build/test-repo");
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> files = Files.walk(repository)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
                hashes.put(repository.relativize(file).toString(), HexFormat.of().formatHex(digest));
            }
        }
        return hashes;
    }
}
