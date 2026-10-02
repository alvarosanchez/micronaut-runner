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

import io.micronaut.runner.build.AotCacheSettings;
import io.micronaut.runner.build.AotTarget;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;

/**
 * The JDK AOT cache that {@code micronautRunnerJdkAotCache} trains and verifies: the {@code jdkAotCache { }} block
 * of the {@link MicronautRunnerExtension}. The task takes its own copy, whose conventions are the block's values.
 *
 * <pre>
 * micronautRunner {            // or micronaut { runner { } } with a Micronaut plugin
 *     training {
 *         workloadPaths = ['/hello']
 *     }
 *     jdkAotCache {
 *         enabled = true       // assemble also builds micronautRunnerJdkAotCache
 *         target = 'layout'    // or 'singleJar'
 *     }
 * }
 * </pre>
 *
 * <p>The packaging library owns the defaults ({@link AotTarget#DEFAULT} and {@link AotCacheSettings#defaults()})
 * and the validation. The API is experimental: it may change in any release, and it belongs to this interim
 * plugin only.</p>
 *
 * @since 1.0
 */
public abstract class JdkAotCacheSpec {

    /** Gradle creates the block. */
    public JdkAotCacheSpec() {
    }

    /**
     * Whether {@code assemble} also builds the cache. Defaults to {@code false}; the task runs whenever it is
     * asked for.
     *
     * @return the switch
     */
    @Internal
    public abstract Property<Boolean> getEnabled();

    /**
     * What the cache is trained for: {@code layout}, the extracted layout that every JDK caches in full, or
     * {@code singleJar}, a copy of the Runner JAR, recorded with {@code micronaut.runner.aot.training} so that JDK 27
     * and later cache its classes. Defaults to {@code layout}.
     *
     * @return the target
     */
    @Input
    @Optional
    public abstract Property<String> getTarget();

    /**
     * Whether {@code app.jvmopts} also carries {@code -XX:AOTMode=on}, which turns a cache that no longer matches
     * into a failed launch instead of a slow one. Defaults to {@code false}.
     *
     * @return the strict flag
     */
    @Input
    @Optional
    public abstract Property<Boolean> getStrict();

    /**
     * JVM arguments of the recording, the creation, the verification launches and {@code app.jvmopts}: the flags
     * the cache depends on, such as the collector, go here so that training and production agree. The training
     * block's {@code jvmArgs} belong to {@code recordStartupProfile} only.
     *
     * @return the JVM arguments
     */
    @Input
    @Optional
    public abstract ListProperty<String> getJvmArgs();

    /**
     * How many strict {@code -version} probes the verification runs. Defaults to 10.
     *
     * @return the probe count
     */
    @Input
    @Optional
    public abstract Property<Integer> getVerifyProbes();

    /**
     * The share of the classes a strict launch loads that must come from the cache, for the {@code layout}
     * target, from 0 to 1. Defaults to 0.95. It is a {@link Number}, so that a Groovy decimal literal such as
     * {@code 0.9} and a Kotlin {@code Double} both set it.
     *
     * @return the minimum coverage
     */
    @Input
    @Optional
    public abstract Property<Number> getMinCoverage();

    /**
     * Shows the packaging library's defaults as the conventions of the extension's block.
     *
     * @param spec the extension's block
     */
    static void defaults(JdkAotCacheSpec spec) {
        AotCacheSettings defaults = AotCacheSettings.defaults();
        spec.getEnabled().convention(false);
        spec.getTarget().convention(AotTarget.DEFAULT.value());
        spec.getStrict().convention(defaults.strict());
        spec.getJvmArgs().convention(defaults.jvmArgs());
        spec.getVerifyProbes().convention(defaults.verifyProbes());
        spec.getMinCoverage().convention(defaults.minCoverage());
    }

    /**
     * Gives a task's block the extension's values as its conventions, so that a value set on the task wins for
     * that task.
     *
     * @param spec the task's block
     * @param from the extension's block
     */
    static void conventions(JdkAotCacheSpec spec, JdkAotCacheSpec from) {
        spec.getEnabled().convention(from.getEnabled());
        spec.getTarget().convention(from.getTarget());
        spec.getStrict().convention(from.getStrict());
        spec.getJvmArgs().convention(from.getJvmArgs());
        spec.getVerifyProbes().convention(from.getVerifyProbes());
        spec.getMinCoverage().convention(from.getMinCoverage());
    }

    /**
     * Maps a block onto the packaging library's settings. An absent value leaves the library's default in place.
     *
     * @param spec the block
     * @return the settings
     * @throws IllegalArgumentException if a value is not usable; the message names the setting
     */
    static AotCacheSettings settings(JdkAotCacheSpec spec) {
        AotCacheSettings.Builder settings = AotCacheSettings.builder();
        if (spec.getStrict().isPresent()) {
            settings.strict(spec.getStrict().get());
        }
        if (spec.getJvmArgs().isPresent()) {
            settings.jvmArgs(spec.getJvmArgs().get());
        }
        if (spec.getVerifyProbes().isPresent()) {
            settings.verifyProbes(spec.getVerifyProbes().get());
        }
        if (spec.getMinCoverage().isPresent()) {
            settings.minCoverage(spec.getMinCoverage().get().doubleValue());
        }
        return settings.build();
    }

    /**
     * The block's target.
     *
     * @param spec the block
     * @return the target, {@link AotTarget#DEFAULT} when none is set
     * @throws IllegalArgumentException if the value names no target; the message names both
     */
    static AotTarget target(JdkAotCacheSpec spec) {
        return spec.getTarget().isPresent() ? AotTarget.parse(spec.getTarget().get()) : AotTarget.DEFAULT;
    }
}
