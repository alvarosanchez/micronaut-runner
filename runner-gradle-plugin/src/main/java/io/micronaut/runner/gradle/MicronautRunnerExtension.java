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

import io.micronaut.runner.build.RunnerJarOption;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * The packaging options of every archive the plugin builds, and its kill switch.
 *
 * <p>The plugin creates it as {@value MicronautRunnerPlugin#EXTENSION_NAME}. When a Micronaut plugin has
 * created the {@code micronaut} extension, the same instance is also {@code micronaut.runner}, the block the
 * Micronaut Gradle plugin's own Runner support reads, whatever order the plugins are applied in:</p>
 *
 * <pre>
 * micronaut {
 *     runner {
 *         compression = 'STORED'
 *     }
 * }
 * </pre>
 *
 * <p>Each option is the convention of the task property of the same name, so a value set on a task wins
 * for that task. The facts of the build, such as the main class, the inputs and the archive name, are
 * properties of the task only. Every {@link RunnerJarOption.Exposure#TYPED typed} option has a property here,
 * whose convention is the packaging library's default when that default is unconditional; every option,
 * typed or not, can also be set by name through {@link #getOptions()}.</p>
 *
 * @since 1.0
 */
public abstract class MicronautRunnerExtension {

    /** Gradle creates the extension when the plugin is applied. */
    public MicronautRunnerExtension() {
    }

    /**
     * Whether the plugin's tasks run. Defaults to {@code true}; with {@code false}, every task the plugin
     * registers is skipped.
     *
     * @return the kill switch
     */
    public abstract Property<Boolean> getEnabled();

    /**
     * How the entries of each dependency are stored: {@code STORED} or {@code PRESERVE}. The convention is
     * {@link RunnerJarOption#COMPRESSION}'s default.
     *
     * @return the compression mode
     */
    public abstract Property<String> getCompression();

    /**
     * Whether to generate the entry stub that calls the main method directly. The convention is
     * {@link RunnerJarOption#ENTRY_STUB}'s default.
     *
     * @return the entry stub flag
     */
    public abstract Property<Boolean> getEntryStub();

    /**
     * Whether the application layer itself is multi-release. The convention is
     * {@link RunnerJarOption#MULTI_RELEASE}'s default.
     *
     * @return the multi-release flag
     */
    public abstract Property<Boolean> getMultiRelease();

    /**
     * Whether to write {@code Enable-Native-Access: ALL-UNNAMED} into the manifest. The convention is
     * {@link RunnerJarOption#ENABLE_NATIVE_ACCESS}'s default.
     *
     * @return the native access flag
     */
    public abstract Property<Boolean> getEnableNativeAccess();

    /**
     * Module/package pairs written into the manifest as {@code Add-Opens}, in JAR manifest syntax such as
     * {@code java.base/java.lang}. Empty by default.
     *
     * @return the packages to open
     */
    public abstract ListProperty<String> getAddOpens();

    /**
     * Module/package pairs written into the manifest as {@code Add-Exports}, in JAR manifest syntax such as
     * {@code java.base/sun.nio.ch}. Empty by default.
     *
     * @return the packages to export
     */
    public abstract ListProperty<String> getAddExports();

    /**
     * Extra main manifest attributes. Empty by default.
     *
     * @return the attributes
     */
    public abstract MapProperty<String, String> getManifestAttributes();

    /**
     * Packaging options by {@linkplain RunnerJarOption#optionName() name}, for the options that have no typed
     * property. Each value uses the grammar {@link RunnerJarOption} documents; the packaging library parses and
     * validates it, and an unknown name fails the task.
     *
     * @return the options by name
     */
    public abstract MapProperty<String, String> getOptions();
}
