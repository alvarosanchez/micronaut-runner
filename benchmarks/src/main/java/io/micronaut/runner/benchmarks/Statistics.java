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

import java.util.Arrays;
import java.util.Random;

/**
 * The summary of one variant's samples.
 *
 * <p>Median and 90th percentile rather than mean and standard deviation, because process start times are
 * not normally distributed: they have a hard floor and a long right tail made of page faults, scheduler
 * decisions and whatever else the machine was doing. A mean chases the tail; a median does not.</p>
 *
 * <p>When there are at least {@value #MIN_CONFIDENCE_SAMPLES} observations, the interval around the median
 * is a <em>percentile bootstrap</em>: resample the observations with replacement {@value #RESAMPLES} times,
 * take the median of each resample, and report the 2.5th and 97.5th percentiles of those medians. Below that
 * reporting threshold the descriptive values remain available but the interval is absent. The threshold is
 * a benchmark reporting policy, not a universal guarantee that ten observations establish precision.</p>
 *
 * @param count  how many samples went in
 * @param min    the smallest sample
 * @param median the 50th percentile
 * @param p90    the 90th percentile
 * @param mean   the arithmetic mean, for readers who want to see the tail's pull
 * @param max    the largest sample
 * @param ciLow  the lower end of the 95% bootstrap interval of the median, or {@code null}
 * @param ciHigh the upper end of the 95% bootstrap interval of the median, or {@code null}
 * @param ciReason why the interval is absent, or {@code null}
 */
record Statistics(int count,
                  double min,
                  double median,
                  double p90,
                  double mean,
                  double max,
                  Double ciLow,
                  Double ciHigh,
                  String ciReason) {

    /** How many bootstrap resamples the interval is built from. */
    static final int RESAMPLES = 2_000;

    /** The confidence level of the reported interval. */
    static final double CONFIDENCE = 0.95;

    /** Minimum observations required before this harness emits an inferential interval. */
    static final int MIN_CONFIDENCE_SAMPLES = 10;

    /**
     * Summarises a set of samples.
     *
     * @param values the samples, which are copied before being sorted
     * @param seed   the seed of the bootstrap's generator, so a report is reproducible from its inputs
     * @return the summary, or {@code null} when there are no samples
     */
    static Statistics of(double[] values, long seed) {
        if (values.length == 0) {
            return null;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double total = 0;
        for (double value : sorted) {
            total += value;
        }
        double median = percentile(sorted, 0.5);
        double[] interval = sorted.length < MIN_CONFIDENCE_SAMPLES
                ? null : bootstrapMedianInterval(sorted, seed);
        String reason = interval == null
                ? "requires at least " + MIN_CONFIDENCE_SAMPLES + " observations; observed " + sorted.length
                : null;
        return new Statistics(sorted.length, sorted[0], median, percentile(sorted, 0.9),
                total / sorted.length, sorted[sorted.length - 1],
                interval == null ? null : interval[0], interval == null ? null : interval[1], reason);
    }

    boolean hasConfidenceInterval() {
        return ciLow != null && ciHigh != null;
    }

    /**
     * The linear-interpolation percentile (the "R-7" definition, which is what most tools mean by it).
     *
     * @param sorted the samples, already sorted ascending
     * @param q      the quantile, between 0 and 1
     * @return the interpolated value
     */
    static double percentile(double[] sorted, double q) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double position = q * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower];
        }
        double fraction = position - lower;
        return sorted[lower] + fraction * (sorted[upper] - sorted[lower]);
    }

    private static double[] bootstrapMedianInterval(double[] sorted, long seed) {
        Random random = new Random(seed);
        double[] medians = new double[RESAMPLES];
        double[] resample = new double[sorted.length];
        for (int i = 0; i < RESAMPLES; i++) {
            for (int j = 0; j < resample.length; j++) {
                resample[j] = sorted[random.nextInt(sorted.length)];
            }
            Arrays.sort(resample);
            medians[i] = percentile(resample, 0.5);
        }
        Arrays.sort(medians);
        double tail = (1 - CONFIDENCE) / 2;
        return new double[] {percentile(medians, tail), percentile(medians, 1 - tail)};
    }
}
