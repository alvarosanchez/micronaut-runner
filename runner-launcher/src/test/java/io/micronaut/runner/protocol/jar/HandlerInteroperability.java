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

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Forked differential check for URLs retained across the process-wide handler installation.
 */
public final class HandlerInteroperability {

    private HandlerInteroperability() {
    }

    /**
     * Creates JDK URLs before Runner registration and compares them with URLs created afterwards.
     *
     * <p>An optional second argument selects another check instead:</p>
     * <ul>
     *   <li>{@code factory} installs a {@code URLStreamHandlerFactory} before registering, checks that the
     *   URLs {@link Handlers#urlFor} builds still open as {@link RunnerJarURLConnection}, and prints
     *   {@code FACTORY-REPORTED};</li>
     *   <li>{@code property} only registers and prints {@code PROPERTY-REGISTERED}, for a JVM started with
     *   another package in {@value Handlers#HANDLER_PACKAGES_PROPERTY}.</li>
     * </ul>
     * <p>The forking test counts the {@code micronaut-runner:} warnings registration printed.</p>
     *
     * @param args the runner archive path, then an optional mode
     * @throws Exception when setup fails
     */
    public static void main(String[] args) throws Exception {
        File archive = new File(args[0]).getAbsoluteFile();
        String mode = args.length > 1 ? args[1] : "";
        if ("factory".equals(mode)) {
            installedFactory(archive);
            return;
        }
        if ("property".equals(mode)) {
            try (ArchiveSource source = ArchiveSource.open(archive)) {
                Handlers.register(archive, Index.open(source), source);
            } finally {
                Handlers.unregister();
            }
            System.out.println("PROPERTY-REGISTERED");
            return;
        }
        if (!mode.isEmpty()) {
            throw new IllegalArgumentException("Unknown mode " + mode);
        }
        String file = archive.toURI().toString();
        String[] specs = {
            "jar:" + file + "!/MICRONAUT-INF/classes/app.txt",
            "jar:" + file + "!/MICRONAUT-INF/lib/dep.jar!/a/B.class",
            "jar:" + file + "!/weird/a%20b%25c%23d%3Fe%5Bf%5Dg%20%C3%A9.txt?version=1",
            "jar:" + file + "!/entry.txt#section",
            "jar:file:/C:/Program%20Files/app.jar!/a%20b.txt"
        };
        URL[] jdk = new URL[specs.length];
        URL[] secondJdk = new URL[specs.length];
        for (int i = 0; i < specs.length; i++) {
            jdk[i] = URI.create(specs[i]).toURL();
            secondJdk[i] = URI.create(specs[i]).toURL();
        }

        try (ArchiveSource source = ArchiveSource.open(archive)) {
            Handlers.register(archive, Index.open(source), source);
            for (int i = 0; i < specs.length; i++) {
                URL registered = URI.create(specs[i]).toURL();
                URL explicit = URL.of(URI.create(specs[i]), new Handler());
                assertInteroperable(jdk[i], secondJdk[i], registered, explicit);
            }

            URL entry = URI.create(specs[0]).toURL();
            check(entry.openConnection() instanceof RunnerJarURLConnection,
                    "URL created after registration did not use Runner's handler");

            URL firstFragment = URI.create("jar:" + file + "!/entry.txt#first").toURL();
            URL secondFragment = URI.create("jar:" + file + "!/entry.txt#second").toURL();
            check(!firstFragment.equals(secondFragment) && !secondFragment.equals(firstFragment),
                    "different fragments compared equal");

            URL firstEntry = URI.create("jar:" + file + "!/first.txt").toURL();
            URL secondEntry = URI.create("jar:" + file + "!/second.txt").toURL();
            check(!firstEntry.sameFile(secondEntry) && !secondEntry.sameFile(firstEntry),
                    "different entries compared as the same file");
        } finally {
            Handlers.unregister();
        }
        System.out.println("INTEROPERABLE");
    }

    /**
     * Registers after an application has claimed the handler factory, which registration can only report.
     *
     * @param archive the runner archive
     * @throws Exception when setup fails
     */
    private static void installedFactory(File archive) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> null);
        try (ArchiveSource source = ArchiveSource.open(archive)) {
            Handlers.register(archive, Index.open(source), source);
            URL url = Handlers.urlFor(IndexFormat.APPLICATION_JAR_ID, "app.txt");
            check(url.openConnection() instanceof RunnerJarURLConnection,
                    "a URL built by Handlers did not open with Runner's connection");
        } finally {
            Handlers.unregister();
        }
        System.out.println("FACTORY-REPORTED");
    }

    private static void assertInteroperable(URL jdk, URL secondJdk, URL registered, URL explicit) {
        check(jdk.equals(registered), "JDK-to-Runner equality failed for " + jdk);
        check(registered.equals(jdk), "Runner-to-JDK equality failed for " + jdk);
        check(registered.equals(explicit) && explicit.equals(secondJdk) && jdk.equals(explicit),
                "equality was not transitive for " + jdk);
        check(jdk.sameFile(registered) && registered.sameFile(jdk),
                "mixed-handler sameFile failed for " + jdk);
        check(jdk.hashCode() == registered.hashCode()
                        && registered.hashCode() == explicit.hashCode()
                        && explicit.hashCode() == secondJdk.hashCode(),
                "equal URLs had different hashes for " + jdk + ": JDK=" + jdk.hashCode()
                        + ", registered=" + registered.hashCode() + ", explicit=" + explicit.hashCode());

        assertCollections(jdk, registered);
        assertCollections(registered, jdk);
    }

    private static void assertCollections(URL inserted, URL lookup) {
        Set<URL> set = new HashSet<>();
        set.add(inserted);
        check(set.contains(lookup), "HashSet lookup failed: inserted=" + inserted + ", lookup=" + lookup);
        Map<URL, String> map = new HashMap<>();
        map.put(inserted, "found");
        check("found".equals(map.get(lookup)),
                "HashMap lookup failed: inserted=" + inserted + ", lookup=" + lookup);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
