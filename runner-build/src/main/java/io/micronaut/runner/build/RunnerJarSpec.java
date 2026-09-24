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
