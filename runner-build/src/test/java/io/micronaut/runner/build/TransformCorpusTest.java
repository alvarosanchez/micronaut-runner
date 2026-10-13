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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the class transform pipeline, with lambda desugaring, over the class paths of real applications, and
 * verifies both sides of every class it rewrites. Micronaut AOT's own corpus test covers local-variable stripping.
 *
 * <p>The class paths come from {@code -Prunner.transformCorpus=<directory>}: one text file per application,
 * one jar per line in class-path order, as {@code test-suite/corpus/classpath.init.gradle} writes them. The
 * "Transform corpus" workflow writes them for {@code benchmark-large}, {@code hello-netty} and each project of
 * {@code test-suite/corpus}, and runs this test through {@code :micronaut-runner-build:transformCorpusTest},
 * which is not part of {@code check}.</p>
 *
 * <p>An application fails when any class verifies worse after a step than before it: whether the pipeline's
 * gate caught it, which the fallback notes record, or not. Every other fallback, and the counts of every step,
 * are printed. It also fails when its class path file names a jar that does not exist, holds no jar at all, or
 * when no class of it was rewritten: a run that transformed nothing has verified nothing.</p>
 */
@Tag("transform-corpus")
class TransformCorpusTest {

    /** The system property the {@code transformCorpusTest} task sets from {@code -Prunner.transformCorpus}. */
    private static final String CORPUS_PROPERTY = "runner.transformCorpus";

    @TestFactory
    Stream<DynamicTest> everyRewrittenClassVerifiesNoWorseThanItsOriginal() throws IOException {
        String corpus = System.getProperty(CORPUS_PROPERTY, "");
        Assumptions.assumeFalse(corpus.isBlank(), "no corpus: run with -P" + CORPUS_PROPERTY + "=<directory>");
        List<Path> classPaths;
        try (Stream<Path> files = Files.list(Path.of(corpus))) {
            classPaths = files.filter(file -> file.getFileName().toString().endsWith(".txt")).sorted().toList();
        }
        assertFalse(classPaths.isEmpty(), "the corpus directory " + corpus + " holds no class path");
        return classPaths.stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(),
                () -> runOver(file)));
    }

    private static void runOver(Path classPathFile) throws IOException {
        String name = classPathFile.getFileName().toString();
        List<Path> dependencies = new ArrayList<>();
        for (String line : Files.readAllLines(classPathFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Path jar = Path.of(line.strip());
            assertTrue(Files.exists(jar), () -> name + " names " + jar + ", which does not exist");
            if (Files.isRegularFile(jar) && jar.getFileName().toString().endsWith(".jar")) {
                dependencies.add(jar);
            } else {
                System.out.println(name + ": skipped " + jar + ", which is not a jar");
            }
        }
        assertFalse(dependencies.isEmpty(), name + " names no jar");
        ClassPathModel model = scan(dependencies);
        ClassTransformPipeline pipeline = new ClassTransformPipeline(List.of(new LambdaDesugarer(model)), model);
        Function<byte[], List<String>> verifier = ClassTransformPipeline.verifierOf(model);

        List<ClassTransformPipeline.JarReport> reports = new ArrayList<>();
        List<String> grown = new ArrayList<>();
        for (int position = 0; position < dependencies.size(); position++) {
            Path dependency = dependencies.get(position);
            try (ZipReader reader = ZipReader.open(dependency)) {
                ClassTransformPipeline.JarRun run = pipeline.start(new ClassTransformPipeline.Layer(
                        dependency.getFileName().toString(), position + 1, false, reader.hasSignatureFiles()));
                // As a repack does: the nests are planned, rewritten and gated before the entry loop.
                run.plan(new ZipRepacker.SourceClasses(reader));
                for (ZipEntryInfo entry : reader.entries()) {
                    if (!ClassTransformPipeline.isClass(entry)) {
                        continue;
                    }
                    ClassTransformPipeline.Planned planned = run.planned(entry.name());
                    byte[] original;
                    byte[] output;
                    if (planned != null) {
                        original = reader.read(entry);
                        output = planned.bytes();
                        for (ClassTransformPipeline.Generated generated : planned.generated()) {
                            // A generated class has no original: it must verify cleanly.
                            List<String> errors = verifier.apply(generated.bytes());
                            if (!errors.isEmpty()) {
                                grown.add(dependency.getFileName() + " " + generated.name() + ": " + errors.get(0));
                            }
                        }
                    } else if (!run.reads(entry.uncompressedSize())) {
                        run.pass(entry.name());
                        continue;
                    } else {
                        original = reader.read(entry);
                        output = run.process(entry.name(), original);
                    }
                    if (output != original) {
                        // The gate's own comparison, which ignores the bytecode offset an error names.
                        String error = ClassTransformPipeline.grown(verifier.apply(output),
                                verifier.apply(original));
                        if (error != null) {
                            grown.add(dependency.getFileName() + " " + entry.name() + ": " + error);
                        }
                    }
                }
                reports.add(run.report());
            }
        }

        List<TransformReport> totals = pipeline.totals(reports);
        for (String summary : pipeline.summaries(reports)) {
            System.out.println(name + ": " + summary);
        }
        for (ClassTransformPipeline.JarReport report : reports) {
            for (String note : report.notes()) {
                System.out.println(name + ": fallback " + note.replace('\t', ' '));
                if (note.split("\t", 4)[3].startsWith("verification: ")) {
                    grown.add(note.replace('\t', ' '));
                }
            }
        }
        assertEquals(List.of(), grown, name + ": classes whose verification errors grew");
        for (TransformReport total : totals) {
            assertTrue(total.rewritten() > 0, () -> name + ": " + total.step() + " rewrote no class of "
                    + dependencies.size() + " jars");
        }
    }

    private static ClassPathModel scan(List<Path> dependencies) throws IOException {
        ClassPathModel.Interner strings = new ClassPathModel.Interner();
        List<ClassPathModel.LayerScan> scans = new ArrayList<>();
        scans.add(ClassPathModel.scan(0, "the application output", false, true, strings));
        int layer = 1;
        for (Path dependency : dependencies) {
            try (ZipReader reader = ZipReader.open(dependency)) {
                Manifest manifest = reader.manifest().orElse(null);
                boolean multiRelease = manifest != null
                        && "true".equalsIgnoreCase(manifest.getMainAttributes().getValue("Multi-Release"));
                ClassPathModel.LayerScan scan = ClassPathModel.scan(layer++, "the dependency " + dependency,
                        multiRelease, true, strings);
                for (ZipEntryInfo entry : reader.entries()) {
                    if (!entry.directory() && scan.wants(entry.name(), entry.uncompressedSize())) {
                        scan.accept(entry.name(), reader.read(entry));
                    }
                }
                scans.add(scan);
            }
        }
        return ClassPathModel.merge(scans, true);
    }
}
