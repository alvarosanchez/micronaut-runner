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
import java.util.List;

/** The specs, variants, results and run contexts the harness and report tests build. */
final class BenchmarkFixtures {

    private BenchmarkFixtures() {
    }

    /**
     * The table's row of that name, or else a core, uncached row entered by the JDK's own loader.
     *
     * @param name the row
     * @return its spec
     */
    static SampleBuild.VariantSpec spec(String name) {
        return SampleBuild.allVariantNames().contains(name) ? SampleBuild.spec(name)
                : new SampleBuild.VariantSpec(name, "fixture " + name, EntryMode.STANDARD_LOADER, false, false, null);
    }

    /**
     * A built variant that runs a command from a directory.
     *
     * @param name             the row, see {@link #spec(String)}
     * @param command          the command
     * @param workingDirectory where it runs
     * @param deploymentSize   its size, or {@code null}
     * @param launchInputs     its launch inputs
     * @return the variant
     */
    static Variant variant(String name, List<String> command, Path workingDirectory, DeploymentSize deploymentSize,
                           Path... launchInputs) {
        return Variant.available(spec(name), command, workingDirectory,
                launchInputs.length == 0 ? null : launchInputs[0], deploymentSize, spec(name).entryMode(), null,
                List.of(launchInputs));
    }

    /**
     * A built variant that runs {@code java} and has no launch input.
     *
     * @param name the row
     * @return the variant
     */
    static Variant variant(String name) {
        return variant(name, List.of("java"), Path.of("."), null);
    }

    /**
     * The result of a variant that was never attempted, out of one requested measured run.
     *
     * @param variant the variant
     * @return the result
     */
    static VariantResult result(Variant variant) {
        return VariantResult.summarize(variant, List.of(), 0, 1, 123L);
    }

    /**
     * A run with seed 123, no warm-up, readiness at {@code /hello}, default conditions and its sample, output and
     * work directories under {@code output}.
     *
     * @param output           the output directory
     * @param iterations       the measured iterations
     * @param requiredVariants the rows that gate the exit code
     * @param policy           the completeness policy
     * @param provenance       the provenance
     * @return the context
     */
    static RunContext context(Path output, int iterations, List<String> requiredVariants, CompletenessPolicy policy,
                              BenchmarkProvenance provenance) {
        return new RunContext(output.resolve("sample"), "1.0", output, output.resolve("work"), iterations, 0, 123L,
                "/hello", false, "2026-10-01T00:00:00Z", requiredVariants, policy, provenance,
                RunConditions.defaults());
    }
}
