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

import io.micronaut.runner.build.training.TrainingSettings;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The training settings of a goal that launches the application: how it is reached, exercised and stopped.
 *
 * <p>There is one parameter for each component of {@link TrainingSettings}, which documents them and owns
 * their defaults and their validation. Each is the user property {@code micronaut.runner.training.<name>}, so
 * it can be set with {@code -D} on the command line and in the POM's {@code <properties>}; a list is then
 * comma-separated. In a {@code <configuration>} element the parameter carries a {@code training} prefix, such
 * as {@code <trainingWorkloadPaths>}, so that it cannot collide with a parameter of the goal itself. The
 * timeouts are in seconds, and the environment is a list of {@code NAME=value} entries.</p>
 */
abstract class AbstractTrainingMojo extends AbstractMojo {

    /** The path to {@code GET} until it answers {@code 2xx}, once the port accepts a connection. */
    @Parameter(property = "micronaut.runner.training.readinessPath")
    private String trainingReadinessPath;

    /** The paths to {@code GET} in order; each must answer {@code 2xx}. */
    @Parameter(property = "micronaut.runner.training.workloadPaths")
    private List<String> trainingWorkloadPaths;

    /** How many times the list of workload paths is requested. Unset, 1. */
    @Parameter(property = "micronaut.runner.training.workloadRepeat")
    private Integer trainingWorkloadRepeat;

    /** A command and its arguments, run after the paths with {@code MICRONAUT_RUNNER_TRAINING_URL} set. */
    @Parameter(property = "micronaut.runner.training.workloadCommand")
    private List<String> trainingWorkloadCommand;

    /** Whether the application ends by itself, as one that is not a server does. Unset, {@code false}. */
    @Parameter(property = "micronaut.runner.training.runToExit")
    private Boolean trainingRunToExit;

    /** The path to {@code POST} to stop the application; unset, the process is destroyed. */
    @Parameter(property = "micronaut.runner.training.stopPath")
    private String trainingStopPath;

    /** JVM arguments of the training launch. They must name no class cache. */
    @Parameter(property = "micronaut.runner.training.jvmArgs")
    private List<String> trainingJvmArgs;

    /** Environment variables added to the training launch, as {@code NAME=value} entries. */
    @Parameter(property = "micronaut.runner.training.environment")
    private List<String> trainingEnvironment;

    /** The environment variable that receives the port. Unset, {@code MICRONAUT_SERVER_PORT}. */
    @Parameter(property = "micronaut.runner.training.portVariable")
    private String trainingPortVariable;

    /** How long the application may take to become ready, in seconds. Unset, 60. */
    @Parameter(property = "micronaut.runner.training.readinessTimeout")
    private Integer trainingReadinessTimeout;

    /** How long the paths and the command may take together, in seconds. Unset, 60. */
    @Parameter(property = "micronaut.runner.training.workloadTimeout")
    private Integer trainingWorkloadTimeout;

    /** How long the application may take to exit once asked to, in seconds. Unset, 30. */
    @Parameter(property = "micronaut.runner.training.stopTimeout")
    private Integer trainingStopTimeout;

    /**
     * Maps the parameters onto the packaging library's settings. An unset parameter leaves the library's
     * default in place.
     *
     * @return the settings
     * @throws MojoFailureException if a value is not usable, with a message that names the setting
     */
    TrainingSettings trainingSettings() throws MojoFailureException {
        try {
            TrainingSettings.Builder settings = TrainingSettings.builder()
                    .readinessPath(blankToNull(trainingReadinessPath))
                    .stopPath(blankToNull(trainingStopPath));
            if (trainingWorkloadPaths != null) {
                settings.workloadPaths(trainingWorkloadPaths);
            }
            if (trainingWorkloadRepeat != null) {
                settings.workloadRepeat(trainingWorkloadRepeat);
            }
            if (trainingWorkloadCommand != null) {
                settings.workloadCommand(trainingWorkloadCommand);
            }
            if (trainingRunToExit != null) {
                settings.runToExit(trainingRunToExit);
            }
            if (trainingJvmArgs != null) {
                settings.jvmArgs(trainingJvmArgs);
            }
            if (trainingEnvironment != null) {
                settings.environment(environment(trainingEnvironment));
            }
            if (trainingPortVariable != null && !trainingPortVariable.isBlank()) {
                settings.portVariable(trainingPortVariable.trim());
            }
            if (trainingReadinessTimeout != null) {
                settings.readinessTimeout(Duration.ofSeconds(trainingReadinessTimeout));
            }
            if (trainingWorkloadTimeout != null) {
                settings.workloadTimeout(Duration.ofSeconds(trainingWorkloadTimeout));
            }
            if (trainingStopTimeout != null) {
                settings.stopTimeout(Duration.ofSeconds(trainingStopTimeout));
            }
            return settings.build();
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException("Invalid training setting: " + e.getMessage(), e);
        }
    }

    /**
     * Reads {@code NAME=value} entries. The value is everything after the first {@code =}, and may be empty.
     *
     * @param entries the entries
     * @return the variables, in order
     * @throws IllegalArgumentException if an entry has no {@code =} or no name
     */
    static Map<String, String> environment(List<String> entries) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String entry : entries) {
            int equals = entry == null ? -1 : entry.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("environment needs NAME=value entries, not '" + entry + "'");
            }
            environment.put(entry.substring(0, equals).trim(), entry.substring(equals + 1));
        }
        return environment;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
