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

/**
 * Steady-state resource workloads over the flat single-JAR (Shadow/Shade layout) baseline: a JDK
 * {@link java.net.URLClassLoader} over one JAR flattened from the same inputs, with merged
 * {@code META-INF/services} descriptors and first-wins duplicates, the layout of the {@code shadow} startup
 * variant. See {@link SyntheticArchive#writeShadedJar(java.nio.file.Path)}.
 */
public class ShadedRepresentativeResourceBenchmark extends RepresentativeResourceBenchmark {

    @Override
    protected RepresentativeResourceWorkload open(SyntheticArchive archive) {
        return RepresentativeResourceWorkload.url(archive, archive.shadedClassPathUrls());
    }

    /** One merged service descriptor and one surviving duplicate, whatever the dependency count. */
    @Override
    protected int expectedContributors(SyntheticArchive archive) {
        return 1;
    }
}
