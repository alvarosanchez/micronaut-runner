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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerJarSpecTest {

    @Test
    void requestsTheEntryStubByDefault() {
        assertTrue(complete(RunnerJarSpec.builder()).build().entryStub());
        assertFalse(complete(RunnerJarSpec.builder().entryStub(false)).build().entryStub());
    }

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

    // ------------------------------------------------------------------ option()

    @Test
    void everyTypedOptionReachesItsGetterByName() {
        RunnerJarSpec spec = complete(RunnerJarSpec.builder()
                .option("compression", "PRESERVE")
                .option("entryStub", "false")
                .option("multiRelease", "TRUE")
                .option("enableNativeAccess", " true ")
                .option("addOpens", "java.base/java.lang,java.base/java.util")
                .option("addExports", "java.base/sun.nio.ch")
                .option("manifestAttributes", "Implementation-Vendor: Example Ltd\nBuilt-By: ci"))
                .build();

        assertEquals(Compression.PRESERVE, spec.compression());
        assertFalse(spec.entryStub());
        assertTrue(spec.multiRelease());
        assertTrue(spec.enableNativeAccess());
        assertEquals(List.of("java.base/java.lang", "java.base/java.util"), spec.addOpens());
        assertEquals(List.of("java.base/sun.nio.ch"), spec.addExports());
        assertEquals(Map.of("Implementation-Vendor", "Example Ltd", "Built-By", "ci"), spec.manifestAttributes());
        assertEquals(List.of("Implementation-Vendor", "Built-By"), List.copyOf(spec.manifestAttributes().keySet()),
                "attributes keep the order of their lines");
    }

    @Test
    void aBooleanOptionAcceptsOnlyTrueOrFalse() {
        for (String name : List.of("entryStub", "multiRelease", "enableNativeAccess")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> RunnerJarSpec.builder().option(name, "yes"));
            assertTrue(failure.getMessage().contains(name), failure::getMessage);
            assertTrue(failure.getMessage().contains("'yes'"), failure::getMessage);
        }
    }

    @Test
    void aListOptionIsSplitTrimmedAndValidated() {
        RunnerJarSpec spec = complete(RunnerJarSpec.builder()
                .option("addOpens", " java.base/java.lang , ,java.base/java.util,"))
                .build();
        assertEquals(List.of("java.base/java.lang", "java.base/java.util"), spec.addOpens());
        assertEquals(List.of(), complete(RunnerJarSpec.builder().option("addExports", " , ")).build().addExports());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RunnerJarSpec.builder().option("addOpens", "java.base/java.lang=ALL-UNNAMED"));
        assertTrue(failure.getMessage().contains("Use 'java.base/java.lang' in the JAR manifest"),
                failure::getMessage);
        assertTrue(failure.getMessage().contains("--add-opens java.base/java.lang=ALL-UNNAMED"),
                failure::getMessage);
    }

    @Test
    void aMapOptionReadsManifestLinesAndRejectsReservedNames() {
        RunnerJarSpec spec = complete(RunnerJarSpec.builder()
                .option("manifestAttributes", "\n  Implementation-Vendor:   Example Ltd  \r\n\nX-Empty:\n"))
                .build();
        assertEquals(Map.of("Implementation-Vendor", "Example Ltd", "X-Empty", ""), spec.manifestAttributes());

        IllegalArgumentException reserved = assertThrows(IllegalArgumentException.class,
                () -> RunnerJarSpec.builder().option("manifestAttributes", "Main-Class: x"));
        assertTrue(reserved.getMessage().contains("'Main-Class' is reserved"), reserved::getMessage);

        IllegalArgumentException noColon = assertThrows(IllegalArgumentException.class,
                () -> RunnerJarSpec.builder().option("manifestAttributes", "Implementation-Vendor Example"));
        assertTrue(noColon.getMessage().contains("'Name: value'"), noColon::getMessage);

        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class,
                () -> RunnerJarSpec.builder().option("manifestAttributes", "X-A: 1\nx-a: 2"));
        assertTrue(twice.getMessage().contains("more than once"), twice::getMessage);
    }

    @Test
    void anUnknownOptionFailsListingEveryKnownName() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RunnerJarSpec.builder().option("desugarLambda", "true"));

        String message = failure.getMessage();
        assertTrue(message.startsWith("Unknown Micronaut Runner option 'desugarLambda'. micronaut-runner-build "),
                message);
        String known = Arrays.stream(RunnerJarOption.values()).map(RunnerJarOption::optionName)
                .collect(Collectors.joining(", "));
        assertTrue(message.endsWith(" knows: " + known), message);
        // Tests run from a classes directory, which carries no Implementation-Version.
        assertTrue(message.contains("micronaut-runner-build unknown knows"), message);
    }

    @Test
    void theLastCallWinsWhetherItIsTypedOrByName() {
        assertEquals(Compression.PRESERVE, complete(RunnerJarSpec.builder()
                .compression(Compression.STORED)
                .option("compression", "PRESERVE")).build().compression());
        assertEquals(Compression.STORED, complete(RunnerJarSpec.builder()
                .option("compression", "PRESERVE")
                .compression(Compression.STORED)).build().compression());
        assertEquals(List.of("java.base/java.util"), complete(RunnerJarSpec.builder()
                .option("addOpens", "java.base/java.lang")
                .addOpens(List.of("java.base/java.util"))).build().addOpens());
        assertFalse(complete(RunnerJarSpec.builder()
                .entryStub(true)
                .option("entryStub", "false")).build().entryStub());
    }

    @Test
    void aNullNameOrValueIsRejected() {
        assertThrows(NullPointerException.class, () -> RunnerJarSpec.builder().option(null, "true"));
        assertThrows(NullPointerException.class, () -> RunnerJarSpec.builder().option("entryStub", null));
    }

    @Test
    void theEffectiveOptionsAreUnmodifiable() {
        Map<String, String> effective = complete(RunnerJarSpec.builder()).build().effectiveOptions();
        assertThrows(UnsupportedOperationException.class, () -> effective.put("compression", "PRESERVE"));
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
