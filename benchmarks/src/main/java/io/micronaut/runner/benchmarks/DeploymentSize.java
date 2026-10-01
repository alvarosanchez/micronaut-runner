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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * The exact logical byte size of the regular files required to deploy one benchmark variant.
 *
 * <p>Each normalized deployed path is counted once and attributed to the first component that names it.
 * Directory inputs are traversed recursively without following symbolic links. Symbolic links themselves are
 * not regular files and are not counted. Distinct paths are counted separately even when the filesystem
 * implements them as hard links, because they are distinct paths in the deployment layout.</p>
 *
 * <p>Every counted file also contributes its gzip length: the sum of per-file gzip -6 lengths, an approximation
 * of image-layer transfer size (tar headers and cross-file dictionary effects excluded). Gzip lengths are
 * computed only by {@link #measure(Input...)} and {@link #withFile(String, Path)}, which run while variants are
 * prepared and never while launches are timed.</p>
 *
 * @param components component byte totals in declaration order
 */
record DeploymentSize(List<Component> components) {

    static final String BOUNDARY = "required regular files";
    static final String UNIT = "bytes";
    static final String SYMLINK_POLICY = "symbolic links are not followed or counted";
    static final String HARD_LINK_POLICY = "distinct deployed paths are counted separately";

    /** The read buffer of the gzip pass. */
    private static final int GZIP_READ_BUFFER = 64 * 1024;

    DeploymentSize {
        components = List.copyOf(components);
    }

    /**
     * Measures deployment inputs, de-duplicating repeated paths across all components.
     *
     * @param inputs ordered deployment components
     * @return the component byte counts
     * @throws IOException if an input is missing or cannot be read
     */
    static DeploymentSize measure(Input... inputs) throws IOException {
        Set<Path> counted = new LinkedHashSet<>();
        Map<String, Component> byName = new LinkedHashMap<>();
        for (Input input : inputs) {
            Component component = byName.getOrDefault(input.name(), new Component(input.name(), 0, 0));
            for (Path root : input.paths()) {
                Path normalized = root.toAbsolutePath().normalize();
                if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("deployment input does not exist: " + normalized);
                }
                if (Files.isSymbolicLink(normalized)) {
                    continue;
                }
                if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    component = addIfDistinct(normalized, counted, component);
                } else if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    List<Path> files;
                    try (var stream = Files.walk(normalized)) {
                        files = stream
                                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                                .sorted()
                                .toList();
                    }
                    for (Path file : files) {
                        component = addIfDistinct(file.toAbsolutePath().normalize(), counted, component);
                    }
                }
            }
            byName.put(input.name(), component);
        }
        return new DeploymentSize(new ArrayList<>(byName.values()));
    }

    static Input input(String name, Path path) {
        return new Input(name, List.of(path));
    }

    static Input input(String name, List<Path> paths) {
        return new Input(name, paths);
    }

    /**
     * Adds one required file, such as a trained application cache, as a component of its own.
     *
     * <p>Unlike {@link #measure(Input...)}, a symbolic link is an error rather than something to skip: the
     * caller names this file because the launch cannot start without it.</p>
     *
     * @param component the new component's name
     * @param file      a regular file that is not a symbolic link
     * @return the existing components followed by the new one
     * @throws IOException if the file is missing, is a symbolic link, is not a regular file or cannot be read
     */
    DeploymentSize withFile(String component, Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("required deployment file does not exist: " + normalized);
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("required deployment file is a symbolic link: " + normalized);
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("required deployment file is not a regular file: " + normalized);
        }
        List<Component> extended = new ArrayList<>(components);
        extended.add(new Component(component, Files.size(normalized), gzipBytes(normalized)));
        return new DeploymentSize(extended);
    }

    /**
     * The complete deployment's logical size.
     *
     * @return the sum of every component's bytes
     */
    long totalBytes() {
        return components.stream().mapToLong(Component::bytes).sum();
    }

    /**
     * The gzip -6 approximation of the complete deployment's transfer size.
     *
     * @return the sum of every component's gzip bytes
     */
    long totalGzipBytes() {
        return components.stream().mapToLong(Component::gzipBytes).sum();
    }

    private static Component addIfDistinct(Path file, Set<Path> counted, Component component) throws IOException {
        if (!counted.add(file)) {
            return component;
        }
        return new Component(component.name(), component.bytes() + Files.size(file),
                component.gzipBytes() + gzipBytes(file));
    }

    /**
     * Streams one file through a {@link GZIPOutputStream} at its default level (zlib 6) into a sink that only
     * counts, so nothing is written to disk.
     *
     * @param file the file
     * @return the length of its gzip stream, header and trailer included
     * @throws IOException if the file cannot be read
     */
    private static long gzipBytes(Path file) throws IOException {
        CountingSink sink = new CountingSink();
        try (InputStream in = Files.newInputStream(file);
             GZIPOutputStream gzip = new GZIPOutputStream(sink)) {
            byte[] buffer = new byte[GZIP_READ_BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) {
                gzip.write(buffer, 0, read);
            }
        }
        return sink.count;
    }

    /**
     * A named part of a complete deployment.
     *
     * @param name      the component's name
     * @param bytes     the logical bytes of its files
     * @param gzipBytes the sum of its files' gzip -6 lengths
     */
    record Component(String name, long bytes, long gzipBytes) {
    }

    /** One named group of files or directory trees required by a deployment. */
    record Input(String name, List<Path> paths) {

        Input {
            paths = List.copyOf(paths);
        }
    }

    /** Counts what is written to it and keeps nothing. */
    private static final class CountingSink extends OutputStream {

        private long count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            count += length;
        }
    }
}
