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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Runs the class transform pipeline, with every step enabled, over the class paths of real applications,
 * and verifies both sides of every class it rewrites.
 *
 * <p>The class paths come from {@code -Prunner.transformCorpus=<directory>}: one text file per application,
 * one jar per line in class-path order, as {@code test-suite/corpus/classpath.init.gradle} writes them. The
 * "Transform corpus" workflow writes them for {@code benchmark-large}, {@code hello-netty} and each project of
 * {@code test-suite/corpus}, and runs this test through {@code :micronaut-runner-build:transformCorpusTest},
 * which is not part of {@code check}.</p>
 *
 * <p>An application fails when any class verifies worse after a step than before it: whether the pipeline's
 * gate caught it, which the fallback notes record, or not. Every other fallback, and the counts of every step,
 * are printed.</p>
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
        List<Path> dependencies = new ArrayList<>();
        for (String line : Files.readAllLines(classPathFile, StandardCharsets.UTF_8)) {
            Path jar = Path.of(line.strip());
            if (!line.isBlank() && Files.isRegularFile(jar) && jar.getFileName().toString().endsWith(".jar")) {
                dependencies.add(jar);
            }
        }
        ClassPathModel model = scan(dependencies);
        model.watched().ifPresent(watched -> System.out.println(classPathFile.getFileName() + ": "
                + watched.layer() + " contains " + watched.entry()
                + ", which reads local-variable tables; a build would not strip this class path"));
        ClassTransformPipeline pipeline = new ClassTransformPipeline(List.of(new LocalVariableStripper()), model);
        Function<byte[], List<String>> verifier = ClassTransformPipeline.verifierOf(model);

        List<ClassTransformPipeline.JarReport> reports = new ArrayList<>();
        List<String> grown = new ArrayList<>();
        for (Path dependency : dependencies) {
            try (ZipReader reader = ZipReader.open(dependency)) {
                ClassTransformPipeline.JarRun run = pipeline.start(new ClassTransformPipeline.Layer(
                        dependency.getFileName().toString(), false, reader.hasSignatureFiles(), false));
                for (ZipEntryInfo entry : reader.entries()) {
                    if (!ClassTransformPipeline.isClass(entry)) {
                        continue;
                    }
                    if (!run.reads(entry.uncompressedSize())) {
                        run.skip();
                        continue;
                    }
                    byte[] original = reader.read(entry);
                    byte[] output = run.process(entry.name(), original);
                    if (output != original) {
                        Set<String> errors = new LinkedHashSet<>(verifier.apply(output));
                        errors.removeAll(verifier.apply(original));
                        if (!errors.isEmpty()) {
                            grown.add(dependency.getFileName() + " " + entry.name() + ": " + errors);
                        }
                    }
                }
                reports.add(run.report());
            }
        }

        String name = classPathFile.getFileName().toString();
        List<TransformReport> totals = pipeline.totals(reports);
        for (int i = 0; i < totals.size(); i++) {
            System.out.println(name + ": " + pipeline.steps().get(i).summary(totals.get(i), reports.size()));
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
    }

    private static ClassPathModel scan(List<Path> dependencies) throws IOException {
        ClassPathModel.Interner strings = new ClassPathModel.Interner();
        List<ClassPathModel.LayerScan> scans = new ArrayList<>();
        scans.add(ClassPathModel.scan(0, "the application output", false, false,
                LocalVariableStripper::isKnownReader, strings));
        int layer = 1;
        for (Path dependency : dependencies) {
            try (ZipReader reader = ZipReader.open(dependency)) {
                Manifest manifest = reader.manifest().orElse(null);
                boolean multiRelease = manifest != null
                        && "true".equalsIgnoreCase(manifest.getMainAttributes().getValue("Multi-Release"));
                ClassPathModel.LayerScan scan = ClassPathModel.scan(layer++, "the dependency " + dependency,
                        multiRelease, false, LocalVariableStripper::isKnownReader, strings);
                for (ZipEntryInfo entry : reader.entries()) {
                    if (!entry.directory() && scan.wants(entry.name(), entry.uncompressedSize())) {
                        scan.accept(entry.name(), reader.read(entry));
                    }
                }
                scans.add(scan);
            }
        }
        return ClassPathModel.merge(scans, false);
    }
}
