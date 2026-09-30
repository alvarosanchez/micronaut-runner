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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the preloader and the preload table it replays, against real runner archives read by a real
 * {@link RunnerClassLoader}.
 *
 * <p>The preloader swallows every failure by design, so a test cannot learn what it did from an exception.
 * It hands the preloader a {@link Recording} loader instead: a plain delegating loader in front of the runner
 * class loader that remembers, for every name the preloader asked for, the class it got or the failure it
 * saw.</p>
 */
class PreloaderTest {

    private static final String DEPENDENCY = IndexFormat.LIB_PREFIX + "dep.jar";
    private static final String INITIALISED_PROPERTY = "micronaut.runner.test.preloader.initialised";
    private static final String INITIALISER = "org.example.Initialiser";
    private static final String ORPHAN = "org.example.Orphan";
    private static final String GARBAGE = "org.example.Garbage";
    private static final String GOOD = "org.example.Good";
    private static final int CONCURRENT_CLASSES = 300;
    private static final int CONCURRENT_ROUNDS = 20;
    private static final ClassLoader PLATFORM = ClassLoader.getPlatformClassLoader();

    @TempDir
    Path temporary;

    private final List<ArchiveSource> sources = new ArrayList<>();

    @AfterEach
    void closeSources() {
        for (ArchiveSource source : sources) {
            source.close();
        }
        sources.clear();
        System.clearProperty(INITIALISED_PROPERTY);
        System.clearProperty(Preloader.PRELOAD_PROPERTY);
    }

    @Test
    void loadsEveryListedClassAndInitialisesNone() throws Exception {
        Fixture fixture = new Fixture();
        fixture.application.put(INITIALISER, initialiserBytes(INITIALISER));
        fixture.application.put("org.example.Base", classBytes("org.example.Base", null));
        fixture.application.put("org.example.Sub", classBytes("org.example.Sub", "org.example.Base"));
        fixture.dependency.put("org.dep.Dep", classBytes("org.dep.Dep", null));
        // A subclass before its superclass, and a class of a nested jar: the order of a real recording.
        fixture.preload(INITIALISER, "org.example.Sub", "org.example.Base", "org.dep.Dep");
        Index index = open(fixture);
        RunnerClassLoader loader = loader(index);
        Recording recording = new Recording(loader);

        Thread thread = Preloader.newThread(index, recording, PLATFORM, false, 0L);
        assertEquals("micronaut-runner-preload", thread.getName());
        assertTrue(thread.isDaemon(), "the preloader must never keep the JVM alive");
        thread.start();
        thread.join();

        assertEquals(List.of(INITIALISER, "org.example.Sub", "org.example.Base", "org.dep.Dep"),
                recording.asked, "the list is replayed in order");
        assertTrue(recording.failures.isEmpty(), () -> recording.failures.toString());
        assertNull(System.getProperty(INITIALISED_PROPERTY), "preloading ran a static initialiser");
        Class<?> preloaded = recording.loaded.get(INITIALISER);
        assertSame(preloaded, loader.loadClass(INITIALISER), "the application gets the preloaded class");
        assertSame(loader, preloaded.getClassLoader());
        assertSame(recording.loaded.get("org.example.Base"),
                recording.loaded.get("org.example.Sub").getSuperclass());

        Class.forName(INITIALISER, true, loader);
        assertEquals("ran", System.getProperty(INITIALISED_PROPERTY),
                "the initialiser runs when the application first uses the class");
    }

    @Test
    void aClassThatCannotBeDefinedIsSkippedAndFailsLaterAsItWouldWithoutPreloading() throws Exception {
        Fixture fixture = new Fixture();
        fixture.application.put(ORPHAN, classBytes(ORPHAN, "org.example.NotPackaged"));
        fixture.application.put(GARBAGE, "not a class file".getBytes(StandardCharsets.UTF_8));
        fixture.application.put(GOOD, classBytes(GOOD, null));
        fixture.preload(ORPHAN, GARBAGE, GOOD);
        Index index = open(fixture);
        RunnerClassLoader preloading = loader(index);
        Recording recording = new Recording(preloading);

        Preloader.newThread(index, recording, PLATFORM, false, 0L).run();

        assertInstanceOf(NoClassDefFoundError.class, recording.failures.get(ORPHAN));
        assertInstanceOf(ClassFormatError.class, recording.failures.get(GARBAGE));
        assertSame(preloading.loadClass(GOOD), recording.loaded.get(GOOD),
                "a class that fails must not stop the ones after it");

        RunnerClassLoader untouched = loader(index);
        for (RunnerClassLoader loader : List.of(preloading, untouched)) {
            assertThrows(NoClassDefFoundError.class, () -> loader.loadClass(ORPHAN));
            assertThrows(ClassFormatError.class, () -> loader.loadClass(GARBAGE));
        }
    }

    @Test
    void thePreloaderAndTheApplicationLoadingTheSameNamesGetTheSameClasses() throws Exception {
        Fixture fixture = new Fixture();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_CLASSES; i++) {
            // Chained, so that defining one class on either thread resolves its superclass on that thread.
            String name = "org.example.chain.C" + i;
            fixture.application.put(name, classBytes(name, i % 8 == 0 ? null : "org.example.chain.C" + (i - 1)));
            names.add(name);
        }
        fixture.preload(names.toArray(String[]::new));
        Index index = open(fixture);

        for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
            RunnerClassLoader loader = loader(index);
            Recording recording = new Recording(loader);
            Thread thread = Preloader.newThread(index, recording, PLATFORM, false, 0L);
            Map<String, Class<?>> mine = new LinkedHashMap<>();
            thread.start();
            // Backwards on odd rounds, so the two threads meet in the middle as well as race side by side.
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(round % 2 == 0 ? i : names.size() - 1 - i);
                mine.put(name, loader.loadClass(name));
            }
            thread.join();

            assertTrue(recording.failures.isEmpty(), () -> recording.failures.toString());
            for (String name : names) {
                assertSame(mine.get(name), recording.loaded.get(name), name);
            }
        }
    }

    @Test
    void aStaleOrForeignRecordIsSkipped() throws Exception {
        Fixture fixture = new Fixture();
        fixture.application.put(GOOD, classBytes(GOOD, null));
        fixture.resources.put("data.txt", "data".getBytes(StandardCharsets.UTF_8));
        Index plain = open(fixture);
        int good = plain.findClass(GOOD);
        int resource = plain.find("data.txt");
        // A record past the entry table, then one that is not a class, then a real class.
        fixture.preloadRecords = new int[] {0x7FFFFFFF, resource, good};
        Index index = open(fixture);
        RunnerClassLoader loader = loader(index);
        Recording recording = new Recording(loader);

        Preloader.newThread(index, recording, PLATFORM, false, 0L).run();

        assertEquals(3, index.preloadCount());
        assertEquals(List.of("data.txt", GOOD), recording.asked, "the stale record names nothing to ask for");
        assertSame(loader.loadClass(GOOD), recording.loaded.get(GOOD));
    }

    @Test
    void loadsTheListedJdkClassesThroughThePlatformLoaderAfterTheArchivesAndSkipsUnknownNames() throws Exception {
        Fixture fixture = new Fixture();
        fixture.application.put(GOOD, classBytes(GOOD, null));
        fixture.preload(GOOD);
        // A class of a platform module, one of the boot loader, and a name no JDK has.
        fixture.jdkPreload.addAll(List.of("java.sql.Timestamp", "no.such.JdkClass", "java.util.zip.Adler32",
                "java.sql.Timestamp"));
        Index index = open(fixture);
        index.validateStringReferences();
        Recording archive = new Recording(loader(index));
        Recording jdk = new Recording(PLATFORM);

        assertEquals(3, index.jdkPreloadCount(), "a repeated name is listed once");
        assertEquals("java.sql.Timestamp", index.jdkPreloadName(0));
        assertEquals("no.such.JdkClass", index.jdkPreloadName(1));
        assertEquals("java.util.zip.Adler32", index.jdkPreloadName(2));
        assertThrows(IllegalStateException.class, () -> index.jdkPreloadName(3));
        assertThrows(IllegalStateException.class, () -> index.jdkPreloadName(-1));

        Preloader.newThread(index, archive, jdk, false, 0L).run();

        assertEquals(List.of(GOOD), archive.asked, "the archive's classes never go to the JDK loader");
        assertEquals(List.of("java.sql.Timestamp", "no.such.JdkClass", "java.util.zip.Adler32"), jdk.asked,
                "the JDK classes are replayed in order, after the archive's");
        assertSame(PLATFORM, jdk.loaded.get("java.sql.Timestamp").getClassLoader());
        assertNull(jdk.loaded.get("java.util.zip.Adler32").getClassLoader(), "a boot class is found too");
        assertInstanceOf(ClassNotFoundException.class, jdk.failures.get("no.such.JdkClass"));
    }

    @Test
    void aListedJdkClassIsNotInitialisedEither() throws Exception {
        Fixture fixture = new Fixture();
        fixture.application.put(INITIALISER, initialiserBytes(INITIALISER));
        fixture.jdkPreload.add(INITIALISER);
        Index index = open(fixture);
        // No JDK class has an initialiser a test can watch, so the fixture's stands in for one: the loader
        // handed over as the JDK loader is the one that holds it.
        RunnerClassLoader standIn = loader(index);
        Recording jdk = new Recording(standIn);

        assertEquals(0, index.preloadCount());
        Preloader.newThread(index, loader(index), jdk, false, 0L).run();

        assertSame(standIn.loadClass(INITIALISER), jdk.loaded.get(INITIALISER));
        assertNull(System.getProperty(INITIALISED_PROPERTY), "preloading ran a static initialiser");
    }

    @Test
    void startsOnlyWithAListASpareProcessorAndNoSwitchOff() {
        assertTrue(Preloader.shouldStart(1, null, 2), "one listed class, of the archive or of the JDK");
        assertTrue(Preloader.shouldStart(3_000, "true", 12));
        assertTrue(Preloader.shouldStart(1, "FALSE", 2), "only exactly \"false\" switches it off");

        assertFalse(Preloader.shouldStart(0, null, 12), "no list");
        assertFalse(Preloader.shouldStart(3_000, "false", 12), "switched off");
        assertFalse(Preloader.shouldStart(3_000, null, 1), "one processor");
    }

    @Test
    void startHonoursTheSwitchOffProperty() throws Exception {
        Fixture fixture = new Fixture();
        fixture.application.put(GOOD, classBytes(GOOD, null));
        fixture.preload(GOOD);
        Index index = open(fixture);
        Recording off = new Recording(loader(index));

        System.setProperty(Preloader.PRELOAD_PROPERTY, "false");
        Preloader.start(index, off, false, 0L);
        assertNull(preloadThread(), "micronaut.runner.preload=false must not start the thread");
        assertTrue(off.asked.isEmpty());

        Assumptions.assumeTrue(Runtime.getRuntime().availableProcessors() >= Preloader.MIN_PROCESSORS,
                "this machine has no spare processor, so the preloader does not start");
        System.clearProperty(Preloader.PRELOAD_PROPERTY);
        Recording on = new Recording(loader(index));
        Preloader.start(index, on, false, 0L);
        Thread started = preloadThread();
        if (started != null) {
            started.join();
        }
        assertEquals(List.of(GOOD), on.asked);
    }

    @Test
    void printsOneCheckpointWhenTimingIsOn() throws Exception {
        Fixture fixture = new Fixture();
        fixture.application.put(GOOD, classBytes(GOOD, null));
        fixture.application.put(GARBAGE, new byte[] {1, 2, 3});
        fixture.preload(GOOD, GARBAGE);
        fixture.jdkPreload.add("no.such.JdkClass");
        Index index = open(fixture);

        assertEquals("",
                standardError(() -> Preloader.newThread(index, loader(index), PLATFORM, false, 0L).run()),
                "without timing the preloader prints nothing, not even for a class that fails");
        String timed = standardError(
                () -> Preloader.newThread(index, loader(index), PLATFORM, true, System.nanoTime()).run());
        assertTrue(timed.matches(
                "\\[micronaut-runner] preload finished \\(2 classes, 1 JDK classes\\) \\d+\\.\\d{3} ms\\R"), timed);
    }

    @Test
    void thePreloadTableRoundTripsThroughTheIndex() throws Exception {
        Fixture fixture = new Fixture();
        fixture.application.put(GOOD, classBytes(GOOD, null));
        fixture.application.put("org.example.Shared", classBytes("org.example.Shared", null));
        fixture.dependency.put("org.example.Shared", classBytes("org.example.Shared", null));
        fixture.dependency.put("org.dep.Dep", classBytes("org.dep.Dep", null));
        // A name the archive does not hold and a repeated one are left out, as the packager leaves them out.
        fixture.preload("org.dep.Dep", "org.example.Absent", "org.example.Shared", GOOD, "org.dep.Dep");
        Index index = open(fixture);

        List<String> expected = List.of("org.dep.Dep", "org.example.Shared", GOOD);
        assertEquals(expected.size(), index.preloadCount());
        for (int position = 0; position < expected.size(); position++) {
            int record = index.preloadRecord(position);
            assertEquals(index.findClass(expected.get(position)), record, expected.get(position));
            assertEquals(expected.get(position), Preloader.binaryName(index.entryName(record)));
        }
        assertEquals(IndexFormat.APPLICATION_JAR_ID, index.entryJarId(index.preloadRecord(1)),
                "a class in two jars is preloaded from the one a lookup finds first");
        assertThrows(IllegalStateException.class, () -> index.preloadRecord(expected.size()));
        assertThrows(IllegalStateException.class, () -> index.preloadRecord(-1));
    }

    @Test
    void anIndexWithoutAListKeepsItsReservedBytesZeroAndReportsNone() throws Exception {
        Fixture fixture = new Fixture();
        fixture.application.put(GOOD, classBytes(GOOD, null));
        byte[] bytes = fixture.index(null);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int at = IndexFormat.H_PRELOAD_TABLE_OFFSET; at < IndexFormat.HEADER_SIZE; at++) {
            assertEquals(0, bytes[at], "header byte " + at);
        }
        assertEquals(bytes.length, header.getLong(IndexFormat.H_STRING_TABLE_OFFSET)
                        + header.getLong(IndexFormat.H_STRING_TABLE_LENGTH),
                "without a list the index ends with the string table");

        Index index = open(fixture);
        assertEquals(0, index.preloadCount());
        assertEquals(0, index.jdkPreloadCount());
        assertThrows(IllegalStateException.class, () -> index.preloadRecord(0));
        assertThrows(IllegalStateException.class, () -> index.jdkPreloadName(0));
    }

    @Test
    void aPreloadTableThatDoesNotFitTheIndexIsRejectedWhenTheIndexOpens() throws Exception {
        Fixture fixture = new Fixture();
        fixture.application.put(GOOD, classBytes(GOOD, null));
        fixture.preload(GOOD);
        fixture.patch = header -> header.putInt(IndexFormat.H_PRELOAD_COUNT, 1_000_000);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> open(fixture));
        assertTrue(failure.getMessage().contains("preload table"), failure.getMessage());
    }

    @Test
    void aJdkPreloadTableThatDoesNotFitOrNamesNothingIsRejected() throws Exception {
        Fixture fixture = new Fixture();
        fixture.application.put(GOOD, classBytes(GOOD, null));
        fixture.jdkPreload.add("java.util.zip.Adler32");
        fixture.patch = header -> header.putInt(IndexFormat.H_JDK_PRELOAD_COUNT, 1_000_000);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> open(fixture));
        assertTrue(failure.getMessage().contains("JDK preload table"), failure.getMessage());

        // A reference outside the string table opens, as every string reference does, and fails when it is
        // validated or read. The preloader skips it.
        fixture.patch = header -> header.putInt(
                (int) header.getLong(IndexFormat.H_JDK_PRELOAD_TABLE_OFFSET), 0x7FFFFFF0);
        Index index = open(fixture);
        assertThrows(IllegalStateException.class, index::validateStringReferences);
        assertThrows(IllegalStateException.class, () -> index.jdkPreloadName(0));
        Recording jdk = new Recording(PLATFORM);
        Preloader.newThread(index, loader(index), jdk, false, 0L).run();
        assertTrue(jdk.asked.isEmpty());
    }

    @Test
    void derivesTheBinaryNameFromTheEntryName() {
        assertEquals("org.example.Service$Inner", Preloader.binaryName("org/example/Service$Inner.class"));
        assertEquals("Top", Preloader.binaryName("Top.class"));
    }

    private Index open(Fixture fixture) throws IOException {
        File file = fixture.writeTo(temporary.resolve("preload-" + sources.size() + ".jar").toFile());
        ArchiveSource source = ArchiveSource.open(file);
        sources.add(source);
        return Index.open(source);
    }

    private static RunnerClassLoader loader(Index index) {
        return new RunnerClassLoader(index, index.source(), ClassLoader.getPlatformClassLoader());
    }

    /** The live preload thread, or {@code null} when there is none. */
    private static Thread preloadThread() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (Preloader.THREAD_NAME.equals(thread.getName())) {
                return thread;
            }
        }
        return null;
    }

    private static String standardError(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    /**
     * A class with one {@code public static String id()}, optionally extending another class. It has no
     * constructor, which nothing here needs: the classes are loaded, never instantiated.
     */
    private static byte[] classBytes(String binaryName, String superName) {
        return ClassFile.of().build(ClassDesc.of(binaryName), builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            if (superName != null) {
                builder.withSuperclass(ClassDesc.of(superName));
            }
            builder.withMethodBody("id", MethodTypeDesc.of(ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                    code -> code.loadConstant(binaryName).areturn());
        });
    }

    /** A class whose static initialiser sets {@link #INITIALISED_PROPERTY}. */
    private static byte[] initialiserBytes(String binaryName) {
        return ClassFile.of().build(ClassDesc.of(binaryName), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withMethodBody(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_STATIC,
                        code -> code.loadConstant(INITIALISED_PROPERTY)
                                .loadConstant("ran")
                                .invokestatic(ClassDesc.of("java.lang.System"), "setProperty",
                                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String,
                                                ConstantDescs.CD_String))
                                .pop()
                                .return_()));
    }

    /**
     * What the preloader asked a loader for, and what it got. It delegates to the runner class loader, which
     * defines the classes, exactly as a request from the preloader itself would.
     */
    private static final class Recording extends ClassLoader {

        private final List<String> asked = java.util.Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Class<?>> loaded = new ConcurrentHashMap<>();
        private final Map<String, Throwable> failures = new ConcurrentHashMap<>();

        private Recording(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            asked.add(name);
            try {
                Class<?> type = getParent().loadClass(name);
                loaded.put(name, type);
                return type;
            } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
                failures.put(name, e);
                throw e;
            }
        }
    }

    /**
     * One runner archive: application classes and resources exploded under {@code MICRONAUT-INF/classes/},
     * one nested dependency jar when it has classes, and the preload table. The index is written twice, as
     * the packager writes it: once to learn its size, and once with the real offsets.
     */
    private static final class Fixture {

        private final Map<String, byte[]> application = new LinkedHashMap<>();
        private final Map<String, byte[]> resources = new LinkedHashMap<>();
        private final Map<String, byte[]> dependency = new LinkedHashMap<>();
        private final List<String> preload = new ArrayList<>();
        private final List<String> jdkPreload = new ArrayList<>();
        private int[] preloadRecords;
        private Consumer<ByteBuffer> patch;

        void preload(String... binaryNames) {
            preload.addAll(List.of(binaryNames));
        }

        File writeTo(File file) throws IOException {
            TestArchiveBuilder nested = new TestArchiveBuilder();
            for (Map.Entry<String, byte[]> entry : dependency.entrySet()) {
                nested.stored(entryName(entry.getKey()), entry.getValue());
            }
            byte[] nestedBytes = nested.build();

            TestArchiveBuilder outer = new TestArchiveBuilder();
            outer.reserve(IndexFormat.INDEX_ENTRY_NAME, index(null).length);
            for (Map.Entry<String, byte[]> entry : application.entrySet()) {
                outer.stored(IndexFormat.CLASSES_PREFIX + entryName(entry.getKey()), entry.getValue());
            }
            for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
                outer.stored(IndexFormat.CLASSES_PREFIX + entry.getKey(), entry.getValue());
            }
            if (!dependency.isEmpty()) {
                outer.stored(DEPENDENCY, nestedBytes);
            }
            byte[] bytes = index(new Offsets(outer, nested, nestedBytes.length));
            if (patch != null) {
                patch.accept(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
            }
            outer.replace(IndexFormat.INDEX_ENTRY_NAME, bytes);
            return outer.writeTo(file);
        }

        /**
         * The index, with every offset zero when the archive is not laid out yet. Its length does not depend
         * on the offsets.
         */
        byte[] index(Offsets offsets) {
            TestIndexBuilder builder = new TestIndexBuilder().startClass(GOOD);
            TestIndexBuilder.Jar jar = builder.addJar(IndexFormat.CLASSES_PREFIX);
            for (Map.Entry<String, byte[]> entry : application.entrySet()) {
                String name = entryName(entry.getKey());
                add(jar, name, entry.getValue(),
                        offsets == null ? 0 : offsets.outer.dataOffset(IndexFormat.CLASSES_PREFIX + name));
            }
            for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
                add(jar, entry.getKey(), entry.getValue(), offsets == null
                        ? 0 : offsets.outer.dataOffset(IndexFormat.CLASSES_PREFIX + entry.getKey()));
            }
            if (!dependency.isEmpty()) {
                long base = offsets == null ? 0 : offsets.outer.dataOffset(DEPENDENCY);
                TestIndexBuilder.Jar nested = builder.addJar(DEPENDENCY).location(base,
                        offsets == null ? 0 : offsets.nestedLength,
                        offsets == null ? 0 : offsets.outer.localHeaderOffset(DEPENDENCY));
                for (Map.Entry<String, byte[]> entry : dependency.entrySet()) {
                    String name = entryName(entry.getKey());
                    add(nested, name, entry.getValue(),
                            offsets == null ? 0 : base + offsets.nested.dataOffset(name));
                }
            }
            for (String name : preload) {
                builder.preloadClass(name);
            }
            for (String name : jdkPreload) {
                builder.jdkPreloadClass(name);
            }
            if (preloadRecords != null) {
                builder.preloadRecords(preloadRecords);
            }
            return builder.build();
        }

        private static void add(TestIndexBuilder.Jar jar, String name, byte[] content, long offset) {
            CRC32 crc = new CRC32();
            crc.update(content, 0, content.length);
            jar.addEntry(name).data(offset, content.length, content.length).crc32(crc.getValue());
        }

        private static String entryName(String binaryName) {
            return binaryName.replace('.', '/') + ".class";
        }

        /** The laid out archive the second pass of {@link #index(Offsets)} takes its offsets from. */
        private record Offsets(TestArchiveBuilder outer, TestArchiveBuilder nested, int nestedLength) {
        }
    }
}
