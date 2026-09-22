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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static io.micronaut.runner.build.ZipReaderTest.bytesAt;
import static io.micronaut.runner.build.ZipReaderTest.deflated;
import static io.micronaut.runner.build.ZipReaderTest.deflatedWithRecordedContent;
import static io.micronaut.runner.build.ZipReaderTest.deflatedWithTrailingByte;
import static io.micronaut.runner.build.ZipReaderTest.directory;
import static io.micronaut.runner.build.ZipReaderTest.intAt;
import static io.micronaut.runner.build.ZipReaderTest.manifestBytes;
import static io.micronaut.runner.build.ZipReaderTest.names;
import static io.micronaut.runner.build.ZipReaderTest.readAll;
import static io.micronaut.runner.build.ZipReaderTest.repeat;
import static io.micronaut.runner.build.ZipReaderTest.stored;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ZipRepacker}, which turns a dependency jar into an all-stored nested jar and reports
 * where every entry's data landed relative to the nested jar's first byte.
 */
class ZipRepackerTest {

    @TempDir
    Path temp;

    @Test
    void repacksEveryEntryAsStoredWithoutChangingWhatItContains() throws IOException {
        Path source = multiReleaseJar("source.jar");
        byte[] repacked;
        ZipRepacker.RepackResult result;
        try (ZipReader reader = ZipReader.open(source)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            result = ZipRepacker.repack(reader, bytes);
            repacked = bytes.toByteArray();

            assertEquals(repacked.length, result.length());
            assertFalse(result.hadSignatureFiles());
            assertEquals(List.of(), result.droppedEntries());
            assertEquals(reader.entries().stream().map(ZipEntryInfo::name).toList(),
                    result.entries().stream().map(ZipEntryInfo::name).toList(),
                    "names and their central directory order are preserved");

            for (ZipEntryInfo entry : result.entries()) {
                ZipEntryInfo original = reader.entry(entry.name()).orElseThrow();
                assertEquals(IndexFormat.METHOD_STORED, entry.method(), entry.name());
                assertEquals(original.uncompressedSize(), entry.uncompressedSize(), entry.name());
                assertEquals(entry.uncompressedSize(), entry.compressedSize(), entry.name());
                assertEquals(original.crc32(), entry.crc32(), entry.name() + " CRC-32");
                assertEquals(original.dosTime(), entry.dosTime(), entry.name() + " MS-DOS time");
                assertEquals(original.directory(), entry.directory(), entry.name());
                assertArrayEquals(reader.read(original), slice(repacked, entry),
                        entry.name() + " at its reported relative offset");
                byte[] header = slice(repacked, entry.localHeaderOffset(), 30);
                assertEquals(IndexFormat.LOCAL_HEADER_SIGNATURE, intAt(header, 0), entry.name());
                assertEquals(0, header[6] & 0x08, entry.name() + " must not use a data descriptor");
                assertEquals(entry.uncompressedSize(), intAt(header, 18) & 0xFFFFFFFFL,
                        entry.name() + " local header carries the compressed size");
                assertEquals(entry.uncompressedSize(), intAt(header, 22) & 0xFFFFFFFFL,
                        entry.name() + " local header carries the uncompressed size");
                assertEquals(0, intAt(header, 26) >>> 16, entry.name() + " must have no extra field");
            }
        }

        Path nested = temp.resolve("nested.jar");
        Files.write(nested, repacked);
        try (ZipFile oracle = new ZipFile(nested.toFile()); ZipFile original = new ZipFile(source.toFile())) {
            assertEquals(names(original), names(oracle));
            for (String name : names(oracle)) {
                assertEquals(ZipEntry.STORED, oracle.getEntry(name).getMethod(), name);
                assertArrayEquals(readAll(original, original.getEntry(name)), readAll(oracle, oracle.getEntry(name)),
                        name);
            }
            assertTrue(oracle.getEntry("org/").isDirectory(), "directory entries are preserved");
            assertNotNull(oracle.getEntry("META-INF/versions/17/org/example/App.class"),
                    "multi-release entries are preserved");
        }
        try (ZipReader reader = ZipReader.open(nested)) {
            assertEquals(result.entries(), reader.entries(),
                    "the reported entries must describe the nested jar exactly");
        }
    }

    @Test
    void keepsTheManifestVerbatim() throws IOException {
        Path source = multiReleaseJar("manifest.jar");
        byte[] expected;
        byte[] repacked;
        try (ZipReader reader = ZipReader.open(source)) {
            expected = reader.read(reader.entry("META-INF/MANIFEST.MF").orElseThrow());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ZipRepacker.repack(reader, bytes);
            repacked = bytes.toByteArray();
        }
        Path nested = temp.resolve("manifest-nested.jar");
        Files.write(nested, repacked);
        try (ZipReader reader = ZipReader.open(nested)) {
            ZipEntryInfo entry = reader.entry("META-INF/MANIFEST.MF").orElseThrow();
            assertEquals(0, entry.localHeaderOffset(), "the manifest stays the first entry");
            assertArrayEquals(expected, reader.read(entry));
            assertEquals("true", reader.manifest().orElseThrow().getMainAttributes().getValue("Multi-Release"));
        }
    }

    @Test
    void refusesToRepackAnEntryWithUnusedBytesInItsCompressedRegion() throws IOException {
        Path source = deflatedWithTrailingByte(temp.resolve("trailing-compressed-byte.jar"),
                "data.txt", "ABCDEF".getBytes(StandardCharsets.UTF_8));

        try (ZipReader reader = ZipReader.open(source)) {
            IOException failure = assertThrows(IOException.class,
                    () -> ZipRepacker.repack(reader, new ByteArrayOutputStream()));
            assertTrue(failure.getMessage().contains("data.txt"), failure.getMessage());
            assertTrue(failure.getMessage().contains("compressed"), failure.getMessage());
        }
    }

    @Test
    void refusesToRepackABCDEFRecordedAsAB() throws IOException {
        Path source = deflatedWithRecordedContent(temp.resolve("overproduction.jar"), "data.txt",
                "ABCDEF".getBytes(StandardCharsets.UTF_8), "AB".getBytes(StandardCharsets.UTF_8));

        try (ZipReader reader = ZipReader.open(source)) {
            IOException failure = assertThrows(IOException.class,
                    () -> ZipRepacker.repack(reader, new ByteArrayOutputStream()));
            assertTrue(failure.getMessage().contains("data.txt"), failure.getMessage());
            assertTrue(failure.getMessage().contains("produces more"), failure.getMessage());
        }
    }

    @Test
    void dropsSignatureFilesAndTheJarIndex() throws IOException {
        Path source = temp.resolve("signed.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
            deflated(zip, "META-INF/MANIFEST.MF", manifestBytes("Created-By", "test"));
            deflated(zip, "META-INF/MY.SF", "signature file".getBytes(StandardCharsets.UTF_8));
            deflated(zip, "META-INF/MY.RSA", new byte[] {1, 2, 3});
            deflated(zip, "META-INF/sig-extra", new byte[] {4});
            deflated(zip, "META-INF/INDEX.LIST", "JarIndex-Version: 1".getBytes(StandardCharsets.UTF_8));
            deflated(zip, "META-INF/services/org.example.Service", "org.example.Impl"
                    .getBytes(StandardCharsets.UTF_8));
            deflated(zip, "org/example/App.class", repeat("class-", 50));
        }
        Path nested = temp.resolve("signed-nested.jar");
        ZipRepacker.RepackResult result = ZipRepacker.repack(source, nested);

        assertTrue(result.hadSignatureFiles(), "the packager records JAR_FLAG_SIGNED_ORIGINAL from this");
        assertEquals(List.of("META-INF/MY.SF", "META-INF/MY.RSA", "META-INF/sig-extra", "META-INF/INDEX.LIST"),
                result.droppedEntries());
        assertEquals(List.of("META-INF/MANIFEST.MF", "META-INF/services/org.example.Service",
                "org/example/App.class"), result.entries().stream().map(ZipEntryInfo::name).toList());
        assertEquals(Files.size(nested), result.length());
        try (ZipFile oracle = new ZipFile(nested.toFile())) {
            assertEquals(List.of("META-INF/MANIFEST.MF", "META-INF/services/org.example.Service",
                    "org/example/App.class"), names(oracle));
        }
    }

    @Test
    void copiesAJarByteForByteInPreserveMode() throws IOException {
        Path source = multiReleaseJar("preserve.jar");
        byte[] sourceBytes = Files.readAllBytes(source);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipRepacker.RepackResult streamed;
        List<ZipEntryInfo> expected;
        try (ZipReader reader = ZipReader.open(source)) {
            expected = new ArrayList<>(reader.entries());
            streamed = ZipRepacker.copy(reader, bytes);
        }
        assertArrayEquals(sourceBytes, bytes.toByteArray(), "PRESERVE copies the jar byte for byte");
        assertEquals(sourceBytes.length, streamed.length());
        assertEquals(expected, streamed.entries(), "the offsets of the source are already relative offsets");
        assertEquals(List.of(), streamed.droppedEntries());

        Path target = temp.resolve("preserved.jar");
        ZipRepacker.RepackResult copied = ZipRepacker.copy(source, target);
        assertArrayEquals(sourceBytes, Files.readAllBytes(target));
        assertEquals(expected, copied.entries());
        // The entries keep their original compression, so a deflated entry is still deflated.
        ZipEntryInfo appClass = copied.entries().stream()
                .filter(e -> e.name().equals("org/example/App.class")).findFirst().orElseThrow();
        assertEquals(IndexFormat.METHOD_DEFLATED, appClass.method());
        assertArrayEquals(bytesAt(source, appClass.dataOffset(), (int) appClass.compressedSize()),
                bytesAt(target, appClass.dataOffset(), (int) appClass.compressedSize()));
    }

    @Test
    void reportsOffsetsThatAreCorrectOnceTheNestedJarIsInsideTheOuterArchive() throws IOException {
        Path source = multiReleaseJar("dependency.jar");
        Path nested = temp.resolve("dependency-stored.jar");
        ZipRepacker.RepackResult result = ZipRepacker.repack(source, nested);

        Path outer = temp.resolve("application.jar");
        long nestedOffset;
        try (ZipWriter writer = ZipWriter.create(outer, ZipWriter.DEFAULT_TIMESTAMP)) {
            writer.writeEntry("META-INF/MANIFEST.MF", manifestBytes("Main-Class", IndexFormat.LAUNCHER_CLASS));
            writer.writeEntry("MICRONAUT-INF/classes/org/example/Main.class", repeat("main-", 20));
            nestedOffset = writer.writeEntry("MICRONAUT-INF/lib/dependency.jar", nested);
        }

        try (ZipReader reader = ZipReader.open(source)) {
            for (ZipEntryInfo entry : result.entries()) {
                ZipEntryInfo absolute = entry.shift(nestedOffset);
                assertEquals(entry.dataOffset() + nestedOffset, absolute.dataOffset());
                assertArrayEquals(reader.read(reader.entry(entry.name()).orElseThrow()),
                        bytesAt(outer, absolute.dataOffset(), (int) absolute.compressedSize()),
                        entry.name() + " read straight out of the outer archive");
            }
        }
    }

    private Path multiReleaseJar(String fileName) throws IOException {
        Path jar = temp.resolve(fileName);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.setComment("a dependency");
            deflated(zip, "META-INF/MANIFEST.MF", manifestBytes("Multi-Release", "true"));
            directory(zip, "org/");
            directory(zip, "org/example/");
            deflated(zip, "org/example/App.class", repeat("class-bytes-", 200));
            stored(zip, "org/example/data.bin", "already stored".getBytes(StandardCharsets.UTF_8));
            deflated(zip, "org/example/empty.txt", new byte[0]);
            deflated(zip, "org/example/caf\u00e9-\u65e5\u672c.txt", "unicode".getBytes(StandardCharsets.UTF_8));
            directory(zip, "META-INF/versions/");
            deflated(zip, "META-INF/versions/17/org/example/App.class", repeat("v17-", 100));
            deflated(zip, "META-INF/versions/21/org/example/App.class", repeat("v21-", 100));
        }
        return jar;
    }

    private static byte[] slice(byte[] archive, ZipEntryInfo entry) {
        return slice(archive, entry.dataOffset(), (int) entry.compressedSize());
    }

    private static byte[] slice(byte[] archive, long offset, int length) {
        byte[] result = new byte[length];
        System.arraycopy(archive, (int) offset, result, 0, length);
        return result;
    }
}
