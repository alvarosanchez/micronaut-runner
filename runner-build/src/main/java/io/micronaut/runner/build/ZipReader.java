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

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A read-only view of an existing {@code .jar} or {@code .zip} file, parsed by hand rather than through
 * {@link java.util.zip.ZipFile}.
 *
 * <p>The packaging library needs two things the JDK does not give it. First, the exact offset of every
 * entry's first data byte, because the runner jar format indexes entry data by absolute offset and the
 * launcher reads it with no ZIP parsing at all; that offset is only knowable by reading each local file
 * header, whose name and extra field lengths may differ from the central directory record's. Second, the
 * central directory <em>order</em>, which must be preserved into the runner jar so that
 * {@link java.util.jar.JarFile#entries()} over a nested jar reports the original order.</p>
 *
 * <p>ZIP64 is handled explicitly: the end of central directory record is located by scanning back from the
 * end of the file, the ZIP64 end of central directory record is read whenever a locator precedes it or any
 * count, size or offset carries the {@link IndexFormat#ZIP64_MARKER} value, and the ZIP64 extended
 * information extra field is parsed for every central directory record that needs it.</p>
 *
 * <p>Entry names are decoded as UTF-8 regardless of the general purpose bit flag, which is what
 * {@link java.util.zip.ZipFile} does by default and what every jar in practice contains. Names that are
 * unsafe to extract or to use as logical names are rejected while the archive is being read, so that no
 * later stage of the pipeline has to remember to check (see {@link #isSafeEntryName(String)}).</p>
 *
 * <p>Instances hold an open file channel and must be closed. They are not thread-safe.</p>
 *
 * @since 1.0
 */
public final class ZipReader implements Closeable {

    /** Size of a local file header, before the name and the extra field. */
    private static final int LOCAL_HEADER_SIZE = 30;

    /** Size of a central directory file header, before the name, extra field and comment. */
    private static final int CENTRAL_HEADER_SIZE = 46;

    /** Size of a ZIP64 end of central directory record, before its extensible data sector. */
    private static final int ZIP64_END_SIZE = 56;

    /** Offset of the name length field within a local file header. */
    private static final int LOCAL_NAME_LENGTH_OFFSET = 26;

    /** Value a 16-bit ZIP field carries when the real value lives in a ZIP64 extra field. */
    private static final int ZIP64_MARKER_16 = 0xFFFF;

    /** General purpose bit 3: CRC and sizes follow the data in a descriptor. */
    private static final int FLAG_DATA_DESCRIPTOR = 1 << 3;

    /** General purpose bit 11: the entry name is UTF-8. */
    private static final int FLAG_UTF8 = 1 << 11;

    /** General purpose bits 1 and 2: compression-level hints defined only for DEFLATE. */
    private static final int FLAG_DEFLATE_OPTIONS = (1 << 1) | (1 << 2);

    /** The largest array the JVM reliably allocates; entries above this are rejected rather than truncated. */
    private static final int MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;

    /** Practical ceiling that prevents untrusted metadata preallocating an enormous object graph. */
    private static final int MAX_ENTRY_COUNT = 1_000_000;

    /** The manifest entry name, as the jar specification spells it. */
    private static final String MANIFEST_NAME = "META-INF/MANIFEST.MF";

    /** Prefix every signature file and the manifest share. */
    private static final String META_INF = "META-INF/";

    /** Name of the jar index the packager drops, since a nested jar never has a class path of its own. */
    private static final String INDEX_LIST_NAME = "META-INF/INDEX.LIST";

    private final Path path;
    private final FileChannel channel;
    private final long fileLength;
    private final List<LocalSpan> localSpans = new ArrayList<>();
    private final String comment;
    private final List<ZipEntryInfo> entries;
    private final Map<String, ZipEntryInfo> byName;
    private final boolean signatureFiles;
    private final Optional<Manifest> manifest;

    private ZipReader(Path path) throws IOException {
        this.path = path;
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        boolean initialised = false;
        try {
            this.fileLength = channel.size();
            long endOffset = findEndOfCentralDirectory();
            byte[] end = readFully(endOffset, IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE);
            int commentLength = readUnsignedShort(end, 20);
            this.comment = commentLength == 0
                    ? ""
                    : new String(readFully(endOffset + IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE, commentLength),
                            StandardCharsets.UTF_8);
            int diskNumber = readUnsignedShort(end, 4);
            int directoryDisk = readUnsignedShort(end, 6);
            long entriesOnDisk = readUnsignedShort(end, 8);
            long entryCount = readUnsignedShort(end, 10);
            if (diskNumber != 0 || directoryDisk != 0 || entriesOnDisk != entryCount) {
                throw malformed("multi-disk ZIP archives are not supported; a single-disk archive was required");
            }
            long directorySize = readUnsignedInt(end, 12);
            long directoryOffset = readUnsignedInt(end, 16);
            long recordStart = endOffset;
            long locatorOffset = endOffset - IndexFormat.ZIP64_LOCATOR_SIZE;
            long zip64End = -1;
            if (locatorOffset >= 0) {
                byte[] locator = readFully(locatorOffset, IndexFormat.ZIP64_LOCATOR_SIZE);
                if (readInt(locator, 0) == IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE) {
                    if (readUnsignedInt(locator, 4) != 0 || readUnsignedInt(locator, 16) != 1) {
                        throw malformed("multi-disk ZIP64 archives are not supported; a single-disk archive was required");
                    }
                    zip64End = readLong(locator, 8);
                }
            }
            // An entry count of 0xFFFF alone does not make the ZIP64 records mandatory: writers that
            // compare with > rather than >= leave an archive of exactly 65535 entries with the marker in
            // the 16-bit field and nothing behind it, and 65535 is the true count anyway. java.util.zip
            // reads those, so this does too. A marked directory size or offset is different: the real
            // value is nowhere else, so the ZIP64 record has to be there.
            boolean marked = directorySize == IndexFormat.ZIP64_MARKER
                    || directoryOffset == IndexFormat.ZIP64_MARKER;
            if (zip64End >= 0 || marked) {
                if (zip64End < 0 || zip64End > fileLength - ZIP64_END_SIZE) {
                    throw malformed("no ZIP64 end of central directory locator before the end record");
                }
                byte[] zip64 = readFully(zip64End, ZIP64_END_SIZE);
                if (readInt(zip64, 0) != IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                    throw malformed("the ZIP64 locator does not point at a ZIP64 end of central directory record");
                }
                long zip64RecordSize = readLong(zip64, 4);
                if (zip64RecordSize < ZIP64_END_SIZE - 12L) {
                    throw malformed("the ZIP64 end of central directory record size is too small: "
                            + zip64RecordSize);
                }
                long zip64RecordEnd = checkedAdd(zip64End,
                        checkedAdd(12, zip64RecordSize, "ZIP64 end of central directory record size"),
                        "ZIP64 end of central directory record size");
                if (zip64RecordEnd != locatorOffset) {
                    throw malformed("the ZIP64 end of central directory record size does not end at its locator");
                }
                if (readUnsignedInt(zip64, 16) != 0 || readUnsignedInt(zip64, 20) != 0
                        || readLong(zip64, 24) != readLong(zip64, 32)) {
                    throw malformed("multi-disk ZIP64 archives are not supported; a single-disk archive was required");
                }
                entryCount = readLong(zip64, 32);
                directorySize = readLong(zip64, 40);
                directoryOffset = readLong(zip64, 48);
                recordStart = zip64End;
            }
            if (entryCount < 0 || directorySize < 0 || directoryOffset < 0) {
                throw malformed("the end of central directory record has a negative count, size or offset");
            }
            long delta = offsetCorrection(recordStart, directorySize, directoryOffset, entryCount);
            long directoryStart = checkedAdd(directoryOffset, delta, "central directory offset correction");
            if (directoryStart > recordStart || directorySize != recordStart - directoryStart) {
                throw malformed("the central directory does not end at its end record");
            }
            this.entries = readCentralDirectory(directoryStart, directorySize, entryCount, delta);
            Map<String, ZipEntryInfo> index = new LinkedHashMap<>(Math.max(16, entries.size() * 2));
            boolean signed = false;
            for (ZipEntryInfo entry : entries) {
                index.putIfAbsent(entry.name(), entry);
                signed = signed || isSignatureFile(entry.name());
            }
            this.byName = index;
            this.signatureFiles = signed;
            this.manifest = readManifest();
            initialised = true;
        } finally {
            if (!initialised) {
                channel.close();
            }
        }
    }

    /**
     * Opens an archive for reading.
     *
     * @param path the archive file
     * @return an open reader the caller must close
     * @throws IOException          if the file cannot be read or is not a well-formed ZIP archive, including
     *                              when it contains an entry name that is unsafe to extract
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public static ZipReader open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return new ZipReader(path);
    }

    /**
     * Opens an archive for reading.
     *
     * @param file the archive file
     * @return an open reader the caller must close
     * @throws IOException          if the file cannot be read or is not a well-formed ZIP archive
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public static ZipReader open(File file) throws IOException {
        Objects.requireNonNull(file, "file");
        return new ZipReader(file.toPath());
    }

    /**
     * Whether a name is safe to use as an entry name, both for extraction to a directory and as a logical
     * name in the index.
     *
     * <p>Rejected are: the empty name, names starting with {@code '/'} (absolute), Windows drive-letter
     * prefixes such as {@code C:/}, names containing a backslash or a NUL character, empty path segments
     * ({@code a//b}) and any {@code .} or {@code ..} segment. A single trailing {@code '/'}, which marks a
     * directory entry, is allowed.</p>
     *
     * @param name the candidate entry name
     * @return {@code true} when the name is safe
     */
    public static boolean isSafeEntryName(String name) {
        if (name == null || name.isEmpty() || name.charAt(0) == '/') {
            return false;
        }
        if (name.length() >= 2 && name.charAt(1) == ':' && isDriveLetter(name.charAt(0))) {
            return false;
        }
        int length = name.length();
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            if (c == '\\' || c == 0) {
                return false;
            }
        }
        // A single trailing '/' marks a directory entry; every other segment must be non-empty.
        int limit = name.charAt(length - 1) == '/' ? length - 1 : length;
        int segmentStart = 0;
        for (int i = 0; i <= limit; i++) {
            if (i == limit || name.charAt(i) == '/') {
                if (!isSafeSegment(name, segmentStart, i)) {
                    return false;
                }
                segmentStart = i + 1;
            }
        }
        return true;
    }

    /**
     * Checks an entry name with {@link #isSafeEntryName(String)} and fails with a message naming both the
     * archive and the offending entry.
     *
     * @param name    the candidate entry name
     * @param archive a description of the archive the name came from, used in the error message
     * @throws IOException if the name is not safe
     */
    public static void requireSafeEntryName(String name, String archive) throws IOException {
        if (!isSafeEntryName(name)) {
            throw new IOException("Unsafe entry name in " + archive + ": '" + name
                    + "'. Entry names must be relative, use '/' separators and contain no '.' or '..' segment");
        }
    }

    /**
     * Whether an entry name is a jar signature file, which the packager drops because the runner jar is
     * repacked and its nested jars can no longer be verified.
     *
     * <p>The jar specification treats these names case-insensitively, so this check does too. The manifest
     * itself is deliberately <em>not</em> included: per-jar manifests are kept verbatim.</p>
     *
     * @param name the entry name
     * @return {@code true} for {@code META-INF/*.SF}, {@code *.DSA}, {@code *.RSA}, {@code *.EC} and
     *         {@code META-INF/SIG-*}
     */
    public static boolean isSignatureFile(String name) {
        if (name == null || name.length() <= META_INF.length()) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith(META_INF)) {
            return false;
        }
        String simple = upper.substring(META_INF.length());
        if (simple.indexOf('/') >= 0) {
            return false;
        }
        return simple.endsWith(".SF")
                || simple.endsWith(".DSA")
                || simple.endsWith(".RSA")
                || simple.endsWith(".EC")
                || simple.startsWith("SIG-");
    }

    /**
     * Whether an entry name is the jar index the packager drops, matched case-insensitively for the same
     * reason signature files are.
     *
     * @param name the entry name
     * @return {@code true} for {@code META-INF/INDEX.LIST}
     */
    public static boolean isIndexList(String name) {
        return name != null && name.equalsIgnoreCase(INDEX_LIST_NAME);
    }

    /**
     * The archive this reader was opened on.
     *
     * @return the archive path
     */
    public Path path() {
        return path;
    }

    /**
     * The length of the archive file in bytes.
     *
     * @return the file length
     */
    public long fileLength() {
        return fileLength;
    }

    /**
     * The archive comment, decoded as UTF-8.
     *
     * @return the comment, or the empty string when the archive has none
     */
    public String comment() {
        return comment;
    }

    /**
     * Every entry of the archive, in central directory order.
     *
     * <p>That order is the one {@link java.util.jar.JarFile#entries()} reports, so the packager preserves it
     * into the runner jar rather than sorting.</p>
     *
     * @return an immutable list of entries
     */
    public List<ZipEntryInfo> entries() {
        return entries;
    }

    /**
     * Looks an entry up by its exact name.
     *
     * @param name the entry name
     * @return the entry, or empty when the archive has no such entry; when an archive contains the same name
     *         twice, the first occurrence in central directory order wins
     */
    public Optional<ZipEntryInfo> entry(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * Whether the archive carries jar signature files, which means the packager must record
     * {@link IndexFormat#JAR_FLAG_SIGNED_ORIGINAL} for it.
     *
     * @return {@code true} when at least one entry matches {@link #isSignatureFile(String)}
     */
    public boolean hasSignatureFiles() {
        return signatureFiles;
    }

    /**
     * The archive's manifest, parsed once when the reader was opened.
     *
     * <p>The manifest entry is looked up case-insensitively, as {@link java.util.jar.JarFile} does. The
     * returned object is the reader's own instance and is mutable; callers must not modify it.</p>
     *
     * @return the manifest, or empty when the archive has no {@code META-INF/MANIFEST.MF}
     */
    public Optional<Manifest> manifest() {
        return manifest;
    }

    /**
     * Reads an entry's data exactly as it is stored in the archive, without decompressing it.
     *
     * @param entry an entry of this archive
     * @return {@link ZipEntryInfo#compressedSize()} bytes read from {@link ZipEntryInfo#dataOffset()}
     * @throws IOException if the data cannot be read, or the entry is larger than the largest Java array
     */
    public byte[] readRaw(ZipEntryInfo entry) throws IOException {
        Objects.requireNonNull(entry, "entry");
        long size = entry.compressedSize();
        if (size > MAX_ARRAY_LENGTH) {
            throw new IOException("Entry '" + entry.name() + "' of " + path + " is too large to read into memory: "
                    + size + " bytes");
        }
        return readFully(entry.dataOffset(), (int) size);
    }

    /**
     * Reads an entry's content, decompressing it when it is deflated and verifying its CRC-32.
     *
     * @param entry an entry of this archive
     * @return the verified uncompressed content, of length {@link ZipEntryInfo#uncompressedSize()}
     * @throws IOException if the data cannot be read, its CRC-32 does not match, the compression method is
     *                     neither stored nor deflated, or the deflate stream is truncated, corrupt,
     *                     overproduces, or does not consume its complete recorded compressed region
     */
    public byte[] read(ZipEntryInfo entry) throws IOException {
        Objects.requireNonNull(entry, "entry");
        if (entry.method() == IndexFormat.METHOD_STORED) {
            CRC32 crc = new CRC32();
            byte[] result = readFully(entry.dataOffset(), checkedArraySize(entry, entry.compressedSize()), crc);
            verifyCrc(entry, crc.getValue());
            return result;
        }
        if (entry.method() != IndexFormat.METHOD_DEFLATED) {
            throw new IOException("Entry '" + entry.name() + "' of " + path + " uses unsupported compression method "
                    + entry.method());
        }
        int resultSize = checkedArraySize(entry, entry.uncompressedSize());
        byte[] compressed = readRaw(entry);
        byte[] result = new byte[resultSize];
        CRC32 crc = new CRC32();
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            int total = 0;
            byte[] probe = new byte[1];
            while (!inflater.finished()) {
                int read;
                if (total < result.length) {
                    read = inflater.inflate(result, total, result.length - total);
                } else {
                    read = inflater.inflate(probe, 0, 1);
                }
                if (read > 0) {
                    if (total == result.length) {
                        throw new IOException("Deflate stream for entry '" + entry.name() + "' of " + path
                                + " produces more than the recorded " + result.length + " bytes");
                    }
                    crc.update(result, total, read);
                    total += read;
                } else if (inflater.finished()) {
                    // The terminal block may produce no plaintext, including for an empty entry.
                    continue;
                } else if (inflater.needsDictionary()) {
                    throw new IOException("Deflate stream for entry '" + entry.name() + "' of " + path
                            + " requires a dictionary");
                } else if (inflater.needsInput()) {
                    throw truncatedDeflate(entry, result.length, total);
                } else {
                    throw new IOException("Deflate stream for entry '" + entry.name() + "' of " + path
                            + " made no progress after " + total + " bytes");
                }
            }
            if (total != result.length) {
                throw truncatedDeflate(entry, result.length, total);
            }
            int remaining = inflater.getRemaining();
            if (remaining != 0) {
                throw new IOException("Deflate stream for entry '" + entry.name() + "' of " + path
                        + " ended with " + remaining + " unused compressed bytes");
            }
            verifyCrc(entry, crc.getValue());
        } catch (DataFormatException e) {
            throw new IOException("Corrupt deflate stream for entry '" + entry.name() + "' of " + path, e);
        } finally {
            inflater.end();
        }
        return result;
    }

    private int checkedArraySize(ZipEntryInfo entry, long size) throws IOException {
        if (size > MAX_ARRAY_LENGTH) {
            throw new IOException("Entry '" + entry.name() + "' of " + path + " is too large to read into memory: "
                    + size + " bytes");
        }
        return (int) size;
    }

    private void verifyCrc(ZipEntryInfo entry, long actual) throws IOException {
        if (actual != entry.crc32()) {
            throw new IOException("Entry '" + entry.name() + "' of " + path
                    + " does not match its recorded CRC-32: expected " + Long.toHexString(entry.crc32())
                    + ", computed " + Long.toHexString(actual));
        }
    }

    private IOException truncatedDeflate(ZipEntryInfo entry, int expected, int actual) {
        return new IOException("Truncated deflate stream for entry '" + entry.name() + "' of " + path
                + ": expected " + expected + " bytes, inflated " + actual);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private static boolean isDriveLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isSafeSegment(String name, int start, int end) {
        int length = end - start;
        if (length == 0) {
            return false;
        }
        if (length == 1 && name.charAt(start) == '.') {
            return false;
        }
        return !(length == 2 && name.charAt(start) == '.' && name.charAt(start + 1) == '.');
    }

    private static int readUnsignedShort(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF) | ((buffer[offset + 1] & 0xFF) << 8);
    }

    private static int readInt(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF)
                | ((buffer[offset + 1] & 0xFF) << 8)
                | ((buffer[offset + 2] & 0xFF) << 16)
                | ((buffer[offset + 3] & 0xFF) << 24);
    }

    private static long readUnsignedInt(byte[] buffer, int offset) {
        return readInt(buffer, offset) & IndexFormat.ZIP64_MARKER;
    }

    private static long readLong(byte[] buffer, int offset) {
        return (readInt(buffer, offset) & IndexFormat.ZIP64_MARKER)
                | ((long) readInt(buffer, offset + 4) << 32);
    }

    private IOException malformed(String detail) {
        return new IOException("Cannot read " + path + " as a ZIP archive: " + detail);
    }

    private byte[] readFully(long position, int length) throws IOException {
        return readFully(position, length, null);
    }

    private byte[] readFully(long position, int length, CRC32 crc) throws IOException {
        if (position < 0 || length < 0 || position > fileLength - length) {
            throw malformed("a read of " + length + " bytes at offset " + position + " runs past the end of the file");
        }
        byte[] result = new byte[length];
        ByteBuffer buffer = ByteBuffer.wrap(result);
        long at = position;
        while (buffer.hasRemaining()) {
            int start = buffer.position();
            int read = channel.read(buffer, at);
            if (read < 0) {
                throw malformed("unexpected end of file at offset " + at);
            }
            if (crc != null) {
                crc.update(result, start, read);
            }
            at += read;
        }
        return result;
    }

    private long findEndOfCentralDirectory() throws IOException {
        int window = (int) Math.min(fileLength,
                (long) IndexFormat.MAX_COMMENT_SIZE + IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE);
        if (window < IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE) {
            throw malformed("the file is too short to contain an end of central directory record");
        }
        long start = fileLength - window;
        byte[] tail = readFully(start, window);
        for (int i = window - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE; i >= 0; i--) {
            if (readInt(tail, i) != IndexFormat.END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                continue;
            }
            int commentLength = readUnsignedShort(tail, i + 20);
            if (start + i + IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE + commentLength == fileLength) {
                return start + i;
            }
        }
        throw malformed("no end of central directory record in the last " + window + " bytes");
    }

    /**
     * Returns the number of bytes every recorded offset has to be shifted by.
     *
     * <p>An archive with data prepended to it - a self-extracting stub, or a launcher script - keeps the
     * offsets it had before the prefix was added. When the recorded central directory offset does not hold a
     * central directory but the position implied by the end record does, every offset in the archive is off
     * by that difference.</p>
     */
    private long offsetCorrection(long recordStart, long directorySize, long directoryOffset, long entryCount)
            throws IOException {
        if (entryCount == 0) {
            return directorySize == 0 ? recordStart - directoryOffset : 0;
        }
        long implied = recordStart - directorySize;
        if (implied == directoryOffset || implied < 0) {
            return 0;
        }
        if (hasSignatureAt(directoryOffset, IndexFormat.CENTRAL_HEADER_SIGNATURE)) {
            return 0;
        }
        if (hasSignatureAt(implied, IndexFormat.CENTRAL_HEADER_SIGNATURE)) {
            return implied - directoryOffset;
        }
        return 0;
    }

    private boolean hasSignatureAt(long offset, int signature) throws IOException {
        if (offset < 0 || offset > fileLength - 4) {
            return false;
        }
        return readInt(readFully(offset, 4), 0) == signature;
    }

    private List<ZipEntryInfo> readCentralDirectory(long directoryStart, long directorySize, long entryCount,
            long delta) throws IOException {
        if (directorySize > MAX_ARRAY_LENGTH) {
            throw malformed("the central directory is too large to read: " + directorySize + " bytes");
        }
        if (entryCount > directorySize / CENTRAL_HEADER_SIZE) {
            throw malformed("the declared entry count " + entryCount + " cannot fit in the " + directorySize
                    + "-byte central directory");
        }
        if (entryCount > MAX_ENTRY_COUNT) {
            throw malformed("the archive declares too many entries: " + entryCount);
        }
        byte[] directory = readFully(directoryStart, (int) directorySize);
        List<ZipEntryInfo> result = new ArrayList<>((int) entryCount);
        int position = 0;
        for (long i = 0; i < entryCount; i++) {
            if (position > directory.length - CENTRAL_HEADER_SIZE) {
                throw malformed("the central directory ends after " + i + " of " + entryCount + " records");
            }
            if (readInt(directory, position) != IndexFormat.CENTRAL_HEADER_SIGNATURE) {
                throw malformed("no central directory file header at directory offset " + position);
            }
            int nameLength = readUnsignedShort(directory, position + 28);
            int extraLength = readUnsignedShort(directory, position + 30);
            int commentLength = readUnsignedShort(directory, position + 32);
            int recordSize = CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength;
            if (recordSize > directory.length - position) {
                throw malformed("central directory record " + i + " runs past the end of the directory");
            }
            result.add(readCentralDirectoryRecord(directory, position, nameLength, extraLength, delta,
                    directoryStart));
            position += recordSize;
        }
        if (position != directory.length) {
            int remaining = directory.length - position;
            boolean digitalSignature = remaining >= 6
                    && readInt(directory, position) == 0x05054B50
                    && readUnsignedShort(directory, position + 4) == remaining - 6;
            if (!digitalSignature) {
                throw malformed("the central directory has " + remaining
                        + " trailing bytes not described by its entry count");
            }
        }
        validateLocalSpans(directoryStart);
        return List.copyOf(result);
    }

    private ZipEntryInfo readCentralDirectoryRecord(byte[] directory, int position, int nameLength, int extraLength,
            long delta, long directoryStart) throws IOException {
        int flags = readUnsignedShort(directory, position + 8);
        int method = readUnsignedShort(directory, position + 10);
        int time = readUnsignedShort(directory, position + 12);
        int date = readUnsignedShort(directory, position + 14);
        long crc = readUnsignedInt(directory, position + 16);
        long compressedSize = readUnsignedInt(directory, position + 20);
        long uncompressedSize = readUnsignedInt(directory, position + 24);
        int diskStart = readUnsignedShort(directory, position + 34);
        long effectiveDiskStart = diskStart;
        long localHeaderOffset = readUnsignedInt(directory, position + 42);
        int nameStart = position + CENTRAL_HEADER_SIZE;
        String name = decodeName(directory, nameStart, nameLength, "central directory");
        requireSafeEntryName(name, path.toString());

        boolean needsZip64 = uncompressedSize == IndexFormat.ZIP64_MARKER
                || compressedSize == IndexFormat.ZIP64_MARKER
                || localHeaderOffset == IndexFormat.ZIP64_MARKER
                || diskStart == ZIP64_MARKER_16;
        if (needsZip64) {
            int extraStart = position + CENTRAL_HEADER_SIZE + nameLength;
            long[] zip64 = readZip64Extra(directory, extraStart, extraLength, name,
                    uncompressedSize == IndexFormat.ZIP64_MARKER,
                    compressedSize == IndexFormat.ZIP64_MARKER,
                    localHeaderOffset == IndexFormat.ZIP64_MARKER,
                    diskStart == ZIP64_MARKER_16);
            uncompressedSize = zip64[0] < 0 ? uncompressedSize : zip64[0];
            compressedSize = zip64[1] < 0 ? compressedSize : zip64[1];
            localHeaderOffset = zip64[2] < 0 ? localHeaderOffset : zip64[2];
            effectiveDiskStart = zip64[3] < 0 ? effectiveDiskStart : zip64[3];
        }
        if (method != IndexFormat.METHOD_STORED && method != IndexFormat.METHOD_DEFLATED) {
            throw malformed("entry '" + name + "' uses unsupported compression method " + method);
        }
        int supportedFlags = FLAG_DATA_DESCRIPTOR | FLAG_UTF8
                | (method == IndexFormat.METHOD_DEFLATED ? FLAG_DEFLATE_OPTIONS : 0);
        if ((flags & ~supportedFlags) != 0) {
            throw malformed("entry '" + name + "' uses unsupported general purpose flags 0x"
                    + Integer.toHexString(flags & ~supportedFlags));
        }
        if (effectiveDiskStart != 0) {
            throw malformed("entry '" + name + "' starts on disk " + effectiveDiskStart
                    + "; only single-disk ZIP archives are supported");
        }
        if (method == IndexFormat.METHOD_STORED && compressedSize != uncompressedSize) {
            throw malformed("STORED entry '" + name + "' has compressed size " + compressedSize
                    + " but uncompressed size " + uncompressedSize);
        }
        localHeaderOffset = checkedAdd(localHeaderOffset, delta,
                "local header offset of entry '" + name + "'");
        long dataOffset = resolveDataOffset(name, directory, nameStart, nameLength, localHeaderOffset,
                flags, method, crc, compressedSize, uncompressedSize, directoryStart);
        boolean directoryEntry = name.charAt(name.length() - 1) == '/';
        return new ZipEntryInfo(name, method, compressedSize, uncompressedSize, crc, (date << 16) | time,
                localHeaderOffset, dataOffset, directoryEntry);
    }

    /**
     * Parses the ZIP64 extended information extra field.
     *
     * <p>Its fields appear in a fixed order - uncompressed size, compressed size, local header offset, disk
     * number - and only the ones whose 32-bit counterpart carries the marker are present, so the field can
     * only be decoded together with the record it belongs to.</p>
     *
     * @return the four values, in that order, with {@code -1} where the caller did not ask for one
     */
    private long[] readZip64Extra(byte[] buffer, int start, int length, String name, boolean wantUncompressed,
            boolean wantCompressed, boolean wantOffset, boolean wantDisk) throws IOException {
        long[] values = {-1, -1, -1, -1};
        int position = start;
        int end = start + length;
        while (position + 4 <= end) {
            int id = readUnsignedShort(buffer, position);
            int size = readUnsignedShort(buffer, position + 2);
            int dataStart = position + 4;
            if (size > end - dataStart) {
                throw malformed("extra field of entry '" + name + "' runs past the end of the record");
            }
            if (id == IndexFormat.ZIP64_EXTRA_FIELD_ID) {
                int at = dataStart;
                int limit = dataStart + size;
                if (wantUncompressed) {
                    at = requireZip64Field(values, 0, buffer, at, limit, name);
                }
                if (wantCompressed) {
                    at = requireZip64Field(values, 1, buffer, at, limit, name);
                }
                if (wantOffset) {
                    at = requireZip64Field(values, 2, buffer, at, limit, name);
                }
                if (wantDisk) {
                    if (at + 4 > limit) {
                        throw malformed("the ZIP64 extra field of entry '" + name + "' is too short");
                    }
                    values[3] = readUnsignedInt(buffer, at);
                }
                return values;
            }
            position = dataStart + size;
        }
        throw malformed("entry '" + name + "' needs a ZIP64 extended information extra field but has none");
    }

    private int requireZip64Field(long[] values, int slot, byte[] buffer, int at, int limit, String name)
            throws IOException {
        if (at + 8 > limit) {
            throw malformed("the ZIP64 extra field of entry '" + name + "' is too short");
        }
        long value = readLong(buffer, at);
        if (value < 0) {
            throw malformed("entry '" + name + "' declares a size or offset larger than 2^63 bytes");
        }
        values[slot] = value;
        return at + 8;
    }

    private long resolveDataOffset(String name, byte[] centralDirectory, int centralNameStart, int centralNameLength,
            long localHeaderOffset, int flags, int method, long crc, long compressedSize, long uncompressedSize,
            long directoryStart) throws IOException {
        byte[] header = readFully(localHeaderOffset, LOCAL_HEADER_SIZE);
        if (readInt(header, 0) != IndexFormat.LOCAL_HEADER_SIGNATURE) {
            throw malformed("no local file header for entry '" + name + "' at offset " + localHeaderOffset);
        }
        int localFlags = readUnsignedShort(header, 6);
        int localMethod = readUnsignedShort(header, 8);
        if (localFlags != flags) {
            throw localDisagreement(name, "flags", flags, localFlags);
        }
        if (localMethod != method) {
            throw localDisagreement(name, "method", method, localMethod);
        }
        long localCrc = readUnsignedInt(header, 14);
        long localCompressedSize = readUnsignedInt(header, 18);
        long localUncompressedSize = readUnsignedInt(header, 22);
        int nameLength = readUnsignedShort(header, LOCAL_NAME_LENGTH_OFFSET);
        int extraLength = readUnsignedShort(header, LOCAL_NAME_LENGTH_OFFSET + 2);
        long variableLength = (long) nameLength + extraLength;
        if (localHeaderOffset > directoryStart - LOCAL_HEADER_SIZE
                || variableLength > directoryStart - localHeaderOffset - LOCAL_HEADER_SIZE) {
            throw malformed("the local header of entry '" + name + "' runs into the central directory");
        }
        long dataOffset = localHeaderOffset + LOCAL_HEADER_SIZE + variableLength;
        byte[] localVariable = readFully(localHeaderOffset + LOCAL_HEADER_SIZE, nameLength + extraLength);
        String localName = decodeName(localVariable, 0, nameLength, "local header of entry '" + name + "'");
        if (nameLength != centralNameLength
                || !Arrays.equals(centralDirectory, centralNameStart, centralNameStart + centralNameLength,
                        localVariable, 0, nameLength)) {
            throw malformed("the local header name '" + localName + "' of entry '" + name
                    + "' disagrees with its central directory name");
        }
        if ((flags & FLAG_DATA_DESCRIPTOR) == 0) {
            if (localCompressedSize == IndexFormat.ZIP64_MARKER
                    || localUncompressedSize == IndexFormat.ZIP64_MARKER) {
                long[] localZip64 = readZip64Extra(localVariable, nameLength, extraLength, name,
                        localUncompressedSize == IndexFormat.ZIP64_MARKER,
                        localCompressedSize == IndexFormat.ZIP64_MARKER, false, false);
                localUncompressedSize = localZip64[0] < 0 ? localUncompressedSize : localZip64[0];
                localCompressedSize = localZip64[1] < 0 ? localCompressedSize : localZip64[1];
            }
            if (localCrc != crc) {
                throw localDisagreement(name, "CRC", crc, localCrc);
            }
            if (localCompressedSize != compressedSize) {
                throw localDisagreement(name, "compressed-size", compressedSize, localCompressedSize);
            }
            if (localUncompressedSize != uncompressedSize) {
                throw localDisagreement(name, "uncompressed-size", uncompressedSize, localUncompressedSize);
            }
        } else {
            if (localCrc != 0 && localCrc != crc) {
                throw localDisagreement(name, "CRC", crc, localCrc);
            }
            if (localCompressedSize != 0 && localCompressedSize != compressedSize
                    && localCompressedSize != IndexFormat.ZIP64_MARKER) {
                throw localDisagreement(name, "compressed-size", compressedSize, localCompressedSize);
            }
            if (localUncompressedSize != 0 && localUncompressedSize != uncompressedSize
                    && localUncompressedSize != IndexFormat.ZIP64_MARKER) {
                throw localDisagreement(name, "uncompressed-size", uncompressedSize, localUncompressedSize);
            }
            if (localCompressedSize == IndexFormat.ZIP64_MARKER
                    || localUncompressedSize == IndexFormat.ZIP64_MARKER) {
                long[] localZip64 = readZip64Extra(localVariable, nameLength, extraLength, name,
                        localUncompressedSize == IndexFormat.ZIP64_MARKER,
                        localCompressedSize == IndexFormat.ZIP64_MARKER, false, false);
                validateDescriptorZip64Placeholder(name, "uncompressed-size", localZip64[0], uncompressedSize);
                validateDescriptorZip64Placeholder(name, "compressed-size", localZip64[1], compressedSize);
            }
        }
        if (compressedSize > directoryStart - dataOffset) {
            throw malformed("the compressed data of entry '" + name + "' runs into the central directory");
        }
        long dataEnd = dataOffset + compressedSize;
        int descriptorLength = 0;
        if ((flags & FLAG_DATA_DESCRIPTOR) != 0) {
            descriptorLength = validateDataDescriptor(name, dataEnd, directoryStart,
                    crc, compressedSize, uncompressedSize);
        }
        localSpans.add(new LocalSpan(name, localHeaderOffset,
                checkedAdd(dataEnd, descriptorLength, "end of entry '" + name + "'")));
        return dataOffset;
    }

    private int validateDataDescriptor(String name, long offset, long directoryStart, long crc,
            long compressedSize, long uncompressedSize) throws IOException {
        int available = (int) Math.min(24, directoryStart - offset);
        if (available < 12) {
            throw malformed("the data descriptor of entry '" + name + "' runs into the central directory");
        }
        byte[] descriptor = readFully(offset, available);
        boolean signed = available >= 4 && readInt(descriptor, 0) == 0x08074B50;
        if (signed && matchesDescriptor(descriptor, 4, false, crc, compressedSize, uncompressedSize)) {
            return 16;
        }
        if (matchesDescriptor(descriptor, 0, false, crc, compressedSize, uncompressedSize)) {
            return 12;
        }
        if (signed && matchesDescriptor(descriptor, 4, true, crc, compressedSize, uncompressedSize)) {
            return 24;
        }
        if (matchesDescriptor(descriptor, 0, true, crc, compressedSize, uncompressedSize)) {
            return 20;
        }
        throw malformed("the data descriptor of entry '" + name
                + "' disagrees with its central directory CRC or sizes");
    }

    private void validateLocalSpans(long directoryStart) throws IOException {
        List<LocalSpan> ordered = new ArrayList<>(localSpans);
        ordered.sort(Comparator.comparingLong(LocalSpan::start));
        List<LocalSpan> distinct = new ArrayList<>(ordered.size());
        for (LocalSpan span : ordered) {
            if (!distinct.isEmpty()) {
                LocalSpan previous = distinct.get(distinct.size() - 1);
                if (span.start() == previous.start()) {
                    if (span.end() != previous.end()) {
                        throw malformed("entry '" + previous.name() + "' overlaps entry '" + span.name() + "'");
                    }
                    continue;
                }
            }
            distinct.add(span);
        }
        for (int i = 0; i < distinct.size(); i++) {
            LocalSpan current = distinct.get(i);
            long limit = i + 1 < distinct.size() ? distinct.get(i + 1).start() : directoryStart;
            if (current.end() > limit) {
                String next = i + 1 < distinct.size()
                        ? "entry '" + distinct.get(i + 1).name() + "'"
                        : "the central directory";
                throw malformed("entry '" + current.name() + "' overlaps " + next);
            }
        }
    }

    private long checkedAdd(long left, long right, String description) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw malformed(description + " overflows a 64-bit file offset");
        }
    }

    private static boolean matchesDescriptor(byte[] descriptor, int start, boolean zip64, long crc,
            long compressedSize, long uncompressedSize) {
        int size = zip64 ? 20 : 12;
        if (start + size > descriptor.length || readUnsignedInt(descriptor, start) != crc) {
            return false;
        }
        if (zip64) {
            return readLong(descriptor, start + 4) == compressedSize
                    && readLong(descriptor, start + 12) == uncompressedSize;
        }
        return readUnsignedInt(descriptor, start + 4) == compressedSize
                && readUnsignedInt(descriptor, start + 8) == uncompressedSize;
    }

    private IOException localDisagreement(String name, String field, long central, long local) {
        return malformed("the local " + field + " of entry '" + name + "' is " + local
                + " but its central directory " + field + " is " + central);
    }

    private void validateDescriptorZip64Placeholder(String name, String field, long local, long central)
            throws IOException {
        if (local >= 0 && local != 0 && local != central) {
            throw localDisagreement(name, field + " ZIP64 placeholder", central, local);
        }
    }

    private String decodeName(byte[] buffer, int offset, int length, String location) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(buffer, offset, length))
                    .toString();
        } catch (CharacterCodingException e) {
            throw malformed("invalid UTF-8 in the " + location + " entry name");
        }
    }

    private Optional<Manifest> readManifest() throws IOException {
        ZipEntryInfo entry = byName.get(MANIFEST_NAME);
        if (entry == null) {
            for (ZipEntryInfo candidate : entries) {
                if (candidate.name().equalsIgnoreCase(MANIFEST_NAME)) {
                    entry = candidate;
                    break;
                }
            }
        }
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(new Manifest(new ByteArrayInputStream(read(entry))));
    }

    private record LocalSpan(String name, long start, long end) {
    }
}
