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
import java.util.List;

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
                new DeploymentSize.Component("application", 10),
                new DeploymentSize.Component("dependencies", 50)), size.components());
    }

    @Test
    void standaloneArchiveCountsOnlyTheArchive(@TempDir Path root) throws Exception {
        Path archive = writeBytes(root.resolve("runner.jar"), 17);
        writeBytes(root.resolve("unrelated.txt"), 100);

        DeploymentSize size = DeploymentSize.measure(DeploymentSize.input("archive", archive));

        assertEquals(17, size.totalBytes());
        assertEquals(List.of(new DeploymentSize.Component("archive", 17)), size.components());
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
        assertEquals(List.of(new DeploymentSize.Component("extracted-layout", 24)), size.components());
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

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"deploymentSize\""));
        assertTrue(json.contains("\"boundary\": \"required regular files\""));
        assertTrue(json.contains("\"unit\": \"bytes\""));
        assertTrue(json.contains("\"totalBytes\": 60"));
        assertTrue(json.contains("{\"name\": \"application\", \"bytes\": 10}"));
        assertTrue(json.contains("{\"name\": \"dependencies\", \"bytes\": 50}"));
        assertFalse(json.contains("\"artifactBytes\""));

        String markdown = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("Complete deployment"));
        assertTrue(markdown.contains("required regular-file lengths"));
        assertTrue(markdown.contains("Symbolic links are not followed or counted"));
        assertTrue(markdown.contains("| `thin-jar` | application | 10 B | 60 B |"));
        assertTrue(markdown.contains("| `thin-jar` | dependencies | 50 B | 60 B |"));
    }

    private static Path writeBytes(Path path, int size) throws Exception {
        return Files.write(path, new byte[size]);
    }
}
