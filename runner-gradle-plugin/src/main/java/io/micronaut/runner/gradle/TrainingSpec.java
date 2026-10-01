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

import io.micronaut.runner.build.training.TrainingSettings;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;

import java.time.Duration;

/**
 * How a training run exercises the application: how the build knows it is ready, what it asks of it and how
 * it stops it. It is the {@code training { }} block of the {@link MicronautRunnerExtension}, and each task
 * that launches the application takes its own copy, whose conventions are the block's values.
 *
 * <pre>
 * micronaut {
 *     runner {
 *         training {
 *             readinessPath = '/health'
 *             workloadPaths = ['/hello', '/orders']
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>There is one property for each setting of {@link TrainingSettings}, which documents them and owns
 * their defaults and their validation. A path is the path of an HTTP request to the application on the
 * loopback interface.</p>
 *
 * <p>The API is experimental: it may change in any release.</p>
 *
 * @since 1.0
 */
public abstract class TrainingSpec {

    /** Gradle creates the block. */
    public TrainingSpec() {
    }

    /**
     * The path to {@code GET} until it answers {@code 2xx}, once the port accepts a connection. Without it the
     * accepted connection alone means ready.
     *
     * @return the readiness path
     */
    @Input
    @Optional
    public abstract Property<String> getReadinessPath();

    /**
     * The paths to {@code GET} in order, each of which must answer {@code 2xx}. When this and
     * {@link #getWorkloadCommand()} are both empty, the application serves one {@code GET /} and the build
     * warns: the workload decides which classes a training run sees.
     *
     * @return the workload paths
     */
    @Input
    @Optional
    public abstract ListProperty<String> getWorkloadPaths();

    /**
     * How many times the whole list of {@link #getWorkloadPaths()} is requested. Defaults to 1.
     *
     * @return the repeat count
     */
    @Input
    @Optional
    public abstract Property<Integer> getWorkloadRepeat();

    /**
     * A command and its arguments, run after the paths with {@code MICRONAUT_RUNNER_TRAINING_URL} set to the
     * application's base URL. It must exit with status 0 within {@link #getWorkloadTimeout()}.
     *
     * @return the workload command
     */
    @Input
    @Optional
    public abstract ListProperty<String> getWorkloadCommand();

    /**
     * Whether the application ends by itself, as one that is not a server does. There is then no readiness
     * check, no workload and no stop; the application must exit with status 0 within
     * {@link #getReadinessTimeout()}. Defaults to {@code false}.
     *
     * @return the run-to-exit flag
     */
    @Input
    @Optional
    public abstract Property<Boolean> getRunToExit();

    /**
     * The path to {@code POST} to stop the application, where an orderly stop needs a request rather than a
     * signal, as on Windows. Without it the process is destroyed.
     *
     * @return the stop path
     */
    @Input
    @Optional
    public abstract Property<String> getStopPath();

    /**
     * JVM arguments of the training launch. They must name no class cache.
     *
     * @return the JVM arguments
     */
    @Input
    @Optional
    public abstract ListProperty<String> getJvmArgs();

    /**
     * Environment variables added to the training launch.
     *
     * @return the environment
     */
    @Input
    @Optional
    public abstract MapProperty<String, String> getEnvironment();

    /**
     * The environment variable that receives the free loopback port the application is to listen on. Defaults
     * to {@code MICRONAUT_SERVER_PORT}.
     *
     * @return the name of the port variable
     */
    @Input
    @Optional
    public abstract Property<String> getPortVariable();

    /**
     * How long the application may take to become ready. Defaults to 60 seconds.
     *
     * @return the readiness timeout
     */
    @Input
    @Optional
    public abstract Property<Duration> getReadinessTimeout();

    /**
     * How long the paths and the command may take together. Defaults to 60 seconds.
     *
     * @return the workload timeout
     */
    @Input
    @Optional
    public abstract Property<Duration> getWorkloadTimeout();

    /**
     * How long the application may take to exit once asked to, before it is killed. Defaults to 30 seconds.
     *
     * @return the stop timeout
     */
    @Input
    @Optional
    public abstract Property<Duration> getStopTimeout();

    /**
     * Shows the packaging library's defaults as the conventions of a block. The collections default to empty,
     * which is a collection property's own initial value, and the two optional paths have no default.
     *
     * @param spec the extension's block
     */
    static void defaults(TrainingSpec spec) {
        TrainingSettings defaults = TrainingSettings.defaults();
        spec.getWorkloadRepeat().convention(defaults.workloadRepeat());
        spec.getRunToExit().convention(defaults.runToExit());
        spec.getPortVariable().convention(defaults.portVariable());
        spec.getReadinessTimeout().convention(defaults.readinessTimeout());
        spec.getWorkloadTimeout().convention(defaults.workloadTimeout());
        spec.getStopTimeout().convention(defaults.stopTimeout());
    }

    /**
     * Gives a task's block the extension's values as its conventions, so that a value set on the task wins
     * for that task.
     *
     * @param spec the task's block
     * @param from the extension's block
     */
    static void conventions(TrainingSpec spec, TrainingSpec from) {
        spec.getReadinessPath().convention(from.getReadinessPath());
        spec.getWorkloadPaths().convention(from.getWorkloadPaths());
        spec.getWorkloadRepeat().convention(from.getWorkloadRepeat());
        spec.getWorkloadCommand().convention(from.getWorkloadCommand());
        spec.getRunToExit().convention(from.getRunToExit());
        spec.getStopPath().convention(from.getStopPath());
        spec.getJvmArgs().convention(from.getJvmArgs());
        spec.getEnvironment().convention(from.getEnvironment());
        spec.getPortVariable().convention(from.getPortVariable());
        spec.getReadinessTimeout().convention(from.getReadinessTimeout());
        spec.getWorkloadTimeout().convention(from.getWorkloadTimeout());
        spec.getStopTimeout().convention(from.getStopTimeout());
    }

    /**
     * Maps a block onto the packaging library's settings. An absent value leaves the library's default in
     * place.
     *
     * @param spec the block
     * @return the settings
     * @throws IllegalArgumentException if a value is not usable; the message names the setting
     */
    static TrainingSettings settings(TrainingSpec spec) {
        TrainingSettings.Builder settings = TrainingSettings.builder()
                .readinessPath(spec.getReadinessPath().getOrNull())
                .stopPath(spec.getStopPath().getOrNull());
        if (spec.getWorkloadPaths().isPresent()) {
            settings.workloadPaths(spec.getWorkloadPaths().get());
        }
        if (spec.getWorkloadRepeat().isPresent()) {
            settings.workloadRepeat(spec.getWorkloadRepeat().get());
        }
        if (spec.getWorkloadCommand().isPresent()) {
            settings.workloadCommand(spec.getWorkloadCommand().get());
        }
        if (spec.getRunToExit().isPresent()) {
            settings.runToExit(spec.getRunToExit().get());
        }
        if (spec.getJvmArgs().isPresent()) {
            settings.jvmArgs(spec.getJvmArgs().get());
        }
        if (spec.getEnvironment().isPresent()) {
            settings.environment(spec.getEnvironment().get());
        }
        if (spec.getPortVariable().isPresent()) {
            settings.portVariable(spec.getPortVariable().get());
        }
        if (spec.getReadinessTimeout().isPresent()) {
            settings.readinessTimeout(spec.getReadinessTimeout().get());
        }
        if (spec.getWorkloadTimeout().isPresent()) {
            settings.workloadTimeout(spec.getWorkloadTimeout().get());
        }
        if (spec.getStopTimeout().isPresent()) {
            settings.stopTimeout(spec.getStopTimeout().get());
        }
        return settings.build();
    }
}
