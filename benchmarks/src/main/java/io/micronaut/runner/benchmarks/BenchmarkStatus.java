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

import java.util.List;

/** Completeness and exit decision derived from the saved run data. */
record BenchmarkStatus(boolean complete, boolean anyMeasuredSuccess, int exitCode) {

    static BenchmarkStatus evaluate(RunContext context, List<VariantResult> results) {
        boolean complete = context.requiredVariants().stream().allMatch(required -> results.stream()
                .filter(result -> result.variant().name().equals(required))
                .anyMatch(result -> result.variant().available()
                        && result.measured().successful() == context.iterations()
                        && result.measured().failed() == 0
                        && result.measured().skipped() == 0));
        boolean anyMeasuredSuccess = results.stream().anyMatch(result -> result.measured().successful() > 0);
        int exitCode = context.completenessPolicy() == CompletenessPolicy.REQUIRED
                ? (complete ? 0 : 1)
                : (anyMeasuredSuccess ? 0 : 1);
        return new BenchmarkStatus(complete, anyMeasuredSuccess, exitCode);
    }
}
