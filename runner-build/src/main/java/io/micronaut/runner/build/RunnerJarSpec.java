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

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Manifest;

/**
 * Everything {@link RunnerJarBuilder} needs to know to produce a runner jar.
 *
 * <p>A spec is immutable and is built through {@link #builder()}. Only the main class, the application
 * output and the output file have to be set. Every packaging option has the default that
 * {@link RunnerJarOption} lists, so a plugin passes only what the user set and decides nothing the user did
 * not ask about.</p>
 *
 * <pre>{@code
 * RunnerJarSpec spec = RunnerJarSpec.builder()
 *         .mainClass("com.example.Application")
 *         .applicationOutput(List.of(classesDir, resourcesDir))
 *         .dependencies(List.of(Dependency.of(nettyJar, "io.netty:netty-common:4.2.1")))
 *         .output(buildDir.resolve("app-all.jar"))
 *         .option("compression", "PRESERVE")
 *         .build();
 * }</pre>
 *
 * <p>The default {@link #timestamp()} is 1980-02-01T00:00:00Z, the earliest instant MS-DOS time can
 * represent with a day to spare, and every entry of the archive is dated with it in UTC. That, together
 * with the fixed entry order the builder uses, is what makes two builds of the same inputs produce the same
 * bytes on any machine in any time zone.</p>
 *
 * @since 1.0
 */
public final class RunnerJarSpec {

    private final String mainClass;
    private final List<Path> applicationOutput;
    private final Path applicationManifestSource;
    private final Manifest applicationManifest;
    private final List<Dependency> dependencies;
    private final Path output;
    private final Compression compression;
    private final boolean multiRelease;
    private final boolean entryStub;
    private final Map<String, String> manifestAttributes;
    private final List<String> addOpens;
    private final List<String> addExports;
    private final boolean enableNativeAccess;
    private final ArchiveReads archiveReads;
    private final boolean precompileLogback;
    private final boolean stripLocalVariables;
    private final Path startupClasses;
    private final boolean staticServices;
    private final boolean desugarLambdas;
    private final Instant timestamp;
    private final Map<String, String> effectiveOptions;

    private RunnerJarSpec(Builder builder) {
        this.mainClass = builder.mainClass;
        this.applicationOutput = List.copyOf(builder.applicationOutput);
        this.applicationManifestSource = builder.applicationManifestSource;
        this.applicationManifest = builder.applicationManifest == null
                ? null : new Manifest(builder.applicationManifest);
        this.dependencies = List.copyOf(builder.dependencies);
        this.output = builder.output;
        this.compression = builder.compression;
        this.multiRelease = builder.multiRelease;
        this.entryStub = builder.entryStub;
        // Not Map.copyOf: its iteration order is derived from a per-JVM salt, so two builds of the same
        // inputs in two JVMs would write the same attributes in different orders and produce archives that
        // differ. The builder keeps the caller's order and so does this.
        this.manifestAttributes =
                Collections.unmodifiableMap(new LinkedHashMap<>(builder.manifestAttributes));
        this.addOpens = List.copyOf(builder.addOpens);
        this.addExports = List.copyOf(builder.addExports);
        this.enableNativeAccess = builder.enableNativeAccess;
        this.archiveReads = builder.archiveReads;
        this.precompileLogback = builder.precompileLogback;
        this.stripLocalVariables = builder.stripLocalVariables;
        this.startupClasses = builder.startupClasses;
        this.staticServices = builder.staticServices;
        this.desugarLambdas = builder.desugarLambdas;
        this.timestamp = builder.timestamp;
        Map<String, String> effective = new LinkedHashMap<>();
        for (RunnerJarOption option : RunnerJarOption.values()) {
            effective.put(option.optionName(), effectiveValue(option));
        }
        this.effectiveOptions = Collections.unmodifiableMap(effective);
    }

    /**
     * Starts describing a runner jar.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The application main class, in binary form.
     *
     * <p>It is recorded in the index and in the {@code Micronaut-Runner-Start-Class} manifest attribute, and
     * the build fails when the application output does not contain it.</p>
     *
     * @return the binary class name
     */
    public String mainClass() {
        return mainClass;
    }

    /**
     * The application's own classes and resources, as directories and jars in the order they take
     * precedence.
     *
     * <p>They are packaged exploded under {@code MICRONAUT-INF/classes/} and form jar {@code 0} of the
     * index. When two of them carry the same relative name the first one wins, exactly as it would on a
     * class path, and the build warns about the one that lost.</p>
     *
     * @return the application output, in order
     */
    public List<Path> applicationOutput() {
        return applicationOutput;
    }

    /**
     * A jar or {@code MANIFEST.MF} file whose attributes describe the application.
     *
     * <p>A directory of class files carries no manifest, so this is how the {@code Implementation-Title} and
     * friends of the application's own jar reach the runner jar's manifest and the jar {@code 0} record of
     * the index, including its per-package sections.</p>
     *
     * @return the manifest source, if one was configured
     */
    public Optional<Path> applicationManifestSource() {
        return Optional.ofNullable(applicationManifestSource);
    }

    /**
     * An already parsed application manifest, as an alternative to {@link #applicationManifestSource()}.
     *
     * <p>The returned manifest is a copy; mutating it has no effect on the spec.</p>
     *
     * @return the manifest, if one was configured
     */
    public Optional<Manifest> applicationManifest() {
        return Optional.ofNullable(applicationManifest == null ? null : new Manifest(applicationManifest));
    }

    /**
     * The dependencies to nest, in class path order.
     *
     * @return the dependencies, in order
     */
    public List<Dependency> dependencies() {
        return dependencies;
    }

    /**
     * Where the runner jar is written.
     *
     * @return the output file
     */
    public Path output() {
        return output;
    }

    /**
     * How the nested dependencies are stored.
     *
     * @return the compression mode, {@link Compression#STORED} unless configured otherwise
     */
    public Compression compression() {
        return compression;
    }

    /**
     * Whether the application layer is itself multi-release.
     *
     * <p>Only when this is set does the index alias the application's own
     * {@code META-INF/versions/N/...} entries; a dependency is aliased when its own manifest says
     * {@code Multi-Release: true}, independently of this option.</p>
     *
     * @return whether jar {@code 0} is multi-release
     */
    public boolean multiRelease() {
        return multiRelease;
    }

    /**
     * Whether the generated entry stub was requested.
     *
     * <p>A request does not guarantee a stub. The packager generates one only when the main class is a
     * public, non-abstract class in a named package that declares its own
     * {@code public static void main(String[])}. When the application layer is declared multi-release, every
     * multi-release variant of that class that the runner can select must have the same directly callable
     * shape. If the request is disabled, a class is ineligible, or the generated class name is already
     * occupied, the packager reports why and leaves the index without a stub; the launcher then uses its
     * reflective fallback.</p>
     *
     * @return whether an entry stub was requested
     */
    public boolean entryStub() {
        return entryStub;
    }

    /**
     * Extra main attributes for the runner jar's manifest.
     *
     * <p>They are written after the attributes the format requires and can override the ones taken from the
     * application manifest. {@code Manifest-Version}, {@code Main-Class} and the entire
     * {@code Micronaut-Runner-*} namespace are reserved and rejected case-insensitively because the runner
     * jar format and launcher depend on them.</p>
     *
     * <p>Attributes are written in this map's iteration order; pass an ordered map such as
     * {@link java.util.LinkedHashMap} for byte-for-byte reproducible archives, as both plugins do.</p>
     *
     * @return the extra attributes, in the order they were configured
     */
    public Map<String, String> manifestAttributes() {
        return manifestAttributes;
    }

    /**
     * The {@code Add-Opens} manifest attribute values.
     *
     * <p>Each value uses the JAR manifest grammar {@code module/package}; unlike the corresponding Java
     * command-line option, it has no {@code =ALL-UNNAMED} suffix.</p>
     *
     * @return the module/package pairs to open, or an empty list
     */
    public List<String> addOpens() {
        return addOpens;
    }

    /**
     * The {@code Add-Exports} manifest attribute values.
     *
     * <p>Each value uses the JAR manifest grammar {@code module/package}; unlike the corresponding Java
     * command-line option, it has no {@code =ALL-UNNAMED} suffix.</p>
     *
     * @return the module/package pairs to export, or an empty list
     */
    public List<String> addExports() {
        return addExports;
    }

    /**
     * Whether to write {@code Enable-Native-Access: ALL-UNNAMED} into the manifest, which silences the
     * restricted method warnings a dependency using the foreign function API would otherwise produce.
     *
     * @return whether native access is enabled
     */
    public boolean enableNativeAccess() {
        return enableNativeAccess;
    }

    /**
     * How the launcher reads the archive when {@code micronaut.runner.mmap} does not say: by mapping all of it,
     * or by mapping only the index and reading classes positionally. It is recorded as a flag of the index
     * header.
     *
     * @return the read mode, {@link ArchiveReads#MAPPED} unless configured otherwise
     */
    public ArchiveReads archiveReads() {
        return archiveReads;
    }

    /**
     * Whether to compile the application's {@code logback.xml} into a Logback {@code Configurator} when it is
     * packaged, so that the application does not parse XML and run Joran on its main thread at every start.
     *
     * <p>The packager generates {@code io.micronaut.runner.generated.logback.LogbackConfigurator} into the
     * application layer and registers it as a {@code ch.qos.logback.classic.spi.Configurator} service. It applies to
     * STORED and PRESERVE alike, since it touches only the application layer. It fails closed: it generates nothing,
     * and logs why, when it cannot prove that the generated code reproduces Joran's result exactly. That is the
     * case unless logback-classic and logback-core are both at the same version from 1.5.37 up to, but excluding,
     * 1.6; when there is no {@code logback.xml}, or a {@code logback-test.xml}, {@code logback.groovy} or versioned
     * Logback file; when any layer already registers a {@code Configurator}, as Micronaut AOT's
     * {@code logback.xml.to.java} output does; when a packaged {@code application*} or {@code bootstrap*} file may
     * set {@code logger.config} or {@code logback.configurationFile}; and when the file uses anything beyond
     * literal appenders with simple properties and a {@code PatternLayoutEncoder}, loggers, the root logger and
     * appender references.</p>
     *
     * <p>What changes at runtime when it applies: Joran's INFO status messages about reading {@code logback.xml} are
     * not recorded, and every way to see them ({@code logback.debug}, a status listener) falls back to Joran;
     * {@code logback.xml} is still packaged but read only on the fallback paths, which are
     * {@code -Dlogback.configurationFile}, a {@code logger.config} location on Micronaut's refresh, and
     * {@code -Dmicronaut.runner.logback.precompiled=false}.</p>
     *
     * @return whether to precompile {@code logback.xml}
     */
    public boolean precompileLogback() {
        return precompileLogback;
    }

    /**
     * Whether the local-variable tables of dependency classes are dropped when they are re-packed.
     *
     * <p>See {@link Builder#stripLocalVariables(boolean)} for what is dropped, what is kept and what changes
     * as a result.</p>
     *
     * @return whether local-variable tables are stripped, {@code true} unless configured otherwise
     */
    public boolean stripLocalVariables() {
        return stripLocalVariables;
    }

    /**
     * The recorded startup class list: the {@code -Xlog:class+load} output of a run of the runner jar, or a
     * file of binary class names.
     *
     * <p>The packager embeds the classes of the list that the archive holds, in the order of the list, and
     * the launcher defines them on one background thread while the application starts, followed by the JDK
     * classes of the list. The launcher loads them and never initialises them, so a list that names a class
     * the application no longer uses costs background work and changes no behaviour.</p>
     *
     * @return the list file, if one was configured
     */
    public Optional<Path> startupClasses() {
        return Optional.ofNullable(startupClasses);
    }

    /**
     * Whether the static service table was requested: generated classes that answer Micronaut's service
     * lookups from names computed at packaging time, so that the application does not scan its class path for
     * them when it starts.
     *
     * <p>A request does not guarantee a table. The packager generates one only for an application whose
     * micronaut-core is in the range {@code [5.1.10, 5.2)} and has the hook the table plugs into. It generates
     * none for a micronaut-core that carries its own service index, when another
     * {@code StaticOptimizations$Loader} of the application already supplies service loaders, or when a
     * generated class name is taken; it reports why, and Micronaut then scans as it does without a table.
     * Within a table, a service type is left to the scan when the packager cannot prove the table equal to
     * it, for example when a listed class or one of its supertypes is in no JAR of the application.</p>
     *
     * <p>The table describes the class path the application was packaged with. At run time it therefore
     * ignores the class loader a lookup names, and does not see a provider added after packaging; it stands
     * down when the application starts with {@code -Dmicronaut.runner.static-services=false} or with
     * {@code -Dmicronaut.runner.parent=system}.</p>
     *
     * @return whether a static service table was requested
     */
    public boolean staticServices() {
        return staticServices;
    }

    /**
     * Whether the lambda and method-reference call sites of dependency and application classes are replaced
     * with classes generated when the application is packaged.
     *
     * <p>It applies to {@link Compression#STORED} only and is on by default. To opt out, set it to
     * {@code false}: through {@link Builder#desugarLambdas(boolean)}, or by name, as the build plugins'
     * generic options do, with {@code option("desugarLambdas", "false")}. See
     * {@link Builder#desugarLambdas(boolean)} for what is rewritten and what changes as a result.</p>
     *
     * @return whether lambdas are desugared, {@code true} unless configured otherwise
     */
    public boolean desugarLambdas() {
        return desugarLambdas;
    }

    /**
     * The instant every entry of the archive is dated with, converted to MS-DOS time in UTC.
     *
     * @return the reproducible timestamp
     */
    public Instant timestamp() {
        return timestamp;
    }

    /**
     * The value every {@link RunnerJarOption} has in this spec, whether it was set or defaulted, keyed by
     * {@linkplain RunnerJarOption#optionName() option name} in table order. Each value is in the grammar
     * {@link Builder#option(String, String)} reads, so passing the entries back to a fresh builder reproduces
     * the same options.
     *
     * @return the effective options, unmodifiable
     */
    public Map<String, String> effectiveOptions() {
        return effectiveOptions;
    }

    /**
     * Writes one option's value in the grammar {@link Builder#option(String, String)} reads. The switch is
     * exhaustive, so an option added to the table without a case here does not compile.
     */
    private String effectiveValue(RunnerJarOption option) {
        return switch (option) {
            case COMPRESSION -> compression.name();
            case ENTRY_STUB -> Boolean.toString(entryStub);
            case MULTI_RELEASE -> Boolean.toString(multiRelease);
            case ENABLE_NATIVE_ACCESS -> Boolean.toString(enableNativeAccess);
            case ADD_OPENS -> String.join(",", addOpens);
            case ADD_EXPORTS -> String.join(",", addExports);
            case MANIFEST_ATTRIBUTES -> formatAttributes(manifestAttributes);
            case ARCHIVE_READS -> archiveReads.name();
            case PRECOMPILE_LOGBACK -> Boolean.toString(precompileLogback);
            case STRIP_LOCAL_VARIABLES -> Boolean.toString(stripLocalVariables);
            case STARTUP_CLASSES -> startupClasses == null ? "" : startupClasses.toString();
            case STATIC_SERVICES -> Boolean.toString(staticServices);
            case DESUGAR_LAMBDAS -> Boolean.toString(desugarLambdas);
        };
    }

    private static String formatAttributes(Map<String, String> attributes) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(attribute.getKey()).append(": ").append(attribute.getValue());
        }
        return text.toString();
    }

    /**
     * Describes a runner jar step by step.
     *
     * <p>The builder is mutable and is not thread safe; {@link #build()} takes a snapshot of it, so it can
     * be reused afterwards.</p>
     *
     * <p>Every packaging option can be set in two ways: through its typed setter, such as
     * {@link #compression(Compression)}, or by name through {@link #option(String, String)}. Each call
     * replaces whatever was set before, so the last one wins. A plugin therefore applies its typed values
     * first and its generic options last.</p>
     */
    public static final class Builder {

        private String mainClass;
        private List<Path> applicationOutput = new ArrayList<>();
        private Path applicationManifestSource;
        private Manifest applicationManifest;
        private List<Dependency> dependencies = new ArrayList<>();
        private Path output;
        private Compression compression;
        private boolean multiRelease;
        private boolean entryStub;
        private Map<String, String> manifestAttributes;
        private List<String> addOpens;
        private List<String> addExports;
        private boolean enableNativeAccess;
        private ArchiveReads archiveReads;
        private boolean precompileLogback;
        private boolean stripLocalVariables;
        private Path startupClasses;
        private boolean staticServices;
        private boolean desugarLambdas;
        private Instant timestamp = ZipWriter.DEFAULT_TIMESTAMP;

        /**
         * Starts from the defaults of the option table, so {@link RunnerJarOption#defaultValue()} is the only
         * place a packaging default is written down.
         */
        private Builder() {
            for (RunnerJarOption option : RunnerJarOption.values()) {
                option.defaultValue().ifPresent(value -> option(option, value));
            }
        }

        /**
         * Sets the application main class.
         *
         * @param value the binary class name, for example {@code com.example.Application}
         * @return this builder
         */
        public Builder mainClass(String value) {
            this.mainClass = value;
            return this;
        }

        /**
         * Sets the application's classes and resources, in the order they take precedence.
         *
         * @param value directories and jars
         * @return this builder
         * @throws NullPointerException if the list or an element is {@code null}
         */
        public Builder applicationOutput(List<Path> value) {
            this.applicationOutput = copyOf(value, "applicationOutput");
            return this;
        }

        /**
         * Adds one directory or jar to the application output.
         *
         * @param value the directory or jar
         * @return this builder
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public Builder addApplicationOutput(Path value) {
            this.applicationOutput.add(Objects.requireNonNull(value, "applicationOutput"));
            return this;
        }

        /**
         * Sets the jar or {@code MANIFEST.MF} file the application's manifest attributes come from.
         *
         * @param value the manifest source, or {@code null} for none
         * @return this builder
         */
        public Builder applicationManifest(Path value) {
            this.applicationManifestSource = value;
            return this;
        }

        /**
         * Sets the application manifest directly, for a build system that has already parsed it.
         *
         * @param value the manifest, copied defensively, or {@code null} for none
         * @return this builder
         */
        public Builder applicationManifest(Manifest value) {
            this.applicationManifest = value == null ? null : new Manifest(value);
            return this;
        }

        /**
         * Sets the dependencies to nest, in class path order.
         *
         * @param value the dependencies
         * @return this builder
         * @throws NullPointerException if the list or an element is {@code null}
         */
        public Builder dependencies(List<Dependency> value) {
            this.dependencies = copyOf(value, "dependencies");
            return this;
        }

        /**
         * Adds one dependency at the end of the class path.
         *
         * @param value the dependency
         * @return this builder
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public Builder addDependency(Dependency value) {
            this.dependencies.add(Objects.requireNonNull(value, "dependency"));
            return this;
        }

        /**
         * Sets the file the runner jar is written to.
         *
         * @param value the output file
         * @return this builder
         */
        public Builder output(Path value) {
            this.output = value;
            return this;
        }

        /**
         * Sets how the nested dependencies are stored.
         *
         * @param value the compression mode
         * @return this builder
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public Builder compression(Compression value) {
            this.compression = Objects.requireNonNull(value, "compression");
            return this;
        }

        /**
         * Declares the application layer multi-release.
         *
         * @param value whether jar {@code 0} carries {@code META-INF/versions/N} entries to alias
         * @return this builder
         */
        public Builder multiRelease(boolean value) {
            this.multiRelease = value;
            return this;
        }

        /**
         * Requests a generated entry stub.
         *
         * <p>Setting this to {@code true} asks the packager to generate the stub only when the base main
         * class, and every selectable multi-release variant when {@link #multiRelease(boolean)} is enabled,
         * is directly callable: a public, non-abstract class in a named package that declares its own
         * {@code public static void main(String[])}. An ineligible class or a collision with the generated
         * class name is reported and uses the launcher's reflective fallback instead. Setting this to
         * {@code false} always uses that fallback.</p>
         *
         * <p>Defaults to {@code true}.</p>
         *
         * @param value whether to generate the stub when the main class is eligible
         * @return this builder
         */
        public Builder entryStub(boolean value) {
            this.entryStub = value;
            return this;
        }

        /**
         * Sets extra main attributes for the manifest.
         *
         * <p>Attributes are written in this map's iteration order; pass an ordered map such as
         * {@link java.util.LinkedHashMap} for byte-for-byte reproducible archives, as both plugins do.</p>
         *
         * @param value the attributes, in the order they should be written
         * @return this builder
         * @throws NullPointerException     if the map, a key or a value is {@code null}
         * @throws IllegalArgumentException if a key is {@code Manifest-Version}, {@code Main-Class} or in
         *                                  the {@code Micronaut-Runner-*} namespace, ignoring case
         */
        public Builder manifestAttributes(Map<String, String> value) {
            Objects.requireNonNull(value, "manifestAttributes");
            Map<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> attribute : value.entrySet()) {
                String name = Objects.requireNonNull(attribute.getKey(), "manifest attribute name");
                Objects.requireNonNull(attribute.getValue(), "manifest attribute value");
                if (isReservedManifestAttribute(name)) {
                    throw new IllegalArgumentException("Manifest attribute '" + name
                            + "' is reserved by Micronaut Runner");
                }
                copy.put(name, attribute.getValue());
            }
            this.manifestAttributes = copy;
            return this;
        }

        private static boolean isReservedManifestAttribute(String name) {
            String runnerPrefix = "Micronaut-Runner-";
            return "Manifest-Version".equalsIgnoreCase(name)
                    || "Main-Class".equalsIgnoreCase(name)
                    || name.regionMatches(true, 0, runnerPrefix, 0, runnerPrefix.length());
        }

        /**
         * Sets the {@code Add-Opens} values.
         *
         * <p>Each entry must use JAR manifest syntax, {@code module/package}. Do not append the
         * command-line-only {@code =ALL-UNNAMED} target.</p>
         *
         * @param value the module/package pairs, one pair per list entry
         * @return this builder
         * @throws NullPointerException if the list or an element is {@code null}
         * @throws IllegalArgumentException if an entry is not a single, whitespace-free
         *                                  {@code module/package} pair
         */
        public Builder addOpens(List<String> value) {
            this.addOpens = copyModulePackagePairs(value, "addOpens", "--add-opens");
            return this;
        }

        /**
         * Sets the {@code Add-Exports} values.
         *
         * <p>Each entry must use JAR manifest syntax, {@code module/package}. Do not append the
         * command-line-only {@code =ALL-UNNAMED} target.</p>
         *
         * @param value the module/package pairs, one pair per list entry
         * @return this builder
         * @throws NullPointerException if the list or an element is {@code null}
         * @throws IllegalArgumentException if an entry is not a single, whitespace-free
         *                                  {@code module/package} pair
         */
        public Builder addExports(List<String> value) {
            this.addExports = copyModulePackagePairs(value, "addExports", "--add-exports");
            return this;
        }

        private static List<String> copyModulePackagePairs(List<String> value, String what,
                String commandLineOption) {
            List<String> copy = copyOf(value, what);
            for (String entry : copy) {
                int equals = entry.indexOf('=');
                if (equals >= 0) {
                    String pair = entry.substring(0, equals);
                    throw new IllegalArgumentException(what + " entry '" + entry
                            + "' uses command-line syntax. Use '" + pair + "' in the JAR manifest; '"
                            + commandLineOption + " " + entry + "' is the command-line form.");
                }
                int slash = entry.indexOf('/');
                if (slash <= 0 || slash == entry.length() - 1 || slash != entry.lastIndexOf('/')
                        || entry.chars().anyMatch(Character::isWhitespace)) {
                    throw new IllegalArgumentException(what + " entry '" + entry
                            + "' must be one whitespace-free module/package pair in JAR manifest syntax");
                }
            }
            return copy;
        }

        /**
         * Enables native access for the unnamed module.
         *
         * @param value whether to write {@code Enable-Native-Access: ALL-UNNAMED}
         * @return this builder
         */
        public Builder enableNativeAccess(boolean value) {
            this.enableNativeAccess = value;
            return this;
        }

        /**
         * Sets how the launcher reads the archive when {@code micronaut.runner.mmap} does not say.
         *
         * <p>{@link ArchiveReads#POSITIONAL} maps only the index and reads each class with one positional read
         * into a pooled buffer: the resident set size is lower and startup slightly slower. Defaults to
         * {@link ArchiveReads#MAPPED}, because paired startup measurements found that slowdown larger than a
         * default may cost; see {@link ArchiveReads}.</p>
         *
         * @param value the read mode
         * @return this builder
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public Builder archiveReads(ArchiveReads value) {
            this.archiveReads = Objects.requireNonNull(value, "archiveReads");
            return this;
        }

        /**
         * Requests that the application's {@code logback.xml} be compiled into a Logback {@code Configurator} when
         * it is packaged. The packager generates nothing, and logs why, whenever it cannot prove that the result
         * matches Joran's; see {@link RunnerJarSpec#precompileLogback()}. Setting this to {@code false} leaves
         * {@code logback.xml} to Joran at startup.
         *
         * <p>Defaults to {@code true}.</p>
         *
         * @param value whether to precompile {@code logback.xml}
         * @return this builder
         */
        public Builder precompileLogback(boolean value) {
            this.precompileLogback = value;
            return this;
        }

        /**
         * Drops the local-variable tables of dependency classes when they are re-packed.
         *
         * <p>Without a JDK AOT cache, parsing and defining classes is the largest single startup cost, and it
         * grows with class bytes and with the symbols the JVM interns. The names and signatures of local
         * variables are neither needed to run a class nor visible to reflection, so a re-packed dependency
         * class is rewritten without them, with a rebuilt constant pool. On the benchmark sample that makes the
         * dependency classes about 15% smaller; the user guide's build-time transforms section has the measured
         * effect on startup.</p>
         *
         * <p>What is dropped from a dependency class:</p>
         * <ul>
         *     <li>{@code LocalVariableTable}, {@code LocalVariableTypeTable} and {@code CharacterRangeTable};</li>
         *     <li>the type annotations of code, {@code RuntimeVisibleTypeAnnotations} and
         *     {@code RuntimeInvisibleTypeAnnotations} inside {@code Code}, which carry bytecode offsets;</li>
         *     <li>{@code RuntimeInvisibleTypeAnnotations} on the class, its fields and its methods.</li>
         * </ul>
         *
         * <p>What is kept: {@code LineNumberTable} and {@code SourceFile}, so stack traces keep their
         * {@code (File.java:N)} frames; {@code SourceDebugExtension}; {@code MethodParameters}, so reflective
         * parameter names survive; {@code Signature}; and every annotation reflection can see.</p>
         *
         * <p>What changes observably, in dependency classes only:</p>
         * <ul>
         *     <li>helpful {@code NullPointerException} messages name a local as {@code <local1>} instead of by
         *     its name, and a debugger shows no local names; the line number stays;</li>
         *     <li>the order of {@code getDeclaredMethods()}, which is unspecified, may change, because the
         *     constant pool is rebuilt;</li>
         *     <li>libraries that read {@code LocalVariableTable} at run time, such as Paranamer's
         *     {@code BytecodeReadingParanamer}, AspectJ load-time weaving and Spring's pre-6.1
         *     {@code LocalVariableTableParameterNameDiscoverer}, find no names. When one of them is on the
         *     class path, stripping is turned off for the build, with a warning;</li>
         *     <li>a rewritten class has new bytes and a new CRC-32, and the archive is reproducible byte for
         *     byte only when it is built with the same JDK build.</li>
         * </ul>
         *
         * <p>The application's own classes, and a dependency marked {@link Dependency#projectModule()}, are
         * never stripped: they are user code, whose locals users debug. A class of a signed jar, a class with
         * an attribute the JDK does not know, and {@code module-info} are left alone too. With
         * {@link Compression#PRESERVE}, which nests every dependency byte for byte, the option has no effect.</p>
         *
         * <p>Defaults to {@code true}.</p>
         *
         * @param value whether to strip the local-variable tables of dependency classes
         * @return this builder
         */
        public Builder stripLocalVariables(boolean value) {
            this.stripLocalVariables = value;
            return this;
        }

        /**
         * Sets the recorded startup class list the launcher preloads on a background thread.
         *
         * <p>The file is either the raw output of {@code -Xlog:class+load=info} from a run of the runner jar
         * without a CDS or AOT cache, or one binary class name per line, where blank lines and lines starting
         * with {@code #} are ignored and {@code jrt:<name>} marks a JDK class. Of a class-load log, the classes
         * the runner class loader defined and the JDK classes loaded from the runtime image are kept. The
         * order of the file is kept and a repeated name counts once.</p>
         *
         * <p>Names the archive does not hold are dropped with one warning, so a list recorded before a
         * dependency upgrade stays usable. Record the list from a jar built with the same packaging options
         * as this one. There is no default: without a list nothing is preloaded.</p>
         *
         * @param value the list file, or {@code null} for none
         * @return this builder
         */
        public Builder startupClasses(Path value) {
            this.startupClasses = value;
            return this;
        }

        /**
         * Requests the static service table.
         *
         * <p>Setting this to {@code true} asks the packager to generate classes that answer Micronaut's
         * service lookups from names it computes while packaging. It does so only when it can reproduce the
         * order of Micronaut's own scan, which it does for micronaut-core {@code [5.1.10, 5.2)}; in every other
         * case it reports why there is no table, and Micronaut scans. {@link RunnerJarSpec#staticServices()}
         * lists those cases and what a table changes at run time. Setting this to {@code false} generates
         * nothing.</p>
         *
         * <p>Defaults to {@code true}.</p>
         *
         * @param value whether to generate the table when the application is eligible
         * @return this builder
         */
        public Builder staticServices(boolean value) {
            this.staticServices = value;
            return this;
        }

        /**
         * Sets whether lambda and method-reference call sites are replaced with classes generated at packaging
         * time.
         *
         * <p>A lambda compiles to an {@code invokedynamic} call site that spins a hidden class the first time
         * it runs. The JDK keeps such a class in a CDS archive or an AOT cache only for callers of its built-in
         * class loaders, which the classes of a runner jar do not have, so every lambda that runs before the
         * application is ready is linked at every start, with or without a cache. With this option each eligible
         * site calls a class that was generated when the application was packaged: nothing is linked at run
         * time, and a cache holds the class like any other. The user guide's build-time transforms section has
         * the measured effect.</p>
         *
         * <p>What is rewritten, in dependency classes and in the application's own: a call site whose bootstrap
         * is {@code LambdaMetafactory.metafactory}, when the generated class provably resolves what the site
         * resolved. Its implementation must be a member the generated class can reach: one of the host's own
         * nest, a non-private member of the host's package, or a public member of a public class, on the class
         * path or in an exported package of the JDK. Everything else keeps its {@code invokedynamic}: a
         * {@code super::method} reference or any other {@code invokespecial} reference that is not to a private
         * method of the host, a caller-sensitive JDK method, a host or an owner that another jar
         * shadows or that has a multi-release variant, every class of a signed jar and of
         * {@code META-INF/versions/}, and every other bootstrap, such as string concatenation, records'
         * {@code ObjectMethods} and serializable lambdas.</p>
         *
         * <p>What changes observably, for the rewritten lambdas:</p>
         * <ul>
         *     <li>{@code Class.isHidden()} is {@code false}, and the class has a stable name,
         *     {@code <Host>$$Lambda$R<n>}, that {@code Class.forName} finds;</li>
         *     <li>each call of the lambda adds one visible frame to stack traces and to {@code StackWalker},
         *     where the hidden class's frame was hidden;</li>
         *     <li>the jars and the application layer gain one {@code .class} resource per rewritten site, next
         *     to its host, and a rewritten host and its nest host have new bytes and a new CRC-32;</li>
         *     <li>a host below class-file version 55, which has no nestmates, gains one package-private static
         *     synthetic method {@code $runner$lambda$<n>} per site whose implementation is private, visible to
         *     {@code getDeclaredMethods()};</li>
         *     <li>the archive is reproducible byte for byte only when it is built with the same JDK build.</li>
         * </ul>
         *
         * <p>The step fails closed: every class it writes or rewrites is verified against the class path, and
         * a nest that verifies worse than before is packaged as it was. With {@link Compression#PRESERVE}, which
         * nests every dependency byte for byte, the option has no effect.</p>
         *
         * <p>Defaults to {@code true}.</p>
         *
         * @param value whether to desugar lambdas
         * @return this builder
         */
        public Builder desugarLambdas(boolean value) {
            this.desugarLambdas = value;
            return this;
        }

        /**
         * Sets a packaging option by its {@linkplain RunnerJarOption#optionName() name}, whether it has a
         * typed setter or not. This is how a build plugin passes the options it has no typed property for.
         *
         * <p>The value is read according to the option's {@linkplain RunnerJarOption#valueType() type}, in
         * the grammar {@link RunnerJarOption} documents, and then validated exactly as the typed setter
         * validates it. The call replaces any earlier value of the option, set by name or by its typed
         * setter, and a later typed setter call replaces it in turn.</p>
         *
         * @param name  the option name, such as {@code compression}
         * @param value the value, as text
         * @return this builder
         * @throws NullPointerException     if {@code name} or {@code value} is {@code null}
         * @throws IllegalArgumentException if no option has that name, or the value is not valid for it; for
         *                                  an unknown name, the message lists every known name
         */
        public Builder option(String name, String value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            RunnerJarOption option = RunnerJarOption.named(name)
                    .orElseThrow(() -> RunnerJarOption.unknown(name));
            return option(option, value);
        }

        /**
         * Dispatches an option to its typed setter. The switch is exhaustive, so an option added to the table
         * without a case here does not compile.
         */
        private Builder option(RunnerJarOption option, String value) {
            return switch (option) {
                case COMPRESSION -> compression(Compression.parse(value));
                case ENTRY_STUB -> entryStub(parseBoolean(option, value));
                case MULTI_RELEASE -> multiRelease(parseBoolean(option, value));
                case ENABLE_NATIVE_ACCESS -> enableNativeAccess(parseBoolean(option, value));
                case ADD_OPENS -> addOpens(parseList(value));
                case ADD_EXPORTS -> addExports(parseList(value));
                case MANIFEST_ATTRIBUTES -> manifestAttributes(parseAttributes(option, value));
                case ARCHIVE_READS -> archiveReads(ArchiveReads.parse(value));
                case PRECOMPILE_LOGBACK -> precompileLogback(parseBoolean(option, value));
                case STRIP_LOCAL_VARIABLES -> stripLocalVariables(parseBoolean(option, value));
                case STARTUP_CLASSES -> startupClasses(parsePath(option, value));
                case STATIC_SERVICES -> staticServices(parseBoolean(option, value));
                case DESUGAR_LAMBDAS -> desugarLambdas(parseBoolean(option, value));
            };
        }

        /**
         * Reads {@code true} or {@code false} and nothing else: {@link Boolean#parseBoolean(String)} would
         * read a typo such as {@code yes} as {@code false}.
         */
        private static boolean parseBoolean(RunnerJarOption option, String value) {
            String text = value.trim();
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
            throw new IllegalArgumentException("Option '" + option.optionName()
                    + "' must be true or false, not '" + value + "'");
        }

        /**
         * Reads a file path. The empty string is no file, which is how an option without a default is
         * reported and therefore how it is read back.
         */
        private static Path parsePath(RunnerJarOption option, String value) {
            if (value.isBlank()) {
                return null;
            }
            try {
                return Path.of(value);
            } catch (InvalidPathException e) {
                throw new IllegalArgumentException("Option '" + option.optionName() + "' is not a file path: '"
                        + value + "'", e);
            }
        }

        private static List<String> parseList(String value) {
            List<String> entries = new ArrayList<>();
            for (String entry : value.split(",", -1)) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    entries.add(trimmed);
                }
            }
            return entries;
        }

        private static Map<String, String> parseAttributes(RunnerJarOption option, String value) {
            Map<String, String> attributes = new LinkedHashMap<>();
            Set<String> seen = new HashSet<>();
            for (String line : value.split("\\R", -1)) {
                if (line.isBlank()) {
                    continue;
                }
                int colon = line.indexOf(':');
                String name = colon < 0 ? "" : line.substring(0, colon).trim();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("Option '" + option.optionName() + "' line '" + line
                            + "' is not a 'Name: value' pair");
                }
                if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Option '" + option.optionName() + "' sets '" + name
                            + "' more than once");
                }
                attributes.put(name, line.substring(colon + 1).strip());
            }
            return attributes;
        }

        /**
         * Sets the instant every entry is dated with.
         *
         * @param value the timestamp, converted to MS-DOS time in UTC
         * @return this builder
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if the year is outside 1980..2107
         */
        public Builder timestamp(Instant value) {
            Objects.requireNonNull(value, "timestamp");
            // Fails here rather than halfway through writing the archive.
            ZipWriter.toDosTime(value);
            this.timestamp = value;
            return this;
        }

        /**
         * Takes a snapshot of the builder.
         *
         * @return the immutable spec
         * @throws IllegalStateException if the main class, the application output or the output file is
         *                               missing
         */
        public RunnerJarSpec build() {
            if (mainClass == null || mainClass.isBlank()) {
                throw new IllegalStateException("A runner jar needs a main class");
            }
            if (applicationOutput.isEmpty()) {
                throw new IllegalStateException("A runner jar needs at least one application output"
                        + " directory or jar");
            }
            if (output == null) {
                throw new IllegalStateException("A runner jar needs an output file");
            }
            return new RunnerJarSpec(this);
        }

        private static <T> List<T> copyOf(List<T> value, String what) {
            Objects.requireNonNull(value, what);
            List<T> copy = new ArrayList<>(value.size());
            for (T element : value) {
                copy.add(Objects.requireNonNull(element, what));
            }
            return copy;
        }
    }
}
