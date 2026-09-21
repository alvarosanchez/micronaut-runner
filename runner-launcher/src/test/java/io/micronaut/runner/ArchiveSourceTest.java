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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the archive reader in both the mapped and the fallback mode, over archives written by
 * {@link TestArchiveBuilder}: plain, with a ZIP comment, and in ZIP64 form.
 */
class ArchiveSourceTest {

    private static final byte[] HELLO = "hello runner".getBytes(StandardCharsets.UTF_8);

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
        assertTrue(slice.isReadOnly());
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
}
