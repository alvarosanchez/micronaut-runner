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

import io.micronaut.runner.build.Compression;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AotCacheTest {

    private static final SampleBuild.VariantSpec CACHED = SampleBuild.spec("runner-stored-aot");

    @Test
    void identityChangesWithOrderedInputsJdkArchitectureCommandReadinessAndCpuLimit(@TempDir Path directory)
            throws Exception {
        Path application = Files.writeString(directory.resolve("app.jar"), "application-one", StandardCharsets.UTF_8);
        Path dependency = Files.writeString(directory.resolve("dependency.jar"), "dependency-one",
                StandardCharsets.UTF_8);

        String original = AotCache.identity(List.of(application, dependency),
                "25.0.3+9", "HotSpot", "aarch64", List.of("-Xmx128m"));
        assertEquals(original, AotCache.identity(List.of(application, dependency),
                "25.0.3+9", "HotSpot", "aarch64", List.of("-Xmx128m")));
        assertNotEquals(original, AotCache.identity(List.of(dependency, application),
                "25.0.3+9", "HotSpot", "aarch64", List.of("-Xmx128m")));
        Files.writeString(dependency, "dependency-two", StandardCharsets.UTF_8);
        assertNotEquals(original, AotCache.identity(List.of(application, dependency),
                "25.0.3+9", "HotSpot", "aarch64", List.of("-Xmx128m")));
        Files.writeString(dependency, "dependency-one", StandardCharsets.UTF_8);
        assertNotEquals(original, AotCache.identity(List.of(application, dependency),
                "25.0.4+1", "HotSpot", "aarch64", List.of("-Xmx128m")));
        assertNotEquals(original, AotCache.identity(List.of(application, dependency),
                "25.0.3+9", "HotSpot", "x86_64", List.of("-Xmx128m")));
        assertNotEquals(original, AotCache.identity(List.of(application, dependency),
                "25.0.3+9", "HotSpot", "aarch64", List.of("-Xmx256m")));

        // The variant's command and the readiness path enter the identity, and so does a CPU limit, through the
        // request's relevant flags: the CPU count sets the VM's ergonomics, while an unlimited run adds nothing.
        Variant source = BenchmarkFixtures.variant("runner-stored", List.of("java", "-jar", application.toString()),
                directory, null, application);
        PrintStream log = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        AotCache.Request unlimited = SampleBuild.aotRequest(directory, "app.Main", null, log);
        AotCache.Request oneCpu = SampleBuild.aotRequest(directory, "app.Main",
                CpuLimit.validate(1, "Linux", "0-3", true), log);
        assertEquals(List.of(), unlimited.relevantJvmFlags());
        assertEquals(List.of("cpus=1"), oneCpu.relevantJvmFlags());
        String plain = AotCache.identity(source, "/hello", unlimited, List.of());
        assertNotEquals(plain, AotCache.identity(source, "/hello", oneCpu, List.of()));
        assertNotEquals(plain, AotCache.identity(source, "/ready", unlimited, List.of()));
    }

    /**
     * Trains a cache, reuses it, reports it, and then retrains it once its file is overwritten with garbage: a cache
     * that does not verify is trained again rather than reused or timed without.
     */
    @Test
    @Tag("benchmark-integration")
    void forkedLifecycleTrainsReusesAndProvesAnApplicationClassIsShared(@TempDir Path directory)
            throws Exception {
        Variant plain = storedJar(directory);
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        AotCache.Request request = request(directory, console);

        try (StartupHarness harness = new StartupHarness("/ready", Duration.ofSeconds(30))) {
            Variant cached = AotCache.prepare(harness, plain, CACHED, request);
            Path archive = cached.launchInputs().getLast();
            long modified = Files.getLastModifiedTime(archive).toMillis();
            Variant reused = AotCache.prepare(harness, plain, CACHED, request);

            assertTrue(cached.available());
            assertEquals(EntryMode.STUB, cached.effectiveEntryMode());
            assertEquals(plain.buildNote(), cached.buildNote(), "the cached row reports what its jar carries");
            DeploymentSize plainSize = plain.deploymentSize();
            DeploymentSize cachedSize = cached.deploymentSize();
            int sourceComponents = plainSize.components().size();
            assertEquals(plainSize.components(), cachedSize.components().subList(0, sourceComponents),
                    "the cache row keeps its source's components");
            DeploymentSize.Component cacheComponent = cachedSize.components().getLast();
            assertEquals(new DeploymentSize.Component("cache", cached.cache().bytes(), cacheComponent.gzipBytes()),
                    cacheComponent, "the launch needs the cache, so it is part of the complete deployment");
            assertTrue(cacheComponent.gzipBytes() > 0);
            assertEquals(sourceComponents + 1, cachedSize.components().size());
            assertEquals(archive, reused.launchInputs().getLast());
            assertEquals(modified, Files.getLastModifiedTime(archive).toMillis());
            assertTrue(cached.command().contains("-XX:AOTMode=on"), cached.command().toString());
            List<String> withoutCache = new ArrayList<>(cached.command());
            withoutCache.removeIf(argument -> argument.equals("-XX:AOTMode=on")
                    || argument.startsWith("-XX:AOTCache="));
            assertEquals(plain.command(), withoutCache,
                    "paired launches must differ only by the strict AOT cache selection");
            assertEquals(Files.size(archive), cached.cache().bytes());
            assertTrue(cached.cache().trainingMillis() >= 0);
            assertFalse(cached.cache().reused());
            assertTrue(reused.cache().reused());
            assertEquals(AotCache.sha256(archive), cached.cache().sha256());
            assertEquals(cached.cache().sha256(), reused.cache().sha256());
            String output = console.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("trained AOT cache"), output);
            assertTrue(output.contains("verified application class " + AotCacheFixture.class.getName()), output);
            assertTrue(output.contains("reusing AOT cache"), output);

            Path report = directory.resolve("report");
            Reports.write(report, BenchmarkFixtures.context(report, 1, List.of(cached.name()),
                    CompletenessPolicy.REQUIRED, BenchmarkProvenance.unavailable()),
                    List.of(BenchmarkFixtures.result(cached)), List.of());
            String json = Files.readString(report.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"cacheBytes\": " + Files.size(archive) + ",\n      \"cacheSha256\": \""
                    + cached.cache().sha256() + "\""), json);
            assertTrue(json.contains("\"cacheReused\": false"), json);
            assertTrue(json.contains("\"totalBytes\": " + (plainSize.totalBytes() + Files.size(archive))), json);
            assertTrue(json.contains("{\"name\": \"cache\", \"bytes\": " + Files.size(archive)
                    + ", \"gzipBytes\": " + cacheComponent.gzipBytes() + "}"), json);

            Files.writeString(archive, "not an AOT cache", StandardCharsets.UTF_8);
            Variant retrained = AotCache.prepare(harness, plain, CACHED, request);

            assertFalse(retrained.cache().reused());
            assertTrue(retrained.cache().trainingMillis() >= 0);
            assertEquals(AotCache.sha256(archive), retrained.cache().sha256());
            output = console.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("invalid cached AOT cache " + cached.cache().identity()), output);
            assertEquals(3, output.split("verified application class", -1).length - 1,
                    "each training and the reuse verified: " + output);
        }
    }

    @Test
    @Tag("benchmark-integration")
    void rebuiltInputWithUnchangedBytesReusesTheTrainedCache(@TempDir Path directory) throws Exception {
        Variant plain = storedJar(directory);
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        AotCache.Request request = request(directory, console);
        try (StartupHarness harness = new StartupHarness("/ready", Duration.ofSeconds(30))) {
            Variant trained = AotCache.prepare(harness, plain, CACHED, request);
            Path archive = trained.launchInputs().getLast();
            FileTime trainedAt = Files.getLastModifiedTime(archive);

            // The premise: the JDK checks the class-path JAR's time, so moving only the time breaks a strict launch.
            Files.setLastModifiedTime(plain.artifact(), FileTime.from(Instant.now()));
            Launch moved = launch(AotCache.launchCommand(plain, archive), directory.resolve("moved.log"));
            assertNotEquals(0, moved.exit(), moved.output());
            assertTrue(moved.output().contains("Unable to use AOT cache"), moved.output());
            assertTrue(moved.output().contains("timestamp"), moved.output());

            // The next run rebuilds the same bytes: the pinned time makes the earlier training valid again.
            Variant rebuilt = storedJar(directory);
            Variant reused = AotCache.prepare(harness, rebuilt, CACHED, request);

            String output = console.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("reusing AOT cache " + trained.cache().identity() + " for runner-stored-aot"),
                    output);
            assertFalse(output.contains("invalid cached AOT cache"), output);
            assertTrue(reused.cache().reused());
            assertEquals(trained.cache().identity(), reused.cache().identity());
            assertEquals(trained.cache().sha256(), reused.cache().sha256(),
                    "a reused cache is the same training, byte for byte");
            assertEquals(trainedAt, Files.getLastModifiedTime(archive), "the cache file was not rewritten");
            assertEquals(LaunchInputs.PINNED_MODIFICATION_TIME, Files.getLastModifiedTime(rebuilt.artifact()));
        }
    }

    @Test
    @Tag("benchmark-integration")
    void absentEmptyAndFailedTrainingAreRejectedClearly(@TempDir Path directory) throws Exception {
        Path absent = directory.resolve("absent.aot");
        IOException missing = assertThrows(IOException.class, () -> AotCache.requireUsableCache(absent));
        assertTrue(missing.getMessage().contains("does not exist"));
        Path empty = Files.createFile(directory.resolve("empty.aot"));
        IOException invalid = assertThrows(IOException.class, () -> AotCache.requireUsableCache(empty));
        assertTrue(invalid.getMessage().contains("empty"));

        Variant exitsEarly = BenchmarkFixtures.variant("early",
                List.of(SampleBuild.javaExecutable().toString(), "-version"), directory, null, empty);
        try (StartupHarness harness = new StartupHarness("/ready", Duration.ofSeconds(30))) {
            StartupHarness.RunFailure failed = assertThrows(StartupHarness.RunFailure.class,
                    () -> AotCache.prepare(harness, exitsEarly, BenchmarkFixtures.spec("early-aot"),
                            request(directory, new ByteArrayOutputStream())));
            assertTrue(failed.getMessage().contains("early exited with status 0 before answering"),
                    failed.getMessage());
        }
    }

    @Test
    void strictLaunchRejectsAnUnusableCache(@TempDir Path directory) throws Exception {
        Path artifact = Files.writeString(directory.resolve("artifact.txt"), "fixture", StandardCharsets.UTF_8);
        String java = SampleBuild.javaExecutable().toString();
        Variant plain = BenchmarkFixtures.variant("plain", List.of(java, "-version"), directory, null, artifact);
        Path corrupt = Files.writeString(directory.resolve("corrupt.aot"), "not an AOT cache", StandardCharsets.UTF_8);

        List<String> strict = AotCache.launchCommand(plain, corrupt);
        List<String> fallback = new ArrayList<>(strict);
        fallback.remove("-XX:AOTMode=on");

        assertEquals(List.of(java, "-XX:AOTMode=on", "-XX:AOTCache=" + corrupt.toAbsolutePath().normalize(),
                "-version"), strict);
        Launch rejected = launch(strict, directory.resolve("strict.log"));
        assertNotEquals(0, rejected.exit(), rejected.output());
        assertTrue(rejected.output().contains("Unable to use AOT cache"), rejected.output());
        Launch uncached = launch(fallback, directory.resolve("fallback.log"));
        assertEquals(0, uncached.exit(), "without -XX:AOTMode=on the JVM runs uncached: " + uncached.output());
    }

    private static Variant storedJar(Path directory) throws Exception {
        Path classes = Path.of(AotCacheFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return SampleBuild.runnerJar(directory, SampleBuild.spec("runner-stored"), AotCacheFixture.class.getName(),
                List.of(classes), List.of(), Compression.STORED, SampleBuild.RunnerJarOptions.DEFAULTS);
    }

    private static AotCache.Request request(Path directory, ByteArrayOutputStream console) {
        return new AotCache.Request(directory.resolve("managed-aot"), List.of("/work"),
                AotCacheFixture.class.getName(), List.of(), new PrintStream(console, true, StandardCharsets.UTF_8));
    }

    private record Launch(int exit, String output) {
    }

    private static Launch launch(List<String> command, Path log) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        StartupHarness.removeInheritedJvmOptions(builder);
        Process process = builder.start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                fail("did not exit within 30 s: " + command);
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
        return new Launch(process.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
    }
}
