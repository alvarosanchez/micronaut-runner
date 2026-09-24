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
 * @param logLineMillis    spawn to the moment this process saw the framework's "Startup completed" line,
 *                         on the same clock, or {@code -1} when the line never appeared. It is earlier
 *                         than readiness, because serving the first request on a cold JVM costs real time
 * @param frameworkMillis  what that line itself claimed, or {@code -1} when it was not seen. This is the
 *                         application's own count, started long after the JVM was; it is reported beside
 *                         the two external measurements and never instead of them
 * @param pollGapMillis    the interval between the last failed poll and the successful one, which bounds
 *                         how much of {@code readinessMillis} is polling latency rather than startup
 * @param exitCode         the exit status after the process was destroyed, or {@code -1} if it was killed
 * @param atReadiness      memory and loaded classes read once after readiness, outside the timed interval;
 *                         {@link ReadinessSnapshot#UNAVAILABLE} when nothing could be read
 */
record StartupSample(int iteration,
                     boolean warmup,
                     int port,
                     double readinessMillis,
                     double logLineMillis,
                     double frameworkMillis,
                     double pollGapMillis,
                     int exitCode,
                     ReadinessSnapshot atReadiness) {
}
