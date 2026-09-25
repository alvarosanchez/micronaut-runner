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
package io.micronaut.runner.build;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * How the launcher reads the runner jar at run time when {@code micronaut.runner.mmap} does not say.
 *
 * <p>The choice is recorded in the index header, so it costs nothing at build time and can be overridden
 * per launch: {@code -Dmicronaut.runner.mmap=full} maps the whole archive whatever the packager chose, and
 * {@code -Dmicronaut.runner.mmap=index} reads positionally whatever it chose.</p>
 *
 * @since 1.0
 */
public enum ArchiveReads {

    /**
     * Map the whole archive and define every STORED class straight from the mapping.
     *
     * <p>This is the default. It starts fastest, but every page a class read touches stays mapped in the
     * process as a clean, file-backed page, so it counts in the process's resident set size until the process
     * exits, although the kernel can drop it at any time.</p>
     */
    MAPPED,

    /**
     * Map only the index, and read each STORED class with one positional read into a pooled direct buffer.
     *
     * <p>The class bytes stay in the page cache and out of the process's resident set, so the RSS that
     * {@code ps} and {@code top} report is lower, while private memory stays about the same. Each class costs a
     * system call, so startup is slightly slower. DEFLATE classes of a {@link Compression#PRESERVE} archive
     * still inflate, from a pooled buffer.</p>
     */
    POSITIONAL;

    /**
     * Reads an archive read mode the way a build script or a POM spells it: surrounding whitespace is ignored
     * and so is case, so {@code " Positional "} is {@link #POSITIONAL}.
     *
     * @param value the mode's name
     * @return the mode
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} names no mode
     * @since 1.0
     */
    public static ArchiveReads parse(String value) {
        Objects.requireNonNull(value, "archiveReads");
        String name = value.trim().toUpperCase(Locale.ROOT);
        for (ArchiveReads reads : values()) {
            if (reads.name().equals(name)) {
                return reads;
            }
        }
        throw new IllegalArgumentException("Unknown archiveReads '" + value + "'. Supported values are "
                + Arrays.stream(values()).map(ArchiveReads::name).collect(Collectors.joining(" and ")) + ".");
    }
}
