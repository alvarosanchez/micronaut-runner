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

import io.micronaut.runner.ArchiveSource;
import io.micronaut.runner.Index;
import io.micronaut.runner.IndexFormat;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Opens a finished runner jar with the launcher's own reader.
 *
 * <p>This is deliberately thin. The index reader and the archive primitives live in the launcher and are
 * shared with this module as source, so a test, the {@code inspect} tool and the verification pass at the
 * end of a build all look at an archive through exactly the code that will read it at startup. A second
 * reader written for build time would be a second set of bugs.</p>
 *
 * <p>A reader holds a memory mapping and a file handle and must be closed. On Windows an open mapping keeps
 * the file locked, so a build that forgets to close one cannot overwrite the archive it just wrote.</p>
 *
 * @since 1.0
 */
public final class RunnerJarReader implements Closeable {

    private final ArchiveSource source;
    private final Index index;

    private RunnerJarReader(ArchiveSource source, Index index) {
        this.source = source;
        this.index = index;
    }

    /**
     * Opens a runner jar.
     *
     * @param file the archive
     * @return an open reader the caller must close
     * @throws IOException           if the file cannot be read or carries no index entry
     * @throws IllegalStateException if the index is not one this release understands, or no longer
     *                               describes the file it sits in
     */
    public static RunnerJarReader open(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        return open(file.toFile());
    }

    /**
     * Opens a runner jar.
     *
     * @param file the archive
     * @return an open reader the caller must close
     * @throws IOException           if the file cannot be read or carries no index entry
     * @throws IllegalStateException if the index is not one this release understands, or no longer
     *                               describes the file it sits in
     */
    public static RunnerJarReader open(File file) throws IOException {
        Objects.requireNonNull(file, "file");
        ArchiveSource source = ArchiveSource.open(file);
        try {
            return new RunnerJarReader(source, Index.open(source));
        } catch (IOException | RuntimeException | Error e) {
            source.close();
            throw e;
        }
    }

    /**
     * The archive this reader reads.
     *
     * @return the path of the open file
     */
    public Path path() {
        return source.file().toPath();
    }

    /**
     * The open archive, for reading entry data.
     *
     * @return the source
     */
    public ArchiveSource source() {
        return source;
    }

    /**
     * The index of the archive.
     *
     * @return the index reader
     */
    public Index index() {
        return index;
    }

    /**
     * Reads the content of an entry, decompressing it when the entry is deflated.
     *
     * @param record an entry record index
     * @return the entry content, empty for a directory or a synthesised record
     * @throws IOException if the entry is larger than a Java array, or its data cannot be read
     */
    public byte[] read(int record) throws IOException {
        long compressed = index.entryCompressedSize(record);
        long uncompressed = index.entryUncompressedSize(record);
        if (uncompressed > ArchiveSource.MAX_SLICE_LENGTH) {
            throw new IOException("Entry '" + index.entryName(record) + "' is too large to read into memory: "
                    + uncompressed + " bytes");
        }
        long offset = index.entryDataOffset(record);
        if (index.entryMethod(record) == IndexFormat.METHOD_STORED) {
            return source.readFully(offset, (int) uncompressed);
        }
        return source.inflate(offset, (int) compressed, (int) uncompressed);
    }

    /**
     * Streams an entry without the Java-array size limit, enforcing the sizes and DEFLATE completion in the
     * same way as the launcher's archive source.
     *
     * @param record an entry record index
     * @return the entry stream, which the caller closes
     * @throws IOException if the indexed region or compression metadata is invalid
     */
    public InputStream stream(int record) throws IOException {
        return source.stream(index.entryDataOffset(record), index.entryCompressedSize(record),
                index.entryUncompressedSize(record), index.entryMethod(record));
    }

    /**
     * Releases the mapping and the file handle.
     */
    @Override
    public void close() {
        source.close();
    }
}
