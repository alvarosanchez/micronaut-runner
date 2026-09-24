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
import io.micronaut.runner.Handlers;
import io.micronaut.runner.Index;
import io.micronaut.runner.RunnerClassLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;

final class ClassLoaderIsolationProbe {

    private ClassLoaderIsolationProbe() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        requireCleanProcess();
        SyntheticArchive archive = SyntheticArchive.forWorkload("no-manifest");
        switch (mode) {
            case "baseline" -> baseline(archive);
            case "stored-then-preserve" -> {
                runner(archive, archive.storedRunnerJar());
                runner(archive, archive.preserveRunnerJar());
            }
            default -> throw new IllegalArgumentException("Unknown mode " + mode);
        }
        System.out.println("OK " + mode);
    }

    private static void baseline(SyntheticArchive archive) throws Exception {
        URL[] classPath = archive.classPathUrls();
        String name = archive.spreadSample(1)[0];
        try (URLClassLoader loader = new URLClassLoader("baseline-probe", classPath,
                ClassLoader.getPlatformClassLoader())) {
            verifyLookup(loader, archive, name, null);
        }
        requireCleanProcess();
    }

    private static void runner(SyntheticArchive archive, Path runnerJar) throws Exception {
        try (RunnerRegistration registration = RunnerRegistration.open(runnerJar);
             ArchiveSource source = ArchiveSource.open(runnerJar.toFile())) {
            if (!runnerJar.toAbsolutePath().toFile().equals(Handlers.archive())) {
                throw new AssertionError("Registered " + Handlers.archive() + " instead of " + runnerJar);
            }
            Index index = Index.open(source);
            RunnerClassLoader loader = new RunnerClassLoader(index, source, RunnerClassLoader.defaultParent());
            verifyLookup(loader, archive, archive.spreadSample(1)[0], runnerJar);
        }
        if (Handlers.registered()) {
            throw new AssertionError("Runner registration leaked past trial cleanup");
        }
    }

    private static void verifyLookup(ClassLoader loader,
                                     SyntheticArchive archive,
                                     String name,
                                     Path expectedRunnerJar) throws Exception {
        Class<?> loaded = loader.loadClass(name);
        if (loaded.getClassLoader() != loader) {
            throw new AssertionError(name + " fell through to " + loaded.getClassLoader());
        }
        if (expectedRunnerJar != null) {
            URL location = loaded.getProtectionDomain().getCodeSource().getLocation();
            String expected = expectedRunnerJar.toAbsolutePath().toUri().toURL().toExternalForm();
            if (location == null || !location.toExternalForm().startsWith("jar:" + expected + "!/")) {
                throw new AssertionError("Wrong code source " + location + "; expected outer archive " + expected);
            }
        }

        String resourceName = name.replace('.', '/') + ".class";
        byte[] actual = read(loader.getResource(resourceName));
        if (actual.length == 0 || loader.getResource(resourceName + ".absent") != null) {
            throw new AssertionError("Resource hit/miss mismatch for " + resourceName);
        }
        try (URLClassLoader expectedLoader = new URLClassLoader("expected-bytes", archive.classPathUrls(),
                ClassLoader.getPlatformClassLoader())) {
            byte[] expected = read(expectedLoader.getResource(resourceName));
            if (!Arrays.equals(expected, actual)) {
                throw new AssertionError("Resource bytes differ for " + resourceName);
            }
        }
    }

    private static byte[] read(URL resource) throws IOException {
        if (resource == null) {
            throw new AssertionError("Expected resource is absent");
        }
        try (InputStream input = resource.openStream()) {
            return input.readAllBytes();
        }
    }

    private static void requireCleanProcess() {
        if (Handlers.registered()) {
            throw new AssertionError("Runner archive already registered: " + Handlers.archive());
        }
        String packages = System.getProperty(Handlers.HANDLER_PACKAGES_PROPERTY, "");
        if (Arrays.asList(packages.split("\\|", -1)).contains(Handlers.PROTOCOL_PACKAGE)) {
            throw new AssertionError("Inherited Runner handler configuration: " + packages);
        }
    }
}
