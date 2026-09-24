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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkClaimsTest {

    @Test
    void reportsStateTheUncontrolledCacheConditions(@TempDir Path output) throws Exception {
        Path sample = output.resolve("sample");
        Files.createDirectory(sample);
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output,
                1, 0, 1, "/hello", false, "2026-09-22T00:00:00Z");
        Variant variant = Variant.unavailable("exploded-cp", "test", "not built");
        Variant unavailableAot = Variant.unavailable("runner-extracted-aot", "test", "training failed");
        Reports.write(output, context,
                List.of(
                        new VariantResult(variant, -1, List.of(), null, null, null, List.of()),
                        new VariantResult(unavailableAot, -1, List.of(), null, null, null, List.of())),
                List.of(new StartupHarness.ClassLoadCount("exploded-cp", 12, 3, 42.0,
                        StartupHarness.DIAGNOSTIC_HORIZON,
                        List.of("${java}", "-Xlog:class+load=info:file=${diagnostic-log}", "-jar", "${input:0}"))));

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"jvmProcessState\": \"fresh per sample\""));
        assertTrue(json.contains("\"osPageCacheState\": \"uncontrolled\""));
        assertTrue(json.contains("\"applicationCacheMode\": \"per-variant\""));
        assertTrue(json.contains("aggregate shared counts do not prove trained application-class reuse"));
        assertTrue(json.contains("\"horizon\": \"spawn-through-shutdown\""));
        assertTrue(json.contains("-Xlog:class+load=info:file=${diagnostic-log}"));

        String summary = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        assertTrue(summary.contains("**JVM process**: fresh for every sample"));
        assertTrue(summary.contains("**OS page cache**: uncontrolled"));
        assertTrue(summary.contains("**Application cache**: per variant"));
        assertTrue(summary.contains("| `runner-extracted-aot` | aot (unavailable) | — | — | — | — |"));
        assertTrue(summary.contains("spawn through completed shutdown"));
    }

    @Test
    void childJvmsCannotInheritUndeclaredCacheOptions() {
        ProcessBuilder builder = new ProcessBuilder();
        builder.environment().put("JAVA_TOOL_OPTIONS", "-XX:SharedArchiveFile=unexpected.jsa");
        builder.environment().put("JDK_JAVA_OPTIONS", "-XX:AOTCache=unexpected.aot");
        builder.environment().put("_JAVA_OPTIONS", "-Xshare:off");
        builder.environment().put("JDK_AOT_VM_OPTIONS", "-Xmx2g");

        StartupHarness.removeInheritedJvmOptions(builder);

        assertFalse(builder.environment().containsKey("JAVA_TOOL_OPTIONS"));
        assertFalse(builder.environment().containsKey("JDK_JAVA_OPTIONS"));
        assertFalse(builder.environment().containsKey("_JAVA_OPTIONS"));
        assertFalse(builder.environment().containsKey("JDK_AOT_VM_OPTIONS"));
    }
}
