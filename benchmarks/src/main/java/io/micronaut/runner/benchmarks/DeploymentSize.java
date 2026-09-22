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
package io.micronaut.runner.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The exact logical byte size of the regular files required to deploy one benchmark variant.
 *
 * <p>Each normalized deployed path is counted once and attributed to the first component that names it.
 * Directory inputs are traversed recursively without following symbolic links. Symbolic links themselves are
 * not regular files and are not counted. Distinct paths are counted separately even when the filesystem
 * implements them as hard links, because they are distinct paths in the deployment layout.</p>
 *
 * @param components component byte totals in declaration order
 * @param totalBytes the sum of all component byte totals
 */
record DeploymentSize(List<Component> components, long totalBytes) {

    static final String BOUNDARY = "required regular files";
    static final String UNIT = "bytes";
    static final String SYMLINK_POLICY = "symbolic links are not followed or counted";
    static final String HARD_LINK_POLICY = "distinct deployed paths are counted separately";

    DeploymentSize {
        components = List.copyOf(components);
        long componentTotal = components.stream().mapToLong(Component::bytes).sum();
        if (componentTotal != totalBytes) {
            throw new IllegalArgumentException("component bytes " + componentTotal
                    + " do not reconcile with total bytes " + totalBytes);
        }
    }

    /**
     * Measures deployment inputs, de-duplicating repeated paths across all components.
     *
     * @param inputs ordered deployment components
     * @return reconciled component and total byte counts
     * @throws IOException if an input is missing or cannot be read
     */
    static DeploymentSize measure(Input... inputs) throws IOException {
        Set<Path> counted = new LinkedHashSet<>();
        Map<String, Long> bytesByComponent = new LinkedHashMap<>();
        for (Input input : inputs) {
            long bytes = bytesByComponent.getOrDefault(input.name(), 0L);
            for (Path root : input.paths()) {
                Path normalized = root.toAbsolutePath().normalize();
                if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("deployment input does not exist: " + normalized);
                }
                if (Files.isSymbolicLink(normalized)) {
                    continue;
                }
                if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = addIfDistinct(normalized, counted, bytes);
                } else if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    List<Path> files;
                    try (var stream = Files.walk(normalized)) {
                        files = stream
                                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                                .sorted()
                                .toList();
                    }
                    for (Path file : files) {
                        bytes = addIfDistinct(file.toAbsolutePath().normalize(), counted, bytes);
                    }
                }
            }
            bytesByComponent.put(input.name(), bytes);
        }
        List<Component> components = new ArrayList<>(bytesByComponent.size());
        long total = 0;
        for (Map.Entry<String, Long> entry : bytesByComponent.entrySet()) {
            components.add(new Component(entry.getKey(), entry.getValue()));
            total = Math.addExact(total, entry.getValue());
        }
        return new DeploymentSize(components, total);
    }

    static Input input(String name, Path path) {
        return new Input(name, List.of(path));
    }

    static Input input(String name, List<Path> paths) {
        return new Input(name, paths);
    }

    private static long addIfDistinct(Path file, Set<Path> counted, long bytes) throws IOException {
        return counted.add(file) ? Math.addExact(bytes, Files.size(file)) : bytes;
    }

    /** A named part of a complete deployment. */
    record Component(String name, long bytes) {

        Component {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("component name must not be blank");
            }
            if (bytes < 0) {
                throw new IllegalArgumentException("component bytes must not be negative");
            }
        }
    }

    /** One named group of files or directory trees required by a deployment. */
    record Input(String name, List<Path> paths) {

        Input {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("input name must not be blank");
            }
            paths = List.copyOf(paths);
        }
    }
}
