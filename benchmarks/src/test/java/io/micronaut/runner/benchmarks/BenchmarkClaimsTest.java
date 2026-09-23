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
    void guideMatchesTheImplementedMatrixAndLabelsPlannedCaches() throws Exception {
        String guide = Files.readString(Path.of(System.getProperty("runner.benchmark.guide")),
                StandardCharsets.UTF_8);
        String normalizedGuide = guide.replaceAll("\\s+", " ");

        for (String variant : SampleBuild.variantNames()) {
            assertTrue(guide.contains("`" + variant + "`"), () -> "guide does not name " + variant);
        }
        assertTrue(guide.contains("https://github.com/alvarosanchez/micronaut-runner/issues/45[#45]"));
        assertTrue(normalizedGuide.contains("runner-stored-cds"));
        assertTrue(normalizedGuide.contains("-Xshare:on"));
        assertTrue(normalizedGuide.contains("-Xshare:auto"));
        assertTrue(normalizedGuide.contains("application class"));
        assertTrue(normalizedGuide.contains("custom-loader CDS"));
        assertTrue(normalizedGuide.contains("cold JVM"));
        assertTrue(normalizedGuide.contains("OS page cache"));
        assertTrue(normalizedGuide.contains("default JDK class sharing"));
        assertTrue(normalizedGuide.contains("trained application cache"));
        assertTrue(normalizedGuide.contains("cold-storage performance has not been measured"));
        assertTrue(normalizedGuide.contains("descriptive-only"));
        assertTrue(normalizedGuide.contains("complete measured iteration pairs"));
        assertTrue(normalizedGuide.contains("reporting threshold, not a universal guarantee"));

        assertFalse(guide.contains("each also with a CDS archive"));
        assertFalse(guide.contains("Both a warm and a cold page cache are measured"));
        assertFalse(guide.contains("cold-page-cache case is measured separately"));
    }

    @Test
    void reportsStateTheUncontrolledCacheConditions(@TempDir Path output) throws Exception {
        Path sample = output.resolve("sample");
        Files.createDirectory(sample);
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output,
                1, 0, 1, "/hello", false, "2026-09-22T00:00:00Z");
        Variant variant = Variant.unavailable("exploded-cp", "test", "not built");
        Reports.write(output, context,
                List.of(new VariantResult(variant, -1, List.of(), null, null, null, List.of())),
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
        assertTrue(summary.contains("spawn through completed shutdown"));
    }

    @Test
    void childJvmsCannotInheritUndeclaredCacheOptions() {
        ProcessBuilder builder = new ProcessBuilder();
        builder.environment().put("JAVA_TOOL_OPTIONS", "-XX:SharedArchiveFile=unexpected.jsa");
        builder.environment().put("JDK_JAVA_OPTIONS", "-XX:AOTCache=unexpected.aot");
        builder.environment().put("_JAVA_OPTIONS", "-Xshare:off");

        StartupHarness.removeInheritedJvmOptions(builder);

        assertFalse(builder.environment().containsKey("JAVA_TOOL_OPTIONS"));
        assertFalse(builder.environment().containsKey("JDK_JAVA_OPTIONS"));
        assertFalse(builder.environment().containsKey("_JAVA_OPTIONS"));
    }
}
