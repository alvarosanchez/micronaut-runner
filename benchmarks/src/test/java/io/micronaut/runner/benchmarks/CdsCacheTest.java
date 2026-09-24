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
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdsCacheTest {

    @Test
    void identityChangesWithArtifactJdkArchitectureAndFlags(@TempDir Path directory) throws Exception {
        Path artifact = directory.resolve("app.jar");
        Files.writeString(artifact, "one", StandardCharsets.UTF_8);

        String original = CdsCache.identity(artifact, "25.0.3+9", "HotSpot", "aarch64", List.of("-Xmx128m"));
        assertEquals(original,
                CdsCache.identity(artifact, "25.0.3+9", "HotSpot", "aarch64", List.of("-Xmx128m")));
        Files.writeString(artifact, "two", StandardCharsets.UTF_8);
        assertNotEquals(original,
                CdsCache.identity(artifact, "25.0.3+9", "HotSpot", "aarch64", List.of("-Xmx128m")));
        Files.writeString(artifact, "one", StandardCharsets.UTF_8);
        assertNotEquals(original,
                CdsCache.identity(artifact, "25.0.4+1", "HotSpot", "aarch64", List.of("-Xmx128m")));
        assertNotEquals(original,
                CdsCache.identity(artifact, "25.0.3+9", "HotSpot", "x86_64", List.of("-Xmx128m")));
        assertNotEquals(original,
                CdsCache.identity(artifact, "25.0.3+9", "HotSpot", "aarch64", List.of("-Xmx256m")));
    }

    @Test
    @Tag("benchmark-integration")
    void forkedLifecycleTrainsReusesAndProvesAnApplicationClassIsShared(@TempDir Path directory) throws Exception {
        Path classes = Path.of(CdsCacheFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Variant plain = SampleBuild.runnerJar(directory, "fixture-runner", CdsCacheFixture.class.getName(),
                List.of(classes), List.of(), Compression.STORED, EntryMode.STUB);
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        CdsCache.Request request = new CdsCache.Request(
                directory.resolve("managed-caches"),
                "/ready",
                List.of("/work"),
                "/stop",
                Duration.ofSeconds(15),
                CdsCacheFixture.class.getName(),
                List.of(),
                new PrintStream(console, true, StandardCharsets.UTF_8));

        Variant cds = CdsCache.prepare(plain, "fixture-runner-cds", request);
        long modified = Files.getLastModifiedTime(cds.launchInputs().get(1)).toMillis();
        Variant reused = CdsCache.prepare(plain, "fixture-runner-cds", request);

        assertTrue(cds.available());
        assertEquals(EntryMode.STUB, cds.effectiveEntryMode());
        assertEquals(2, cds.launchInputs().size());
        Path archive = cds.launchInputs().get(1);
        assertTrue(Files.size(archive) > 0);
        assertEquals(archive, reused.launchInputs().get(1));
        assertEquals(modified, Files.getLastModifiedTime(archive).toMillis());
        assertTrue(cds.command().stream().anyMatch(argument -> argument.equals("-Xshare:on")));
        assertTrue(cds.command().stream().anyMatch(argument -> argument.startsWith("-XX:SharedArchiveFile=")));
        String output = console.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("trained CDS cache"), output);
        assertTrue(output.contains("verified application class " + CdsCacheFixture.class.getName()), output);
        assertTrue(output.contains("reusing CDS cache"), output);
    }

    @Test
    void absentOrEmptyArchiveIsRejectedBeforeLaunch(@TempDir Path directory) throws Exception {
        Path absent = directory.resolve("absent.jsa");
        IOException missing = assertThrows(IOException.class, () -> CdsCache.requireUsableArchive(absent));
        assertTrue(missing.getMessage().contains("does not exist"));

        Path empty = Files.createFile(directory.resolve("empty.jsa"));
        IOException invalid = assertThrows(IOException.class, () -> CdsCache.requireUsableArchive(empty));
        assertTrue(invalid.getMessage().contains("empty"));
    }

    @Test
    void strictLaunchRejectsCorruptArchiveInsteadOfFallingBack(@TempDir Path directory) throws Exception {
        Path classes = Path.of(CdsCacheFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Variant plain = SampleBuild.runnerJar(directory, "strict-fixture", CdsCacheFixture.class.getName(),
                List.of(classes), List.of(), Compression.STORED, EntryMode.STUB);
        Path corrupt = directory.resolve("corrupt.jsa");
        Files.writeString(corrupt, "not a CDS archive", StandardCharsets.UTF_8);
        List<String> command = CdsCache.launchCommand(plain, corrupt, CdsCache.SharingPolicy.STRICT);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();

        assertNotEquals(0, exit, output);
        assertTrue(command.contains("-Xshare:on"));
        assertFalse(command.contains("-Xshare:auto"));
    }

    @Test
    void fallbackLaunchIsExplicit(@TempDir Path directory) throws Exception {
        Variant plain = Variant.available("plain", "fixture",
                List.of(SampleBuild.javaExecutable().toString(), "-jar", directory.resolve("app.jar").toString()),
                directory, directory.resolve("app.jar"));
        Files.writeString(directory.resolve("bad.jsa"), "bad", StandardCharsets.UTF_8);

        List<String> command = CdsCache.launchCommand(plain, directory.resolve("bad.jsa"),
                CdsCache.SharingPolicy.FALLBACK);

        assertTrue(command.contains("-Xshare:auto"));
        assertFalse(command.contains("-Xshare:on"));
    }
}
