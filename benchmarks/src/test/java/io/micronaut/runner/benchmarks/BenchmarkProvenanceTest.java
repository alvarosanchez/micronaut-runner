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
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkProvenanceTest {

    @Test
    void savedBundleIsRelocatableAndIdentifiesOrderedInputBytes(@TempDir Path temporary) throws Exception {
        Path first = fixtureTree(temporary.resolve("private-a"));
        Path second = fixtureTree(temporary.resolve("private-b"));
        Path firstOutput = temporary.resolve("report-a");
        Path secondOutput = temporary.resolve("report-b");

        writeReport(firstOutput, first);
        writeReport(secondOutput, second);

        String left = Files.readString(firstOutput.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        String right = Files.readString(secondOutput.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertEquals(left, right);
        assertTrue(left.contains("\"schemaVersion\": 6"));
        assertTrue(left.contains("\"id\": \"input:0\""));
        assertTrue(left.contains("\"id\": \"input:1\""));
        assertTrue(left.indexOf("\"id\": \"input:0\"") < left.indexOf("\"id\": \"input:1\""));
        assertTrue(left.contains("\"sha256\":"));
        assertTrue(left.contains("${input:0}"));
        assertTrue(left.contains("${input:1}"));
        assertFalse(left.contains(temporary.toString()));
        assertFalse(left.contains(System.getProperty("user.home")));
        assertFalse(left.contains("top-secret"));
        assertFalse(left.contains("second-secret"));
        assertTrue(left.contains("--password"));
        assertTrue(left.contains("<redacted:command-value>"));
        assertTrue(left.contains("\"globalOrder\": 0"));
        assertTrue(left.contains("\"outcome\": \"failed\""));
        assertTrue(left.contains("\"exitCode\": 17"));
    }

    @Test
    void changedInputBytesChangeTheRecordedIdentity(@TempDir Path temporary) throws Exception {
        Path root = fixtureTree(temporary.resolve("root"));
        Path firstOutput = temporary.resolve("before");
        Path secondOutput = temporary.resolve("after");
        writeReport(firstOutput, root);
        Files.writeString(root.resolve("dependency.jar"), "changed", StandardCharsets.UTF_8);
        writeReport(secondOutput, root);

        String before = Files.readString(firstOutput.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        String after = Files.readString(secondOutput.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertNotEquals(before, after);
    }

    @Test
    void sourceRevisionCaptureDistinguishesCleanAndDirtyTreesWithoutPaths(@TempDir Path repository) throws Exception {
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "fixture@example.invalid");
        run(repository, "git", "config", "user.name", "Fixture");
        Files.writeString(repository.resolve("tracked.txt"), "one", StandardCharsets.UTF_8);
        run(repository, "git", "add", "tracked.txt");
        run(repository, "git", "commit", "-q", "-m", "fixture");

        BenchmarkProvenance.SourceState clean = BenchmarkProvenance.SourceState.capture(repository);
        Files.writeString(repository.resolve("tracked.txt"), "two", StandardCharsets.UTF_8);
        BenchmarkProvenance.SourceState dirty = BenchmarkProvenance.SourceState.capture(repository);
        run(repository, "git", "restore", "tracked.txt");
        Files.writeString(repository.resolve("untracked.java"), "class Untracked {}", StandardCharsets.UTF_8);
        BenchmarkProvenance.SourceState untracked = BenchmarkProvenance.SourceState.capture(repository);

        assertEquals("clean", clean.state());
        assertEquals("dirty", dirty.state());
        assertEquals("dirty", untracked.state());
        assertEquals(clean.revision(), dirty.revision());
        assertEquals(40, clean.revision().length());
        assertFalse(clean.toString().contains(repository.toString()));
    }

    @Test
    void sourceDirtyStateIsScopedToTheRequestedTree(@TempDir Path repository) throws Exception {
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "fixture@example.invalid");
        run(repository, "git", "config", "user.name", "Fixture");
        Path runner = Files.createDirectory(repository.resolve("runner"));
        Path sample = Files.createDirectory(repository.resolve("sample"));
        Files.writeString(runner.resolve("Runner.java"), "class Runner {}", StandardCharsets.UTF_8);
        Files.writeString(sample.resolve("Sample.java"), "class Sample {}", StandardCharsets.UTF_8);
        run(repository, "git", "add", ".");
        run(repository, "git", "commit", "-q", "-m", "fixture");
        Files.writeString(runner.resolve("Runner.java"), "class Runner { int changed; }", StandardCharsets.UTF_8);

        assertEquals("dirty", BenchmarkProvenance.SourceState.capture(runner).state());
        assertEquals("clean", BenchmarkProvenance.SourceState.capture(sample).state());
    }

    @Test
    void capturedRunnerStateCoversTheWholeRepositoryWhileSampleStateStaysScoped(@TempDir Path repository)
            throws Exception {
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "fixture@example.invalid");
        run(repository, "git", "config", "user.name", "Fixture");
        Path runner = Files.createDirectory(repository.resolve("benchmarks"));
        Path sample = Files.createDirectories(repository.resolve("test-suite/samples/hello"));
        Files.writeString(runner.resolve("Harness.java"), "class Harness {}", StandardCharsets.UTF_8);
        Files.writeString(sample.resolve("Sample.java"), "class Sample {}", StandardCharsets.UTF_8);
        run(repository, "git", "add", ".");
        run(repository, "git", "commit", "-q", "-m", "fixture");
        Files.writeString(repository.resolve("outside.txt"), "untracked", StandardCharsets.UTF_8);

        BenchmarkProvenance provenance = BenchmarkProvenance.capture(runner, sample, Map.of());

        assertEquals("dirty", provenance.runnerSource().state());
        assertEquals("clean", provenance.sampleSource().state());
    }

    @Test
    void missingRunnerSourceIsUnavailableRatherThanAttributedToTheWorkingDirectory(@TempDir Path repository)
            throws Exception {
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "fixture@example.invalid");
        run(repository, "git", "config", "user.name", "Fixture");
        Path sample = Files.createDirectory(repository.resolve("sample"));
        Files.writeString(sample.resolve("Sample.java"), "class Sample {}", StandardCharsets.UTF_8);
        run(repository, "git", "add", ".");
        run(repository, "git", "commit", "-q", "-m", "fixture");

        BenchmarkProvenance provenance = BenchmarkProvenance.capture(null, sample, Map.of());

        assertEquals("unavailable", provenance.runnerSource().state());
        assertEquals("clean", provenance.sampleSource().state());
    }

    @Test
    void provenanceCommandTimeoutDoesNotWaitForOutputEof(@TempDir Path temporary) {
        Path lifecycle = temporary.resolve("lifecycle");
        List<String> command = List.of(
                SampleBuild.javaExecutable().toString(),
                "-cp",
                System.getProperty("java.class.path"),
                StartupHarnessFixture.class.getName(),
                "hang",
                lifecycle.toString());

        long started = System.nanoTime();
        String result = BenchmarkProvenance.SourceState.runCommand(
                temporary, command, Duration.ofMillis(150));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertNull(result);
        assertTrue(elapsedMillis < 2_000, "command timeout took " + elapsedMillis + " ms");
    }

    @Test
    void environmentPolicyRecordsPresenceButRedactsInjectedValues(@TempDir Path output) throws Exception {
        Map<String, String> environment = new HashMap<>();
        environment.put("JAVA_TOOL_OPTIONS", "-Dpassword=top-secret");
        environment.put("JDK_AOT_VM_OPTIONS", "-Dpassword=aot-secret");
        BenchmarkProvenance provenance = BenchmarkProvenance.capture(output, output, environment);
        RunContext context = context(output, provenance);
        Variant unavailable = Variant.unavailable("fixture", "fixture", "not built");

        Reports.write(output, context,
                List.of(new VariantResult(unavailable, -1, List.of(), null, null, null, List.of())), List.of());

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"JAVA_TOOL_OPTIONS\": {\"present\": true, \"action\": \"removed\","));
        assertTrue(json.contains("\"value\": \"<redacted:ambient-jvm-options>\""));
        assertTrue(json.contains("\"JDK_JAVA_OPTIONS\": {\"present\": false, \"action\": \"removed\""));
        assertTrue(json.contains("\"JDK_AOT_VM_OPTIONS\": {\"present\": true, \"action\": \"removed\""));
        assertFalse(json.contains("top-secret"));
        assertFalse(json.contains("aot-secret"));
        assertTrue(json.contains("\"totalMemoryBytes\":"));
        assertTrue(json.contains("\"javaRuntimeVersion\":"));
    }

    private static Path fixtureTree(Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("application.jar"), "application", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("dependency.jar"), "dependency", StandardCharsets.UTF_8);
        return root;
    }

    private static void writeReport(Path output, Path root) throws Exception {
        Path application = root.resolve("application.jar");
        Path dependency = root.resolve("dependency.jar");
        Variant variant = Variant.available("fixture", "fixture", List.of(
                SampleBuild.javaExecutable().toString(), "-Dapi.token=top-secret",
                "--password", "second-secret", "-cp",
                application + java.io.File.pathSeparator + dependency, "example.Main"),
                root, application, List.of(application, dependency));
        RunContext context = context(output, BenchmarkProvenance.unavailable());
        RunAttempt failure = RunAttempt.failure("fixture", 0, 0, 0, false,
                "failed while launching " + root.resolve("application.jar"), 17);
        VariantResult result = VariantResult.summarize(variant, -1, List.of(failure), 0, 1, 17L);
        Reports.write(output, context, List.of(result), List.of());
    }

    private static RunContext context(Path output, BenchmarkProvenance provenance) {
        return new RunContext(output.resolve("sample"), "file:/private/repository?token=top-secret", "1.0", output,
                1, 0, 17, "/ready", false, "2026-09-22T00:00:00Z", List.of("fixture"),
                CompletenessPolicy.REQUIRED, provenance);
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
