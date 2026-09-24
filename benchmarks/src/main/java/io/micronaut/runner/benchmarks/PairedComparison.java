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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A readiness comparison whose observations stay aligned by measured iteration.
 *
 * <p>The estimator is the median of {@code candidate - baseline} readiness times, so a negative value means the
 * candidate is faster. The relative change is the median of the per-iteration {@code candidate / baseline}
 * ratios minus one. Bootstrap resampling, when the reporting threshold is met, resamples complete iteration
 * pairs by resampling their already-paired differences (and, separately, their ratios). A failed, missing,
 * unavailable or duplicate attempt excludes that iteration instead of allowing the next successful value to
 * slide into its place.</p>
 *
 * @param candidateVariant the variant being judged
 * @param baselineVariant  the variant it is judged against
 * @param requestedPairs   number of measured iterations requested for each variant
 * @param pairs            complete iteration pairs
 * @param exclusions       incomplete or ambiguous iterations
 * @param differences      summary of paired differences, or {@code null} when there are no complete pairs
 * @param ratios           summary of paired ratios, or {@code null} when there are no complete pairs
 */
record PairedComparison(String candidateVariant,
                        String baselineVariant,
                        int requestedPairs,
                        List<Pair> pairs,
                        List<Exclusion> exclusions,
                        Statistics differences,
                        Statistics ratios) {

    static final String ESTIMATOR = "median of paired readiness differences (candidate - baseline)";
    static final String RELATIVE_ESTIMATOR = "median of paired readiness ratios (candidate / baseline) - 1";
    static final String RESAMPLING_UNIT = "complete measured iteration pair";
    static final String CI_METHOD =
            "percentile bootstrap of the median paired difference and, separately, of the median paired ratio";

    static PairedComparison of(VariantResult candidate,
                               VariantResult baseline,
                               int requestedPairs,
                               long seed) {
        List<Pair> pairs = new ArrayList<>();
        List<Exclusion> exclusions = new ArrayList<>();
        for (int iteration = 0; iteration < requestedPairs; iteration++) {
            AttemptValue candidateValue = valueAt(candidate, iteration);
            AttemptValue baselineValue = valueAt(baseline, iteration);
            if (candidateValue.value() != null && baselineValue.value() != null) {
                pairs.add(new Pair(iteration, candidateValue.value(), baselineValue.value(),
                        candidateValue.value() - baselineValue.value()));
            } else {
                exclusions.add(new Exclusion(iteration, candidateValue.outcome(), baselineValue.outcome()));
            }
        }
        double[] differences = pairs.stream().mapToDouble(Pair::differenceMillis).toArray();
        double[] ratios = pairs.stream().mapToDouble(Pair::ratio).toArray();
        return new PairedComparison(candidate.variant().name(), baseline.variant().name(), requestedPairs,
                List.copyOf(pairs), List.copyOf(exclusions),
                Statistics.of(differences, seed), Statistics.of(ratios, seed));
    }

    int pairedCount() {
        return pairs.size();
    }

    int excludedCount() {
        return exclusions.size();
    }

    Double medianDifferenceMillis() {
        return differences == null ? null : differences.median();
    }

    /**
     * The candidate's median readiness over the complete pairs only, so it describes the same iterations as
     * the difference.
     *
     * @return the median, or {@code null} when there are no complete pairs
     */
    Double candidateMedianMillis() {
        return median(pairs.stream().mapToDouble(Pair::candidateMillis).toArray());
    }

    /**
     * The baseline's median readiness over the complete pairs only.
     *
     * @return the median, or {@code null} when there are no complete pairs
     */
    Double baselineMedianMillis() {
        return median(pairs.stream().mapToDouble(Pair::baselineMillis).toArray());
    }

    Double ciLow() {
        return differences == null ? null : differences.ciLow();
    }

    Double ciHigh() {
        return differences == null ? null : differences.ciHigh();
    }

    /**
     * The median per-iteration ratio minus one: {@code -0.10} means the candidate took 10% less time.
     *
     * @return the relative change, or {@code null} when there are no complete pairs
     */
    Double relativeChange() {
        return ratios == null ? null : ratios.median() - 1;
    }

    Double relativeCiLow() {
        return ratios == null || ratios.ciLow() == null ? null : ratios.ciLow() - 1;
    }

    Double relativeCiHigh() {
        return ratios == null || ratios.ciHigh() == null ? null : ratios.ciHigh() - 1;
    }

    boolean descriptiveOnly() {
        return differences == null || !differences.hasConfidenceInterval();
    }

    String ciReason() {
        if (!descriptiveOnly()) {
            return null;
        }
        return "requires at least " + Statistics.MIN_CONFIDENCE_SAMPLES
                + " complete pairs; observed " + pairedCount();
    }

    private static Double median(double[] values) {
        if (values.length == 0) {
            return null;
        }
        Arrays.sort(values);
        return Statistics.percentile(values, 0.5);
    }

    private static AttemptValue valueAt(VariantResult result, int phaseIteration) {
        List<RunAttempt> matching = result.attempts().stream()
                .filter(attempt -> !attempt.warmup() && attempt.phaseIteration() == phaseIteration)
                .toList();
        if (matching.isEmpty()) {
            return new AttemptValue(null, result.variant().available() ? "missing" : "unavailable");
        }
        if (matching.size() > 1) {
            return new AttemptValue(null, "duplicate");
        }
        RunAttempt attempt = matching.getFirst();
        if (attempt.outcome() != AttemptOutcome.SUCCESS || attempt.sample() == null) {
            return new AttemptValue(null, attempt.outcome().externalName());
        }
        return new AttemptValue(attempt.sample().readinessMillis(), attempt.outcome().externalName());
    }

    /** One complete measured-iteration pair and its predeclared difference. */
    record Pair(int iteration, double candidateMillis, double baselineMillis, double differenceMillis) {

        double ratio() {
            return candidateMillis / baselineMillis;
        }
    }

    /** Why a measured iteration was excluded from the paired estimator. */
    record Exclusion(int iteration, String candidateOutcome, String baselineOutcome) {
    }

    private record AttemptValue(Double value, String outcome) {
    }
}
