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
 * unit test fakes them to check which rows a selection builds.
 */
interface SampleSteps {

    /**
     * Copies the class files and dependency jars into an explicit class path.
     *
     * @return the {@code exploded-cp} variant
     * @throws Exception if it cannot be built
     */
    Variant explodedClasspath() throws Exception;

    /**
     * Writes the application jar with a {@code Class-Path} manifest.
     *
     * @return the {@code thin-jar} variant
     * @throws Exception if it cannot be built
     */
    Variant thinJar() throws Exception;

    /**
     * Copies the sample's default Shadow jar.
     *
     * @return the {@code shadow} variant
     * @throws Exception if it cannot be built
     */
    Variant shadow() throws Exception;

    /**
     * Copies the sample's STORED Shadow jar.
     *
     * @return the {@code shadow-stored} variant
     * @throws Exception if it cannot be built
     */
    Variant shadowStored() throws Exception;

    /**
     * Builds a Runner jar.
     *
     * @param name               the row
     * @param compression        how nested dependencies are written
     * @param requestedEntryMode how the application is entered
     * @return the variant
     * @throws Exception if it cannot be built
     */
    Variant runnerJar(String name, Compression compression, EntryMode requestedEntryMode) throws Exception;

    /**
     * Trains, or reuses, and verifies a JDK AOT cache for another row.
     *
     * @param source the row the cache is trained on
     * @param name   the cached row
     * @return the variant
     * @throws Exception if it cannot be built
     */
    Variant aotCache(Variant source, String name) throws Exception;

    /**
     * Extracts a Runner jar.
     *
     * @param stored the Runner row to extract
     * @return the {@code runner-extracted} variant
     * @throws Exception if it cannot be built
     */
    Variant extracted(Variant stored) throws Exception;
}
