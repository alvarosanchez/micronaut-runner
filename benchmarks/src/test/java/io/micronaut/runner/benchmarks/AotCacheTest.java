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
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        Variant plain = standardLoaderFixture(directory, "fixture.jar", "one");
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        AotCache.Request request = request(directory, console);

        Variant cached = AotCache.prepare(plain, "fixture-aot", request);
        Path archive = cached.launchInputs().get(cached.launchInputs().size() - 1);
        long modified = Files.getLastModifiedTime(archive).toMillis();
        Variant reused = AotCache.prepare(plain, "fixture-aot", request);

        assertTrue(cached.available());
        assertEquals(EntryMode.STANDARD_LOADER, cached.effectiveEntryMode());
        assertEquals(plain.deploymentSize(), cached.deploymentSize(),
                "cache bytes must be reported separately from deployment bytes");
        assertEquals(archive, reused.launchInputs().get(reused.launchInputs().size() - 1));
        assertEquals(modified, Files.getLastModifiedTime(archive).toMillis());
        assertTrue(Files.size(archive) > 0);
        assertTrue(cached.command().stream().anyMatch(argument -> argument.startsWith("-XX:AOTCache=")));
        List<String> withoutCache = new ArrayList<>(cached.command());
        withoutCache.removeIf(argument -> argument.startsWith("-XX:AOTCache="));
        assertEquals(plain.command(), withoutCache,
                "paired launches must differ only by the selected AOT cache");
        assertEquals("aot", cached.cache().mode());
        assertEquals(Files.size(archive), cached.cache().bytes());
        assertTrue(cached.cache().trainingMillis() >= 0);
        assertFalse(cached.cache().reused());
        assertTrue(reused.cache().reused());
        String output = console.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("trained AOT cache"), output);
        assertTrue(output.contains("verified application class " + CdsCacheFixture.class.getName()), output);
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
        assertTrue(json.contains("\"trainingMillis\":"), json);
        assertTrue(json.contains("\"deploymentSize\":")
                && json.contains("\"totalBytes\": " + plain.deploymentSize().totalBytes()), json);
        String summary = Files.readString(report.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        assertTrue(summary.contains("## Application-cache preparation"), summary);
        assertTrue(summary.contains("Training cost"), summary);
        assertTrue(summary.contains("Cache bytes"), summary);
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
    void customLoaderSourcesAreRejectedBeforeTraining(@TempDir Path directory) throws Exception {
        Path classes = Path.of(CdsCacheFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Variant runner = SampleBuild.runnerJar(directory, "custom-loader", CdsCacheFixture.class.getName(),
                List.of(classes), List.of(), io.micronaut.runner.build.Compression.STORED, EntryMode.STUB);

        IOException failure = assertThrows(IOException.class,
                () -> AotCache.prepare(runner, "not-aot", request(directory, new ByteArrayOutputStream())));

        assertTrue(failure.getMessage().contains("built-in application class loader"), failure.getMessage());
    }

    private static AotCache.Request request(Path directory, ByteArrayOutputStream console) {
        return new AotCache.Request(directory.resolve("managed-aot"), "/ready", List.of("/work"), "/stop",
                Duration.ofSeconds(30), CdsCacheFixture.class.getName(), List.of(),
                new PrintStream(console, true, StandardCharsets.UTF_8));
    }

    private static Variant standardLoaderFixture(Path directory, String fileName, String marker) throws IOException {
        Path jar = directory.resolve(fileName);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, CdsCacheFixture.class.getName());
        manifest.getMainAttributes().putValue("Fixture-Marker", marker);
        String classEntry = CdsCacheFixture.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest);
             InputStream input = CdsCacheFixture.class.getResourceAsStream("/" + classEntry)) {
            if (input == null) {
                throw new IOException("missing fixture bytecode " + classEntry);
            }
            output.putNextEntry(new JarEntry(classEntry));
            input.transferTo(output);
            output.closeEntry();
        }
        DeploymentSize size = DeploymentSize.measure(DeploymentSize.input("application", jar));
        return Variant.available("fixture", "built-in-loader fixture",
                List.of(SampleBuild.javaExecutable().toString(), "-jar", jar.toAbsolutePath().toString()),
                directory, jar, size, List.of(jar));
    }
}
