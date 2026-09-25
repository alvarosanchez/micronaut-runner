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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.classfile.Attribute;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.LineNumber;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strip step as a build runs it: {@code -g}-compiled dependency classes packaged by {@link RunnerJarBuilder},
 * read back out of the nested jar and compared, in behaviour and in structure, with the classes as compiled.
 */
class LocalVariableStripperTest {

    private static final String FIXTURE = "fixture.Stripped";

    private static final String FIXTURE_ENTRY = "fixture/Stripped.class";

    @TempDir
    static Path temp;

    private static Path applicationClasses;

    @BeforeAll
    static void compileTheApplication() throws IOException {
        applicationClasses = ClassFixtures.compile(temp.resolve("app-src"), temp.resolve("app-classes"),
                List.of("--release", "25"), ClassFixtures.source("app.Main", """
                        package app;
                        public class Main {
                            public static void main(String[] args) {
                            }
                        }
                        """));
        Assumptions.assumeTrue(
                RunnerJarBuilder.class.getResource("/META-INF/micronaut-runner/launcher.jar") != null,
                "the bundled launcher jar is not on the test class path");
    }

    @ParameterizedTest(name = "--release {0}")
    @ValueSource(ints = {8, 11, 17, 25})
    void strippedClassesBehaveAsCompiledAndKeepWhatReflectionAndStackTracesRead(int release) throws Exception {
        Map<String, byte[]> original = fixture(release, "release-" + release, List.of("-g", "-parameters"));
        Path dependency = ClassFixtures.jar(temp.resolve("release-" + release + "/fixture.jar"), original);

        Path output = temp.resolve("release-" + release + "/app.jar");
        RunnerJarResult result = build(output, List.of(Dependency.of(dependency)), true);
        Map<String, byte[]> stripped = nestedClasses(output, "MICRONAUT-INF/lib/fixture.jar");

        TransformReport report = result.transforms().get(0);
        assertEquals(LocalVariableStripper.NAME, report.step());
        assertEquals(0, report.fallbacks(), report::toString);
        assertTrue(report.rewritten() >= 1, report::toString);
        assertEquals(original.keySet(), stripped.keySet(), "no class is added or removed");
        assertTrue(stripped.get(FIXTURE_ENTRY).length < original.get(FIXTURE_ENTRY).length, "the class shrank");

        for (Map.Entry<String, byte[]> entry : stripped.entrySet()) {
            ClassModel before = ClassFile.of().parse(original.get(entry.getKey()));
            ClassModel after = ClassFile.of().parse(entry.getValue());
            assertEquals(before.majorVersion(), after.majorVersion(), entry.getKey());
            assertStructure(entry.getKey(), before, after);
        }

        Class<?> compiled = load(original);
        Class<?> rewritten = load(stripped);
        assertEquals(behaviour(compiled), behaviour(rewritten), "the same results");
        assertEquals(reflection(compiled), reflection(rewritten), "the same reflective view");
        Method add = rewritten.getMethod("add", Comparable.class, int.class);
        assertEquals(List.of("item", "weight"), Arrays.stream(add.getParameters()).map(Parameter::getName).toList(),
                "MethodParameters is kept");
        assertTrue(add.getParameters()[0].isNamePresent());

        StackTraceElement thrownAsCompiled = thrownFrom(compiled);
        StackTraceElement thrownStripped = thrownFrom(rewritten);
        assertEquals("Stripped.java", thrownStripped.getFileName());
        assertEquals(thrownAsCompiled.getLineNumber(), thrownStripped.getLineNumber());
        assertTrue(thrownStripped.getLineNumber() > 0);
        assertTrue(thrownStripped.toString().endsWith("(Stripped.java:" + thrownAsCompiled.getLineNumber() + ")"),
                thrownStripped::toString);
    }

    @Test
    void aClassWithAnUnknownAttributeTheClassesOfASignedJarAndModuleInfoAreLeftAlone() throws Exception {
        Map<String, byte[]> classes = fixture(25, "left-alone", List.of("-g"));
        byte[] withUnknown = ClassFixtures.withUnknownAttribute(classes.get(FIXTURE_ENTRY));
        byte[] moduleInfo = moduleInfo();

        Map<String, byte[]> plainEntries = new LinkedHashMap<>();
        plainEntries.put(FIXTURE_ENTRY, withUnknown);
        plainEntries.put("module-info.class", moduleInfo);
        plainEntries.put("META-INF/versions/11/module-info.class", moduleInfo);
        Path plain = ClassFixtures.jar(temp.resolve("left-alone/plain.jar"), plainEntries);

        Map<String, byte[]> signedEntries = new LinkedHashMap<>(classes);
        signedEntries.put("META-INF/SIGNER.SF", ClassFixtures.utf8("Signature-Version: 1.0\n"));
        signedEntries.put("META-INF/SIGNER.RSA", new byte[] {1, 2, 3});
        Path signed = ClassFixtures.jar(temp.resolve("left-alone/signed.jar"), signedEntries);

        Path output = temp.resolve("left-alone/app.jar");
        RunnerJarResult result = build(output, List.of(Dependency.of(plain), Dependency.of(signed)), true);

        Map<String, byte[]> nestedPlain = nestedClasses(output, "MICRONAUT-INF/lib/plain.jar");
        assertArrayEquals(withUnknown, nestedPlain.get(FIXTURE_ENTRY), "an unknown attribute declines the class");
        assertArrayEquals(moduleInfo, nestedPlain.get("module-info.class"));
        assertArrayEquals(moduleInfo, nestedPlain.get("META-INF/versions/11/module-info.class"));
        Map<String, byte[]> nestedSigned = nestedClasses(output, "MICRONAUT-INF/lib/signed.jar");
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            assertArrayEquals(entry.getValue(), nestedSigned.get(entry.getKey()), entry.getKey() + " of a signed jar");
        }
        TransformReport report = result.transforms().get(0);
        assertEquals(0, report.rewritten(), report::toString);
        assertEquals(0, report.fallbacks(), report::toString);
        assertEquals(3 + classes.size(), report.unchanged(), report::toString);
    }

    @Test
    void theClassesOfAProjectModuleAreNeverStrippedAndCountAsUnchanged() throws Exception {
        Map<String, byte[]> classes = fixture(25, "project-module", List.of("-g"));
        Path module = ClassFixtures.jar(temp.resolve("project-module/lib.jar"), classes);
        Path library = ClassFixtures.jar(temp.resolve("project-module/library.jar"),
                fixture(25, "project-module-library", List.of("-g")));

        Path output = temp.resolve("project-module/app.jar");
        RunnerJarResult result = build(output, List.of(Dependency.of(module, "com.example:lib:1.0")
                .projectModule(true), Dependency.of(library)), true);

        Map<String, byte[]> nested = nestedClasses(output, "MICRONAUT-INF/lib/lib.jar");
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            assertArrayEquals(entry.getValue(), nested.get(entry.getKey()), entry.getKey());
        }
        // Only the library's copy of the class with code carries local-variable tables; its two annotation
        // interfaces have nothing to strip, and every class of the module is counted as unchanged.
        TransformReport report = result.transforms().get(0);
        assertEquals(1, report.rewritten(), report::toString);
        assertEquals(2 * classes.size() - 1, report.unchanged(), report::toString);
        assertEquals(0, report.fallbacks(), report::toString);
    }

    @Test
    void aKnownLocalVariableTableReaderTurnsStrippingOffWithOneWarning() throws Exception {
        Map<String, byte[]> classes = fixture(25, "reader", List.of("-g"));
        Map<String, byte[]> readerEntries = new LinkedHashMap<>();
        readerEntries.put("com/thoughtworks/paranamer/BytecodeReadingParanamer.class", new byte[0]);
        Path paranamer = ClassFixtures.jar(temp.resolve("reader/paranamer.jar"), readerEntries);
        Path library = ClassFixtures.jar(temp.resolve("reader/library.jar"), classes);
        List<Dependency> dependencies = List.of(Dependency.of(library), Dependency.of(paranamer));

        Path disabled = temp.resolve("reader/disabled.jar");
        RunnerJarResult result = build(disabled, dependencies, true);
        Path off = temp.resolve("reader/off.jar");
        RunnerJarResult offResult = build(off, dependencies, false);

        List<String> warnings = result.warnings().stream()
                .filter(warning -> warning.contains("local-variable")).toList();
        assertEquals(1, warnings.size(), result.warnings()::toString);
        assertTrue(warnings.get(0).contains(paranamer.toString()), warnings::toString);
        assertTrue(warnings.get(0).contains("com/thoughtworks/paranamer/BytecodeReadingParanamer.class"),
                warnings::toString);
        assertTrue(warnings.get(0).contains("stripLocalVariables option to false"), warnings::toString);
        assertEquals(List.of(), result.transforms());
        assertEquals(List.of(), offResult.warnings());
        assertEquals(-1, Files.mismatch(disabled, off), "the archive is the one stripping off builds");
        assertFalse(nestedClasses(off, "MICRONAUT-INF/lib/library.jar").isEmpty());
    }

    @Test
    void preserveBuildsWithTheOptionOnAndSaysOnceThatItHasNoEffect() throws Exception {
        Path dependency = ClassFixtures.jar(temp.resolve("preserve/fixture.jar"),
                fixture(25, "preserve", List.of("-g")));
        List<String> info = new ArrayList<>();
        BuildLogger logger = new BuildLogger() {
            @Override
            public void info(String message) {
                info.add(message);
            }

            @Override
            public void warn(String message) {
            }
        };

        RunnerJarResult result = RunnerJarBuilder.build(RunnerJarSpec.builder()
                .mainClass("app.Main")
                .applicationOutput(List.of(applicationClasses))
                .dependencies(List.of(Dependency.of(dependency)))
                .output(temp.resolve("preserve/app.jar"))
                .compression(Compression.PRESERVE)
                .build(), logger);

        assertEquals(List.of(), result.transforms());
        assertEquals(1, info.stream().filter(line -> line.contains("stripLocalVariables")
                && line.contains("has no effect")).count(), info::toString);
        try (ZipFile zip = new ZipFile(temp.resolve("preserve/app.jar").toFile())) {
            assertEquals(null, zip.getEntry("MICRONAUT-INF/transforms.txt"));
        }
    }

    @Test
    void stripOnItsOwnDeclinesWhatThePipelineDeclines() throws Exception {
        Map<String, byte[]> classes = fixture(25, "standalone", List.of("-g"));
        byte[] compiled = classes.get(FIXTURE_ENTRY);
        byte[] stripped = LocalVariableStripper.strip(compiled);

        assertTrue(stripped.length < compiled.length);
        for (MethodModel method : ClassFile.of().parse(stripped).methods()) {
            method.code().ifPresent(code -> assertFalse(
                    code.findAttribute(Attributes.localVariableTable()).isPresent(), method.methodName()::toString));
        }
        byte[] withUnknown = ClassFixtures.withUnknownAttribute(compiled);
        assertSame(withUnknown, LocalVariableStripper.strip(withUnknown));
        byte[] withoutDebug = Files.readAllBytes(applicationClasses.resolve("app/Main.class"));
        assertSame(withoutDebug, LocalVariableStripper.strip(withoutDebug), "nothing to drop");
        assertTrue(LocalVariableStripper.isKnownReader("org/aspectj/weaver/World.class"));
        assertFalse(LocalVariableStripper.isKnownReader("org/aspectj/lang/Aspects.class"));
    }

    /** The structural comparison of one class, as compiled and as stripped. */
    private static void assertStructure(String name, ClassModel before, ClassModel after) {
        // A rebuilt pool writes BootstrapMethods last, so the class attributes are compared as a set.
        assertEquals(new java.util.TreeSet<>(names(before.attributes())), new java.util.TreeSet<>(
                names(after.attributes())), name + " class attributes");
        assertEquals(before.methods().size(), after.methods().size(), name);
        for (int i = 0; i < before.methods().size(); i++) {
            MethodModel original = before.methods().get(i);
            MethodModel stripped = after.methods().get(i);
            String method = name + " " + original.methodName() + original.methodType();
            assertEquals(original.methodName().stringValue() + original.methodType().stringValue(),
                    stripped.methodName().stringValue() + stripped.methodType().stringValue(), method);
            assertEquals(names(original.attributes()).stream()
                    .filter(attribute -> !attribute.equals("RuntimeInvisibleTypeAnnotations")).toList(),
                    names(stripped.attributes()), method + " attributes");
            if (original.code().isEmpty()) {
                continue;
            }
            CodeModel code = stripped.code().orElseThrow();
            List<String> codeAttributes = names(code.attributes());
            assertFalse(codeAttributes.contains("LocalVariableTable"), method);
            assertFalse(codeAttributes.contains("LocalVariableTypeTable"), method);
            assertFalse(codeAttributes.contains("RuntimeVisibleTypeAnnotations"), method);
            assertFalse(codeAttributes.contains("RuntimeInvisibleTypeAnnotations"), method);
            assertEquals(original.code().get().findAttribute(Attributes.stackMapTable()).isPresent(),
                    code.findAttribute(Attributes.stackMapTable()).isPresent(), method + " frames");
            assertEquals(linesByInstruction(original.code().get()), linesByInstruction(code),
                    method + " maps every instruction to the same line");
            assertEquals(opcodes(original.code().get()), opcodes(code), method + " has the same instructions");
        }
        for (String kept : List.of("Signature", "SourceFile", "RuntimeVisibleAnnotations", "InnerClasses")) {
            assertEquals(names(before.attributes()).contains(kept), names(after.attributes()).contains(kept),
                    name + " keeps " + kept);
        }
    }

    private static List<Integer> linesByInstruction(CodeModel code) {
        List<Integer> lines = new ArrayList<>();
        int line = -1;
        for (CodeElement element : code) {
            if (element instanceof LineNumber number) {
                line = number.line();
            } else if (element instanceof Instruction) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * The opcodes by instruction index, with {@code ldc} and {@code ldc_w} counted as one: a rebuilt pool is
     * free to choose either.
     */
    private static List<String> opcodes(CodeModel code) {
        List<String> opcodes = new ArrayList<>();
        for (CodeElement element : code) {
            if (element instanceof Instruction instruction) {
                String opcode = instruction.opcode().name();
                opcodes.add(opcode.equals("LDC_W") ? "LDC" : opcode);
            }
        }
        return opcodes;
    }

    private static List<String> names(List<Attribute<?>> attributes) {
        return attributes.stream().map(attribute -> attribute.attributeName().stringValue()).toList();
    }

    private static List<Object> behaviour(Class<?> type) throws Exception {
        List<Object> results = new ArrayList<>();
        Method constants = type.getMethod("constants", int.class);
        for (int index = 0; index < 400; index++) {
            results.add(constants.invoke(null, index));
        }
        Object instance = type.getConstructor().newInstance();
        results.add(type.getMethod("add", Comparable.class, int.class).invoke(instance, "abc", 4));
        results.add(type.getMethod("fail", int.class).invoke(null, 3));
        results.add(type.getMethod("size").invoke(instance));
        return results;
    }

    private static List<String> reflection(Class<?> type) {
        List<String> view = new ArrayList<>();
        view.add(type.toGenericString());
        view.add(Arrays.toString(type.getTypeParameters()));
        for (Method method : type.getDeclaredMethods()) {
            view.add(method.toGenericString());
            for (Annotation annotation : method.getAnnotations()) {
                view.add(method.getName() + " " + annotation);
            }
            view.add(method.getName() + " returns " + method.getAnnotatedReturnType());
            for (Parameter parameter : method.getParameters()) {
                view.add(method.getName() + " " + parameter + " " + parameter.isNamePresent());
            }
        }
        view.sort(String::compareTo);
        return view;
    }

    private static StackTraceElement thrownFrom(Class<?> type) throws Exception {
        try {
            type.getMethod("fail", int.class).invoke(null, 9);
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IllegalStateException, e::toString);
            return e.getCause().getStackTrace()[0];
        }
        throw new AssertionError("fail(9) did not throw");
    }

    private static Class<?> load(Map<String, byte[]> classes) throws ClassNotFoundException {
        ClassLoader loader = new ClassLoader(LocalVariableStripperTest.class.getClassLoader().getParent()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/') + ".class");
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        return loader.loadClass(FIXTURE);
    }

    private static RunnerJarResult build(Path output, List<Dependency> dependencies, boolean strip)
            throws IOException {
        Files.createDirectories(output.getParent());
        return RunnerJarBuilder.build(RunnerJarSpec.builder()
                .mainClass("app.Main")
                .applicationOutput(List.of(applicationClasses))
                .dependencies(dependencies)
                .output(output)
                .stripLocalVariables(strip)
                .build(), BuildLogger.noOp());
    }

    /** The entries of a jar nested in a runner jar, by name. */
    static Map<String, byte[]> nestedClasses(Path archive, String nestedName) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry nested = zip.getEntry(nestedName);
            assertNotNull(nested, () -> nestedName + " is not in " + archive);
            byte[] bytes;
            try (InputStream in = zip.getInputStream(nested)) {
                bytes = in.readAllBytes();
            }
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                    if (entry.getName().endsWith(".class")) {
                        entries.put(entry.getName(), in.readAllBytes());
                    }
                }
            }
        }
        return entries;
    }

    private static Map<String, byte[]> fixture(int release, String directory, List<String> debug)
            throws IOException {
        List<String> options = new ArrayList<>(debug);
        options.addAll(List.of("--release", Integer.toString(release)));
        Path classes = ClassFixtures.compile(temp.resolve(directory + "/src"), temp.resolve(directory + "/classes"),
                options, ClassFixtures.source(FIXTURE, source()));
        return ClassFixtures.classes(classes);
    }

    private static byte[] moduleInfo() throws IOException {
        Path classes = ClassFixtures.compile(temp.resolve("module/src"), temp.resolve("module/classes"),
                List.of("-g", "--release", "25"), Map.of(
                        "module-info.java", "module fixture.module { exports fixture.module; }\n",
                        "fixture/module/Api.java", "package fixture.module; public interface Api { }\n"));
        return Files.readAllBytes(classes.resolve("module-info.class"));
    }

    /**
     * A class that exercises what a rebuilt constant pool moves: more than 256 constants, so that a rebuilt
     * pool chooses {@code ldc} and {@code ldc_w} afresh, a {@code tableswitch} and a {@code lookupswitch} after
     * them, whose padding then moves, a type-annotated local, lines holding several statements, a generic
     * signature and a runtime annotation. It compiles at {@code --release 8}.
     */
    private static String source() {
        StringBuilder constants = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            if (i > 0) {
                constants.append(", ");
            }
            constants.append("\"constant-").append(i).append('"');
        }
        return """
                package fixture;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                import java.util.ArrayList;
                import java.util.List;

                public class Stripped<T extends Comparable<T>> {

                    @Target(ElementType.TYPE_USE)
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Checked {
                    }

                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Marker {
                        String value();
                    }

                    private final List<T> items = new ArrayList<T>();

                    @Marker("kept")
                    public <E extends T> int add(E item, int weight) {
                        @Checked String label = String.valueOf(item); int total = weight; items.add(item);
                        return label.length() + total;
                    }

                    public int size() {
                        return items.size();
                    }

                    public static String constants(int index) {
                        String[] all = {@constants@};
                        String picked = all[index % all.length];
                        switch (index % 4) {
                            case 0: picked = picked + "-zero"; break;
                            case 1: picked = picked + "-one"; break;
                            case 2: picked = picked + "-two"; break;
                            default: picked = picked + "-other";
                        }
                        switch (index * 1000) {
                            case 1000: picked = picked + "!"; break;
                            case 70000: picked = picked + "?"; break;
                            default: break;
                        }
                        return picked;
                    }

                    public static int fail(int value) {
                        int doubled = value * 2;
                        if (doubled > 10) {
                            throw new IllegalStateException("too big: " + doubled);
                        }
                        return doubled;
                    }
                }
                """.replace("@constants@", constants);
    }
}
