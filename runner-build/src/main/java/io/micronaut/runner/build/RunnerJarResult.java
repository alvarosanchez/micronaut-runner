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
import java.util.List;
import java.util.Objects;

/**
 * What {@link RunnerJarBuilder#build(RunnerJarSpec, BuildLogger)} produced.
 *
 * <p>The counts are the ones worth printing at the end of a build or asserting on in a test: how much went
 * in, how much came out, and whether anything was dropped along the way. Every warning was also reported to
 * the {@link BuildLogger} as the build ran; they are collected here so a caller that passed
 * {@link BuildLogger#noOp()} still sees them.</p>
 *
 * @param output                   the archive that was written
 * @param jarCount                 the number of jars in the index, the application layer included, so the
 *                                 number of nested dependencies is one less
 * @param entryCount               the number of index records: physical entries, versioned aliases and
 *                                 synthesised directories of every jar
 * @param applicationEntryCount    the number of distinct entries the application output contributed, which
 *                                 is what ends up under {@code MICRONAUT-INF/classes/}; the merged service
 *                                 entries belong to jar {@code 0} as well but are counted separately
 * @param mergedServiceEntryCount  the number of distinct {@code META-INF/micronaut/<service>/<name>}
 *                                 entries merged into the outer archive root
 * @param archiveSize              the length of the archive in bytes
 * @param warnings                 every warning the build reported, in the order it reported them
 * @since 1.0
 */
public record RunnerJarResult(
        Path output,
        int jarCount,
        int entryCount,
        int applicationEntryCount,
        int mergedServiceEntryCount,
        long archiveSize,
        List<String> warnings) {

    /**
     * Validates the result and makes the warning list immutable.
     *
     * @param output                  the archive that was written
     * @param jarCount                the number of jars in the index
     * @param entryCount              the number of index records
     * @param applicationEntryCount   the number of physical records of the application layer
     * @param mergedServiceEntryCount the number of merged service entries
     * @param archiveSize             the length of the archive
     * @param warnings                the warnings reported during the build
     * @throws NullPointerException     if {@code output} or {@code warnings} is {@code null}
     * @throws IllegalArgumentException if a count or the size is negative
     */
    public RunnerJarResult {
        Objects.requireNonNull(output, "output");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        if (jarCount < 0 || entryCount < 0 || applicationEntryCount < 0 || mergedServiceEntryCount < 0
                || archiveSize < 0) {
            throw new IllegalArgumentException("Negative count in the result of packaging " + output);
        }
    }

    /**
     * The number of nested dependency jars, which is every jar but the application layer.
     *
     * @return the dependency count
     */
    public int dependencyCount() {
        return Math.max(0, jarCount - 1);
    }
}
