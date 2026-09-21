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
package io.micronaut.runner.maven;

import io.micronaut.runner.build.BuildLogger;
import io.micronaut.runner.build.Compression;
import io.micronaut.runner.build.Dependency;
import io.micronaut.runner.build.RunnerJarBuilder;
import io.micronaut.runner.build.RunnerJarResult;
import io.micronaut.runner.build.RunnerJarSpec;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Packages a Micronaut application as a runner jar: one executable archive in which every dependency
 * remains an intact nested jar.
 *
 * <p>Bind it to the {@code package} phase so it runs after {@code maven-jar-plugin}. By default it
 * replaces the project's main artifact, exactly as the Shade plugin does, keeping the original jar as
 * {@code original-<finalName>.jar}.</p>
 *
 * @since 1.0
 */
@Mojo(name = "package",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.RUNTIME,
        threadSafe = true)
public class PackageMojo extends AbstractMojo {

    private static final String DEFAULT_TIMESTAMP = "1980-02-01T00:00:00Z";

    /** The project being built. */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Attaches the archive when a classifier is set. */
    @Component
    private MavenProjectHelper projectHelper;

    /**
     * The application main class. The Micronaut parent POM already sets {@code exec.mainClass}.
     */
    @Parameter(property = "micronaut.runner.mainClass", defaultValue = "${exec.mainClass}", required = true)
    private String mainClass;

    /** Where the archive is written. */
    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private File outputDirectory;

    /** The base name of the archive. */
    @Parameter(defaultValue = "${project.build.finalName}", required = true)
    private String finalName;

    /**
     * When empty, the archive replaces the project's main artifact and the jar produced by
     * {@code maven-jar-plugin} is kept as {@code original-<finalName>.jar}. When set, the archive is
     * attached as an additional artifact and the main one is left alone.
     */
    @Parameter(property = "micronaut.runner.classifier")
    private String classifier;

    /**
     * How the entries of each dependency are stored: {@code STORED} re-packs them uncompressed so classes
     * are defined straight from the memory-mapped archive, {@code PRESERVE} copies each dependency byte
     * for byte.
     */
    @Parameter(property = "micronaut.runner.compression", defaultValue = "STORED")
    private String compression;

    /** Whether the application layer itself is multi-release. */
    @Parameter(property = "micronaut.runner.multiRelease", defaultValue = "false")
    private boolean multiRelease;

    /** Whether to generate the entry stub that avoids reflection when entering the application. */
    @Parameter(property = "micronaut.runner.entryStub", defaultValue = "true")
    private boolean entryStub;

    /** Extra main manifest attributes. */
    @Parameter
    private Map<String, String> manifestEntries;

    /** Packages to open, written into the manifest as {@code Add-Opens}. */
    @Parameter
    private List<String> addOpens;

    /** Packages to export, written into the manifest as {@code Add-Exports}. */
    @Parameter
    private List<String> addExports;

    /** Whether to write {@code Enable-Native-Access: ALL-UNNAMED} into the manifest. */
    @Parameter(property = "micronaut.runner.enableNativeAccess", defaultValue = "false")
    private boolean enableNativeAccess;

    /**
     * The reproducible build timestamp, as an ISO-8601 instant or as seconds since the epoch. Defaults to
     * the project's {@code project.build.outputTimestamp}.
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;

    /** Skips the goal. */
    @Parameter(property = "micronaut.runner.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping runner jar packaging");
            return;
        }
        File classes = new File(project.getBuild().getOutputDirectory());
        if (!classes.isDirectory()) {
            throw new MojoFailureException("No classes to package: " + classes + " does not exist. "
                    + "Bind this goal to the package phase so it runs after compilation.");
        }

        boolean replaceMainArtifact = classifier == null || classifier.isBlank();
        File target = new File(outputDirectory, finalName + (replaceMainArtifact ? "" : "-" + classifier) + ".jar");
        File mainArtifact = new File(outputDirectory, finalName + ".jar");
        File original = new File(outputDirectory, "original-" + finalName + ".jar");

        try {
            // Keep the jar plugin's output, both as the source of the application manifest and as the
            // artifact users expect beside a replaced main artifact. Running the goal twice must not turn
            // a previously written runner jar into the "original".
            File manifestSource = null;
            if (replaceMainArtifact) {
                if (original.isFile()) {
                    manifestSource = original;
                } else if (mainArtifact.isFile()) {
                    Files.move(mainArtifact.toPath(), original.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    manifestSource = original;
                }
            } else if (mainArtifact.isFile()) {
                manifestSource = mainArtifact;
            }

            RunnerJarResult result = RunnerJarBuilder.build(
                    buildSpec(classes, target, manifestSource), new MavenBuildLogger(getLog()));

            if (replaceMainArtifact) {
                project.getArtifact().setFile(target);
            } else {
                projectHelper.attachArtifact(project, "jar", classifier, target);
            }
            getLog().info("Runner jar written to " + target + " (" + (result.dependencyCount())
                    + " dependencies, " + result.entryCount() + " entries)");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to package " + target, e);
        }
    }

    private RunnerJarSpec buildSpec(File classes, File target, File manifestSource) {
        List<Dependency> dependencies = new ArrayList<>();
        for (Artifact artifact : project.getArtifacts()) {
            if (!Artifact.SCOPE_COMPILE.equals(artifact.getScope())
                    && !Artifact.SCOPE_RUNTIME.equals(artifact.getScope())) {
                continue;
            }
            File file = artifact.getFile();
            if (file == null || !file.isFile()) {
                getLog().warn("Skipping " + artifact + ": it has no resolved file");
                continue;
            }
            if (!file.getName().endsWith(".jar")) {
                getLog().warn("Skipping " + artifact + ": only jar dependencies can be nested");
                continue;
            }
            dependencies.add(new Dependency(file.toPath(), artifact.getGroupId() + ":"
                    + artifact.getArtifactId() + ":" + artifact.getVersion()));
        }

        Map<String, String> attributes = manifestEntries == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(manifestEntries);

        RunnerJarSpec.Builder spec = RunnerJarSpec.builder()
                .mainClass(mainClass)
                .applicationOutput(List.of(classes.toPath()))
                .dependencies(dependencies)
                .output(target.toPath())
                .compression(compression())
                .multiRelease(multiRelease)
                .entryStub(entryStub)
                .manifestAttributes(attributes)
                .addOpens(addOpens == null ? List.of() : addOpens)
                .addExports(addExports == null ? List.of() : addExports)
                .enableNativeAccess(enableNativeAccess)
                .timestamp(timestamp());

        if (manifestSource != null) {
            spec.applicationManifest(manifestSource.toPath());
        }
        return spec.build();
    }

    private Compression compression() {
        try {
            return Compression.valueOf(compression.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown compression '" + compression
                    + "'. Supported values are STORED and PRESERVE.", e);
        }
    }

    /**
     * Resolves the reproducible timestamp the way the Maven reproducible build specification defines it:
     * either an ISO-8601 instant or seconds since the epoch. An unset or unresolved property falls back to
     * the format's own fixed default.
     */
    private Instant timestamp() {
        if (outputTimestamp == null || outputTimestamp.isBlank() || outputTimestamp.startsWith("${")) {
            return Instant.parse(DEFAULT_TIMESTAMP);
        }
        String value = outputTimestamp.trim();
        if (value.length() > 1 && value.chars().allMatch(Character::isDigit)) {
            return Instant.ofEpochSecond(Long.parseLong(value));
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            getLog().warn("Could not parse project.build.outputTimestamp '" + value
                    + "', falling back to " + DEFAULT_TIMESTAMP);
            return Instant.parse(DEFAULT_TIMESTAMP);
        }
    }

    /** Bridges the packaging library's log calls to Maven's logger. */
    private static final class MavenBuildLogger implements BuildLogger {

        private final Log log;

        private MavenBuildLogger(Log log) {
            this.log = log;
        }

        @Override
        public void info(String message) {
            log.info(message);
        }

        @Override
        public void warn(String message) {
            log.warn(message);
        }
    }

    /**
     * Reads a path relative to the output directory, used by the tests to assert where the archive lands.
     *
     * @return the output directory
     */
    Path outputDirectory() {
        return outputDirectory.toPath();
    }
}
