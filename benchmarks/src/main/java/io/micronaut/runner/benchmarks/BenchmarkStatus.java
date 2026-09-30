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

/**
 * Completeness and exit decision derived from the saved run data.
 *
 * <p>A selection of only opt-in rows has no required variant. Such a run is never {@code complete}, because
 * there is no required comparison to complete, and under either policy it exits {@code 0} only when at least one
 * measured run succeeded: nothing built or nothing measured must not pass for an empty list of requirements.</p>
 *
 * @param complete           whether there is a required variant and every one completed every requested measured
 *                           run
 * @param anyMeasuredSuccess whether any variant, required or not, completed a measured run
 * @param exitCode           the invocation's exit status
 */
record BenchmarkStatus(boolean complete, boolean anyMeasuredSuccess, int exitCode) {

    static BenchmarkStatus evaluate(RunContext context, List<VariantResult> results) {
        boolean gated = hasRequiredVariant(context);
        boolean complete = gated && context.requiredVariants().stream().allMatch(required -> results.stream()
                .filter(result -> result.variant().name().equals(required))
                .anyMatch(result -> result.variant().available()
                        && result.measured().successful() == context.iterations()
                        && result.measured().failed() == 0
                        && result.measured().skipped() == 0));
        boolean anyMeasuredSuccess = results.stream().anyMatch(result -> result.measured().successful() > 0);
        int exitCode = gated && context.completenessPolicy() == CompletenessPolicy.REQUIRED
                ? (complete ? 0 : 1)
                : (anyMeasuredSuccess ? 0 : 1);
        return new BenchmarkStatus(complete, anyMeasuredSuccess, exitCode);
    }

    /**
     * Whether the run has a required variant to gate on. It has none when {@code --variants} named only opt-in
     * rows.
     *
     * @param context the run
     * @return {@code false} when no required variant was selected
     */
    static boolean hasRequiredVariant(RunContext context) {
        return !context.requiredVariants().isEmpty();
    }
}
