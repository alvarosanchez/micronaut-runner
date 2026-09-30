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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Generates the static service table: the classes that answer Micronaut's service lookups from names the
 * packager computed, instead of from a scan of the class path when the application starts.
 *
 * <p>The table is names only. For each service type it lists the implementation names in the order
 * Micronaut's scan finds them in a runner jar; the classes it adds to the application instantiate them as
 * Micronaut does. It is registered through {@code io.micronaut.core.optim.StaticOptimizations$Loader}, the
 * hook Micronaut reads before it scans.</p>
 *
 * <h2>The order</h2>
 * <p>For a type {@code T}, in micronaut-core {@value #SUPPORTED_RANGE}:</p>
 * <ol>
 *     <li>every layer's {@code META-INF/services/T}, in class-path order, the application layer first. Each
 *     file's names are added in the iteration order of the {@link HashSet} Micronaut parses the file into,
 *     so a name listed by two files appears twice, as Micronaut instantiates it twice;</li>
 *     <li>then the names under {@code META-INF/micronaut/T/}, in the reverse of the order the merged copy at
 *     the archive root stores them in, which is the order a walk of that copy visits them.</li>
 * </ol>
 * <p>{@code BeanDefinitionReference} and {@code BeanConfiguration} are read only from
 * {@code META-INF/micronaut}, so only the second part applies to them.</p>
 *
 * <p>micronaut-core 5.2.x lists the merged copy instead of walking it and finds the same order: a launch of the
 * large benchmark sample on 5.2.2 verified 27 types and 494 names without a mismatch. The range stops at 5.2
 * because the scan of 5.2.x is already cheap, and the paired startup measurement on 5.2.2 did not show a gain
 * its interval separates from zero. Widening the range is a change of {@code END_VERSION} and of the versions
 * {@code StaticServiceTableRuntimeTest} runs on, after such a measurement.</p>
 *
 * <h2>When in doubt, no table</h2>
 * <p>A table that differs from the scan changes which beans an application has, without a word at startup. So
 * every doubt resolves to the scan. No table at all is generated without a micronaut-core in the supported
 * range and of the expected shape, for a micronaut-core that carries its own service index, when another
 * {@code StaticOptimizations$Loader} already supplies service loaders, or when a generated name is taken. A
 * single type is left to the scan ("dynamic") when the table cannot be proven equal to it: a name that is not
 * a class the archive or the JDK has, together with its supertypes; a {@code META-INF/micronaut} entry that
 * has content, which a names-only table would drop; two copies of a service file in one jar. A class file
 * that cannot be read counts as a class that is not there: it never fails the build.</p>
 *
 * <h2>Registration</h2>
 * <p>The table is registered by one line in the application layer's
 * {@code META-INF/services/io.micronaut.core.optim.StaticOptimizations$Loader}. When the application output
 * already has that file, as the output of Micronaut AOT does, the line is put in front of its content.</p>
 *
 * <p>Nothing is loaded: class files are parsed, never defined, and this class never logs.</p>
 *
 * @since 1.0
 */
final class StaticServiceTableGenerator {

    /** The package of the generated classes, a subpackage of the one the launcher leaves to the archive. */
    static final String GENERATED_PACKAGE = IndexFormat.GENERATED_PACKAGE + ".services";

    /** The entry name prefix of the generated classes inside the application layer. */
    static final String GENERATED_PREFIX = GENERATED_PACKAGE.replace('.', '/') + "/";

    /** The class Micronaut's hook instantiates. */
    static final String LOADER_CLASS = GENERATED_PACKAGE + ".RunnerStaticServices";

    /** The data-only class this generator writes for each application. */
    static final String TABLE_CLASS = GENERATED_PACKAGE + ".RunnerServiceTable";

    /** The service file that registers {@link #LOADER_CLASS}. */
    static final String REGISTRATION = "META-INF/services/io.micronaut.core.optim.StaticOptimizations$Loader";

    /** The micronaut-core versions whose scan order this generator reproduces. */
    static final String SUPPORTED_RANGE = "[5.1.10, 5.2)";

    /** The most modified UTF-8 bytes one generated string constant holds; the class file limit is 65,535. */
    static final int MAX_CONSTANT_BYTES = 60_000;

    /** Flag of a type whose implementations are instantiated on the fork-join common pool. */
    static final int FLAG_PARALLEL = 1;

    /** Flag of a type Micronaut reads only from {@code META-INF/micronaut}. */
    static final int FLAG_MICRONAUT_ONLY = 2;

    /** Flag of a type the table does not serve. */
    static final int FLAG_DYNAMIC = 4;

    /** The classes copied into every application that gets a table, bundled by this library's build. */
    private static final String TEMPLATES_RESOURCE = "/META-INF/micronaut-runner/static-services.jar";

    private static final String SERVICES_PREFIX = "META-INF/services/";
    private static final String CLASS_SUFFIX = ".class";
    private static final String CORE_PACKAGE = "io/micronaut/core/io/service/";
    private static final String SOFT_SERVICE_LOADER = CORE_PACKAGE + "SoftServiceLoader";
    private static final String OPTIMIZATIONS = SOFT_SERVICE_LOADER + "$Optimizations";
    private static final String STATIC_SERVICE_LOADER = SOFT_SERVICE_LOADER + "$StaticServiceLoader";
    private static final String STATIC_DEFINITION = SOFT_SERVICE_LOADER + "$StaticDefinition";
    private static final String OPTIMIZATIONS_LOADER = "io/micronaut/core/optim/StaticOptimizations$Loader";

    /**
     * The type micronaut-projects/micronaut-core#13389 proposes. A micronaut-core that has it reads a service
     * index of its own, and gets no table from here.
     */
    private static final String UPSTREAM_INDEX = CORE_PACKAGE + "ServiceIndex";

    private static final String BEAN_DEFINITION_REFERENCE = "io.micronaut.inject.BeanDefinitionReference";
    private static final String BEAN_CONFIGURATION = "io.micronaut.inject.BeanConfiguration";
    private static final String BEAN_INTROSPECTION_REFERENCE = "io.micronaut.core.beans.BeanIntrospectionReference";

    private static final int[] MINIMUM_VERSION = {5, 1, 10};
    private static final int[] END_VERSION = {5, 2, 0};

    /** The feature version every runner jar runs on at least; a versioned class above it may or may not apply. */
    private static final int RUNTIME_BASELINE = 25;

    /** How many dynamic types the report names. */
    private static final int REPORTED_TYPES = 5;

    private static final ClassDesc TABLE_TYPE = ClassDesc.of(TABLE_CLASS);
    private static final ClassDesc ILLEGAL_ARGUMENT = ClassDesc.of("java.lang.IllegalArgumentException");

    private static volatile Map<String, byte[]> templates;

    private final List<LayerIndex> layers;
    private final Map<String, Long> mergedServices;
    private final Map<String, Boolean> presence = new HashMap<>();
    /** What the application layer will hold once the table is added, which the closed world includes. */
    private final Map<String, byte[]> overlay = new HashMap<>();

    private StaticServiceTableGenerator(List<Layer> layers, Map<String, Long> mergedServices) {
        this.layers = new ArrayList<>(layers.size());
        for (Layer layer : layers) {
            this.layers.add(new LayerIndex(layer));
        }
        this.mergedServices = mergedServices;
    }

    /**
     * Generates the table for an application, or explains why it has none.
     *
     * @param layers         the class path: the application layer first, then the nested jars in index order
     * @param mergedServices the size of every file of the merged {@code META-INF/micronaut/} copy, by entry
     *                       name, in the order the archive stores them
     * @return the entries to add to the application layer, or the reason there are none
     * @throws IOException if an entry of a layer cannot be read, or this library carries no table classes
     */
    static Result generate(List<Layer> layers, Map<String, Long> mergedServices) throws IOException {
        if (layers.isEmpty()) {
            throw new IllegalArgumentException("The class path has no application layer");
        }
        return new StaticServiceTableGenerator(layers, mergedServices).run();
    }

    private Result run() throws IOException {
        LayerIndex core = null;
        for (LayerIndex layer : layers) {
            // A plain scan: an application without micronaut-core pays for no index of its entries.
            if (layer.carries(SOFT_SERVICE_LOADER + CLASS_SUFFIX)) {
                core = layer;
                break;
            }
        }
        if (core == null) {
            return Result.none("the application has no micronaut-core");
        }
        String version = core.layer.implementationVersion();
        if (version == null || version.isBlank()) {
            return Result.none("the manifest of " + core.layer.description()
                    + " has no Implementation-Version to tell which micronaut-core it is");
        }
        version = version.trim();
        int[] parsed = parseVersion(version);
        if (parsed == null || compare(parsed, MINIMUM_VERSION) < 0 || compare(parsed, END_VERSION) >= 0) {
            return Result.none("micronaut-core " + version + " is outside the supported range "
                    + SUPPORTED_RANGE);
        }
        if (core.position(UPSTREAM_INDEX + CLASS_SUFFIX) >= 0) {
            return Result.none("micronaut-core " + version + " has its own service index");
        }
        String missing = missingMember(core);
        if (missing != null) {
            return Result.none("micronaut-core " + version + " has no " + missing);
        }

        Map<String, byte[]> generated = new TreeMap<>();
        LayerIndex application = layers.get(0);
        for (Map.Entry<String, byte[]> template : templates().entrySet()) {
            generated.put(template.getKey(), template.getValue());
        }
        String tableName = TABLE_CLASS.replace('.', '/') + CLASS_SUFFIX;
        for (String name : generated.keySet()) {
            if (application.position(name) >= 0) {
                return Result.taken(name);
            }
        }
        if (application.position(tableName) >= 0) {
            return Result.taken(tableName);
        }
        String standDown = standDownReason();
        if (standDown != null) {
            return Result.none(standDown);
        }
        String malformed = malformedMergedName();
        if (malformed != null) {
            return Result.none("the merged Micronaut metadata has the entry '" + malformed
                    + "', which is not a META-INF/micronaut/<type>/<name> file");
        }

        byte[] registration = registration(application);
        overlay.putAll(generated);
        overlay.put(REGISTRATION, registration);

        TreeMap<String, TypePlan> types = collect();
        for (String type : types.keySet()) {
            if (!recordable(type)) {
                return Result.none("the service type '" + type + "' has a name the table cannot record");
            }
        }
        for (TypePlan plan : types.values()) {
            check(plan);
        }

        StringBuilder table = new StringBuilder();
        List<String> slots = new ArrayList<>();
        List<String> dynamic = new ArrayList<>();
        int served = 0;
        for (TypePlan plan : types.values()) {
            if (plan.dynamic) {
                dynamic.add(plan.type);
                appendType(table, plan.type, 0, 0, FLAG_DYNAMIC);
                continue;
            }
            if (plan.names.isEmpty()) {
                // Served by the map's shared empty loader, as any type nothing provides is.
                continue;
            }
            served++;
            appendType(table, plan.type, slots.size(), plan.names.size(), plan.flags);
            slots.addAll(plan.names);
        }
        if (modifiedUtf8Length(table) > MAX_CONSTANT_BYTES) {
            return Result.none("its " + types.size() + " service types do not fit one class file constant");
        }
        List<String> chunks = chunk(slots);
        generated.put(tableName, tableClass(table.toString(), slots.size(), chunks));
        generated.put(REGISTRATION, registration);
        return new Result(Collections.unmodifiableMap(new LinkedHashMap<>(generated)), served, slots.size(),
                List.copyOf(dynamic), version, null, false);
    }

    /**
     * The numeric prefix of a micronaut-core version, which is what the version gate compares.
     *
     * @param version the {@code Implementation-Version}
     * @return major, minor and patch, or {@code null} when the text does not start with {@code major.minor}
     */
    static int[] parseVersion(String version) {
        int[] parts = new int[3];
        int index = 0;
        int length = version.length();
        for (int part = 0; part < parts.length; part++) {
            int start = index;
            long value = 0;
            while (index < length && version.charAt(index) >= '0' && version.charAt(index) <= '9') {
                value = value * 10 + (version.charAt(index) - '0');
                if (value > Integer.MAX_VALUE) {
                    return null;
                }
                index++;
            }
            if (index == start) {
                return null;
            }
            parts[part] = (int) value;
            if (part == parts.length - 1) {
                break;
            }
            if (index < length && version.charAt(index) == '.') {
                index++;
            } else if (part == 0) {
                return null;
            } else {
                // "5.2" is 5.2.0.
                break;
            }
        }
        return parts;
    }

    private static int compare(int[] left, int[] right) {
        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) {
                return Integer.compare(left[i], right[i]);
            }
        }
        return 0;
    }

    /**
     * Checks that micronaut-core has the members the table classes were compiled against.
     *
     * @return the first member that is missing, or {@code null}
     */
    private static String missingMember(LayerIndex core) throws IOException {
        String[][] members = {
            {OPTIMIZATIONS, "<init>", "(Ljava/util/Map;)V"},
            {STATIC_SERVICE_LOADER, "findAll", "(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;"},
            {STATIC_SERVICE_LOADER, "load", "(Ljava/util/function/Predicate;)Ljava/util/List;"},
            {STATIC_SERVICE_LOADER, "load",
                "(Ljava/util/function/Predicate;Ljava/util/function/Predicate;)Ljava/util/List;"},
            {STATIC_DEFINITION, "of",
                "(Ljava/lang/String;Ljava/util/function/Supplier;)L" + STATIC_DEFINITION + ";"},
            {STATIC_DEFINITION, "of", "(Ljava/lang/String;Ljava/lang/Class;)L" + STATIC_DEFINITION + ";"},
            {STATIC_DEFINITION, "load", "()Ljava/lang/Object;"},
            {OPTIMIZATIONS_LOADER, "load", "()Ljava/lang/Object;"},
        };
        Map<String, Set<String>> methods = new HashMap<>();
        for (String[] member : members) {
            String owner = member[0];
            if (!methods.containsKey(owner)) {
                int position = core.position(owner + CLASS_SUFFIX);
                if (position < 0) {
                    return owner.replace('/', '.');
                }
                methods.put(owner, publicMethods(core.layer.read(position)));
            }
            Set<String> declared = methods.get(owner);
            if (declared == null) {
                return "readable " + owner.replace('/', '.');
            }
            if (!declared.contains(member[1] + member[2])) {
                return "public " + owner.replace('/', '.') + "." + member[1] + member[2];
            }
        }
        return null;
    }

    /*
     * The three readers below are the only places that look into a class file. The ClassFile API checks a class
     * file as it is read, not when it is parsed: a constant pool index that points nowhere throws from the
     * accessor that follows it. So each reader parses and reads inside one try, and answers "cannot be read",
     * which its caller resolves to the scan.
     */

    /**
     * The public methods a class declares, as name and descriptor joined.
     *
     * @param classFile the class file
     * @return the methods, or {@code null} when the class file cannot be read
     */
    private static Set<String> publicMethods(byte[] classFile) {
        try {
            Set<String> methods = new HashSet<>();
            for (MethodModel method : ClassFile.of().parse(classFile).methods()) {
                // Every method is read, public or not: one that cannot be makes the class unreadable.
                String signature = method.methodName().stringValue() + method.methodType().stringValue();
                if (method.flags().has(AccessFlag.PUBLIC)) {
                    methods.add(signature);
                }
            }
            return methods;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Whether a class names another anywhere in its constant pool.
     *
     * @param classFile    the class file
     * @param internalName the name looked for, with slashes
     * @return whether it is there, or {@code null} when the class file cannot be read
     */
    private static Boolean mentions(byte[] classFile, String internalName) {
        try {
            for (PoolEntry entry : ClassFile.of().parse(classFile).constantPool()) {
                if (entry instanceof Utf8Entry utf8 && utf8.stringValue().contains(internalName)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The class and the interfaces a class file extends and implements.
     *
     * @param internalName the class the file must be of, with slashes
     * @param classFile    the class file
     * @return the internal names of its supertypes, or {@code null} when the class file is of another class
     *         or cannot be read
     */
    private static List<String> supertypes(String internalName, byte[] classFile) {
        try {
            ClassModel model = ClassFile.of().parse(classFile);
            if (!internalName.equals(model.thisClass().asInternalName())) {
                return null;
            }
            List<String> supertypes = new ArrayList<>();
            if (model.superclass().isPresent()) {
                supertypes.add(model.superclass().get().asInternalName());
            }
            for (ClassEntry implemented : model.interfaces()) {
                supertypes.add(implemented.asInternalName());
            }
            return supertypes;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Looks for a registered {@code StaticOptimizations$Loader} that already supplies service loaders. Micronaut
     * keeps one value per optimization class, so the last loader to supply one would win.
     *
     * @return why no table can be generated, or {@code null} when none of the loaders gets in its way
     */
    private String standDownReason() throws IOException {
        for (LayerIndex layer : layers) {
            Integer position = layer.services().get(REGISTRATION);
            if (position == null) {
                continue;
            }
            if (layer.duplicateServices().contains(REGISTRATION)) {
                return layer.layer.description() + " carries '" + REGISTRATION + "' twice";
            }
            for (String name : serviceLoaderNames(layer.layer.read(position))) {
                String resource = name.replace('.', '/') + CLASS_SUFFIX;
                Boolean supplies = null;
                for (LayerIndex candidate : layers) {
                    int found = candidate.classPosition(resource);
                    if (found == LayerIndex.AMBIGUOUS) {
                        break;
                    }
                    if (found >= 0) {
                        supplies = mentions(candidate.layer.read(found), OPTIMIZATIONS);
                        break;
                    }
                }
                if (supplies == null) {
                    return "the class of the registered StaticOptimizations loader " + name
                            + " is missing or cannot be read";
                }
                if (supplies) {
                    return "the registered StaticOptimizations loader " + name
                            + " already supplies Micronaut's service loaders";
                }
            }
        }
        return null;
    }

    /**
     * Reads a provider-configuration file the way {@link java.util.ServiceLoader} does.
     */
    private static List<String> serviceLoaderNames(byte[] content) throws IOException {
        List<String> names = new ArrayList<>();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            line = line.trim();
            if (!line.isEmpty()) {
                names.add(line);
            }
        }
        return names;
    }

    /**
     * Reads a service file exactly as Micronaut's {@code computeStandardServiceTypeNames} does: UTF-8, line by
     * line, skipping empty lines and lines that start with {@code #}, cutting at the first {@code #} and
     * trimming nothing, into a fresh {@link HashSet} whose iteration order is the order Micronaut instantiates
     * in. That order depends only on the names and on the order they were added in, not on the JVM.
     *
     * @param content the file
     * @return the names, in the set's iteration order
     * @throws IOException never, for an in-memory file
     */
    static List<String> serviceNames(byte[] content) throws IOException {
        Set<String> names = new HashSet<>();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int comment = line.indexOf('#');
            if (comment > -1) {
                line = line.substring(0, comment);
            }
            names.add(line);
        }
        return new ArrayList<>(names);
    }

    /**
     * The first merged entry that is not exactly {@code META-INF/micronaut/<type>/<name>}. Micronaut 5.1 and
     * 5.2 disagree about a file directly under the directory and about a deeper one, so their order is not
     * reproduced.
     */
    private String malformedMergedName() {
        int prefix = IndexFormat.MICRONAUT_SERVICES_PREFIX.length();
        for (String name : mergedServices.keySet()) {
            int slash = name.indexOf('/', prefix);
            if (!name.startsWith(IndexFormat.MICRONAUT_SERVICES_PREFIX) || slash <= prefix
                    || slash == name.length() - 1 || name.indexOf('/', slash + 1) >= 0) {
                return name;
            }
        }
        return null;
    }

    /**
     * The registration file the application layer ends up with: the table's line, then the application's own
     * file when it has one.
     *
     * <p>The line goes first, and the application layer is first on the class path, so the table is the first
     * optimization Micronaut sets. Micronaut refuses an optimization that was read before it was set, and it
     * does so inside a static initialiser: had another loader of the application run first and touched
     * {@code SoftServiceLoader}, setting the table after it would stop the application from starting.</p>
     */
    private static byte[] registration(LayerIndex application) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write((LOADER_CLASS + "\n").getBytes(StandardCharsets.UTF_8));
        int existing = application.position(REGISTRATION);
        if (existing >= 0) {
            byte[] content = application.layer.read(existing);
            bytes.write(content);
            if (content.length > 0 && content[content.length - 1] != '\n') {
                bytes.write('\n');
            }
        }
        return bytes.toByteArray();
    }

    /**
     * Lists every service type of the class path with its names in scan order.
     */
    private TreeMap<String, TypePlan> collect() throws IOException {
        TreeMap<String, TypePlan> types = new TreeMap<>();
        for (int i = 0; i < layers.size(); i++) {
            LayerIndex layer = layers.get(i);
            Map<String, Integer> files = new LinkedHashMap<>(layer.services());
            if (i == 0) {
                files.putIfAbsent(REGISTRATION, -1);
            }
            for (Map.Entry<String, Integer> file : files.entrySet()) {
                String type = file.getKey().substring(SERVICES_PREFIX.length());
                TypePlan plan = types.computeIfAbsent(type, TypePlan::new);
                plan.serviceFiles++;
                if (layer.duplicateServices().contains(file.getKey())) {
                    plan.dynamic = true;
                    continue;
                }
                byte[] content = i == 0 && overlay.containsKey(file.getKey())
                        ? overlay.get(file.getKey())
                        : layer.layer.read(file.getValue());
                plan.names.addAll(serviceNames(content));
            }
        }
        int prefix = IndexFormat.MICRONAUT_SERVICES_PREFIX.length();
        Map<String, List<String>> stored = new LinkedHashMap<>();
        for (Map.Entry<String, Long> merged : mergedServices.entrySet()) {
            String name = merged.getKey();
            int slash = name.indexOf('/', prefix);
            String type = name.substring(prefix, slash);
            stored.computeIfAbsent(type, key -> new ArrayList<>()).add(name.substring(slash + 1));
            if (merged.getValue() != 0) {
                // A names-only table would drop what the entry says.
                types.computeIfAbsent(type, TypePlan::new).dynamic = true;
            }
        }
        for (Map.Entry<String, List<String>> merged : stored.entrySet()) {
            TypePlan plan = types.computeIfAbsent(merged.getKey(), TypePlan::new);
            List<String> names = merged.getValue();
            for (int i = names.size() - 1; i >= 0; i--) {
                plan.names.add(names.get(i));
            }
        }
        return types;
    }

    /**
     * Applies the per-type rules: a type is served completely or not at all.
     */
    private void check(TypePlan plan) throws IOException {
        boolean micronautOnly = BEAN_DEFINITION_REFERENCE.equals(plan.type)
                || BEAN_CONFIGURATION.equals(plan.type);
        if (micronautOnly) {
            plan.flags |= FLAG_MICRONAUT_ONLY;
            if (plan.serviceFiles > 0) {
                // Micronaut never reads these through META-INF/services; a caller that does is left alone.
                plan.dynamic = true;
            }
        }
        if (BEAN_DEFINITION_REFERENCE.equals(plan.type) || BEAN_INTROSPECTION_REFERENCE.equals(plan.type)) {
            plan.flags |= FLAG_PARALLEL;
        }
        if (plan.dynamic) {
            return;
        }
        for (String name : plan.names) {
            if (!recordable(name)) {
                plan.dynamic = true;
                return;
            }
            // Micronaut ignores a class it cannot load for these two types, and so does the table.
            if (!micronautOnly && !(binaryName(name) && present(name.replace('.', '/')))) {
                plan.dynamic = true;
                return;
            }
        }
    }

    /** Whether a name can be one line of the table: not empty, no control character, and short enough. */
    private static boolean recordable(String name) {
        if (name.isEmpty() || modifiedUtf8Length(name) > MAX_CONSTANT_BYTES) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < ' ') {
                return false;
            }
        }
        return true;
    }

    /** Whether a name is a binary class name: identifiers joined by dots, such as {@code com.example.A$B}. */
    static boolean binaryName(String name) {
        boolean start = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.') {
                if (start) {
                    return false;
                }
                start = true;
            } else if (start ? Character.isJavaIdentifierStart(c) : Character.isJavaIdentifierPart(c)) {
                start = false;
            } else {
                return false;
            }
        }
        return !start;
    }

    /**
     * Whether a class, and every class and interface it extends or implements, is one the application can
     * load: from the first layer that holds it, otherwise from the JDK that runs the build.
     *
     * @param internalName the class, with slashes
     * @return whether the class and its supertypes are all there
     */
    private boolean present(String internalName) throws IOException {
        Boolean known = presence.get(internalName);
        if (known != null) {
            return known;
        }
        // A class that is its own supertype does not load; while it is being resolved the answer is "yes".
        presence.put(internalName, Boolean.TRUE);
        boolean result = resolve(internalName);
        presence.put(internalName, result);
        return result;
    }

    private boolean resolve(String internalName) throws IOException {
        String resource = internalName + CLASS_SUFFIX;
        byte[] classFile = overlay.get(resource);
        if (classFile == null) {
            for (LayerIndex layer : layers) {
                int position = layer.classPosition(resource);
                if (position == LayerIndex.AMBIGUOUS) {
                    return false;
                }
                if (position >= 0) {
                    classFile = layer.layer.read(position);
                    break;
                }
            }
        }
        if (classFile == null) {
            return ClassLoader.getPlatformClassLoader().getResource(resource) != null;
        }
        List<String> supertypes = supertypes(internalName, classFile);
        if (supertypes == null) {
            return false;
        }
        for (String supertype : supertypes) {
            if (!present(supertype)) {
                return false;
            }
        }
        return true;
    }

    private static void appendType(StringBuilder table, String type, int first, int count, int flags) {
        if (table.length() > 0) {
            table.append('\n');
        }
        table.append(type).append('\t').append(first).append('\t').append(count).append('\t').append(flags);
    }

    /**
     * Joins the slot names with {@code \n} into as few constants as fit {@link #MAX_CONSTANT_BYTES} each.
     *
     * @param slots the names, in slot order
     * @return the chunks, none of them empty
     */
    static List<String> chunk(List<String> slots) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bytes = 0;
        for (String name : slots) {
            int length = modifiedUtf8Length(name);
            if (current.length() > 0 && bytes + 1 + length > MAX_CONSTANT_BYTES) {
                chunks.add(current.toString());
                current.setLength(0);
                bytes = 0;
            }
            if (current.length() > 0) {
                current.append('\n');
                bytes++;
            }
            current.append(name);
            bytes += length;
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /** The length of a string as a class file stores it. */
    static int modifiedUtf8Length(CharSequence text) {
        int length = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0001 && c <= 0x007F) {
                length++;
            } else if (c <= 0x07FF) {
                length += 2;
            } else {
                length += 3;
            }
        }
        return length;
    }

    /**
     * Writes {@code RunnerServiceTable}: three constants and a switch over the name chunks, nothing else.
     */
    private static byte[] tableClass(String types, int slotCount, List<String> chunks) {
        for (String chunk : chunks) {
            if (modifiedUtf8Length(chunk) > 0xFFFF) {
                throw new IllegalArgumentException("A service name is longer than a class file constant");
            }
        }
        return ClassFile.of().build(TABLE_TYPE, builder -> {
            // Explicitly, as EntryStubGenerator does: the default is the version of the JDK that packages.
            builder.withVersion(EntryStubGenerator.CLASS_FILE_MAJOR_VERSION,
                    EntryStubGenerator.CLASS_FILE_MINOR_VERSION);
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
            int constant = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL;
            builder.withField("TYPES", ConstantDescs.CD_String, field -> field.withFlags(constant)
                    .with(ConstantValueAttribute.of(types)));
            builder.withField("SLOT_COUNT", ConstantDescs.CD_int, field -> field.withFlags(constant)
                    .with(ConstantValueAttribute.of(slotCount)));
            builder.withField("NAME_CHUNKS", ConstantDescs.CD_int, field -> field.withFlags(constant)
                    .with(ConstantValueAttribute.of(chunks.size())));
            builder.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PRIVATE,
                    code -> code.aload(0)
                            .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                    ConstantDescs.MTD_void)
                            .return_());
            namesMethod(builder, chunks);
        });
    }

    private static void namesMethod(ClassBuilder builder, List<String> chunks) {
        builder.withMethodBody("names", MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_int),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                    Label unknown = code.newLabel();
                    if (!chunks.isEmpty()) {
                        List<Label> labels = new ArrayList<>(chunks.size());
                        List<SwitchCase> cases = new ArrayList<>(chunks.size());
                        for (int i = 0; i < chunks.size(); i++) {
                            Label label = code.newLabel();
                            labels.add(label);
                            cases.add(SwitchCase.of(i, label));
                        }
                        code.iload(0).tableswitch(0, chunks.size() - 1, unknown, cases);
                        for (int i = 0; i < chunks.size(); i++) {
                            code.labelBinding(labels.get(i)).ldc(chunks.get(i)).areturn();
                        }
                    }
                    unknownChunk(code.labelBinding(unknown));
                });
    }

    private static void unknownChunk(CodeBuilder code) {
        code.new_(ILLEGAL_ARGUMENT)
                .dup()
                .ldc("No such chunk of the static service table")
                .invokespecial(ILLEGAL_ARGUMENT, ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String))
                .athrow();
    }

    /**
     * The classes this library carries for the application, by entry name. The generated table class is not
     * among them.
     *
     * @return the class files
     * @throws IOException if the library was built without them
     */
    static Map<String, byte[]> templates() throws IOException {
        Map<String, byte[]> loaded = templates;
        if (loaded != null) {
            return loaded;
        }
        Map<String, byte[]> classes = new TreeMap<>();
        try (InputStream in = StaticServiceTableGenerator.class.getResourceAsStream(TEMPLATES_RESOURCE)) {
            if (in == null) {
                throw new IOException("The packaging library carries no static service classes at "
                        + TEMPLATES_RESOURCE + "; it was built incorrectly");
            }
            try (ZipInputStream zip = new ZipInputStream(in)) {
                for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.startsWith(GENERATED_PREFIX) && name.endsWith(CLASS_SUFFIX)) {
                        classes.put(name, zip.readAllBytes());
                    }
                }
            }
        }
        if (!classes.containsKey(LOADER_CLASS.replace('.', '/') + CLASS_SUFFIX)) {
            throw new IOException("The packaging library's " + TEMPLATES_RESOURCE + " has no " + LOADER_CLASS
                    + "; it was built incorrectly");
        }
        loaded = Collections.unmodifiableMap(classes);
        templates = loaded;
        return loaded;
    }

    /**
     * One entry of the class path, as the generator reads it: its entry names in stored order and, on
     * demand, the content of an entry. Nothing is read until the generator asks.
     */
    interface Layer {

        /**
         * @return what to call the layer in a message
         */
        String description();

        /**
         * @return the number of entries, directories included
         */
        int size();

        /**
         * @param position an entry, from {@code 0} to {@link #size()} - 1
         * @return its name; a directory's ends with a slash
         */
        String name(int position);

        /**
         * @param position an entry that is not a directory
         * @return its content, uncompressed
         * @throws IOException if it cannot be read
         */
        byte[] read(int position) throws IOException;

        /**
         * @return the {@code Implementation-Version} of the layer's manifest, or {@code null}
         * @throws IOException if the manifest cannot be read
         */
        String implementationVersion() throws IOException;

        /**
         * @return whether the class loader selects the layer's {@code META-INF/versions/} entries
         */
        boolean multiRelease();
    }

    /**
     * What one run produced.
     *
     * @param entries      the entries to put into the application layer, by name: the generated classes and
     *                     the registration file, which replaces the application's own; empty when there is no
     *                     table
     * @param typeCount    the number of service types the table serves
     * @param slotCount    the number of implementation names it lists, {@code 0} when there is no table
     * @param dynamicTypes the types it leaves to Micronaut's scan
     * @param coreVersion  the micronaut-core the table was generated for, or {@code null} when there is none
     * @param reason       why there is no table, or {@code null} when there is one
     * @param warn         whether the reason deserves a warning rather than a line of information
     */
    record Result(Map<String, byte[]> entries, int typeCount, int slotCount, List<String> dynamicTypes,
                  String coreVersion, String reason, boolean warn) {

        static Result none(String reason) {
            return new Result(Map.of(), 0, 0, List.of(), null, reason, false);
        }

        private static Result taken(String name) {
            return new Result(Map.of(), 0, 0, List.of(), null,
                    "the application output already carries '" + name + "'", true);
        }

        /**
         * @return whether a table was generated
         */
        boolean generated() {
            return reason == null;
        }

        /**
         * What the table covers, for the build log: {@code N types, M slots (...)}.
         *
         * @return the description
         */
        String describe() {
            StringBuilder text = new StringBuilder();
            text.append(typeCount).append(" types, ").append(slotCount).append(" slots (");
            if (dynamicTypes.isEmpty()) {
                text.append("none left to Micronaut's scan");
            } else {
                text.append(dynamicTypes.size()).append(" left to Micronaut's scan: ");
                for (int i = 0; i < dynamicTypes.size() && i < REPORTED_TYPES; i++) {
                    text.append(i == 0 ? "" : ", ").append(dynamicTypes.get(i));
                }
                if (dynamicTypes.size() > REPORTED_TYPES) {
                    text.append(", ...");
                }
            }
            return text.append(')').toString();
        }
    }

    /** One service type while its table line is being worked out. */
    private static final class TypePlan {

        private final String type;
        private final List<String> names = new ArrayList<>();
        private int serviceFiles;
        private int flags;
        private boolean dynamic;

        private TypePlan(String type) {
            this.type = type;
        }
    }

    /**
     * The names of one layer, indexed once: where each entry is, which service files it has, and which
     * versioned copies of a class a multi-release layer carries.
     */
    private static final class LayerIndex {

        /** A class the layer has in a versioned copy that only some runtimes select. */
        private static final int AMBIGUOUS = -2;

        private final Layer layer;
        private Map<String, Integer> positions;
        private Map<String, Integer> services;
        private Set<String> duplicateServices;
        private Map<String, TreeMap<Integer, Integer>> versions;

        private LayerIndex(Layer layer) {
            this.layer = layer;
        }

        private void index() {
            if (positions != null) {
                return;
            }
            int size = layer.size();
            Map<String, Integer> byName = new HashMap<>(Math.max(16, size * 2));
            Map<String, Integer> files = new LinkedHashMap<>();
            Set<String> duplicates = new HashSet<>();
            Map<String, TreeMap<Integer, Integer>> versioned = new HashMap<>();
            boolean multiRelease = layer.multiRelease();
            for (int position = 0; position < size; position++) {
                String name = layer.name(position);
                if (name.endsWith("/")) {
                    continue;
                }
                // The last copy of a name wins, as it does in the launcher's index and in java.util.zip.
                byName.put(name, position);
                if (name.startsWith(SERVICES_PREFIX) && name.length() > SERVICES_PREFIX.length()
                        && name.indexOf('/', SERVICES_PREFIX.length()) < 0) {
                    if (files.put(name, position) != null) {
                        duplicates.add(name);
                    }
                } else if (multiRelease && name.endsWith(CLASS_SUFFIX)) {
                    int version = IndexWriter.versionOf(name);
                    if (version > 0) {
                        String path = IndexWriter.pathOf(name);
                        if (!path.startsWith("META-INF/")) {
                            versioned.computeIfAbsent(path, key -> new TreeMap<>()).put(version, position);
                        }
                    }
                }
            }
            positions = byName;
            services = files;
            duplicateServices = duplicates;
            versions = versioned;
        }

        /** Whether the layer has an entry of this name, found without indexing the layer. */
        private boolean carries(String name) {
            if (positions != null) {
                return positions.containsKey(name);
            }
            int size = layer.size();
            for (int position = 0; position < size; position++) {
                if (name.equals(layer.name(position))) {
                    return true;
                }
            }
            return false;
        }

        /** Where an entry is, or {@code -1}. */
        private int position(String name) {
            index();
            Integer position = positions.get(name);
            return position == null ? -1 : position;
        }

        /**
         * Where the class file the application loads for a name is: the versioned copy the runner's baseline
         * selects, otherwise the base entry.
         *
         * @return the position, {@code -1} when the layer has no such class, or {@link #AMBIGUOUS} when it has a
         *         copy for a release above the baseline, which only some runtimes load
         */
        private int classPosition(String resource) {
            index();
            TreeMap<Integer, Integer> copies = versions.get(resource);
            if (copies != null) {
                if (copies.lastKey() > RUNTIME_BASELINE) {
                    return AMBIGUOUS;
                }
                return copies.lastEntry().getValue();
            }
            return position(resource);
        }

        /** The layer's {@code META-INF/services/<type>} files, by entry name, in stored order. */
        private Map<String, Integer> services() {
            index();
            return services;
        }

        /** The service files the layer stores more than once. */
        private Set<String> duplicateServices() {
            index();
            return duplicateServices;
        }
    }
}
