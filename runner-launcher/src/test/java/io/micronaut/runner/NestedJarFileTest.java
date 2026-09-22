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
    private static final byte[] DIRECTORY = new byte[0];
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
        long[] inner = new long[5];
        inner[0] = dependency.stored("META-INF/MANIFEST.MF", MANIFEST);
        inner[1] = dependency.stored("a/", DIRECTORY);
        inner[2] = dependency.stored("a/B.class", BASE_CLASS);
        inner[3] = dependency.deflated(TEXT_NAME, TEXT);
        inner[4] = dependency.stored("META-INF/versions/21/a/B.class", VERSIONED_CLASS);
        byte[] dependencyBytes = dependency.build();
        int compressed = dependency.storedSize(TEXT_NAME);

        TestArchiveBuilder outer = new TestArchiveBuilder();
        outer.stored("META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\r\n\r\n"));
        byte[] draft = buildIndex(0, inner, 0, 0, 0, compressed);
        outer.reserve(IndexFormat.INDEX_ENTRY_NAME, draft.length);
        long application = outer.stored(IndexFormat.CLASSES_PREFIX + "app.txt", bytes("application"));
        long base = outer.stored(DEPENDENCY, dependencyBytes);
        long header = outer.localHeaderOffset(DEPENDENCY);
        byte[] real = buildIndex(application, inner, base, dependencyBytes.length, header, compressed);
        assertEquals(draft.length, real.length, "the index size must not depend on the offsets");
        outer.replace(IndexFormat.INDEX_ENTRY_NAME, real);

        archive = outer.writeTo(temporary.resolve("app.jar").toFile());
        source = ArchiveSource.open(archive);
        index = Index.open(source);
        jar = new NestedJarFile(archive, index, source, 1);
    }

    @AfterEach
    void closeArchive() {
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
                "META-INF/versions/21/a/B.class"), names);
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
        assertEquals(5, jar.size());
    }

    @Test
    void refusesToViewSomethingThatIsNotANestedJar() {
        assertThrows(IllegalArgumentException.class,
                () -> new NestedJarFile(archive, index, source, IndexFormat.APPLICATION_JAR_ID));
        assertThrows(IllegalArgumentException.class, () -> new NestedJarFile(archive, index, source, 9));
    }

    private byte[] buildIndex(long application, long[] inner, long base, long length, long header,
                              int compressed) {
        TestIndexBuilder builder = new TestIndexBuilder();
        TestIndexBuilder.Jar layer = builder.addJar(IndexFormat.CLASSES_PREFIX);
        layer.addEntry("app.txt").data(application, 11, 11);
        TestIndexBuilder.Jar dependency = builder.addJar(DEPENDENCY)
                .location(base, length, header)
                .manifest(null, null, null, "Dependency", "1.0", null)
                .multiRelease();
        dependency.addEntry("META-INF/MANIFEST.MF").data(base + inner[0], MANIFEST.length, MANIFEST.length)
                .dosTime(DOS_TIME);
        dependency.addEntry("a/").data(base + inner[1], 0, 0).dosTime(DOS_TIME);
        dependency.addEntry("a/B.class").data(base + inner[2], BASE_CLASS.length, BASE_CLASS.length)
                .dosTime(DOS_TIME);
        dependency.addEntry(TEXT_NAME).data(base + inner[3], compressed, TEXT.length)
                .method(IndexFormat.METHOD_DEFLATED).dosTime(DOS_TIME);
        dependency.addEntry("META-INF/versions/21/a/B.class")
                .data(base + inner[4], VERSIONED_CLASS.length, VERSIONED_CLASS.length).dosTime(DOS_TIME);
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
}
