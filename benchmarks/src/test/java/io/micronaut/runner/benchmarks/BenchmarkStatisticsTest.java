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
        StartupSample startupSample = new StartupSample(0, false, 8080, 100, -1, -1, 0.1, 143);
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
        assertTrue(markdown.contains("descriptive only"));
        assertTrue(markdown.contains("at least 10 successful measured samples"));
        assertFalse(markdown.contains("100.0 ms – 100.0 ms"));
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
        VariantResult left = result("a", new double[] {100, 200, 300, 400}, -1);
        VariantResult right = result("b", new double[] {90, 180, 270, 360}, -1);

        PairedComparison comparison = PairedComparison.of(left, right, 4, 123L);

        assertEquals(4, comparison.pairedCount());
        assertEquals(0, comparison.excludedCount());
        assertEquals(List.of(0, 1, 2, 3), comparison.pairs().stream()
                .map(PairedComparison.Pair::iteration)
                .toList());
        assertEquals(List.of(10.0, 20.0, 30.0, 40.0), comparison.pairs().stream()
                .map(PairedComparison.Pair::differenceMillis)
                .toList());
        assertEquals(25.0, comparison.medianDifferenceMillis());
        assertTrue(comparison.descriptiveOnly());
    }

    @Test
    void failedAttemptExcludesItsIterationWithoutRepairingThePair(@TempDir Path output) throws Exception {
        VariantResult left = result("a", new double[] {100, 200, 300, 400}, -1);
        VariantResult right = result("b", new double[] {90, 180, 270, 360}, 1);

        PairedComparison comparison = PairedComparison.of(left, right, 4, 123L);

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
        assertEquals("success", comparison.exclusions().getFirst().leftOutcome());
        assertEquals("failed", comparison.exclusions().getFirst().rightOutcome());

        Path sample = Files.createDirectory(output.resolve("sample"));
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output,
                4, 0, 123L, "/hello", false, "2026-09-22T00:00:00Z",
                List.of("a", "b"), CompletenessPolicy.PARTIAL);
        Reports.write(output, context, List.of(left, right), List.of());

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"comparisons\""));
        assertTrue(json.contains("\"pairedCount\": 3"));
        assertTrue(json.contains("\"excludedCount\": 1"));
        assertTrue(json.contains("\"includedIterations\": [0, 2, 3]"));
        assertTrue(json.contains("\"iteration\": 1, \"leftOutcome\": \"success\", \"rightOutcome\": \"failed\""));
        assertTrue(json.contains("\"resamplingUnit\": \"complete measured iteration pair\""));
        assertTrue(json.contains("\"seed\": 123"));
        assertTrue(json.contains("\"ci95Low\": null"));

        String markdown = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("## Paired readiness comparisons"));
        assertTrue(markdown.contains("3 complete / 4 requested"));
        assertTrue(markdown.contains("iteration 1 (a: success; b: failed)"));
        assertFalse(markdown.contains("statistically significant"));
    }

    @Test
    void missingAttemptIsReportedInsteadOfBeingSilentlyRepaired() {
        VariantResult left = result("a", new double[] {100, 200, 300, 400}, -1);
        VariantResult right = resultWithMissing("b", new double[] {90, 180, 270, 360}, 1);

        PairedComparison comparison = PairedComparison.of(left, right, 4, 123L);

        assertEquals(List.of(0, 2, 3), comparison.pairs().stream()
                .map(PairedComparison.Pair::iteration)
                .toList());
        assertEquals(1, comparison.excludedCount());
        assertEquals("missing", comparison.exclusions().getFirst().rightOutcome());
    }

    @Test
    void pairedBootstrapIsStableOnceThePairThresholdIsMet() {
        VariantResult left = result("a", new double[] {101, 202, 303, 404, 505, 606, 707, 808, 909, 1010}, -1);
        VariantResult right = result("b", new double[] {100, 200, 300, 400, 500, 600, 700, 800, 900, 1000}, -1);

        PairedComparison first = PairedComparison.of(left, right, 10, 9876L);
        PairedComparison second = PairedComparison.of(left, right, 10, 9876L);

        assertFalse(first.descriptiveOnly());
        assertEquals(5.5, first.medianDifferenceMillis());
        assertEquals(first.ciLow(), second.ciLow());
        assertEquals(first.ciHigh(), second.ciHigh());
        assertNull(first.ciReason());
    }

    private static VariantResult result(String name, double[] values, int failedIteration) {
        Variant variant = Variant.available(name, "fixture " + name,
                List.of("java"), Path.of("."), Path.of("."));
        List<RunAttempt> attempts = new ArrayList<>();
        for (int iteration = 0; iteration < values.length; iteration++) {
            if (iteration == failedIteration) {
                attempts.add(RunAttempt.failure(name, iteration, iteration, iteration, false,
                        "fixture failure", 1));
            } else {
                StartupSample sample = new StartupSample(iteration, false, 8080 + iteration,
                        values[iteration], -1, -1, 0.1, 143);
                attempts.add(RunAttempt.success(name, iteration, iteration, sample));
            }
        }
        return VariantResult.summarize(variant, 1, attempts, 0, values.length, 123L);
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
                    values[iteration], -1, -1, 0.1, 143);
            attempts.add(RunAttempt.success(name, iteration, iteration, sample));
        }
        return VariantResult.summarize(variant, 1, attempts, 0, values.length, 123L);
    }
}
