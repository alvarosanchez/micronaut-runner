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
package io.micronaut.runner.build.aotcache;

import io.micronaut.runner.build.BuildLogger;
import io.micronaut.runner.build.training.TrainingSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gate's checks on canned logs: no JVM is launched. */
class AotCacheGateTest {

    /** A strict launch's {@code -Xlog:class+load=info} lines: 8 classes, 5 from the cache. */
    private static final List<String> CLASS_LOAD = List.of(
            "[0.004s][info][class,load] java.lang.Object source: shared objects file",
            "[0.010s][info][class,load] com.example.Application source: shared objects file",
            "[0.120s][info][class,load] io.micronaut.core.Foo source: shared objects file",
            "[0.121s][info][class,load] io.micronaut.core.Foo$$Lambda/0x0000000800123456 source: io.micronaut.core.Foo",
            "[0.130s][info][class,load] io.micronaut.http.netty.EpollEventLoopGroupFactory source: file:/app/lib/a.jar",
            "[0.131s][info][class,load] io.micronaut.http.server.netty.HttpRequestEvent source: file:/app/lib/b.jar",
            "[0.140s][info][class,load] io.micronaut.http.Bar source: shared objects file (top)",
            "[0.150s][info][class,load] java.lang.invoke.LambdaForm$MH/0x0000000800234567 source: __JVM_LookupDefineClass__",
            "[0.151s][info][gc] Using G1");

    /** The recording's skip notes, as JDK 25 ({@code warning}) and JDK 27 ({@code info}) log them. */
    private static final List<String> RECORD_LOG = List.of(
            "[0.684s][warning][aot] Skipping io/micronaut/http/netty/EpollEventLoopGroupFactory: Failed verification",
            "[1.068s][info][aot] Skipping io/micronaut/http/server/netty/HttpRequestEvent: JFR event class",
            "[0.684s][warning][aot] Skipping io/micronaut/other/NotLoaded: Not in loaded state",
            "[0.684s][info   ][aot] Skipping java/lang/invoke/BoundMethodHandle$Species_F because it is dynamically"
                    + " generated");

    @TempDir
    Path temp;

    @Test
    void coverageCountsTheSourcesUpToTheWorkload() {
        AotCacheGate.Coverage coverage = AotCacheGate.coverage(CLASS_LOAD, RECORD_LOG);

        assertEquals(8, coverage.classesLoaded());
        assertEquals(4, coverage.classesFromCache());
        assertEquals(0.5, coverage.ratio());
        assertEquals(5, coverage.micronautLoaded());
        assertEquals(2, coverage.micronautFromCache());
        assertEquals(List.of("io.micronaut.core.Foo$$Lambda/0x0000000800123456",
                "io.micronaut.http.netty.EpollEventLoopGroupFactory",
                "io.micronaut.http.server.netty.HttpRequestEvent"), coverage.micronautNotFromCache());
        assertEquals(List.of(), coverage.unexpected(), "a lambda proxy and the two skipped classes are allowed");
        assertEquals(1, coverage.runtimeLambdas());
        assertEquals(0, AotCacheGate.coverage(List.of(), List.of()).ratio());
    }

    @Test
    void theAllowlistReadsVerificationAndJfrSkipsAtEveryLevel() {
        assertEquals(Set.of("io.micronaut.http.netty.EpollEventLoopGroupFactory",
                "io.micronaut.http.server.netty.HttpRequestEvent"), AotCacheGate.allowlist(RECORD_LOG));
        assertEquals(Set.of("a.B"), AotCacheGate.allowlist(List.of(
                "[1.0s][info   ][aot] Skipping a/B: Failed verification")));
    }

    @Test
    void anUnexpectedMicronautClassFailsAndIsListed() {
        List<String> log = new java.util.ArrayList<>(CLASS_LOAD);
        log.add("[0.2s][info][class,load] io.micronaut.inject.Unexpected source: file:/app/lib/c.jar");
        log.add("[0.2s][info][class,load] io.micronaut.inject.Another source: jrt:/x");
        AotCacheGate.Coverage coverage = AotCacheGate.coverage(log, RECORD_LOG);

        assertEquals(List.of("io.micronaut.inject.Unexpected", "io.micronaut.inject.Another"), coverage.unexpected());
        List<String> problems = AotCacheGate.coverageProblems(coverage, 0.95, true);
        assertEquals(2, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("40.0% of the classes came from the cache, less than the minimum of 95.0%"),
                problems.get(0));
        assertTrue(problems.get(1).startsWith("2 io.micronaut classes did not come from the cache")
                && problems.get(1).endsWith("io.micronaut.inject.Unexpected, io.micronaut.inject.Another"),
                problems.get(1));
    }

    @Test
    void withoutEnforcementTheSameLogOnlyWarns() {
        AotCacheGate.Coverage coverage = AotCacheGate.coverage(CLASS_LOAD, List.of());
        assertEquals(2, coverage.unexpected().size(), "without the recording's skips nothing is allowed");
        assertEquals(List.of(), AotCacheGate.coverageProblems(
                AotCacheGate.coverage(CLASS_LOAD, RECORD_LOG), 0.5, true), "0.5 is enough, and every class is allowed");

        // The gate turns problems into failures only when coverage is enforced; the single-JAR target reports them.
        assertEquals(2, AotCacheGate.coverageProblems(coverage, 0.95, false).size());
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add("[0.3s][info][class,load] io.micronaut.many.C" + i + " source: jrt:/x");
        }
        AotCacheGate.Coverage lots = AotCacheGate.coverage(many, List.of());
        String reported = AotCacheGate.coverageProblems(lots, 0, false).get(0);
        assertTrue(reported.endsWith("io.micronaut.many.C9 and 15 more"), reported);
        String enforced = AotCacheGate.coverageProblems(lots, 0, true).get(0);
        assertTrue(enforced.endsWith("io.micronaut.many.C24"), "an enforced failure lists every offender");
        assertFalse(AotCacheSettings.defaults().withEnforceCoverage(false).enforceCoverage());
    }

    @Test
    void probeCountingRunsEveryProbeAndReportsKOfN() throws Exception {
        Deque<Integer> statuses = new ArrayDeque<>(List.of(0, 1, 0, 1, 1));
        List<String> outputs = List.of("", "first failure\n", "", "second failure\n", "third failure\n");
        int[] calls = new int[1];

        AotCacheGate.ProbeRun run = AotCacheGate.runProbes(5, () -> {
            int status = statuses.pop();
            return new Forks.Result(status, outputs.get(calls[0]++));
        });

        assertEquals(5, calls[0], "every probe runs");
        assertEquals(3, run.failures());
        assertEquals("3 of 5 strict probes failed", run.summary());
        assertTrue(run.firstFailure().startsWith("exit status 1") && run.firstFailure().contains("first failure"),
                run.firstFailure());
        AotCacheGate.ProbeRun clean = AotCacheGate.runProbes(10, () -> new Forks.Result(0, ""));
        assertEquals("0 of 10 strict probes failed", clean.summary());
        assertNull(clean.firstFailure());
    }

    @Test
    void anIdentityMismatchFailsBeforeAnyLaunchAndNamesBothVersions() throws IOException {
        JdkProbe built = new JdkProbe("25.0.4.1", "25.0.4.1", "Mac OS X", "aarch64", false);
        JdkProbe verifying = new JdkProbe("27", "27", "Mac OS X", "aarch64", true);
        AotLaunchOptions.writeIdentity(temp.resolve(AotLaunchOptions.IDENTITY_FILE),
                AotLaunchOptions.identity(built, List.of(), List.of(), Map.of("target", "layout")));
        // A java that does not exist: a launch would fail with another message.
        Path java = temp.resolve("no-such-jdk").resolve("bin").resolve("java");

        IOException failure = assertThrows(IOException.class, () -> AotCacheGate.verify(AotCacheSettings.defaults(),
                verifying, java, temp, "app.jar", TrainingSettings.defaults(), AotCacheReport.STOP_JCMD,
                BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains("java.vm.version 25.0.4.1")
                && failure.getMessage().contains("java.vm.version 27"), failure.getMessage());
        String report = Files.readString(temp.resolve(AotCacheReport.FILE));
        assertTrue(report.contains("\"verdict\": \"failed\"") && report.contains("\"probes\": 10")
                && report.contains("\"probeFailures\": 0") && report.contains("\"target\": \"layout\""), report);
        assertNull(AotCacheGate.identityMismatch(built, AotLaunchOptions.readIdentity(
                temp.resolve(AotLaunchOptions.IDENTITY_FILE))));
        assertTrue(AotCacheGate.identityMismatch(new JdkProbe("25.0.4.1", "25.0.4.1", "Linux", "aarch64", false),
                AotLaunchOptions.readIdentity(temp.resolve(AotLaunchOptions.IDENTITY_FILE))).contains("os.name"));
    }
}
