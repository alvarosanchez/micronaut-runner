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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Rewrites a dependency jar so that every entry is {@code STORED}, or copies it byte for byte, and reports
 * where each entry's data ended up.
 *
 * <p>Repacking is what the default {@code STORED} compression mode does to every dependency. It is worth
 * the build-time cost because a stored nested entry can be handed to {@code ClassLoader.defineClass} as a
 * slice of the memory-mapped outer archive, with no inflater and no intermediate {@code byte[]} at all.</p>
 *
 * <p>What is preserved: entry names, their central directory order, their MS-DOS timestamps and their
 * CRC-32 values, and the manifest, verbatim, because a per-jar manifest carries package metadata and sealing, so
 * rewriting it would change the semantics of the classes in it. (Digest attributes left in the manifest of a
 * jar whose signature files were dropped are inert, so they are left alone too.)</p>
 *
 * <p>What is dropped: every extra field, including ZIP64 ones, since {@link ZipWriter} re-derives what it
 * needs; data descriptors, because sizes are known up front and are written into the local header; the
 * signature files of a signed jar, which cannot verify a repacked archive
 * ({@link ZipReader#isSignatureFile(String)}); and {@code META-INF/INDEX.LIST}, which describes a class path
 * the nested jar no longer has. {@link RepackResult#hadSignatureFiles()} tells the packager to record
 * {@link IndexFormat#JAR_FLAG_SIGNED_ORIGINAL}.</p>
 *
 * <p>The offsets in {@link RepackResult#entries()} are relative to the first byte of the nested jar. The
 * packager turns them into the absolute offsets the index stores by adding the offset at which it wrote the
 * nested jar into the outer archive, which is what {@link ZipEntryInfo#shift(long)} is for.</p>
 *
 * <p>The nested jar itself goes wherever the caller's stream points: pass a
 * {@link java.io.ByteArrayOutputStream} to get it as a byte array, or use {@link #repack(Path, Path)} to
 * build it in a temporary file next to the output.</p>
 *
 * @since 1.0
 */
public final class ZipRepacker {

    /** Buffer size used when copying a jar verbatim. */
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private ZipRepacker() {
    }

    /**
     * Repacks an archive so that every entry is stored uncompressed.
     *
     * @param source the archive to read; it is neither closed nor modified
     * @param target the stream the nested jar is written to; it is flushed but not closed
     * @return the entries of the produced jar, with offsets relative to its first byte
     * @throws IOException if the source cannot be read, an entry's content does not match its recorded
     *                     CRC-32, or the target cannot be written
     */
    public static RepackResult repack(ZipReader source, OutputStream target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        List<ZipEntryInfo> entries = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        boolean signed = false;
        // A dependency jar somebody else produced may legitimately carry two entries that differ only by
        // case, or the very same name twice. Both round trip: the index keys on the record, so a repeated
        // name becomes a same-name chain, which is exactly what the PRESERVE mode produces for the same
        // input. Neither is worth failing a build that java.util.zip.ZipFile would read without complaint.
        ZipWriter writer = new ZipWriter(target, ZipWriter.DEFAULT_TIMESTAMP, false);
        for (ZipEntryInfo entry : source.entries()) {
            String name = entry.name();
            boolean signature = ZipReader.isSignatureFile(name);
            signed = signed || signature;
            if (signature || ZipReader.isIndexList(name)) {
                dropped.add(name);
                continue;
            }
            long localHeaderOffset = writer.offset();
            long dataOffset;
            long size;
            long crc;
            if (entry.directory()) {
                // A directory entry is written with no data at all, so its CRC-32 is the CRC of nothing,
                // whatever the source recorded.
                dataOffset = writer.writeDirectoryEntry(name, entry.dosTime());
                size = 0;
                crc = 0;
            } else {
                byte[] content = source.read(entry);
                verifyCrc(source, entry, content);
                dataOffset = writer.writeEntry(name, content, 0, content.length, entry.dosTime());
                size = content.length;
                crc = entry.crc32();
            }
            entries.add(new ZipEntryInfo(name, IndexFormat.METHOD_STORED, size, size, crc,
                    entry.dosTime(), localHeaderOffset, dataOffset, entry.directory()));
        }
        writer.finish();
        return new RepackResult(List.copyOf(entries), writer.offset(), signed, List.copyOf(dropped));
    }

    /**
     * Repacks an archive into a file.
     *
     * @param source the archive to read
     * @param target the nested jar to create, replacing anything already there
     * @return the entries of the produced jar, with offsets relative to its first byte
     * @throws IOException if either file cannot be read or written, or an entry fails its CRC-32 check
     */
    public static RepackResult repack(Path source, Path target) throws IOException {
        Objects.requireNonNull(target, "target");
        try (ZipReader reader = ZipReader.open(source);
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), COPY_BUFFER_SIZE)) {
            RepackResult result = repack(reader, out);
            out.flush();
            return result;
        }
    }

    /**
     * Copies an archive byte for byte, which is what the {@code PRESERVE} compression mode does: the nested
     * jar keeps its original compression, extra fields, data descriptors, signature files and comment, and
     * only its entry offsets have to be discovered.
     *
     * <p>Because nothing moves, the offsets {@link ZipReader} already resolved are exactly the offsets
     * relative to the nested jar, and are reported unchanged. Nothing is dropped, so
     * {@link RepackResult#droppedEntries()} is empty even for a signed jar; the packager still learns from
     * {@link RepackResult#hadSignatureFiles()} that the source was signed.</p>
     *
     * @param source the archive to read; it is neither closed nor modified
     * @param target the stream the copy is written to; it is flushed but not closed
     * @return the entries of the copy, with offsets relative to its first byte
     * @throws IOException if the source cannot be read or the target cannot be written
     */
    public static RepackResult copy(ZipReader source, OutputStream target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        long copied = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(source.path())) {
            int read = in.read(buffer);
            while (read > 0) {
                target.write(buffer, 0, read);
                copied += read;
                read = in.read(buffer);
            }
        }
        target.flush();
        if (copied != source.fileLength()) {
            throw new IOException("Copied " + copied + " of " + source.fileLength() + " bytes of " + source.path()
                    + "; it changed while it was being packaged");
        }
        return new RepackResult(source.entries(), copied, source.hasSignatureFiles(), List.of());
    }

    /**
     * Copies an archive byte for byte into a file.
     *
     * @param source the archive to read
     * @param target the nested jar to create, replacing anything already there
     * @return the entries of the copy, with offsets relative to its first byte
     * @throws IOException if either file cannot be read or written
     */
    public static RepackResult copy(Path source, Path target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        try (ZipReader reader = ZipReader.open(source)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return new RepackResult(reader.entries(), reader.fileLength(), reader.hasSignatureFiles(), List.of());
        }
    }

    private static void verifyCrc(ZipReader source, ZipEntryInfo entry, byte[] content) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(content, 0, content.length);
        if (crc.getValue() != entry.crc32()) {
            throw new IOException("Entry '" + entry.name() + "' of " + source.path()
                    + " does not match its recorded CRC-32: expected " + Long.toHexString(entry.crc32())
                    + ", computed " + Long.toHexString(crc.getValue()));
        }
    }

    /**
     * What a repack or a verbatim copy produced.
     *
     * @param entries           every entry of the nested jar, in the source's central directory order, with
     *                          {@link ZipEntryInfo#localHeaderOffset()} and {@link ZipEntryInfo#dataOffset()}
     *                          relative to the nested jar's first byte
     * @param length            the length of the nested jar in bytes
     * @param hadSignatureFiles whether the source archive carried signature files, which the packager records
     *                          as {@link IndexFormat#JAR_FLAG_SIGNED_ORIGINAL}
     * @param droppedEntries    the names of the entries that were left out, in source order; always empty for
     *                          a verbatim copy
     */
    public record RepackResult(
            List<ZipEntryInfo> entries,
            long length,
            boolean hadSignatureFiles,
            List<String> droppedEntries) {

        /**
         * Validates the result and makes its lists immutable.
         *
         * @param entries           the entries of the produced jar
         * @param length            the length of the produced jar
         * @param hadSignatureFiles whether the source carried signature files
         * @param droppedEntries    the names that were left out
         * @throws NullPointerException     if a list is {@code null}
         * @throws IllegalArgumentException if the length is negative
         */
        public RepackResult {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            droppedEntries = List.copyOf(Objects.requireNonNull(droppedEntries, "droppedEntries"));
            if (length < 0) {
                throw new IllegalArgumentException("Negative nested jar length: " + length);
            }
        }
    }
}
