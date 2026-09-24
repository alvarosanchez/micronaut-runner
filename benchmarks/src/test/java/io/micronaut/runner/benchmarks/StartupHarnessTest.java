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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupHarnessTest {

    @Test
    void successUsesHttpReadinessAndCleansUpTheChild(@TempDir Path directory) throws Exception {
        Path lifecycle = directory.resolve("success.pid");
        StartupSample sample;

        try (StartupHarness harness = harness(Duration.ofSeconds(2), Map.of())) {
            sample = harness.run(fixture("success", lifecycle), 3, false);
        }

        assertEquals(3, sample.iteration());
        assertTrue(sample.readinessMillis() > 0);
        assertTrue(sample.logLineMillis() >= 0);
        assertStopped(lifecycle);
    }

    @Test
    void earlyExitIncludesTheRealStatusAndCleansUp(@TempDir Path directory) throws Exception {
        Path lifecycle = directory.resolve("early.pid");

        StartupHarness.RunFailure failure;
        try (StartupHarness harness = harness(Duration.ofSeconds(2), Map.of())) {
            failure = assertThrows(StartupHarness.RunFailure.class,
                    () -> harness.run(fixture("early-exit", lifecycle), 0, false));
        }

        assertEquals(7, failure.exitCode());
        assertStopped(lifecycle);
    }

    /**
     * The 150 ms timeout can fire before the child JVM reaches {@code main}, so neither its PID file nor its
     * shutdown hook is guaranteed. What the timeout path does guarantee is that {@code run} returns only after
     * the child is gone.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void timeoutCleansUpTheHungChild(@TempDir Path directory) throws Exception {
        Set<Long> before = childPids();

        StartupHarness.RunFailure failure;
        try (StartupHarness harness = harness(Duration.ofMillis(150), Map.of())) {
            failure = assertThrows(StartupHarness.RunFailure.class,
                    () -> harness.run(fixture("hang", directory.resolve("timeout.pid")), 0, false));
        }

        assertTrue(failure.getMessage().contains("did not answer"), failure.getMessage());
        List<ProcessHandle> alive = ProcessHandle.current().children()
                .filter(child -> !before.contains(child.pid()))
                .filter(ProcessHandle::isAlive)
                .toList();
        assertTrue(alive.isEmpty(), "timed-out child still alive: " + alive);
    }

    @Test
    void sustainedNon200ResponseIsNotMisreportedAsSlowStartup(@TempDir Path directory) throws Exception {
        Path lifecycle = directory.resolve("non-200.pid");

        StartupHarness.RunFailure failure;
        try (StartupHarness harness = harness(Duration.ofSeconds(2), Map.of())) {
            failure = assertThrows(StartupHarness.RunFailure.class,
                    () -> harness.run(fixture("non-200", lifecycle), 0, false));
        }

        assertTrue(failure.getMessage().contains("HTTP 503"));
        assertStopped(lifecycle);
    }

    @Test
    void delayedAndMissingStartupLinesDoNotChangeReadiness(@TempDir Path directory) throws Exception {
        StartupSample delayed;
        StartupSample missing;
        try (StartupHarness harness = harness(Duration.ofSeconds(2), Map.of())) {
            delayed = harness.run(fixture("delayed-log", directory.resolve("delayed.pid")), 0, false);
            missing = harness.run(fixture("missing-log", directory.resolve("missing.pid")), 1, false);
        }

        assertTrue(delayed.logLineMillis() > delayed.readinessMillis());
        assertEquals(123, delayed.frameworkMillis());
        assertEquals(-1, missing.logLineMillis());
        assertEquals(-1, missing.frameworkMillis());
        assertStopped(directory.resolve("delayed.pid"));
        assertStopped(directory.resolve("missing.pid"));
    }

    @Test
    void allAmbientJvmOptionVariablesAreRemovedFromRealTimingChildren(@TempDir Path directory) throws Exception {
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                "JDK_AOT_VM_OPTIONS")) {
            Map<String, String> environment = new HashMap<>(System.getenv());
            environment.put(variable, "-Dfixture.injected=" + variable);
            Path lifecycle = directory.resolve(variable + ".pid");

            try (StartupHarness harness = harness(Duration.ofSeconds(2), environment)) {
                StartupSample sample = harness.run(fixture("success", lifecycle), 0, false);
                assertTrue(sample.readinessMillis() > 0, variable);
            }
            assertStopped(lifecycle);
        }
    }

    @Test
    void diagnosticRunWritesTheLogAndARelocatableCommand(@TempDir Path directory) throws Exception {
        Path log = directory.resolve("class-load.log");
        Variant variant = fixture("success", directory.resolve("lifecycle"));

        StartupHarness.DiagnosticRun run;
        try (StartupHarness harness = new StartupHarness("/ready", Duration.ofSeconds(5))) {
            run = harness.diagnose(variant, log);
        }

        assertEquals("success", run.variant());
        assertTrue(run.readinessMillis() > 0);
        assertTrue(run.command().contains("-Xlog:class+load=info:file=${diagnostic-log}"));
        assertFalse(String.join(" ", run.command()).contains(directory.toString()));
        assertFalse(Files.readString(log, StandardCharsets.UTF_8).isBlank());
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void realProbeRecordsMemoryAndClassesAtReadiness(@TempDir Path directory) throws Exception {
        Path lifecycle = directory.resolve("probe.pid");
        StartupSample sample;

        try (StartupHarness harness = harness(Duration.ofSeconds(2), Map.of())) {
            sample = harness.run(fixture("success", lifecycle), 0, false);
        }

        ReadinessSnapshot snapshot = sample.atReadiness();
        assertTrue(snapshot.probeMillis() >= 0, snapshot.toString());
        assertTrue(snapshot.rssBytes() > 0, snapshot.toString());
        assertTrue(snapshot.loadedClasses() > 0, snapshot.toString());
        assertTrue(snapshot.sharedClasses() >= 0, snapshot.toString());
        if (OS.MAC.isCurrentOs()) {
            assertTrue(snapshot.footprintBytes() > 0, snapshot.toString());
            assertTrue(snapshot.peakFootprintBytes() >= snapshot.footprintBytes(), snapshot.toString());
        } else {
            assertTrue(snapshot.anonBytes() > 0, snapshot.toString());
        }
        assertStopped(lifecycle);
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void classCountsWithoutPerfDataAreUnavailableAndTheRunStillSucceeds(@TempDir Path directory) throws Exception {
        Path lifecycle = directory.resolve("no-perf-data.pid");
        StartupSample sample;

        try (StartupHarness harness = harness(Duration.ofSeconds(2), Map.of())) {
            sample = harness.run(fixture("success", lifecycle, "-XX:-UsePerfData"), 0, false);
        }

        assertTrue(sample.readinessMillis() > 0);
        assertEquals(-1, sample.atReadiness().loadedClasses());
        assertEquals(-1, sample.atReadiness().sharedClasses());
        assertTrue(sample.atReadiness().rssBytes() > 0, sample.atReadiness().toString());
        assertStopped(lifecycle);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aSlowProbeRunsOnceAfterReadinessWhileTheChildIsAlive(@TempDir Path directory) throws Exception {
        Path lifecycle = directory.resolve("slow-probe.pid");
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean aliveDuringProbe = new AtomicBoolean();
        ReadinessSnapshot recorded = new ReadinessSnapshot(-1, 42, -1, -1, -1, -1, -1, 7, 3);
        StartupHarness.ReadinessProbe probe = (pid, java) -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(2_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            aliveDuringProbe.set(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            return recorded;
        };
        StartupSample sample;

        try (StartupHarness harness = harness(Duration.ofSeconds(2), Map.of(), probe)) {
            sample = harness.run(fixture("success", lifecycle), 0, false);
        }

        assertTrue(sample.readinessMillis() < 2_000, "readiness " + sample.readinessMillis());
        assertEquals(1, calls.get());
        assertTrue(aliveDuringProbe.get());
        assertEquals(42, sample.atReadiness().rssBytes());
        assertEquals(7, sample.atReadiness().loadedClasses());
        assertEquals(3, sample.atReadiness().sharedClasses());
        assertTrue(sample.atReadiness().probeMillis() >= 2_500, "probe " + sample.atReadiness().probeMillis());
        assertStopped(lifecycle);
    }

    @Test
    void aThrowingProbeLeavesTheSnapshotUnavailableAndTheRunSuccessful(@TempDir Path directory) throws Exception {
        Path lifecycle = directory.resolve("throwing-probe.pid");
        StartupHarness.ReadinessProbe probe = (pid, java) -> {
            throw new IllegalStateException("probe failure");
        };
        StartupSample sample;

        try (StartupHarness harness = harness(Duration.ofSeconds(2), Map.of(), probe)) {
            sample = harness.run(fixture("success", lifecycle), 0, false);
        }

        assertTrue(sample.readinessMillis() > 0);
        assertTrue(sample.atReadiness().probeMillis() >= 0);
        assertEquals(ReadinessSnapshot.UNAVAILABLE.withProbeMillis(sample.atReadiness().probeMillis()),
                sample.atReadiness());
        assertStopped(lifecycle);
    }

    private static StartupHarness harness(Duration startupTimeout, Map<String, String> environment) {
        return harness(startupTimeout, environment, ReadinessSnapshot::take);
    }

    private static StartupHarness harness(Duration startupTimeout,
                                          Map<String, String> environment,
                                          StartupHarness.ReadinessProbe probe) {
        return new StartupHarness("/ready", startupTimeout, new StartupHarness.Settings(
                Duration.ofMillis(2), Duration.ofMillis(100), Duration.ofMillis(250),
                Duration.ofSeconds(2), Duration.ofMillis(50), StartupHarness::freePort, environment, probe));
    }

    private static Set<Long> childPids() {
        return ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());
    }

    private static void assertStopped(Path lifecycle) throws Exception {
        Path stopped = lifecycle.resolveSibling(lifecycle.getFileName() + ".stopped");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Files.isRegularFile(stopped) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.isRegularFile(stopped), "shutdown hook did not run for " + lifecycle);
        long pid = Long.parseLong(Files.readString(lifecycle, StandardCharsets.UTF_8));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "child remains alive: " + pid);
    }

    private static Variant fixture(String mode, Path lifecycle, String... jvmOptions) {
        List<String> command = new ArrayList<>();
        command.add(SampleBuild.javaExecutable().toString());
        command.addAll(List.of(jvmOptions));
        command.addAll(List.of("-cp", System.getProperty("java.class.path"),
                StartupHarnessFixture.class.getName(), mode, lifecycle.toString()));
        return Variant.available(mode, "fixture " + mode, command, lifecycle.getParent(), lifecycle.getParent());
    }
}
