/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.runner.benchmarks;

/**
 * One scheduled run that reached the process runner, successful or failed.
 *
 * @param variant       variant identity
 * @param iteration     zero-based iteration across warm-up and measured phases
 * @param phaseIteration zero-based iteration within the selected phase
 * @param globalOrder   zero-based position in the complete interleaved schedule
 * @param warmup        whether statistics discard this run
 * @param outcome       success or failure
 * @param sample        timing data for a success, otherwise {@code null}
 * @param failureReason failure detail, otherwise {@code null}
 * @param exitCode      child exit status when known, otherwise {@code null}
 */
record RunAttempt(String variant,
                  int iteration,
                  int phaseIteration,
                  int globalOrder,
                  boolean warmup,
                  AttemptOutcome outcome,
                  StartupSample sample,
                  String failureReason,
                  Integer exitCode) {

    static RunAttempt success(String variant, int phaseIteration, int globalOrder, StartupSample sample) {
        return new RunAttempt(variant, sample.iteration(), phaseIteration, globalOrder, sample.warmup(),
                AttemptOutcome.SUCCESS, sample, null, sample.exitCode());
    }

    static RunAttempt failure(String variant,
                              int iteration,
                              int phaseIteration,
                              int globalOrder,
                              boolean warmup,
                              String reason,
                              Integer exitCode) {
        return new RunAttempt(variant, iteration, phaseIteration, globalOrder, warmup,
                AttemptOutcome.FAILED, null, reason, exitCode);
    }
}
