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

import io.micronaut.runner.IndexFormat;

import java.util.Objects;

/**
 * An immutable description of one entry of a ZIP archive, as the packaging library needs it.
 *
 * <p>This is deliberately not {@link java.util.zip.ZipEntry}: the runner jar format indexes entry data by
 * <em>absolute file offset</em> and the launcher reads it with no ZIP parsing at all, so the packager needs
 * the two offsets the JDK never exposes: the offset of the entry's local file header and the offset of its
 * first data byte. The latter cannot be derived from the central directory alone, because the name and
 * extra field lengths of the local header may differ from the ones in the central directory record; it is
 * resolved by {@link ZipReader} from the local header itself.</p>
 *
 * <p>All numeric fields hold the values as stored in the archive, widened to signed Java types so that
 * unsigned 32-bit values and ZIP64 64-bit values survive: sizes and offsets are {@code long} and
 * {@link #crc32()} is a {@code long} holding an unsigned 32-bit value, exactly like
 * {@link java.util.zip.ZipEntry#getCrc()}.</p>
 *
 * @param name             the entry name, that is, the path inside the archive, using {@code '/'} separators;
 *                         a directory entry's name ends with {@code '/'}
 * @param method           the compression method, {@link IndexFormat#METHOD_STORED} or
 *                         {@link IndexFormat#METHOD_DEFLATED}
 * @param compressedSize   the number of bytes of entry data physically present in the archive
 * @param uncompressedSize the size of the entry's content once decompressed
 * @param crc32            the CRC-32 of the uncompressed content, as an unsigned 32-bit value
 * @param dosTime          the MS-DOS date and time as stored in the ZIP header, the date in the high 16 bits
 *                         and the time in the low 16 bits, which is the layout
 *                         {@link IndexFormat#E_DOS_TIME} expects
 * @param localHeaderOffset the offset of the entry's local file header, relative to the start of the archive
 * @param dataOffset       the offset of the entry's first data byte, that is, past the local file header, its
 *                         name and its extra field, relative to the start of the archive
 * @param directory        whether the entry is a directory
 * @since 1.0
 */
public record ZipEntryInfo(
        String name,
        int method,
        long compressedSize,
        long uncompressedSize,
        long crc32,
        int dosTime,
        long localHeaderOffset,
        long dataOffset,
        boolean directory) {

    /**
     * Validates the invariants every reader and writer in this library relies on.
     *
     * @param name              the entry name
     * @param method            the compression method
     * @param compressedSize    the stored size
     * @param uncompressedSize  the size of the content
     * @param crc32             the CRC-32 of the content
     * @param dosTime           the MS-DOS date and time
     * @param localHeaderOffset the offset of the local file header
     * @param dataOffset        the offset of the first data byte
     * @param directory         whether the entry is a directory
     * @throws NullPointerException     if {@code name} is {@code null}
     * @throws IllegalArgumentException if a size, an offset or the CRC-32 is out of range
     */
    public ZipEntryInfo {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Entry name must not be empty");
        }
        if (compressedSize < 0 || uncompressedSize < 0) {
            throw new IllegalArgumentException("Entry '" + name + "' has a negative size");
        }
        if (crc32 < 0 || crc32 > IndexFormat.ZIP64_MARKER) {
            throw new IllegalArgumentException("Entry '" + name + "' has a CRC-32 outside 32 bits: " + crc32);
        }
        if (localHeaderOffset < 0 || dataOffset < 0) {
            throw new IllegalArgumentException("Entry '" + name + "' has a negative offset");
        }
    }

    /**
     * Whether the entry data is stored uncompressed, in which case the bytes between {@link #dataOffset()}
     * and {@code dataOffset() + compressedSize()} are the content itself. This is what lets the launcher
     * define a class straight from a memory-mapped slice of the outer archive.
     *
     * @return {@code true} when the compression method is {@link IndexFormat#METHOD_STORED}
     */
    public boolean stored() {
        return method == IndexFormat.METHOD_STORED;
    }

    /**
     * Returns a copy of this entry with both offsets moved by {@code delta} bytes.
     *
     * <p>{@link ZipRepacker} reports offsets relative to the start of the nested jar it produced. The
     * packager turns them into the absolute offsets the index stores by shifting them with the offset at
     * which that nested jar was written into the outer archive.</p>
     *
     * @param delta the number of bytes to add to {@link #localHeaderOffset()} and {@link #dataOffset()}
     * @return the shifted entry, or this instance when {@code delta} is zero
     * @throws IllegalArgumentException if the shift would make an offset negative
     */
    public ZipEntryInfo shift(long delta) {
        if (delta == 0) {
            return this;
        }
        return new ZipEntryInfo(name, method, compressedSize, uncompressedSize, crc32, dosTime,
                localHeaderOffset + delta, dataOffset + delta, directory);
    }
}
