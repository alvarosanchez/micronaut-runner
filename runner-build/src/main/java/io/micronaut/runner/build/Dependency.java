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

import java.nio.file.Path;
import java.util.Objects;

/**
 * One dependency jar to nest into the runner jar, together with the coordinates it was resolved from.
 *
 * <p>The coordinates are metadata only: they are written into the jar table of the index so that
 * {@code inspect} and any tool reading a runner jar can tell which module a nested jar came from, and they
 * never influence how the archive is built. Build systems that can resolve them (Gradle from
 * {@code ResolvedArtifactResult}, Maven from the project artifacts) should pass them; file dependencies
 * that have none pass {@code null}.</p>
 *
 * @param path        the jar to nest, which must exist and be readable when the build runs
 * @param coordinates the Maven coordinates such as {@code io.netty:netty-common:4.2.1}, or {@code null}
 *                    when they are unknown
 * @since 1.0
 */
public record Dependency(Path path, String coordinates) {

    /**
     * Validates the dependency and normalises blank coordinates to {@code null}, so that the index never
     * stores an empty string where "unknown" is meant.
     *
     * @param path        the jar to nest
     * @param coordinates the Maven coordinates, or {@code null}
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public Dependency {
        Objects.requireNonNull(path, "path");
        if (coordinates != null && coordinates.isBlank()) {
            coordinates = null;
        }
    }

    /**
     * Creates a dependency whose coordinates are unknown.
     *
     * @param path the jar to nest
     */
    public Dependency(Path path) {
        this(path, null);
    }

    /**
     * The file name of the jar, which is the name the nested entry takes under
     * {@link io.micronaut.runner.IndexFormat#LIB_PREFIX} unless another dependency already claimed it.
     *
     * @return the last element of the path
     */
    public String fileName() {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }
}
