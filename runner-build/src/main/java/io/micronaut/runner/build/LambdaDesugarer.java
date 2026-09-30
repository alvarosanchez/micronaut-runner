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

import io.micronaut.runner.IndexFormat;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.NestHostAttribute;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.InvokeDynamicEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The desugar step: replaces the lambda and method-reference call sites of a class with calls to classes it
 * generates at packaging time.
 *
 * <p>A lambda compiles to an {@code invokedynamic} whose bootstrap, {@code LambdaMetafactory.metafactory}, spins
 * a hidden class the first time the site runs. The JDK archives such a class, in a CDS archive or an AOT cache,
 * only when the caller was defined by a built-in class loader, which {@code RunnerClassLoader} is not. So this
 * step writes that class ahead of time, as an ordinary class next to its host, and points the site at it: the
 * runtime links nothing, and a cache can hold the class.</p>
 *
 * <h2>What a site becomes</h2>
 * <p>One class per site, {@code <Host>$$Lambda$R<n>}: package-private, final and synthetic, with the host's
 * class-file version, in the host's package and jar. It implements the site's functional interface, keeps the
 * captured values in final fields, and forwards the interface method to the implementation with the argument
 * and return conversions {@code LambdaMetafactory} would apply. The site itself becomes
 * {@code invokestatic <Host>$$Lambda$R<n>.create}, with the descriptor of the {@code invokedynamic}, followed by
 * two {@code nop}s: the same length and the same stack effect, so every bytecode offset of the method and the
 * frames the pipeline attaches again stay valid. A site that captures nothing always yields the same
 * instance.</p>
 *
 * <p>From class-file version 55 the generated class joins the host's nest, so it may call a private
 * implementation directly, and the nest host's {@code NestMembers} is extended. Below 55 there are no
 * nestmates: a private implementation of the host is reached through a package-private static synthetic
 * bridge, {@code $runner$lambda$<n>}, added to the host.</p>
 *
 * <h2>What stays {@code invokedynamic}</h2>
 * <p>A site is rewritten only when the generated class provably resolves what the site resolved; every
 * {@link Reason} names one way it does not. Nothing of a signed jar, of a {@code META-INF/versions/} directory
 * or of {@code module-info} is considered, and neither is any bootstrap other than {@code metafactory}: string
 * concatenation, {@code ObjectMethods}, {@code altMetafactory} (serializable and marker-interface lambdas) and
 * the rest keep their call sites.</p>
 *
 * <h2>The nest is the unit</h2>
 * <p>{@link #plan(ClassTransformPipeline.Layer, ClassTransformPipeline.JarClasses)} plans a whole jar before
 * any of it is written, because a nest host may come before its members. The pipeline then rewrites, verifies
 * and accepts each {@link Unit}, a nest host with its rewritten members and their generated classes, or gives
 * up on it as a whole. The step never logs and never fails a build: a class it cannot parse is not planned,
 * and a unit it cannot rewrite falls back.</p>
 *
 * <p>The output is deterministic for a given build JDK: sites are numbered per host in method order and then
 * bytecode order, and nothing depends on the thread that stages the jar. The class depends on
 * {@code java.lang.classfile}, the class path model, {@link JdkClasses} and the pipeline's contract; it is
 * deliberately not public API.</p>
 */
final class LambdaDesugarer implements ClassTransformPipeline.Step {

    /** The name of the step, which is the name of the option that enables it. */
    static final String NAME = "desugarLambdas";

    /** What follows the host's name in a generated class's name, before the site number. */
    static final String GENERATED_INFIX = "$$Lambda$R";

    /** What precedes the site number in the name of a bridge method. */
    static final String BRIDGE_PREFIX = "$runner$lambda$";

    /** The static method of a generated class that a rewritten site calls. */
    static final String FACTORY_METHOD = "create";

    /** The constant pool string every class with a lambda call site holds. */
    private static final byte[] MARKER = "java/lang/invoke/LambdaMetafactory".getBytes(StandardCharsets.UTF_8);

    private static final String METAFACTORY_OWNER = "java/lang/invoke/LambdaMetafactory";

    private static final String METAFACTORY = "metafactory";

    private static final String ALT_METAFACTORY = "altMetafactory";

    private static final String CLASS_SUFFIX = ".class";

    private static final String META_INF = "META-INF/";

    private static final String SERIALIZABLE = "java/io/Serializable";

    private static final String SERIAL_VERSION_UID = "serialVersionUID";

    private static final String INSTANCE_FIELD = "INSTANCE";

    private static final String CONSTRUCTOR = ConstantDescs.INIT_NAME;

    /** The launcher's own package, whose classes the launcher's loader defines when it has them. */
    private static final String LAUNCHER_PREFIX = "io/micronaut/runner/";

    /** The packager's generated package, which is part of the application layer. */
    private static final String GENERATED_PREFIX = IndexFormat.GENERATED_PACKAGE.replace('.', '/') + "/";

    /** The first class-file version with {@code invokedynamic}. */
    private static final int INDY_MAJOR = 51;

    /** The first class-file version with nestmates. */
    private static final int NESTMATE_MAJOR = 55;

    private static final int CLASS_MAGIC = 0xCAFEBABE;

    /** What {@link #kindOf(String)} answers for a name no class answers to. */
    private static final int UNRESOLVED = 0;

    /** What {@link #kindOf(String)} answers for a class. */
    private static final int CLASS = 1;

    /** What {@link #kindOf(String)} answers for an interface. */
    private static final int INTERFACE = 2;

    private static final ClassFile PARSER = ClassFile.of();

    /** Generated classes have no branch, so they need no frames, and no class hierarchy to compute any. */
    private static final ClassFile GENERATOR = ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS);

    private final ClassPathModel model;

    /**
     * A desugarer over a class path.
     *
     * @param model the class path model, built with member tables
     * @throws IllegalArgumentException if the model has no member tables
     */
    LambdaDesugarer(ClassPathModel model) {
        if (!model.hasMembers()) {
            throw new IllegalArgumentException("Desugaring lambdas needs a class path model with member tables");
        }
        this.model = model;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean appliesTo(ClassTransformPipeline.Layer layer) {
        return !layer.signed();
    }

    /**
     * The pre-filter: a class outside {@code META-INF/}, other than {@code module-info}, of a class-file
     * version that has {@code invokedynamic}, whose bytes name {@code LambdaMetafactory}.
     */
    @Override
    public boolean matches(String entryName, byte[] bytes) {
        return isCandidate(entryName) && matchesBytes(bytes);
    }

    /**
     * The pre-filter's test of the bytes alone: a class file of a version that has {@code invokedynamic} that
     * names {@code LambdaMetafactory}. The class path scan applies it to every class it reads.
     *
     * @param bytes the class bytes
     * @return whether the class may hold a lambda call site
     */
    static boolean matchesBytes(byte[] bytes) {
        return bytes.length > 8 && u4(bytes, 0) == CLASS_MAGIC && u2(bytes, 6) >= INDY_MAJOR
                && LocalVariableStripper.contains(bytes, MARKER);
    }

    /**
     * Never: a class is desugared only as part of a planned {@link Unit}, never on its own.
     */
    @Override
    public boolean changes(ClassModel model) {
        return false;
    }

    @Override
    public ClassTransform transform(ClassModel model) {
        throw new IllegalStateException("A class is desugared through its nest's plan");
    }

    @Override
    public String summary(TransformReport report, List<ClassTransformPipeline.JarReport> reports) {
        int sites = 0;
        int generated = 0;
        int bridges = 0;
        int left = 0;
        int nestFallbacks = 0;
        int involved = 0;
        boolean application = false;
        for (ClassTransformPipeline.JarReport jar : reports) {
            ClassTransformPipeline.Desugared desugared = jar.desugared();
            if (desugared == null) {
                continue;
            }
            sites += desugared.sites();
            generated += desugared.generated();
            bridges += desugared.bridges();
            left += desugared.leftTotal();
            nestFallbacks += desugared.nestFallbacks();
            if (desugared.sites() > 0) {
                if (jar.application()) {
                    application = true;
                } else {
                    involved++;
                }
            }
        }
        return "Desugared " + sites + " lambda call sites into " + generated + " generated classes in " + involved
                + " dependencies" + (application ? " and the application layer" : "") + " (" + report.rewritten()
                + " classes rewritten, " + bridges + " bridges, " + left + " sites left as invokedynamic, "
                + nestFallbacks + " nest fallbacks, " + report.fallbacks() + " fallbacks)";
    }

    /**
     * Whether an entry may hold a host: a class outside {@code META-INF/}, which is where versioned variants
     * live, other than {@code module-info}.
     *
     * @param entryName the entry name
     * @return whether the entry is considered
     */
    static boolean isCandidate(String entryName) {
        return entryName.endsWith(CLASS_SUFFIX) && !entryName.startsWith(META_INF)
                && !LocalVariableStripper.isModuleInfo(entryName);
    }

    /**
     * Plans one jar, or the application layer: reads the classes that pass the pre-filter, and the nest hosts
     * they need, decides which sites are rewritten and allocates the generated and bridge names.
     *
     * <p>The class path scan has already applied the pre-filter to every class, so only the classes it marked
     * are read again, and only the copy of a name that the runtime loads, which is the only one that can be
     * rewritten: a copy that an earlier layer shadows or that a multi-release variant replaces is never
     * loaded, so its sites are neither rewritten nor counted.</p>
     *
     * @param layer   the jar
     * @param classes its classes
     * @return the plan
     * @throws IOException if a class cannot be read
     */
    JarPlan plan(ClassTransformPipeline.Layer layer, ClassTransformPipeline.JarClasses classes)
            throws IOException {
        Planner planner = new Planner(layer, classes);
        for (ClassTransformPipeline.ClassEntry entry : classes.classes()) {
            String name = entry.name();
            if (!isCandidate(name) || entry.size() > ClassTransformPipeline.MAX_CLASS_SIZE) {
                continue;
            }
            Optional<ClassPathModel.Copy> copy = model.winner(name.substring(0, name.length() - CLASS_SUFFIX.length()));
            if (copy.isEmpty() || !copy.get().lambdas() || copy.get().layer() != layer.index()
                    || copy.get().version() != 0) {
                continue;
            }
            byte[] bytes = planner.read(name);
            if (matches(name, bytes)) {
                planner.host(name, bytes);
            }
        }
        return planner.finish();
    }

    private static int u2(byte[] bytes, int position) {
        return (bytes[position] & 0xFF) << 8 | bytes[position + 1] & 0xFF;
    }

    private static int u4(byte[] bytes, int position) {
        return u2(bytes, position) << 16 | u2(bytes, position + 2);
    }

    /**
     * Whether the launcher's own loader may define a class of this name instead of the archive's copy:
     * {@code RunnerClassLoader} asks it first for {@code io.micronaut.runner}, outside the packager's generated
     * package, and the class path model does not hold the launcher's classes.
     */
    private static boolean preempted(String internalName) {
        return internalName.startsWith(LAUNCHER_PREFIX) && !internalName.startsWith(GENERATED_PREFIX);
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    private static String internalName(ClassDesc type) {
        String descriptor = type.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1);
    }

    private static String key(MethodModel method) {
        return method.methodName().stringValue() + method.methodType().stringValue();
    }

    private static boolean isVoid(ClassDesc type) {
        return type.descriptorString().equals("V");
    }

    /**
     * What a name resolves to at run time, as far as the build can tell: for a package the runtime asks the JDK
     * for first, the JDK's class; otherwise the class path's winning copy.
     *
     * @return {@link #UNRESOLVED}, {@link #CLASS} or {@link #INTERFACE}
     */
    private int kindOf(String internalName) {
        int flags;
        if (JdkClasses.owns(packageOf(internalName))) {
            JdkClasses.JdkClass jdk = JdkClasses.find(internalName);
            if (jdk == null) {
                return UNRESOLVED;
            }
            flags = jdk.flags();
        } else {
            Optional<ClassPathModel.Copy> copy = model.winner(internalName);
            if (copy.isEmpty()) {
                return UNRESOLVED;
            }
            flags = copy.get().flags();
        }
        return (flags & ClassFile.ACC_INTERFACE) != 0 ? INTERFACE : CLASS;
    }

    /**
     * Whether a type a generated class casts to resolves: a primitive, {@code void}, or a class, possibly the
     * element type of an array, that {@link #kindOf(String)} finds.
     */
    private boolean resolvesType(ClassDesc type) {
        ClassDesc element = type;
        while (element.isArray()) {
            element = element.componentType();
        }
        return element.isPrimitive() || kindOf(internalName(element)) != UNRESOLVED;
    }

    /**
     * Whether a class is, or may be, serializable: it is {@code java.io.Serializable}, a supertype of it is, or
     * a supertype cannot be found, in which case it may well be.
     */
    private boolean serializable(String internalName, Set<String> seen) {
        if (internalName.equals(SERIALIZABLE)) {
            return true;
        }
        if (!seen.add(internalName)) {
            return false;
        }
        String superName;
        List<String> interfaces;
        if (JdkClasses.owns(packageOf(internalName))) {
            JdkClasses.JdkClass jdk = JdkClasses.find(internalName);
            if (jdk == null) {
                return true;
            }
            superName = jdk.superName();
            interfaces = jdk.interfaces();
        } else {
            Optional<ClassPathModel.Copy> copy = model.winner(internalName);
            if (copy.isEmpty()) {
                return true;
            }
            superName = copy.get().superName();
            interfaces = copy.get().interfaces();
        }
        if (superName != null && serializable(superName, seen)) {
            return true;
        }
        for (String implemented : interfaces) {
            if (serializable(implemented, seen)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any {@code invokedynamic} constant of a class is bootstrapped by {@code LambdaMetafactory}: the
     * marker alone may be a string the class merely mentions.
     */
    private static boolean usesMetafactory(ClassModel model) {
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof InvokeDynamicEntry indy) {
                MemberRefEntry bootstrap = indy.bootstrap().bootstrapMethod().reference();
                if (bootstrap.owner().name().equalsString(METAFACTORY_OWNER)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The {@code LambdaMetafactory} bootstrap a site uses.
     *
     * @return the bootstrap method's name, or {@code null} for any other bootstrap
     */
    private static String bootstrap(InvokeDynamicInstruction indy) {
        MemberRefEntry bootstrap = indy.invokedynamic().bootstrap().bootstrapMethod().reference();
        if (!bootstrap.owner().name().equalsString(METAFACTORY_OWNER)) {
            return null;
        }
        return bootstrap.name().stringValue();
    }

    private static List<ClassDesc> nestMembers(ClassModel model) {
        Optional<NestMembersAttribute> attribute = model.findAttribute(Attributes.nestMembers());
        if (attribute.isEmpty()) {
            return List.of();
        }
        List<ClassDesc> members = new ArrayList<>(attribute.get().nestMembers().size());
        for (ClassEntry member : attribute.get().nestMembers()) {
            members.add(member.asSymbol());
        }
        return members;
    }

    /**
     * Why a {@code metafactory} site stays {@code invokedynamic}.
     */
    enum Reason {

        /** The bootstrap is {@code altMetafactory}: a serializable, marker-interface or bridged lambda. */
        ALT_METAFACTORY("altMetafactory"),

        /**
         * The host, its nest host or the implementation's owner is not the copy the runtime is sure to load: a
         * newer runtime would pick another variant, a jar holds the name twice, or, for a nest host or an owner,
         * an earlier layer holds it. A host copy that an earlier layer shadows is not counted: it is never loaded.
         */
        SHADOWED_OR_UNCERTAIN("shadowedOrUncertain"),

        /**
         * The host's nest host has a multi-release variant, which replaces it at run time. A host that has one is
         * not counted at all: the runtime never loads it.
         */
        MULTI_RELEASE("multiRelease"),

        /** The host's nest host is not a class of the same jar that lists the host as a member. */
        NEST("nest"),

        /** The implementation is an {@code invokespecial} on another class, such as {@code super::method}. */
        SUPER_CALL("superCall"),

        /**
         * The implementation's owner does not declare the member with that descriptor, or the generated class
         * could not access it.
         */
        OWNER_ACCESS("ownerAccess"),

        /** The implementation is a caller-sensitive method of the JDK. */
        CALLER_SENSITIVE("callerSensitive"),

        /** A class the generated class would name does not resolve on the class path or in the JDK. */
        UNRESOLVED_TYPE("unresolvedType"),

        /** A class or a member already has the name the generated class or the bridge would take. */
        NAME_TAKEN("nameTaken"),

        /** The host is an interface below class-file version 55 whose implementation is private. */
        JAVA8_INTERFACE("java8Interface"),

        /**
         * The host is a serializable class below class-file version 55 without a {@code serialVersionUID},
         * whose default would change with a bridge.
         */
        SERIAL_VERSION_UID("serialVersionUid"),

        /** The site has a shape {@code LambdaMetafactory} would reject, or one this step does not generate. */
        SHAPE("shape"),

        /** The site's nest was planned and then fell back. */
        NEST_FALLBACK("nestFallback");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        /**
         * The reason as {@code MICRONAUT-INF/transforms.txt} spells it.
         *
         * @return the label
         */
        String label() {
            return label;
        }
    }

    /**
     * The plan of one jar.
     */
    static final class JarPlan {

        private final List<Unit> units;
        private final int[] left;

        private JarPlan(List<Unit> units, int[] left) {
            this.units = units;
            this.left = left;
        }

        /**
         * The nests to rewrite, in the order their first host appears in the jar.
         *
         * @return the units
         */
        List<Unit> units() {
            return units;
        }

        /**
         * The sites that stay {@code invokedynamic}, counted by {@link Reason#ordinal()}.
         *
         * @return the counts
         */
        int[] left() {
            return left;
        }
    }

    /**
     * One nest to rewrite: the nest host, the members that hold rewritten sites and their generated classes.
     * Below class-file version 55 it is one host and its generated classes.
     */
    static final class Unit {

        private final String nestHostEntry;
        private final Map<String, ClassPlan> classes = new LinkedHashMap<>();

        private Unit(String nestHostEntry) {
            this.nestHostEntry = nestHostEntry;
        }

        /**
         * The entry of the nest host, which names the unit.
         *
         * @return the entry name
         */
        String nestHostEntry() {
            return nestHostEntry;
        }

        /**
         * The existing classes the unit rewrites, keyed by entry name.
         *
         * @return the classes
         */
        Map<String, ClassPlan> classes() {
            return classes;
        }

        /**
         * The number of sites the unit rewrites.
         *
         * @return the site count
         */
        int sites() {
            int sites = 0;
            for (ClassPlan plan : classes.values()) {
                sites += plan.sites.size();
            }
            return sites;
        }

        /**
         * The number of bridge methods the unit adds.
         *
         * @return the bridge count
         */
        int bridges() {
            int bridges = 0;
            for (ClassPlan plan : classes.values()) {
                for (SitePlan site : plan.sites) {
                    if (site.bridgeName != null) {
                        bridges++;
                    }
                }
            }
            return bridges;
        }

        /**
         * Generates the unit's classes.
         *
         * @return the generated classes of each host, keyed by the host's entry name, in site order
         */
        Map<String, List<ClassTransformPipeline.Generated>> generate() {
            Map<String, List<ClassTransformPipeline.Generated>> generated = new LinkedHashMap<>();
            for (ClassPlan plan : classes.values()) {
                if (plan.sites.isEmpty()) {
                    continue;
                }
                List<ClassTransformPipeline.Generated> own = new ArrayList<>(plan.sites.size());
                for (SitePlan site : plan.sites) {
                    own.add(new ClassTransformPipeline.Generated(internalName(site.generated) + CLASS_SUFFIX,
                            site.generate()));
                }
                generated.put(plan.entryName, own);
            }
            return generated;
        }
    }

    /**
     * What the step does to one existing class: the sites it rewrites, the bridges it adds and, for a nest
     * host, the members it gains.
     */
    static final class ClassPlan {

        private final String entryName;
        private final byte[] original;
        private final Map<String, SitePlan[]> sitesByMethod;
        private final List<SitePlan> sites;
        private List<ClassDesc> nestMembers = List.of();

        private ClassPlan(String entryName, byte[] original, Map<String, SitePlan[]> sitesByMethod,
                          List<SitePlan> sites) {
            this.entryName = entryName;
            this.original = original;
            this.sitesByMethod = sitesByMethod;
            this.sites = sites;
        }

        /**
         * The class as the jar holds it.
         *
         * @return its bytes
         */
        byte[] original() {
            return original;
        }

        /**
         * The transform that rewrites the class. Every edit keeps its length and its stack effect, as the
         * pipeline requires.
         *
         * @return a fresh transform
         */
        ClassTransform transform() {
            return new ClassTransform() {
                @Override
                public void accept(ClassBuilder builder, ClassElement element) {
                    if (element instanceof NestMembersAttribute && !nestMembers.isEmpty()) {
                        // Written again at the end, with the generated classes.
                        return;
                    }
                    if (element instanceof MethodModel method && method.code().isPresent()) {
                        SitePlan[] planned = sitesByMethod.get(key(method));
                        if (planned != null) {
                            builder.transformMethod(method, MethodTransform.transformingCode(new Rewriter(planned)));
                            return;
                        }
                    }
                    builder.with(element);
                }

                @Override
                public void atEnd(ClassBuilder builder) {
                    for (SitePlan site : sites) {
                        if (site.bridgeName != null) {
                            site.addBridge(builder);
                        }
                    }
                    if (!nestMembers.isEmpty()) {
                        builder.with(NestMembersAttribute.ofSymbols(nestMembers));
                    }
                }
            };
        }
    }

    /**
     * Rewrites the planned sites of one method. A site is found by its position among the method's
     * {@code invokedynamic} instructions, which no pipeline option changes.
     */
    private static final class Rewriter implements CodeTransform {

        private final SitePlan[] planned;
        private int ordinal;

        private Rewriter(SitePlan[] planned) {
            this.planned = planned;
        }

        @Override
        public void atStart(CodeBuilder builder) {
            // The ClassFile API may run a code handler again, for instance to widen a jump.
            ordinal = 0;
        }

        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            if (element instanceof InvokeDynamicInstruction indy) {
                SitePlan site = ordinal < planned.length ? planned[ordinal] : null;
                ordinal++;
                if (site != null) {
                    if (!indy.typeSymbol().equals(site.factoryType)
                            || !indy.name().equalsString(site.samName)) {
                        throw new IllegalStateException("The call site of " + site.generated.displayName()
                                + " is not where it was planned");
                    }
                    // 3 + 1 + 1 bytes, as the invokedynamic, with the same stack effect.
                    builder.invokestatic(site.generated, FACTORY_METHOD, site.factoryType).nop().nop();
                    return;
                }
            }
            builder.with(element);
        }
    }

    /** How a generated class calls the implementation. */
    private enum Invocation {
        STATIC, VIRTUAL, INTERFACE, CONSTRUCTOR
    }

    /**
     * Where the classes generated for a host go.
     *
     * @param host     the host
     * @param major    the host's class-file version, which its generated classes take
     * @param minor    the host's minor version
     * @param nestHost the nest the generated classes join, or {@code null} below class-file version 55
     */
    private record Home(ClassDesc host, int major, int minor, ClassDesc nestHost) {
    }

    /**
     * A call site, as the host's code states it.
     *
     * @param factoryType      the descriptor of the {@code invokedynamic}: the captured types to the functional
     *                         interface
     * @param samName          the name of the interface method
     * @param samType          the erased descriptor of the interface method, which the generated class declares
     * @param instantiatedType the descriptor the interface method has at this site, which decides the casts
     */
    private record Shape(MethodTypeDesc factoryType, String samName, MethodTypeDesc samType,
                         MethodTypeDesc instantiatedType) {
    }

    /**
     * The implementation a site forwards to.
     *
     * @param owner          the class that declares it
     * @param ownerInterface whether the site names that class as an interface
     * @param name           its name, {@code <init>} for a constructor
     * @param descriptor     its descriptor, as its owner declares it
     * @param type           the implementation as a call: the receiver first for an instance method, the owner as
     *                       a constructor's result
     * @param invocation     how a generated class calls it
     * @param isStatic       whether it is a static method
     */
    private record Target(ClassDesc owner, boolean ownerInterface, String name, MethodTypeDesc descriptor,
                          MethodTypeDesc type, Invocation invocation, boolean isStatic) {
    }

    /**
     * One rewritten site: everything its generated class and its bridge are written from.
     */
    private static final class SitePlan {

        private final ClassDesc host;
        private final int major;
        private final int minor;
        /** The nest the generated class joins, or {@code null} below class-file version 55. */
        private final ClassDesc nestHost;
        private final ClassDesc generated;
        /** The descriptor of the {@code invokedynamic}: the captured types to the functional interface. */
        private final MethodTypeDesc factoryType;
        private final String samName;
        /** The erased descriptor of the interface method, which the generated class declares. */
        private final MethodTypeDesc samType;
        /** The descriptor the interface method has at this site, which decides the casts. */
        private final MethodTypeDesc instantiatedType;
        private final ClassDesc owner;
        private final boolean ownerInterface;
        private final String implName;
        /** The descriptor of the implementation as its owner declares it. */
        private final MethodTypeDesc implDescriptor;
        /** The implementation as a call: the receiver first, or the owner as a constructor's result. */
        private final MethodTypeDesc implType;
        private final Invocation invocation;
        /** The bridge the host gains and the generated class calls, or {@code null}. */
        private final String bridgeName;
        /** How the bridge itself calls the private implementation. */
        private final boolean bridgeStatic;

        private SitePlan(Home home, ClassDesc generated, Shape shape, Target target, String bridgeName) {
            this.host = home.host();
            this.major = home.major();
            this.minor = home.minor();
            this.nestHost = home.nestHost();
            this.generated = generated;
            this.factoryType = shape.factoryType();
            this.samName = shape.samName();
            this.samType = shape.samType();
            this.instantiatedType = shape.instantiatedType();
            this.owner = target.owner();
            this.ownerInterface = target.ownerInterface();
            this.implName = target.name();
            this.implDescriptor = target.descriptor();
            this.implType = target.type();
            this.invocation = target.invocation();
            this.bridgeName = bridgeName;
            this.bridgeStatic = target.isStatic();
        }

        /**
         * Adds the bridge to the host: a static method with the implementation's call type that loads its
         * arguments, calls the private implementation and returns.
         */
        private void addBridge(ClassBuilder builder) {
            builder.withMethodBody(bridgeName, implType, ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC, code -> {
                if (invocation == Invocation.CONSTRUCTOR) {
                    code.new_(owner).dup();
                }
                int slot = 0;
                for (int i = 0; i < implType.parameterCount(); i++) {
                    TypeKind kind = TypeKind.from(implType.parameterType(i));
                    code.loadLocal(kind, slot);
                    slot += kind.slotSize();
                }
                if (bridgeStatic) {
                    code.invokestatic(owner, implName, implDescriptor);
                } else {
                    // The pre-nestmate form of a call to a private instance method or constructor.
                    code.invokespecial(owner, implName, implDescriptor);
                }
                code.return_(TypeKind.from(implType.returnType()));
            });
        }

        /**
         * Writes the generated class.
         */
        private byte[] generate() {
            int captured = factoryType.parameterCount();
            ClassDesc functionalInterface = factoryType.returnType();
            MethodTypeDesc constructorType = MethodTypeDesc.of(ConstantDescs.CD_void, factoryType.parameterList());
            return GENERATOR.build(generated, builder -> {
                builder.withVersion(major, minor);
                builder.withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC | ClassFile.ACC_SUPER);
                builder.withSuperclass(ConstantDescs.CD_Object);
                builder.withInterfaceSymbols(functionalInterface);
                if (captured == 0) {
                    builder.withField(INSTANCE_FIELD, generated,
                            ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
                    builder.withMethodBody(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void,
                            ClassFile.ACC_STATIC, code -> code
                                    .new_(generated).dup()
                                    .invokespecial(generated, CONSTRUCTOR, constructorType)
                                    .putstatic(generated, INSTANCE_FIELD, generated)
                                    .return_());
                }
                for (int i = 0; i < captured; i++) {
                    builder.withField("f" + i, factoryType.parameterType(i),
                            ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
                }
                builder.withMethodBody(CONSTRUCTOR, constructorType, ClassFile.ACC_PRIVATE, code -> {
                    code.aload(0).invokespecial(ConstantDescs.CD_Object, CONSTRUCTOR, ConstantDescs.MTD_void);
                    int slot = 1;
                    for (int i = 0; i < captured; i++) {
                        ClassDesc type = factoryType.parameterType(i);
                        TypeKind kind = TypeKind.from(type);
                        code.aload(0).loadLocal(kind, slot).putfield(generated, "f" + i, type);
                        slot += kind.slotSize();
                    }
                    code.return_();
                });
                builder.withMethodBody(FACTORY_METHOD, factoryType, ClassFile.ACC_STATIC, code -> {
                    if (captured == 0) {
                        code.getstatic(generated, INSTANCE_FIELD, generated).areturn();
                        return;
                    }
                    code.new_(generated).dup();
                    int slot = 0;
                    for (int i = 0; i < captured; i++) {
                        TypeKind kind = TypeKind.from(factoryType.parameterType(i));
                        code.loadLocal(kind, slot);
                        slot += kind.slotSize();
                    }
                    code.invokespecial(generated, CONSTRUCTOR, constructorType).areturn();
                });
                builder.withMethodBody(samName, samType, ClassFile.ACC_PUBLIC, this::forward);
                if (nestHost != null) {
                    builder.with(NestHostAttribute.of(nestHost));
                }
            });
        }

        /**
         * The body of the interface method: the captured values, then each argument adapted to the
         * implementation, the call, and the result adapted back.
         */
        private void forward(CodeBuilder code) {
            int captured = factoryType.parameterCount();
            if (invocation == Invocation.CONSTRUCTOR && bridgeName == null) {
                code.new_(owner).dup();
            }
            for (int i = 0; i < captured; i++) {
                code.aload(0).getfield(generated, "f" + i, factoryType.parameterType(i));
            }
            int slot = 1;
            for (int i = 0; i < samType.parameterCount(); i++) {
                ClassDesc argument = samType.parameterType(i);
                TypeKind kind = TypeKind.from(argument);
                code.loadLocal(kind, slot);
                slot += kind.slotSize();
                Conversions.convert(code, argument, implType.parameterType(captured + i),
                        instantiatedType.parameterType(i));
            }
            if (bridgeName != null) {
                code.invokestatic(host, bridgeName, implType);
            } else {
                switch (invocation) {
                    case STATIC -> code.invokestatic(owner, implName, implDescriptor, ownerInterface);
                    case VIRTUAL -> code.invokevirtual(owner, implName, implDescriptor);
                    case INTERFACE -> code.invokeinterface(owner, implName, implDescriptor);
                    case CONSTRUCTOR -> code.invokespecial(owner, implName, implDescriptor);
                    default -> throw new IllegalStateException("Unknown invocation " + invocation);
                }
            }
            ClassDesc result = implType.returnType();
            ClassDesc expected = samType.returnType();
            if (isVoid(expected)) {
                if (!isVoid(result)) {
                    if (TypeKind.from(result).slotSize() == 2) {
                        code.pop2();
                    } else {
                        code.pop();
                    }
                }
                code.return_();
                return;
            }
            Conversions.convert(code, result, expected, expected);
            code.return_(TypeKind.from(expected));
        }
    }

    /**
     * The argument and return conversions of {@code LambdaMetafactory}, from
     * {@code java.lang.invoke.TypeConvertingMethodAdapter}, over type descriptors instead of loaded classes.
     */
    private static final class Conversions {

        private Conversions() {
        }

        /**
         * Converts the value on top of the stack.
         *
         * @param code       where to emit
         * @param argument   the type the value has
         * @param target     the type it must get
         * @param functional the type the site says it has, which is cast to first when it is more specific
         */
        static void convert(CodeBuilder code, ClassDesc argument, ClassDesc target, ClassDesc functional) {
            if (argument.equals(target) && argument.equals(functional)) {
                return;
            }
            if (isVoid(argument) || isVoid(target)) {
                return;
            }
            if (argument.isPrimitive()) {
                if (target.isPrimitive()) {
                    widen(code, TypeKind.from(argument), TypeKind.from(target));
                    return;
                }
                TypeKind unwrapped = unwrapped(target);
                if (unwrapped != null) {
                    widen(code, TypeKind.from(argument), unwrapped);
                    box(code, unwrapped);
                } else {
                    box(code, TypeKind.from(argument));
                    cast(code, target);
                }
                return;
            }
            ClassDesc source;
            if (argument.equals(functional) || functional.isPrimitive()) {
                source = argument;
            } else {
                source = functional;
                cast(code, functional);
            }
            if (!target.isPrimitive()) {
                if (!source.equals(target)) {
                    cast(code, target);
                }
                return;
            }
            TypeKind kind = TypeKind.from(target);
            TypeKind unwrapped = unwrapped(source);
            if (unwrapped != null) {
                unbox(code, wrapper(unwrapped), unwrapped);
                widen(code, unwrapped, kind);
            } else if (kind == TypeKind.BOOLEAN || kind == TypeKind.CHAR) {
                code.checkcast(wrapper(kind));
                unbox(code, wrapper(kind), kind);
            } else {
                code.checkcast(ConstantDescs.CD_Number);
                unbox(code, ConstantDescs.CD_Number, kind);
            }
        }

        /**
         * Whether {@link #convert} has a legal conversion for a value, as far as descriptors alone can tell.
         * Two reference types are taken to be related, as {@code javac} guarantees.
         */
        static boolean convertible(ClassDesc argument, ClassDesc target, ClassDesc functional) {
            if (isVoid(argument) || isVoid(target) || isVoid(functional)) {
                return false;
            }
            if (argument.isPrimitive()) {
                if (target.isPrimitive()) {
                    return widens(TypeKind.from(argument), TypeKind.from(target));
                }
                TypeKind unwrapped = unwrapped(target);
                return unwrapped == null || widens(TypeKind.from(argument), unwrapped);
            }
            if (!target.isPrimitive()) {
                return true;
            }
            ClassDesc source = argument.equals(functional) || functional.isPrimitive() ? argument : functional;
            TypeKind unwrapped = unwrapped(source);
            return unwrapped == null || widens(unwrapped, TypeKind.from(target));
        }

        private static boolean widens(TypeKind from, TypeKind to) {
            if (from == to) {
                return true;
            }
            return switch (from) {
                case BYTE -> to == TypeKind.SHORT || to == TypeKind.INT || to == TypeKind.LONG
                        || to == TypeKind.FLOAT || to == TypeKind.DOUBLE;
                case SHORT, CHAR -> to == TypeKind.INT || to == TypeKind.LONG || to == TypeKind.FLOAT
                        || to == TypeKind.DOUBLE;
                case INT -> to == TypeKind.LONG || to == TypeKind.FLOAT || to == TypeKind.DOUBLE;
                case LONG -> to == TypeKind.FLOAT || to == TypeKind.DOUBLE;
                case FLOAT -> to == TypeKind.DOUBLE;
                default -> false;
            };
        }

        private static void widen(CodeBuilder code, TypeKind from, TypeKind to) {
            TypeKind source = from.asLoadable();
            TypeKind destination = to.asLoadable();
            if (source == destination) {
                return;
            }
            switch (source) {
                case INT -> {
                    switch (destination) {
                        case LONG -> code.i2l();
                        case FLOAT -> code.i2f();
                        case DOUBLE -> code.i2d();
                        default -> throw new IllegalStateException("No widening from int to " + destination);
                    }
                }
                case LONG -> {
                    switch (destination) {
                        case FLOAT -> code.l2f();
                        case DOUBLE -> code.l2d();
                        default -> throw new IllegalStateException("No widening from long to " + destination);
                    }
                }
                case FLOAT -> {
                    if (destination != TypeKind.DOUBLE) {
                        throw new IllegalStateException("No widening from float to " + destination);
                    }
                    code.f2d();
                }
                default -> throw new IllegalStateException("No widening from " + source + " to " + destination);
            }
        }

        private static void cast(CodeBuilder code, ClassDesc target) {
            if (!target.equals(ConstantDescs.CD_Object)) {
                code.checkcast(target);
            }
        }

        private static void box(CodeBuilder code, TypeKind kind) {
            ClassDesc wrapper = wrapper(kind);
            code.invokestatic(wrapper, "valueOf", MethodTypeDesc.of(wrapper, primitive(kind)));
        }

        private static void unbox(CodeBuilder code, ClassDesc owner, TypeKind kind) {
            ClassDesc primitive = primitive(kind);
            code.invokevirtual(owner, primitive.displayName() + "Value", MethodTypeDesc.of(primitive));
        }

        /** The primitive a wrapper class wraps, or {@code null} for any other type. */
        private static TypeKind unwrapped(ClassDesc type) {
            return switch (type.descriptorString()) {
                case "Ljava/lang/Boolean;" -> TypeKind.BOOLEAN;
                case "Ljava/lang/Byte;" -> TypeKind.BYTE;
                case "Ljava/lang/Short;" -> TypeKind.SHORT;
                case "Ljava/lang/Character;" -> TypeKind.CHAR;
                case "Ljava/lang/Integer;" -> TypeKind.INT;
                case "Ljava/lang/Long;" -> TypeKind.LONG;
                case "Ljava/lang/Float;" -> TypeKind.FLOAT;
                case "Ljava/lang/Double;" -> TypeKind.DOUBLE;
                default -> null;
            };
        }

        private static ClassDesc wrapper(TypeKind kind) {
            return switch (kind) {
                case BOOLEAN -> ConstantDescs.CD_Boolean;
                case BYTE -> ConstantDescs.CD_Byte;
                case SHORT -> ConstantDescs.CD_Short;
                case CHAR -> ConstantDescs.CD_Character;
                case INT -> ConstantDescs.CD_Integer;
                case LONG -> ConstantDescs.CD_Long;
                case FLOAT -> ConstantDescs.CD_Float;
                case DOUBLE -> ConstantDescs.CD_Double;
                default -> throw new IllegalStateException("No wrapper for " + kind);
            };
        }

        private static ClassDesc primitive(TypeKind kind) {
            return switch (kind) {
                case BOOLEAN -> ConstantDescs.CD_boolean;
                case BYTE -> ConstantDescs.CD_byte;
                case SHORT -> ConstantDescs.CD_short;
                case CHAR -> ConstantDescs.CD_char;
                case INT -> ConstantDescs.CD_int;
                case LONG -> ConstantDescs.CD_long;
                case FLOAT -> ConstantDescs.CD_float;
                case DOUBLE -> ConstantDescs.CD_double;
                default -> throw new IllegalStateException("No primitive for " + kind);
            };
        }
    }

    /**
     * A nest host, as the jar holds it.
     */
    private static final class Nest {

        private final String name;
        private final byte[] bytes;
        /** The members its {@code NestMembers} attribute lists, in order. */
        private final List<ClassDesc> members;
        private final Set<String> memberNames;

        private Nest(String name, byte[] bytes, List<ClassDesc> members) {
            this.name = name;
            this.bytes = bytes;
            this.members = members;
            this.memberNames = new HashSet<>();
            for (ClassDesc member : members) {
                memberNames.add(internalName(member));
            }
        }

        private boolean holds(String internalName) {
            return internalName.equals(name) || memberNames.contains(internalName);
        }
    }

    /**
     * One {@code metafactory} site, as the host's code holds it.
     *
     * @param method  the method that holds it, by name and descriptor
     * @param ordinal its position among the {@code invokedynamic} instructions of that method
     * @param indy    the instruction
     */
    private record Found(String method, int ordinal, InvokeDynamicInstruction indy) {
    }

    /**
     * Plans one jar. It is confined to the thread that stages the jar.
     */
    private final class Planner {

        private final ClassTransformPipeline.Layer layer;
        private final ClassTransformPipeline.JarClasses classes;
        /** The bytes of the classes the plan holds on to: planned hosts and nest hosts. */
        private final Map<String, byte[]> retained = new HashMap<>();
        /** The nest hosts read so far, by internal name; {@code null} for one that cannot be used. */
        private final Map<String, Nest> nests = new HashMap<>();
        private final Map<String, Unit> units = new LinkedHashMap<>();
        private final Map<String, Nest> unitNests = new HashMap<>();
        private final int[] left = new int[Reason.values().length];

        private Planner(ClassTransformPipeline.Layer layer, ClassTransformPipeline.JarClasses classes) {
            this.layer = layer;
            this.classes = classes;
        }

        private byte[] read(String entryName) throws IOException {
            byte[] kept = retained.get(entryName);
            return kept != null ? kept : classes.read(entryName);
        }

        /**
         * Plans one class that passed the pre-filter. A class that cannot be parsed is not planned: the entry
         * loop writes it as it would without this step.
         */
        private void host(String entryName, byte[] bytes) throws IOException {
            HostPlan plan;
            try {
                plan = evaluate(entryName, bytes);
            } catch (RuntimeException e) {
                return;
            }
            if (plan == null) {
                return;
            }
            for (int reason = 0; reason < left.length; reason++) {
                left[reason] += plan.left[reason];
            }
            if (plan.sites.isEmpty()) {
                return;
            }
            retained.put(entryName, bytes);
            Unit unit = units.computeIfAbsent(plan.unitName, name -> new Unit(name + CLASS_SUFFIX));
            if (plan.nest != null) {
                unitNests.put(plan.unitName, plan.nest);
            }
            unit.classes.put(entryName, new ClassPlan(entryName, bytes, plan.sitesByMethod, plan.sites));
        }

        /**
         * Completes the plan: every nest host of version 55 or later is rewritten, even without a site of its
         * own, to list the generated classes of its nest after the members it already has.
         */
        private JarPlan finish() {
            List<Unit> planned = new ArrayList<>(units.size());
            for (Map.Entry<String, Unit> entry : units.entrySet()) {
                Unit unit = entry.getValue();
                Nest nest = unitNests.get(entry.getKey());
                if (nest != null) {
                    List<ClassDesc> members = new ArrayList<>(nest.members);
                    for (ClassPlan plan : unit.classes.values()) {
                        for (SitePlan site : plan.sites) {
                            members.add(site.generated);
                        }
                    }
                    ClassPlan host = unit.classes.get(unit.nestHostEntry);
                    if (host == null) {
                        host = new ClassPlan(unit.nestHostEntry, nest.bytes, Map.of(), List.of());
                        unit.classes.put(unit.nestHostEntry, host);
                    }
                    host.nestMembers = List.copyOf(members);
                }
                planned.add(unit);
            }
            return new JarPlan(planned, left);
        }

        /**
         * Decides what happens to each site of one class.
         *
         * @return the class's plan, or {@code null} when it has no {@code metafactory} site
         * @throws RuntimeException if the class is malformed
         */
        private HostPlan evaluate(String entryName, byte[] bytes) throws IOException {
            ClassModel host = PARSER.parse(bytes);
            if (!usesMetafactory(host)) {
                return null;
            }
            String hostName = host.thisClass().asInternalName();
            if (!entryName.equals(hostName + CLASS_SUFFIX)) {
                // No class loader defines it under this name.
                return null;
            }
            HostPlan plan = new HostPlan();
            List<Found> found = new ArrayList<>();
            Map<String, Integer> sitesPerMethod = new HashMap<>();
            for (MethodModel method : host.methods()) {
                Optional<CodeModel> code = method.code();
                if (code.isEmpty()) {
                    continue;
                }
                String methodKey = key(method);
                int ordinal = 0;
                for (CodeElement element : code.get()) {
                    if (element instanceof InvokeDynamicInstruction indy) {
                        String bootstrap = bootstrap(indy);
                        if (METAFACTORY.equals(bootstrap)) {
                            found.add(new Found(methodKey, ordinal, indy));
                        } else if (ALT_METAFACTORY.equals(bootstrap)) {
                            plan.left[Reason.ALT_METAFACTORY.ordinal()]++;
                        }
                        ordinal++;
                    }
                }
                sitesPerMethod.put(methodKey, ordinal);
            }
            if (found.isEmpty()) {
                return plan;
            }
            int major = host.majorVersion();
            Reason excluded = excluded(hostName, entryName);
            Nest nest = null;
            if (excluded == null && major >= NESTMATE_MAJOR) {
                String nestHostName = host.findAttribute(Attributes.nestHost())
                        .map(attribute -> attribute.nestHost().asInternalName()).orElse(hostName);
                if (nestHostName.equals(hostName)) {
                    nest = new Nest(hostName, bytes, nestMembers(host));
                } else {
                    excluded = excluded(nestHostName, nestHostName + CLASS_SUFFIX);
                    if (excluded == null) {
                        nest = nest(nestHostName);
                        if (nest == null || !nest.holds(hostName)) {
                            excluded = Reason.NEST;
                        }
                    }
                }
            }
            if (excluded != null) {
                plan.left[excluded.ordinal()] += found.size();
                return plan;
            }
            plan.nest = nest;
            plan.unitName = nest == null ? hostName : nest.name;
            Host context = new Host(host, hostName, major, host.minorVersion(), nest);
            for (Found site : found) {
                Object outcome = context.site(site.indy);
                if (outcome instanceof Reason reason) {
                    plan.left[reason.ordinal()]++;
                    continue;
                }
                SitePlan planned = (SitePlan) outcome;
                plan.sites.add(planned);
                plan.sitesByMethod.computeIfAbsent(site.method,
                        method -> new SitePlan[sitesPerMethod.get(method)])[site.ordinal] = planned;
            }
            return plan;
        }

        /**
         * Why a host or a nest host cannot be rewritten at all, or {@code null} when it can: it must be the
         * copy the runtime loads, on JDK 25 and later, and the only entry of its name in its jar.
         */
        private Reason excluded(String internalName, String entryName) {
            Optional<ClassPathModel.Copy> winner = model.winner(internalName);
            if (winner.isEmpty() || winner.get().layer() != layer.index() || preempted(internalName)) {
                return Reason.SHADOWED_OR_UNCERTAIN;
            }
            if (winner.get().version() != 0) {
                return Reason.MULTI_RELEASE;
            }
            if (model.uncertain(internalName) || classes.repeated(entryName)) {
                return Reason.SHADOWED_OR_UNCERTAIN;
            }
            return null;
        }

        /**
         * Reads a nest host that is not the class being planned.
         *
         * @return the nest, or {@code null} when the jar holds no such class of version 55 or later
         */
        private Nest nest(String nestHostName) throws IOException {
            if (nests.containsKey(nestHostName)) {
                return nests.get(nestHostName);
            }
            Nest nest = null;
            String entryName = nestHostName + CLASS_SUFFIX;
            long size = classes.size(entryName);
            if (isCandidate(entryName) && size >= 0 && size <= ClassTransformPipeline.MAX_CLASS_SIZE) {
                byte[] bytes = read(entryName);
                try {
                    ClassModel parsed = PARSER.parse(bytes);
                    if (parsed.thisClass().asInternalName().equals(nestHostName)
                            && parsed.majorVersion() >= NESTMATE_MAJOR) {
                        nest = new Nest(nestHostName, bytes, nestMembers(parsed));
                        retained.put(entryName, bytes);
                    }
                } catch (RuntimeException e) {
                    nest = null;
                }
            }
            nests.put(nestHostName, nest);
            return nest;
        }

        /**
         * One class being planned, with what its sites share.
         */
        private final class Host {

            private final ClassModel parsed;
            private final String name;
            private final String packageName;
            private final Home home;
            private final int major;
            private final Nest nest;
            private final boolean isInterface;
            private final Set<String> memberNames = new HashSet<>();
            private int next;
            /** Why the class cannot take a bridge, computed when the first site needs one. */
            private Reason unbridgeable;
            private boolean bridgeChecked;

            private Host(ClassModel parsed, String name, int major, int minor, Nest nest) {
                this.parsed = parsed;
                this.name = name;
                this.packageName = packageOf(name);
                this.home = new Home(ClassDesc.ofInternalName(name), major, minor,
                        nest == null ? null : ClassDesc.ofInternalName(nest.name));
                this.major = major;
                this.nest = nest;
                this.isInterface = (parsed.flags().flagsMask() & ClassFile.ACC_INTERFACE) != 0;
                for (MethodModel method : parsed.methods()) {
                    memberNames.add(method.methodName().stringValue());
                }
                for (FieldModel field : parsed.fields()) {
                    memberNames.add(field.fieldName().stringValue());
                }
            }

            /**
             * Decides one site.
             *
             * @return its {@link SitePlan}, or the {@link Reason} it stays {@code invokedynamic}
             */
            private Object site(InvokeDynamicInstruction indy) {
                List<ConstantDesc> arguments = indy.bootstrapArgs();
                if (arguments.size() != 3 || !(arguments.get(0) instanceof MethodTypeDesc samType)
                        || !(arguments.get(1) instanceof DirectMethodHandleDesc implementation)
                        || !(arguments.get(2) instanceof MethodTypeDesc instantiatedType)) {
                    return Reason.SHAPE;
                }
                MethodTypeDesc factoryType = indy.typeSymbol();
                String samName = indy.name().stringValue();
                ClassDesc functionalInterface = factoryType.returnType();
                if (!functionalInterface.isClassOrInterface() || !implementation.owner().isClassOrInterface()) {
                    return Reason.SHAPE;
                }
                String owner = internalName(implementation.owner());
                Invocation invocation;
                boolean instance = true;
                switch (implementation.kind()) {
                    case STATIC, INTERFACE_STATIC -> {
                        invocation = Invocation.STATIC;
                        instance = false;
                    }
                    case VIRTUAL -> invocation = Invocation.VIRTUAL;
                    case INTERFACE_VIRTUAL -> invocation = Invocation.INTERFACE;
                    case SPECIAL, INTERFACE_SPECIAL -> {
                        if (!owner.equals(name)) {
                            return Reason.SUPER_CALL;
                        }
                        // As LambdaMetafactory does for a private method of the caller itself.
                        invocation = implementation.isOwnerInterface() ? Invocation.INTERFACE : Invocation.VIRTUAL;
                    }
                    case CONSTRUCTOR -> {
                        invocation = Invocation.CONSTRUCTOR;
                        instance = false;
                    }
                    default -> {
                        return Reason.SHAPE;
                    }
                }
                MethodTypeDesc implType = implementation.invocationType();
                MethodTypeDesc implDescriptor = MethodTypeDesc.ofDescriptor(implementation.lookupDescriptor());
                String implName = invocation == Invocation.CONSTRUCTOR ? CONSTRUCTOR : implementation.methodName();
                if (!shaped(factoryType, samName, samType, instantiatedType, implType, instance)) {
                    return Reason.SHAPE;
                }

                boolean bridged = false;
                int flags;
                boolean ownerIsInterface;
                if (JdkClasses.owns(packageOf(owner))) {
                    // The runtime asks the JDK first for this package, whatever the class path holds.
                    JdkClasses.JdkClass jdk = JdkClasses.find(owner);
                    if (jdk == null || (jdk.flags() & ClassFile.ACC_PUBLIC) == 0 || !jdk.exported()) {
                        return Reason.OWNER_ACCESS;
                    }
                    Integer method = jdk.method(implName, implementation.lookupDescriptor());
                    if (method == null || (method & ClassFile.ACC_PUBLIC) == 0) {
                        return Reason.OWNER_ACCESS;
                    }
                    if ((method & JdkClasses.JdkClass.CALLER_SENSITIVE) != 0) {
                        return Reason.CALLER_SENSITIVE;
                    }
                    if ((method & ClassFile.ACC_NATIVE) != 0 && (method & ClassFile.ACC_VARARGS) != 0
                            && owner.startsWith("java/lang/invoke/")) {
                        // A signature-polymorphic method: its descriptor is the call site's, not the method's.
                        return Reason.SHAPE;
                    }
                    flags = method;
                    ownerIsInterface = (jdk.flags() & ClassFile.ACC_INTERFACE) != 0;
                } else {
                    Optional<ClassPathModel.Copy> copy = model.winner(owner);
                    if (copy.isEmpty()) {
                        return Reason.OWNER_ACCESS;
                    }
                    if (model.uncertain(owner) || preempted(owner)) {
                        return Reason.SHADOWED_OR_UNCERTAIN;
                    }
                    ClassPathModel.Member member = copy.get().member(implName, implementation.lookupDescriptor());
                    if (member == null) {
                        return Reason.OWNER_ACCESS;
                    }
                    flags = member.flags();
                    ownerIsInterface = copy.get().isInterface();
                    if ((flags & ClassFile.ACC_PRIVATE) != 0) {
                        if (owner.equals(name)) {
                            bridged = major < NESTMATE_MAJOR;
                        } else if (nest == null || !inNest(owner, copy.get())) {
                            return Reason.OWNER_ACCESS;
                        }
                    } else if (!model.isAccessible(owner, implName, implementation.lookupDescriptor(), packageName)) {
                        return Reason.OWNER_ACCESS;
                    }
                }
                if (((flags & ClassFile.ACC_STATIC) != 0) != (invocation == Invocation.STATIC)
                        || ownerIsInterface != implementation.isOwnerInterface()
                        || ownerIsInterface && invocation == Invocation.CONSTRUCTOR) {
                    return Reason.SHAPE;
                }
                if (bridged) {
                    Reason reason = unbridgeable();
                    if (reason != null) {
                        return reason;
                    }
                }
                if (!resolves(factoryType, samType, instantiatedType, implType, owner, instance)) {
                    return Reason.UNRESOLVED_TYPE;
                }

                int number = next++;
                String generatedName = name + GENERATED_INFIX + number;
                String bridgeName = bridged ? BRIDGE_PREFIX + number : null;
                if (model.known(generatedName) || classes.size(generatedName + CLASS_SUFFIX) >= 0
                        || bridged && memberNames.contains(bridgeName)) {
                    return Reason.NAME_TAKEN;
                }
                return new SitePlan(home, ClassDesc.ofInternalName(generatedName),
                        new Shape(factoryType, samName, samType, instantiatedType),
                        new Target(implementation.owner(), implementation.isOwnerInterface(), implName,
                                implDescriptor, implType, invocation, (flags & ClassFile.ACC_STATIC) != 0),
                        bridgeName);
            }

            /**
             * Whether the site has the shape {@code LambdaMetafactory} accepts and this step generates: the
             * implementation takes the captured values and then the interface method's arguments, the captured
             * values match exactly, and every argument and the result have a conversion.
             */
            private boolean shaped(MethodTypeDesc factoryType, String samName, MethodTypeDesc samType,
                                   MethodTypeDesc instantiatedType, MethodTypeDesc implType, boolean instance) {
                int captured = factoryType.parameterCount();
                int arity = samType.parameterCount();
                if (implType.parameterCount() != captured + arity || instantiatedType.parameterCount() != arity) {
                    return false;
                }
                if (samName.startsWith("<") || samName.equals(FACTORY_METHOD) && samType.equals(factoryType)) {
                    return false;
                }
                // A captured receiver may be a subclass of the owner; every other captured value is exact.
                int first = instance && captured > 0 ? 1 : 0;
                if (first == 1 && factoryType.parameterType(0).isPrimitive()) {
                    return false;
                }
                for (int i = first; i < captured; i++) {
                    if (!factoryType.parameterType(i).equals(implType.parameterType(i))) {
                        return false;
                    }
                }
                for (int i = 0; i < arity; i++) {
                    if (!Conversions.convertible(samType.parameterType(i), implType.parameterType(captured + i),
                            instantiatedType.parameterType(i))) {
                        return false;
                    }
                }
                ClassDesc expected = samType.returnType();
                if (isVoid(expected)) {
                    return true;
                }
                return Conversions.convertible(implType.returnType(), expected, expected);
            }

            /**
             * Whether a private member's owner, another class than the host, is in the host's nest: a base
             * class of this jar that names the nest host and that the nest host lists.
             */
            private boolean inNest(String owner, ClassPathModel.Copy copy) {
                if (copy.layer() != layer.index() || copy.version() != 0 || !nest.holds(owner)) {
                    return false;
                }
                return owner.equals(nest.name) || nest.name.equals(copy.nestHost());
            }

            /**
             * Why the host cannot take a bridge, or {@code null} when it can: an interface's bridge would have
             * to be public, and a bridge changes the default {@code serialVersionUID} of a serializable class
             * that declares none.
             */
            private Reason unbridgeable() {
                if (!bridgeChecked) {
                    bridgeChecked = true;
                    if (isInterface) {
                        unbridgeable = Reason.JAVA8_INTERFACE;
                    } else if (!memberNames.contains(SERIAL_VERSION_UID) && serializable()) {
                        unbridgeable = Reason.SERIAL_VERSION_UID;
                    }
                }
                return unbridgeable;
            }

            private boolean serializable() {
                Set<String> seen = new HashSet<>();
                if (parsed.superclass().isPresent()
                        && LambdaDesugarer.this.serializable(parsed.superclass().get().asInternalName(), seen)) {
                    return true;
                }
                for (ClassEntry entry : parsed.interfaces()) {
                    if (LambdaDesugarer.this.serializable(entry.asInternalName(), seen)) {
                        return true;
                    }
                }
                return false;
            }

            /**
             * Whether every class the generated class names resolves: the functional interface, which must be
             * an interface, the types it casts to, and a captured receiver that is not the owner itself, which
             * the verifier has to relate to the owner. The owner was resolved with its member.
             */
            private boolean resolves(MethodTypeDesc factoryType, MethodTypeDesc samType,
                                     MethodTypeDesc instantiatedType, MethodTypeDesc implType, String owner,
                                     boolean instance) {
                if (kindOf(internalName(factoryType.returnType())) != INTERFACE) {
                    return false;
                }
                int captured = factoryType.parameterCount();
                if (instance && captured > 0 && !resolvesType(factoryType.parameterType(0))) {
                    return false;
                }
                for (int i = 0; i < samType.parameterCount(); i++) {
                    ClassDesc argument = samType.parameterType(i);
                    ClassDesc functional = instantiatedType.parameterType(i);
                    ClassDesc target = implType.parameterType(captured + i);
                    if (!argument.equals(functional) && !resolvesType(functional)
                            || !argument.equals(target) && !resolvesType(target)) {
                        return false;
                    }
                }
                ClassDesc expected = samType.returnType();
                return implType.returnType().equals(expected) || resolvesType(expected);
            }
        }
    }

    /**
     * What one class's sites became while it is being planned.
     */
    private static final class HostPlan {
        private final int[] left = new int[Reason.values().length];
        private final List<SitePlan> sites = new ArrayList<>();
        private final Map<String, SitePlan[]> sitesByMethod = new HashMap<>();
        private Nest nest;
        private String unitName;
    }
}
