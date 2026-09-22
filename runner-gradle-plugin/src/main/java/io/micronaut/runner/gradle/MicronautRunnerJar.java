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
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Packages the application and its dependencies into a runner jar.
 *
 * <p>Every property has a convention taken from the project, so the task normally needs no configuration
 * at all. See the plugin documentation for the properties worth overriding.</p>
 *
 * @since 1.0
 */
@CacheableTask
public abstract class MicronautRunnerJar extends DefaultTask {

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
     * The jar produced by the {@code jar} task, used only as the source of the application's own manifest
     * attributes. A directory of classes cannot carry a manifest, so without this the archive would lose
     * attributes such as {@code Implementation-Version}.
     *
     * @return the application jar
     */
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getApplicationJar();

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
     * Packages to open, written into the manifest as {@code Add-Opens} so users need not pass the flag.
     *
     * @return the packages to open
     */
    @Input
    public abstract ListProperty<String> getAddOpens();

    /**
     * Packages to export, written into the manifest as {@code Add-Exports}.
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
     * The archive of a shading task that may write to the same location.
     *
     * @return the possible conflicting archive
     * @deprecated collision validation is now performed before both archive producers by
     *             {@link ValidateShadowArchiveCollision}; this compatibility property is ignored
     */
    @Deprecated
    @Internal
    public abstract RegularFileProperty getConflictingArchive();

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
            File jar = getApplicationJar().get().getAsFile();
            if (jar.isFile()) {
                spec.applicationManifest(jar.toPath());
            }
        }
        return spec.build();
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
