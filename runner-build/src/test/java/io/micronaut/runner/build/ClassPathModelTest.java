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

import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver.ClassHierarchyInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClassPathModel}: which copy of a class wins on JDK 25, which names a newer runtime would resolve
 * differently, the class hierarchy it answers for verification and the member access it answers for a step
 * that rewrites calls. The classes are generated with the ClassFile API, each copy with its own superclass so
 * that the winning copy is visible in the hierarchy.
 */
class ClassPathModelTest {

    private static final String SHARED = "com/example/Shared";

    @Test
    void theApplicationLayerWinsOverADependencyAndAnEarlierDependencyOverALaterOne() {
        ClassPathModel model = model(false,
                layer(0, "application", false, Map.of(entry(SHARED), type(SHARED, "com/example/FromApplication"))),
                layer(1, "first", false, Map.of(
                        entry(SHARED), type(SHARED, "com/example/FromFirst"),
                        entry("com/example/Dep"), type("com/example/Dep", "com/example/FromFirst"))),
                layer(2, "second", false, Map.of(
                        entry("com/example/Dep"), type("com/example/Dep", "com/example/FromSecond"),
                        entry("com/example/Only"), type("com/example/Only", "com/example/FromSecond"))));

        assertEquals(0, model.winner(SHARED).orElseThrow().layer());
        assertEquals("com/example/FromApplication", model.winner(SHARED).orElseThrow().superName());
        assertEquals(1, model.winner("com/example/Dep").orElseThrow().layer());
        assertEquals("com/example/FromFirst", model.winner("com/example/Dep").orElseThrow().superName());
        assertEquals(2, model.winner("com/example/Only").orElseThrow().layer());
        assertTrue(model.winner("com/example/Missing").isEmpty());
        assertFalse(model.uncertain(SHARED));
        assertEquals("second", model.layerName(2));
    }

    @Test
    void theJdk25WinnerOfAMultiReleaseJarIsTheHighestVariantItAcceptsAndANewerOneMakesItUncertain() {
        Map<String, byte[]> entries = Map.of(
                entry(SHARED), type(SHARED, "com/example/Base"),
                "META-INF/versions/11/" + entry(SHARED), type(SHARED, "com/example/Eleven"),
                "META-INF/versions/27/" + entry(SHARED), type(SHARED, "com/example/TwentySeven"),
                entry("com/example/Plain"), type("com/example/Plain", "com/example/Base"));
        ClassPathModel model = model(false, layer(0, "application", false, Map.of()),
                layer(1, "multi-release", true, entries));

        ClassPathModel.Copy winner = model.winner(SHARED).orElseThrow();
        assertEquals(11, winner.version());
        assertEquals("com/example/Eleven", winner.superName());
        assertTrue(model.uncertain(SHARED), "JDK 27 would load the versions/27 copy");
        assertFalse(model.uncertain("com/example/Plain"));
    }

    @Test
    void versionedEntriesOfAJarThatIsNotMultiReleaseAndAVersionWithALeadingZeroAreIgnored() {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put(entry(SHARED), type(SHARED, "com/example/Base"));
        entries.put("META-INF/versions/11/" + entry(SHARED), type(SHARED, "com/example/Eleven"));
        entries.put("META-INF/versions/27/" + entry(SHARED), type(SHARED, "com/example/TwentySeven"));
        ClassPathModel plain = model(false, layer(0, "application", false, Map.of()),
                layer(1, "plain", false, entries));
        assertEquals(0, plain.winner(SHARED).orElseThrow().version());
        assertEquals("com/example/Base", plain.winner(SHARED).orElseThrow().superName());
        assertFalse(plain.uncertain(SHARED));

        ClassPathModel leadingZero = model(false, layer(0, "application", false, Map.of()),
                layer(1, "multi-release", true, Map.of(
                        entry(SHARED), type(SHARED, "com/example/Base"),
                        "META-INF/versions/09/" + entry(SHARED), type(SHARED, "com/example/Nine"))));
        assertEquals("com/example/Base", leadingZero.winner(SHARED).orElseThrow().superName());
        assertEquals(1, leadingZero.size(), "META-INF/versions/09/ is an ordinary entry, not a class");
    }

    @Test
    void theHierarchyAnswersForModelClassesAndForJdkClasses() {
        ClassPathModel model = model(false,
                layer(0, "application", false, Map.of(
                        entry("com/example/Api"), iface("com/example/Api"),
                        entry("com/example/Impl"), type("com/example/Impl", "java/util/AbstractList")))
        );

        ClassHierarchyInfo api = model.getClassInfo(ClassDesc.ofInternalName("com/example/Api"));
        ClassHierarchyInfo impl = model.getClassInfo(ClassDesc.ofInternalName("com/example/Impl"));
        ClassHierarchyInfo list = model.getClassInfo(ClassDesc.of("java.util.List"));
        ClassHierarchyInfo abstractList = model.getClassInfo(ClassDesc.of("java.util.AbstractList"));

        assertEquals(ClassHierarchyInfo.ofInterface(), api);
        assertEquals(ClassHierarchyInfo.ofClass(ClassDesc.of("java.util.AbstractList")), impl);
        assertEquals(ClassHierarchyInfo.ofInterface(), list);
        assertEquals(ClassHierarchyInfo.ofClass(ClassDesc.of("java.util.AbstractCollection")), abstractList);
        assertNull(model.getClassInfo(ClassDesc.of("com.example.Nowhere")),
                "neither on the class path nor in the JDK");
        assertNull(model.getClassInfo(ClassDesc.of("org.junit.jupiter.api.Test")),
                "the build tool's own class path is not the runtime's");
    }

    @Test
    void memberAccessFollowsTheRulesOfTheWinningCopy() {
        byte[] owner = ClassFile.of().build(ClassDesc.ofInternalName("com/example/Owner"), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withField("publicField", ConstantDescs.CD_int, ClassFile.ACC_PUBLIC)
                .withField("protectedField", ConstantDescs.CD_int, ClassFile.ACC_PROTECTED)
                .withField("packageField", ConstantDescs.CD_int, 0)
                .withField("privateField", ConstantDescs.CD_int, ClassFile.ACC_PRIVATE)
                .withMethod("publicMethod", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC
                        | ClassFile.ACC_ABSTRACT, method -> { })
                .withMethod("protectedMethod", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PROTECTED
                        | ClassFile.ACC_ABSTRACT, method -> { })
                .withMethod("packageMethod", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_ABSTRACT,
                        method -> { })
                .withMethod("privateMethod", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PRIVATE
                        | ClassFile.ACC_ABSTRACT, method -> { }));
        byte[] hidden = ClassFile.of().build(ClassDesc.ofInternalName("com/example/Hidden"), builder -> builder
                .withFlags(ClassFile.ACC_SUPER)
                .withField("publicField", ConstantDescs.CD_int, ClassFile.ACC_PUBLIC));
        ClassPathModel model = model(true, layer(0, "application", false, Map.of(
                entry("com/example/Owner"), owner, entry("com/example/Hidden"), hidden)));

        for (String kind : List.of("Field", "Method")) {
            String descriptor = kind.equals("Field") ? "I" : "()V";
            assertTrue(model.isAccessible("com/example/Owner", "public" + kind, descriptor, "com/example"));
            assertTrue(model.isAccessible("com/example/Owner", "public" + kind, descriptor, "org/other"));
            assertTrue(model.isAccessible("com/example/Owner", "protected" + kind, descriptor, "com/example"));
            assertFalse(model.isAccessible("com/example/Owner", "protected" + kind, descriptor, "org/other"),
                    "a protected member of another package needs a subclass the model is not told about");
            assertTrue(model.isAccessible("com/example/Owner", "package" + kind, descriptor, "com/example"));
            assertFalse(model.isAccessible("com/example/Owner", "package" + kind, descriptor, "org/other"));
            assertFalse(model.isAccessible("com/example/Owner", "private" + kind, descriptor, "com/example"));
            assertFalse(model.isAccessible("com/example/Owner", "private" + kind, descriptor, "org/other"));
        }
        assertFalse(model.isAccessible("com/example/Owner", "publicField", "J", "com/example"),
                "a member is identified by its descriptor as well as its name");
        assertTrue(model.isAccessible("com/example/Hidden", "publicField", "I", "com/example"));
        assertFalse(model.isAccessible("com/example/Hidden", "publicField", "I", "org/other"),
                "the public member of a package-private class");

        ClassPathModel withoutMembers = model(false, layer(0, "application", false,
                Map.of(entry("com/example/Owner"), owner)));
        assertThrows(IllegalStateException.class,
                () -> withoutMembers.isAccessible("com/example/Owner", "publicField", "I", "com/example"));
    }

    @Test
    void theFirstWatchedClassIsReportedAndAClassWhoseNameDoesNotMatchItsEntryIsLeftOut() {
        Predicate<String> watch = name -> name.startsWith("com/watched/");
        ClassPathModel model = model(false, watch,
                layer(0, "application", false, Map.of(entry("com/example/Wrong"), type("com/example/Right",
                        "java/lang/Object"))),
                layer(1, "first", false, Map.of("com/watched/First.class", new byte[0])),
                layer(2, "second", false, Map.of("com/watched/Second.class", new byte[0])));

        assertEquals(new ClassPathModel.Watched("first", "com/watched/First.class"), model.watched().orElseThrow());
        assertTrue(model.winner("com/example/Wrong").isEmpty());
        assertTrue(model.winner("com/example/Right").isEmpty());
    }

    @Test
    void aClassWhoseSuperclassOrInterfaceCannotBeReadIsLeftOutWithoutFailingTheScan() {
        byte[] implementation = implementation("com/example/Impl");
        assertThrows(IllegalArgumentException.class, () -> ClassFile.of().parse(
                ClassFixtures.withCorruptSuperclass(implementation)).superclass(), "the fixture is corrupt");
        assertThrows(IllegalArgumentException.class, () -> ClassFile.of().parse(
                ClassFixtures.withCorruptInterface(implementation)).interfaces(), "the fixture is corrupt");

        for (boolean members : List.of(false, true)) {
            Map<String, byte[]> entries = Map.of(
                    entry("com/example/Impl"), implementation,
                    entry("com/example/BadSuper"), ClassFixtures.withCorruptSuperclass(
                            implementation("com/example/BadSuper")),
                    entry("com/example/BadIface"), ClassFixtures.withCorruptInterface(
                            implementation("com/example/BadIface")));
            ClassPathModel model = model(members, layer(0, "application", false, Map.of()),
                    layer(1, "dependency", false, entries));

            assertTrue(model.winner("com/example/BadSuper").isEmpty(), "members: " + members);
            assertTrue(model.winner("com/example/BadIface").isEmpty(), "members: " + members);
            assertEquals(List.of("java/lang/Runnable"), model.winner("com/example/Impl").orElseThrow().interfaces(),
                    "the class next to them is recorded");
            assertEquals(1, model.size());
        }
    }

    @Test
    void noTruncationOrByteFlipOfAClassMakesTheScanThrow() {
        byte[] implementation = implementation("com/example/Impl");
        List<byte[]> damaged = new ArrayList<>();
        for (int length = 0; length < implementation.length; length++) {
            damaged.add(java.util.Arrays.copyOf(implementation, length));
        }
        for (int position = 0; position < implementation.length; position++) {
            for (int mask : new int[] {0x01, 0x80, 0xFF}) {
                byte[] flipped = implementation.clone();
                flipped[position] ^= (byte) mask;
                damaged.add(flipped);
            }
        }

        for (boolean members : List.of(false, true)) {
            ClassPathModel.LayerScan scan = ClassPathModel.scan(1, "dependency", false, members, name -> false,
                    new ClassPathModel.Interner());
            for (byte[] bytes : damaged) {
                scan.accept(entry("com/example/Impl"), bytes);
            }
            scan.accept(entry("com/example/Impl"), implementation);
            ClassPathModel model = ClassPathModel.merge(List.of(scan), members);
            assertEquals(1, model.size(), "members: " + members);
        }
    }

    /**
     * The ClassFile API parses code only when asked. A class whose static initializer or constructor code it
     * cannot parse is still a class the runtime may find, so it stays in the model with its interfaces and member
     * tables; only what {@link InterfaceInitializers} would prove of that code is unknown: the class is not inert,
     * and the interface's initializer is not quiet.
     */
    @Test
    void aClassWhoseInitializerOrConstructorCodeCannotBeParsedStaysInTheModel() {
        byte[] broken = ClassFixtures.withCorruptCode(initializedInterface("com/example/Broken"),
                ConstantDescs.CLASS_INIT_NAME);
        byte[] fragile = ClassFixtures.withCorruptCode(inertClass("com/example/Fragile"), ConstantDescs.INIT_NAME);
        for (byte[] bytes : List.of(broken, fragile)) {
            assertThrows(IllegalArgumentException.class, () -> ClassFile.of().parse(bytes).methods()
                    .forEach(method -> method.code().orElseThrow().forEach(element -> { })), "the fixture is corrupt");
        }
        Map<String, byte[]> entries = Map.of(
                entry("com/example/Broken"), broken,
                entry("com/example/Fragile"), fragile,
                entry("com/example/Sound"), initializedInterface("com/example/Sound"),
                entry("com/example/Plain"), inertClass("com/example/Plain"));

        for (boolean members : List.of(false, true)) {
            ClassPathModel model = model(members, layer(0, "application", false, Map.of()),
                    layer(1, "dependency", false, entries));

            assertEquals(4, model.size(), "members: " + members);
            assertEquals(List.of("java/util/function/Supplier"),
                    model.winner("com/example/Broken").orElseThrow().interfaces(), "members: " + members);
            assertEquals(List.of("java/lang/Runnable"), model.winner("com/example/Fragile").orElseThrow().interfaces(),
                    "members: " + members);
        }
        ClassPathModel model = model(true, layer(0, "application", false, Map.of()),
                layer(1, "dependency", false, entries));
        ClassPathModel.Copy interfaceCopy = model.winner("com/example/Broken").orElseThrow();
        assertEquals(ClassFile.ACC_STATIC, interfaceCopy.member(ConstantDescs.CLASS_INIT_NAME, "()V").flags());
        assertTrue(interfaceCopy.declaresConcreteInstanceMethod(), "its default method is recorded");
        assertNull(interfaceCopy.initializer(), "an initializer that cannot be read is not quiet");
        ClassPathModel.Copy classCopy = model.winner("com/example/Fragile").orElseThrow();
        assertEquals(ClassFile.ACC_PUBLIC, classCopy.member(ConstantDescs.INIT_NAME, "()V").flags());
        assertFalse(classCopy.inert(), "a constructor that cannot be read is not inert");
        assertEquals(new InterfaceInitializers.Initializer(List.of(), List.of()),
                model.winner("com/example/Sound").orElseThrow().initializer(), "the same initializer, readable");
        assertTrue(model.winner("com/example/Plain").orElseThrow().inert(), "the same constructor, readable");
    }

    @Test
    void aNameIsKnownWhicheverCopyWinsAndTheNestHostIsRecordedWithTheMembers(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path temp) throws Exception {
        Map<String, byte[]> compiled = ClassFixtures.classes(ClassFixtures.compile(temp.resolve("src"),
                temp.resolve("classes"), List.of("--release", "25"), ClassFixtures.source("com.example.Outer", """
                        package com.example;
                        public class Outer {
                            public static class Inner {
                                private void hidden() {
                                }
                            }
                        }
                        """)));
        Map<String, byte[]> versionedOnly = Map.of(
                "META-INF/versions/27/" + entry("com/example/Future"), type("com/example/Future", "java/lang/Object"));

        ClassPathModel with = model(true, layer(0, "application", false, compiled),
                layer(1, "multi-release", true, versionedOnly));
        ClassPathModel without = model(false, layer(0, "application", false, compiled));

        assertTrue(with.hasMembers());
        assertFalse(without.hasMembers());
        assertEquals("com/example/Outer", with.winner("com/example/Outer$Inner").orElseThrow().nestHost());
        assertEquals(null, with.winner("com/example/Outer").orElseThrow().nestHost(), "a nest host names none");
        assertEquals(null, without.winner("com/example/Outer$Inner").orElseThrow().nestHost(),
                "the nest host is recorded with the member tables");
        ClassPathModel.Member hidden = with.winner("com/example/Outer$Inner").orElseThrow().member("hidden", "()V");
        assertEquals(java.lang.classfile.ClassFile.ACC_PRIVATE, hidden.flags());
        assertEquals(null, with.winner("com/example/Outer$Inner").orElseThrow().member("hidden", "(I)V"));
        assertEquals(null, without.winner("com/example/Outer$Inner").orElseThrow().member("hidden", "()V"));
        assertTrue(with.known("com/example/Outer"));
        assertTrue(with.known("com/example/Future"), "a name only a newer runtime loads is still taken");
        assertTrue(with.winner("com/example/Future").isEmpty());
        assertFalse(with.known("com/example/Missing"));
    }

    @Test
    void aClassAboveTheSizeLimitIsNotRead() {
        ClassPathModel.LayerScan scan = ClassPathModel.scan(1, "dependency", false, false, name -> false,
                new ClassPathModel.Interner());

        assertTrue(scan.wants(entry("com/example/Large"), ClassPathModel.MAX_CLASS_SIZE));
        assertFalse(scan.wants(entry("com/example/Large"), ClassPathModel.MAX_CLASS_SIZE + 1L));
    }

    private static ClassPathModel model(boolean members, Layer... layers) {
        return model(members, name -> false, layers);
    }

    private static ClassPathModel model(boolean members, Predicate<String> watch, Layer... layers) {
        ClassPathModel.Interner strings = new ClassPathModel.Interner();
        List<ClassPathModel.LayerScan> scans = new ArrayList<>();
        for (Layer layer : layers) {
            ClassPathModel.LayerScan scan = ClassPathModel.scan(layer.position, layer.name, layer.multiRelease,
                    members, watch, strings);
            for (Map.Entry<String, byte[]> entry : new java.util.TreeMap<>(layer.entries).entrySet()) {
                if (scan.wants(entry.getKey(), entry.getValue().length)) {
                    scan.accept(entry.getKey(), entry.getValue());
                }
            }
            scans.add(scan);
        }
        return ClassPathModel.merge(scans, members);
    }

    private static Layer layer(int position, String name, boolean multiRelease, Map<String, byte[]> entries) {
        return new Layer(position, name, multiRelease, entries);
    }

    private static String entry(String internalName) {
        return internalName + ".class";
    }

    private static byte[] type(String internalName, String superName) {
        return ClassFile.of().build(ClassDesc.ofInternalName(internalName), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withSuperclass(ClassDesc.ofInternalName(superName)));
    }

    /** A class with a superclass, an interface, a field and a method, so that every part the scan reads exists. */
    private static byte[] implementation(String internalName) {
        return ClassFile.of().build(ClassDesc.ofInternalName(internalName), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withSuperclass(ClassDesc.of("java.util.AbstractList"))
                .withInterfaceSymbols(ClassDesc.of("java.lang.Runnable"))
                .withField("count", ConstantDescs.CD_int, ClassFile.ACC_PRIVATE)
                .withMethod("run", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC
                        | ClassFile.ACC_ABSTRACT, method -> { }));
    }

    private static byte[] iface(String internalName) {
        return ClassFile.of().build(ClassDesc.ofInternalName(internalName), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT));
    }

    /** An interface with a default method and a static initializer that stores a new {@code Object}. */
    private static byte[] initializedInterface(String internalName) {
        ClassDesc self = ClassDesc.ofInternalName(internalName);
        return ClassFile.of().build(self, builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT)
                .withSuperclass(ConstantDescs.CD_Object)
                .withInterfaceSymbols(ClassDesc.of("java.util.function.Supplier"))
                .withField("NONE", ConstantDescs.CD_Object,
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL)
                .withMethod("name", MethodTypeDesc.of(ConstantDescs.CD_String), ClassFile.ACC_PUBLIC,
                        method -> method.withCode(code -> code.ldc("name").areturn()))
                .withMethod(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_STATIC,
                        method -> method.withCode(code -> code.new_(ConstantDescs.CD_Object).dup()
                                .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                        ConstantDescs.MTD_void)
                                .putstatic(self, "NONE", ConstantDescs.CD_Object).return_())));
    }

    /** A class whose constructor only calls {@code Object}'s. */
    private static byte[] inertClass(String internalName) {
        return ClassFile.of().build(ClassDesc.ofInternalName(internalName), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER | ClassFile.ACC_ABSTRACT)
                .withSuperclass(ConstantDescs.CD_Object)
                .withInterfaceSymbols(ClassDesc.of("java.lang.Runnable"))
                .withMethod(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
                        method -> method.withCode(code -> code.aload(0).invokespecial(ConstantDescs.CD_Object,
                                ConstantDescs.INIT_NAME, ConstantDescs.MTD_void).return_())));
    }

    private record Layer(int position, String name, boolean multiRelease, Map<String, byte[]> entries) {
    }
}
