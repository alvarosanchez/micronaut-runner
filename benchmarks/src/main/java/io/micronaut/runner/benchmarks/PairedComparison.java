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
import java.util.List;

/**
 * A readiness comparison whose observations stay aligned by measured iteration.
 *
 * <p>The estimator is the median of {@code left - right} readiness times. Bootstrap resampling, when the
 * reporting threshold is met, resamples complete iteration pairs by resampling their already-paired
 * differences. A failed, missing, unavailable or duplicate attempt excludes that iteration instead of
 * allowing the next successful value to slide into its place.</p>
 *
 * @param leftVariant   minuend variant
 * @param rightVariant  subtrahend variant
 * @param requestedPairs number of measured iterations requested for each variant
 * @param pairs         complete iteration pairs
 * @param exclusions    incomplete or ambiguous iterations
 * @param differences   summary of paired differences, or {@code null} when there are no complete pairs
 * @param seed          bootstrap seed
 */
record PairedComparison(String leftVariant,
                        String rightVariant,
                        int requestedPairs,
                        List<Pair> pairs,
                        List<Exclusion> exclusions,
                        Statistics differences,
                        long seed) {

    static final String ESTIMATOR = "median of paired readiness differences (left - right)";
    static final String RESAMPLING_UNIT = "complete measured iteration pair";

    static PairedComparison of(VariantResult left,
                               VariantResult right,
                               int requestedPairs,
                               long seed) {
        List<Pair> pairs = new ArrayList<>();
        List<Exclusion> exclusions = new ArrayList<>();
        for (int iteration = 0; iteration < requestedPairs; iteration++) {
            AttemptValue leftValue = valueAt(left, iteration);
            AttemptValue rightValue = valueAt(right, iteration);
            if (leftValue.value() != null && rightValue.value() != null) {
                pairs.add(new Pair(iteration, leftValue.value(), rightValue.value(),
                        leftValue.value() - rightValue.value()));
            } else {
                exclusions.add(new Exclusion(iteration, leftValue.outcome(), rightValue.outcome()));
            }
        }
        double[] values = pairs.stream().mapToDouble(Pair::differenceMillis).toArray();
        return new PairedComparison(left.variant().name(), right.variant().name(), requestedPairs,
                List.copyOf(pairs), List.copyOf(exclusions), Statistics.of(values, seed), seed);
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

    Double ciLow() {
        return differences == null ? null : differences.ciLow();
    }

    Double ciHigh() {
        return differences == null ? null : differences.ciHigh();
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
    record Pair(int iteration, double leftMillis, double rightMillis, double differenceMillis) {
    }

    /** Why a measured iteration was excluded from the paired estimator. */
    record Exclusion(int iteration, String leftOutcome, String rightOutcome) {
    }

    private record AttemptValue(Double value, String outcome) {
    }
}
