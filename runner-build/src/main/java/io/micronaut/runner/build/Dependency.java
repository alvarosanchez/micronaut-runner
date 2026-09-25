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
import java.util.Optional;

/**
 * One dependency to nest into the runner jar, together with the coordinates it was resolved from.
 *
 * <p>Five things about a dependency determine the archive, and a plugin's cache key must cover all five:</p>
 * <ul>
 *     <li>the file's bytes, which are nested as they are or repacked uncompressed;</li>
 *     <li>its {@linkplain #fileName() file name}, which becomes the nested entry's name;</li>
 *     <li>its {@linkplain #coordinates() coordinates}, which are written into the index so that
 *     {@code inspect} and any tool reading a runner jar can tell which module a nested jar came from;</li>
 *     <li>whether it is a {@linkplain #projectModule() project module}, which decides whether its classes
 *     are rewritten;</li>
 *     <li>its position in {@link RunnerJarSpec#dependencies()}, which is the class path order.</li>
 * </ul>
 *
 * <p>The builder nests a file that is a ZIP archive, fails on a directory, and skips any other file with a
 * warning. Build systems that can resolve coordinates (Gradle from {@code ResolvedArtifactResult}, Maven from
 * the project artifacts) should pass them; a file dependency that has none uses {@link #of(Path)}.</p>
 *
 * <p>A dependency is an immutable value: two dependencies with the same path, coordinates and project-module
 * flag are equal.</p>
 *
 * @since 1.0
 */
public final class Dependency {

    private final Path path;
    private final String coordinates;
    private final boolean projectModule;

    private Dependency(Path path, String coordinates, boolean projectModule) {
        this.path = path;
        this.coordinates = coordinates;
        this.projectModule = projectModule;
    }

    /**
     * A dependency whose coordinates are unknown.
     *
     * @param path the file to nest, which must exist when the build runs
     * @return the dependency
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public static Dependency of(Path path) {
        return of(path, null);
    }

    /**
     * A dependency resolved from the given coordinates.
     *
     * @param path        the file to nest, which must exist when the build runs
     * @param coordinates the Maven coordinates such as {@code io.netty:netty-common:4.2.1}, or {@code null}
     *                    or a blank string when they are unknown
     * @return the dependency
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public static Dependency of(Path path, String coordinates) {
        Objects.requireNonNull(path, "path");
        // Blank means unknown, so the index never stores an empty string where "unknown" is meant.
        return new Dependency(path, coordinates == null || coordinates.isBlank() ? null : coordinates, false);
    }

    /**
     * A copy of this dependency with the project-module flag set as given.
     *
     * @param value whether the build that packages the application also produced this jar
     * @return the flagged copy, or this instance when the flag already has that value
     * @see #projectModule()
     */
    public Dependency projectModule(boolean value) {
        return value == projectModule ? this : new Dependency(path, coordinates, value);
    }

    /**
     * The file to nest.
     *
     * @return the path, as it was given
     */
    public Path path() {
        return path;
    }

    /**
     * The coordinates written into the index.
     *
     * @return the coordinates, or empty when they are unknown
     */
    public Optional<String> coordinates() {
        return Optional.ofNullable(coordinates);
    }

    /**
     * Whether the build that packages the application also produced this jar: another project of a Gradle
     * build, or another module of a Maven reactor.
     *
     * <p>Unlike the coordinates, the flag changes how the archive is built. A project module is user code,
     * whose locals users debug, so its classes are nested as they are: {@link
     * RunnerJarSpec#stripLocalVariables()} never rewrites them, just as it never rewrites the application's
     * own classes. {@link #of(Path)} and {@link #of(Path, String)} leave it unset.</p>
     *
     * @return whether this dependency is a module of the same build
     */
    public boolean projectModule() {
        return projectModule;
    }

    /**
     * The file name of the dependency, which is the name the nested entry takes under
     * {@link io.micronaut.runner.IndexFormat#LIB_PREFIX} unless another dependency already claimed it.
     *
     * @return the last element of the path
     */
    public String fileName() {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Dependency that
                && path.equals(that.path) && Objects.equals(coordinates, that.coordinates)
                && projectModule == that.projectModule;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * path.hashCode() + Objects.hashCode(coordinates)) + Boolean.hashCode(projectModule);
    }

    @Override
    public String toString() {
        String text = coordinates == null ? path.toString() : coordinates + " (" + path + ")";
        return projectModule ? text + " [project module]" : text;
    }
}
