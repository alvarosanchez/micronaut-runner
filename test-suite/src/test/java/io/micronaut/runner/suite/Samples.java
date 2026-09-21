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

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * What every sample build in this suite needs: where the samples are, which repository and version the
 * plugins under test come from, which JDK the packaged applications must be started with, and the two
 * preconditions that decide whether these tests can run at all.
 *
 * <p>All of it arrives as system properties set by the build:</p>
 * <ul>
 *   <li>{@code runner.test.repo} and {@code runner.test.version} - the aggregated local Maven repository
 *       holding the {@code -DUMMY} publications of every module under test, and their version. The samples
 *       resolve the Gradle plugin, the Maven plugin and the launcher from there, so this suite exercises
 *       exactly what a user would resolve from Maven Central.</li>
 *   <li>{@code runner.test.samplesDir} - the {@code samples} directory of this project.</li>
 *   <li>{@code runner.test.javaHome} - the JDK running the build, which is the JDK the packaged
 *       applications are started with.</li>
 *   <li>{@code runner.test.micronautVersion} and {@code runner.test.micronautPlatformVersion} - the
 *       Micronaut versions from the version catalog, handed to the Maven sample, which has no catalog.</li>
 * </ul>
 *
 * <p>These tests resolve real Micronaut artifacts from Maven Central, so they are skipped rather than
 * failed when there is no network. To skip them outright set the environment variable
 * {@code RUNNER_TEST_OFFLINE=true}, which a forked test JVM inherits; the system property
 * {@code runner.test.offline} does the same when the build is configured to forward it.</p>
 */
final class Samples {

    /** The aggregated local Maven repository of {@code -DUMMY} publications, as a URI string. */
    static final String REPO = System.getProperty("runner.test.repo");

    /** The version the modules under test were published under, typically {@code <v>-DUMMY}. */
    static final String VERSION = System.getProperty("runner.test.version");

    /** The Micronaut core version, for the Maven sample, which has no version catalog. */
    static final String MICRONAUT_VERSION = System.getProperty("runner.test.micronautVersion");

    /** The Micronaut platform BOM version, for the Maven sample. */
    static final String MICRONAUT_PLATFORM_VERSION = System.getProperty("runner.test.micronautPlatformVersion");

    /** The host whose reachability stands in for "the artifact repositories are reachable". */
    private static final String CENTRAL_HOST = "repo1.maven.org";

    private static final int CENTRAL_PORT = 443;

    private static final int NETWORK_PROBE_TIMEOUT_MILLIS = 5_000;

    private Samples() {
    }

    /**
     * Skips when the build did not hand this suite what it needs. That happens when the tests are run
     * outside Gradle, for instance straight from an IDE that did not pick the system properties up.
     */
    static void assumeTheBuildProvidedItsProperties() {
        Assumptions.assumeTrue(REPO != null && !REPO.isBlank(),
                "runner.test.repo is not set; run this suite through Gradle (:test-suite:test)");
        Assumptions.assumeTrue(VERSION != null && !VERSION.isBlank(),
                "runner.test.version is not set; run this suite through Gradle (:test-suite:test)");
        Assumptions.assumeTrue(System.getProperty("runner.test.samplesDir") != null,
                "runner.test.samplesDir is not set; run this suite through Gradle (:test-suite:test)");
        Assumptions.assumeTrue(javaExecutable() != null,
                "no JDK to start the packaged applications with; set runner.test.javaHome");
    }

    /**
     * Fails, with the repository's actual contents, when the aggregated local repository does not hold the
     * plugin the samples are about to ask for.
     *
     * <p>Without this the sample build fails several layers down, inside a nested Gradle or Maven
     * invocation, with "plugin was not found in any of the following sources" and a list of repositories
     * that looks entirely correct. That message says nothing about what the repository actually contained,
     * which is the only thing worth knowing.</p>
     *
     * @param artifact the artifact path that must be present, relative to the repository root
     */
    static void requirePublishedArtifact(String artifact) {
        Path repository;
        try {
            repository = Path.of(URI.create(REPO));
        } catch (RuntimeException e) {
            throw new AssertionError("runner.test.repo is not a usable file URI: " + REPO, e);
        }
        Path expected = repository.resolve(artifact);
        if (Files.isRegularFile(expected)) {
            return;
        }
        StringBuilder message = new StringBuilder(512);
        message.append(expected).append(" is missing, so no sample can resolve the plugin.\n")
                .append("The aggregated repository is ").append(repository).append(" and it holds:\n");
        if (!Files.isDirectory(repository)) {
            message.append("  (the directory does not exist)");
        } else {
            try (Stream<Path> tree = Files.walk(repository)) {
                List<String> found = tree.filter(Files::isRegularFile)
                        .map(file -> repository.relativize(file).toString())
                        .sorted()
                        .toList();
                if (found.isEmpty()) {
                    message.append("  (nothing at all)");
                } else {
                    found.forEach(name -> message.append("  ").append(name).append('\n'));
                }
            } catch (IOException e) {
                message.append("  (could not be listed: ").append(e).append(')');
            }
        }
        throw new AssertionError(message.toString());
    }

    /**
     * Skips when Maven Central cannot be reached. Every sample build resolves real Micronaut artifacts, so
     * without a network these tests would fail for a reason that has nothing to do with the runner.
     */
    static void assumeTheNetworkIsAvailable() {
        if (Boolean.getBoolean("runner.test.offline")
                || Boolean.parseBoolean(System.getenv("RUNNER_TEST_OFFLINE"))) {
            Assumptions.abort("offline was requested: the sample builds resolve real Micronaut artifacts");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(CENTRAL_HOST, CENTRAL_PORT), NETWORK_PROBE_TIMEOUT_MILLIS);
        } catch (IOException e) {
            Assumptions.abort("no network: " + CENTRAL_HOST + ":" + CENTRAL_PORT + " is unreachable ("
                    + e + "), and the sample builds resolve real Micronaut artifacts");
        }
    }

    /**
     * Locates one sample project.
     *
     * @param name the directory name under {@code samples}
     * @return the sample's directory
     */
    static Path sample(String name) {
        Path directory = Path.of(System.getProperty("runner.test.samplesDir")).resolve(name);
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("No such sample: " + directory);
        }
        return directory;
    }

    /**
     * The {@code java} of the JDK the build runs on, or {@code null} when there is none.
     *
     * @return the executable, or {@code null}
     */
    static Path javaExecutable() {
        String home = System.getProperty("runner.test.javaHome", System.getProperty("java.home"));
        if (home == null || home.isEmpty()) {
            return null;
        }
        Path candidate = Path.of(home, "bin", "java");
        if (!Files.isExecutable(candidate)) {
            candidate = Path.of(home, "bin", "java.exe");
        }
        return Files.isExecutable(candidate) ? candidate : null;
    }

    /** The home of the JDK the packaged applications are started with. */
    static Path javaHome() {
        return Path.of(System.getProperty("runner.test.javaHome", System.getProperty("java.home")));
    }

    /**
     * Picks a port nothing is listening on, by binding it and letting it go again. There is a window in
     * which something else could take it, which is why the port is never hard-coded: a stale hard-coded
     * port fails every run on a busy machine, this one fails approximately never.
     *
     * @return a port that was free a moment ago
     */
    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not find a free port", e);
        }
    }

    /**
     * Deletes a directory tree if it is there, so that a stale artifact from an earlier run of a module
     * under test can never be picked up. Versions in this suite are fixed ({@code -DUMMY}), so a cache
     * keyed on the version alone would happily serve yesterday's plugin.
     *
     * @param directory the tree to remove
     */
    static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try {
            List<Path> entries = new ArrayList<>();
            try (var stream = Files.walk(directory)) {
                stream.forEach(entries::add);
            }
            for (int i = entries.size() - 1; i >= 0; i--) {
                Files.deleteIfExists(entries.get(i));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + directory, e);
        }
    }
}
