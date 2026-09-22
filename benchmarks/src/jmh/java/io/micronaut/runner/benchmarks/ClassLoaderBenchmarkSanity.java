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
package io.micronaut.runner.benchmarks;

import io.micronaut.runner.ArchiveSource;
import io.micronaut.runner.Index;
import io.micronaut.runner.RunnerClassLoader;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** Fail-fast checks that keep benchmark fixture or parent-loader mistakes out of measurements. */
final class ClassLoaderBenchmarkSanity {

    private ClassLoaderBenchmarkSanity() {
    }

    static void verifyUrlClassPath(URL[] classPath, String className) throws IOException, ClassNotFoundException {
        try (URLClassLoader loader = new URLClassLoader("benchmark-sanity", classPath,
                ClassLoader.getPlatformClassLoader())) {
            verifyLookup(loader, className, null);
        }
    }

    static void verifyRunnerArchive(Path archive, String className) throws IOException, ClassNotFoundException {
        ArchiveSource source = ArchiveSource.open(archive.toFile());
        try {
            Index index = Index.open(source);
            RunnerClassLoader loader = new RunnerClassLoader(index, source, RunnerClassLoader.defaultParent());
            verifyLookup(loader, className, archive);
        } finally {
            source.close();
        }
    }

    private static void verifyLookup(ClassLoader loader, String className, Path expectedArchive)
            throws ClassNotFoundException {
        Class<?> loaded = loader.loadClass(className);
        if (loaded.getClassLoader() != loader) {
            throw new IllegalStateException(className + " fell through to an ambient parent loader");
        }
        String resourceName = className.replace('.', '/') + ".class";
        if (loader.getResource(resourceName) == null || loader.getResource(resourceName + ".absent") != null) {
            throw new IllegalStateException("Fixture resource hit/miss mismatch for " + resourceName);
        }
        if (expectedArchive != null) {
            URL location = loaded.getProtectionDomain().getCodeSource().getLocation();
            try {
                String expected = "jar:" + expectedArchive.toAbsolutePath().toUri().toURL().toExternalForm() + "!/";
                if (location == null || !location.toExternalForm().startsWith(expected)) {
                    throw new IllegalStateException("Code source " + location + " does not use " + expectedArchive);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Generated archive path is not a URL", e);
            }
        }
    }
}
