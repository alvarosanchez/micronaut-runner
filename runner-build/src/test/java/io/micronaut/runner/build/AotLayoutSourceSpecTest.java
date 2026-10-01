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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that gives the build plugins' extracted layout its source JAR ({@link AotLayout#sourceSpec}): the
 * shipped spec with every lambda kept, every other value unchanged, the startup class list included, or nothing
 * when the shipped JAR already keeps every lambda; the list without the classes desugaring generates; and the copy
 * of a spec it is built on.
 */
class AotLayoutSourceSpecTest {

    @TempDir
    Path temp;

    @Test
    void theSourceKeepsLambdasAndChangesNothingElse() throws IOException {
        Path list = Files.writeString(temp.resolve("startup-classes.txt"), "app.Main\n");
        RunnerJarSpec shipped = fullSpec(temp.resolve("libs/app-all.jar"))
                .startupClasses(list)
                .build();
        Path output = temp.resolve("layout-source/app-all.jar");

        RunnerJarSpec source = AotLayout.sourceSpec(shipped, output).orElseThrow();

        assertEquals(output, source.output());
        assertFalse(source.desugarLambdas());
        assertTrue(source.layoutSource(), "its build leaves the generated classes out of the startup list");
        assertFalse(shipped.layoutSource());
        assertEquals(Optional.of(list), source.startupClasses(), "the list orders the nested entries");
        Map<String, String> expected = new LinkedHashMap<>(shipped.effectiveOptions());
        expected.put(RunnerJarOption.DESUGAR_LAMBDAS.optionName(), "false");
        assertEquals(expected, source.effectiveOptions(), "every other option is the shipped JAR's");
        assertEquals(shipped.mainClass(), source.mainClass());
        assertEquals(shipped.applicationOutput(), source.applicationOutput());
        assertEquals(shipped.dependencies(), source.dependencies());
        assertEquals(shipped.applicationManifestSource(), source.applicationManifestSource());
        assertEquals(shipped.applicationManifest(), source.applicationManifest());
        assertEquals(shipped.timestamp(), source.timestamp());
    }

    @Test
    void aJarThatKeepsEveryLambdaIsItsOwnSource() {
        Path output = temp.resolve("layout-source/app-all.jar");

        assertEquals(Optional.empty(), AotLayout.sourceSpec(fullSpec(temp.resolve("app-all.jar"))
                .desugarLambdas(false).build(), output), "desugarLambdas=false");
        assertEquals(Optional.empty(), AotLayout.sourceSpec(fullSpec(temp.resolve("app-all.jar"))
                .compression(Compression.PRESERVE).build(), output), "PRESERVE runs no transform");
        assertTrue(AotLayout.sourceSpec(fullSpec(temp.resolve("app-all.jar")).build(), output).isPresent(),
                "the default desugars");
        assertTrue(AotLayout.sourceSpec(fullSpec(temp.resolve("app-all.jar")).compression(Compression.HYBRID)
                .build(), output).isPresent(), "HYBRID desugars as STORED does");
    }

    @Test
    void toBuilderCopiesEveryValueOfTheSpec() throws IllegalAccessException {
        RunnerJarSpec spec = fullSpec(temp.resolve("app-all.jar"))
                .startupClasses(temp.resolve("startup-classes.txt"))
                .applicationManifest(temp.resolve("thin.jar"))
                .build();

        RunnerJarSpec copy = spec.toBuilder().build();

        // Every field, so that one added to the spec without a line in toBuilder fails here.
        for (Field field : RunnerJarSpec.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            assertEquals(field.get(spec), field.get(copy), field.getName());
        }
    }

    /**
     * The layout-source JAR of a desugaring build is, byte for byte, the JAR of the same options with
     * {@code desugarLambdas=false} and the startup class list without the generated classes it names: no generated
     * lambda class and no bridge in any layer, the same hot-first order, and no warning about dropped names.
     */
    @ParameterizedTest
    @EnumSource(value = Compression.class, names = {"STORED", "HYBRID"})
    void theSourceJarIsTheJarOfTheSameOptionsWithLambdasKept(Compression compression) throws IOException {
        Assumptions.assumeTrue(
                RunnerJarBuilder.class.getResource("/META-INF/micronaut-runner/launcher.jar") != null,
                "the bundled launcher jar is not on the test class path");
        // A Java 8 library, whose private lambda bodies desugaring reaches through bridges, and an application.
        Path libraryClasses = ClassFixtures.compile(temp.resolve("library-src"), temp.resolve("library-classes"),
                List.of("--release", "8"), ClassFixtures.source("lib.Greeter", """
                        package lib;

                        import java.util.function.Function;

                        public final class Greeter {
                            public static String greet(String who) {
                                Function<String, String> greeting = name -> "hello " + name;
                                return greeting.apply(who);
                            }
                        }
                        """));
        Path library = ClassFixtures.jar(temp.resolve("greeter.jar"), ClassFixtures.classes(libraryClasses));
        Path application = ClassFixtures.compile(temp.resolve("app-src"), temp.resolve("app-classes"),
                List.of("-cp", libraryClasses.toString()),
                ClassFixtures.source("app.Main", """
                        package app;

                        import java.util.function.Supplier;

                        public final class Main {
                            public static void main(String[] args) {
                                Supplier<String> line = () -> lib.Greeter.greet("world");
                                System.out.println(line.get());
                            }
                        }
                        """));
        // As recorded from the shipped JAR: its generated classes come right after their hosts.
        Path list = Files.writeString(temp.resolve("startup-classes.txt"),
                "app.Main\napp.Main$$Lambda$R0\nlib.Greeter\nlib.Greeter$$Lambda$R0\n");
        Path listWithoutGenerated = Files.writeString(temp.resolve("startup-classes-kept.txt"),
                "app.Main\nlib.Greeter\n");
        RunnerJarSpec shipped = jar(application, library, temp.resolve("shipped/app-all.jar"))
                .compression(compression)
                .startupClasses(list)
                .build();
        RunnerJarBuilder.build(shipped, BuildLogger.noOp());
        Path source = temp.resolve("layout-source/app-all.jar");
        Logged sourceLog = new Logged();
        RunnerJarBuilder.build(AotLayout.sourceSpec(shipped, source).orElseThrow(), sourceLog);
        Path kept = temp.resolve("kept/app-all.jar");
        RunnerJarBuilder.build(jar(application, library, kept).compression(compression).desugarLambdas(false)
                .startupClasses(listWithoutGenerated).build(), BuildLogger.noOp());

        assertEquals(-1, Files.mismatch(source, kept),
                "the source JAR is the JAR built with lambdas kept and the list without the generated classes");
        assertTrue(sourceLog.warnings.isEmpty(), sourceLog.warnings::toString);
        assertTrue(sourceLog.infos.contains("Left 2 generated lambda classes out of the startup class list,"
                + " because the layout-source JAR keeps every lambda"), sourceLog.infos::toString);
        Scan desugared = scan(shipped.output());
        assertFalse(desugared.generated().isEmpty(), "the shipped JAR desugars: " + desugared);
        assertFalse(desugared.bridges().isEmpty(), "the Java 8 host gains a bridge: " + desugared);
        Scan layout = scan(source);
        assertTrue(layout.generated().isEmpty(), layout::toString);
        assertTrue(layout.bridges().isEmpty(), layout::toString);
        assertNotEquals(-1, Files.mismatch(shipped.output(), source));
    }

    /** Keeps what a build logs. */
    private static final class Logged implements BuildLogger {

        private final List<String> infos = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        @Override
        public void info(String message) {
            infos.add(message);
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }
    }

    private static RunnerJarSpec.Builder fullSpec(Path output) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Built-By", "test");
        attributes.put("X-Second", "2");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "1.2.3");
        return RunnerJarSpec.builder()
                .mainClass("app.Main")
                .applicationOutput(List.of(output.resolveSibling("classes"), output.resolveSibling("resources")))
                .dependencies(List.of(Dependency.of(output.resolveSibling("a.jar"), "com.example:a:1"),
                        Dependency.of(output.resolveSibling("b.jar")).projectModule(true)))
                .applicationManifest(manifest)
                .output(output)
                .multiRelease(true)
                .entryStub(false)
                .manifestAttributes(attributes)
                .addOpens(List.of("java.base/java.lang"))
                .addExports(List.of("java.base/sun.nio.ch"))
                .enableNativeAccess(true)
                .archiveReads(ArchiveReads.POSITIONAL)
                .precompileLogback(false)
                .stripLocalVariables(false)
                .staticServices(false)
                .definitionPrefetch(true)
                .timestamp(Instant.parse("2024-01-01T00:00:00Z"));
    }

    private static RunnerJarSpec.Builder jar(Path application, Path library, Path output) {
        return RunnerJarSpec.builder()
                .mainClass("app.Main")
                .applicationOutput(List.of(application))
                .dependencies(List.of(Dependency.of(library, "com.example:greeter:1.0")))
                .output(output);
    }

    /**
     * The generated lambda classes and the bridges of every layer of a Runner JAR.
     *
     * @param generated the entries whose name has {@code $$Lambda$R}
     * @param bridges   the {@code $runner$lambda$} methods, as {@code entry#method}
     */
    private record Scan(List<String> generated, List<String> bridges) {
    }

    private static Scan scan(Path runnerJar) throws IOException {
        List<String> generated = new ArrayList<>();
        List<String> bridges = new ArrayList<>();
        try (ZipFile zip = new ZipFile(runnerJar.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                if (entry.getName().endsWith(".jar")) {
                    try (ZipInputStream nested = new ZipInputStream(zip.getInputStream(entry))) {
                        for (ZipEntry inner = nested.getNextEntry(); inner != null; inner = nested.getNextEntry()) {
                            classEntry(entry.getName() + "!/" + inner.getName(), nested, generated, bridges);
                        }
                    }
                } else {
                    try (InputStream in = zip.getInputStream(entry)) {
                        classEntry(entry.getName(), in, generated, bridges);
                    }
                }
            }
        }
        return new Scan(generated, bridges);
    }

    private static void classEntry(String name, InputStream in, List<String> generated, List<String> bridges)
            throws IOException {
        if (!name.endsWith(".class")) {
            return;
        }
        if (name.contains("$$Lambda$R")) {
            generated.add(name);
        }
        for (MethodModel method : ClassFile.of().parse(in.readAllBytes()).methods()) {
            if (method.methodName().stringValue().startsWith("$runner$lambda$")) {
                bridges.add(name + "#" + method.methodName().stringValue());
            }
        }
    }
}
