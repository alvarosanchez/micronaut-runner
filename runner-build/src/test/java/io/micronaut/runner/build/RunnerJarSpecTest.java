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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    void precompilesLogbackByDefaultAndTakesTheOptOutByName() {
        assertTrue(complete(RunnerJarSpec.builder()).build().precompileLogback());
        assertEquals("true", complete(RunnerJarSpec.builder()).build().effectiveOptions().get("precompileLogback"));
        assertFalse(complete(RunnerJarSpec.builder().option("precompileLogback", "false")).build()
                .precompileLogback());
        assertFalse(complete(RunnerJarSpec.builder().precompileLogback(false)).build().precompileLogback());
        assertEquals(RunnerJarOption.Exposure.PASSTHROUGH, RunnerJarOption.PRECOMPILE_LOGBACK.exposure());
    }

    @Test
    void keepsLocalVariablesByDefaultAndTheOptionTurnsStrippingOn() {
        assertFalse(complete(RunnerJarSpec.builder()).build().stripLocalVariables());
        assertEquals("false", complete(RunnerJarSpec.builder()).build().effectiveOptions().get("stripLocalVariables"));
        assertEquals(RunnerJarOption.Exposure.PASSTHROUGH, RunnerJarOption.STRIP_LOCAL_VARIABLES.exposure());
        assertEquals("false", RunnerJarOption.STRIP_LOCAL_VARIABLES.defaultValue().orElseThrow());
        assertTrue(complete(RunnerJarSpec.builder().stripLocalVariables(true)).build().stripLocalVariables());
        RunnerJarSpec on = complete(RunnerJarSpec.builder().option("stripLocalVariables", "true")).build();
        assertTrue(on.stripLocalVariables());
        assertEquals("true", on.effectiveOptions().get("stripLocalVariables"));
        assertFalse(complete(RunnerJarSpec.builder().option("stripLocalVariables", "true")
                .option("stripLocalVariables", "false")).build().stripLocalVariables(), "the last setting wins");
        assertTrue(complete(RunnerJarSpec.builder().compression(Compression.PRESERVE)
                .option("stripLocalVariables", "true")).build().stripLocalVariables(),
                "PRESERVE accepts the option, which then has no effect");
    }

    @Test
    void desugarsLambdasByDefaultAndTheOptionTurnsItOff() {
        assertTrue(complete(RunnerJarSpec.builder()).build().desugarLambdas());
        assertFalse(complete(RunnerJarSpec.builder().desugarLambdas(false)).build().desugarLambdas());
        RunnerJarSpec off = complete(RunnerJarSpec.builder().option("desugarLambdas", "false")).build();
        assertFalse(off.desugarLambdas());
        assertEquals("false", off.effectiveOptions().get("desugarLambdas"));
        assertEquals("true", complete(RunnerJarSpec.builder()).build().effectiveOptions().get("desugarLambdas"));
        assertTrue(complete(RunnerJarSpec.builder().option("desugarLambdas", "false").desugarLambdas(true)).build()
                .desugarLambdas(), "the last call wins");
        assertEquals(RunnerJarOption.Exposure.PASSTHROUGH, RunnerJarOption.DESUGAR_LAMBDAS.exposure());
        assertEquals(Boolean.class, RunnerJarOption.DESUGAR_LAMBDAS.valueType());
        assertEquals("true", RunnerJarOption.DESUGAR_LAMBDAS.defaultValue().orElseThrow());
        assertEquals(java.util.Optional.of(RunnerJarOption.DESUGAR_LAMBDAS), RunnerJarOption.named("desugarLambdas"));
        assertTrue(complete(RunnerJarSpec.builder().compression(Compression.PRESERVE)).build().desugarLambdas(),
                "PRESERVE accepts the option, which then has no effect");
    }

    /**
     * Runner's own bean definition prefetch was removed before 1.0 in favour of micronaut-core's: a build that
     * still sets the option fails with a message that names micronaut-core's system property, by value or not.
     */
    @Test
    void theRemovedDefinitionPrefetchOptionNamesMicronautsProperty() {
        assertEquals(Optional.empty(), RunnerJarOption.named("definitionPrefetch"));
        assertFalse(complete(RunnerJarSpec.builder()).build().effectiveOptions().containsKey("definitionPrefetch"));
        for (String value : List.of("true", "false")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> RunnerJarSpec.builder().option("definitionPrefetch", value));

            assertTrue(failure.getMessage().startsWith("The Micronaut Runner option 'definitionPrefetch' was removed"),
                    failure::getMessage);
            assertTrue(failure.getMessage().contains("micronaut.bean-definitions.prefetch=true"),
                    failure::getMessage);
            assertFalse(failure.getMessage().contains(" knows: "), failure::getMessage);
        }
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
                .option("manifestAttributes", "Implementation-Vendor: Example Ltd\nBuilt-By: ci")
                .option("startupClasses", "profiles/startup-classes.log"))
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
        assertEquals(Optional.of(Path.of("profiles/startup-classes.log")), spec.startupClasses());
    }

    @Test
    void startupClassesIsAFileWithoutADefault() {
        assertEquals(Path.class, RunnerJarOption.STARTUP_CLASSES.valueType());
        assertEquals(Optional.empty(), RunnerJarOption.STARTUP_CLASSES.defaultValue());
        assertEquals(RunnerJarOption.Exposure.TYPED, RunnerJarOption.STARTUP_CLASSES.exposure());

        RunnerJarSpec defaults = complete(RunnerJarSpec.builder()).build();
        assertEquals(Optional.empty(), defaults.startupClasses());
        assertEquals("", defaults.effectiveOptions().get("startupClasses"), "no file is the empty value");

        Path list = Path.of("profiles", "startup-classes.log");
        RunnerJarSpec typed = complete(RunnerJarSpec.builder().startupClasses(list)).build();
        assertEquals(Optional.of(list), typed.startupClasses());
        assertEquals(list.toString(), typed.effectiveOptions().get("startupClasses"));

        assertEquals(Optional.empty(), complete(RunnerJarSpec.builder().startupClasses(list)
                .option("startupClasses", " ")).build().startupClasses(), "the empty value by name unsets the file");
        assertEquals(Optional.empty(), complete(RunnerJarSpec.builder().startupClasses(list)
                .startupClasses(null)).build().startupClasses(), "null unsets the file");
    }

    @Test
    void aBooleanOptionAcceptsOnlyTrueOrFalse() {
        for (String name : List.of("entryStub", "multiRelease", "enableNativeAccess", "precompileLogback",
                "stripLocalVariables", "desugarLambdas")) {
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
    void archiveReadsDefaultsToTheGatesChoiceInTheBuilderAndTheTable() {
        assertEquals(ArchiveReads.MAPPED, complete(RunnerJarSpec.builder()).build().archiveReads(),
                "positional reads missed the default gate, so they are opt-in");
        assertEquals(RunnerJarOption.Exposure.PASSTHROUGH, RunnerJarOption.ARCHIVE_READS.exposure());
        assertEquals(String.class, RunnerJarOption.ARCHIVE_READS.valueType(),
                "a PASSTHROUGH option exposes no type of this library");
        assertEquals(java.util.Optional.of(ArchiveReads.MAPPED.name()), RunnerJarOption.ARCHIVE_READS.defaultValue());
        assertEquals(ArchiveReads.POSITIONAL, complete(RunnerJarSpec.builder()
                .archiveReads(ArchiveReads.POSITIONAL)).build().archiveReads());
        assertThrows(NullPointerException.class, () -> RunnerJarSpec.builder().archiveReads(null));
    }

    @Test
    void archiveReadsIsSetByNameAndReportedInTheEffectiveOptions() {
        RunnerJarSpec spec = complete(RunnerJarSpec.builder().option("archiveReads", "positional")).build();

        assertEquals(ArchiveReads.POSITIONAL, spec.archiveReads());
        assertEquals("POSITIONAL", spec.effectiveOptions().get("archiveReads"));
        assertEquals("MAPPED", complete(RunnerJarSpec.builder()).build().effectiveOptions().get("archiveReads"));
        assertEquals(ArchiveReads.MAPPED, complete(RunnerJarSpec.builder()
                .option("archiveReads", "positional")
                .archiveReads(ArchiveReads.MAPPED)).build().archiveReads(), "the last call wins");
    }

    @Test
    void anUnknownArchiveReadsValueListsTheSupportedValues() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RunnerJarSpec.builder().option("archiveReads", "bogus"));

        assertEquals("Unknown archiveReads 'bogus'. Supported values are MAPPED and POSITIONAL.",
                failure.getMessage());
    }

    @Test
    void archiveReadsParsesIgnoringCaseAndSurroundingWhitespace() {
        assertEquals(ArchiveReads.POSITIONAL, ArchiveReads.parse(" Positional "));
        assertEquals(ArchiveReads.MAPPED, ArchiveReads.parse("MAPPED"));
        for (ArchiveReads reads : ArchiveReads.values()) {
            assertEquals(reads, ArchiveReads.parse(reads.name()));
        }
        assertThrows(NullPointerException.class, () -> ArchiveReads.parse(null));
    }

    @Test
    void staticServicesAreRequestedByDefaultAndTurnedOffByName() {
        assertTrue(complete(RunnerJarSpec.builder()).build().staticServices(), "on by default, in one place");
        assertEquals(RunnerJarOption.Exposure.PASSTHROUGH, RunnerJarOption.STATIC_SERVICES.exposure());
        assertEquals(Boolean.class, RunnerJarOption.STATIC_SERVICES.valueType());
        assertEquals(java.util.Optional.of("true"), RunnerJarOption.STATIC_SERVICES.defaultValue());

        RunnerJarSpec off = complete(RunnerJarSpec.builder().option("staticServices", "false")).build();
        assertFalse(off.staticServices());
        assertEquals("false", off.effectiveOptions().get("staticServices"));
        assertEquals("true", complete(RunnerJarSpec.builder()).build().effectiveOptions().get("staticServices"));
        assertFalse(complete(RunnerJarSpec.builder().staticServices(false)).build().staticServices());
        assertTrue(complete(RunnerJarSpec.builder()
                .option("staticServices", "false")
                .staticServices(true)).build().staticServices(), "the last call wins");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RunnerJarSpec.builder().option("staticServices", "off"));
        assertTrue(failure.getMessage().contains("staticServices"), failure::getMessage);
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
