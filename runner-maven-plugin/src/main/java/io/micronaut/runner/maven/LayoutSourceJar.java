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
import io.micronaut.runner.build.RunnerJarBuilder;
import io.micronaut.runner.build.RunnerJarResult;
import io.micronaut.runner.build.RunnerJarSpec;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The JAR {@code mn-runner:layout} and {@code mn-runner:jdk-aot-cache} extract the layout from: the layout-source
 * JAR, which the packaging library's rule ({@link AotLayout#sourceSpec(RunnerJarSpec, Path)}) derives from the spec
 * {@code mn-runner:package} built the Runner JAR with, or the Runner JAR itself when that spec keeps every lambda.
 *
 * <p>{@code mn-runner:package} leaves its spec in the project's context, so both goals use exactly its options,
 * dependencies and manifest without parameters or a dependency resolution of their own. The first of them that runs
 * in a build packages the JAR, and the second takes it from the context too.</p>
 */
final class LayoutSourceJar {

    /** The context entry that holds the spec {@code mn-runner:package} built the Runner JAR with. */
    static final String SPEC = LayoutSourceJar.class.getName() + ".spec";

    /** The context entry that holds the JAR the layout is extracted from, once a goal of this build has chosen it. */
    static final String JAR = LayoutSourceJar.class.getName() + ".jar";

    private LayoutSourceJar() {
    }

    /**
     * Keeps the spec a Runner JAR was built with, for the goals that extract its layout later in the build. A
     * layout-source JAR an earlier package of the same build chose no longer applies.
     *
     * @param project the project
     * @param spec    the spec, whose manifest source is the file that outlives the goal
     */
    static void remember(MavenProject project, RunnerJarSpec spec) {
        project.setContextValue(SPEC, spec);
        project.setContextValue(JAR, null);
    }

    /**
     * The JAR to extract the layout from, packaged once per build.
     *
     * @param project   the project
     * @param runnerJar the Runner JAR {@code mn-runner:package} wrote
     * @param goal      the goal that asks, for the failure message
     * @param log       where packaging is reported
     * @return the layout-source JAR, or the Runner JAR when its spec keeps every lambda
     * @throws MojoFailureException   if {@code mn-runner:package} did not run earlier in this build
     * @throws MojoExecutionException if the layout-source JAR cannot be packaged
     */
    static Path resolve(MavenProject project, Path runnerJar, String goal, Log log)
            throws MojoFailureException, MojoExecutionException {
        if (project.getContextValue(JAR) instanceof Path chosen && Files.isRegularFile(chosen)) {
            return chosen;
        }
        if (!(project.getContextValue(SPEC) instanceof RunnerJarSpec spec)) {
            throw new MojoFailureException("mn-runner:" + goal + " extracts the layout from a JAR it packages with"
                    + " the options of mn-runner:package, which did not run earlier in this build: list "
                    + goal + " after package in the plugin's execution, or run mvn package with both goals");
        }
        Path fileName = runnerJar.getFileName();
        Path output = Path.of(project.getBuild().getDirectory(), "micronaut-runner", "layout-source",
                fileName == null ? "runner.jar" : fileName.toString());
        Optional<RunnerJarSpec> source = AotLayout.sourceSpec(spec, output);
        Path chosen = runnerJar;
        if (source.isPresent()) {
            try {
                RunnerJarResult result = RunnerJarBuilder.build(source.get(), new MavenBuildLogger(log));
                log.info("Packaged the layout-source JAR, which keeps every lambda: " + result.summary());
            } catch (IOException e) {
                throw new MojoExecutionException("Could not package the layout-source JAR " + output + ": "
                        + e.getMessage(), e);
            }
            chosen = output;
        }
        project.setContextValue(JAR, chosen);
        return chosen;
    }
}
