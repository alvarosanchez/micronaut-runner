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
import io.micronaut.runner.build.AotTarget;
import io.micronaut.runner.build.aotcache.AotCacheReport;
import io.micronaut.runner.build.aotcache.AotCacheSettings;
import io.micronaut.runner.build.training.TrainingSettings;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
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
 * <p>The task is tracked: with the same Runner JAR, JDK build and settings it is up to date. It is never cached,
 * and it is part of {@code assemble} only with {@code jdkAotCache.enabled = true}. It is experimental and belongs
 * to this interim plugin only.</p>
 *
 * @since 1.0
 */
@DisableCachingByDefault(because = "JDK AOT caches are not byte-reproducible and are tied to the exact JDK build and"
        + " to file modification times")
public abstract class MicronautRunnerJdkAotCache extends DefaultTask {

    /** Gradle creates the task. */
    public MicronautRunnerJdkAotCache() {
    }

    /**
     * The Runner JAR to train the cache for. The plugin sets the archive of {@code micronautRunnerJar}.
     *
     * @return the Runner JAR
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getArchiveFile();

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
        long started = System.nanoTime();
        AotCacheReport report;
        try {
            report = AotCacheOutput.write(target, settings, java, getArchiveFile().get().getAsFile().toPath(), out,
                    training, new GradleBuildLogger(getLogger()));
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
