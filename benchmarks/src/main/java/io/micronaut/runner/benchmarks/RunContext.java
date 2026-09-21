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

import java.nio.file.Path;

/**
 * What one invocation of the harness was: which sample, from which repository, how many times, and with
 * which seed. It is written into both reports so that a result can be traced back to the run that made it.
 *
 * @param sample            the sample application's project directory
 * @param repository        the Maven repository the runner plugins were resolved from
 * @param runnerVersion     the version they were published under
 * @param outputDirectory   where the reports go
 * @param iterations        measured iterations per variant
 * @param warmupIterations  discarded iterations per variant, run first
 * @param seed              the seed of the interleaving shuffle and of the bootstrap
 * @param readinessPath     the HTTP path polled for readiness
 * @param diagnostics       whether separate class-load counting runs were made
 * @param generatedAt       when the run finished, in ISO-8601
 */
record RunContext(Path sample,
                  String repository,
                  String runnerVersion,
                  Path outputDirectory,
                  int iterations,
                  int warmupIterations,
                  long seed,
                  String readinessPath,
                  boolean diagnostics,
                  String generatedAt) {
}
