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
 * @param deploymentBytes the complete deployment size, or {@code -1} when it is unavailable
 * @param samples     successful runs, retained for machine-readable compatibility
 * @param readiness   the successful measured readiness distribution, or {@code null}
 * @param logLine     the successful measured startup-line distribution, or {@code null}
 * @param framework   the successful measured framework distribution, or {@code null}
 * @param failures    failure reasons, retained for machine-readable compatibility
 * @param attempts    every process attempt, with schedule identity and outcome
 * @param warmup      warm-up requested/attempted/successful/failed/skipped counts
 * @param measured    measured requested/attempted/successful/failed/skipped counts
 * @param atReadiness per-field medians of the successful measured attempts' readiness snapshots, each
 *                    {@code -1} when no attempt had a value
 */
record VariantResult(Variant variant,
                     long deploymentBytes,
                     List<StartupSample> samples,
                     Statistics readiness,
                     Statistics logLine,
                     Statistics framework,
                     List<String> failures,
                     List<RunAttempt> attempts,
                     PhaseCounts warmup,
                     PhaseCounts measured,
                     ReadinessSnapshot atReadiness) {

    VariantResult {
        DeploymentSize deploymentSize = variant.deploymentSize();
        if (deploymentSize != null && deploymentBytes != deploymentSize.totalBytes()) {
            throw new IllegalArgumentException("reported deployment bytes " + deploymentBytes
                    + " do not reconcile with measured total " + deploymentSize.totalBytes());
        }
    }

    VariantResult(Variant variant,
                  long deploymentBytes,
                  List<StartupSample> samples,
                  Statistics readiness,
                  Statistics logLine,
                  Statistics framework,
                  List<String> failures) {
        this(variant, deploymentBytes, samples, readiness, logLine, framework, failures, List.of(),
                new PhaseCounts(0, 0, 0, 0, 0), new PhaseCounts(0, 0, 0, 0, 0), ReadinessSnapshot.UNAVAILABLE);
    }

    static VariantResult summarize(Variant variant,
                                   long deploymentBytes,
                                   List<RunAttempt> attempts,
                                   int warmupRequested,
                                   int measuredRequested,
                                   long seed) {
        List<StartupSample> samples = attempts.stream()
                .filter(attempt -> attempt.outcome() == AttemptOutcome.SUCCESS)
                .map(RunAttempt::sample)
                .toList();
        List<String> failures = attempts.stream()
                .filter(attempt -> attempt.outcome() == AttemptOutcome.FAILED)
                .map(RunAttempt::failureReason)
                .toList();
        double[] readiness = samples.stream()
                .filter(sample -> !sample.warmup())
                .mapToDouble(StartupSample::readinessMillis)
                .toArray();
        double[] logLine = samples.stream()
                .filter(sample -> !sample.warmup() && sample.logLineMillis() >= 0)
                .mapToDouble(StartupSample::logLineMillis)
                .toArray();
        double[] framework = samples.stream()
                .filter(sample -> !sample.warmup() && sample.frameworkMillis() >= 0)
                .mapToDouble(StartupSample::frameworkMillis)
                .toArray();
        return new VariantResult(variant, deploymentBytes, List.copyOf(samples),
                Statistics.of(readiness, seed), Statistics.of(logLine, seed), Statistics.of(framework, seed),
                List.copyOf(failures), List.copyOf(attempts),
                PhaseCounts.of(warmupRequested, true, attempts),
                PhaseCounts.of(measuredRequested, false, attempts),
                medianSnapshot(samples.stream().filter(sample -> !sample.warmup()).toList()));
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
                medianCount(snapshots, ReadinessSnapshot::sharedClasses));
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
