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

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationManifestTest {

    @Test
    void consumedNamesAreMatchedIgnoringCaseAndWrittenCanonically() {
        assertTrue(ApplicationManifest.isConsumed("implementation-version"));
        assertTrue(ApplicationManifest.isConsumed("SEALED"));
        assertFalse(ApplicationManifest.isConsumed("Build-Time"));
        assertFalse(ApplicationManifest.isConsumed("Main-Class"));

        assertEquals(Map.of("Implementation-Version", "1.2.3"),
                ApplicationManifest.filter(Map.of("implementation-version", "1.2.3"), Map.of()));
    }

    @Test
    void dropsUnconsumedAttributesAndNonPackageSections() {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        sections.put("com/example/", Map.of("Sealed", "true", "Build-Time", "later"));
        sections.put("com/example/App.class", Map.of("Implementation-Version", "per-class"));

        SortedMap<String, String> consumed = ApplicationManifest.filter(
                Map.of("Build-Time", "now", "Specification-Vendor", "Example"), sections);

        assertEquals(new TreeMap<>(Map.of("Specification-Vendor", "Example", "com/example/Sealed", "true")),
                consumed);
    }

    @Test
    void leavesOutNullNamesAndValues() {
        Map<String, String> main = new HashMap<>();
        main.put("Implementation-Title", null);
        main.put(null, "orphan");
        main.put("Implementation-Vendor", "Example");

        assertEquals(Map.of("Implementation-Vendor", "Example"), ApplicationManifest.filter(main, Map.of()));
    }

    @Test
    void buildsTheManifestTheJarTaskConfigurationDescribes() {
        // The jar task configuration the Gradle plugin's functional tests use.
        Map<String, String> main = new LinkedHashMap<>();
        main.put("Implementation-Title", "app");
        main.put("Implementation-Version", "1.2.3");
        main.put("Specification-Vendor", "Example");
        main.put("Build-Time", "now");
        Map<String, Map<String, String>> sections = Map.of(
                "com/example/", Map.of("Implementation-Version", "pkg-9", "Sealed", "true"));

        Manifest manifest = ApplicationManifest.toManifest(ApplicationManifest.filter(main, sections));

        Attributes attributes = manifest.getMainAttributes();
        assertEquals("1.0", attributes.getValue(Attributes.Name.MANIFEST_VERSION));
        assertEquals("app", attributes.getValue("Implementation-Title"));
        assertEquals("1.2.3", attributes.getValue("Implementation-Version"));
        assertEquals("Example", attributes.getValue("Specification-Vendor"));
        assertEquals(4, attributes.size(), attributes::toString);
        assertEquals(1, manifest.getEntries().size(), () -> manifest.getEntries().toString());
        Attributes applicationPackage = manifest.getAttributes("com/example/");
        assertEquals("pkg-9", applicationPackage.getValue("Implementation-Version"));
        assertEquals("true", applicationPackage.getValue("Sealed"));
        assertEquals(2, applicationPackage.size(), applicationPackage::toString);
    }

    @Test
    void anEmptyMapGivesOnlyTheManifestVersion() {
        Manifest manifest = ApplicationManifest.toManifest(new TreeMap<>());

        assertEquals(Map.of(Attributes.Name.MANIFEST_VERSION, "1.0"), manifest.getMainAttributes());
        assertTrue(manifest.getEntries().isEmpty());
    }
}
