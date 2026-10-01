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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.Deflater;

/**
 * Rewrites a dependency jar for the runner jar: every entry {@code STORED}, or, for {@link Compression#HYBRID},
 * every cold class compressed, and reports where each entry's data ended up.
 *
 * <p>Repacking is what the default {@code STORED} compression mode does to every dependency. It is worth
 * the build-time cost because a stored nested entry can be handed to {@code ClassLoader.defineClass} as a
 * slice of the memory-mapped outer archive, with no inflater and no intermediate {@code byte[]} at all.</p>
 *
 * <p>What is preserved: entry names, their MS-DOS timestamps, the CRC-32 value of every entry that no class
 * transform changes, and the manifest, verbatim, because a per-jar manifest carries package metadata and
 * sealing, so rewriting it would change the semantics of the classes in it. (Digest attributes left in the
 * manifest of a jar whose signature files were dropped are inert, so they are left alone too.) Without a startup
 * class list, the central directory order is preserved as well, except for the classes a transform step adds,
 * each of which follows the class it was generated for.</p>
 *
 * <p>What a startup class list changes: in a jar that holds at least one of the listed classes, or a
 * {@code META-INF/versions/N/} variant of one, the hot entries come first, so the bytes startup reads are
 * contiguous. The jar starts with {@code META-INF/} and {@code META-INF/MANIFEST.MF}, where
 * {@link java.util.jar.JarInputStream} looks for the manifest, then the hot entries in list order (same-name
 * entries and a class with its versioned variants keep their relative order, so duplicate precedence does not
 * change), each hot class followed by the unlisted classes generated for it, then every other entry in the order
 * the list-free repack writes it. A jar without a hot entry keeps that order. Nested jars themselves are never
 * reordered in the outer archive.</p>
 *
 * <p>What {@link Compression#HYBRID} changes: a hot class, every resource, every directory and every entry that
 * is too large or was stored or incompressible in the source stays {@code STORED}. A cold class that no step
 * changed keeps the source entry's original DEFLATE bytes, read once with {@link ZipReader#readRaw(ZipEntryInfo)}
 * and verified by inflating them ({@link ZipReader#inflate(ZipEntryInfo, byte[])}); a cold class that a step
 * rewrote or generated is deflated afresh, and written {@code STORED} when that is not smaller. Original
 * compressed bytes are never kept for a rewritten class: they would ship the class before the transform.</p>
 *
 * <p>What a class transform changes: {@link #repack(ZipReader, OutputStream, ClassTransformPipeline.JarRun)}
 * runs the {@link ClassTransformPipeline} over the jar's classes, so a class a step rewrites keeps its name and
 * MS-DOS time and carries the size and CRC-32 of its new bytes. The public {@code repack} methods run no
 * transform, order nothing and keep every entry's bytes.</p>
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
final class ZipRepacker {

    /** Buffer size of the file a nested jar is written to. */
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    /** The directory entry {@link java.util.jar.JarInputStream} skips before it looks for the manifest. */
    private static final String META_INF = "META-INF/";

    /** The manifest, which {@link java.util.jar.JarInputStream} finds only among a jar's first two entries. */
    private static final String MANIFEST = "META-INF/MANIFEST.MF";

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
        return repack(source, target, null);
    }

    /**
     * Repacks an archive for the runner jar, running a class transform pipeline over its classes and applying
     * the pipeline's {@linkplain ClassTransformPipeline.Options options}: hot-first order for a startup class
     * list, and compressed cold classes for {@link Compression#HYBRID}.
     *
     * <p>The order is computed from the source's central directory and the startup class ranks before the first
     * entry is written. The entries are then read, transformed and written one at a time in that order. A class
     * the pipeline {@linkplain ClassTransformPipeline.JarRun#reads(long) reads} is read into memory once,
     * inflated and checked against its CRC-32, and handed to the pipeline; every other entry, and a class above
     * {@link ClassTransformPipeline#MAX_CLASS_SIZE}, is streamed as {@link #repack(ZipReader, OutputStream)}
     * streams it, so the memory a repack needs stays bounded. A class the pipeline leaves alone is written from
     * the bytes it read, with the same header, CRC-32 and data as the streaming path would write. The classes of
     * a planned nest are held from planning until they are written, as they are without a list.</p>
     *
     * @param source the archive to read; it is neither closed nor modified
     * @param target the stream the nested jar is written to; it is flushed but not closed
     * @param run    the pipeline's run over this jar, or {@code null} to transform, order and compress nothing
     * @return the entries of the produced jar, in the order they were written, with offsets relative to its
     * first byte
     * @throws IOException if the source cannot be read, an entry's content does not match its recorded
     *                     CRC-32 or DEFLATE framing, or the target cannot be written
     */
    static RepackResult repack(ZipReader source, OutputStream target, ClassTransformPipeline.JarRun run)
            throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        ClassTransformPipeline.Options options = run == null ? ClassTransformPipeline.Options.NONE : run.options();
        // A dependency jar somebody else produced may legitimately carry two entries that differ only by
        // case, or the very same name twice. Both round trip: the index keys on the record, so a repeated
        // name becomes a same-name chain, which is exactly what the PRESERVE mode produces for the same
        // input. Neither is worth failing a build that java.util.zip.ZipFile would read without complaint.
        ZipWriter writer = new ZipWriter(target, ZipWriter.DEFAULT_TIMESTAMP, false);
        if (run != null && run.plans()) {
            // A nest host may come before its members, so the nests are planned, rewritten and gated before
            // the first entry is written.
            run.plan(new SourceClasses(source));
        }
        List<ZipEntryInfo> kept = new ArrayList<>(source.entries().size());
        List<String> dropped = new ArrayList<>();
        boolean signed = false;
        for (ZipEntryInfo entry : source.entries()) {
            boolean signature = ZipReader.isSignatureFile(entry.name());
            signed = signed || signature;
            if (signature || ZipReader.isIndexList(entry.name())) {
                dropped.add(entry.name());
            } else {
                kept.add(entry);
            }
        }
        // The first entry of a name takes its nest's accepted bytes, as the entry loop always did.
        ClassTransformPipeline.Planned[] planned = new ClassTransformPipeline.Planned[kept.size()];
        int[] pending = new int[kept.size()];
        List<Item> listFree = new ArrayList<>(kept.size());
        for (int position = 0; position < kept.size(); position++) {
            ZipEntryInfo entry = kept.get(position);
            if (run != null && ClassTransformPipeline.isClass(entry)) {
                planned[position] = run.planned(entry.name());
            }
            listFree.add(new Item(position, -1, options.rank(entry.name()), listFree.size()));
            List<ClassTransformPipeline.Generated> generated = planned[position] == null ? List.of()
                    : planned[position].generated();
            for (int index = 0; index < generated.size(); index++) {
                listFree.add(new Item(position, index, options.rank(generated.get(index).name()), listFree.size()));
            }
            pending[position] = 1 + generated.size();
        }
        Order order = order(listFree, kept);
        List<ZipEntryInfo> entries = new ArrayList<>(order.items().size());
        Compressor compressor = options.hybrid() ? new Compressor() : null;
        try {
            for (Item item : order.items()) {
                ZipEntryInfo entry = kept.get(item.source());
                ClassTransformPipeline.Planned nest = planned[item.source()];
                boolean cold = compressor != null && item.rank() < 0;
                if (item.generated() >= 0) {
                    // After its host, or at its own rank when it is listed, with its host's time.
                    ClassTransformPipeline.Generated added = nest.generated().get(item.generated());
                    entries.add(writeBytes(writer, added.name(), added.bytes(), run.crc32(added.bytes()),
                            entry.dosTime(), cold ? compressor : null));
                } else {
                    entries.add(writeEntry(writer, source, entry, nest, run, cold ? compressor : null));
                }
                if (--pending[item.source()] == 0) {
                    planned[item.source()] = null;
                }
            }
        } finally {
            if (compressor != null) {
                compressor.end();
            }
        }
        writer.finish();
        return new RepackResult(List.copyOf(entries), writer.offset(), signed, List.copyOf(dropped), order.hot());
    }

    /**
     * Writes one entry the source carries.
     *
     * @param compressor the jar's compressor when the entry is a cold class of a {@code HYBRID} build, otherwise
     *                   {@code null}
     */
    private static ZipEntryInfo writeEntry(ZipWriter writer, ZipReader source, ZipEntryInfo entry,
                                           ClassTransformPipeline.Planned nest, ClassTransformPipeline.JarRun run,
                                           Compressor compressor) throws IOException {
        String name = entry.name();
        long localHeaderOffset = writer.offset();
        if (entry.directory()) {
            // A directory entry is written with no data at all, so its CRC-32 is the CRC of nothing,
            // whatever the source recorded.
            long dataOffset = writer.writeDirectoryEntry(name, entry.dosTime());
            return new ZipEntryInfo(name, IndexFormat.METHOD_STORED, 0, 0, 0, entry.dosTime(), localHeaderOffset,
                    dataOffset, true);
        }
        boolean cold = compressor != null && ClassTransformPipeline.isClass(entry);
        if (nest != null) {
            // A class of a planned nest: its accepted bytes were kept until now.
            byte[] output = nest.bytes();
            if (cold && !nest.rewritten() && keepsOriginal(entry)) {
                return writeOriginal(writer, source, entry, null);
            }
            return writeBytes(writer, name, output, run.crc32(output), entry.dosTime(),
                    cold && nest.rewritten() ? compressor : null);
        }
        if (run != null && ClassTransformPipeline.isClass(entry) && run.reads(entry.uncompressedSize())) {
            if (cold && keepsOriginal(entry)) {
                // One read of the compressed region, verified by inflating it once; the pipeline takes the
                // inflated bytes, and an unchanged class is written from the same compressed array.
                byte[] compressed = source.readRaw(entry);
                byte[] original = source.inflate(entry, compressed);
                byte[] output = run.process(name, original);
                if (output == original) {
                    return writeOriginal(writer, source, entry, compressed);
                }
                return writeBytes(writer, name, output, run.crc32(output), entry.dosTime(), compressor);
            }
            // Inflated at its exact size and checked against its CRC-32, so that CRC-32 describes the bytes
            // whenever the pipeline hands them back unchanged.
            byte[] original = source.read(entry);
            byte[] output = run.process(name, original);
            if (output == original) {
                return writeBytes(writer, name, output, entry.crc32(), entry.dosTime(), null);
            }
            return writeBytes(writer, name, output, run.crc32(output), entry.dosTime(), cold ? compressor : null);
        }
        if (run != null && ClassTransformPipeline.isClass(entry)) {
            run.pass(name);
        }
        if (cold && keepsOriginal(entry) && entry.uncompressedSize() <= ClassTransformPipeline.MAX_CLASS_SIZE) {
            return writeOriginal(writer, source, entry, null);
        }
        long dataOffset = writer.writeEntry(name, source, entry, entry.dosTime());
        return new ZipEntryInfo(name, IndexFormat.METHOD_STORED, entry.uncompressedSize(), entry.uncompressedSize(),
                entry.crc32(), entry.dosTime(), localHeaderOffset, dataOffset, false);
    }

    /**
     * Whether a cold class can keep its source entry's compressed bytes: they are a DEFLATE stream smaller than the
     * class, and neither size needs ZIP64.
     */
    private static boolean keepsOriginal(ZipEntryInfo entry) {
        return entry.method() == IndexFormat.METHOD_DEFLATED
                && entry.compressedSize() < entry.uncompressedSize()
                && entry.uncompressedSize() < IndexFormat.ZIP64_MARKER;
    }

    /**
     * Writes a class with its source entry's original DEFLATE bytes, which are verified by inflating them first
     * unless the caller has done so already.
     *
     * @param compressed the entry's compressed region, already verified, or {@code null} to read and verify it
     */
    private static ZipEntryInfo writeOriginal(ZipWriter writer, ZipReader source, ZipEntryInfo entry,
                                              byte[] compressed) throws IOException {
        byte[] region = compressed;
        if (region == null) {
            region = source.readRaw(entry);
            source.inflate(entry, region);
        }
        long localHeaderOffset = writer.offset();
        long dataOffset = writer.writeDeflatedEntry(entry.name(), region, 0, region.length, entry.crc32(),
                entry.uncompressedSize(), entry.dosTime());
        return new ZipEntryInfo(entry.name(), IndexFormat.METHOD_DEFLATED, region.length, entry.uncompressedSize(),
                entry.crc32(), entry.dosTime(), localHeaderOffset, dataOffset, false);
    }

    /**
     * Writes a file entry from bytes in memory: {@code STORED}, or deflated afresh when a compressor is given and
     * the result is smaller.
     *
     * @param compressor the jar's compressor, or {@code null} to store the bytes
     */
    private static ZipEntryInfo writeBytes(ZipWriter writer, String name, byte[] content, long crc, int dosTime,
                                           Compressor compressor) throws IOException {
        long localHeaderOffset = writer.offset();
        int compressedLength = compressor == null ? -1 : compressor.deflate(content);
        if (compressedLength < 0) {
            long dataOffset = writer.writeEntry(name, content, 0, content.length, crc, dosTime);
            return new ZipEntryInfo(name, IndexFormat.METHOD_STORED, content.length, content.length, crc, dosTime,
                    localHeaderOffset, dataOffset, false);
        }
        long dataOffset = writer.writeDeflatedEntry(name, compressor.output(), 0, compressedLength, crc,
                content.length, dosTime);
        return new ZipEntryInfo(name, IndexFormat.METHOD_DEFLATED, compressedLength, content.length, crc, dosTime,
                localHeaderOffset, dataOffset, false);
    }

    /**
     * The order a jar's entries are written in, from their list-free order.
     *
     * <p>Without a hot entry it is the list-free order. Otherwise {@code META-INF/} and the manifest come first,
     * in list-free order; then every hot entry, by rank and, for equal ranks, in list-free order, each hot source
     * class followed by its unlisted generated classes; then everything else in list-free order.</p>
     *
     * @param listFree every entry, as the list-free repack writes them
     * @param kept     the source entries the items point at
     * @return the order and the number of hot entries
     */
    private static Order order(List<Item> listFree, List<ZipEntryInfo> kept) {
        int hot = 0;
        for (Item item : listFree) {
            if (item.rank() >= 0) {
                hot++;
            }
        }
        if (hot == 0) {
            return new Order(listFree, 0);
        }
        boolean[] placed = new boolean[listFree.size()];
        List<Item> result = new ArrayList<>(listFree.size());
        for (Item item : listFree) {
            String name = kept.get(item.source()).name();
            if (item.generated() < 0 && (name.equalsIgnoreCase(META_INF) || name.equalsIgnoreCase(MANIFEST))) {
                result.add(item);
                placed[item.position()] = true;
            }
        }
        List<Item> ranked = new ArrayList<>(hot);
        for (Item item : listFree) {
            if (item.rank() >= 0 && !placed[item.position()]) {
                ranked.add(item);
            }
        }
        // List.sort is stable: equal ranks keep their list-free order.
        ranked.sort(Comparator.comparingInt(Item::rank));
        for (Item item : ranked) {
            result.add(item);
            placed[item.position()] = true;
            if (item.generated() >= 0) {
                continue;
            }
            // The host's generated classes follow it in the list-free order; the unlisted ones come along.
            for (int next = item.position() + 1; next < listFree.size(); next++) {
                Item following = listFree.get(next);
                if (following.source() != item.source() || following.generated() < 0) {
                    break;
                }
                if (following.rank() < 0) {
                    result.add(following);
                    placed[following.position()] = true;
                }
            }
        }
        for (Item item : listFree) {
            if (!placed[item.position()]) {
                result.add(item);
            }
        }
        return new Order(List.copyOf(result), hot);
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
     * One entry of the nested jar, before it is written.
     *
     * @param source    the position, among the source entries a repack keeps, of the entry or of the host it was
     *                  generated for
     * @param generated the index among its host's generated classes, or {@code -1} for an entry of the source
     * @param rank      its startup class rank, or {@code -1} when it is cold
     * @param position  its position in the list-free order
     */
    private record Item(int source, int generated, int rank, int position) {
    }

    /**
     * The order a jar is written in.
     *
     * @param items every entry, in the order it is written
     * @param hot   how many of them are hot
     */
    private record Order(List<Item> items, int hot) {
    }

    /**
     * What a repack, or a dependency nested as it is, produced.
     *
     * @param entries           every entry of the nested jar, in the order it holds them, with
     *                          {@link ZipEntryInfo#localHeaderOffset()} and {@link ZipEntryInfo#dataOffset()}
     *                          relative to the nested jar's first byte, and each entry's own method and sizes
     * @param length            the length of the nested jar in bytes
     * @param hadSignatureFiles whether the source archive carried signature files, which the packager records
     *                          as {@link IndexFormat#JAR_FLAG_SIGNED_ORIGINAL}
     * @param droppedEntries    the names of the entries that were left out, in source order; always empty for
     *                          a dependency nested as it is
     * @param hotEntries        how many entries are startup classes or their versioned variants, which the
     *                          repack wrote first; always {@code 0} without a startup class list
     */
    record RepackResult(
            List<ZipEntryInfo> entries,
            long length,
            boolean hadSignatureFiles,
            List<String> droppedEntries,
            int hotEntries) {

        /**
         * Validates the result and makes its lists immutable.
         *
         * @param entries           the entries of the produced jar
         * @param length            the length of the produced jar
         * @param hadSignatureFiles whether the source carried signature files
         * @param droppedEntries    the names that were left out
         * @param hotEntries        how many entries are hot
         * @throws NullPointerException     if a list is {@code null}
         * @throws IllegalArgumentException if the length or the hot count is negative
         */
        RepackResult {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            droppedEntries = List.copyOf(Objects.requireNonNull(droppedEntries, "droppedEntries"));
            if (length < 0) {
                throw new IllegalArgumentException("Negative nested jar length: " + length);
            }
            if (hotEntries < 0) {
                throw new IllegalArgumentException("Negative hot entry count: " + hotEntries);
            }
        }

        /**
         * A result without a hot entry, which is what a dependency nested as it is has.
         *
         * @param entries           the entries of the nested jar
         * @param length            its length
         * @param hadSignatureFiles whether the source carried signature files
         * @param droppedEntries    the names that were left out
         */
        RepackResult(List<ZipEntryInfo> entries, long length, boolean hadSignatureFiles,
                     List<String> droppedEntries) {
            this(entries, length, hadSignatureFiles, droppedEntries, 0);
        }
    }

    /**
     * The one {@link Deflater} of a {@code HYBRID} repack, which a stage task creates for its jar, resets for each
     * class and ends when the jar is written, and the buffer it deflates into.
     */
    private static final class Compressor {

        private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        private byte[] output = new byte[0];

        /**
         * Deflates a class into {@link #output()}.
         *
         * @param content the class
         * @return the length of the raw DEFLATE stream, or {@code -1} when it would not be smaller than the class
         */
        int deflate(byte[] content) {
            int limit = content.length - 1;
            if (limit <= 0) {
                return -1;
            }
            if (output.length < limit) {
                output = new byte[limit];
            }
            deflater.reset();
            deflater.setInput(content, 0, content.length);
            deflater.finish();
            int length = 0;
            while (!deflater.finished()) {
                if (length == limit) {
                    return -1;
                }
                int produced = deflater.deflate(output, length, limit - length);
                if (produced == 0) {
                    // Finishing with room left always makes progress; stay safe and store the class.
                    return -1;
                }
                length += produced;
            }
            return length;
        }

        /**
         * The buffer the last {@link #deflate(byte[])} wrote into.
         *
         * @return the buffer
         */
        byte[] output() {
            return output;
        }

        /** Releases the deflater's native memory. */
        void end() {
            deflater.end();
        }
    }

    /**
     * The classes of a source archive, as a planning step reads them: each through
     * {@link ZipReader#read(ZipEntryInfo)}, inflated at its exact size and checked against its CRC-32.
     */
    static final class SourceClasses implements ClassTransformPipeline.JarClasses {

        private final ZipReader source;
        private final Map<String, ZipEntryInfo> first = new HashMap<>();
        private final Set<String> repeated = new HashSet<>();
        private final List<ClassTransformPipeline.ClassEntry> classes = new ArrayList<>();

        /**
         * The classes of an archive, less the entries a repack drops.
         *
         * @param source the archive
         */
        SourceClasses(ZipReader source) {
            this.source = source;
            for (ZipEntryInfo entry : source.entries()) {
                if (ZipReader.isSignatureFile(entry.name()) || ZipReader.isIndexList(entry.name())) {
                    continue;
                }
                if (first.putIfAbsent(entry.name(), entry) != null) {
                    repeated.add(entry.name());
                } else if (ClassTransformPipeline.isClass(entry)) {
                    classes.add(new ClassTransformPipeline.ClassEntry(entry.name(), entry.uncompressedSize()));
                }
            }
        }

        @Override
        public List<ClassTransformPipeline.ClassEntry> classes() {
            return classes;
        }

        @Override
        public long size(String entryName) {
            ZipEntryInfo entry = first.get(entryName);
            return entry == null ? -1 : entry.uncompressedSize();
        }

        @Override
        public boolean repeated(String entryName) {
            return repeated.contains(entryName);
        }

        @Override
        public byte[] read(String entryName) throws IOException {
            ZipEntryInfo entry = first.get(entryName);
            if (entry == null) {
                throw new IOException("No entry " + entryName + " in " + source.path());
            }
            return source.read(entry);
        }
    }
}
