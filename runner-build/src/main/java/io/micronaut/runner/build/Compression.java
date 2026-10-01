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
 * How a dependency is stored inside the runner jar.
 *
 * <p>Every entry of the <em>outer</em> archive is always stored uncompressed, because the launcher maps the
 * file and slices it; this option only decides what happens to the <em>inner</em> entries of the nested
 * dependency jars.</p>
 *
 * @since 1.0
 */
public enum Compression {

    /**
     * Rewrite every dependency so that all of its entries are stored uncompressed.
     *
     * <p>This is the default, and the mode the format is designed for: a stored nested class can be handed
     * to {@code ClassLoader.defineClass} as a slice of the memory mapped outer archive, with no inflater and
     * no intermediate array. The price is a larger artifact.</p>
     */
    STORED,

    /**
     * Copy every dependency byte for byte, keeping its original compression.
     *
     * <p>The artifact stays as small as the dependencies made it and their bytes are untouched, which is
     * what a build that has to reproduce a published jar exactly needs. Classes then have to be inflated
     * when they are loaded.</p>
     */
    PRESERVE,

    /**
     * Re-pack every dependency as {@link #STORED} does, but keep the classes the application does not load at
     * startup compressed.
     *
     * <p>The startup classes come from the recorded startup class list ({@link RunnerJarSpec#startupClasses()}).
     * A listed class stays stored, so it is still defined straight from the mapped archive. Every other class
     * keeps its original DEFLATE bytes; a cold class that a build-time class transform rewrote or generated, such
     * as a stripped or desugared class, is deflated afresh instead, because its original compressed bytes would
     * ship the class before the transform. Resources and directories stay stored. Signature files and
     * {@code META-INF/INDEX.LIST} are dropped as {@link #STORED} drops them, and the build-time transforms run as
     * they do for {@link #STORED}.</p>
     *
     * <p>The archive is smaller on disk and unpacked than a {@link #STORED} one, but larger after layer or
     * transfer compression, because DEFLATE bytes do not compress again. An unlisted class costs a few
     * microseconds of inflation when it is loaded.</p>
     *
     * <p>Without a startup class list, or with one that names no class of any dependency, there is nothing to
     * keep stored: the build logs one warning and writes the nested jars exactly as {@link #STORED} would with
     * the same list.</p>
     *
     * <p>A class deflated afresh is compressed by the zlib the build JDK's {@code java.util.zip} uses, so an
     * archive with rewritten cold classes is byte-reproducible only with the same JDK build and the same zlib.
     * {@link #STORED} output does not depend on zlib.</p>
     */
    HYBRID;

    /**
     * Reads a compression mode the way a build script or a POM spells it: surrounding whitespace is ignored
     * and so is case, so {@code " Preserve "} is {@link #PRESERVE} and {@code "hybrid"} is {@link #HYBRID}.
     *
     * <p>Both plugins, and any other caller that takes the mode as text, parse it here, so an unknown value
     * fails with one message that lists every constant this release supports.</p>
     *
     * @param value the mode's name
     * @return the mode
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} names no mode
     * @since 1.0
     */
    public static Compression parse(String value) {
        Objects.requireNonNull(value, "compression");
        String name = value.trim().toUpperCase(Locale.ROOT);
        for (Compression compression : values()) {
            if (compression.name().equals(name)) {
                return compression;
            }
        }
        throw new IllegalArgumentException("Unknown compression '" + value + "'. Supported values are "
                + Arrays.stream(values()).map(Compression::name).collect(Collectors.joining(", ")) + ".");
    }
}
