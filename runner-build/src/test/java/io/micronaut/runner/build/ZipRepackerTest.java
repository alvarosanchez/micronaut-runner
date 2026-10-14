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
import io.micronaut.runner.build.ZipReaderTest.Payload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
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
import static io.micronaut.runner.build.ZipReaderTest.rawDeflate;
import static io.micronaut.runner.build.ZipReaderTest.rawDeflatedArchive;
import static io.micronaut.runner.build.ZipReaderTest.readAll;
import static io.micronaut.runner.build.ZipReaderTest.repeat;
import static io.micronaut.runner.build.ZipReaderTest.stored;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @ParameterizedTest(name = "{0}, {1} bytes")
    @MethodSource("io.micronaut.runner.build.ZipReaderTest#transferBoundaryEntries")
    void repacksADeflatedEntryWhateverItsSizeIsNextToTheTransferBuffer(Payload payload, int size)
            throws IOException {
        // The inflater can have consumed all of an entry's compressed bytes and still owe content that did
        // not fit the buffer it was given. That is not a truncated stream, and the rest has to be drained.
        byte[] content = payload.bytes(size);
        Path source = temp.resolve("boundary.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
            deflated(zip, "data.bin", content);
        }
        Path nested = temp.resolve("boundary-nested.jar");

        ZipRepacker.RepackResult result = ZipRepacker.repack(source, nested);

        assertEquals(1, result.entries().size());
        ZipEntryInfo entry = result.entries().get(0);
        assertEquals("data.bin", entry.name());
        assertEquals(IndexFormat.METHOD_STORED, entry.method());
        assertEquals(size, entry.uncompressedSize());
        assertArrayEquals(content, bytesAt(nested, entry.dataOffset(), size),
                "the content at the offset the repack reported");
        try (ZipFile oracle = new ZipFile(nested.toFile())) {
            assertArrayEquals(content, readAll(oracle, oracle.getEntry("data.bin")));
        }
        try (ZipReader reader = ZipReader.open(source)) {
            assertArrayEquals(content, reader.read(reader.entry("data.bin").orElseThrow()),
                    "reading the entry into an array of its size agrees with streaming it");
        }
    }

    @Test
    void repacksEveryEntrySizeJustPastATransferBuffer() throws IOException {
        // Which sizes leave content behind in the inflater depends on where the deflater happened to cut its
        // matches, not on the size alone. So this goes through every size up to 300 bytes past one buffer,
        // which is further than the longest match reaches.
        int buffer = ZipReader.TRANSFER_BUFFER_SIZE;
        Payload[] payloads = {Payload.ZEROS, Payload.TEXT};
        Path source = temp.resolve("sweep.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
            for (Payload payload : payloads) {
                for (int extra = 1; extra <= 300; extra++) {
                    deflated(zip, payload + "/" + extra + ".bin", payload.bytes(buffer + extra));
                }
            }
        }
        Path nested = temp.resolve("sweep-nested.jar");

        ZipRepacker.RepackResult result = ZipRepacker.repack(source, nested);

        assertEquals(payloads.length * 300, result.entries().size());
        try (ZipFile oracle = new ZipFile(nested.toFile())) {
            for (Payload payload : payloads) {
                for (int extra = 1; extra <= 300; extra++) {
                    String name = payload + "/" + extra + ".bin";
                    assertArrayEquals(payload.bytes(buffer + extra), readAll(oracle, oracle.getEntry(name)), name);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}, {1} bytes")
    @MethodSource("io.micronaut.runner.build.ZipReaderTest#transferBoundaryEntries")
    void refusesATruncatedDeflateStreamWhateverItsSizeIsNextToTheTransferBuffer(Payload payload, int size)
            throws IOException {
        byte[] content = payload.bytes(size);
        byte[] compressed = rawDeflate(content);
        // The fixture itself is sound: with the whole stream the archive repacks to the content.
        Path complete = rawDeflatedArchive(temp.resolve("complete.jar"), "data.bin", compressed, content);
        Path nested = temp.resolve("complete-nested.jar");
        ZipRepacker.RepackResult result = ZipRepacker.repack(complete, nested);
        assertArrayEquals(content, bytesAt(nested, result.entries().get(0).dataOffset(), size));

        // Without its last byte the stream yields most or all of the content and then cannot end; cut in
        // half it yields only part of the content. Either way every record of the archive agrees about the
        // shorter data, so the inflater is the only one that can tell.
        for (int kept : new int[] {compressed.length - 1, compressed.length / 2}) {
            Path truncated = rawDeflatedArchive(temp.resolve("truncated-" + kept + ".jar"), "data.bin",
                    Arrays.copyOf(compressed, kept), content);
            String expected = "Truncated deflate stream for entry 'data.bin' of " + truncated + ": expected "
                    + size + " bytes, inflated ";

            IOException streamed = assertThrows(IOException.class,
                    () -> ZipRepacker.repack(truncated, temp.resolve("truncated-nested.jar")),
                    () -> kept + " of " + compressed.length + " compressed bytes");
            assertTrue(streamed.getMessage().startsWith(expected), streamed.getMessage());
            try (ZipReader reader = ZipReader.open(truncated)) {
                ZipEntryInfo entry = reader.entry("data.bin").orElseThrow();
                IOException read = assertThrows(IOException.class, () -> reader.read(entry),
                        () -> kept + " of " + compressed.length + " compressed bytes");
                assertTrue(read.getMessage().startsWith(expected), read.getMessage());
                // Both paths take everything the inflater can still give before they conclude, so they
                // count the same content.
                assertEquals(read.getMessage(), streamed.getMessage());
            }
        }
    }

    /** Zero bytes and text, recorded as exactly one transfer buffer and as one byte more. */
    static Stream<Arguments> overproducedEntries() {
        int buffer = ZipReader.TRANSFER_BUFFER_SIZE;
        return Stream.of(Payload.ZEROS, Payload.TEXT).flatMap(payload ->
                Stream.of(Arguments.of(payload, buffer), Arguments.of(payload, buffer + 1)));
    }

    @ParameterizedTest(name = "{0}, recorded as {1} bytes")
    @MethodSource("overproducedEntries")
    void refusesADeflateStreamThatHoldsMoreThanItsRecordedSizeNextToTheTransferBuffer(Payload payload, int recorded)
            throws IOException {
        // A stream with more content than the archive records can reach the recorded size with all of its
        // compressed bytes consumed and content still held by the inflater. That is no truncation: what is
        // left is content the archive does not account for, and both ways of reading the entry have to take
        // it before they conclude, streaming at the end of a buffer and reading at the end of the array of
        // the recorded size. Where the deflater cut its matches decides which streams get there, so this
        // goes through every excess up to 299 bytes.
        byte[] content = payload.bytes(recorded);
        for (int more = 1; more < 300; more++) {
            int excess = more;
            Path source = rawDeflatedArchive(temp.resolve("overproduction.jar"), "data.bin",
                    rawDeflate(payload.bytes(recorded + excess)), content);
            String expected = "Deflate stream for entry 'data.bin' of " + source
                    + " produces more than the recorded " + recorded + " bytes";

            IOException streamed = assertThrows(IOException.class,
                    () -> ZipRepacker.repack(source, temp.resolve("overproduction-nested.jar")),
                    () -> "a stream of " + excess + " bytes more");
            assertEquals(expected, streamed.getMessage(), () -> "streaming " + excess + " bytes more");
            try (ZipReader reader = ZipReader.open(source)) {
                ZipEntryInfo entry = reader.entry("data.bin").orElseThrow();
                IOException read = assertThrows(IOException.class, () -> reader.read(entry),
                        () -> "a stream of " + excess + " bytes more");
                assertEquals(expected, read.getMessage(), () -> "reading " + excess + " bytes more");
            }
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

    /**
     * The SHA-256 of {@link #parentCommitFixture(Path)} repacked by the public methods, as the parent commit of
     * the class transform pipeline repacked it: the public methods run no transform and must keep their output.
     */
    private static final String PARENT_COMMIT_REPACK_SHA256 =
            "814b96aa2eba3fb5a6106c5c824dd4fcb5c3b052a6054bda65ec4f0a86b9005c";

    @Test
    void thePublicRepackMethodsWriteTheBytesTheyWroteBeforeClassTransforms() throws Exception {
        Path source = parentCommitFixture(temp.resolve("parent-commit.jar"));

        ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        try (ZipReader reader = ZipReader.open(source)) {
            ZipRepacker.repack(reader, streamed);
        }
        Path target = temp.resolve("parent-commit-repacked.jar");
        ZipRepacker.repack(source, target);

        assertEquals(PARENT_COMMIT_REPACK_SHA256, sha256(streamed.toByteArray()));
        assertEquals(PARENT_COMMIT_REPACK_SHA256, sha256(Files.readAllBytes(target)));
    }

    @Test
    void desugaredHostsAndTheirGeneratedClassesCarryTheCrcOfTheirBytesAndStayTogether() throws Exception {
        List<LambdaFixtures.Layer> layers = LambdaFixtures.scenario(temp.resolve("desugared"), 25);
        // The nest host last: its members come before it, so the engine has to plan the jar before it writes it.
        Map<String, byte[]> ordered = new java.util.LinkedHashMap<>(layers.get(1).entries());
        byte[] nestHost = ordered.remove("fix/Scenario.class");
        ordered.put("fix/Scenario.class", nestHost);
        LambdaFixtures.Outcome outcome = LambdaFixtures.desugar(temp.resolve("desugared/out"), List.of(
                layers.get(0), LambdaFixtures.Layer.dependency(ordered), layers.get(2)));
        Path source = outcome.sources().get(1);
        Set<String> generated = generatedBy(outcome, 1);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipRepacker.RepackResult result;
        ZipRepacker.RepackResult plain;
        try (ZipReader reader = ZipReader.open(source); ZipReader original = ZipReader.open(outcome.jars().get(1))) {
            result = ZipRepacker.repack(reader, bytes, RepackOptions.NONE, generated);
            plain = ZipRepacker.repack(original, new ByteArrayOutputStream());
        }
        byte[] repacked = bytes.toByteArray();

        List<String> written = result.entries().stream().map(ZipEntryInfo::name).toList();
        assertEquals(plain.entries().stream().map(ZipEntryInfo::name).toList(),
                written.stream().filter(name -> !generated.contains(name)).toList(),
                "the entries the jar had keep their order");
        int count = 0;
        for (int i = 0; i < result.entries().size(); i++) {
            ZipEntryInfo entry = result.entries().get(i);
            byte[] content = slice(repacked, entry);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(content);
            assertEquals(crc.getValue(), entry.crc32(), entry.name() + " carries the CRC of its bytes");
            assertEquals(content.length, entry.uncompressedSize(), entry.name());
            int infix = entry.name().indexOf("$$Lambda$R");
            if (!generated.contains(entry.name())) {
                continue;
            }
            count++;
            String host = entry.name().substring(0, infix);
            ZipEntryInfo previous = result.entries().get(i - 1);
            assertTrue(previous.name().equals(host + ".class") || previous.name().startsWith(host + "$$Lambda$R"),
                    entry.name() + " sits right after its host, not after " + previous.name());
            assertEquals(result.entries().get(written.indexOf(host + ".class")).dosTime(), entry.dosTime(),
                    entry.name() + " takes its host's time");
            assertEquals(host.replace('/', '.') + entry.name().substring(infix, entry.name().length() - 6),
                    java.lang.classfile.ClassFile.of().parse(content).thisClass().asInternalName()
                            .replace('/', '.'), "the entry holds the class it names");
        }
        ClassPathRewrite.Counts counts = outcome.counts().get(1);
        assertEquals(counts.generated(), count, "every generated class is an entry");
        assertTrue(count > 0 && counts.sites() >= count, counts::toString);
        assertEquals(List.of(), counts.notes());
        Path nested = temp.resolve("desugared/fix-nested.jar");
        Files.write(nested, repacked);
        try (ZipReader reader = ZipReader.open(nested)) {
            assertEquals(result.entries(), reader.entries(), "the reported entries describe the nested jar");
            ZipEntryInfo rewritten = reader.entry("fix/Scenario.class").orElseThrow();
            assertNotEquals(plain.entries().get(plain.entries().size() - 1).crc32(), rewritten.crc32(),
                    "the nest host was rewritten where it is");
            assertEquals(0, LambdaFixtures.sites(reader.read(rewritten), "java/lang/invoke/LambdaMetafactory"));
        }
    }

    @Test
    void ordersAJarHotFirstAndKeepsEveryEntrysBytes() throws IOException {
        Path source = temp.resolve("ordered-source.jar");
        try (java.io.OutputStream out = Files.newOutputStream(source);
             ZipWriter writer = new ZipWriter(out, ZipWriter.DEFAULT_TIMESTAMP, false)) {
            writer.writeDirectoryEntry("META-INF/");
            writer.writeEntry("META-INF/MANIFEST.MF", manifestBytes("Created-By", "test"));
            deflatedEntry(writer, "a/A.class", repeat("class-a-", 40));
            deflatedEntry(writer, "b/B.class", repeat("class-b-", 40));
            writer.writeEntry("META-INF/versions/17/b/B.class", repeat("class-b17-", 40));
            writer.writeEntry("r.txt", "a resource".getBytes(StandardCharsets.UTF_8));
            writer.writeEntry("d/D.class", "the first D".getBytes(StandardCharsets.UTF_8));
            deflatedEntry(writer, "d/D.class", repeat("the-second-D-", 40));
        }
        RepackOptions options = RepackOptions.of(List.of("b.B", "d.D"), false);
        ByteArrayOutputStream ordered = new ByteArrayOutputStream();
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ZipRepacker.RepackResult result;
        try (ZipReader reader = ZipReader.open(source)) {
            result = ZipRepacker.repack(reader, ordered, options, Set.of());
            ZipRepacker.repack(reader, plain);
        }

        assertEquals(List.of("META-INF/", "META-INF/MANIFEST.MF", "b/B.class", "META-INF/versions/17/b/B.class",
                "d/D.class", "d/D.class", "a/A.class", "r.txt"), entryNames(result));
        assertEquals(4, result.hotEntries(), "the listed classes, their versioned variant and both duplicates");
        Path nested = temp.resolve("ordered-nested.jar");
        Files.write(nested, ordered.toByteArray());
        // Where each written entry came from: the duplicates keep their order, first then second.
        int[] from = {0, 1, 3, 4, 6, 7, 2, 5};
        try (ZipReader original = ZipReader.open(source); ZipReader written = ZipReader.open(nested)) {
            assertEquals(result.entries(), written.entries(), "the reported entries describe the nested jar");
            for (int i = 0; i < from.length; i++) {
                ZipEntryInfo before = original.entries().get(from[i]);
                ZipEntryInfo after = written.entries().get(i);
                assertEquals(before.name(), after.name());
                assertEquals(IndexFormat.METHOD_STORED, after.method(), after.name());
                assertEquals(before.crc32(), after.crc32(), after.name() + " keeps its CRC-32");
                assertEquals(before.dosTime(), after.dosTime(), after.name() + " keeps its time");
                if (!before.directory()) {
                    assertArrayEquals(original.read(before), written.read(after), after.name() + " keeps its bytes");
                }
            }
        }
        try (java.util.jar.JarInputStream jar = new java.util.jar.JarInputStream(
                new java.io.ByteArrayInputStream(ordered.toByteArray()))) {
            assertNotNull(jar.getManifest(), "JarInputStream still finds the manifest");
            assertEquals("test", jar.getManifest().getMainAttributes().getValue("Created-By"));
            List<String> streamed = new ArrayList<>();
            for (ZipEntry entry = jar.getNextEntry(); entry != null; entry = jar.getNextEntry()) {
                streamed.add(entry.getName());
            }
            assertEquals(entryNames(result).subList(2, from.length), streamed);
        }

        // A rank map that names no class of the jar orders nothing: the bytes of the list-free repack.
        ByteArrayOutputStream unrelated = new ByteArrayOutputStream();
        ZipRepacker.RepackResult none;
        try (ZipReader reader = ZipReader.open(source)) {
            none = ZipRepacker.repack(reader, unrelated, RepackOptions.of(List.of("x.Absent"), false), Set.of());
        }
        assertArrayEquals(plain.toByteArray(), unrelated.toByteArray());
        assertEquals(0, none.hotEntries());
        assertEquals(RepackOptions.NONE, RepackOptions.of(List.of(), false));
        assertFalse(RepackOptions.NONE.any(), "empty options order and compress nothing");
    }

    @Test
    void aListedHostTakesItsGeneratedClassesAlongAndAListedGeneratedClassHasItsOwnRank() throws Exception {
        List<LambdaFixtures.Layer> layers = LambdaFixtures.scenario(temp.resolve("hot-lambdas"), 25);
        LambdaFixtures.Outcome outcome = LambdaFixtures.desugar(temp.resolve("hot-lambdas/out"), layers);
        Path source = outcome.sources().get(1);
        Set<String> generated = generatedBy(outcome, 1);
        ZipRepacker.RepackResult listFree = repack(source, RepackOptions.NONE, generated);
        List<String> plain = entryNames(listFree);
        String infix = "$$Lambda$R";
        String inner = "fix/Scenario$Inner.class";
        List<String> innerGenerated = plain.stream().filter(name -> name.startsWith("fix/Scenario$Inner" + infix))
                .toList();
        String serGenerated = plain.stream().filter(name -> name.startsWith("fix/Ser" + infix)).findFirst()
                .orElseThrow();
        assertFalse(innerGenerated.isEmpty(), plain::toString);
        assertTrue(plain.indexOf(serGenerated) > plain.indexOf(inner), "Ser comes after Scenario$Inner in the jar");

        // First a generated class whose host is not listed, then a host whose generated classes are not.
        String serBinary = serGenerated.substring(0, serGenerated.length() - ".class".length()).replace('/', '.');
        ZipRepacker.RepackResult ordered = repack(source,
                RepackOptions.of(List.of(serBinary, "fix.Scenario$Inner"), false), generated);
        List<String> names = entryNames(ordered);

        List<String> hot = new ArrayList<>();
        hot.add("META-INF/MANIFEST.MF");
        hot.add(serGenerated);
        hot.add(inner);
        hot.addAll(innerGenerated);
        assertEquals(hot, names.subList(0, hot.size()), "the hot region: the listed generated class at its own"
                + " rank, then the listed host with its unlisted generated classes right after it");
        List<String> cold = new ArrayList<>(plain);
        cold.removeAll(hot);
        assertEquals(cold, names.subList(hot.size(), names.size()), "everything else keeps the list-free order");
        assertTrue(names.indexOf("fix/Ser.class") >= hot.size(), "the generated class's host stays cold");
        assertEquals(2, ordered.hotEntries());
        Map<String, Long> crcs = new java.util.HashMap<>();
        listFree.entries().forEach(entry -> crcs.put(entry.name(), entry.crc32()));
        for (ZipEntryInfo entry : ordered.entries()) {
            assertEquals(crcs.get(entry.name()), entry.crc32(), entry.name() + " has the bytes the list-free repack"
                    + " wrote");
        }
        ZipRepacker.RepackResult unknown = repack(source, RepackOptions.of(List.of("fix.Scenario$Inner"), false),
                Set.of());
        assertEquals(List.of("META-INF/MANIFEST.MF", inner), entryNames(unknown).subList(0, 2),
                "without the engine's names, a generated class is an entry like any other and stays where it is");
    }

    @Test
    void hybridStoresTheHotClassesAndKeepsTheCompressedBytesOfTheColdOnes() throws Exception {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put("h/Hot.class", repeat("a hot class that compresses well ", 20));
        entries.put("c/Cold.class", repeat("a cold class that compresses well ", 20));
        entries.put("c/Tiny.class", "x".getBytes(StandardCharsets.UTF_8));
        entries.put("c/data.txt", repeat("a resource that compresses well ", 20));
        Path source = ClassFixtures.jar(temp.resolve("hybrid.jar"), entries);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipRepacker.RepackResult result;
        Map<String, byte[]> raw = new java.util.HashMap<>();
        try (ZipReader reader = ZipReader.open(source)) {
            for (ZipEntryInfo entry : reader.entries()) {
                raw.put(entry.name(), reader.readRaw(entry));
                assertTrue(entry.directory() || entry.method() == IndexFormat.METHOD_DEFLATED, entry.name());
            }
            result = ZipRepacker.repack(reader, bytes, RepackOptions.of(List.of("h.Hot"), true), Set.of());
        }
        Path nested = temp.resolve("hybrid-nested.jar");
        Files.write(nested, bytes.toByteArray());
        Map<String, ZipEntryInfo> after = new java.util.HashMap<>();
        result.entries().forEach(entry -> after.put(entry.name(), entry));
        assertEquals(List.of("META-INF/MANIFEST.MF", "h/Hot.class"), entryNames(result).subList(0, 2));

        try (ZipReader written = ZipReader.open(nested); ZipFile oracle = new ZipFile(nested.toFile())) {
            assertEquals(result.entries(), written.entries(), "the reported entries describe the nested jar");
            assertEquals(IndexFormat.METHOD_STORED, after.get("h/Hot.class").method(), "a listed class is stored");
            ZipEntryInfo cold = after.get("c/Cold.class");
            assertEquals(IndexFormat.METHOD_DEFLATED, cold.method());
            assertArrayEquals(raw.get("c/Cold.class"), written.readRaw(cold),
                    "a cold class keeps its source entry's compressed bytes exactly");
            assertEquals(IndexFormat.METHOD_STORED, after.get("c/Tiny.class").method(),
                    "a cold class that does not compress stays stored");
            assertEquals(IndexFormat.METHOD_STORED, after.get("c/data.txt").method(), "a resource stays stored");
            assertEquals(IndexFormat.METHOD_STORED, after.get("META-INF/MANIFEST.MF").method());
            for (ZipEntryInfo entry : written.entries()) {
                assertArrayEquals(written.read(entry), readAll(oracle, oracle.getEntry(entry.name())), entry.name());
            }
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                assertArrayEquals(entry.getValue(), written.read(after.get(entry.getKey())), entry.getKey());
            }
        }
    }

    @Test
    void hybridFailsOnABrokenColdClassExactlyAsTheStoredRepackDoes() throws IOException {
        // The cold class keeps its original bytes and is verified before it is written: a broken stream fails with
        // the message the streaming STORED repack gives.
        byte[] content = repeat("a-cold-class-", 40);
        List<Path> broken = List.of(
                deflatedWithTrailingByte(temp.resolve("trailing/cold.jar"), "x/Cold.class", content),
                deflatedWithRecordedContent(temp.resolve("overproduced/cold.jar"), "x/Cold.class", content,
                        repeat("a-cold-class-", 20)));
        RepackOptions options = RepackOptions.of(List.of("x.Hot"), true);
        for (Path source : broken) {
            try (ZipReader reader = ZipReader.open(source)) {
                ZipEntryInfo cold = reader.entries().get(0);
                assertEquals(IndexFormat.METHOD_DEFLATED, cold.method());
                assertTrue(cold.compressedSize() < cold.uncompressedSize(),
                        "the class would keep its original bytes");
                IOException stored = assertThrows(IOException.class,
                        () -> ZipRepacker.repack(reader, new ByteArrayOutputStream()));
                IOException hybrid = assertThrows(IOException.class,
                        () -> ZipRepacker.repack(reader, new ByteArrayOutputStream(), options, Set.of()));
                assertEquals(stored.getMessage(), hybrid.getMessage(), source::toString);
                assertTrue(hybrid.getMessage().contains("x/Cold.class"), hybrid.getMessage());
            }
        }
    }

    @ParameterizedTest(name = "deflated in the dependency: {0}")
    @ValueSource(booleans = {false, true})
    void aColdClassAboveTheSizeLimitIsStreamedStored(boolean deflated) throws Exception {
        // Deflated, it is a highly compressible entry one byte past a multiple of the transfer buffer, which the
        // streaming path has to inflate to its last byte.
        byte[] large = new byte[ZipRepacker.MAX_KEPT_CLASS_SIZE + 1];
        byte[] marker = "LocalVariableTable".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(marker, 0, large, 16, marker.length);
        Path source = temp.resolve("large-class-" + deflated + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
            parentCommitEntry(zip, "org/example/Large.class", large, deflated, 0);
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipRepacker.RepackResult result;
        long crc;
        try (ZipReader reader = ZipReader.open(source)) {
            crc = reader.entries().get(0).crc32();
            result = ZipRepacker.repack(reader, bytes, RepackOptions.of(List.of("x.Hot"), true), Set.of());
        }

        ZipEntryInfo entry = result.entries().get(0);
        assertEquals(IndexFormat.METHOD_STORED, entry.method());
        assertEquals(large.length, entry.uncompressedSize());
        assertEquals(crc, entry.crc32());
        assertArrayEquals(large, slice(bytes.toByteArray(), entry));
    }

    /**
     * A jar whose every byte is fixed, whatever the zlib or the time zone: the DOS times are set as local
     * times, and the repacked output carries no compressed byte.
     */
    private static Path parentCommitFixture(Path jar) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.setComment("the parent commit fixture");
            parentCommitEntry(zip, "META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".getBytes(StandardCharsets.UTF_8), true,
                    0);
            parentCommitEntry(zip, "org/", new byte[0], false, 1);
            parentCommitEntry(zip, "org/example/", new byte[0], false, 2);
            parentCommitEntry(zip, "org/example/App.class", "class-bytes-".repeat(200)
                    .getBytes(StandardCharsets.UTF_8), true, 3);
            parentCommitEntry(zip, "org/example/data.bin", "already stored".getBytes(StandardCharsets.UTF_8), false, 4);
            parentCommitEntry(zip, "org/example/empty.txt", new byte[0], true, 5);
            parentCommitEntry(zip, "org/example/caf\u00e9-\u65e5\u672c.txt", "unicode".getBytes(StandardCharsets.UTF_8),
                    true, 6);
            parentCommitEntry(zip, "META-INF/versions/17/org/example/App.class", "v17-".repeat(100)
                    .getBytes(StandardCharsets.UTF_8), true, 7);
            parentCommitEntry(zip, "META-INF/INDEX.LIST", "JarIndex-Version: 1.0".getBytes(StandardCharsets.UTF_8),
                    true, 8);
            parentCommitEntry(zip, "META-INF/SIGNER.SF", "Signature-Version: 1.0".getBytes(StandardCharsets.UTF_8),
                    true, 9);
        }
        return jar;
    }

    private static void parentCommitEntry(ZipOutputStream zip, String name, byte[] data, boolean deflated,
                                          int minute) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(LocalDateTime.of(2020, 1, 2, 3, minute, 4));
        if (deflated) {
            entry.setMethod(ZipEntry.DEFLATED);
        } else {
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static void deflatedEntry(ZipWriter writer, String name, byte[] content) throws IOException {
        byte[] compressed = rawDeflate(content);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(content);
        writer.writeDeflatedEntry(name, compressed, 0, compressed.length, crc.getValue(), content.length,
                writer.dosTime());
    }

    private static List<String> entryNames(ZipRepacker.RepackResult result) {
        return result.entries().stream().map(ZipEntryInfo::name).toList();
    }

    /**
     * The classes the engine generated in a layer: the entries of its copy that the layer's own jar does not hold.
     */
    private static Set<String> generatedBy(LambdaFixtures.Outcome outcome, int layer) throws IOException {
        Set<String> own = new java.util.HashSet<>();
        try (ZipReader reader = ZipReader.open(outcome.jars().get(layer))) {
            reader.entries().forEach(entry -> own.add(entry.name()));
        }
        Set<String> generated = new java.util.HashSet<>(outcome.outputs().get(layer).keySet());
        generated.removeAll(own);
        return generated;
    }

    private static ZipRepacker.RepackResult repack(Path source, RepackOptions options, Set<String> generated)
            throws IOException {
        try (ZipReader reader = ZipReader.open(source)) {
            return ZipRepacker.repack(reader, new ByteArrayOutputStream(), options, generated);
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
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
