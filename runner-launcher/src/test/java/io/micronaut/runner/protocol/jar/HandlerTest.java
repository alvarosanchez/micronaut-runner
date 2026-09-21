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
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private static final byte[] NESTED_MANIFEST = bytes("Manifest-Version: 1.0\r\n"
            + "Implementation-Title: Dependency\r\n"
            + "Multi-Release: true\r\n\r\n");
    private static final byte[] OUTER_MANIFEST = bytes("Manifest-Version: 1.0\r\n"
            + "Main-Class: io.micronaut.runner.Launcher\r\n\r\n");
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
        long[] inner = new long[4];
        inner[0] = dependency.stored("META-INF/MANIFEST.MF", NESTED_MANIFEST);
        inner[1] = dependency.stored("a/B.class", BASE_CLASS);
        inner[2] = dependency.deflated(NESTED_TEXT_NAME, NESTED_TEXT);
        inner[3] = dependency.stored("META-INF/versions/21/a/B.class", VERSIONED_CLASS);
        byte[] dependencyBytes = dependency.build();
        int nestedCompressed = dependency.storedSize(NESTED_TEXT_NAME);

        TestArchiveBuilder outer = new TestArchiveBuilder();
        outer.stored("META-INF/MANIFEST.MF", OUTER_MANIFEST);
        byte[] draft = buildIndex(new long[5], inner, 0, 0, 0, 0, nestedCompressed);
        outer.reserve(IndexFormat.INDEX_ENTRY_NAME, draft.length);
        long[] application = new long[5];
        application[0] = outer.stored(IndexFormat.CLASSES_PREFIX + "app.txt", APP_TEXT);
        application[1] = outer.deflated(IndexFormat.CLASSES_PREFIX + AWKWARD, AWKWARD_TEXT);
        application[2] = outer.stored(IndexFormat.CLASSES_PREFIX + BANG, BANG_TEXT);
        application[3] = outer.stored(SERVICE, new byte[0]);
        application[4] = outer.stored(IndexFormat.CLASSES_PREFIX + "META-INF/MANIFEST.MF",
                APPLICATION_MANIFEST);
        int awkwardCompressed = outer.storedSize(IndexFormat.CLASSES_PREFIX + AWKWARD);
        long base = outer.stored(DEPENDENCY, dependencyBytes);
        long header = outer.localHeaderOffset(DEPENDENCY);
        byte[] real = buildIndex(application, inner, base, dependencyBytes.length, header, awkwardCompressed,
                nestedCompressed);
        assertEquals(draft.length, real.length, "the index size must not depend on the offsets");
        outer.replace(IndexFormat.INDEX_ENTRY_NAME, real);

        archive = outer.writeTo(newFile("app.jar"));
        source = ArchiveSource.open(archive);
        index = Index.open(source);
        index.validateStringReferences();
        Handlers.register(archive, index, source);
    }

    @AfterEach
    void closeArchive() {
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
                "META-INF/versions/21/a/B.class"), names);
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
    void rejectsMalformedJarUrls() {
        assertThrows(MalformedURLException.class, () -> URI.create("jar:file:/x.jar").toURL());
        assertThrows(MalformedURLException.class, () -> URI.create("jar:!/entry").toURL());
        assertThrows(MalformedURLException.class,
                () -> URI.create("jar:jar:file:/x.jar!/inner.jar!/e").toURL());
    }

    private byte[] buildIndex(long[] application, long[] inner, long base, long length, long header,
                         int awkwardCompressed, int nestedCompressed) {
        TestIndexBuilder builder = new TestIndexBuilder()
                .startClass("com.example.Application")
                .headerFlags(IndexFormat.HEADER_FLAG_NESTED_STORED);
        TestIndexBuilder.Jar layer = builder.addJar(IndexFormat.CLASSES_PREFIX);
        layer.addEntry("app.txt").data(application[0], APP_TEXT.length, APP_TEXT.length)
                .dosTime(DOS_TIME);
        layer.addEntry(AWKWARD).data(application[1], awkwardCompressed, AWKWARD_TEXT.length)
                .method(IndexFormat.METHOD_DEFLATED);
        layer.addEntry(BANG).data(application[2], BANG_TEXT.length, BANG_TEXT.length);
        layer.addEntry(SERVICE).data(application[3], 0, 0);
        layer.addEntry("META-INF/MANIFEST.MF")
                .data(application[4], APPLICATION_MANIFEST.length, APPLICATION_MANIFEST.length);
        TestIndexBuilder.Jar dependency = builder.addJar(DEPENDENCY)
                .coordinates("com.example:dep:1.0")
                .location(base, length, header)
                .manifest(null, null, null, "Dependency", "1.0", null)
                .multiRelease();
        dependency.addEntry("META-INF/MANIFEST.MF")
                .data(base + inner[0], NESTED_MANIFEST.length, NESTED_MANIFEST.length);
        dependency.addEntry("a/B.class").data(base + inner[1], BASE_CLASS.length, BASE_CLASS.length);
        dependency.addEntry(NESTED_TEXT_NAME).data(base + inner[2], nestedCompressed, NESTED_TEXT.length)
                .method(IndexFormat.METHOD_DEFLATED);
        dependency.addEntry("META-INF/versions/21/a/B.class")
                .data(base + inner[3], VERSIONED_CLASS.length, VERSIONED_CLASS.length);
        return builder.build();
    }

    private String fileUrl() {
        StringBuilder url = new StringBuilder("file:");
        url.append(archive.getPath().replace('\\', '/'));
        return url.toString();
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

    private File newFile(String name) {
        files++;
        StringBuilder unique = new StringBuilder();
        unique.append(files).append('-').append(name);
        return temporary.resolve(unique.toString()).toFile();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
