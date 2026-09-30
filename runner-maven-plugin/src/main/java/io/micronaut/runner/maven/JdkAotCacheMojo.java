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

import io.micronaut.runner.build.AotCacheOutput;
import io.micronaut.runner.build.AotTarget;
import io.micronaut.runner.build.aotcache.AotCacheReport;
import io.micronaut.runner.build.aotcache.AotCacheSettings;
import io.micronaut.runner.build.aotcache.AotLaunchOptions;
import io.micronaut.runner.build.training.TrainingSettings;
import org.apache.maven.execution.MavenSession;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Trains a JDK AOT cache for the Runner JAR that {@code mn-runner:package} wrote, and verifies it, in
 * {@code target/micronaut-runner/jdk-aot-cache/}: the extracted layout (or a copy of the Runner JAR), the cache,
 * the launch argfile {@code app.jvmopts}, the identity file, the report and the logs. The application launches from
 * that directory as {@code java @app.jvmopts -jar <jar>}.
 *
 * <p>It does nothing unless {@code micronaut.runner.jdkAotCache.enabled} is {@code true}. List it after
 * {@code package} in the plugin's execution: Maven runs a phase's goals in declaration order. The training settings
 * are the {@code micronaut.runner.training.*} properties, except {@code jvmArgs}: the cache's launches take
 * {@code micronaut.runner.jdkAotCache.jvmArgs}. The JDK is the one the {@code maven-toolchains-plugin} selected, or
 * the one that runs Maven; the cache fits only that exact JDK build.</p>
 *
 * <p>Every setting is a user property, such as {@code micronaut.runner.jdkAotCache.target}, a list comma-separated,
 * and a parameter with the {@code jdkAotCache} prefix in the plugin's configuration, such as
 * {@code jdkAotCacheTarget}. An unset one leaves the packaging library's default in place. The goal is experimental
 * and belongs to this interim plugin only.</p>
 *
 * @since 1.0
 */
@Mojo(name = "jdk-aot-cache", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class JdkAotCacheMojo extends AbstractTrainingMojo {

    /** Whether the goal trains the cache. Unset, {@code false}: the goal logs one line and does nothing. */
    @Parameter(property = "micronaut.runner.jdkAotCache.enabled", defaultValue = "false")
    private boolean jdkAotCacheEnabled;

    /** {@code layout}, the extracted layout, or {@code singleJar}, a copy of the Runner JAR. Unset, {@code layout}. */
    @Parameter(property = "micronaut.runner.jdkAotCache.target")
    private String jdkAotCacheTarget;

    /** Whether {@code app.jvmopts} also carries {@code -XX:AOTMode=on}. Unset, {@code false}. */
    @Parameter(property = "micronaut.runner.jdkAotCache.strict")
    private Boolean jdkAotCacheStrict;

    /** JVM arguments of the recording, the creation, the verification launches and {@code app.jvmopts}. */
    @Parameter(property = "micronaut.runner.jdkAotCache.jvmArgs")
    private List<String> jdkAotCacheJvmArgs;

    /** How many strict {@code -version} probes the verification runs. Unset, 10. */
    @Parameter(property = "micronaut.runner.jdkAotCache.verifyProbes")
    private Integer jdkAotCacheVerifyProbes;

    /** The share of the classes that must come from the cache, for the {@code layout} target. Unset, 0.95. */
    @Parameter(property = "micronaut.runner.jdkAotCache.minCoverage")
    private Double jdkAotCacheMinCoverage;

    /** The project whose Runner JAR the cache is trained for. */
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
    public JdkAotCacheMojo() {
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!jdkAotCacheEnabled || skip) {
            getLog().info("Skipping the JDK AOT cache: set micronaut.runner.jdkAotCache.enabled=true to train one");
            return;
        }
        File archive = runnerJar();
        if (!MojoSupport.isRunnerJar(archive)) {
            throw new MojoFailureException("There is no Runner JAR at " + archive + " to train a JDK AOT cache for:"
                    + " list mn-runner:jdk-aot-cache after mn-runner:package");
        }
        AotTarget target = target();
        AotCacheSettings settings = settings();
        TrainingSettings training = trainingSettings();
        Path out = outputDirectoryOfTheCache();
        long started = System.nanoTime();
        AotCacheReport report;
        try {
            report = AotCacheOutput.write(target, settings, java(), archive.toPath(), out, training,
                    new MavenBuildLogger(getLog()));
        } catch (IOException e) {
            throw new MojoExecutionException("Could not build the JDK AOT cache: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(String.valueOf(e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Training the JDK AOT cache was interrupted. Every process it started"
                    + " was stopped.", e);
        }
        getLog().info(String.format(Locale.ROOT, "Trained and verified the JDK AOT cache for the %s target in %.1f"
                        + " s: %.1f%% of the classes and %d of %d io.micronaut classes from the cache, %d of %d strict"
                        + " probes failed. Launch it from %s with: java @%s -jar <jar>", target,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) / 1000.0, report.coverage() * 100,
                report.micronautFromCache(), report.micronautLoaded(), report.probeFailures(), report.probes(), out,
                AotLaunchOptions.ARGFILE));
    }

    /**
     * The cache settings: the packaging library's defaults, with the parameters that are set.
     *
     * @return the settings
     * @throws MojoFailureException if a value is not usable, with a message that names the setting
     */
    AotCacheSettings settings() throws MojoFailureException {
        try {
            AotCacheSettings.Builder settings = AotCacheSettings.builder();
            if (jdkAotCacheStrict != null) {
                settings.strict(jdkAotCacheStrict);
            }
            if (jdkAotCacheJvmArgs != null) {
                settings.jvmArgs(jdkAotCacheJvmArgs);
            }
            if (jdkAotCacheVerifyProbes != null) {
                settings.verifyProbes(jdkAotCacheVerifyProbes);
            }
            if (jdkAotCacheMinCoverage != null) {
                settings.minCoverage(jdkAotCacheMinCoverage);
            }
            return settings.build();
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException("Invalid JDK AOT cache setting: " + e.getMessage(), e);
        }
    }

    /**
     * The target: {@link AotTarget#DEFAULT} unless the parameter is set.
     *
     * @return the target
     * @throws MojoFailureException if the value names no target
     */
    AotTarget target() throws MojoFailureException {
        if (jdkAotCacheTarget == null || jdkAotCacheTarget.isBlank()) {
            return AotTarget.DEFAULT;
        }
        try {
            return AotTarget.parse(jdkAotCacheTarget);
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage(), e);
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
     * Where the cached deployment goes: {@code micronaut-runner/jdk-aot-cache} in the build directory.
     *
     * @return the directory
     */
    Path outputDirectoryOfTheCache() {
        return Path.of(project.getBuild().getDirectory(), "micronaut-runner", "jdk-aot-cache");
    }

    /**
     * The {@code java} that trains the cache.
     *
     * @return the executable
     */
    Path java() {
        return MojoSupport.java(toolchainManager, session, getLog(), "Training the JDK AOT cache");
    }
}
