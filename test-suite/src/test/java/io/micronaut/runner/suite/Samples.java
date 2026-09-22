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
import java.net.URI;
import java.net.ServerSocket;
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
 *   <li>{@code runner.test.mode} - {@code required} in CI and by default, or the explicit developer
 *       opt-out {@code offline}.</li>
 * </ul>
 *
 * <p>Required mode executes against the repositories configured by each sample and fails closed when a
 * dependency cannot be resolved. It deliberately has no separate hostname reachability probe: a proxy,
 * mirror or populated cache can make the configured repository usable even when an unrelated host is not.
 * Offline mode reports every dependency-resolving scenario as skipped while retaining descriptor-only
 * checks.</p>
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

    /** Whether dependency-resolving scenarios are mandatory or explicitly omitted. */
    private static final String MODE = System.getProperty("runner.test.mode");

    private Samples() {
    }

    /**
     * Skips when the build did not hand this suite what it needs. That happens when the tests are run
     * outside Gradle, for instance straight from an IDE that did not pick the system properties up.
     */
    static void assumeTheBuildProvidedItsProperties() {
        requireBuildProperty(REPO != null && !REPO.isBlank(),
                "runner.test.repo is not set; run this suite through Gradle (:test-suite:test)");
        requireBuildProperty(VERSION != null && !VERSION.isBlank(),
                "runner.test.version is not set; run this suite through Gradle (:test-suite:test)");
        requireBuildProperty(System.getProperty("runner.test.samplesDir") != null,
                "runner.test.samplesDir is not set; run this suite through Gradle (:test-suite:test)");
        requireBuildProperty(javaExecutable() != null,
                "no JDK to start the packaged applications with; set runner.test.javaHome");
    }

    private static void requireBuildProperty(boolean available, String message) {
        if (available) {
            return;
        }
        if ("required".equals(MODE)) {
            throw new AssertionError(message);
        }
        Assumptions.abort(message);
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

    /** Executes a dependency-resolving scenario unless the developer explicitly selected offline mode. */
    static void requireIntegrationScenario() {
        if ("offline".equals(MODE)) {
            Assumptions.abort("offline was requested: the sample builds resolve real Micronaut artifacts");
        }
        if (!"required".equals(MODE)) {
            throw new AssertionError("runner.test.mode must be 'required' or 'offline', not '" + MODE + "'");
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
