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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The check {@link Handlers} runs, off the default start path, to tell whether a URL re-parsed from its string
 * form reached the launcher's own connection. The connection class is package-private, so the check compares its
 * package and its class loader with the handler's instead of naming it; these tests hold it to what the
 * {@code instanceof} it replaces answered.
 */
class HandlersConnectionCheckTest {

    /** The launcher's connection class, which this package cannot name. */
    private static final String CONNECTION = "io.micronaut.runner.protocol.jar.RunnerJarURLConnection";

    private static final byte[] MANIFEST = bytes("Manifest-Version: 1.0\r\n"
            + "Main-Class: io.micronaut.runner.Launcher\r\n\r\n");
    private static final byte[] TEXT = bytes("application resource");

    @TempDir
    Path temporary;

    private ArchiveSource source;

    @AfterEach
    void unregister() {
        Handlers.unregister();
        if (source != null) {
            source.close();
            source = null;
        }
    }

    @Test
    void acceptsTheConnectionOfARunnerUrlReparsedFromItsStringForm() throws IOException {
        File archive = writeArchive("app.jar");
        source = ArchiveSource.open(archive);
        Handlers.register(archive, Index.open(source), source);

        URL url = Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "app.txt");
        URLConnection connection = URI.create(url.toString()).toURL().openConnection();

        assertEquals(CONNECTION, connection.getClass().getName(),
                "a URL rebuilt from its string form must come back to the launcher's handler");
        assertTrue(Handlers.servedByHandler(connection));
    }

    @Test
    void rejectsTheConnectionTheJdkOpensForAnotherFile() throws IOException {
        File archive = writeArchive("app.jar");
        source = ArchiveSource.open(archive);
        Handlers.register(archive, Index.open(source), source);
        TestArchiveBuilder plain = new TestArchiveBuilder();
        plain.stored("hello.txt", TEXT);
        File other = plain.writeTo(temporary.resolve("other.jar").toFile());

        URLConnection connection = URI.create("jar:" + other.toURI() + "!/hello.txt").toURL().openConnection();
        connection.setUseCaches(false);

        assertInstanceOf(JarURLConnection.class, connection);
        assertNotEquals(CONNECTION, connection.getClass().getName(), "the JDK must open another file's URL");
        assertFalse(Handlers.servedByHandler(connection));
    }

    /**
     * A copy of the launcher defined by another class loader serves the registered archive with a connection
     * class of the same name in the same package. That is not this launcher's connection, which is the half of
     * the check a comparison of names would miss.
     */
    @Test
    void rejectsTheSameConnectionClassDefinedByAnotherClassLoader() throws Exception {
        File archive = writeArchive("app.jar");
        URL classes = Launcher.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader foreign = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader())) {
            Class<?> sourceType = foreign.loadClass(ArchiveSource.class.getName());
            Class<?> indexType = foreign.loadClass(Index.class.getName());
            Class<?> handlersType = foreign.loadClass(Handlers.class.getName());
            assertNotSame(Handlers.class, handlersType);
            Object foreignSource = sourceType.getMethod("open", File.class).invoke(null, archive);
            try {
                Object foreignIndex = indexType.getMethod("open", sourceType).invoke(null, foreignSource);
                // The other copy is not on the system class loader, so its registration checks the string-parse
                // path and may print a warning; that is the case the check exists for.
                handlersType.getMethod("register", File.class, indexType, sourceType)
                        .invoke(null, archive, foreignIndex, foreignSource);
                try {
                    Method urlFor = handlersType.getDeclaredMethod("urlFor", int.class, String.class);
                    urlFor.setAccessible(true);
                    URL url = (URL) urlFor.invoke(null, IndexFormat.APPLICATION_JAR_ID, "app.txt");
                    URLConnection connection = url.openConnection();

                    assertEquals(CONNECTION, connection.getClass().getName());
                    assertSame(foreign, connection.getClass().getClassLoader());
                    assertFalse(Handlers.servedByHandler(connection));
                    Method servedByHandler = handlersType.getDeclaredMethod("servedByHandler", URLConnection.class);
                    servedByHandler.setAccessible(true);
                    assertTrue((Boolean) servedByHandler.invoke(null, connection),
                            "the other copy recognises its own connection");
                } finally {
                    handlersType.getMethod("unregister").invoke(null);
                }
            } finally {
                sourceType.getMethod("close").invoke(foreignSource);
            }
        }
    }

    /**
     * Writes an archive with one application entry, {@code app.txt}.
     *
     * @param name the file name
     * @return the archive
     * @throws IOException if it cannot be written
     */
    private File writeArchive(String name) throws IOException {
        TestArchiveBuilder outer = new TestArchiveBuilder();
        outer.stored("META-INF/MANIFEST.MF", MANIFEST);
        byte[] draft = index(0);
        outer.reserve(IndexFormat.INDEX_ENTRY_NAME, draft.length);
        long offset = outer.stored(IndexFormat.CLASSES_PREFIX + "app.txt", TEXT);
        byte[] real = index(offset);
        assertEquals(draft.length, real.length, "the index size must not depend on the offsets");
        outer.replace(IndexFormat.INDEX_ENTRY_NAME, real);
        return outer.writeTo(temporary.resolve(name).toFile());
    }

    private static byte[] index(long offset) {
        CRC32 crc = new CRC32();
        crc.update(TEXT);
        TestIndexBuilder builder = new TestIndexBuilder().startClass("com.example.Application");
        builder.addJar(IndexFormat.CLASSES_PREFIX).addEntry("app.txt").data(offset, TEXT.length, TEXT.length)
                .crc32(crc.getValue());
        return builder.build();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
