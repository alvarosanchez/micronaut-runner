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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkDeploymentSizeTest {

    @Test
    void thinDeploymentIncludesApplicationAndDependenciesExactlyOnce(@TempDir Path root) throws Exception {
        Path thin = Files.createDirectory(root.resolve("thin"));
        Path application = writeBytes(thin.resolve("app.jar"), 10);
        Path lib = Files.createDirectory(thin.resolve("lib"));
        Path firstDependency = writeBytes(lib.resolve("first.jar"), 20);
        writeBytes(lib.resolve("second.jar"), 30);
        writeBytes(thin.resolve("benchmark.log"), 40);
        writeBytes(root.resolve("another-variant.jar"), 50);

        DeploymentSize size = DeploymentSize.measure(
                DeploymentSize.input("application", application),
                DeploymentSize.input("dependencies", List.of(firstDependency, firstDependency, lib)));

        assertEquals(60, size.totalBytes());
        assertEquals(List.of(
                new DeploymentSize.Component("application", 10, gzip(10)),
                new DeploymentSize.Component("dependencies", 50, gzip(20) + gzip(30))), size.components());
        assertEquals(gzip(10) + gzip(20) + gzip(30), size.totalGzipBytes());
    }

    @Test
    void standaloneArchiveCountsOnlyTheArchive(@TempDir Path root) throws Exception {
        Path archive = writeBytes(root.resolve("runner.jar"), 17);
        writeBytes(root.resolve("unrelated.txt"), 100);

        DeploymentSize size = DeploymentSize.measure(DeploymentSize.input("archive", archive));

        assertEquals(17, size.totalBytes());
        assertEquals(List.of(new DeploymentSize.Component("archive", 17, gzip(17))), size.components());
    }

    @Test
    void directoryLayoutCountsRegularFilesWithoutFollowingSymbolicLinks(@TempDir Path root) throws Exception {
        Path layout = Files.createDirectory(root.resolve("layout"));
        writeBytes(layout.resolve("app.jar"), 11);
        Path nested = Files.createDirectory(layout.resolve("lib"));
        writeBytes(nested.resolve("dependency.jar"), 13);
        Path external = writeBytes(root.resolve("external.bin"), 101);
        Files.createSymbolicLink(layout.resolve("external-link"), external);

        DeploymentSize size = DeploymentSize.measure(DeploymentSize.input("extracted-layout", layout));

        assertEquals(24, size.totalBytes());
        assertEquals(List.of(new DeploymentSize.Component("extracted-layout", 24, gzip(11) + gzip(13))),
                size.components());
    }

    @Test
    void aRequiredCacheFileIsItsOwnComponentOfTheCompleteDeployment(@TempDir Path root) throws Exception {
        Path archive = writeBytes(root.resolve("runner.jar"), 10);
        Path cache = writeBytes(root.resolve("app.aot"), 5);
        DeploymentSize source = DeploymentSize.measure(DeploymentSize.input("archive", archive));

        DeploymentSize withCache = source.withFile("cache", cache);

        assertEquals(15, withCache.totalBytes());
        assertEquals(List.of(
                new DeploymentSize.Component("archive", 10, gzip(10)),
                new DeploymentSize.Component("cache", 5, gzip(5))), withCache.components());
        assertEquals(gzip(10) + gzip(5), withCache.totalGzipBytes());
        assertEquals(10, source.totalBytes(), "the source size is not changed");
    }

    @Test
    void aMissingOrSymbolicallyLinkedCacheIsAnError(@TempDir Path root) throws Exception {
        Path archive = writeBytes(root.resolve("runner.jar"), 10);
        Path cache = writeBytes(root.resolve("app.aot"), 5);
        Path link = Files.createSymbolicLink(root.resolve("linked.aot"), cache);
        DeploymentSize source = DeploymentSize.measure(DeploymentSize.input("archive", archive));

        IOException missing = assertThrows(IOException.class,
                () -> source.withFile("cache", root.resolve("absent.aot")));
        assertTrue(missing.getMessage().contains("does not exist"), missing.getMessage());
        IOException linked = assertThrows(IOException.class, () -> source.withFile("cache", link));
        assertTrue(linked.getMessage().contains("symbolic link"), linked.getMessage());
        IOException directory = assertThrows(IOException.class, () -> source.withFile("cache", root));
        assertTrue(directory.getMessage().contains("not a regular file"), directory.getMessage());
    }

    @Test
    void gzipBytesAreTheDeterministicLengthOfAGzipStream(@TempDir Path root) throws Exception {
        byte[] content = new byte[300_000];
        Random random = new Random(20260924L);
        for (int i = 0; i < content.length; i++) {
            // Compressible but not trivial, and larger than one read buffer.
            content[i] = (byte) ('a' + random.nextInt(8));
        }
        Path file = Files.write(root.resolve("payload.bin"), content);

        DeploymentSize first = DeploymentSize.measure(DeploymentSize.input("archive", file));
        DeploymentSize second = DeploymentSize.measure(DeploymentSize.input("archive", file));

        long expected = gzip(content);
        assertTrue(expected > 0 && expected < content.length, "gzip length " + expected);
        assertEquals(expected, first.components().getFirst().gzipBytes());
        assertEquals(first.components().getFirst().gzipBytes(), second.components().getFirst().gzipBytes());
        assertEquals(expected, first.totalGzipBytes());
    }

    @Test
    void variantResultsRejectADeploymentTotalThatDoesNotMatchItsComponents(@TempDir Path root) throws Exception {
        Path archive = writeBytes(root.resolve("runner.jar"), 17);
        DeploymentSize deploymentSize = DeploymentSize.measure(DeploymentSize.input("archive", archive));
        Variant variant = Variant.available("runner", "fixture", List.of("java"), root, archive, deploymentSize);

        assertThrows(IllegalArgumentException.class,
                () -> new VariantResult(variant, 16, List.of(), null, null, null, List.of()));
    }

    @Test
    void reportsExposeTheSameCompleteDeploymentBoundaryAndComponents(@TempDir Path output) throws Exception {
        Path sample = Files.createDirectory(output.resolve("sample"));
        Path application = writeBytes(output.resolve("app.jar"), 10);
        Path dependencies = Files.createDirectory(output.resolve("lib"));
        writeBytes(dependencies.resolve("first.jar"), 20);
        writeBytes(dependencies.resolve("second.jar"), 30);
        DeploymentSize deploymentSize = DeploymentSize.measure(
                DeploymentSize.input("application", application),
                DeploymentSize.input("dependencies", dependencies));
        Variant variant = Variant.available("thin-jar", "fixture", List.of("java"), output, application,
                deploymentSize);
        RunContext context = new RunContext(sample, "file:/repo", "1.0", output,
                1, 0, 1, "/hello", false, "2026-09-22T00:00:00Z",
                List.of("thin-jar"), CompletenessPolicy.PARTIAL);
        VariantResult result = new VariantResult(variant, 60, List.of(), null, null, null, List.of());

        Reports.write(output, context, List.of(result), List.of());

        long applicationGzip = gzip(10);
        long dependenciesGzip = gzip(20) + gzip(30);
        long totalGzip = applicationGzip + dependenciesGzip;
        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"deploymentSize\""));
        assertTrue(json.contains("\"boundary\": \"required regular files\""));
        assertTrue(json.contains("\"unit\": \"bytes\""));
        assertTrue(json.contains("\"totalBytes\": 60, \"totalGzipBytes\": " + totalGzip), json);
        assertTrue(json.contains("{\"name\": \"application\", \"bytes\": 10, \"gzipBytes\": " + applicationGzip + "}"),
                json);
        assertTrue(json.contains(
                "{\"name\": \"dependencies\", \"bytes\": 50, \"gzipBytes\": " + dependenciesGzip + "}"), json);
        assertFalse(json.contains("\"artifactBytes\""));

        String markdown = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("Complete deployment"));
        assertTrue(markdown.contains("required regular-file lengths"));
        assertTrue(markdown.contains("per-file gzip -6 lengths"));
        assertTrue(markdown.contains("Symbolic links are not followed or counted"));
        assertFalse(markdown.contains("compressed transfer sizes are not reported"));
        assertTrue(markdown.contains("| Variant | Component | Component bytes | Component gzip | Complete deployment |"
                + " Complete deployment gzip |"), markdown);
        assertTrue(markdown.contains("| `thin-jar` | application | 10 B | " + applicationGzip + " B | 60 B | "
                + totalGzip + " B |"), markdown);
        assertTrue(markdown.contains("| `thin-jar` | dependencies | 50 B | " + dependenciesGzip + " B | 60 B | "
                + totalGzip + " B |"), markdown);
    }

    private static Path writeBytes(Path path, int size) throws Exception {
        return Files.write(path, new byte[size]);
    }

    private static long gzip(int zeroes) throws IOException {
        return gzip(new byte[zeroes]);
    }

    private static long gzip(byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content);
        }
        return out.size();
    }
}
