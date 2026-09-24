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
package io.micronaut.runner.build;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Every packaging option of a runner jar, in one table: its name, the type of its value, its default,
 * how the build plugins expose it and the Runner release that added it.
 *
 * <p>A build plugin passes options to {@link RunnerJarSpec.Builder#option(String, String)} by name, as text,
 * and the packaging library parses, validates and defaults them. A plugin therefore owns no Runner default
 * and no Runner parsing; it only decides which options get a typed property of their own.</p>
 *
 * <h2>Rules</h2>
 * <ul>
 *     <li>Options are added only in Runner minor releases.</li>
 *     <li>A new option starts as {@link Exposure#PASSTHROUGH}: a build sets it by name, through the plugin's
 *     generic options map, and it needs no plugin release.</li>
 *     <li>An option becomes {@link Exposure#TYPED} only in a Runner minor release, once its default is
 *     settled. The Micronaut build plugins then add its typed property in their next minor release.</li>
 * </ul>
 *
 * <h2>Value grammar</h2>
 * <p>{@link RunnerJarSpec.Builder#option(String, String)} reads a value according to {@link #valueType()},
 * and {@link RunnerJarSpec#effectiveOptions()} writes each value back in the same grammar:</p>
 * <ul>
 *     <li>{@link Boolean}: {@code true} or {@code false}, ignoring case and surrounding whitespace. Anything
 *     else fails.</li>
 *     <li>{@link Compression}: as {@link Compression#parse(String)} reads it.</li>
 *     <li>{@link List}: comma-separated. Entries are trimmed and empty entries dropped, so the empty string
 *     is the empty list.</li>
 *     <li>{@link Map}: one {@code Name: value} pair per line, as in {@code MANIFEST.MF} but without
 *     continuation lines. Surrounding whitespace of names and values is ignored and blank lines are
 *     skipped, so the empty string is the empty map.</li>
 * </ul>
 *
 * @since 1.0
 */
public enum RunnerJarOption {

    /**
     * How the nested dependencies are stored, a {@link Compression} name. See
     * {@link RunnerJarSpec.Builder#compression(Compression)}.
     */
    COMPRESSION("compression", Compression.class, "STORED", Exposure.TYPED, "1.0"),

    /**
     * Whether to generate the entry stub that calls the main method directly. See
     * {@link RunnerJarSpec.Builder#entryStub(boolean)}.
     */
    ENTRY_STUB("entryStub", Boolean.class, "true", Exposure.TYPED, "1.0"),

    /**
     * Whether the application layer itself is multi-release. See
     * {@link RunnerJarSpec.Builder#multiRelease(boolean)}.
     */
    MULTI_RELEASE("multiRelease", Boolean.class, "false", Exposure.TYPED, "1.0"),

    /**
     * Whether to write {@code Enable-Native-Access: ALL-UNNAMED} into the manifest. See
     * {@link RunnerJarSpec.Builder#enableNativeAccess(boolean)}.
     */
    ENABLE_NATIVE_ACCESS("enableNativeAccess", Boolean.class, "false", Exposure.TYPED, "1.0"),

    /**
     * The {@code module/package} pairs written into the {@code Add-Opens} manifest attribute. See
     * {@link RunnerJarSpec.Builder#addOpens(List)}.
     */
    ADD_OPENS("addOpens", List.class, "", Exposure.TYPED, "1.0"),

    /**
     * The {@code module/package} pairs written into the {@code Add-Exports} manifest attribute. See
     * {@link RunnerJarSpec.Builder#addExports(List)}.
     */
    ADD_EXPORTS("addExports", List.class, "", Exposure.TYPED, "1.0"),

    /**
     * Extra main attributes of the runner jar's manifest. See
     * {@link RunnerJarSpec.Builder#manifestAttributes(Map)}.
     */
    MANIFEST_ATTRIBUTES("manifestAttributes", Map.class, "", Exposure.TYPED, "1.0");

    private final String optionName;
    private final Class<?> valueType;
    private final String defaultValue;
    private final String defaultDescription;
    private final Exposure exposure;
    private final String since;

    RunnerJarOption(String optionName, Class<?> valueType, String defaultValue, Exposure exposure,
            String since) {
        this(optionName, valueType, defaultValue, describe(defaultValue), exposure, since);
    }

    RunnerJarOption(String optionName, Class<?> valueType, String defaultValue, String defaultDescription,
            Exposure exposure, String since) {
        this.optionName = optionName;
        this.valueType = valueType;
        this.defaultValue = defaultValue;
        this.defaultDescription = defaultDescription;
        this.exposure = exposure;
        this.since = since;
    }

    /**
     * The option's name, in camel case, as a build script, a POM and
     * {@link RunnerJarSpec.Builder#option(String, String)} spell it. {@link #name()} is the enum constant's.
     *
     * @return the option name
     */
    public String optionName() {
        return optionName;
    }

    /**
     * The type of the option's value, which decides how its text is read: {@link Boolean},
     * {@link Compression}, {@link List} or {@link Map}.
     *
     * @return the value type
     */
    public Class<?> valueType() {
        return valueType;
    }

    /**
     * The value the option has when nothing sets it, in the grammar
     * {@link RunnerJarSpec.Builder#option(String, String)} reads.
     *
     * @return the default, or empty when the default depends on other inputs or there is none
     */
    public Optional<String> defaultValue() {
        return Optional.ofNullable(defaultValue);
    }

    /**
     * The default as documentation states it: the value itself, {@code empty} for an empty list or map,
     * how a conditional default is chosen, or {@code none}.
     *
     * @return a description of the default
     */
    public String defaultDescription() {
        return defaultDescription;
    }

    /**
     * How the build plugins expose the option.
     *
     * @return the exposure
     */
    public Exposure exposure() {
        return exposure;
    }

    /**
     * The Runner release that added the option, as {@code major.minor}.
     *
     * @return the release
     */
    public String since() {
        return since;
    }

    /**
     * Finds an option by its {@linkplain #optionName() name}. Names are compared exactly.
     *
     * @param optionName the option name, such as {@code entryStub}
     * @return the option, or empty when no option has that name
     * @throws NullPointerException if {@code optionName} is {@code null}
     */
    public static Optional<RunnerJarOption> named(String optionName) {
        Objects.requireNonNull(optionName, "optionName");
        for (RunnerJarOption option : values()) {
            if (option.optionName.equals(optionName)) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }

    /**
     * The failure for a name that is not in this table. It lists the known names, in table order, and the
     * version of the packaging library that knows them, because an unknown name usually means that a build
     * sets an option a newer release added.
     *
     * @param optionName the unknown name
     * @return the exception to throw
     */
    static IllegalArgumentException unknown(String optionName) {
        String version = RunnerJarOption.class.getPackage().getImplementationVersion();
        return new IllegalArgumentException("Unknown Micronaut Runner option '" + optionName
                + "'. micronaut-runner-build " + (version == null ? "unknown" : version) + " knows: "
                + Arrays.stream(values()).map(RunnerJarOption::optionName).collect(Collectors.joining(", ")));
    }

    private static String describe(String defaultValue) {
        if (defaultValue == null) {
            return "none";
        }
        return defaultValue.isEmpty() ? "empty" : defaultValue;
    }

    /**
     * How the build plugins expose an option.
     */
    public enum Exposure {

        /**
         * The option has a typed property in each build plugin, a Gradle task property or a Maven parameter,
         * with the documented default.
         */
        TYPED,

        /**
         * The option is set by name, through the build plugin's generic options: Gradle's
         * {@code options.put(name, value)}, Maven's {@code <runnerOptions>} or
         * {@code -Dmicronaut.runner.<name>}. It needs no plugin release.
         */
        PASSTHROUGH
    }
}
