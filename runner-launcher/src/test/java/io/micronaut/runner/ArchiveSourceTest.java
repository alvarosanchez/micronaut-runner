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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the archive reader in both the mapped and the fallback mode, over archives written by
 * {@link TestArchiveBuilder}: plain, with a ZIP comment, and in ZIP64 form.
 */
class ArchiveSourceTest {

    private static final byte[] HELLO = "hello runner".getBytes(StandardCharsets.UTF_8);

    /** Size of the random file the interrupt stress reads. */
    private static final int STRESS_FILE_LENGTH = 4 * 1024 * 1024;
    private static final int STRESS_THREADS = 4;
    private static final int STRESS_READS_PER_THREAD = 5_000;
    /** Longest stress read: larger than the 64 KiB chunk that a heap read is split into. */
    private static final int STRESS_MAX_READ = 96 * 1024;
    private static final long INTERRUPT_SPIN_NANOS = 20_000;
    /** How long the replaced-file stress waits for an interrupt to close the channel. */
    private static final long REPLACED_STRESS_SECONDS = 10;
    /** Upper bound for a complete stress run, which takes well under a second on a laptop. */
    private static final long STRESS_SECONDS = 120;

    @TempDir
    Path temporary;

    private final List<ArchiveSource> sources = new ArrayList<>();
    private int files;

    @AfterEach
    void closeSources() {
        for (ArchiveSource source : sources) {
            source.close();
        }
        sources.clear();
        System.clearProperty(ArchiveSource.MMAP_PROPERTY);
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void readsPrimitivesLittleEndian(boolean mapped) throws IOException {
        byte[] content = new byte[] {
            0x01, (byte) 0xFF, 0x02, 0x03, (byte) 0x80, 0x11, 0x22, 0x33,
            0x44, 0x55, 0x66, 0x77, (byte) 0x88, (byte) 0x99, (byte) 0xAA, (byte) 0xBB
        };
        ArchiveSource source = open(content, mapped);

        assertEquals(mapped, source.mapped());
        assertEquals(content.length, source.length());
        assertEquals(1, source.u8(0));
        assertEquals(255, source.u8(1));
        assertEquals(0xFF01, source.u16(0));
        assertEquals(0x0302FF01, source.i32(0));
        assertEquals(0x0302FF01L, source.u32(0));
        assertEquals(0x80, source.u8(4));
        assertEquals(0x3322_1180L, source.u32(4));
        assertEquals(0x33221180_0302FF01L, source.u64(0));
        // An unaligned read must work: the offset is deliberately odd.
        assertEquals(0x800302FF, source.i32(1));
        assertEquals(0x800302FFL, source.u32(1));
        assertEquals(0x66554433, source.i32(7));
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void refusesReadsOutsideTheArchive(boolean mapped) throws IOException {
        ArchiveSource source = open(HELLO, mapped);
        assertThrows(IOException.class, () -> source.u8(-1));
        assertThrows(IOException.class, () -> source.u8(HELLO.length));
        assertThrows(IOException.class, () -> source.u32(HELLO.length - 3));
        assertThrows(IOException.class, () -> source.u64(HELLO.length - 7));
        assertThrows(IOException.class, () -> source.slice(4, HELLO.length));
        assertThrows(IOException.class, () -> source.slice(0, -1));
        assertThrows(IOException.class, () -> source.readFully(0, HELLO.length + 1));
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void slicesAndCopiesContent(boolean mapped) throws IOException {
        ArchiveSource source = open(HELLO, mapped);

        ByteBuffer slice = source.slice(6, 6);
        if (mapped) {
            assertTrue(slice.isReadOnly(), "a mapped slice is a read-only window onto the mapping");
        } else {
            // A class defines straight from an accessible array; a read-only heap buffer is copied again.
            assertFalse(slice.isReadOnly());
            assertTrue(slice.hasArray());
            ByteBuffer again = source.slice(6, 6);
            assertNotSame(slice.array(), again.array(), "every fallback slice owns a fresh array");
        }
        assertEquals(6, slice.remaining());
        byte[] read = new byte[slice.remaining()];
        slice.get(read);
        assertEquals("runner", new String(read, StandardCharsets.UTF_8));

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), source.readFully(0, 5));
        assertEquals(0, source.readFully(0, 0).length);
        assertEquals(0, source.slice(3, 0).remaining());
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void streamsStoredEntries(boolean mapped) throws IOException {
        byte[] large = payload(200_000);
        TestArchiveBuilder archive = new TestArchiveBuilder();
        long at = archive.stored("large.bin", large);
        ArchiveSource source = open(archive.build(), mapped);

        try (InputStream in = source.stream(at, large.length, large.length, IndexFormat.METHOD_STORED)) {
            assertEquals(large.length, in.available());
            assertArrayEquals(large, in.readAllBytes());
            assertEquals(-1, in.read());
        }
        try (InputStream in = source.stream(at, 5, 5, IndexFormat.METHOD_STORED)) {
            assertEquals("hello".length(), in.read(new byte[5]));
            assertEquals(-1, in.read());
        }
        assertThrows(IOException.class,
                () -> source.stream(at, 10, 20, IndexFormat.METHOD_STORED));
        assertThrows(IOException.class,
                () -> source.stream(at, source.length(), source.length(), IndexFormat.METHOD_STORED));
        assertThrows(IOException.class, () -> source.stream(at, 1, 1, 12));
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void streamsAndInflatesDeflatedEntries(boolean mapped) throws IOException {
        byte[] large = payload(200_000);
        TestArchiveBuilder archive = new TestArchiveBuilder();
        long at = archive.deflated("large.bin", large);
        int compressed = archive.storedSize("large.bin");
        ArchiveSource source = open(archive.build(), mapped);

        try (InputStream in = source.stream(at, compressed, large.length, IndexFormat.METHOD_DEFLATED)) {
            assertArrayEquals(large, in.readAllBytes());
        }
        try (InputStream in = source.stream(at, compressed, large.length, IndexFormat.METHOD_DEFLATED)) {
            assertEquals(large[0], (byte) in.read());
            assertEquals(large.length - 1, in.available());
        }
        assertArrayEquals(large, source.inflate(at, compressed, large.length));
        // The inflater pool must hand back usable inflaters, over and over.
        for (int i = 0; i < 64; i++) {
            assertEquals(large.length, source.inflate(at, compressed, large.length).length);
        }
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void rejectsTruncatedAndOverlongDeflateStreams(boolean mapped) throws IOException {
        byte[] large = payload(50_000);
        TestArchiveBuilder archive = new TestArchiveBuilder();
        long at = archive.deflated("large.bin", large);
        int compressed = archive.storedSize("large.bin");
        ArchiveSource source = open(archive.build(), mapped);

        assertThrows(IOException.class, () -> source.stream(at, compressed / 2, large.length,
                IndexFormat.METHOD_DEFLATED).readAllBytes());
        assertThrows(IOException.class, () -> source.stream(at, compressed, large.length + 1,
                IndexFormat.METHOD_DEFLATED).readAllBytes());
        assertThrows(IOException.class, () -> source.stream(at, compressed, 32,
                IndexFormat.METHOD_DEFLATED).readAllBytes());
        assertThrows(IOException.class, () -> source.inflate(at, compressed / 2, large.length));
        assertThrows(IOException.class, () -> source.inflate(at, compressed, large.length + 1));
        assertThrows(IOException.class, () -> source.inflate(at, compressed, 32));
        assertThrows(IOException.class, () -> source.inflate(at, compressed, -1));
        // A pool that has seen failures must still work.
        assertArrayEquals(large, source.inflate(at, compressed, large.length));
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void rejectsUnterminatedDeflateAfterExpectedPlaintext(boolean mapped) throws IOException {
        byte[] unterminated = new byte[] {0, 1, 0, (byte) 0xFE, (byte) 0xFF, 'x'};
        ArchiveSource source = open(unterminated, mapped);

        assertThrows(IOException.class, () -> source.inflate(0, unterminated.length, 1));
        try (InputStream in = source.stream(0, unterminated.length, 1, IndexFormat.METHOD_DEFLATED)) {
            assertThrows(IOException.class, in::readAllBytes);
        }
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void acceptsTerminalDeflateFramingAfterExpectedPlaintext(boolean mapped) throws IOException {
        byte[] complete = new byte[] {
            0, 1, 0, (byte) 0xFE, (byte) 0xFF, 'x',
            1, 0, 0, (byte) 0xFF, (byte) 0xFF
        };
        ArchiveSource source = open(complete, mapped);

        assertArrayEquals(new byte[] {'x'}, source.inflate(0, complete.length, 1));
        try (InputStream in = source.stream(0, complete.length, 1, IndexFormat.METHOD_DEFLATED)) {
            assertArrayEquals(new byte[] {'x'}, in.readAllBytes());
        }
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void rejectsUnusedBytesAfterACompleteDeflateStream(boolean mapped) throws IOException {
        byte[] completeWithTrailingByte = new byte[] {
            1, 1, 0, (byte) 0xFE, (byte) 0xFF, 'x', 0
        };
        ArchiveSource source = open(completeWithTrailingByte, mapped);

        IOException failure = assertThrows(IOException.class,
                () -> source.inflate(0, completeWithTrailingByte.length, 1));
        assertTrue(failure.getMessage().contains("unused compressed bytes"), failure.getMessage());
        try (InputStream input = source.stream(0, completeWithTrailingByte.length, 1,
                IndexFormat.METHOD_DEFLATED)) {
            IOException streamFailure = assertThrows(IOException.class, input::readAllBytes);
            assertTrue(streamFailure.getMessage().contains("unused compressed bytes"),
                    streamFailure.getMessage());
        }
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void validatesEmptyDeflateStreamsAndReusesInflatersAfterFailures(boolean mapped) throws IOException {
        byte[] completeEmpty = new byte[] {1, 0, 0, (byte) 0xFF, (byte) 0xFF};
        ArchiveSource source = open(completeEmpty, mapped);

        for (int i = 0; i < 16; i++) {
            assertThrows(IOException.class, () -> source.inflate(0, 0, 0));
        }
        assertArrayEquals(new byte[0], source.inflate(0, completeEmpty.length, 0));

        try (InputStream in = source.stream(0, 0, 0, IndexFormat.METHOD_DEFLATED)) {
            assertThrows(IOException.class, in::readAllBytes);
        }
        assertArrayEquals(new byte[0], source.inflate(0, completeEmpty.length, 0));
        try (InputStream in = source.stream(0, completeEmpty.length, 0, IndexFormat.METHOD_DEFLATED)) {
            assertArrayEquals(new byte[0], in.readAllBytes());
        }
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void streamsCorruptDataAsAnIoException(boolean mapped) throws IOException {
        TestArchiveBuilder archive = new TestArchiveBuilder();
        long at = archive.raw("broken.bin", new byte[] {1, 2, 3, 4, 5, 6, 7, 8},
                IndexFormat.METHOD_DEFLATED, 64);
        ArchiveSource source = open(archive.build(), mapped);
        assertThrows(IOException.class,
                () -> source.stream(at, 8, 64, IndexFormat.METHOD_DEFLATED).readAllBytes());
        assertThrows(IOException.class, () -> source.inflate(at, 8, 64));
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void findsTheIndexEntry(boolean mapped) throws IOException {
        byte[] index = smallIndex();
        TestArchiveBuilder archive = new TestArchiveBuilder();
        archive.stored("META-INF/MANIFEST.MF", HELLO);
        long at = archive.stored(IndexFormat.INDEX_ENTRY_NAME, index);
        archive.stored("MICRONAUT-INF/classes/a/B.class", HELLO);
        ArchiveSource source = open(archive.build(), mapped);

        long[] location = source.openIndex();
        assertEquals(at, location[0]);
        assertEquals(index.length, location[1]);
        assertEquals(IndexFormat.MAGIC, source.i32(location[0]));
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void findsTheIndexEntryBehindAZipComment(boolean mapped) throws IOException {
        byte[] index = smallIndex();
        StringBuilder comment = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            comment.append("comment ").append(i).append(' ');
        }
        TestArchiveBuilder archive = new TestArchiveBuilder().comment(comment.toString());
        archive.stored("META-INF/MANIFEST.MF", HELLO);
        long at = archive.stored(IndexFormat.INDEX_ENTRY_NAME, index);
        byte[] content = archive.build();
        ArchiveSource source = open(content, mapped);

        long[] location = source.openIndex();
        assertEquals(at, location[0]);
        assertEquals(index.length, location[1]);

        File file = write(content);
        try (ZipFile zip = new ZipFile(file)) {
            assertEquals(comment.toString(), zip.getComment());
            ZipEntry entry = zip.getEntry(IndexFormat.INDEX_ENTRY_NAME);
            assertEquals(index.length, entry.getSize());
        }
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void findsTheIndexEntryInAZip64Archive(boolean mapped) throws IOException {
        byte[] index = smallIndex();
        TestArchiveBuilder archive = new TestArchiveBuilder().zip64(true);
        archive.stored("META-INF/MANIFEST.MF", HELLO);
        long at = archive.stored(IndexFormat.INDEX_ENTRY_NAME, index);
        archive.stored("MICRONAUT-INF/classes/a/B.class", HELLO);
        byte[] content = archive.build();
        ArchiveSource source = open(content, mapped);

        long[] location = source.openIndex();
        assertEquals(at, location[0]);
        assertEquals(index.length, location[1]);

        // The archive must also be a valid ZIP64 file for every other tool.
        try (ZipFile zip = new ZipFile(write(content))) {
            assertEquals(index.length, zip.getEntry(IndexFormat.INDEX_ENTRY_NAME).getSize());
            assertEquals(HELLO.length, zip.getEntry("MICRONAUT-INF/classes/a/B.class").getSize());
        }
    }

    @Test
    void refusesArchivesThatAreNotRunnerJars() throws IOException {
        TestArchiveBuilder archive = new TestArchiveBuilder();
        archive.stored("META-INF/MANIFEST.MF", HELLO);
        ArchiveSource withoutIndex = open(archive.build(), true);
        IOException missing = assertThrows(IOException.class, withoutIndex::openIndex);
        assertTrue(missing.getMessage().contains(IndexFormat.INDEX_ENTRY_NAME), missing.getMessage());

        byte[] notAZip = new byte[512];
        Arrays.fill(notAZip, (byte) 'x');
        ArchiveSource garbage = open(notAZip, true);
        assertThrows(IOException.class, garbage::openIndex);

        ArchiveSource tooShort = open(new byte[4], true);
        assertThrows(IOException.class, tooShort::openIndex);
        ArchiveSource nothing = open(new byte[0], true);
        assertEquals(0, nothing.length());
        assertThrows(IOException.class, nothing::openIndex);
        assertThrows(IOException.class, () -> ArchiveSource.open(new File(temporary.toFile(), "missing")));
        assertThrows(IOException.class, () -> ArchiveSource.open(null));
    }

    @Test
    void refusesAnIndexEntryThatIsNotStored() throws IOException {
        TestArchiveBuilder archive = new TestArchiveBuilder();
        archive.stored("META-INF/MANIFEST.MF", HELLO);
        archive.deflated(IndexFormat.INDEX_ENTRY_NAME, payload(4096));
        ArchiveSource source = open(archive.build(), true);
        assertThrows(IOException.class, source::openIndex);
    }

    @Test
    void closesTwiceAndReleasesTheFile() throws IOException {
        File file = write(HELLO);
        System.setProperty(ArchiveSource.MMAP_PROPERTY, "true");
        ArchiveSource source = ArchiveSource.open(file);
        assertTrue(source.mapped());
        assertEquals(file, source.file());
        source.close();
        source.close();
        assertTrue(file.delete(), "an archive must be deletable once the source is closed");
    }

    @Test
    void fallbackModeIsSelectedOnlyByTheExactValue() throws IOException {
        File file = write(HELLO);
        System.setProperty(ArchiveSource.MMAP_PROPERTY, "FALSE");
        assertTrue(track(ArchiveSource.open(file)).mapped());
        System.setProperty(ArchiveSource.MMAP_PROPERTY, "false");
        assertFalse(track(ArchiveSource.open(file)).mapped());
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void readsWithTheInterruptFlagSet(boolean mapped) throws IOException {
        // A FileChannel read by an interrupted thread closes the channel for every thread, so before the
        // positional reads handled interrupts, one cancelled task stopped all class loading in the process.
        byte[] stored = payload(200_000);
        byte[] deflated = random(150_000, 149);
        TestArchiveBuilder archive = new TestArchiveBuilder();
        long storedAt = archive.stored("stored.bin", stored);
        long deflatedAt = archive.deflated("deflated.bin", deflated);
        int compressed = archive.storedSize("deflated.bin");
        ArchiveSource source = open(archive.build(), mapped);
        long expectedU32 = ByteBuffer.wrap(stored).order(ByteOrder.LITTLE_ENDIAN).getInt(9) & 0xFFFFFFFFL;

        try {
            Thread.currentThread().interrupt();
            assertArrayEquals(Arrays.copyOfRange(stored, 1_000, 101_000),
                    source.readFully(storedAt + 1_000, 100_000));
            assertTrue(Thread.interrupted(), "readFully must leave the interrupt status set");

            Thread.currentThread().interrupt();
            assertEquals(expectedU32, source.u32(storedAt + 9));
            assertTrue(Thread.interrupted(), "u32 must leave the interrupt status set");

            Thread.currentThread().interrupt();
            ByteBuffer slice = source.slice(storedAt, stored.length);
            assertTrue(Thread.interrupted(), "slice must leave the interrupt status set");
            byte[] sliced = new byte[slice.remaining()];
            slice.get(sliced);
            assertArrayEquals(stored, sliced);

            Thread.currentThread().interrupt();
            try (InputStream in = source.stream(storedAt, stored.length, stored.length,
                    IndexFormat.METHOD_STORED)) {
                assertArrayEquals(stored, in.readAllBytes());
            }
            assertTrue(Thread.interrupted(), "a STORED stream must leave the interrupt status set");

            Thread.currentThread().interrupt();
            try (InputStream in = source.stream(deflatedAt, compressed, deflated.length,
                    IndexFormat.METHOD_DEFLATED)) {
                assertArrayEquals(deflated, in.readAllBytes());
            }
            assertTrue(Thread.interrupted(), "a DEFLATED stream must leave the interrupt status set");

            Thread.currentThread().interrupt();
            assertArrayEquals(deflated, source.inflate(deflatedAt, compressed, deflated.length));
            assertTrue(Thread.interrupted(), "inflate must leave the interrupt status set");
        } finally {
            Thread.interrupted();
        }

        assertArrayEquals(stored, source.readFully(storedAt, stored.length), "an uninterrupted read works too");
        assertFalse(Thread.currentThread().isInterrupted(), "a read must not set the interrupt status");
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void readsSurviveConcurrentInterrupts(boolean mapped) throws Exception {
        byte[] content = random(STRESS_FILE_LENGTH, 149);
        ArchiveSource source = open(content, mapped);

        Stress stress = stress(source, content, STRESS_READS_PER_THREAD, false, STRESS_SECONDS);

        assertEquals(0, stress.failures.get(),
                () -> "reads failed; the first failure was " + stress.firstFailure.get());
        assertEquals(0, stress.mismatches.get(), "reads returned the wrong bytes");
        assertEquals(STRESS_THREADS * STRESS_READS_PER_THREAD, stress.reads.get());
        assertTrue(stress.interrupts.get() > 0, "the interrupter must have run");
        assertArrayEquals(Arrays.copyOfRange(content, 0, 4096), source.readFully(0, 4096),
                "the source still reads after the stress");
    }

    @Test
    void closedPositionalSourceNeverReopens() throws IOException {
        ArchiveSource source = open(HELLO, false);
        assertFalse(source.mapped());
        source.close();

        assertThrows(ClosedChannelException.class, () -> source.readFully(0, HELLO.length));
        Thread.currentThread().interrupt();
        try {
            assertThrows(ClosedChannelException.class, () -> source.readFully(0, HELLO.length));
            assertTrue(Thread.currentThread().isInterrupted(),
                    "a failed read must leave the interrupt status set");
        } finally {
            Thread.interrupted();
        }
        assertThrows(ClosedChannelException.class, () -> source.readFully(0, HELLO.length),
                "a closed source must never reopen the file");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "an open file cannot be replaced by a rename on Windows")
    void refusesToReopenAReplacedFile() throws Exception {
        byte[] original = random(STRESS_FILE_LENGTH, 149);
        File file = write(original);
        System.setProperty(ArchiveSource.MMAP_PROPERTY, "false");
        ArchiveSource source = track(ArchiveSource.open(file));
        assertFalse(source.mapped());

        // A rename-based deployment: the same path and length, but another file.
        File staged = write(random(STRESS_FILE_LENGTH, 150));
        Files.move(staged.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);

        Stress stress = stress(source, original, Integer.MAX_VALUE, true, REPLACED_STRESS_SECONDS);

        assertEquals(0, stress.mismatches.get(), "no read may return bytes of the replacement");
        Throwable first = stress.firstFailure.get();
        assertNotNull(first, "an interrupt must have closed the channel within the time limit");
        assertTrue(String.valueOf(first.getMessage()).contains("was replaced"), first.toString());
        IOException later = assertThrows(IOException.class, () -> source.readFully(0, 16));
        assertEquals(first.getMessage(), later.getMessage(), "the refusal is remembered");
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void findsTheIndexEntryWhenTheEntryCountCarriesTheMarker(boolean mapped) throws IOException {
        // Exactly 65535 entries: the 16-bit count field of the end record has no value for that number
        // which is not also the ZIP64 marker, and a writer that does not notice leaves no ZIP64 records
        // behind it. java.util.zip falls back to the 32-bit fields and reads the archive, so the launcher
        // has to as well; refusing it means a jar every other tool accepts does not start.
        byte[] index = smallIndex();
        TestArchiveBuilder archive = new TestArchiveBuilder();
        archive.stored("META-INF/MANIFEST.MF", HELLO);
        long at = archive.stored(IndexFormat.INDEX_ENTRY_NAME, index);
        for (int i = 2; i < 0xFFFF; i++) {
            archive.stored("MICRONAUT-INF/classes/f/" + i, new byte[0]);
        }
        byte[] content = archive.build();
        ArchiveSource source = open(content, mapped);

        long[] location = source.openIndex();
        assertEquals(at, location[0]);
        assertEquals(index.length, location[1]);
        assertEquals(IndexFormat.MAGIC, source.i32(location[0]));

        try (ZipFile zip = new ZipFile(write(content))) {
            assertEquals(0xFFFF, zip.size(), "the JDK reads the same archive");
        }
    }

    /**
     * Runs {@value #STRESS_THREADS} threads that each read random regions of up to {@value #STRESS_MAX_READ}
     * bytes and compare them with {@code expected}, while another thread interrupts a random reader about
     * every 20 µs until they finish. Readers stop after {@code readsPerThread} reads, after {@code seconds},
     * or, when {@code stopAtFailure} is set, once any read fails. Every thread is joined before this returns.
     */
    private static Stress stress(ArchiveSource source, byte[] expected, int readsPerThread,
                                 boolean stopAtFailure, long seconds) throws InterruptedException {
        Stress stress = new Stress();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        AtomicBoolean readersDone = new AtomicBoolean();
        Thread[] readers = new Thread[STRESS_THREADS];
        for (int t = 0; t < readers.length; t++) {
            long seed = 1_000L + t;
            readers[t] = new Thread(() -> {
                SplittableRandom random = new SplittableRandom(seed);
                for (int i = 0; i < readsPerThread && !stress.stop && System.nanoTime() < deadline; i++) {
                    int length = 1 + random.nextInt(STRESS_MAX_READ);
                    int offset = random.nextInt(expected.length - length + 1);
                    try {
                        byte[] read = source.readFully(offset, length);
                        if (!Arrays.equals(read, 0, length, expected, offset, offset + length)) {
                            stress.mismatches.incrementAndGet();
                        }
                    } catch (IOException | RuntimeException e) {
                        stress.failures.incrementAndGet();
                        stress.firstFailure.compareAndSet(null, e);
                        if (stopAtFailure) {
                            stress.stop = true;
                        }
                    }
                    stress.reads.incrementAndGet();
                }
            }, "archive-reader-" + t);
            readers[t].setDaemon(true);
        }
        Thread interrupter = new Thread(() -> {
            SplittableRandom random = new SplittableRandom(7);
            while (!readersDone.get()) {
                readers[random.nextInt(readers.length)].interrupt();
                stress.interrupts.incrementAndGet();
                long until = System.nanoTime() + INTERRUPT_SPIN_NANOS;
                while (System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
            }
        }, "archive-interrupter");
        interrupter.setDaemon(true);
        try {
            for (Thread reader : readers) {
                reader.start();
            }
            interrupter.start();
        } finally {
            for (Thread reader : readers) {
                reader.join(TimeUnit.SECONDS.toMillis(seconds + 30));
            }
            readersDone.set(true);
            interrupter.join(TimeUnit.SECONDS.toMillis(30));
        }
        for (Thread reader : readers) {
            assertFalse(reader.isAlive(), reader.getName() + " must have finished");
        }
        assertFalse(interrupter.isAlive(), "the interrupter must have finished");
        return stress;
    }

    private static byte[] random(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static byte[] payload(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((i * 31) ^ (i >> 5));
        }
        return bytes;
    }

    private static byte[] smallIndex() {
        TestIndexBuilder builder = new TestIndexBuilder().startClass("org.example.Application");
        builder.addJar(IndexFormat.CLASSES_PREFIX).addEntry("a/B.class").data(0, 1, 1);
        return builder.build();
    }

    private ArchiveSource open(byte[] content, boolean mapped) throws IOException {
        System.setProperty(ArchiveSource.MMAP_PROPERTY, Boolean.toString(mapped));
        return track(ArchiveSource.open(write(content)));
    }

    private ArchiveSource track(ArchiveSource source) {
        sources.add(source);
        return source;
    }

    private File write(byte[] content) throws IOException {
        File file = newFile();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
        return file;
    }

    private File newFile() {
        files++;
        return temporary.resolve("archive-" + files + ".zip").toFile();
    }

    /** What {@link #stress} observed. */
    private static final class Stress {

        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicInteger mismatches = new AtomicInteger();
        private final AtomicInteger interrupts = new AtomicInteger();
        private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        private volatile boolean stop;
    }
}
