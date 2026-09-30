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

import io.micronaut.runner.build.AotCacheOutput;
import io.micronaut.runner.build.AotLayout;
import io.micronaut.runner.build.AotTarget;
import io.micronaut.runner.build.BuildLogger;
import io.micronaut.runner.build.Compression;
import io.micronaut.runner.build.Dependency;
import io.micronaut.runner.build.RunnerJarBuilder;
import io.micronaut.runner.build.RunnerJarSpec;
import io.micronaut.runner.build.training.TrainingSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trains real caches with the JDK at {@code runner.test.javaHome}, once per target in {@link #trainOnce()}, for
 * a Runner JAR built here: a JDK {@code HttpServer} application with one dependency JAR, STORED, with an entry
 * stub. Every launch is reaped: the last check looks for any process whose command line names the test's
 * directory.
 */
class AotCacheBuilderTest {

    private static final String MAIN_CLASS = AotCacheFixture.class.getName();

    private static final String LIBRARY_JAR = "fixture-library.jar";

    private static final List<String> WARNINGS = Collections.synchronizedList(new ArrayList<>());

    @TempDir
    static Path temp;

    private static Path java;
    private static Path runnerJar;
    private static Path layout;
    private static Path singleJar;
    private static AotCacheReport layoutReport;
    private static AotCacheReport singleJarReport;

    @BeforeAll
    static void trainOnce() throws Exception {
        java = javaExecutable();
        Assumptions.assumeTrue(
                RunnerJarBuilder.class.getResource("/META-INF/micronaut-runner/launcher.jar") != null,
                "the bundled launcher jar is not on the test class path");
        runnerJar = packageFixture(temp.resolve("package"));
        layout = temp.resolve("layout");
        singleJar = temp.resolve("single-jar");
        layoutReport = AotCacheOutput.write(AotTarget.LAYOUT, AotCacheSettings.defaults(), java, runnerJar, layout,
                training().build(), new RecordingLog());
        singleJarReport = AotCacheOutput.write(AotTarget.SINGLE_JAR, AotCacheSettings.defaults(), java, runnerJar,
                singleJar, training().build(), new RecordingLog());
    }

    @AfterAll
    static void noProcessIsLeft() {
        if (temp == null) {
            return;
        }
        String directory = temp.toString();
        List<String> survivors = ProcessHandle.allProcesses()
                .filter(process -> process.pid() != ProcessHandle.current().pid())
                .filter(process -> process.info().commandLine().map(line -> line.contains(directory)).orElse(false))
                .map(process -> process.pid() + " " + process.info().commandLine().orElse(""))
                .toList();
        assertTrue(survivors.isEmpty(), () -> "processes left behind: " + survivors);
    }

    @Test
    void theLayoutPassesTheGateWithJcmdEndingTheRecording() throws IOException {
        assertTrue(layoutReport.passed(), layoutReport::toJson);
        assertTrue(layoutReport.coverage() >= 0.95, layoutReport::toJson);
        assertTrue(layoutReport.failures().isEmpty(), layoutReport::toJson);
        assertEquals(AotTarget.LAYOUT.value(), layoutReport.labels().get(AotCacheOutput.TARGET_LABEL));
        assertEquals(AotCacheSettings.DEFAULT_VERIFY_PROBES, layoutReport.probes());
        assertEquals(0, layoutReport.probeFailures());
        assertTrue(layoutReport.micronautLoaded() > 0 && layoutReport.micronautNotFromCache().stream()
                .allMatch(name -> name.contains("$$Lambda")), layoutReport::toJson);
        if (jcmdEndsRecordings(layoutReport.jdk())) {
            assertEquals(AotCacheReport.STOP_JCMD, layoutReport.recordStop(), layoutReport::toJson);
        }

        String jarName = AotLayout.applicationJarName(runnerJar);
        for (String file : List.of(jarName, "lib/" + LIBRARY_JAR, AotLaunchOptions.CACHE_FILE,
                AotLaunchOptions.ARGFILE, AotLaunchOptions.IDENTITY_FILE, AotCacheReport.FILE,
                AotCacheBuilder.RECORD_LOG, AotCacheBuilder.CREATE_LOG, AotCacheGate.SMOKE_LOG,
                AotCacheGate.CLASS_LOAD_LOG)) {
            assertTrue(Files.isRegularFile(layout.resolve(file)), () -> "no " + file + " in " + layout);
        }
        assertFalse(Files.exists(layout.resolve(AotCacheBuilder.CONFIGURATION_FILE)), "the configuration is gone");
        assertEquals("-XX:AOTCache=app.aot\n", Files.readString(layout.resolve(AotLaunchOptions.ARGFILE)));
        assertEquals(layoutReport.toJson(), Files.readString(layout.resolve(AotCacheReport.FILE)));
        assertEquals("layout",
                AotLaunchOptions.readIdentity(layout.resolve(AotLaunchOptions.IDENTITY_FILE)).get("target"));
        assertTrue(Files.readString(layout.resolve(AotCacheGate.CLASS_LOAD_LOG), StandardCharsets.ISO_8859_1)
                .contains(AotCacheFixtureLibrary.class.getName() + " source: shared objects file"),
                "the dependency's class came from the cache");
    }

    @Test
    void theSingleJarPassesTheEnforcedChecks() throws IOException {
        assertTrue(singleJarReport.passed(), singleJarReport::toJson);
        assertEquals(0, singleJarReport.probeFailures());
        assertEquals("singleJar", singleJarReport.labels().get(AotCacheOutput.TARGET_LABEL));
        Path copy = singleJar.resolve(runnerJar.getFileName().toString());
        assertEquals(Files.getLastModifiedTime(runnerJar), Files.getLastModifiedTime(copy),
                "the copy keeps the modification time");
        assertTrue(Files.isRegularFile(singleJar.resolve(AotLaunchOptions.CACHE_FILE)));
    }

    @Test
    void withoutJcmdTheTrainingStopEndsTheRecording() throws Exception {
        Path out = temp.resolve("driver-stop");
        TrainingSettings.Builder training = training();
        if (OS.WINDOWS.isCurrentOs()) {
            // Windows ends a destroyed process without its shutdown hooks, so no cache would be written.
            training.stopPath("/stop");
        }
        AotCacheBuilder.useJcmd = false;
        AotCacheReport report;
        try {
            report = AotCacheOutput.write(AotTarget.LAYOUT, AotCacheSettings.builder().verifyProbes(3).build(), java,
                    runnerJar, out, training.build(), new RecordingLog());
        } finally {
            AotCacheBuilder.useJcmd = true;
        }
        assertEquals(AotCacheReport.STOP_DRIVER, report.recordStop(), report::toJson);
        assertTrue(report.passed(), report::toJson);
    }

    @Test
    void aTouchedLibraryFailsTheStrictProbes() throws Exception {
        Path copy = temp.resolve("touched");
        AotCacheOutput.write(AotTarget.LAYOUT, AotCacheSettings.builder().verifyProbes(2).build(), java, runnerJar,
                copy, training().build(), new RecordingLog());
        Files.setLastModifiedTime(copy.resolve("lib").resolve(LIBRARY_JAR), FileTime.from(Instant.now()));

        IOException failure = assertThrows(IOException.class, () -> AotCacheGate.verify(
                AotCacheSettings.builder().verifyProbes(2).build(), JdkProbe.probe(java), java, copy,
                AotLayout.applicationJarName(runnerJar), training().build(), AotCacheReport.STOP_JCMD,
                new RecordingLog()));

        assertTrue(failure.getMessage().contains("2 of 2 strict probes failed"), failure.getMessage());
        assertTrue(failure.getMessage().contains("timestamp has changed"), failure.getMessage());
        String report = Files.readString(copy.resolve(AotCacheReport.FILE));
        assertTrue(report.contains("\"verdict\": \"failed\"") && report.contains("\"probeFailures\": 2"), report);
    }

    private static boolean jcmdEndsRecordings(String vmVersion) {
        try {
            return Runtime.Version.parse(vmVersion).compareToIgnoreOptional(Runtime.Version.parse("25.0.4")) >= 0;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static TrainingSettings.Builder training() {
        return TrainingSettings.builder()
                .readinessPath("/ready")
                .workloadPaths(List.of("/work"))
                .readinessTimeout(Duration.ofSeconds(120));
    }

    /** Packs the fixture into a STORED Runner JAR with an entry stub, and its library into a nested JAR. */
    private static Path packageFixture(Path directory) throws IOException {
        Path classes = directory.resolve("classes");
        String packagePath = AotCacheFixture.class.getPackageName().replace('.', '/');
        Path applicationClass = classes.resolve(packagePath).resolve(AotCacheFixture.class.getSimpleName() + ".class");
        Files.createDirectories(applicationClass.getParent());
        Files.write(applicationClass, classBytes(AotCacheFixture.class));

        Path library = directory.resolve(LIBRARY_JAR);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(library))) {
            out.putNextEntry(new ZipEntry(packagePath + "/" + AotCacheFixtureLibrary.class.getSimpleName() + ".class"));
            out.write(classBytes(AotCacheFixtureLibrary.class));
            out.closeEntry();
        }
        Path archive = directory.resolve("fixture-1.0-all.jar");
        RunnerJarBuilder.build(RunnerJarSpec.builder()
                .mainClass(MAIN_CLASS)
                .applicationOutput(List.of(classes))
                .dependencies(List.of(Dependency.of(library, "com.example:fixture-library:1.0")))
                .compression(Compression.STORED)
                .entryStub(true)
                .output(archive)
                .build(), BuildLogger.noOp());
        return archive;
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertNotNull(in, () -> type + " is not on the test class path");
            return in.readAllBytes();
        }
    }

    private static Path javaExecutable() {
        String home = System.getProperty("runner.test.javaHome", System.getProperty("java.home"));
        Path candidate = Path.of(home, "bin", "java");
        if (!Files.isExecutable(candidate)) {
            candidate = Path.of(home, "bin", "java.exe");
        }
        Path found = candidate;
        assertTrue(Files.isExecutable(found), () -> "no java executable under " + home);
        return found;
    }

    /** Keeps the warnings, and prints everything for a failure's context. */
    private static final class RecordingLog implements BuildLogger {

        @Override
        public void info(String message) {
            System.out.println("[info] " + message);
        }

        @Override
        public void warn(String message) {
            WARNINGS.add(message);
            System.out.println("[warn] " + message);
        }
    }
}
