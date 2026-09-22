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
package io.micronaut.runner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * A minimal ZIP writer for the launcher tests.
 *
 * <p>The launcher reads the outer archive by hand, so the tests must be able to produce archives with
 * exactly the shapes it has to cope with: a plain archive, one with a ZIP comment after the end of central
 * directory record, and a ZIP64 one whose sizes and offsets live in extra fields. {@code java.util.zip}
 * cannot write the last two on demand, hence this writer.</p>
 *
 * <p>When an entry named {@code MICRONAUT-INF/index.bin} looks like a runner index, its recorded outer file
 * length is patched with the real length of the finished archive before the CRCs are computed. That closes
 * the circular dependency between the index, which records the file length, and the file, which contains
 * the index.</p>
 *
 * <p>This is test code and is deliberately written in ordinary Java: the hot path rules that govern
 * {@code io.micronaut.runner} do not apply here.</p>
 */
public final class TestArchiveBuilder {

    private static final int LOCAL_HEADER_SIZE = 30;
    private static final int CENTRAL_HEADER_SIZE = 46;
    private static final int ZIP64_END_SIZE = 56;
    private static final int DOS_TIME = 0x00210000;

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Long> dataOffsets = new LinkedHashMap<>();
    private final Map<String, Long> localHeaderOffsets = new LinkedHashMap<>();
    private byte[] buffer = new byte[1024];
    private int size;
    private byte[] comment = new byte[0];
    private boolean zip64;
    private boolean patchIndexFileLength = true;
    private byte[] finished;

    /**
     * Appends a STORED entry.
     *
     * @param name the entry name
     * @param data the content
     * @return the absolute offset of the entry data in the finished archive
     */
    public long stored(String name, byte[] data) {
        return add(name, data, data, IndexFormat.METHOD_STORED);
    }

    /**
     * Appends a DEFLATE entry, compressing the content with a raw deflate stream.
     *
     * @param name the entry name
     * @param data the uncompressed content
     * @return the absolute offset of the entry data in the finished archive
     */
    public long deflated(String name, byte[] data) {
        return add(name, data, deflate(data), IndexFormat.METHOD_DEFLATED);
    }

    /**
     * Appends an entry whose stored bytes are given verbatim, so that a test can write a deliberately
     * broken deflate stream.
     *
     * @param name   the entry name
     * @param stored the bytes to store
     * @param method the compression method to record
     * @param size   the uncompressed size to record
     * @return the absolute offset of the entry data in the finished archive
     */
    public long raw(String name, byte[] stored, int method, int size) {
        Entry entry = new Entry(name, stored, method, size, crc(stored));
        return append(entry);
    }

    /**
     * Appends a placeholder STORED entry of a known size, to be filled in with {@link #replace} once the
     * offsets of every entry are known. This is how a test escapes the circle in which the index records
     * the offsets of the entries and the entries contain the index.
     *
     * @param name the entry name
     * @param size the number of bytes to reserve
     * @return the absolute offset of the entry data in the finished archive
     */
    public long reserve(String name, int size) {
        return raw(name, new byte[size], IndexFormat.METHOD_STORED, size);
    }

    /**
     * Overwrites the content of an entry in place. The new content must be exactly as long as the old one,
     * so that no offset moves.
     *
     * @param name the entry name
     * @param data the new content
     * @return this builder
     */
    public TestArchiveBuilder replace(String name, byte[] data) {
        if (finished != null) {
            throw new IllegalStateException("The archive has already been built");
        }
        long at = dataOffset(name);
        for (Entry entry : entries) {
            if (entry.nameText.equals(name)) {
                if (entry.stored.length != data.length) {
                    throw new IllegalArgumentException("Cannot replace " + entry.stored.length
                            + " bytes of " + name + " with " + data.length);
                }
                System.arraycopy(data, 0, buffer, (int) at, data.length);
                entry.stored = data.clone();
                entry.crc = crc(data);
                return this;
            }
        }
        throw new IllegalArgumentException("No entry named " + name);
    }

    /**
     * Sets the archive comment, which pushes the end of central directory record away from the end of the
     * file and forces the launcher to scan backwards for it.
     *
     * @param value the comment
     * @return this builder
     */
    public TestArchiveBuilder comment(String value) {
        this.comment = value.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    /**
     * Writes the archive in ZIP64 form: sizes and offsets are replaced by the {@code 0xFFFFFFFF} marker and
     * carried in extra fields, and the end of central directory record is preceded by a ZIP64 record and a
     * locator.
     *
     * @param value whether to use ZIP64
     * @return this builder
     */
    public TestArchiveBuilder zip64(boolean value) {
        this.zip64 = value;
        return this;
    }

    /**
     * Disables patching of the index's recorded outer file length, so that a test can check that the
     * mismatch is detected.
     *
     * @param value whether to patch
     * @return this builder
     */
    public TestArchiveBuilder patchIndexFileLength(boolean value) {
        this.patchIndexFileLength = value;
        return this;
    }

    /**
     * The absolute offset of an entry's data.
     *
     * @param name the entry name
     * @return the offset
     */
    public long dataOffset(String name) {
        Long offset = dataOffsets.get(name);
        if (offset == null) {
            throw new IllegalArgumentException("No entry named " + name);
        }
        return offset;
    }

    /**
     * The absolute offset of an entry's local file header, which is what a jar record of the index
     * records so that the launcher can check the archive has not moved underneath it.
     *
     * @param name the entry name
     * @return the offset
     */
    public long localHeaderOffset(String name) {
        Long offset = localHeaderOffsets.get(name);
        if (offset == null) {
            throw new IllegalArgumentException("No entry named " + name);
        }
        return offset;
    }

    /**
     * The number of bytes an entry occupies in the archive, which is its compressed size.
     *
     * @param name the entry name
     * @return the stored length
     */
    public int storedSize(String name) {
        for (Entry entry : entries) {
            if (entry.nameText.equals(name)) {
                return entry.stored.length;
            }
        }
        throw new IllegalArgumentException("No entry named " + name);
    }

    /**
     * The stored length of the last entry with a name, matching {@code ZipFile.getEntry} duplicate
     * precedence.
     *
     * @param name the entry name
     * @return the last matching record's stored length
     */
    public int latestStoredSize(String name) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (entry.nameText.equals(name)) {
                return entry.stored.length;
            }
        }
        throw new IllegalArgumentException("No entry named " + name);
    }

    /**
     * Finishes the archive. The central directory is written once; further calls hand out a fresh copy of
     * the same bytes, so a test can build, write and then mutate a copy.
     *
     * @return the complete ZIP file content
     */
    public byte[] build() {
        if (finished != null) {
            return finished.clone();
        }
        int centralSize = 0;
        for (Entry entry : entries) {
            centralSize += CENTRAL_HEADER_SIZE + entry.name.length + (zip64 ? 28 : 0);
        }
        int tail = IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE + comment.length
                + (zip64 ? ZIP64_END_SIZE + IndexFormat.ZIP64_LOCATOR_SIZE : 0);
        long total = (long) size + centralSize + tail;
        if (patchIndexFileLength) {
            patchIndex(total);
        }
        long centralOffset = size;
        for (Entry entry : entries) {
            writeCentralHeader(entry);
        }
        if (zip64) {
            long zip64End = size;
            writeInt(IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE);
            writeLong(ZIP64_END_SIZE - 12);
            writeShort(45);
            writeShort(45);
            writeInt(0);
            writeInt(0);
            writeLong(entries.size());
            writeLong(entries.size());
            writeLong(centralSize);
            writeLong(centralOffset);
            writeInt(IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE);
            writeInt(0);
            writeLong(zip64End);
            writeInt(1);
        }
        writeInt(IndexFormat.END_OF_CENTRAL_DIRECTORY_SIGNATURE);
        writeShort(0);
        writeShort(0);
        writeShort(zip64 ? 0xFFFF : entries.size());
        writeShort(zip64 ? 0xFFFF : entries.size());
        writeInt(zip64 ? -1 : centralSize);
        writeInt(zip64 ? -1 : (int) centralOffset);
        writeShort(comment.length);
        writeBytes(comment, 0, comment.length);
        if (size != total) {
            throw new IllegalStateException("Predicted " + total + " bytes but wrote " + size);
        }
        byte[] result = new byte[size];
        System.arraycopy(buffer, 0, result, 0, size);
        finished = result;
        return result.clone();
    }

    /**
     * Finishes the archive and writes it to a file.
     *
     * @param file the destination
     * @return the same file
     * @throws IOException if the file cannot be written
     */
    public File writeTo(File file) throws IOException {
        byte[] content = build();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
        return file;
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setInput(data);
            deflater.finish();
            byte[] out = new byte[Math.max(64, data.length * 2 + 64)];
            int length = deflater.deflate(out);
            byte[] result = new byte[length];
            System.arraycopy(out, 0, result, 0, length);
            return result;
        } finally {
            deflater.end();
        }
    }

    private static long crc(byte[] data) {
        CRC32 crc32 = new CRC32();
        crc32.update(data);
        return crc32.getValue();
    }

    private long add(String name, byte[] data, byte[] stored, int method) {
        return append(new Entry(name, stored, method, data.length, crc(data)));
    }

    private long append(Entry entry) {
        if (finished != null) {
            throw new IllegalStateException("The archive has already been built");
        }
        entry.localHeaderOffset = size;
        writeInt(IndexFormat.LOCAL_HEADER_SIGNATURE);
        writeShort(zip64 ? 45 : 20);
        writeShort(0);
        writeShort(entry.method);
        writeInt(DOS_TIME);
        writeInt((int) entry.crc);
        writeInt(zip64 ? -1 : entry.stored.length);
        writeInt(zip64 ? -1 : entry.size);
        writeShort(entry.name.length);
        writeShort(zip64 ? 20 : 0);
        writeBytes(entry.name, 0, entry.name.length);
        if (zip64) {
            writeShort(IndexFormat.ZIP64_EXTRA_FIELD_ID);
            writeShort(16);
            writeLong(entry.size);
            writeLong(entry.stored.length);
        }
        long dataOffset = size;
        writeBytes(entry.stored, 0, entry.stored.length);
        entries.add(entry);
        dataOffsets.put(entry.nameText, dataOffset);
        localHeaderOffsets.put(entry.nameText, entry.localHeaderOffset);
        return dataOffset;
    }

    private void writeCentralHeader(Entry entry) {
        writeInt(IndexFormat.CENTRAL_HEADER_SIGNATURE);
        writeShort(zip64 ? 45 : 20);
        writeShort(zip64 ? 45 : 20);
        writeShort(0);
        writeShort(entry.method);
        writeInt(DOS_TIME);
        writeInt((int) entry.crc);
        writeInt(zip64 ? -1 : entry.stored.length);
        writeInt(zip64 ? -1 : entry.size);
        writeShort(entry.name.length);
        writeShort(zip64 ? 28 : 0);
        writeShort(0);
        writeShort(0);
        writeShort(0);
        writeInt(0);
        writeInt(zip64 ? -1 : (int) entry.localHeaderOffset);
        writeBytes(entry.name, 0, entry.name.length);
        if (zip64) {
            writeShort(IndexFormat.ZIP64_EXTRA_FIELD_ID);
            writeShort(24);
            writeLong(entry.size);
            writeLong(entry.stored.length);
            writeLong(entry.localHeaderOffset);
        }
    }

    private void patchIndex(long total) {
        Long offset = dataOffsets.get(IndexFormat.INDEX_ENTRY_NAME);
        if (offset == null) {
            return;
        }
        int at = (int) (long) offset;
        if (at + IndexFormat.HEADER_SIZE > size || littleEndianInt(at) != IndexFormat.MAGIC) {
            return;
        }
        int field = at + IndexFormat.H_OUTER_FILE_LENGTH;
        for (int i = 0; i < 8; i++) {
            buffer[field + i] = (byte) (total >>> (8 * i));
        }
        for (Entry entry : entries) {
            if (entry.nameText.equals(IndexFormat.INDEX_ENTRY_NAME)) {
                byte[] patched = new byte[entry.stored.length];
                System.arraycopy(buffer, at, patched, 0, patched.length);
                entry.stored = patched;
                entry.crc = crc(patched);
            }
        }
    }

    private int littleEndianInt(int at) {
        return (buffer[at] & 0xFF) | ((buffer[at + 1] & 0xFF) << 8)
                | ((buffer[at + 2] & 0xFF) << 16) | ((buffer[at + 3] & 0xFF) << 24);
    }

    private void writeShort(int value) {
        ensure(2);
        buffer[size++] = (byte) value;
        buffer[size++] = (byte) (value >>> 8);
    }

    private void writeInt(int value) {
        ensure(4);
        for (int i = 0; i < 4; i++) {
            buffer[size++] = (byte) (value >>> (8 * i));
        }
    }

    private void writeLong(long value) {
        ensure(8);
        for (int i = 0; i < 8; i++) {
            buffer[size++] = (byte) (value >>> (8 * i));
        }
    }

    private void writeBytes(byte[] bytes, int offset, int length) {
        ensure(length);
        System.arraycopy(bytes, offset, buffer, size, length);
        size += length;
    }

    private void ensure(int count) {
        if (size + count > buffer.length) {
            byte[] grown = new byte[Math.max(buffer.length * 2, size + count)];
            System.arraycopy(buffer, 0, grown, 0, size);
            buffer = grown;
        }
    }

    /**
     * One entry of the archive under construction.
     */
    private static final class Entry {

        private final String nameText;
        private final byte[] name;
        private final int method;
        private final int size;
        private byte[] stored;
        private long crc;
        private long localHeaderOffset;

        private Entry(String name, byte[] stored, int method, int size, long crc) {
            this.nameText = name;
            this.name = name.getBytes(StandardCharsets.UTF_8);
            this.stored = stored;
            this.method = method;
            this.size = size;
            this.crc = crc;
        }
    }
}
