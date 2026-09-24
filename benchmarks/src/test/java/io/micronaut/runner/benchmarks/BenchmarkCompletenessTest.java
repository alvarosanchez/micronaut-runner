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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkCompletenessTest {

    @Test
    void commandLineDefaultsToRequiredAndNeedsAnExplicitPartialFlag(@TempDir Path output) {
        String[] required = {"--sample", output.toString(), "--repo", "file:/repo", "--version", "1.0",
                "--iterations", "2", "--out", output.resolve("required").toString()};
        String[] partial = {"--sample", output.toString(), "--repo", "file:/repo", "--version", "1.0",
                "--iterations", "2", "--out", output.resolve("partial").toString(), "--allow-partial"};

        assertEquals(CompletenessPolicy.REQUIRED, StartupBenchmark.Options.parse(required).completenessPolicy());
        assertEquals(CompletenessPolicy.PARTIAL, StartupBenchmark.Options.parse(partial).completenessPolicy());
    }

    @Test
    void workDirectoryDefaultsUnderTheOutputDirectoryAndCanBeMovedOutOfIt(@TempDir Path output) {
        Path out = output.resolve("reports");
        Path work = output.resolve("work");
        String[] defaulted = {"--sample", output.toString(), "--repo", "file:/repo", "--version", "1.0",
                "--iterations", "2", "--out", out.toString()};
        String[] explicit = {"--sample", output.toString(), "--repo", "file:/repo", "--version", "1.0",
                "--iterations", "2", "--out", out.toString(), "--work", work.toString()};

        assertEquals(out.resolve("artifacts"), StartupBenchmark.Options.parse(defaulted).workDirectory());
        assertEquals(work, StartupBenchmark.Options.parse(explicit).workDirectory());
        assertEquals(out, StartupBenchmark.Options.parse(explicit).outputDirectory());
    }

    @Test
    void failureTextKeepsVariantPathsUnderTheHomeDirectoryTokenised(@TempDir Path output) throws Exception {
        Path fixture = Path.of(System.getProperty("user.home")).resolve("runner-redaction-fixture");
        Path work = fixture.resolve("work");
        Path jar = fixture.resolve("lib").resolve("runner.jar");
        List<Variant> variants = List.of(
                Variant.available("a", "fixture a", List.of("java"), work, jar, List.of(jar)),
                available("b"));
        StartupBenchmark.Options options = options(output, 1, 0, CompletenessPolicy.PARTIAL);
        List<VariantResult> results = StartupBenchmark.measure(scriptedRunner((variant, iteration, warmup) -> {
            if (variant.name().equals("a")) {
                throw new StartupHarness.RunFailure("cannot open " + jar + " from " + work, null);
            }
            return sample(iteration, warmup);
        }), variants, options, log());

        Reports.write(output, context(output, options), results, List.of());
        String json = Files.readString(output.resolve(Reports.RESULTS_FILE));
        assertTrue(json.contains("\"failureReason\": \"cannot open ${input:0} from ${workdir}\""), json);
    }

    @Test
    void mixedMeasuredSuccessFailsRequiredMatrixAndRecordsEveryAttempt(@TempDir Path output) throws Exception {
        StartupBenchmark.Options options = options(output, 3, 0, CompletenessPolicy.REQUIRED);
        List<VariantResult> results = StartupBenchmark.measure(scriptedRunner((variant, iteration, warmup) -> {
            if (variant.name().equals("a") && iteration > 0) {
                throw new StartupHarness.RunFailure("timed out", null);
            }
            return sample(iteration, warmup);
        }), variants(), options, log());
        RunContext context = context(output, options);
        BenchmarkStatus status = BenchmarkStatus.evaluate(context, results);

        assertFalse(status.complete());
        assertEquals(1, status.exitCode());
        assertEquals(3, results.get(0).measured().attempted());
        assertEquals(1, results.get(0).measured().successful());
        assertEquals(2, results.get(0).measured().failed());
        assertEquals(3, results.get(1).measured().successful());
        assertEquals(List.of(0, 1, 2, 3, 4, 5), results.stream()
                .flatMap(result -> result.attempts().stream())
                .map(RunAttempt::globalOrder)
                .sorted()
                .toList());

        Reports.write(output, context, results, List.of());
        String json = Files.readString(output.resolve(Reports.RESULTS_FILE));
        assertTrue(json.contains("\"completenessPolicy\": \"required\""));
        assertTrue(json.contains("\"complete\": false"));
        assertTrue(json.contains("\"measured\": {\"requested\": 3, \"attempted\": 3, \"successful\": 1, \"failed\": 2, \"skipped\": 0}"));
        assertTrue(json.contains("\"globalOrder\": 5"));
        assertTrue(json.contains("\"outcome\": \"failed\""));
        assertTrue(json.contains("\"failureReason\": \"timed out\""));
        assertTrue(json.contains("\"exitCode\": null"));

        String markdown = Files.readString(output.resolve(Reports.SUMMARY_FILE));
        assertTrue(markdown.contains("**INCOMPLETE required comparison**"));
        assertTrue(markdown.contains("| `a` | measured | 3 | 3 | 1 | 2 | 0 |"));
        assertFalse(markdown.contains("variants were built and measured"));
    }

    @Test
    void explicitPartialPolicyKeepsIncompleteReportButAllowsUsefulSamples(@TempDir Path output) throws Exception {
        StartupBenchmark.Options options = options(output, 2, 0, CompletenessPolicy.PARTIAL);
        List<VariantResult> results = StartupBenchmark.measure(scriptedRunner((variant, iteration, warmup) -> {
            if (variant.name().equals("a")) {
                throw new StartupHarness.RunFailure("fixture failure", 17);
            }
            return sample(iteration, warmup);
        }), variants(), options, log());
        RunContext context = context(output, options);
        BenchmarkStatus status = BenchmarkStatus.evaluate(context, results);

        assertFalse(status.complete());
        assertEquals(0, status.exitCode());
        Reports.write(output, context, results, List.of());
        String markdown = Files.readString(output.resolve(Reports.SUMMARY_FILE));
        assertTrue(markdown.contains("**INCOMPLETE exploratory comparison**"));
        assertTrue(markdown.contains("Partial policy permits exit 0 because at least one measured run succeeded"));
        assertFalse(markdown.contains("All 2 variants were built and measured"));
    }

    @Test
    void allMeasuredRunsFailEvenInPartialMode(@TempDir Path output) throws Exception {
        StartupBenchmark.Options options = options(output, 1, 0, CompletenessPolicy.PARTIAL);
        List<VariantResult> results = StartupBenchmark.measure(scriptedRunner((variant, iteration, warmup) -> {
            throw new StartupHarness.RunFailure("never ready", 9);
        }), variants(), options, log());

        BenchmarkStatus status = BenchmarkStatus.evaluate(context(output, options), results);

        assertFalse(status.complete());
        assertEquals(1, status.exitCode());
        assertEquals(0, results.stream().mapToInt(result -> result.measured().successful()).sum());
    }

    @Test
    void unavailableVariantIsSkippedRatherThanMisreportedAsAttempted(@TempDir Path output) throws Exception {
        StartupBenchmark.Options options = options(output, 2, 1, CompletenessPolicy.REQUIRED);
        List<Variant> variants = List.of(
                available("a"),
                Variant.unavailable("b", "fixture b", "build failed"));
        List<VariantResult> results = StartupBenchmark.measure(scriptedRunner((variant, iteration, warmup) ->
                sample(iteration, warmup)), variants, options, log());

        VariantResult unavailable = results.get(1);
        assertEquals(0, unavailable.warmup().attempted());
        assertEquals(1, unavailable.warmup().skipped());
        assertEquals(0, unavailable.measured().attempted());
        assertEquals(2, unavailable.measured().skipped());
        assertTrue(unavailable.attempts().isEmpty());
        assertEquals(1, BenchmarkStatus.evaluate(context(output, options), results).exitCode());
    }

    @Test
    void sharedBuildFailurePreservesEveryRequiredVariantAsUnavailable(@TempDir Path output) throws Exception {
        List<Variant> variants = SampleBuild.unavailableVariants("sample build failed");
        StartupBenchmark.Options options = new StartupBenchmark.Options(output, "file:/repo", "1.0", output,
                output.resolve("artifacts"), 2, 1, 1234L, "/hello", Duration.ofSeconds(1), false,
                CompletenessPolicy.REQUIRED);
        List<VariantResult> results = StartupBenchmark.measure(scriptedRunner((variant, iteration, warmup) -> {
            throw new AssertionError("an unavailable variant must not reach the runner");
        }), variants, options, log());
        RunContext context = new RunContext(output, "file:/repo", "1.0", output,
                2, 1, 1234L, "/hello", false, "2026-09-22T00:00:00Z",
                SampleBuild.variantNames(), CompletenessPolicy.REQUIRED);

        assertEquals(SampleBuild.variantNames(), variants.stream().map(Variant::name).toList());
        assertTrue(results.stream().allMatch(result -> !result.variant().available()));
        assertTrue(results.stream().allMatch(result -> result.measured().skipped() == 2));
        assertEquals(1, BenchmarkStatus.evaluate(context, results).exitCode());
        Reports.write(output, context, results, List.of());
        String markdown = Files.readString(output.resolve(Reports.SUMMARY_FILE));
        for (String variant : SampleBuild.variantNames()) {
            assertTrue(markdown.contains("| `" + variant + "` | could not be built | sample build failed |"));
        }
    }

    @Test
    void warmupFailureIsVisibleButDoesNotInvalidateCompleteMeasuredMatrix(@TempDir Path output) throws Exception {
        StartupBenchmark.Options options = options(output, 2, 1, CompletenessPolicy.REQUIRED);
        List<VariantResult> results = StartupBenchmark.measure(scriptedRunner((variant, iteration, warmup) -> {
            if (variant.name().equals("a") && warmup) {
                throw new StartupHarness.RunFailure("warm-up failed", null);
            }
            return sample(iteration, warmup);
        }), variants(), options, log());

        BenchmarkStatus status = BenchmarkStatus.evaluate(context(output, options), results);

        assertTrue(status.complete());
        assertEquals(0, status.exitCode());
        assertEquals(1, results.get(0).warmup().failed());
        assertEquals(2, results.get(0).measured().successful());
    }

    @Test
    void fullySuccessfulRequiredMatrixIsComplete(@TempDir Path output) throws Exception {
        StartupBenchmark.Options options = options(output, 2, 1, CompletenessPolicy.REQUIRED);
        List<VariantResult> results = StartupBenchmark.measure(scriptedRunner((variant, iteration, warmup) ->
                sample(iteration, warmup)), variants(), options, log());

        BenchmarkStatus status = BenchmarkStatus.evaluate(context(output, options), results);

        assertTrue(status.complete());
        assertEquals(0, status.exitCode());
        assertEquals(6, results.stream().mapToInt(result -> result.attempts().size()).sum());
    }

    @Test
    void scheduleIsDeterministicForTheSeedAndRecordsActualOrder(@TempDir Path output) throws Exception {
        List<Variant> variants = List.of(available("a"), available("b"), available("c"));
        StartupBenchmark.Options first = options(output, 5, 1, 1234L);
        StartupBenchmark.Options same = options(output, 5, 1, 1234L);
        StartupBenchmark.Options different = options(output, 5, 1, 5678L);
        StartupRunner runner = scriptedRunner((variant, iteration, warmup) -> sample(iteration, warmup));

        List<String> firstOrder = schedule(StartupBenchmark.measure(runner, variants, first, log()));
        List<String> sameOrder = schedule(StartupBenchmark.measure(runner, variants, same, log()));
        List<String> differentOrder = schedule(StartupBenchmark.measure(runner, variants, different, log()));

        assertEquals(firstOrder, sameOrder);
        assertFalse(firstOrder.equals(differentOrder));
        assertEquals(18, firstOrder.size());
    }

    @Test
    void finishReturnsFailureAfterSavingBothReports(@TempDir Path output) throws Exception {
        StartupBenchmark.Options options = options(output, 2, 0, CompletenessPolicy.REQUIRED);
        List<VariantResult> results = StartupBenchmark.measure(scriptedRunner((variant, iteration, warmup) -> {
            if (variant.name().equals("a") && iteration == 1) {
                throw new StartupHarness.RunFailure("fixture timeout", null);
            }
            return sample(iteration, warmup);
        }), variants(), options, log());

        int exit = StartupBenchmark.finish(context(output, options), results, List.of(), log());

        assertEquals(1, exit);
        assertTrue(Files.isRegularFile(output.resolve(Reports.RESULTS_FILE)));
        assertTrue(Files.isRegularFile(output.resolve(Reports.SUMMARY_FILE)));
        assertTrue(Files.readString(output.resolve(Reports.RESULTS_FILE)).contains("\"complete\": false"));
    }

    private static RunContext context(Path output, StartupBenchmark.Options options) {
        return new RunContext(output, "file:/repo", "1.0", output,
                options.iterations(), options.warmupIterations(), options.seed(), options.readinessPath(),
                options.diagnostics(), "2026-09-22T00:00:00Z", List.of("a", "b"), options.completenessPolicy());
    }

    private static StartupBenchmark.Options options(Path output,
                                                    int iterations,
                                                    int warmup,
                                                    CompletenessPolicy policy) throws IOException {
        Path sample = output.resolve("sample");
        Files.createDirectories(sample);
        return new StartupBenchmark.Options(sample, "file:/repo", "1.0", output, output.resolve("artifacts"),
                iterations, warmup, 1234L, "/hello", Duration.ofSeconds(1), false, policy);
    }

    private static StartupBenchmark.Options options(Path output,
                                                    int iterations,
                                                    int warmup,
                                                    long seed) throws IOException {
        Path sample = output.resolve("sample");
        Files.createDirectories(sample);
        return new StartupBenchmark.Options(sample, "file:/repo", "1.0", output, output.resolve("artifacts"),
                iterations, warmup, seed, "/hello", Duration.ofSeconds(1), false, CompletenessPolicy.REQUIRED);
    }

    private static List<String> schedule(List<VariantResult> results) {
        return results.stream()
                .flatMap(result -> result.attempts().stream())
                .sorted(java.util.Comparator.comparingInt(RunAttempt::globalOrder))
                .map(attempt -> attempt.globalOrder() + ":" + attempt.iteration() + ":" + attempt.variant())
                .toList();
    }

    private static List<Variant> variants() {
        return List.of(available("a"), available("b"));
    }

    private static Variant available(String name) {
        return Variant.available(name, "fixture " + name, List.of("java"), Path.of("."), Path.of("."));
    }

    private static StartupSample sample(int iteration, boolean warmup) {
        return new StartupSample(iteration, warmup, 8080, 10 + iteration, 9 + iteration,
                8 + iteration, 0.1, 143, ReadinessSnapshot.UNAVAILABLE);
    }

    private static StartupRunner scriptedRunner(Script script) {
        return script::run;
    }

    private static PrintStream log() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface Script {
        StartupSample run(Variant variant, int iteration, boolean warmup) throws IOException;
    }
}
