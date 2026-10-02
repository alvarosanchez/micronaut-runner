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
        assertEquals(List.of("runner-stored-reflection", "runner-stored-preload", "runner-stored-preload-aot",
                        "runner-stored-ordered", "runner-stored-hybrid", "runner-stored-positional", "runner-stored-positional-aot", "runner-stored-joran",
                        "runner-stored-joran-aot", "runner-stored-stripdebug", "runner-stored-stripdebug-aot",
                        "runner-stored-dynamic-services", "runner-stored-dynamic-services-aot",
                        "runner-stored-lambdas", "runner-stored-lambdas-aot", "runner-extracted-lambdas-aot",
                        "runner-stored-prefetch", "runner-stored-prefetch-aot", "shadow-maot-aot", "runner-maot-aot"),
                all.stream().filter(name -> !SampleBuild.variantNames().contains(name)).toList());
        assertEquals(all.indexOf("runner-stored-aot") + 1, all.indexOf("runner-stored-reflection"));
        assertEquals(all.indexOf("runner-extracted-lambdas-aot") + 1, all.indexOf("runner-stored-prefetch"));
        assertEquals(all.indexOf("runner-stored-prefetch-aot") + 1, all.indexOf("runner-preserve"));
        assertEquals(all.indexOf("shadow-maot") + 1, all.indexOf("shadow-maot-aot"));
        assertEquals(all.indexOf("runner-maot") + 1, all.indexOf("runner-maot-aot"));
    }

    @Test
    void theExtractedLambdaControlBuildsTheStoredControlAndItsLayoutOnce() {
        FakeSteps steps = new FakeSteps();

        List<Variant> variants = SampleBuild.variants(steps, List.of("runner-stored-lambdas-aot",
                "runner-extracted-lambdas-aot"), log());

        assertEquals(List.of("runner-stored-lambdas-aot", "runner-extracted-lambdas-aot"), names(variants));
        assertEquals(Map.of("runnerJar:runner-stored-lambdas", 1,
                "aotCache:runner-stored-lambdas->runner-stored-lambdas-aot", 1,
                "extracted:runner-stored-lambdas", 1,
                "aotCache:runner-extracted-lambdas->runner-extracted-lambdas-aot", 1), steps.calls);
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
    void anOptInCachedRowBuildsItsOptInSourceWithThatRowsPackagingOptions() {
        List<SampleBuild.RunnerJarOptions> packaged = new ArrayList<>();
        FakeSteps steps = new FakeSteps() {
            @Override
            public Variant runnerJar(SampleBuild.VariantSpec spec, Compression compression,
                                     SampleBuild.RunnerJarOptions options) {
                packaged.add(options);
                return super.runnerJar(spec, compression, options);
            }
        };

        List<Variant> variants = SampleBuild.variants(steps, List.of("runner-stored-positional-aot"), log());

        assertEquals(List.of("runner-stored-positional-aot"), names(variants));
        assertEquals(Map.of("runnerJar:runner-stored-positional", 1,
                "aotCache:runner-stored-positional->runner-stored-positional-aot", 1), steps.calls);
        assertEquals(List.of(SampleBuild.RunnerJarOptions.DEFAULTS.withArchiveReads("POSITIONAL")),
                packaged);

        packaged.clear();
        steps.calls.clear();
        assertEquals(List.of("runner-stored-stripdebug-aot"),
                names(SampleBuild.variants(steps, List.of("runner-stored-stripdebug-aot"), log())));
        assertEquals(Map.of("runnerJar:runner-stored-stripdebug", 1,
                "aotCache:runner-stored-stripdebug->runner-stored-stripdebug-aot", 1), steps.calls);
        assertEquals(List.of(SampleBuild.RunnerJarOptions.DEFAULTS.withStripLocalVariables(true)), packaged);
    }

    @Test
    void theJoranControlIsBuiltFromTheStoredRowWhichIsNotReportedUnlessNamed() {
        FakeSteps steps = new FakeSteps();

        List<Variant> variants = SampleBuild.variants(steps, List.of("runner-stored-joran-aot"), log());

        assertEquals(List.of("runner-stored-joran-aot"), names(variants));
        assertEquals(Map.of("runnerJar:runner-stored", 1, "joranControl:runner-stored", 1,
                "aotCache:runner-stored-joran->runner-stored-joran-aot", 1), steps.calls);
    }

    @Test
    void thePrefetchRowsAreBuiltFromTheStoredRowWhichIsNotReportedUnlessNamed() {
        FakeSteps steps = new FakeSteps();

        List<Variant> variants = SampleBuild.variants(steps, List.of("runner-stored-prefetch-aot"), log());

        assertEquals(List.of("runner-stored-prefetch-aot"), names(variants));
        assertEquals(Map.of("runnerJar:runner-stored", 1, "prefetchCandidate:runner-stored", 1,
                "aotCache:runner-stored-prefetch->runner-stored-prefetch-aot", 1), steps.calls);
    }

    @Test
    void thePreloadRowsRecordFromTheStoredRowWhichIsNotReportedUnlessNamed() {
        FakeSteps steps = new FakeSteps();

        List<Variant> variants = SampleBuild.variants(steps, List.of("runner-stored-preload-aot"), log());

        assertEquals(List.of("runner-stored-preload-aot"), names(variants));
        assertEquals(Map.of("runnerJar:runner-stored", 1, "preloadingRunnerJar:runner-stored", 1,
                "aotCache:runner-stored-preload->runner-stored-preload-aot", 1), steps.calls);

        steps.calls.clear();
        assertEquals(List.of("runner-stored", "runner-stored-preload"),
                names(SampleBuild.variants(steps, List.of("runner-stored-preload", "runner-stored"), log())));
        assertEquals(Map.of("runnerJar:runner-stored", 1, "preloadingRunnerJar:runner-stored", 1), steps.calls);
    }

    @Test
    void theOrderedAndHybridRowsAreOptInAndBuiltFromTheStoredRowAloneWhenNamed(@TempDir Path output) {
        for (String name : List.of("runner-stored-ordered", "runner-stored-hybrid")) {
            SampleBuild.VariantSpec spec = SampleBuild.spec(name);
            assertTrue(spec.optIn(), name);
            assertEquals(EntryMode.STUB, spec.entryMode(), name);
            assertFalse(spec.aotCache(), name);
            assertFalse(SampleBuild.variantNames().contains(name), name + " is not a core row");
            assertFalse(StartupBenchmark.Options.parse(arguments(output)).selection().contains(name),
                    name + " is built only on request");
            assertTrue(StartupBenchmark.Options.parse(arguments(output, "--optional-rows")).selection()
                    .contains(name), name + " is built with the opt-in rows");
        }
        StartupBenchmark.Options hybridAlone = StartupBenchmark.Options.parse(
                arguments(output, "--variants", "runner-stored-hybrid"));
        assertEquals(List.of(), hybridAlone.requiredVariants(), "an opt-in row never gates");
        FakeSteps steps = new FakeSteps();

        List<Variant> variants = SampleBuild.variants(steps, hybridAlone.selection(), log());

        assertEquals(List.of("runner-stored-hybrid"), names(variants));
        assertEquals(Map.of("runnerJar:runner-stored", 1, "hybridRunnerJar:runner-stored", 1), steps.calls);

        steps.calls.clear();
        assertEquals(List.of("runner-stored-preload", "runner-stored-ordered", "runner-stored-hybrid"),
                names(SampleBuild.variants(steps, List.of("runner-stored-hybrid", "runner-stored-ordered",
                        "runner-stored-preload"), log())));
        assertEquals(Map.of("runnerJar:runner-stored", 1, "preloadingRunnerJar:runner-stored", 1,
                "orderedRunnerJar:runner-stored", 1, "hybridRunnerJar:runner-stored", 1), steps.calls,
                "the three rows that package the recording share one runner-stored jar to record it from");
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
                "extracted:runner-stored", "aotCache:runner-extracted->runner-extracted-aot", "shadowMaot",
                "runnerMaot"),
                List.copyOf(steps.calls.keySet()));
    }

    /**
     * The table hands every cache of a Runner single JAR a source that trains in the launcher's AOT training mode,
     * and none of a Shadow JAR or an extracted layout, whose classes the JDK's own loaders load.
     */
    @Test
    void onlyTheCachesOfRunnerSingleJarsTrainInTheLaunchersTrainingMode() {
        FakeSteps steps = new FakeSteps();
        List<String> cached = SampleBuild.allVariantNames().stream()
                .filter(name -> SampleBuild.spec(name).aotCache()).toList();

        SampleBuild.variants(steps, cached, log());

        Map<String, List<String>> expected = new LinkedHashMap<>();
        for (String name : cached) {
            boolean runnerSingleJar = name.startsWith("runner-") && !name.startsWith("runner-extracted");
            expected.put(name, runnerSingleJar ? List.of("-Dmicronaut.runner.aot.training=true") : List.of());
        }
        assertEquals(expected, steps.trainingArguments);
        assertEquals(List.of("shadow-aot", "runner-extracted-lambdas-aot", "runner-extracted-aot", "shadow-maot-aot"),
                expected.entrySet().stream().filter(entry -> entry.getValue().isEmpty()).map(Map.Entry::getKey)
                        .toList());
    }

    @Test
    void aCachedMicronautAotRowBuildsItsUncachedTwinWithoutReportingIt() {
        FakeSteps steps = new FakeSteps();

        List<Variant> variants = SampleBuild.variants(steps, List.of("shadow-maot-aot", "runner-maot-aot"), log());

        assertEquals(List.of("shadow-maot-aot", "runner-maot-aot"), names(variants));
        assertEquals(List.of("shadowMaot", "aotCache:shadow-maot->shadow-maot-aot", "runnerMaot",
                "aotCache:runner-maot->runner-maot-aot"), List.copyOf(steps.calls.keySet()));
    }

    @Test
    void aFailedPrerequisiteMakesTheDerivedRowUnavailableAndIsNotRetried() {
        FakeSteps steps = new FakeSteps() {
            @Override
            public Variant runnerJar(SampleBuild.VariantSpec spec, Compression compression,
                                     SampleBuild.RunnerJarOptions options) {
                note("runnerJar:" + spec.name());
                throw new IllegalStateException("no runner jar for " + spec.name());
            }

            @Override
            public Variant aotCache(Variant source, SampleBuild.VariantSpec spec) throws IOException {
                if (!source.available()) {
                    note("aotCache:" + source.name() + "->" + spec.name());
                    throw new IOException("cannot train AOT cache because " + source.name() + " is unavailable");
                }
                return super.aotCache(source, spec);
            }

            @Override
            public Variant extracted(Variant stored, SampleBuild.VariantSpec spec) {
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

    /** A row's source comes from its spec alone, and each row is built once however many rows derive from it. */
    @Test
    void theRowHelperMemoizesFakeFactories() {
        Map<String, Integer> built = new LinkedHashMap<>();
        VariantRows.Factory<Map<String, Integer>> factory = (calls, spec, source) -> {
            assertTrue(spec.source() == null ? source == null : source.name().equals(spec.source()), spec.name());
            calls.merge(spec.name(), 1, Integer::sum);
            return BenchmarkFixtures.variant(spec.name());
        };
        List<VariantRows.Row<Map<String, Integer>>> table = List.of(
                new VariantRows.Row<>(new SampleBuild.VariantSpec("base", "base", EntryMode.STUB, false, false, null),
                        factory),
                new VariantRows.Row<>(new SampleBuild.VariantSpec("left", "left", EntryMode.STUB, false, false,
                        "base"), factory),
                new VariantRows.Row<>(new SampleBuild.VariantSpec("right", "right", EntryMode.STUB, true, true,
                        "left"), factory));
        VariantRows.Attempt attempt = (spec, create) -> {
            try {
                return create.create();
            } catch (Exception e) {
                return Variant.unavailable(spec, e.getMessage());
            }
        };

        assertEquals(List.of("left", "right"), names(VariantRows.build(table, built, List.of("right", "left"),
                attempt)));
        assertEquals(Map.of("base", 1, "left", 1, "right", 1), built);
        assertThrows(IllegalArgumentException.class, () -> VariantRows.build(table, built, List.of("missing"),
                attempt));
    }

    @Test
    void comparisonsNeedBothSelectedVariants(@TempDir Path output) throws Exception {
        List<String> selection = List.of("shadow", "runner-stored", "runner-extracted-aot");
        Reports.write(output, BenchmarkFixtures.context(output, 1, selection, CompletenessPolicy.REQUIRED,
                BenchmarkProvenance.unavailable()), SampleBuild.unavailableVariants("not built", selection).stream()
                .map(BenchmarkFixtures::result).toList(), List.of());

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


    /** Records every build step instead of building anything. */
    private static class FakeSteps implements SampleSteps {

        final Map<String, Integer> calls = new LinkedHashMap<>();

        /** The training-only arguments of each cached row's source. */
        final Map<String, List<String>> trainingArguments = new LinkedHashMap<>();

        void note(String call) {
            calls.merge(call, 1, Integer::sum);
        }

        int calls(String call) {
            return calls.getOrDefault(call, 0);
        }

        private Variant variant(String call, SampleBuild.VariantSpec spec) {
            note(call);
            return Variant.available(spec, List.of("java", "-jar", spec.name() + ".jar"), Path.of("."),
                    Path.of(spec.name() + ".jar"), null, spec.entryMode(), null, List.of());
        }

        @Override
        public Variant explodedClasspath(SampleBuild.VariantSpec spec) {
            return variant("explodedClasspath", spec);
        }

        @Override
        public Variant thinJar(SampleBuild.VariantSpec spec) {
            return variant("thinJar", spec);
        }

        @Override
        public Variant shadow(SampleBuild.VariantSpec spec) {
            return variant("shadow", spec);
        }

        @Override
        public Variant shadowStored(SampleBuild.VariantSpec spec) {
            return variant("shadowStored", spec);
        }

        @Override
        public Variant shadowMaot(SampleBuild.VariantSpec spec) {
            return variant("shadowMaot", spec);
        }

        @Override
        public Variant runnerMaot(SampleBuild.VariantSpec spec) {
            return variant("runnerMaot", spec);
        }

        @Override
        public Variant runnerJar(SampleBuild.VariantSpec spec, Compression compression,
                                 SampleBuild.RunnerJarOptions options) {
            return variant("runnerJar:" + spec.name(), spec);
        }

        @Override
        public Variant aotCache(Variant source, SampleBuild.VariantSpec spec) throws IOException {
            trainingArguments.put(spec.name(), AotCache.trainingArguments(source));
            return variant("aotCache:" + source.name() + "->" + spec.name(), spec);
        }

        @Override
        public Variant preloadingRunnerJar(Variant stored, SampleBuild.VariantSpec spec) {
            return variant("preloadingRunnerJar:" + stored.name(), spec);
        }

        @Override
        public Variant orderedRunnerJar(Variant stored, SampleBuild.VariantSpec spec) {
            return variant("orderedRunnerJar:" + stored.name(), spec);
        }

        @Override
        public Variant hybridRunnerJar(Variant stored, SampleBuild.VariantSpec spec) {
            return variant("hybridRunnerJar:" + stored.name(), spec);
        }

        @Override
        public Variant joranControl(Variant stored, SampleBuild.VariantSpec spec) {
            return variant("joranControl:" + stored.name(), spec);
        }

        @Override
        public Variant prefetchCandidate(Variant stored, SampleBuild.VariantSpec spec) {
            return variant("prefetchCandidate:" + stored.name(), spec);
        }

        @Override
        public Variant extracted(Variant stored, SampleBuild.VariantSpec spec) {
            return variant("extracted:" + stored.name(), spec);
        }
    }

}
