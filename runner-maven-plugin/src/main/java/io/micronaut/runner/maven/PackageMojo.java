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
import io.micronaut.runner.build.RunnerJarOption;
import io.micronaut.runner.build.RunnerJarReader;
import io.micronaut.runner.build.RunnerJarResult;
import io.micronaut.runner.build.RunnerJarSpec;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Packages a Micronaut application as a runner jar: one executable archive in which every dependency
 * remains an intact nested jar.
 *
 * <p>Bind it to the {@code package} phase so it runs after {@code maven-jar-plugin}. By default it
 * replaces the project's main artifact, exactly as the Shade plugin does, keeping the original jar as
 * {@code original-<finalName>.jar}.</p>
 *
 * <p>The goal is wiring: it hands the project's facts and the options the build sets to the packaging
 * library, which owns every option's default, parsing and validation. A
 * {@link RunnerJarOption.Exposure#TYPED typed} option has a parameter here that is passed only when it is set;
 * every option can also be set by name through {@code <runnerOptions>}, and a
 * {@link RunnerJarOption.Exposure#PASSTHROUGH passthrough} option through the
 * {@code micronaut.runner.<name>} user or project property.</p>
 *
 * <p>This is the interim plugin. micronaut-maven-plugin's {@code runner} packaging replaces it, and in a
 * project with that packaging the goal fails, even when skipped.</p>
 *
 * @since 1.0
 */
@Mojo(name = "package",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.RUNTIME,
        threadSafe = true)
public class PackageMojo extends AbstractMojo {

    /** The prefix of the properties that set a packaging option by name. */
    private static final String PROPERTY_PREFIX = "micronaut.runner.";

    /** The packaging with which micronaut-maven-plugin builds the Runner JAR itself. */
    private static final String RUNNER_PACKAGING = "runner";

    /** The project being built. */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** The build session, whose user properties ({@code -D}) set passthrough options. */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * The projects of the reactor. A dependency whose group, artifact and version match one of them is a
     * module of this build, whose classes the packaging library never rewrites.
     */
    @Parameter(defaultValue = "${reactorProjects}", readonly = true)
    private List<MavenProject> reactorProjects;

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
     * for byte. Unset, the packaging library's default applies ({@code STORED}).
     */
    @Parameter(property = "micronaut.runner.compression")
    private String compression;

    /**
     * Whether the application layer itself is multi-release. Unset, the packaging library's default applies
     * ({@code false}).
     */
    @Parameter(property = "micronaut.runner.multiRelease")
    private Boolean multiRelease;

    /**
     * Whether to generate the entry stub that avoids reflection when entering the application. Unset, the
     * packaging library's default applies ({@code true}).
     */
    @Parameter(property = "micronaut.runner.entryStub")
    private Boolean entryStub;

    /** Extra main manifest attributes: the {@code manifestAttributes} option. */
    @Parameter
    private Map<String, String> manifestEntries;

    /**
     * Module/package pairs to open, written into the manifest as {@code Add-Opens}. Entries use JAR manifest
     * syntax, such as {@code java.base/java.lang}, without {@code =ALL-UNNAMED}.
     */
    @Parameter
    private List<String> addOpens;

    /**
     * Module/package pairs to export, written into the manifest as {@code Add-Exports}. Entries use JAR
     * manifest syntax, such as {@code java.base/sun.nio.ch}, without {@code =ALL-UNNAMED}.
     */
    @Parameter
    private List<String> addExports;

    /**
     * Whether to write {@code Enable-Native-Access: ALL-UNNAMED} into the manifest. Unset, the packaging
     * library's default applies ({@code false}).
     */
    @Parameter(property = "micronaut.runner.enableNativeAccess")
    private Boolean enableNativeAccess;

    /**
     * Packaging options by name, with values in the grammar {@link RunnerJarOption} documents. An entry wins
     * over the typed parameter and over the {@code micronaut.runner.<name>} property of the same option; an
     * unknown name fails the build.
     */
    @Parameter
    private Map<String, String> runnerOptions;

    /**
     * The reproducible build timestamp, read exactly as {@code maven-jar-plugin} reads it: an ISO-8601
     * instant or seconds since the epoch, with {@code SOURCE_DATE_EPOCH} applying when it is unset or
     * disabled. Defaults to the project's {@code project.build.outputTimestamp}.
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;

    /** Skips the goal. */
    @Parameter(property = "micronaut.runner.skip")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // micronaut-maven-plugin's runner packaging has already replaced the main artifact with a Runner JAR.
        // Running here too would package it again over the same file. skip does not help: the fix is to remove
        // the execution.
        if (RUNNER_PACKAGING.equals(project.getPackaging())) {
            throw new MojoFailureException("micronaut-maven-plugin builds the Runner JAR in runner packaging;"
                    + " remove this plugin's execution");
        }
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

        Path savedThinArtifact = null;
        boolean mainArtifactReplaced = false;
        try {
            // Keep the jar plugin's output, both as the source of the application manifest and as the
            // artifact users expect beside a replaced main artifact. Running the goal twice must not turn
            // a previously written runner jar into the "original".
            File manifestSource = null;
            if (replaceMainArtifact) {
                if (mainArtifact.isFile() && !RunnerJarReader.isRunnerJar(mainArtifact.toPath())) {
                    Files.createDirectories(outputDirectory.toPath());
                    savedThinArtifact = Files.createTempFile(outputDirectory.toPath(),
                            ".micronaut-runner-original-", ".jar");
                    Files.copy(mainArtifact.toPath(), savedThinArtifact, StandardCopyOption.REPLACE_EXISTING);
                    manifestSource = savedThinArtifact.toFile();
                } else if (original.isFile()) {
                    manifestSource = original;
                }
            } else if (mainArtifact.isFile()) {
                manifestSource = mainArtifact;
            }

            RunnerJarResult result = RunnerJarBuilder.build(
                    buildSpec(classes, target, manifestSource), new MavenBuildLogger(getLog()));
            mainArtifactReplaced = replaceMainArtifact;

            if (replaceMainArtifact) {
                if (savedThinArtifact != null) {
                    replace(savedThinArtifact, original.toPath());
                    savedThinArtifact = null;
                }
                project.getArtifact().setFile(target);
            } else {
                projectHelper.attachArtifact(project, "jar", classifier, target);
            }
            getLog().info(result.summary());
        } catch (IOException e) {
            if (mainArtifactReplaced && savedThinArtifact != null && Files.isRegularFile(savedThinArtifact)) {
                getLog().warn("Could not update " + original + "; the thin jar is preserved at "
                        + savedThinArtifact, e);
            }
            throw new MojoExecutionException("Failed to package " + target, e);
        } finally {
            if (savedThinArtifact != null && !mainArtifactReplaced) {
                try {
                    Files.deleteIfExists(savedThinArtifact);
                } catch (IOException e) {
                    getLog().warn("Could not delete temporary thin artifact " + savedThinArtifact, e);
                }
            }
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Hands the project's facts and the configured options to the packaging library.
     *
     * @param classes        the application's classes directory
     * @param target         where the archive is written
     * @param manifestSource the jar whose manifest describes the application, or {@code null}
     * @return the spec
     * @throws MojoFailureException if an option, the timestamp or a dependency is not usable, with the
     *                              packaging library's or maven-archiver's message
     */
    RunnerJarSpec buildSpec(File classes, File target, File manifestSource) throws MojoFailureException {
        Set<String> modules = new HashSet<>();
        if (reactorProjects != null) {
            for (MavenProject module : reactorProjects) {
                modules.add(module.getGroupId() + ":" + module.getArtifactId() + ":" + module.getVersion());
            }
        }
        List<Dependency> dependencies = new ArrayList<>();
        for (Artifact artifact : project.getArtifacts()) {
            if (!Artifact.SCOPE_COMPILE.equals(artifact.getScope())
                    && !Artifact.SCOPE_RUNTIME.equals(artifact.getScope())) {
                continue;
            }
            String coordinates = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                    + artifact.getVersion();
            File file = artifact.getFile();
            if (file == null) {
                // Runtime resolution never produces this; it is a broken reactor or extension.
                throw new MojoFailureException("The dependency " + coordinates + " has no resolved file");
            }
            // The base version, because a snapshot resolved from a repository reports a timestamped version.
            boolean module = modules.contains(artifact.getGroupId() + ":" + artifact.getArtifactId() + ":"
                    + artifact.getBaseVersion());
            dependencies.add(Dependency.of(file.toPath(), coordinates).projectModule(module));
        }

        try {
            RunnerJarSpec.Builder spec = RunnerJarSpec.builder()
                    .mainClass(mainClass)
                    .applicationOutput(List.of(classes.toPath()))
                    .dependencies(dependencies)
                    .output(target.toPath());

            // Typed values first and the options last, because the last call wins. An unset parameter leaves
            // the packaging library's default in place.
            if (compression != null) {
                spec.compression(Compression.parse(compression));
            }
            if (multiRelease != null) {
                spec.multiRelease(multiRelease);
            }
            if (entryStub != null) {
                spec.entryStub(entryStub);
            }
            if (enableNativeAccess != null) {
                spec.enableNativeAccess(enableNativeAccess);
            }
            if (addOpens != null) {
                spec.addOpens(addOpens);
            }
            if (addExports != null) {
                spec.addExports(addExports);
            }
            if (manifestEntries != null) {
                spec.manifestAttributes(new LinkedHashMap<>(manifestEntries));
            }
            List<String> passthrough = Arrays.stream(RunnerJarOption.values())
                    .filter(option -> option.exposure() == RunnerJarOption.Exposure.PASSTHROUGH)
                    .map(RunnerJarOption::optionName)
                    .toList();
            options(session.getUserProperties(), project.getProperties(), runnerOptions, passthrough)
                    .forEach(spec::option);

            // Empty when the property is unset or disabled and SOURCE_DATE_EPOCH is not set, so the packaging
            // library's own fixed timestamp applies.
            MavenArchiver.parseBuildOutputTimestamp(outputTimestamp).ifPresent(spec::timestamp);

            if (manifestSource != null) {
                spec.applicationManifest(manifestSource.toPath());
            }
            return spec.build();
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage(), e);
        }
    }

    /**
     * Collects the options set by name. A passthrough option is read from the user property
     * {@code micronaut.runner.<name>}, or else from the project property of that name, as {@code -D} wins over
     * {@code <properties>}. A {@code <runnerOptions>} entry, for any option, wins over both, as POM
     * configuration wins over {@code -D} for any parameter.
     *
     * @param userProperties    the session's user properties
     * @param projectProperties the project's properties
     * @param configured        the {@code <runnerOptions>} entries, or {@code null} when there are none
     * @param passthroughNames  the names of the passthrough options, in table order
     * @return the options by name, in the order they are applied
     */
    static Map<String, String> options(Properties userProperties, Properties projectProperties,
            Map<String, String> configured, Collection<String> passthroughNames) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String name : passthroughNames) {
            String value = userProperties.getProperty(PROPERTY_PREFIX + name);
            if (value == null) {
                value = projectProperties.getProperty(PROPERTY_PREFIX + name);
            }
            if (value != null) {
                options.put(name, value);
            }
        }
        if (configured != null) {
            // An empty element, such as <addOpens/>, reaches the map as null: the empty value.
            configured.forEach((name, value) -> options.put(name, value == null ? "" : value));
        }
        return options;
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

}
