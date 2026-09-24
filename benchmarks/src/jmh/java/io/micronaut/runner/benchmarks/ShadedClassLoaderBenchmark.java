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

import java.net.URL;

/**
 * JDK class loader baseline over the flat single-JAR (Shadow/Shade layout): one JAR flattened from the same
 * inputs as the Runner archives, with merged {@code META-INF/services} descriptors and first-wins duplicates,
 * the layout of the {@code shadow} startup variant. See
 * {@link SyntheticArchive#writeShadedJar(java.nio.file.Path)}.
 *
 * <p>Only the class path differs from the thin/exploded {@link URLClassLoaderBenchmark}: the samples, the fork
 * settings, the guard against inherited Runner registration and the fixture sanity check are all inherited.
 * {@code java -jar} runs a Shadow JAR on the JDK's built-in application loader rather than
 * {@link java.net.URLClassLoader}, so this is a proxy for that loader.</p>
 */
public class ShadedClassLoaderBenchmark extends URLClassLoaderBenchmark {

    @Override
    protected URL[] classPath(SyntheticArchive archive) {
        return archive.shadedClassPathUrls();
    }
}
