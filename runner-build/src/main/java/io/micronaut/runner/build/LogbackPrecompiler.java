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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;

/**
 * Compiles an application's {@code logback.xml} into a Logback {@code Configurator} at packaging time, so that the
 * application does not parse XML and run Joran on the main thread at every start.
 *
 * <p>It is the interim, Runner-only home of a build-tool-neutral engine that the Micronaut build plugins may
 * eventually share; nothing in it needs Runner-only knowledge. It fails closed: whenever it cannot prove that the
 * generated code reproduces Joran's result exactly, it generates nothing, logs why, and Logback reads
 * {@code logback.xml} with Joran at startup exactly as it would without Micronaut Runner.</p>
 *
 * <h2>What it generates</h2>
 * <p>Three entries of the application layer, under {@value #PACKAGE_PATH}: a {@code LogbackConfigurator} emitted
 * with the ClassFile API, the {@code JoranFallback} class copied byte for byte from the front end jar this library
 * carries, and a {@code META-INF/services/ch.qos.logback.classic.spi.Configurator} file naming the configurator.
 * {@code logback.xml} stays in the archive for the fallbacks. At runtime the configurator, on every call:</p>
 * <ol>
 *     <li>hands over to Logback's own default lookup when {@code -Dlogback.configurationFile} is set;</li>
 *     <li>on a second or later call (Micronaut's {@code LoggingSystem.refresh()}), configures from the
 *     {@code logger.config} system property, else the {@code LOGGER_CONFIG} or {@code LOGBACK_CONFIGURATIONFILE}
 *     environment variable, when one is set, as Micronaut does without a configurator;</li>
 *     <li>hands over to Logback's default lookup when {@code logback.debug} or {@code logback.statusListenerClass}
 *     asks for Logback's status output, or {@code micronaut.runner.logback.precompiled} is {@code false};</li>
 *     <li>otherwise applies the configuration literally, in Joran's order, and returns
 *     {@code DO_NOT_INVOKE_NEXT_IF_ANY}.</li>
 * </ol>
 *
 * <h2>Build policy</h2>
 * <p>The packager never loads, initialises or runs application classes. This class is the one exception to "no
 * library code either": its front end runs Logback and slf4j-api classes, and only those, in an isolated class
 * loader whose parent is the platform class loader, and instantiates only the {@code ch.qos.logback.*} classes the
 * configuration names. It never starts an appender and never opens a file or a stream.</p>
 *
 * <h2>When it generates nothing</h2>
 * <p>When {@link RunnerJarSpec#precompileLogback()} is {@code false}; when logback-classic, logback-core or
 * slf4j-api is missing, the application layer carries Logback or SLF4J classes of its own, or either Logback
 * version is missing, differs from the other or is outside {@value #MINIMUM_VERSION} to {@value #VERSION_LIMIT}
 * (exclusive); when there is no {@code logback.xml}, or any layer has a {@code logback-test.xml}, a
 * {@code logback.groovy} or a versioned Logback file; when any layer already registers a {@code Configurator}
 * service, which covers the application's own configurator and Micronaut AOT's; when a packaged
 * {@code application*} or {@code bootstrap*} file might set {@code logger.config} or
 * {@code logback.configurationFile}, which the generated code cannot see; and when the front end finds the file
 * outside its literal subset. Each of these logs one informational line with the reason. A generated name that
 * is already taken, or an unexpected failure, is a warning instead, and the build still succeeds.</p>
 */
final class LogbackPrecompiler {

    /** The package of the generated classes, a subpackage of the one the launcher never delegates. */
    static final String PACKAGE = IndexFormat.GENERATED_PACKAGE + ".logback";

    /** The binary name of the generated configurator. */
    static final String CONFIGURATOR_CLASS = PACKAGE + ".LogbackConfigurator";

    /** The binary name of the class every Joran path goes through. */
    static final String FALLBACK_CLASS = PACKAGE + ".JoranFallback";

    /** The entry names of the generated classes in the application layer. */
    static final String PACKAGE_PATH = "io/micronaut/runner/generated/logback/";

    static final String CONFIGURATOR_ENTRY = PACKAGE_PATH + "LogbackConfigurator.class";

    static final String FALLBACK_ENTRY = PACKAGE_PATH + "JoranFallback.class";

    /** The service file that makes Logback, and Micronaut's refresh, find the configurator. */
    static final String SERVICE_ENTRY = "META-INF/services/ch.qos.logback.classic.spi.Configurator";

    /** The lowest Logback version the generated code is tested against. */
    static final String MINIMUM_VERSION = "1.5.37";

    /** The first Logback version it is not. */
    static final String VERSION_LIMIT = "1.6";

    /**
     * Applied to every description the front end returns before code is emitted from it. Tests replace it to
     * force a front-end or emitter failure; it is never set in production.
     */
    static volatile UnaryOperator<Map<String, Object>> descriptionHook = UnaryOperator.identity();

    /** The front end, which this library carries as a resource and never puts on its own class path. */
    private static final String FRONTEND_RESOURCE = "/META-INF/micronaut-runner/logback-frontend.jar";

    private static final String FRONTEND_CLASS = "io.micronaut.runner.build.logback.LogbackFrontend";

    private static final int IR_VERSION = 1;

    private static final String LOGBACK_XML = "logback.xml";

    private static final String CLASSIC_MARKER = "ch/qos/logback/classic/LoggerContext.class";

    private static final String CORE_MARKER = "ch/qos/logback/core/Context.class";

    private static final String SLF4J_MARKER = "org/slf4j/ILoggerFactory.class";

    private static final Pattern VERSIONED_LOGBACK_FILE =
            Pattern.compile("META-INF/versions/[^/]+/logback(-test)?\\.xml");

    private static final Pattern PACKAGED_CONFIGURATION = Pattern.compile(
            "(config/)?(application|bootstrap)[^/]*\\.(properties|yml|yaml|json|toml|groovy)");

    private static final Pattern LOGGER_KEY_LINE = Pattern.compile("(?m)^\\s*\"?logger\"?\\s*[:={]");

    private static final Pattern CONFIG_KEY_LINE = Pattern.compile("(?m)^\\s*\"?config\"?\\s*[:=]");

    private LogbackPrecompiler() {
    }

    /**
     * Precompiles the first {@code logback.xml} on the class path of a runner jar, or reports why it does not: one
     * informational line in every case, or one warning when a generated name is taken or something failed.
     *
     * @param requested whether {@link RunnerJarSpec#precompileLogback()} asks for it
     * @param layers    the application layer, then every dependency in class-path order
     * @param taken     the names the application layer already holds
     * @param work      the build's work directory, where the front end jar is unpacked
     * @param logger    where the informational line goes
     * @param warnings  where the warning goes, which also reaches {@link RunnerJarResult#warnings()}
     * @return the entries to add to the application layer, in order; empty when nothing was generated
     */
    static Map<String, byte[]> precompile(boolean requested, List<Layer> layers, Collection<String> taken, Path work,
                                          BuildLogger logger, Consumer<String> warnings) {
        Outcome outcome = decide(requested, layers, taken, work);
        if (outcome.warning() != null) {
            warnings.accept(outcome.warning());
        } else {
            logger.info(outcome.message());
        }
        return outcome.entries();
    }

    /**
     * Whether a Logback version is in {@code [MINIMUM_VERSION, VERSION_LIMIT)}. Only plain numeric versions are:
     * a qualified one, such as a snapshot, was never tested.
     *
     * @param version an {@code Implementation-Version}
     * @return whether the generated code is tested against it
     */
    static boolean supported(String version) {
        if (!version.matches("\\d+(\\.\\d+)*")) {
            return false;
        }
        return compare(version, MINIMUM_VERSION) >= 0 && compare(version, VERSION_LIMIT) < 0;
    }

    private static int compare(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? Long.parseLong(a[i]) : 0;
            long y = i < b.length ? Long.parseLong(b[i]) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    private static Outcome decide(boolean requested, List<Layer> layers, Collection<String> taken, Path work) {
        long start = System.nanoTime();
        if (!requested) {
            return Outcome.standDown("the precompileLogback option is false");
        }
        Survey survey;
        try {
            survey = Survey.of(layers);
            String reason = survey.standDownReason();
            if (reason != null) {
                return Outcome.standDown(reason);
            }
            reason = packagedConfigurationReason(layers.get(0));
            if (reason != null) {
                return Outcome.standDown(reason);
            }
        } catch (IOException | RuntimeException e) {
            return Outcome.failure("the class path could not be read", e);
        }
        for (String name : List.of(CONFIGURATOR_ENTRY, FALLBACK_ENTRY, SERVICE_ENTRY)) {
            if (taken.contains(name)) {
                return Outcome.warning("The application output already carries '" + name + "'; no Logback"
                        + " configuration was precompiled and Logback will configure itself with Joran at startup");
            }
        }
        Layer source = layers.get(survey.logbackXml);
        try {
            Path frontEnd = work.resolve("logback-frontend.jar");
            try (InputStream in = LogbackPrecompiler.class.getResourceAsStream(FRONTEND_RESOURCE)) {
                if (in == null) {
                    throw new IOException("the packaging library carries no Logback front end at "
                            + FRONTEND_RESOURCE + "; it was built incorrectly");
                }
                Files.copy(in, frontEnd, StandardCopyOption.REPLACE_EXISTING);
            }
            byte[] fallback;
            try (ZipReader reader = ZipReader.open(frontEnd)) {
                fallback = reader.read(reader.entry(FALLBACK_ENTRY).orElseThrow(
                        () -> new IOException("the Logback front end carries no " + FALLBACK_ENTRY)));
            }
            URL[] path = {
                frontEnd.toUri().toURL(),
                layers.get(survey.classic).source().toUri().toURL(),
                layers.get(survey.core).source().toUri().toURL(),
                layers.get(survey.slf4j).source().toUri().toURL()
            };
            Map<String, byte[]> entries;
            Map<String, Object> description;
            try (URLClassLoader loader = new URLClassLoader("micronaut-runner-logback-frontend", path,
                    ClassLoader.getPlatformClassLoader())) {
                description = descriptionHook.apply(frontEnd(loader).apply(source.read(LOGBACK_XML)));
                if (!Integer.valueOf(IR_VERSION).equals(description.get("irVersion"))) {
                    throw new IllegalStateException("the Logback front end answered with description version "
                            + description.get("irVersion") + ", not " + IR_VERSION);
                }
                Object rejection = description.get("rejection");
                if (rejection != null) {
                    return Outcome.standDown("logback.xml (" + source.description() + ") is outside what the"
                            + " precompiler can reproduce exactly: " + rejection);
                }
                entries = Emitter.emit(description, fallback, loader);
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            int appenders = (Integer) description.get("appenders");
            int patterns = (Integer) description.get("patterns");
            return new Outcome(entries, "Precompiled logback.xml (" + source.description() + ") into "
                    + CONFIGURATOR_CLASS + ": " + appenders + (appenders == 1 ? " appender, " : " appenders, ")
                    + patterns + (patterns == 1 ? " pattern, " : " patterns, ") + millis + " ms", null);
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Outcome.failure("it could not be compiled", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Function<byte[], Map<String, Object>> frontEnd(ClassLoader loader)
            throws ReflectiveOperationException {
        return (Function<byte[], Map<String, Object>>) Class.forName(FRONTEND_CLASS, true, loader)
                .getConstructor().newInstance();
    }

    /**
     * Stands down when a packaged configuration file of the application might set {@code logger.config} or
     * {@code logback.configurationFile}: Micronaut's refresh applies such a location only when no
     * {@code Configurator} service is registered, and the generated code cannot see it. A false positive only
     * costs the optimisation.
     */
    private static String packagedConfigurationReason(Layer application) throws IOException {
        for (String name : application.names()) {
            if (!PACKAGED_CONFIGURATION.matcher(name).matches()) {
                continue;
            }
            byte[] content = application.read(name);
            String text = new String(content, StandardCharsets.UTF_8);
            boolean sets;
            if (name.endsWith(".properties")) {
                Properties properties = new Properties();
                properties.load(new ByteArrayInputStream(content));
                sets = false;
                for (String key : properties.stringPropertyNames()) {
                    String normalized = key.toLowerCase(Locale.ROOT).replace("-", "");
                    sets |= normalized.equals("logger.config") || normalized.equals("logback.configurationfile");
                }
            } else {
                String lower = text.toLowerCase(Locale.ROOT);
                sets = lower.contains("configurationfile") || lower.contains("configuration-file")
                        || lower.contains("logger.config")
                        || LOGGER_KEY_LINE.matcher(text).find() && CONFIG_KEY_LINE.matcher(text).find();
            }
            if (sets) {
                return "the packaged " + name + " may set logger.config or logback.configurationFile, which"
                        + " Micronaut applies only without a Configurator service";
            }
        }
        return null;
    }

    /**
     * One layer of the class path, as the runner class loader searches it.
     *
     * @param description how the log names it: {@code application layer}, or the dependency's file name
     * @param source      the dependency's own file, or {@code null} for the application layer
     * @param manifest    the dependency's manifest, or {@code null}
     * @param names       every entry name the layer holds
     * @param reader      reads one entry
     */
    record Layer(String description, Path source, Manifest manifest, Collection<String> names,
                 EntryReader reader) {

        /**
         * The application layer.
         *
         * @param names  its entry names
         * @param reader reads one of them
         * @return the layer
         */
        static Layer application(Collection<String> names, EntryReader reader) {
            return new Layer("application layer", null, null, names, reader);
        }

        /**
         * A staged dependency, read from its nested jar and named after the dependency itself.
         *
         * @param dependency the dependency, whose file goes on the front end's class path
         * @param nested     its nested jar: the repacked copy in STORED, the dependency in PRESERVE
         * @param manifest   its manifest, or {@code null}
         * @param entries    the entries of its nested jar
         * @return the layer
         */
        static Layer of(Dependency dependency, Path nested, Manifest manifest, List<ZipEntryInfo> entries) {
            List<String> names = new ArrayList<>(entries.size());
            for (ZipEntryInfo entry : entries) {
                names.add(entry.name());
            }
            return new Layer(dependency.fileName(), dependency.path(), manifest, names, name -> {
                try (ZipReader reader = ZipReader.open(nested)) {
                    return reader.read(reader.entry(name).orElseThrow(
                            () -> new IOException(nested + " has no " + name)));
                }
            });
        }

        byte[] read(String name) throws IOException {
            return reader.read(name);
        }

        String implementationVersion() {
            return manifest == null ? null
                    : manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        }
    }

    /** Reads the content of one entry of a layer. */
    @FunctionalInterface
    interface EntryReader {

        /**
         * Reads one entry.
         *
         * @param name the entry name
         * @return its content
         * @throws IOException if it cannot be read
         */
        byte[] read(String name) throws IOException;
    }

    /**
     * What a precompilation decided.
     *
     * @param entries the entries to add to the application layer, in order; empty when nothing was generated
     * @param message the informational line to log, or {@code null} when {@code warning} is set
     * @param warning the warning to report, or {@code null}
     */
    record Outcome(Map<String, byte[]> entries, String message, String warning) {

        static Outcome standDown(String reason) {
            return new Outcome(Map.of(), "No Logback configuration was precompiled because " + reason
                    + "; Logback will configure itself with Joran at startup", null);
        }

        static Outcome warning(String warning) {
            return new Outcome(Map.of(), null, warning);
        }

        static Outcome failure(String what, Throwable cause) {
            return warning("No Logback configuration was precompiled because " + what + ": " + cause
                    + "; Logback will configure itself with Joran at startup");
        }
    }

    /** One pass over every name of every layer, resolved as the runner class loader resolves them. */
    private static final class Survey {

        private final List<Layer> layers;
        private int classic = -1;
        private int core = -1;
        private int slf4j = -1;
        private int logbackXml = -1;
        private String applicationLogging;
        private String unsupportedFile;
        private String configurator;

        private Survey(List<Layer> layers) {
            this.layers = layers;
        }

        static Survey of(List<Layer> layers) {
            Survey survey = new Survey(layers);
            for (int i = 0; i < layers.size(); i++) {
                for (String name : layers.get(i).names()) {
                    survey.visit(i, name);
                }
            }
            return survey;
        }

        private void visit(int layer, String name) {
            if (layer == 0 && applicationLogging == null && name.endsWith(".class")
                    && (name.startsWith("ch/qos/logback/") || name.startsWith("org/slf4j/"))) {
                applicationLogging = name;
            }
            if (classic < 0 && name.equals(CLASSIC_MARKER)) {
                classic = layer;
            } else if (core < 0 && name.equals(CORE_MARKER)) {
                core = layer;
            } else if (slf4j < 0 && name.equals(SLF4J_MARKER)) {
                slf4j = layer;
            } else if (logbackXml < 0 && name.equals(LOGBACK_XML)) {
                logbackXml = layer;
            } else if (unsupportedFile == null && (name.equals("logback-test.xml") || name.equals("logback.groovy")
                    || VERSIONED_LOGBACK_FILE.matcher(name).matches())) {
                unsupportedFile = layers.get(layer).description() + " has " + name;
            } else if (configurator == null && name.equals(SERVICE_ENTRY)) {
                configurator = layers.get(layer).description();
            }
        }

        String standDownReason() {
            if (applicationLogging != null) {
                return "the application layer carries its own Logback or SLF4J class " + applicationLogging;
            }
            if (classic < 0) {
                return "logback-classic is not on the class path";
            }
            if (core < 0) {
                return "logback-core is not on the class path";
            }
            if (slf4j < 0) {
                return "slf4j-api is not on the class path";
            }
            String classicVersion = layers.get(classic).implementationVersion();
            String coreVersion = layers.get(core).implementationVersion();
            if (classicVersion == null || coreVersion == null) {
                return "logback-classic or logback-core declares no Implementation-Version";
            }
            if (!classicVersion.equals(coreVersion)) {
                return "logback-classic " + classicVersion + " and logback-core " + coreVersion + " differ";
            }
            if (!supported(classicVersion)) {
                return "Logback " + classicVersion + " is outside the tested range [" + MINIMUM_VERSION + ", "
                        + VERSION_LIMIT + ")";
            }
            if (logbackXml < 0) {
                return "there is no logback.xml";
            }
            if (unsupportedFile != null) {
                return unsupportedFile;
            }
            if (configurator != null) {
                return configurator + " already registers a Logback Configurator (" + SERVICE_ENTRY + ")";
            }
            return null;
        }
    }

    /**
     * Emits the configurator from the front end's description, with the ClassFile API, and verifies every class
     * before anything is added.
     */
    private static final class Emitter {

        private static final ClassDesc CONFIGURATOR = ClassDesc.of(CONFIGURATOR_CLASS);
        private static final ClassDesc FALLBACK = ClassDesc.of(FALLBACK_CLASS);
        private static final ClassDesc CONTEXT_AWARE_BASE = ClassDesc.of("ch.qos.logback.core.spi.ContextAwareBase");
        private static final ClassDesc CONTEXT_AWARE = ClassDesc.of("ch.qos.logback.core.spi.ContextAware");
        private static final ClassDesc CONTEXT = ClassDesc.of("ch.qos.logback.core.Context");
        private static final ClassDesc LIFE_CYCLE = ClassDesc.of("ch.qos.logback.core.spi.LifeCycle");
        private static final ClassDesc APPENDER = ClassDesc.of("ch.qos.logback.core.Appender");
        private static final ClassDesc CONTEXT_UTIL = ClassDesc.of("ch.qos.logback.core.util.ContextUtil");
        private static final ClassDesc STATUS = ClassDesc.of("ch.qos.logback.core.status.Status");
        private static final ClassDesc STATUS_MANAGER = ClassDesc.of("ch.qos.logback.core.status.StatusManager");
        private static final ClassDesc WARN_STATUS = ClassDesc.of("ch.qos.logback.core.status.WarnStatus");
        private static final ClassDesc LOGGER_CONTEXT = ClassDesc.of("ch.qos.logback.classic.LoggerContext");
        private static final ClassDesc LOGGER = ClassDesc.of("ch.qos.logback.classic.Logger");
        private static final ClassDesc LEVEL = ClassDesc.of("ch.qos.logback.classic.Level");
        private static final ClassDesc CONFIGURATOR_INTERFACE = ClassDesc.of("ch.qos.logback.classic.spi.Configurator");
        private static final ClassDesc EXECUTION_STATUS =
                ClassDesc.of("ch.qos.logback.classic.spi.Configurator$ExecutionStatus");

        private static final MethodTypeDesc CONFIGURE = MethodTypeDesc.of(EXECUTION_STATUS, LOGGER_CONTEXT);
        private static final MethodTypeDesc LOCATION =
                MethodTypeDesc.of(EXECUTION_STATUS, LOGGER_CONTEXT, ConstantDescs.CD_String);
        private static final MethodTypeDesc STRING_TO_STRING =
                MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String);
        private static final MethodTypeDesc TAKES_CONTEXT = MethodTypeDesc.of(ConstantDescs.CD_void, CONTEXT);
        private static final MethodTypeDesc TAKES_STRING =
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String);
        private static final MethodTypeDesc TAKES_APPENDER = MethodTypeDesc.of(ConstantDescs.CD_void, APPENDER);

        private static final String CONFIGURED_ONCE = "configuredOnce";

        /** Slots of the configure method: this, the context, again, the visible location, the current logger. */
        private static final int CONTEXT_SLOT = 1;
        private static final int AGAIN_SLOT = 2;
        private static final int LOCATION_SLOT = 3;
        private static final int LOGGER_SLOT = 4;
        private static final int ENCODER_SLOT = 5;
        private static final int FIRST_APPENDER_SLOT = 6;

        private Emitter() {
        }

        static Map<String, byte[]> emit(Map<String, Object> description, byte[] fallback, ClassLoader loader) {
            // The generated class is not packaged anywhere yet, so its supertype is declared here; everything else
            // is parsed from the isolated loader, whose platform parent resolves the JDK. Deliberately not
            // defaultResolver(): it would also see the build tool's own class path, and could resolve a Logback
            // supertype from a copy there instead of the application's.
            ClassHierarchyResolver resolver = ClassHierarchyResolver
                    .of(List.of(), Map.of(CONFIGURATOR, CONTEXT_AWARE_BASE))
                    .orElse(ClassHierarchyResolver.ofResourceParsing(loader))
                    .cached();
            ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
            byte[] configurator = classFile.build(CONFIGURATOR, new ConfiguratorClass(operations(description)));
            for (byte[] bytes : List.of(configurator, fallback)) {
                var errors = classFile.verify(bytes);
                if (!errors.isEmpty()) {
                    throw new IllegalStateException("a generated class does not verify: " + errors.get(0));
                }
            }
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put(CONFIGURATOR_ENTRY, configurator);
            entries.put(FALLBACK_ENTRY, fallback);
            entries.put(SERVICE_ENTRY, (CONFIGURATOR_CLASS + "\n").getBytes(StandardCharsets.UTF_8));
            return entries;
        }

        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> operations(Map<String, Object> description) {
            return (List<Map<String, Object>>) description.get("operations");
        }

        /**
         * The class: a final ContextAwareBase implementing Configurator, with one static flag.
         *
         * @param operations what the configuration does, in Joran's order
         */
        private record ConfiguratorClass(List<Map<String, Object>> operations) implements Consumer<ClassBuilder> {

            @Override
            public void accept(ClassBuilder builder) {
                builder.withVersion(EntryStubGenerator.CLASS_FILE_MAJOR_VERSION,
                        EntryStubGenerator.CLASS_FILE_MINOR_VERSION);
                builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
                builder.withSuperclass(CONTEXT_AWARE_BASE);
                builder.withInterfaceSymbols(CONFIGURATOR_INTERFACE);
                builder.withField(CONFIGURED_ONCE, ConstantDescs.CD_boolean,
                        ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_VOLATILE);
                builder.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
                        code -> code.aload(0)
                                .invokespecial(CONTEXT_AWARE_BASE, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                                .return_());
                builder.withMethodBody("configure", CONFIGURE, ClassFile.ACC_PUBLIC, new Configure(operations));
            }
        }

        /**
         * The body of {@code configure}: the runtime rules, then the literal configuration.
         *
         * @param operations what the configuration does, in Joran's order
         */
        private record Configure(List<Map<String, Object>> operations) implements Consumer<CodeBuilder> {

            @Override
            public void accept(CodeBuilder code) {
                Label afterProperty = code.newLabel();
                Label notAgain = code.newLabel();
                Label useLocation = code.newLabel();
                Label joran = code.newLabel();
                Label literal = code.newLabel();

                // boolean again = configuredOnce; configuredOnce = true;
                code.getstatic(CONFIGURATOR, CONFIGURED_ONCE, ConstantDescs.CD_boolean).istore(AGAIN_SLOT)
                        .iconst_1().putstatic(CONFIGURATOR, CONFIGURED_ONCE, ConstantDescs.CD_boolean);

                // Rule 1: -Dlogback.configurationFile is Logback's own lookup.
                systemProperty(code, "logback.configurationFile").ifnull(afterProperty);
                defaultLookup(code);
                code.labelBinding(afterProperty);

                // Rule 2: again, and a location is visible.
                code.iload(AGAIN_SLOT).ifeq(notAgain);
                systemProperty(code, "logger.config").astore(LOCATION_SLOT)
                        .aload(LOCATION_SLOT).ifnonnull(useLocation);
                environment(code, "LOGGER_CONFIG").astore(LOCATION_SLOT)
                        .aload(LOCATION_SLOT).ifnonnull(useLocation);
                environment(code, "LOGBACK_CONFIGURATIONFILE").astore(LOCATION_SLOT)
                        .aload(LOCATION_SLOT).ifnull(notAgain);
                code.labelBinding(useLocation);
                code.aload(CONTEXT_SLOT).aload(LOCATION_SLOT)
                        .invokestatic(FALLBACK, "location", LOCATION).areturn();
                code.labelBinding(notAgain);

                // Rule 3: Logback's status output was asked for, or the runtime opt-out is set.
                systemProperty(code, "logback.debug").ifnonnull(joran);
                systemProperty(code, "logback.statusListenerClass").ifnonnull(joran);
                code.ldc("false");
                systemProperty(code, "micronaut.runner.logback.precompiled")
                        .invokevirtual(ConstantDescs.CD_String, "equals",
                                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object))
                        .ifeq(literal);
                code.labelBinding(joran);
                defaultLookup(code);

                // Rule 4: the literal configuration, starting with what ConfigurationModelHandler does.
                code.labelBinding(literal);
                code.aload(CONTEXT_SLOT).iconst_0()
                        .invokevirtual(LOGGER_CONTEXT, "setPackagingDataEnabled",
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_boolean));
                code.new_(CONTEXT_UTIL).dup().aload(CONTEXT_SLOT)
                        .invokespecial(CONTEXT_UTIL, ConstantDescs.INIT_NAME, TAKES_CONTEXT)
                        .aload(CONTEXT_SLOT)
                        .invokevirtual(LOGGER_CONTEXT, "getFrameworkPackages",
                                MethodTypeDesc.of(ConstantDescs.CD_List))
                        .invokevirtual(CONTEXT_UTIL, "addGroovyPackages",
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_List));
                Map<String, Integer> appenderSlots = new HashMap<>();
                for (Map<String, Object> operation : operations) {
                    switch ((String) operation.get("op")) {
                        case "appender" -> appender(code, operation, appenderSlots);
                        case "skip" -> skipped(code, (String) operation.get("name"));
                        case "logger" -> logger(code, operation, appenderSlots);
                        default -> throw new IllegalStateException("unknown operation " + operation.get("op"));
                    }
                }
                code.getstatic(EXECUTION_STATUS, "DO_NOT_INVOKE_NEXT_IF_ANY", EXECUTION_STATUS).areturn();
            }

            private static CodeBuilder systemProperty(CodeBuilder code, String name) {
                return code.ldc(name).invokestatic(ClassDesc.of("java.lang.System"), "getProperty", STRING_TO_STRING);
            }

            private static CodeBuilder environment(CodeBuilder code, String name) {
                return code.ldc(name).invokestatic(ClassDesc.of("java.lang.System"), "getenv", STRING_TO_STRING);
            }

            private static void defaultLookup(CodeBuilder code) {
                code.aload(CONTEXT_SLOT)
                        .invokestatic(FALLBACK, "defaultLookup", MethodTypeDesc.of(EXECUTION_STATUS, LOGGER_CONTEXT))
                        .areturn();
            }

            /**
             * What {@code AppenderModelHandler} and {@code ImplicitModelHandler} do: create the appender, set its
             * context and name, apply its nested elements in document order, and start it.
             */
            @SuppressWarnings("unchecked")
            private static void appender(CodeBuilder code, Map<String, Object> operation, Map<String, Integer> slots) {
                ClassDesc type = ClassDesc.of((String) operation.get("className"));
                int slot = FIRST_APPENDER_SLOT + slots.size();
                slots.put((String) operation.get("name"), slot);
                code.new_(type).dup().invokespecial(type, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .astore(slot);
                code.aload(slot).aload(CONTEXT_SLOT).invokeinterface(APPENDER, "setContext", TAKES_CONTEXT);
                code.aload(slot).ldc((String) operation.get("name"))
                        .invokeinterface(APPENDER, "setName", TAKES_STRING);
                for (Map<String, Object> step : (List<Map<String, Object>>) operation.get("steps")) {
                    if ("encoder".equals(step.get("step"))) {
                        encoder(code, type, slot, step);
                        continue;
                    }
                    code.aload(slot);
                    Object value = step.get("value");
                    if (value instanceof String text) {
                        code.ldc(text);
                    } else if (value instanceof Boolean flag) {
                        code.loadConstant(flag ? 1 : 0);
                    } else {
                        code.loadConstant((Integer) value);
                    }
                    setter(code, type, (String) step.get("method"), (String) step.get("descriptor"));
                }
                if (Boolean.TRUE.equals(operation.get("start"))) {
                    code.aload(slot).invokeinterface(LIFE_CYCLE, "start", ConstantDescs.MTD_void);
                }
            }

            /**
             * A plain {@code PatternLayoutEncoder}, as Joran builds one: context, pattern, parent, start, and only
             * then the appender's encoder property.
             */
            private static void encoder(CodeBuilder code, ClassDesc appender, int appenderSlot,
                                        Map<String, Object> step) {
                ClassDesc type = ClassDesc.of((String) step.get("className"));
                code.new_(type).dup().invokespecial(type, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .astore(ENCODER_SLOT);
                code.aload(ENCODER_SLOT).aload(CONTEXT_SLOT)
                        .invokeinterface(CONTEXT_AWARE, "setContext", TAKES_CONTEXT);
                code.aload(ENCODER_SLOT).ldc((String) step.get("pattern"));
                setter(code, type, (String) step.get("patternMethod"), (String) step.get("patternDescriptor"));
                if (step.get("parentMethod") != null) {
                    code.aload(ENCODER_SLOT).aload(appenderSlot);
                    setter(code, type, (String) step.get("parentMethod"), (String) step.get("parentDescriptor"));
                }
                if (Boolean.TRUE.equals(step.get("start"))) {
                    code.aload(ENCODER_SLOT).invokeinterface(LIFE_CYCLE, "start", ConstantDescs.MTD_void);
                }
                code.aload(appenderSlot).aload(ENCODER_SLOT);
                setter(code, appender, (String) step.get("method"), (String) step.get("descriptor"));
            }

            private static void setter(CodeBuilder code, ClassDesc owner, String name, String descriptor) {
                MethodTypeDesc type = MethodTypeDesc.ofDescriptor(descriptor);
                code.invokevirtual(owner, name, type);
                TypeKind result = TypeKind.from(type.returnType());
                if (result.slotSize() == 2) {
                    code.pop2();
                } else if (result.slotSize() == 1) {
                    code.pop();
                }
            }

            /** The warning {@code AppenderModelHandler} adds for an appender nothing references. */
            private static void skipped(CodeBuilder code, String name) {
                code.aload(CONTEXT_SLOT)
                        .invokevirtual(LOGGER_CONTEXT, "getStatusManager", MethodTypeDesc.of(STATUS_MANAGER))
                        .new_(WARN_STATUS).dup()
                        .ldc("Appender named [" + name + "] not referenced. Skipping further processing.")
                        .aload(0)
                        .invokespecial(WARN_STATUS, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String,
                                        ConstantDescs.CD_Object))
                        .invokeinterface(STATUS_MANAGER, "add", MethodTypeDesc.of(ConstantDescs.CD_void, STATUS));
            }

            /**
             * What {@code LoggerModelHandler} or {@code RootLoggerModelHandler} and then
             * {@code AppenderRefModelHandler} do: level, additivity, then each referenced appender in order.
             */
            @SuppressWarnings("unchecked")
            private static void logger(CodeBuilder code, Map<String, Object> operation, Map<String, Integer> slots) {
                code.aload(CONTEXT_SLOT).ldc((String) operation.get("name"))
                        .invokevirtual(LOGGER_CONTEXT, "getLogger", MethodTypeDesc.of(LOGGER, ConstantDescs.CD_String))
                        .astore(LOGGER_SLOT);
                Object level = operation.get("level");
                if (level != null) {
                    code.aload(LOGGER_SLOT);
                    if ("NULL".equals(level)) {
                        code.aconst_null();
                    } else {
                        code.getstatic(LEVEL, (String) level, LEVEL);
                    }
                    code.invokevirtual(LOGGER, "setLevel", MethodTypeDesc.of(ConstantDescs.CD_void, LEVEL));
                }
                Object additivity = operation.get("additivity");
                if (additivity != null) {
                    code.aload(LOGGER_SLOT).loadConstant(Boolean.TRUE.equals(additivity) ? 1 : 0)
                            .invokevirtual(LOGGER, "setAdditive",
                                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_boolean));
                }
                for (String reference : (List<String>) operation.get("appenders")) {
                    Integer slot = slots.get(reference);
                    if (slot == null) {
                        throw new IllegalStateException("the logger " + operation.get("name")
                                + " references " + reference + " before it is created");
                    }
                    code.aload(LOGGER_SLOT).aload(slot).invokevirtual(LOGGER, "addAppender", TAKES_APPENDER);
                }
            }
        }
    }
}
