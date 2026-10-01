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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * How a training run exercises the application: how the run knows it is ready, what it asks of it, and how it
 * stops it.
 *
 * <p>A training run launches the application once with {@code java -jar}, from the archive the build produced,
 * to learn something from it: the classes it loads at startup, or a JDK cache. It passes the application a free
 * loopback port in {@link #portVariable()}, waits until it is ready, sends it the workload and stops it; every
 * phase has a timeout, and every process the run started is gone when it ends. The settings describe the
 * application, not the build tool, so both build plugins map theirs onto this one type.</p>
 *
 * <p>A path is the path of an HTTP request to the application on the loopback interface. It starts with
 * {@code /} and may carry a query string.</p>
 *
 * <p>Settings are made with {@link #builder()}, and are immutable. Two settings are equal when every setting
 * is.</p>
 *
 * <h2>Compatibility</h2>
 *
 * <p>This type is stable API for all of 1.x. A setting added in a minor release arrives as a new accessor and a
 * new builder setter, whose default keeps the behaviour of the release before; no accessor or setter changes
 * or goes away. A default value changes only in a minor release. Each setting is named like the Gradle property
 * and the Maven parameter that sets it, and every rejection names the setting.</p>
 *
 * @since 1.0
 */
@Experimental
public final class TrainingSettings {

    /**
     * The environment variable that tells a {@linkplain #workloadCommand() workload command} where the
     * application listens: {@code http://127.0.0.1:<port>}. Workload commands read it, so its value is fixed for
     * all of 1.x.
     */
    public static final String URL_VARIABLE = "MICRONAUT_RUNNER_TRAINING_URL";

    /** The environment variable Micronaut reads the server port from, whatever the application configures. */
    private static final String DEFAULT_PORT_VARIABLE = "MICRONAUT_SERVER_PORT";

    private static final Duration DEFAULT_READINESS_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration DEFAULT_WORKLOAD_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(30);

    private final @Nullable String readinessPath;
    private final List<String> workloadPaths;
    private final int workloadRepeat;
    private final List<String> workloadCommand;
    private final boolean runToExit;
    private final @Nullable String stopPath;
    private final List<String> jvmArgs;
    private final Map<String, String> environment;
    private final String portVariable;
    private final Duration readinessTimeout;
    private final Duration workloadTimeout;
    private final Duration stopTimeout;

    /**
     * Validates the builder's values and copies the collections.
     *
     * @param builder the builder
     * @throws NullPointerException     if a setting that takes no {@code null} is {@code null}
     * @throws IllegalArgumentException if a value is not usable
     */
    private TrainingSettings(Builder builder) {
        this.workloadPaths = copyOf("workloadPaths", builder.workloadPaths);
        this.workloadCommand = copyOf("workloadCommand", builder.workloadCommand);
        this.jvmArgs = copyOf("jvmArgs", builder.jvmArgs);
        this.environment = environment(builder.environment);
        this.portVariable = Objects.requireNonNull(builder.portVariable, "portVariable");
        this.readinessTimeout = Objects.requireNonNull(builder.readinessTimeout, "readinessTimeout");
        this.workloadTimeout = Objects.requireNonNull(builder.workloadTimeout, "workloadTimeout");
        this.stopTimeout = Objects.requireNonNull(builder.stopTimeout, "stopTimeout");
        this.readinessPath = builder.readinessPath;
        this.stopPath = builder.stopPath;
        this.workloadRepeat = builder.workloadRepeat;
        this.runToExit = builder.runToExit;

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
        for (String name : environment.keySet()) {
            requireVariableName("environment", name);
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
     * {@code MICRONAUT_SERVER_PORT}, is ready when the port accepts a connection, is sent one {@code GET /} and
     * is stopped by destroying its process, with a readiness and a workload timeout of 60 seconds and a stop
     * timeout of 30 seconds. They are the builder's starting values.
     *
     * @return the defaults
     */
    public static TrainingSettings defaults() {
        return builder().build();
    }

    /**
     * Starts from the {@linkplain #defaults() defaults}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts from these settings, to make settings that differ in a few of them.
     *
     * @return a new builder that holds every value of these settings
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.readinessPath = readinessPath;
        builder.workloadPaths = workloadPaths;
        builder.workloadRepeat = workloadRepeat;
        builder.workloadCommand = workloadCommand;
        builder.runToExit = runToExit;
        builder.stopPath = stopPath;
        builder.jvmArgs = jvmArgs;
        builder.environment = environment;
        builder.portVariable = portVariable;
        builder.readinessTimeout = readinessTimeout;
        builder.workloadTimeout = workloadTimeout;
        builder.stopTimeout = stopTimeout;
        return builder;
    }

    /**
     * The path to {@code GET} until it answers {@code 2xx}, once the port accepts a connection.
     *
     * @return the path, or empty when the accepted connection alone means ready, which is the default
     */
    public Optional<String> readinessPath() {
        return Optional.ofNullable(readinessPath);
    }

    /**
     * The paths to {@code GET} in order, each of which must answer {@code 2xx}. When this and
     * {@link #workloadCommand()} are both empty, which is the default, the run sends one {@code GET /} and
     * accepts any status below 500.
     *
     * @return the paths, unmodifiable
     */
    public List<String> workloadPaths() {
        return workloadPaths;
    }

    /**
     * How many times the whole list of {@link #workloadPaths()} is requested: at least 1, and 1 by default.
     *
     * @return the repeat count
     */
    public int workloadRepeat() {
        return workloadRepeat;
    }

    /**
     * A command and its arguments, run after the paths with {@value #URL_VARIABLE} set to the application's base
     * URL. It must exit with status 0 within {@link #workloadTimeout()}.
     *
     * @return the command and its arguments, unmodifiable; empty for none, which is the default
     */
    public List<String> workloadCommand() {
        return workloadCommand;
    }

    /**
     * Whether the application ends by itself, as one that is not a server does. There is then no readiness
     * check, no workload and no stop, and the application must exit with status 0 within
     * {@link #readinessTimeout()}. The default is {@code false}.
     *
     * @return whether the application runs to its exit
     */
    public boolean runToExit() {
        return runToExit;
    }

    /**
     * The path to {@code POST} to stop the application, where an orderly stop needs a request rather than a
     * signal, as on Windows.
     *
     * @return the path, or empty to destroy the process, which is the default
     */
    public Optional<String> stopPath() {
        return Optional.ofNullable(stopPath);
    }

    /**
     * JVM arguments of the training launch, placed before the ones the run adds for its purpose, which win.
     *
     * @return the JVM arguments, unmodifiable; empty by default
     */
    public List<String> jvmArgs() {
        return jvmArgs;
    }

    /**
     * Environment variables added to the training launch, in the order they are set.
     *
     * @return the variables, unmodifiable; empty by default
     */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * The environment variable that receives the free loopback port the application is to listen on:
     * {@code MICRONAUT_SERVER_PORT} by default.
     *
     * @return the name of the variable
     */
    public String portVariable() {
        return portVariable;
    }

    /**
     * How long the application may take to become ready, or to exit with {@link #runToExit()}: 60 seconds by
     * default.
     *
     * @return the timeout, positive
     */
    public Duration readinessTimeout() {
        return readinessTimeout;
    }

    /**
     * How long the paths and the command may take together: 60 seconds by default.
     *
     * @return the timeout, positive
     */
    public Duration workloadTimeout() {
        return workloadTimeout;
    }

    /**
     * How long the application may take to exit once asked to, before it is killed: 30 seconds by default.
     *
     * @return the timeout, positive
     */
    public Duration stopTimeout() {
        return stopTimeout;
    }

    /**
     * Compares every setting.
     *
     * @param other the object to compare with
     * @return whether {@code other} is training settings with the same value for every setting
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TrainingSettings that)) {
            return false;
        }
        return workloadRepeat == that.workloadRepeat
                && runToExit == that.runToExit
                && Objects.equals(readinessPath, that.readinessPath)
                && workloadPaths.equals(that.workloadPaths)
                && workloadCommand.equals(that.workloadCommand)
                && Objects.equals(stopPath, that.stopPath)
                && jvmArgs.equals(that.jvmArgs)
                && environment.equals(that.environment)
                && portVariable.equals(that.portVariable)
                && readinessTimeout.equals(that.readinessTimeout)
                && workloadTimeout.equals(that.workloadTimeout)
                && stopTimeout.equals(that.stopTimeout);
    }

    /**
     * Hashes every setting.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(readinessPath, workloadPaths, workloadRepeat, workloadCommand, runToExit, stopPath,
                jvmArgs, environment, portVariable, readinessTimeout, workloadTimeout, stopTimeout);
    }

    /**
     * Describes the settings for a log. The values of the environment variables are left out, since they may be
     * secrets; their names are kept.
     *
     * @return the description
     */
    @Override
    public String toString() {
        StringJoiner hidden = new StringJoiner(", ", "{", "}");
        environment.keySet().forEach(name -> hidden.add(name + "=***"));
        return "TrainingSettings[readinessPath=" + readinessPath
                + ", workloadPaths=" + workloadPaths
                + ", workloadRepeat=" + workloadRepeat
                + ", workloadCommand=" + workloadCommand
                + ", runToExit=" + runToExit
                + ", stopPath=" + stopPath
                + ", jvmArgs=" + jvmArgs
                + ", environment=" + hidden
                + ", portVariable=" + portVariable
                + ", readinessTimeout=" + readinessTimeout
                + ", workloadTimeout=" + workloadTimeout
                + ", stopTimeout=" + stopTimeout + "]";
    }

    private static List<String> copyOf(String setting, List<String> values) {
        Objects.requireNonNull(values, setting);
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null) {
                throw new IllegalArgumentException(setting + " must not contain null");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    private static Map<String, String> environment(Map<String, String> values) {
        Objects.requireNonNull(values, "environment");
        // Insertion order is the order the variables are set in, and the order a failure names them in.
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> variable : values.entrySet()) {
            String name = variable.getKey();
            if (name == null) {
                throw new IllegalArgumentException("environment must not contain a null name");
            }
            if (variable.getValue() == null) {
                throw new IllegalArgumentException("environment has no value for " + name);
            }
            copy.put(name, variable.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void requirePath(String setting, String path) {
        if (!path.startsWith("/")) {
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
        if (name.isBlank() || name.indexOf('=') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(setting + " needs the name of an environment variable, not '"
                    + name + "'");
        }
    }

    private static void requirePositive(String setting, Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(setting + " must be positive, not " + timeout);
        }
    }

    /**
     * Collects training settings, starting from the {@linkplain TrainingSettings#defaults() defaults}. A setter
     * that is not called leaves the default in place, and a later call replaces an earlier one. The setters
     * only store what they are given; {@link #build()} checks it all, so a value that is not usable, such as a
     * {@code null} where a setting takes none, fails there.
     *
     * <p>Stable API for all of 1.x, like the settings: a setting added in a minor release adds a setter.</p>
     *
     * @since 1.0
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
         * Sets {@link TrainingSettings#workloadPaths()}, replacing the paths set before.
         *
         * @param value the paths, in request order; {@link #build()} copies them
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
         * Sets {@link TrainingSettings#workloadCommand()}, replacing the command set before.
         *
         * @param value the command and its arguments, empty for none; {@link #build()} copies them
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
         * Sets {@link TrainingSettings#jvmArgs()}, replacing the arguments set before.
         *
         * @param value the JVM arguments; {@link #build()} copies them
         * @return this builder
         */
        public Builder jvmArgs(List<String> value) {
            this.jvmArgs = value;
            return this;
        }

        /**
         * Sets {@link TrainingSettings#environment()}, replacing the variables set before.
         *
         * @param value the environment variables to add, in the order to set them; {@link #build()} copies them
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
         * Validates and builds the settings. Every failure names the setting it is about.
         *
         * @return the settings
         * @throws NullPointerException     if {@code workloadPaths}, {@code workloadCommand}, {@code jvmArgs},
         *                                  {@code environment}, {@code portVariable} or a timeout was set to
         *                                  {@code null}; the message is the name of the setting
         * @throws IllegalArgumentException if a value is not usable: a list that holds {@code null}, an
         *                                  environment with a {@code null} name or value, a path that is not a
         *                                  request path, a {@code workloadRepeat} below 1, a blank program or
         *                                  JVM argument, a name that is not an environment variable's, a timeout
         *                                  that is not positive, or a workload with {@code runToExit}
         */
        public TrainingSettings build() {
            return new TrainingSettings(this);
        }
    }
}
