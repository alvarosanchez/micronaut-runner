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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

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
    void relocatedConcurrentBuildsReuseCompleteCachedRepositories() throws Exception {
        Path origin = projectDir.resolve("origin");
        Path relocatedOne = projectDir.resolve("relocated-one");
        Path relocatedTwo = projectDir.resolve("relocated-two");
        Path gradleHome = System.getenv("GRADLE_USER_HOME") == null
                ? Path.of(System.getProperty("user.home"), ".gradle")
                : Path.of(System.getenv("GRADLE_USER_HOME"));
        writeFixture(origin);
        writeFixture(relocatedOne);
        writeFixture(relocatedTwo);
        run(origin, "publishToTestRepo", "--build-cache", "-g", gradleHome.toString());
        Map<String, String> expected = repositoryHashes(origin);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<BuildResult> firstBuild = () -> run(relocatedOne, "publishToTestRepo", "--build-cache",
                    "-g", gradleHome.toString());
            Callable<BuildResult> secondBuild = () -> run(relocatedTwo, "publishToTestRepo", "--build-cache",
                    "-g", gradleHome.toString());
            Future<BuildResult> first = executor.submit(firstBuild);
            Future<BuildResult> second = executor.submit(secondBuild);

            assertEquals(TaskOutcome.FROM_CACHE,
                    outcome(first.get(), ":publishLocalMavenPublicationToTestRepoRepository"));
            assertEquals(TaskOutcome.FROM_CACHE,
                    outcome(second.get(), ":publishLocalMavenPublicationToTestRepoRepository"));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(expected, repositoryHashes(relocatedOne));
        assertEquals(expected, repositoryHashes(relocatedTwo));
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
    void mavenPluginDescriptorPatchingIsIncrementalAndChecksumStable() throws Exception {
        writeMavenPluginFixture(projectDir);

        BuildResult first = run("publishToTestRepo");
        Path jar = onlyPublishedJar(projectDir);
        Map<String, String> initialHashes = repositoryHashes(projectDir);
        assertEquals(TaskOutcome.SUCCESS, outcome(first, ":patchTestRepoDescriptor"));
        assertDescriptorVersion(jar, "META-INF/maven/plugin.xml", "1.0-DUMMY");
        assertDescriptorVersion(jar, "META-INF/maven/example/staging-fixture/plugin-help.xml", "1.0-DUMMY");
        assertChecksumsMatch(jar);

        BuildResult second = run("publishToTestRepo");

        assertEquals(TaskOutcome.UP_TO_DATE, outcome(second, ":patchTestRepoDescriptor"));
        assertEquals(TaskOutcome.UP_TO_DATE,
                outcome(second, ":publishLocalMavenPublicationToTestRepoRepository"));
        assertEquals(TaskOutcome.UP_TO_DATE, outcome(second, ":publishToTestRepo"));
        assertEquals(initialHashes, repositoryHashes(projectDir));
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
        writeFixture(projectDir);
    }

    private static void writeFixture(Path fixtureDir) throws IOException {
        Files.createDirectories(fixtureDir);
        Files.writeString(fixtureDir.resolve("settings.gradle"), "rootProject.name = 'staging-fixture'\n");
        Files.writeString(fixtureDir.resolve("build.gradle"), """
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
        Path source = fixtureDir.resolve("src/main/java/example/Thing.java");
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

    private static void writeMavenPluginFixture(Path fixtureDir) throws IOException {
        Files.createDirectories(fixtureDir.resolve("gradle"));
        Files.writeString(fixtureDir.resolve("settings.gradle"), "rootProject.name = 'staging-fixture'\n");
        Files.writeString(fixtureDir.resolve("gradle.properties"), """
                projectVersion=1.0-SNAPSHOT
                projectGroup=example
                title=Staging Fixture
                projectDesc=Test fixture
                projectUrl=https://example.com
                githubSlug=example/staging-fixture
                developers=Example Developer
                """);
        Files.writeString(fixtureDir.resolve("gradle/libs.versions.toml"), """
                [versions]
                junit = "6.1.3"
                maven = "3.9.16"
                maven-plugin-tools = "3.16.0"

                [libraries]
                junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
                junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
                junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
                maven-plugin-api = { module = "org.apache.maven:maven-plugin-api", version.ref = "maven" }
                maven-core = { module = "org.apache.maven:maven-core", version.ref = "maven" }
                maven-plugin-annotations = { module = "org.apache.maven.plugin-tools:maven-plugin-annotations", version.ref = "maven-plugin-tools" }
                """);
        Files.writeString(fixtureDir.resolve("build.gradle"), """
                plugins {
                    id 'io.micronaut.build.internal.runner-maven-plugin'
                }

                group = 'example'
                version = '1.0-SNAPSHOT'

                repositories {
                    mavenCentral()
                }
                """);
        Path source = fixtureDir.resolve("src/main/java/example/SampleMojo.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;

                import org.apache.maven.plugin.AbstractMojo;
                import org.apache.maven.plugin.MojoExecutionException;
                import org.apache.maven.plugins.annotations.Mojo;

                @Mojo(name = "sample")
                public final class SampleMojo extends AbstractMojo {
                    @Override
                    public void execute() throws MojoExecutionException {
                    }
                }
                """);
    }

    private static Path onlyPublishedJar(Path fixtureDir) throws IOException {
        try (Stream<Path> files = Files.walk(fixtureDir.resolve("build/test-repo"))) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".jar"))
                    .filter(file -> !file.getFileName().toString().endsWith("-sources.jar"))
                    .filter(file -> !file.getFileName().toString().endsWith("-javadoc.jar"))
                    .reduce((first, second) -> {
                        throw new AssertionError("Expected one published jar, found " + first + " and " + second);
                    })
                    .orElseThrow(() -> new AssertionError("No jar was published"));
        }
    }

    private static void assertDescriptorVersion(Path jar, String entry, String expectedVersion) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            assertNotNull(zip.getEntry(entry), () -> "Missing " + entry + " in " + jar);
            String descriptor = new String(zip.getInputStream(zip.getEntry(entry)).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(descriptor.contains("<version>" + expectedVersion + "</version>"),
                    () -> entry + " does not contain version " + expectedVersion);
        }
    }

    private static void assertChecksumsMatch(Path artifact) throws Exception {
        for (Map.Entry<String, String> checksum : Map.of(
                "md5", "MD5", "sha1", "SHA-1", "sha256", "SHA-256", "sha512", "SHA-512").entrySet()) {
            Path sidecar = artifact.resolveSibling(artifact.getFileName() + "." + checksum.getKey());
            String expected = HexFormat.of().formatHex(
                    MessageDigest.getInstance(checksum.getValue()).digest(Files.readAllBytes(artifact)));
            assertEquals(expected, Files.readString(sidecar),
                    () -> sidecar + " does not describe the published artifact bytes");
        }
    }

    private BuildResult run(String... tasks) {
        return run(projectDir, tasks);
    }

    private BuildResult run(Path fixtureDir, String... tasks) {
        return GradleRunner.create()
                .withProjectDir(fixtureDir.toFile())
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
