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

import io.micronaut.core.annotation.Internal;
import io.micronaut.runner.build.training.TrainingSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Signature;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the JDK AOT cache engine generic and small now that it shares {@code io.micronaut.runner.build} with the
 * Runner JAR code (#233), which drew no package boundary around it.
 *
 * <ul>
 *     <li>The engine's class files, nested classes included, name no Runner type but the build logger, the
 *     training settings, the training driver and each other: it still knows nothing about the Runner JAR, and
 *     the check sees calls, field types, descriptors, generic signatures and annotations alike.</li>
 *     <li>Of the engine and the types built on it, exactly the types the plugins and the benchmarks call are
 *     public, each with its own {@code @Internal}, which japicmp reads per class from the class files.</li>
 *     <li>Those types expose only the members the plugins and the benchmarks call.</li>
 * </ul>
 */
class AotCacheBoundaryTest {

    /** The engine: what was {@code io.micronaut.runner.build.aotcache}, and the driver it launches through. */
    private static final List<Class<?>> ENGINE = List.of(AotCacheBuilder.class, AotCacheGate.class,
            AotCacheReport.class, AotCacheSettings.class, AotLaunchOptions.class, Forks.class, JdkProbe.class,
            TrainingDriver.class);

    /** The other Runner types the engine may name, each with its nested types. */
    private static final List<Class<?>> ALLOWED = List.of(BuildLogger.class, TrainingSettings.class);

    /** The Runner-specific types built on the engine, which the build plugins call. */
    private static final List<Class<?>> BUILD_TYPES = List.of(AotCacheOutput.class, AotLayout.class, AotTarget.class);

    /** Where the types of runner-build and of the launcher live, as internal names. */
    private static final String RUNNER = "io/micronaut/runner/";

    /** A class type in a descriptor or a generic signature: the internal name after {@code L}. */
    private static final Pattern CLASS_TYPE = Pattern.compile("L(" + RUNNER + "[\\w/$]+)[;<]");

    @Test
    void theEngineNamesNoRunnerTypeButTheLoggerTheTrainingSettingsTheDriverAndItself() throws IOException {
        Map<String, byte[]> classFiles = PublicApiTest.classFiles(AotCacheSettings.class);
        Set<String> checked = new TreeSet<>();
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : classFiles.entrySet()) {
            if (!within(file.getKey(), ENGINE)) {
                continue;
            }
            checked.add(file.getKey());
            for (String type : runnerTypes(file.getValue())) {
                if (!within(type, ENGINE) && !within(type, ALLOWED)) {
                    offenders.add(file.getKey() + " names " + type);
                }
            }
        }
        assertTrue(checked.containsAll(ENGINE.stream().map(Class::getName).toList())
                && checked.contains(AotCacheGate.Coverage.class.getName())
                && checked.contains(TrainingDriver.Outcome.class.getName()), checked::toString);
        assertTrue(offenders.isEmpty(), () -> "the JDK AOT cache engine must know nothing about the Runner JAR: "
                + offenders);
    }

    @Test
    void theCheckSeesACallAFieldTypeAGenericSignatureAndAnAnnotation() {
        ClassDesc spec = ClassDesc.of(RunnerJarSpec.class.getName());
        ClassDesc builder = ClassDesc.of(RunnerJarSpec.Builder.class.getName());
        byte[] call = ClassFile.of().build(ClassDesc.of("io.example.Call"), type -> type
                .withMethodBody("run", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC, code -> code
                        .invokestatic(spec, "builder", MethodTypeDesc.of(builder))
                        .pop()
                        .return_()));
        byte[] field = ClassFile.of().build(ClassDesc.of("io.example.Field"), type -> type
                .withField("spec", spec, ClassFile.ACC_PRIVATE));
        byte[] generic = ClassFile.of().build(ClassDesc.of("io.example.Generic"), type -> type
                .withField("specs", ConstantDescs.CD_List, member -> member
                        .withFlags(ClassFile.ACC_PRIVATE)
                        .with(SignatureAttribute.of(Signature.parseFrom(
                                "Ljava/util/List<Lio/micronaut/runner/build/RunnerJarSpec;>;")))));
        byte[] annotated = ClassFile.of().build(ClassDesc.of("io.example.Annotated"), type -> type
                .with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(
                        ClassDesc.of("io.micronaut.runner.Marker")))));
        byte[] clean = ClassFile.of().build(ClassDesc.of("io.example.Clean"), type -> type
                .withField("log", ClassDesc.of(BuildLogger.class.getName()), ClassFile.ACC_PRIVATE)
                .withField("text", ConstantDescs.CD_String, ClassFile.ACC_PRIVATE));

        assertEquals(Set.of(RunnerJarSpec.class.getName(), RunnerJarSpec.Builder.class.getName()), runnerTypes(call));
        assertEquals(Set.of(RunnerJarSpec.class.getName()), runnerTypes(field));
        assertEquals(Set.of(RunnerJarSpec.class.getName()), runnerTypes(generic));
        assertEquals(Set.of("io.micronaut.runner.Marker"), runnerTypes(annotated));
        assertEquals(Set.of(BuildLogger.class.getName()), runnerTypes(clean));
    }

    @Test
    void exactlyTheTypesThePluginsAndTheBenchmarksCallArePublicEachWithItsOwnInternal()
            throws IOException, ClassNotFoundException {
        Set<String> exported = new TreeSet<>();
        List<String> missing = new ArrayList<>();
        for (String name : PublicApiTest.classFiles(AotCacheSettings.class).keySet()) {
            if (!within(name, ENGINE) && !within(name, BUILD_TYPES)) {
                continue;
            }
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            if (!exported(type)) {
                continue;
            }
            exported.add(name);
            // Its own annotation, as micronaut-build reads it: an @Internal enclosing type does not cover it.
            if (type.getDeclaredAnnotation(Internal.class) == null) {
                missing.add(name);
            }
        }
        assertEquals(new TreeSet<>(Stream.of(AotCacheOutput.class, AotLayout.class, AotLayout.Result.class,
                AotTarget.class, AotCacheReport.class, AotCacheSettings.class, AotCacheSettings.Builder.class,
                JdkProbe.class, AotCacheGate.class, AotCacheGate.Coverage.class).map(Class::getName).toList()),
                exported, "AotCacheBuilder, AotLaunchOptions, Forks and TrainingDriver with its nested types stay"
                        + " package-private; the rest is public for a plugin or a benchmark");
        assertTrue(missing.isEmpty(), () -> "public, and not @Internal: " + missing);
    }

    @Test
    void theInternalTypesOfTheEngineExposeOnlyWhatThePluginsAndTheBenchmarksCall() {
        // The plugins: they build the settings, read the defaults, and log the report's summary.
        assertEquals(Set.of("defaults", "builder", "strict", "jvmArgs", "verifyProbes", "minCoverage"),
                publicMembers(AotCacheSettings.class));
        assertEquals(Set.of("strict", "jvmArgs", "verifyProbes", "minCoverage", "build"),
                publicMembers(AotCacheSettings.Builder.class));
        assertEquals(Set.of("summary"), publicMembers(AotCacheReport.class));
        // The benchmarks: they probe a JDK for its creation flags and count what a cached launch took from the cache.
        assertEquals(Set.of("probe", "creationFlags"), publicMembers(JdkProbe.class));
        assertEquals(Set.of("coverage"), publicMembers(AotCacheGate.class));
        assertEquals(Set.of("summary"), publicMembers(AotCacheGate.Coverage.class));
    }

    /**
     * Every runner-build or launcher type a class file names, as binary names: its constant pool holds each class
     * it uses as a class entry, and every descriptor, generic signature and annotation type as text.
     */
    private static Set<String> runnerTypes(byte[] classFile) {
        ClassModel model = ClassFile.of().parse(classFile);
        Set<String> types = new TreeSet<>();
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof ClassEntry type) {
                String name = type.asInternalName();
                if (name.startsWith(RUNNER)) {
                    types.add(name.replace('/', '.'));
                } else {
                    collect(name, types);
                }
            } else if (entry instanceof Utf8Entry text) {
                collect(text.stringValue(), types);
            }
        }
        types.remove(model.thisClass().asInternalName().replace('/', '.'));
        return types;
    }

    private static void collect(String text, Set<String> types) {
        Matcher type = CLASS_TYPE.matcher(text);
        while (type.find()) {
            types.add(type.group(1).replace('/', '.'));
        }
    }

    /** Whether a binary name is one of the types, or nested in one. */
    private static boolean within(String name, List<Class<?>> types) {
        return types.stream().anyMatch(type -> name.equals(type.getName()) || name.startsWith(type.getName() + "$"));
    }

    /** Whether a type is public, and nested only in public types. */
    private static boolean exported(Class<?> type) {
        if (type.isAnonymousClass() || type.isLocalClass() || type.isSynthetic()) {
            return false;
        }
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (!Modifier.isPublic(current.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    /** The names of a type's own public fields, methods and constructors, leaving out what the compiler adds. */
    private static Set<String> publicMembers(Class<?> type) {
        Set<String> names = new TreeSet<>();
        Stream.of(type.getDeclaredFields(), type.getDeclaredMethods(), type.getDeclaredConstructors())
                .flatMap(Stream::of)
                .map(Member.class::cast)
                .filter(member -> Modifier.isPublic(member.getModifiers()) && !member.isSynthetic())
                .forEach(member -> names.add(member.getName().equals(type.getName()) ? "<init>" : member.getName()));
        return names;
    }
}
