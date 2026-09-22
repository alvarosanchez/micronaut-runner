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

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Deterministic child-process fixture for the benchmark CLI exit/report contract. */
public final class BenchmarkCliFixture {

    private BenchmarkCliFixture() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        CompletenessPolicy policy = CompletenessPolicy.parse(args[1]);
        Path sample = output.resolve("sample");
        Files.createDirectories(sample);
        StartupBenchmark.Options options = new StartupBenchmark.Options(sample, "file:/repo", "1.0", output,
                2, 0, 7L, "/hello", Duration.ofSeconds(1), false, policy);
        List<Variant> variants = List.of(
                Variant.available("a", "fixture a", List.of("java"), Path.of("."), Path.of(".")),
                Variant.available("b", "fixture b", List.of("java"), Path.of("."), Path.of(".")));
        StartupRunner runner = (variant, iteration, warmup) -> {
            if (variant.name().equals("a") && iteration == 1) {
                throw new StartupHarness.RunFailure("fixture timeout", null);
            }
            return new StartupSample(iteration, warmup, 8080, 10, 9, 8, 0.1, 143);
        };
        List<VariantResult> results = StartupBenchmark.measure(runner, variants, options, System.out);
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output,
                2, 0, 7L, "/hello", false, "2026-09-22T00:00:00Z", List.of("a", "b"), policy);
        int exit = StartupBenchmark.finish(context, results, List.of(),
                new PrintStream(PrintStream.nullOutputStream()));
        System.exit(exit);
    }
}
