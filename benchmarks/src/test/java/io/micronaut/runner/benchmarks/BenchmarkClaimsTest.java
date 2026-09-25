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
                List.of(new StartupHarness.DiagnosticRun("exploded-cp", 42.0,
                        List.of("${java}", "-Xlog:class+load=info:file=${diagnostic-log}", "-jar", "${input:0}"))));

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"jvmProcessState\": \"fresh per sample\""));
        assertTrue(json.contains("\"osPageCacheState\": \"uncontrolled\""));
        assertTrue(json.contains("\"applicationCacheMode\": \"per-variant\""));
        assertTrue(json.contains("aggregate shared counts do not prove trained application-class reuse"));
        assertTrue(json.contains("-Xlog:class+load=info:file=${diagnostic-log}"));

        String summary = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        assertTrue(summary.contains("**JVM process**: fresh for every sample"));
        assertTrue(summary.contains("**OS page cache**: uncontrolled"));
        assertTrue(summary.contains("**Application cache**: per variant"));
        assertTrue(summary.contains("| `runner-extracted-aot` | aot (unavailable) | — | — | — | — | — |"));
    }

    @Test
    void reportsAttributeEveryCachedRowToOneTraining(@TempDir Path output) throws Exception {
        Path sample = Files.createDirectory(output.resolve("sample"));
        Path artifact = Files.writeString(output.resolve("app.jar"), "application", StandardCharsets.UTF_8);
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output,
                1, 0, 1, "/hello", false, "2026-09-25T00:00:00Z");
        String reusedSha = "a".repeat(64);
        String trainedSha = "0123456789ab" + "c".repeat(52);
        Variant shadowAot = cached("shadow-aot", artifact, new CacheInfo("aot", "1".repeat(64), 1024, reusedSha,
                900, -1, true, "lifecycle", "verification"));
        Variant storedAot = cached("runner-stored-aot", artifact, new CacheInfo("aot", "2".repeat(64), 2048,
                trainedSha, 9400, 8500, false, "lifecycle", "verification"));
        Variant extractedAot = Variant.unavailable("runner-extracted-aot", "test", "training failed");
        Variant uncached = Variant.unavailable("runner-stored", "test", "not built");
        Reports.write(output, context,
                List.of(result(uncached), result(shadowAot), result(storedAot), result(extractedAot)), List.of());

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\": 6"), json);
        assertTrue(json.contains("\"cacheBytes\": 1024,\n      \"cacheSha256\": \"" + reusedSha + "\""), json);
        assertTrue(json.contains("\"cacheBytes\": 2048,\n      \"cacheSha256\": \"" + trainedSha + "\""), json);
        assertTrue(json.contains("\"cacheBytes\": null,\n      \"cacheSha256\": null"), json);
        assertTrue(json.contains("\"cacheReused\": true"), json);
        assertTrue(json.contains("\"cacheReused\": false"), json);

        String summary = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        String line = "**AOT caches:** reused from an earlier run: `shadow-aot`; trained this run:"
                + " `runner-stored-aot`; unavailable: `runner-extracted-aot`.";
        assertTrue(summary.contains(line), summary);
        int headline = summary.indexOf("Runner + AOT cache vs Shadow + AOT cache: `runner-stored-aot`");
        assertTrue(headline >= 0 && headline < summary.indexOf(line), "the line follows the cached rows");
        assertTrue(summary.indexOf(line) < summary.indexOf("## Run conditions"), summary);
        assertTrue(summary.contains("| `runner-stored-aot` | aot | 2048 B | `0123456789ab` | 8500 ms | 9400 ms"
                + " | false |"), summary);
        assertTrue(summary.contains("| `shadow-aot` | aot | 1024 B | `aaaaaaaaaaaa` | not trained (reused)"
                + " | 900 ms | true |"), summary);
    }

    private static Variant cached(String name, Path artifact, CacheInfo cache) {
        return new Variant(name, "test", List.of("java", "-jar", artifact.toString()), artifact.getParent(),
                artifact, null, EntryMode.STANDARD_LOADER, EntryMode.STANDARD_LOADER, true, null,
                List.of(artifact), cache);
    }

    private static VariantResult result(Variant variant) {
        return new VariantResult(variant, -1, List.of(), null, null, null, List.of());
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
