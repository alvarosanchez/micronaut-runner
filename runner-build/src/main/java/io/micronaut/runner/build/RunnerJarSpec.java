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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.Manifest;

/**
 * Everything {@link RunnerJarBuilder} needs to know to produce a runner jar.
 *
 * <p>A spec is immutable and is built through {@link #builder()}. Only the main class, the application
 * output and the output file have to be set; every other option has the default the format was designed
 * around, so the plugins can hand over a spec that mirrors their own conventions without deciding anything
 * the user did not ask about.</p>
 *
 * <pre>{@code
 * RunnerJarSpec spec = RunnerJarSpec.builder()
 *         .mainClass("com.example.Application")
 *         .applicationOutput(List.of(classesDir, resourcesDir))
 *         .dependencies(List.of(new Dependency(nettyJar, "io.netty:netty-common:4.2.1")))
 *         .output(buildDir.resolve("app-all.jar"))
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
     * Whether the packager may generate the entry stub that lets the launcher start the application through
     * an interface call instead of reflection.
     *
     * <p>Generating the stub is not implemented yet: the flag is carried through the spec so that the
     * plugins can already expose it, and the launcher's reflective fallback starts the application in the
     * meantime.</p>
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
     * application manifest, but not {@code Main-Class} or the {@code Micronaut-Runner-*} attributes, which
     * the launcher depends on.</p>
     *
     * <p>The order the caller configured is preserved, because the manifest is written by iterating this
     * map and a runner jar has to be byte for byte reproducible.</p>
     *
     * @return the extra attributes, in the order they were configured
     */
    public Map<String, String> manifestAttributes() {
        return manifestAttributes;
    }

    /**
     * The {@code Add-Opens} manifest attribute values.
     *
     * @return the module/package pairs to open, or an empty list
     */
    public List<String> addOpens() {
        return addOpens;
    }

    /**
     * The {@code Add-Exports} manifest attribute values.
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
     * Describes a runner jar step by step.
     *
     * <p>The builder is mutable and is not thread safe; {@link #build()} takes a snapshot of it, so it can
     * be reused afterwards.</p>
     */
    public static final class Builder {

        private String mainClass;
        private List<Path> applicationOutput = new ArrayList<>();
        private Path applicationManifestSource;
        private Manifest applicationManifest;
        private List<Dependency> dependencies = new ArrayList<>();
        private Path output;
        private Compression compression = Compression.STORED;
        private boolean multiRelease;
        private boolean entryStub;
        private Map<String, String> manifestAttributes = new LinkedHashMap<>();
        private List<String> addOpens = new ArrayList<>();
        private List<String> addExports = new ArrayList<>();
        private boolean enableNativeAccess;
        private Instant timestamp = ZipWriter.DEFAULT_TIMESTAMP;

        private Builder() {
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
         * Requests the generated entry stub, which is not implemented yet and is ignored.
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
         * @param value the attributes, in the order they should be written
         * @return this builder
         * @throws NullPointerException if the map, a key or a value is {@code null}
         */
        public Builder manifestAttributes(Map<String, String> value) {
            Objects.requireNonNull(value, "manifestAttributes");
            Map<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> attribute : value.entrySet()) {
                Objects.requireNonNull(attribute.getKey(), "manifest attribute name");
                Objects.requireNonNull(attribute.getValue(), "manifest attribute value");
                copy.put(attribute.getKey(), attribute.getValue());
            }
            this.manifestAttributes = copy;
            return this;
        }

        /**
         * Sets the {@code Add-Opens} values.
         *
         * @param value the module/package pairs
         * @return this builder
         * @throws NullPointerException if the list or an element is {@code null}
         */
        public Builder addOpens(List<String> value) {
            this.addOpens = copyOf(value, "addOpens");
            return this;
        }

        /**
         * Sets the {@code Add-Exports} values.
         *
         * @param value the module/package pairs
         * @return this builder
         * @throws NullPointerException if the list or an element is {@code null}
         */
        public Builder addExports(List<String> value) {
            this.addExports = copyOf(value, "addExports");
            return this;
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
