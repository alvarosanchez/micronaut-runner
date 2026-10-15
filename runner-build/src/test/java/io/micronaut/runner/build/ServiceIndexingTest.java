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
package io.micronaut.runner.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How {@link ServiceIndexing} names a nested jar in a message of the module. The builder's tests cover the messages a
 * real build gets; these cover the ones a build cannot be made to produce, such as a path in an exception's text.
 */
class ServiceIndexingTest {

    private static final Path WORK = Path.of("/build/.micronaut-runner-1");
    private static final Path CORE = Path.of("/repository/micronaut-core-5.3.0.jar");
    private static final Path TRICKY = Path.of("/repository/tricky-lib-1.jar");

    @Test
    void aCopysPathBecomesTheDependencysPathAndItsFileNameTheDependency() {
        Map<String, String> names = ServiceIndexing.names(
                List.of(WORK.resolve("lib-0.jar"), WORK.resolve("lib-1.jar")), List.of(TRICKY, CORE));

        assertEquals("because the class path entry " + CORE + " does not exist (java.util.zip.ZipException: "
                        + TRICKY + ": bad header); the dependency " + TRICKY + " holds x.",
                ServiceIndexing.named("because the class path entry " + WORK.resolve("lib-1.jar") + " does not exist"
                        + " (java.util.zip.ZipException: " + WORK.resolve("lib-0.jar") + ": bad header); lib-0.jar"
                        + " holds x.", names));
    }

    @Test
    void aNameInsideAnotherNameOrPathStaysAsItIs() {
        Map<String, String> names = ServiceIndexing.names(List.of(WORK.resolve("lib-1.jar")), List.of(CORE));
        String message = "tricky-lib-1.jar, " + Path.of("/elsewhere/lib-1.jar") + ", lib-1.jar.tmp and lib-1.jar2";

        assertEquals(message, ServiceIndexing.named(message, names));
        assertEquals("the dependency " + CORE + ".", ServiceIndexing.named("lib-1.jar.", names));
    }

    @Test
    void preserveNamesTheDependencyByItsFileNameUnlessTwoShareIt() {
        Path other = Path.of("/elsewhere/micronaut-core-5.3.0.jar");
        Path duplicate = WORK.resolve("service-index-duplicate-3.jar");
        Map<String, String> names = ServiceIndexing.names(List.of(CORE, TRICKY, other, duplicate),
                List.of(CORE, TRICKY, other, TRICKY));

        assertEquals("the dependency " + TRICKY + " and micronaut-core-5.3.0.jar, " + TRICKY + " and the dependency "
                        + TRICKY,
                ServiceIndexing.named("tricky-lib-1.jar and micronaut-core-5.3.0.jar, " + duplicate
                        + " and service-index-duplicate-3.jar", names));
    }
}
