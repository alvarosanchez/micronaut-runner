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
package io.micronaut.runner.build.training;

import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.Nullable;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How a training run exercises the application: how the {@link TrainingDriver} knows it is ready, what it
 * asks of it, and how it stops it.
 *
 * <p>A training run launches the application once, from the archive the build produced, to learn something
 * from it: the classes it loads at startup, or a JDK cache. The settings describe the application, not the
 * build tool, so both plugins map theirs onto this one record.</p>
 *
 * <p>A path is the path of an HTTP request to the application on the loopback interface. It starts with
 * {@code /} and may carry a query string.</p>
 *
 * @param readinessPath    the path to {@code GET} until it answers {@code 2xx}, once the port accepts a
 *                         connection; {@code null} when the accepted connection alone means ready
 * @param workloadPaths    the paths to {@code GET} in order, each of which must answer {@code 2xx}; when
 *                         this and {@code workloadCommand} are both empty the driver sends one {@code GET /}
 *                         and accepts any status below 500
 * @param workloadRepeat   how many times the whole list of {@code workloadPaths} is requested; at least 1
 * @param workloadCommand  a command and its arguments, run after the paths with
 *                         {@value TrainingDriver#URL_VARIABLE} set to the application's base URL; it must
 *                         exit with status 0; empty for none
 * @param runToExit        whether the application ends by itself, as one that is not a server does; there is
 *                         then no readiness check, no workload and no stop, and the application must exit
 *                         with status 0 within {@code readinessTimeout}
 * @param stopPath         the path to {@code POST} to stop the application, where an orderly stop needs a
 *                         request rather than a signal; {@code null} to destroy the process
 * @param jvmArgs          JVM arguments of the training launch, placed before the driver's own
 * @param environment      environment variables added to the training launch
 * @param portVariable     the environment variable that receives the free loopback port the application is
 *                         to listen on
 * @param readinessTimeout how long the application may take to become ready, or to exit with
 *                         {@code runToExit}
 * @param workloadTimeout  how long the paths and the command may take together
 * @param stopTimeout      how long the application may take to exit once asked to, before it is killed
 * @since 1.0
 */
@Experimental
public record TrainingSettings(@Nullable String readinessPath,
                               List<String> workloadPaths,
                               int workloadRepeat,
                               List<String> workloadCommand,
                               boolean runToExit,
                               @Nullable String stopPath,
                               List<String> jvmArgs,
                               Map<String, String> environment,
                               String portVariable,
                               Duration readinessTimeout,
                               Duration workloadTimeout,
                               Duration stopTimeout) {

    /** The environment variable Micronaut reads the server port from, whatever the application configures. */
    public static final String DEFAULT_PORT_VARIABLE = "MICRONAUT_SERVER_PORT";

    /** The default of {@link #readinessTimeout()}. */
    public static final Duration DEFAULT_READINESS_TIMEOUT = Duration.ofSeconds(60);

    /** The default of {@link #workloadTimeout()}. */
    public static final Duration DEFAULT_WORKLOAD_TIMEOUT = Duration.ofSeconds(60);

    /** The default of {@link #stopTimeout()}. */
    public static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Validates the settings and copies the collections.
     *
     * @throws NullPointerException     if a collection, the port variable or a timeout is {@code null}
     * @throws IllegalArgumentException if a value is not usable; the message names the setting
     */
    public TrainingSettings {
        workloadPaths = List.copyOf(Objects.requireNonNull(workloadPaths, "workloadPaths"));
        workloadCommand = List.copyOf(Objects.requireNonNull(workloadCommand, "workloadCommand"));
        jvmArgs = List.copyOf(Objects.requireNonNull(jvmArgs, "jvmArgs"));
        // Insertion order is the order the variables are set in, and the order a failure names them in.
        environment = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(environment, "environment")));
        Objects.requireNonNull(portVariable, "portVariable");

        if (readinessPath != null) {
            requirePath("readinessPath", readinessPath);
        }
        if (stopPath != null) {
            requirePath("stopPath", stopPath);
        }
        for (String path : workloadPaths) {
            requirePath("workloadPaths", path);
        }
        if (workloadRepeat < 1) {
            throw new IllegalArgumentException("workloadRepeat must be at least 1, not " + workloadRepeat);
        }
        if (!workloadCommand.isEmpty() && workloadCommand.get(0).isBlank()) {
            throw new IllegalArgumentException("workloadCommand must start with the program to run");
        }
        for (String argument : jvmArgs) {
            if (argument.isBlank()) {
                throw new IllegalArgumentException("jvmArgs must not contain a blank argument");
            }
        }
        requireVariableName("portVariable", portVariable);
        for (Map.Entry<String, String> variable : environment.entrySet()) {
            requireVariableName("environment", variable.getKey());
            if (variable.getValue() == null) {
                throw new IllegalArgumentException("environment has no value for " + variable.getKey());
            }
        }
        requirePositive("readinessTimeout", readinessTimeout);
        requirePositive("workloadTimeout", workloadTimeout);
        requirePositive("stopTimeout", stopTimeout);
        if (runToExit && (!workloadPaths.isEmpty() || !workloadCommand.isEmpty() || workloadRepeat != 1)) {
            throw new IllegalArgumentException("runToExit launches an application that ends by itself, so no"
                    + " workload can be sent to it: remove workloadPaths, workloadCommand and workloadRepeat");
        }
    }

    /**
     * The settings of an application about which nothing is known: a server that reads its port from
     * {@value #DEFAULT_PORT_VARIABLE}, is ready when the port accepts a connection, and is stopped by
     * destroying the process.
     *
     * @return the defaults
     */
    public static TrainingSettings defaults() {
        return builder().build();
    }

    /**
     * Starts from the defaults.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private static void requirePath(String setting, String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException(setting + " must be a request path that starts with '/', not '"
                    + path + "'");
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= ' ' || c == 0x7f) {
                throw new IllegalArgumentException(setting + " must not contain whitespace or a control"
                        + " character: '" + path + "'. Percent-encode it.");
            }
        }
        try {
            URI.create("http://127.0.0.1" + path);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(setting + " is not a request path a URL can carry: '" + path
                    + "'. Percent-encode what is not allowed in one.", e);
        }
    }

    private static void requireVariableName(String setting, String name) {
        if (name == null || name.isBlank() || name.indexOf('=') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(setting + " needs the name of an environment variable, not '"
                    + name + "'");
        }
    }

    private static void requirePositive(String setting, Duration timeout) {
        Objects.requireNonNull(timeout, setting);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(setting + " must be positive, not " + timeout);
        }
    }

    /**
     * Collects training settings, starting from the defaults. A setter that is not called leaves the default
     * in place.
     */
    public static final class Builder {

        private @Nullable String readinessPath;
        private List<String> workloadPaths = List.of();
        private int workloadRepeat = 1;
        private List<String> workloadCommand = List.of();
        private boolean runToExit;
        private @Nullable String stopPath;
        private List<String> jvmArgs = List.of();
        private Map<String, String> environment = Map.of();
        private String portVariable = DEFAULT_PORT_VARIABLE;
        private Duration readinessTimeout = DEFAULT_READINESS_TIMEOUT;
        private Duration workloadTimeout = DEFAULT_WORKLOAD_TIMEOUT;
        private Duration stopTimeout = DEFAULT_STOP_TIMEOUT;

        private Builder() {
        }

        /**
         * Sets {@link TrainingSettings#readinessPath()}.
         *
         * @param value the path, or {@code null} for none
         * @return this builder
         */
        public Builder readinessPath(@Nullable String value) {
            this.readinessPath = value;
            return this;
        }

        /**
         * Sets {@link TrainingSettings#workloadPaths()}.
         *
         * @param value the paths, in request order
         * @return this builder
         */
        public Builder workloadPaths(List<String> value) {
            this.workloadPaths = value;
            return this;
        }

        /**
         * Sets {@link TrainingSettings#workloadRepeat()}.
         *
         * @param value how many times the paths are requested
         * @return this builder
         */
        public Builder workloadRepeat(int value) {
            this.workloadRepeat = value;
            return this;
        }

        /**
         * Sets {@link TrainingSettings#workloadCommand()}.
         *
         * @param value the command and its arguments, empty for none
         * @return this builder
         */
        public Builder workloadCommand(List<String> value) {
            this.workloadCommand = value;
            return this;
        }

        /**
         * Sets {@link TrainingSettings#runToExit()}.
         *
         * @param value whether the application ends by itself
         * @return this builder
         */
        public Builder runToExit(boolean value) {
            this.runToExit = value;
            return this;
        }

        /**
         * Sets {@link TrainingSettings#stopPath()}.
         *
         * @param value the path, or {@code null} to destroy the process
         * @return this builder
         */
        public Builder stopPath(@Nullable String value) {
            this.stopPath = value;
            return this;
        }

        /**
         * Sets {@link TrainingSettings#jvmArgs()}.
         *
         * @param value the JVM arguments
         * @return this builder
         */
        public Builder jvmArgs(List<String> value) {
            this.jvmArgs = value;
            return this;
        }

        /**
         * Sets {@link TrainingSettings#environment()}.
         *
         * @param value the environment variables to add
         * @return this builder
         */
        public Builder environment(Map<String, String> value) {
            this.environment = value;
            return this;
        }

        /**
         * Sets {@link TrainingSettings#portVariable()}.
         *
         * @param value the name of the environment variable
         * @return this builder
         */
        public Builder portVariable(String value) {
            this.portVariable = value;
            return this;
        }

        /**
         * Sets {@link TrainingSettings#readinessTimeout()}.
         *
         * @param value the timeout
         * @return this builder
         */
        public Builder readinessTimeout(Duration value) {
            this.readinessTimeout = value;
            return this;
        }

        /**
         * Sets {@link TrainingSettings#workloadTimeout()}.
         *
         * @param value the timeout
         * @return this builder
         */
        public Builder workloadTimeout(Duration value) {
            this.workloadTimeout = value;
            return this;
        }

        /**
         * Sets {@link TrainingSettings#stopTimeout()}.
         *
         * @param value the timeout
         * @return this builder
         */
        public Builder stopTimeout(Duration value) {
            this.stopTimeout = value;
            return this;
        }

        /**
         * Validates and builds the settings.
         *
         * @return the settings
         * @throws IllegalArgumentException if a value is not usable; the message names the setting
         */
        public TrainingSettings build() {
            return new TrainingSettings(readinessPath, workloadPaths, workloadRepeat, workloadCommand, runToExit,
                    stopPath, jvmArgs, environment, portVariable, readinessTimeout, workloadTimeout, stopTimeout);
        }
    }
}
