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
package io.micronaut.runner;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.classfile.AccessFlags;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the launcher's public API to {@code src/test/resources/public-api.txt}.
 *
 * <p>The list is what micronaut-build's binary compatibility check, enabled after 1.0.0, would report as an
 * error when it changes: every public type whose enclosing types are public, with its public members, and its
 * protected members when the type is not final, constructors and enum constants included. Synthetic and bridge
 * members are left out, and so is everything micronaut-build 8.1.2 lowers to a warning: a type that carries
 * {@code io.micronaut.core.annotation.Internal} itself (with all of its members), a member annotated with it, and a
 * type under {@code io.micronaut} whose name contains {@code .internal.}. {@code @Internal} counts per class, as in
 * micronaut-build: a public nested type of an {@code @Internal} type is listed unless it carries the annotation
 * itself.</p>
 *
 * <p>The only public API users need is the {@code Main-Class}, {@code io.micronaut.runner.Launcher}, and its
 * {@code main} method. Everything else is package-private, or public only because another package or module calls
 * it, and then {@code @Internal}. From 1.0.0, a change to the list is a 1.x binary-compatibility decision.</p>
 */
class PublicApiTest {

    /** The checked-in list, a resource of this test. */
    private static final String ALLOWLIST = "/public-api.txt";

    /** The descriptor of the annotation that turns japicmp's errors into warnings. */
    private static final String INTERNAL = "Lio/micronaut/core/annotation/Internal;";

    @Test
    void theLaunchersPublicApiIsExactlyTheCheckedInList() throws IOException {
        List<String> actual = publicApi(launcherClassFiles());
        List<String> expected = allowlist();
        if (actual.equals(expected)) {
            return;
        }
        TreeSet<String> added = new TreeSet<>(actual);
        added.removeAll(expected);
        TreeSet<String> removed = new TreeSet<>(expected);
        removed.removeAll(actual);
        StringBuilder message = new StringBuilder(512);
        message.append("The launcher's public API differs from src/test/resources").append(ALLOWLIST)
                .append(". Keep a new type or member package-private, or mark it @Internal when another package or")
                .append(" module must call it; change the list only for API users need. From 1.0.0 a change is a")
                .append(" 1.x binary-compatibility decision.\n");
        for (String line : added) {
            message.append("+ ").append(line).append('\n');
        }
        for (String line : removed) {
            message.append("- ").append(line).append('\n');
        }
        fail(message.toString());
    }

    @Test
    void listsAPublicTypeWithItsPublicMembersAndNothingElse() {
        byte[] type = ClassFile.of().build(ClassDesc.of("io.example.Api"), builder -> builder
                .withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
                .withField("CONSTANT", ConstantDescs.CD_String, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)
                .withField("hidden", ConstantDescs.CD_int, 0)
                .withMethodBody("run", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, code -> code
                        .return_())
                .withMethodBody("protectedInAFinalType", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PROTECTED, code -> code.return_())
                .withMethodBody("bridge", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_BRIDGE | ClassFile.ACC_SYNTHETIC, code -> code.return_())
                .withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PRIVATE, code -> code
                        .return_()));
        // The ClassFile API makes a class public unless told otherwise.
        byte[] hidden = ClassFile.of().build(ClassDesc.of("io.example.Hidden"), builder -> builder
                .withFlags(ClassFile.ACC_SUPER)
                .withMethodBody("run", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, code -> code
                        .return_()));

        assertEquals(List.of("io.example.Api", "io.example.Api#CONSTANT:Ljava/lang/String;",
                "io.example.Api#run()V"), publicApi(Map.of("io.example.Api", type, "io.example.Hidden", hidden)));
    }

    @Test
    void listsTheProtectedMembersOfATypeThatIsNotFinal() {
        byte[] type = ClassFile.of().build(ClassDesc.of("io.example.Open"), builder -> builder
                .withFlags(AccessFlag.PUBLIC)
                .withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PROTECTED,
                        code -> code.return_()));

        assertEquals(List.of("io.example.Open", "io.example.Open#<init>()V"),
                publicApi(Map.of("io.example.Open", type)));
    }

    @Test
    void leavesOutWhatMicronautBuildLowersToAWarning() {
        byte[] internalType = ClassFile.of().build(ClassDesc.of("io.example.Internal"), builder -> builder
                .withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
                .with(internal())
                .withMethodBody("run", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, code -> code
                        .return_()));
        byte[] internalMember = ClassFile.of().build(ClassDesc.of("io.example.Member"), builder -> builder
                .withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
                .withMethod("register", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, method -> method
                                .with(internal())
                                .withCode(code -> code.return_())));
        byte[] internalPackage = ClassFile.of().build(ClassDesc.of("io.micronaut.example.internal.Tool"),
                builder -> builder.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL));

        assertEquals(List.of("io.example.Member"), publicApi(Map.of(
                "io.example.Internal", internalType,
                "io.example.Member", internalMember,
                "io.micronaut.example.internal.Tool", internalPackage)));
    }

    @Test
    void countsInternalPerClassAndNestedTypesByTheirOwnAndTheirEnclosingAccess() {
        ClassDesc outer = ClassDesc.of("io.example.Outer");
        ClassDesc nested = ClassDesc.of("io.example.Outer$Nested");
        ClassDesc hidden = ClassDesc.of("io.example.Hidden");
        ClassDesc hiddenNested = ClassDesc.of("io.example.Hidden$Nested");
        byte[] outerType = ClassFile.of().build(outer, builder -> builder
                .withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
                .with(internal())
                .with(innerClasses(nested, outer, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)));
        byte[] nestedType = ClassFile.of().build(nested, builder -> builder
                .withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
                .with(innerClasses(nested, outer, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)));
        byte[] hiddenType = ClassFile.of().build(hidden, builder -> builder
                .withFlags(ClassFile.ACC_SUPER)
                .with(innerClasses(hiddenNested, hidden, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)));
        byte[] hiddenNestedType = ClassFile.of().build(hiddenNested, builder -> builder
                .withFlags(AccessFlag.PUBLIC)
                .with(innerClasses(hiddenNested, hidden, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)));

        assertEquals(List.of("io.example.Outer$Nested"), publicApi(Map.of(
                "io.example.Outer", outerType,
                "io.example.Outer$Nested", nestedType,
                "io.example.Hidden", hiddenType,
                "io.example.Hidden$Nested", hiddenNestedType)));
    }

    /**
     * Lists what japicmp would report as an error when it changes, as {@code type} and {@code type#member}
     * lines with the member's descriptor, sorted.
     *
     * @param classFiles the class files, by binary name
     * @return the lines
     */
    static List<String> publicApi(Map<String, byte[]> classFiles) {
        Map<String, ClassModel> models = new TreeMap<>();
        for (Map.Entry<String, byte[]> file : classFiles.entrySet()) {
            models.put(file.getKey(), ClassFile.of().parse(file.getValue()));
        }
        TreeSet<String> lines = new TreeSet<>();
        for (ClassModel model : models.values()) {
            String name = binaryName(model.thisClass());
            if (!exported(model, models) || annotatedInternal(model.findAttribute(
                    Attributes.runtimeVisibleAnnotations())) || internalPackage(name)) {
                continue;
            }
            lines.add(name);
            boolean open = !model.flags().has(AccessFlag.FINAL);
            for (FieldModel field : model.fields()) {
                if (visible(field.flags(), open) && !annotatedInternal(field.findAttribute(
                        Attributes.runtimeVisibleAnnotations()))) {
                    lines.add(name + "#" + field.fieldName().stringValue() + ":" + field.fieldType().stringValue());
                }
            }
            for (MethodModel method : model.methods()) {
                if (visible(method.flags(), open) && !method.flags().has(AccessFlag.BRIDGE)
                        && !annotatedInternal(method.findAttribute(Attributes.runtimeVisibleAnnotations()))) {
                    lines.add(name + "#" + method.methodName().stringValue() + method.methodType().stringValue());
                }
            }
        }
        return new ArrayList<>(lines);
    }

    /**
     * Whether a type is reachable from outside its package: public, or protected in a type that is not final,
     * and nested only in types that are themselves reachable. A nested type's real access is in the
     * {@code InnerClasses} attribute; its class file flags make a protected type public and a private one
     * package-private.
     */
    private static boolean exported(ClassModel model, Map<String, ClassModel> models) {
        ClassEntry self = model.thisClass();
        Optional<InnerClassInfo> nesting = model.findAttribute(Attributes.innerClasses()).stream()
                .flatMap(attribute -> attribute.classes().stream())
                .filter(info -> info.innerClass().asInternalName().equals(self.asInternalName()))
                .findFirst();
        if (nesting.isEmpty()) {
            return model.flags().has(AccessFlag.PUBLIC);
        }
        InnerClassInfo info = nesting.get();
        if (info.outerClass().isEmpty()) {
            // A local or anonymous class.
            return false;
        }
        ClassModel enclosing = models.get(binaryName(info.outerClass().get()));
        if (enclosing == null || !exported(enclosing, models)) {
            return false;
        }
        return info.has(AccessFlag.PUBLIC)
                || (info.has(AccessFlag.PROTECTED) && !enclosing.flags().has(AccessFlag.FINAL));
    }

    private static boolean visible(AccessFlags flags, boolean open) {
        if (flags.has(AccessFlag.SYNTHETIC)) {
            return false;
        }
        return flags.has(AccessFlag.PUBLIC) || (open && flags.has(AccessFlag.PROTECTED));
    }

    private static boolean annotatedInternal(Optional<RuntimeVisibleAnnotationsAttribute> annotations) {
        return annotations.isPresent() && annotations.get().annotations().stream()
                .anyMatch(annotation -> annotation.className().stringValue().equals(INTERNAL));
    }

    private static boolean internalPackage(String name) {
        return name.startsWith("io.micronaut") && name.contains(".internal.");
    }

    private static String binaryName(ClassEntry entry) {
        return entry.asInternalName().replace('/', '.');
    }

    /**
     * The launcher's main class files, read from where this test's class path loads {@link Launcher} from: the
     * compiled classes directory in this build, or a jar.
     */
    private static Map<String, byte[]> launcherClassFiles() throws IOException {
        Path location;
        try {
            location = Path.of(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        Map<String, byte[]> classes = new TreeMap<>();
        if (Files.isDirectory(location)) {
            try (Stream<Path> files = Files.walk(location)) {
                for (Path file : files.filter(PublicApiTest::isClassFile).toList()) {
                    String relative = location.relativize(file).toString().replace(file.getFileSystem()
                            .getSeparator(), "/");
                    classes.put(className(relative), Files.readAllBytes(file));
                }
            }
        } else {
            try (JarFile jar = new JarFile(location.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class") && !entry.getName().endsWith("module-info.class")) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            classes.put(className(entry.getName()), in.readAllBytes());
                        }
                    }
                }
            }
        }
        assertTrue(classes.containsKey(Launcher.class.getName()), "no launcher classes at " + location);
        return classes;
    }

    private static boolean isClassFile(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".class") && !name.equals("module-info.class");
    }

    private static String className(String path) {
        return path.substring(0, path.length() - ".class".length()).replace('/', '.');
    }

    /**
     * The checked-in list, without its comment lines.
     */
    private static List<String> allowlist() throws IOException {
        List<String> lines = new ArrayList<>();
        try (InputStream in = PublicApiTest.class.getResourceAsStream(ALLOWLIST)) {
            assertNotNull(in, "no " + ALLOWLIST + " on the test class path");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
        }
        return lines;
    }

    private static RuntimeVisibleAnnotationsAttribute internal() {
        return RuntimeVisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.ofDescriptor(INTERNAL)));
    }

    private static InnerClassesAttribute innerClasses(ClassDesc inner, ClassDesc outer, int flags) {
        String simpleName = inner.displayName().substring(inner.displayName().indexOf('$') + 1);
        return InnerClassesAttribute.of(InnerClassInfo.of(inner, Optional.of(outer), Optional.of(simpleName), flags));
    }
}
