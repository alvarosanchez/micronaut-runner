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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ZipReader}, which parses archives by hand so that entry data offsets are exact.
 *
 * <p>The archives under test are built with {@link ZipOutputStream}, so every assertion is against an
 * independent implementation, and the reported data offsets are cross-checked by seeking to them in the
 * file and comparing the bytes with what {@link ZipFile} returns for the same entry.</p>
 */
class ZipReaderTest {

    @TempDir
    Path temp;

    @Test
    void readsEveryFieldOfAMixedArchive() throws IOException {
        byte[] classBytes = repeat("class-bytes-", 300);
        byte[] storedBytes = "stored content".getBytes(StandardCharsets.UTF_8);
        Path jar = temp.resolve("mixed.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.setComment("an archive comment");
            deflated(zip, "META-INF/MANIFEST.MF", manifestBytes("Multi-Release", "true"));
            directory(zip, "org/");
            directory(zip, "org/example/");
            deflated(zip, "org/example/App.class", classBytes);
            stored(zip, "org/example/data.bin", storedBytes);
            deflated(zip, "org/example/caf\u00e9-\u65e5\u672c.txt", "unicode".getBytes(StandardCharsets.UTF_8));
            deflated(zip, "META-INF/versions/17/org/example/App.class", repeat("v17-", 200));
            deflated(zip, "META-INF/versions/21/org/example/App.class", repeat("v21-", 200));
        }

        try (ZipReader reader = ZipReader.open(jar); ZipFile oracle = new ZipFile(jar.toFile())) {
            assertEquals("an archive comment", reader.comment());
            assertEquals(Files.size(jar), reader.fileLength());
            assertEquals(jar, reader.path());
            assertEquals(names(oracle), reader.entries().stream().map(ZipEntryInfo::name).toList(),
                    "entries must be reported in central directory order");

            for (ZipEntryInfo entry : reader.entries()) {
                ZipEntry expected = oracle.getEntry(entry.name());
                assertEquals(expected.getSize(), entry.uncompressedSize(), entry.name());
                assertEquals(expected.getCompressedSize(), entry.compressedSize(), entry.name());
                assertEquals(expected.getCrc(), entry.crc32(), entry.name());
                assertEquals(expected.getMethod(), entry.method(), entry.name());
                assertEquals(expected.isDirectory(), entry.directory(), entry.name());
                byte[] content = readAll(oracle, expected);
                assertArrayEquals(content, reader.read(entry), entry.name());
                assertArrayEquals(content, contentAt(jar, entry), entry.name() + " at its reported data offset");
                assertArrayEquals(new byte[] {0x50, 0x4B, 0x03, 0x04}, bytesAt(jar, entry.localHeaderOffset(), 4),
                        entry.name() + " local file header");
            }

            ZipEntryInfo stored = reader.entry("org/example/data.bin").orElseThrow();
            assertEquals(IndexFormat.METHOD_STORED, stored.method());
            assertArrayEquals(storedBytes, reader.readRaw(stored), "a stored entry's raw bytes are its content");

            ZipEntryInfo deflated = reader.entry("org/example/App.class").orElseThrow();
            assertEquals(IndexFormat.METHOD_DEFLATED, deflated.method());
            assertNotEquals(deflated.uncompressedSize(), deflated.compressedSize());
            assertArrayEquals(classBytes, reader.read(deflated));

            assertTrue(reader.entry("META-INF/versions/17/org/example/App.class").isPresent(),
                    "multi-release entries are ordinary entries");
            assertTrue(reader.entry("org/").orElseThrow().directory());
            assertEquals(0, reader.entry("org/").orElseThrow().uncompressedSize());
            assertTrue(reader.entry("nope").isEmpty());

            Manifest manifest = reader.manifest().orElseThrow();
            assertEquals("true", manifest.getMainAttributes().getValue("Multi-Release"));
            assertFalse(reader.hasSignatureFiles());
        }
    }

    @Test
    void rejectsALocalNameThatDisagreesWithTheCentralDirectory() throws IOException {
        Path jar = temp.resolve("different-local-name.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            stored(zip, "safe.txt", "SAFE".getBytes(StandardCharsets.UTF_8));
        }
        byte[] archive = Files.readAllBytes(jar);
        System.arraycopy("../x.txt".getBytes(StandardCharsets.UTF_8), 0, archive, 30, 8);
        Files.write(jar, archive);

        try (ZipFile centralView = new ZipFile(jar.toFile());
             ZipInputStream localView = new ZipInputStream(Files.newInputStream(jar))) {
            assertEquals("safe.txt", centralView.entries().nextElement().getName());
            assertEquals("../x.txt", localView.getNextEntry().getName());
        }
        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(jar));
        assertTrue(failure.getMessage().contains("safe.txt"), failure.getMessage());
        assertTrue(failure.getMessage().contains("local"), failure.getMessage());
        assertTrue(failure.getMessage().contains("name"), failure.getMessage());
    }

    @ParameterizedTest(name = "rejects malformed UTF-8 in the {0} name")
    @ValueSource(booleans = {false, true})
    void rejectsMalformedUtf8EntryNames(boolean central) throws IOException {
        Path jar = temp.resolve("invalid-utf8-" + central + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            stored(zip, "safe.txt", new byte[] {'X'});
        }
        byte[] archive = Files.readAllBytes(jar);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int name = central ? intAt(archive, end + 16) + 46 : 30;
        archive[name] = (byte) 0xC0;
        Files.write(jar, archive);

        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(jar));
        assertTrue(failure.getMessage().contains("invalid UTF-8"), failure.getMessage());
        assertTrue(failure.getMessage().contains(central ? "central" : "local"), failure.getMessage());
    }

    @Test
    void rejectsAStoredEntryWhoseCompressedAndUncompressedSizesDisagree() throws IOException {
        Path jar = temp.resolve("different-stored-sizes.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            stored(zip, "bad.txt", new byte[] {'X'});
        }
        byte[] archive = Files.readAllBytes(jar);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int central = intAt(archive, end + 16);
        putInt(archive, 14, 0);
        putInt(archive, 22, 0);
        putInt(archive, central + 16, 0);
        putInt(archive, central + 24, 0);
        Files.write(jar, archive);

        try (ZipInputStream localView = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry local = localView.getNextEntry();
            assertEquals("bad.txt", local.getName());
            assertEquals(1, local.getCompressedSize());
            assertEquals(0, local.getSize());
        }
        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(jar));
        assertTrue(failure.getMessage().contains("bad.txt"), failure.getMessage());
        assertTrue(failure.getMessage().contains("STORED"), failure.getMessage());
        assertTrue(failure.getMessage().contains("size"), failure.getMessage());
    }

    @ParameterizedTest(name = "rejects local {2} disagreement")
    @CsvSource({
            "6, 0, flags",
            "8, 8, method",
            "14, 0, CRC",
            "18, 2, compressed-size",
            "22, 2, uncompressed-size"
    })
    void rejectsLocalHeaderFieldDisagreements(int offset, long value, String field) throws IOException {
        Path jar = temp.resolve("different-local-" + field + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            stored(zip, "safe.txt", new byte[] {'X'});
        }
        byte[] archive = Files.readAllBytes(jar);
        if (offset == 6 || offset == 8) {
            putShort(archive, offset, (int) value);
        } else {
            putInt(archive, offset, value);
        }
        Files.write(jar, archive);

        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(jar));
        assertTrue(failure.getMessage().contains("safe.txt"), failure.getMessage());
        assertTrue(failure.getMessage().contains("local"), failure.getMessage());
        assertTrue(failure.getMessage().contains(field), failure.getMessage());
    }

    @Test
    void rejectsUnsupportedGeneralPurposeFlags() throws IOException {
        Path jar = temp.resolve("encrypted.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            stored(zip, "secret.txt", new byte[] {'X'});
        }
        byte[] archive = Files.readAllBytes(jar);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int central = intAt(archive, end + 16);
        int flags = shortAt(archive, 6) | 1;
        putShort(archive, 6, flags);
        putShort(archive, central + 8, flags);
        Files.write(jar, archive);

        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(jar));
        assertTrue(failure.getMessage().contains("secret.txt"), failure.getMessage());
        assertTrue(failure.getMessage().contains("flags"), failure.getMessage());
    }

    @ParameterizedTest(name = "rejects multi-disk end field at {0}")
    @CsvSource({"4, 1", "6, 1", "8, 0"})
    void rejectsMultiDiskEndRecords(int offset, int value) throws IOException {
        Path jar = temp.resolve("multi-disk-end-" + offset + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            stored(zip, "one.txt", new byte[] {'1'});
        }
        byte[] archive = Files.readAllBytes(jar);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        putShort(archive, end + offset, value);
        Files.write(jar, archive);

        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(jar));
        assertTrue(failure.getMessage().contains("single-disk"), failure.getMessage());
    }

    @Test
    void rejectsAnEntryThatStartsOnAnotherDisk() throws IOException {
        Path jar = temp.resolve("multi-disk-entry.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            stored(zip, "one.txt", new byte[] {'1'});
        }
        byte[] archive = Files.readAllBytes(jar);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int central = intAt(archive, end + 16);
        putShort(archive, central + 34, 1);
        Files.write(jar, archive);

        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(jar));
        assertTrue(failure.getMessage().contains("one.txt"), failure.getMessage());
        assertTrue(failure.getMessage().contains("disk"), failure.getMessage());
    }

    @Test
    void readsEntriesWrittenWithADataDescriptor() throws IOException {
        byte[] content = repeat("descriptor-", 500);
        Path jar = temp.resolve("descriptor.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            deflated(zip, "a/B.class", content);
        }
        // ZipOutputStream writes deflated entries with the sizes in a trailing data descriptor: general
        // purpose bit 3 is set and the local header's size fields are zero.
        byte[] header = bytesAt(jar, 0, 30);
        assertEquals(8, header[6] & 0xFF, "general purpose bit 3 must be set for this fixture to be meaningful");
        assertEquals(0, intAt(header, 18), "the local header carries no compressed size");
        assertEquals(0, intAt(header, 22), "the local header carries no uncompressed size");

        try (ZipReader reader = ZipReader.open(jar)) {
            ZipEntryInfo entry = reader.entry("a/B.class").orElseThrow();
            assertEquals(content.length, entry.uncompressedSize(), "sizes come from the central directory");
            assertTrue(entry.compressedSize() > 0);
            assertArrayEquals(content, reader.read(entry));
            assertArrayEquals(content, contentAt(jar, entry));
        }
    }

    @Test
    void readsADataDescriptorEntryWhoseLocalHeaderAlsoCarriesFinalValues() throws IOException {
        byte[] content = repeat("descriptor-", 500);
        Path jar = temp.resolve("descriptor-with-local-values.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            deflated(zip, "data.txt", content);
        }
        byte[] archive = Files.readAllBytes(jar);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int central = intAt(archive, end + 16);
        System.arraycopy(archive, central + 16, archive, 14, 12);
        Files.write(jar, archive);

        try (ZipReader reader = ZipReader.open(jar)) {
            assertArrayEquals(content, reader.read(reader.entry("data.txt").orElseThrow()));
        }
    }

    @ParameterizedTest(name = "rejects non-placeholder local descriptor {1}")
    @CsvSource({
            "14, CRC",
            "18, compressed-size",
            "22, uncompressed-size"
    })
    void rejectsNonPlaceholderLocalDataDescriptorFields(int offset, String field) throws IOException {
        Path jar = temp.resolve("bad-local-descriptor-" + field + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            deflated(zip, "bad.txt", repeat("descriptor-", 20));
        }
        byte[] archive = Files.readAllBytes(jar);
        assertTrue((shortAt(archive, 6) & (1 << 3)) != 0, "fixture uses a data descriptor");
        putInt(archive, offset, 1);
        Files.write(jar, archive);

        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(jar));
        assertTrue(failure.getMessage().contains("bad.txt"), failure.getMessage());
        assertTrue(failure.getMessage().contains("local"), failure.getMessage());
        assertTrue(failure.getMessage().contains(field), failure.getMessage());
    }

    @ParameterizedTest(name = "rejects descriptor {1} disagreement")
    @CsvSource({
            "4, CRC",
            "8, compressed-size",
            "12, uncompressed-size"
    })
    void rejectsADataDescriptorThatDisagreesWithTheCentralDirectory(int offset, String field) throws IOException {
        Path jar = temp.resolve("different-descriptor-" + field + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            deflated(zip, "bad.txt", repeat("descriptor-", 20));
        }
        byte[] archive = Files.readAllBytes(jar);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int central = intAt(archive, end + 16);
        int descriptor = central - 16;
        assertEquals(0x08074B50, intAt(archive, descriptor), "fixture has a signed data descriptor");
        putInt(archive, descriptor + offset, (intAt(archive, descriptor + offset) & 0xFFFFFFFFL) + 1);
        Files.write(jar, archive);

        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(jar));
        assertTrue(failure.getMessage().contains("bad.txt"), failure.getMessage());
        assertTrue(failure.getMessage().contains("descriptor"), failure.getMessage());
    }

    @Test
    void readsAZip64EndOfCentralDirectoryRecord() throws IOException {
        // More than 65535 entries forces ZipOutputStream to write a ZIP64 end record and locator, and to
        // put the 0xFFFF marker in the 16-bit count of the ordinary end record.
        int count = 65_600;
        Path jar = temp.resolve("many.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            byte[] empty = new byte[0];
            for (int i = 0; i < count; i++) {
                stored(zip, "e/" + i, empty);
            }
        }
        try (ZipReader reader = ZipReader.open(jar)) {
            assertEquals(count, reader.entries().size());
            assertEquals("e/0", reader.entries().get(0).name());
            assertEquals("e/" + (count - 1), reader.entries().get(count - 1).name());
            ZipEntryInfo last = reader.entries().get(count - 1);
            assertArrayEquals(new byte[] {0x50, 0x4B, 0x03, 0x04}, bytesAt(jar, last.localHeaderOffset(), 4));
            assertEquals(last.localHeaderOffset() + 30 + "e/65599".length(), last.dataOffset());
        }
    }

    @ParameterizedTest(name = "rejects ZIP64 end record size {0}")
    @ValueSource(longs = {43, 45})
    void rejectsAZip64EndRecordWhoseDeclaredSizeDoesNotReachItsLocator(long recordSize) throws IOException {
        Path plain = temp.resolve("plain-zip64-end-" + recordSize + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(plain))) {
            stored(zip, "one.txt", new byte[] {'1'});
        }
        byte[] archive = withZip64EndRecord(Files.readAllBytes(plain));
        int zip64End = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE
                - IndexFormat.ZIP64_LOCATOR_SIZE - 56;
        putLong(archive, zip64End + 4, recordSize);
        Files.write(plain, archive);

        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(plain));
        assertTrue(failure.getMessage().contains("ZIP64"), failure.getMessage());
        assertTrue(failure.getMessage().contains("size"), failure.getMessage());
    }

    @Test
    void readsSizesAndOffsetsFromTheZip64ExtraField() throws IOException {
        Path plain = temp.resolve("plain.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(plain))) {
            stored(zip, "a/one.txt", "first".getBytes(StandardCharsets.UTF_8));
            deflated(zip, "a/two.txt", repeat("second-", 100));
        }
        List<ZipEntryInfo> expected;
        try (ZipReader reader = ZipReader.open(plain)) {
            expected = List.copyOf(reader.entries());
        }

        // Rewrite the central directory so that every size and offset carries the 0xFFFFFFFF marker and the
        // real value lives in a ZIP64 extended information extra field. The values do not change, so the
        // entries must come back exactly as before - through a different code path.
        Path patched = temp.resolve("zip64-extra.jar");
        Files.write(patched, withZip64Extras(Files.readAllBytes(plain)));

        try (ZipFile oracle = new ZipFile(patched.toFile())) {
            assertEquals(List.of("a/one.txt", "a/two.txt"), names(oracle), "the fixture must be a valid archive");
            assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), readAll(oracle, oracle.getEntry("a/one.txt")));
        }
        try (ZipReader reader = ZipReader.open(patched)) {
            assertEquals(expected, reader.entries());
            assertArrayEquals("first".getBytes(StandardCharsets.UTF_8),
                    reader.read(reader.entry("a/one.txt").orElseThrow()));
            assertArrayEquals(repeat("second-", 100), reader.read(reader.entry("a/two.txt").orElseThrow()));
        }
    }

    @Test
    void readsLocalAndCentralSizesFromZip64ExtraFields() throws IOException {
        Path plain = temp.resolve("plain-local-zip64.jar");
        byte[] content = "zip64-content".getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(plain))) {
            stored(zip, "safe.txt", content);
        }
        Path patched = temp.resolve("local-zip64.jar");
        Files.write(patched, withLocalAndCentralZip64Extras(Files.readAllBytes(plain)));

        try (ZipFile oracle = new ZipFile(patched.toFile())) {
            assertArrayEquals(content, readAll(oracle, oracle.getEntry("safe.txt")));
        }
        try (ZipReader reader = ZipReader.open(patched)) {
            ZipEntryInfo entry = reader.entry("safe.txt").orElseThrow();
            assertEquals(content.length, entry.compressedSize());
            assertEquals(content.length, entry.uncompressedSize());
            assertArrayEquals(content, reader.read(entry));
        }
    }

    @Test
    void rejectsAMarkedFieldWithNoZip64ExtraField() throws IOException {
        Path plain = temp.resolve("marked.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(plain))) {
            stored(zip, "a/one.txt", "first".getBytes(StandardCharsets.UTF_8));
        }
        byte[] archive = Files.readAllBytes(plain);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int central = intAt(archive, end + 16);
        putInt(archive, central + 20, 0xFFFFFFFFL);
        Path broken = temp.resolve("marked-broken.jar");
        Files.write(broken, archive);
        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(broken));
        assertTrue(failure.getMessage().contains("ZIP64"), failure.getMessage());
    }

    @Test
    void rejectsAnImpossibleEntryCountBeforeAllocatingTheEntryList() throws IOException {
        Path jar = temp.resolve("impossible-count.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            stored(zip, "one.txt", new byte[] {'1'});
        }
        byte[] archive = Files.readAllBytes(jar);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        putShort(archive, end + 8, 0xFFFF);
        putShort(archive, end + 10, 0xFFFF);
        Files.write(jar, archive);

        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(jar));
        assertTrue(failure.getMessage().contains("entry count"), failure.getMessage());
        assertTrue(failure.getMessage().contains("central directory"), failure.getMessage());
    }

    @Test
    void rejectsAnEntryCountThatLeavesAnUnclaimedCentralRecord() throws IOException {
        Path jar = temp.resolve("too-small-count.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            stored(zip, "one.txt", new byte[] {'1'});
            stored(zip, "two.txt", new byte[] {'2'});
        }
        byte[] archive = Files.readAllBytes(jar);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        putShort(archive, end + 8, 1);
        putShort(archive, end + 10, 1);
        Files.write(jar, archive);

        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(jar));
        assertTrue(failure.getMessage().contains("central directory"), failure.getMessage());
        assertTrue(failure.getMessage().contains("trailing"), failure.getMessage());
    }

    @Test
    void rejectsACentralDirectoryWhoseRecordedSizeReachesTheEndRecord() throws IOException {
        Path jar = temp.resolve("oversized-central-directory.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            stored(zip, "one.txt", new byte[] {'1'});
        }
        byte[] archive = Files.readAllBytes(jar);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        putInt(archive, end + 12, (intAt(archive, end + 12) & 0xFFFFFFFFL) + 1);
        Files.write(jar, archive);

        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(jar));
        assertTrue(failure.getMessage().contains("central directory"), failure.getMessage());
        assertTrue(failure.getMessage().contains("end"), failure.getMessage());
    }

    @Test
    void rejectsAnEntryWhoseDataOverlapsAnotherLocalHeader() throws IOException {
        Path jar = temp.resolve("overlapping-entry-data.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            stored(zip, "one.txt", new byte[] {'1'});
            stored(zip, "two.txt", new byte[] {'2'});
        }
        byte[] archive = Files.readAllBytes(jar);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int central = intAt(archive, end + 16);
        int secondCentral = central + 46 + shortAt(archive, central + 28)
                + shortAt(archive, central + 30) + shortAt(archive, central + 32);
        long secondLocal = intAt(archive, secondCentral + 42) & 0xFFFFFFFFL;
        long firstData = 30L + shortAt(archive, 26) + shortAt(archive, 28);
        long overlappingSize = secondLocal - firstData + 1;
        putInt(archive, 18, overlappingSize);
        putInt(archive, 22, overlappingSize);
        putInt(archive, central + 20, overlappingSize);
        putInt(archive, central + 24, overlappingSize);
        Files.write(jar, archive);

        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(jar));
        assertTrue(failure.getMessage().contains("one.txt"), failure.getMessage());
        assertTrue(failure.getMessage().contains("two.txt"), failure.getMessage());
        assertTrue(failure.getMessage().contains("overlap"), failure.getMessage());
    }

    @Test
    void acceptsRepeatedCentralRecordsThatReferenceTheSameLocalEntry() throws IOException {
        Path jar = temp.resolve("shared-local-entry.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            stored(zip, "shared.txt", "content".getBytes(StandardCharsets.UTF_8));
        }
        byte[] archive = Files.readAllBytes(jar);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int central = intAt(archive, end + 16);
        int directorySize = intAt(archive, end + 12);
        byte[] duplicated = new byte[archive.length + directorySize];
        System.arraycopy(archive, 0, duplicated, 0, end);
        System.arraycopy(archive, central, duplicated, end, directorySize);
        int newEnd = end + directorySize;
        System.arraycopy(archive, end, duplicated, newEnd, IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE);
        putShort(duplicated, newEnd + 8, 2);
        putShort(duplicated, newEnd + 10, 2);
        putInt(duplicated, newEnd + 12, directorySize * 2L);
        Files.write(jar, duplicated);

        try (ZipFile oracle = new ZipFile(jar.toFile())) {
            assertEquals(2, oracle.size(), "the JDK accepts repeated records for one local entry");
        }
        try (ZipReader reader = ZipReader.open(jar)) {
            assertEquals(2, reader.entries().size());
            assertEquals(reader.entries().get(0), reader.entries().get(1));
            assertArrayEquals("content".getBytes(StandardCharsets.UTF_8), reader.read(reader.entries().get(1)));
        }
    }

    @Test
    void readsAnArchiveWithNoEntries() throws IOException {
        Path jar = temp.resolve("empty.jar");
        new ZipOutputStream(Files.newOutputStream(jar)).close();
        try (ZipReader reader = ZipReader.open(jar)) {
            assertEquals(List.of(), reader.entries());
            assertTrue(reader.manifest().isEmpty());
            assertEquals("", reader.comment());
        }
    }

    @Test
    void detectsSignatureFiles() throws IOException {
        Path jar = temp.resolve("signed.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            deflated(zip, "META-INF/MANIFEST.MF", manifestBytes("Created-By", "test"));
            deflated(zip, "META-INF/my.sf", "signature".getBytes(StandardCharsets.UTF_8));
            deflated(zip, "META-INF/MY.RSA", new byte[] {1, 2, 3});
            deflated(zip, "a/B.class", new byte[] {4});
        }
        try (ZipReader reader = ZipReader.open(jar)) {
            assertTrue(reader.hasSignatureFiles());
        }

        assertTrue(ZipReader.isSignatureFile("META-INF/X.SF"));
        assertTrue(ZipReader.isSignatureFile("meta-inf/x.sf"));
        assertTrue(ZipReader.isSignatureFile("META-INF/X.DSA"));
        assertTrue(ZipReader.isSignatureFile("META-INF/X.rsa"));
        assertTrue(ZipReader.isSignatureFile("META-INF/x.ec"));
        assertTrue(ZipReader.isSignatureFile("META-INF/SIG-anything"));
        assertTrue(ZipReader.isSignatureFile("meta-inf/sig-anything"));
        assertFalse(ZipReader.isSignatureFile("META-INF/MANIFEST.MF"));
        assertFalse(ZipReader.isSignatureFile("META-INF/nested/x.SF"));
        assertFalse(ZipReader.isSignatureFile("a/B.SF"));
        assertFalse(ZipReader.isSignatureFile("META-INF/"));
        assertTrue(ZipReader.isIndexList("META-INF/INDEX.LIST"));
        assertTrue(ZipReader.isIndexList("meta-inf/index.list"));
        assertFalse(ZipReader.isIndexList("META-INF/INDEX.LIST/x"));
    }

    @Test
    void rejectsUnsafeEntryNames() throws IOException {
        assertUnsafe("../escape.txt");
        assertUnsafe("a/../../escape.txt");
        assertUnsafe("/absolute.txt");
        assertUnsafe("./relative.txt");
        assertUnsafe("a/./b.txt");
        assertUnsafe("a//b.txt");
        assertUnsafe("windows\\path.txt");
        assertUnsafe("nul\u0000name.txt");
    }

    @Test
    void acceptsOrdinaryEntryNames() {
        assertTrue(ZipReader.isSafeEntryName("a"));
        assertTrue(ZipReader.isSafeEntryName("a/b/C.class"));
        assertTrue(ZipReader.isSafeEntryName("a/b/"));
        assertTrue(ZipReader.isSafeEntryName("META-INF/versions/17/a/B.class"));
        assertTrue(ZipReader.isSafeEntryName("caf\u00e9/na\u00efve.txt"));
        assertTrue(ZipReader.isSafeEntryName("a..b/c.txt"));
        assertTrue(ZipReader.isSafeEntryName("...."));
        assertFalse(ZipReader.isSafeEntryName(""));
        assertFalse(ZipReader.isSafeEntryName("C:/windows"));
        assertFalse(ZipReader.isSafeEntryName("/"));
        assertFalse(ZipReader.isSafeEntryName(".."));
        assertFalse(ZipReader.isSafeEntryName("."));
    }

    @Test
    void rejectsSomethingThatIsNotAnArchive() throws IOException {
        Path notAJar = temp.resolve("not-a-jar.txt");
        Files.write(notAJar, "hello".getBytes(StandardCharsets.UTF_8));
        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(notAJar));
        assertTrue(failure.getMessage().contains("not-a-jar.txt"), failure.getMessage());
    }

    @Test
    void readingADeflatedEntryFailsWhenItsDataIsCorrupt() throws IOException {
        Path jar = temp.resolve("corrupt.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            deflated(zip, "a/B.class", repeat("corrupt-me-", 100));
        }
        ZipEntryInfo entry;
        try (ZipReader reader = ZipReader.open(jar)) {
            entry = reader.entry("a/B.class").orElseThrow();
        }
        byte[] all = Files.readAllBytes(jar);
        // A run of zero bytes decodes as a stored deflate block whose length fields contradict each other,
        // which the inflater always rejects.
        for (int i = 0; i < 16; i++) {
            all[(int) entry.dataOffset() + i] = 0;
        }
        Files.write(jar, all);
        try (ZipReader reader = ZipReader.open(jar)) {
            ZipEntryInfo corrupt = reader.entry("a/B.class").orElseThrow();
            assertThrows(IOException.class, () -> reader.read(corrupt));
        }
    }

    @Test
    void rejectsOversizedDeflatedOutputBeforeReadingItsCompressedInput() throws IOException {
        Path jar = temp.resolve("oversized-deflated-output.jar");
        try (ZipOutputStream ignored = new ZipOutputStream(Files.newOutputStream(jar))) {
            // An empty archive is enough: the synthetic entry exercises validation order before any read.
        }
        long uncompressedSize = Integer.MAX_VALUE - 7L;
        long compressedSize = 1;
        ZipEntryInfo oversized = new ZipEntryInfo("large.bin", IndexFormat.METHOD_DEFLATED,
                compressedSize, uncompressedSize, 0, 0, 0, Files.size(jar), false);

        try (ZipReader reader = ZipReader.open(jar)) {
            IOException failure = assertThrows(IOException.class, () -> reader.read(oversized));
            assertTrue(failure.getMessage().contains("too large to read into memory"), failure.getMessage());
        }
    }

    @Test
    void readingAStoredEntryRejectsPayloadThatDoesNotMatchItsRecordedCrc() throws IOException {
        byte[] recorded = "GOOD".getBytes(StandardCharsets.UTF_8);
        Path jar = temp.resolve("stored-crc-mismatch.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            stored(zip, "data.txt", recorded);
        }
        replaceEntryBytes(jar, "data.txt", recorded, "BOOD".getBytes(StandardCharsets.UTF_8));

        assertCrcFailure(jar, "data.txt");
    }

    @Test
    void readingADeflatedEntryRejectsPayloadThatDoesNotMatchItsRecordedCrc() throws IOException {
        byte[] recorded = "GOOD".getBytes(StandardCharsets.UTF_8);
        Path jar = temp.resolve("deflated-crc-mismatch.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            deflated(zip, "data.txt", recorded);
        }
        replaceEntryBytes(jar, "data.txt", recorded, "BOOD".getBytes(StandardCharsets.UTF_8));

        assertCrcFailure(jar, "data.txt");
    }

    @Test
    void readingAnEntryRejectsADeclaredCrcThatDoesNotMatchItsPayload() throws IOException {
        Path jar = temp.resolve("declared-crc-mismatch.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            stored(zip, "data.txt", "GOOD".getBytes(StandardCharsets.UTF_8));
        }
        byte[] archive = Files.readAllBytes(jar);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int central = intAt(archive, end + 16);
        long wrongCrc = (intAt(archive, 14) & 0xFFFFFFFFL) ^ 1;
        putInt(archive, 14, wrongCrc);
        putInt(archive, central + 16, wrongCrc);
        Files.write(jar, archive);

        assertCrcFailure(jar, "data.txt");
    }

    @Test
    void readingADeflatedEntryRejectsMissingTerminalBlock() throws IOException {
        Path jar = temp.resolve("unterminated.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            deflated(zip, "a/B.class", repeat("make-room-for-the-fixture-", 20));
        }
        ZipEntryInfo original;
        try (ZipReader reader = ZipReader.open(jar)) {
            original = reader.entry("a/B.class").orElseThrow();
        }
        byte[] all = Files.readAllBytes(jar);
        byte[] unterminated = new byte[] {0, 1, 0, (byte) 0xFE, (byte) 0xFF, 'x'};
        System.arraycopy(unterminated, 0, all, (int) original.dataOffset(), unterminated.length);
        int end = all.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int central = intAt(all, end + 16);
        putInt(all, central + 20, unterminated.length);
        putInt(all, central + 24, 1);
        int descriptor = (int) original.dataOffset() + unterminated.length;
        putInt(all, descriptor, 0x08074B50L);
        putInt(all, descriptor + 4, intAt(all, central + 16) & 0xFFFFFFFFL);
        putInt(all, descriptor + 8, unterminated.length);
        putInt(all, descriptor + 12, 1);
        Files.write(jar, all);

        try (ZipReader reader = ZipReader.open(jar)) {
            ZipEntryInfo entry = reader.entry("a/B.class").orElseThrow();
            assertThrows(IOException.class, () -> reader.read(entry));
        }
    }

    @Test
    void readingADeflatedEntryRejectsUnusedBytesInItsCompressedRegion() throws IOException {
        Path jar = deflatedWithTrailingByte(temp.resolve("trailing-compressed-byte.jar"),
                "data.txt", "ABCDEF".getBytes(StandardCharsets.UTF_8));

        try (ZipReader reader = ZipReader.open(jar)) {
            ZipEntryInfo entry = reader.entry("data.txt").orElseThrow();
            IOException failure = assertThrows(IOException.class, () -> reader.read(entry));
            assertTrue(failure.getMessage().contains("data.txt"), failure.getMessage());
            assertTrue(failure.getMessage().contains("compressed"), failure.getMessage());
        }
    }

    @ParameterizedTest(name = "rejects ABCDEF when the recorded content is ''{0}''")
    @ValueSource(strings = {"", "AB"})
    void readingADeflatedEntryRejectsOutputBeyondItsRecordedSize(String recorded) throws IOException {
        Path jar = deflatedWithRecordedContent(temp.resolve("overproduction-" + recorded.length() + ".jar"),
                "data.txt", "ABCDEF".getBytes(StandardCharsets.UTF_8), recorded.getBytes(StandardCharsets.UTF_8));

        try (ZipReader reader = ZipReader.open(jar)) {
            ZipEntryInfo entry = reader.entry("data.txt").orElseThrow();
            IOException failure = assertThrows(IOException.class, () -> reader.read(entry));
            assertTrue(failure.getMessage().contains("data.txt"), failure.getMessage());
            assertTrue(failure.getMessage().contains("produces more"), failure.getMessage());
        }
    }

    @Test
    void readingAValidEmptyDeflateStreamSucceeds() throws IOException {
        Path jar = temp.resolve("empty-deflate.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            deflated(zip, "empty.txt", new byte[0]);
        }

        try (ZipReader reader = ZipReader.open(jar)) {
            assertArrayEquals(new byte[0], reader.read(reader.entry("empty.txt").orElseThrow()));
        }
    }

    private void assertUnsafe(String name) throws IOException {
        assertFalse(ZipReader.isSafeEntryName(name), name);
        Path jar = temp.resolve("unsafe-" + Integer.toHexString(name.hashCode()) + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            stored(zip, name, new byte[] {1});
        }
        IOException failure = assertThrows(IOException.class, () -> ZipReader.open(jar), name);
        assertTrue(failure.getMessage().contains(jar.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains(name), failure.getMessage());
    }

    private static void replaceEntryBytes(Path jar, String name, byte[] expected, byte[] replacement)
            throws IOException {
        assertEquals(expected.length, replacement.length);
        ZipEntryInfo entry;
        try (ZipReader reader = ZipReader.open(jar)) {
            entry = reader.entry(name).orElseThrow();
        }
        byte[] archive = Files.readAllBytes(jar);
        int start = (int) entry.dataOffset();
        int end = start + (int) entry.compressedSize();
        int match = -1;
        for (int at = start; at <= end - expected.length; at++) {
            boolean equal = true;
            for (int i = 0; i < expected.length; i++) {
                equal &= archive[at + i] == expected[i];
            }
            if (equal) {
                match = at;
                break;
            }
        }
        assertTrue(match >= 0, "the entry's compressed region must contain the fixture payload");
        System.arraycopy(replacement, 0, archive, match, replacement.length);
        Files.write(jar, archive);
    }

    private static void assertCrcFailure(Path jar, String name) throws IOException {
        try (ZipReader reader = ZipReader.open(jar)) {
            IOException failure = assertThrows(IOException.class,
                    () -> reader.read(reader.entry(name).orElseThrow()));
            assertTrue(failure.getMessage().contains(jar.toString()), failure.getMessage());
            assertTrue(failure.getMessage().contains(name), failure.getMessage());
            assertTrue(failure.getMessage().contains("CRC-32"), failure.getMessage());
        }
    }

    static byte[] manifestBytes(String key, String value) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue(key, value);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        manifest.write(bytes);
        return bytes.toByteArray();
    }

    static byte[] repeat(String text, int times) {
        StringBuilder builder = new StringBuilder(text.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(text);
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    static void stored(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    static void deflated(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.DEFLATED);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    /** Makes an internally consistent archive whose DEFLATE stream expands beyond its recorded content. */
    static Path deflatedWithRecordedContent(Path jar, String name, byte[] actual, byte[] recorded) throws IOException {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            deflated(zip, name, actual);
        }
        byte[] archive = Files.readAllBytes(jar);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int central = intAt(archive, end + 16);
        int descriptor = central - 16;
        if (intAt(archive, descriptor) != 0x08074B50) {
            throw new IOException("Fixture entry has no signed data descriptor");
        }
        CRC32 crc = new CRC32();
        crc.update(recorded);
        putInt(archive, descriptor + 4, crc.getValue());
        putInt(archive, descriptor + 12, recorded.length);
        putInt(archive, central + 16, crc.getValue());
        putInt(archive, central + 24, recorded.length);
        Files.write(jar, archive);
        return jar;
    }

    /**
     * Makes a structurally consistent one-entry archive whose recorded compressed region contains one byte
     * after a complete raw DEFLATE stream. The data descriptor, central directory and end record all agree
     * about the enlarged region, so only the inflater can detect that the byte is not part of the stream.
     */
    static Path deflatedWithTrailingByte(Path jar, String name, byte[] data) throws IOException {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            deflated(zip, name, data);
        }
        byte[] archive = Files.readAllBytes(jar);
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int central = intAt(archive, end + 16);
        int descriptor = central - 16;
        if (intAt(archive, descriptor) != 0x08074B50) {
            throw new IOException("Fixture entry has no signed data descriptor");
        }

        byte[] patched = new byte[archive.length + 1];
        System.arraycopy(archive, 0, patched, 0, descriptor);
        patched[descriptor] = 0;
        System.arraycopy(archive, descriptor, patched, descriptor + 1, archive.length - descriptor);

        int patchedDescriptor = descriptor + 1;
        int patchedCentral = central + 1;
        int patchedEnd = end + 1;
        long compressedSize = intAt(archive, central + 20) & 0xFFFFFFFFL;
        putInt(patched, patchedDescriptor + 8, compressedSize + 1);
        putInt(patched, patchedCentral + 20, compressedSize + 1);
        putInt(patched, patchedEnd + 16, patchedCentral);
        Files.write(jar, patched);
        return jar;
    }

    static void directory(ZipOutputStream zip, String name) throws IOException {
        stored(zip, name, new byte[0]);
    }

    static List<String> names(ZipFile file) {
        List<String> names = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = file.entries();
        while (entries.hasMoreElements()) {
            names.add(entries.nextElement().getName());
        }
        return names;
    }

    static byte[] readAll(ZipFile file, ZipEntry entry) throws IOException {
        try (InputStream in = file.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    static byte[] bytesAt(Path file, long offset, int length) throws IOException {
        byte[] result = new byte[length];
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(offset);
            in.readNBytes(result, 0, length);
        }
        return result;
    }

    /**
     * Reads an entry's content straight from the file at the offset the reader reported, decompressing it
     * when needed, which is exactly what the launcher does with the index.
     */
    static byte[] contentAt(Path file, ZipEntryInfo entry) throws IOException {
        byte[] raw = bytesAt(file, entry.dataOffset(), (int) entry.compressedSize());
        if (entry.method() == IndexFormat.METHOD_STORED) {
            return raw;
        }
        byte[] result = new byte[(int) entry.uncompressedSize()];
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(raw);
            int total = 0;
            while (total < result.length) {
                int read = inflater.inflate(result, total, result.length - total);
                if (read == 0) {
                    break;
                }
                total += read;
            }
            if (total != result.length) {
                throw new IOException("Inflated " + total + " of " + result.length + " bytes");
            }
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
        return result;
    }

    /** Adds a minimal ZIP64 end record and locator to an ordinary archive. */
    static byte[] withZip64EndRecord(byte[] archive) {
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        long count = shortAt(archive, end + 10);
        long directorySize = intAt(archive, end + 12) & 0xFFFFFFFFL;
        long directoryOffset = intAt(archive, end + 16) & 0xFFFFFFFFL;
        byte[] result = new byte[archive.length + 56 + IndexFormat.ZIP64_LOCATOR_SIZE];
        System.arraycopy(archive, 0, result, 0, end);

        int zip64End = end;
        putInt(result, zip64End, IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE);
        putLong(result, zip64End + 4, 44);
        putShort(result, zip64End + 12, 45);
        putShort(result, zip64End + 14, 45);
        putLong(result, zip64End + 24, count);
        putLong(result, zip64End + 32, count);
        putLong(result, zip64End + 40, directorySize);
        putLong(result, zip64End + 48, directoryOffset);

        int locator = zip64End + 56;
        putInt(result, locator, IndexFormat.ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE);
        putLong(result, locator + 8, zip64End);
        putInt(result, locator + 16, 1);
        System.arraycopy(archive, end, result, locator + IndexFormat.ZIP64_LOCATOR_SIZE,
                IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE);
        return result;
    }

    /**
     * Rewrites an archive's central directory so that every record's sizes and local header offset carry the
     * ZIP64 marker and their real values sit in a ZIP64 extended information extra field.
     */
    static byte[] withZip64Extras(byte[] archive) {
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int count = shortAt(archive, end + 10);
        int central = intAt(archive, end + 16);
        ByteArrayOutputStream directory = new ByteArrayOutputStream();
        int at = central;
        for (int i = 0; i < count; i++) {
            int nameLength = shortAt(archive, at + 28);
            int extraLength = shortAt(archive, at + 30);
            int commentLength = shortAt(archive, at + 32);
            byte[] header = new byte[46];
            System.arraycopy(archive, at, header, 0, 46);
            putShort(header, 6, 45);
            long compressed = intAt(header, 20) & 0xFFFFFFFFL;
            long uncompressed = intAt(header, 24) & 0xFFFFFFFFL;
            long localHeader = intAt(header, 42) & 0xFFFFFFFFL;
            putInt(header, 20, 0xFFFFFFFFL);
            putInt(header, 24, 0xFFFFFFFFL);
            putInt(header, 42, 0xFFFFFFFFL);
            putShort(header, 30, 28);
            directory.write(header, 0, header.length);
            directory.write(archive, at + 46, nameLength);
            byte[] extra = new byte[28];
            putShort(extra, 0, IndexFormat.ZIP64_EXTRA_FIELD_ID);
            putShort(extra, 2, 24);
            putLong(extra, 4, uncompressed);
            putLong(extra, 12, compressed);
            putLong(extra, 20, localHeader);
            directory.write(extra, 0, extra.length);
            at += 46 + nameLength + extraLength + commentLength;
        }
        byte[] newDirectory = directory.toByteArray();
        byte[] newEnd = new byte[IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE];
        System.arraycopy(archive, end, newEnd, 0, newEnd.length);
        putInt(newEnd, 12, newDirectory.length);
        putInt(newEnd, 16, central);
        byte[] result = new byte[central + newDirectory.length + newEnd.length];
        System.arraycopy(archive, 0, result, 0, central);
        System.arraycopy(newDirectory, 0, result, central, newDirectory.length);
        System.arraycopy(newEnd, 0, result, central + newDirectory.length, newEnd.length);
        return result;
    }

    /** Adds ZIP64 size fields to both headers of a one-entry, non-descriptor archive. */
    static byte[] withLocalAndCentralZip64Extras(byte[] archive) {
        int end = archive.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int central = intAt(archive, end + 16);
        int nameLength = shortAt(archive, 26);
        int localExtraLength = shortAt(archive, 28);
        long compressed = intAt(archive, 18) & 0xFFFFFFFFL;
        long uncompressed = intAt(archive, 22) & 0xFFFFFFFFL;
        int dataStart = 30 + nameLength + localExtraLength;

        ByteArrayOutputStream local = new ByteArrayOutputStream();
        byte[] localHeader = new byte[30];
        System.arraycopy(archive, 0, localHeader, 0, localHeader.length);
        putShort(localHeader, 4, 45);
        putInt(localHeader, 18, 0xFFFFFFFFL);
        putInt(localHeader, 22, 0xFFFFFFFFL);
        putShort(localHeader, 28, 20);
        local.write(localHeader, 0, localHeader.length);
        local.write(archive, 30, nameLength);
        byte[] localExtra = new byte[20];
        putShort(localExtra, 0, IndexFormat.ZIP64_EXTRA_FIELD_ID);
        putShort(localExtra, 2, 16);
        putLong(localExtra, 4, uncompressed);
        putLong(localExtra, 12, compressed);
        local.write(localExtra, 0, localExtra.length);
        local.write(archive, dataStart, central - dataStart);

        int centralNameLength = shortAt(archive, central + 28);
        int centralCommentLength = shortAt(archive, central + 32);
        byte[] centralHeader = new byte[46];
        System.arraycopy(archive, central, centralHeader, 0, centralHeader.length);
        putShort(centralHeader, 6, 45);
        putInt(centralHeader, 20, 0xFFFFFFFFL);
        putInt(centralHeader, 24, 0xFFFFFFFFL);
        putShort(centralHeader, 30, 32);
        putShort(centralHeader, 34, 0xFFFF);
        putInt(centralHeader, 42, 0xFFFFFFFFL);
        ByteArrayOutputStream directory = new ByteArrayOutputStream();
        directory.write(centralHeader, 0, centralHeader.length);
        directory.write(archive, central + 46, centralNameLength);
        byte[] centralExtra = new byte[32];
        putShort(centralExtra, 0, IndexFormat.ZIP64_EXTRA_FIELD_ID);
        putShort(centralExtra, 2, 28);
        putLong(centralExtra, 4, uncompressed);
        putLong(centralExtra, 12, compressed);
        putLong(centralExtra, 20, 0);
        putInt(centralExtra, 28, 0);
        directory.write(centralExtra, 0, centralExtra.length);
        directory.write(archive, central + 46 + centralNameLength
                + shortAt(archive, central + 30), centralCommentLength);

        byte[] localBytes = local.toByteArray();
        byte[] directoryBytes = directory.toByteArray();
        byte[] newEnd = new byte[IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE];
        System.arraycopy(archive, end, newEnd, 0, newEnd.length);
        putInt(newEnd, 12, directoryBytes.length);
        putInt(newEnd, 16, localBytes.length);
        byte[] result = new byte[localBytes.length + directoryBytes.length + newEnd.length];
        System.arraycopy(localBytes, 0, result, 0, localBytes.length);
        System.arraycopy(directoryBytes, 0, result, localBytes.length, directoryBytes.length);
        System.arraycopy(newEnd, 0, result, localBytes.length + directoryBytes.length, newEnd.length);
        return result;
    }

    static int shortAt(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF) | ((buffer[offset + 1] & 0xFF) << 8);
    }

    @Test
    void readsAnEntryCountMarkerThatNoZip64RecordExplains() throws IOException {
        // What a writer that compared the entry count with > rather than >= left behind: exactly 65535
        // entries, the marker in the 16-bit count field, and no ZIP64 end record or locator. It is a
        // perfectly good archive to java.util.zip, which falls back to the 32-bit fields, and refusing it
        // would mean refusing a jar every other tool on the machine accepts.
        int count = 0xFFFF;
        Path full = temp.resolve("marked.jar");
        try (ZipWriter writer = ZipWriter.create(full, ZipWriter.DEFAULT_TIMESTAMP)) {
            for (int i = 0; i < count; i++) {
                writer.writeEntry("e/" + i, new byte[] {(byte) i});
            }
        }
        byte[] bytes = Files.readAllBytes(full);
        int endOffset = bytes.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int zip64EndOffset = endOffset - IndexFormat.ZIP64_LOCATOR_SIZE - 56;
        byte[] legacy = new byte[zip64EndOffset + IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE];
        System.arraycopy(bytes, 0, legacy, 0, zip64EndOffset);
        System.arraycopy(bytes, endOffset, legacy, zip64EndOffset,
                IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE);
        Path jar = temp.resolve("legacy.jar");
        Files.write(jar, legacy);

        try (ZipFile oracle = new ZipFile(jar.toFile())) {
            assertEquals(count, oracle.size(), "the JDK reads it, so this reader has to as well");
        }
        try (ZipReader reader = ZipReader.open(jar)) {
            assertEquals(count, reader.entries().size());
            assertArrayEquals(new byte[] {0}, reader.read(reader.entry("e/0").orElseThrow()));
            assertArrayEquals(new byte[] {(byte) (count - 1)},
                    reader.read(reader.entry("e/" + (count - 1)).orElseThrow()));
        }
    }

    static void putShort(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >>> 8);
    }

    static void putInt(byte[] buffer, int offset, long value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >>> 8);
        buffer[offset + 2] = (byte) (value >>> 16);
        buffer[offset + 3] = (byte) (value >>> 24);
    }

    static void putLong(byte[] buffer, int offset, long value) {
        putInt(buffer, offset, value);
        putInt(buffer, offset + 4, value >>> 32);
    }

    static int intAt(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF)
                | ((buffer[offset + 1] & 0xFF) << 8)
                | ((buffer[offset + 2] & 0xFF) << 16)
                | ((buffer[offset + 3] & 0xFF) << 24);
    }
}
