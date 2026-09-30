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
package io.micronaut.runner.build.training;

import io.micronaut.runner.build.BuildLogger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The driver against a real child JVM: each test launches {@link TrainingFixture} from a jar, with the
 * {@code java} of {@code runner.test.javaHome}, and ends by checking through {@link ProcessHandle#of(long)}
 * that no process the fixture reported is still alive.
 *
 * <p>No deadline shorter than three seconds depends on a JVM starting.</p>
 */
class TrainingDriverTest {

    /** How long a test waits for something that a JVM has to start for. */
    private static final Duration PATIENCE = Duration.ofSeconds(60);

    /** How long a process may take to be gone once the driver was asked to let go of it. */
    private static final Duration GONE = Duration.ofSeconds(10);

    private static Path fixtureJar;

    @TempDir
    Path temp;

    private final RecordingLogger log = new RecordingLogger();

    @BeforeAll
    static void packTheFixture(@TempDir Path directory) throws IOException {
        fixtureJar = directory.resolve("training-fixture.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, TrainingFixture.class.getName());
        String entry = TrainingFixture.class.getName().replace('.', '/') + ".class";
        try (InputStream in = TrainingFixture.class.getResourceAsStream("/" + entry);
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(fixtureJar), manifest)) {
            assertNotNull(in, "the fixture's class file is not on the test class path");
            out.putNextEntry(new ZipEntry(entry));
            in.transferTo(out);
            out.closeEntry();
        }
    }

    // ------------------------------------------------------------------ (a) serve

    @Test
    void launchesWaitsForReadinessSendsTheWorkloadAndStops() throws Exception {
        AtomicLong callbackPid = new AtomicLong(-1);
        AtomicBoolean aliveInCallback = new AtomicBoolean();
        TrainingSettings settings = settings("")
                .readinessPath("/ready").workloadPaths(List.of("/work")).workloadRepeat(2).build();

        TrainingDriver.Outcome outcome = run(settings, application -> {
            callbackPid.set(application.pid());
            aliveInCallback.set(application.isAlive());
        });

        Map<String, String> report = report();
        assertEquals(Long.parseLong(report.get("pid")), callbackPid.get(),
                "the callback is handed the application's process");
        assertTrue(aliveInCallback.get(), "the application is still up when the callback runs");
        assertFalse(outcome.forced());
        assertTrue(outcome.destroyed());
        if (OS.WINDOWS.isCurrentOs()) {
            assertEquals(1, outcome.exitStatus());
            assertThrows(IOException.class, outcome::requireOrderlyExit);
        } else {
            assertEquals(143, outcome.exitStatus());
            assertDoesNotThrow(outcome::requireOrderlyExit);
        }
        assertTrue(outcome.readiness().compareTo(outcome.total()) <= 0 && !outcome.readiness().isNegative());
        String output = applicationLog();
        assertTrue(output.contains(TrainingFixture.LISTENING), output);
        assertTrue(output.contains("TRAINING FIXTURE work 2") && !output.contains("TRAINING FIXTURE work 3"),
                () -> "the workload is one path, twice:\n" + output);
        assertTrue(log.warnings.isEmpty(), () -> log.warnings.toString());
        assertTrue(log.infos.stream().anyMatch(line -> line.contains("was ready after")), log.infos::toString);
        assertTrue(log.infos.stream().anyMatch(line -> line.contains("workload of 2 requests")),
                log.infos::toString);
        assertTrue(log.infos.stream().anyMatch(line -> line.contains("exited with status")), log.infos::toString);
        assertGone(report, "pid");
    }

    @Test
    void withoutAWorkloadItSendsOneRequestAndWarns() throws Exception {
        TrainingDriver.Outcome outcome = run(settings("").build(), TrainingDriver.AfterWorkload.NOTHING);

        assertFalse(outcome.forced());
        assertEquals(1, log.warnings.size(), log.warnings::toString);
        assertTrue(log.warnings.get(0).contains("No training workload is configured")
                && log.warnings.get(0).contains("workloadPaths"), log.warnings.get(0));
        assertGone(report(), "pid");
    }

    // ------------------------------------------------------------------ (b) output

    @Test
    void anApplicationThatWritesEightMebibytesBeforeItListensStillBecomesReady() throws Exception {
        run(settings("flood").readinessPath("/ready").workloadPaths(List.of("/work")).build(),
                TrainingDriver.AfterWorkload.NOTHING);

        assertTrue(Files.size(applicationLogFile()) >= 8L * 1024 * 1024,
                "all of the application's output goes to the log file");
        assertGone(report(), "pid");
    }

    // ------------------------------------------------------------------ (c), (d), (e) failures

    @Test
    void anApplicationThatNeverListensFailsReadinessQuotingItsOutput() throws Exception {
        TrainingSettings settings = settings("never-listen").readinessTimeout(Duration.ofSeconds(3)).build();
        long started = System.nanoTime();

        IOException failure = assertThrows(IOException.class,
                () -> run(settings, TrainingDriver.AfterWorkload.NOTHING));

        assertTrue(System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(3), "the timeout was waited for");
        String message = failure.getMessage();
        assertTrue(message.contains("during readiness") && message.contains("127.0.0.1:"), message);
        assertTrue(message.contains(applicationLogFile().toString()), message);
        assertTrue(message.contains(TrainingFixture.NEVER_LISTENS), () -> "the log tail is quoted: " + message);
        assertGone(report(), "pid");
    }

    @Test
    void anApplicationThatExitsBeforeItIsReadyFailsAtOnceWithItsStatus() throws Exception {
        long started = System.nanoTime();

        IOException failure = assertThrows(IOException.class,
                () -> run(settings("exit-3").build(), TrainingDriver.AfterWorkload.NOTHING));

        assertTrue(failure.getMessage().contains("exited with status 3 before it was ready"), failure.getMessage());
        assertTrue(System.nanoTime() - started < TrainingSettings.DEFAULT_READINESS_TIMEOUT.toNanos() / 2,
                "the failure does not wait for the readiness timeout");
        assertGone(report(), "pid");
    }

    @Test
    void aWorkloadPathThatAnswers500FailsNamingThePathAndTheStatus() throws Exception {
        TrainingSettings settings = settings("work-500").readinessPath("/ready").workloadPaths(List.of("/work"))
                .build();

        IOException failure = assertThrows(IOException.class,
                () -> run(settings, TrainingDriver.AfterWorkload.NOTHING));

        assertTrue(failure.getMessage().contains("during the workload")
                && failure.getMessage().contains("GET /work answered HTTP 500"), failure.getMessage());
        assertGone(report(), "pid");
    }

    // ------------------------------------------------------------------ (f) workload command

    @Test
    void theWorkloadCommandSeesTheTrainingUrlAndItsExitStatusDecides() throws Exception {
        Path commandReport = temp.resolve("command.report");
        TrainingSettings settings = settings("")
                .workloadCommand(fixtureCommand("command", commandReport)).build();

        run(settings, TrainingDriver.AfterWorkload.NOTHING);

        Map<String, String> application = report();
        Map<String, String> command = report(commandReport);
        assertEquals("http://127.0.0.1:" + application.get("port"), command.get("url"));
        assertTrue(log.warnings.isEmpty(), () -> "a command is a workload: " + log.warnings);
        assertTrue(applicationLog().contains("TRAINING FIXTURE command saw http://127.0.0.1:"),
                "the command's output goes to the same log");
        assertGone(application, "pid");
        assertGone(command, "pid");
    }

    @Test
    void aWorkloadCommandThatOutlivesTheTimeoutFailsAndIsReapedWithItsChildren() throws Exception {
        Path commandReport = temp.resolve("command.report");
        TrainingSettings settings = settings("")
                .workloadCommand(fixtureCommand("command-hang,grandchild", commandReport))
                .workloadTimeout(Duration.ofSeconds(5)).build();

        IOException failure = assertThrows(IOException.class,
                () -> run(settings, TrainingDriver.AfterWorkload.NOTHING));

        assertTrue(failure.getMessage().contains("workloadCommand did not exit within PT5S"), failure.getMessage());
        Map<String, String> command = report(commandReport);
        assertGone(report(), "pid");
        assertGone(command, "pid");
        assertGone(command, "child");
    }

    @Test
    void aWorkloadCommandThatFailsFailsTheRun() throws Exception {
        TrainingSettings settings = settings("")
                .workloadCommand(fixtureCommand("exit-3", temp.resolve("command.report"))).build();

        IOException failure = assertThrows(IOException.class,
                () -> run(settings, TrainingDriver.AfterWorkload.NOTHING));

        assertTrue(failure.getMessage().contains("workloadCommand exited with status 3"), failure.getMessage());
        assertGone(report(), "pid");
    }

    // ------------------------------------------------------------------ (g) interruption

    @Test
    void anInterruptedRunReapsTheApplicationAndRethrows() throws Exception {
        TrainingSettings settings = settings("never-listen").build();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                run(settings, TrainingDriver.AfterWorkload.NOTHING);
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "training-driver-under-test");
        runner.start();
        try {
            Map<String, String> report = awaitReport(reportFile());
            runner.interrupt();
            runner.join(GONE.toMillis());

            assertFalse(runner.isAlive(), "the driver returns once it is interrupted");
            assertInstanceOf(InterruptedException.class, thrown.get());
            assertGone(report, "pid");
        } finally {
            runner.interrupt();
            runner.join(GONE.toMillis());
        }
    }

    // ------------------------------------------------------------------ (h) descendants

    @Test
    void theApplicationsOwnChildrenAreReapedAfterASuccessfulRun() throws Exception {
        run(settings("grandchild").readinessPath("/ready").workloadPaths(List.of("/work")).build(),
                TrainingDriver.AfterWorkload.NOTHING);

        Map<String, String> report = report();
        assertGone(report, "pid");
        assertGone(report, "child");
    }

    @Test
    void theApplicationsOwnChildrenAreReapedAfterAFailedRun() throws Exception {
        TrainingSettings settings = settings("grandchild,work-500").readinessPath("/ready")
                .workloadPaths(List.of("/work")).build();

        assertThrows(IOException.class, () -> run(settings, TrainingDriver.AfterWorkload.NOTHING));

        Map<String, String> report = report();
        assertGone(report, "pid");
        assertGone(report, "child");
    }

    // ------------------------------------------------------------------ (i) stop path

    @Test
    void aStopPathEndsTheApplicationWithAStopRequest() throws Exception {
        TrainingSettings settings = settings("").readinessPath("/ready").workloadPaths(List.of("/work"))
                .stopPath("/stop").build();

        TrainingDriver.Outcome outcome = run(settings, TrainingDriver.AfterWorkload.NOTHING);

        assertEquals(0, outcome.exitStatus());
        assertFalse(outcome.forced());
        assertFalse(outcome.destroyed());
        assertDoesNotThrow(outcome::requireOrderlyExit);
        assertTrue(applicationLog().contains("TRAINING FIXTURE stopping"));
        assertGone(report(), "pid");
    }

    @Test
    void aStopPathThatTheApplicationDoesNotServeFailsTheRun() throws Exception {
        TrainingSettings settings = settings("").workloadPaths(List.of("/work")).stopPath("/no-such-stop").build();

        IOException failure = assertThrows(IOException.class,
                () -> run(settings, TrainingDriver.AfterWorkload.NOTHING));

        assertTrue(failure.getMessage().contains("during the stop")
                && failure.getMessage().contains("POST /no-such-stop answered HTTP 404"), failure.getMessage());
        assertGone(report(), "pid");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "destroy() terminates a Windows process at once")
    void anApplicationThatDoesNotStopInTimeIsKilledAndTheOutcomeSaysSo() throws Exception {
        TrainingSettings settings = settings("stubborn").workloadPaths(List.of("/work"))
                .stopTimeout(Duration.ofSeconds(3)).build();

        TrainingDriver.Outcome outcome = run(settings, TrainingDriver.AfterWorkload.NOTHING);

        assertTrue(outcome.forced());
        assertEquals(137, outcome.exitStatus());
        IOException failure = assertThrows(IOException.class, outcome::requireOrderlyExit);
        assertTrue(failure.getMessage().contains("was killed"), failure.getMessage());
        assertGone(report(), "pid");
    }

    // ------------------------------------------------------------------ (j) run to exit

    @Test
    void runToExitWaitsForTheExitAndCallsBackAfterIt() throws Exception {
        AtomicBoolean aliveInCallback = new AtomicBoolean(true);

        TrainingDriver.Outcome outcome = run(settings("run-to-exit").runToExit(true).build(),
                application -> aliveInCallback.set(application.isAlive()));

        assertEquals(0, outcome.exitStatus());
        assertFalse(outcome.forced());
        assertFalse(outcome.destroyed());
        assertDoesNotThrow(outcome::requireOrderlyExit);
        assertFalse(aliveInCallback.get(), "the callback runs after the application has exited");
        Map<String, String> report = report();
        assertTrue(Integer.parseInt(report.get("port")) > 0, "the port variable arrives in this mode too");
        assertGone(report, "pid");
    }

    @Test
    void runToExitFailsWhenTheApplicationExitsWithAnotherStatus() throws Exception {
        AtomicBoolean called = new AtomicBoolean();

        IOException failure = assertThrows(IOException.class,
                () -> run(settings("exit-3").runToExit(true).build(), _ -> called.set(true)));

        assertTrue(failure.getMessage().contains("exited with status 3"), failure.getMessage());
        assertFalse(called.get(), "a failed run does not call back");
        assertGone(report(), "pid");
    }

    // ------------------------------------------------------------------ (k) the build JVM is terminated

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a terminated Windows JVM runs no shutdown hook")
    void aTerminatedBuildJvmTakesTheApplicationDownWithIt() throws Exception {
        Process build = new ProcessBuilder(java().toString(), "-cp", System.getProperty("java.class.path"),
                TrainingDriverFork.class.getName(), java().toString(), fixtureJar.toString(),
                temp.resolve("work").toString(), reportFile().toString())
                .redirectErrorStream(true)
                .redirectOutput(temp.resolve("build.log").toFile())
                .start();
        try {
            Map<String, String> report = awaitReport(reportFile());
            assertTrue(alive(report, "pid"), "the application is running before the build JVM is ended");

            build.toHandle().destroy();

            assertTrue(build.waitFor(GONE.toMillis(), TimeUnit.MILLISECONDS),
                    () -> "the build JVM did not end: " + readQuietly(temp.resolve("build.log")));
            assertGone(report, "pid");
        } finally {
            build.destroyForcibly().waitFor(GONE.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    // ------------------------------------------------------------------ (l) environment

    @Test
    void theChildEnvironmentDropsTheAmbientJvmOptionsAndAddsTheSettingsAndThePort() {
        Map<String, String> parent = new LinkedHashMap<>();
        parent.put("PATH", "/usr/bin");
        parent.put("JAVA_TOOL_OPTIONS", "-XX:AOTCache=app.aot");
        parent.put("JDK_JAVA_OPTIONS", "-Xmx1g");
        parent.put("_JAVA_OPTIONS", "-Xss1m");
        parent.put("JDK_AOT_VM_OPTIONS", "-Xlog:aot");
        parent.put("OVERRIDDEN", "by the settings");
        TrainingSettings settings = TrainingSettings.builder()
                .environment(Map.of("OVERRIDDEN", "yes", "ADDED", "too"))
                .portVariable("SERVER_PORT")
                .build();

        assertEquals(List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JDK_AOT_VM_OPTIONS"),
                TrainingDriver.ambientVariables(parent));
        assertEquals(Map.of("PATH", "/usr/bin", "OVERRIDDEN", "yes", "ADDED", "too", "SERVER_PORT", "4711"),
                TrainingDriver.childEnvironment(parent, settings, 4711));
        assertEquals(List.of(), TrainingDriver.ambientVariables(Map.of("PATH", "/usr/bin")));
    }

    @Test
    void thePortVariableAndTheEnvironmentReachTheApplication() throws Exception {
        Map<String, String> environment = new LinkedHashMap<>(settings("").build().environment());
        environment.put(TrainingFixture.MARKER, "from the settings");

        run(TrainingSettings.builder().environment(environment).workloadPaths(List.of("/work")).build(),
                TrainingDriver.AfterWorkload.NOTHING);

        Map<String, String> report = report();
        assertEquals("from the settings", report.get("marker"));
        assertTrue(Integer.parseInt(report.get("port")) > 0, "the port the application served on was passed in");
        assertGone(report, "pid");
    }

    // ------------------------------------------------------------------ the log tail

    @Test
    void theLogTailQuotesTheLastThirtyLinesAndCutsLongOnes() throws IOException {
        Path file = temp.resolve("tail.log");
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            lines.add("line " + i);
        }
        lines.add("x".repeat(2000));
        Files.write(file, lines, StandardCharsets.UTF_8);

        String tail = TrainingDriver.logTail(file);

        assertTrue(tail.contains("last 30 lines"), tail);
        assertFalse(tail.contains("line 11\n"), tail);
        assertTrue(tail.contains("line 12\n") && tail.contains("line 40\n"), tail);
        assertTrue(tail.endsWith("x".repeat(500) + " ..."), "a long line is cut");
        assertTrue(TrainingDriver.logTail(temp.resolve("missing.log")).contains("could not be read"));
        Files.createFile(temp.resolve("empty.log"));
        assertTrue(TrainingDriver.logTail(temp.resolve("empty.log")).contains("wrote no output"));
    }

    // ------------------------------------------------------------------ helpers

    /** Settings whose environment selects the fixture's modes and names the report file. */
    private TrainingSettings.Builder settings(String modes) {
        return TrainingSettings.builder().environment(Map.of(
                TrainingFixture.MODE, modes, TrainingFixture.REPORT, reportFile().toString()));
    }

    private TrainingDriver.Outcome run(TrainingSettings settings, TrainingDriver.AfterWorkload afterWorkload)
            throws IOException, InterruptedException {
        return TrainingDriver.run(java(), fixtureJar, List.of(), settings, temp.resolve("work"),
                applicationLogFile(), afterWorkload, log);
    }

    /** The command that runs the fixture as a workload command, in the given modes. */
    private static List<String> fixtureCommand(String modes, Path report) {
        return List.of(java().toString(), "-cp", fixtureJar.toString(), TrainingFixture.class.getName(), modes,
                report.toString());
    }

    private Path reportFile() {
        return temp.resolve("application.report");
    }

    private Path applicationLogFile() {
        return temp.resolve("logs").resolve("application.log");
    }

    private String applicationLog() throws IOException {
        return Files.readString(applicationLogFile());
    }

    private Map<String, String> report() throws IOException, InterruptedException {
        return report(reportFile());
    }

    private static Map<String, String> report(Path file) throws IOException, InterruptedException {
        return awaitReport(file);
    }

    /** Waits for a report file, which the fixture moves into place complete. */
    private static Map<String, String> awaitReport(Path file) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (!Files.isRegularFile(file)) {
            assertTrue(System.nanoTime() < deadline, () -> "the fixture never wrote " + file);
            Thread.sleep(20);
        }
        Map<String, String> report = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file)) {
            int equals = line.indexOf('=');
            report.put(line.substring(0, equals), line.substring(equals + 1));
        }
        return report;
    }

    private static boolean alive(Map<String, String> report, String key) {
        return ProcessHandle.of(Long.parseLong(report.get(key))).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Asserts that a process the fixture reported is gone, giving it the time a reaped process may take. */
    private static void assertGone(Map<String, String> report, String key) throws InterruptedException {
        assertNotNull(report.get(key), () -> "the fixture reported no " + key + ": " + report);
        long deadline = System.nanoTime() + GONE.toNanos();
        while (alive(report, key) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(alive(report, key), () -> "process " + report.get(key) + " (" + key + ") is still alive");
    }

    private static Path java() {
        String home = System.getProperty("runner.test.javaHome", System.getProperty("java.home"));
        Path java = Path.of(home, "bin", "java");
        return Files.isExecutable(java) ? java : Path.of(home, "bin", "java.exe");
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return e.toString();
        }
    }

    /** Keeps every line the driver logs, by level. */
    private static final class RecordingLogger implements BuildLogger {

        private final List<String> infos = new CopyOnWriteArrayList<>();
        private final List<String> warnings = new CopyOnWriteArrayList<>();

        @Override
        public void info(String message) {
            infos.add(message);
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }
    }
}
