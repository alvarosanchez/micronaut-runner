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
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
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
 * <h2>Mapping, threads and lifetime</h2>
 * <p>Opening an archive maps the whole file read-only into a {@link MemorySegment} owned by a confined
 * {@link Arena}. Every local file header is checked against its central directory record, and every data
 * descriptor against the sizes and CRC-32 it repeats, by reading them from that mapping, so opening costs no
 * system call and no array per entry. The central directory is copied out of the mapping once, and the
 * manifest is only read when {@link #manifest()} asks for it. {@link #read(ZipEntryInfo)} copies from the
 * mapping as well; {@link #transfer(ZipEntryInfo, OutputStream)} streams through the file channel the
 * reader also keeps open, in 64 KiB chunks.</p>
 *
 * <p>A reader is confined to the thread that opened it: only that thread may use it and close it, and it
 * must never be handed to another thread, whose reads of the mapping would fail. Every reader must be
 * closed, which unmaps the file and then closes the channel. The mapping lives outside the heap and the
 * garbage collector never releases it, and on Windows a mapped file can be neither moved nor deleted.</p>
 *
 * <p>The file must not change while it is open. A read of the mapping that a concurrent truncation left
 * without backing fails with an {@link IOException}; payloads are always copied out of the mapping before
 * they are inflated or checksummed, so a truncation never faults inside native code.</p>
 *
 * @since 1.0
 */
final class ZipReader implements Closeable {

    private static final int TRANSFER_BUFFER_SIZE = 64 * 1024;

    private static final int MAX_MANIFEST_SIZE = 16 * 1024 * 1024;

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

    /** The optional signature of a data descriptor. */
    private static final int DATA_DESCRIPTOR_SIGNATURE = 0x08074B50;

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

    /** ZIP integers are little-endian and sit at any offset, so every read of the mapping uses these. */
    private static final ValueLayout.OfShort SHORT_LE =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt INT_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG_LE =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final Path path;
    private final FileChannel channel;
    private final Arena arena;
    private final MemorySegment mapping;
    private final long fileLength;
    private final String comment;
    private final List<ZipEntryInfo> entries;
    private final Map<String, ZipEntryInfo> byName;
    private final boolean signatureFiles;
    /** The parsed manifest, {@code null} until {@link #manifest()} has read it once. */
    private Optional<Manifest> manifest;
    /** The strict UTF-8 decoder for names that are not plain ASCII, created at the first such name. */
    private CharsetDecoder utf8;
    private byte[] transferInput;
    private byte[] transferOutput;

    /**
     * Parses an archive that {@link #map(Path)} has opened and mapped. The caller closes the channel and the
     * arena when this throws.
     */
    private ZipReader(Path path, FileChannel channel, Arena arena, MemorySegment mapping) throws IOException {
        this.path = path;
        this.channel = channel;
        this.arena = arena;
        this.mapping = mapping;
        this.fileLength = mapping.byteSize();
        try {
            long endOffset = findEndOfCentralDirectory();
            int commentLength = readUnsignedShort(endOffset + 20);
            this.comment = commentLength == 0
                    ? ""
                    : new String(bytesAt(endOffset + IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE, commentLength),
                            StandardCharsets.UTF_8);
            int diskNumber = readUnsignedShort(endOffset + 4);
            int directoryDisk = readUnsignedShort(endOffset + 6);
            long entriesOnDisk = readUnsignedShort(endOffset + 8);
            long entryCount = readUnsignedShort(endOffset + 10);
            if (diskNumber != 0 || directoryDisk != 0 || entriesOnDisk != entryCount) {
                throw malformed("multi-disk ZIP archives are not supported; a single-disk archive was required");
            }
            long directorySize = readUnsignedInt(endOffset + 12);
            long directoryOffset = readUnsignedInt(endOffset + 16);
            long recordStart = endOffset;
            long locatorOffset = endOffset - IndexFormat.ZIP64_LOCATOR_SIZE;
            long zip64End = -1;
            if (locatorOffset >= 0
                    && readInt(locatorOffset) == IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE) {
                if (readUnsignedInt(locatorOffset + 4) != 0 || readUnsignedInt(locatorOffset + 16) != 1) {
                    throw malformed("multi-disk ZIP64 archives are not supported; a single-disk archive was required");
                }
                zip64End = readLong(locatorOffset + 8);
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
                if (readInt(zip64End) != IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                    throw malformed("the ZIP64 locator does not point at a ZIP64 end of central directory record");
                }
                long zip64RecordSize = readLong(zip64End + 4);
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
                if (readUnsignedInt(zip64End + 16) != 0 || readUnsignedInt(zip64End + 20) != 0
                        || readLong(zip64End + 24) != readLong(zip64End + 32)) {
                    throw malformed("multi-disk ZIP64 archives are not supported; a single-disk archive was required");
                }
                entryCount = readLong(zip64End + 32);
                directorySize = readLong(zip64End + 40);
                directoryOffset = readLong(zip64End + 48);
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
        } catch (IndexOutOfBoundsException | InternalError e) {
            throw unreadable(e);
        }
    }

    /**
     * Opens an archive for reading.
     *
     * @param path the archive file
     * @return an open reader the caller must close, on the thread that opened it
     * @throws IOException          if the file cannot be read or mapped, or is not a well-formed ZIP archive,
     *                              including when it contains an entry name that is unsafe to extract
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public static ZipReader open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return map(path);
    }

    /**
     * Opens an archive for reading.
     *
     * @param file the archive file
     * @return an open reader the caller must close, on the thread that opened it
     * @throws IOException          if the file cannot be read or mapped, or is not a well-formed ZIP archive
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public static ZipReader open(File file) throws IOException {
        Objects.requireNonNull(file, "file");
        return map(file.toPath());
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
     * The archive's manifest, parsed the first time it is asked for and remembered after that.
     *
     * <p>Opening an archive neither reads nor parses its manifest. The manifest entry is looked up
     * case-insensitively, as {@link java.util.jar.JarFile} does, and read with {@link #read(ZipEntryInfo)},
     * so its CRC-32 is verified. The returned object is the reader's own instance and is mutable; callers
     * must not modify it.</p>
     *
     * @return the manifest, or empty when the archive has no {@code META-INF/MANIFEST.MF}
     * @throws IOException if the manifest entry is larger than 16 MiB, cannot be read, does not match its
     *                     recorded CRC-32 or is not a valid manifest
     */
    public Optional<Manifest> manifest() throws IOException {
        Optional<Manifest> parsed = manifest;
        if (parsed == null) {
            parsed = readManifest();
            manifest = parsed;
        }
        return parsed;
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
        return bytesAt(entry.dataOffset(), (int) size);
    }

    /**
     * Reads an entry's content, decompressing it when it is deflated and verifying its CRC-32.
     *
     * <p>The content is copied or inflated straight into an array of its exact size. A deflated entry's
     * compressed bytes pass through one buffer of at most 64 KiB, and only as large as they are.</p>
     *
     * @param entry an entry of this archive
     * @return the verified uncompressed content, of length {@link ZipEntryInfo#uncompressedSize()}
     * @throws IOException if the data cannot be read, its CRC-32 does not match, the compression method is
     *                     neither stored nor deflated, or the deflate stream is truncated, corrupt,
     *                     overproduces, or does not consume its complete recorded compressed region
     */
    public byte[] read(ZipEntryInfo entry) throws IOException {
        Objects.requireNonNull(entry, "entry");
        int resultSize = checkedArraySize(entry, entry.uncompressedSize());
        requirePayload(entry);
        byte[] result = new byte[resultSize];
        if (entry.method() == IndexFormat.METHOD_STORED) {
            copyFromMapping(entry.dataOffset(), result, 0, resultSize);
            CRC32 crc = new CRC32();
            crc.update(result, 0, resultSize);
            verifyCrc(entry, crc.getValue());
            return result;
        }
        byte[] input = new byte[(int) Math.min(TRANSFER_BUFFER_SIZE, entry.compressedSize())];
        inflate(entry, input, true, result, null);
        return result;
    }

    /**
     * Streams one entry to {@code target} while verifying its size, CRC-32 and complete DEFLATE region.
     * Neither the compressed nor expanded payload is materialised in a payload-sized array: both pass
     * through two 64 KiB buffers the reader allocates at its first transfer and keeps.
     *
     * @param entry  an entry of this archive
     * @param target destination, flushed and closed by its owner
     * @return the verified uncompressed byte count
     * @throws IOException if the payload or its recorded metadata disagree, or either stream fails
     */
    public synchronized long transfer(ZipEntryInfo entry, OutputStream target) throws IOException {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(target, "target");
        requirePayload(entry);
        if (entry.method() == IndexFormat.METHOD_STORED) {
            return transferStored(entry, target);
        }
        return inflate(entry, transferInput(), false, transferOutput(), target);
    }

    private void requirePayload(ZipEntryInfo entry) throws IOException {
        requireRange(entry.dataOffset(), entry.compressedSize());
        if (entry.method() == IndexFormat.METHOD_STORED) {
            if (entry.compressedSize() != entry.uncompressedSize()) {
                throw new IOException("STORED entry '" + entry.name() + "' of " + path
                        + " has different compressed and uncompressed sizes");
            }
        } else if (entry.method() != IndexFormat.METHOD_DEFLATED) {
            throw new IOException("Entry '" + entry.name() + "' of " + path + " uses unsupported compression method "
                    + entry.method());
        }
    }

    private long transferStored(ZipEntryInfo entry, OutputStream target) throws IOException {
        byte[] buffer = transferOutput();
        CRC32 crc = new CRC32();
        long remaining = entry.uncompressedSize();
        long position = entry.dataOffset();
        while (remaining > 0) {
            int count = (int) Math.min(buffer.length, remaining);
            readFully(position, buffer, count);
            target.write(buffer, 0, count);
            crc.update(buffer, 0, count);
            position += count;
            remaining -= count;
        }
        verifyCrc(entry, crc.getValue());
        return entry.uncompressedSize();
    }

    /**
     * Inflates a deflated entry and checks how the stream is framed: it must end exactly at the recorded
     * uncompressed size, consume exactly the recorded compressed region, need no preset dictionary and match
     * the recorded CRC-32. {@link #read(ZipEntryInfo)} and {@link #transfer(ZipEntryInfo, OutputStream)} differ
     * only in where the compressed bytes come from and where the content goes.
     *
     * @param entry  the entry, already checked by {@link #requirePayload(ZipEntryInfo)}
     * @param input  the buffer each chunk of compressed bytes is staged in; its length is the chunk size
     * @param mapped {@code true} to copy the chunks out of the mapping, {@code false} to read them through
     *               the channel
     * @param output with a {@code target}, the buffer every chunk of content is inflated into before it is
     *               written; without one, the result itself, of the entry's exact size, which is filled in
     *               place
     * @param target where the content is written, or {@code null} to leave it in {@code output}
     * @return the number of bytes inflated, which is the entry's uncompressed size
     */
    private long inflate(ZipEntryInfo entry, byte[] input, boolean mapped, byte[] output, OutputStream target)
            throws IOException {
        CRC32 crc = new CRC32();
        Inflater inflater = new Inflater(true);
        long expected = entry.uncompressedSize();
        long compressedRemaining = entry.compressedSize();
        long position = entry.dataOffset();
        long total = 0;
        try {
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    if (compressedRemaining == 0) {
                        throw truncatedDeflate(entry, expected, total);
                    }
                    int count = (int) Math.min(input.length, compressedRemaining);
                    if (mapped) {
                        copyFromMapping(position, input, 0, count);
                    } else {
                        readFully(position, input, count);
                    }
                    inflater.setInput(input, 0, count);
                    position += count;
                    compressedRemaining -= count;
                }
                byte[] into = output;
                int offset = target == null ? (int) total : 0;
                int room = output.length - offset;
                if (room == 0) {
                    // The result is full but the stream has not ended: one more byte of content is too many.
                    into = new byte[1];
                    offset = 0;
                    room = 1;
                }
                int read = inflater.inflate(into, offset, room);
                if (read > 0) {
                    if (read > expected - total) {
                        throw new IOException("Deflate stream for entry '" + entry.name() + "' of " + path
                                + " produces more than the recorded " + expected + " bytes");
                    }
                    if (target != null) {
                        target.write(into, offset, read);
                    }
                    crc.update(into, offset, read);
                    total += read;
                } else if (inflater.finished()) {
                    // The terminal block may produce no plaintext, including for an empty entry.
                    continue;
                } else if (inflater.needsDictionary()) {
                    throw new IOException("Deflate stream for entry '" + entry.name() + "' of " + path
                            + " requires a dictionary");
                } else if (inflater.needsInput()) {
                    continue;
                } else {
                    throw new IOException("Deflate stream for entry '" + entry.name() + "' of " + path
                            + " made no progress after " + total + " bytes");
                }
            }
            if (total != expected) {
                throw truncatedDeflate(entry, expected, total);
            }
            long remaining = inflater.getRemaining() + compressedRemaining;
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
        return total;
    }

    private byte[] transferInput() {
        if (transferInput == null) {
            transferInput = new byte[TRANSFER_BUFFER_SIZE];
        }
        return transferInput;
    }

    private byte[] transferOutput() {
        if (transferOutput == null) {
            transferOutput = new byte[TRANSFER_BUFFER_SIZE];
        }
        return transferOutput;
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

    private IOException truncatedDeflate(ZipEntryInfo entry, long expected, long actual) {
        return new IOException("Truncated deflate stream for entry '" + entry.name() + "' of " + path
                + ": expected " + expected + " bytes, inflated " + actual);
    }

    private void requireRange(long position, long length) throws IOException {
        if (position < 0 || length < 0 || position > fileLength - length) {
            throw malformed("a read of " + length + " bytes at offset " + position + " runs past the end of the file");
        }
    }

    /** Reads through the channel, which only {@link #transfer(ZipEntryInfo, OutputStream)} does. */
    private void readFully(long position, byte[] destination, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(destination, 0, length);
        long at = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, at);
            if (read < 0) {
                throw malformed("unexpected end of file at offset " + at);
            }
            if (read == 0) {
                continue;
            }
            at += read;
        }
    }

    /**
     * Unmaps the archive and closes its channel. Only the thread that opened the reader may close it;
     * closing it again does nothing.
     *
     * @throws IOException if the channel cannot be closed
     */
    @Override
    public void close() throws IOException {
        if (!arena.scope().isAlive()) {
            return;
        }
        try {
            arena.close();
        } finally {
            channel.close();
        }
    }

    /**
     * Opens and maps an archive, then parses it. Whatever fails after the channel is open closes the arena
     * as well as the channel: a confined arena is never reclaimed by the garbage collector, so every archive
     * this rejects would otherwise leak its mapping and, on Windows, keep the file from being deleted.
     */
    private static ZipReader map(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        Arena arena = null;
        try {
            arena = Arena.ofConfined();
            MemorySegment mapping = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
            return new ZipReader(path, channel, arena, mapping);
        } catch (IOException | RuntimeException | Error e) {
            if (arena != null) {
                arena.close();
            }
            try {
                channel.close();
            } catch (IOException secondary) {
                e.addSuppressed(secondary);
            }
            throw e;
        }
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

    // Reads of the mapping. Every caller has checked the range first, with requireRange or against a bound
    // derived from the file length, so the messages name what is wrong with the archive; an access that
    // still falls outside the mapping, or faults because the file shrank, becomes an IOException in the
    // constructor, copyFromMapping or bytesAt.

    private int readUnsignedShort(long offset) {
        return Short.toUnsignedInt(mapping.get(SHORT_LE, offset));
    }

    private int readInt(long offset) {
        return mapping.get(INT_LE, offset);
    }

    private long readUnsignedInt(long offset) {
        return Integer.toUnsignedLong(mapping.get(INT_LE, offset));
    }

    private long readLong(long offset) {
        return mapping.get(LONG_LE, offset);
    }

    /** Copies {@code length} bytes of the archive at {@code position} into a new array. */
    private byte[] bytesAt(long position, int length) throws IOException {
        requireRange(position, length);
        byte[] result = new byte[length];
        copyFromMapping(position, result, 0, length);
        return result;
    }

    private void copyFromMapping(long position, byte[] destination, int offset, int length) throws IOException {
        try {
            // Segment to segment rather than segment to array: the array overload bootstraps a type switch on
            // its first call, about a millisecond that every fresh packaging JVM would pay.
            MemorySegment.copy(mapping, position, MemorySegment.ofArray(destination), offset, length);
        } catch (IndexOutOfBoundsException | InternalError e) {
            throw unreadable(e);
        }
    }

    /**
     * Turns a failed read of the mapping into the reader's own failure: an access past the end of the
     * mapping, or the {@link InternalError} an access throws when the file was truncated while it was open.
     */
    private IOException unreadable(Throwable cause) {
        IOException failure = malformed("a read of the file failed; it may have been truncated while it was open");
        failure.initCause(cause);
        return failure;
    }

    private IOException malformed(String detail) {
        return new IOException("Cannot read " + path + " as a ZIP archive: " + detail);
    }

    /**
     * Locates the end of central directory record by scanning back from the end of the file, over the
     * mapping in place. The first candidate is the last 22 bytes of the file, and for an archive without a
     * comment, which is nearly every jar, it is the record: those are the only bytes the scan reads.
     */
    private long findEndOfCentralDirectory() throws IOException {
        long window = Math.min(fileLength,
                (long) IndexFormat.MAX_COMMENT_SIZE + IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE);
        if (window < IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE) {
            throw malformed("the file is too short to contain an end of central directory record");
        }
        long start = fileLength - window;
        for (long at = fileLength - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE; at >= start; at--) {
            if (readInt(at) != IndexFormat.END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                continue;
            }
            int commentLength = readUnsignedShort(at + 20);
            if (at + IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE + commentLength == fileLength) {
                return at;
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
    private long offsetCorrection(long recordStart, long directorySize, long directoryOffset, long entryCount) {
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

    private boolean hasSignatureAt(long offset, int signature) {
        if (offset < 0 || offset > fileLength - 4) {
            return false;
        }
        return readInt(offset) == signature;
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
        byte[] directory = bytesAt(directoryStart, (int) directorySize);
        // Compares central names with local names in place, without copying the local ones.
        MemorySegment directoryView = MemorySegment.ofArray(directory);
        int count = (int) entryCount;
        List<ZipEntryInfo> result = new ArrayList<>(count);
        // Where each entry's local header starts and its data or data descriptor ends, by central
        // directory position; only needed until the spans have been checked for overlaps.
        long[] spanStarts = new long[count];
        long[] spanEnds = new long[count];
        int position = 0;
        for (int i = 0; i < count; i++) {
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
            ZipEntryInfo entry = readCentralDirectoryRecord(directory, directoryView, position, nameLength,
                    extraLength, delta, directoryStart, spanEnds, i);
            spanStarts[i] = entry.localHeaderOffset();
            result.add(entry);
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
        validateLocalSpans(result, spanStarts, spanEnds, directoryStart);
        return List.copyOf(result);
    }

    private ZipEntryInfo readCentralDirectoryRecord(byte[] directory, MemorySegment directoryView, int position,
            int nameLength, int extraLength, long delta, long directoryStart, long[] spanEnds, int index)
            throws IOException {
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
        long dataOffset = resolveDataOffset(name, directoryView, nameStart, nameLength, localHeaderOffset,
                flags, method, crc, compressedSize, uncompressedSize, directoryStart);
        long dataEnd = dataOffset + compressedSize;
        int descriptorLength = 0;
        if ((flags & FLAG_DATA_DESCRIPTOR) != 0) {
            descriptorLength = validateDataDescriptor(name, dataEnd, directoryStart,
                    crc, compressedSize, uncompressedSize);
        }
        spanEnds[index] = checkedAdd(dataEnd, descriptorLength, "end of entry '" + name + "'");
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

    /**
     * Checks an entry's local file header against its central directory record, reading the header and
     * the name from the mapping, and returns the offset of its first data byte.
     */
    private long resolveDataOffset(String name, MemorySegment directory, int centralNameStart, int centralNameLength,
            long localHeaderOffset, int flags, int method, long crc, long compressedSize, long uncompressedSize,
            long directoryStart) throws IOException {
        requireRange(localHeaderOffset, LOCAL_HEADER_SIZE);
        if (readInt(localHeaderOffset) != IndexFormat.LOCAL_HEADER_SIGNATURE) {
            throw malformed("no local file header for entry '" + name + "' at offset " + localHeaderOffset);
        }
        int localFlags = readUnsignedShort(localHeaderOffset + 6);
        int localMethod = readUnsignedShort(localHeaderOffset + 8);
        if (localFlags != flags) {
            throw localDisagreement(name, "flags", flags, localFlags);
        }
        if (localMethod != method) {
            throw localDisagreement(name, "method", method, localMethod);
        }
        long localCrc = readUnsignedInt(localHeaderOffset + 14);
        long localCompressedSize = readUnsignedInt(localHeaderOffset + 18);
        long localUncompressedSize = readUnsignedInt(localHeaderOffset + 22);
        int nameLength = readUnsignedShort(localHeaderOffset + LOCAL_NAME_LENGTH_OFFSET);
        int extraLength = readUnsignedShort(localHeaderOffset + LOCAL_NAME_LENGTH_OFFSET + 2);
        long variableLength = (long) nameLength + extraLength;
        if (localHeaderOffset > directoryStart - LOCAL_HEADER_SIZE
                || variableLength > directoryStart - localHeaderOffset - LOCAL_HEADER_SIZE) {
            throw malformed("the local header of entry '" + name + "' runs into the central directory");
        }
        long nameOffset = localHeaderOffset + LOCAL_HEADER_SIZE;
        long dataOffset = nameOffset + variableLength;
        // Equal bytes are all it takes to prove the names equal; the local name is only decoded to say
        // what it is instead, strictly, so a malformed one is reported as malformed.
        if (nameLength != centralNameLength
                || MemorySegment.mismatch(directory, centralNameStart, centralNameStart + centralNameLength,
                        mapping, nameOffset, nameOffset + nameLength) >= 0) {
            byte[] localName = bytesAt(nameOffset, nameLength);
            throw malformed("the local header name '"
                    + decodeName(localName, 0, nameLength, "local header of entry '" + name + "'")
                    + "' of entry '" + name + "' disagrees with its central directory name");
        }
        if ((flags & FLAG_DATA_DESCRIPTOR) == 0) {
            if (localCompressedSize == IndexFormat.ZIP64_MARKER
                    || localUncompressedSize == IndexFormat.ZIP64_MARKER) {
                long[] localZip64 = readZip64Extra(bytesAt(nameOffset + nameLength, extraLength), 0, extraLength,
                        name, localUncompressedSize == IndexFormat.ZIP64_MARKER,
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
                long[] localZip64 = readZip64Extra(bytesAt(nameOffset + nameLength, extraLength), 0, extraLength,
                        name, localUncompressedSize == IndexFormat.ZIP64_MARKER,
                        localCompressedSize == IndexFormat.ZIP64_MARKER, false, false);
                validateDescriptorZip64Placeholder(name, "uncompressed-size", localZip64[0], uncompressedSize);
                validateDescriptorZip64Placeholder(name, "compressed-size", localZip64[1], compressedSize);
            }
        }
        if (compressedSize > directoryStart - dataOffset) {
            throw malformed("the compressed data of entry '" + name + "' runs into the central directory");
        }
        return dataOffset;
    }

    /**
     * Checks the data descriptor that follows an entry's data against its central directory record, reading
     * it from the mapping, and returns its length. It may or may not carry its signature, and its sizes are
     * 4 or, for ZIP64, 8 bytes each.
     */
    private int validateDataDescriptor(String name, long offset, long directoryStart, long crc,
            long compressedSize, long uncompressedSize) throws IOException {
        int available = (int) Math.min(24, directoryStart - offset);
        if (available < 12) {
            throw malformed("the data descriptor of entry '" + name + "' runs into the central directory");
        }
        requireRange(offset, available);
        boolean signed = readInt(offset) == DATA_DESCRIPTOR_SIGNATURE;
        if (signed && matchesDescriptor(offset, available, 4, false, crc, compressedSize, uncompressedSize)) {
            return 16;
        }
        if (matchesDescriptor(offset, available, 0, false, crc, compressedSize, uncompressedSize)) {
            return 12;
        }
        if (signed && matchesDescriptor(offset, available, 4, true, crc, compressedSize, uncompressedSize)) {
            return 24;
        }
        if (matchesDescriptor(offset, available, 0, true, crc, compressedSize, uncompressedSize)) {
            return 20;
        }
        throw malformed("the data descriptor of entry '" + name
                + "' disagrees with its central directory CRC or sizes");
    }

    private boolean matchesDescriptor(long offset, int available, int start, boolean zip64, long crc,
            long compressedSize, long uncompressedSize) {
        int size = zip64 ? 20 : 12;
        long at = offset + start;
        if (start + size > available || readUnsignedInt(at) != crc) {
            return false;
        }
        if (zip64) {
            return readLong(at + 4) == compressedSize && readLong(at + 12) == uncompressedSize;
        }
        return readUnsignedInt(at + 4) == compressedSize && readUnsignedInt(at + 8) == uncompressedSize;
    }

    /**
     * Checks that no entry's local header, data and data descriptor overlap another entry's or the central
     * directory. Repeated central records may share one local entry, but only with the same end.
     *
     * <p>Local entries almost always appear in central directory order, and then one linear pass in that
     * order suffices. Otherwise the entries are visited sorted by start, stably, so the result and the
     * message do not depend on which order the archive used.</p>
     *
     * @param entries the entries, in central directory order, which name them in the messages
     * @param starts  where each entry's local header starts
     * @param ends    where each entry's data, or data descriptor, ends
     */
    private void validateLocalSpans(List<ZipEntryInfo> entries, long[] starts, long[] ends, long directoryStart)
            throws IOException {
        int count = starts.length;
        int[] order = null;
        for (int i = 1; i < count; i++) {
            if (starts[i] < starts[i - 1]) {
                order = sortedByStart(starts);
                break;
            }
        }
        int first = -1;
        for (int k = 0; k < count; k++) {
            int i = order == null ? k : order[k];
            if (first >= 0 && starts[i] == starts[first]) {
                if (ends[i] != ends[first]) {
                    throw malformed("entry '" + entries.get(first).name() + "' overlaps entry '"
                            + entries.get(i).name() + "'");
                }
            } else {
                first = i;
            }
        }
        int current = -1;
        for (int k = 0; k < count; k++) {
            int i = order == null ? k : order[k];
            if (current >= 0 && starts[i] == starts[current]) {
                continue;
            }
            if (current >= 0 && ends[current] > starts[i]) {
                throw malformed("entry '" + entries.get(current).name() + "' overlaps entry '"
                        + entries.get(i).name() + "'");
            }
            current = i;
        }
        if (current >= 0 && ends[current] > directoryStart) {
            throw malformed("entry '" + entries.get(current).name() + "' overlaps the central directory");
        }
    }

    /** The positions of {@code starts} sorted by value, stably; only an unusual archive needs it. */
    private static int[] sortedByStart(long[] starts) {
        Integer[] boxed = new Integer[starts.length];
        for (int i = 0; i < boxed.length; i++) {
            boxed[i] = i;
        }
        Arrays.sort(boxed, Comparator.comparingLong(i -> starts[i]));
        int[] order = new int[boxed.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = boxed[i];
        }
        return order;
    }

    private long checkedAdd(long left, long right, String description) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw malformed(description + " overflows a 64-bit file offset");
        }
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

    /**
     * Decodes an entry name as strict UTF-8. A name of plain ASCII, which is nearly every name, is copied
     * without a decoder; any other name goes through the reader's one decoder, created at the first such
     * name.
     */
    private String decodeName(byte[] buffer, int offset, int length, String location) throws IOException {
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            if (buffer[i] < 0) {
                return decodeUtf8(buffer, offset, length, location);
            }
        }
        // ASCII is the same in ISO-8859-1, which copies the bytes straight into a compact string.
        return new String(buffer, offset, length, StandardCharsets.ISO_8859_1);
    }

    private String decodeUtf8(byte[] buffer, int offset, int length, String location) throws IOException {
        CharsetDecoder decoder = utf8;
        if (decoder == null) {
            decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            utf8 = decoder;
        }
        try {
            return decoder.reset().decode(ByteBuffer.wrap(buffer, offset, length)).toString();
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
        if (entry.uncompressedSize() > MAX_MANIFEST_SIZE) {
            throw new IOException("Manifest entry of " + path + " is " + entry.uncompressedSize()
                    + " bytes; the in-memory metadata limit is " + MAX_MANIFEST_SIZE);
        }
        return Optional.of(new Manifest(new ByteArrayInputStream(read(entry))));
    }
}
