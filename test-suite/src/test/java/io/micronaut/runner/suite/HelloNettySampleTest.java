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
package io.micronaut.runner.suite;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the {@code hello-netty} sample the way a user would: the real Gradle plugin, resolved by id and
 * version from a repository, packaging a real Micronaut HTTP application, and then {@code java -jar}.
 *
 * <p>The assertion that matters is the last one: a request to the running server answers, and the greeting
 * it answers with names {@code RunnerClassLoader}. That single string proves the archive started, that the
 * launcher installed its own class loader, that bean discovery found a bean inside a nested jar, and that
 * Netty bound a port - none of which a build that merely produced a file would prove.</p>
 *
 * <p>The sample is built in place rather than in a temporary directory because its {@code settings.gradle}
 * reads the version catalog by a relative path. Its outputs are declared as build outputs of the sample,
 * not of this test.</p>
 */
@Timeout(value = 30, unit = TimeUnit.MINUTES)
class HelloNettySampleTest {

    /** The task the Gradle plugin registers. */
    private static final String TASK = ":micronautRunnerJar";

    /** Where that task writes, with the classifier the plugin defaults to. */
    private static final String ARCHIVE = "build/libs/hello-netty-0.1-all.jar";

    /** How long the server is given to come up. Generous: a cold JIT on a loaded CI agent is slow. */
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);

    @BeforeAll
    static void assumeTheSuiteCanRun() {
        Samples.assumeTheBuildProvidedItsProperties();
        Samples.assumeTheNetworkIsAvailable();
    }

    @Test
    void packagesTheSampleAndServesARequestFromTheRunnerJar() throws Exception {
        Path sample = Samples.sample("hello-netty");
        Path archive = sample.resolve(ARCHIVE);
        Files.deleteIfExists(archive);

        BuildResult result = gradle(sample, "clean", TASK);

        assertEquals(TaskOutcome.SUCCESS, result.task(TASK).getOutcome(),
                () -> "the packaging task did not run:\n" + result.getOutput());
        assertTrue(Files.isRegularFile(archive),
                () -> "the plugin did not write " + archive + ":\n" + result.getOutput());
        assertStartsTheApplication(archive);

        int port = Samples.freePort();
        ForkedApplication application = ForkedApplication.start(archive, sample, Map.of(
                "SERVER_PORT", Integer.toString(port)));
        try {
            String body = application.awaitBody(
                    URI.create("http://localhost:" + port + "/hello"), STARTUP_TIMEOUT);
            assertEquals("hello from RunnerClassLoader", body,
                    () -> "the application answered, but not from the runner class loader"
                            + application.describe());
        } finally {
            application.close();
        }
    }

    /** Checks the manifest the JVM will read before anything is started, so a failure names the cause. */
    private static void assertStartsTheApplication(Path archive) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            Manifest manifest = jar.getManifest();
            assertNotNull(manifest, () -> archive + " has no manifest");
            assertEquals("io.micronaut.runner.Launcher", manifest.getMainAttributes().getValue("Main-Class"),
                    () -> archive + " does not start the launcher");
            assertEquals("com.example.Application",
                    manifest.getMainAttributes().getValue("Micronaut-Runner-Start-Class"),
                    () -> archive + " does not name the application's main class");
        }
    }

    private static BuildResult gradle(Path projectDirectory, String... tasks) {
        List<String> arguments = new ArrayList<>(List.of(tasks));
        arguments.add("-Prunner.repo=" + Samples.REPO);
        arguments.add("-Prunner.version=" + Samples.VERSION);
        arguments.add("--stacktrace");

        GradleRunner runner = GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withArguments(arguments)
                .forwardOutput();

        String version = gradleVersion();
        if (version != null) {
            runner = runner.withGradleVersion(version);
        }
        return runner.build();
    }

    /**
     * The Gradle version to build the samples with: the newest entry of the matrix the build passes in
     * {@code runner.test.gradleVersions}, or the distribution running this build when that is empty.
     *
     * <p>The newest rather than the whole matrix, because one run of this test costs a full Micronaut
     * dependency resolution plus a real server start, and what it is checking is the archive, not Gradle
     * compatibility. The version matrix belongs to the Gradle plugin's own functional tests, which
     * configure and run the task without packaging a real application.</p>
     *
     * @return the version, or {@code null} to use the running distribution
     */
    private static String gradleVersion() {
        String[] versions = System.getProperty("runner.test.gradleVersions", "").split(",");
        for (int i = versions.length - 1; i >= 0; i--) {
            String version = versions[i].trim();
            if (!version.isEmpty()) {
                return version;
            }
        }
        return null;
    }
}
