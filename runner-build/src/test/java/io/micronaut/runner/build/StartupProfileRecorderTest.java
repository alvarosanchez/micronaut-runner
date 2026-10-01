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
package io.micronaut.runner.build;

import io.micronaut.runner.build.training.TrainingSettings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recorder against a real runner jar: a STORED archive with the entry stub, built here with
 * {@link RunnerJarBuilder}, whose application is a small HTTP server. The server loads {@code WorkloadOnly}
 * when it serves {@code /work} and {@code ShutdownOnly} from a shutdown hook, which is what tells a profile
 * taken before the stop from one taken after it.
 */
class StartupProfileRecorderTest {

    private static final String RERECORD = "./record-it-again --please";

    private static final String APP = """
            package fixture;

            import com.sun.net.httpserver.HttpExchange;
            import com.sun.net.httpserver.HttpServer;

            import java.net.InetAddress;
            import java.net.InetSocketAddress;

            public final class ProfileApp {

                public static void main(String[] args) throws Exception {
                    Thread lifetime = new Thread(() -> {
                        try {
                            Thread.sleep(180_000);
                        } catch (InterruptedException ignored) {
                        }
                        Runtime.getRuntime().halt(99);
                    });
                    lifetime.setDaemon(true);
                    lifetime.start();
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println(ShutdownOnly.describe())));
                    if ("exit".equals(System.getenv("PROFILE_APP_MODE"))) {
                        System.out.println(WorkloadOnly.describe());
                        return;
                    }
                    int port = Integer.parseInt(System.getenv("MICRONAUT_SERVER_PORT"));
                    HttpServer server = HttpServer.create(
                            new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 0);
                    server.createContext("/ready", exchange -> respond(exchange, "ready"));
                    server.createContext("/work", exchange -> respond(exchange, WorkloadOnly.describe()));
                    server.start();
                    System.out.println("PROFILE APP listening");
                }

                private static void respond(HttpExchange exchange, String body) throws java.io.IOException {
                    byte[] bytes = body.getBytes();
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                }
            }
            """;

    private static final String WORKLOAD_ONLY = """
            package fixture;

            public final class WorkloadOnly {
                public static String describe() {
                    return "loaded by the workload";
                }
            }
            """;

    private static final String SHUTDOWN_ONLY = """
            package fixture;

            public final class ShutdownOnly {
                public static String describe() {
                    return "loaded by the shutdown hook";
                }
            }
            """;

    private static Path runnerJar;

    @TempDir
    Path temp;

    private final RecordingLogger log = new RecordingLogger();

    @BeforeAll
    static void packageTheFixture(@TempDir Path directory) throws IOException {
        Assumptions.assumeTrue(
                RunnerJarBuilder.class.getResource("/META-INF/micronaut-runner/launcher.jar") != null,
                "the bundled launcher jar is not on the test class path");
        Path classes = ClassFixtures.compile(directory.resolve("sources"), directory.resolve("classes"),
                List.of("--release", "25"), Map.of(
                        "fixture/ProfileApp.java", APP,
                        "fixture/WorkloadOnly.java", WORKLOAD_ONLY,
                        "fixture/ShutdownOnly.java", SHUTDOWN_ONLY));
        runnerJar = directory.resolve("profile-app.jar");
        RunnerJarBuilder.build(RunnerJarSpec.builder()
                .mainClass("fixture.ProfileApp")
                .applicationOutput(List.of(classes))
                .dependencies(List.of())
                .output(runnerJar)
                .compression(Compression.STORED)
                .entryStub(true)
                .build(), BuildLogger.noOp());
    }

    @Test
    void recordsTheClassesLoadedUpToTheEndOfTheWorkloadAndNotThoseOfTheShutdown() throws Exception {
        Path work = temp.resolve("build/record");
        Path profile = temp.resolve("project/src/main/micronaut-runner/startup-classes.txt");

        int recorded = StartupProfileRecorder.record(java(), runnerJar, serverSettings(), work, profile, RERECORD,
                log);

        String content = Files.readString(profile, StandardCharsets.UTF_8);
        List<String> lines = content.lines().toList();
        assertTrue(lines.get(0).startsWith("# Micronaut Runner startup profile"), lines.get(0));
        assertTrue(lines.contains("# re-record: " + RERECORD), content);
        assertTrue(lines.contains("# jdk: " + StartupProfileRecorder.jdkVersion(java())), content);
        assertTrue(lines.contains("# jvm: -Djava.util.concurrent.ForkJoinPool.common.parallelism=0"), content);
        assertTrue(lines.contains("# workload: GET /work x1"), content);
        List<String> classes = lines.stream()
                .filter(line -> !line.startsWith("#") && !line.startsWith("jrt:")).toList();
        assertEquals(recorded, classes.size());
        assertTrue(lines.contains("# classes: " + recorded), content);
        long jdkClasses = lines.stream().filter(line -> line.startsWith("jrt:")).count();
        assertTrue(lines.contains("# jdk-classes: " + jdkClasses), content);

        assertTrue(classes.contains("fixture.ProfileApp"), content);
        assertTrue(classes.contains("fixture.WorkloadOnly"), content);
        assertFalse(content.contains("ShutdownOnly"), "a class only the shutdown loads is not in the profile");
        assertEquals(new HashSet<>(classes).size(), classes.size(), "no class is listed twice");

        assertFalse(content.contains("\r"), "the line endings are \\n on every OS");
        assertTrue(content.endsWith("\n"));
        for (Path absolute : List.of(temp, runnerJar.getParent(), Path.of(System.getProperty("java.home")))) {
            assertFalse(content.contains(absolute.toString()), () -> "the profile names " + absolute);
        }
        assertTrue(lines.stream().noneMatch(line -> line.contains(" source: ")), "no raw log line is kept");

        if (!OS.WINDOWS.isCurrentOs()) {
            // The stop is SIGTERM here, which runs the hook: the JVM's own log, read after the exit, has the
            // class. It is the snapshot taken before the stop that keeps it out.
            assertTrue(Files.readString(work.resolve("class-load.log")).contains("fixture.ShutdownOnly"));
        }
        assertFalse(Files.readString(work.resolve("class-load.snapshot.log")).contains("fixture.ShutdownOnly"));
        assertTrue(Files.readString(work.resolve("application.log")).contains("PROFILE APP listening"));
        assertTrue(log.infos.stream().anyMatch(line -> line.startsWith("Recorded " + recorded + " startup classes")
                && !line.contains("since the previous profile")), log.infos::toString);

        // The packager reads the profile back: every name is a class of the archive.
        RecordingLogger packaging = new RecordingLogger();
        Path repackaged = temp.resolve("repackaged.jar");
        RunnerJarBuilder.build(RunnerJarSpec.builder()
                .mainClass("fixture.ProfileApp")
                .applicationOutput(List.of(applicationClasses()))
                .dependencies(List.of())
                .output(repackaged)
                .startupClasses(profile)
                .build(), packaging);
        assertTrue(packaging.warnings.isEmpty(), packaging.warnings::toString);
        try (RunnerJarReader reader = RunnerJarReader.open(repackaged)) {
            assertEquals(recorded, reader.index().preloadCount());
            assertEquals(jdkClasses, reader.index().jdkPreloadCount());
        }
    }

    @Test
    void recordingTwiceGivesTheSameClasses() throws Exception {
        Path work = temp.resolve("record");
        Path profile = temp.resolve("startup-classes.txt");

        int first = StartupProfileRecorder.record(java(), runnerJar, serverSettings(), work, profile, RERECORD, log);
        List<String> firstClasses = StartupClassList.read(profile).classes();
        // The first recording marked the directory as its own, so the second one empties it.
        Files.writeString(work.resolve("left-over.txt"), "from the first recording");
        int second = StartupProfileRecorder.record(java(), runnerJar, serverSettings(), work, profile, RERECORD, log);
        List<String> secondClasses = StartupClassList.read(profile).classes();

        assertEquals(first, second);
        assertEquals(new HashSet<>(firstClasses), new HashSet<>(secondClasses));
        assertFalse(Files.exists(work.resolve("left-over.txt")), "the work directory is emptied");
        assertTrue(log.infos.stream().anyMatch(
                line -> line.endsWith("; 0 added and 0 removed since the previous profile")), log.infos::toString);
    }

    @Test
    void anApplicationThatRunsToItsExitIsRecordedWhole() throws Exception {
        Path profile = temp.resolve("startup-classes.txt");
        TrainingSettings settings = TrainingSettings.builder().runToExit(true)
                .environment(Map.of("PROFILE_APP_MODE", "exit")).build();

        StartupProfileRecorder.record(java(), runnerJar, settings, temp.resolve("record"), profile, RERECORD, log);

        String content = Files.readString(profile);
        List<String> classes = StartupClassList.read(profile).classes();
        assertTrue(classes.contains("fixture.ProfileApp") && classes.contains("fixture.WorkloadOnly"), content);
        // There is no stop to take the snapshot before: the whole log is the profile, shutdown classes included,
        // which is harmless because the launcher never initialises a preloaded class.
        assertTrue(classes.contains("fixture.ShutdownOnly"), content);
        assertEquals("none, the application ran to its exit", headerValue(content, "workload"));
        assertTrue(log.warnings.isEmpty(), log.warnings::toString);
    }

    @Test
    void anEmptyRecordingFailsAndNamesTheLikelyCause() {
        StartupClassList cached = StartupClassList.parse(List.of(
                "[0.011s][info][class,load] java.lang.Object source: shared objects file",
                "[0.071s][info][class,load] fixture.ProfileApp source: shared objects file (top)"));

        for (StartupClassList empty : List.of(cached, StartupClassList.parse(List.of()))) {
            IOException failure = assertThrows(IOException.class,
                    () -> StartupProfileRecorder.render(empty, RERECORD, "25", TrainingSettings.defaults()));
            assertTrue(failure.getMessage().contains("no class that RunnerClassLoader defined")
                    && failure.getMessage().contains("class cache")
                    && failure.getMessage().contains("jvmArgs")
                    && failure.getMessage().contains("-Dmicronaut.runner.aot.training"), failure.getMessage());
        }
    }

    @Test
    void theRerecordCommandMustBeOneNonBlankLine() {
        for (String command : new String[] {"", "   ", "./gradlew a\n./gradlew b", "mvn a\r", null}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> StartupProfileRecorder.record(java(), runnerJar, TrainingSettings.defaults(),
                            temp.resolve("record"), temp.resolve("startup-classes.txt"), command, log));
            assertTrue(failure.getMessage().contains("one non-blank line"), failure.getMessage());
            assertThrows(IllegalArgumentException.class, () -> StartupProfileRecorder.render(archiveClasses(),
                    command, "25", TrainingSettings.defaults()));
        }
        assertFalse(Files.exists(temp.resolve("record")), "nothing is launched for a command that is refused");
    }

    @Test
    void theProfileMustNotLieInTheWorkDirectoryAndTheArchiveMustBeARunnerJar() throws IOException {
        Path work = temp.resolve("record");
        Files.createDirectories(work);
        Path keep = Files.writeString(work.resolve("keep.txt"), "not deleted");

        IllegalArgumentException inside = assertThrows(IllegalArgumentException.class,
                () -> StartupProfileRecorder.record(java(), runnerJar, TrainingSettings.defaults(), work,
                        work.resolve("nested/startup-classes.txt"), RERECORD, log));
        assertTrue(inside.getMessage().contains("must not be inside the work directory"), inside.getMessage());

        Path plain = ClassFixtures.jar(temp.resolve("plain.jar"), Map.of("a.txt", new byte[] {1}));
        for (Path notARunnerJar : List.of(plain, temp.resolve("missing.jar"))) {
            IOException failure = assertThrows(IOException.class,
                    () -> StartupProfileRecorder.record(java(), notARunnerJar, TrainingSettings.defaults(), work,
                            temp.resolve("startup-classes.txt"), RERECORD, log));
            assertTrue(failure.getMessage().contains("is not a runner jar"), failure.getMessage());
        }
        assertTrue(Files.exists(keep), "the work directory is only emptied for a recording that starts");
    }

    @Test
    void aWorkDirectoryWithContentNoRecordingLeftIsRefusedAndKept() throws Exception {
        Path profile = temp.resolve("project/src/main/micronaut-runner/startup-classes.txt");
        TrainingSettings settings = TrainingSettings.builder().runToExit(true)
                .environment(Map.of("PROFILE_APP_MODE", "exit")).build();

        // workDirectory = layout.buildDirectory: the runner jar is in it, and so is every other build output.
        Path build = temp.resolve("project/build");
        Path archive = Files.copy(runnerJar, Files.createDirectories(build.resolve("libs")).resolve("app-all.jar"));
        Path classes = Files.writeString(Files.createDirectories(build.resolve("classes")).resolve("App.class"), "");
        IllegalArgumentException holdsTheJar = assertThrows(IllegalArgumentException.class,
                () -> StartupProfileRecorder.record(java(), archive, settings, build, profile, RERECORD, log));
        assertTrue(holdsTheJar.getMessage().contains("The runner jar " + archive.toAbsolutePath().normalize()
                + " must not be inside the work directory"), holdsTheJar.getMessage());

        // workDirectory = file('src/main/java'): nothing the recording needs is in it, but it is not the build's.
        Path sources = Files.createDirectories(temp.resolve("project/src/main/java/com/example"));
        Path source = Files.writeString(sources.resolve("App.java"), "class App {}");
        Path sourceRoot = temp.resolve("project/src/main/java");
        IllegalArgumentException foreign = assertThrows(IllegalArgumentException.class,
                () -> StartupProfileRecorder.record(java(), runnerJar, settings, sourceRoot, profile, RERECORD, log));
        assertTrue(foreign.getMessage().contains("The work directory " + sourceRoot.toAbsolutePath().normalize()
                + " is not empty and no earlier startup-profile recording left it"), foreign.getMessage());

        assertTrue(Files.exists(archive) && Files.exists(classes) && Files.exists(source), "nothing is deleted");
        assertFalse(Files.exists(profile), "nothing is recorded");

        // An empty directory is taken, and the recording marks it as its own for the next one.
        Path empty = Files.createDirectories(temp.resolve("project/build/record"));
        StartupProfileRecorder.record(java(), runnerJar, settings, empty, profile, RERECORD, log);
        assertTrue(Files.isRegularFile(profile));
        assertTrue(Files.isRegularFile(empty.resolve(StartupProfileRecorder.WORK_DIRECTORY_MARKER)));
    }

    @Test
    void theHeaderDescribesTheRecordingAndTheJdkClassesFollowTheArchives() throws IOException {
        StartupClassList recorded = StartupClassList.parse(List.of(
                "jrt:java.util.zip.CRC32", "fixture.ProfileApp", "fixture.WorkloadOnly", "jrt:java.sql.Timestamp"));
        TrainingSettings settings = TrainingSettings.builder()
                .workloadPaths(List.of("/hello", "/beans?x=1")).workloadRepeat(3)
                .workloadCommand(List.of("/usr/local/bin/warm-up", "--all"))
                .jvmArgs(List.of("-Xmx256m", "-Dapp.mode=training")).build();

        String content = StartupProfileRecorder.render(recorded, "mvn package mn-runner:record-startup-profile",
                "25.0.4.1", settings);

        assertEquals("""
                # Micronaut Runner startup profile: the classes RunnerClassLoader defined before the training workload
                # finished, in load order, then as jrt: lines the JDK classes the same run loaded from the runtime image.
                # Commit this file. Re-record it after upgrading dependencies, the JDK or the packaging options.
                # re-record: mvn package mn-runner:record-startup-profile
                # jdk: 25.0.4.1
                # jvm: -Djava.util.concurrent.ForkJoinPool.common.parallelism=0 -Xmx256m -Dapp.mode=training
                # workload: GET /hello, GET /beans?x=1 x3; command
                # classes: 2
                # jdk-classes: 2
                fixture.ProfileApp
                fixture.WorkloadOnly
                jrt:java.util.zip.CRC32
                jrt:java.sql.Timestamp
                """, content);
        assertFalse(content.contains("/usr/local/bin"), "the command's path stays out of the profile");
        assertEquals("GET / x1", headerValue(StartupProfileRecorder.render(recorded, RERECORD, "25",
                TrainingSettings.defaults()), "workload"));
        assertEquals("command", headerValue(StartupProfileRecorder.render(recorded, RERECORD, "25",
                TrainingSettings.builder().workloadCommand(List.of("warm-up")).build()), "workload"));

        // What is written is what the packager's parser reads.
        Path file = Files.writeString(temp.resolve("startup-classes.txt"), content);
        StartupClassList read = StartupClassList.read(file);
        assertEquals(recorded.classes(), read.classes());
        assertEquals(recorded.jdkClasses(), read.jdkClasses());
    }

    @Test
    void theJdkVersionIsTheRuntimeVersionOfTheReleaseFileOrUnknown() throws IOException {
        Path home = temp.resolve("jdk");
        Path java = home.resolve("bin/java");
        Files.createDirectories(java.getParent());
        Files.createFile(java);
        assertEquals("unknown", StartupProfileRecorder.jdkVersion(java), "a JDK without a release file");

        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"Example\"\nJAVA_VERSION=\"25.0.4\"\n");
        assertEquals("unknown", StartupProfileRecorder.jdkVersion(java), "a release file without the line");

        Files.writeString(home.resolve("release"),
                "IMPLEMENTOR=\"Example\"\nJAVA_RUNTIME_VERSION=\"25.0.4+7-LTS\"\nJAVA_VERSION=\"25.0.4\"\n");
        assertEquals("25.0.4+7-LTS", StartupProfileRecorder.jdkVersion(java));
        assertEquals("unknown", StartupProfileRecorder.jdkVersion(temp.resolve("no-such-jdk/bin/java")));
    }

    private static TrainingSettings serverSettings() {
        return TrainingSettings.builder().readinessPath("/ready").workloadPaths(List.of("/work")).build();
    }

    private static StartupClassList archiveClasses() {
        return StartupClassList.parse(List.of("fixture.ProfileApp"));
    }

    private static Path applicationClasses() {
        return runnerJar.resolveSibling("classes");
    }

    private static String headerValue(String content, String key) {
        String prefix = "# " + key + ": ";
        return content.lines().filter(line -> line.startsWith(prefix)).findFirst().orElseThrow()
                .substring(prefix.length());
    }

    private static Path java() {
        String home = System.getProperty("runner.test.javaHome", System.getProperty("java.home"));
        Path java = Path.of(home, "bin", "java");
        return Files.isExecutable(java) ? java : Path.of(home, "bin", "java.exe");
    }

    /** Keeps every line the recorder logs, by level. */
    private static final class RecordingLogger implements BuildLogger {

        private final List<String> infos = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

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
