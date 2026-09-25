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

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * A read-only, header-only model of every class on the runtime class path of a runner jar: the application
 * layer, then each dependency in class-path order.
 *
 * <p>For every binary name it records the copy that wins, as {@code Index.resolve} picks it on JDK
 * {@value #RUNTIME_FEATURE}: the application layer before any dependency, an earlier dependency before a later
 * one, and within one layer the {@code META-INF/versions/N/} variants in descending version before the base
 * entry. A variant counts only in a jar whose manifest says {@code Multi-Release: true}, or in the application
 * layer when it is declared multi-release, and only under the rules {@code IndexWriter.versionOf} and
 * {@code pathOf} apply to the index. For the winning copy it records the access flags, the superclass and the
 * interfaces, and, when a step asks for them, the name, descriptor and flags of every field and method.</p>
 *
 * <p>A name whose chain holds a variant for a version above {@value #RUNTIME_FEATURE}, ahead of its winner, is
 * {@linkplain #uncertain(String) uncertain}: a newer runtime would load another copy.</p>
 *
 * <p>The model is a {@link ClassHierarchyResolver} for the classes it holds and falls back to the JDK's own
 * classes only, parsed from the platform class loader. It deliberately does not use
 * {@link ClassHierarchyResolver#defaultResolver()}, which also sees the class path of the build tool running
 * the packager, such as a Gradle daemon's, and the runtime never does.</p>
 *
 * <p>It is built by one {@link LayerScan} per layer, which may run on any thread, merged on the calling thread
 * in class-path order, and is immutable afterwards: every stage task reads it at once, and the build discards
 * it when it returns.</p>
 */
final class ClassPathModel implements ClassHierarchyResolver {

    /** The runtime feature version the winning copies are chosen for, the lowest Runner supports. */
    static final int RUNTIME_FEATURE = 25;

    /** The largest class the scan parses; a larger one is left out of the model. */
    static final int MAX_CLASS_SIZE = ClassTransformPipeline.MAX_CLASS_SIZE;

    private static final String VERSIONS_PREFIX = "META-INF/versions/";

    private static final String META_INF = "META-INF/";

    private static final String CLASS_SUFFIX = ".class";

    /**
     * The JDK's own classes only, parsed from the platform class loader. The cache lives as long as the JVM that
     * runs the packager, because its JDK does not change between two builds, and a Gradle daemon runs many; every
     * stage of every build resolves the same few JDK classes.
     */
    private static final ClassHierarchyResolver JDK =
            ClassHierarchyResolver.ofResourceParsing(ClassLoader.getPlatformClassLoader()).cached(ConcurrentHashMap::new);

    private final Map<String, Resolution> classes;

    private final List<String> layers;

    private final Watched watched;

    private final boolean members;

    private ClassPathModel(Map<String, Resolution> classes, List<String> layers, Watched watched, boolean members) {
        this.classes = classes;
        this.layers = layers;
        this.watched = watched;
        this.members = members;
    }

    /**
     * Starts the scan of one layer.
     *
     * @param layer        the layer's position: {@code 0} for the application layer, then one per dependency
     * @param name         what messages call the layer
     * @param multiRelease whether the layer's versioned directories count
     * @param members      whether to record member tables
     * @param watch        the class entries worth reporting, such as a library that reads what a step drops
     * @param strings      the interner every scan of one build shares
     * @return the scan
     */
    static LayerScan scan(int layer, String name, boolean multiRelease, boolean members, Predicate<String> watch,
                          Interner strings) {
        return new LayerScan(layer, name, multiRelease, members, watch, strings);
    }

    /**
     * Merges the scans of every layer, in class-path order.
     *
     * @param scans   one finished scan per layer, the application layer first
     * @param members whether the scans recorded member tables
     * @return the model
     */
    static ClassPathModel merge(List<LayerScan> scans, boolean members) {
        Map<String, Resolution> classes = new HashMap<>();
        List<String> layers = new ArrayList<>(scans.size());
        Watched watched = null;
        for (LayerScan scan : scans) {
            layers.add(scan.name);
            if (watched == null && scan.watched != null) {
                watched = new Watched(scan.name, scan.watched);
            }
            // Within one layer, variants in descending version before the base entry; the sort is stable, so
            // a name a jar carries twice resolves to its first copy, as the index does.
            Map<String, List<Copy>> byName = new LinkedHashMap<>();
            for (Copy copy : scan.copies) {
                byName.computeIfAbsent(copy.name, key -> new ArrayList<>(1)).add(copy);
            }
            for (Map.Entry<String, List<Copy>> chain : byName.entrySet()) {
                Resolution resolution = classes.computeIfAbsent(chain.getKey(), key -> new Resolution());
                if (resolution.winner != null) {
                    continue;
                }
                List<Copy> copies = chain.getValue();
                copies.sort(Comparator.comparingInt((Copy copy) -> copy.version == 0 ? 0 : -copy.version));
                for (Copy copy : copies) {
                    if (copy.version > RUNTIME_FEATURE) {
                        resolution.uncertain = true;
                    } else {
                        resolution.winner = copy;
                        break;
                    }
                }
            }
        }
        return new ClassPathModel(classes, List.copyOf(layers), watched, members);
    }

    /**
     * The copy of a class that wins on JDK {@value #RUNTIME_FEATURE}.
     *
     * @param internalName the class, such as {@code com/example/Foo}
     * @return the winning copy, or empty when no layer holds one that JDK {@value #RUNTIME_FEATURE} loads
     */
    Optional<Copy> winner(String internalName) {
        Resolution resolution = classes.get(internalName);
        return resolution == null ? Optional.empty() : Optional.ofNullable(resolution.winner);
    }

    /**
     * Whether a newer runtime would load another copy of a class than JDK {@value #RUNTIME_FEATURE} does: its
     * chain holds a variant for a version above {@value #RUNTIME_FEATURE}, ahead of its winner.
     *
     * @param internalName the class
     * @return whether the winner depends on the runtime version
     */
    boolean uncertain(String internalName) {
        Resolution resolution = classes.get(internalName);
        return resolution != null && resolution.uncertain;
    }

    /**
     * The name of a layer, as the scan was given it.
     *
     * @param layer the layer's position
     * @return its name
     */
    String layerName(int layer) {
        return layers.get(layer);
    }

    /**
     * The first watched class the scans found, in class-path order.
     *
     * @return the layer and entry of the class, or empty when no layer holds one
     */
    Optional<Watched> watched() {
        return Optional.ofNullable(watched);
    }

    /**
     * The number of distinct class names the model holds a winner for.
     *
     * @return the class count
     */
    int size() {
        int size = 0;
        for (Resolution resolution : classes.values()) {
            if (resolution.winner != null) {
                size++;
            }
        }
        return size;
    }

    /**
     * Whether code in a package can access a member of a class, by the rules the JVM applies to the winning
     * copies: the class must be public or in the same package, and the member public, or not private and in
     * the same package. A protected member of a class in another package is reported as inaccessible, because
     * that depends on the accessing class, which the model is not told.
     *
     * @param owner       the class that declares the member, such as {@code com/example/Foo}
     * @param name        the member's name
     * @param descriptor  the member's descriptor
     * @param fromPackage the accessing package, such as {@code com/example}, or the empty string for the
     *                    unnamed package
     * @return whether the access is legal; {@code false} when the model has no such class or member
     * @throws IllegalStateException if the model was built without member tables
     */
    boolean isAccessible(String owner, String name, String descriptor, String fromPackage) {
        if (!members) {
            throw new IllegalStateException("The class path model was built without member tables");
        }
        Optional<Copy> copy = winner(owner);
        if (copy.isEmpty()) {
            return false;
        }
        Member member = copy.get().member(name, descriptor);
        if (member == null) {
            return false;
        }
        boolean samePackage = packageOf(owner).equals(fromPackage);
        if ((copy.get().flags & ClassFile.ACC_PUBLIC) == 0 && !samePackage) {
            return false;
        }
        if ((member.flags & ClassFile.ACC_PUBLIC) != 0) {
            return true;
        }
        return (member.flags & ClassFile.ACC_PRIVATE) == 0 && samePackage;
    }

    @Override
    public ClassHierarchyInfo getClassInfo(ClassDesc classDesc) {
        if (classDesc.isClassOrInterface()) {
            String descriptor = classDesc.descriptorString();
            Resolution resolution = classes.get(descriptor.substring(1, descriptor.length() - 1));
            if (resolution != null && resolution.winner != null) {
                Copy winner = resolution.winner;
                if ((winner.flags & ClassFile.ACC_INTERFACE) != 0) {
                    return ClassHierarchyInfo.ofInterface();
                }
                return ClassHierarchyInfo.ofClass(winner.superName == null
                        ? null : ClassDesc.ofInternalName(winner.superName));
            }
        }
        return JDK.getClassInfo(classDesc);
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    /**
     * The class entry a scan found that a caller asked to be told about.
     *
     * @param layer the name of the layer that holds it
     * @param entry the class's entry name, relative to its layer
     */
    record Watched(String layer, String entry) {
    }

    /**
     * One field or method of a class.
     *
     * @param name       its name
     * @param descriptor its descriptor
     * @param flags      its access flags
     */
    record Member(String name, String descriptor, int flags) {
    }

    /**
     * One copy of a class, as one layer holds it.
     */
    static final class Copy {

        private final String name;
        private final int layer;
        private final int version;
        private final int flags;
        private final String superName;
        private final List<String> interfaces;
        private final List<Member> members;

        private Copy(String name, int layer, int version, int flags, String superName, List<String> interfaces,
                     List<Member> members) {
            this.name = name;
            this.layer = layer;
            this.version = version;
            this.flags = flags;
            this.superName = superName;
            this.interfaces = interfaces;
            this.members = members;
        }

        /**
         * The class's internal name.
         *
         * @return the name, such as {@code com/example/Foo}
         */
        String name() {
            return name;
        }

        /**
         * The layer that holds this copy.
         *
         * @return {@code 0} for the application layer, then the dependency's position plus one
         */
        int layer() {
            return layer;
        }

        /**
         * The version directory this copy lives in.
         *
         * @return {@code N} of {@code META-INF/versions/N/}, or {@code 0} for the base entry
         */
        int version() {
            return version;
        }

        /**
         * The class's access flags.
         *
         * @return the flags
         */
        int flags() {
            return flags;
        }

        /**
         * The superclass.
         *
         * @return its internal name, or {@code null} for {@code java/lang/Object}
         */
        String superName() {
            return superName;
        }

        /**
         * The directly implemented interfaces.
         *
         * @return their internal names, in declaration order
         */
        List<String> interfaces() {
            return interfaces;
        }

        /**
         * Whether the class is an interface.
         *
         * @return whether {@code ACC_INTERFACE} is set
         */
        boolean isInterface() {
            return (flags & ClassFile.ACC_INTERFACE) != 0;
        }

        private Member member(String name, String descriptor) {
            if (members == null) {
                return null;
            }
            for (Member member : members) {
                if (member.name.equals(name) && member.descriptor.equals(descriptor)) {
                    return member;
                }
            }
            return null;
        }
    }

    /** What the model knows about one binary name. */
    private static final class Resolution {
        private Copy winner;
        private boolean uncertain;
    }

    /**
     * Deduplicates the names and descriptors of one build's scans, which run on several threads at once.
     */
    static final class Interner {

        private final ConcurrentHashMap<String, String> strings = new ConcurrentHashMap<>();

        String intern(String value) {
            String existing = strings.putIfAbsent(value, value);
            return existing == null ? value : existing;
        }
    }

    /**
     * The scan of one layer. It is confined to the thread that runs it until it is handed to
     * {@link #merge(List, boolean)}, and holds nothing but what it records.
     */
    static final class LayerScan {

        private static final ClassFile PARSER = ClassFile.of();

        private final int layer;
        private final String name;
        private final boolean multiRelease;
        private final boolean members;
        private final Predicate<String> watch;
        private final Interner strings;
        private final List<Copy> copies = new ArrayList<>();
        private String watched;

        private LayerScan(int layer, String name, boolean multiRelease, boolean members, Predicate<String> watch,
                          Interner strings) {
            this.layer = layer;
            this.name = Objects.requireNonNull(name, "name");
            this.multiRelease = multiRelease;
            this.members = members;
            this.watch = Objects.requireNonNull(watch, "watch");
            this.strings = Objects.requireNonNull(strings, "strings");
        }

        /**
         * Whether an entry is a class this scan records, so its bytes are worth reading.
         *
         * @param entryName the entry name
         * @param size      its uncompressed size
         * @return whether {@link #accept(String, byte[])} should be called with its content
         */
        boolean wants(String entryName, long size) {
            String path = classPath(entryName);
            if (path == null) {
                return false;
            }
            if (watched == null && watch.test(path)) {
                watched = path;
            }
            return size <= MAX_CLASS_SIZE && !LocalVariableStripper.isModuleInfo(path);
        }

        /**
         * Records one class. A class whose header cannot be parsed, or that does not declare the name its entry
         * implies, is left out: no class loader could define it under that name.
         *
         * @param entryName the entry name
         * @param bytes     its content
         */
        void accept(String entryName, byte[] bytes) {
            String path = classPath(entryName);
            if (path == null) {
                return;
            }
            String internalName = path.substring(0, path.length() - CLASS_SUFFIX.length());
            ClassModel model;
            String thisClass;
            try {
                model = PARSER.parse(bytes);
                thisClass = model.thisClass().asInternalName();
            } catch (IllegalArgumentException e) {
                return;
            }
            if (!thisClass.equals(internalName)) {
                return;
            }
            int version = entryName.equals(path) ? 0 : IndexWriter.versionOf(entryName);
            List<String> interfaces = new ArrayList<>(model.interfaces().size());
            for (ClassEntry entry : model.interfaces()) {
                interfaces.add(strings.intern(entry.asInternalName()));
            }
            List<Member> table = null;
            if (members) {
                table = new ArrayList<>(model.fields().size() + model.methods().size());
                for (FieldModel field : model.fields()) {
                    table.add(new Member(strings.intern(field.fieldName().stringValue()),
                            strings.intern(field.fieldType().stringValue()), field.flags().flagsMask()));
                }
                for (MethodModel method : model.methods()) {
                    table.add(new Member(strings.intern(method.methodName().stringValue()),
                            strings.intern(method.methodType().stringValue()), method.flags().flagsMask()));
                }
                table = List.copyOf(table);
            }
            String superName = model.superclass().map(ClassEntry::asInternalName).map(strings::intern)
                    .orElse(null);
            copies.add(new Copy(strings.intern(internalName), layer, version, model.flags().flagsMask(),
                    superName, interfaces.isEmpty() ? List.of() : Collections.unmodifiableList(interfaces),
                    table));
        }

        /**
         * The class-path name of an entry: the entry itself for a base class, the path after the version
         * directory for a variant that counts, or {@code null} for anything that is not a class on the class
         * path.
         */
        private String classPath(String entryName) {
            if (!entryName.endsWith(CLASS_SUFFIX) || entryName.endsWith("/")) {
                return null;
            }
            if (!entryName.startsWith(META_INF)) {
                return entryName;
            }
            if (!multiRelease || !entryName.startsWith(VERSIONS_PREFIX) || IndexWriter.versionOf(entryName) == 0) {
                return null;
            }
            String path = IndexWriter.pathOf(entryName);
            return path.isEmpty() || path.startsWith(META_INF) ? null : path;
        }
    }
}
