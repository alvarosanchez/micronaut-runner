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
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * Everything the report knows about one variant, including every successful and failed process attempt.
 *
 * @param variant     the variant, including its command line and why it may be unavailable
 * @param readiness   the successful measured readiness distribution, or {@code null}
 * @param logLine     the successful measured startup-line distribution, or {@code null}
 * @param framework   the successful measured framework distribution, or {@code null}
 * @param attempts    every process attempt, with schedule identity and outcome: the only per-run record
 * @param warmup      warm-up requested/attempted/successful/failed/skipped counts
 * @param measured    measured requested/attempted/successful/failed/skipped counts
 * @param atReadiness per-field medians of the successful measured attempts' readiness snapshots, each
 *                    {@code -1} when no attempt had a value
 */
record VariantResult(Variant variant,
                     Statistics readiness,
                     Statistics logLine,
                     Statistics framework,
                     List<RunAttempt> attempts,
                     PhaseCounts warmup,
                     PhaseCounts measured,
                     ReadinessSnapshot atReadiness) {

    VariantResult {
        attempts = List.copyOf(attempts);
    }

    static VariantResult summarize(Variant variant,
                                   List<RunAttempt> attempts,
                                   int warmupRequested,
                                   int measuredRequested,
                                   long seed) {
        List<StartupSample> measured = measuredSamples(attempts);
        double[] readiness = measured.stream()
                .mapToDouble(StartupSample::readinessMillis)
                .toArray();
        double[] logLine = measured.stream()
                .filter(sample -> sample.logLineMillis() >= 0)
                .mapToDouble(StartupSample::logLineMillis)
                .toArray();
        double[] framework = measured.stream()
                .filter(sample -> sample.frameworkMillis() >= 0)
                .mapToDouble(StartupSample::frameworkMillis)
                .toArray();
        return new VariantResult(variant,
                Statistics.of(readiness, seed), Statistics.of(logLine, seed), Statistics.of(framework, seed),
                attempts,
                PhaseCounts.of(warmupRequested, true, attempts),
                PhaseCounts.of(measuredRequested, false, attempts),
                medianSnapshot(measured));
    }

    /**
     * The samples of the successful measured attempts.
     *
     * @return the samples, in attempt order
     */
    List<StartupSample> measuredSamples() {
        return measuredSamples(attempts);
    }

    /**
     * Why the first failed attempt failed.
     *
     * @return the reason, or {@code null} when no attempt failed
     */
    String firstFailure() {
        return attempts.stream().filter(attempt -> attempt.outcome() == AttemptOutcome.FAILED)
                .map(RunAttempt::failureReason).findFirst().orElse(null);
    }

    private static List<StartupSample> measuredSamples(List<RunAttempt> attempts) {
        return attempts.stream()
                .filter(attempt -> attempt.outcome() == AttemptOutcome.SUCCESS && !attempt.warmup())
                .map(RunAttempt::sample)
                .toList();
    }

    /**
     * Per-field medians over the measured snapshots, ignoring unavailable ({@code -1}) values. Byte and class
     * medians are rounded to whole units. No interval: memory is reported descriptively.
     */
    private static ReadinessSnapshot medianSnapshot(List<StartupSample> measured) {
        List<ReadinessSnapshot> snapshots = measured.stream()
                .map(StartupSample::atReadiness)
                .filter(Objects::nonNull)
                .toList();
        return new ReadinessSnapshot(
                medianMillis(snapshots, ReadinessSnapshot::probeMillis),
                medianCount(snapshots, snapshot -> snapshot.rssBytes()),
                medianCount(snapshots, ReadinessSnapshot::peakRssBytes),
                medianCount(snapshots, ReadinessSnapshot::anonBytes),
                medianCount(snapshots, ReadinessSnapshot::fileBytes),
                medianCount(snapshots, ReadinessSnapshot::footprintBytes),
                medianCount(snapshots, ReadinessSnapshot::peakFootprintBytes),
                medianCount(snapshots, ReadinessSnapshot::loadedClasses),
                medianCount(snapshots, ReadinessSnapshot::sharedClasses),
                medianCount(snapshots, ReadinessSnapshot::majorFaults),
                medianCount(snapshots, ReadinessSnapshot::readBytes));
    }

    private static double medianMillis(List<ReadinessSnapshot> snapshots,
                                       ToDoubleFunction<ReadinessSnapshot> field) {
        double[] values = snapshots.stream().mapToDouble(field).filter(value -> value >= 0).toArray();
        if (values.length == 0) {
            return -1;
        }
        Arrays.sort(values);
        return Statistics.percentile(values, 0.5);
    }

    private static long medianCount(List<ReadinessSnapshot> snapshots, ToLongFunction<ReadinessSnapshot> field) {
        double[] values = snapshots.stream().mapToLong(field).filter(value -> value >= 0)
                .mapToDouble(value -> value).toArray();
        if (values.length == 0) {
            return -1;
        }
        Arrays.sort(values);
        return Math.round(Statistics.percentile(values, 0.5));
    }
}
