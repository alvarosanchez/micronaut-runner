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

import io.micronaut.aot.logback.LogbackPrecompiler;
import io.micronaut.runner.Launcher;
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
import java.lang.classfile.Signature;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins runner-build's public API to {@code src/test/resources/public-api.txt}, and keeps the launcher's and Micronaut
 * AOT's types out of every public signature.
 *
 * <p>The list is what micronaut-build's binary compatibility check, enabled after 1.0.0, would report as an
 * error when it changes: every public type whose enclosing types are public, with its public members, and its
 * protected members when the type is not final, constructors, record accessors and enum constants included.
 * Synthetic and bridge members are left out, and so is everything micronaut-build 8.1.2 lowers to a warning: a
 * type that carries {@code io.micronaut.core.annotation.Internal} itself (with all of its members), a member
 * annotated with it, and a type under {@code io.micronaut} whose name contains {@code .internal.}.
 * {@code @Internal} counts per class, as in micronaut-build: a public nested type of an {@code @Internal} type is
 * listed unless it carries the annotation itself.</p>
 *
 * <p>The API users need is what the Micronaut build plugins call: the spec, its builder, the option table, the
 * builder, its result, the dependency, the compression, the logger, the application manifest,
 * {@code RunnerJarReader.isRunnerJar} and the startup profile recorder with its training settings. Everything else
 * is package-private, or public only because another package or module calls it, and then {@code @Internal}. From
 * 1.0.0, a change to the list is a 1.x binary-compatibility decision.</p>
 *
 * <p>The launcher and {@code micronaut-aot-logback} are runtime dependencies of this library, never compile-time
 * ones of its users: no public or protected signature names one of their types, {@code @Internal} ones included.
 * That covers descriptors, generic signatures, {@code throws} clauses, and the supertypes of a public type.</p>
 *
 * <p>The checks duplicate the launcher's {@code PublicApiTest} on purpose: a shared helper would be public API of
 * its own.</p>
 */
class PublicApiTest {

    /** The checked-in list, a resource of this test. */
    private static final String ALLOWLIST = "/public-api.txt";

    /** The descriptor of the annotation that turns japicmp's errors into warnings. */
    private static final String INTERNAL = "Lio/micronaut/core/annotation/Internal;";

    /** Whether a package, as an internal name, is one of Micronaut AOT's. */
    private static final Predicate<String> MICRONAUT_AOT = name -> name.equals("io/micronaut/aot")
            || name.startsWith("io/micronaut/aot/");

    /** A class type in a descriptor or a generic signature: the internal name after {@code L}. */
    private static final Pattern CLASS_TYPE = Pattern.compile("L([\\w$/]+)");

    @Test
    void runnerBuildsPublicApiIsExactlyTheCheckedInList() throws IOException {
        List<String> actual = publicApi(classFiles(RunnerJarSpec.class));
        List<String> expected = allowlist().stream().sorted().toList();
        if (actual.equals(expected)) {
            return;
        }
        TreeSet<String> added = new TreeSet<>(actual);
        added.removeAll(expected);
        TreeSet<String> removed = new TreeSet<>(expected);
        removed.removeAll(actual);
        StringBuilder message = new StringBuilder(512);
        message.append("runner-build's public API differs from src/test/resources").append(ALLOWLIST)
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
    void noPublicSignatureNamesALauncherType() throws IOException {
        Set<String> launcherPackages = packages(classFiles(Launcher.class).keySet());
        assertTrue(launcherPackages.contains("io/micronaut/runner"), launcherPackages::toString);
        assertFalse(launcherPackages.contains("io/micronaut/runner/build"), launcherPackages::toString);

        List<String> offenders = foreignTypesInSignatures(classFiles(RunnerJarSpec.class),
                launcherPackages::contains);

        assertTrue(offenders.isEmpty(), () -> "public signatures name launcher types, which would put the launcher"
                + " on the compile class path of every build plugin: " + offenders);
    }

    @Test
    void noPublicSignatureNamesAMicronautAotType() throws IOException {
        Set<String> logbackPackages = packages(classFiles(LogbackPrecompiler.class).keySet());
        assertTrue(logbackPackages.stream().allMatch(MICRONAUT_AOT), logbackPackages::toString);

        List<String> offenders = foreignTypesInSignatures(classFiles(RunnerJarSpec.class), MICRONAUT_AOT);

        assertTrue(offenders.isEmpty(), () -> "public signatures name Micronaut AOT types, whose API is internal to"
                + " its callers and which would join the compile class path of every build plugin: " + offenders);
    }

    @Test
    void findsMicronautAotTypesInPublicSignatures() {
        ClassDesc result = ClassDesc.of("io.micronaut.aot.logback.LogbackPrecompiler$Result");
        byte[] type = ClassFile.of().build(ClassDesc.of("io.example.Api"), builder -> builder
                .withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
                .withMethodBody("result", MethodTypeDesc.of(result), ClassFile.ACC_PUBLIC, code -> code
                        .aconst_null().areturn())
                .withMethodBody("aotLookalike", MethodTypeDesc.of(ClassDesc.of("io.micronaut.aotx.Type")),
                        ClassFile.ACC_PUBLIC, code -> code.aconst_null().areturn())
                .withMethodBody("hidden", MethodTypeDesc.of(result), ClassFile.ACC_PRIVATE, code -> code
                        .aconst_null().areturn()));

        assertEquals(List.of("io.example.Api#result()Lio/micronaut/aot/logback/LogbackPrecompiler$Result; names"
                        + " io.micronaut.aot.logback.LogbackPrecompiler$Result"),
                foreignTypesInSignatures(Map.of("io.example.Api", type), MICRONAUT_AOT));
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
        ClassDesc index = ClassDesc.of("io.example.launcher.Index");
        byte[] type = ClassFile.of().build(ClassDesc.of("io.example.Open"), builder -> builder
                .withFlags(AccessFlag.PUBLIC)
                .withField("shared", ConstantDescs.CD_int, ClassFile.ACC_PROTECTED)
                .withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PROTECTED,
                        code -> code.return_())
                .withMethodBody("index", MethodTypeDesc.of(index), ClassFile.ACC_PROTECTED, code -> code
                        .aconst_null().areturn()));

        assertEquals(List.of("io.example.Open", "io.example.Open#<init>()V",
                        "io.example.Open#index()Lio/example/launcher/Index;", "io.example.Open#shared:I"),
                publicApi(Map.of("io.example.Open", type)));
        assertEquals(List.of("io.example.Open#index()Lio/example/launcher/Index; names io.example.launcher.Index"),
                foreignTypesInSignatures(Map.of("io.example.Open", type), Set.of("io/example/launcher")::contains));
    }

    @Test
    void listsANestedTypeOnlyWhenEveryEnclosingTypeIsExported() {
        ClassDesc hidden = ClassDesc.of("io.example.Hidden");
        ClassDesc hiddenNested = ClassDesc.of("io.example.Hidden$Nested");
        ClassDesc open = ClassDesc.of("io.example.Open");
        ClassDesc openNested = ClassDesc.of("io.example.Open$Nested");
        ClassDesc closed = ClassDesc.of("io.example.Closed");
        ClassDesc closedNested = ClassDesc.of("io.example.Closed$Nested");
        // A public type nested in a package-private one: unreachable from another package.
        byte[] hiddenType = ClassFile.of().build(hidden, builder -> builder
                .withFlags(ClassFile.ACC_SUPER)
                .with(innerClasses(hiddenNested, hidden, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)));
        byte[] hiddenNestedType = ClassFile.of().build(hiddenNested, builder -> builder
                .withFlags(AccessFlag.PUBLIC)
                .with(innerClasses(hiddenNested, hidden, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)));
        // A protected type nested in a public type that is not final: a subclass in another package reaches it.
        // Its class file flags say public, as javac writes them.
        byte[] openType = ClassFile.of().build(open, builder -> builder
                .withFlags(AccessFlag.PUBLIC)
                .with(innerClasses(openNested, open, ClassFile.ACC_PROTECTED | ClassFile.ACC_STATIC)));
        byte[] openNestedType = ClassFile.of().build(openNested, builder -> builder
                .withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
                .with(innerClasses(openNested, open, ClassFile.ACC_PROTECTED | ClassFile.ACC_STATIC)));
        // The same in a final type, which nothing can extend.
        byte[] closedType = ClassFile.of().build(closed, builder -> builder
                .withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
                .with(innerClasses(closedNested, closed, ClassFile.ACC_PROTECTED | ClassFile.ACC_STATIC)));
        byte[] closedNestedType = ClassFile.of().build(closedNested, builder -> builder
                .withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
                .with(innerClasses(closedNested, closed, ClassFile.ACC_PROTECTED | ClassFile.ACC_STATIC)));

        assertEquals(List.of("io.example.Closed", "io.example.Open", "io.example.Open$Nested"), publicApi(Map.of(
                "io.example.Hidden", hiddenType,
                "io.example.Hidden$Nested", hiddenNestedType,
                "io.example.Open", openType,
                "io.example.Open$Nested", openNestedType,
                "io.example.Closed", closedType,
                "io.example.Closed$Nested", closedNestedType)));
    }

    @Test
    void leavesOutWhatMicronautBuildLowersToAWarningAndCountsInternalPerClass() {
        ClassDesc outer = ClassDesc.of("io.example.Outer");
        ClassDesc nested = ClassDesc.of("io.example.Outer$Nested");
        byte[] outerType = ClassFile.of().build(outer, builder -> builder
                .withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
                .with(internal())
                .with(innerClasses(nested, outer, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC))
                .withMethodBody("run", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, code -> code
                        .return_()));
        byte[] nestedType = ClassFile.of().build(nested, builder -> builder
                .withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
                .with(innerClasses(nested, outer, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)));
        byte[] internalMember = ClassFile.of().build(ClassDesc.of("io.example.Member"), builder -> builder
                .withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
                .withMethod("register", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, method -> method
                                .with(internal())
                                .withCode(code -> code.return_())));
        byte[] internalPackage = ClassFile.of().build(ClassDesc.of("io.micronaut.example.internal.Tool"),
                builder -> builder.withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL));

        assertEquals(List.of("io.example.Member", "io.example.Outer$Nested"), publicApi(Map.of(
                "io.example.Outer", outerType,
                "io.example.Outer$Nested", nestedType,
                "io.example.Member", internalMember,
                "io.micronaut.example.internal.Tool", internalPackage)));
    }

    @Test
    void findsLauncherTypesInDescriptorsGenericSignaturesThrowsClausesAndSupertypes() {
        ClassDesc index = ClassDesc.of("io.example.launcher.Index");
        ClassDesc failure = ClassDesc.of("io.example.launcher.Failure");
        byte[] type = ClassFile.of().build(ClassDesc.of("io.example.Api"), builder -> builder
                .withFlags(AccessFlag.PUBLIC, AccessFlag.FINAL)
                .with(internal())
                .withMethodBody("index", MethodTypeDesc.of(index), ClassFile.ACC_PUBLIC, code -> code
                        .aconst_null().areturn())
                .withField("indexes", ConstantDescs.CD_List, field -> field
                        .withFlags(ClassFile.ACC_PUBLIC)
                        .with(SignatureAttribute.of(Signature.parseFrom(
                                "Ljava/util/List<Lio/example/launcher/Index;>;"))))
                .withMethod("read", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, method -> method
                        .with(internal())
                        .with(ExceptionsAttribute.ofSymbols(failure))
                        .withCode(code -> code.return_()))
                .withMethodBody("hidden", MethodTypeDesc.of(index), ClassFile.ACC_PRIVATE, code -> code
                        .aconst_null().areturn()));
        byte[] subtype = ClassFile.of().build(ClassDesc.of("io.example.Sub"), builder -> builder
                .withFlags(AccessFlag.PUBLIC)
                .withSuperclass(ClassDesc.of("io.example.launcher.Base")));
        byte[] packagePrivate = ClassFile.of().build(ClassDesc.of("io.example.Reader"), builder -> builder
                .withFlags(ClassFile.ACC_SUPER)
                .withMethodBody("index", MethodTypeDesc.of(index), ClassFile.ACC_PUBLIC, code -> code
                        .aconst_null().areturn()));

        assertEquals(List.of(
                        "io.example.Api#index()Lio/example/launcher/Index; names io.example.launcher.Index",
                        "io.example.Api#indexes:Ljava/util/List; names io.example.launcher.Index",
                        "io.example.Api#read()V names io.example.launcher.Failure",
                        "io.example.Sub names io.example.launcher.Base"),
                foreignTypesInSignatures(Map.of("io.example.Api", type, "io.example.Sub", subtype,
                        "io.example.Reader", packagePrivate), Set.of("io/example/launcher")::contains));
    }

    /**
     * Lists what japicmp would report as an error when it changes, as {@code type} and {@code type#member}
     * lines with the member's descriptor, sorted.
     *
     * @param classFiles the class files, by binary name
     * @return the lines
     */
    static List<String> publicApi(Map<String, byte[]> classFiles) {
        Map<String, ClassModel> models = parse(classFiles);
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
     * Lists every type of another library that a public or protected signature names, {@code @Internal} types and
     * members included: the supertypes and generic signature of an exported type, and the descriptor, generic
     * signature and {@code throws} clause of each of its visible members.
     *
     * @param classFiles     the class files, by binary name
     * @param foreignPackage whether a package, as an internal name, is the other library's
     * @return one {@code <member> names <type>} line per such type a signature names, sorted
     */
    static List<String> foreignTypesInSignatures(Map<String, byte[]> classFiles, Predicate<String> foreignPackage) {
        Map<String, ClassModel> models = parse(classFiles);
        TreeSet<String> offenders = new TreeSet<>();
        for (ClassModel model : models.values()) {
            if (!exported(model, models)) {
                continue;
            }
            String name = binaryName(model.thisClass());
            List<String> typeSignature = new ArrayList<>();
            model.superclass().ifPresent(superclass -> typeSignature.add("L" + superclass.asInternalName() + ";"));
            model.interfaces().forEach(type -> typeSignature.add("L" + type.asInternalName() + ";"));
            model.findAttribute(Attributes.signature()).ifPresent(signature -> typeSignature.add(signature
                    .signature().stringValue()));
            report(name, typeSignature, foreignPackage, offenders);
            boolean open = !model.flags().has(AccessFlag.FINAL);
            for (FieldModel field : model.fields()) {
                if (visible(field.flags(), open)) {
                    List<String> signature = new ArrayList<>(List.of(field.fieldType().stringValue()));
                    field.findAttribute(Attributes.signature()).ifPresent(generic -> signature.add(generic
                            .signature().stringValue()));
                    report(name + "#" + field.fieldName().stringValue() + ":" + field.fieldType().stringValue(),
                            signature, foreignPackage, offenders);
                }
            }
            for (MethodModel method : model.methods()) {
                if (visible(method.flags(), open) && !method.flags().has(AccessFlag.BRIDGE)) {
                    List<String> signature = new ArrayList<>(List.of(method.methodType().stringValue()));
                    method.findAttribute(Attributes.signature()).ifPresent(generic -> signature.add(generic
                            .signature().stringValue()));
                    method.findAttribute(Attributes.exceptions()).ifPresent(exceptions -> exceptions.exceptions()
                            .forEach(type -> signature.add("L" + type.asInternalName() + ";")));
                    report(name + "#" + method.methodName().stringValue() + method.methodType().stringValue(),
                            signature, foreignPackage, offenders);
                }
            }
        }
        return new ArrayList<>(offenders);
    }

    private static void report(String member, List<String> signatures, Predicate<String> foreignPackage,
                               Set<String> offenders) {
        for (String signature : signatures) {
            Matcher type = CLASS_TYPE.matcher(signature);
            while (type.find()) {
                String internalName = type.group(1);
                int slash = internalName.lastIndexOf('/');
                if (slash > 0 && foreignPackage.test(internalName.substring(0, slash))) {
                    offenders.add(member + " names " + internalName.replace('/', '.'));
                }
            }
        }
    }

    private static Map<String, ClassModel> parse(Map<String, byte[]> classFiles) {
        Map<String, ClassModel> models = new TreeMap<>();
        for (Map.Entry<String, byte[]> file : classFiles.entrySet()) {
            models.put(file.getKey(), ClassFile.of().parse(file.getValue()));
        }
        return models;
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

    private static Set<String> packages(Set<String> classNames) {
        Set<String> packages = new TreeSet<>();
        for (String className : classNames) {
            int dot = className.lastIndexOf('.');
            if (dot > 0) {
                packages.add(className.substring(0, dot).replace('.', '/'));
            }
        }
        return packages;
    }

    /**
     * The main class files of the module a class belongs to, read from where this test's class path loads it
     * from: a compiled classes directory in this build, or a jar. {@code AotCacheBoundaryTest} reads them too.
     */
    static Map<String, byte[]> classFiles(Class<?> member) throws IOException {
        Path location;
        try {
            location = Path.of(member.getProtectionDomain().getCodeSource().getLocation().toURI());
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
                    if (entry.getName().endsWith(".class") && !entry.getName().endsWith("module-info.class")
                            && !entry.getName().startsWith("META-INF/")) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            classes.put(className(entry.getName()), in.readAllBytes());
                        }
                    }
                }
            }
        }
        assertTrue(classes.containsKey(member.getName()), "no classes of " + member + " at " + location);
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
