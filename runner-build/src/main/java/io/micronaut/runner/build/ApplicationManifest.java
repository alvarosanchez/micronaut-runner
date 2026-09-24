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

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * The part of an application's manifest the packaging library reads, for a build tool that describes that
 * manifest in its own model rather than as a file.
 *
 * <p>The library reads seven attributes: {@code Specification-Title}, {@code Specification-Version},
 * {@code Specification-Vendor}, {@code Implementation-Title}, {@code Implementation-Version},
 * {@code Implementation-Vendor} and {@code Sealed}, in the main section and in package sections, whose names
 * end in {@code /}. They reach the runner jar's manifest and the index, where the launcher defines each
 * {@link Package} with them. Every other attribute and section is ignored.</p>
 *
 * <p>A plugin collects its manifest configuration as plain strings, keeps what {@link #filter(Map, Map)}
 * keeps, which is small and stable enough to be a task input, and passes {@link #toManifest(SortedMap)} of
 * it to {@link RunnerJarSpec.Builder#applicationManifest(Manifest)}.</p>
 *
 * @since 1.0
 */
public final class ApplicationManifest {

    /** The attributes the library reads, keyed by lower-case name, with their canonical spelling. */
    private static final Map<String, String> CONSUMED = consumed(
            "Specification-Title", "Specification-Version", "Specification-Vendor",
            "Implementation-Title", "Implementation-Version", "Implementation-Vendor",
            "Sealed");

    private ApplicationManifest() {
    }

    /**
     * Whether the packaging library reads an attribute: one of {@code Specification-Title},
     * {@code Specification-Version}, {@code Specification-Vendor}, {@code Implementation-Title},
     * {@code Implementation-Version}, {@code Implementation-Vendor} and {@code Sealed}. Names are compared
     * without regard to case, as {@link Attributes.Name} compares them.
     *
     * @param attributeName an attribute name
     * @return whether the library reads it
     * @throws NullPointerException if {@code attributeName} is {@code null}
     */
    public static boolean isConsumed(String attributeName) {
        return canonicalName(attributeName) != null;
    }

    /**
     * Keeps what the packaging library reads of a manifest.
     *
     * <p>The result is keyed by {@code Name} for a main attribute and by {@code <section>Name} for an
     * attribute of a package section, such as {@code com/example/Implementation-Version}. Only
     * {@linkplain #isConsumed(String) consumed} attributes are kept, written with their canonical spelling,
     * and only sections whose names end in {@code /}. An attribute whose name or value is {@code null} is
     * left out.</p>
     *
     * @param main     the main attributes, by name
     * @param sections the named sections' attributes, by section name
     * @return the consumed attributes, sorted by key
     * @throws NullPointerException if {@code main} or {@code sections} is {@code null}
     */
    public static SortedMap<String, String> filter(Map<String, String> main,
            Map<String, Map<String, String>> sections) {
        Objects.requireNonNull(main, "main");
        Objects.requireNonNull(sections, "sections");
        SortedMap<String, String> consumed = new TreeMap<>();
        collect(main, "", consumed);
        for (Map.Entry<String, Map<String, String>> section : sections.entrySet()) {
            String name = section.getKey();
            if (name != null && name.endsWith("/") && section.getValue() != null) {
                collect(section.getValue(), name, consumed);
            }
        }
        return consumed;
    }

    /**
     * Builds the application manifest from {@link #filter(Map, Map)}'s result: {@code Manifest-Version: 1.0},
     * then the main attributes, then the package sections. A key without a {@code /} is a main attribute; any
     * other key is split at its last {@code /} into a section name, which keeps the {@code /}, and an
     * attribute name.
     *
     * @param consumed the attributes, as {@link #filter(Map, Map)} returns them
     * @return a new manifest
     * @throws NullPointerException if {@code consumed} is {@code null}
     */
    public static Manifest toManifest(SortedMap<String, String> consumed) {
        Objects.requireNonNull(consumed, "consumed");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        for (Map.Entry<String, String> attribute : consumed.entrySet()) {
            String key = attribute.getKey();
            int slash = key.lastIndexOf('/');
            Attributes section = slash < 0
                    ? manifest.getMainAttributes()
                    : manifest.getEntries().computeIfAbsent(key.substring(0, slash + 1), _ -> new Attributes());
            section.putValue(key.substring(slash + 1), attribute.getValue());
        }
        return manifest;
    }

    private static void collect(Map<String, String> attributes, String prefix, SortedMap<String, String> result) {
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            String name = attribute.getKey() == null ? null : canonicalName(attribute.getKey());
            if (name != null && attribute.getValue() != null) {
                result.put(prefix + name, attribute.getValue());
            }
        }
    }

    private static String canonicalName(String attributeName) {
        return CONSUMED.get(attributeName.toLowerCase(Locale.ROOT));
    }

    private static Map<String, String> consumed(String... names) {
        Map<String, String> byLowerCaseName = new TreeMap<>();
        for (String name : names) {
            byLowerCaseName.put(name.toLowerCase(Locale.ROOT), name);
        }
        return Map.copyOf(byLowerCaseName);
    }
}
