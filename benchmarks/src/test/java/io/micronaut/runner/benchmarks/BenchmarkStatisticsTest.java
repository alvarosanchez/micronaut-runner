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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkStatisticsTest {

    @Test
    void oneSampleReportIsDescriptiveOnly(@TempDir Path output) throws Exception {
        Statistics statistics = Statistics.of(new double[] {100.0}, 123L);

        assertEquals(1, statistics.count());
        assertEquals(100.0, statistics.median());
        assertEquals(100.0, statistics.p90());
        assertNull(statistics.ciLow());
        assertNull(statistics.ciHigh());

        Path sample = Files.createDirectory(output.resolve("sample"));
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output,
                1, 0, 123L, "/hello", false, "2026-09-22T00:00:00Z");
        Variant variant = Variant.available("a", "fixture a", List.of("java"), Path.of("."), Path.of("."));
        StartupSample startupSample = new StartupSample(0, false, 8080, 100, -1, -1, 0.1, 143,
                ReadinessSnapshot.UNAVAILABLE);
        VariantResult result = new VariantResult(variant, 1, List.of(startupSample),
                statistics, null, null, List.of());

        Reports.write(output, context, List.of(result), List.of());

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"count\": 1"));
        assertTrue(json.contains("\"median\": 100.000"));
        assertTrue(json.contains("\"ci95Low\": null"));
        assertTrue(json.contains("\"ci95High\": null"));
        assertTrue(json.contains("\"descriptiveOnly\": true"));

        String markdown = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("| `a` | 1 | **100.0 ms** | 100.0 ms | n=1 (<10) |"), markdown);
        assertFalse(markdown.contains("100.0 ms – 100.0 ms"));
        assertTrue(markdown.contains("## Runner vs Shadow\n\nNo declared comparison applies"), markdown);
    }

    @Test
    void readinessMediansIgnoreUnavailableValuesAndAreWrittenAsNull(@TempDir Path output) throws Exception {
        Variant variant = Variant.available("a", "fixture a", List.of("java"), Path.of("."), Path.of("."));
        long[] rss = {100, -1, 300};
        List<RunAttempt> attempts = new ArrayList<>();
        for (int iteration = 0; iteration < rss.length; iteration++) {
            ReadinessSnapshot snapshot = new ReadinessSnapshot(10 + iteration, rss[iteration],
                    -1, -1, -1, -1, -1, -1, -1);
            attempts.add(RunAttempt.success("a", iteration, iteration, new StartupSample(iteration, false,
                    8080 + iteration, 100 + iteration, -1, -1, 0.1, 143, snapshot)));
        }

        VariantResult result = VariantResult.summarize(variant, 1, attempts, 0, rss.length, 123L);

        assertEquals(200, result.atReadiness().rssBytes());
        assertEquals(11.0, result.atReadiness().probeMillis());
        assertEquals(-1, result.atReadiness().loadedClasses());
        assertEquals(-1, result.atReadiness().sharedClasses());

        Path sample = Files.createDirectory(output.resolve("sample"));
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output,
                3, 0, 123L, "/hello", false, "2026-09-22T00:00:00Z",
                List.of("a"), CompletenessPolicy.REQUIRED);
        Reports.write(output, context, List.of(result), List.of());

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"atReadiness\": {\"probeMillis\": 11.000, \"rssBytes\": 200,"
                + " \"peakRssBytes\": null, \"anonBytes\": null, \"fileBytes\": null, \"footprintBytes\": null,"
                + " \"peakFootprintBytes\": null, \"loadedClasses\": null, \"sharedClasses\": null}"), json);
        assertTrue(json.contains("\"atReadiness\": {\"probeMillis\": 11.000, \"rssBytes\": null,"), json);
        assertTrue(json.contains("\"atReadiness\": {\"probeMillis\": 10.000, \"rssBytes\": 100,"), json);
        String attemptsJson = json.substring(json.indexOf("\"attempts\": ["), json.indexOf("\"diagnostics\": {"));
        assertEquals(3, occurrences(attemptsJson, "\"atReadiness\": {"), attemptsJson);

        String markdown = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        String memory = section(markdown, "## Memory and classes at readiness (not timed)");
        assertTrue(memory.contains("| `a` | 3 | 0.0 MiB | — | — | — | — |"), memory);
        assertTrue(memory.contains("a median of 11.0 ms after readiness"), memory);
        assertTrue(memory.contains("RSS includes clean, file-backed pages of memory-mapped files"), memory);
        assertTrue(markdown.indexOf("## Successful measured runs only")
                < markdown.indexOf("## Memory and classes at readiness (not timed)"), markdown);
    }

    @Test
    void runnerVersusShadowHasAMemoryRowForEveryTimingRow(@TempDir Path output) throws Exception {
        long mebibyte = 1024 * 1024;
        // Linux-shaped snapshots: RssAnon is the private memory and VmHWM the peak, whatever OS runs the test.
        VariantResult stored = withSnapshot("runner-stored",
                new ReadinessSnapshot(5, 180 * mebibyte, 190 * mebibyte, 120 * mebibyte, 60 * mebibyte,
                        -1, -1, 6200, 1280));
        VariantResult shadow = withSnapshot("shadow",
                new ReadinessSnapshot(5, 178 * mebibyte, 185 * mebibyte, 150 * mebibyte, 28 * mebibyte,
                        -1, -1, 6100, 1270));
        VariantResult preserve = withSnapshot("runner-preserve", ReadinessSnapshot.UNAVAILABLE);

        Path sample = Files.createDirectory(output.resolve("sample"));
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output,
                1, 0, 123L, "/hello", false, "2026-09-22T00:00:00Z",
                List.of("runner-stored", "shadow", "runner-preserve"), CompletenessPolicy.PARTIAL);
        Reports.write(output, context, List.of(stored, shadow, preserve), List.of());

        String markdown = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        String section = section(markdown, "## Runner vs Shadow");
        assertTrue(section.contains("| Runner default vs Shadow: `runner-stored` − `shadow` | +2.0 MiB | -30.0 MiB"
                + " | +100 |"), section);
        assertTrue(section.contains("| Runner PRESERVE vs Shadow: `runner-preserve` − `shadow` | — | — | — |"),
                section);
        assertTrue(section.contains("| STORED vs PRESERVE: `runner-stored` − `runner-preserve` | — | — | — |"),
                section);
        assertTrue(section.contains("not paired estimates, and carry no interval"), section);
        for (String row : List.of("| Runner default vs Shadow: `runner-stored` − `shadow` |",
                "| Runner PRESERVE vs Shadow: `runner-preserve` − `shadow` |",
                "| STORED vs PRESERVE: `runner-stored` − `runner-preserve` |")) {
            assertEquals(2, occurrences(section, row), "one timing and one memory row: " + row);
        }

        String memory = section(markdown, "## Memory and classes at readiness (not timed)");
        assertTrue(memory.contains("| `runner-stored` | 1 | 180.0 MiB | 120.0 MiB | 190.0 MiB | 6200 | 1280 |"),
                memory);
        assertTrue(memory.contains("| `runner-preserve` | 1 | — | — | — | — | — |"), memory);

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"loadedClasses\": 6200, \"sharedClasses\": 1280}"), json);
    }

    @Test
    void tinyEqualSamplesDoNotManufacturePrecision() {
        Statistics statistics = Statistics.of(new double[] {100.0, 100.0, 100.0}, 123L);

        assertEquals(3, statistics.count());
        assertEquals(100.0, statistics.median());
        assertEquals(100.0, statistics.p90());
        assertNull(statistics.ciLow());
        assertNull(statistics.ciHigh());
        assertTrue(statistics.ciReason().contains("observed 3"));
    }

    @Test
    void ordinaryPercentilesAndSeededBootstrapStayDeterministic() {
        double[] values = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        Statistics first = Statistics.of(values, 9876L);
        Statistics second = Statistics.of(values, 9876L);

        assertEquals(5.5, first.median());
        assertEquals(9.1, first.p90());
        assertEquals(first.ciLow(), second.ciLow());
        assertEquals(first.ciHigh(), second.ciHigh());
        assertTrue(first.hasConfidenceInterval());
        assertNull(first.ciReason());
    }

    @Test
    void pairedDifferencesKeepIterationIdentity() {
        VariantResult candidate = result("a", new double[] {100, 200, 300, 400}, -1);
        VariantResult baseline = result("b", new double[] {90, 180, 270, 360}, -1);

        PairedComparison comparison = PairedComparison.of(candidate, baseline, 4, 123L);

        assertEquals("a", comparison.candidateVariant());
        assertEquals("b", comparison.baselineVariant());
        assertEquals(4, comparison.pairedCount());
        assertEquals(0, comparison.excludedCount());
        assertEquals(List.of(0, 1, 2, 3), comparison.pairs().stream()
                .map(PairedComparison.Pair::iteration)
                .toList());
        assertEquals(List.of(100.0, 200.0, 300.0, 400.0), comparison.pairs().stream()
                .map(PairedComparison.Pair::candidateMillis)
                .toList());
        assertEquals(List.of(10.0, 20.0, 30.0, 40.0), comparison.pairs().stream()
                .map(PairedComparison.Pair::differenceMillis)
                .toList());
        assertEquals(25.0, comparison.medianDifferenceMillis());
        assertTrue(comparison.descriptiveOnly());
    }

    @Test
    void negativeDifferenceAndRelativeChangeMeanTheCandidateIsFaster() {
        VariantResult candidate = result("runner-stored", new double[] {90, 180, 270, 360}, -1);
        VariantResult baseline = result("shadow", new double[] {100, 200, 300, 400}, -1);

        PairedComparison comparison = PairedComparison.of(candidate, baseline, 4, 123L);

        assertEquals(4, comparison.pairedCount());
        assertEquals(-25.0, comparison.medianDifferenceMillis());
        assertEquals(-0.10, comparison.relativeChange(), 1e-9);
        assertTrue(comparison.descriptiveOnly());
        assertNull(comparison.relativeCiLow());
        assertNull(comparison.relativeCiHigh());

        VariantResult failedBaseline = result("shadow", new double[] {100, 200, 300, 400}, 1);
        PairedComparison excluded = PairedComparison.of(candidate, failedBaseline, 4, 123L);

        assertEquals(List.of(0, 2, 3), excluded.pairs().stream()
                .map(PairedComparison.Pair::iteration)
                .toList());
        assertEquals(-30.0, excluded.medianDifferenceMillis());
        assertEquals(-0.10, excluded.relativeChange(), 1e-9);
        assertEquals(List.of(new PairedComparison.Exclusion(1, "success", "failed")), excluded.exclusions());
    }

    @Test
    void failedAttemptExcludesItsIterationWithoutRepairingThePair(@TempDir Path output) throws Exception {
        VariantResult candidate = result("runner-stored", new double[] {100, 200, 300, 400}, -1);
        VariantResult baseline = result("shadow", new double[] {90, 180, 270, 360}, 1);

        PairedComparison comparison = PairedComparison.of(candidate, baseline, 4, 123L);

        assertEquals(3, comparison.pairedCount());
        assertEquals(1, comparison.excludedCount());
        assertEquals(List.of(0, 2, 3), comparison.pairs().stream()
                .map(PairedComparison.Pair::iteration)
                .toList());
        assertEquals(List.of(10.0, 30.0, 40.0), comparison.pairs().stream()
                .map(PairedComparison.Pair::differenceMillis)
                .toList());
        assertEquals(30.0, comparison.medianDifferenceMillis());
        assertEquals(1, comparison.exclusions().getFirst().iteration());
        assertEquals("success", comparison.exclusions().getFirst().candidateOutcome());
        assertEquals("failed", comparison.exclusions().getFirst().baselineOutcome());

        Path sample = Files.createDirectory(output.resolve("sample"));
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output,
                4, 0, 123L, "/hello", false, "2026-09-22T00:00:00Z",
                List.of("runner-stored", "shadow"), CompletenessPolicy.PARTIAL);
        Reports.write(output, context, List.of(candidate, baseline), List.of());

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"comparisons\""));
        assertTrue(json.contains("\"label\": \"Runner default vs Shadow\", \"candidateVariant\": \"runner-stored\","
                + " \"baselineVariant\": \"shadow\""), json);
        assertTrue(json.contains("\"pairedCount\": 3"));
        assertTrue(json.contains("\"excludedCount\": 1"));
        assertTrue(json.contains("\"includedIterations\": [0, 2, 3]"));
        assertTrue(json.contains(
                "\"iteration\": 1, \"candidateOutcome\": \"success\", \"baselineOutcome\": \"failed\""), json);
        assertTrue(json.contains("\"resamplingUnit\": \"complete measured iteration pair\""));
        assertTrue(json.contains("\"seed\": 123"));
        assertTrue(json.contains("\"ci95Low\": null"));
        assertFalse(json.contains("leftVariant"));
        assertFalse(json.contains("rightOutcome"));

        String markdown = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        String section = section(markdown, "## Runner vs Shadow");
        assertTrue(section.contains("| Runner default vs Shadow: `runner-stored` − `shadow` | 300.0 ms | 270.0 ms"
                + " | +30.0 ms | "), section);
        assertTrue(section.contains(" | n=3 (<10) | 3/4 | "), section);
        assertTrue(section.contains("iteration 1 (runner-stored: success; shadow: failed)"), section);
        assertFalse(markdown.contains("## Paired readiness comparisons"));
        assertFalse(markdown.contains("statistically significant"));
    }

    @Test
    void missingAttemptIsReportedInsteadOfBeingSilentlyRepaired() {
        VariantResult candidate = result("a", new double[] {100, 200, 300, 400}, -1);
        VariantResult baseline = resultWithMissing("b", new double[] {90, 180, 270, 360}, 1);

        PairedComparison comparison = PairedComparison.of(candidate, baseline, 4, 123L);

        assertEquals(List.of(0, 2, 3), comparison.pairs().stream()
                .map(PairedComparison.Pair::iteration)
                .toList());
        assertEquals(1, comparison.excludedCount());
        assertEquals("missing", comparison.exclusions().getFirst().baselineOutcome());
    }

    @Test
    void pairedBootstrapIsStableOnceThePairThresholdIsMet() {
        VariantResult candidate = result("a", new double[] {101, 202, 303, 404, 505, 606, 707, 808, 909, 1010}, -1);
        VariantResult baseline = result("b", new double[] {100, 200, 300, 400, 500, 600, 700, 800, 900, 1000}, -1);

        PairedComparison first = PairedComparison.of(candidate, baseline, 10, 9876L);
        PairedComparison second = PairedComparison.of(candidate, baseline, 10, 9876L);

        assertFalse(first.descriptiveOnly());
        assertEquals(5.5, first.medianDifferenceMillis());
        assertEquals(first.ciLow(), second.ciLow());
        assertEquals(first.ciHigh(), second.ciHigh());
        assertNull(first.ciReason());
        assertEquals(0.01, first.relativeChange(), 1e-9);
        assertEquals(0.01, first.relativeCiLow(), 1e-9);
        assertEquals(0.01, first.relativeCiHigh(), 1e-9);
    }

    @Test
    void runnerVersusShadowLeadsBothReports(@TempDir Path output) throws Exception {
        double[] runner = new double[10];
        double[] shadow = new double[10];
        for (int i = 0; i < 10; i++) {
            runner[i] = 500 + 7 * i;
            shadow[i] = 590 + 11 * i;
        }
        VariantResult candidate = result("runner-stored", runner, -1, size(300, 100));
        VariantResult baseline = result("shadow", shadow, -1, size(150, 120));
        Path sample = Files.createDirectory(output.resolve("sample"));
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output,
                10, 0, 123L, "/hello", false, "2026-09-22T00:00:00Z",
                List.of("runner-stored", "shadow"), CompletenessPolicy.REQUIRED);

        Reports.write(output, context, List.of(baseline, candidate), List.of());

        String markdown = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        assertTrue(markdown.startsWith("# Startup benchmark\n\n**COMPLETE required comparison**"), markdown);
        int heading = markdown.indexOf("\n## ");
        assertTrue(markdown.startsWith("\n## Runner vs Shadow\n", heading), markdown);
        String row = markdown.lines()
                .filter(line -> line.startsWith("| Runner default vs Shadow: `runner-stored` − `shadow` |"))
                .findFirst().orElseThrow();
        String[] cells = row.split(" \\| ");
        assertEquals("531.5 ms", cells[1]);
        assertEquals("639.5 ms", cells[2]);
        assertEquals("-108.0 ms", cells[3]);
        assertTrue(cells[4].matches("-1[0-9]\\.[0-9]%"), cells[4]);
        assertTrue(cells[5].matches("-[0-9.]+ ms to -[0-9.]+ ms"), cells[5]);
        assertEquals("10/10", cells[6]);
        assertEquals("2.00×", cells[7]);
        assertEquals("0.83× |", cells[8]);
        assertTrue(markdown.indexOf("## Runner vs Shadow") < markdown.indexOf("## Run conditions"));
        assertEquals(1, occurrences(markdown, "does not by itself establish"));

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertEquals(1, occurrences(json, "\"comparisonMethod\""));
        assertEquals(1, occurrences(json, "\"candidateVariant\""));
        assertTrue(json.contains("\"relativeChange\": -0.1"), json);
        assertTrue(json.contains("\"relativeCi95Low\": -0."), json);
        assertTrue(json.contains("\"relativeCi95High\": -0."), json);
        assertFalse(json.contains("\"pairs\":"));
    }

    @Test
    void theWholeMatrixReportsOnlyTheDeclaredComparisons(@TempDir Path output) throws Exception {
        int iterations = 20;
        List<VariantResult> results = new ArrayList<>();
        int offset = 0;
        for (String name : SampleBuild.variantNames()) {
            double[] values = new double[iterations];
            for (int i = 0; i < iterations; i++) {
                values[i] = 300 + 10 * offset + (i * 7 % 13);
            }
            results.add(result(name, values, -1));
            offset++;
        }
        Path sample = Files.createDirectory(output.resolve("sample"));
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output,
                iterations, 0, 123L, "/hello", false, "2026-09-22T00:00:00Z");

        Reports.write(output, context, results, List.of());

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        String comparisons = json.substring(json.indexOf("\"comparisons\""), json.indexOf("\"attempts\""));
        // Every spec whose two rows are core applies; the reflection spec waits for its opt-in row.
        List<SampleBuild.ComparisonSpec> applicable = SampleBuild.comparisons().stream()
                .filter(spec -> SampleBuild.variantNames().contains(spec.candidate())
                        && SampleBuild.variantNames().contains(spec.baseline()))
                .toList();
        assertEquals(7, applicable.size());
        assertEquals(applicable.size(), occurrences(comparisons, "\"candidateVariant\""));
        assertFalse(comparisons.contains("\"baselineVariant\": \"runner-stored-reflection\""));
        assertTrue(comparisons.length() < json.length() / 10,
                comparisons.length() + " of " + json.length() + " bytes");
        assertEquals(1, occurrences(json, "\"resamplingUnit\""));
        assertFalse(comparisons.contains("\"estimator\""));
        assertFalse(comparisons.contains("\"ciMethod\""));
        assertFalse(comparisons.contains("\"seed\""));
        assertFalse(comparisons.contains("\"pairs\""));
        List<String> order = Pattern.compile("\"candidateVariant\": \"([^\"]+)\", \"baselineVariant\": \"([^\"]+)\"")
                .matcher(comparisons).results()
                .map(match -> match.group(1) + " - " + match.group(2))
                .toList();
        assertEquals(applicable.stream()
                .map(spec -> spec.candidate() + " - " + spec.baseline())
                .toList(), order);
        assertEquals("runner-stored - shadow", order.getFirst());
        // The one Shadow candidate is the Shadow-only compression control, and its label says so in both reports.
        List<SampleBuild.ComparisonSpec> shadowCandidates = applicable.stream()
                .filter(spec -> spec.candidate().startsWith("shadow"))
                .toList();
        assertEquals(1, shadowCandidates.size(), shadowCandidates.toString());
        SampleBuild.ComparisonSpec control = shadowCandidates.getFirst();
        assertEquals("shadow-stored - shadow", control.candidate() + " - " + control.baseline());
        assertTrue(control.label().startsWith("Shadow-only control"), control.label());
        assertTrue(comparisons.contains("{\"label\": \"" + control.label()
                + "\", \"candidateVariant\": \"shadow-stored\", \"baselineVariant\": \"shadow\""), comparisons);

        String markdown = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        String section = section(markdown, "## Runner vs Shadow");
        for (SampleBuild.ComparisonSpec spec : applicable) {
            assertTrue(section.contains("| " + spec.label() + ": `" + spec.candidate() + "` − `"
                    + spec.baseline() + "` |"), section);
        }
        assertTrue(section.contains("| Shadow-only control: "), section);
        assertFalse(section.contains("runner-stored-reflection"), section);
        assertEquals(1, occurrences(markdown, "does not by itself establish"));
        assertFalse(markdown.contains("## Paired readiness comparisons"));
    }

    @Test
    void theOptInReflectionRowBringsItsDeclaredComparison(@TempDir Path output) throws Exception {
        int iterations = 10;
        double[] stub = new double[iterations];
        double[] reflection = new double[iterations];
        for (int i = 0; i < iterations; i++) {
            stub[i] = 500 + (i * 7 % 13);
            reflection[i] = 510 + (i * 5 % 11);
        }
        Path sample = Files.createDirectory(output.resolve("sample"));
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output,
                iterations, 0, 123L, "/hello", false, "2026-09-22T00:00:00Z",
                List.of("runner-stored"), CompletenessPolicy.REQUIRED);

        Reports.write(output, context, List.of(result("runner-stored", stub, -1),
                result("runner-stored-reflection", reflection, -1)), List.of());

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        String comparisons = json.substring(json.indexOf("\"comparisons\""), json.indexOf("\"attempts\""));
        assertEquals(1, occurrences(comparisons, "\"candidateVariant\""));
        assertTrue(comparisons.contains("{\"label\": \"Entry stub vs reflection\", \"candidateVariant\":"
                + " \"runner-stored\", \"baselineVariant\": \"runner-stored-reflection\""), comparisons);
    }

    private static String section(String markdown, String heading) {
        int start = markdown.indexOf(heading + "\n");
        assertTrue(start >= 0, markdown);
        int end = markdown.indexOf("\n## ", start + heading.length());
        return markdown.substring(start, end < 0 ? markdown.length() : end);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static DeploymentSize size(long bytes, long gzipBytes) {
        return new DeploymentSize(List.of(new DeploymentSize.Component("archive", bytes, gzipBytes)), bytes);
    }

    private static VariantResult result(String name, double[] values, int failedIteration) {
        return result(name, values, failedIteration, null);
    }

    private static VariantResult result(String name,
                                        double[] values,
                                        int failedIteration,
                                        DeploymentSize deploymentSize) {
        Variant variant = Variant.available(name, "fixture " + name,
                List.of("java"), Path.of("."), Path.of("."), deploymentSize);
        List<RunAttempt> attempts = new ArrayList<>();
        for (int iteration = 0; iteration < values.length; iteration++) {
            if (iteration == failedIteration) {
                attempts.add(RunAttempt.failure(name, iteration, iteration, iteration, false,
                        "fixture failure", 1));
            } else {
                StartupSample sample = new StartupSample(iteration, false, 8080 + iteration,
                        values[iteration], -1, -1, 0.1, 143, ReadinessSnapshot.UNAVAILABLE);
                attempts.add(RunAttempt.success(name, iteration, iteration, sample));
            }
        }
        long deploymentBytes = deploymentSize == null ? 1 : deploymentSize.totalBytes();
        return VariantResult.summarize(variant, deploymentBytes, attempts, 0, values.length, 123L);
    }

    private static VariantResult withSnapshot(String name, ReadinessSnapshot snapshot) {
        Variant variant = Variant.available(name, "fixture " + name, List.of("java"), Path.of("."), Path.of("."));
        StartupSample sample = new StartupSample(0, false, 8080, 100, -1, -1, 0.1, 143, snapshot);
        return VariantResult.summarize(variant, 1, List.of(RunAttempt.success(name, 0, 0, sample)), 0, 1, 123L);
    }

    private static VariantResult resultWithMissing(String name, double[] values, int missingIteration) {
        Variant variant = Variant.available(name, "fixture " + name,
                List.of("java"), Path.of("."), Path.of("."));
        List<RunAttempt> attempts = new ArrayList<>();
        for (int iteration = 0; iteration < values.length; iteration++) {
            if (iteration == missingIteration) {
                continue;
            }
            StartupSample sample = new StartupSample(iteration, false, 8080 + iteration,
                    values[iteration], -1, -1, 0.1, 143, ReadinessSnapshot.UNAVAILABLE);
            attempts.add(RunAttempt.success(name, iteration, iteration, sample));
        }
        return VariantResult.summarize(variant, 1, attempts, 0, values.length, 123L);
    }
}
