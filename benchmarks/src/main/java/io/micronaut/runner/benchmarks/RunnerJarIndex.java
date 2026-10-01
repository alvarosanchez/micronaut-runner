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

import io.micronaut.runner.ArchiveSource;
import io.micronaut.runner.Index;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The index of a Runner JAR, read with the launcher's own reader, so that a row is checked against exactly what
 * the launcher reads at startup. It holds a memory mapping and a file handle until it is closed.
 */
final class RunnerJarIndex implements AutoCloseable {

    private final ArchiveSource source;
    private final Index index;

    private RunnerJarIndex(ArchiveSource source, Index index) {
        this.source = source;
        this.index = index;
    }

    /**
     * Opens a Runner JAR and reads its index.
     *
     * @param file the archive
     * @return the open index, which the caller closes
     * @throws IOException if the file cannot be read or carries no index entry
     */
    static RunnerJarIndex open(Path file) throws IOException {
        ArchiveSource source = ArchiveSource.open(file.toFile());
        try {
            return new RunnerJarIndex(source, Index.open(source));
        } catch (IOException | RuntimeException | Error e) {
            source.close();
            throw e;
        }
    }

    /**
     * The index of the archive.
     *
     * @return the index reader
     */
    Index index() {
        return index;
    }

    /** Releases the mapping and the file handle. */
    @Override
    public void close() {
        source.close();
    }
}
