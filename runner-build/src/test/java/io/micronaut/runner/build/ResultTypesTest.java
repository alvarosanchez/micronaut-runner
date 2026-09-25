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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Dependency} and {@link RunnerJarResult}, the two value types a plugin handles, as final classes.
 */
class ResultTypesTest {

    @Test
    void blankCoordinatesAreUnknown() {
        Path jar = Path.of("libs", "netty-common.jar");

        assertEquals(Optional.empty(), Dependency.of(jar, " ").coordinates());
        assertEquals(Optional.empty(), Dependency.of(jar, null).coordinates());
        assertEquals(Optional.empty(), Dependency.of(jar).coordinates());
        assertEquals(Optional.of("io.netty:netty-common:4.2.1"),
                Dependency.of(jar, "io.netty:netty-common:4.2.1").coordinates());
        assertEquals("netty-common.jar", Dependency.of(jar).fileName());
        assertEquals(jar, Dependency.of(jar).path());
        assertThrows(NullPointerException.class, () -> Dependency.of(null));
    }

    @Test
    void dependenciesAreValues() {
        Path jar = Path.of("libs", "netty-common.jar");
        Dependency first = Dependency.of(jar, "io.netty:netty-common:4.2.1");
        Dependency second = Dependency.of(Path.of("libs", "netty-common.jar"), "io.netty:netty-common:4.2.1");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(Dependency.of(jar, ""), Dependency.of(jar));
        assertNotEquals(first, Dependency.of(jar));
        assertNotEquals(first, Dependency.of(Path.of("other.jar"), "io.netty:netty-common:4.2.1"));
    }

    @Test
    void theProjectModuleFlagIsPartOfTheValue() {
        Path jar = Path.of("libs", "lib.jar");
        Dependency plain = Dependency.of(jar, "com.example:lib:1.0");
        Dependency module = plain.projectModule(true);

        assertFalse(plain.projectModule());
        assertFalse(Dependency.of(jar).projectModule());
        assertTrue(module.projectModule());
        assertEquals(plain.path(), module.path());
        assertEquals(plain.coordinates(), module.coordinates());
        assertNotEquals(plain, module);
        assertEquals(module, Dependency.of(jar, "com.example:lib:1.0").projectModule(true));
        assertEquals(module.hashCode(), Dependency.of(jar, "com.example:lib:1.0").projectModule(true).hashCode());
        assertSame(plain, plain.projectModule(false));
        assertEquals(plain, module.projectModule(false));
    }

    @Test
    void theTransformReportsOfAResultAreUnmodifiableAndAddUp() {
        TransformReport report = new TransformReport("stripLocalVariables", 7, 2, 1, 4096);
        RunnerJarResult result = new RunnerJarResult(Path.of("app.jar"), 2, 10, 3, 0, 1024, List.of(), Map.of(),
                new ArrayList<>(List.of(report)));

        assertEquals(List.of(report), result.transforms());
        assertEquals(10, report.classes());
        assertThrows(UnsupportedOperationException.class, () -> result.transforms().clear());
        assertEquals(List.of(), result(new ArrayList<>(), new LinkedHashMap<>()).transforms());
        assertEquals(report, new TransformReport("stripLocalVariables", 7, 2, 1, 4096));
        assertThrows(IllegalArgumentException.class, () -> new TransformReport("x", -1, 0, 0, 0));
    }

    @Test
    void theSummaryReportsWhatWasWritten() {
        RunnerJarResult result = result(new ArrayList<>(List.of("a warning")), new LinkedHashMap<>());

        assertEquals("Runner jar written to " + Path.of("build", "app-all.jar")
                + " (3 dependencies, 120 index records, 4096 bytes)", result.summary());
        assertEquals(3, result.dependencyCount());
    }

    @Test
    void theCollectionsOfAResultAreUnmodifiable() {
        List<String> warnings = new ArrayList<>(List.of("a warning"));
        Map<String, String> options = new LinkedHashMap<>(Map.of("compression", "STORED"));
        RunnerJarResult result = result(warnings, options);
        warnings.add("added later");
        options.put("entryStub", "true");

        assertEquals(List.of("a warning"), result.warnings());
        assertEquals(Map.of("compression", "STORED"), result.effectiveOptions());
        assertThrows(UnsupportedOperationException.class, () -> result.warnings().add("another"));
        assertThrows(UnsupportedOperationException.class,
                () -> result.effectiveOptions().put("compression", "PRESERVE"));
    }

    @Test
    void aNegativeCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RunnerJarResult(Path.of("app.jar"), -1, 0, 0, 0,
                0, List.of(), Map.of()));
    }

    private static RunnerJarResult result(List<String> warnings, Map<String, String> options) {
        return new RunnerJarResult(Path.of("build", "app-all.jar"), 4, 120, 10, 2, 4096, warnings, options);
    }
}
