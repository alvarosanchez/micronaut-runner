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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How both reports record a CPU-limited, cold-cache run. */
class RunConditionsReportTest {

    @Test
    void aOneCpuEvictedRunRecordsItsConditionsInBothReports(@TempDir Path output) throws Exception {
        Path sample = Files.createDirectory(output.resolve("sample"));
        // Deliberately not this JVM's count: the Machine line must show what was captured before pinning.
        int machineCpus = Runtime.getRuntime().availableProcessors() + 61;
        RunConditions conditions = new RunConditions(1, machineCpus, "0-3", "0", "1-3", Map.of(0, "0,2"),
                "AMD EPYC 7763 64-Core Processor", 1,
                "-XX:ConcGCThreads=1 -XX:+UseCompressedOops -XX:+UseSerialGC", PageCacheMode.EVICT_ARTIFACTS,
                PageCacheMode.EVICT_ARTIFACTS.evictionMethod("Linux"), "6.8.0-1021-azure", "/dev/root ext4",
                "root 0 Virtual Disk\n└─sda 0 Virtual Disk");
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output, 1, 0, 1, "/hello", false,
                "2026-09-25T00:00:00Z", List.of("runner-stored"), CompletenessPolicy.REQUIRED,
                BenchmarkProvenance.unavailable(), conditions);
        Variant variant = Variant.unavailable("runner-stored", "fixture", "not built");

        Reports.write(output, context, List.of(new VariantResult(variant, -1, List.of(), null, null, null,
                List.of())), List.of());

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("  \"osPageCacheState\": \"evict-artifacts\",\n"), json);
        String recorded = json.substring(json.indexOf("\"conditions\": {"), json.indexOf("\"variants\": ["));
        for (String field : List.of("\"cpuLimit\": 1,", "\"machineCpuCount\": " + machineCpus + ",",
                "\"machineCpus\": \"0-3\",", "\"childCpus\": \"0\",", "\"harnessCpus\": \"1-3\",",
                "\"childCpuSiblings\": {\"0\": \"0,2\"},", "\"cpuModel\": \"AMD EPYC 7763 64-Core Processor\",",
                "\"probeAvailableProcessors\": 1,", "\"probeFlags\": \"-XX:ConcGCThreads=1 -XX:+UseCompressedOops"
                        + " -XX:+UseSerialGC\",", "\"osPageCacheState\": \"evict-artifacts\",",
                "\"evictionMethod\": \"posix_fadvise(POSIX_FADV_DONTNEED) per file\",",
                "\"kernel\": \"6.8.0-1021-azure\",", "\"workDirectoryMount\": \"/dev/root ext4\",",
                "\"workDirectoryDevices\": \"root 0 Virtual Disk\\n└─sda 0 Virtual Disk\"")) {
            assertTrue(recorded.contains(field), field + " in " + recorded);
        }

        String summary = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        assertTrue(summary.contains("· " + machineCpus + " CPUs · AMD EPYC 7763 64-Core Processor\n"), summary);
        assertFalse(summary.contains("· " + Runtime.getRuntime().availableProcessors() + " CPUs"), summary);
        assertTrue(summary.contains("- **CPU limit**: 1 CPU by affinity (`taskset -c 0`"), summary);
        assertTrue(summary.contains("child CPUs `0`, harness CPUs `1-3` (pinned after preparation),"
                + " machine CPUs `0-3`; SMT siblings: cpu0 `0,2`"), summary);
        assertTrue(summary.contains("the probe JVM saw 1 CPU with ergonomic flags `-XX:ConcGCThreads=1"
                + " -XX:+UseCompressedOops -XX:+UseSerialGC`"), summary);
        assertTrue(summary.contains("- **OS page cache**: `evict-artifacts`"), summary);
        assertTrue(summary.contains("evicted (posix_fadvise(POSIX_FADV_DONTNEED) per file)"), summary);
        assertFalse(summary.contains("**OS page cache**: uncontrolled"), summary);
        assertTrue(summary.contains("- **Storage**: kernel `6.8.0-1021-azure`; work directory on `/dev/root ext4`"
                + " (`lsblk -s` name, rotational, model: `root 0 Virtual Disk`, `└─sda 0 Virtual Disk`)\n"), summary);
    }

    @Test
    void theDefaultConditionsAreUnlimitedAndUncontrolled(@TempDir Path output) throws Exception {
        Path sample = Files.createDirectory(output.resolve("sample"));
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output, 1, 0, 1, "/hello", false,
                "2026-09-25T00:00:00Z");

        Reports.write(output, context, List.of(), List.of());

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"cpuLimit\": null,"), json);
        assertTrue(json.contains("\"childCpus\": null,"), json);
        assertTrue(json.contains("\"evictionMethod\": null,"), json);
        String summary = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        assertTrue(summary.contains("· " + Runtime.getRuntime().availableProcessors() + " CPUs\n"), summary);
        assertTrue(summary.contains("- **CPU limit**: none"), summary);
        assertTrue(summary.contains("- **OS page cache**: uncontrolled; discarded warm-ups do not establish a"
                + " controlled warm-cache or cold-filesystem-cache state\n"), summary);
    }
}
