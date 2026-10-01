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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupHarnessTest {

    /** Long enough that a loaded machine starts the fixture in time; only the timeout test sets its own. */
    private static final Duration STARTUP = Duration.ofSeconds(30);

    /**
     * A port with a listener on the loopback address alone is not free, because the readiness probe would reach that
     * listener rather than the application: {@code freePort} passes over such a candidate and takes the next one.
     */
    @Test
    void freePortPassesOverACandidateTheLoopbackAddressHolds() throws Exception {
        try (ServerSocket foreign = loopbackListener()) {
            int taken = foreign.getLocalPort();
            int free = StartupHarness.bindExclusively(new InetSocketAddress(0));
            int[] candidates = {taken, free};
            AtomicInteger offered = new AtomicInteger();

            assertFalse(StartupHarness.freeOnLoopback(taken));
            assertEquals(free, StartupHarness.freePort(() -> candidates[offered.getAndIncrement()]));
            assertEquals(2, offered.get());
            assertThrows(UncheckedIOException.class, () -> StartupHarness.freePort(() -> taken));
        }
    }

    /**
     * On macOS a wildcard bind that reuses addresses succeeds beside a listener on the loopback address alone, which
     * once cost two measured runs to a local VM's port forward. The harness binds without reuse, so the port is
     * refused; on Linux it is refused either way.
     */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void aWildcardBindWithoutReuseIsRefusedBesideALoopbackListener() throws Exception {
        try (ServerSocket foreign = loopbackListener()) {
            assertThrows(IOException.class,
                    () -> StartupHarness.bindExclusively(new InetSocketAddress(foreign.getLocalPort())));
        }
    }

    /**
     * A poll's request timeout ends it only until the response headers arrive. An answer whose body never ends, as
     * a desktop application listening on the loopback address once sent, fails the run one poll timeout after its
     * startup timeout instead of hanging it; an exercise's workload request is bounded by the startup timeout.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void anAnswerThatNeverEndsCannotHangTheRunOrTheExercise(@TempDir Path directory) throws Exception {
        try (StartupHarness harness = new StartupHarness("/ready", Duration.ofSeconds(10), settings(Map.of(),
                ReadinessSnapshot::take, StartupHarness.BeforeLaunch.NONE, Duration.ofMillis(250),
                StartupHarness::freePort))) {
            StartupHarness.RunFailure run = assertThrows(StartupHarness.RunFailure.class,
                    () -> harness.run(fixture("stall", directory.resolve("run.pid")), 0, false));
            assertTrue(run.getMessage().contains("a poll was still waiting for its response"), run.getMessage());
            StartupHarness.RunFailure exercise = assertThrows(StartupHarness.RunFailure.class, () -> harness.exercise(
                    fixture("stall-after-ready", directory.resolve("exercise.pid")), List.of(), List.of("/work")));
            assertTrue(exercise.getMessage().contains("did not answer the workload request"), exercise.getMessage());
        }
    }

    private static ServerSocket loopbackListener() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        return socket;
    }

    @Test
    void successUsesHttpReadinessAndCleansUpTheChild(@TempDir Path directory) throws Exception {
        Path lifecycle = directory.resolve("success.pid");
        StartupSample sample;

        try (StartupHarness harness = harness(Map.of())) {
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
        try (StartupHarness harness = harness(Map.of())) {
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
        try (StartupHarness harness = new StartupHarness("/ready", Duration.ofMillis(150), settings(Map.of(),
                ReadinessSnapshot::take, StartupHarness.BeforeLaunch.NONE, Duration.ofMillis(250),
                StartupHarness::freePort))) {
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
        try (StartupHarness harness = harness(Map.of())) {
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
        try (StartupHarness harness = harness(Map.of())) {
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

    /** The injected option makes the fixture answer 503, so a run or an exercise that it reached would fail. */
    @Test
    void allAmbientJvmOptionVariablesAreRemovedFromRealTimingChildren(@TempDir Path directory) throws Exception {
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                "JDK_AOT_VM_OPTIONS")) {
            Map<String, String> environment = new HashMap<>(System.getenv());
            environment.put(variable, "-Dfixture.injected=" + variable);
            Path timed = directory.resolve(variable + ".pid");
            Path exercised = directory.resolve(variable + "-exercise.pid");

            try (StartupHarness harness = harness(environment)) {
                StartupSample sample = harness.run(fixture("success", timed), 0, false);
                assertTrue(sample.readinessMillis() > 0, variable);
                harness.exercise(fixture("success", exercised), List.of(), List.of("/work"));
            }
            assertStopped(timed);
            assertStopped(exercised);
        }
    }

    @Test
    void anExerciseAnswersItsWorkloadAndEndsWithSigterm(@TempDir Path directory) throws Exception {
        Path lifecycle = directory.resolve("exercise.pid");
        String output;

        try (StartupHarness harness = harness(Map.of())) {
            output = harness.exercise(fixture("success", lifecycle), List.of("-Dfixture.exercise=true"),
                    List.of("/work"));
        }

        assertTrue(output.contains("Startup completed in 12ms"), output);
        assertStopped(lifecycle);
    }

    /** No startup line, no readiness snapshot and no launch hook: an exercise is never a timing run. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void anExerciseNeitherWaitsForTheStartupLineNorProbesNorPreparesTheLaunch(@TempDir Path directory)
            throws Exception {
        Path lifecycle = directory.resolve("no-line.pid");
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger hooks = new AtomicInteger();
        long started = System.nanoTime();

        try (StartupHarness harness = new StartupHarness("/ready", STARTUP, settings(Map.of(), (pid, java) -> {
            probes.incrementAndGet();
            return ReadinessSnapshot.UNAVAILABLE;
        }, variant -> hooks.incrementAndGet(), Duration.ofSeconds(60), StartupHarness::freePort))) {
            harness.exercise(fixture("missing-log", lifecycle), List.of(), List.of());
        }

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsedMillis < 10_000, "the exercise took " + elapsedMillis + " ms");
        assertEquals(0, probes.get());
        assertEquals(0, hooks.get());
        assertStopped(lifecycle);
    }

    @Test
    void anExitOtherThanZeroOrSigtermFailsTheExercise(@TempDir Path directory) throws Exception {
        Path lifecycle = directory.resolve("halt.pid");

        StartupHarness.RunFailure failure;
        try (StartupHarness harness = harness(Map.of())) {
            failure = assertThrows(StartupHarness.RunFailure.class,
                    () -> harness.exercise(fixture("halt-on-stop", lifecycle), List.of(), List.of()));
        }

        assertEquals(3, failure.exitCode());
        assertTrue(failure.getMessage().contains("exited with status 3"), failure.getMessage());
        long pid = Long.parseLong(Files.readString(lifecycle, StandardCharsets.UTF_8));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "child remains alive: " + pid);
    }

    @Test
    void diagnosticRunWritesTheLogAndItsEffectiveCommand(@TempDir Path directory) throws Exception {
        Path log = directory.resolve("class-load.log");

        StartupHarness.DiagnosticRun run;
        try (StartupHarness harness = harness(Map.of())) {
            run = harness.diagnose(fixture("success", directory.resolve("lifecycle")), log);
        }

        assertEquals("success", run.variant());
        assertTrue(run.readinessMillis() > 0);
        assertEquals("-Xlog:class+load=info:file=" + log.toAbsolutePath(), run.command().get(1));
        assertFalse(Files.readString(log, StandardCharsets.UTF_8).isBlank());
    }

    /** The macOS lifetime peak footprint can lag the current one by a page or so, so only its presence is checked. */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void realProbeRecordsMemoryAndClassesAtReadiness(@TempDir Path directory) throws Exception {
        Path lifecycle = directory.resolve("probe.pid");
        StartupSample sample;

        try (StartupHarness harness = harness(Map.of())) {
            sample = harness.run(fixture("success", lifecycle), 0, false);
        }

        ReadinessSnapshot snapshot = sample.atReadiness();
        assertTrue(snapshot.probeMillis() >= 0, snapshot.toString());
        assertTrue(snapshot.rssBytes() > 0, snapshot.toString());
        assertTrue(snapshot.loadedClasses() > 0, snapshot.toString());
        assertTrue(snapshot.sharedClasses() >= 0, snapshot.toString());
        if (OS.MAC.isCurrentOs()) {
            assertTrue(snapshot.footprintBytes() > 0, snapshot.toString());
            assertTrue(snapshot.peakFootprintBytes() > 0, snapshot.toString());
            assertEquals(-1, snapshot.majorFaults(), snapshot.toString());
            assertEquals(-1, snapshot.readBytes(), snapshot.toString());
        } else {
            assertTrue(snapshot.anonBytes() > 0, snapshot.toString());
            assertTrue(snapshot.majorFaults() >= 0, snapshot.toString());
            assertTrue(snapshot.readBytes() >= 0, snapshot.toString());
        }
        assertStopped(lifecycle);
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void classCountsWithoutPerfDataAreUnavailableAndTheRunStillSucceeds(@TempDir Path directory) throws Exception {
        Path lifecycle = directory.resolve("no-perf-data.pid");
        StartupSample sample;

        try (StartupHarness harness = harness(Map.of())) {
            sample = harness.run(fixture("success", lifecycle, "-XX:-UsePerfData"), 0, false);
        }

        assertTrue(sample.readinessMillis() > 0);
        assertEquals(-1, sample.atReadiness().loadedClasses());
        assertEquals(-1, sample.atReadiness().sharedClasses());
        assertTrue(sample.atReadiness().rssBytes() > 0, sample.atReadiness().toString());
        assertStopped(lifecycle);
    }

    /**
     * The probe sleeps, and readiness is final before it is called: the readiness time never exceeds the time from
     * the end of the launch hook, just before the clock starts, to the probe's call, whatever the machine's load.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void aSlowProbeRunsOnceAfterReadinessWhileTheChildIsAlive(@TempDir Path directory) throws Exception {
        Path lifecycle = directory.resolve("slow-probe.pid");
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean aliveDuringProbe = new AtomicBoolean();
        AtomicLong hookReturned = new AtomicLong();
        AtomicLong probeCalled = new AtomicLong();
        ReadinessSnapshot recorded = new ReadinessSnapshot(-1, 42, -1, -1, -1, -1, -1, 7, 3, -1, -1);
        StartupHarness.ReadinessProbe probe = (pid, java) -> {
            probeCalled.set(System.nanoTime());
            calls.incrementAndGet();
            sleep(2_500);
            aliveDuringProbe.set(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            return recorded;
        };
        StartupSample sample;

        try (StartupHarness harness = harness(Map.of(), probe, variant -> hookReturned.set(System.nanoTime()))) {
            sample = harness.run(fixture("success", lifecycle), 0, false);
        }

        assertTrue(sample.readinessMillis() <= (probeCalled.get() - hookReturned.get()) / 1_000_000.0,
                "readiness " + sample.readinessMillis());
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

        try (StartupHarness harness = harness(Map.of(), probe, StartupHarness.BeforeLaunch.NONE)) {
            sample = harness.run(fixture("success", lifecycle), 0, false);
        }

        assertTrue(sample.readinessMillis() > 0);
        assertTrue(sample.atReadiness().probeMillis() >= 0);
        assertEquals(ReadinessSnapshot.UNAVAILABLE.withProbeMillis(sample.atReadiness().probeMillis()),
                sample.atReadiness());
        assertStopped(lifecycle);
    }

    @Test
    void theLaunchHookRunsBeforeEveryLaunchAndItsFailureFailsTheRun(@TempDir Path directory) throws Exception {
        List<String> prepared = new ArrayList<>();
        StartupHarness.BeforeLaunch recording = variant -> prepared.add(variant.name());
        try (StartupHarness harness = harness(Map.of(), ReadinessSnapshot::take, recording)) {
            harness.run(fixture("success", directory.resolve("first.pid")), 0, true);
            harness.run(fixture("success", directory.resolve("second.pid")), 1, false);
        }
        assertEquals(List.of("success", "success"), prepared);

        StartupHarness.BeforeLaunch failing = variant -> {
            throw new IOException("posix_fadvise returned 9");
        };
        try (StartupHarness harness = harness(Map.of(), ReadinessSnapshot::take, failing)) {
            StartupHarness.LaunchPreparationFailure failure = assertThrows(
                    StartupHarness.LaunchPreparationFailure.class,
                    () -> harness.run(fixture("success", directory.resolve("never.pid")), 0, false));
            assertTrue(failure.getMessage().contains("posix_fadvise returned 9"), failure.getMessage());
        }
        assertFalse(Files.exists(directory.resolve("never.pid")), "nothing was spawned");
    }

    /**
     * The hook sleeps, so a hook inside the timed interval would add its sleep to the readiness time. The bound
     * needs no guess about how fast the child starts: the clock starts after the hook returned and readiness is
     * final before the probe runs, so the readiness time can never exceed the time between those two instants.
     */
    @Test
    void theLaunchHookIsOutsideTheTimedInterval(@TempDir Path directory) throws Exception {
        Path lifecycle = directory.resolve("timed.pid");
        AtomicLong hookReturned = new AtomicLong();
        AtomicLong probeCalled = new AtomicLong();
        StartupHarness.BeforeLaunch slow = variant -> {
            sleep(300);
            hookReturned.set(System.nanoTime());
        };
        StartupHarness.ReadinessProbe probe = (pid, java) -> {
            probeCalled.set(System.nanoTime());
            return ReadinessSnapshot.UNAVAILABLE;
        };
        StartupSample sample;

        try (StartupHarness harness = harness(Map.of(), probe, slow)) {
            sample = harness.run(fixture("success", lifecycle), 0, false);
        }

        double hookToProbeMillis = (probeCalled.get() - hookReturned.get()) / 1_000_000.0;
        assertTrue(hookReturned.get() != 0 && probeCalled.get() != 0, "the hook and the probe both ran");
        assertTrue(sample.readinessMillis() <= hookToProbeMillis, "readiness " + sample.readinessMillis()
                + " ms includes the launch hook: only " + hookToProbeMillis + " ms passed between the hook's return"
                + " and the readiness probe");
        assertStopped(lifecycle);
    }

    @Test
    void aChildAliveAfterTheForcedKillFailsTheAttemptOnlyUnderAnEvictingHook() throws Exception {
        StartupHarness.BeforeLaunch evicting = variant -> { };

        StartupHarness.RunFailure failure = assertThrows(StartupHarness.RunFailure.class,
                () -> StartupHarness.requireGone("runner-stored", -1, evicting));
        assertTrue(failure.getMessage().contains("runner-stored was still alive after a forced kill"),
                failure.getMessage());
        assertNull(failure.exitCode());

        assertEquals(143, StartupHarness.requireGone("runner-stored", 143, evicting));
        assertEquals(0, StartupHarness.requireGone("runner-stored", 0, evicting));
        assertEquals(-1, StartupHarness.requireGone("runner-stored", -1, StartupHarness.BeforeLaunch.NONE),
                "without eviction a surviving child is recorded, as before");
    }

    private static StartupHarness harness(Map<String, String> environment) {
        return harness(environment, ReadinessSnapshot::take, StartupHarness.BeforeLaunch.NONE);
    }

    private static StartupHarness harness(Map<String, String> environment,
                                          StartupHarness.ReadinessProbe probe,
                                          StartupHarness.BeforeLaunch beforeLaunch) {
        return new StartupHarness("/ready", STARTUP,
                settings(environment, probe, beforeLaunch, Duration.ofMillis(250), StartupHarness::freePort));
    }

    private static StartupHarness.Settings settings(Map<String, String> environment,
                                                    StartupHarness.ReadinessProbe probe,
                                                    StartupHarness.BeforeLaunch beforeLaunch,
                                                    Duration logLineGrace,
                                                    IntSupplier ports) {
        return new StartupHarness.Settings(Duration.ofMillis(2), Duration.ofSeconds(2), logLineGrace,
                Duration.ofSeconds(2), Duration.ofMillis(50), ports, environment, probe, List.of(), beforeLaunch);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
        return BenchmarkFixtures.variant(mode, command, lifecycle.getParent(), null);
    }
}
