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

import java.net.URL;
import java.util.jar.JarFile;

/**
 * Test-only access, for the tests of {@code io.micronaut.runner.protocol.jar}, to the launcher members that are
 * package-private because no main code outside {@code io.micronaut.runner} calls them. It is test code: it is
 * never packaged, and it keeps those members off the launcher's public API.
 */
public final class LauncherTestAccess {

    private LauncherTestAccess() {
    }

    /**
     * See {@link Handlers#urlFor(int, String)}.
     *
     * @param jarId       the jar, {@code 0} being the application layer
     * @param logicalName the entry name relative to that jar
     * @return the URL, or {@code null}
     */
    public static URL urlFor(int jarId, String logicalName) {
        return Handlers.urlFor(jarId, logicalName);
    }

    /**
     * See {@link Handlers#outerUrlFor(String)}.
     *
     * @param outerEntryName the entry name as the outer archive stores it
     * @return the URL, or {@code null}
     */
    public static URL outerUrlFor(String outerEntryName) {
        return Handlers.outerUrlFor(outerEntryName);
    }

    /**
     * See {@link Handlers#codeSourceUrlFor(int)}.
     *
     * @param jarId the jar, {@code 0} being the application layer
     * @return the URL, or {@code null}
     */
    public static URL codeSourceUrlFor(int jarId) {
        return Handlers.codeSourceUrlFor(jarId);
    }

    /**
     * Whether a jar is a {@link NestedJarFile} view, which {@link Handlers#nestedJarFile(int)} and a connection's
     * {@code getJarFile()} return typed as {@link JarFile}.
     *
     * @param jar the jar
     * @return {@code true} for a nested jar view
     */
    public static boolean isNestedJarFile(JarFile jar) {
        return jar instanceof NestedJarFile;
    }

    /**
     * See {@link NestedJarFile#jarId()}.
     *
     * @param jar the nested jar view, typed as {@link JarFile}
     * @return the jar it is a view of
     * @throws ClassCastException if {@code jar} is not a nested jar view
     */
    public static int jarId(JarFile jar) {
        return ((NestedJarFile) jar).jarId();
    }

    /**
     * See {@link NestedJarEntry#dosTimeToMillis(long)}.
     *
     * @param dosTime the packed MS-DOS date and time
     * @return the instant in epoch milliseconds, or {@code -1} when the word records no time
     */
    public static long dosTimeToMillis(long dosTime) {
        return NestedJarEntry.dosTimeToMillis(dosTime);
    }

    /**
     * See {@link Index#jarLocalHeaderOffset(int)}.
     *
     * @param index the index
     * @param jarId the nested jar
     * @return the offset of its outer local file header
     */
    public static long jarLocalHeaderOffset(Index index, int jarId) {
        return index.jarLocalHeaderOffset(jarId);
    }
}
