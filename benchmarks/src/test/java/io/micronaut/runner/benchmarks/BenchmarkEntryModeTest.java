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

import io.micronaut.runner.IndexFormat;
import io.micronaut.runner.build.Compression;
import io.micronaut.runner.build.RunnerJarReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkEntryModeTest {

    private static final String GENERATED_ENTRY_STUB = "io.micronaut.runner.generated.AppEntry";

    private static final List<String> CORE_ROWS = List.of(
            "exploded-cp",
            "thin-jar",
            "shadow",
            "shadow-stored",
            "shadow-aot",
            "runner-stored",
            "runner-stored-aot",
            "runner-preserve",
            "runner-extracted",
            "runner-extracted-aot");

    @Test
    void matrixNamesPluginDefaults() {
        assertEquals(CORE_ROWS, SampleBuild.variantNames());
        assertTrue(SampleBuild.variantNames().stream().noneMatch(name -> name.endsWith("-reflection")));
    }

    @Test
    void sharedBuildFailureSchedulesTheCoreRowsAndOptInRowsOnlyOnRequest() {
        List<String> core = SampleBuild.unavailableVariants("sample build failed", false).stream()
                .map(Variant::name).toList();
        List<String> withOptIn = SampleBuild.unavailableVariants("sample build failed", true).stream()
                .map(Variant::name).toList();

        assertEquals(CORE_ROWS, core);
        assertTrue(core.stream().noneMatch(name -> name.endsWith("-reflection")), core.toString());

        List<String> expected = new ArrayList<>(CORE_ROWS);
        int afterStored = expected.indexOf("runner-stored-aot") + 1;
        expected.addAll(afterStored, List.of("runner-stored-reflection", "runner-stored-keepdebug",
                "runner-stored-keepdebug-aot"));
        assertEquals(expected, withOptIn, "the opt-in rows follow the runner-stored group");
        assertEquals(CORE_ROWS.size() + 3, withOptIn.size());
        assertTrue(core.stream().noneMatch(name -> name.contains("keepdebug")), core.toString());
        assertTrue(SampleBuild.variantNames().stream().noneMatch(name -> name.contains("keepdebug")));
        assertEquals(EntryMode.REFLECTION, EntryMode.requestedBy("runner-stored-reflection"));
        assertEquals(EntryMode.STUB, EntryMode.requestedBy("runner-stored-keepdebug"));
        assertEquals(EntryMode.STUB, EntryMode.requestedBy("runner-stored-keepdebug-aot"));
        assertEquals(EntryMode.STANDARD_LOADER, EntryMode.requestedBy("shadow-stored"));
    }

    @Test
    void reportsRequestedAndEffectiveEntryModes(@TempDir Path output) throws Exception {
        List<Variant> variants = List.of(
                Variant.unavailable("runner-stored", "plugin-default fixture", "not built"),
                Variant.unavailable("runner-stored-aot", "AOT fixture", "not trained"),
                Variant.unavailable("runner-stored-reflection", "reflection fixture", "not built"),
                Variant.unavailable("runner-extracted", "standard loader fixture", "not built"));
        RunContext context = new RunContext(output, "file:/repo", "1.0", output,
                1, 0, 1, "/hello", false, "2026-09-22T00:00:00Z",
                variants.stream().map(Variant::name).toList(), CompletenessPolicy.REQUIRED);
        List<VariantResult> results = variants.stream()
                .map(variant -> new VariantResult(variant, -1, List.of(), null, null, null, List.of()))
                .toList();

        Reports.write(output, context, results, List.of());

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"requestedEntryMode\": \"stub\""));
        assertTrue(json.contains("\"applicationCacheMode\": \"aot\""));
        assertTrue(json.contains("\"effectiveEntryMode\": null"));
        assertTrue(json.contains("\"requestedEntryMode\": \"reflection\""));
        assertTrue(json.contains("\"requestedEntryMode\": \"standard-loader\""));

        String markdown = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("| Variant | Requested entry | Effective entry | Packaging |"));
        assertTrue(markdown.contains("| `runner-stored` | stub | unavailable | plugin-default fixture |"));
        assertTrue(markdown.contains("| `runner-stored-aot` | stub | unavailable | AOT fixture |"));
        assertTrue(markdown.contains("| `runner-stored-reflection` | reflection | unavailable | reflection fixture |"));
        assertTrue(markdown.contains("| `runner-extracted` | standard-loader | unavailable | standard loader fixture |"));
    }

    @Test
    void preparesVerifiedStubAndReflectionPairsForBothCompressionModes(@TempDir Path output) throws Exception {
        Path classes = compile(output.resolve("eligible"), "fixture.EligibleMain", """
                package fixture;
                public final class EligibleMain {
                    public static void main(String[] args) { }
                }
                """);

        for (Compression compression : Compression.values()) {
            String suffix = compression.name().toLowerCase(java.util.Locale.ROOT);
            Variant stub = SampleBuild.runnerJar(output, "runner-" + suffix,
                    "fixture.EligibleMain", List.of(classes), List.of(), compression, EntryMode.STUB);
            Variant reflection = SampleBuild.runnerJar(output, "runner-" + suffix + "-reflection",
                    "fixture.EligibleMain", List.of(classes), List.of(), compression, EntryMode.REFLECTION);

            assertEquals(EntryMode.STUB, stub.requestedEntryMode());
            assertEquals(EntryMode.STUB, stub.effectiveEntryMode());
            assertEquals(EntryMode.REFLECTION, reflection.requestedEntryMode());
            assertEquals(EntryMode.REFLECTION, reflection.effectiveEntryMode());
            assertRunnerIndex(stub.artifact(), true);
            assertRunnerIndex(reflection.artifact(), false);
            assertSameApplicationInputs(stub.artifact(), reflection.artifact());
        }
    }

    @Test
    void theLocalVariableTableControlKeepsTheTablesTheDefaultStrips(@TempDir Path output) throws Exception {
        Path classes = compile(output.resolve("eligible"), "fixture.EligibleMain", """
                package fixture;
                public final class EligibleMain {
                    public static void main(String[] args) {
                        int unused = args.length;
                    }
                }
                """);
        Path library = output.resolve("library.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(library))) {
            jar.putNextEntry(new ZipEntry("fixture/EligibleMain.class"));
            jar.write(Files.readAllBytes(classes.resolve("fixture/EligibleMain.class")));
            jar.closeEntry();
        }

        Variant stripped = SampleBuild.runnerJar(output, "runner-stored", "fixture.EligibleMain",
                List.of(classes), List.of(library), Compression.STORED, EntryMode.STUB);
        Variant kept = SampleBuild.runnerJar(output, "runner-stored-keepdebug", "fixture.EligibleMain",
                List.of(classes), List.of(library), Compression.STORED, EntryMode.STUB,
                new SampleBuild.RunnerJarOptions(false));

        assertTrue(SampleBuild.RunnerJarOptions.defaults().stripLocalVariables());
        assertTrue(kept.description().endsWith("; local-variable tables kept"), kept.description());
        assertFalse(stripped.description().contains("local-variable"), stripped.description());
        assertTrue(entryNames(stripped.artifact()).contains("MICRONAUT-INF/transforms.txt"),
                "the default strips the -g compiled dependency");
        assertFalse(entryNames(kept.artifact()).contains("MICRONAUT-INF/transforms.txt"));
        assertTrue(SampleBuild.comparisons().contains(new SampleBuild.ComparisonSpec("runner-stored",
                "runner-stored-keepdebug", "Local-variable tables stripped vs kept")));
        assertTrue(SampleBuild.comparisons().stream().anyMatch(spec -> spec.candidate().equals("runner-stored-aot")
                && spec.baseline().equals("runner-stored-keepdebug-aot")));
    }

    private static List<String> entryNames(Path artifact) throws IOException {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            return jar.stream().map(ZipEntry::getName).toList();
        }
    }

    @Test
    void requestedStubForIneligibleMainFailsBeforeMeasurement(@TempDir Path output) throws Exception {
        Path classes = compile(output.resolve("ineligible"), "fixture.IneligibleMain", """
                package fixture;
                public final class IneligibleMain {
                    public static int main(String[] args) { return 0; }
                }
                """);

        IOException failure = assertThrows(IOException.class, () -> SampleBuild.runnerJar(output,
                "runner-stored", "fixture.IneligibleMain", List.of(classes), List.of(),
                Compression.STORED, EntryMode.STUB));

        assertTrue(failure.getMessage().contains("entry stub was requested"));
    }

    @Test
    void extractedVariantLaunchesTheActualApplicationJarAndManifestClasspathInOrder(@TempDir Path output)
            throws Exception {
        Path classes = compile(output.resolve("extracted-input"), "fixture.ExtractedMain", """
                package fixture;
                public final class ExtractedMain {
                    public static void main(String[] args) { }
                }
                """);
        Path dependencyOne = emptyJar(output.resolve("first dependency.jar"));
        Path dependencyTwo = emptyJar(output.resolve("second.jar"));
        Variant runner = SampleBuild.runnerJar(output, "extract-source", "fixture.ExtractedMain",
                List.of(classes), List.of(dependencyOne, dependencyTwo), Compression.STORED, EntryMode.STUB);

        Variant extracted = SampleBuild.extractedRunner(output, runner, "runner-extracted");

        assertEquals(EntryMode.STANDARD_LOADER, extracted.effectiveEntryMode());
        assertEquals("-jar", extracted.command().get(1));
        assertEquals(extracted.launchInputs().get(0).toAbsolutePath().toString(), extracted.command().get(2));
        assertEquals(3, extracted.launchInputs().size());
        assertEquals("first dependency.jar", extracted.launchInputs().get(1).getFileName().toString());
        assertEquals("second.jar", extracted.launchInputs().get(2).getFileName().toString());
    }

    private static Path compile(Path fixture, String className, String source) throws IOException {
        Path sources = fixture.resolve("src");
        Path classes = fixture.resolve("classes");
        Path sourceFile = sources.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(classes);
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        // -g, so that a dependency built from a fixture carries local-variable tables.
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-g", "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, exit, "fixture compilation failed");
        return classes;
    }

    private static Path emptyJar(Path path) throws IOException {
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(path))) {
            return path;
        }
    }

    private static void assertRunnerIndex(Path artifact, boolean stubExpected) throws IOException {
        try (RunnerJarReader reader = RunnerJarReader.open(artifact)) {
            if (stubExpected) {
                assertEquals(GENERATED_ENTRY_STUB, reader.index().entryStubClass());
                assertTrue(reader.index().findClass(GENERATED_ENTRY_STUB) != IndexFormat.NO_INDEX);
            } else {
                assertNull(reader.index().entryStubClass());
                assertEquals(IndexFormat.NO_INDEX, reader.index().findClass(GENERATED_ENTRY_STUB));
            }
        }
    }

    private static void assertSameApplicationInputs(Path stub, Path reflection) throws IOException {
        Map<String, byte[]> stubEntries = payloadEntries(stub);
        Map<String, byte[]> reflectionEntries = payloadEntries(reflection);
        assertEquals(stubEntries.keySet(), reflectionEntries.keySet());
        for (String entry : stubEntries.keySet()) {
            assertArrayEquals(stubEntries.get(entry), reflectionEntries.get(entry), entry);
        }
    }

    private static Map<String, byte[]> payloadEntries(Path artifact) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            var enumeration = jar.entries();
            while (enumeration.hasMoreElements()) {
                var entry = enumeration.nextElement();
                if (entry.isDirectory() || entry.getName().equals(IndexFormat.INDEX_ENTRY_NAME)
                        || entry.getName().equals(IndexFormat.CLASSES_PREFIX
                        + GENERATED_ENTRY_STUB.replace('.', '/') + ".class")) {
                    continue;
                }
                try (var in = jar.getInputStream(entry)) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return entries;
    }
}
