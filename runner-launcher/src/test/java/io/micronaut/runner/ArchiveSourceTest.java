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
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the archive reader on each of its read paths, see {@link Mode}, over archives written by
 * {@link TestArchiveBuilder}: plain, with a ZIP comment, and in ZIP64 form.
 */
class ArchiveSourceTest {

    private static final byte[] HELLO = "hello runner".getBytes(StandardCharsets.UTF_8);

    /** The one class of {@link #runnerArchive}. */
    private static final String CLASS_ENTRY = "a/B.class";

    /** A {@link #setMmap} value that leaves the property unset, so that the archive's flag decides. */
    private static final String ARCHIVE_SELECTED = "<unset>";

    /** The content of the verifying stream cases: longer than one skip buffer, so a skip takes two reads. */
    private static final byte[] VERIFIED = payload(20_000);
    private static final String DESCRIPTION = "The entry verified.bin of test.jar";
    private static final int[] METHODS = {IndexFormat.METHOD_STORED, IndexFormat.METHOD_DEFLATED};

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

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void readsPrimitivesLittleEndian(Mode mode) throws IOException {
        byte[] content = new byte[] {
            0x01, (byte) 0xFF, 0x02, 0x03, (byte) 0x80, 0x11, 0x22, 0x33,
            0x44, 0x55, 0x66, 0x77, (byte) 0x88, (byte) 0x99, (byte) 0xAA, (byte) 0xBB
        };
        ArchiveSource source = open(content, mode);

        assertEquals(mode.mapped(), source.mapped());
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

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void refusesReadsOutsideTheArchive(Mode mode) throws IOException {
        ArchiveSource source = open(HELLO, mode);
        assertThrows(IOException.class, () -> source.u8(-1));
        assertThrows(IOException.class, () -> source.u8(HELLO.length));
        assertThrows(IOException.class, () -> source.u32(HELLO.length - 3));
        assertThrows(IOException.class, () -> source.u64(HELLO.length - 7));
        assertThrows(IOException.class, () -> source.slice(4, HELLO.length));
        assertThrows(IOException.class, () -> source.slice(0, -1));
        assertThrows(IOException.class, () -> source.readFully(0, HELLO.length + 1));
    }

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void slicesAndCopiesContent(Mode mode) throws IOException {
        ArchiveSource source = open(HELLO, mode);

        ByteBuffer slice = source.slice(6, 6);
        if (mode.mapped()) {
            assertTrue(slice.isDirect(), "a mapped slice is a window onto the mapping");
            assertTrue(slice.isReadOnly(), "a mapped slice is a read-only window onto the mapping");
            assertEquals(ByteOrder.BIG_ENDIAN, slice.order());
            assertEquals(0, slice.position());
            assertEquals(6, slice.limit());
            assertEquals(6, slice.capacity());
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

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(value = Mode.class, names = {"VIEW", "SEGMENT_ONLY"})
    void mappedReadsFailOnceTheSourceIsClosed(Mode mode) throws IOException {
        ArchiveSource source = open(HELLO, mode);
        ByteBuffer slice = source.slice(6, 6);
        assertEquals('r', slice.get(0));
        source.close();

        assertThrows(IllegalStateException.class, () -> slice.get(0),
                "a slice shares the mapping's lifetime, so it cannot outlive the source");
        assertThrows(IllegalStateException.class, () -> source.readFully(0, HELLO.length));
    }

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void verifyingStreamsReadIntactEntriesToTheEnd(Mode mode) throws IOException {
        Verified archive = verified(mode);
        for (int method : METHODS) {
            try (InputStream in = archive.stream(method, archive.crc)) {
                assertArrayEquals(VERIFIED, in.readAllBytes());
                for (int i = 0; i < 3; i++) {
                    assertEquals(-1, in.read());
                    assertEquals(-1, in.read(new byte[4], 0, 4));
                    assertEquals(0, in.skip(1));
                }
            }
            try (InputStream in = archive.emptyStream(method, 0)) {
                assertEquals(-1, in.read());
                assertEquals(-1, in.read());
            }
        }
    }

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void verifyingStreamsFailBeforeTheLastReadReturnsAndKeepFailing(Mode mode) throws IOException {
        Verified archive = verified(mode);
        for (int method : METHODS) {
            try (InputStream in = archive.stream(method, archive.crc ^ 1)) {
                byte[] content = new byte[VERIFIED.length];
                IOException failure = assertThrows(IOException.class,
                        () -> in.readNBytes(content, 0, content.length),
                        "an exact-length read never asks for the end, and must still fail");
                assertTrue(failure.getMessage().startsWith(DESCRIPTION + " has checksum "), failure.getMessage());
                assertTrue(failure.getMessage().contains(Index.REBUILD_MESSAGE), failure.getMessage());
                assertSame(failure, assertThrows(IOException.class, in::read));
                assertSame(failure, assertThrows(IOException.class, () -> in.read(content, 0, 1)));
                assertSame(failure, assertThrows(IOException.class, () -> in.skip(1)));
            }
            try (InputStream in = archive.emptyStream(method, 1)) {
                IOException failure = assertThrows(IOException.class, in::read);
                assertTrue(failure.getMessage().startsWith(DESCRIPTION), failure.getMessage());
                assertSame(failure, assertThrows(IOException.class, in::read));
            }
        }
    }

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void verifyingStreamsChecksumSkippedBytes(Mode mode) throws IOException {
        Verified archive = verified(mode);
        int skip = VERIFIED.length / 2;
        for (int method : METHODS) {
            try (InputStream in = archive.stream(method, archive.crc)) {
                assertEquals(skip, in.skip(skip));
                assertArrayEquals(Arrays.copyOfRange(VERIFIED, skip, VERIFIED.length), in.readAllBytes());
            }
            try (InputStream in = archive.stream(method, archive.crc ^ 1)) {
                assertEquals(skip, in.skip(skip));
                IOException failure = assertThrows(IOException.class, in::readAllBytes);
                assertTrue(failure.getMessage().startsWith(DESCRIPTION), failure.getMessage());
            }
            try (InputStream in = archive.stream(method, archive.crc ^ 1)) {
                IOException failure = assertThrows(IOException.class, () -> in.skip(Long.MAX_VALUE));
                assertTrue(failure.getMessage().startsWith(DESCRIPTION), failure.getMessage());
            }
        }
    }

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void closingAPartiallyReadVerifyingStreamReportsNothing(Mode mode) throws IOException {
        Verified archive = verified(mode);
        for (int method : METHODS) {
            InputStream in = archive.stream(method, archive.crc ^ 1);
            assertEquals(VERIFIED[0] & 0xFF, in.read());
            in.close();
            in.close();
        }
        assertArrayEquals(VERIFIED, archive.source.inflate(archive.deflated, archive.compressed, VERIFIED.length),
                "the inflater a closed stream returned to the pool must still work");
    }

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void streamsStoredEntries(Mode mode) throws IOException {
        byte[] large = payload(200_000);
        TestArchiveBuilder archive = new TestArchiveBuilder();
        long at = archive.stored("large.bin", large);
        ArchiveSource source = open(archive.build(), mode);

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

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void streamsAndInflatesDeflatedEntries(Mode mode) throws IOException {
        byte[] large = payload(200_000);
        TestArchiveBuilder archive = new TestArchiveBuilder();
        long at = archive.deflated("large.bin", large);
        int compressed = archive.storedSize("large.bin");
        ArchiveSource source = open(archive.build(), mode);

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

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void rejectsTruncatedAndOverlongDeflateStreams(Mode mode) throws IOException {
        byte[] large = payload(50_000);
        TestArchiveBuilder archive = new TestArchiveBuilder();
        long at = archive.deflated("large.bin", large);
        int compressed = archive.storedSize("large.bin");
        ArchiveSource source = open(archive.build(), mode);

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

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void rejectsUnterminatedDeflateAfterExpectedPlaintext(Mode mode) throws IOException {
        byte[] unterminated = new byte[] {0, 1, 0, (byte) 0xFE, (byte) 0xFF, 'x'};
        ArchiveSource source = open(unterminated, mode);

        assertThrows(IOException.class, () -> source.inflate(0, unterminated.length, 1));
        try (InputStream in = source.stream(0, unterminated.length, 1, IndexFormat.METHOD_DEFLATED)) {
            assertThrows(IOException.class, in::readAllBytes);
        }
    }

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void acceptsTerminalDeflateFramingAfterExpectedPlaintext(Mode mode) throws IOException {
        byte[] complete = new byte[] {
            0, 1, 0, (byte) 0xFE, (byte) 0xFF, 'x',
            1, 0, 0, (byte) 0xFF, (byte) 0xFF
        };
        ArchiveSource source = open(complete, mode);

        assertArrayEquals(new byte[] {'x'}, source.inflate(0, complete.length, 1));
        try (InputStream in = source.stream(0, complete.length, 1, IndexFormat.METHOD_DEFLATED)) {
            assertArrayEquals(new byte[] {'x'}, in.readAllBytes());
        }
    }

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void rejectsUnusedBytesAfterACompleteDeflateStream(Mode mode) throws IOException {
        byte[] completeWithTrailingByte = new byte[] {
            1, 1, 0, (byte) 0xFE, (byte) 0xFF, 'x', 0
        };
        ArchiveSource source = open(completeWithTrailingByte, mode);

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

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void validatesEmptyDeflateStreamsAndReusesInflatersAfterFailures(Mode mode) throws IOException {
        byte[] completeEmpty = new byte[] {1, 0, 0, (byte) 0xFF, (byte) 0xFF};
        ArchiveSource source = open(completeEmpty, mode);

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

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void streamsCorruptDataAsAnIoException(Mode mode) throws IOException {
        TestArchiveBuilder archive = new TestArchiveBuilder();
        long at = archive.raw("broken.bin", new byte[] {1, 2, 3, 4, 5, 6, 7, 8},
                IndexFormat.METHOD_DEFLATED, 64);
        ArchiveSource source = open(archive.build(), mode);
        assertThrows(IOException.class,
                () -> source.stream(at, 8, 64, IndexFormat.METHOD_DEFLATED).readAllBytes());
        assertThrows(IOException.class, () -> source.inflate(at, 8, 64));
    }

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void findsTheIndexEntry(Mode mode) throws IOException {
        byte[] index = smallIndex();
        TestArchiveBuilder archive = new TestArchiveBuilder();
        archive.stored("META-INF/MANIFEST.MF", HELLO);
        long at = archive.stored(IndexFormat.INDEX_ENTRY_NAME, index);
        archive.stored("MICRONAUT-INF/classes/a/B.class", HELLO);
        ArchiveSource source = open(archive.build(), mode);

        long[] location = source.openIndex();
        assertEquals(at, location[0]);
        assertEquals(index.length, location[1]);
        assertEquals(IndexFormat.MAGIC, source.i32(location[0]));
    }

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void findsTheIndexEntryBehindAZipComment(Mode mode) throws IOException {
        byte[] index = smallIndex();
        StringBuilder comment = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            comment.append("comment ").append(i).append(' ');
        }
        TestArchiveBuilder archive = new TestArchiveBuilder().comment(comment.toString());
        archive.stored("META-INF/MANIFEST.MF", HELLO);
        long at = archive.stored(IndexFormat.INDEX_ENTRY_NAME, index);
        byte[] content = archive.build();
        ArchiveSource source = open(content, mode);

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

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void findsTheIndexEntryInAZip64Archive(Mode mode) throws IOException {
        byte[] index = smallIndex();
        TestArchiveBuilder archive = new TestArchiveBuilder().zip64(true);
        archive.stored("META-INF/MANIFEST.MF", HELLO);
        long at = archive.stored(IndexFormat.INDEX_ENTRY_NAME, index);
        archive.stored("MICRONAUT-INF/classes/a/B.class", HELLO);
        byte[] content = archive.build();
        ArchiveSource source = open(content, mode);

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
        ArchiveSource withoutIndex = open(archive.build(), Mode.VIEW);
        IOException missing = assertThrows(IOException.class, withoutIndex::openIndex);
        assertTrue(missing.getMessage().contains(IndexFormat.INDEX_ENTRY_NAME), missing.getMessage());

        byte[] notAZip = new byte[512];
        Arrays.fill(notAZip, (byte) 'x');
        ArchiveSource garbage = open(notAZip, Mode.VIEW);
        assertThrows(IOException.class, garbage::openIndex);

        ArchiveSource tooShort = open(new byte[4], Mode.VIEW);
        assertThrows(IOException.class, tooShort::openIndex);
        ArchiveSource nothing = open(new byte[0], Mode.VIEW);
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
        ArchiveSource source = open(archive.build(), Mode.VIEW);
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

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void readsWithTheInterruptFlagSet(Mode mode) throws IOException {
        // A FileChannel read by an interrupted thread closes the channel for every thread, so before the
        // positional reads handled interrupts, one cancelled task stopped all class loading in the process.
        byte[] stored = payload(200_000);
        byte[] deflated = random(150_000, 149);
        TestArchiveBuilder archive = new TestArchiveBuilder();
        long storedAt = archive.stored("stored.bin", stored);
        long deflatedAt = archive.deflated("deflated.bin", deflated);
        int compressed = archive.storedSize("deflated.bin");
        ArchiveSource source = open(archive.build(), mode);
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

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void readsSurviveConcurrentInterrupts(Mode mode) throws Exception {
        byte[] content = random(STRESS_FILE_LENGTH, 149);
        ArchiveSource source = open(content, mode);

        Stress stress = stress(source, content, STRESS_READS_PER_THREAD, false, STRESS_SECONDS);

        assertEquals(0, stress.failures.get(),
                () -> "reads failed; the first failure was " + stress.firstFailure.get());
        assertEquals(0, stress.mismatches.get(), "reads returned the wrong bytes");
        assertEquals(STRESS_THREADS * STRESS_READS_PER_THREAD, stress.reads.get());
        assertTrue(stress.interrupts.get() > 0, "the interrupter must have run");
        assertArrayEquals(Arrays.copyOfRange(content, 0, 4096), source.readFully(0, 4096),
                "the source still reads after the stress");
    }

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(value = Mode.class, names = {"CHANNEL", "INDEX"})
    void closedPositionalSourceNeverReopens(Mode mode) throws IOException {
        ArchiveSource source = open(HELLO, mode);
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

    /**
     * The reopen check of every positional mode: {@code false} and {@code index} capture the file key at open,
     * and an archive left to choose captures it when its flag switches the source to the {@code index} mode.
     */
    @ParameterizedTest(name = "mmap={0}")
    @ValueSource(strings = {"false", "index", ARCHIVE_SELECTED})
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "an open file cannot be replaced by a rename on Windows")
    void refusesToReopenAReplacedFile(String mmap) throws Exception {
        byte[] original = runnerArchive(IndexFormat.HEADER_FLAG_POSITIONAL_READS, random(STRESS_FILE_LENGTH, 149));
        File file = write(original);
        setMmap(mmap);
        ArchiveSource source = track(ArchiveSource.open(file));
        Index.open(source);
        assertFalse(source.mapped());
        assertEquals(!"false".equals(mmap), source.indexOnly());
        assertNotNull(source.fileKey(), "the key is captured before the first positional read");

        // A rename-based deployment: the same path and length, but another file.
        File staged = write(runnerArchive(IndexFormat.HEADER_FLAG_POSITIONAL_READS,
                random(STRESS_FILE_LENGTH, 150)));
        assertEquals(original.length, staged.length());
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

    /**
     * Every value of {@value ArchiveSource#MMAP_PROPERTY} against both states of the archive's flag, after
     * {@link Index#open(ArchiveSource)} has settled the mode.
     */
    @ParameterizedTest(name = "mmap={0}, flag={1}")
    @CsvSource({
        "<unset>, false, true,  false",
        "<unset>, true,  false, true",
        "FALSE,   false, true,  false",
        "FALSE,   true,  false, true",
        "full,    false, true,  false",
        "full,    true,  true,  false",
        "true,    false, true,  false",
        "true,    true,  true,  false",
        "index,   false, false, true",
        "index,   true,  false, true",
        "false,   false, false, false",
        "false,   true,  false, false"})
    void theReadModeFollowsThePropertyAndThenTheArchiveFlag(String mmap, boolean flag, boolean mapped,
                                                           boolean indexOnly) throws IOException {
        byte[] classBytes = payload(3_000);
        File file = write(runnerArchive(flag ? IndexFormat.HEADER_FLAG_POSITIONAL_READS : 0, classBytes));
        setMmap(mmap);
        ArchiveSource source = track(ArchiveSource.open(file));
        Index index = Index.open(source);

        assertEquals(flag, index.positionalReads());
        assertEquals(mapped, source.mapped());
        assertEquals(indexOnly, source.indexOnly());
        // Only the index mode maps the index on its own, which sizes the pool from the header.
        assertEquals(indexOnly ? (flag ? 4096 : ArchiveSource.POOL_MAX_BUFFER_SIZE) : 0, source.poolBufferSize());
        if (mapped) {
            assertNull(source.fileKey(), "a mapped launch never reads the file key");
        } else if (!OS.WINDOWS.isCurrentOs()) {
            assertNotNull(source.fileKey(), "positional reads capture the key before the first read");
        }
        int record = index.find(CLASS_ENTRY);
        ByteBuffer borrowed = source.borrow(index.entryDataOffset(record), classBytes.length);
        try {
            assertArrayEquals(classBytes, content(borrowed));
            assertEquals(indexOnly, borrowed.isDirect() && !borrowed.isReadOnly(), "only the index mode pools");
        } finally {
            source.release(borrowed);
        }
    }

    @Test
    void anArchiveThatAsksForPositionalReadsSwitchesAndCanBeDeletedOnceClosed() throws IOException {
        byte[] classBytes = payload(200_000);
        File file = write(runnerArchive(IndexFormat.HEADER_FLAG_POSITIONAL_READS, classBytes));
        System.clearProperty(ArchiveSource.MMAP_PROPERTY);
        ArchiveSource source = ArchiveSource.open(file);
        assertTrue(source.mapped(), "the whole file is mapped until the index is read");
        assertFalse(source.indexOnly());
        try {
            Index index = Index.open(source);
            assertFalse(source.mapped());
            assertTrue(source.indexOnly());
            assertEquals(classBytes.length, index.largestStoredClass());
            assertEquals(ArchiveSource.POOL_MAX_BUFFER_SIZE, source.poolBufferSize(),
                    "a class larger than the largest buffer caps the buffer size");
            assertArrayEquals(classBytes, source.readFully(index.entryDataOffset(index.find(CLASS_ENTRY)),
                    classBytes.length));
            // A second index open maps the region again, without a second switch.
            Index again = Index.open(source);
            assertEquals(index.entryCount(), again.entryCount());
        } finally {
            source.close();
            source.close();
        }
        assertTrue(file.delete(), "an archive must be deletable once the source is closed");
    }

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void streamsOneMebibyteEntriesByteForByte(Mode mode) throws IOException {
        byte[] stored = random(1024 * 1024, 11);
        byte[] deflated = payload(1024 * 1024);
        TestArchiveBuilder archive = new TestArchiveBuilder();
        long storedAt = archive.stored("stored.bin", stored);
        long deflatedAt = archive.deflated("deflated.bin", deflated);
        int compressed = archive.storedSize("deflated.bin");
        ArchiveSource source = open(archive.build(), mode);
        int skip = 300_001;

        for (boolean verifying : new boolean[] {false, true}) {
            try (InputStream in = stream(source, storedAt, stored.length, stored, IndexFormat.METHOD_STORED,
                    verifying)) {
                assertArrayEquals(stored, in.readAllBytes());
            }
            try (InputStream in = stream(source, storedAt, stored.length, stored, IndexFormat.METHOD_STORED,
                    verifying)) {
                assertEquals(skip, in.skip(skip));
                assertArrayEquals(Arrays.copyOfRange(stored, skip, stored.length), in.readAllBytes());
            }
            try (InputStream in = stream(source, deflatedAt, compressed, deflated, IndexFormat.METHOD_DEFLATED,
                    verifying)) {
                assertArrayEquals(deflated, in.readAllBytes());
            }
            try (InputStream in = stream(source, deflatedAt, compressed, deflated, IndexFormat.METHOD_DEFLATED,
                    verifying)) {
                assertEquals(skip, in.skip(skip));
                assertArrayEquals(Arrays.copyOfRange(deflated, skip, deflated.length), in.readAllBytes());
            }
        }
        assertArrayEquals(deflated, source.inflate(deflatedAt, compressed, deflated.length));
    }

    private static InputStream stream(ArchiveSource source, long at, int compressed, byte[] content, int method,
                                      boolean verifying) throws IOException {
        if (!verifying) {
            return source.stream(at, compressed, content.length, method);
        }
        CRC32 checksum = new CRC32();
        checksum.update(content);
        return source.stream(at, compressed, content.length, method, checksum.getValue(), DESCRIPTION);
    }

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void borrowLendsEachModesOwnKindOfBuffer(Mode mode) throws IOException {
        byte[] content = random(100_000, 3);
        ArchiveSource source = open(content, mode);

        ByteBuffer borrowed = source.borrow(1_000, 5_000);
        assertEquals(0, borrowed.position());
        assertEquals(5_000, borrowed.remaining());
        assertArrayEquals(Arrays.copyOfRange(content, 1_000, 6_000), content(borrowed));
        switch (mode) {
            case VIEW, SEGMENT_ONLY -> assertTrue(borrowed.isDirect() && borrowed.isReadOnly(),
                    "a mapped borrow is a window onto the mapping");
            case CHANNEL -> assertTrue(borrowed.hasArray(), "the false mode reads into a heap array");
            case INDEX -> assertTrue(borrowed.isDirect() && !borrowed.isReadOnly(), "a pooled buffer");
            default -> throw new AssertionError(mode);
        }
        source.release(borrowed);
        int pooled = mode == Mode.INDEX ? 1 : 0;
        assertEquals(pooled, source.idleBuffers());
        assertEquals(pooled, source.createdBuffers());
        assertThrows(IOException.class, () -> source.borrow(content.length - 10, 11));
        assertThrows(IOException.class, () -> source.borrow(0, -1));
        assertEquals(pooled, source.idleBuffers(), "a refused borrow takes no buffer");
    }

    @Test
    void theIndexModePoolLendsAtMostSixteenDirectBuffersAndTrimsAfterStartup() throws IOException {
        byte[] content = random(300_000, 5);
        ArchiveSource source = open(content, Mode.INDEX);

        List<ByteBuffer> borrowed = new ArrayList<>();
        for (int i = 0; i < ArchiveSource.POOL_MAX_BUFFERS; i++) {
            ByteBuffer buffer = source.borrow(i * 1_000L, 2_000);
            assertTrue(buffer.isDirect(), "buffer " + i);
            assertEquals(ArchiveSource.POOL_MAX_BUFFER_SIZE, buffer.capacity(), "no index recorded a size");
            assertArrayEquals(Arrays.copyOfRange(content, i * 1_000, i * 1_000 + 2_000), content(buffer));
            borrowed.add(buffer);
        }
        assertEquals(ArchiveSource.POOL_MAX_BUFFERS, source.createdBuffers());

        ByteBuffer overflow = source.borrow(7, 3_000);
        assertFalse(overflow.isDirect(), "a borrow that finds every buffer in use reads into a heap array");
        assertArrayEquals(Arrays.copyOfRange(content, 7, 3_007), content(overflow));
        source.release(overflow);
        ByteBuffer large = source.borrow(0, ArchiveSource.POOL_MAX_BUFFER_SIZE + 1);
        assertFalse(large.isDirect(), "a region larger than a pooled buffer reads into a heap array");
        assertArrayEquals(Arrays.copyOf(content, ArchiveSource.POOL_MAX_BUFFER_SIZE + 1), content(large));
        assertEquals(ArchiveSource.POOL_MAX_BUFFERS, source.createdBuffers(), "never a seventeenth buffer");

        for (ByteBuffer buffer : borrowed) {
            source.release(buffer);
        }
        assertEquals(ArchiveSource.POOL_MAX_BUFFERS, source.idleBuffers());
        ByteBuffer reused = source.borrow(11, 20);
        assertSame(borrowed.get(borrowed.size() - 1), reused, "the most recently released buffer is reused");
        assertEquals(20, reused.remaining());
        assertArrayEquals(Arrays.copyOfRange(content, 11, 31), content(reused));
        source.release(reused);

        source.startupFinished();
        assertEquals(ArchiveSource.POOL_IDLE_AFTER_STARTUP, source.idleBuffers());
        List<ByteBuffer> after = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            after.add(source.borrow(i, 100));
        }
        for (ByteBuffer buffer : after) {
            source.release(buffer);
        }
        assertEquals(ArchiveSource.POOL_IDLE_AFTER_STARTUP, source.idleBuffers(),
                "after startup the pool keeps two idle buffers");
    }

    @Test
    void theIndexModeInflatesFromAPooledBuffer() throws IOException {
        byte[] content = payload(150_000);
        TestArchiveBuilder archive = new TestArchiveBuilder();
        long at = archive.deflated("deflated.bin", content);
        int compressed = archive.storedSize("deflated.bin");
        assertTrue(compressed < ArchiveSource.POOL_MAX_BUFFER_SIZE, "the fixture must fit a pooled buffer");
        ArchiveSource source = open(archive.build(), Mode.INDEX);

        for (int i = 0; i < 4; i++) {
            assertArrayEquals(content, source.inflate(at, compressed, content.length));
        }
        assertEquals(1, source.createdBuffers(), "each inflate borrowed and returned the same buffer");
        assertEquals(1, source.idleBuffers());
    }

    @Test
    void aClosedIndexSourceRefusesBorrowsAndReads() throws IOException {
        byte[] classBytes = payload(3_000);
        File file = write(runnerArchive(0, classBytes));
        System.setProperty(ArchiveSource.MMAP_PROPERTY, "index");
        ArchiveSource source = track(ArchiveSource.open(file));
        Index index = Index.open(source);
        long at = index.entryDataOffset(index.find(CLASS_ENTRY));
        source.release(source.borrow(at, classBytes.length));
        assertEquals(1, source.idleBuffers());
        source.close();

        assertEquals(0, source.idleBuffers(), "close drops the pool");
        assertThrows(IOException.class, () -> source.borrow(at, classBytes.length));
        assertThrows(IOException.class, () -> source.readFully(at, classBytes.length));
        assertThrows(IllegalStateException.class, index::startClass, "the index mapping is closed too");
    }

    @Test
    void poolBuffersAreTheLargestClassRoundedUpToAPageAndCapped() {
        assertEquals(ArchiveSource.POOL_MAX_BUFFER_SIZE, ArchiveSource.poolBufferSize(0));
        assertEquals(4096, ArchiveSource.poolBufferSize(1));
        assertEquals(4096, ArchiveSource.poolBufferSize(4096));
        assertEquals(8192, ArchiveSource.poolBufferSize(4097));
        assertEquals(ArchiveSource.POOL_MAX_BUFFER_SIZE,
                ArchiveSource.poolBufferSize(ArchiveSource.POOL_MAX_BUFFER_SIZE));
        assertEquals(ArchiveSource.POOL_MAX_BUFFER_SIZE, ArchiveSource.poolBufferSize(0xFFFFFFFFL));
    }

    @ParameterizedTest(name = "mode={0}")
    @EnumSource(Mode.class)
    void findsTheIndexEntryWhenTheEntryCountCarriesTheMarker(Mode mode) throws IOException {
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
        ArchiveSource source = open(content, mode);

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

    /** An archive with {@link #VERIFIED} stored and deflated, and an empty entry of each kind. */
    private Verified verified(Mode mode) throws IOException {
        TestArchiveBuilder archive = new TestArchiveBuilder();
        long stored = archive.stored("stored.bin", VERIFIED);
        long deflated = archive.deflated("deflated.bin", VERIFIED);
        int compressed = archive.storedSize("deflated.bin");
        long emptyStored = archive.stored("empty-stored.bin", new byte[0]);
        long emptyDeflated = archive.deflated("empty-deflated.bin", new byte[0]);
        int emptyCompressed = archive.storedSize("empty-deflated.bin");
        CRC32 checksum = new CRC32();
        checksum.update(VERIFIED);
        return new Verified(open(archive.build(), mode), stored, deflated, compressed, emptyStored, emptyDeflated,
                emptyCompressed, checksum.getValue());
    }

    private ArchiveSource open(byte[] content, Mode mode) throws IOException {
        System.setProperty(ArchiveSource.MMAP_PROPERTY, mode.property());
        ArchiveSource source = track(ArchiveSource.open(write(content), mode != Mode.SEGMENT_ONLY));
        assertEquals(mode.mapped(), source.mapped());
        assertEquals(mode == Mode.INDEX, source.indexOnly());
        return source;
    }

    /** Sets {@value ArchiveSource#MMAP_PROPERTY}, or clears it for {@link #ARCHIVE_SELECTED}. */
    private static void setMmap(String value) {
        if (ARCHIVE_SELECTED.equals(value)) {
            System.clearProperty(ArchiveSource.MMAP_PROPERTY);
        } else {
            System.setProperty(ArchiveSource.MMAP_PROPERTY, value);
        }
    }

    /**
     * A runner archive: a manifest, an index whose header carries {@code headerFlags}, and one STORED class,
     * {@value #CLASS_ENTRY}, in the application layer.
     */
    private static byte[] runnerArchive(int headerFlags, byte[] classBytes) {
        TestArchiveBuilder archive = new TestArchiveBuilder();
        archive.stored("META-INF/MANIFEST.MF", HELLO);
        archive.reserve(IndexFormat.INDEX_ENTRY_NAME, runnerIndex(headerFlags, 0, classBytes).length);
        long at = archive.stored(IndexFormat.CLASSES_PREFIX + CLASS_ENTRY, classBytes);
        archive.replace(IndexFormat.INDEX_ENTRY_NAME, runnerIndex(headerFlags, at, classBytes));
        return archive.build();
    }

    private static byte[] runnerIndex(int headerFlags, long at, byte[] classBytes) {
        CRC32 checksum = new CRC32();
        checksum.update(classBytes);
        TestIndexBuilder builder = new TestIndexBuilder().startClass("a.B").headerFlags(headerFlags);
        builder.addJar(IndexFormat.CLASSES_PREFIX).addEntry(CLASS_ENTRY)
                .data(at, classBytes.length, classBytes.length).crc32(checksum.getValue());
        return builder.build();
    }

    private static byte[] content(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
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

    /** The read path a test runs on. */
    enum Mode {
        /** Mapped, read through the buffer view of the mapping: the default. */
        VIEW,
        /** Mapped, read through the segment, as for an archive too large for a buffer view. */
        SEGMENT_ONLY,
        /** Not mapped: {@value ArchiveSource#MMAP_PROPERTY} is {@code false}, and reads are positional. */
        CHANNEL,
        /**
         * Only an index would be mapped: {@value ArchiveSource#MMAP_PROPERTY} is {@code index}, and reads are
         * positional, borrowed ones into pooled direct buffers.
         */
        INDEX;

        boolean mapped() {
            return this == VIEW || this == SEGMENT_ONLY;
        }

        String property() {
            return switch (this) {
                case VIEW, SEGMENT_ONLY -> "true";
                case CHANNEL -> "false";
                case INDEX -> "index";
            };
        }
    }

    /** Where {@link #verified} put its entries, and what they checksum to. */
    private record Verified(ArchiveSource source, long stored, long deflated, int compressed, long emptyStored,
                            long emptyDeflated, int emptyCompressed, long crc) {

        /** A verifying stream of {@link #VERIFIED}, expecting {@code expectedCrc}. */
        InputStream stream(int method, long expectedCrc) throws IOException {
            if (method == IndexFormat.METHOD_STORED) {
                return source.stream(stored, VERIFIED.length, VERIFIED.length, method, expectedCrc, DESCRIPTION);
            }
            return source.stream(deflated, compressed, VERIFIED.length, method, expectedCrc, DESCRIPTION);
        }

        /** A verifying stream of an empty entry, whose CRC-32 is 0, expecting {@code expectedCrc}. */
        InputStream emptyStream(int method, long expectedCrc) throws IOException {
            if (method == IndexFormat.METHOD_STORED) {
                return source.stream(emptyStored, 0, 0, method, expectedCrc, DESCRIPTION);
            }
            return source.stream(emptyDeflated, emptyCompressed, 0, method, expectedCrc, DESCRIPTION);
        }
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
