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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.constant.ClassDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs Micronaut AOT's class path transform over the class paths of real applications, as a build runs it
 * ({@link ClassPathRewrite}), and verifies both sides of every class it rewrites: once with lambda desugaring
 * alone, as a build with the default options does, and once with local-variable stripping too, as a build with
 * {@code stripLocalVariables} does.
 *
 * <p>The class paths come from {@code -Prunner.transformCorpus=<directory>}: one text file per application,
 * one jar per line in class-path order, as {@code test-suite/corpus/classpath.init.gradle} writes them. The
 * "Transform corpus" workflow writes them for {@code benchmark-large}, {@code hello-netty} and each project of
 * {@code test-suite/corpus}, and runs this test through {@code :micronaut-runner-build:transformCorpusTest},
 * which is not part of {@code check}.</p>
 *
 * <p>An application fails when any class verifies worse after the transform than before it, against the
 * rewritten class path: whether the transform's gate caught it, which the fallback notes record, or not; when a
 * class the transform generated does not verify cleanly; and when a nest fell back. Every other fallback, and the
 * counts of each run, are printed. It also fails when its class path file names a jar that does not exist, holds no
 * jar at all, or when no class of it was rewritten: a run that transformed nothing has verified nothing.</p>
 */
@Tag("transform-corpus")
class TransformCorpusTest {

    /** The system property the {@code transformCorpusTest} task sets from {@code -Prunner.transformCorpus}. */
    private static final String CORPUS_PROPERTY = "runner.transformCorpus";

    /** The bytecode offset in a verifier message, such as the {@code @41} of {@code in Foo::bar() @41}. */
    private static final Pattern BYTECODE_OFFSET = Pattern.compile("@\\d+");

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
        for (boolean strip : new boolean[] {false, true}) {
            Path work = Files.createTempDirectory("transform-corpus-");
            try {
                verify(name + (strip ? " (stripLocalVariables)" : ""), dependencies, strip, work);
            } finally {
                deleteRecursively(work);
            }
        }
    }

    private static void verify(String name, List<Path> dependencies, boolean strip, Path work) throws IOException {
        Path application = ClassFixtures.jar(work.resolve("application.jar"), Map.of());
        ClassPathRewrite.Result result = ClassPathRewrite.run(application, dependencies, true,
                strip ? dependencies : List.of(), work.resolve("rewritten"),
                Runtime.getRuntime().availableProcessors());
        result.warnings().forEach(warning -> System.out.println(name + ": " + warning));
        List<Path> sources = result.classPath().subList(1, result.classPath().size());
        List<String> grown = new ArrayList<>();
        int sites = 0;
        int desugared = 0;
        int[] verified = new int[2];
        TransformReport stripped = null;
        try (Verifier verifier = new Verifier(sources)) {
            for (int position = 0; position < dependencies.size(); position++) {
                ClassPathRewrite.Counts counts = result.counts().get(position + 1);
                if (counts == null) {
                    continue;
                }
                sites += counts.sites();
                desugared += counts.classesDesugared();
                if (counts.stripped() != null) {
                    stripped = add(stripped, counts.stripped());
                }
                for (String note : counts.notes()) {
                    System.out.println(name + ": fallback " + note.replace('\t', ' '));
                    String[] fields = note.split("\t", 4);
                    if (fields[2].equals(ClassPathRewrite.DESUGAR) || fields[3].startsWith("verification: ")) {
                        grown.add(note.replace('\t', ' '));
                    }
                }
                Path dependency = dependencies.get(position);
                Path source = sources.get(position);
                if (!source.equals(dependency)) {
                    compare(dependency, source, verifier, grown, verified);
                }
            }
        }
        System.out.println(name + ": desugared " + sites + " lambda call sites in " + desugared + " classes; verified "
                + verified[0] + " rewritten classes against their originals and " + verified[1] + " generated ones");
        result.summary().forEach(line -> System.out.println(name + ": " + line));
        assertEquals(List.of(), grown, name + ": classes whose verification errors grew, or that fell back");
        int rewritten = desugared;
        assertTrue(rewritten > 0, () -> name + ": desugarLambdas rewrote no class of " + dependencies.size()
                + " jars");
        if (strip && result.warnings().isEmpty()) {
            TransformReport total = stripped;
            assertTrue(total != null && total.rewritten() > 0, () -> name + ": stripLocalVariables rewrote no class"
                    + " of " + dependencies.size() + " jars");
        }
    }

    /**
     * Verifies every class the transform rewrote in one dependency against its original, and every class it
     * generated on its own, and counts them: rewritten ones first, generated ones second.
     */
    private static void compare(Path dependency, Path copy, Verifier verifier, List<String> grown, int[] verified)
            throws IOException {
        try (ZipReader original = ZipReader.open(dependency); ZipReader rewritten = ZipReader.open(copy)) {
            for (ZipEntryInfo entry : rewritten.entries()) {
                if (!entry.isClass()) {
                    continue;
                }
                Optional<ZipEntryInfo> before = original.entry(entry.name());
                if (before.isPresent() && before.get().crc32() == entry.crc32()) {
                    continue;
                }
                List<String> errors = verifier.verify(rewritten.read(entry));
                verified[before.isEmpty() ? 1 : 0]++;
                String error;
                if (before.isEmpty()) {
                    error = errors.isEmpty() ? null : errors.get(0);
                } else {
                    error = errors.isEmpty() ? null : grown(errors, verifier.verify(original.read(before.get())));
                }
                if (error != null) {
                    grown.add(dependency.getFileName() + " " + entry.name() + ": " + error);
                }
            }
        }
    }

    /**
     * The first verification error of a rewritten class that its original does not have, as the transform's gate
     * compares them: two messages are the same error when they are equal without the bytecode offset they name,
     * which a rebuilt constant pool moves, and each error of the original accounts for one of the rewrite.
     */
    private static String grown(List<String> rewritten, List<String> original) {
        Map<String, Integer> known = new HashMap<>();
        for (String error : original) {
            known.merge(BYTECODE_OFFSET.matcher(error).replaceAll("@"), 1, Integer::sum);
        }
        for (String error : rewritten) {
            String key = BYTECODE_OFFSET.matcher(error).replaceAll("@");
            Integer left = known.get(key);
            if (left == null || left == 0) {
                return error;
            }
            known.put(key, left - 1);
        }
        return null;
    }

    private static TransformReport add(TransformReport total, TransformReport report) {
        if (total == null) {
            return report;
        }
        return new TransformReport(report.step(), total.rewritten() + report.rewritten(),
                total.unchanged() + report.unchanged(), total.fallbacks() + report.fallbacks(),
                total.bytesSaved() + report.bytesSaved());
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /**
     * Verifies classes against a class path: the first copy of each class in class-path order, then the JDK's.
     */
    private static final class Verifier implements AutoCloseable {

        private final List<ZipReader> readers = new ArrayList<>();
        private final Map<String, ZipReader> owners = new HashMap<>();
        private final ClassFile context;

        Verifier(List<Path> classPath) throws IOException {
            for (Path jar : classPath) {
                ZipReader reader = ZipReader.open(jar);
                readers.add(reader);
                for (ZipEntryInfo entry : reader.entries()) {
                    if (entry.isClass() && !entry.name().startsWith("META-INF/")) {
                        owners.putIfAbsent(entry.name(), reader);
                    }
                }
            }
            ClassHierarchyResolver jars = ClassHierarchyResolver.ofResourceParsing(this::open);
            context = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                    jars.orElse(ClassHierarchyResolver.defaultResolver()).cached()));
        }

        private InputStream open(ClassDesc type) {
            String descriptor = type.descriptorString();
            String entryName = descriptor.substring(1, descriptor.length() - 1) + ".class";
            ZipReader reader = owners.get(entryName);
            if (reader == null) {
                return null;
            }
            try {
                return new ByteArrayInputStream(reader.read(reader.entry(entryName).orElseThrow()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        List<String> verify(byte[] type) {
            List<String> messages = new ArrayList<>();
            for (VerifyError error : context.verify(type)) {
                messages.add(String.valueOf(error.getMessage()));
            }
            return messages;
        }

        @Override
        public void close() throws IOException {
            for (ZipReader reader : readers) {
                reader.close();
            }
        }
    }
}
