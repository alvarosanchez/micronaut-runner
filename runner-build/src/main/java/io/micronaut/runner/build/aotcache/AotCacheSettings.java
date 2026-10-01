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
package io.micronaut.runner.build.aotcache;

import io.micronaut.core.annotation.Internal;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * How a JDK AOT cache is built and how strictly it is verified. Both build plugins start from
 * {@link #defaults()} and replace only what the user set, so every default lives here.
 *
 * <p>Internal to Runner's interim build plugins: it may change in any release.</p>
 *
 * @param strict          whether the launch argfile also carries {@code -XX:AOTMode=on}, which turns a cache
 *                        that no longer matches, such as one whose JARs' modification times were rewritten, into
 *                        a failed launch instead of a slow one
 * @param jvmArgs         the JVM arguments of every launch of the cache: the recording, the creation, the strict
 *                        probes, the smoke launch and the argfile. Flags the cache depends on, such as the
 *                        collector, compact object headers or a heap size that changes the compressed-oops mode,
 *                        go here, so that training and production agree
 * @param verifyProbes    how many strict {@code -version} probes the gate runs; at least 1
 * @param minCoverage     the share of the classes a strict smoke launch loads that must come from the cache,
 *                        from 0 to 1
 * @param enforceCoverage whether the coverage checks fail the build; without it they are only reported
 * @since 1.0
 */
@Internal
public record AotCacheSettings(boolean strict,
                               List<String> jvmArgs,
                               int verifyProbes,
                               double minCoverage,
                               boolean enforceCoverage) {

    /** The default of {@link #verifyProbes()}. */
    static final int DEFAULT_VERIFY_PROBES = 10;

    /** The default of {@link #minCoverage()}. */
    static final double DEFAULT_MIN_COVERAGE = 0.95;

    /** The options the build sets itself, which {@link #jvmArgs()} must not name. */
    private static final List<String> RESERVED = List.of("-XX:AOTMode", "-XX:AOTCache", "-XX:AOTCacheOutput",
            "-XX:AOTConfiguration", "-XX:SharedArchiveFile", "-XX:ArchiveClassesAtExit", "-Xshare",
            "-XX:+AutoCreateSharedArchive", "-jar", "-cp", "-classpath", "--class-path");

    /**
     * Validates the settings and copies the list.
     *
     * @throws NullPointerException     if {@code jvmArgs} is {@code null}
     * @throws IllegalArgumentException if a value is not usable; the message names the setting
     */
    public AotCacheSettings {
        jvmArgs = List.copyOf(Objects.requireNonNull(jvmArgs, "jvmArgs"));
        for (String argument : jvmArgs) {
            if (argument.isBlank()) {
                throw new IllegalArgumentException("jvmArgs must not contain a blank argument");
            }
            for (String reserved : RESERVED) {
                if (argument.equals(reserved) || argument.startsWith(reserved + "=")
                        || argument.startsWith(reserved + ":")) {
                    throw new IllegalArgumentException("jvmArgs must not contain " + argument + ": the build sets"
                            + " the cache, its mode and the class path of every launch itself");
                }
            }
        }
        if (verifyProbes < 1) {
            throw new IllegalArgumentException("verifyProbes must be at least 1, not " + verifyProbes
                    + ": one passing probe proves little, since some failures depend on where ASLR puts the heap");
        }
        if (!(minCoverage >= 0 && minCoverage <= 1)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "minCoverage must be between 0 and 1, not %s", minCoverage));
        }
    }

    /**
     * The defaults: not strict, no JVM arguments, 10 probes, a coverage of at least 0.95, enforced.
     *
     * @return the defaults
     */
    public static AotCacheSettings defaults() {
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

    /**
     * The same settings with coverage enforced or only reported.
     *
     * @param enforce whether the coverage checks fail the build
     * @return the settings
     */
    public AotCacheSettings withEnforceCoverage(boolean enforce) {
        return new AotCacheSettings(strict, jvmArgs, verifyProbes, minCoverage, enforce);
    }

    /**
     * Collects cache settings, starting from the defaults. A setter that is not called leaves the default in
     * place.
     *
     * <p>Internal to Runner's interim build plugins: it may change in any release.</p>
     */
    @Internal
    public static final class Builder {

        private boolean strict;
        private List<String> jvmArgs = List.of();
        private int verifyProbes = DEFAULT_VERIFY_PROBES;
        private double minCoverage = DEFAULT_MIN_COVERAGE;
        private boolean enforceCoverage = true;

        private Builder() {
        }

        /**
         * Sets {@link AotCacheSettings#strict()}.
         *
         * @param value whether the argfile carries {@code -XX:AOTMode=on}
         * @return this builder
         */
        public Builder strict(boolean value) {
            this.strict = value;
            return this;
        }

        /**
         * Sets {@link AotCacheSettings#jvmArgs()}.
         *
         * @param value the JVM arguments
         * @return this builder
         */
        public Builder jvmArgs(List<String> value) {
            this.jvmArgs = value;
            return this;
        }

        /**
         * Sets {@link AotCacheSettings#verifyProbes()}.
         *
         * @param value the number of strict probes
         * @return this builder
         */
        public Builder verifyProbes(int value) {
            this.verifyProbes = value;
            return this;
        }

        /**
         * Sets {@link AotCacheSettings#minCoverage()}.
         *
         * @param value the minimum share of classes from the cache
         * @return this builder
         */
        public Builder minCoverage(double value) {
            this.minCoverage = value;
            return this;
        }

        /**
         * Sets {@link AotCacheSettings#enforceCoverage()}.
         *
         * @param value whether the coverage checks fail the build
         * @return this builder
         */
        Builder enforceCoverage(boolean value) {
            this.enforceCoverage = value;
            return this;
        }

        /**
         * Validates and builds the settings.
         *
         * @return the settings
         * @throws IllegalArgumentException if a value is not usable; the message names the setting
         */
        public AotCacheSettings build() {
            return new AotCacheSettings(strict, jvmArgs, verifyProbes, minCoverage, enforceCoverage);
        }
    }
}
