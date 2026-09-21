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

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * A minimal ZIP writer that only ever writes {@code STORED} entries and reports the absolute offset of
 * every entry's data as it goes.
 *
 * <p>Both properties are what the runner jar format is built on. Because nothing is deflated, an entry's
 * bytes lie contiguously in the outer file and the launcher can define a class straight from a
 * memory-mapped slice, or hand out a nested jar's bytes without copying them. Because the offset of the
 * data is returned by the write call itself, the packager never has to parse back what it just wrote to
 * fill in {@link IndexFormat#E_DATA_OFFSET}.</p>
 *
 * <p>Timestamps are fixed: every entry gets the MS-DOS time derived from the {@link Instant} handed to the
 * constructor, converted in UTC so that the same inputs produce a byte-identical archive on any machine.
 * The default is {@link #DEFAULT_TIMESTAMP}, which is in 1980 because MS-DOS time cannot represent anything
 * earlier. {@link ZipRepacker} overrides it per entry to preserve a dependency's original times.</p>
 *
 * <p>ZIP64 records are written when the archive needs them: a ZIP64 end of central directory record and
 * locator from 65535 entries upwards, or once the central directory reaches 4 GiB in size or in offset,
 * and a ZIP64
 * extended information extra field on the records of entries whose size or local header offset does not fit
 * in 32 bits. Every field that is replaced by a 64-bit value in an extra field carries the
 * {@link IndexFormat#ZIP64_MARKER} value in its 32-bit slot, as the specification requires.</p>
 *
 * <p>Instances are not thread-safe. {@link #close()} finishes the archive if {@link #finish()} was not
 * called and closes the underlying stream.</p>
 *
 * @since 1.0
 */
public final class ZipWriter implements Closeable {

    /**
     * The timestamp given to every entry unless the caller supplies another one: 1980-02-01T00:00:00Z.
     *
     * <p>The MS-DOS date field cannot represent a year before 1980, so the usual 1970 epoch is unusable;
     * the first of February keeps the value clear of any time zone rounding into 1979.</p>
     */
    public static final Instant DEFAULT_TIMESTAMP = Instant.parse("1980-02-01T00:00:00Z");

    /** Size of a local file header, before the name and the extra field. */
    private static final int LOCAL_HEADER_SIZE = 30;

    /** Size of a central directory file header, before the name, extra field and comment. */
    private static final int CENTRAL_HEADER_SIZE = 46;

    /** Size of a ZIP64 end of central directory record; it is written without extensible data. */
    private static final int ZIP64_END_SIZE = 56;

    /** Value a 16-bit ZIP field carries when the real value lives elsewhere. */
    private static final int ZIP64_MARKER_16 = 0xFFFF;

    /** Version needed to extract a stored entry, as {@link java.util.zip.ZipOutputStream} writes it. */
    private static final int VERSION_STORED = 10;

    /** Version needed to extract an entry that carries ZIP64 information. */
    private static final int VERSION_ZIP64 = 45;

    /** General purpose bit 11: the name is encoded in UTF-8. */
    private static final int FLAG_UTF8 = 1 << 11;

    /** MS-DOS external file attribute marking a directory. */
    private static final int MSDOS_DIRECTORY_ATTRIBUTE = 0x10;

    /** Largest entry name the format allows, in UTF-8 bytes. */
    private static final int MAX_NAME_LENGTH = 0xFFFF;

    /** Copy buffer size for streamed entries. */
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final OutputStream out;
    private final int defaultDosTime;
    private final boolean uniqueNames;
    private final List<CentralRecord> records = new ArrayList<>();
    private final Set<String> names = new HashSet<>();
    private final Set<String> foldedNames = new HashSet<>();
    private final byte[] scratch = new byte[CENTRAL_HEADER_SIZE];
    private long written;
    private boolean finished;

    /**
     * Creates a writer using {@link #DEFAULT_TIMESTAMP} for every entry.
     *
     * @param out the stream the archive is written to; the writer buffers nothing of its own, so a
     *            {@link BufferedOutputStream} is usually the right thing to hand in
     * @throws NullPointerException if {@code out} is {@code null}
     */
    public ZipWriter(OutputStream out) {
        this(out, DEFAULT_TIMESTAMP);
    }

    /**
     * Creates a writer using a fixed timestamp for every entry.
     *
     * @param out       the stream the archive is written to
     * @param timestamp the instant every entry is dated with, converted to MS-DOS time in UTC
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the instant is outside the range MS-DOS time can represent
     */
    public ZipWriter(OutputStream out, Instant timestamp) {
        this(out, timestamp, true);
    }

    /**
     * Creates a writer using a fixed timestamp for every entry, choosing how strict duplicate detection is.
     *
     * <p>The outer archive is built from scratch and must reject a repeated name, and a name that differs
     * from another only by case, because it has to behave the same when it is unpacked on a
     * case-insensitive file system. A dependency jar being repacked is a different matter: both are legal
     * in a jar somebody else produced - {@code zip -g} and some shading pipelines emit them - and a build
     * must not fail over an input that {@code java.util.zip.ZipFile} reads without complaint. The index
     * keys on the record rather than on a set of names, so a repeated name simply becomes a same-name
     * chain, exactly as it does in the {@code PRESERVE} compression mode where the jar is copied
     * verbatim. {@link ZipRepacker} therefore relaxes both checks.</p>
     *
     * @param out           the stream the archive is written to
     * @param timestamp     the instant every entry is dated with, converted in UTC
     * @param uniqueNames   whether a repeated name, or one differing from another only by case, is an error
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the instant is outside the range MS-DOS time can represent
     */
    public ZipWriter(OutputStream out, Instant timestamp, boolean uniqueNames) {
        this.out = Objects.requireNonNull(out, "out");
        this.defaultDosTime = toDosTime(timestamp);
        this.uniqueNames = uniqueNames;
    }

    /**
     * Creates a writer over a file, buffering the writes.
     *
     * @param file      the archive to create, truncating anything already there
     * @param timestamp the instant every entry is dated with, converted in UTC
     * @return an open writer the caller must close
     * @throws IOException              if the file cannot be created
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the instant is outside the range MS-DOS time can represent
     */
    public static ZipWriter create(Path file, Instant timestamp) throws IOException {
        Objects.requireNonNull(file, "file");
        return new ZipWriter(new BufferedOutputStream(Files.newOutputStream(file), COPY_BUFFER_SIZE), timestamp);
    }

    /**
     * Converts an instant to the MS-DOS date and time layout the ZIP format stores, the date in the high 16
     * bits and the time in the low 16 bits.
     *
     * <p>The conversion is done in UTC rather than in the default time zone, which is what makes the output
     * independent of the machine that produced it.</p>
     *
     * @param timestamp the instant to convert
     * @return the MS-DOS date and time, in the layout {@link IndexFormat#E_DOS_TIME} expects
     * @throws NullPointerException     if {@code timestamp} is {@code null}
     * @throws IllegalArgumentException if the year is before 1980 or after 2107, which MS-DOS time cannot
     *                                  represent
     */
    public static int toDosTime(Instant timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        LocalDateTime time = LocalDateTime.ofInstant(timestamp, ZoneOffset.UTC);
        int year = time.getYear();
        if (year < 1980 || year > 2107) {
            throw new IllegalArgumentException("MS-DOS time cannot represent " + timestamp
                    + "; the year must be between 1980 and 2107");
        }
        return ((year - 1980) << 25)
                | (time.getMonthValue() << 21)
                | (time.getDayOfMonth() << 16)
                | (time.getHour() << 11)
                | (time.getMinute() << 5)
                | (time.getSecond() >> 1);
    }

    /**
     * The number of bytes written so far, which is the offset at which the next local file header will go.
     *
     * @return the current offset in the archive
     */
    public long offset() {
        return written;
    }

    /**
     * The number of entries written so far.
     *
     * @return the entry count
     */
    public int entryCount() {
        return records.size();
    }

    /**
     * The MS-DOS date and time every entry gets unless the caller passes another one.
     *
     * @return the fixed MS-DOS date and time
     */
    public int dosTime() {
        return defaultDosTime;
    }

    /**
     * Writes a file entry.
     *
     * @param name the entry name, which must be safe (see {@link ZipReader#isSafeEntryName(String)}) and must
     *             not end with {@code '/'}
     * @param data the entry content
     * @return the absolute offset of the entry's first data byte
     * @throws IOException if the name is unsafe or duplicated, or the stream cannot be written
     */
    public long writeEntry(String name, byte[] data) throws IOException {
        Objects.requireNonNull(data, "data");
        return writeEntry(name, data, 0, data.length, defaultDosTime);
    }

    /**
     * Writes a file entry from part of an array.
     *
     * @param name   the entry name, which must be safe and must not end with {@code '/'}
     * @param data   the array holding the content
     * @param offset the first byte of the content
     * @param length the content length
     * @return the absolute offset of the entry's first data byte
     * @throws IOException if the name is unsafe or duplicated, or the stream cannot be written
     */
    public long writeEntry(String name, byte[] data, int offset, int length) throws IOException {
        return writeEntry(name, data, offset, length, defaultDosTime);
    }

    /**
     * Writes a file entry from part of an array, with an explicit MS-DOS timestamp.
     *
     * @param name    the entry name, which must be safe and must not end with {@code '/'}
     * @param data    the array holding the content
     * @param offset  the first byte of the content
     * @param length  the content length
     * @param dosTime the MS-DOS date and time to store, in the layout {@link #toDosTime(Instant)} produces
     * @return the absolute offset of the entry's first data byte
     * @throws IOException if the name is unsafe or duplicated, or the stream cannot be written
     */
    public long writeEntry(String name, byte[] data, int offset, int length, int dosTime) throws IOException {
        Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        long dataOffset = writeHeader(name, length, crc.getValue(), dosTime, false);
        writeBytes(data, offset, length);
        return dataOffset;
    }

    /**
     * Writes a file entry by streaming exactly {@code length} bytes from a source.
     *
     * <p>The size and CRC-32 have to be known in advance because a stored entry's local file header carries
     * them and this writer never emits a data descriptor.</p>
     *
     * @param name    the entry name, which must be safe and must not end with {@code '/'}
     * @param source  the content, read but not closed
     * @param length  the exact number of bytes to copy
     * @param crc32   the CRC-32 of those bytes, as an unsigned 32-bit value
     * @param dosTime the MS-DOS date and time to store
     * @return the absolute offset of the entry's first data byte
     * @throws IOException if the name is unsafe or duplicated, the source ends early, or the stream cannot
     *                     be written
     */
    public long writeEntry(String name, InputStream source, long length, long crc32, int dosTime)
            throws IOException {
        Objects.requireNonNull(source, "source");
        if (length < 0) {
            throw new IllegalArgumentException("Negative length for entry '" + name + "': " + length);
        }
        long dataOffset = writeHeader(name, length, crc32, dosTime, false);
        copy(name, source, length);
        return dataOffset;
    }

    /**
     * Writes a file entry from a file, reading it twice: once to compute its CRC-32 and once to copy it.
     *
     * <p>This is how a nested jar built into a temporary file is added to the outer archive without ever
     * holding it in memory.</p>
     *
     * @param name   the entry name, which must be safe and must not end with {@code '/'}
     * @param source the file to store
     * @return the absolute offset of the entry's first data byte
     * @throws IOException if the name is unsafe or duplicated, or either file cannot be read or written
     */
    public long writeEntry(String name, Path source) throws IOException {
        Objects.requireNonNull(source, "source");
        long length = Files.size(source);
        long crc = crc32(source);
        try (InputStream in = Files.newInputStream(source)) {
            return writeEntry(name, in, length, crc, defaultDosTime);
        }
    }

    /**
     * Writes an explicit, zero length directory entry.
     *
     * <p>Micronaut's service scanner enumerates directories, so the packager writes them explicitly rather
     * than relying on readers to synthesise them.</p>
     *
     * @param name the directory name, which must end with {@code '/'}
     * @return the absolute offset of the entry's (empty) data
     * @throws IOException if the name is unsafe or duplicated, or the stream cannot be written
     */
    public long writeDirectoryEntry(String name) throws IOException {
        return writeDirectoryEntry(name, defaultDosTime);
    }

    /**
     * Writes an explicit, zero length directory entry with an explicit MS-DOS timestamp.
     *
     * @param name    the directory name, which must end with {@code '/'}
     * @param dosTime the MS-DOS date and time to store
     * @return the absolute offset of the entry's (empty) data
     * @throws IOException if the name is unsafe or duplicated, or the stream cannot be written
     */
    public long writeDirectoryEntry(String name, int dosTime) throws IOException {
        Objects.requireNonNull(name, "name");
        if (!name.endsWith("/")) {
            throw new IOException("Directory entry name must end with '/': '" + name + "'");
        }
        return writeHeader(name, 0, 0, dosTime, true);
    }

    /**
     * Writes the central directory and the end of central directory record, with their ZIP64 counterparts
     * when the archive needs them. Does nothing when the archive is already finished.
     *
     * @throws IOException if the stream cannot be written
     */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        finished = true;
        long directoryOffset = written;
        for (CentralRecord record : records) {
            writeCentralRecord(record);
        }
        long directorySize = written - directoryOffset;
        long count = records.size();
        // Every comparison is >=, not >, because the marker value itself is what tells a reader to look
        // for the ZIP64 records: an archive of exactly 65535 entries, or one whose central directory
        // starts at or is exactly 0xFFFFFFFF bytes, writes the marker into the 32-bit field and must
        // therefore carry the 64-bit records that explain it. This is what
        // java.util.zip.ZipOutputStream does with ZIP64_MAGICCOUNT and ZIP64_MAGICVAL.
        boolean zip64 = count >= ZIP64_MARKER_16
                || directoryOffset >= IndexFormat.ZIP64_MARKER
                || directorySize >= IndexFormat.ZIP64_MARKER;
        if (zip64) {
            long zip64EndOffset = written;
            writeZip64End(count, directoryOffset, directorySize);
            writeZip64Locator(zip64EndOffset);
        }
        writeEnd(count, directoryOffset, directorySize, zip64);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        try {
            finish();
        } finally {
            out.close();
        }
    }

    private static long crc32(Path source) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(source)) {
            int read = in.read(buffer);
            while (read > 0) {
                crc.update(buffer, 0, read);
                read = in.read(buffer);
            }
        }
        return crc.getValue();
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static void putShort(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >>> 8);
    }

    private static void putInt(byte[] buffer, int offset, long value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >>> 8);
        buffer[offset + 2] = (byte) (value >>> 16);
        buffer[offset + 3] = (byte) (value >>> 24);
    }

    private static void putLong(byte[] buffer, int offset, long value) {
        putInt(buffer, offset, value);
        putInt(buffer, offset + 4, value >>> 32);
    }

    private void writeBytes(byte[] data, int offset, int length) throws IOException {
        out.write(data, offset, length);
        written += length;
    }

    private void copy(String name, InputStream source, long length) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int wanted = (int) Math.min(buffer.length, remaining);
            int read = source.read(buffer, 0, wanted);
            if (read < 0) {
                throw new IOException("Entry '" + name + "' ended after " + (length - remaining)
                        + " of " + length + " bytes");
            }
            writeBytes(buffer, 0, read);
            remaining -= read;
        }
    }

    /**
     * Registers an entry, writes its local file header and returns the offset of its first data byte.
     */
    private long writeHeader(String name, long size, long crc32, int dosTime, boolean directory)
            throws IOException {
        if (finished) {
            throw new IOException("The archive is already finished; entry '" + name + "' cannot be added");
        }
        Objects.requireNonNull(name, "name");
        ZipReader.requireSafeEntryName(name, "the archive being written");
        if (!directory && name.endsWith("/")) {
            throw new IOException("Only a directory entry may have a name ending with '/': '" + name + "'");
        }
        if (crc32 < 0 || crc32 > IndexFormat.ZIP64_MARKER) {
            throw new IOException("Entry '" + name + "' has a CRC-32 outside 32 bits: " + crc32);
        }
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > MAX_NAME_LENGTH) {
            throw new IOException("Entry name is longer than " + MAX_NAME_LENGTH + " UTF-8 bytes: '" + name + "'");
        }
        if (uniqueNames) {
            if (!names.add(name)) {
                throw new IOException("Duplicate entry name: '" + name + "'");
            }
            if (!foldedNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new IOException("Duplicate entry name, differing only by case: '" + name
                        + "'. The archive must behave the same on a case-insensitive file system");
            }
        }
        long localHeaderOffset = written;
        boolean sizeNeedsZip64 = size > IndexFormat.ZIP64_MARKER;
        boolean utf8 = !isAscii(name);
        byte[] header = scratch;
        putInt(header, 0, IndexFormat.LOCAL_HEADER_SIGNATURE);
        putShort(header, 4, sizeNeedsZip64 ? VERSION_ZIP64 : VERSION_STORED);
        putShort(header, 6, utf8 ? FLAG_UTF8 : 0);
        putShort(header, 8, IndexFormat.METHOD_STORED);
        putShort(header, 10, dosTime & ZIP64_MARKER_16);
        putShort(header, 12, dosTime >>> 16);
        putInt(header, 14, crc32);
        putInt(header, 18, sizeNeedsZip64 ? IndexFormat.ZIP64_MARKER : size);
        putInt(header, 22, sizeNeedsZip64 ? IndexFormat.ZIP64_MARKER : size);
        putShort(header, 26, nameBytes.length);
        putShort(header, 28, sizeNeedsZip64 ? 20 : 0);
        writeBytes(header, 0, LOCAL_HEADER_SIZE);
        writeBytes(nameBytes, 0, nameBytes.length);
        if (sizeNeedsZip64) {
            byte[] extra = new byte[20];
            putShort(extra, 0, IndexFormat.ZIP64_EXTRA_FIELD_ID);
            putShort(extra, 2, 16);
            putLong(extra, 4, size);
            putLong(extra, 12, size);
            writeBytes(extra, 0, extra.length);
        }
        records.add(new CentralRecord(nameBytes, utf8, dosTime, crc32, size, localHeaderOffset, directory));
        return written;
    }

    private void writeCentralRecord(CentralRecord record) throws IOException {
        boolean sizeNeedsZip64 = record.size() > IndexFormat.ZIP64_MARKER;
        boolean offsetNeedsZip64 = record.localHeaderOffset() > IndexFormat.ZIP64_MARKER;
        int extraLength = (sizeNeedsZip64 ? 16 : 0) + (offsetNeedsZip64 ? 8 : 0);
        byte[] header = scratch;
        putInt(header, 0, IndexFormat.CENTRAL_HEADER_SIGNATURE);
        putShort(header, 4, extraLength == 0 ? VERSION_STORED : VERSION_ZIP64);
        putShort(header, 6, extraLength == 0 ? VERSION_STORED : VERSION_ZIP64);
        putShort(header, 8, record.utf8() ? FLAG_UTF8 : 0);
        putShort(header, 10, IndexFormat.METHOD_STORED);
        putShort(header, 12, record.dosTime() & ZIP64_MARKER_16);
        putShort(header, 14, record.dosTime() >>> 16);
        putInt(header, 16, record.crc32());
        putInt(header, 20, sizeNeedsZip64 ? IndexFormat.ZIP64_MARKER : record.size());
        putInt(header, 24, sizeNeedsZip64 ? IndexFormat.ZIP64_MARKER : record.size());
        putShort(header, 28, record.name().length);
        putShort(header, 30, extraLength == 0 ? 0 : extraLength + 4);
        putShort(header, 32, 0);
        putShort(header, 34, 0);
        putShort(header, 36, 0);
        putInt(header, 38, record.directory() ? MSDOS_DIRECTORY_ATTRIBUTE : 0);
        putInt(header, 42, offsetNeedsZip64 ? IndexFormat.ZIP64_MARKER : record.localHeaderOffset());
        writeBytes(header, 0, CENTRAL_HEADER_SIZE);
        writeBytes(record.name(), 0, record.name().length);
        if (extraLength > 0) {
            byte[] extra = new byte[extraLength + 4];
            putShort(extra, 0, IndexFormat.ZIP64_EXTRA_FIELD_ID);
            putShort(extra, 2, extraLength);
            int at = 4;
            if (sizeNeedsZip64) {
                putLong(extra, at, record.size());
                putLong(extra, at + 8, record.size());
                at += 16;
            }
            if (offsetNeedsZip64) {
                putLong(extra, at, record.localHeaderOffset());
            }
            writeBytes(extra, 0, extra.length);
        }
    }

    private void writeZip64End(long count, long directoryOffset, long directorySize) throws IOException {
        byte[] record = new byte[ZIP64_END_SIZE];
        putInt(record, 0, IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE);
        putLong(record, 4, ZIP64_END_SIZE - 12L);
        putShort(record, 12, VERSION_ZIP64);
        putShort(record, 14, VERSION_ZIP64);
        putInt(record, 16, 0);
        putInt(record, 20, 0);
        putLong(record, 24, count);
        putLong(record, 32, count);
        putLong(record, 40, directorySize);
        putLong(record, 48, directoryOffset);
        writeBytes(record, 0, record.length);
    }

    private void writeZip64Locator(long zip64EndOffset) throws IOException {
        byte[] locator = new byte[IndexFormat.ZIP64_LOCATOR_SIZE];
        putInt(locator, 0, IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE);
        putInt(locator, 4, 0);
        putLong(locator, 8, zip64EndOffset);
        putInt(locator, 16, 1);
        writeBytes(locator, 0, locator.length);
    }

    private void writeEnd(long count, long directoryOffset, long directorySize, boolean zip64) throws IOException {
        byte[] record = new byte[IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE];
        long visibleCount = zip64 && count > ZIP64_MARKER_16 ? ZIP64_MARKER_16 : count;
        long visibleSize = directorySize > IndexFormat.ZIP64_MARKER ? IndexFormat.ZIP64_MARKER : directorySize;
        long visibleOffset = directoryOffset > IndexFormat.ZIP64_MARKER ? IndexFormat.ZIP64_MARKER : directoryOffset;
        putInt(record, 0, IndexFormat.END_OF_CENTRAL_DIRECTORY_SIGNATURE);
        putShort(record, 4, 0);
        putShort(record, 6, 0);
        putShort(record, 8, (int) visibleCount);
        putShort(record, 10, (int) visibleCount);
        putInt(record, 12, visibleSize);
        putInt(record, 16, visibleOffset);
        putShort(record, 20, 0);
        writeBytes(record, 0, record.length);
    }

    /**
     * One pending central directory record. The bytes are written only once the archive is finished, so
     * every field has to be remembered until then.
     *
     * @param name              the entry name, already encoded as UTF-8
     * @param utf8              whether the name needs the UTF-8 general purpose bit
     * @param dosTime           the MS-DOS date and time
     * @param crc32             the CRC-32 of the content, as an unsigned 32-bit value
     * @param size              the content length, which is both the compressed and the uncompressed size
     * @param localHeaderOffset the offset of the entry's local file header
     * @param directory         whether the entry is a directory
     */
    private record CentralRecord(
            byte[] name,
            boolean utf8,
            int dosTime,
            long crc32,
            long size,
            long localHeaderOffset,
            boolean directory) {
    }
}
