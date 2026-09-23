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
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    @Test
    void timeoutCleansUpTheHungChild(@TempDir Path directory) throws Exception {
        Path lifecycle = directory.resolve("timeout.pid");

        StartupHarness.RunFailure failure;
        try (StartupHarness harness = harness(Duration.ofMillis(150), Map.of())) {
            failure = assertThrows(StartupHarness.RunFailure.class,
                    () -> harness.run(fixture("hang", lifecycle), 0, false));
        }

        assertTrue(failure.getMessage().contains("did not answer"));
        assertStopped(lifecycle);
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
    void diagnosticCountsAreHonestlyLabelledThroughShutdown(@TempDir Path directory) throws Exception {
        Path log = directory.resolve("class-load.log");
        Variant variant = fixture("diagnostic", directory.resolve("lifecycle"));

        StartupHarness.ClassLoadCount count;
        try (StartupHarness harness = new StartupHarness("/ready", Duration.ofSeconds(5))) {
            count = harness.diagnose(variant, log);
        }

        assertEquals("spawn-through-shutdown", count.horizon());
        assertTrue(count.command().contains("-Xlog:class+load=info:file=${diagnostic-log}"));
        assertFalse(String.join(" ", count.command()).contains(directory.toString()));
        assertTrue(count.classesLoaded() > 0);
        assertTrue(Files.readString(log, StandardCharsets.UTF_8)
                .contains(StartupHarnessFixture.ShutdownMarker.class.getName()));
    }

    private static StartupHarness harness(Duration startupTimeout, Map<String, String> environment) {
        return new StartupHarness("/ready", startupTimeout, new StartupHarness.Settings(
                Duration.ofMillis(2), Duration.ofMillis(100), Duration.ofMillis(250),
                Duration.ofSeconds(2), Duration.ofMillis(50), StartupHarness::freePort, environment));
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

    private static Variant fixture(String mode, Path lifecycle) {
        return Variant.available(mode, "fixture " + mode, List.of(
                SampleBuild.javaExecutable().toString(),
                "-cp", System.getProperty("java.class.path"),
                StartupHarnessFixture.class.getName(), mode, lifecycle.toString()),
                lifecycle.getParent(), lifecycle.getParent());
    }
}
