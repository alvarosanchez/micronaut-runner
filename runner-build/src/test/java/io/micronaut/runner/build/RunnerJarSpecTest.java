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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerJarSpecTest {

    @Test
    void acceptsMultipleManifestModulePackagePairs() {
        RunnerJarSpec.Builder builder = RunnerJarSpec.builder()
                .addExports(List.of("java.base/sun.nio.ch", "java.base/jdk.internal.misc"))
                .addOpens(List.of("java.base/java.lang", "java.base/java.util"));

        RunnerJarSpec spec = complete(builder).build();

        assertEquals(List.of("java.base/sun.nio.ch", "java.base/jdk.internal.misc"), spec.addExports());
        assertEquals(List.of("java.base/java.lang", "java.base/java.util"), spec.addOpens());
    }

    @Test
    void rejectsCommandLineExportSyntaxWithACorrection() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RunnerJarSpec.builder()
                        .addExports(List.of("java.base/sun.nio.ch=ALL-UNNAMED")));

        assertTrue(failure.getMessage().contains("addExports"), failure::getMessage);
        assertTrue(failure.getMessage().contains("java.base/sun.nio.ch=ALL-UNNAMED"), failure::getMessage);
        assertTrue(failure.getMessage().contains("java.base/sun.nio.ch"), failure::getMessage);
        assertTrue(failure.getMessage().contains("--add-exports"), failure::getMessage);
    }

    @Test
    void rejectsMalformedManifestModulePackagePairsForExportsAndOpens() {
        for (String entry : List.of("", " ", "/java.lang", "java.base/", "java.base/java/lang",
                "java.base /java.lang", "java.base/java.lang\t")) {
            assertMalformed("addExports", entry,
                    () -> RunnerJarSpec.builder().addExports(List.of(entry)));
            assertMalformed("addOpens", entry,
                    () -> RunnerJarSpec.builder().addOpens(List.of(entry)));
        }
    }

    private static void assertMalformed(String property, String entry, Runnable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(failure.getMessage().contains(property), failure::getMessage);
        assertTrue(failure.getMessage().contains("module/package"), failure::getMessage);
        assertTrue(failure.getMessage().contains("'" + entry + "'"), failure::getMessage);
    }

    private static RunnerJarSpec.Builder complete(RunnerJarSpec.Builder builder) {
        return builder
                .mainClass("com.example.Application")
                .applicationOutput(List.of(java.nio.file.Path.of("classes")))
                .output(java.nio.file.Path.of("application.jar"));
    }
}
