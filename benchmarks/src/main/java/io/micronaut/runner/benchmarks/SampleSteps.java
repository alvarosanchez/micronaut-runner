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

import io.micronaut.runner.build.Compression;

/**
 * The build steps {@link SampleBuild}'s row table is made of. {@link SampleBuild} performs them on the sample; a
 * unit test fakes them to check which rows a selection builds. Every step builds the variant of the row its
 * {@link SampleBuild.VariantSpec} names.
 */
interface SampleSteps {

    /**
     * Copies the class files and dependency jars into an explicit class path.
     *
     * @param spec the {@code exploded-cp} row
     * @return the variant
     * @throws Exception if it cannot be built
     */
    Variant explodedClasspath(SampleBuild.VariantSpec spec) throws Exception;

    /**
     * Writes the application jar with a {@code Class-Path} manifest.
     *
     * @param spec the {@code thin-jar} row
     * @return the variant
     * @throws Exception if it cannot be built
     */
    Variant thinJar(SampleBuild.VariantSpec spec) throws Exception;

    /**
     * Copies the sample's default Shadow jar.
     *
     * @param spec the {@code shadow} row
     * @return the variant
     * @throws Exception if it cannot be built
     */
    Variant shadow(SampleBuild.VariantSpec spec) throws Exception;

    /**
     * Copies the sample's STORED Shadow jar.
     *
     * @param spec the {@code shadow-stored} row
     * @return the variant
     * @throws Exception if it cannot be built
     */
    Variant shadowStored(SampleBuild.VariantSpec spec) throws Exception;

    /**
     * Copies the sample's {@code optimizedJitJarAll}: Micronaut AOT's Shadow jar of the optimized application.
     *
     * @param spec the {@code shadow-maot} row
     * @return the variant
     * @throws Exception if it cannot be built, for example because the sample does not apply
     *                   {@code io.micronaut.aot}
     */
    Variant shadowMaot(SampleBuild.VariantSpec spec) throws Exception;

    /**
     * Builds the Runner jar of the Micronaut AOT-optimized application: {@code runner-stored} with the sample's
     * {@code optimizedJitJar} as its application layer.
     *
     * @param spec the {@code runner-maot} row
     * @return the variant
     * @throws Exception if it cannot be built, for example because the sample does not apply
     *                   {@code io.micronaut.aot}
     */
    Variant runnerMaot(SampleBuild.VariantSpec spec) throws Exception;

    /**
     * Builds a Runner jar, entered as the row's entry mode asks.
     *
     * @param spec        the row
     * @param compression how nested dependencies are written
     * @param options     the packaging options the row sets on top of the builder defaults
     * @return the variant
     * @throws Exception if it cannot be built
     */
    Variant runnerJar(SampleBuild.VariantSpec spec, Compression compression, SampleBuild.RunnerJarOptions options)
            throws Exception;

    /**
     * Packages the {@code runner-stored} inputs again with the run's startup class list, which is recorded once
     * per run from the list-free {@code runner-stored} jar.
     *
     * @param stored the {@code runner-stored} row, which the recording launch runs
     * @param spec   the {@code runner-stored-preload} row
     * @return the variant
     * @throws Exception if the recording launch fails, or the jar embeds no startup class
     */
    Variant preloadingRunnerJar(Variant stored, SampleBuild.VariantSpec spec) throws Exception;

    /**
     * Packages the {@code runner-stored} inputs with the run's startup class list, so each nested jar holds its
     * startup classes first, and launches the jar with the preloader off.
     *
     * @param stored the {@code runner-stored} row, which the recording launch runs
     * @param spec   the {@code runner-stored-ordered} row
     * @return the variant
     * @throws Exception if the recording launch fails, or the jar embeds no startup class
     */
    Variant orderedRunnerJar(Variant stored, SampleBuild.VariantSpec spec) throws Exception;

    /**
     * Packages the {@code runner-stored} inputs as {@code HYBRID} with the run's startup class list, and launches
     * the jar with the preloader off.
     *
     * @param stored the {@code runner-stored} row, which the recording launch runs
     * @param spec   the {@code runner-stored-hybrid} row
     * @return the variant
     * @throws Exception if the recording launch fails, the jar embeds no startup class, or it holds no deflated
     *                   nested entry
     */
    Variant hybridRunnerJar(Variant stored, SampleBuild.VariantSpec spec) throws Exception;

    /**
     * Builds the Joran control: the {@code runner-stored} inputs with {@code logback.xml} left to Joran.
     *
     * @param stored the {@code runner-stored} row, which must carry the precompiled Logback configuration
     * @param spec   the {@code runner-stored-joran} row
     * @return the variant
     * @throws Exception if it cannot be built, or if either archive is not what the comparison needs
     */
    Variant joranControl(Variant stored, SampleBuild.VariantSpec spec) throws Exception;

    /**
     * Builds the bean definition prefetch candidate: the {@code runner-stored} inputs with the prefetch packaged.
     *
     * @param stored the {@code runner-stored} row, which must not carry the prefetch
     * @param spec   the {@code runner-stored-prefetch} row
     * @return the variant
     * @throws Exception if it cannot be built, or if either archive is not what the comparison needs
     */
    Variant prefetchCandidate(Variant stored, SampleBuild.VariantSpec spec) throws Exception;

    /**
     * Trains, or reuses, and verifies a JDK AOT cache for another row.
     *
     * @param source the variant the cache is trained on
     * @param spec   the cached row
     * @return the variant
     * @throws Exception if it cannot be built
     */
    Variant aotCache(Variant source, SampleBuild.VariantSpec spec) throws Exception;

    /**
     * Extracts a Runner jar into the directory the spec names, as it is.
     *
     * @param stored the Runner variant to extract
     * @param spec   the extracted layout
     * @return the variant
     * @throws Exception if it cannot be built
     */
    Variant extracted(Variant stored, SampleBuild.VariantSpec spec) throws Exception;

    /**
     * Writes the layout the build plugins write for a Runner jar: packages the jar that runner-build's rule derives
     * from the jar's spec, which keeps every lambda, and extracts it into the directory the spec names.
     *
     * @param stored the {@code runner-stored} row, the jar that ships
     * @param spec   the extracted layout
     * @return the variant
     * @throws Exception if it cannot be built
     */
    Variant pluginLayout(Variant stored, SampleBuild.VariantSpec spec) throws Exception;
}
