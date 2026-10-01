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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkProvenanceTest {

    @Test
    void reportsOfTheSameBytesInTwoPlacesAreIdenticalAndIdentifyTheOrderedInputs(@TempDir Path temporary)
            throws Exception {
        Path firstOutput = temporary.resolve("report-a");
        Path secondOutput = temporary.resolve("report-b");

        writeReport(firstOutput, fixtureTree(temporary.resolve("checkout-a")));
        writeReport(secondOutput, fixtureTree(temporary.resolve("checkout-b")));

        String left = Files.readString(firstOutput.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertEquals(left, Files.readString(secondOutput.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8));
        assertTrue(left.indexOf("\"id\": \"input:0\"") < left.indexOf("\"id\": \"input:1\""), left);
        assertTrue(left.contains("\"command\": [\"${java}\", \"-cp\", \"${input:0}" + File.pathSeparator
                + "${input:1}\", \"example.Main\"]"), left);
        assertTrue(left.contains("\"failureReason\": \"failed while launching ${input:0} in ${workdir}\""), left);
        assertFalse(left.contains(temporary.toString()), left);
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

    /**
     * One relocation for commands, the diagnostic runs' commands and failure text: the same file gets the same token,
     * and no home path is left.
     */
    @Test
    void commandsAndFailureTextUseTheSameTokensAndNoHomePath(@TempDir Path written) throws Exception {
        String home = System.getProperty("user.home");
        RunContext context = BenchmarkFixtures.context(Path.of(home, "runner-relocation-fixture", "reports"), 1,
                List.of(), CompletenessPolicy.REQUIRED, BenchmarkProvenance.unavailable());
        Path workdir = context.workDirectory().resolve("thin");
        Path jar = workdir.resolve("app-thin.jar");
        Path dependency = workdir.resolve("lib").resolve("dependency.jar");
        Variant variant = BenchmarkFixtures.variant("thin-jar",
                List.of(SampleBuild.javaExecutable().toString(), "-jar", jar.toString()), workdir, null, jar,
                dependency);

        String failure = BenchmarkProvenance.relocate("cannot open " + dependency + " from " + jar + " in " + workdir
                + "; see " + context.sample().resolve("build.gradle") + ", "
                + context.outputDirectory().resolve("summary.md") + ", "
                + context.workDirectory().resolve("managed-aot") + " and " + Path.of(home, ".gradle"), context, variant);


        assertEquals(List.of("${java}", "-jar", "${input:0}"),
                BenchmarkProvenance.relocate(variant.command(), context, variant));
        String separator = File.separator;
        assertEquals("cannot open ${input:1} from ${input:0} in ${workdir}; see ${sample}" + separator + "build.gradle,"
                + " ${output}" + separator + "summary.md, ${work}" + separator + "managed-aot and ${user-home}"
                + separator + ".gradle", failure);
        assertNull(BenchmarkProvenance.relocate((String) null, context, variant));

        Path log = context.outputDirectory().resolve("diagnostics").resolve("thin-jar-class-load.log");
        Reports.write(written, context, List.of(BenchmarkFixtures.result(variant)), List.of(
                new StartupHarness.DiagnosticRun("thin-jar", 42.0, StartupHarness.processCommand(List.of(),
                        variant.command(), List.of("-Xlog:class+load=info:file=" + log)))));
        String json = Files.readString(written.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        String escaped = separator.replace("\\", "\\\\");
        assertTrue(json.contains("\"command\": [\"${java}\", \"-Xlog:class+load=info:file=${output}" + escaped
                + "diagnostics" + escaped + "thin-jar-class-load.log\", \"-jar\", \"${input:0}\"]"), json);
        assertFalse(json.contains(home.replace("\\", "\\\\")), json);
    }

    @Test
    void revisionCaptureDistinguishesCleanAndDirtyTreesWithoutPaths(@TempDir Path repository) throws Exception {
        init(repository);
        Files.writeString(repository.resolve("tracked.txt"), "one", StandardCharsets.UTF_8);
        commit(repository);

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

    /**
     * The sample's state covers its own tree; the Runner's covers its whole repository, and is unavailable rather
     * than attributed to the working directory when no checkout is configured.
     */
    @Test
    void sampleStateIsScopedToItsTreeAndRunnerStateCoversTheRepository(@TempDir Path repository) throws Exception {
        init(repository);
        Path runner = Files.createDirectory(repository.resolve("benchmarks"));
        Path sample = Files.createDirectories(repository.resolve("test-suite/samples/hello"));
        Files.writeString(runner.resolve("Harness.java"), "class Harness {}", StandardCharsets.UTF_8);
        Files.writeString(sample.resolve("Sample.java"), "class Sample {}", StandardCharsets.UTF_8);
        commit(repository);
        Files.writeString(repository.resolve("outside.txt"), "untracked", StandardCharsets.UTF_8);

        BenchmarkProvenance provenance = BenchmarkProvenance.capture(runner, sample);

        assertEquals("dirty", provenance.runnerSource().state());
        assertEquals("clean", provenance.sampleSource().state());
        assertEquals("clean", BenchmarkProvenance.SourceState.capture(runner).state());
        assertEquals("unavailable", BenchmarkProvenance.capture(null, sample).runnerSource().state());
    }

    @Test
    void revisionCaptureDoesNotWaitForTheOutputOfACommandThatTimedOut(@TempDir Path temporary) {
        List<String> command = List.of(SampleBuild.javaExecutable().toString(), "-cp",
                System.getProperty("java.class.path"), StartupHarnessFixture.class.getName(), "hang",
                temporary.resolve("lifecycle").toString());

        long started = System.nanoTime();
        String result = BenchmarkProvenance.SourceState.runCommand(temporary, command, Duration.ofMillis(150));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertNull(result);
        assertTrue(elapsedMillis < 2_000, "command timeout took " + elapsedMillis + " ms");
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
        Variant variant = BenchmarkFixtures.variant("fixture", List.of(SampleBuild.javaExecutable().toString(), "-cp",
                application + File.pathSeparator + dependency, "example.Main"), root, null, application, dependency);
        RunAttempt failure = RunAttempt.failure("fixture", 0, 0, 0, false,
                "failed while launching " + application + " in " + root, 17);
        Reports.write(output, BenchmarkFixtures.context(output, 1, List.of("fixture"), CompletenessPolicy.REQUIRED,
                BenchmarkProvenance.unavailable()), List.of(VariantResult.summarize(variant, List.of(failure), 0, 1,
                17L)), List.of());
    }

    private static void init(Path repository) throws Exception {
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "fixture@example.invalid");
        run(repository, "git", "config", "user.name", "Fixture");
    }

    private static void commit(Path repository) throws Exception {
        run(repository, "git", "add", ".");
        run(repository, "git", "commit", "-q", "-m", "fixture");
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
