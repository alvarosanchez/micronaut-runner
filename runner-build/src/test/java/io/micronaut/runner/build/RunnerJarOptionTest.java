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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The option table's invariants. Every assertion iterates over {@link RunnerJarOption#values()}, so an
 * option added later is covered without a new test.
 */
class RunnerJarOptionTest {

    @Test
    void namesAreUniqueCamelCaseIdentifiers() {
        Set<String> names = new HashSet<>();
        for (RunnerJarOption option : RunnerJarOption.values()) {
            assertTrue(option.optionName().matches("[a-z][A-Za-z0-9]*"),
                    () -> option + " has a name a build script cannot use: " + option.optionName());
            assertTrue(names.add(option.optionName()), () -> "two options are named " + option.optionName());
            assertEquals(Optional.of(option), RunnerJarOption.named(option.optionName()));
        }
    }

    @Test
    void everyDefaultIsWhatASpecReportsWhenNothingSetsTheOption() {
        Map<String, String> effective = spec(RunnerJarSpec.builder()).effectiveOptions();

        assertEquals(Arrays.stream(RunnerJarOption.values()).map(RunnerJarOption::optionName).toList(),
                List.copyOf(effective.keySet()), "every option is reported, in table order");
        for (RunnerJarOption option : RunnerJarOption.values()) {
            option.defaultValue().ifPresent(value -> assertEquals(value, effective.get(option.optionName()),
                    () -> option + " defaults to something other than its table entry"));
        }
    }

    @Test
    void effectiveValuesRoundTripThroughOption() {
        RunnerJarSpec defaults = spec(RunnerJarSpec.builder());
        RunnerJarSpec configured = spec(RunnerJarSpec.builder()
                .compression(Compression.PRESERVE)
                .entryStub(false)
                .multiRelease(true)
                .enableNativeAccess(true)
                .addOpens(List.of("java.base/java.lang", "java.base/java.util"))
                .addExports(List.of("java.base/sun.nio.ch"))
                .manifestAttributes(Map.of("Implementation-Vendor", "Example Ltd")));

        for (RunnerJarSpec spec : List.of(defaults, configured)) {
            RunnerJarSpec.Builder replayed = RunnerJarSpec.builder();
            spec.effectiveOptions().forEach(replayed::option);
            assertEquals(spec.effectiveOptions(), spec(replayed).effectiveOptions());
        }
    }

    @Test
    void describesEachDefault() {
        assertEquals("STORED", RunnerJarOption.COMPRESSION.defaultDescription());
        assertEquals("true", RunnerJarOption.ENTRY_STUB.defaultDescription());
        assertEquals("empty", RunnerJarOption.ADD_OPENS.defaultDescription());
        for (RunnerJarOption option : RunnerJarOption.values()) {
            assertFalse(option.defaultDescription().isBlank(), () -> option + " has no default description");
            assertTrue(option.since().matches("\\d+\\.\\d+"), () -> option + " since " + option.since());
        }
    }

    @Test
    void namesAreMatchedExactly() {
        assertEquals(Optional.of(RunnerJarOption.ENTRY_STUB), RunnerJarOption.named("entryStub"));
        assertEquals(Optional.empty(), RunnerJarOption.named("ENTRY_STUB"));
        assertEquals(Optional.empty(), RunnerJarOption.named("entrystub"));
        assertThrows(NullPointerException.class, () -> RunnerJarOption.named(null));
    }

    private static RunnerJarSpec spec(RunnerJarSpec.Builder builder) {
        return builder.mainClass("com.example.Application")
                .applicationOutput(List.of(Path.of("classes")))
                .output(Path.of("application.jar"))
                .build();
    }
}
