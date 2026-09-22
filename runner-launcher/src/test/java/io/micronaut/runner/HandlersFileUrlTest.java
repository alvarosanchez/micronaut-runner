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

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of the {@code file:} URL the handler builds for the archive.
 *
 * <p>Both platforms' path shapes are exercised from whichever platform runs the test, because the
 * Windows shape was wrong for a while and only a Windows machine noticed.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HandlersFileUrlTest {

    @TempDir
    Path temporary;

    @Test
    void aPosixPathIsUsedAsIs() {
        assertEquals("file:/home/user/app.jar", Handlers.fileUrl("/home/user/app.jar", '/'));
    }

    @Test
    void aWindowsPathGainsTheSeparatorThatMakesTheUriHierarchical() {
        // Without the added '/', this reads file:C:/dir/app.jar, which URI treats as opaque.
        assertEquals("file:/C:/dir/app.jar", Handlers.fileUrl("C:\\dir\\app.jar", '\\'));
    }

    @Test
    void aWindowsUncPathKeepsItsLeadingSeparators() {
        assertEquals("file://server/share/app.jar", Handlers.fileUrl("\\\\server\\share\\app.jar", '\\'));
    }

    @Test
    void bothShapesProduceAHierarchicalUriThatCanBecomeAFile() {
        // This is what micronaut-core does with the merged service directory URL.
        for (String url : new String[] {
                Handlers.fileUrl("/home/user/app.jar", '/'),
                Handlers.fileUrl("C:\\dir\\app.jar", '\\')}) {
            URI uri = URI.create(url);
            assertNotNull(uri.getPath(), url + " is opaque, so new File(URI) would reject it");
            assertNotNull(new File(uri), url);
        }
    }

    @Test
    void charactersThatWouldBreakAUrlAreEncoded() {
        assertEquals("file:/a%20we%C3%AFrd%20dir/app.jar", Handlers.fileUrl("/a weïrd dir/app.jar", '/'));
        assertEquals("file:/C:/a%20dir/app.jar", Handlers.fileUrl("C:\\a dir\\app.jar", '\\'));
    }

    @Test
    void aWindowsDrivePathKeepsNormalizingAfterEveryUnicodeSegment() {
        assertEquals(
                "file:/C:/Users/%C3%81lvaro/apps%20%F0%9F%98%80/na%C3%AFve%20100%25%23%3F/app.jar",
                Handlers.fileUrl("C:\\Users\\Álvaro\\apps 😀\\naïve 100%#?\\app.jar", '\\'));
    }

    @Test
    void aWindowsUncPathKeepsNormalizingAfterUnicode() {
        assertEquals(
                "file://server/share/unicode-%C3%A9/apps%20%F0%9F%98%80/app.jar",
                Handlers.fileUrl("\\\\server\\share\\unicode-é\\apps 😀\\app.jar", '\\'));
    }

    @Test
    void aPosixBackslashRemainsDataAfterUnicode() {
        assertEquals(
                "file:/srv/unicode-%C3%A9/literal%5Cbackslash/100%25%23%3F/app.jar",
                Handlers.fileUrl("/srv/unicode-é/literal\\backslash/100%#?/app.jar", '/'));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void previousEscapedSeparatorsRemainAcceptedByWindowsJdkConsumers() throws Exception {
        Path archive = temporary.resolve("unicode-é").resolve("apps").resolve("app.jar");
        Files.createDirectories(archive.getParent());
        byte[] content = "legacy-path-consumer".getBytes(StandardCharsets.UTF_8);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new JarEntry("probe.txt"));
            out.write(content);
            out.closeEntry();
        }

        String canonical = Handlers.fileUrl(archive.toAbsolutePath().toString(), File.separatorChar);
        String previous = canonical.replace("/apps/app.jar", "%5Capps%5Capp.jar");
        assertNotEquals(canonical, previous);
        assertTrue(previous.endsWith("unicode-%C3%A9%5Capps%5Capp.jar"), previous);

        URI fileUri = URI.create(previous);
        assertEquals(archive.toRealPath(), new File(fileUri).toPath().toRealPath());
        try (InputStream in = fileUri.toURL().openStream()) {
            assertEquals('P', in.read(), "file: URL did not open the ZIP bytes");
        }
        try (JarFile jar = new JarFile(new File(fileUri))) {
            assertEquals("legacy-path-consumer", new String(
                    jar.getInputStream(jar.getJarEntry("probe.txt")).readAllBytes(), StandardCharsets.UTF_8));
        }
        URLConnection entryConnection = URI.create("jar:" + previous + "!/probe.txt").toURL().openConnection();
        entryConnection.setUseCaches(false);
        try (InputStream in = entryConnection.getInputStream()) {
            assertEquals("legacy-path-consumer", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
