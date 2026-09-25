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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The CPU limit's parsing and validation, fed injected inputs so it runs on any OS. */
class CpuLimitTest {

    private static final String LINUX = "Linux";

    @Test
    void theFirstAllowedCpusAreTheChildsAndTheRestTheHarnesss() {
        CpuLimit limit = CpuLimit.validate(1, LINUX, "0-3", true);

        assertEquals(List.of(0), limit.child());
        assertEquals(List.of(1, 2, 3), limit.harness());
        assertEquals(List.of(0, 1, 2, 3), limit.machine());
        assertEquals("0", CpuLimit.format(limit.child()));
        assertEquals("1-3", CpuLimit.format(limit.harness()));
        assertEquals(List.of("taskset", "-c", "0"), limit.commandPrefix());
        assertEquals(List.of("cpus=1"), limit.relevantJvmFlags());
        assertEquals(List.of("taskset", "-a", "-c", "-p", "1-3", "4242"), limit.pinCommand(4242));

        CpuLimit two = CpuLimit.validate(2, LINUX, "0,2,4-5", true);
        assertEquals(List.of(0, 2), two.child());
        assertEquals(List.of(4, 5), two.harness());
        assertEquals(List.of("taskset", "-c", "0,2"), two.commandPrefix());
    }

    @Test
    void cpuListsParseAndFormatTheKernelSyntax() {
        assertEquals(List.of(0, 1, 2, 3), CpuLimit.parseList("0-3"));
        assertEquals(List.of(0, 2, 4, 5), CpuLimit.parseList("0,2,4-5"));
        assertEquals(List.of(7), CpuLimit.parseList(" 7\n"));
        assertEquals("0,2,4-5", CpuLimit.format(CpuLimit.parseList("0,2,4-5")));
        assertEquals("0-3,8-11", CpuLimit.format(CpuLimit.parseList("8-11,0-3")));
        for (String malformed : new String[] {"", "a", "3-1", "0,,2", "-1", "0-"}) {
            assertThrows(IllegalArgumentException.class, () -> CpuLimit.parseList(malformed), malformed);
        }
    }

    @Test
    void theAllowedListComesFromProcSelfStatus() {
        String status = "Name:\tjava\nCpus_allowed:\t3f\nCpus_allowed_list:\t0-5\nMems_allowed_list:\t0\n";

        assertEquals("0-5", CpuLimit.allowedList(status));
        assertNull(CpuLimit.allowedList("Name:\tjava\n"));
        assertNull(CpuLimit.allowedList(null));
    }

    @Test
    void zeroTheWholeMachineAndMoreAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CpuLimit.validate(0, LINUX, "0-3", true));
        IllegalArgumentException all = assertThrows(IllegalArgumentException.class,
                () -> CpuLimit.validate(4, LINUX, "0-3", true));
        assertTrue(all.getMessage().contains("between 1 and 3"), all.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CpuLimit.validate(5, LINUX, "0-3", true));
        assertThrows(IllegalArgumentException.class, () -> CpuLimit.validate(1, LINUX, "0", true));
        assertThrows(IllegalArgumentException.class, () -> CpuLimit.validate(-1, LINUX, "0-3", true));
    }

    @Test
    void anyValueIsRejectedOffLinuxOrWithoutTaskset() {
        for (String os : new String[] {"Mac OS X", "Windows 11", "FreeBSD"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> CpuLimit.validate(1, os, "0-3", true), os);
            assertTrue(failure.getMessage().contains("needs Linux"), failure.getMessage());
        }
        IllegalArgumentException noTaskset = assertThrows(IllegalArgumentException.class,
                () -> CpuLimit.validate(1, LINUX, "0-3", false));
        assertTrue(noTaskset.getMessage().contains("taskset"), noTaskset.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CpuLimit.validate(1, LINUX, null, true));
    }

    @Test
    void aNonNumberIsAUsageError(@TempDir Path output) {
        String[] arguments = {"--sample", output.toString(), "--repo", "file:/repo", "--version", "1.0",
                "--iterations", "1", "--out", output.toString(), "--cpus", "one"};

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StartupBenchmark.Options.parse(arguments));
        assertTrue(failure.getMessage().contains("--cpus needs a number"), failure.getMessage());
    }

    @Test
    void withoutTheOptionThereIsNoLimit(@TempDir Path output) {
        String[] arguments = {"--sample", output.toString(), "--repo", "file:/repo", "--version", "1.0",
                "--iterations", "1", "--out", output.toString()};

        assertNull(StartupBenchmark.Options.parse(arguments).cpus());
        assertEquals(1, StartupBenchmark.Options.parse(append(arguments, "--cpus", "1")).cpus());
    }

    @Test
    void theProbeOutputGivesTheCountAndTheErgonomicFlags() {
        CpuLimit.Probe probe = CpuLimit.parseProbe("""
                -XX:ConcGCThreads=1 -XX:InitialHeapSize=16777216 -XX:MaxHeapSize=262144000 \
                -XX:+UseCompressedOops -XX:+UseSerialGC
                1
                """);

        assertEquals(1, probe.availableProcessors());
        assertTrue(probe.flags().endsWith("-XX:+UseSerialGC"), probe.flags());
        assertEquals(-1, CpuLimit.parseProbe("Error: Could not find or load main class\n").availableProcessors());
        assertEquals(List.of("taskset", "-c", "0", "/jdk/bin/java", "-XX:+PrintCommandLineFlags", "-cp", "cp",
                        CpuProbe.class.getName()),
                CpuLimit.probeCommand(List.of("taskset", "-c", "0"), Path.of("/jdk/bin/java"), "cp"));
    }

    @Test
    void theCpuModelIsLscpusModelName() {
        String lscpu = """
                Architecture:             x86_64
                  CPU op-mode(s):         32-bit, 64-bit
                Vendor ID:                AuthenticAMD
                  Model name:             AMD EPYC 7763 64-Core Processor
                    CPU family:           25
                """;

        assertEquals("AMD EPYC 7763 64-Core Processor", CpuLimit.modelName(lscpu));
        assertNull(CpuLimit.modelName("Architecture: aarch64\n"));
    }

    /** The probe the harness runs before the sample build, under a real one-CPU affinity mask. */
    @Test
    @Tag("benchmark-integration")
    @EnabledOnOs(OS.LINUX)
    void theProbeSeesOneCpuUnderTasksetAndItsCollector() throws Exception {
        assumeTrue(CpuLimit.onPath("taskset", System.getenv("PATH")), "taskset is not installed");
        String allowed = CpuLimit.machineList();
        assertNotNull(allowed);
        int first = CpuLimit.parseList(allowed).get(0);

        CpuLimit.Probe probe = CpuLimit.runProbe(List.of("taskset", "-c", Integer.toString(first)),
                SampleBuild.javaExecutable(), System.getProperty("java.class.path"));

        assertEquals(1, probe.availableProcessors());
        assertNotNull(probe.flags());
        assertTrue(probe.flags().matches(".*-XX:\\+Use[A-Za-z]+GC.*"), probe.flags());
        if (Runtime.version().feature() == 25) {
            assertTrue(probe.flags().contains("-XX:+UseSerialGC"), probe.flags());
        }
    }

    private static String[] append(String[] arguments, String... more) {
        String[] result = new String[arguments.length + more.length];
        System.arraycopy(arguments, 0, result, 0, arguments.length);
        System.arraycopy(more, 0, result, arguments.length, more.length);
        return result;
    }
}
