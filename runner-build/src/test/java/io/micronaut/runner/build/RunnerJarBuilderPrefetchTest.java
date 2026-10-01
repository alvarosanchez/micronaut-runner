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

import io.micronaut.runner.Index;
import io.micronaut.runner.IndexFormat;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the way {@link RunnerJarBuilder} packages the bean definition prefetch: every condition that leaves
 * it out, each on its own, and what the application layer looks like when it is packaged.
 *
 * <p>The Micronaut of these class paths is a jar with only the three class files the packager parses, copied
 * from the micronaut-inject of the test class path, so the number of bean definition references is the number
 * the fixture registers. What the two classes do when they run is {@code DefinitionPrefetchTest}'s subject, and
 * an application that starts with them is the end-to-end suite's.</p>
 */
class RunnerJarBuilderPrefetchTest {

    private static final String MAIN_CLASS = "com.example.Application";
    private static final String REFERENCES = DefinitionPrefetchPackager.REFERENCES_PREFIX;
    private static final String STAND_DOWN = "No bean definition prefetch was packaged because ";
    private static final String STAND_DOWN_END =
            "; Micronaut loads the bean definitions when the application context starts";
    private static final String CONFIGURER_LINE = DefinitionPrefetchPackager.CONFIGURER_CLASS + "\n";
    private static final String BUILDER_CLASS = "io/micronaut/context/ApplicationContextBuilder.class";
    private static final String CONFIGURATION_CLASS = "io/micronaut/context/BeanContextConfiguration.class";
    private static final String CONFIGURER_TYPE = "io.micronaut.context.ApplicationContextConfigurer";
    private static final String SERVICE_TABLE = "io/micronaut/runner/generated/services/RunnerServiceTable.class";

    @TempDir
    static Path fixtures;

    private static Path classes;
    private static Path references;
    private static Path micronaut;
    private static int counter;

    @BeforeAll
    static void createFixtures() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "this JDK has no java compiler");
        Assumptions.assumeTrue(
                RunnerJarBuilder.class.getResource("/META-INF/micronaut-runner/launcher.jar") != null,
                "the bundled launcher jar is not on the test class path");

        classes = fixtures.resolve("classes");
        Path source = fixtures.resolve("sources/com/example/Application.java");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, """
                package com.example;

                public class Application {
                    public static void main(String[] args) {
                    }
                }
                """);
        assertEquals(0, compiler.run(null, null, null, "--release", "25", "-d", classes.toString(),
                source.toString()));

        references = resources("references", Map.of(
                REFERENCES + "com.example.$Application$Definition", "",
                REFERENCES + "com.example.$Greeter$Definition", ""));
        micronaut = jar("micronaut-inject-5.jar", Map.of(
                DefinitionPrefetchPackager.PROVIDER_ENTRY, real(DefinitionPrefetchPackager.PROVIDER_ENTRY),
                BUILDER_CLASS, real(BUILDER_CLASS),
                CONFIGURATION_CLASS, real(CONFIGURATION_CLASS)));
    }

    @ParameterizedTest
    @EnumSource(value = Compression.class, names = {"STORED", "PRESERVE"})
    void packagesThePrefetchIntoTheApplicationLayer(Compression compression) throws IOException {
        Path without = output();
        Path with = output();
        Path again = output();
        build(spec(without, references, micronaut).compression(compression).definitionPrefetch(false));

        Build build = build(spec(with, references, micronaut).compression(compression));
        build(spec(again, references, micronaut).compression(compression));

        assertTrue(build.result().definitionPrefetch());
        assertEquals("true", build.result().effectiveOptions().get("definitionPrefetch"));
        assertEquals(List.of(), build.result().warnings());
        assertEquals(List.of("Packaged the bean definition prefetch for 2 bean definition references"),
                build.prefetch());
        assertArrayEquals(Files.readAllBytes(with), Files.readAllBytes(again), "the build is reproducible");

        Map<String, byte[]> shipped = DefinitionPrefetchPackager.classes();
        assertEquals(List.of(DefinitionPrefetchPackager.TASK_ENTRY, DefinitionPrefetchPackager.CONFIGURER_ENTRY),
                List.copyOf(shipped.keySet()));
        try (RunnerJarArchive reader = RunnerJarArchive.open(with)) {
            Index index = reader.index();
            assertNotEquals(IndexFormat.NO_INDEX, index.findClass(DefinitionPrefetchPackager.TASK_CLASS));
            assertNotEquals(IndexFormat.NO_INDEX, index.findClass(DefinitionPrefetchPackager.CONFIGURER_CLASS));
            for (Map.Entry<String, byte[]> item : shipped.entrySet()) {
                byte[] packaged = reader.read(index.find(item.getKey()));
                assertArrayEquals(item.getValue(), packaged, item.getKey());
                // Every application carries them: line numbers for stack traces, no local-variable tables.
                assertTrue(ClassFixtures.assertLineNumbersWithoutLocalVariables(item.getKey(), packaged) > 0,
                        item.getKey());
            }
            assertArrayEquals(EntryStubGenerator.generate(MAIN_CLASS, true),
                    reader.read(index.find(EntryStubGenerator.STUB_RESOURCE_NAME)));
            assertEquals(CONFIGURER_LINE, new String(
                    reader.read(index.find(DefinitionPrefetchPackager.SERVICE_ENTRY)), StandardCharsets.UTF_8));
        }

        // Nothing moves: the archive without the prefetch, then the two classes and the new service file.
        List<String> expected = new ArrayList<>(applicationLayer(without));
        assertTrue(expected.contains(EntryStubGenerator.STUB_RESOURCE_NAME), expected::toString);
        expected.addAll(List.of(DefinitionPrefetchPackager.TASK_ENTRY, DefinitionPrefetchPackager.CONFIGURER_ENTRY,
                DefinitionPrefetchPackager.SERVICE_ENTRY));
        assertEquals(expected, applicationLayer(with));
    }

    /**
     * An application that already registers configurers, as Micronaut AOT's output does, keeps its lines, in
     * order, before the prefetch's, and the file keeps its place in the archive.
     */
    @ParameterizedTest
    @ValueSource(strings = {"com.example.First\ncom.example.Second", "com.example.First\ncom.example.Second\n",
        "# a comment\r\ncom.example.First\r\n"})
    void addsItsLineAfterTheConfigurersTheApplicationRegisters(String existing) throws IOException {
        Path resources = resources("existing-" + counter++, Map.of(
                REFERENCES + "com.example.$Application$Definition", "",
                DefinitionPrefetchPackager.SERVICE_ENTRY, existing,
                "zz/last.txt", "after the service file"));
        Path without = output();
        Path with = output();
        build(spec(without, resources, micronaut).definitionPrefetch(false));

        Build build = build(spec(with, resources, micronaut));

        assertTrue(build.result().definitionPrefetch());
        assertEquals(List.of("Packaged the bean definition prefetch for 1 bean definition references"),
                build.prefetch());
        try (RunnerJarArchive reader = RunnerJarArchive.open(with)) {
            assertEquals(existing + (existing.endsWith("\n") ? "" : "\n") + CONFIGURER_LINE, new String(
                    reader.read(reader.index().find(DefinitionPrefetchPackager.SERVICE_ENTRY)),
                    StandardCharsets.UTF_8));
        }
        List<String> expected = new ArrayList<>(applicationLayer(without));
        expected.addAll(List.of(DefinitionPrefetchPackager.TASK_ENTRY, DefinitionPrefetchPackager.CONFIGURER_ENTRY));
        assertEquals(expected, applicationLayer(with));
    }

    /**
     * The three Micronaut classes are found in class-path order, so an application output that carries them
     * itself, as a shaded application jar does, is one the prefetch is packaged for.
     */
    @Test
    void findsMicronautInTheApplicationLayer() throws IOException {
        Path own = fixtures.resolve("micronaut-in-application");
        for (String name : List.of(DefinitionPrefetchPackager.PROVIDER_ENTRY, BUILDER_CLASS, CONFIGURATION_CLASS)) {
            Files.createDirectories(own.resolve(name).getParent());
            Files.write(own.resolve(name), real(name));
        }
        Path output = output();

        Build build = build(RunnerJarSpec.builder().mainClass(MAIN_CLASS).definitionPrefetch(true)
                .applicationOutput(List.of(classes, references, own)).output(output));

        assertTrue(build.result().definitionPrefetch());
    }

    /**
     * The prefetch is packaged only for a build that asks for it: left alone, the option is off, and the archive
     * is, byte for byte, the one a build that turns it off gets.
     */
    @Test
    void aBuildThatDoesNotAskGetsNoPrefetch() throws IOException {
        Path untouched = output();
        Path off = output();

        Build build = build(RunnerJarSpec.builder().mainClass(MAIN_CLASS).output(untouched)
                .applicationOutput(List.of(classes, references)).dependencies(List.of(Dependency.of(micronaut))));
        build(spec(off, references, micronaut).definitionPrefetch(false));

        assertFalse(build.result().definitionPrefetch());
        assertEquals("false", build.result().effectiveOptions().get("definitionPrefetch"));
        assertEquals(List.of(STAND_DOWN + "the definitionPrefetch option is false" + STAND_DOWN_END),
                build.prefetch());
        assertNothingPackaged(untouched, true);
        assertArrayEquals(Files.readAllBytes(off), Files.readAllBytes(untouched));
    }

    /**
     * Each condition of {@link RunnerJarSpec#definitionPrefetch()}, on its own: nothing is packaged, the entry
     * stub is the one without the prefetch, and one informational line names the reason.
     */
    @ParameterizedTest
    @ValueSource(strings = {"the option", "no entry stub", "no Micronaut", "a Micronaut without the provider",
        "a provider that is not public", "a provider without provide", "a builder without the setter",
        "a configuration without the getter", "a class file that cannot be parsed", "no reference",
        "a reference with content"})
    void standsDownWithOneInformationalLine(String condition) throws IOException {
        Path output = output();
        RunnerJarSpec.Builder spec = spec(output, references, micronaut);
        boolean stub = true;
        String reason;
        String none = "the class path has no micronaut-inject 5.0 or later: ";
        switch (condition) {
            case "the option" -> {
                spec.option("definitionPrefetch", "false");
                reason = "the definitionPrefetch option is false";
            }
            case "no entry stub" -> {
                spec.entryStub(false);
                stub = false;
                reason = "no entry stub was generated to start it from";
            }
            case "no Micronaut" -> {
                spec.dependencies(List.of());
                reason = none + "no io/micronaut/context/DefaultBeanDefinitionsProvider.class";
            }
            case "a Micronaut without the provider" -> {
                // micronaut-inject 4.x has the builder and the configuration, and no provider class.
                spec.dependencies(List.of(Dependency.of(jar("micronaut-inject-4.jar", Map.of(
                        BUILDER_CLASS, real(BUILDER_CLASS), CONFIGURATION_CLASS, real(CONFIGURATION_CLASS))))));
                reason = none + "no io/micronaut/context/DefaultBeanDefinitionsProvider.class";
            }
            case "a provider that is not public" -> {
                spec.dependencies(List.of(Dependency.of(jar("hidden-provider.jar", Map.of(
                        DefinitionPrefetchPackager.PROVIDER_ENTRY, provider(0, true, true),
                        BUILDER_CLASS, real(BUILDER_CLASS), CONFIGURATION_CLASS, real(CONFIGURATION_CLASS))))));
                reason = none + "io/micronaut/context/DefaultBeanDefinitionsProvider is not a public class";
            }
            case "a provider without provide" -> {
                spec.dependencies(List.of(Dependency.of(jar("reshaped-provider.jar", Map.of(
                        DefinitionPrefetchPackager.PROVIDER_ENTRY, provider(ClassFile.ACC_PUBLIC, true, false),
                        BUILDER_CLASS, real(BUILDER_CLASS), CONFIGURATION_CLASS, real(CONFIGURATION_CLASS))))));
                reason = none + "io/micronaut/context/DefaultBeanDefinitionsProvider has no public"
                        + " provide(ClassLoader)";
            }
            case "a builder without the setter" -> {
                spec.dependencies(List.of(Dependency.of(jar("no-setter.jar", Map.of(
                        DefinitionPrefetchPackager.PROVIDER_ENTRY, real(DefinitionPrefetchPackager.PROVIDER_ENTRY),
                        BUILDER_CLASS, real(CONFIGURATION_CLASS), CONFIGURATION_CLASS, real(CONFIGURATION_CLASS))))));
                reason = none + "no ApplicationContextBuilder.beanDefinitionsProvider(BeanDefinitionsProvider)";
            }
            case "a configuration without the getter" -> {
                spec.dependencies(List.of(Dependency.of(jar("no-getter.jar", Map.of(
                        DefinitionPrefetchPackager.PROVIDER_ENTRY, real(DefinitionPrefetchPackager.PROVIDER_ENTRY),
                        BUILDER_CLASS, real(BUILDER_CLASS))))));
                reason = none + "no BeanContextConfiguration.getBeanDefinitionsProvider()";
            }
            case "a class file that cannot be parsed" -> {
                spec.dependencies(List.of(Dependency.of(jar("not-a-class.jar", Map.of(
                        DefinitionPrefetchPackager.PROVIDER_ENTRY, new byte[] {1, 2, 3, 4},
                        BUILDER_CLASS, real(BUILDER_CLASS), CONFIGURATION_CLASS, real(CONFIGURATION_CLASS))))));
                reason = none + "a Micronaut class file cannot be parsed: ";
            }
            case "no reference" -> {
                spec.applicationOutput(List.of(classes, resources("no-reference", Map.of(
                        "META-INF/micronaut/io.micronaut.inject.BeanIntrospectionReference/com.example.Thing", ""))));
                reason = "the class path registers no bean definition reference under " + REFERENCES;
            }
            case "a reference with content" -> {
                spec.applicationOutput(List.of(classes, references, resources("with-content", Map.of(
                        REFERENCES + "com.example.$Described$Definition", "a descriptor"))));
                reason = "the bean definition reference entry '" + REFERENCES + "com.example.$Described$Definition'"
                        + " has content, which this version of Micronaut Runner does not interpret";
            }
            default -> throw new IllegalArgumentException(condition);
        }

        Build build = build(spec);

        assertFalse(build.result().definitionPrefetch());
        assertEquals(List.of(), build.result().warnings());
        assertEquals(1, build.prefetch().size(), build.prefetch()::toString);
        String line = build.prefetch().get(0);
        assertTrue(line.startsWith(STAND_DOWN + reason), () -> line + "\ndoes not name: " + reason);
        assertTrue(line.endsWith(STAND_DOWN_END), line);
        assertNothingPackaged(output, stub);
    }

    /**
     * A name in the prefetch's own package that the application already uses is a warning, as it is for the
     * entry stub, and nothing is packaged.
     */
    @Test
    void aTakenNameIsAWarning() throws IOException {
        Path output = output();
        String taken = DefinitionPrefetchPackager.PACKAGE_PATH + "Mine.class";

        Build build = build(RunnerJarSpec.builder().mainClass(MAIN_CLASS).output(output).definitionPrefetch(true)
                .applicationOutput(List.of(classes, references, resources("taken", Map.of(taken, "not ours"))))
                .dependencies(List.of(Dependency.of(micronaut))));

        assertFalse(build.result().definitionPrefetch());
        assertEquals(List.of(), build.prefetch());
        assertEquals(1, build.result().warnings().size(), build.result().warnings()::toString);
        assertTrue(build.result().warnings().get(0).startsWith("The application output already carries '" + taken
                + "'; no bean definition prefetch was packaged"), build.result().warnings()::toString);
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertEquals(IndexFormat.NO_INDEX, index.find(DefinitionPrefetchPackager.TASK_ENTRY));
            assertEquals(IndexFormat.NO_INDEX, index.find(DefinitionPrefetchPackager.SERVICE_ENTRY));
            assertArrayEquals(EntryStubGenerator.generate(MAIN_CLASS),
                    reader.read(index.find(EntryStubGenerator.STUB_RESOURCE_NAME)));
        }
    }

    /**
     * With a micronaut-core the static service table serves, the table is generated after the prefetch, so it
     * lists the prefetch's configurer, first, as Micronaut's scan finds it in the application layer. A table
     * generated before would leave it out: the context builder would never load the configurer, and the prefetch
     * would run without the guard and without the handover.
     */
    @Test
    void theStaticServiceTableListsThePrefetchConfigurer() throws IOException {
        List<Dependency> micronautCore = StaticServiceTableGeneratorTest.micronautCore51().stream()
                .map(Dependency::of).toList();
        Path output = output();

        Build build = build(spec(output, references, micronaut).dependencies(micronautCore));

        assertTrue(build.result().definitionPrefetch());
        assertTrue(build.result().staticServiceSlots() > 0, "no static service table was generated");
        try (ZipReader archive = ZipReader.open(output)) {
            StaticServiceTableGeneratorTest.Table table = StaticServiceTableGeneratorTest.Table.of(archive.read(
                    archive.entry(IndexFormat.CLASSES_PREFIX + SERVICE_TABLE).orElseThrow()));
            List<String> configurers = table.names(CONFIGURER_TYPE);
            assertEquals(DefinitionPrefetchPackager.CONFIGURER_CLASS, configurers.get(0), configurers::toString);
            assertEquals(1, configurers.stream()
                    .filter(DefinitionPrefetchPackager.CONFIGURER_CLASS::equals).count(), configurers::toString);
        }
    }

    /**
     * Where no prefetch can be packaged, the option changes no byte of the archive.
     */
    @Test
    void withoutAMicronautTheOptionChangesNoByte() throws IOException {
        Path on = output();
        Path off = output();

        build(spec(on, references, micronaut).dependencies(List.of()));
        build(spec(off, references, micronaut).dependencies(List.of()).definitionPrefetch(false));

        assertArrayEquals(Files.readAllBytes(on), Files.readAllBytes(off));
    }

    @Test
    void theServiceFileKeepsTheApplicationsLinesAndEndsWithOurs() {
        assertEquals(CONFIGURER_LINE, text(DefinitionPrefetchPackager.serviceFile(null)));
        assertEquals(CONFIGURER_LINE, text(DefinitionPrefetchPackager.serviceFile(new byte[0])));
        assertEquals("a.B\n" + CONFIGURER_LINE, text(DefinitionPrefetchPackager.serviceFile(bytes("a.B"))));
        assertEquals("a.B\n\n" + CONFIGURER_LINE, text(DefinitionPrefetchPackager.serviceFile(bytes("a.B\n\n"))));
    }

    private static void assertNothingPackaged(Path output, boolean stub) throws IOException {
        for (String name : applicationLayer(output)) {
            assertFalse(name.startsWith(DefinitionPrefetchPackager.PACKAGE_PATH), name);
            assertNotEquals(DefinitionPrefetchPackager.SERVICE_ENTRY, name);
        }
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            int record = index.find(EntryStubGenerator.STUB_RESOURCE_NAME);
            if (stub) {
                assertArrayEquals(EntryStubGenerator.generate(MAIN_CLASS), reader.read(record),
                        "the entry stub is not the one without the prefetch");
            } else {
                assertEquals(IndexFormat.NO_INDEX, record);
            }
        }
    }

    /** The logical names of the application layer, in archive order. */
    private static List<String> applicationLayer(Path output) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipReader archive = ZipReader.open(output)) {
            for (ZipEntryInfo entry : archive.entries()) {
                if (!entry.directory() && entry.name().startsWith(IndexFormat.CLASSES_PREFIX)) {
                    names.add(entry.name().substring(IndexFormat.CLASSES_PREFIX.length()));
                }
            }
        }
        return names;
    }

    /** A build that asks for the prefetch, which is off unless a build does. */
    private static RunnerJarSpec.Builder spec(Path output, Path resources, Path dependency) {
        return RunnerJarSpec.builder()
                .mainClass(MAIN_CLASS)
                .definitionPrefetch(true)
                .applicationOutput(List.of(classes, resources))
                .dependencies(List.of(Dependency.of(dependency)))
                .output(output);
    }

    private static Path output() {
        counter++;
        return fixtures.resolve("out/app-" + counter + ".jar");
    }

    private static Path resources(String name, Map<String, String> files) throws IOException {
        Path directory = fixtures.resolve("resources/" + name);
        Files.createDirectories(directory);
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = directory.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        return directory;
    }

    private static Path jar(String name, Map<String, byte[]> entries) throws IOException {
        Path file = fixtures.resolve("libs/" + name);
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file), new Manifest())) {
            for (Map.Entry<String, byte[]> entry : new LinkedHashMap<>(entries).entrySet()) {
                ZipEntry record = new ZipEntry(entry.getKey());
                record.setTime(1_000_000_000_000L);
                out.putNextEntry(record);
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    /** A class file of the micronaut-inject on the test class path. */
    private static byte[] real(String entry) throws IOException {
        try (InputStream in = RunnerJarBuilderPrefetchTest.class.getResourceAsStream("/" + entry)) {
            if (in == null) {
                throw new IOException("micronaut-inject is not on the test class path: " + entry);
            }
            return in.readAllBytes();
        }
    }

    /**
     * A class named like Micronaut's provider that is not the provider the prefetch was written for.
     *
     * @param flags       the class access flags
     * @param constructor whether it has a public no-argument constructor
     * @param provide     whether it has a public {@code provide(ClassLoader)}
     */
    private static byte[] provider(int flags, boolean constructor, boolean provide) {
        ClassDesc type = ClassDesc.ofInternalName("io/micronaut/context/DefaultBeanDefinitionsProvider");
        return ClassFile.of().build(type, builder -> {
            builder.withFlags(flags);
            builder.withInterfaceSymbols(ClassDesc.ofInternalName("io/micronaut/context/BeanDefinitionsProvider"));
            if (constructor) {
                builder.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
                        code -> code.aload(0).invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                ConstantDescs.MTD_void).return_());
            }
            if (provide) {
                builder.withMethodBody("provide", MethodTypeDesc.of(ClassDesc.of("java.util.List"),
                                ClassDesc.of("java.lang.ClassLoader")), ClassFile.ACC_PUBLIC,
                        code -> code.aconst_null().areturn());
            }
        });
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }

    /** Builds, keeping the informational lines about the prefetch. */
    private static Build build(RunnerJarSpec.Builder spec) throws IOException {
        List<String> prefetch = new ArrayList<>();
        RunnerJarResult result = RunnerJarBuilder.build(spec.build(), new BuildLogger() {
            @Override
            public void info(String message) {
                if (message.contains("bean definition prefetch")) {
                    prefetch.add(message);
                }
            }

            @Override
            public void warn(String message) {
            }
        });
        return new Build(result, prefetch);
    }

    /**
     * One build and what it said about the prefetch.
     *
     * @param result   the result
     * @param prefetch the informational lines that mention the prefetch
     */
    private record Build(RunnerJarResult result, List<String> prefetch) {
    }
}
