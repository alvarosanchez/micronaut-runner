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
package io.micronaut.runner.gradle;

import io.micronaut.runner.build.BuildLogger;
import io.micronaut.runner.build.Compression;
import io.micronaut.runner.build.Dependency;
import io.micronaut.runner.build.RunnerJarBuilder;
import io.micronaut.runner.build.RunnerJarResult;
import io.micronaut.runner.build.RunnerJarSpec;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.java.archives.Manifest;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.jar.Attributes;

/**
 * Packages the application and its dependencies into a runner jar.
 *
 * <p>The plugin takes the task's inputs from the project: conventions for its properties, and the
 * {@code jar} task's manifest configuration for the application's own manifest attributes. The task
 * therefore normally needs no configuration at all. See the plugin documentation for the properties worth
 * overriding.</p>
 *
 * @since 1.0
 */
@CacheableTask
public abstract class MicronautRunnerJar extends DefaultTask {

    /**
     * The attributes the packaging library reads from the application's manifest, keyed by lower-case name:
     * the six {@code Specification-*} and {@code Implementation-*} attributes and {@code Sealed}, in the
     * main section and in package sections. The library ignores every other attribute.
     */
    private static final Map<String, String> CONSUMED = consumed(
            "Specification-Title", "Specification-Version", "Specification-Vendor",
            "Implementation-Title", "Implementation-Version", "Implementation-Vendor",
            "Sealed");

    private @Nullable Manifest inheritedManifest;

    /**
     * The application main class. Defaults to the main class of the {@code application} plugin.
     *
     * @return the main class name
     */
    @Input
    public abstract Property<String> getMainClass();

    /**
     * The application's own classes and resources, which become the application layer of the archive.
     *
     * @return the application output
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getApplicationOutput();

    /**
     * An explicit override: a jar whose manifest supplies the application's own manifest attributes, for a
     * manifest that the {@code jar} task's configuration cannot describe. It has no default.
     *
     * <p>Without it, the six {@code Implementation-*} and {@code Specification-*} attributes, {@code Sealed}
     * and the package sections come from the {@code jar} task's manifest configuration, including
     * {@code manifest.from(...)} merges, and this task does not build the thin JAR. When attributes are added
     * in the {@code jar} task's {@code doFirst} or {@code doLast}, or merged from a file that another task
     * generates, set it to {@code tasks.named('jar').flatMap { it.archiveFile }}.</p>
     *
     * @return the jar whose manifest supplies the application's attributes
     */
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getApplicationJar();

    /**
     * The manifest configuration the application's own manifest attributes are taken from; the plugin sets
     * the {@code jar} task's. It is read when this task executes, not when it is configured, so an attribute
     * whose value is a provider, or a merged manifest file, is read as it is then. Only the attributes the
     * archive carries are inputs: see {@link #getInheritedManifestAttributes()}.
     *
     * @return the manifest configuration, or {@code null} when none was set
     */
    @Internal
    public @Nullable Manifest getInheritedManifest() {
        return inheritedManifest;
    }

    /**
     * Sets the manifest configuration the application's own manifest attributes are taken from.
     *
     * @param manifest the manifest configuration, or {@code null} for none
     */
    public void setInheritedManifest(@Nullable Manifest manifest) {
        inheritedManifest = manifest;
    }

    /**
     * What the packaging library reads from the {@linkplain #getInheritedManifest() inherited manifest}: the
     * six {@code Specification-*} and {@code Implementation-*} attributes and {@code Sealed}, keyed by
     * {@code Name} in the main section and by {@code <section>Name} in a package section, whose name ends in
     * {@code /}. Every other attribute and section is left out, so it cannot invalidate the archive.
     *
     * <p>Empty when there is no inherited manifest, or when {@link #getApplicationJar()} overrides it.</p>
     *
     * @return the consumed attributes, sorted by key
     */
    @Input
    public SortedMap<String, String> getInheritedManifestAttributes() {
        SortedMap<String, String> result = new TreeMap<>();
        if (inheritedManifest == null || getApplicationJar().isPresent()) {
            return result;
        }
        Manifest effective = inheritedManifest.getEffectiveManifest();
        collect(effective.getAttributes(), "", result);
        effective.getSections().forEach((section, attributes) -> {
            if (section.endsWith("/")) {
                collect(attributes, section, result);
            }
        });
        return result;
    }

    /**
     * The dependencies, in runtime classpath resolution order.
     *
     * <p>This is deliberately not annotated {@code @Classpath}: that normalisation ignores the order of a
     * jar's entries and their timestamps, which is exactly what {@code PRESERVE} compression reproduces
     * byte for byte. File names matter too, because a dependency keeps its file name inside the archive.
     *
     * @return the dependency files
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getClasspath();

    /**
     * Maven coordinates of the dependencies, keyed by absolute file path, recorded in the index so tooling
     * can report what an archive contains. Not an input: it is derived from {@link #getClasspath()}.
     *
     * @return the coordinates by file path
     */
    @Internal
    public abstract MapProperty<String, String> getCoordinates();

    /**
     * The ordered, relocatable identity of every dependency used to build the archive.
     *
     * <p>The file collection above retains task dependency inference. This nested sequence adds the ordering
     * Gradle's file collection snapshot does not retain and pairs each position with its file name,
     * coordinates and raw bytes. Path sensitivity is deliberately {@code NONE}: moving an otherwise
     * identical project must not change its cache key.</p>
     *
     * @return dependency inputs in runtime classpath order
     */
    @Nested
    public List<DependencyInput> getDependencyInputs() {
        Map<String, String> coordinates = getCoordinates().get();
        List<DependencyInput> inputs = new ArrayList<>();
        for (File file : getClasspath().getFiles()) {
            String gav = coordinates.get(file.getAbsolutePath());
            inputs.add(gav == null ? new DependencyInput(file) : new DependencyInput(file, gav));
        }
        return inputs;
    }

    /**
     * Where the archive is written.
     *
     * @return the output file
     */
    @OutputFile
    public abstract RegularFileProperty getArchiveFile();

    /**
     * The archive classifier used to build the default output file name. Defaults to {@code all}.
     *
     * @return the classifier
     */
    @Input
    public abstract Property<String> getArchiveClassifier();

    /**
     * How the entries of each dependency are stored: {@code STORED} re-packs them uncompressed so classes
     * are defined straight from the memory-mapped archive, {@code PRESERVE} copies each dependency byte
     * for byte.
     *
     * @return the compression mode
     */
    @Input
    public abstract Property<String> getCompression();

    /**
     * Whether the application layer itself is multi-release.
     *
     * @return the multi-release flag
     */
    @Input
    public abstract Property<Boolean> getMultiRelease();

    /**
     * Whether to generate the entry stub that lets the launcher call the application main method through
     * an interface rather than by reflection.
     *
     * @return the entry stub flag
     */
    @Input
    public abstract Property<Boolean> getEntryStub();

    /**
     * Module/package pairs to open, written into the manifest as {@code Add-Opens} so users need not pass
     * the flag. Each entry uses JAR manifest syntax, for example {@code java.base/java.lang}, without the
     * command-line-only {@code =ALL-UNNAMED} suffix.
     *
     * @return the packages to open
     */
    @Input
    public abstract ListProperty<String> getAddOpens();

    /**
     * Module/package pairs to export, written into the manifest as {@code Add-Exports}. Each entry uses JAR
     * manifest syntax, for example {@code java.base/sun.nio.ch}, without the command-line-only
     * {@code =ALL-UNNAMED} suffix.
     *
     * @return the packages to export
     */
    @Input
    public abstract ListProperty<String> getAddExports();

    /**
     * Whether to write {@code Enable-Native-Access: ALL-UNNAMED} into the manifest.
     *
     * @return the native access flag
     */
    @Input
    public abstract Property<Boolean> getEnableNativeAccess();

    /**
     * Extra main manifest attributes.
     *
     * @return the attributes
     */
    @Input
    public abstract MapProperty<String, String> getManifestAttributes();

    /**
     * The project's target Java version, reported in the build log to make a mismatch between the JDK that
     * compiled the application and the JDK that will run it easy to spot.
     *
     * @return the target version
     */
    @Internal
    public abstract Property<String> getJavaLauncherVersion();

    /**
     * Builds the archive.
     *
     * @throws IOException if the archive cannot be written
     */
    @TaskAction
    public void packageArchive() throws IOException {
        File output = getArchiveFile().get().getAsFile();
        RunnerJarResult result = RunnerJarBuilder.build(buildSpec(output), new GradleBuildLogger(getLogger()));

        getLogger().lifecycle("Runner jar written to {} ({} dependencies, {} entries, {} bytes)",
                output, result.dependencyCount(), result.entryCount(), result.archiveSize());
    }

    private RunnerJarSpec buildSpec(File output) {
        List<Path> applicationOutput = new ArrayList<>();
        for (File file : getApplicationOutput().getFiles()) {
            if (file.exists()) {
                applicationOutput.add(file.toPath());
            }
        }

        Map<String, String> coordinates = getCoordinates().get();
        List<Dependency> dependencies = new ArrayList<>();
        for (File file : getClasspath().getFiles()) {
            // A file dependency resolves to no coordinates at all; Dependency has a constructor for that
            // case, which also keeps the nullable map lookup out of the two-argument constructor.
            String gav = coordinates.get(file.getAbsolutePath());
            dependencies.add(gav == null ? new Dependency(file.toPath())
                    : new Dependency(file.toPath(), gav));
        }

        RunnerJarSpec.Builder spec = RunnerJarSpec.builder()
                .mainClass(getMainClass().get())
                .applicationOutput(applicationOutput)
                .dependencies(dependencies)
                .output(output.toPath())
                .compression(compression())
                .multiRelease(getMultiRelease().get())
                .entryStub(getEntryStub().get())
                .manifestAttributes(getManifestAttributes().get())
                .addOpens(getAddOpens().get())
                .addExports(getAddExports().get())
                .enableNativeAccess(getEnableNativeAccess().get())
                // Fixed, so that the same inputs always produce the same bytes.
                .timestamp(Instant.parse("1980-02-01T00:00:00Z"));

        if (getApplicationJar().isPresent()) {
            // @InputFile validation has already established that the file exists.
            spec.applicationManifest(getApplicationJar().get().getAsFile().toPath());
        } else if (inheritedManifest != null) {
            // Passed even when empty, so the builder never falls back to a META-INF/MANIFEST.MF among the
            // application's resources.
            spec.applicationManifest(toManifest(getInheritedManifestAttributes()));
        }
        return spec.build();
    }

    /**
     * Collects the consumed attributes of one section of a Gradle manifest, resolving each value the way the
     * {@code jar} task does when it writes the file: a provider is unwrapped and an absent value left out.
     *
     * @param attributes the section's attributes
     * @param prefix     the section name, or the empty string for the main section
     * @param result     where the attributes are collected
     */
    private static void collect(Map<String, Object> attributes, String prefix, SortedMap<String, String> result) {
        for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
            String name = consumedName(attribute.getKey());
            if (name == null) {
                continue;
            }
            Object value = attribute.getValue();
            if (value instanceof Provider<?> provider) {
                value = provider.getOrNull();
            }
            String text = value == null ? null : value.toString();
            if (text != null) {
                result.put(prefix + name, text);
            }
        }
    }

    /**
     * The canonical spelling of an attribute the packaging library reads from the application's manifest.
     * Names are compared without regard to case, as {@link Attributes.Name} compares them.
     *
     * @param name an attribute name
     * @return its canonical spelling, or {@code null} when the library does not read it
     */
    private static @Nullable String consumedName(String name) {
        return name == null ? null : CONSUMED.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Builds the application manifest the packaging library reads from collected attributes: a key without a
     * {@code /} is a main attribute, and any other key is split at its last {@code /} into a section name,
     * which keeps the {@code /}, and an attribute name.
     *
     * @param attributes the attributes, as {@link #getInheritedManifestAttributes()} collects them
     * @return the manifest
     */
    private static java.util.jar.Manifest toManifest(SortedMap<String, String> attributes) {
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            String key = attribute.getKey();
            int slash = key.lastIndexOf('/');
            Attributes section = slash < 0
                    ? manifest.getMainAttributes()
                    : manifest.getEntries().computeIfAbsent(key.substring(0, slash + 1), unused -> new Attributes());
            section.putValue(key.substring(slash + 1), attribute.getValue());
        }
        return manifest;
    }

    private static Map<String, String> consumed(String... names) {
        Map<String, String> byLowerCaseName = new TreeMap<>();
        for (String name : names) {
            byLowerCaseName.put(name.toLowerCase(Locale.ROOT), name);
        }
        return Map.copyOf(byLowerCaseName);
    }

    private Compression compression() {
        String value = getCompression().get().trim().toUpperCase(Locale.ROOT);
        try {
            return Compression.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new GradleException("Unknown compression '" + getCompression().get()
                    + "'. Supported values are STORED and PRESERVE.", e);
        }
    }

    /** One position in the ordered dependency input sequence. */
    public static final class DependencyInput {

        private final File file;
        private final String coordinates;

        private DependencyInput(File file) {
            this(file, "");
        }

        private DependencyInput(File file, String coordinates) {
            this.file = file;
            this.coordinates = coordinates;
        }

        /**
         * The dependency bytes, without their machine-specific path.
         *
         * @return the dependency file
         */
        @InputFile
        @PathSensitive(PathSensitivity.NONE)
        public File getFile() {
            return file;
        }

        /**
         * The file name retained in the runner archive.
         *
         * @return the dependency file name
         */
        @Input
        public String getFileName() {
            return file.getName();
        }

        /**
         * The coordinates written into the runner index.
         *
         * @return the coordinates, or an empty string for a file dependency
         */
        @Input
        public String getCoordinates() {
            return coordinates;
        }
    }

    /** Bridges the packaging library's log calls to Gradle's logger. */
    private static final class GradleBuildLogger implements BuildLogger {

        private final Logger logger;

        private GradleBuildLogger(Logger logger) {
            this.logger = logger;
        }

        @Override
        public void info(String message) {
            logger.info(message);
        }

        @Override
        public void warn(String message) {
            logger.warn(message);
        }
    }
}
