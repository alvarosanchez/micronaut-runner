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

import java.nio.file.Path;
import java.util.List;

/** Invocation metadata and the explicit completeness contract for one benchmark run. */
record RunContext(Path sample,
                  String repository,
                  String runnerVersion,
                  Path outputDirectory,
                  int iterations,
                  int warmupIterations,
                  long seed,
                  String readinessPath,
                  boolean diagnostics,
                  String generatedAt,
                  List<String> requiredVariants,
                  CompletenessPolicy completenessPolicy) {

    RunContext {
        requiredVariants = List.copyOf(requiredVariants);
    }

    RunContext(Path sample,
               String repository,
               String runnerVersion,
               Path outputDirectory,
               int iterations,
               int warmupIterations,
               long seed,
               String readinessPath,
               boolean diagnostics,
               String generatedAt) {
        this(sample, repository, runnerVersion, outputDirectory, iterations, warmupIterations, seed,
                readinessPath, diagnostics, generatedAt, SampleBuild.variantNames(), CompletenessPolicy.REQUIRED);
    }
}
