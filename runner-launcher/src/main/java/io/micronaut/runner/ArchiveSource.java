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

import java.io.EOFException;
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
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.zip.CRC32;
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
 * <h2>Read modes</h2>
 * <p>The system property {@value #MMAP_PROPERTY} selects how the archive is read:</p>
 * <ul>
 *   <li>{@code full} or {@code true}: the whole file is mapped, as described above.</li>
 *   <li>{@code index}: only {@code MICRONAUT-INF/index.bin} is mapped. Every other read is a positional
 *   {@link FileChannel#read(ByteBuffer, long)}, and a STORED class is read with one such call into a direct
 *   buffer borrowed from a small pool; see {@link #borrow(long, int)}. The pages of the class bytes then stay
 *   in the page cache and out of the process's resident set, at the price of a system call per class.</li>
 *   <li>{@code false}: nothing is mapped, and every read is a positional read into a heap buffer, at most
 *   64 KiB per call so that no thread keeps a large temporary native buffer. It exists for platforms or
 *   containers where a mapping is unwelcome; it is behaviourally identical, only slower.</li>
 *   <li>Unset, or any other value: the archive decides. {@link #open(File)} maps the whole file, exactly as
 *   for {@code full}, and {@link #indexRegion(long, int)} reads {@code IndexFormat.HEADER_FLAG_POSITIONAL_READS}
 *   from the index header. When the flag is set, the source switches to the {@code index} mode before anything
 *   else has read through the mapping: it captures the file key, closes the whole-file mapping and maps the
 *   index alone. An archive whose flag is clear keeps the whole-file mapping and pays one extra read.</li>
 * </ul>
 *
 * <p>A {@link FileChannel} is interruptible: a read by a thread whose interrupt status is set, or that is
 * interrupted during the read, closes the channel for every thread. Positional reads therefore clear the
 * caller's interrupt status before each attempt and restore it afterwards, and a read that finds the channel
 * closed by an interrupt reopens the archive by its path and continues from the bytes it already has. The
 * reopened file is used only if it still has the length, and where the file system reports one the file key,
 * captured before the first positional read; otherwise every later read fails and asks for a restart. Nothing
 * reopens after {@link #close()}.</p>
 *
 * <h2>Lifetime</h2>
 * <p>The instance is {@link AutoCloseable}, but the launcher never closes it: application threads keep
 * loading classes for as long as the JVM lives, so the mapping must outlive {@code main}. Tests and
 * benchmarks do close it, and they must: on Windows an open mapping prevents the file from being deleted.
 * {@link #close()} closes the arenas, which invalidates the segments, drops the buffer pool and then closes
 * the channel.</p>
 *
 * <h2>Archive immutability</h2>
 * <p>The archive must not change while it is open. The recorded length
 * ({@code IndexFormat.H_OUTER_FILE_LENGTH}, compared at open) and each nested jar's local-header signature
 * (checked once, on first use) diagnose stale packaging only; they do not detect later changes. In mapped
 * mode a truncation can surface as an {@link InternalError} from a read, or terminate the VM while a STORED
 * class is being defined straight from the mapping.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Everything after {@link #open(File)} and the first {@link #indexRegion(long, int)}, which may switch the
 * mode and is called by {@link Index#open(ArchiveSource, long, long)} before the source is shared, is safe for
 * concurrent use by any number of class-loading threads: the segments are read-only, positional channel reads
 * do not touch the channel position, and the inflater and buffer pools are guarded by this instance's lock.
 * Thread interrupts are harmless in every mode: mapped reads are not interruptible, and positional reads clear
 * and restore the caller's interrupt status and reopen, under the same lock, a channel that an interrupt
 * closed. Only {@link #close()} must not race with readers; a read that does fails and never reopens.</p>
 *
 * @since 1.0
 */
public final class ArchiveSource implements AutoCloseable {

    /**
     * System property that selects the read mode: {@code full} (or {@code true}), {@code index} or
     * {@code false}. Unset, or any other value, lets the archive's header choose between {@code full} and
     * {@code index}. Values are compared exactly, so {@code FALSE} is an unrecognised value.
     */
    public static final String MMAP_PROPERTY = "micronaut.runner.mmap";

    /**
     * The mode of a source whose {@value #MMAP_PROPERTY} is unset or unrecognised, until
     * {@link #indexRegion(long, int)} has read the archive's header: the whole file is mapped, as in
     * {@link #MODE_FULL}.
     */
    static final int MODE_DEFAULT = 0;

    /** The whole file is mapped, and every read goes through the mapping. */
    static final int MODE_FULL = 1;

    /** Only the index is mapped. Everything else is read positionally, STORED classes into pooled buffers. */
    static final int MODE_INDEX = 2;

    /** Nothing is mapped, and every read is positional, into a heap buffer. */
    static final int MODE_NONE = 3;

    /**
     * Most direct buffers the {@code index} mode's pool creates, so that it never holds more than
     * {@code POOL_MAX_BUFFERS * POOL_MAX_BUFFER_SIZE} bytes, 1 MiB, of native memory. A borrow that finds
     * every one of them in use reads into a heap array instead of allocating another.
     */
    static final int POOL_MAX_BUFFERS = 16;

    /** Largest pooled buffer. An entry larger than the pool's buffers is read into a heap array. */
    static final int POOL_MAX_BUFFER_SIZE = 64 * 1024;

    /** Idle buffers the pool keeps once {@link #startupFinished()} has run; before that, all of them. */
    static final int POOL_IDLE_AFTER_STARTUP = 2;

    /** The granularity a pooled buffer's size is rounded up to. */
    private static final int POOL_BUFFER_ALIGNMENT = 4096;

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

    /**
     * Buffer size handed to {@link InflaterInputStream}; also the chunk size of raw region reads, and the
     * largest skip buffer of a verifying stream.
     */
    private static final int STREAM_BUFFER_SIZE = 8192;

    /** {@code MICRONAUT-INF/index.bin} as UTF-8 bytes, compared without decoding a name. */
    private static final byte[] INDEX_ENTRY_NAME_BYTES = indexEntryNameBytes();

    private final File file;
    /**
     * The open archive and its channel. Both are {@code volatile} because a positional read that finds the
     * channel closed by a thread interrupt publishes a reopened pair; see {@link PositionalReads}. In the
     * mapped mode they are only read by {@link #close()}.
     */
    private volatile RandomAccessFile handle;
    private volatile FileChannel channel;
    /**
     * The identity of the file this source opened, and {@code null} in the mapped modes or when the file
     * system reports no key (Windows). A reopen after an interrupt compares it with the file the path names by
     * then.
     *
     * <p>It is captured before the first positional read: at open when {@value #MMAP_PROPERTY} asks for
     * {@code index} or {@code false}, and otherwise in {@link #indexRegion(long, int)}, at the switch the
     * archive's flag selects. It is not final for that second case, and it is written only there, before
     * {@link Launcher} publishes the source to {@link Handlers} and the class loader. The archive-selected
     * capture leaves a window of a few milliseconds between the open and the switch: a replacement renamed
     * onto the path inside it is not detected, because the key then names the new file.</p>
     */
    private Object fileKey;
    /**
     * The failure of a reopen that found the path naming another file, stored under this instance's lock so
     * that every later read fails the same way instead of reopening again.
     */
    private IOException replaced;
    /**
     * The arena of the whole-file mapping, {@code null} when nothing is mapped or once the archive's flag
     * switched the source to the {@code index} mode, which closes it.
     */
    private Arena arena;
    /**
     * The whole-file mapping. Not final: {@link #indexRegion(long, int)} clears it when the archive's flag
     * switches the source to the {@code index} mode, which happens inside {@link Index#open(ArchiveSource,
     * long, long)} before {@link Launcher} publishes the source to {@link Handlers} and the class loader, and
     * before anything has sliced the mapping.
     */
    private MemorySegment segment;
    /**
     * Little-endian view of the whole mapping, through which every mapped read goes: the scalar getters,
     * {@link #copyTo}, {@link #slice} and {@link #inflate}. Cleared, like {@link #segment}, by the switch to
     * the {@code index} mode.
     *
     * <p>A {@link ByteBuffer} keeps work off the startup path that the {@link MemorySegment} API adds on
     * JDK 25: the first {@code MemorySegment.copy} into an array bootstraps a pattern {@code switch}, which
     * spins a hidden class with the ClassFile API, and the {@link ValueLayout} constants that segment reads
     * take initialise a dozen layout classes from {@code jrt:/java.base} rather than the CDS archive.
     * Reading a {@code ValueLayout} also goes through a {@code VarHandle}. A buffer's absolute getters and
     * bulk get need none of that, and {@code view.slice} is one object where
     * {@code segment.asSlice(...).asByteBuffer()} is three.</p>
     *
     * <p>{@link MemorySegment#asByteBuffer()} only accepts segments up to {@link Integer#MAX_VALUE}, so
     * this is {@code null} for a larger archive, and only then do mapped reads go through the segment and
     * {@link SegmentLayouts}. Absolute accessors do not touch the buffer's position, so sharing one across
     * threads is safe.</p>
     */
    private ByteBuffer view;
    private final long length;
    /**
     * One of the {@code MODE_*} constants. {@link #MODE_DEFAULT} becomes {@link #MODE_FULL} or
     * {@link #MODE_INDEX} in the first {@link #indexRegion(long, int)}, before the source is published, and
     * never changes afterwards.
     */
    private int mode;
    /** The arena of the index mappings of the {@code index} mode, created by the first one. */
    private Arena indexArena;
    private final ArrayDeque<Inflater> inflaters;
    /** The idle pooled buffers of the {@code index} mode, most recently released first. Guarded by this. */
    private final ArrayDeque<ByteBuffer> buffers;
    /** The capacity of every pooled buffer, fixed by the first index or the first borrow. Guarded by this. */
    private int poolBufferSize;
    /** Pooled buffers that exist, idle or borrowed; never more than {@link #POOL_MAX_BUFFERS}. Guarded by this. */
    private int liveBuffers;
    /** Pooled buffers ever allocated, for tests. Guarded by this. */
    private int createdBuffers;
    /** How many idle buffers {@link #release(ByteBuffer)} keeps. Guarded by this. */
    private int maxIdle = POOL_MAX_BUFFERS;
    private boolean closed;

    private ArchiveSource(File file, RandomAccessFile handle, FileChannel channel, Object fileKey, Arena arena,
                          MemorySegment segment, long length, boolean bufferView, int mode) {
        this.file = file;
        this.handle = handle;
        this.channel = channel;
        this.fileKey = fileKey;
        this.arena = arena;
        this.segment = segment;
        this.view = bufferView && segment != null && length <= MAX_SLICE_LENGTH
                ? segment.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
                : null;
        this.length = length;
        this.mode = mode;
        this.inflaters = new ArrayDeque<Inflater>(INFLATER_POOL_LIMIT);
        this.buffers = new ArrayDeque<ByteBuffer>(POOL_MAX_BUFFERS);
    }

    /**
     * Opens an archive for reading.
     *
     * <p>Nothing about the content is checked here: an archive that is not a runner jar, or not a ZIP file
     * at all, is only diagnosed by {@link #openIndex()}, so that the primitives stay usable over any file.</p>
     *
     * @param file the outer archive, which must exist and be readable
     * @return an open source, mapped as a whole unless {@value #MMAP_PROPERTY} is {@code "index"} or
     *         {@code "false"}
     * @throws IOException if the file cannot be opened or cannot be mapped
     */
    public static ArchiveSource open(File file) throws IOException {
        return open(file, true);
    }

    /**
     * Opens an archive, optionally without the buffer view of the mapping, so that tests can run the
     * segment reads that otherwise only an archive over {@link #MAX_SLICE_LENGTH} bytes takes.
     *
     * @param file       the outer archive
     * @param bufferView {@code false} to read a mapped archive through its segment only
     * @return an open source
     * @throws IOException if the file cannot be opened or cannot be mapped
     */
    static ArchiveSource open(File file, boolean bufferView) throws IOException {
        if (file == null) {
            throw new IOException("No archive file given");
        }
        int mode = requestedMode(System.getProperty(MMAP_PROPERTY));
        boolean map = mode == MODE_DEFAULT || mode == MODE_FULL;
        // Reads through the channel may have to reopen the file by its path after an interrupt, and then
        // compare it with this key. It is read before the file is opened: if the path is replaced in between,
        // the key cannot match any later file, which fails safe, whereas a key read after the open could
        // belong to a file renamed in afterwards and let a reopen switch to it. A mapped open reads no key,
        // which keeps the stat and its java.nio.file classes off the default launch.
        Object fileKey = map ? null : PositionalReads.fileKey(file);
        RandomAccessFile handle = new RandomAccessFile(file, "r");
        Arena arena = null;
        try {
            FileChannel channel = handle.getChannel();
            long length = channel.size();
            MemorySegment segment = null;
            if (map) {
                arena = Arena.ofShared();
                segment = channel.map(FileChannel.MapMode.READ_ONLY, 0L, length, arena);
            }
            return new ArchiveSource(file, handle, channel, fileKey, arena, segment, length, bufferView, mode);
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
     * The length of the archive when it was opened, which {@link Index} compares with the length it recorded.
     *
     * @return the file length in bytes
     */
    public long length() {
        return length;
    }

    /**
     * The read mode {@value #MMAP_PROPERTY} asks for.
     *
     * @param value the property's value, or {@code null} when it is unset
     * @return {@link #MODE_FULL} for {@code full} or {@code true}, {@link #MODE_INDEX} for {@code index},
     *         {@link #MODE_NONE} for {@code false}, and {@link #MODE_DEFAULT} for anything else
     */
    static int requestedMode(String value) {
        if ("false".equals(value)) {
            return MODE_NONE;
        }
        if ("index".equals(value)) {
            return MODE_INDEX;
        }
        if ("full".equals(value) || "true".equals(value)) {
            return MODE_FULL;
        }
        return MODE_DEFAULT;
    }

    /**
     * Whether the whole archive is memory mapped.
     *
     * @return {@code true} in the {@code full} mode, and in the default mode until the archive's flag selects
     *         the {@code index} mode; {@code false} in the {@code index} and {@code false} modes
     */
    public boolean mapped() {
        return segment != null;
    }

    /**
     * Whether only the index is mapped, and every other read is positional.
     *
     * @return {@code true} in the {@code index} mode, whether {@value #MMAP_PROPERTY} or the archive's flag
     *         selected it
     */
    public boolean indexOnly() {
        return mode == MODE_INDEX;
    }

    /**
     * The bytes of the index, for {@link Index#open(ArchiveSource, long, long)}, which is where the read mode
     * of a source left to the archive is settled.
     *
     * <p>In the default mode the whole file is mapped, and this reads the header flag word through the
     * mapping. With {@code IndexFormat.HEADER_FLAG_POSITIONAL_READS} clear the source becomes {@code full}
     * and this returns a {@link #slice}, exactly as before the flag existed. With the flag set the source
     * switches to the {@code index} mode: it captures the file key that a reopen after an interrupt compares,
     * closes the whole-file mapping, which nothing has sliced yet, and maps the index region alone. In the
     * {@code index} mode, however selected, the region is mapped into an arena of its own, and the first region
     * sizes the buffer pool from {@code IndexFormat.H_LARGEST_STORED_CLASS}. The other modes slice.</p>
     *
     * @param offset absolute offset of the index in the archive
     * @param length its length in bytes, at most {@link #MAX_SLICE_LENGTH}
     * @return a buffer positioned at zero over the index; its byte order is the caller's to set
     * @throws IOException if the region is outside the archive, cannot be mapped, or the file key cannot be read
     */
    synchronized ByteBuffer indexRegion(long offset, int length) throws IOException {
        if (length < 0 || length > MAX_SLICE_LENGTH) {
            throw new IOException("Cannot map an index of " + length + " bytes, the limit is " + MAX_SLICE_LENGTH);
        }
        checkRange(offset, length);
        if (mode == MODE_DEFAULT) {
            if (!positionalReadsFlagged(offset, length)) {
                mode = MODE_FULL;
                return slice(offset, length);
            }
            // Before anything reads through the channel: see fileKey.
            fileKey = PositionalReads.fileKey(file);
            mode = MODE_INDEX;
            Arena whole = arena;
            arena = null;
            view = null;
            segment = null;
            whole.close();
        }
        if (mode == MODE_INDEX) {
            return mapIndex(offset, length);
        }
        return slice(offset, length);
    }

    /**
     * Whether the index at {@code offset} asks for positional reads. The flag word is read first, so that an
     * archive whose flag is clear costs one read; the magic is checked only for a set flag, so that a region
     * that is not an index never switches the mode.
     */
    private boolean positionalReadsFlagged(long offset, int length) throws IOException {
        return length >= IndexFormat.HEADER_SIZE
                && (u16(offset + IndexFormat.H_FLAGS) & IndexFormat.HEADER_FLAG_POSITIONAL_READS) != 0
                && i32(offset + IndexFormat.H_MAGIC) == IndexFormat.MAGIC;
    }

    private ByteBuffer mapIndex(long offset, int length) throws IOException {
        if (indexArena == null) {
            indexArena = Arena.ofShared();
        }
        // FileChannel.map is interruptible too, so it runs with the caller's interrupt status cleared.
        boolean interrupted = Thread.interrupted();
        ByteBuffer index;
        try {
            index = channel.map(FileChannel.MapMode.READ_ONLY, offset, length, indexArena).asByteBuffer();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (poolBufferSize == 0) {
            long largest = length >= IndexFormat.H_LARGEST_STORED_CLASS + 4
                    ? index.order(ByteOrder.LITTLE_ENDIAN).getInt(IndexFormat.H_LARGEST_STORED_CLASS) & 0xFFFFFFFFL
                    : 0;
            poolBufferSize = poolBufferSize(largest);
            index.order(ByteOrder.BIG_ENDIAN);
        }
        return index;
    }

    /**
     * The capacity of the pooled buffers: the archive's largest STORED class rounded up to 4 KiB, capped at
     * {@link #POOL_MAX_BUFFER_SIZE}, which is also the size when the archive recorded none.
     *
     * @param largestStoredClass {@code IndexFormat.H_LARGEST_STORED_CLASS}, {@code 0} when not recorded
     * @return the buffer capacity in bytes
     */
    static int poolBufferSize(long largestStoredClass) {
        if (largestStoredClass <= 0 || largestStoredClass >= POOL_MAX_BUFFER_SIZE) {
            return POOL_MAX_BUFFER_SIZE;
        }
        return (int) ((largestStoredClass + POOL_BUFFER_ALIGNMENT - 1) & -POOL_BUFFER_ALIGNMENT);
    }

    /**
     * A buffer holding a region of the archive, for a caller that is done with it before it returns and then
     * hands it to {@link #release(ByteBuffer)}. The class loader defines a STORED class from it.
     *
     * <p>In the {@code index} mode the region is read with one positional read into a direct buffer taken
     * from a pool of at most {@link #POOL_MAX_BUFFERS}, which is allocated on first need, so that the class
     * bytes never enter the process's resident set as mapped pages and no heap array is allocated. A region
     * larger than a pooled buffer, and a borrow that finds every pooled buffer in use, is read into a heap array
     * instead. Buffers are borrowed per call, never per thread: the VM resolves a class's supertypes while it
     * is still parsing its bytes, so a nested define on the same thread must not reuse the buffer. The other
     * modes return {@link #slice}.</p>
     *
     * @param offset absolute offset in the archive
     * @param length number of bytes, at most {@link #MAX_SLICE_LENGTH}
     * @return a buffer positioned at zero whose remaining bytes are the region; read-only in the mapped modes
     * @throws IOException if the region is outside the archive, the length is negative or too large, the
     *                     source is closed, or the read fails
     */
    ByteBuffer borrow(long offset, int length) throws IOException {
        if (mode != MODE_INDEX) {
            return slice(offset, length);
        }
        if (length < 0 || length > MAX_SLICE_LENGTH) {
            throw new IOException("Cannot read " + length + " bytes at once, the limit is " + MAX_SLICE_LENGTH);
        }
        checkRange(offset, length);
        ByteBuffer buffer = acquireBuffer(length);
        if (buffer == null) {
            return ByteBuffer.wrap(readFully(offset, length));
        }
        try {
            readInto(offset, buffer);
        } catch (IOException | RuntimeException | Error e) {
            release(buffer);
            throw e;
        }
        buffer.flip();
        return buffer;
    }

    /**
     * Returns a buffer that {@link #borrow(long, int)} handed out. Only a pooled buffer of the {@code index}
     * mode is kept, while fewer than the idle limit are; anything else, including a heap buffer or a slice of
     * a mapping, is ignored. The caller must not touch the buffer afterwards, nor release it twice.
     *
     * @param buffer the borrowed buffer
     */
    void release(ByteBuffer buffer) {
        if (mode != MODE_INDEX || !buffer.isDirect() || buffer.isReadOnly()) {
            return;
        }
        synchronized (this) {
            if (buffer.capacity() != poolBufferSize) {
                return;
            }
            buffer.clear();
            if (!closed && buffers.size() < maxIdle) {
                buffers.addFirst(buffer);
                return;
            }
            // Dropped: the garbage collector returns its native memory.
            liveBuffers--;
        }
    }

    /**
     * An idle pooled buffer, or a new one while fewer than {@link #POOL_MAX_BUFFERS} exist, limited to
     * {@code length}.
     *
     * @return the buffer, or {@code null} when the region does not fit one, all are in use or the source is
     *         closed, in which case the caller reads into a heap array
     */
    private ByteBuffer acquireBuffer(int length) {
        int size;
        synchronized (this) {
            if (closed) {
                return null;
            }
            size = poolBufferSize;
            if (size == 0) {
                // No index has been mapped, so nothing recorded the largest class.
                size = POOL_MAX_BUFFER_SIZE;
                poolBufferSize = size;
            }
            if (length > size) {
                return null;
            }
            ByteBuffer idle = buffers.pollFirst();
            if (idle != null) {
                idle.limit(length);
                return idle;
            }
            if (liveBuffers >= POOL_MAX_BUFFERS) {
                return null;
            }
            liveBuffers++;
            createdBuffers++;
        }
        try {
            ByteBuffer created = ByteBuffer.allocateDirect(size);
            created.limit(length);
            return created;
        } catch (RuntimeException | Error e) {
            synchronized (this) {
                liveBuffers--;
            }
            throw e;
        }
    }

    /**
     * Tells the source that the application's {@code main} has returned, so that it can trim what only
     * startup needed: from here on the {@code index} mode's pool keeps at most {@link #POOL_IDLE_AFTER_STARTUP}
     * idle buffers, and the native memory of the others returns once the garbage collector reclaims them.
     * {@link Launcher} calls it once, after a normal return; an application whose {@code main} never returns
     * keeps the pool.
     */
    void startupFinished() {
        synchronized (this) {
            maxIdle = POOL_IDLE_AFTER_STARTUP;
            while (buffers.size() > maxIdle) {
                buffers.pollLast();
                liveBuffers--;
            }
        }
    }

    /**
     * The file key a reopen after an interrupt compares, for tests.
     *
     * @return the key, or {@code null} in the mapped modes or when the file system reports none
     */
    Object fileKey() {
        return fileKey;
    }

    /**
     * How many pooled buffers the {@code index} mode has allocated since this source opened.
     *
     * @return the count, never more than {@link #POOL_MAX_BUFFERS} while no buffer was dropped
     */
    synchronized int createdBuffers() {
        return createdBuffers;
    }

    /**
     * How many pooled buffers are idle.
     *
     * @return the count
     */
    synchronized int idleBuffers() {
        return buffers.size();
    }

    /**
     * The capacity of the pooled buffers.
     *
     * @return the size in bytes, or {@code 0} before the first index or borrow of the {@code index} mode
     */
    synchronized int poolBufferSize() {
        return poolBufferSize;
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
            return s.get(SegmentLayouts.BYTE, offset) & 0xFF;
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
            return s.get(SegmentLayouts.SHORT_LE, offset) & 0xFFFF;
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
            return s.get(SegmentLayouts.INT_LE, offset);
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
            return s.get(SegmentLayouts.LONG_LE, offset);
        }
        return readScalar(offset, 8);
    }

    /**
     * A buffer over a region of the archive. In the mapped modes this copies nothing: the buffer is a
     * read-only window onto the mapping, which is what lets the class loader define a STORED class without
     * ever materialising a {@code byte[]}. In the {@code index} and {@code false} modes the region is read into
     * a new heap array, and the buffer wraps that array, writable and with {@link ByteBuffer#hasArray() an
     * accessible array}, so that {@link ClassLoader} defines a class straight from it instead of copying it
     * again. Every call returns a fresh array that the caller owns, so writing to it changes no shared state.
     * The class loader reads classes through {@link #borrow(long, int)} instead, which pools buffers in the
     * {@code index} mode.
     *
     * <p>The returned buffer's byte order is the {@link ByteBuffer} default, big-endian; callers reading
     * little-endian structures out of it must set the order themselves.</p>
     *
     * @param offset absolute offset in the archive
     * @param length number of bytes, at most {@link #MAX_SLICE_LENGTH}
     * @return a buffer positioned at zero with the region as its content: read-only in the mapped mode, and a
     *         new array owned by the caller in the fallback mode
     * @throws IOException if the region is outside the archive, the length is negative or too large, or
     *                     the read fails
     */
    public ByteBuffer slice(long offset, int length) throws IOException {
        if (length < 0 || length > MAX_SLICE_LENGTH) {
            throw new IOException("Cannot slice " + length + " bytes, the limit is " + MAX_SLICE_LENGTH);
        }
        checkRange(offset, length);
        ByteBuffer b = view;
        if (b != null) {
            return b.slice((int) offset, length);
        }
        MemorySegment s = segment;
        if (s != null) {
            return s.asSlice(offset, length).asByteBuffer();
        }
        return ByteBuffer.wrap(readFully(offset, length));
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
        return entryStream(dataOffset, compressedSize, uncompressedSize, method, null);
    }

    /**
     * Streams an entry like {@link #stream(long, long, long, int)} and checks the CRC-32 of the bytes read or
     * skipped when the read reaching the recorded end returns; closing early checks nothing. An integrity
     * failure names the entry by {@code description}, and every later read and skip rethrows it.
     */
    InputStream stream(long dataOffset, long compressedSize, long uncompressedSize, int method,
                       long expectedCrc, String description) throws IOException {
        return entryStream(dataOffset, compressedSize, uncompressedSize, method,
                new Verification(expectedCrc, description));
    }

    private InputStream entryStream(long dataOffset, long compressedSize, long uncompressedSize, int method,
                                    Verification verification) throws IOException {
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
            return new EntryInputStream(raw, raw, uncompressedSize, this, null, verification);
        }
        if (method != IndexFormat.METHOD_DEFLATED) {
            throw new IOException("Unsupported compression method " + method + " at offset " + dataOffset);
        }
        Inflater inflater = acquireInflater();
        RegionInputStream raw = new RegionInputStream(this, dataOffset, compressedSize);
        return new EntryInputStream(new InflaterInputStream(raw, inflater, STREAM_BUFFER_SIZE), raw,
                uncompressedSize, this, inflater, verification);
    }

    /**
     * Decompresses a DEFLATE entry into an array of exactly {@code uncompressedSize} bytes.
     *
     * <p>This is the class loading path for a compressed archive, so it allocates one array, borrows an
     * inflater from the pool and, in the mapped modes, feeds the inflater a direct buffer over the mapping
     * rather than copying the compressed bytes first. In the {@code index} mode the compressed bytes are read
     * into a buffer {@link #borrow(long, int) borrowed} from the pool, or into a heap array when they are
     * larger than a pooled buffer.</p>
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
        ByteBuffer borrowed = null;
        try {
            ByteBuffer b = view;
            MemorySegment s = segment;
            if (b != null) {
                inflater.setInput(b.slice((int) dataOffset, compressedSize));
            } else if (s != null) {
                inflater.setInput(s.asSlice(dataOffset, compressedSize).asByteBuffer());
            } else if (mode == MODE_INDEX) {
                borrowed = borrow(dataOffset, compressedSize);
                inflater.setInput(borrowed);
            } else {
                inflater.setInput(readFully(dataOffset, compressedSize));
            }
            int done = 0;
            byte[] probe = null;
            while (!inflater.finished()) {
                int n;
                if (done < uncompressedSize) {
                    n = inflater.inflate(result, done, uncompressedSize - done);
                } else {
                    // Only a stream that has not finished on the exactly sized output gets here, which is rare.
                    if (probe == null) {
                        probe = new byte[1];
                    }
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
            // The inflater first: resetting it drops its reference to the borrowed input.
            releaseInflater(inflater);
            if (borrowed != null) {
                release(borrowed);
            }
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
     * Releases the mappings, the buffer pool and the file handle. The launcher never calls this; tests, tools
     * and benchmarks must, because an open mapping keeps the file alive on Windows.
     *
     * @throws UncheckedIOException if the channel cannot be closed
     */
    @Override
    public void close() {
        Inflater[] pooled;
        FileChannel openChannel;
        RandomAccessFile openHandle;
        Arena wholeFile;
        Arena index;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            pooled = inflaters.toArray(new Inflater[0]);
            inflaters.clear();
            liveBuffers -= buffers.size();
            buffers.clear();
            // A reopen publishes under this lock and checks closed first, so these are the last pair.
            openChannel = channel;
            openHandle = handle;
            // Null once the archive's flag switched the source to the index mode, which closed it already.
            wholeFile = arena;
            index = indexArena;
        }
        for (int i = 0; i < pooled.length; i++) {
            pooled[i].end();
        }
        if (wholeFile != null) {
            wholeFile.close();
        }
        if (index != null) {
            index.close();
        }
        IOException failure = null;
        try {
            openChannel.close();
        } catch (IOException e) {
            failure = e;
        }
        try {
            openHandle.close();
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
        ByteBuffer b = view;
        if (b != null) {
            // An absolute bulk get leaves the shared view's position alone. The cast is safe: a view exists
            // only for an archive of at most MAX_SLICE_LENGTH bytes, and every caller has checked the range.
            b.get((int) offset, destination, destinationOffset, count);
            return;
        }
        MemorySegment s = segment;
        if (s != null) {
            MemorySegment.copy(s, SegmentLayouts.BYTE, offset, destination, destinationOffset, count);
            return;
        }
        readInto(offset, ByteBuffer.wrap(destination, destinationOffset, count));
    }

    /**
     * Fills a buffer with positional channel reads, surviving thread interrupts.
     *
     * <p>Every read that does not go through the mapping ends here. It reads {@code destination.remaining()}
     * bytes starting at {@code position} and leaves the buffer's position at its limit. A heap buffer is
     * filled at most 64 KiB per call, and a direct buffer in one call. The caller's interrupt status is
     * cleared for the read and restored afterwards, and a channel that an interrupt closed, in this thread
     * or in any other, is reopened a bounded number of times; see {@link PositionalReads}. The method holds no
     * mode-specific logic. The file key that a reopen compares is captured before the first positional read,
     * by {@link #open(File)} or by the switch in {@link #indexRegion(long, int)}.</p>
     *
     * @param position    absolute offset in the archive; the caller has already checked the region
     * @param destination the buffer to fill between its position and its limit
     * @throws IOException if the archive ends early, the source is closed, the path no longer names the
     *                     file this source opened when a reopen is needed, or interrupts keep closing the
     *                     channel
     */
    void readInto(long position, ByteBuffer destination) throws IOException {
        PositionalReads.read(this, position, destination);
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
     * The layouts of the segment reads, which run only for a mapped archive without a {@link #view}, one
     * larger than {@link #MAX_SLICE_LENGTH}. Holding them here keeps the {@link ValueLayout} classes that
     * their initialisation loads, from {@code jrt:/java.base} rather than the CDS archive, off every other
     * launch.
     */
    private static final class SegmentLayouts {

        static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

        /** Little-endian and alignment-free, like every ZIP and index field. */
        static final ValueLayout.OfShort SHORT_LE =
                ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

        static final ValueLayout.OfInt INT_LE =
                ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

        static final ValueLayout.OfLong LONG_LE =
                ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

        private SegmentLayouts() {
        }
    }

    /**
     * The positional read loop and the reopen logic behind {@link #readInto(long, ByteBuffer)}.
     *
     * <p>A {@link FileChannel} is an interruptible channel. A read by a thread whose interrupt status is set,
     * or that is interrupted during the read, closes the channel for every thread, and closing a channel
     * obtained from a {@link RandomAccessFile} closes that file too. Without this class one cancelled task
     * would stop all class loading from the archive for the rest of the process. So each attempt clears the
     * caller's interrupt status, a read that finds the channel closed reopens the file and continues from the
     * bytes it already has, and the caller's interrupt status is restored at the end: the caller sees it as if
     * the read had not touched it. A closed channel is not a sign of a damaged archive, only of an interrupt
     * or of {@link ArchiveSource#close()}, and nothing reopens after the latter.</p>
     *
     * <p>A reopen goes by path, and a deployment may have renamed another file onto that path since this JVM
     * opened it. The reopened file is published only if its length, and the file key when the file system
     * has one, still match what {@link ArchiveSource#open(File)} or the switch to the {@code index} mode
     * captured. Otherwise the failure is stored and every later read fails with it. Opening first and comparing
     * the key afterwards can only raise a false alarm, never switch to a file that was renamed in.</p>
     *
     * <p>This is a separate class, reached only through static calls from the positional branches, so that
     * verifying {@link ArchiveSource} does not load the exception types caught and thrown here. In the mapped
     * modes the class is never loaded; in the {@code index} mode it is the one launcher class that loads
     * before {@code main} on top of the mapped modes' set.</p>
     */
    static final class PositionalReads {

        /**
         * Largest read into a heap buffer. The JDK reads a heap buffer through a per-thread temporary direct
         * buffer as large as the request and caches it with no size limit by default, so one large read would
         * leave that much native memory behind in the thread.
         */
        static final int HEAP_READ_CHUNK = 64 * 1024;

        /**
         * How many times one read may reopen the channel before giving up. Most closures seen by a read are
         * another thread's interrupt closing the channel just before or during this read's attempt, and a
         * retry can race the next one; under heavy stress one read needed five reopens.
         */
        static final int MAX_REOPENS = 16;

        private PositionalReads() {
        }

        /**
         * Fills {@code destination} from {@code position}; see {@link ArchiveSource#readInto(long, ByteBuffer)}.
         *
         * @param source      the archive
         * @param position    absolute offset of the first byte to read
         * @param destination the buffer to fill between its position and its limit
         * @throws IOException if the archive ends early, the source is closed, the file was replaced, or
         *                     interrupts keep closing the channel
         */
        static void read(ArchiveSource source, long position, ByteBuffer destination) throws IOException {
            int start = destination.position();
            int limit = destination.limit();
            boolean chunked = !destination.isDirect();
            boolean interrupted = false;
            int reopens = 0;
            try {
                while (true) {
                    if (Thread.interrupted()) {
                        interrupted = true;
                    }
                    FileChannel channel = source.channel;
                    try {
                        int at = destination.position();
                        while (at < limit) {
                            if (chunked) {
                                destination.limit(limit - at > HEAP_READ_CHUNK ? at + HEAP_READ_CHUNK : limit);
                            }
                            long offset = position + (at - start);
                            if (channel.read(destination, offset) < 0) {
                                throw new EOFException("Unexpected end of " + source.file + " at offset "
                                        + offset);
                            }
                            at = destination.position();
                        }
                        return;
                    } catch (ClosedChannelException e) {
                        // Also ClosedByInterruptException and AsynchronousCloseException. Bytes the failed
                        // call transferred have already advanced the buffer, so the retry starts after them.
                        if (reopens == MAX_REOPENS) {
                            throw new IOException("Thread interrupts closed " + source.file + " "
                                    + (MAX_REOPENS + 1) + " times during one read", e);
                        }
                        reopens++;
                        reopen(source, channel, e);
                    } finally {
                        destination.limit(limit);
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * The identity of a file as the file system reports it: device and inode on POSIX, {@code null} on
         * Windows, where the JDK opens files without delete sharing so that an open file cannot be replaced.
         *
         * @param file the file
         * @return the key, or {@code null} when the file system has none
         * @throws IOException if the attributes cannot be read
         */
        static Object fileKey(File file) throws IOException {
            return Files.readAttributes(file.toPath(), BasicFileAttributes.class).fileKey();
        }

        /**
         * Replaces a channel that was found closed, unless another thread already did or the source is closed.
         *
         * @param source the archive
         * @param stale  the channel the failed attempt read from
         * @param cause  why that attempt failed
         * @throws IOException {@code cause} when the source is closed, or the stored or new failure when the
         *                     path no longer names the file this source opened
         */
        private static void reopen(ArchiveSource source, FileChannel stale, ClosedChannelException cause)
                throws IOException {
            synchronized (source) {
                if (source.closed) {
                    throw cause;
                }
                if (source.channel != stale) {
                    return;
                }
                IOException replaced = source.replaced;
                if (replaced != null) {
                    throw replaced;
                }
                RandomAccessFile handle = null;
                boolean same;
                try {
                    handle = new RandomAccessFile(source.file, "r");
                    same = handle.length() == source.length
                            && (source.fileKey == null || source.fileKey.equals(fileKey(source.file)));
                } catch (IOException e) {
                    // Not stored: the path may name the original file again by the next read.
                    IOException failure = new IOException("Cannot reopen the runner jar " + source.file
                            + " after an interrupted read closed it", e);
                    failure.addSuppressed(cause);
                    if (handle != null) {
                        closeQuietly(handle, failure);
                    }
                    throw failure;
                } catch (RuntimeException | Error e) {
                    if (handle != null) {
                        closeQuietly(handle, e);
                    }
                    throw e;
                }
                if (!same) {
                    replaced = new IOException("The runner jar " + source.file + " was replaced after this JVM"
                            + " opened it, and an interrupted read closed the original file; restart the JVM");
                    replaced.addSuppressed(cause);
                    closeQuietly(handle, replaced);
                    source.replaced = replaced;
                    throw replaced;
                }
                source.handle = handle;
                source.channel = handle.getChannel();
            }
        }

        private static void closeQuietly(RandomAccessFile handle, Throwable failure) {
            try {
                handle.close();
            } catch (IOException e) {
                failure.addSuppressed(e);
            }
        }
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
     * Enforces that exactly the recorded number of bytes reaches the caller, verifies them when the stream
     * has a {@link Verification}, and returns the borrowed inflater to the pool on close.
     */
    private static final class EntryInputStream extends InputStream {

        private final InputStream delegate;
        private final RegionInputStream raw;
        private final ArchiveSource source;
        private final Inflater inflater;
        /** {@code null} unless the stream verifies; declared as its own type so that it loads only then. */
        private final Verification verification;
        private long remaining;
        private boolean checkedTrailing;
        private boolean closed;

        private EntryInputStream(InputStream delegate, RegionInputStream raw, long size,
                                 ArchiveSource source, Inflater inflater, Verification verification) {
            this.delegate = delegate;
            this.raw = raw;
            this.source = source;
            this.inflater = inflater;
            this.verification = verification;
            this.remaining = size;
        }

        @Override
        public int read() throws IOException {
            Verification v = verification;
            if (v != null) {
                v.rethrow();
            }
            if (remaining <= 0) {
                end(v);
                return -1;
            }
            int value = delegate.read();
            if (value < 0) {
                throw truncated();
            }
            remaining--;
            if (v != null) {
                v.update(value);
                if (remaining == 0) {
                    end(v);
                }
            }
            return value;
        }

        @Override
        public int read(byte[] destination, int offset, int count) throws IOException {
            Verification v = verification;
            if (v != null) {
                v.rethrow();
            }
            Objects.checkFromIndexSize(offset, count, destination.length);
            if (count == 0) {
                return 0;
            }
            if (remaining <= 0) {
                end(v);
                return -1;
            }
            int chunk = (int) Math.min(count, remaining);
            int read = delegate.read(destination, offset, chunk);
            if (read < 0) {
                throw truncated();
            }
            remaining -= read;
            if (v != null) {
                v.update(destination, offset, read);
                if (remaining == 0) {
                    end(v);
                }
            }
            return read;
        }

        @Override
        public long skip(long count) throws IOException {
            long wanted = Math.min(Math.max(count, 0L), remaining);
            Verification v = verification;
            if (v == null) {
                long skipped = delegate.skip(wanted);
                remaining -= skipped;
                return skipped;
            }
            v.rethrow();
            if (wanted == 0 && count > 0) {
                end(v);
            }
            long skipped = 0;
            while (skipped < wanted) {
                if (v.skipBuffer == null) {
                    v.skipBuffer = new byte[(int) Math.min(remaining, STREAM_BUFFER_SIZE)];
                }
                // Read, to checksum what is skipped. Never -1 or 0: the entry has the bytes, or read throws.
                skipped += read(v.skipBuffer, 0, (int) Math.min(v.skipBuffer.length, wanted - skipped));
            }
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

        /** Runs when the recorded end is reached: the trailing data check, and the CRC-32 when verifying. */
        private void end(Verification v) throws IOException {
            checkTrailing();
            if (v != null) {
                v.verify();
            }
        }

        private void checkTrailing() throws IOException {
            if (checkedTrailing) {
                return;
            }
            checkedTrailing = true;
            if (delegate.read() >= 0) {
                throw fail("The entry holds more data than the index records");
            }
            if (inflater != null) {
                if (!inflater.finished()) {
                    throw fail("The deflate stream ended before its terminal block");
                }
                long unused = inflater.getRemaining() + raw.remaining();
                if (unused != 0) {
                    throw fail("The deflate stream ended with " + unused + " unused compressed bytes");
                }
            }
        }

        private IOException truncated() {
            return fail("The entry ended " + remaining + " bytes before the size the index records");
        }

        /** An integrity failure, which a verifying stream rethrows; an I/O failure of a read is not one. */
        private IOException fail(String message) {
            IOException failure = new IOException(message);
            if (verification != null) {
                verification.failure = failure;
            }
            return failure;
        }
    }

    /**
     * The state of a verifying {@link EntryInputStream}. Never held as a supertype, so it only loads to verify.
     */
    private static final class Verification {

        private final CRC32 checksum = new CRC32();
        private final long expected;
        private final String description;
        private boolean verified;
        private IOException failure;
        private byte[] skipBuffer;

        private Verification(long expected, String description) {
            this.expected = expected;
            this.description = description;
        }

        private void rethrow() throws IOException {
            if (failure != null) {
                throw failure;
            }
        }

        private void update(int value) {
            checksum.update(value);
        }

        private void update(byte[] bytes, int offset, int count) {
            checksum.update(bytes, offset, count);
        }

        private void verify() throws IOException {
            if (!verified) {
                long actual = checksum.getValue();
                if (actual != expected) {
                    failure = new IOException(description + " has checksum " + actual
                            + " but the index records " + expected + "; " + Index.REBUILD_MESSAGE);
                    throw failure;
                }
                verified = true;
            }
        }
    }
}
