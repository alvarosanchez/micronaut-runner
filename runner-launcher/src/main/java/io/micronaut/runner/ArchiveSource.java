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
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Random access to the bytes of the outer runner archive.
 *
 * <p>By default the whole file is mapped once with
 * {@link FileChannel#map(FileChannel.MapMode, long, long, Arena)} into a {@link MemorySegment} owned by a
 * shared {@link Arena}, so that reading an entry costs a page fault at worst and never a system call, and
 * so that defining a class from a STORED entry can hand the VM a {@link ByteBuffer} view instead of a copy.
 * Every accessor takes an <em>absolute offset in the outer file</em>, which is exactly what the index
 * stores, so no per-entry bookkeeping is needed.</p>
 *
 * <p>Setting the system property {@value #MMAP_PROPERTY} to exactly {@code "false"} selects a fallback mode
 * that maps nothing and serves every read with a positional {@link FileChannel#read(ByteBuffer, long)} into
 * a heap buffer. The fallback exists for platforms or containers where a large mapping is unwelcome. It
 * reads the same immutable archive format, but it does not make concurrent changes to that archive safe.</p>
 *
 * <h2>Lifetime</h2>
 * <p>The instance is {@link AutoCloseable}, but the launcher never closes it: application threads keep
 * loading classes for as long as the JVM lives, so the mapping must outlive {@code main}. Tests and
 * benchmarks do close it, and they must: on Windows an open mapping prevents the file from being deleted.
 * {@link #close()} closes the arena, which invalidates the segment, and then the channel. The launcher
 * cannot close the source when {@code main} returns because background threads may still load classes.</p>
 *
 * <h2>Archive immutability</h2>
 * <p>The archive must not be modified, truncated or overwritten from {@link #open(File)} until the JVM
 * terminates. The JDK does not specify when a mapping observes same-length file changes, or which exception
 * an access to a region made inaccessible by truncation will produce. The outcome is operating-system and
 * file-system dependent and may include abnormal JVM termination. Positional reads can likewise observe
 * changed bytes or fail when the file changes underneath them; disabling the mapping is not a deployment
 * replacement protocol.</p>
 *
 * <p>The index's recorded length ({@code IndexFormat.H_OUTER_FILE_LENGTH}) is compared with the length
 * captured when this source opens, and each nested jar's local-header signature is checked once, on first
 * use. Those checks diagnose some stale packaged artifacts at open or first access. They are not ongoing
 * monitoring, do not authenticate the archive, and cannot guarantee safe access after a concurrent
 * mutation.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Everything after {@link #open(File)} is safe for concurrent use by any number of class-loading
 * threads when the archive obeys the immutability requirement: the segment is read-only, positional channel
 * reads do not touch the channel position, and the
 * inflater pool is guarded by its own lock. Only {@link #close()} must not race with readers.</p>
 *
 * @since 1.0
 */
public final class ArchiveSource implements AutoCloseable {

    /**
     * System property that disables memory mapping when set to exactly {@code "false"}.
     */
    public static final String MMAP_PROPERTY = "micronaut.runner.mmap";

    /**
     * Largest slice {@link #slice(long, int)} can produce. {@link MemorySegment#asByteBuffer()} only
     * supports segments up to {@link Integer#MAX_VALUE} bytes, and array allocation is capped a little
     * below that on most VMs, so larger entries have to be streamed by {@link #stream}.
     */
    public static final int MAX_SLICE_LENGTH = Integer.MAX_VALUE - 8;

    /**
     * Number of inflaters kept alive for reuse. Class loading from a DEFLATE archive inflates constantly,
     * and each {@link Inflater} owns a native zlib stream whose allocation dwarfs the pooling cost.
     */
    private static final int INFLATER_POOL_LIMIT = 8;

    /** Buffer size handed to {@link InflaterInputStream}; also the chunk size of raw region reads. */
    private static final int STREAM_BUFFER_SIZE = 8192;

    /** Little-endian, alignment-free view of a {@code u16}. ZIP and index fields are never aligned. */
    private static final ValueLayout.OfShort SHORT_LE =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /** Little-endian, alignment-free view of a {@code u32}. */
    private static final ValueLayout.OfInt INT_LE =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /** Little-endian, alignment-free view of a {@code u64}. */
    private static final ValueLayout.OfLong LONG_LE =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /** {@code MICRONAUT-INF/index.bin} as UTF-8 bytes, compared without decoding a name. */
    private static final byte[] INDEX_ENTRY_NAME_BYTES = indexEntryNameBytes();

    private final File file;
    private final RandomAccessFile handle;
    private final FileChannel channel;
    private final Arena arena;
    private final MemorySegment segment;
    /**
     * Little-endian view of the whole mapping, used in preference to the {@link ValueLayout} accessors.
     *
     * <p>Reading through a {@code ValueLayout} goes through a {@code VarHandle}, and the first such read
     * initialises {@code java.lang.invoke}, which costs milliseconds in a cold JVM and lands squarely on
     * the startup path. A {@link ByteBuffer} absolute getter is an intrinsic with no such bootstrap, and
     * the buffer machinery is loaded anyway because {@link #slice} hands out buffer views. Measured on a
     * 31 MB archive: 10.2 ms to first read through var handles against 8.1 ms through a buffer.</p>
     *
     * <p>{@link MemorySegment#asByteBuffer()} only accepts segments up to {@link Integer#MAX_VALUE}, so
     * this is {@code null} for a larger archive and the {@code ValueLayout} path remains as the fallback.
     * Absolute getters do not touch the buffer's position, so sharing one across threads is safe.</p>
     */
    private final ByteBuffer view;
    private final long length;
    private final ArrayDeque<Inflater> inflaters;
    private boolean closed;

    private ArchiveSource(File file, RandomAccessFile handle, FileChannel channel, Arena arena,
                          MemorySegment segment, long length) {
        this.file = file;
        this.handle = handle;
        this.channel = channel;
        this.arena = arena;
        this.segment = segment;
        this.view = segment != null && length <= MAX_SLICE_LENGTH
                ? segment.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
                : null;
        this.length = length;
        this.inflaters = new ArrayDeque<Inflater>(INFLATER_POOL_LIMIT);
    }

    /**
     * Opens an archive for reading.
     *
     * <p>Nothing about the content is checked here: an archive that is not a runner jar, or not a ZIP file
     * at all, is only diagnosed by {@link #openIndex()}, so that the primitives stay usable over any file.</p>
     *
     * @param file the outer archive, which must exist and be readable
     * @return an open source, mapped unless {@value #MMAP_PROPERTY} is {@code "false"}
     * @throws IOException if the file cannot be opened or cannot be mapped
     */
    public static ArchiveSource open(File file) throws IOException {
        if (file == null) {
            throw new IOException("No archive file given");
        }
        RandomAccessFile handle = new RandomAccessFile(file, "r");
        Arena arena = null;
        try {
            FileChannel channel = handle.getChannel();
            long length = channel.size();
            MemorySegment segment = null;
            if (!"false".equals(System.getProperty(MMAP_PROPERTY))) {
                arena = Arena.ofShared();
                segment = channel.map(FileChannel.MapMode.READ_ONLY, 0L, length, arena);
            }
            return new ArchiveSource(file, handle, channel, arena, segment, length);
        } catch (IOException | RuntimeException | Error e) {
            if (arena != null) {
                arena.close();
            }
            try {
                handle.close();
            } catch (IOException secondary) {
                e.addSuppressed(secondary);
            }
            throw e;
        }
    }

    /**
     * The archive this source reads.
     *
     * @return the file passed to {@link #open(File)}
     */
    public File file() {
        return file;
    }

    /**
     * The length of the archive as it was when it was opened. The index records the same value as a startup
     * staleness diagnostic; this cached value does not monitor later changes to the file.
     *
     * @return the file length in bytes
     */
    public long length() {
        return length;
    }

    /**
     * Whether the archive is memory mapped.
     *
     * @return {@code true} in the default mode, {@code false} when {@value #MMAP_PROPERTY} disabled mapping
     */
    public boolean mapped() {
        return segment != null;
    }

    /**
     * Reads one unsigned byte.
     *
     * @param offset absolute offset in the archive
     * @return the value, in {@code 0..255}
     * @throws IOException if the offset is outside the archive or the read fails
     */
    public int u8(long offset) throws IOException {
        checkRange(offset, 1);
        ByteBuffer b = view;
        if (b != null) {
            return b.get((int) offset) & 0xFF;
        }
        MemorySegment s = segment;
        if (s != null) {
            return s.get(ValueLayout.JAVA_BYTE, offset) & 0xFF;
        }
        return (int) readScalar(offset, 1);
    }

    /**
     * Reads a little-endian unsigned 16-bit value.
     *
     * @param offset absolute offset in the archive
     * @return the value, in {@code 0..65535}
     * @throws IOException if the offset is outside the archive or the read fails
     */
    public int u16(long offset) throws IOException {
        checkRange(offset, 2);
        ByteBuffer b = view;
        if (b != null) {
            return b.getShort((int) offset) & 0xFFFF;
        }
        MemorySegment s = segment;
        if (s != null) {
            return s.get(SHORT_LE, offset) & 0xFFFF;
        }
        return (int) readScalar(offset, 2);
    }

    /**
     * Reads a little-endian 32-bit value as a signed {@code int}. Use it for values compared against a
     * signature constant, where the sign is irrelevant.
     *
     * @param offset absolute offset in the archive
     * @return the raw 32 bits
     * @throws IOException if the offset is outside the archive or the read fails
     */
    public int i32(long offset) throws IOException {
        checkRange(offset, 4);
        ByteBuffer b = view;
        if (b != null) {
            return b.getInt((int) offset);
        }
        MemorySegment s = segment;
        if (s != null) {
            return s.get(INT_LE, offset);
        }
        return (int) readScalar(offset, 4);
    }

    /**
     * Reads a little-endian unsigned 32-bit value, widened so that values above
     * {@link Integer#MAX_VALUE} stay positive.
     *
     * @param offset absolute offset in the archive
     * @return the value, in {@code 0..4294967295}
     * @throws IOException if the offset is outside the archive or the read fails
     */
    public long u32(long offset) throws IOException {
        return i32(offset) & 0xFFFFFFFFL;
    }

    /**
     * Reads a little-endian 64-bit value. ZIP64 sizes and offsets are always well below {@code 2^63}, so
     * the signed result is the value.
     *
     * @param offset absolute offset in the archive
     * @return the value
     * @throws IOException if the offset is outside the archive or the read fails
     */
    public long u64(long offset) throws IOException {
        checkRange(offset, 8);
        ByteBuffer b = view;
        if (b != null) {
            return b.getLong((int) offset);
        }
        MemorySegment s = segment;
        if (s != null) {
            return s.get(LONG_LE, offset);
        }
        return readScalar(offset, 8);
    }

    /**
     * A read-only view of a region of the archive. In the mapped mode this copies nothing: the buffer is a
     * window onto the mapping, which is what lets the class loader define a STORED class without ever
     * materialising a {@code byte[]}. In the fallback mode the region is read into a heap array first.
     *
     * <p>The returned buffer's byte order is the {@link ByteBuffer} default, big-endian; callers reading
     * little-endian structures out of it must set the order themselves.</p>
     *
     * @param offset absolute offset in the archive
     * @param length number of bytes, at most {@link #MAX_SLICE_LENGTH}
     * @return a read-only buffer positioned at zero with the region as its content
     * @throws IOException if the region is outside the archive, the length is negative or too large, or
     *                     the read fails
     */
    public ByteBuffer slice(long offset, int length) throws IOException {
        if (length < 0 || length > MAX_SLICE_LENGTH) {
            throw new IOException("Cannot slice " + length + " bytes, the limit is " + MAX_SLICE_LENGTH);
        }
        checkRange(offset, length);
        MemorySegment s = segment;
        if (s != null) {
            return s.asSlice(offset, length).asByteBuffer();
        }
        return ByteBuffer.wrap(readFully(offset, length)).asReadOnlyBuffer();
    }

    /**
     * Copies a region of the archive into a new array.
     *
     * @param offset absolute offset in the archive
     * @param length number of bytes
     * @return a new array of exactly {@code length} bytes
     * @throws IOException if the region is outside the archive, the length is negative, or the read fails
     */
    public byte[] readFully(long offset, int length) throws IOException {
        if (length < 0) {
            throw new IOException("Negative read length " + length);
        }
        checkRange(offset, length);
        byte[] result = new byte[length];
        copyTo(offset, result, 0, length);
        return result;
    }

    /**
     * Streams the content of an entry, decompressing it when needed.
     *
     * <p>The stream delivers exactly {@code uncompressedSize} bytes and then reports end of input. Fewer
     * bytes (a truncated archive or a truncated deflate stream) and more bytes (data that does not match
     * the recorded size) both raise an {@link IOException}, because either means the archive no longer
     * agrees with the index. Entries larger than {@link #MAX_SLICE_LENGTH}, which {@link #slice} cannot
     * cover, stream correctly: the region is read in chunks.</p>
     *
     * @param dataOffset       absolute offset of the entry data, that is, past its local file header
     * @param compressedSize   number of stored bytes
     * @param uncompressedSize number of bytes the caller must see
     * @param method           {@link IndexFormat#METHOD_STORED} or {@link IndexFormat#METHOD_DEFLATED}
     * @return a stream over the entry content, which the caller closes
     * @throws IOException if the region is outside the archive, the sizes disagree for a STORED entry, or
     *                     the method is not supported
     */
    public InputStream stream(long dataOffset, long compressedSize, long uncompressedSize, int method)
            throws IOException {
        if (compressedSize < 0 || uncompressedSize < 0) {
            throw new IOException("Negative entry size at offset " + dataOffset);
        }
        checkRange(dataOffset, compressedSize);
        if (method == IndexFormat.METHOD_STORED) {
            if (compressedSize != uncompressedSize) {
                throw new IOException("Stored entry at offset " + dataOffset + " has compressed size "
                        + compressedSize + " but uncompressed size " + uncompressedSize);
            }
            RegionInputStream raw = new RegionInputStream(this, dataOffset, compressedSize);
            return new EntryInputStream(raw, raw, uncompressedSize, this, null);
        }
        if (method != IndexFormat.METHOD_DEFLATED) {
            throw new IOException("Unsupported compression method " + method + " at offset " + dataOffset);
        }
        Inflater inflater = acquireInflater();
        RegionInputStream raw = new RegionInputStream(this, dataOffset, compressedSize);
        return new EntryInputStream(new InflaterInputStream(raw, inflater, STREAM_BUFFER_SIZE), raw,
                uncompressedSize, this, inflater);
    }

    /**
     * Decompresses a DEFLATE entry into an array of exactly {@code uncompressedSize} bytes.
     *
     * <p>This is the class loading path for a compressed archive, so it allocates one array, borrows an
     * inflater from the pool and, in the mapped mode, feeds the inflater a direct buffer over the mapping
     * rather than copying the compressed bytes first.</p>
     *
     * @param dataOffset       absolute offset of the entry data
     * @param compressedSize   number of stored bytes
     * @param uncompressedSize number of bytes the entry expands to
     * @return the decompressed content
     * @throws IOException if the deflate stream is corrupt, ends early, produces more than
     *                     {@code uncompressedSize} bytes, or does not consume all {@code compressedSize} bytes
     */
    public byte[] inflate(long dataOffset, int compressedSize, int uncompressedSize) throws IOException {
        if (compressedSize < 0 || uncompressedSize < 0) {
            throw new IOException("Negative entry size at offset " + dataOffset);
        }
        checkRange(dataOffset, compressedSize);
        byte[] result = new byte[uncompressedSize];
        Inflater inflater = acquireInflater();
        try {
            MemorySegment s = segment;
            if (s != null) {
                inflater.setInput(s.asSlice(dataOffset, compressedSize).asByteBuffer());
            } else {
                inflater.setInput(readFully(dataOffset, compressedSize));
            }
            int done = 0;
            byte[] probe = new byte[1];
            while (!inflater.finished()) {
                int n;
                if (done < uncompressedSize) {
                    n = inflater.inflate(result, done, uncompressedSize - done);
                } else {
                    n = inflater.inflate(probe, 0, 1);
                }
                if (n > 0) {
                    if (done == uncompressedSize) {
                        throw new IOException("Deflate stream at offset " + dataOffset
                                + " produces more than the recorded " + uncompressedSize + " bytes");
                    }
                    done += n;
                } else if (inflater.finished()) {
                    // The terminal block may produce no plaintext, including for an empty entry.
                    continue;
                } else if (inflater.needsDictionary()) {
                    throw new IOException("Deflate stream at offset " + dataOffset + " requires a dictionary");
                } else if (inflater.needsInput()) {
                    throw new IOException("Deflate stream at offset " + dataOffset + " ended after " + done
                            + " bytes but the index records " + uncompressedSize);
                } else {
                    throw new IOException("Deflate stream at offset " + dataOffset + " made no progress after "
                            + done + " bytes");
                }
            }
            if (done != uncompressedSize) {
                throw new IOException("Deflate stream at offset " + dataOffset + " ended after " + done
                        + " bytes but the index records " + uncompressedSize);
            }
            int remaining = inflater.getRemaining();
            if (remaining != 0) {
                throw new IOException("Deflate stream at offset " + dataOffset + " ended with " + remaining
                        + " unused compressed bytes");
            }
            return result;
        } catch (DataFormatException e) {
            throw new IOException("Corrupt deflate stream at offset " + dataOffset, e);
        } finally {
            releaseInflater(inflater);
        }
    }

    /**
     * Locates {@code MICRONAUT-INF/index.bin} in the outer archive without {@code java.util.zip}.
     *
     * <p>The end of central directory record is found by scanning back from the end of the file, following
     * the ZIP64 locator when one sits immediately before it, falling back to the 32-bit fields when the
     * entry count carries the {@code 0xFFFF} marker but no locator does, and the central directory is
     * then walked until
     * the index entry is found. By construction it is the second entry, right after the manifest, so this
     * reads a couple of records. The entry's true data offset comes from its <em>local</em> header, whose
     * name and extra field lengths may differ from the central directory's.</p>
     *
     * @return a two element array: the absolute offset of the index data and its length in bytes
     * @throws IOException if the file is not a ZIP archive, carries no index, or the archive disagrees
     *                     with itself
     */
    public long[] openIndex() throws IOException {
        long endOfCentralDirectory = findEndOfCentralDirectory();
        long entries = u16(endOfCentralDirectory + 10);
        long directorySize = u32(endOfCentralDirectory + 12);
        long directoryOffset = u32(endOfCentralDirectory + 16);
        // A 16-bit entry count of 0xFFFF is not on its own a promise that the ZIP64 records are there:
        // a writer that compares with > rather than >= leaves an archive of exactly 65535 entries looking
        // like this, and 65535 is then the true count. java.util.zip reads such an archive, so the
        // launcher must too, or a jar every other tool accepts refuses to start. A marked directory size
        // or offset is different: the real value exists only in the ZIP64 record.
        boolean needsZip64 = directoryOffset == IndexFormat.ZIP64_MARKER
                || directorySize == IndexFormat.ZIP64_MARKER;
        long locator = endOfCentralDirectory - IndexFormat.ZIP64_LOCATOR_SIZE;
        boolean hasLocator = locator >= 0
                && i32(locator) == IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE;
        if (hasLocator) {
            long zip64 = u64(locator + 8);
            if (zip64 < 0 || zip64 + 56 > length) {
                throw new IOException("ZIP64 end of central directory offset " + zip64
                        + " is outside the archive " + file);
            }
            if (i32(zip64) != IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                throw new IOException("No ZIP64 end of central directory record at offset " + zip64);
            }
            entries = u64(zip64 + 32);
            directorySize = u64(zip64 + 40);
            directoryOffset = u64(zip64 + 48);
        } else if (needsZip64) {
            throw new IOException("The archive " + file
                    + " needs a ZIP64 end of central directory record but none is present");
        }
        return findIndexEntry(directoryOffset, directorySize, entries);
    }

    /**
     * Releases the mapping and the file handle. The launcher never calls this; tests, tools and benchmarks
     * must, because an open mapping keeps the file alive on Windows.
     *
     * @throws UncheckedIOException if the channel cannot be closed
     */
    @Override
    public void close() {
        Inflater[] pooled;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            pooled = inflaters.toArray(new Inflater[0]);
            inflaters.clear();
        }
        for (int i = 0; i < pooled.length; i++) {
            pooled[i].end();
        }
        if (arena != null) {
            arena.close();
        }
        IOException failure = null;
        try {
            channel.close();
        } catch (IOException e) {
            failure = e;
        }
        try {
            handle.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw new UncheckedIOException("Failed to close " + file, failure);
        }
    }

    private static byte[] indexEntryNameBytes() {
        String name = IndexFormat.INDEX_ENTRY_NAME;
        byte[] bytes = new byte[name.length()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) name.charAt(i);
        }
        return bytes;
    }

    private void checkRange(long offset, long count) throws IOException {
        if (offset < 0 || count < 0 || offset + count > length || offset + count < 0) {
            throw new IOException("Read of " + count + " bytes at offset " + offset
                    + " is outside the archive " + file + " of length " + length);
        }
    }

    private long readScalar(long offset, int count) throws IOException {
        byte[] scratch = new byte[count];
        copyTo(offset, scratch, 0, count);
        long value = 0;
        for (int i = count - 1; i >= 0; i--) {
            value = (value << 8) | (scratch[i] & 0xFFL);
        }
        return value;
    }

    private void copyTo(long offset, byte[] destination, int destinationOffset, int count)
            throws IOException {
        MemorySegment s = segment;
        if (s != null) {
            MemorySegment.copy(s, ValueLayout.JAVA_BYTE, offset, destination, destinationOffset, count);
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(destination, destinationOffset, count);
        long position = offset;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new IOException("Unexpected end of " + file + " at offset " + position);
            }
            position += read;
        }
    }

    private Inflater acquireInflater() {
        Inflater pooled;
        synchronized (this) {
            pooled = inflaters.pollFirst();
        }
        if (pooled != null) {
            return pooled;
        }
        return new Inflater(true);
    }

    private void releaseInflater(Inflater inflater) {
        inflater.reset();
        synchronized (this) {
            if (!closed && inflaters.size() < INFLATER_POOL_LIMIT) {
                inflaters.addFirst(inflater);
                return;
            }
        }
        inflater.end();
    }

    private long findEndOfCentralDirectory() throws IOException {
        long direct = length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        if (direct >= 0 && i32(direct) == IndexFormat.END_OF_CENTRAL_DIRECTORY_SIGNATURE
                && u16(direct + 20) == 0) {
            return direct;
        }
        int window = (int) Math.min(length,
                IndexFormat.MAX_COMMENT_SIZE + (long) IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE);
        long start = length - window;
        byte[] tail = readFully(start, window);
        for (int i = window - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE; i >= 0; i--) {
            if (littleEndianInt(tail, i) == IndexFormat.END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                int comment = (tail[i + 20] & 0xFF) | ((tail[i + 21] & 0xFF) << 8);
                if (start + i + IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE + comment == length) {
                    return start + i;
                }
            }
        }
        throw new IOException("Not a ZIP archive, no end of central directory record in " + file);
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private long[] findIndexEntry(long directoryOffset, long directorySize, long entries)
            throws IOException {
        if (directoryOffset < 0 || directorySize < 0 || directoryOffset + directorySize > length) {
            throw new IOException("Central directory of " + file + " at offset " + directoryOffset
                    + " with size " + directorySize + " is outside the archive");
        }
        byte[] wanted = INDEX_ENTRY_NAME_BYTES;
        long end = directoryOffset + directorySize;
        long position = directoryOffset;
        for (long i = 0; i < entries && position + 46 <= end; i++) {
            if (i32(position) != IndexFormat.CENTRAL_HEADER_SIGNATURE) {
                throw new IOException("Corrupt central directory in " + file + " at offset " + position);
            }
            int method = u16(position + 10);
            long compressedSize = u32(position + 20);
            long uncompressedSize = u32(position + 24);
            int nameLength = u16(position + 28);
            int extraLength = u16(position + 30);
            int commentLength = u16(position + 32);
            long localHeaderOffset = u32(position + 42);
            if (nameLength == wanted.length && nameMatches(position + 46, wanted)) {
                long[] sizes = new long[] {uncompressedSize, compressedSize, localHeaderOffset};
                if (uncompressedSize == IndexFormat.ZIP64_MARKER
                        || compressedSize == IndexFormat.ZIP64_MARKER
                        || localHeaderOffset == IndexFormat.ZIP64_MARKER) {
                    readZip64Extra(position + 46 + nameLength, extraLength, sizes);
                }
                if (method != IndexFormat.METHOD_STORED) {
                    throw new IOException("The " + IndexFormat.INDEX_ENTRY_NAME + " entry of " + file
                            + " is compressed with method " + method + " but must be stored");
                }
                return indexLocation(sizes[2], sizes[0]);
            }
            position += 46L + nameLength + extraLength + commentLength;
        }
        throw new IOException("Not a runner jar, " + file + " has no " + IndexFormat.INDEX_ENTRY_NAME
                + " entry");
    }

    private boolean nameMatches(long offset, byte[] wanted) throws IOException {
        byte[] actual = readFully(offset, wanted.length);
        for (int i = 0; i < wanted.length; i++) {
            if (actual[i] != wanted[i]) {
                return false;
            }
        }
        return true;
    }

    private void readZip64Extra(long offset, int extraLength, long[] sizes) throws IOException {
        long position = offset;
        long end = offset + extraLength;
        while (position + 4 <= end) {
            int id = u16(position);
            int size = u16(position + 2);
            if (position + 4 + size > end) {
                throw new IOException("Truncated extra field in the central directory of " + file);
            }
            if (id == IndexFormat.ZIP64_EXTRA_FIELD_ID) {
                long field = position + 4;
                long limit = field + size;
                for (int i = 0; i < sizes.length; i++) {
                    if (sizes[i] == IndexFormat.ZIP64_MARKER) {
                        if (field + 8 > limit) {
                            throw new IOException("ZIP64 extra field of " + IndexFormat.INDEX_ENTRY_NAME
                                    + " in " + file + " is too short");
                        }
                        sizes[i] = u64(field);
                        field += 8;
                    }
                }
                return;
            }
            position += 4 + size;
        }
        throw new IOException("The " + IndexFormat.INDEX_ENTRY_NAME + " entry of " + file
                + " needs a ZIP64 extra field but none is present");
    }

    private long[] indexLocation(long localHeaderOffset, long size) throws IOException {
        if (localHeaderOffset < 0 || localHeaderOffset + 30 > length) {
            throw new IOException("Local header of " + IndexFormat.INDEX_ENTRY_NAME + " in " + file
                    + " is outside the archive");
        }
        if (i32(localHeaderOffset) != IndexFormat.LOCAL_HEADER_SIGNATURE) {
            throw new IOException("No local file header for " + IndexFormat.INDEX_ENTRY_NAME + " in " + file
                    + " at offset " + localHeaderOffset + "; the jar was modified after packaging");
        }
        int nameLength = u16(localHeaderOffset + 26);
        int extraLength = u16(localHeaderOffset + 28);
        long dataOffset = localHeaderOffset + 30 + nameLength + extraLength;
        checkRange(dataOffset, size);
        return new long[] {dataOffset, size};
    }

    /**
     * A stream over a raw region of the archive, chunked so that regions beyond {@link Integer#MAX_VALUE}
     * work, and free of the mapped or fallback distinction because it goes through {@link #copyTo}.
     */
    private static final class RegionInputStream extends InputStream {

        private final ArchiveSource source;
        private long position;
        private long remaining;

        private RegionInputStream(ArchiveSource source, long offset, long count) {
            this.source = source;
            this.position = offset;
            this.remaining = count;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = source.u8(position);
            position++;
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] destination, int offset, int count) throws IOException {
            if (destination == null) {
                throw new NullPointerException("destination");
            }
            if (offset < 0 || count < 0 || count > destination.length - offset) {
                throw new IndexOutOfBoundsException("offset " + offset + ", count " + count);
            }
            if (count == 0) {
                return 0;
            }
            if (remaining <= 0) {
                return -1;
            }
            int chunk = (int) Math.min(count, remaining);
            source.copyTo(position, destination, offset, chunk);
            position += chunk;
            remaining -= chunk;
            return chunk;
        }

        @Override
        public long skip(long count) {
            long skipped = Math.min(Math.max(count, 0L), remaining);
            position += skipped;
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() {
            return (int) Math.min(remaining, Integer.MAX_VALUE);
        }

        private long remaining() {
            return remaining;
        }
    }

    /**
     * Enforces that exactly the recorded number of bytes reaches the caller, and returns the borrowed
     * inflater to the pool on close.
     */
    private static final class EntryInputStream extends InputStream {

        private final InputStream delegate;
        private final RegionInputStream raw;
        private final ArchiveSource source;
        private final Inflater inflater;
        private long remaining;
        private boolean checkedTrailing;
        private boolean closed;

        private EntryInputStream(InputStream delegate, RegionInputStream raw, long size,
                                 ArchiveSource source, Inflater inflater) {
            this.delegate = delegate;
            this.raw = raw;
            this.source = source;
            this.inflater = inflater;
            this.remaining = size;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                checkTrailing();
                return -1;
            }
            int value = delegate.read();
            if (value < 0) {
                throw truncated();
            }
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] destination, int offset, int count) throws IOException {
            if (count == 0) {
                return 0;
            }
            if (remaining <= 0) {
                checkTrailing();
                return -1;
            }
            int chunk = (int) Math.min(count, remaining);
            int read = delegate.read(destination, offset, chunk);
            if (read < 0) {
                throw truncated();
            }
            remaining -= read;
            return read;
        }

        @Override
        public long skip(long count) throws IOException {
            long wanted = Math.min(Math.max(count, 0L), remaining);
            long skipped = delegate.skip(wanted);
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() {
            return (int) Math.min(remaining, Integer.MAX_VALUE);
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                delegate.close();
            } finally {
                if (inflater != null) {
                    source.releaseInflater(inflater);
                }
            }
        }

        private void checkTrailing() throws IOException {
            if (checkedTrailing) {
                return;
            }
            checkedTrailing = true;
            if (delegate.read() >= 0) {
                throw new IOException("The entry holds more data than the index records");
            }
            if (inflater != null) {
                if (!inflater.finished()) {
                    throw new IOException("The deflate stream ended before its terminal block");
                }
                long unused = inflater.getRemaining() + raw.remaining();
                if (unused != 0) {
                    throw new IOException("The deflate stream ended with " + unused
                            + " unused compressed bytes");
                }
            }
        }

        private IOException truncated() {
            return new IOException("The entry ended " + remaining
                    + " bytes before the size the index records");
        }
    }
}
