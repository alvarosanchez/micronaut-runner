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

import java.io.File;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The shape of the {@code file:} URL the handler builds for the archive.
 *
 * <p>Both platforms' path shapes are exercised from whichever platform runs the test, because the
 * Windows shape was wrong for a while and only a Windows machine noticed.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HandlersFileUrlTest {

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
}
