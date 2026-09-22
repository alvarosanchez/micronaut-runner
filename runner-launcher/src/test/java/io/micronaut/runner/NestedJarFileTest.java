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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@code JarFile} view of a nested jar: that it reports the nested jar rather than the outer
 * archive, that it enumerates exactly the entries the original dependency had, that it applies the nested
 * jar's own multi-release policy, and that it serves content straight from the archive.
 */
class NestedJarFileTest {

    private static final String DEPENDENCY = "MICRONAUT-INF/lib/dep.jar";
    private static final String TEXT_NAME = "a/data file.txt";
    private static final long DOS_TIME = 0x00210000L;
    private static final long DOS_TIME_MILLIS = 315532800000L;

    private static final byte[] BASE_CLASS = bytes("base class bytes");
    private static final byte[] VERSIONED_CLASS = bytes("versioned class bytes");
    private static final byte[] TEXT = bytes("nested deflated payload ".repeat(40));
    private static final byte[] CORRUPT_STORED = bytes("corrupt stored resource");
    private static final byte[] CORRUPT_DEFLATED = bytes("corrupt deflated resource ".repeat(20));
    private static final byte[] DIRECTORY = new byte[0];
    private static final byte[] STORED_FIRST = bytes("stored first");
    private static final byte[] STORED_LAST = bytes("stored last!");
    private static final byte[] DEFLATED_FIRST = bytes("deflated first ".repeat(20));
    private static final byte[] DEFLATED_LAST = bytes("deflated last! ".repeat(20));
    private static final byte[] VERSIONED_FIRST = bytes("versioned first");
    private static final byte[] VERSIONED_LAST = bytes("versioned last!");
    private static final byte[] MANIFEST = bytes("Manifest-Version: 1.0\r\n"
            + "Implementation-Title: Dependency\r\n"
            + "Implementation-Version: 1.0\r\n"
            + "Multi-Release: true\r\n"
            + "\r\n"
            + "Name: a/data file.txt\r\n"
            + "Sealed: true\r\n"
            + "\r\n");

    @TempDir
    Path temporary;

    private File archive;
    private ArchiveSource source;
    private Index index;
    private NestedJarFile jar;

    @BeforeEach
    void openArchive() throws IOException {
        TestArchiveBuilder dependency = new TestArchiveBuilder();
        long[] inner = new long[16];
        int[] duplicateCompressed = new int[2];
        inner[0] = dependency.stored("META-INF/MANIFEST.MF", MANIFEST);
        inner[1] = dependency.stored("a/", DIRECTORY);
        inner[2] = dependency.stored("a/B.class", BASE_CLASS);
        inner[3] = dependency.deflated(TEXT_NAME, TEXT);
        inner[4] = dependency.stored("META-INF/versions/21/a/B.class", VERSIONED_CLASS);
        inner[5] = dependency.stored("corrupt-stored.txt", CORRUPT_STORED);
        inner[6] = dependency.deflated("corrupt-deflated.txt", CORRUPT_DEFLATED);
        inner[7] = dependency.stored("corrupt-empty.txt", DIRECTORY);
        inner[8] = dependency.stored("duplicate-stored.txt", STORED_FIRST);
        inner[9] = dependency.stored("duplicate-stored.txt", STORED_LAST);
        inner[10] = dependency.deflated("duplicate-deflated.txt", DEFLATED_FIRST);
        duplicateCompressed[0] = dependency.storedSize("duplicate-deflated.txt");
        inner[11] = dependency.deflated("duplicate-deflated.txt", DEFLATED_LAST);
        duplicateCompressed[1] = dependency.latestStoredSize("duplicate-deflated.txt");
        inner[12] = dependency.stored("duplicate-directory/", DIRECTORY);
        inner[13] = dependency.stored("duplicate-directory/", DIRECTORY);
        inner[14] = dependency.stored("META-INF/versions/21/duplicate-versioned.txt", VERSIONED_FIRST);
        inner[15] = dependency.stored("META-INF/versions/21/duplicate-versioned.txt", VERSIONED_LAST);
        byte[] dependencyBytes = dependency.build();
        int compressed = dependency.storedSize(TEXT_NAME);
        int corruptCompressed = dependency.storedSize("corrupt-deflated.txt");

        TestArchiveBuilder outer = new TestArchiveBuilder();
        outer.stored("META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\r\n\r\n"));
        byte[] draft = buildIndex(0, inner, 0, 0, 0, compressed, corruptCompressed, duplicateCompressed);
        outer.reserve(IndexFormat.INDEX_ENTRY_NAME, draft.length);
        long application = outer.stored(IndexFormat.CLASSES_PREFIX + "app.txt", bytes("application"));
        long base = outer.stored(DEPENDENCY, dependencyBytes);
        long header = outer.localHeaderOffset(DEPENDENCY);
        byte[] real = buildIndex(application, inner, base, dependencyBytes.length, header, compressed,
                corruptCompressed, duplicateCompressed);
        assertEquals(draft.length, real.length, "the index size must not depend on the offsets");
        outer.replace(IndexFormat.INDEX_ENTRY_NAME, real);

        archive = outer.writeTo(temporary.resolve("app.jar").toFile());
        source = ArchiveSource.open(archive);
        index = Index.open(source);
        jar = new NestedJarFile(archive, index, source, 1);
    }

    @AfterEach
    void closeArchive() {
        System.clearProperty(RunnerClassLoader.VERIFY_PROPERTY);
        if (jar != null) {
            jar.closeNested();
            jar = null;
        }
        if (source != null) {
            source.close();
            source = null;
        }
    }

    @Test
    void reportsTheNestedJarRatherThanTheOuterArchive() {
        StringBuilder expected = new StringBuilder();
        expected.append(archive.getPath()).append("!/").append(DEPENDENCY);
        assertEquals(expected.toString(), jar.getName());
        assertEquals(1, jar.jarId());
        assertNull(jar.getComment());
    }

    @Test
    void enumeratesThePhysicalEntriesInCentralDirectoryOrder() {
        List<String> names = new ArrayList<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            names.add(entries.nextElement().getName());
        }
        assertEquals(List.of("META-INF/MANIFEST.MF", "a/", "a/B.class", TEXT_NAME,
                "META-INF/versions/21/a/B.class", "corrupt-stored.txt", "corrupt-deflated.txt",
                "corrupt-empty.txt", "duplicate-stored.txt", "duplicate-stored.txt",
                "duplicate-deflated.txt", "duplicate-deflated.txt", "duplicate-directory/",
                "duplicate-directory/", "META-INF/versions/21/duplicate-versioned.txt",
                "META-INF/versions/21/duplicate-versioned.txt"), names);
        assertEquals(names.size(), jar.size());
        assertEquals(names, jar.stream().map(JarEntry::getName).toList());
        assertTrue(index.jarEntryCount(1) > names.size(),
                "the index also holds the alias and the synthesised directories");
    }

    @Test
    void appliesTheNestedJarsOwnMultiReleasePolicy() throws IOException {
        JarEntry versioned = jar.getJarEntry("a/B.class");
        assertNotNull(versioned);
        assertEquals("a/B.class", versioned.getName());
        assertEquals("META-INF/versions/21/a/B.class", versioned.getRealName());
        assertEquals(VERSIONED_CLASS.length, versioned.getSize());
        assertArrayEquals(VERSIONED_CLASS, read(versioned));

        JarEntry physical = jar.getJarEntry("META-INF/versions/21/a/B.class");
        assertEquals(physical.getName(), physical.getRealName());
        assertArrayEquals(VERSIONED_CLASS, read(physical));

        // isMultiRelease() and getVersion() are final in JarFile and keep reporting the outer archive,
        // which is not multi-release and is opened at the base version. Documented limitation.
        assertFalse(jar.isMultiRelease());
        assertEquals(8, jar.getVersion().feature());
        assertTrue(index.jarMultiRelease(1), "the index knows what JarFile cannot report");
    }

    @Test
    void readsStoredAndDeflatedEntries() throws IOException {
        assertArrayEquals(MANIFEST, read(jar.getJarEntry("META-INF/MANIFEST.MF")));
        assertArrayEquals(TEXT, read(jar.getJarEntry(TEXT_NAME)));
        JarEntry deflated = jar.getJarEntry(TEXT_NAME);
        assertEquals(ZipEntry.DEFLATED, deflated.getMethod());
        assertEquals(TEXT.length, deflated.getSize());
        assertTrue(deflated.getCompressedSize() < TEXT.length);
        // Repeated reads of the same entry must each deliver the whole content.
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(TEXT, read(jar.getJarEntry(TEXT_NAME)));
        }
    }

    @Test
    void duplicateLookupsSelectTheLastEntryWhileEnumerationRetainsEveryRecord() throws IOException {
        assertArrayEquals(STORED_LAST, read(jar.getJarEntry("duplicate-stored.txt")));
        assertArrayEquals(DEFLATED_LAST, read(jar.getEntry("duplicate-deflated.txt")));
        assertArrayEquals(VERSIONED_LAST, read(jar.getJarEntry("duplicate-versioned.txt")),
                "the last duplicate within the selected MR version wins");
        assertArrayEquals(VERSIONED_LAST,
                read(jar.getJarEntry("META-INF/versions/21/duplicate-versioned.txt")),
                "direct physical-name lookup has the same precedence");
        assertTrue(jar.getEntry("duplicate-directory").isDirectory());

        List<byte[]> stored = new ArrayList<>();
        List<byte[]> deflated = new ArrayList<>();
        int directories = 0;
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.getName().equals("duplicate-stored.txt")) {
                stored.add(read(entry));
            } else if (entry.getName().equals("duplicate-deflated.txt")) {
                deflated.add(read(entry));
            } else if (entry.getName().equals("duplicate-directory/")) {
                directories++;
            }
        }
        assertEquals(2, stored.size());
        assertArrayEquals(STORED_FIRST, stored.get(0));
        assertArrayEquals(STORED_LAST, stored.get(1));
        assertEquals(2, deflated.size());
        assertArrayEquals(DEFLATED_FIRST, deflated.get(0));
        assertArrayEquals(DEFLATED_LAST, deflated.get(1));
        assertEquals(2, directories);
    }

    @Test
    void verifiesStoredAndDeflatedEntryStreamsWhenAskedTo() throws IOException {
        assertArrayEquals(CORRUPT_STORED, read(jar.getJarEntry("corrupt-stored.txt")));
        assertArrayEquals(CORRUPT_DEFLATED, read(jar.getJarEntry("corrupt-deflated.txt")));

        System.setProperty(RunnerClassLoader.VERIFY_PROPERTY, "true");
        IOException stored = assertThrows(IOException.class,
                () -> read(jar.getJarEntry("corrupt-stored.txt")));
        assertTrue(stored.getMessage().contains("corrupt-stored.txt"), stored.getMessage());
        IOException deflated = assertThrows(IOException.class,
                () -> read(jar.getJarEntry("corrupt-deflated.txt")));
        assertTrue(deflated.getMessage().contains("corrupt-deflated.txt"), deflated.getMessage());
        assertArrayEquals(TEXT, read(jar.getJarEntry(TEXT_NAME)),
                "an intact deflated entry still reads with verification on");
    }

    @Test
    void verificationCoversSkipAndDoesNotDrainPartialStreamsOnClose() throws IOException {
        System.setProperty(RunnerClassLoader.VERIFY_PROPERTY, "true");

        InputStream partial = jar.getInputStream(jar.getJarEntry("corrupt-deflated.txt"));
        assertTrue(partial.read() >= 0);
        partial.close();
        partial.close();

        try (InputStream skipped = jar.getInputStream(jar.getJarEntry("corrupt-stored.txt"))) {
            IOException failure = assertThrows(IOException.class, () -> skipped.skip(Long.MAX_VALUE));
            assertTrue(failure.getMessage().contains("corrupt-stored.txt"), failure.getMessage());
        }

        try (InputStream valid = jar.getInputStream(jar.getJarEntry(TEXT_NAME))) {
            long skipped = valid.skip(17);
            byte[] remainder = valid.readAllBytes();
            assertEquals(TEXT.length, skipped + remainder.length,
                    "skipped bytes are checksummed rather than silently omitted from verification");
        }
    }

    @Test
    void verifiesZeroLengthAndExactLengthReads() throws IOException {
        System.setProperty(RunnerClassLoader.VERIFY_PROPERTY, "true");
        try (InputStream empty = jar.getInputStream(jar.getJarEntry("a/"))) {
            assertEquals(-1, empty.read());
            assertEquals(-1, empty.read());
        }
        try (InputStream corruptEmpty = jar.getInputStream(jar.getJarEntry("corrupt-empty.txt"))) {
            IOException failure = assertThrows(IOException.class, corruptEmpty::read);
            assertTrue(failure.getMessage().contains("corrupt-empty.txt"), failure.getMessage());
        }
        try (InputStream exact = jar.getInputStream(jar.getJarEntry("corrupt-stored.txt"))) {
            byte[] content = new byte[CORRUPT_STORED.length];
            IOException failure = assertThrows(IOException.class,
                    () -> exact.readNBytes(content, 0, content.length));
            assertTrue(failure.getMessage().contains("corrupt-stored.txt"), failure.getMessage());
        }
    }

    @Test
    void verificationFailuresDoNotPoisonRepeatedDeflatedReads() throws IOException {
        System.setProperty(RunnerClassLoader.VERIFY_PROPERTY, "true");
        for (int i = 0; i < 10; i++) {
            assertThrows(IOException.class, () -> read(jar.getJarEntry("corrupt-deflated.txt")));
            assertArrayEquals(TEXT, read(jar.getJarEntry(TEXT_NAME)));
        }
    }

    @Test
    void findsDirectoriesIncludingTheSynthesisedOnes() {
        ZipEntry explicit = jar.getEntry("a/");
        assertNotNull(explicit);
        assertTrue(explicit.isDirectory());
        // ZipFile retries a name that does not resolve with a trailing slash; so does this view.
        assertEquals("a/", jar.getEntry("a").getName());

        ZipEntry synthesised = jar.getEntry("META-INF/versions/21/");
        assertNotNull(synthesised, "a directory the packager synthesised must still answer a lookup");
        assertTrue(synthesised.isDirectory());
        assertEquals(0, synthesised.getSize());
    }

    @Test
    void returnsNullForEntriesTheJarDoesNotHave() throws IOException {
        assertNull(jar.getEntry("a/Missing.class"));
        assertNull(jar.getJarEntry("META-INF/versions/21/a/Missing.class"));
        assertNull(jar.getInputStream(new ZipEntry("a/Missing.class")));
        assertThrows(NullPointerException.class, () -> jar.getEntry(null));
    }

    @Test
    void servesAnEntryThatCameFromSomewhereElseByName() throws IOException {
        // A caller that kept a plain ZipEntry, or an entry of another view of the same jar, still works.
        assertArrayEquals(TEXT, read(new ZipEntry(TEXT_NAME)));
        NestedJarFile second = new NestedJarFile(archive, index, source, 1);
        try {
            assertArrayEquals(VERSIONED_CLASS, read(second.getJarEntry("a/B.class")));
        } finally {
            second.closeNested();
        }
    }

    @Test
    void parsesTheNestedManifestAndCachesIt() throws IOException {
        Manifest manifest = jar.getManifest();
        assertNotNull(manifest);
        assertEquals("Dependency", manifest.getMainAttributes().getValue("Implementation-Title"));
        assertEquals("1.0", manifest.getMainAttributes().getValue("Implementation-Version"));
        assertSame(manifest, jar.getManifest());
        assertEquals("true", jar.getJarEntry(TEXT_NAME).getAttributes().getValue("Sealed"));
        assertNull(jar.getJarEntry("a/B.class").getAttributes(),
                "a per-entry section is looked up under the real name, which is the versioned one");
    }

    @Test
    void carriesTheTimestampOfTheRecord() {
        JarEntry entry = jar.getJarEntry("a/B.class");
        assertEquals(DOS_TIME_MILLIS, entry.getTime());
        assertEquals(DOS_TIME_MILLIS, NestedJarEntry.dosTimeToMillis(DOS_TIME));
        assertEquals(-1, NestedJarEntry.dosTimeToMillis(0));
        assertNull(entry.getCertificates());
        assertNull(entry.getCodeSigners());
    }

    @Test
    void closeIsANoOpBecauseViewsAreShared() throws IOException {
        JarEntry entry = jar.getJarEntry("META-INF/versions/21/a/B.class");
        try (InputStream active = jar.getInputStream(entry)) {
            jar.close();
            assertArrayEquals(VERSIONED_CLASS, active.readAllBytes());
        }
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(VERSIONED_CLASS, read(entry));
        }
        assertEquals(16, jar.size());
    }

    @Test
    void refusesToViewSomethingThatIsNotANestedJar() {
        assertThrows(IllegalArgumentException.class,
                () -> new NestedJarFile(archive, index, source, IndexFormat.APPLICATION_JAR_ID));
        assertThrows(IllegalArgumentException.class, () -> new NestedJarFile(archive, index, source, 9));
    }

    private byte[] buildIndex(long application, long[] inner, long base, long length, long header,
                              int compressed, int corruptCompressed, int[] duplicateCompressed) {
        TestIndexBuilder builder = new TestIndexBuilder();
        TestIndexBuilder.Jar layer = builder.addJar(IndexFormat.CLASSES_PREFIX);
        layer.addEntry("app.txt").data(application, 11, 11);
        TestIndexBuilder.Jar dependency = builder.addJar(DEPENDENCY)
                .location(base, length, header)
                .manifest(null, null, null, "Dependency", "1.0", null)
                .multiRelease();
        dependency.addEntry("META-INF/MANIFEST.MF").data(base + inner[0], MANIFEST.length, MANIFEST.length)
                .crc32(crc32(MANIFEST)).dosTime(DOS_TIME);
        dependency.addEntry("a/").data(base + inner[1], 0, 0).crc32(0).dosTime(DOS_TIME);
        dependency.addEntry("a/B.class").data(base + inner[2], BASE_CLASS.length, BASE_CLASS.length)
                .crc32(crc32(BASE_CLASS)).dosTime(DOS_TIME);
        dependency.addEntry(TEXT_NAME).data(base + inner[3], compressed, TEXT.length)
                .method(IndexFormat.METHOD_DEFLATED).crc32(crc32(TEXT)).dosTime(DOS_TIME);
        dependency.addEntry("META-INF/versions/21/a/B.class")
                .data(base + inner[4], VERSIONED_CLASS.length, VERSIONED_CLASS.length)
                .crc32(crc32(VERSIONED_CLASS)).dosTime(DOS_TIME);
        dependency.addEntry("corrupt-stored.txt")
                .data(base + inner[5], CORRUPT_STORED.length, CORRUPT_STORED.length)
                .crc32(crc32(CORRUPT_STORED) ^ 0xFFFFFFFFL);
        dependency.addEntry("corrupt-deflated.txt")
                .data(base + inner[6], corruptCompressed, CORRUPT_DEFLATED.length)
                .method(IndexFormat.METHOD_DEFLATED)
                .crc32(crc32(CORRUPT_DEFLATED) ^ 0xFFFFFFFFL);
        dependency.addEntry("corrupt-empty.txt").data(base + inner[7], 0, 0).crc32(1);
        dependency.addEntry("duplicate-stored.txt")
                .data(base + inner[8], STORED_FIRST.length, STORED_FIRST.length).crc32(crc32(STORED_FIRST));
        dependency.addEntry("duplicate-stored.txt")
                .data(base + inner[9], STORED_LAST.length, STORED_LAST.length).crc32(crc32(STORED_LAST));
        dependency.addEntry("duplicate-deflated.txt")
                .data(base + inner[10], duplicateCompressed[0], DEFLATED_FIRST.length)
                .method(IndexFormat.METHOD_DEFLATED).crc32(crc32(DEFLATED_FIRST));
        dependency.addEntry("duplicate-deflated.txt")
                .data(base + inner[11], duplicateCompressed[1], DEFLATED_LAST.length)
                .method(IndexFormat.METHOD_DEFLATED).crc32(crc32(DEFLATED_LAST));
        dependency.addEntry("duplicate-directory/").data(base + inner[12], 0, 0).crc32(0);
        dependency.addEntry("duplicate-directory/").data(base + inner[13], 0, 0).crc32(0);
        dependency.addEntry("META-INF/versions/21/duplicate-versioned.txt")
                .data(base + inner[14], VERSIONED_FIRST.length, VERSIONED_FIRST.length)
                .crc32(crc32(VERSIONED_FIRST));
        dependency.addEntry("META-INF/versions/21/duplicate-versioned.txt")
                .data(base + inner[15], VERSIONED_LAST.length, VERSIONED_LAST.length)
                .crc32(crc32(VERSIONED_LAST));
        return builder.build();
    }

    private byte[] read(ZipEntry entry) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static long crc32(byte[] value) {
        CRC32 checksum = new CRC32();
        checksum.update(value);
        return checksum.getValue();
    }
}
