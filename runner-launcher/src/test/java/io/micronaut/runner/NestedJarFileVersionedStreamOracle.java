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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Forked differential oracle for {@link NestedJarFile#versionedStream()}.
 */
public final class NestedJarFileVersionedStreamOracle {

    private NestedJarFileVersionedStreamOracle() {
    }

    /**
     * Compares nested and JDK views in a fresh VM, where JarFile reads its global MR properties.
     *
     * @param arguments outer runner archive and original dependency
     * @throws Exception if either view cannot be read or differs
     */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Expected outer archive and original dependency");
        }
        File archive = new File(arguments[0]);
        File dependency = new File(arguments[1]);
        try (ArchiveSource source = ArchiveSource.open(archive);
             JarFile oracle = new JarFile(dependency, false, JarFile.OPEN_READ, JarFile.runtimeVersion())) {
            Index index = Index.open(source);
            NestedJarFile nested = new NestedJarFile(archive, index, source, 1);
            try {
                Stream<JarEntry> effective = nested.versionedStream();
                nested.close();
                assertEqual("versioned stream", describe(oracle, oracle.versionedStream()),
                        describe(nested, effective));
                assertEqual("physical stream", describe(oracle, oracle.stream()),
                        describe(nested, nested.stream()));
                if (oracle.size() != nested.size()) {
                    throw new AssertionError("physical size: expected " + oracle.size() + " but got "
                            + nested.size());
                }
            } finally {
                nested.closeNested();
            }
        }
        System.out.println("OK");
    }

    private static List<String> describe(JarFile jarFile, Stream<JarEntry> stream) {
        try (stream) {
            return stream.map(entry -> describe(jarFile, entry)).toList();
        }
    }

    private static String describe(JarFile jarFile, JarEntry entry) {
        try (InputStream in = jarFile.getInputStream(entry)) {
            return entry.getName() + "=>" + entry.getRealName() + "="
                    + new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void assertEqual(String label, List<String> expected, List<String> actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }
}
