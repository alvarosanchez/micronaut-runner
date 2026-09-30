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

import io.micronaut.runner.build.StartupProfileRecorder;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.ToolchainManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Records the startup profile of the application: launches the runner jar that {@code mn-runner:package}
 * wrote, waits until it is ready, sends it the training workload and writes the classes it loaded to
 * {@value io.micronaut.runner.build.StartupProfileRecorder#PROFILE_LOCATION}. The file is meant to be
 * committed; the next {@code mn-runner:package} embeds it, and the launcher preloads those classes.
 *
 * <p>The goal launches the application, so it is bound to no phase: run it after {@code package}, as in
 * {@code mvn package mn-runner:record-startup-profile}. Whether it succeeds, fails or is interrupted, the
 * application and every process the application started are gone when it ends. The JDK is the one the {@code maven-toolchains-plugin} selected,
 * or the one that runs Maven. The training settings are the {@code micronaut.runner.training.*}
 * properties.</p>
 *
 * <p>The goal is experimental: it may change in any release.</p>
 *
 * @since 1.0
 */
@Mojo(name = "record-startup-profile", threadSafe = true)
public class RecordStartupProfileMojo extends AbstractTrainingMojo {

    /** The command that runs this goal again, which the profile's header carries. */
    static final String RERECORD_COMMAND = "mvn package mn-runner:record-startup-profile";

    /** The project whose archive is recorded. */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** The build session, in which a toolchain may have been selected. */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /** Finds the JDK the toolchains plugin selected. */
    @Component
    private ToolchainManager toolchainManager;

    /** Where {@code mn-runner:package} wrote the archive. */
    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private File outputDirectory;

    /** The base name of the archive. */
    @Parameter(defaultValue = "${project.build.finalName}", required = true)
    private String finalName;

    /** The classifier {@code mn-runner:package} attached the archive with, if any. */
    @Parameter(property = "micronaut.runner.classifier")
    private String classifier;

    /** Creates the goal; Maven sets its parameters. */
    public RecordStartupProfileMojo() {
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        File archive = PackageMojo.archiveFile(outputDirectory, finalName, classifier);
        if (!MojoSupport.isRunnerJar(archive)) {
            throw new MojoFailureException("There is no runner jar at " + archive + " to record the startup"
                    + " profile from: run `" + RERECORD_COMMAND + "`");
        }
        Path basedir = project.getBasedir().toPath();
        Path profile = basedir.resolve(StartupProfileRecorder.PROFILE_LOCATION);
        Path work = Path.of(project.getBuild().getDirectory(), "micronaut-runner", "record-startup-profile");
        long started = System.nanoTime();
        int classes;
        try {
            classes = StartupProfileRecorder.record(java(), archive.toPath(), trainingSettings(), work, profile,
                    RERECORD_COMMAND, new MavenBuildLogger(getLog()));
        } catch (IOException e) {
            throw new MojoExecutionException("Could not record the startup profile: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            // A work directory with content no recording left there, or an archive inside it.
            throw new MojoFailureException(String.valueOf(e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Recording the startup profile was interrupted. The application was"
                    + " stopped and the profile was not changed.", e);
        }
        getLog().info(String.format(Locale.ROOT, "Recorded %d startup classes to %s in %.1f s. Commit it; the"
                        + " next mn-runner:package embeds it.", classes, StartupProfileRecorder.PROFILE_LOCATION,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) / 1000.0));
    }

    /**
     * The {@code java} of the JDK the toolchains plugin selected, or else of the JDK that runs Maven.
     *
     * @return the executable
     */
    Path java() {
        return MojoSupport.java(toolchainManager, session, getLog(), "Recording");
    }
}
