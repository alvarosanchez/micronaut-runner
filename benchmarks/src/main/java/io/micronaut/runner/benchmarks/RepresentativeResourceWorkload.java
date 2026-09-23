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
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;

/**
 * Reusable steady-state resource workload shared by the three class-loader JMH forks.
 *
 * <p>Fixture construction, archive registration and loader construction happen outside measurement. Each
 * operation therefore measures repeated lookup/enumeration/stream behavior, while the existing loader
 * benchmarks continue to measure fresh-loader and class-definition costs.</p>
 */
final class RepresentativeResourceWorkload implements AutoCloseable {

    private static final int LOOKUPS = 16;

    private final SyntheticArchive archive;
    private final ClassLoader loader;
    private final AutoCloseable loaderClose;
    private final ArchiveSource source;
    private final RunnerRegistration registration;
    private final String[] localNames;
    private final String[] spreadNames;

    private RepresentativeResourceWorkload(SyntheticArchive archive,
                                           ClassLoader loader,
                                           AutoCloseable loaderClose,
                                           ArchiveSource source,
                                           RunnerRegistration registration) {
        this.archive = archive;
        this.loader = loader;
        this.loaderClose = loaderClose;
        this.source = source;
        this.registration = registration;
        this.localNames = archive.localSample(LOOKUPS);
        this.spreadNames = archive.spreadSample(LOOKUPS);
    }

    /** Opens the JDK URL class-path baseline without registering runner global state. */
    static RepresentativeResourceWorkload url(SyntheticArchive archive) {
        URLClassLoader loader = new URLClassLoader("representative-resources", archive.classPathUrls(),
                ClassLoader.getPlatformClassLoader());
        return new RepresentativeResourceWorkload(archive, loader, loader, null, null);
    }

    /** Opens a persistent loader over one matching runner archive. */
    static RepresentativeResourceWorkload runner(SyntheticArchive archive, boolean preserve) throws IOException {
        Path jar = preserve ? archive.preserveRunnerJar() : archive.storedRunnerJar();
        RunnerRegistration registration = RunnerRegistration.open(jar);
        ArchiveSource source = null;
        try {
            source = ArchiveSource.open(jar.toFile());
            Index index = Index.open(source);
            RunnerClassLoader loader = new RunnerClassLoader(index, source, RunnerClassLoader.defaultParent());
            return new RepresentativeResourceWorkload(archive, loader, null, source, registration);
        } catch (Throwable failure) {
            if (source != null) {
                source.close();
            }
            registration.close();
            throw failure;
        }
    }

    /** Looks up 16 adjacent resources from one dependency. */
    int localLookupCount() {
        return lookup(localNames);
    }

    /** Looks up 16 resources spread across the ordered class path. */
    int spreadLookupCount() {
        return lookup(spreadNames);
    }

    /** Looks up 16 realistic late-failing misses. */
    int missingLookupCount() {
        int found = 0;
        for (String name : spreadNames) {
            if (loader.getResource(resourceName(name) + ".absent") != null) {
                found++;
            }
        }
        return found;
    }

    /** Enumerates the service descriptor contributed by every dependency. */
    int serviceDiscoveryCount() throws IOException {
        return Collections.list(loader.getResources(SyntheticArchive.SERVICE_RESOURCE)).size();
    }

    /** Enumerates the duplicate same-name resource contributed by every dependency. */
    int duplicateResourceCount() throws IOException {
        return Collections.list(loader.getResources(SyntheticArchive.DUPLICATE_RESOURCE)).size();
    }

    /** Streams the complete deterministic payload and returns its byte count. */
    int streamBytes() throws IOException {
        try (InputStream stream = requiredStream(SyntheticArchive.STREAM_RESOURCE)) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
            }
            return total;
        }
    }

    /** Resolves and reads the effective multi-release resource. */
    String multiReleaseValue() throws IOException {
        try (InputStream stream = requiredStream(SyntheticArchive.VERSIONED_RESOURCE)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int lookup(String[] names) {
        int found = 0;
        for (String name : names) {
            URL resource = loader.getResource(resourceName(name));
            if (resource != null) {
                found++;
            }
        }
        return found;
    }

    private InputStream requiredStream(String name) throws IOException {
        InputStream stream = loader.getResourceAsStream(name);
        if (stream == null) {
            throw new IOException("Representative fixture has no " + name + " for " + archive.shape().name());
        }
        return stream;
    }

    private static String resourceName(String binaryName) {
        return binaryName.replace('.', '/') + ".class";
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        try {
            if (loaderClose != null) {
                loaderClose.close();
            }
        } catch (Exception e) {
            failure = e;
        }
        try {
            if (source != null) {
                source.close();
            }
        } catch (Exception e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        } finally {
            if (registration != null) {
                registration.close();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
