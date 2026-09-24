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
package io.micronaut.runner.protocol.jar;

import io.micronaut.runner.ArchiveSource;
import io.micronaut.runner.Handlers;
import io.micronaut.runner.Index;
import io.micronaut.runner.IndexFormat;
import io.micronaut.runner.NestedJarFile;
import io.micronaut.runner.TestArchiveBuilder;
import io.micronaut.runner.TestIndexBuilder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the {@code jar:} protocol handler and the connection behind it against a real runner archive: a
 * two jar index over a real ZIP file, with awkward entry names, a stored and a deflated entry in each
 * layer, and a multi-release dependency.
 */
class HandlerTest {

    private static final String DEPENDENCY = "MICRONAUT-INF/lib/dep.jar";
    private static final String SERVICE = "META-INF/micronaut/io.micronaut.Service/com.example.Impl";
    private static final String AWKWARD = "weird/a b%c#d?e[f]g é.txt";
    private static final String ENCODED_AWKWARD = "weird/a%20b%25c%23d%3Fe%5Bf%5Dg%20%C3%A9.txt";
    private static final String BANG = "bang!/fake.txt";
    private static final String NESTED_TEXT_NAME = "a/data file.txt";
    private static final long DOS_TIME = 0x00210000L;
    private static final long DOS_TIME_MILLIS = 315532800000L;

    private static final byte[] APP_TEXT = bytes("application resource");
    private static final byte[] AWKWARD_TEXT = bytes("awkward name, deflated ".repeat(20));
    private static final byte[] BANG_TEXT = bytes("a name with a separator in it");
    private static final byte[] BASE_CLASS = bytes("base class bytes");
    private static final byte[] VERSIONED_CLASS = bytes("versioned class bytes");
    private static final byte[] NESTED_TEXT = bytes("nested deflated payload ".repeat(40));
    private static final byte[] CORRUPT_STORED = bytes("corrupt stored resource");
    private static final byte[] CORRUPT_DEFLATED = bytes("corrupt deflated resource ".repeat(20));
    private static final byte[] NESTED_MANIFEST = bytes("Manifest-Version: 1.0\r\n"
            + "Implementation-Title: Dependency\r\n"
            + "Multi-Release: true\r\n\r\n");
    private static final byte[] OUTER_MANIFEST = bytes("Manifest-Version: 1.0\r\n"
            + "Main-Class: io.micronaut.runner.Launcher\r\n\r\n"
            + "Name: outer.txt\r\n"
            + "Purpose: lifecycle-test\r\n\r\n");
    private static final byte[] APPLICATION_MANIFEST = bytes("Manifest-Version: 1.0\r\n"
            + "Implementation-Title: Application\r\n\r\n");

    @TempDir
    Path temporary;

    private File archive;
    private ArchiveSource source;
    private Index index;
    private int files;

    @BeforeEach
    void openArchive() throws IOException {
        TestArchiveBuilder dependency = new TestArchiveBuilder();
        long[] inner = new long[5];
        inner[0] = dependency.stored("META-INF/MANIFEST.MF", NESTED_MANIFEST);
        inner[1] = dependency.stored("a/B.class", BASE_CLASS);
        inner[2] = dependency.deflated(NESTED_TEXT_NAME, NESTED_TEXT);
        inner[3] = dependency.stored("META-INF/versions/21/a/B.class", VERSIONED_CLASS);
        inner[4] = dependency.deflated("corrupt-deflated.txt", CORRUPT_DEFLATED);
        byte[] dependencyBytes = dependency.build();
        int nestedCompressed = dependency.storedSize(NESTED_TEXT_NAME);
        int corruptNestedCompressed = dependency.storedSize("corrupt-deflated.txt");

        TestArchiveBuilder outer = new TestArchiveBuilder();
        outer.stored("META-INF/MANIFEST.MF", OUTER_MANIFEST);
        outer.stored("outer.txt", bytes("outer resource"));
        byte[] draft = buildIndex(new long[6], inner, 0, 0, 0, 0, nestedCompressed,
                corruptNestedCompressed);
        outer.reserve(IndexFormat.INDEX_ENTRY_NAME, draft.length);
        long[] application = new long[6];
        application[0] = outer.stored(IndexFormat.CLASSES_PREFIX + "app.txt", APP_TEXT);
        application[1] = outer.deflated(IndexFormat.CLASSES_PREFIX + AWKWARD, AWKWARD_TEXT);
        application[2] = outer.stored(IndexFormat.CLASSES_PREFIX + BANG, BANG_TEXT);
        application[3] = outer.stored(SERVICE, new byte[0]);
        application[4] = outer.stored(IndexFormat.CLASSES_PREFIX + "META-INF/MANIFEST.MF",
                APPLICATION_MANIFEST);
        application[5] = outer.stored(IndexFormat.CLASSES_PREFIX + "corrupt-stored.txt", CORRUPT_STORED);
        int awkwardCompressed = outer.storedSize(IndexFormat.CLASSES_PREFIX + AWKWARD);
        long base = outer.stored(DEPENDENCY, dependencyBytes);
        long header = outer.localHeaderOffset(DEPENDENCY);
        byte[] real = buildIndex(application, inner, base, dependencyBytes.length, header, awkwardCompressed,
                nestedCompressed, corruptNestedCompressed);
        assertEquals(draft.length, real.length, "the index size must not depend on the offsets");
        outer.replace(IndexFormat.INDEX_ENTRY_NAME, real);

        archive = outer.writeTo(newFile("app.jar"));
        source = ArchiveSource.open(archive);
        index = Index.open(source);
        index.validateStringReferences();
        Handlers.register(archive, index, source);
    }

    /**
     * Turns verification on and registers the handler again over a source and index reopened on the same
     * archive file, because an index reads the verification flag once, when it is opened. A URL keeps the
     * handler of the registration it was made under, so verified reads need URLs made after this call.
     */
    private void reregisterVerifying() throws IOException {
        System.setProperty(io.micronaut.runner.RunnerClassLoader.VERIFY_PROPERTY, "true");
        Handlers.unregister();
        source.close();
        source = ArchiveSource.open(archive);
        index = Index.open(source);
        index.validateStringReferences();
        Handlers.register(archive, index, source);
    }

    @AfterEach
    void closeArchive() {
        System.clearProperty(io.micronaut.runner.RunnerClassLoader.VERIFY_PROPERTY);
        Handlers.unregister();
        if (source != null) {
            source.close();
            source = null;
        }
    }

    @Test
    void encodesAwkwardNamesAndReadsThemBackFromTheirStringForm() throws Exception {
        URL url = Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, AWKWARD);
        StringBuilder expected = new StringBuilder();
        expected.append("jar:").append(fileUrl()).append("!/").append(IndexFormat.CLASSES_PREFIX)
                .append(ENCODED_AWKWARD);
        assertEquals(expected.toString(), url.toString());
        // A URL that cannot be turned into a URI and back is a URL no library can pass around.
        assertEquals(url.toString(), url.toURI().toString());

        URL reparsed = URI.create(url.toString()).toURL();
        assertInstanceOf(RunnerJarURLConnection.class, reparsed.openConnection(),
                "a URL rebuilt from its string form must come back to our handler");
        assertArrayEquals(AWKWARD_TEXT, read(reparsed));
        JarURLConnection connection = (JarURLConnection) reparsed.openConnection();
        assertEquals(IndexFormat.CLASSES_PREFIX + AWKWARD, connection.getEntryName());
    }

    @Test
    void encodesTheSeparatorInsideAnEntryName() throws IOException {
        URL url = Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, BANG);
        assertTrue(url.toString().endsWith("bang%21/fake.txt"), url.toString());
        JarURLConnection connection = (JarURLConnection) URI.create(url.toString()).toURL().openConnection();
        assertEquals(IndexFormat.CLASSES_PREFIX + BANG, connection.getEntryName());
        assertArrayEquals(BANG_TEXT, read(url));
    }

    @Test
    void describesAnApplicationLayerDirectoryFromItsIndexRecord() throws IOException {
        // The application layer is stored exploded, so the outer archive carries no entry for a directory
        // inside it. Asking the outer JarFile for one answered null while getEntryName() was not null and
        // getInputStream() worked: four accessors that no longer described the same entry, and a
        // NullPointerException for the usual conn.getJarEntry().isDirectory() way of classifying a URL.
        URL url = Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "weird/");
        JarURLConnection connection = (JarURLConnection) url.openConnection();
        connection.setUseCaches(false);

        assertEquals(IndexFormat.CLASSES_PREFIX + "weird/", connection.getEntryName());
        JarEntry entry = connection.getJarEntry();
        assertNotNull(entry, "a URL that resolves has to describe an entry");
        assertEquals(connection.getEntryName(), entry.getName());
        assertTrue(entry.isDirectory());
        assertEquals(0, entry.getSize());
        try (JarFile jar = connection.getJarFile()) {
            assertNull(jar.getEntry(connection.getEntryName()),
                    "the outer archive really does not carry this entry, which is the whole point");
        }
        try (InputStream in = connection.getInputStream()) {
            assertEquals(0, in.readAllBytes().length);
        }
    }

    @Test
    void describesAnApplicationLayerFileFromItsIndexRecord() throws IOException {
        JarURLConnection connection =
                (JarURLConnection) Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "app.txt")
                        .openConnection();
        connection.setUseCaches(false);

        JarEntry entry = connection.getJarEntry();
        assertEquals(IndexFormat.CLASSES_PREFIX + "app.txt", entry.getName());
        assertEquals(APP_TEXT.length, entry.getSize());
        assertFalse(entry.isDirectory());
        assertEquals(DOS_TIME_MILLIS, entry.getTime());
    }

    @Test
    void buildsTheDocumentedUrlShapes() {
        StringBuilder application = new StringBuilder();
        application.append("jar:").append(fileUrl()).append("!/").append(IndexFormat.CLASSES_PREFIX);
        assertEquals(application + "app.txt",
                Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "app.txt").toString());
        assertEquals(application.toString(),
                Handlers.codeSourceUrlFor(IndexFormat.APPLICATION_JAR_ID).toString());

        StringBuilder nested = new StringBuilder();
        nested.append("jar:").append(fileUrl()).append("!/").append(DEPENDENCY);
        assertEquals(nested + "!/a/B.class", Handlers.urlFor(1, "a/B.class").toString());
        assertEquals(nested + "!/", Handlers.codeSourceUrlFor(1).toString());
        assertEquals(nested.toString(), Handlers.jarFileUrlFor(1).toString());
        assertEquals(fileUrl(), Handlers.jarFileUrlFor(IndexFormat.APPLICATION_JAR_ID).toString());
        assertNull(Handlers.urlFor(7, "a/B.class"));
        assertNull(Handlers.codeSourceUrlFor(-1));
    }

    /**
     * Pins every field of the URLs the class loader hands out, now that no {@link URI} parse guards their
     * construction: each shape, reached through the plain-name fast path, a leading slash, the full encoder
     * and a plain run that stops at an escape, has to be a valid URI and has to equal its own string form
     * re-parsed.
     */
    @Test
    void buildsEveryUrlShapeWithTheFieldsOfItsReparsedStringForm() throws Exception {
        String awkward = "odd/a b%c#d?e!f[g]h é😀.txt";
        String encodedAwkward = "odd/a%20b%25c%23d%3Fe%21f%5Bg%5Dh%20%C3%A9%F0%9F%98%80.txt";
        String dollar = "a/B$C.class";
        String encodedDollar = "a/B%24C.class";
        String classes = IndexFormat.CLASSES_PREFIX;
        String nested = DEPENDENCY + "!/";
        int app = IndexFormat.APPLICATION_JAR_ID;

        assertUrlShape(Handlers.urlFor(app, "app.txt"), classes + "app.txt");
        assertUrlShape(Handlers.urlFor(app, "/app.txt"), classes + "app.txt");
        assertUrlShape(Handlers.urlFor(app, awkward), classes + encodedAwkward);
        assertUrlShape(Handlers.urlFor(app, dollar), classes + encodedDollar);

        assertUrlShape(Handlers.urlFor(1, "a/B.class"), nested + "a/B.class");
        assertUrlShape(Handlers.urlFor(1, "/a/B.class"), nested + "a/B.class");
        assertUrlShape(Handlers.urlFor(1, awkward), nested + encodedAwkward);
        assertUrlShape(Handlers.urlFor(1, dollar), nested + encodedDollar);

        // A leading slash defeats urlFor's routing of the merged service directory, so that case has to
        // go to outerUrlFor directly.
        assertUrlShape(Handlers.urlFor(app, SERVICE), SERVICE);
        assertUrlShape(Handlers.outerUrlFor("/" + SERVICE), SERVICE);
        assertUrlShape(Handlers.outerUrlFor("META-INF/micronaut/" + awkward),
                "META-INF/micronaut/" + encodedAwkward);

        assertUrlShape(Handlers.codeSourceUrlFor(app), classes);
        assertUrlShape(Handlers.codeSourceUrlFor(1), nested);
    }

    @Test
    @SuppressWarnings("deprecation")
    void resolvesRelativeAndParentSpecs() throws IOException {
        URL base = Handlers.urlFor(1, "a/B.class");

        URL sibling = new URL(base, "data%20file.txt");
        assertEquals(Handlers.urlFor(1, NESTED_TEXT_NAME).toString(), sibling.toString());
        assertArrayEquals(NESTED_TEXT, read(sibling));

        URL parent = new URL(base, "../a/B.class");
        assertEquals(base.toString(), parent.toString());

        URL rooted = new URL(base, "/META-INF/MANIFEST.MF");
        assertEquals(Handlers.urlFor(1, "META-INF/MANIFEST.MF").toString(), rooted.toString());
        assertArrayEquals(NESTED_MANIFEST, read(rooted));

        URL dotted = new URL(base, "./B.class");
        assertEquals(base.toString(), dotted.toString());

        assertThrows(MalformedURLException.class, () -> new URL(base, "../../../../etc/passwd"));
        assertThrows(MalformedURLException.class, () -> new URL(base, "/../outside"));
    }

    @Test
    void opensForeignJarUrlsWithTheJdkHandler() throws IOException {
        byte[] content = bytes("a jar that is nothing to do with the runner");
        TestArchiveBuilder plain = new TestArchiveBuilder();
        plain.stored("hello.txt", content);
        File other = plain.writeTo(newFile("other.jar"));

        StringBuilder spec = new StringBuilder();
        spec.append("jar:").append(other.toURI()).append("!/hello.txt");
        URL url = URI.create(spec.toString()).toURL();
        URLConnection connection = url.openConnection();
        connection.setUseCaches(false);
        assertFalse(connection instanceof RunnerJarURLConnection,
                "a URL of another file must be handed to the JDK");
        assertInstanceOf(JarURLConnection.class, connection);
        try (InputStream in = connection.getInputStream()) {
            assertArrayEquals(content, in.readAllBytes());
        }
    }

    @Test
    void agreesOnTheIdentityOfANestedEntry() throws IOException {
        URL url = Handlers.urlFor(1, "a/B.class");
        JarURLConnection connection = (JarURLConnection) url.openConnection();

        StringBuilder jarUrl = new StringBuilder();
        jarUrl.append("jar:").append(fileUrl()).append("!/").append(DEPENDENCY);
        assertEquals(jarUrl.toString(), connection.getJarFileURL().toString());
        assertEquals("a/B.class", connection.getEntryName());

        JarFile jar = connection.getJarFile();
        assertInstanceOf(NestedJarFile.class, jar);
        assertEquals(1, ((NestedJarFile) jar).jarId());

        JarEntry entry = connection.getJarEntry();
        assertEquals("a/B.class", entry.getName());
        assertEquals("META-INF/versions/21/a/B.class", entry.getRealName());
        assertEquals(VERSIONED_CLASS.length, entry.getSize());
        assertEquals(entry.getName(), jar.getJarEntry(connection.getEntryName()).getName());
        assertArrayEquals(VERSIONED_CLASS, read(url));

        Attributes main = connection.getMainAttributes();
        assertEquals("Dependency", main.getValue("Implementation-Title"));
        assertNull(connection.getCertificates());
    }

    @Test
    void agreesOnTheIdentityOfAnApplicationEntry() throws IOException {
        URL url = Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "app.txt");
        JarURLConnection connection = (JarURLConnection) url.openConnection();

        assertEquals(fileUrl(), connection.getJarFileURL().toString());
        assertEquals(IndexFormat.CLASSES_PREFIX + "app.txt", connection.getEntryName());
        JarFile jar = connection.getJarFile();
        assertFalse(jar instanceof NestedJarFile);
        assertEquals(archive.getPath(), jar.getName());
        assertEquals(connection.getEntryName(), connection.getJarEntry().getName());
        assertArrayEquals(APP_TEXT, read(url));
        assertEquals("io.micronaut.runner.Launcher", connection.getMainAttributes().getValue("Main-Class"));
    }

    @Test
    void servesTheOuterArchivesOwnEntries() throws IOException {
        URL service = Handlers.outerUrlFor(SERVICE);
        StringBuilder spec = new StringBuilder();
        spec.append("jar:").append(fileUrl()).append("!/").append(SERVICE);
        assertEquals(spec.toString(), service.toString());
        assertEquals(0, read(service).length);
        assertEquals(0, read(URI.create(spec.toString()).toURL()).length);

        // The physical archive answers first, so this is the outer manifest and not the application
        // layer's entry of the same logical name.
        assertArrayEquals(OUTER_MANIFEST, read(Handlers.outerUrlFor("META-INF/MANIFEST.MF")));
        assertArrayEquals(OUTER_MANIFEST, read(Handlers.outerUrlFor("/META-INF/MANIFEST.MF")));
        assertArrayEquals(APPLICATION_MANIFEST,
                read(Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "META-INF/MANIFEST.MF")));
        assertNull(Handlers.outerUrlFor(null));

        // A nested jar addressed as an entry of the outer archive is its stored bytes.
        URL dependency = Handlers.outerUrlFor(DEPENDENCY);
        assertEquals(index.jarDataLength(1), read(dependency).length);
    }

    @Test
    void readsStoredAndDeflatedEntriesOfBothLayers() throws IOException {
        assertArrayEquals(APP_TEXT, read(Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "app.txt")));
        assertArrayEquals(AWKWARD_TEXT, read(Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, AWKWARD)));
        assertArrayEquals(NESTED_MANIFEST, read(Handlers.urlFor(1, "META-INF/MANIFEST.MF")));
        assertArrayEquals(NESTED_TEXT, read(Handlers.urlFor(1, NESTED_TEXT_NAME)));
    }

    @Test
    void verifiesStoredAndDeflatedUrlStreamsWithAndWithoutCaching() throws IOException {
        assertArrayEquals(CORRUPT_STORED,
                read(Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "corrupt-stored.txt")));
        assertArrayEquals(CORRUPT_DEFLATED, read(Handlers.urlFor(1, "corrupt-deflated.txt")));

        reregisterVerifying();
        URL stored = Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "corrupt-stored.txt");
        URL deflated = Handlers.urlFor(1, "corrupt-deflated.txt");
        for (boolean caches : List.of(true, false)) {
            IOException storedFailure = assertThrows(IOException.class, () -> read(stored, caches));
            assertTrue(storedFailure.getMessage().contains("corrupt-stored.txt"), storedFailure.getMessage());
            IOException deflatedFailure = assertThrows(IOException.class, () -> read(deflated, caches));
            assertTrue(deflatedFailure.getMessage().contains("corrupt-deflated.txt"),
                    deflatedFailure.getMessage());
        }
        assertArrayEquals(APP_TEXT, read(Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "app.txt")),
                "an intact URL still reads with verification on");
    }

    @Test
    void urlStreamsPerformLazyNestedHeaderValidationWithoutMetadataAccess() throws IOException {
        try (RandomAccessFile editable = new RandomAccessFile(archive, "rw")) {
            editable.seek(index.jarLocalHeaderOffset(1));
            editable.write(new byte[4]);
        }

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Handlers.urlFor(1, NESTED_TEXT_NAME).openStream());
        assertTrue(failure.getMessage().contains("no local file header"), failure.getMessage());
    }

    @Test
    void verificationIsIndependentAcrossConcurrentRepeatedUrlReads() throws Exception {
        reregisterVerifying();
        URL valid = Handlers.urlFor(1, NESTED_TEXT_NAME);
        URL corrupt = Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "corrupt-stored.txt");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Void>> reads = new ArrayList<>();
            for (boolean caches : List.of(true, false)) {
                for (int task = 0; task < 4; task++) {
                    reads.add(pool.submit(() -> {
                        for (int i = 0; i < 20; i++) {
                            assertArrayEquals(NESTED_TEXT, read(valid, caches));
                            IOException failure = assertThrows(IOException.class, () -> read(corrupt, caches));
                            assertTrue(failure.getMessage().contains("corrupt-stored.txt"),
                                    failure.getMessage());
                        }
                        return null;
                    }));
                }
            }
            for (Future<Void> read : reads) {
                read.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void readsRepeatedlyWithCachesDisabled() throws IOException {
        URLConnection connection = Handlers.urlFor(1, NESTED_TEXT_NAME).openConnection();
        connection.setUseCaches(false);
        assertFalse(connection.getUseCaches());
        for (int i = 0; i < 3; i++) {
            try (InputStream in = connection.getInputStream()) {
                assertArrayEquals(NESTED_TEXT, in.readAllBytes());
            }
        }
        // Flipping the flag after the connection is connected must not throw, unlike the JDK's default.
        connection.setUseCaches(true);
        assertTrue(connection.getUseCaches());
        try (InputStream in = connection.getInputStream()) {
            assertArrayEquals(NESTED_TEXT, in.readAllBytes());
        }
    }

    @Test
    void closingUncachedOuterJarDoesNotPoisonFreshConnection() throws IOException {
        JarURLConnection first = (JarURLConnection) Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "app.txt")
                .openConnection();
        first.setUseCaches(false);
        try (JarFile ignored = first.getJarFile()) {
            assertEquals(archive.getPath(), ignored.getName());
        }

        assertArrayEquals(OUTER_MANIFEST, read(Handlers.outerUrlFor("META-INF/MANIFEST.MF")));
    }

    @Test
    void closingCachedOuterJarDoesNotPoisonAnotherConsumer() throws IOException {
        JarURLConnection first = (JarURLConnection) Handlers.outerUrlFor("META-INF/MANIFEST.MF")
                .openConnection();
        JarURLConnection second = (JarURLConnection) Handlers.outerUrlFor("META-INF/MANIFEST.MF")
                .openConnection();
        JarFile firstJar = first.getJarFile();
        JarFile secondJar = second.getJarFile();
        assertSame(firstJar, secondJar, "cached connections should share the process-lifetime view");

        firstJar.close();

        assertEquals("io.micronaut.runner.Launcher", second.getMainAttributes().getValue("Main-Class"));
        assertArrayEquals(OUTER_MANIFEST, read(Handlers.outerUrlFor("META-INF/MANIFEST.MF")));
    }

    @Test
    void uncachedOuterEntriesRetainManifestAttributes() throws IOException {
        JarURLConnection connection = (JarURLConnection) Handlers.outerUrlFor("outer.txt").openConnection();
        connection.setUseCaches(false);

        assertEquals("lifecycle-test", connection.getJarEntry().getAttributes().getValue("Purpose"));
    }

    @Test
    void manifestAccessStillValidatesTheNamedOuterEntry() throws IOException {
        URL missing = Handlers.outerUrlFor("missing.txt");
        for (boolean caches : List.of(true, false)) {
            JarURLConnection manifest = (JarURLConnection) missing.openConnection();
            manifest.setUseCaches(caches);
            assertThrows(FileNotFoundException.class, manifest::getManifest);
            JarURLConnection attributes = (JarURLConnection) missing.openConnection();
            attributes.setUseCaches(caches);
            assertThrows(FileNotFoundException.class, attributes::getMainAttributes);
        }
    }

    @Test
    void uncachedOuterStreamsReleaseTheirOwnedJarFiles() throws IOException {
        for (int i = 0; i < 3; i++) {
            JarURLConnection connection = (JarURLConnection) Handlers.outerUrlFor("META-INF/MANIFEST.MF")
                    .openConnection();
            connection.setUseCaches(false);
            try (InputStream in = connection.getInputStream()) {
                assertArrayEquals(OUTER_MANIFEST, in.readAllBytes());
            }
        }
    }

    @Test
    void uncachedOuterStreamsHaveIndependentLifetimesOnOneConnection() throws IOException {
        JarURLConnection connection = (JarURLConnection) Handlers.outerUrlFor("META-INF/MANIFEST.MF")
                .openConnection();
        connection.setUseCaches(false);

        InputStream first = connection.getInputStream();
        try (InputStream second = connection.getInputStream()) {
            first.close();
            assertArrayEquals(OUTER_MANIFEST, second.readAllBytes());
        }
        try (InputStream third = connection.getInputStream()) {
            assertArrayEquals(OUTER_MANIFEST, third.readAllBytes());
        }
    }

    @Test
    void anOwnedOuterJarRemainsOwnedAfterTheCacheFlagChanges() throws IOException {
        JarURLConnection connection = (JarURLConnection) Handlers.outerUrlFor("META-INF/MANIFEST.MF")
                .openConnection();
        connection.setUseCaches(false);
        JarFile jar = connection.getJarFile();
        connection.setUseCaches(true);

        try (InputStream in = connection.getInputStream()) {
            assertArrayEquals(OUTER_MANIFEST, in.readAllBytes());
        }

        assertTrue(jar.size() > 0, "the explicitly requested handle remains caller-owned");
        jar.close();
    }

    @Test
    void anUncachedIndexedStreamDoesNotCloseTheCallerOwnedOuterJar() throws IOException {
        JarURLConnection connection = (JarURLConnection) Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "app.txt")
                .openConnection();
        connection.setUseCaches(false);
        JarFile jar = connection.getJarFile();

        try (InputStream in = connection.getInputStream()) {
            assertArrayEquals(APP_TEXT, in.readAllBytes());
        }

        assertTrue(jar.size() > 0, "the explicitly requested handle remains caller-owned");
        jar.close();
    }

    @Test
    void protocolDefaultDisablesOuterJarSharing() throws IOException {
        boolean previous = URLConnection.getDefaultUseCaches("jar");
        URLConnection.setDefaultUseCaches("jar", false);
        try {
            JarURLConnection first = (JarURLConnection) Handlers.outerUrlFor("META-INF/MANIFEST.MF")
                    .openConnection();
            JarURLConnection second = (JarURLConnection) Handlers.outerUrlFor("META-INF/MANIFEST.MF")
                    .openConnection();
            assertFalse(first.getUseCaches());
            assertFalse(second.getUseCaches());
            JarFile firstJar = first.getJarFile();
            JarFile secondJar = second.getJarFile();
            assertNotSame(firstJar, secondJar);

            firstJar.close();

            assertEquals("io.micronaut.runner.Launcher", second.getMainAttributes().getValue("Main-Class"));
            secondJar.close();
        } finally {
            URLConnection.setDefaultUseCaches("jar", previous);
        }
    }

    @Test
    void concurrentOuterConsumersCloseIndependentlyWithAndWithoutCaching() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (boolean caches : List.of(true, false)) {
                for (int i = 0; i < 10; i++) {
                    JarURLConnection first = (JarURLConnection) Handlers.outerUrlFor("META-INF/MANIFEST.MF")
                            .openConnection();
                    JarURLConnection second = (JarURLConnection) Handlers.outerUrlFor("META-INF/MANIFEST.MF")
                            .openConnection();
                    first.setUseCaches(caches);
                    second.setUseCaches(caches);
                    JarFile firstJar = first.getJarFile();
                    try (firstJar; JarFile secondJar = second.getJarFile()) {
                        CountDownLatch start = new CountDownLatch(1);
                        Future<Void> close = pool.submit(() -> {
                            start.await();
                            firstJar.close();
                            return null;
                        });
                        Future<byte[]> read = pool.submit(() -> {
                            start.await();
                            try (InputStream in = second.getInputStream()) {
                                return in.readAllBytes();
                            }
                        });

                        start.countDown();
                        close.get(10, TimeUnit.SECONDS);
                        assertArrayEquals(OUTER_MANIFEST, read.get(10, TimeUnit.SECONDS));
                    }
                }
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void opensAWholeNestedJarInBothItsForms() throws IOException {
        URL withSeparator = Handlers.codeSourceUrlFor(1);
        JarURLConnection connection = (JarURLConnection) withSeparator.openConnection();
        assertNull(connection.getEntryName());
        assertNull(connection.getJarEntry());
        assertInstanceOf(NestedJarFile.class, connection.getJarFile());
        assertEquals("x-java/jar", connection.getContentType());
        assertEquals(entryNames(withSeparator), entryNames(Handlers.jarFileUrlFor(1)));

        List<String> names = entryNames(withSeparator);
        assertEquals(List.of("META-INF/MANIFEST.MF", "a/B.class", NESTED_TEXT_NAME,
                "META-INF/versions/21/a/B.class", "corrupt-deflated.txt"), names);
    }

    @Test
    void reportsLengthTypeAndTimestamp() throws IOException {
        URLConnection text = Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "app.txt").openConnection();
        assertEquals(APP_TEXT.length, text.getContentLengthLong());
        assertEquals(APP_TEXT.length, text.getContentLength());
        assertEquals("text/plain", text.getContentType());
        assertEquals(DOS_TIME_MILLIS, text.getLastModified());
        assertEquals(Long.toString(APP_TEXT.length), text.getHeaderField("Content-Length"));

        URLConnection deflated = Handlers.urlFor(1, NESTED_TEXT_NAME).openConnection();
        assertEquals(NESTED_TEXT.length, deflated.getContentLengthLong());

        URLConnection whole = Handlers.codeSourceUrlFor(1).openConnection();
        assertEquals(index.jarDataLength(1), whole.getContentLengthLong());
    }

    @Test
    void throwsFileNotFoundForUnknownEntries() throws IOException {
        URL missingNested = Handlers.urlFor(1, "a/Missing.class");
        assertThrows(IOException.class, () -> missingNested.openConnection().connect());
        assertThrows(IOException.class, () -> read(missingNested));

        URL missingApplication = Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "missing.txt");
        assertThrows(IOException.class, () -> read(missingApplication));

        StringBuilder spec = new StringBuilder();
        spec.append("jar:").append(fileUrl()).append("!/MICRONAUT-INF/lib/absent.jar!/a/B.class");
        URL missingJar = URI.create(spec.toString()).toURL();
        assertThrows(IOException.class, () -> read(missingJar));
    }

    @Test
    void comparesUrlsWithoutOpeningOrResolvingAnything() throws IOException {
        URL url = Handlers.urlFor(1, "a/B.class");
        URL same = URI.create(url.toString()).toURL();
        URL other = Handlers.urlFor(1, NESTED_TEXT_NAME);
        assertEquals(url, same);
        assertEquals(url.hashCode(), same.hashCode());
        assertTrue(url.sameFile(same));
        assertNotEquals(url, other);
        assertFalse(url.sameFile(other));
    }

    @Test
    void interoperatesWithJdkUrlsCreatedBeforeRegistration() throws Exception {
        Path java = javaExecutable();
        assertNotNull(java, "no JDK to fork; set runner.test.javaHome");
        String testClasses = Path.of(HandlerInteroperability.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        String mainClasses = Path.of(Handler.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        Process process = new ProcessBuilder(java.toString(), "-cp",
                testClasses + File.pathSeparator + mainClasses,
                HandlerInteroperability.class.getName(), archive.getAbsolutePath())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "forked equality test timed out");
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("INTEROPERABLE"), output);
    }

    @Test
    void rejectsMalformedJarUrls() {
        assertThrows(MalformedURLException.class, () -> URI.create("jar:file:/x.jar").toURL());
        assertThrows(MalformedURLException.class, () -> URI.create("jar:!/entry").toURL());
        assertThrows(MalformedURLException.class,
                () -> URI.create("jar:jar:file:/x.jar!/inner.jar!/e").toURL());
    }

    private byte[] buildIndex(long[] application, long[] inner, long base, long length, long header,
                              int awkwardCompressed, int nestedCompressed, int corruptNestedCompressed) {
        TestIndexBuilder builder = new TestIndexBuilder()
                .startClass("com.example.Application")
                .headerFlags(IndexFormat.HEADER_FLAG_NESTED_STORED);
        TestIndexBuilder.Jar layer = builder.addJar(IndexFormat.CLASSES_PREFIX);
        layer.addEntry("app.txt").data(application[0], APP_TEXT.length, APP_TEXT.length)
                .crc32(crc32(APP_TEXT)).dosTime(DOS_TIME);
        layer.addEntry(AWKWARD).data(application[1], awkwardCompressed, AWKWARD_TEXT.length)
                .method(IndexFormat.METHOD_DEFLATED).crc32(crc32(AWKWARD_TEXT));
        layer.addEntry(BANG).data(application[2], BANG_TEXT.length, BANG_TEXT.length)
                .crc32(crc32(BANG_TEXT));
        layer.addEntry(SERVICE).data(application[3], 0, 0).crc32(0);
        layer.addEntry("META-INF/MANIFEST.MF")
                .data(application[4], APPLICATION_MANIFEST.length, APPLICATION_MANIFEST.length)
                .crc32(crc32(APPLICATION_MANIFEST));
        layer.addEntry("corrupt-stored.txt")
                .data(application[5], CORRUPT_STORED.length, CORRUPT_STORED.length)
                .crc32(crc32(CORRUPT_STORED) ^ 0xFFFFFFFFL);
        TestIndexBuilder.Jar dependency = builder.addJar(DEPENDENCY)
                .coordinates("com.example:dep:1.0")
                .location(base, length, header)
                .manifest(null, null, null, "Dependency", "1.0", null)
                .multiRelease();
        dependency.addEntry("META-INF/MANIFEST.MF")
                .data(base + inner[0], NESTED_MANIFEST.length, NESTED_MANIFEST.length)
                .crc32(crc32(NESTED_MANIFEST));
        dependency.addEntry("a/B.class").data(base + inner[1], BASE_CLASS.length, BASE_CLASS.length)
                .crc32(crc32(BASE_CLASS));
        dependency.addEntry(NESTED_TEXT_NAME).data(base + inner[2], nestedCompressed, NESTED_TEXT.length)
                .method(IndexFormat.METHOD_DEFLATED).crc32(crc32(NESTED_TEXT));
        dependency.addEntry("META-INF/versions/21/a/B.class")
                .data(base + inner[3], VERSIONED_CLASS.length, VERSIONED_CLASS.length)
                .crc32(crc32(VERSIONED_CLASS));
        dependency.addEntry("corrupt-deflated.txt")
                .data(base + inner[4], corruptNestedCompressed, CORRUPT_DEFLATED.length)
                .method(IndexFormat.METHOD_DEFLATED)
                .crc32(crc32(CORRUPT_DEFLATED) ^ 0xFFFFFFFFL);
        return builder.build();
    }

    /**
     * The expected {@code file:} URL of the archive, taken from the JDK rather than rebuilt here.
     *
     * <p>This used to concatenate "file:" with the path, which silently encoded the same mistake the
     * production code made on Windows, where a path starts with a drive letter rather than a separator:
     * both produced the opaque {@code file:C:/dir/app.jar}, so the assertions agreed with each other and
     * with nothing else. {@link File#toURI()} is an independent oracle.</p>
     *
     * @return the archive URL
     */
    private String fileUrl() {
        return archive.toURI().toString();
    }

    /**
     * Asserts that a URL of the registered archive has the documented text, is a valid URI, is equal to
     * the URL re-parsed from its string form, and has the fields the handler's own parse sets.
     *
     * @param url          the URL under test
     * @param expectedPath the encoded part after the archive's {@value Handlers#SEPARATOR}
     */
    private void assertUrlShape(URL url, String expectedPath) throws Exception {
        assertNotNull(url, expectedPath);
        StringBuilder expected = new StringBuilder();
        expected.append("jar:").append(fileUrl()).append(Handlers.SEPARATOR).append(expectedPath);
        String spec = expected.toString();
        assertEquals(spec, url.toString());
        assertEquals(spec, url.toExternalForm());
        assertEquals(spec, url.toURI().toString(), "the URL must be a valid URI that round trips");

        URL reparsed = URI.create(url.toString()).toURL();
        assertEquals(reparsed, url, spec);
        assertEquals(url, reparsed, spec);
        assertEquals(reparsed.hashCode(), url.hashCode(), spec);
        assertEquals(reparsed.getFile(), url.getFile(), spec);
        assertEquals(reparsed.getPath(), url.getPath(), spec);

        assertEquals("jar", url.getProtocol(), spec);
        assertNull(url.getAuthority(), spec);
        assertEquals("", url.getHost(), spec);
        assertEquals(-1, url.getPort(), spec);
        assertNull(url.getUserInfo(), spec);
        assertNull(url.getQuery(), spec);
        assertNull(url.getRef(), spec);
        assertEquals(spec.substring("jar:".length()), url.getFile(), spec);
    }

    private List<String> entryNames(URL url) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(url.openStream())) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private byte[] read(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        try (InputStream in = connection.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private byte[] read(URL url, boolean caches) throws IOException {
        URLConnection connection = url.openConnection();
        connection.setUseCaches(caches);
        try (InputStream in = connection.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private File newFile(String name) {
        files++;
        StringBuilder unique = new StringBuilder();
        unique.append(files).append('-').append(name);
        return temporary.resolve(unique.toString()).toFile();
    }

    private static Path javaExecutable() {
        String home = System.getProperty("runner.test.javaHome", System.getProperty("java.home"));
        if (home == null || home.isEmpty()) {
            return null;
        }
        Path candidate = Path.of(home, "bin", "java");
        if (Files.isExecutable(candidate)) {
            return candidate;
        }
        candidate = Path.of(home, "bin", "java.exe");
        return Files.isExecutable(candidate) ? candidate : null;
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
