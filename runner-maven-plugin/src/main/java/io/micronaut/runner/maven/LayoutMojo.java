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

import io.micronaut.runner.build.AotLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.ToolchainManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes the extracted layout of the Runner JAR that {@code mn-runner:package} wrote, to
 * {@code target/micronaut-runner/layout/}: the application JAR and {@code lib/}, for a build that trains its JDK
 * AOT cache elsewhere, such as in a container image. It is what {@code java -Dmicronaut.runner.mode=extract} writes,
 * run with the JDK the {@code maven-toolchains-plugin} selected or else the one that runs Maven.
 *
 * <p>List it after {@code package} in the plugin's execution: Maven runs a phase's goals in declaration order.
 * The goal is experimental and belongs to this interim plugin only.</p>
 *
 * @since 1.0
 */
@Mojo(name = "layout", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class LayoutMojo extends AbstractMojo {

    /** The project whose Runner JAR is extracted. */
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

    /** Skips the goal, as it skips {@code mn-runner:package}. */
    @Parameter(property = "micronaut.runner.skip")
    private boolean skip;

    /** Creates the goal; Maven sets its parameters. */
    public LayoutMojo() {
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping the Runner layout");
            return;
        }
        File archive = runnerJar();
        if (!MojoSupport.isRunnerJar(archive)) {
            throw new MojoFailureException("There is no Runner JAR at " + archive + " to extract: list"
                    + " mn-runner:layout after mn-runner:package");
        }
        Path destination = destination();
        try {
            AotLayout.Result layout = AotLayout.write(java(), archive.toPath(), destination,
                    AotLayout.DEFAULT_TIMEOUT);
            getLog().info("Wrote the layout " + layout.applicationJar().getFileName() + " with "
                    + layout.libraries().size() + " JARs in " + AotLayout.LIBRARY_DIRECTORY + "/ to " + destination);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not write the layout: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Writing the layout was interrupted", e);
        }
    }

    /**
     * The Runner JAR, where {@code mn-runner:package} writes it.
     *
     * @return the archive
     */
    File runnerJar() {
        return PackageMojo.archiveFile(outputDirectory, finalName, classifier);
    }

    /**
     * Where the layout goes: {@code micronaut-runner/layout} in the build directory.
     *
     * @return the directory
     */
    Path destination() {
        return Path.of(project.getBuild().getDirectory(), "micronaut-runner", "layout");
    }

    /**
     * The {@code java} that runs the extraction.
     *
     * @return the executable
     */
    Path java() {
        return MojoSupport.java(toolchainManager, session, getLog(), "Extracting");
    }
}
