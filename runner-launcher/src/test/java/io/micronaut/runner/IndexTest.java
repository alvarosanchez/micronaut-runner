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
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the index reader against indexes written by {@link TestIndexBuilder}, the second implementation of
 * the writer. Every test runs the reader over a real file, in both the mapped and the fallback mode where
 * the mode can make a difference.
 */
class IndexTest {

    private static final String DEP = "MICRONAUT-INF/lib/dep.jar";
    private static volatile int lookupResult;

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
    void readsTheHeaderAndTheTables(boolean mapped) throws IOException {
        TestIndexBuilder builder = new TestIndexBuilder()
                .startClass("org.example.Application")
                .entryStubClass("io.micronaut.runner.generated.AppEntry")
                .launcherVersion("1.0.0")
                .headerFlags(IndexFormat.HEADER_FLAG_NESTED_STORED);
        TestIndexBuilder.Jar application = builder.addJar(IndexFormat.CLASSES_PREFIX);
        application.addEntry("org/example/A.class").data(300, 10, 20)
                .method(IndexFormat.METHOD_DEFLATED).crc32(0xCAFEBABEL).dosTime(0x00210000);
        TestIndexBuilder.Jar dependency = builder.addJar(DEP)
                .coordinates("org.example:dep:1.2")
                .location(100, 200, 50)
                .manifest("Spec", "1.0", "Vendor", "Impl", "1.2.3", "Vendor Inc");
        dependency.addEntry("org/example/B.class").data(400, 30, 30);
        dependency.addPackage("org.example").attributes(null, null, null, "Dep", "9", null).sealed(true);

        Index index = open(builder, mapped);
        index.validateStringReferences();

        assertEquals(IndexFormat.HEADER_FLAG_NESTED_STORED, index.flags());
        assertTrue(index.nestedStored());
        assertFalse(index.applicationMultiRelease());
        assertEquals("org.example.Application", index.startClass());
        assertEquals("io.micronaut.runner.generated.AppEntry", index.entryStubClass());
        assertEquals("1.0.0", index.launcherVersion());
        assertEquals(2, index.jarCount());
        assertEquals(1, index.packageCount());
        assertTrue(index.hashSlots() >= 2);
        assertEquals(0, index.hashSlots() & (index.hashSlots() - 1));
        assertTrue(index.maxProbe() <= IndexFormat.MAX_PROBE_LIMIT);

        assertEquals(IndexFormat.CLASSES_PREFIX, index.jarName(0));
        assertNull(index.jarCoordinates(0));
        assertEquals(DEP, index.jarName(1));
        assertEquals("org.example:dep:1.2", index.jarCoordinates(1));
        assertEquals(100, index.jarDataOffset(1));
        assertEquals(200, index.jarDataLength(1));
        assertEquals(50, index.jarLocalHeaderOffset(1));
        assertEquals("Spec", index.jarSpecTitle(1));
        assertEquals("1.0", index.jarSpecVersion(1));
        assertEquals("Vendor", index.jarSpecVendor(1));
        assertEquals("Impl", index.jarImplTitle(1));
        assertEquals("1.2.3", index.jarImplVersion(1));
        assertEquals("Vendor Inc", index.jarImplVendor(1));
        assertEquals(index.jarEntryCount(0) + index.jarEntryCount(1), index.entryCount());
        assertEquals(0, index.jarFirstEntry(0));
        assertEquals(index.jarEntryCount(0), index.jarFirstEntry(1));

        assertEquals("org.example", index.packageName(0));
        assertEquals("Dep", index.packageImplTitle(0));
        assertNull(index.packageSpecTitle(0));
        assertTrue(index.packageSealedSpecified(0));
        assertTrue(index.packageSealedValue(0));
        assertEquals(0, index.findPackage(1, "org.example"));
        assertEquals(IndexFormat.NO_INDEX, index.findPackage(1, "org.other"));
        assertEquals(IndexFormat.NO_INDEX, index.findPackage(0, "org.example"));

        int record = index.find("org/example/A.class");
        assertNotEquals(IndexFormat.NO_INDEX, record);
        assertEquals("org/example/A.class", index.entryName(record));
        assertEquals(IndexFormat.hash("org/example/A.class"), index.entryNameHash(record));
        assertEquals(19, index.entryNameLength(record));
        assertEquals(0, index.entryJarId(record));
        assertEquals(300, index.entryDataOffset(record));
        assertEquals(10, index.entryCompressedSize(record));
        assertEquals(20, index.entryUncompressedSize(record));
        assertEquals(0xCAFEBABEL, index.entryCrc32(record));
        assertEquals(0x00210000L, index.entryDosTime(record));
        assertEquals(IndexFormat.METHOD_DEFLATED, index.entryMethod(record));
        assertEquals(0, index.entryMrVersion(record));
        assertTrue(index.entryPhysical(record));
        assertEquals(record, index.entryPhysicalIndex(record));
        assertEquals(IndexFormat.NO_INDEX, index.entryNextSameName(record));
    }

    @Test
    void repeatedPackageLookupsDoNotDecodePackageNamesAgain() throws IOException {
        TestIndexBuilder builder = new TestIndexBuilder();
        TestIndexBuilder.Jar application = builder.addJar(IndexFormat.CLASSES_PREFIX);
        String[] present = new String[64];
        String[] missing = new String[64];
        for (int i = 0; i < present.length; i++) {
            present[i] = "org.example.package" + i;
            missing[i] = "org.example.missing" + i;
            application.addPackage(present[i]);
        }
        Index index = open(builder, true);

        int result = exercisePackageLookups(index, present, missing, 20_000);
        com.sun.management.ThreadMXBean allocation =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        assertTrue(allocation.isThreadAllocatedMemorySupported(), "the test JVM must expose thread allocation");
        if (!allocation.isThreadAllocatedMemoryEnabled()) {
            allocation.setThreadAllocatedMemoryEnabled(true);
        }
        long thread = Thread.currentThread().threadId();
        long before = allocation.getThreadAllocatedBytes(thread);
        result += exercisePackageLookups(index, present, missing, 20_000);
        long allocated = allocation.getThreadAllocatedBytes(thread) - before;
        lookupResult = result;

        assertTrue(allocated <= 4_096,
                "repeated lookups allocated " + allocated + " bytes after warm-up");
    }

    private static int exercisePackageLookups(Index index, String[] present, String[] missing, int iterations) {
        int result = 0;
        for (int i = 0; i < iterations; i++) {
            int slot = i & (present.length - 1);
            result += index.findPackage(0, present[slot]);
            result += index.findPackage(0, missing[slot]);
        }
        return result;
    }

    @Test
    void packageLookupHandlesCollisionsUnicodeAndDuplicateSections() throws Exception {
        TestIndexBuilder builder = new TestIndexBuilder();
        builder.addJar(IndexFormat.CLASSES_PREFIX);
        TestIndexBuilder.Jar application = builder.addJar(DEP);
        application.addPackage("org.example.Aa").attributes(null, null, null, "first", null, null);
        application.addPackage("org.example.BB").attributes(null, null, null, "collision", null, null);
        application.addPackage("中文.包").attributes(null, null, null, "unicode", null, null);
        application.addPackage("org.example.Aa").attributes(null, null, null, "duplicate", null, null);
        Index index = open(builder, true);
        java.lang.reflect.Field caches = Index.class.getDeclaredField("packageLookups");
        caches.setAccessible(true);

        assertNull(caches.get(index), "opening an index must not eagerly decode package metadata");
        assertEquals(IndexFormat.NO_INDEX, index.findPackage(0, "org.example.Absent"));
        assertNull(caches.get(index), "a zero-record jar must not allocate the package cache");

        int first = index.findPackage(1, "org.example.Aa");
        assertEquals(0, first, "the first matching manifest section keeps precedence");
        assertEquals("first", index.packageImplTitle(first));
        assertEquals(1, index.findPackage(1, "org.example.BB"));
        assertEquals(2, index.findPackage(1, "中文.包"));
        for (int i = 0; i < 1_000; i++) {
            assertEquals(IndexFormat.NO_INDEX, index.findPackage(1, "org.example.missing" + i));
        }

        java.util.concurrent.atomic.AtomicReferenceArray<?> byJar =
                (java.util.concurrent.atomic.AtomicReferenceArray<?>) caches.get(index);
        assertEquals(2, byJar.length(), "the cache is bounded by the fixed jar table");
        assertNull(byJar.get(0));
        Object lookup = byJar.get(1);
        java.lang.reflect.Field names = lookup.getClass().getDeclaredField("names");
        names.setAccessible(true);
        String[] indexed = (String[]) names.get(lookup);
        assertEquals(3, java.util.Arrays.stream(indexed).filter(java.util.Objects::nonNull).count(),
                "arbitrary misses must not be retained and duplicate sections share one slot");
    }

    @Test
    void findsMissesAndDirectories() throws IOException {
        TestIndexBuilder builder = new TestIndexBuilder();
        TestIndexBuilder.Jar application = builder.addJar(IndexFormat.CLASSES_PREFIX);
        application.addEntry("org/example/A.class").data(100, 1, 1);
        application.addEntry("org/example/data/").data(0, 0, 0);
        application.addEntry("org/example/data/values.txt").data(200, 1, 1);

        Index index = open(builder, true);

        assertEquals(IndexFormat.NO_INDEX, index.find("org/example/Absent.class"));
        assertEquals(IndexFormat.NO_INDEX, index.find(""));
        assertEquals(IndexFormat.NO_INDEX, index.findClass("org.example.Absent"));

        int explicit = index.find("org/example/data/");
        assertNotEquals(IndexFormat.NO_INDEX, explicit);
        assertTrue(index.entryDirectory(explicit));
        assertFalse(index.entrySyntheticDirectory(explicit));
        assertTrue(index.entryPhysical(explicit));

        int synthetic = index.find("org/example/");
        assertNotEquals(IndexFormat.NO_INDEX, synthetic);
        assertTrue(index.entryDirectory(synthetic));
        assertTrue(index.entrySyntheticDirectory(synthetic));
        assertFalse(index.entryPhysical(synthetic));
        assertEquals(IndexFormat.NO_INDEX, index.entryPhysicalIndex(synthetic));
        assertNotEquals(IndexFormat.NO_INDEX, index.find("org/"));
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void findClassMatchesFindOfTheResourceName(boolean mapped) throws IOException {
        String[] classes = {
            "org.example.A",
            "org.example.Outer$Inner",
            "a.b.c.d.e.f.g.VeryDeeplyNestedType",
            "org.example.été.Café",
            "中文.类",
            "NoPackage"
        };
        TestIndexBuilder builder = new TestIndexBuilder();
        TestIndexBuilder.Jar application = builder.addJar(IndexFormat.CLASSES_PREFIX);
        for (int i = 0; i < classes.length; i++) {
            application.addEntry(resourceName(classes[i])).data(100 + i, 1, 1);
        }
        application.addEntry("org/example/Almost.klass").data(1, 1, 1);

        Index index = open(builder, mapped);
        for (String name : classes) {
            int byResource = index.find(resourceName(name));
            assertNotEquals(IndexFormat.NO_INDEX, byResource, name);
            assertEquals(byResource, index.findClass(name), name);
            assertEquals(resourceName(name), index.entryName(byResource));
        }
        assertEquals(IndexFormat.NO_INDEX, index.findClass("org.example.Almost"));
        assertEquals(IndexFormat.NO_INDEX, index.findClass("org.example.A$Missing"));
        assertEquals(IndexFormat.NO_INDEX, index.findClass("org.example.été.Absent"));
    }

    @Test
    void survivesACrowdedHashTable() throws IOException {
        TestIndexBuilder builder = new TestIndexBuilder().hashSlots(64);
        TestIndexBuilder.Jar application = builder.addJar(IndexFormat.CLASSES_PREFIX);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String name = "org/example/C" + i + ".class";
            names.add(name);
            application.addEntry(name).data(100 + i, 1, 1);
        }

        Index index = open(builder, true);
        assertEquals(64, index.hashSlots());
        assertTrue(index.maxProbe() > 0, "40 names in 64 slots must collide");
        assertTrue(index.maxProbe() <= IndexFormat.MAX_PROBE_LIMIT);
        for (String name : names) {
            assertNotEquals(IndexFormat.NO_INDEX, index.find(name), name);
            assertEquals(name, index.entryName(index.find(name)));
        }
        assertEquals(IndexFormat.NO_INDEX, index.find("org/example/C40.class"));
    }

    @Test
    void rejectsACandidateWhoseHashMatchesButWhoseNameDoesNot() throws IOException {
        TestIndexBuilder builder = new TestIndexBuilder().hashSlots(16).maxProbe(4);
        TestIndexBuilder.Jar application = builder.addJar(IndexFormat.CLASSES_PREFIX);
        application.addEntry("a/A.class").data(100, 1, 1);
        application.addEntry("a/B.class").data(200, 1, 1);
        byte[] bytes = selfContained(builder);

        // Record 0 is a/A.class and record 1 is a/B.class. Give B the hash of A and put it in A's slot, so
        // that a lookup of A meets a candidate whose hash matches and must reject it by its name bytes.
        ByteBuffer view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int entryTable = (int) view.getLong(IndexFormat.H_ENTRY_TABLE_OFFSET);
        int hashTable = (int) view.getLong(IndexFormat.H_HASH_TABLE_OFFSET);
        int hash = IndexFormat.hash("a/A.class");
        view.putInt(entryTable + IndexFormat.ENTRY_RECORD_SIZE + IndexFormat.E_NAME_HASH, hash);
        for (int slot = 0; slot < 16; slot++) {
            view.putInt(hashTable + slot * 4, IndexFormat.NO_INDEX);
        }
        int home = hash & 15;
        view.putInt(hashTable + home * 4, 1);
        view.putInt(hashTable + ((home + 1) & 15) * 4, 0);

        Index index = openBytes(bytes, true);
        assertEquals(0, index.find("a/A.class"));
        assertEquals("a/A.class", index.entryName(index.find("a/A.class")));
        assertEquals(0, index.findClass("a.A"));
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void findDirectoryMatchesFindOfTheNameWithATrailingSlash(boolean mapped) throws IOException {
        TestIndexBuilder builder = new TestIndexBuilder();
        TestIndexBuilder.Jar application = builder.addJar(IndexFormat.CLASSES_PREFIX);
        application.addEntry("org/example/A.class").data(100, 1, 1);
        application.addEntry("org/example/data/").data(0, 0, 0);
        application.addEntry("org/été/Café.txt").data(120, 1, 1);
        application.addEntry("中文/").data(0, 0, 0);
        application.addEntry("emoji😀/").data(0, 0, 0);
        builder.addJar(DEP).addEntry("org/example").data(200, 1, 1);

        Index index = open(builder, mapped);
        String[] names = {
            "org", "org/example", "org/example/data", "org/été", "中文", "emoji😀",
            "", "org/exampl", "org/examplX", "org/example/A.class", "org/nowhere", "org/ét", "中", "emoji",
            "emoji\uD83D", "org/example/data/"
        };
        for (String name : names) {
            assertEquals(index.find(name + "/"), index.findDirectory(name), name);
        }
        assertNotEquals(IndexFormat.NO_INDEX, index.findDirectory("org/été"), "a synthesised non-ASCII directory");
        assertNotEquals(IndexFormat.NO_INDEX, index.findDirectory("中文"));
        assertNotEquals(IndexFormat.NO_INDEX, index.findDirectory("emoji😀"));
        assertEquals("org/example/", index.entryName(index.findDirectory("org/example")),
                "the directory, not the dependency's file that has the name without the slash");
        assertEquals(IndexFormat.NO_INDEX, index.findDirectory("org/example/A.class"));
    }

    @Test
    void findDirectoryRejectsACandidateWhoseHashMatchesButWhoseNameDoesNot() throws IOException {
        TestIndexBuilder builder = new TestIndexBuilder().synthesizeDirectories(false).hashSlots(16).maxProbe(4);
        TestIndexBuilder.Jar application = builder.addJar(IndexFormat.CLASSES_PREFIX);
        application.addEntry("a/B/").data(0, 0, 0);
        application.addEntry("a/Bc").data(100, 1, 1);
        application.addEntry("é/").data(0, 0, 0);
        application.addEntry("éc").data(200, 1, 1);
        byte[] bytes = selfContained(builder);

        // Records 1 and 3 have the same UTF-8 length as the directories 0 and 2 and share their prefix. Give
        // each the hash of its directory and put it first in that directory's run, so that a lookup meets a
        // candidate whose hash, length and prefix all match and must reject it by its final byte, both on
        // the ASCII path and on the decoding path.
        ByteBuffer view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int entryTable = (int) view.getLong(IndexFormat.H_ENTRY_TABLE_OFFSET);
        int hashTable = (int) view.getLong(IndexFormat.H_HASH_TABLE_OFFSET);
        for (int slot = 0; slot < 16; slot++) {
            view.putInt(hashTable + slot * 4, IndexFormat.NO_INDEX);
        }
        int[][] placements = {{1, IndexFormat.hash("a/B/")}, {0, IndexFormat.hash("a/B/")},
            {3, IndexFormat.hash("é/")}, {2, IndexFormat.hash("é/")}};
        for (int[] placement : placements) {
            view.putInt(entryTable + placement[0] * IndexFormat.ENTRY_RECORD_SIZE + IndexFormat.E_NAME_HASH,
                    placement[1]);
            int slot = placement[1] & 15;
            while (view.getInt(hashTable + slot * 4) != IndexFormat.NO_INDEX) {
                slot = (slot + 1) & 15;
            }
            view.putInt(hashTable + slot * 4, placement[0]);
        }

        Index index = openBytes(bytes, true);
        assertEquals(0, index.findDirectory("a/B"));
        assertEquals(index.find("a/B/"), index.findDirectory("a/B"));
        assertEquals(2, index.findDirectory("é"));
        assertEquals(index.find("é/"), index.findDirectory("é"));
    }

    @Test
    void flagsNamesThatAlsoExistAsADirectory() throws IOException {
        TestIndexBuilder builder = new TestIndexBuilder();
        TestIndexBuilder.Jar application = builder.addJar(IndexFormat.CLASSES_PREFIX);
        application.addEntry("org/example/A.class").data(100, 1, 1);
        application.addEntry("org/example/data/").data(0, 0, 0);
        builder.addJar(DEP).addEntry("org/example/data").data(200, 1, 1);

        Index index = open(builder, true);

        assertTrue(index.entryDirectoryTwin(index.find("org/example/data")));
        assertFalse(index.entryDirectoryTwin(index.find("org/example/data/")), "never on a directory");
        assertFalse(index.entryDirectoryTwin(index.find("org/example/A.class")));
    }

    @Test
    void stopsProbingAtAnEmptySlot() throws IOException {
        TestIndexBuilder builder = new TestIndexBuilder().hashSlots(16);
        TestIndexBuilder.Jar application = builder.addJar(IndexFormat.CLASSES_PREFIX);
        application.addEntry("a/A.class").data(100, 1, 1);
        Index index = open(builder, true);
        assertEquals(IndexFormat.NO_INDEX, index.find("a/Missing.class"));
        assertEquals(IndexFormat.NO_INDEX, index.findClass("a.Missing"));
    }

    @Test
    void chainsRecordsOfTheSameNameInClasspathOrder() throws IOException {
        TestIndexBuilder builder = new TestIndexBuilder();
        builder.addJar(IndexFormat.CLASSES_PREFIX)
                .addEntry("shared/resource.txt").data(100, 1, 1);
        builder.addJar("MICRONAUT-INF/lib/first.jar")
                .addEntry("shared/resource.txt").data(200, 2, 2);
        builder.addJar("MICRONAUT-INF/lib/second.jar")
                .addEntry("shared/resource.txt").data(300, 3, 3);

        Index index = open(builder, true);
        int record = index.find("shared/resource.txt");
        for (int jar = 0; jar < 3; jar++) {
            assertNotEquals(IndexFormat.NO_INDEX, record);
            assertEquals(jar, index.entryJarId(record));
            assertEquals(100L * (jar + 1), index.entryDataOffset(record));
            assertEquals(record, index.resolveInJar(index.find("shared/resource.txt"), 25, jar));
            record = index.next(record);
        }
        assertEquals(IndexFormat.NO_INDEX, record);
        assertEquals(IndexFormat.NO_INDEX, index.resolveInJar(index.find("shared/resource.txt"), 25, 1234));
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void resolvesDuplicateEntriesLikeJarFileWithoutChangingClasspathOrVersionPrecedence(boolean mapped)
            throws IOException {
        TestIndexBuilder builder = new TestIndexBuilder();
        TestIndexBuilder.Jar first = builder.addJar(IndexFormat.CLASSES_PREFIX);
        first.addEntry("shared.txt").data(10, 1, 1);
        TestIndexBuilder.Jar dependency = builder.addJar(DEP).multiRelease();
        dependency.addEntry("shared.txt").data(20, 1, 1);
        dependency.addEntry("shared.txt").data(21, 1, 1);
        dependency.addEntry("META-INF/versions/17/shared.txt").data(170, 1, 1);
        dependency.addEntry("META-INF/versions/17/shared.txt").data(171, 1, 1);
        dependency.addEntry("META-INF/versions/21/shared.txt").data(210, 1, 1);
        dependency.addEntry("META-INF/versions/21/shared.txt").data(211, 1, 1);
        TestIndexBuilder.Jar second = builder.addJar("MICRONAUT-INF/lib/second.jar");
        second.addEntry("shared.txt").data(30, 1, 1);
        second.addEntry("shared.txt").data(31, 1, 1);

        Index index = open(builder, mapped);
        int head = index.find("shared.txt");

        assertEquals(10, index.entryDataOffset(index.resolve(head, 25)),
                "the first jar on the classpath still wins");
        assertEquals(211, index.entryDataOffset(index.resolveInJar(head, 25, 1)),
                "the last duplicate of the highest applicable version wins");
        assertEquals(171, index.entryDataOffset(index.resolveInJar(head, 20, 1)));
        assertEquals(21, index.entryDataOffset(index.resolveInJar(head, Index.BASE_VERSION, 1)));
        assertEquals(31, index.entryDataOffset(index.resolveInJar(head, 25, 2)));

        List<Long> chain = new ArrayList<>();
        for (int record = head; record != IndexFormat.NO_INDEX; record = index.next(record)) {
            chain.add(index.entryDataOffset(record));
        }
        assertEquals(List.of(10L, 211L, 210L, 171L, 170L, 21L, 20L, 31L, 30L), chain);

        List<Long> selected = new ArrayList<>();
        for (int record = index.resolve(head, 25); record != IndexFormat.NO_INDEX;
             record = index.resolve(index.nextJar(record), 25)) {
            selected.add(index.entryDataOffset(record));
        }
        assertEquals(List.of(10L, 211L, 31L), selected,
                "advancing from the current record selects one effective entry per jar");
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void resolvesMultiReleaseEntries(boolean mapped) throws IOException {
        TestIndexBuilder builder = new TestIndexBuilder();
        builder.addJar(IndexFormat.CLASSES_PREFIX).addEntry("org/example/A.class").data(50, 1, 1);
        TestIndexBuilder.Jar dependency = builder.addJar(DEP).multiRelease();
        dependency.addEntry("org/example/A.class").data(100, 1, 1);
        dependency.addEntry("META-INF/versions/17/org/example/A.class").data(170, 1, 1);
        dependency.addEntry("META-INF/versions/21/org/example/A.class").data(210, 1, 1);
        dependency.addEntry("META-INF/versions/99/org/example/A.class").data(990, 1, 1);
        dependency.addEntry("META-INF/versions/21/META-INF/services/org.example.Service").data(211, 1, 1);
        dependency.addEntry("META-INF/services/org.example.Service").data(111, 1, 1);

        Index index = open(builder, mapped);
        int head = index.find("org/example/A.class");
        assertEquals(0, index.entryJarId(head));

        int inDependency = index.next(head);
        assertEquals(1, index.entryJarId(inDependency));
        assertEquals(99, index.entryMrVersion(inDependency));
        assertEquals(21, index.entryMrVersion(index.next(inDependency)));
        assertEquals(17, index.entryMrVersion(index.next(index.next(inDependency))));
        assertEquals(0, index.entryMrVersion(index.next(index.next(index.next(inDependency)))));

        assertEquals(990, index.entryDataOffset(index.resolve(inDependency, 99)));
        assertEquals(990, index.entryDataOffset(index.resolve(inDependency, 120)));
        assertEquals(210, index.entryDataOffset(index.resolve(inDependency, 25)));
        assertEquals(210, index.entryDataOffset(index.resolve(inDependency, 21)));
        assertEquals(170, index.entryDataOffset(index.resolve(inDependency, 20)));
        assertEquals(100, index.entryDataOffset(index.resolve(inDependency, 16)));
        assertEquals(100, index.entryDataOffset(index.resolve(inDependency, Index.BASE_VERSION)));
        assertEquals(100, index.entryDataOffset(index.resolve(inDependency, 0)));

        // The chain head is in the application layer, which has no versioned entries at all.
        assertEquals(50, index.entryDataOffset(index.resolve(head, 99)));
        assertEquals(210, index.entryDataOffset(index.resolveInJar(head, 25, 1)));
        assertEquals(100, index.entryDataOffset(index.resolveInJar(head, Index.BASE_VERSION, 1)));

        int alias = index.resolve(inDependency, 25);
        assertTrue(index.entryVersionedAlias(alias));
        assertFalse(index.entryPhysical(alias));
        assertEquals("org/example/A.class", index.entryName(alias));
        assertEquals("META-INF/versions/21/org/example/A.class",
                index.entryName(index.entryPhysicalIndex(alias)));
    }

    @Test
    void neverAliasesNamesUnderMetaInf() throws IOException {
        TestIndexBuilder builder = new TestIndexBuilder();
        TestIndexBuilder.Jar dependency = builder.addJar(IndexFormat.CLASSES_PREFIX).multiRelease();
        dependency.addEntry("META-INF/versions/21/META-INF/services/org.example.Service").data(1, 1, 1);
        dependency.addEntry("META-INF/versions/21/META-INF/MANIFEST.MF").data(2, 1, 1);
        dependency.addEntry("META-INF/versions/21/org/example/A.class").data(3, 1, 1);

        Index index = open(builder, true);
        assertEquals(IndexFormat.NO_INDEX, index.find("META-INF/services/org.example.Service"));
        assertEquals(IndexFormat.NO_INDEX, index.find("META-INF/MANIFEST.MF"));
        assertNotEquals(IndexFormat.NO_INDEX, index.find("org/example/A.class"));
        for (int record = 0; record < index.entryCount(); record++) {
            if (index.entryVersionedAlias(record)) {
                assertFalse(index.entryName(record).startsWith("META-INF/"),
                        index.entryName(record) + " must not be an alias");
            }
        }
    }

    @Test
    void validatesNestedJarsLazily() throws IOException {
        byte[] payload = "nested".getBytes("UTF-8");
        TestIndexBuilder builder = new TestIndexBuilder();
        builder.addJar(IndexFormat.CLASSES_PREFIX);
        TestIndexBuilder.Jar dependency = builder.addJar(DEP);
        dependency.addEntry("a/B.class").data(0, 1, 1);
        byte[] draft = builder.build();

        TestArchiveBuilder archive = new TestArchiveBuilder();
        archive.stored("META-INF/MANIFEST.MF", payload);
        archive.reserve(IndexFormat.INDEX_ENTRY_NAME, draft.length);
        long nested = archive.stored(DEP, payload);
        dependency.location(nested, payload.length, archive.localHeaderOffset(DEP));
        archive.replace(IndexFormat.INDEX_ENTRY_NAME, builder.build());

        File file = newFile();
        archive.writeTo(file);
        ArchiveSource source = openSource(file, true);
        Index index = Index.open(source);
        index.validateJar(0);
        index.validateJar(1);
        index.validateJar(1);

        // Break the local file header of the nested jar and open the archive again.
        byte[] broken = archive.build();
        broken[(int) archive.localHeaderOffset(DEP)] = 'X';
        File brokenFile = newFile();
        try (OutputStream out = new FileOutputStream(brokenFile)) {
            out.write(broken);
        }
        Index brokenIndex = Index.open(openSource(brokenFile, true));
        brokenIndex.validateJar(0);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> brokenIndex.validateJar(1));
        assertTrue(failure.getMessage().startsWith(Index.REBUILD_MESSAGE), failure.getMessage());
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void reportsANestedJarHeaderOutsideTheArchiveAsStale(boolean mapped) throws IOException {
        // The jar table check at open bounds each jar's data, not its local header offset, so a header
        // offset past the end reaches validateJar, and it means the index does not describe this file.
        for (long header : new long[] {-1L, 1L << 40}) {
            Index index = Index.open(openSource(nestedJarArchive(header), mapped));
            index.validateJar(0);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> index.validateJar(1));
            assertTrue(failure.getMessage().startsWith(Index.REBUILD_MESSAGE), failure.getMessage());
            assertTrue(failure.getMessage().contains("is outside the archive"), failure.getMessage());
        }
    }

    @Test
    void reportsAHeaderThatCannotBeReadAsAnIoFailure() throws IOException {
        // A read that fails says nothing about the jar: before, a closed positional source was reported as
        // a jar modified after packaging, which sent users to rebuild an archive that was fine.
        ArchiveSource source = openSource(nestedJarArchive(null), false);
        assertFalse(source.mapped());
        Index index = Index.open(source);
        index.validateJar(0);
        source.close();

        UncheckedIOException failure = assertThrows(UncheckedIOException.class, () -> index.validateJar(1));
        assertFalse(failure.getMessage().contains(Index.REBUILD_MESSAGE), failure.getMessage());
        assertTrue(failure.getMessage().contains(DEP), failure.getMessage());
        assertInstanceOf(ClosedChannelException.class, failure.getCause());
    }

    @Test
    void rejectsAnIndexWhoseMagicIsWrong() {
        assertStale(patchInt(valid(), IndexFormat.H_MAGIC, 0));
    }

    @Test
    void rejectsAnUnsupportedFormatVersion() throws IOException {
        byte[] bytes = valid();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(IndexFormat.H_FORMAT_VERSION, (short) 2);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> openBytes(bytes, true));
        assertTrue(failure.getMessage().contains("Unsupported runner jar format version 2"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("format version 1"), failure.getMessage());
    }

    @Test
    void rejectsAHashTableThatIsNotAPowerOfTwo() {
        assertStale(patchInt(valid(), IndexFormat.H_HASH_SLOTS, 24));
    }

    @Test
    void rejectsAnEmptyHashTable() {
        assertStale(patchInt(valid(), IndexFormat.H_HASH_SLOTS, 0));
    }

    @Test
    void rejectsAProbeLimitAboveTheFormatMaximum() {
        assertStale(patchInt(valid(), IndexFormat.H_MAX_PROBE, IndexFormat.MAX_PROBE_LIMIT + 1));
    }

    @Test
    void rejectsSectionsThatDoNotFitInTheIndex() {
        assertStale(patchLong(valid(), IndexFormat.H_ENTRY_TABLE_OFFSET, 1L << 40));
        assertStale(patchLong(valid(), IndexFormat.H_JAR_TABLE_OFFSET, 1L << 40));
        assertStale(patchLong(valid(), IndexFormat.H_HASH_TABLE_OFFSET, 1L << 40));
        assertStale(patchLong(valid(), IndexFormat.H_PACKAGE_TABLE_OFFSET, 1L << 40));
        assertStale(patchLong(valid(), IndexFormat.H_STRING_TABLE_OFFSET, 1L << 40));
        assertStale(patchLong(valid(), IndexFormat.H_STRING_TABLE_LENGTH, 1L << 40));
        assertStale(patchLong(valid(), IndexFormat.H_JAR_TABLE_OFFSET, 8));
        assertStale(patchInt(valid(), IndexFormat.H_ENTRY_COUNT, 1 << 20));
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void rejectsOverflowingStringTableLengthDuringOpen(boolean mapped) throws IOException {
        byte[] bytes = validWithoutHeaderStrings();
        patchLong(bytes, IndexFormat.H_STRING_TABLE_LENGTH, Long.MAX_VALUE);

        assertStale(bytes, mapped);
    }

    @ParameterizedTest(name = "mapped={0}")
    @ValueSource(booleans = {true, false})
    void rejectsOverflowingJarSpanDuringOpen(boolean mapped) throws IOException {
        byte[] bytes = validWithoutHeaderStrings();
        int jarTable = (int) ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                .getLong(IndexFormat.H_JAR_TABLE_OFFSET);
        patchLong(bytes, jarTable + IndexFormat.J_DATA_OFFSET, Long.MAX_VALUE - 16);
        patchLong(bytes, jarTable + IndexFormat.J_DATA_LENGTH, 64);

        assertStale(bytes, mapped);
    }

    @Test
    void validatesSectionArithmeticBoundaries() throws IOException {
        byte[] template = validWithoutHeaderStrings();
        long limit = template.length;
        long[][] validRanges = {
            {limit, 0},
            {limit - 1, 1}
        };
        for (long[] range : validRanges) {
            byte[] bytes = template.clone();
            patchLong(bytes, IndexFormat.H_STRING_TABLE_OFFSET, range[0]);
            patchLong(bytes, IndexFormat.H_STRING_TABLE_LENGTH, range[1]);
            assertEquals(1, openBytes(bytes, true).jarCount());
        }

        long stringTable = ByteBuffer.wrap(template).order(ByteOrder.LITTLE_ENDIAN)
                .getLong(IndexFormat.H_STRING_TABLE_OFFSET);
        long[][] invalidRanges = {
            {-1, 0},
            {stringTable, -1},
            {limit + 1, 0},
            {limit, 1},
            {stringTable, Long.MAX_VALUE},
            {Long.MAX_VALUE - 16, 64}
        };
        for (long[] range : invalidRanges) {
            byte[] bytes = template.clone();
            patchLong(bytes, IndexFormat.H_STRING_TABLE_OFFSET, range[0]);
            patchLong(bytes, IndexFormat.H_STRING_TABLE_LENGTH, range[1]);
            assertStale(bytes);
        }

        assertStale(patchInt(template.clone(), IndexFormat.H_ENTRY_COUNT, -1));
    }

    @Test
    void rejectsAJarThatClaimsEntriesTheIndexDoesNotHave() {
        byte[] bytes = valid();
        int jarTable = (int) ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                .getLong(IndexFormat.H_JAR_TABLE_OFFSET);
        assertStale(patchInt(bytes, jarTable + IndexFormat.J_ENTRY_COUNT, 1 << 20));
        assertStale(patchInt(valid(), jarTable + IndexFormat.J_PACKAGE_COUNT, 4));
        assertStale(patchLong(valid(), jarTable + IndexFormat.J_DATA_LENGTH, 1L << 40));
    }

    @Test
    void rejectsAHeaderStringReferenceOutsideTheStringTable() {
        assertStale(patchInt(valid(), IndexFormat.H_START_CLASS, 1 << 20));
        assertStale(patchInt(valid(), IndexFormat.H_ENTRY_STUB_CLASS, 1 << 20));
        assertStale(patchInt(valid(), IndexFormat.H_LAUNCHER_VERSION, 1 << 20));
    }

    @Test
    void rejectsAnIndexWhoseRecordedFileLengthIsWrong() throws IOException {
        byte[] bytes = valid();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(IndexFormat.H_OUTER_FILE_LENGTH, bytes.length + 1L);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> openBytes(bytes, true));
        assertTrue(failure.getMessage().startsWith(Index.REBUILD_MESSAGE), failure.getMessage());
        assertTrue(failure.getMessage().contains("bytes but the file is"), failure.getMessage());
    }

    @Test
    void rejectsAnIndexShorterThanItsHeader() throws IOException {
        byte[] bytes = valid();
        ArchiveSource source = openSource(write(bytes), true);
        assertThrows(IllegalStateException.class, () -> Index.open(source, 0, 64));
        assertThrows(IllegalStateException.class, () -> Index.open(source, 0, 0));
    }

    @Test
    void rejectsACorruptHashSlotAndAWildRecordIndex() throws IOException {
        byte[] bytes = valid();
        int hashTable = (int) ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                .getLong(IndexFormat.H_HASH_TABLE_OFFSET);
        int slot = IndexFormat.hash("org/example/A.class") & (indexOf(bytes) - 1);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(hashTable + slot * 4, 4096);
        Index index = openBytes(bytes, true);
        assertThrows(IllegalStateException.class, () -> index.find("org/example/A.class"));
        assertThrows(IllegalStateException.class, () -> index.entryName(4096));
        assertThrows(IllegalStateException.class, () -> index.jarName(7));
        assertThrows(IllegalStateException.class, () -> index.packageName(0));
    }

    @Test
    void rejectsAnEntryNameOutsideTheStringTable() throws IOException {
        byte[] bytes = valid();
        int entryTable = (int) ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                .getLong(IndexFormat.H_ENTRY_TABLE_OFFSET);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(entryTable + IndexFormat.E_NAME, 1 << 20);
        Index index = openBytes(bytes, true);
        assertThrows(IllegalStateException.class, () -> index.entryName(0));
        assertThrows(IllegalStateException.class, index::validateStringReferences);
    }

    @Test
    void returnsNullForTheAbsentString() throws IOException {
        Index index = openBytes(valid(), true);
        assertNull(index.string(0));
    }

    @Test
    void computesTheEffectiveMultiReleaseVersion() {
        int runtime = Runtime.version().feature();
        assertEquals(runtime, Index.effectiveMultiReleaseVersion());
        System.setProperty("jdk.util.jar.enableMultiRelease", "false");
        try {
            assertEquals(Index.BASE_VERSION, Index.effectiveMultiReleaseVersion());
        } finally {
            System.clearProperty("jdk.util.jar.enableMultiRelease");
        }
        System.setProperty("jdk.util.jar.version", "11");
        try {
            assertEquals(11, Index.effectiveMultiReleaseVersion());
            System.setProperty("jdk.util.jar.version", "2");
            assertEquals(Index.BASE_VERSION, Index.effectiveMultiReleaseVersion());
            System.setProperty("jdk.util.jar.version", String.valueOf(runtime + 100));
            assertEquals(runtime, Index.effectiveMultiReleaseVersion());
            System.setProperty("jdk.util.jar.version", "rubbish");
            assertEquals(runtime, Index.effectiveMultiReleaseVersion());
        } finally {
            System.clearProperty("jdk.util.jar.version");
        }
    }

    /**
     * Writes an archive holding the application layer and one nested jar, {@link #DEP}, whose index record
     * names {@code header} as its local file header offset, or the real offset when {@code header} is
     * {@code null}.
     */
    private File nestedJarArchive(Long header) throws IOException {
        byte[] payload = "nested".getBytes(StandardCharsets.UTF_8);
        TestIndexBuilder builder = new TestIndexBuilder();
        builder.addJar(IndexFormat.CLASSES_PREFIX);
        TestIndexBuilder.Jar dependency = builder.addJar(DEP);
        dependency.addEntry("a/B.class").data(0, 1, 1);
        byte[] draft = builder.build();

        TestArchiveBuilder archive = new TestArchiveBuilder();
        archive.stored("META-INF/MANIFEST.MF", payload);
        archive.reserve(IndexFormat.INDEX_ENTRY_NAME, draft.length);
        long nested = archive.stored(DEP, payload);
        dependency.location(nested, payload.length, header != null ? header : archive.localHeaderOffset(DEP));
        archive.replace(IndexFormat.INDEX_ENTRY_NAME, builder.build());
        File file = newFile();
        archive.writeTo(file);
        return file;
    }

    private static String resourceName(String binaryName) {
        return binaryName.replace('.', '/') + ".class";
    }

    private static byte[] patchInt(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
        return bytes;
    }

    private static byte[] patchLong(byte[] bytes, int offset, long value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, value);
        return bytes;
    }

    private static int indexOf(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(IndexFormat.H_HASH_SLOTS);
    }

    private static byte[] selfContained(TestIndexBuilder builder) {
        byte[] draft = builder.build();
        builder.outerFileLength(draft.length);
        byte[] bytes = builder.build();
        assertEquals(draft.length, bytes.length, "the index size must not depend on its content");
        return bytes;
    }

    private byte[] valid() {
        TestIndexBuilder builder = new TestIndexBuilder().startClass("org.example.Application");
        builder.addJar(IndexFormat.CLASSES_PREFIX).addEntry("org/example/A.class").data(100, 1, 1);
        return selfContained(builder);
    }

    private byte[] validWithoutHeaderStrings() {
        TestIndexBuilder builder = new TestIndexBuilder();
        builder.addJar(IndexFormat.CLASSES_PREFIX);
        return selfContained(builder);
    }

    private void assertStale(byte[] bytes) {
        assertStale(bytes, true);
    }

    private void assertStale(byte[] bytes, boolean mapped) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> openBytes(bytes, mapped));
        assertTrue(failure.getMessage().startsWith(Index.REBUILD_MESSAGE), failure.getMessage());
    }

    private Index open(TestIndexBuilder builder, boolean mapped) throws IOException {
        return openBytes(selfContained(builder), mapped);
    }

    private Index openBytes(byte[] bytes, boolean mapped) throws IOException {
        return Index.open(openSource(write(bytes), mapped), 0, bytes.length);
    }

    private File write(byte[] bytes) throws IOException {
        File file = newFile();
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return file;
    }

    private File newFile() {
        files++;
        return temporary.resolve("archive-" + files + ".bin").toFile();
    }

    private ArchiveSource openSource(File file, boolean mapped) throws IOException {
        System.setProperty(ArchiveSource.MMAP_PROPERTY, Boolean.toString(mapped));
        ArchiveSource source = ArchiveSource.open(file);
        sources.add(source);
        return source;
    }
}
