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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link IndexWriter}: the bytes it produces are read by the launcher without any parsing, so
 * every offset, flag and chain has to be exactly what {@link IndexFormat} specifies.
 *
 * <p>The most valuable test here is {@link #agreesWithTheLaunchersOwnIndexBuilder()}, which builds the same
 * logical index with the launcher's {@code TestIndexBuilder} — an implementation written independently from
 * the same specification — and compares the two byte arrays. Two writers agreeing by accident is unlikely;
 * two writers agreeing on 128 bytes of header, fixed size records, alignment, chain order and string table
 * layout is essentially a proof that both read the specification the same way.</p>
 */
class IndexWriterTest {

    @Test
    void layoutReportsTheLengthTheWriteProduces() {
        IndexWriter writer = new IndexWriter().startClass("com.example.App");
        IndexWriter.JarSpec application = writer.addJar(IndexFormat.CLASSES_PREFIX);
        application.addEntry("com/example/App.class").sizes(120, 120).crc32(0x1234L).dosTime(0x2A210000L);
        application.addEntry("application.yml").sizes(40, 40).crc32(0x4321L).dosTime(0x2A210000L);

        IndexWriter.Layout layout = writer.layout();
        byte[] first = writer.write(layout, 4096);

        assertEquals(layout.length(), first.length);

        // The offsets are the only thing that may change after the layout is fixed.
        application.entries().get(0).dataOffset(2048);
        byte[] second = writer.write(layout, 4096);
        assertEquals(first.length, second.length);
        assertFalse(Arrays.equals(first, second),
                "moving an entry has to change the bytes of the index");
    }

    @Test
    void headerDescribesEveryAlignedSection() {
        IndexWriter writer = new IndexWriter()
                .startClass("com.example.App")
                .launcherVersion("1.0.0")
                .headerFlags(IndexFormat.HEADER_FLAG_NESTED_STORED);
        IndexWriter.JarSpec application = writer.addJar(IndexFormat.CLASSES_PREFIX);
        application.addEntry("com/example/App.class").sizes(120, 120);
        application.addPackage("com.example").attributes("s", "1", "v", "i", "2", "w").sealed(true);
        IndexWriter.JarSpec dependency = writer.addJar("MICRONAUT-INF/lib/dep.jar");
        dependency.addEntry("com/example/dep/Dep.class").sizes(8, 8);

        Decoded index = new Decoded(writer.write(writer.layout(), 9999));

        assertEquals(IndexFormat.MAGIC, index.i32(IndexFormat.H_MAGIC));
        assertEquals(IndexFormat.FORMAT_VERSION, index.u16(IndexFormat.H_FORMAT_VERSION));
        assertEquals(IndexFormat.HEADER_FLAG_NESTED_STORED, index.u16(IndexFormat.H_FLAGS));
        assertEquals(2, index.jarCount());
        assertEquals(1, index.packageCount());
        assertEquals(9999L, index.u64(IndexFormat.H_OUTER_FILE_LENGTH));
        assertEquals("com.example.App", index.string(index.i32(IndexFormat.H_START_CLASS)));
        assertEquals("1.0.0", index.string(index.i32(IndexFormat.H_LAUNCHER_VERSION)));
        assertEquals(0, index.i32(IndexFormat.H_ENTRY_STUB_CLASS), "no stub is generated yet");

        assertEquals(IndexFormat.HEADER_SIZE, index.jarTable());
        for (long offset : new long[] {index.jarTable(), index.packageTable(), index.entryTable(),
                index.hashTable(), index.stringTable()}) {
            assertEquals(0, offset % 8, "every section starts 8 byte aligned");
        }
        assertTrue(index.packageTable() >= index.jarTable() + 2L * IndexFormat.JAR_RECORD_SIZE);
        assertTrue(index.entryTable() >= index.packageTable() + IndexFormat.PACKAGE_RECORD_SIZE);
        assertTrue(index.hashTable()
                >= index.entryTable() + (long) index.entryCount() * IndexFormat.ENTRY_RECORD_SIZE);
        assertTrue(index.stringTable() >= index.hashTable() + 4L * index.hashSlots());
        assertEquals(index.stringTable() + index.stringTableLength(), index.length());
        assertEquals(0, index.u16((int) index.stringTable()), "offset 0 of the table is the empty string");
    }

    @Test
    void recordsTheLargestStoredClassOnlyForPositionalReads() {
        for (boolean positional : new boolean[] {false, true}) {
            int flags = IndexFormat.HEADER_FLAG_NESTED_STORED
                    | (positional ? IndexFormat.HEADER_FLAG_POSITIONAL_READS : 0);
            IndexWriter writer = new IndexWriter().startClass("com.example.App").headerFlags(flags);
            IndexWriter.JarSpec application = writer.addJar(IndexFormat.CLASSES_PREFIX);
            application.addEntry("com/example/App.class").sizes(120, 120);
            // Larger, but not a class, or not STORED: neither sizes a pooled class buffer.
            application.addEntry("payload.bin").sizes(90_000, 90_000);
            application.addEntry("com/example/Deflated.class").sizes(20_000, 70_000)
                    .method(IndexFormat.METHOD_DEFLATED);
            IndexWriter.JarSpec dependency = writer.addJar("MICRONAUT-INF/lib/dep.jar");
            dependency.addEntry("org/dep/Largest.class").sizes(5_000, 5_000);
            dependency.addEntry("org/dep/Small.class").sizes(300, 300);

            Decoded index = new Decoded(writer.write(writer.layout(), 9999));

            assertEquals(flags, index.u16(IndexFormat.H_FLAGS));
            if (positional) {
                assertEquals(5_000L, index.u32(IndexFormat.H_LARGEST_STORED_CLASS));
                for (int at = IndexFormat.H_RESERVED; at < IndexFormat.H_LARGEST_STORED_CLASS; at++) {
                    assertEquals(0, index.u8(at), "reserved byte " + at);
                }
            } else {
                for (int at = IndexFormat.H_RESERVED; at < IndexFormat.HEADER_SIZE; at++) {
                    assertEquals(0, index.u8(at), "a mapped archive's header keeps byte " + at + " zero");
                }
            }
        }
    }

    @Test
    void applicationRecordsKeepTheirOrderAndCarryTheirEntryData() {
        IndexWriter writer = new IndexWriter();
        IndexWriter.JarSpec application = writer.addJar(IndexFormat.CLASSES_PREFIX);
        application.addEntry("b.txt").sizes(3, 3).crc32(0xAABBCCDDL).dosTime(0x2A210000L).dataOffset(700);
        application.addEntry("a.txt").sizes(9, 20).method(IndexFormat.METHOD_DEFLATED).dataOffset(800);

        Decoded index = new Decoded(writer.write(writer.layout(), 1024));

        assertEquals("b.txt", index.entryName(0), "records follow the order they were added, not the alphabet");
        assertEquals("a.txt", index.entryName(1));
        assertEquals(0xAABBCCDDL, index.entryCrc32(0));
        assertEquals(0x2A210000L, index.entryDosTime(0));
        assertEquals(700L, index.entryDataOffset(0));
        assertEquals(IndexFormat.METHOD_STORED, index.entryMethod(0));
        assertEquals(IndexFormat.METHOD_DEFLATED, index.entryMethod(1));
        assertEquals(9L, index.entryCompressedSize(1));
        assertEquals(20L, index.entryUncompressedSize(1));
        assertEquals(IndexFormat.ENTRY_FLAG_PHYSICAL, index.entryFlags(0));
        assertEquals(0, index.entryPhysicalIndex(0), "a physical record points at itself");
        assertEquals(IndexFormat.hash("b.txt"), index.i32(index.entryOffset(0) + IndexFormat.E_NAME_HASH));
        assertEquals(5, index.u16(index.entryOffset(0) + IndexFormat.E_NAME_LENGTH));
    }

    @Test
    void versionedEntriesAreAliasedExceptTheOnesUnderMetaInf() {
        IndexWriter writer = new IndexWriter();
        IndexWriter.JarSpec jar = writer.addJar(IndexFormat.CLASSES_PREFIX)
                .addFlags(IndexFormat.JAR_FLAG_MULTI_RELEASE);
        jar.addEntry("com/example/A.class").sizes(1, 1).dataOffset(100);
        jar.addEntry("META-INF/versions/17/com/example/A.class").sizes(2, 2).dataOffset(200);
        jar.addEntry("META-INF/versions/21/com/example/A.class").sizes(3, 3).dataOffset(300);
        jar.addEntry("META-INF/versions/21/META-INF/services/foo").sizes(4, 4).dataOffset(400);

        Decoded index = new Decoded(writer.write(writer.layout(), 1024));

        int base = index.find("com/example/A.class");
        assertNotEquals(IndexFormat.NO_INDEX, base);
        // The chain hands out the highest version first, then the lower one, then the base entry.
        assertEquals(21, index.entryMrVersion(base));
        assertEquals(300L, index.entryDataOffset(base));
        assertTrue((index.entryFlags(base) & IndexFormat.ENTRY_FLAG_VERSIONED_ALIAS) != 0);
        assertEquals(2, index.entryPhysicalIndex(base), "the alias points at the record it copies");
        int second = index.entryNext(base);
        assertEquals(17, index.entryMrVersion(second));
        int third = index.entryNext(second);
        assertEquals(0, index.entryMrVersion(third));
        assertEquals(100L, index.entryDataOffset(third));
        assertEquals(IndexFormat.NO_INDEX, index.entryNext(third));

        assertEquals(IndexFormat.NO_INDEX, index.find("META-INF/services/foo"),
                "META-INF is never versioned, so that entry keeps only its literal name");
        assertNotEquals(IndexFormat.NO_INDEX, index.find("META-INF/versions/21/META-INF/services/foo"));
    }

    @Test
    void versionedEntriesOfAPlainJarAreNotAliased() {
        IndexWriter writer = new IndexWriter();
        IndexWriter.JarSpec jar = writer.addJar(IndexFormat.CLASSES_PREFIX);
        jar.addEntry("META-INF/versions/17/com/example/A.class").sizes(2, 2);

        Decoded index = new Decoded(writer.write(writer.layout(), 1024));

        assertEquals(IndexFormat.NO_INDEX, index.find("com/example/A.class"));
    }

    @Test
    void impliedDirectoriesAreSynthesisedOnceAndExcludedFromThePhysicalRange() {
        IndexWriter writer = new IndexWriter();
        IndexWriter.JarSpec jar = writer.addJar(IndexFormat.CLASSES_PREFIX);
        jar.addEntry("com/example/A.class").sizes(1, 1);
        jar.addEntry("com/example/deep/B.class").sizes(1, 1);
        jar.addEntry("com/").sizes(0, 0);

        Decoded index = new Decoded(writer.write(writer.layout(), 1024));

        int explicit = index.find("com/");
        assertTrue((index.entryFlags(explicit) & IndexFormat.ENTRY_FLAG_PHYSICAL) != 0);
        assertTrue((index.entryFlags(explicit) & IndexFormat.ENTRY_FLAG_DIRECTORY) != 0);
        assertEquals(0, index.entryFlags(explicit) & IndexFormat.ENTRY_FLAG_SYNTHETIC_DIR,
                "a directory that is stored in the archive is not synthetic");

        for (String name : new String[] {"com/example/", "com/example/deep/"}) {
            int record = index.find(name);
            assertNotEquals(IndexFormat.NO_INDEX, record, name + " should resolve");
            assertEquals(IndexFormat.ENTRY_FLAG_DIRECTORY | IndexFormat.ENTRY_FLAG_SYNTHETIC_DIR,
                    index.entryFlags(record));
            assertEquals(IndexFormat.NO_INDEX, index.entryPhysicalIndex(record));
            assertEquals(0L, index.entryDataOffset(record));
        }
        assertEquals(5, index.entryCount(), "three entries plus the two directories they imply");
    }

    @Test
    void flagsEveryRecordOfANameThatAlsoExistsAsADirectory() {
        IndexWriter writer = new IndexWriter();
        IndexWriter.JarSpec application = writer.addJar(IndexFormat.CLASSES_PREFIX);
        application.addEntry("explicit/").sizes(0, 0);
        application.addEntry("implied/child.txt").sizes(1, 1);
        application.addEntry("same").sizes(1, 1);
        application.addEntry("same/").sizes(0, 0);
        application.addEntry("plain.txt").sizes(1, 1);
        application.addEntry("doubled//").sizes(0, 0);
        IndexWriter.JarSpec multiRelease = writer.addJar("MICRONAUT-INF/lib/a.jar")
                .addFlags(IndexFormat.JAR_FLAG_MULTI_RELEASE);
        multiRelease.addEntry("explicit").sizes(1, 1);
        multiRelease.addEntry("implied").sizes(1, 1);
        multiRelease.addEntry("versioned").sizes(1, 1);
        multiRelease.addEntry("META-INF/versions/11/versioned").sizes(1, 1);
        multiRelease.addEntry("META-INF/versions/17/aliased/").sizes(0, 0);
        IndexWriter.JarSpec other = writer.addJar("MICRONAUT-INF/lib/b.jar");
        other.addEntry("versioned/").sizes(0, 0);
        other.addEntry("aliased").sizes(1, 1);

        Decoded index = new Decoded(writer.write(writer.layout(), 1024));

        assertEquals(List.of(1), twinFlaggedJars(index, "explicit"),
                "a file in a later jar, twin of the application's stored directory");
        assertEquals(IndexFormat.ENTRY_FLAG_DIRECTORY | IndexFormat.ENTRY_FLAG_SYNTHETIC_DIR,
                index.entryFlags(index.find("implied/")), "the twin is only implied by implied/child.txt");
        assertEquals(List.of(1), twinFlaggedJars(index, "implied"),
                "a file in a later jar, twin of a directory the application only implies");
        assertEquals(List.of(0), twinFlaggedJars(index, "same"), "a file and a directory in one jar");
        int versioned = index.find("versioned");
        assertTrue((index.entryFlags(versioned) & IndexFormat.ENTRY_FLAG_VERSIONED_ALIAS) != 0,
                "the chain starts with the alias of META-INF/versions/11/versioned");
        assertEquals(List.of(1, 1), twinFlaggedJars(index, "versioned"),
                "the alias and the base record of a multi-release jar, twins of a later jar's directory");
        int aliased = index.find("aliased/");
        assertEquals(IndexFormat.ENTRY_FLAG_VERSIONED_ALIAS | IndexFormat.ENTRY_FLAG_DIRECTORY,
                index.entryFlags(aliased), "the only aliased/ record is a versioned alias");
        assertEquals(IndexFormat.NO_INDEX, index.entryNext(aliased));
        assertEquals(List.of(2), twinFlaggedJars(index, "aliased"),
                "a file whose twin is the versioned alias of a directory");

        for (String name : new String[] {"plain.txt", "implied/child.txt", "META-INF/versions/11/versioned"}) {
            int record = index.find(name);
            assertNotEquals(IndexFormat.NO_INDEX, record, name);
            assertEquals(0, index.entryFlags(record) & IndexFormat.ENTRY_FLAG_DIRECTORY_TWIN,
                    name + " has no directory twin");
        }
        assertNotEquals(IndexFormat.NO_INDEX, index.find("doubled/"),
                "doubled// implies doubled/, which is a directory and so never flagged");
        for (int record = 0; record < index.entryCount(); record++) {
            String name = index.entryName(record);
            boolean twin = !name.endsWith("/") && index.find(name + "/") != IndexFormat.NO_INDEX;
            assertEquals(twin, (index.entryFlags(record) & IndexFormat.ENTRY_FLAG_DIRECTORY_TWIN) != 0,
                    "record " + record + " '" + name + "' is flagged exactly when its name has a directory twin");
        }
    }

    /**
     * Walks the chain of a name and returns the jar of every record, asserting that each one carries
     * {@link IndexFormat#ENTRY_FLAG_DIRECTORY_TWIN}.
     *
     * @param index the index
     * @param name  the logical name
     * @return the jar ids of the chain's records, in chain order
     */
    private static List<Integer> twinFlaggedJars(Decoded index, String name) {
        List<Integer> jars = new ArrayList<>();
        for (int record = index.find(name); record != IndexFormat.NO_INDEX; record = index.entryNext(record)) {
            assertTrue((index.entryFlags(record) & IndexFormat.ENTRY_FLAG_DIRECTORY_TWIN) != 0,
                    "record " + record + " of " + name + " in jar " + index.entryJarId(record));
            jars.add(index.entryJarId(record));
        }
        return jars;
    }

    @Test
    void chainsRunInClasspathOrderAcrossJars() {
        IndexWriter writer = new IndexWriter();
        writer.addJar(IndexFormat.CLASSES_PREFIX).addEntry("shared.txt").sizes(1, 1).dataOffset(10);
        writer.addJar("MICRONAUT-INF/lib/a.jar").addEntry("shared.txt").sizes(2, 2).dataOffset(20);
        writer.addJar("MICRONAUT-INF/lib/b.jar").addEntry("shared.txt").sizes(3, 3).dataOffset(30);

        Decoded index = new Decoded(writer.write(writer.layout(), 1024));

        int record = index.find("shared.txt");
        assertEquals(0, index.entryJarId(record));
        record = index.entryNext(record);
        assertEquals(1, index.entryJarId(record));
        record = index.entryNext(record);
        assertEquals(2, index.entryJarId(record));
        assertEquals(IndexFormat.NO_INDEX, index.entryNext(record));
    }

    @Test
    void duplicateEntriesPreferTheLastRecordWithinEachJarAndVersion() {
        IndexWriter writer = new IndexWriter();
        writer.addJar(IndexFormat.CLASSES_PREFIX)
                .addEntry("shared.txt").sizes(1, 1).dataOffset(10);
        IndexWriter.JarSpec first = writer.addJar("MICRONAUT-INF/lib/a.jar")
                .addFlags(IndexFormat.JAR_FLAG_MULTI_RELEASE);
        first.addEntry("shared.txt").sizes(1, 1).dataOffset(20);
        first.addEntry("shared.txt").sizes(1, 1).dataOffset(21);
        first.addEntry("META-INF/versions/17/shared.txt").sizes(1, 1).dataOffset(170);
        first.addEntry("META-INF/versions/17/shared.txt").sizes(1, 1).dataOffset(171);
        first.addEntry("META-INF/versions/21/shared.txt").sizes(1, 1).dataOffset(210);
        first.addEntry("META-INF/versions/21/shared.txt").sizes(1, 1).dataOffset(211);
        IndexWriter.JarSpec second = writer.addJar("MICRONAUT-INF/lib/b.jar");
        second.addEntry("shared.txt").sizes(1, 1).dataOffset(30);
        second.addEntry("shared.txt").sizes(1, 1).dataOffset(31);

        Decoded index = new Decoded(writer.write(writer.layout(), 1024));

        List<Long> chain = new ArrayList<>();
        for (int record = index.find("shared.txt"); record != IndexFormat.NO_INDEX;
                record = index.entryNext(record)) {
            chain.add(index.entryDataOffset(record));
        }
        assertEquals(List.of(10L, 211L, 210L, 171L, 170L, 21L, 20L, 31L, 30L), chain,
                "classpath and MR-version precedence stay unchanged, but the last duplicate wins");
        assertEquals(List.of(10L, 20L, 21L, 170L, 171L, 210L, 211L, 30L, 31L),
                physicalOffsets(index), "physical records keep central-directory order");
    }

    private static List<Long> physicalOffsets(Decoded index) {
        List<Long> offsets = new ArrayList<>();
        for (int record = 0; record < index.entryCount(); record++) {
            if ((index.entryFlags(record) & IndexFormat.ENTRY_FLAG_PHYSICAL) != 0) {
                offsets.add(index.entryDataOffset(record));
            }
        }
        return offsets;
    }

    @Test
    void everyNameIsReachableThroughTheHashTable() {
        IndexWriter writer = new IndexWriter();
        IndexWriter.JarSpec jar = writer.addJar(IndexFormat.CLASSES_PREFIX);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String name = "com/example/pkg" + (i % 7) + "/Class" + i + ".class";
            names.add(name);
            jar.addEntry(name).sizes(1, 1).dataOffset(i);
        }

        IndexWriter.Layout layout = writer.layout();
        Decoded index = new Decoded(writer.write(layout, 1024));

        assertTrue(index.hashSlots() >= 2 * index.distinctNames(), "the table stays at most half full");
        assertEquals(Integer.highestOneBit(index.hashSlots()), index.hashSlots(), "a power of two");
        assertTrue(layout.maxProbe() <= IndexFormat.MAX_PROBE_LIMIT);
        assertEquals(layout.maxProbe(), index.maxProbe());
        for (String name : names) {
            assertNotEquals(IndexFormat.NO_INDEX, index.find(name), name);
        }
        assertEquals(IndexFormat.NO_INDEX, index.find("com/example/pkg0/Missing.class"));
    }

    @Test
    void packageSectionsAreContiguousPerJar() {
        IndexWriter writer = new IndexWriter();
        writer.addJar(IndexFormat.CLASSES_PREFIX).addEntry("a.txt").sizes(1, 1);
        IndexWriter.JarSpec first = writer.addJar("MICRONAUT-INF/lib/a.jar");
        first.addEntry("b.txt").sizes(1, 1);
        first.addPackage("com.example.api").attributes("api", "1.0", "example", null, null, null).sealed(true);
        IndexWriter.JarSpec second = writer.addJar("MICRONAUT-INF/lib/b.jar");
        second.addEntry("c.txt").sizes(1, 1);
        second.addPackage("com.example.spi").attributes(null, null, null, "spi", "2.0", "example");
        second.addPackage("com.example.impl").attributes(null, null, null, null, null, null).sealed(false);

        Decoded index = new Decoded(writer.write(writer.layout(), 1024));

        assertEquals(3, index.packageCount());
        assertEquals(0, index.jarPackageCount(0));
        assertEquals(1, index.jarPackageCount(1));
        assertEquals(0, index.jarFirstPackage(1), "a jar without sections leaves the count where it was");
        assertEquals(2, index.jarPackageCount(2));
        assertEquals(1, index.jarFirstPackage(2));
        assertEquals("com.example.api", index.packageName(0));
        assertEquals("com.example.spi", index.packageName(1));
        assertEquals("com.example.impl", index.packageName(2));
        assertEquals("api", index.string(index.i32(index.packageOffset(0) + IndexFormat.P_SPEC_TITLE)));
        assertEquals("spi", index.string(index.i32(index.packageOffset(1) + IndexFormat.P_IMPL_TITLE)));
        assertEquals(IndexFormat.PACKAGE_FLAG_SEALED_SPECIFIED | IndexFormat.PACKAGE_FLAG_SEALED_VALUE,
                index.u16(index.packageOffset(0) + IndexFormat.P_FLAGS));
        assertEquals(0, index.u16(index.packageOffset(1) + IndexFormat.P_FLAGS),
                "a section that says nothing about sealing leaves the jar's default alone");
        assertEquals(IndexFormat.PACKAGE_FLAG_SEALED_SPECIFIED,
                index.u16(index.packageOffset(2) + IndexFormat.P_FLAGS));
    }

    @Test
    void agreesWithTheLaunchersOwnIndexBuilder() throws Exception {
        Class<?> type = launcherIndexBuilder();
        Assumptions.assumeTrue(type != null,
                "the launcher's TestIndexBuilder is not on the class path and its source was not found");

        int mapped = IndexFormat.HEADER_FLAG_NESTED_STORED | IndexFormat.HEADER_FLAG_APP_MULTI_RELEASE;
        for (int flags : new int[] {mapped, mapped | IndexFormat.HEADER_FLAG_POSITIONAL_READS}) {
            byte[] mine = buildWithIndexWriter(flags);
            byte[] theirs = buildWithLauncherBuilder(type, flags);

            assertArrayEquals(theirs, mine, () -> difference(theirs, mine));
            Decoded index = new Decoded(mine);
            assertTrue((index.entryFlags(index.find("resources")) & IndexFormat.ENTRY_FLAG_DIRECTORY_TWIN) != 0,
                    "the fixture has to exercise the directory twin flag for the comparison to cover it");
            assertEquals((flags & IndexFormat.HEADER_FLAG_POSITIONAL_READS) != 0 ? 500L : 0L,
                    index.u32(IndexFormat.H_LARGEST_STORED_CLASS),
                    "both writers record the largest STORED class, and only for positional reads");
        }
    }

    /**
     * Builds a fixture covering everything the two writers could disagree about: several jars, a name that
     * appears in all of them, versioned aliases in both directions, an entry under {@code META-INF} that
     * must not be aliased, version directories below the multi-release floor and past the {@code u8} of
     * {@link IndexFormat#E_MR_VERSION} that must not be aliased either, directories that are stored and
     * directories that are only implied, a file whose name another jar holds as a directory, package
     * sections with and without sealing, and metadata strings that repeat so the string table has to
     * deduplicate them.
     *
     * @param flags the header flags
     * @return the index as {@link IndexWriter} writes it
     */
    private static byte[] buildWithIndexWriter(int flags) {
        IndexWriter writer = new IndexWriter()
                .startClass("com.example.Application")
                .launcherVersion("1.0.0-SNAPSHOT")
                .headerFlags(flags);

        IndexWriter.JarSpec application = writer.addJar(IndexFormat.CLASSES_PREFIX)
                .addFlags(IndexFormat.JAR_FLAG_MULTI_RELEASE)
                .location(0, 123456789L, 0)
                .manifest("example", "1.0", "Example", "example", "1.0.0", "Example");
        application.addEntry("com/example/Application.class").sizes(500, 500).crc32(0x11111111L)
                .dosTime(0x2A210000L).dataOffset(1000);
        application.addEntry("com/example/Shared.class").sizes(50, 50).crc32(0x22222222L)
                .dosTime(0x2A210000L).dataOffset(2000);
        application.addEntry("META-INF/versions/21/com/example/Shared.class").sizes(60, 60)
                .crc32(0x33333333L).dosTime(0x2A210000L).dataOffset(3000);
        application.addEntry("META-INF/versions/17/com/example/Shared.class").sizes(70, 70)
                .crc32(0x44444444L).dosTime(0x2A210000L).dataOffset(4000);
        application.addEntry("META-INF/versions/17/META-INF/keep.txt").sizes(10, 10).crc32(0x55555555L)
                .dosTime(0x2A210000L).dataOffset(5000);
        application.addEntry("META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/"
                + "com.example.Application$Definition").sizes(0, 0).dosTime(0x2A210000L).dataOffset(6000);
        application.addEntry("resources/").sizes(0, 0).dosTime(0x2A210000L).dataOffset(7000);
        application.addEntry("resources/logback.xml").sizes(120, 300)
                .method(IndexFormat.METHOD_DEFLATED).crc32(0x66666666L).dosTime(0x2A210000L)
                .dataOffset(8000);
        application.addPackage("com.example").attributes("example", "1.0", "Example", "example", "1.0.0",
                "Example").sealed(false);

        IndexWriter.JarSpec dependency = writer.addJar("MICRONAUT-INF/lib/dep.jar")
                .coordinates("com.example:dep:1.0")
                .addFlags(IndexFormat.JAR_FLAG_MULTI_RELEASE | IndexFormat.JAR_FLAG_HAS_MANIFEST
                        | IndexFormat.JAR_FLAG_SIGNED_ORIGINAL | IndexFormat.JAR_FLAG_SEALED_BY_DEFAULT)
                .location(200000L, 4096L, 199950L)
                .manifest("dep", "2.0", "Example", "dep", "2.0.1", "Example");
        dependency.addEntry("META-INF/MANIFEST.MF").sizes(200, 200).crc32(0x77777777L)
                .dosTime(0x2A210000L).dataOffset(200100L);
        dependency.addEntry("com/example/Shared.class").sizes(40, 40).crc32(0x88888888L)
                .dosTime(0x2A210000L).dataOffset(200400L);
        dependency.addEntry("com/example/dep/Dep.class").sizes(30, 30).crc32(0x99999999L)
                .dosTime(0x2A210000L).dataOffset(200800L);
        dependency.addEntry("META-INF/versions/9/com/example/dep/Dep.class").sizes(35, 35)
                .crc32(0xAAAAAAAAL).dosTime(0x2A210000L).dataOffset(201200L);
        // Neither of these two is a multi-release entry: 5 is below the floor JEP 238 sets, and 300 does
        // not fit the u8 of E_MR_VERSION. Both must stay ordinary physical records with no alias.
        dependency.addEntry("META-INF/versions/5/com/example/dep/Old.class").sizes(36, 36)
                .crc32(0xABABABABL).dosTime(0x2A210000L).dataOffset(201600L);
        dependency.addEntry("META-INF/versions/300/com/example/dep/Far.class").sizes(37, 37)
                .crc32(0xACACACACL).dosTime(0x2A210000L).dataOffset(202000L);
        dependency.addPackage("com.example.api")
                .attributes("dep", "2.0", "Example", "dep", "2.0.1", "Example").sealed(true);
        dependency.addPackage("com.example.dep").attributes(null, null, null, "dep", "2.0.1", null);

        IndexWriter.JarSpec other = writer.addJar("MICRONAUT-INF/lib/other.jar")
                .location(300000L, 2048L, 299950L);
        other.addEntry("com/example/Shared.class").sizes(20, 20).crc32(0xBBBBBBBBL)
                .dosTime(0x2A210000L).dataOffset(300100L);
        other.addEntry("other/thing.txt").sizes(11, 11).crc32(0xCCCCCCCCL)
                .dosTime(0x2A210000L).dataOffset(300200L);
        // A file named like the application's stored resources/ directory: its record carries
        // ENTRY_FLAG_DIRECTORY_TWIN, so the two writers have to agree on that bit as well.
        other.addEntry("resources").sizes(12, 12).crc32(0xCDCDCDCDL)
                .dosTime(0x2A210000L).dataOffset(300300L);

        return writer.write(writer.layout(), 123456789L);
    }

    /**
     * Builds the same fixture with the launcher's own test builder, reached reflectively because it lives in
     * another module's test source set.
     *
     * @param type  the {@code TestIndexBuilder} class
     * @param flags the header flags
     * @return the index as that builder writes it
     * @throws Exception if the builder cannot be driven
     */
    private static byte[] buildWithLauncherBuilder(Class<?> type, int flags) throws Exception {
        Object writer = type.getConstructor().newInstance();
        call(writer, "startClass", "com.example.Application");
        call(writer, "launcherVersion", "1.0.0-SNAPSHOT");
        call(writer, "headerFlags", flags);
        call(writer, "outerFileLength", 123456789L);

        Object application = call(writer, "addJar", IndexFormat.CLASSES_PREFIX);
        call(application, "flags", IndexFormat.JAR_FLAG_IS_OUTER | IndexFormat.JAR_FLAG_MULTI_RELEASE);
        call(application, "location", 0L, 123456789L, 0L);
        call(application, "manifest", "example", "1.0", "Example", "example", "1.0.0", "Example");
        entry(application, "com/example/Application.class", 1000L, 500L, 0x11111111L);
        entry(application, "com/example/Shared.class", 2000L, 50L, 0x22222222L);
        entry(application, "META-INF/versions/21/com/example/Shared.class", 3000L, 60L, 0x33333333L);
        entry(application, "META-INF/versions/17/com/example/Shared.class", 4000L, 70L, 0x44444444L);
        entry(application, "META-INF/versions/17/META-INF/keep.txt", 5000L, 10L, 0x55555555L);
        entry(application, "META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/"
                + "com.example.Application$Definition", 6000L, 0L, 0L);
        entry(application, "resources/", 7000L, 0L, 0L);
        Object logback = entry(application, "resources/logback.xml", 8000L, 120L, 0x66666666L);
        call(logback, "data", 8000L, 120L, 300L);
        call(logback, "method", IndexFormat.METHOD_DEFLATED);
        Object applicationPackage = call(application, "addPackage", "com.example");
        call(applicationPackage, "attributes", "example", "1.0", "Example", "example", "1.0.0", "Example");
        call(applicationPackage, "sealed", false);

        Object dependency = call(writer, "addJar", "MICRONAUT-INF/lib/dep.jar");
        call(dependency, "coordinates", "com.example:dep:1.0");
        call(dependency, "flags", IndexFormat.JAR_FLAG_MULTI_RELEASE | IndexFormat.JAR_FLAG_HAS_MANIFEST
                | IndexFormat.JAR_FLAG_SIGNED_ORIGINAL | IndexFormat.JAR_FLAG_SEALED_BY_DEFAULT);
        call(dependency, "location", 200000L, 4096L, 199950L);
        call(dependency, "manifest", "dep", "2.0", "Example", "dep", "2.0.1", "Example");
        entry(dependency, "META-INF/MANIFEST.MF", 200100L, 200L, 0x77777777L);
        entry(dependency, "com/example/Shared.class", 200400L, 40L, 0x88888888L);
        entry(dependency, "com/example/dep/Dep.class", 200800L, 30L, 0x99999999L);
        entry(dependency, "META-INF/versions/9/com/example/dep/Dep.class", 201200L, 35L, 0xAAAAAAAAL);
        entry(dependency, "META-INF/versions/5/com/example/dep/Old.class", 201600L, 36L, 0xABABABABL);
        entry(dependency, "META-INF/versions/300/com/example/dep/Far.class", 202000L, 37L, 0xACACACACL);
        Object api = call(dependency, "addPackage", "com.example.api");
        call(api, "attributes", "dep", "2.0", "Example", "dep", "2.0.1", "Example");
        call(api, "sealed", true);
        Object dep = call(dependency, "addPackage", "com.example.dep");
        call(dep, "attributes", null, null, null, "dep", "2.0.1", null);

        Object other = call(writer, "addJar", "MICRONAUT-INF/lib/other.jar");
        call(other, "location", 300000L, 2048L, 299950L);
        entry(other, "com/example/Shared.class", 300100L, 20L, 0xBBBBBBBBL);
        entry(other, "other/thing.txt", 300200L, 11L, 0xCCCCCCCCL);
        entry(other, "resources", 300300L, 12L, 0xCDCDCDCDL);

        return (byte[]) call(writer, "build");
    }

    private static Object entry(Object jar, String name, long offset, long size, long crc) throws Exception {
        Object spec = call(jar, "addEntry", name);
        call(spec, "data", offset, size, size);
        call(spec, "crc32", crc);
        call(spec, "dosTime", 0x2A210000L);
        return spec;
    }

    private static Object call(Object target, String name, Object... arguments) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                return method.invoke(target, arguments);
            }
        }
        throw new IllegalStateException("No method " + name + "/" + arguments.length + " on " + target);
    }

    /**
     * Finds the launcher's {@code TestIndexBuilder}, compiling it from the launcher's test sources when it
     * is not already on the class path, which it is not when this module is built on its own.
     *
     * @return the class, or {@code null} when neither the class nor its source can be found
     */
    private static Class<?> launcherIndexBuilder() {
        try {
            return Class.forName("io.micronaut.runner.TestIndexBuilder");
        } catch (ClassNotFoundException notOnTheClassPath) {
            // Expected: the launcher's test classes are not a dependency of this module.
            return compileLauncherIndexBuilder();
        }
    }

    private static Class<?> compileLauncherIndexBuilder() {
        String relative = "runner-launcher/src/test/java/io/micronaut/runner/TestIndexBuilder.java";
        Path source = null;
        Path directory = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && directory != null; i++) {
            Path candidate = directory.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                source = candidate;
                break;
            }
            directory = directory.getParent();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (source == null || compiler == null) {
            return null;
        }
        try {
            Path classes = Files.createTempDirectory("micronaut-runner-test-index-builder");
            int status = compiler.run(null, null, null, "--release", "25",
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classes.toString(), source.toString());
            if (status != 0) {
                return null;
            }
            URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                    IndexWriterTest.class.getClassLoader());
            return loader.loadClass("io.micronaut.runner.TestIndexBuilder");
        } catch (IOException | ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Describes where two indexes first differ, so a failure says something more useful than "arrays
     * differ".
     *
     * @param expected the launcher's bytes
     * @param actual   the packager's bytes
     * @return the message
     */
    private static String difference(byte[] expected, byte[] actual) {
        StringBuilder message = new StringBuilder("The two index writers disagree: ");
        if (expected.length != actual.length) {
            message.append("lengths ").append(expected.length).append(" and ").append(actual.length)
                    .append("; ");
        }
        int limit = Math.min(expected.length, actual.length);
        for (int i = 0; i < limit; i++) {
            if (expected[i] != actual[i]) {
                Decoded reference = new Decoded(expected);
                message.append("first difference at byte ").append(i).append(" (")
                        .append(reference.section(i)).append("): expected 0x")
                        .append(Integer.toHexString(expected[i] & 0xFF)).append(", wrote 0x")
                        .append(Integer.toHexString(actual[i] & 0xFF));
                return message.toString();
            }
        }
        return message.append("one is a prefix of the other").toString();
    }

    @Test
    void placesAsManyNamesWithOneHashAsTheProbeLimitAllows() {
        List<String> names = collidingNames(IndexFormat.MAX_PROBE_LIMIT + 1);
        IndexWriter writer = new IndexWriter().startClass("com.example.App");
        IndexWriter.JarSpec application = writer.addJar(IndexFormat.CLASSES_PREFIX);
        for (String name : names) {
            application.addEntry(name).sizes(1, 1).crc32(0);
        }

        IndexWriter.Layout layout = writer.layout();

        assertEquals(IndexFormat.MAX_PROBE_LIMIT, layout.maxProbe(),
                "names that share a hash sit in one run, one probe shorter than their number");
        Decoded index = new Decoded(writer.write(layout, 4096));
        for (String name : names) {
            assertNotEquals(IndexFormat.NO_INDEX, index.find(name), name + " has to be findable");
        }
    }

    @Test
    void namesTheEntriesThatShareAHashInsteadOfGrowingTheTableForever() {
        // Names whose 32-bit hash codes are equal land in the same slot at every table size, so doubling
        // the table never shortens the run they force. The loop used to double on the assumption that it
        // always would, and allocated int arrays until the heap was gone: an OutOfMemoryError naming
        // neither the jar nor the entries. One name past the probe limit is the cliff.
        List<String> names = collidingNames(IndexFormat.MAX_PROBE_LIMIT + 2);
        assertEquals(1, names.stream().map(String::hashCode).distinct().count(),
                "the fixture only works if every name really does share one hash code");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> layoutOf(names));

        assertTrue(failure.getMessage().contains("share the hash code"), failure.getMessage());
        assertTrue(failure.getMessage().contains(names.get(0)),
                "the message has to name an entry to look at: " + failure.getMessage());
    }

    /**
     * Lays out an index holding one application layer entry per name.
     *
     * @param names the logical names
     * @return the layout
     */
    private static IndexWriter.Layout layoutOf(List<String> names) {
        IndexWriter writer = new IndexWriter().startClass("com.example.App");
        IndexWriter.JarSpec application = writer.addJar(IndexFormat.CLASSES_PREFIX);
        for (String name : names) {
            application.addEntry(name).sizes(1, 1).crc32(0);
        }
        return writer.layout();
    }

    /**
     * Distinct names that all share one {@link String#hashCode()}, built from the classic {@code "Aa"} and
     * {@code "BB"} pair: equal length and equal hash in the middle means equal hash overall.
     *
     * @param count how many names to produce
     * @return the names, all different, all hashing the same
     */
    private static List<String> collidingNames(int count) {
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            StringBuilder name = new StringBuilder("h/");
            for (int bit = 6; bit >= 0; bit--) {
                name.append(((i >> bit) & 1) == 0 ? "Aa" : "BB");
            }
            names.add(name.append(".txt").toString());
        }
        return names;
    }

    /**
     * Reads an index the way the launcher does, so that a test can assert on records and lookups without
     * building an archive around the bytes.
     */
    private static final class Decoded {

        private final byte[] bytes;
        private final ByteBuffer buffer;

        private Decoded(byte[] bytes) {
            this.bytes = bytes;
            this.buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }

        private int length() {
            return bytes.length;
        }

        private int u16(long offset) {
            return buffer.getShort((int) offset) & 0xFFFF;
        }

        private int u8(long offset) {
            return buffer.get((int) offset) & 0xFF;
        }

        private int i32(long offset) {
            return buffer.getInt((int) offset);
        }

        private long u32(long offset) {
            return buffer.getInt((int) offset) & 0xFFFFFFFFL;
        }

        private long u64(long offset) {
            return buffer.getLong((int) offset);
        }

        private int jarCount() {
            return (int) u32(IndexFormat.H_JAR_COUNT);
        }

        private int entryCount() {
            return (int) u32(IndexFormat.H_ENTRY_COUNT);
        }

        private int packageCount() {
            return (int) u32(IndexFormat.H_PACKAGE_COUNT);
        }

        private int hashSlots() {
            return (int) u32(IndexFormat.H_HASH_SLOTS);
        }

        private int maxProbe() {
            return (int) u32(IndexFormat.H_MAX_PROBE);
        }

        private long jarTable() {
            return u64(IndexFormat.H_JAR_TABLE_OFFSET);
        }

        private long packageTable() {
            return u64(IndexFormat.H_PACKAGE_TABLE_OFFSET);
        }

        private long entryTable() {
            return u64(IndexFormat.H_ENTRY_TABLE_OFFSET);
        }

        private long hashTable() {
            return u64(IndexFormat.H_HASH_TABLE_OFFSET);
        }

        private long stringTable() {
            return u64(IndexFormat.H_STRING_TABLE_OFFSET);
        }

        private long stringTableLength() {
            return u64(IndexFormat.H_STRING_TABLE_LENGTH);
        }

        private String string(int ref) {
            if (ref == 0) {
                return null;
            }
            int at = (int) stringTable() + ref;
            int length = u16(at);
            return new String(bytes, at + 2, length, StandardCharsets.UTF_8);
        }

        private long entryOffset(int record) {
            return entryTable() + (long) record * IndexFormat.ENTRY_RECORD_SIZE;
        }

        private long packageOffset(int record) {
            return packageTable() + (long) record * IndexFormat.PACKAGE_RECORD_SIZE;
        }

        private long jarOffset(int jarId) {
            return jarTable() + (long) jarId * IndexFormat.JAR_RECORD_SIZE;
        }

        private int jarFirstPackage(int jarId) {
            return (int) u32(jarOffset(jarId) + IndexFormat.J_FIRST_PACKAGE);
        }

        private int jarPackageCount(int jarId) {
            return (int) u32(jarOffset(jarId) + IndexFormat.J_PACKAGE_COUNT);
        }

        private String packageName(int record) {
            return string(i32(packageOffset(record) + IndexFormat.P_NAME));
        }

        private String entryName(int record) {
            return string(i32(entryOffset(record) + IndexFormat.E_NAME));
        }

        private int entryFlags(int record) {
            return u8(entryOffset(record) + IndexFormat.E_FLAGS);
        }

        private int entryMethod(int record) {
            return u8(entryOffset(record) + IndexFormat.E_METHOD);
        }

        private int entryMrVersion(int record) {
            return u8(entryOffset(record) + IndexFormat.E_MR_VERSION);
        }

        private int entryJarId(int record) {
            return u16(entryOffset(record) + IndexFormat.E_JAR_ID);
        }

        private int entryNext(int record) {
            return i32(entryOffset(record) + IndexFormat.E_NEXT_SAME_NAME);
        }

        private int entryPhysicalIndex(int record) {
            return i32(entryOffset(record) + IndexFormat.E_PHYSICAL_INDEX);
        }

        private long entryDataOffset(int record) {
            return u64(entryOffset(record) + IndexFormat.E_DATA_OFFSET);
        }

        private long entryCompressedSize(int record) {
            return u32(entryOffset(record) + IndexFormat.E_COMPRESSED_SIZE);
        }

        private long entryUncompressedSize(int record) {
            return u32(entryOffset(record) + IndexFormat.E_UNCOMPRESSED_SIZE);
        }

        private long entryCrc32(int record) {
            return u32(entryOffset(record) + IndexFormat.E_CRC32);
        }

        private long entryDosTime(int record) {
            return u32(entryOffset(record) + IndexFormat.E_DOS_TIME);
        }

        private int distinctNames() {
            int distinct = 0;
            for (int slot = 0; slot < hashSlots(); slot++) {
                if (i32(hashTable() + 4L * slot) != IndexFormat.NO_INDEX) {
                    distinct++;
                }
            }
            return distinct;
        }

        /**
         * Looks a name up exactly as the launcher does: one probe into the table, then linear probing until
         * an empty slot or the recorded probe limit.
         *
         * @param name the logical name
         * @return the head of the chain, or {@link IndexFormat#NO_INDEX}
         */
        private int find(String name) {
            int hash = IndexFormat.hash(name);
            int mask = hashSlots() - 1;
            int slot = hash & mask;
            for (int probe = 0; probe <= maxProbe(); probe++) {
                int record = i32(hashTable() + 4L * ((slot + probe) & mask));
                if (record == IndexFormat.NO_INDEX) {
                    return IndexFormat.NO_INDEX;
                }
                if (i32(entryOffset(record) + IndexFormat.E_NAME_HASH) == hash
                        && name.equals(entryName(record))) {
                    return record;
                }
            }
            return IndexFormat.NO_INDEX;
        }

        private String section(int offset) {
            if (offset < IndexFormat.HEADER_SIZE) {
                return "header";
            }
            if (offset < packageTable()) {
                return "jar table, record " + (offset - jarTable()) / IndexFormat.JAR_RECORD_SIZE;
            }
            if (offset < entryTable()) {
                return "package table, record " + (offset - packageTable()) / IndexFormat.PACKAGE_RECORD_SIZE;
            }
            if (offset < hashTable()) {
                int record = (int) ((offset - entryTable()) / IndexFormat.ENTRY_RECORD_SIZE);
                return "entry table, record " + record + " '" + entryName(record) + "'";
            }
            if (offset < stringTable()) {
                return "hash table, slot " + (offset - hashTable()) / 4;
            }
            return "string table";
        }
    }
}
