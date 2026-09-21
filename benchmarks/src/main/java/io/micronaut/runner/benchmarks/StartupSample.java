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

/**
 * One measured start of one variant. Every field is written to {@code results.json}; nothing is averaged
 * away before it gets there.
 *
 * @param iteration        the iteration this run belongs to, counting warm-up runs
 * @param warmup           whether it was a warm-up run, which the statistics ignore
 * @param port             the port this run was given
 * @param readinessMillis  spawn to first HTTP 200, on one monotonic clock: the headline metric
 * @param frameworkMillis  what Micronaut's own "Startup completed in Nms" line claimed, or {@code -1} when
 *                         the line was not seen. Reported beside the readiness time, never instead of it
 * @param pollGapMillis    the interval between the last failed poll and the successful one, which bounds
 *                         how much of {@code readinessMillis} is polling latency rather than startup
 * @param exitCode         the exit status after the process was destroyed, or {@code -1} if it was killed
 */
record StartupSample(int iteration,
                     boolean warmup,
                     int port,
                     double readinessMillis,
                     double frameworkMillis,
                     double pollGapMillis,
                     int exitCode) {
}
