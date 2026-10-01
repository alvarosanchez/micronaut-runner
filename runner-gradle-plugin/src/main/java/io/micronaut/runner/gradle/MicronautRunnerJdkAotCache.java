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

import io.micronaut.runner.build.AotCacheOutput;
import io.micronaut.runner.build.AotCacheReport;
import io.micronaut.runner.build.AotCacheSettings;
import io.micronaut.runner.build.AotTarget;
import io.micronaut.runner.build.training.TrainingSettings;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Trains a JDK AOT cache for the Runner JAR and verifies it: stages the program in its output directory (the
 * extracted layout, or a copy of the Runner JAR), records a training run with the project's toolchain, creates
 * the cache, and runs the verification gate. The directory then holds everything a cached deployment needs, and
 * the application launches from it as {@code java @app.jvmopts -jar <jar>}.
 *
 * <p>While {@link #getArchiveFile()} is {@code micronautRunnerJar}'s archive, the plugin has the layout target
 * extract the archive of an internal task instead, a JAR of the same inputs and options that keeps every lambda, so
 * the cache matches that layout and not an extract of {@code micronautRunnerJar}'s archive when that archive
 * desugars lambdas. {@code aot-report.json} and {@code app.aot.properties} then record the SHA-256 of both JARs.</p>
 *
 * <p>The task is tracked: with the same Runner JAR, JDK build and settings it is up to date. It is never cached,
 * and it is part of {@code assemble} only with {@code jdkAotCache.enabled = true}. It is experimental and belongs
 * to this interim plugin only.</p>
 *
 * @since 1.0
 */
@DisableCachingByDefault(because = "JDK AOT caches are not byte-reproducible and are tied to the exact JDK build and"
        + " to file modification times")
public abstract class MicronautRunnerJdkAotCache extends DefaultTask {

    /** The name of the input {@link #extractLayoutFrom(Provider)} registers. */
    private static final String LAYOUT_SOURCE_INPUT = "layoutSourceFile";

    /** The JAR the layout target extracts, when the plugin sets one; unset, it is {@link #getArchiveFile()}. */
    private final RegularFileProperty layoutSourceFile;

    /** Gradle creates the task. */
    public MicronautRunnerJdkAotCache() {
        layoutSourceFile = getProject().getObjects().fileProperty();
    }

    /**
     * The Runner JAR to train the cache for, which the {@code singleJar} target copies and the layout target
     * extracts. The plugin sets the archive of {@code micronautRunnerJar}, and while it is that archive, the layout
     * target extracts the plugin's layout-source JAR instead and identifies this one in its report by its SHA-256.
     * Set to another Runner JAR, the layout target extracts that JAR as it is, desugared lambdas included.
     *
     * @return the Runner JAR
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getArchiveFile();

    /**
     * Sets the JAR the layout target extracts. The plugin hands it the archive of its internal layout-source task
     * while the target is the layout and {@link #getArchiveFile()} is {@code micronautRunnerJar}'s archive, and
     * {@link #getArchiveFile()} itself otherwise. It is an input of its own rather than a property, so that a build
     * script cannot set it; the provider carries the task that builds the JAR, which the input therefore runs first.
     *
     * @param jar the JAR the layout target extracts, which always has a value
     */
    void extractLayoutFrom(Provider<RegularFile> jar) {
        layoutSourceFile.set(jar);
        layoutSourceFile.disallowChanges();
        getInputs().file(layoutSourceFile)
                .withPropertyName(LAYOUT_SOURCE_INPUT)
                .withPathSensitivity(PathSensitivity.NONE);
    }

    /**
     * The JAR the layout target extracts, as {@link #extractLayoutFrom(Provider)} set it.
     *
     * @return the JAR, or no value when the plugin set none
     */
    Provider<RegularFile> layoutSourceFile() {
        return layoutSourceFile;
    }

    /**
     * The JVM that trains the cache, and the only JDK build the cache fits. The plugin sets the launcher of the
     * project's toolchain, which is the JVM that runs Gradle when the project configures none.
     *
     * @return the launcher
     */
    @Nested
    public abstract Property<JavaLauncher> getJavaLauncher();

    /**
     * The exact build of the launcher's JDK: its runtime version and its VM version. The plugin derives it from
     * the launcher, so that another build of the same Java version trains the cache again.
     *
     * @return the JDK build
     */
    @Input
    public abstract Property<String> getJdkBuild();

    /**
     * The cache settings. Each property takes the value of the extension's {@code jdkAotCache { }} block as its
     * convention.
     *
     * @return the settings
     */
    @Nested
    public abstract JdkAotCacheSpec getJdkAotCache();

    /**
     * How the application is reached, exercised and stopped. Each property takes the value of the extension's
     * {@code training { }} block as its convention, except {@code jvmArgs}: the block's belong to the startup
     * profile, and the cache's launches take {@code jdkAotCache.jvmArgs}, so a change to the block's does not
     * make this task out of date.
     *
     * @return the training settings
     */
    @Nested
    public abstract TrainingSpec getTraining();

    /**
     * The directory of the cached deployment, replaced as a whole. The plugin sets
     * {@code build/micronaut-runner/jdk-aot-cache}.
     *
     * @return the directory
     */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /**
     * Trains and verifies the cache.
     *
     * @throws IOException if staging, training or verification fails; the logs stay in the output directory
     */
    @TaskAction
    public void train() throws IOException {
        Path java = getJavaLauncher().get().getExecutablePath().getAsFile().toPath();
        Path out = getOutputDirectory().get().getAsFile().toPath();
        AotTarget target;
        AotCacheSettings settings;
        TrainingSettings training;
        try {
            target = JdkAotCacheSpec.target(getJdkAotCache());
            settings = JdkAotCacheSpec.settings(getJdkAotCache());
            training = TrainingSpec.settings(getTraining());
        } catch (IllegalArgumentException e) {
            throw new InvalidUserDataException(String.valueOf(e.getMessage()), e);
        }
        Path archive = getArchiveFile().get().getAsFile().toPath();
        Path layoutSource = layoutSourceFile.isPresent() ? layoutSourceFile.get().getAsFile().toPath() : archive;
        long started = System.nanoTime();
        AotCacheReport report;
        try {
            report = AotCacheOutput.write(target, settings, java, archive, layoutSource, out, training,
                    new GradleBuildLogger(getLogger()));
        } catch (IllegalArgumentException e) {
            throw new InvalidUserDataException(String.valueOf(e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("Training the JDK AOT cache was interrupted. Every process it started was"
                    + " stopped.", e);
        }
        getLogger().lifecycle(report.summary() + String.format(Locale.ROOT, " (in %s, %.1f s)", out,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) / 1000.0));
    }
}
