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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code --variants}: validation, the one resolved selection, and which rows building it builds. */
class VariantSelectionTest {

    @Test
    void unknownEmptyAndRepeatedNamesAreUsageErrorsThatListTheValidNames(@TempDir Path output) {
        for (String value : new String[] {"shadow,runner-nested", "", "shadow,,runner-stored", " , ",
                "shadow,runner-stored,shadow", "shadow, shadow"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> StartupBenchmark.Options.parse(arguments(output, "--variants", value)), value);
            assertTrue(failure.getMessage().contains("valid names: " + String.join(", ",
                    SampleBuild.allVariantNames())), failure.getMessage());
        }
    }

    @Test
    void variantsCannotBeCombinedWithOptionalRows(@TempDir Path output) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StartupBenchmark.Options.parse(arguments(output, "--variants", "shadow", "--optional-rows")));
        assertTrue(failure.getMessage().contains("--optional-rows"), failure.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> StartupBenchmark.Options.parse(arguments(output, "--optional-rows", "--variants", "shadow")));
    }

    @Test
    void theSelectionFollowsReportOrderAndDefaultsToTodaysRows(@TempDir Path output) {
        StartupBenchmark.Options named = StartupBenchmark.Options.parse(
                arguments(output, "--variants", " runner-extracted-aot , shadow,runner-stored "));
        assertEquals(List.of("shadow", "runner-stored", "runner-extracted-aot"), named.selection());
        assertEquals(List.of("shadow", "runner-stored", "runner-extracted-aot"), named.requiredVariants());

        StartupBenchmark.Options defaults = StartupBenchmark.Options.parse(arguments(output));
        assertEquals(SampleBuild.variantNames(), defaults.selection());
        assertEquals(SampleBuild.variantNames(), defaults.requiredVariants());

        StartupBenchmark.Options optIn = StartupBenchmark.Options.parse(arguments(output, "--optional-rows"));
        assertEquals(SampleBuild.allVariantNames(), optIn.selection());
        assertEquals(SampleBuild.variantNames(), optIn.requiredVariants());
    }

    @Test
    void theValidNamesAreEveryRowOfTheTableInReportOrder() {
        List<String> all = SampleBuild.allVariantNames();

        assertTrue(all.containsAll(SampleBuild.variantNames()));
        assertEquals(List.of("runner-stored-reflection"),
                all.stream().filter(name -> !SampleBuild.variantNames().contains(name)).toList());
        assertEquals(all.indexOf("runner-stored-aot") + 1, all.indexOf("runner-stored-reflection"));
    }

    @Test
    void namingAnOptInRowBuildsItWithoutOptionalRowsButNeverRequiresIt(@TempDir Path output) {
        StartupBenchmark.Options options = StartupBenchmark.Options.parse(
                arguments(output, "--variants", "runner-stored-reflection,runner-stored"));
        FakeSteps steps = new FakeSteps();

        List<Variant> variants = SampleBuild.variants(steps, options.selection(), log());

        assertFalse(options.optionalRows());
        assertEquals(List.of("runner-stored", "runner-stored-reflection"), names(variants));
        assertEquals(List.of("runner-stored"), options.requiredVariants());
        assertEquals(1, steps.calls("runnerJar:runner-stored-reflection"));
    }

    @Test
    void aDerivedRowBuildsItsPrerequisitesOnceAndReportsOnlyItself() {
        FakeSteps steps = new FakeSteps();
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        List<Variant> variants = SampleBuild.variants(steps, List.of("runner-extracted-aot"),
                new PrintStream(console, true, StandardCharsets.UTF_8));

        assertEquals(List.of("runner-extracted-aot"), names(variants));
        assertEquals(Map.of("runnerJar:runner-stored", 1, "extracted:runner-stored", 1,
                "aotCache:runner-extracted->runner-extracted-aot", 1), steps.calls);
        String log = console.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("[startup-benchmark] prepared runner-stored"), log);
        assertTrue(log.contains("[startup-benchmark] prepared runner-extracted\n")
                || log.contains("[startup-benchmark] prepared runner-extracted" + System.lineSeparator()), log);
    }

    @Test
    void aSharedPrerequisiteIsBuiltOnce() {
        FakeSteps steps = new FakeSteps();

        List<Variant> variants = SampleBuild.variants(steps,
                List.of("runner-extracted-aot", "runner-stored-aot", "shadow-aot"), log());

        assertEquals(List.of("shadow-aot", "runner-stored-aot", "runner-extracted-aot"), names(variants));
        assertEquals(1, steps.calls("runnerJar:runner-stored"));
        assertEquals(1, steps.calls("shadow"));
        assertEquals(0, steps.calls("shadowStored"));
        assertEquals(0, steps.calls("thinJar"));
    }

    @Test
    void theDefaultSelectionBuildsEveryCoreRowOnceInReportOrder() {
        FakeSteps steps = new FakeSteps();

        List<Variant> variants = SampleBuild.variants(steps, SampleBuild.variantNames(), log());

        assertEquals(SampleBuild.variantNames(), names(variants));
        assertTrue(steps.calls.values().stream().allMatch(count -> count == 1), steps.calls.toString());
        assertEquals(List.of("explodedClasspath", "thinJar", "shadow", "shadowStored", "aotCache:shadow->shadow-aot",
                "runnerJar:runner-stored", "aotCache:runner-stored->runner-stored-aot", "runnerJar:runner-preserve",
                "extracted:runner-stored", "aotCache:runner-extracted->runner-extracted-aot"),
                List.copyOf(steps.calls.keySet()));
    }

    @Test
    void aFailedPrerequisiteMakesTheDerivedRowUnavailableAndIsNotRetried() {
        FakeSteps steps = new FakeSteps() {
            @Override
            public Variant runnerJar(String name, Compression compression, EntryMode requestedEntryMode)
                    throws IOException {
                note("runnerJar:" + name);
                throw new IOException("no runner jar for " + name);
            }

            @Override
            public Variant aotCache(Variant source, String name) throws IOException {
                if (!source.available()) {
                    note("aotCache:" + source.name() + "->" + name);
                    throw new IOException("cannot train AOT cache because " + source.name() + " is unavailable");
                }
                return super.aotCache(source, name);
            }

            @Override
            public Variant extracted(Variant stored) {
                note("extracted:" + stored.name());
                throw new IllegalStateException("there is no runner jar to extract: " + stored.unavailableReason());
            }
        };

        List<Variant> variants = SampleBuild.variants(steps, List.of("runner-stored-aot", "runner-extracted"), log());

        assertEquals(List.of("runner-stored-aot", "runner-extracted"), names(variants));
        assertTrue(variants.stream().noneMatch(Variant::available));
        assertEquals(1, steps.calls("runnerJar:runner-stored"));
        assertEquals(1, steps.calls("extracted:runner-stored"));
        assertTrue(variants.get(0).unavailableReason().contains("runner-stored is unavailable"),
                variants.get(0).unavailableReason());
        assertTrue(variants.get(1).unavailableReason().contains("no runner jar for runner-stored"),
                variants.get(1).unavailableReason());
    }

    @Test
    void aFailedSampleBuildListsExactlyTheSelection() {
        List<String> selection = List.of("shadow", "runner-stored", "runner-extracted-aot");

        List<Variant> variants = SampleBuild.unavailableVariants("sample build failed", selection);

        assertEquals(selection, names(variants));
        assertTrue(variants.stream().noneMatch(Variant::available));
        assertTrue(variants.stream().allMatch(variant -> variant.unavailableReason().equals("sample build failed")));
    }

    @Test
    void theRowHelperMemoizesFakeFactories() {
        Map<String, Integer> built = new LinkedHashMap<>();
        VariantRows.Factory<Map<String, Integer>> leaf = (calls, rows) -> fake(calls, "base");
        List<VariantRows.Row<Map<String, Integer>>> table = List.of(
                new VariantRows.Row<>("base", "base row", true, leaf),
                new VariantRows.Row<>("left", "derived", true, (calls, rows) -> derived(calls, rows.get("base"), "left")),
                new VariantRows.Row<>("right", "derived", false,
                        (calls, rows) -> derived(calls, rows.get("left"), "right")));

        List<Variant> variants = VariantRows.build(table, built, List.of("right"), (name, description, create) -> {
            try {
                return create.create();
            } catch (Exception e) {
                return Variant.unavailable(name, description, e.getMessage());
            }
        });

        assertEquals(List.of("right"), names(variants));
        assertEquals(Map.of("base", 1, "left", 1, "right", 1), built);
        assertEquals(List.of("base", "left", "right"), VariantRows.names(table));
        assertEquals(List.of("base", "left"), VariantRows.coreNames(table));
        assertThrows(IllegalArgumentException.class, () -> VariantRows.build(table, built, List.of("missing"),
                (name, description, create) -> Variant.unavailable(name, description, "unused")));
    }

    @Test
    void comparisonsNeedBothSelectedVariants(@TempDir Path output) throws Exception {
        Path sample = Files.createDirectories(output.resolve("sample"));
        List<String> selection = List.of("shadow", "runner-stored", "runner-extracted-aot");
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output, 1, 0, 1, "/hello", false,
                "2026-09-25T00:00:00Z", selection, CompletenessPolicy.REQUIRED);
        List<VariantResult> results = SampleBuild.unavailableVariants("not built", selection).stream()
                .map(variant -> new VariantResult(variant, -1, List.of(), null, null, null, List.of()))
                .toList();

        Reports.write(output, context, results, List.of());

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        String comparisons = json.substring(json.indexOf("\"comparisons\": ["), json.indexOf("\"attempts\": ["));
        assertTrue(comparisons.contains("\"candidateVariant\": \"runner-stored\", \"baselineVariant\": \"shadow\""),
                comparisons);
        assertEquals(1, comparisons.split("\"label\"", -1).length - 1, comparisons);
        assertFalse(json.contains("\"name\": \"shadow-aot\""), "an unselected row is absent, not unavailable");
    }

    private static List<String> names(List<Variant> variants) {
        return variants.stream().map(Variant::name).toList();
    }

    private static String[] arguments(Path output, String... more) {
        List<String> arguments = new ArrayList<>(List.of("--sample", output.toString(), "--repo", "file:/repo",
                "--version", "1.0", "--iterations", "1", "--out", output.toString()));
        arguments.addAll(List.of(more));
        return arguments.toArray(String[]::new);
    }

    private static PrintStream log() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static Variant fake(Map<String, Integer> calls, String name) {
        calls.merge(name, 1, Integer::sum);
        return Variant.available(name, "fake " + name, List.of("java"), Path.of("."), Path.of(name + ".jar"));
    }

    private static Variant derived(Map<String, Integer> calls, Variant source, String name) {
        assertTrue(source.available(), source.name());
        return fake(calls, name);
    }

    /** Records every build step instead of building anything. */
    private static class FakeSteps implements SampleSteps {

        final Map<String, Integer> calls = new LinkedHashMap<>();

        void note(String call) {
            calls.merge(call, 1, Integer::sum);
        }

        int calls(String call) {
            return calls.getOrDefault(call, 0);
        }

        private Variant variant(String name) {
            return Variant.available(name, "fake " + name, List.of("java", "-jar", name + ".jar"), Path.of("."),
                    Path.of(name + ".jar"));
        }

        @Override
        public Variant explodedClasspath() {
            note("explodedClasspath");
            return variant("exploded-cp");
        }

        @Override
        public Variant thinJar() {
            note("thinJar");
            return variant("thin-jar");
        }

        @Override
        public Variant shadow() {
            note("shadow");
            return variant("shadow");
        }

        @Override
        public Variant shadowStored() {
            note("shadowStored");
            return variant("shadow-stored");
        }

        @Override
        public Variant runnerJar(String name, Compression compression, EntryMode requestedEntryMode)
                throws IOException {
            note("runnerJar:" + name);
            return variant(name);
        }

        @Override
        public Variant aotCache(Variant source, String name) throws IOException {
            note("aotCache:" + source.name() + "->" + name);
            return variant(name);
        }

        @Override
        public Variant extracted(Variant stored) {
            note("extracted:" + stored.name());
            return variant("runner-extracted");
        }
    }
}
