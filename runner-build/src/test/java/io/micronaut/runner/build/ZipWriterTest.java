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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static io.micronaut.runner.build.ZipReaderTest.bytesAt;
import static io.micronaut.runner.build.ZipReaderTest.intAt;
import static io.micronaut.runner.build.ZipReaderTest.manifestBytes;
import static io.micronaut.runner.build.ZipReaderTest.names;
import static io.micronaut.runner.build.ZipReaderTest.readAll;
import static io.micronaut.runner.build.ZipReaderTest.repeat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ZipWriter}: the archive it produces has to be an ordinary ZIP file to every other tool,
 * while the offsets it reports have to be exact, because the launcher reads entry data by absolute offset
 * and never parses the archive.
 */
class ZipWriterTest {

    @TempDir
    Path temp;

    @Test
    void writesAnArchiveTheJdkCanRead() throws IOException {
        byte[] classBytes = repeat("class-", 100);
        byte[] resourceBytes = "resource".getBytes(StandardCharsets.UTF_8);
        byte[] unicodeBytes = "unicode".getBytes(StandardCharsets.UTF_8);
        Path jar = temp.resolve("out.jar");
        Map<String, Long> offsets = new LinkedHashMap<>();
        try (ZipWriter writer = ZipWriter.create(jar, ZipWriter.DEFAULT_TIMESTAMP)) {
            offsets.put("META-INF/MANIFEST.MF", writer.writeEntry("META-INF/MANIFEST.MF",
                    manifestBytes("Created-By", "runner")));
            offsets.put("META-INF/micronaut/", writer.writeDirectoryEntry("META-INF/micronaut/"));
            offsets.put("org/example/App.class", writer.writeEntry("org/example/App.class", classBytes));
            offsets.put("org/example/empty.txt", writer.writeEntry("org/example/empty.txt", new byte[0]));
            offsets.put("org/example/caf\u00e9.txt", writer.writeEntry("org/example/caf\u00e9.txt", unicodeBytes));
            offsets.put("org/example/part.bin", writer.writeEntry("org/example/part.bin",
                    new byte[] {9, 9, 8, 7, 9}, 2, 2));
            offsets.put("org/example/stream.bin", writer.writeEntry("org/example/stream.bin",
                    new ByteArrayInputStream(resourceBytes), resourceBytes.length, crc(resourceBytes),
                    writer.dosTime()));
            assertEquals(7, writer.entryCount());
        }

        try (ZipFile oracle = new ZipFile(jar.toFile())) {
            assertEquals(List.copyOf(offsets.keySet()), names(oracle), "entries keep the order they were written in");
            assertArrayEquals(classBytes, readAll(oracle, oracle.getEntry("org/example/App.class")));
            assertArrayEquals(unicodeBytes, readAll(oracle, oracle.getEntry("org/example/caf\u00e9.txt")));
            assertArrayEquals(new byte[] {8, 7}, readAll(oracle, oracle.getEntry("org/example/part.bin")));
            assertArrayEquals(resourceBytes, readAll(oracle, oracle.getEntry("org/example/stream.bin")));
            assertTrue(oracle.getEntry("META-INF/micronaut/").isDirectory(), "directory entries survive");
            assertEquals(0, oracle.getEntry("META-INF/micronaut/").getSize());
            for (String name : offsets.keySet()) {
                ZipEntry entry = oracle.getEntry(name);
                assertEquals(ZipEntry.STORED, entry.getMethod(), name + " must be stored");
                assertEquals(entry.getSize(), entry.getCompressedSize(), name);
                assertEquals(crc(readAll(oracle, entry)), entry.getCrc(), name + " CRC-32");
                assertArrayEquals(readAll(oracle, entry), bytesAt(jar, offsets.get(name), (int) entry.getSize()),
                        name + " must be at the reported data offset");
            }
        }

        try (ZipReader reader = ZipReader.open(jar)) {
            assertEquals(List.copyOf(offsets.keySet()), reader.entries().stream().map(ZipEntryInfo::name).toList());
            for (ZipEntryInfo entry : reader.entries()) {
                assertEquals(offsets.get(entry.name()).longValue(), entry.dataOffset(), entry.name());
                assertEquals(IndexFormat.METHOD_STORED, entry.method());
                assertEquals(0, intAt(bytesAt(jar, entry.localHeaderOffset(), 30), 26) >>> 16,
                        entry.name() + " must have no extra field");
            }
            assertEquals("runner", reader.manifest().orElseThrow().getMainAttributes().getValue("Created-By"));
        }
    }

    @Test
    void writesAnEntryFromAFile() throws IOException {
        byte[] content = repeat("from-a-file-", 50);
        Path source = temp.resolve("source.bin");
        Files.write(source, content);
        Path jar = temp.resolve("file-entry.jar");
        long dataOffset;
        try (ZipWriter writer = ZipWriter.create(jar, ZipWriter.DEFAULT_TIMESTAMP)) {
            dataOffset = writer.writeEntry("MICRONAUT-INF/lib/dep.jar", source);
        }
        assertArrayEquals(content, bytesAt(jar, dataOffset, content.length));
        try (ZipFile oracle = new ZipFile(jar.toFile())) {
            ZipEntry entry = oracle.getEntry("MICRONAUT-INF/lib/dep.jar");
            assertEquals(content.length, entry.getSize());
            assertEquals(crc(content), entry.getCrc());
        }
    }

    @Test
    void usesAFixedTimestampConvertedInUtc() throws IOException {
        // 1980-02-01T00:00:00Z: date = (0 << 9) | (2 << 5) | 1, time = 0.
        assertEquals(0x0041_0000, ZipWriter.toDosTime(ZipWriter.DEFAULT_TIMESTAMP));
        assertEquals(0x2A62_2145, ZipWriter.toDosTime(Instant.parse("2001-03-02T04:10:10Z")));
        assertThrows(IllegalArgumentException.class, () -> ZipWriter.toDosTime(Instant.parse("1979-12-31T23:59:59Z")));
        assertThrows(IllegalArgumentException.class, () -> ZipWriter.toDosTime(Instant.parse("2108-01-01T00:00:00Z")));

        byte[] first = archiveOf(ZipWriter.DEFAULT_TIMESTAMP);
        byte[] second = archiveOf(ZipWriter.DEFAULT_TIMESTAMP);
        assertArrayEquals(first, second, "the same inputs must produce a byte-identical archive");
        assertFalse(Arrays.equals(first, archiveOf(Instant.parse("2001-03-02T04:10:10Z"))),
                "a different timestamp must change the output");

        Path jar = temp.resolve("timestamped.jar");
        Files.write(jar, first);
        try (ZipReader reader = ZipReader.open(jar)) {
            for (ZipEntryInfo entry : reader.entries()) {
                assertEquals(0x0041_0000, entry.dosTime(), entry.name());
            }
        }
    }

    @Test
    void rejectsDuplicateAndUnsafeNames() throws IOException {
        try (ZipWriter writer = new ZipWriter(new ByteArrayOutputStream())) {
            writer.writeEntry("a/B.class", new byte[] {1});
            IOException duplicate = assertThrows(IOException.class,
                    () -> writer.writeEntry("a/B.class", new byte[] {2}));
            assertTrue(duplicate.getMessage().contains("a/B.class"), duplicate.getMessage());
            IOException caseOnly = assertThrows(IOException.class,
                    () -> writer.writeEntry("a/b.class", new byte[] {3}));
            assertTrue(caseOnly.getMessage().contains("case"), caseOnly.getMessage());
            assertThrows(IOException.class, () -> writer.writeEntry("../escape.txt", new byte[] {4}));
            assertThrows(IOException.class, () -> writer.writeEntry("/absolute.txt", new byte[] {5}));
            assertThrows(IOException.class, () -> writer.writeEntry("a\\b.txt", new byte[] {6}));
            assertThrows(IOException.class, () -> writer.writeEntry("trailing/", new byte[] {7}));
            assertThrows(IOException.class, () -> writer.writeDirectoryEntry("no-slash"));
            assertEquals(1, writer.entryCount(), "a rejected entry must not be written");
        }
    }

    @Test
    void canBeToldToAllowRepeatedNames() throws IOException {
        // A dependency jar somebody else produced may carry two names that differ only by case, or the
        // very same name twice; java.util.zip reads both, so repacking one must not fail a build.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipWriter writer = new ZipWriter(bytes, ZipWriter.DEFAULT_TIMESTAMP, false)) {
            writer.writeEntry("a/B.class", new byte[] {1});
            writer.writeEntry("a/b.class", new byte[] {2});
            writer.writeEntry("a/B.class", new byte[] {3});
        }
        Path jar = temp.resolve("case.jar");
        Files.write(jar, bytes.toByteArray());
        try (ZipReader reader = ZipReader.open(jar)) {
            List<ZipEntryInfo> entries = reader.entries();
            assertEquals(List.of("a/B.class", "a/b.class", "a/B.class"),
                    entries.stream().map(ZipEntryInfo::name).toList());
            assertArrayEquals(new byte[] {1}, reader.read(entries.get(0)));
            assertArrayEquals(new byte[] {3}, reader.read(entries.get(2)),
                    "both records keep their own content");
        }
        try (ZipFile oracle = new ZipFile(jar.toFile())) {
            assertEquals(3, oracle.size(), "the JDK reads the archive and counts every record");
        }
    }

    @Test
    void rejectsRepeatedNamesInTheOuterArchive() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipWriter writer = new ZipWriter(bytes, ZipWriter.DEFAULT_TIMESTAMP, true)) {
            writer.writeEntry("a/B.class", new byte[] {1});
            assertThrows(IOException.class, () -> writer.writeEntry("a/B.class", new byte[] {2}));
            assertThrows(IOException.class, () -> writer.writeEntry("a/b.class", new byte[] {3}));
        }
    }

    @Test
    void refusesToWriteAfterFinishing() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipWriter writer = new ZipWriter(bytes);
        writer.writeEntry("a.txt", new byte[] {1});
        writer.finish();
        long length = writer.offset();
        writer.finish();
        assertEquals(length, writer.offset(), "finishing twice must not write a second end record");
        assertThrows(IOException.class, () -> writer.writeEntry("b.txt", new byte[] {2}));
        writer.close();
        assertEquals(length, bytes.size());
    }

    @Test
    void writesZip64RecordsAtExactlyTheMarkerEntryCount() throws IOException {
        // 65535 is the value the 16-bit entry count field uses to say "the real count is in the ZIP64
        // record", so an archive of exactly that many entries has to carry those records. Deciding with
        // > rather than >= wrote the marker and nothing behind it, and every reader that takes the marker
        // at its word - the launcher's included - then refused an archive the writer thought was fine.
        int count = 0xFFFF;
        Path jar = temp.resolve("exactly.jar");
        try (ZipWriter writer = ZipWriter.create(jar, ZipWriter.DEFAULT_TIMESTAMP)) {
            for (int i = 0; i < count; i++) {
                writer.writeEntry("e/" + i, new byte[] {(byte) i});
            }
            assertEquals(count, writer.entryCount());
        }
        long length = Files.size(jar);

        byte[] end = bytesAt(jar, length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE,
                IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE);
        assertEquals(0xFFFF, intAt(end, 8) & 0xFFFF, "the 16-bit count can only carry the marker");

        long locatorOffset = length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE
                - IndexFormat.ZIP64_LOCATOR_SIZE;
        byte[] locator = bytesAt(jar, locatorOffset, IndexFormat.ZIP64_LOCATOR_SIZE);
        assertEquals(IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE, intAt(locator, 0),
                "the marker has to be explained by a ZIP64 end of central directory record");
        byte[] zip64 = bytesAt(jar, intAt(locator, 8) & 0xFFFFFFFFL, 56);
        assertEquals(IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE, intAt(zip64, 0));
        assertEquals(count, intAt(zip64, 32) & 0xFFFFFFFFL);

        try (ZipReader reader = ZipReader.open(jar)) {
            assertEquals(count, reader.entries().size());
        }
        try (ZipFile oracle = new ZipFile(jar.toFile())) {
            assertEquals(count, oracle.size());
        }
    }

    @Test
    void writesZip64RecordsWhenThereAreMoreThan65535Entries() throws IOException {
        int count = 65_600;
        Path jar = temp.resolve("many.jar");
        List<Long> offsets = new ArrayList<>(count);
        try (ZipWriter writer = ZipWriter.create(jar, ZipWriter.DEFAULT_TIMESTAMP)) {
            for (int i = 0; i < count; i++) {
                offsets.add(writer.writeEntry("e/" + i, new byte[] {(byte) i}));
            }
        }
        long length = Files.size(jar);

        byte[] end = bytesAt(jar, length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE,
                IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE);
        assertEquals(IndexFormat.END_OF_CENTRAL_DIRECTORY_SIGNATURE, intAt(end, 0));
        assertEquals(0xFFFF, intAt(end, 8) & 0xFFFF, "the 16-bit entry count must carry the marker");

        long locatorOffset = length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE - IndexFormat.ZIP64_LOCATOR_SIZE;
        byte[] locator = bytesAt(jar, locatorOffset, IndexFormat.ZIP64_LOCATOR_SIZE);
        assertEquals(IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE, intAt(locator, 0));
        long zip64End = intAt(locator, 8) & 0xFFFFFFFFL;
        byte[] zip64 = bytesAt(jar, zip64End, 56);
        assertEquals(IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE, intAt(zip64, 0));
        assertEquals(count, intAt(zip64, 32) & 0xFFFFFFFFL, "the ZIP64 record carries the real entry count");

        try (ZipFile oracle = new ZipFile(jar.toFile())) {
            assertEquals(count, oracle.size());
            assertArrayEquals(new byte[] {(byte) (count - 1)}, readAll(oracle, oracle.getEntry("e/" + (count - 1))));
        }
        try (ZipReader reader = ZipReader.open(jar)) {
            assertEquals(count, reader.entries().size());
            for (int i = 0; i < count; i += 1_000) {
                assertEquals(offsets.get(i).longValue(), reader.entries().get(i).dataOffset(), "entry " + i);
            }
            assertEquals(offsets.get(count - 1).longValue(), reader.entries().get(count - 1).dataOffset());
        }
    }

    private byte[] archiveOf(Instant timestamp) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipWriter writer = new ZipWriter(bytes, timestamp)) {
            writer.writeDirectoryEntry("org/");
            writer.writeEntry("org/a.txt", "a".getBytes(StandardCharsets.UTF_8));
            writer.writeEntry("org/b.txt", "b".getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static long crc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }
}
