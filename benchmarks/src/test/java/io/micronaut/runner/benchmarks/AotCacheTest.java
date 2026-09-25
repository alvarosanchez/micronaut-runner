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

    @Test
    void identityChangesWithOrderedArtifactsJdkArchitectureAndFlags(@TempDir Path directory) throws Exception {
        Path application = directory.resolve("app.jar");
        Path dependency = directory.resolve("dependency.jar");
        Files.writeString(application, "application-one", StandardCharsets.UTF_8);
        Files.writeString(dependency, "dependency-one", StandardCharsets.UTF_8);

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
    }

    @Test
    @Tag("benchmark-integration")
    void forkedLifecycleTrainsReusesAndProvesAnApplicationClassIsShared(@TempDir Path directory)
            throws Exception {
        Path classes = Path.of(AotCacheFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Variant plain = SampleBuild.runnerJar(directory, "runner-stored", AotCacheFixture.class.getName(),
                List.of(classes), List.of(), Compression.STORED, EntryMode.STUB);
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        AotCache.Request request = request(directory, console);

        Variant cached = AotCache.prepare(plain, "runner-stored-aot", request);
        Path archive = cached.launchInputs().get(cached.launchInputs().size() - 1);
        long modified = Files.getLastModifiedTime(archive).toMillis();
        Variant reused = AotCache.prepare(plain, "runner-stored-aot", request);

        assertTrue(cached.available());
        assertEquals(EntryMode.STUB, cached.effectiveEntryMode());
        DeploymentSize plainSize = plain.deploymentSize();
        DeploymentSize cachedSize = cached.deploymentSize();
        int sourceComponents = plainSize.components().size();
        assertEquals(sourceComponents + 1, cachedSize.components().size());
        assertEquals(plainSize.components(), cachedSize.components().subList(0, sourceComponents),
                "the cache row keeps its source's components");
        DeploymentSize.Component cacheComponent = cachedSize.components().getLast();
        assertEquals("cache", cacheComponent.name());
        assertEquals(cached.cache().bytes(), cacheComponent.bytes(),
                "the launch needs the cache, so it is part of the complete deployment");
        assertTrue(cacheComponent.gzipBytes() > 0);
        assertEquals(plainSize.totalBytes() + cached.cache().bytes(), cachedSize.totalBytes());
        assertEquals(plainSize.totalGzipBytes() + cacheComponent.gzipBytes(), cachedSize.totalGzipBytes());
        assertEquals(archive, reused.launchInputs().get(reused.launchInputs().size() - 1));
        assertEquals(modified, Files.getLastModifiedTime(archive).toMillis());
        assertTrue(Files.size(archive) > 0);
        assertTrue(cached.command().contains("-XX:AOTMode=on"), cached.command().toString());
        assertTrue(cached.command().stream().anyMatch(argument -> argument.startsWith("-XX:AOTCache=")));
        List<String> withoutCache = new ArrayList<>(cached.command());
        withoutCache.removeIf(argument -> argument.equals("-XX:AOTMode=on") || argument.startsWith("-XX:AOTCache="));
        assertEquals(plain.command(), withoutCache,
                "paired launches must differ only by the strict AOT cache selection");
        assertEquals("aot", cached.cache().mode());
        assertEquals(Files.size(archive), cached.cache().bytes());
        assertTrue(cached.cache().trainingMillis() >= 0);
        assertFalse(cached.cache().reused());
        assertTrue(reused.cache().reused());
        String output = console.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("trained AOT cache"), output);
        assertTrue(output.contains("verified application class " + AotCacheFixture.class.getName()), output);
        assertTrue(output.contains("reusing AOT cache"), output);

        Path report = directory.resolve("report");
        RunContext context = new RunContext(directory, "file:/repo", "1.0", report,
                1, 0, 1, "/ready", false, "2026-09-23T00:00:00Z",
                List.of(cached.name()), CompletenessPolicy.REQUIRED);
        Reports.write(report, context,
                List.of(new VariantResult(cached, cached.deploymentSize().totalBytes(), List.of(),
                        null, null, null, List.of())), List.of());
        String json = Files.readString(report.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"cacheBytes\": " + Files.size(archive)), json);
        assertTrue(json.contains("\"cacheSha256\": \"" + AotCache.sha256(archive) + "\""), json);
        assertEquals(AotCache.sha256(archive), cached.cache().sha256());
        assertEquals(cached.cache().sha256(), reused.cache().sha256());
        assertTrue(json.contains("\"cacheReused\": false"), json);
        assertTrue(json.contains("\"trainingMillis\":"), json);
        assertTrue(json.contains("\"deploymentSize\":")
                && json.contains("\"totalBytes\": " + (plainSize.totalBytes() + Files.size(archive))), json);
        assertTrue(json.contains("{\"name\": \"cache\", \"bytes\": " + Files.size(archive)
                + ", \"gzipBytes\": " + cacheComponent.gzipBytes() + "}"), json);
        String summary = Files.readString(report.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        assertTrue(summary.contains("## Application-cache preparation"), summary);
        assertTrue(summary.contains("Training cost"), summary);
        assertTrue(summary.contains("Cache bytes"), summary);
    }

    @Test
    @Tag("benchmark-integration")
    void rebuiltInputWithUnchangedBytesReusesTheTrainedCache(@TempDir Path directory) throws Exception {
        Path classes = Path.of(AotCacheFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Variant plain = SampleBuild.runnerJar(directory, "runner-stored", AotCacheFixture.class.getName(),
                List.of(classes), List.of(), Compression.STORED, EntryMode.STUB);
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        AotCache.Request request = request(directory, console);
        Variant trained = AotCache.prepare(plain, "runner-stored-aot", request);
        Path archive = trained.launchInputs().getLast();
        FileTime trainedAt = Files.getLastModifiedTime(archive);

        // The premise: the JDK checks the class-path JAR's time, so moving only the time breaks a strict launch.
        Files.setLastModifiedTime(plain.artifact(), FileTime.from(Instant.now()));
        Launch moved = launch(AotCache.launchCommand(plain, archive), directory.resolve("moved.log"));
        assertNotEquals(0, moved.exit(), moved.output());
        assertTrue(moved.output().contains("Unable to use AOT cache"), moved.output());
        assertTrue(moved.output().contains("timestamp"), moved.output());

        // The next run rebuilds the same bytes: the pinned time makes the earlier training valid again.
        Variant rebuilt = SampleBuild.runnerJar(directory, "runner-stored", AotCacheFixture.class.getName(),
                List.of(classes), List.of(), Compression.STORED, EntryMode.STUB);
        Variant reused = AotCache.prepare(rebuilt, "runner-stored-aot", request);

        String output = console.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("reusing AOT cache " + trained.cache().identity() + " for runner-stored-aot"),
                output);
        assertFalse(output.contains("invalid cached AOT cache"), output);
        assertTrue(reused.cache().reused());
        assertEquals(trained.cache().identity(), reused.cache().identity());
        assertEquals(trained.cache().sha256(), reused.cache().sha256(),
                "a reused cache is the same training, byte for byte");
        assertEquals(AotCache.sha256(archive), reused.cache().sha256());
        assertEquals(trainedAt, Files.getLastModifiedTime(archive), "the cache file was not rewritten");
        assertEquals(LaunchInputs.PINNED_MODIFICATION_TIME, Files.getLastModifiedTime(rebuilt.artifact()));
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

        Variant exitsEarly = Variant.available("early", "exits before readiness",
                List.of(SampleBuild.javaExecutable().toString(), "-version"), directory, empty);
        IOException failed = assertThrows(IOException.class,
                () -> AotCache.prepare(exitsEarly, "early-aot", request(directory, new ByteArrayOutputStream())));
        assertTrue(failed.getMessage().contains("before readiness"), failed.getMessage());
    }

    @Test
    void strictLaunchRejectsAnUnusableCache(@TempDir Path directory) throws Exception {
        Path artifact = Files.writeString(directory.resolve("artifact.txt"), "fixture", StandardCharsets.UTF_8);
        String java = SampleBuild.javaExecutable().toString();
        Variant plain = Variant.available("plain", "fixture", List.of(java, "-version"), directory, artifact);
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

    @Test
    void compatibleOopCompressionProbeParsesPrintFlagsFinal() {
        List<String> jdk25 = List.of(
                "[Global flags]",
                "    ccstr AOTCacheOutput                           =                                           {product} {default}",
                "     bool AOTClassLinking                          = false                                     {product} {default}",
                "    ccstr AOTMode                                  =                                           {product} {default}",
                "openjdk version \"25.0.4.1\" 2026-08-18",
                "OpenJDK 64-Bit Server VM Homebrew (build 25.0.4.1, mixed mode, sharing)");
        List<String> jdk27 = List.of(
                "[Global flags]",
                "    ccstr AOTCacheOutput                           =                                           {product} {default}",
                "     bool AOTCompatibleOopCompression              = false                          {diagnostic lp64_product} {ergonomic}",
                "    ccstr AOTMode                                  =                                           {product} {default}",
                "openjdk version \"27\" 2026-09-15",
                "OpenJDK 64-Bit Server VM Homebrew (build 27, mixed mode, sharing)");

        assertEquals(List.of(), AotCache.compatibleOopCompressionFlags(jdk25));
        assertEquals(List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:+AOTCompatibleOopCompression"),
                AotCache.compatibleOopCompressionFlags(jdk27));
        assertEquals(List.of(), AotCache.compatibleOopCompressionFlags(List.of()));
    }

    private static AotCache.Request request(Path directory, ByteArrayOutputStream console) {
        return new AotCache.Request(directory.resolve("managed-aot"), "/ready", List.of("/work"),
                Duration.ofSeconds(30), AotCacheFixture.class.getName(), List.of(),
                new PrintStream(console, true, StandardCharsets.UTF_8));
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
