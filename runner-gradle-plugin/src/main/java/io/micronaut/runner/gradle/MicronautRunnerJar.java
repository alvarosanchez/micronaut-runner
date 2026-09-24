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

import io.micronaut.runner.build.ApplicationManifest;
import io.micronaut.runner.build.BuildLogger;
import io.micronaut.runner.build.Compression;
import io.micronaut.runner.build.Dependency;
import io.micronaut.runner.build.RunnerJarBuilder;
import io.micronaut.runner.build.RunnerJarOption;
import io.micronaut.runner.build.RunnerJarResult;
import io.micronaut.runner.build.RunnerJarSpec;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Packages the application and its dependencies into a runner jar.
 *
 * <p>The plugin takes the task's inputs from the project: conventions for its properties, and the
 * {@code jar} task's manifest configuration for the application's own manifest attributes. The task
 * therefore normally needs no configuration at all. See the plugin documentation for the properties worth
 * overriding.</p>
 *
 * <p>The task is wiring: it hands the project's facts and the options the build sets to the packaging
 * library, which owns every option's default, parsing and validation. A {@link RunnerJarOption.Exposure#TYPED
 * typed} option has a property here; every option, typed or not, can also be set by name through
 * {@link #getOptions()}. Each option property takes the {@link MicronautRunnerExtension}'s value as its
 * convention, so a value set here wins for this task only. The archive is named as an archive task names
 * it, from {@link #getArchiveBaseName()}, {@link #getArchiveVersion()}, {@link #getArchiveClassifier()} and
 * {@link #getDestinationDirectory()}.</p>
 *
 * @since 1.0
 */
@CacheableTask
public abstract class MicronautRunnerJar extends DefaultTask {

    private final RegularFileProperty archiveFile;

    private @Nullable Manifest inheritedManifest;

    /** Creates the task, deriving the archive's location from its naming properties. */
    public MicronautRunnerJar() {
        // A property rather than a derived provider, so that Gradle records this task as its producer.
        archiveFile = getProject().getObjects().fileProperty();
        archiveFile.set(getDestinationDirectory().file(getArchiveBaseName()
                .zip(getArchiveVersion().orElse(""), MicronautRunnerJar::appendNamePart)
                .zip(getArchiveClassifier().orElse(""), MicronautRunnerJar::appendNamePart)
                .map(name -> name + ".jar")));
        archiveFile.disallowChanges();
    }

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
     * What the packaging library reads from the {@linkplain #getInheritedManifest() inherited manifest}, as
     * {@link ApplicationManifest#filter(Map, Map)} keeps it: the six {@code Specification-*} and
     * {@code Implementation-*} attributes and {@code Sealed}, keyed by {@code Name} in the main section and by
     * {@code <section>Name} in a package section, whose name ends in {@code /}. Every other attribute and
     * section is left out, so it cannot invalidate the archive.
     *
     * <p>Empty when there is no inherited manifest, or when {@link #getApplicationJar()} overrides it.</p>
     *
     * @return the consumed attributes, sorted by key
     */
    @Input
    public SortedMap<String, String> getInheritedManifestAttributes() {
        if (inheritedManifest == null || getApplicationJar().isPresent()) {
            return new TreeMap<>();
        }
        Manifest effective = inheritedManifest.getEffectiveManifest();
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        effective.getSections().forEach((section, attributes) -> sections.put(section, resolve(attributes)));
        return ApplicationManifest.filter(resolve(effective.getAttributes()), sections);
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
     * Where the archive is written: {@code <destinationDirectory>/<archiveBaseName>[-<archiveVersion>]
     * [-<archiveClassifier>].jar}, as an archive task names it. It is derived from the naming properties and
     * cannot be set.
     *
     * @return the output file
     */
    @OutputFile
    public Provider<RegularFile> getArchiveFile() {
        return archiveFile;
    }

    /**
     * The base name of the archive. The plugin sets {@code base.archivesName} as the convention.
     *
     * @return the base name
     */
    @Internal("Represented as part of archiveFile")
    public abstract Property<String> getArchiveBaseName();

    /**
     * The version in the archive name, left out when absent. The plugin sets the project version as the
     * convention, absent when it is empty or {@code unspecified}.
     *
     * @return the version
     */
    @Internal("Represented as part of archiveFile")
    public abstract Property<String> getArchiveVersion();

    /**
     * The classifier in the archive name, left out when absent or empty. The plugin sets {@code all} as the
     * convention.
     *
     * @return the classifier
     */
    @Internal("Represented as part of archiveFile")
    public abstract Property<String> getArchiveClassifier();

    /**
     * The directory the archive is written to. The plugin sets {@code base.libsDirectory} as the convention.
     *
     * @return the directory
     */
    @Internal("Represented as part of archiveFile")
    public abstract DirectoryProperty getDestinationDirectory();

    /**
     * How the entries of each dependency are stored: {@code STORED} re-packs them uncompressed so classes
     * are defined straight from the memory-mapped archive, {@code PRESERVE} copies each dependency byte
     * for byte. The plugin sets the extension's value as the convention.
     *
     * @return the compression mode
     */
    @Input
    @Optional
    public abstract Property<String> getCompression();

    /**
     * Whether the application layer itself is multi-release. The plugin sets the extension's value as the
     * convention.
     *
     * @return the multi-release flag
     */
    @Input
    @Optional
    public abstract Property<Boolean> getMultiRelease();

    /**
     * Whether to generate the entry stub that lets the launcher call the application main method through
     * an interface rather than by reflection. The plugin sets the extension's value as the convention.
     *
     * @return the entry stub flag
     */
    @Input
    @Optional
    public abstract Property<Boolean> getEntryStub();

    /**
     * Module/package pairs to open, written into the manifest as {@code Add-Opens} so users need not pass
     * the flag. Each entry uses JAR manifest syntax, for example {@code java.base/java.lang}, without the
     * command-line-only {@code =ALL-UNNAMED} suffix. The plugin sets the extension's value as the convention.
     *
     * @return the packages to open
     */
    @Input
    @Optional
    public abstract ListProperty<String> getAddOpens();

    /**
     * Module/package pairs to export, written into the manifest as {@code Add-Exports}. Each entry uses JAR
     * manifest syntax, for example {@code java.base/sun.nio.ch}, without the command-line-only
     * {@code =ALL-UNNAMED} suffix. The plugin sets the extension's value as the convention.
     *
     * @return the packages to export
     */
    @Input
    @Optional
    public abstract ListProperty<String> getAddExports();

    /**
     * Whether to write {@code Enable-Native-Access: ALL-UNNAMED} into the manifest. The plugin sets the
     * extension's value as the convention.
     *
     * @return the native access flag
     */
    @Input
    @Optional
    public abstract Property<Boolean> getEnableNativeAccess();

    /**
     * Extra main manifest attributes. The plugin sets the extension's value as the convention.
     *
     * @return the attributes
     */
    @Input
    @Optional
    public abstract MapProperty<String, String> getManifestAttributes();

    /**
     * Packaging options by {@linkplain RunnerJarOption#optionName() name}, for the options that have no
     * typed property here. Each value uses the grammar {@link RunnerJarOption} documents, and the packaging
     * library parses and validates it; an unknown name fails the task. An entry is applied after the typed
     * properties, so it wins over a typed property of the same option. The plugin sets the extension's value
     * as the convention.
     *
     * @return the options by name
     */
    @Input
    public abstract MapProperty<String, String> getOptions();

    /**
     * Builds the archive.
     *
     * @throws IOException if the archive cannot be written
     */
    @TaskAction
    public void packageArchive() throws IOException {
        File output = getArchiveFile().get().getAsFile();
        RunnerJarResult result = RunnerJarBuilder.build(buildSpec(output), new GradleBuildLogger(getLogger()));
        getLogger().lifecycle(result.summary());
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
            // A file dependency resolves to no coordinates at all.
            String gav = coordinates.get(file.getAbsolutePath());
            dependencies.add(gav == null ? Dependency.of(file.toPath()) : Dependency.of(file.toPath(), gav));
        }

        RunnerJarSpec.Builder spec = RunnerJarSpec.builder()
                .mainClass(getMainClass().get())
                .applicationOutput(applicationOutput)
                .dependencies(dependencies)
                .output(output.toPath());

        // Typed values first and the options last, because the last call wins. An absent value leaves the
        // packaging library's default in place.
        if (getCompression().isPresent()) {
            spec.compression(Compression.parse(getCompression().get()));
        }
        if (getEntryStub().isPresent()) {
            spec.entryStub(getEntryStub().get());
        }
        if (getMultiRelease().isPresent()) {
            spec.multiRelease(getMultiRelease().get());
        }
        if (getEnableNativeAccess().isPresent()) {
            spec.enableNativeAccess(getEnableNativeAccess().get());
        }
        if (getAddOpens().isPresent()) {
            spec.addOpens(getAddOpens().get());
        }
        if (getAddExports().isPresent()) {
            spec.addExports(getAddExports().get());
        }
        if (getManifestAttributes().isPresent()) {
            spec.manifestAttributes(getManifestAttributes().get());
        }
        getOptions().get().forEach(spec::option);

        if (getApplicationJar().isPresent()) {
            // @InputFile validation has already established that the file exists.
            spec.applicationManifest(getApplicationJar().get().getAsFile().toPath());
        } else if (inheritedManifest != null) {
            // Passed even when empty, so the builder never falls back to a META-INF/MANIFEST.MF among the
            // application's resources.
            spec.applicationManifest(ApplicationManifest.toManifest(getInheritedManifestAttributes()));
        }
        return spec.build();
    }

    /**
     * Appends one part of an archive name, as an archive task does: after a dash, and not at all when empty.
     *
     * @param name the name so far
     * @param part the part, possibly empty
     * @return the name with the part
     */
    private static String appendNamePart(String name, String part) {
        return part.isEmpty() ? name : name + "-" + part;
    }

    /**
     * Resolves the attributes of one section of a Gradle manifest that the packaging library reads, the way
     * the {@code jar} task does when it writes the file: a provider is unwrapped and an absent value left
     * out. An attribute the library does not read is not resolved at all.
     *
     * @param attributes the section's attributes
     * @return the consumed attributes, as strings
     */
    private static Map<String, String> resolve(Map<String, Object> attributes) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
            String name = attribute.getKey();
            if (name == null || !ApplicationManifest.isConsumed(name)) {
                continue;
            }
            Object value = attribute.getValue();
            if (value instanceof Provider<?> provider) {
                value = provider.getOrNull();
            }
            if (value != null) {
                resolved.put(name, value.toString());
            }
        }
        return resolved;
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
