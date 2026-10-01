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

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@link RunnerJarBuilder#build(RunnerJarSpec, BuildLogger)} produced.
 *
 * <p>The counts are the ones worth printing at the end of a build or asserting on in a test: how much went
 * in, how much came out, and whether anything was dropped along the way. Every warning was also reported to
 * the {@link BuildLogger} as the build ran; they are collected here so a caller that passed
 * {@link BuildLogger#noOp()} still sees them. A plugin reports the build with {@link #summary()}.</p>
 *
 * <p>Only the builder creates a result, so later releases can add accessors without breaking callers.</p>
 *
 * @since 1.0
 */
public final class RunnerJarResult {

    private final Path output;
    private final int jarCount;
    private final int entryCount;
    private final int applicationEntryCount;
    private final int mergedServiceEntryCount;
    private final long archiveSize;
    private final List<String> warnings;
    private final Map<String, String> effectiveOptions;
    private final boolean logbackPrecompiled;
    private final List<TransformReport> transforms;
    private final int staticServiceSlots;
    private final String staticServicesCoreVersion;
    private final boolean definitionPrefetch;

    /**
     * Validates the result and makes its collections immutable.
     *
     * @param output                  the archive that was written
     * @param jarCount                the number of jars in the index
     * @param entryCount              the number of index records
     * @param applicationEntryCount   the number of physical records of the application layer
     * @param mergedServiceEntryCount the number of merged service entries
     * @param archiveSize             the length of the archive
     * @param warnings                the warnings reported during the build
     * @param effectiveOptions        the options the archive was built with
     * @param logbackPrecompiled      whether a Logback configurator was generated
     * @throws NullPointerException     if {@code output}, {@code warnings} or {@code effectiveOptions} is
     *                                  {@code null}
     * @throws IllegalArgumentException if a count or the size is negative
     */
    RunnerJarResult(Path output, int jarCount, int entryCount, int applicationEntryCount,
            int mergedServiceEntryCount, long archiveSize, List<String> warnings,
            Map<String, String> effectiveOptions, boolean logbackPrecompiled) {
        this(output, jarCount, entryCount, applicationEntryCount, mergedServiceEntryCount, archiveSize, warnings,
                effectiveOptions, logbackPrecompiled, List.of(), 0, null);
    }

    /**
     * Validates the result and makes its collections immutable.
     *
     * @param output                  the archive that was written
     * @param jarCount                the number of jars in the index
     * @param entryCount              the number of index records
     * @param applicationEntryCount   the number of physical records of the application layer
     * @param mergedServiceEntryCount the number of merged service entries
     * @param archiveSize             the length of the archive
     * @param warnings                the warnings reported during the build
     * @param effectiveOptions        the options the archive was built with
     * @param logbackPrecompiled      whether a Logback configurator was generated
     * @param transforms              what each build-time class transform did, in the order they ran
     * @param staticServiceSlots      the number of names the static service table lists
     * @param staticServicesCoreVersion the micronaut-core the static service table was generated for, or
     *                                  {@code null} when there is no table
     * @throws NullPointerException     if a reference argument other than {@code staticServicesCoreVersion} is
     *                                  {@code null}
     * @throws IllegalArgumentException if a count or the size is negative
     */
    RunnerJarResult(Path output, int jarCount, int entryCount, int applicationEntryCount,
            int mergedServiceEntryCount, long archiveSize, List<String> warnings,
            Map<String, String> effectiveOptions, boolean logbackPrecompiled, List<TransformReport> transforms,
            int staticServiceSlots, String staticServicesCoreVersion) {
        this.output = Objects.requireNonNull(output, "output");
        this.transforms = List.copyOf(Objects.requireNonNull(transforms, "transforms"));
        this.warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        this.effectiveOptions = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(effectiveOptions, "effectiveOptions")));
        if (jarCount < 0 || entryCount < 0 || applicationEntryCount < 0 || mergedServiceEntryCount < 0
                || archiveSize < 0 || staticServiceSlots < 0) {
            throw new IllegalArgumentException("Negative count in the result of packaging " + output);
        }
        this.jarCount = jarCount;
        this.entryCount = entryCount;
        this.applicationEntryCount = applicationEntryCount;
        this.mergedServiceEntryCount = mergedServiceEntryCount;
        this.archiveSize = archiveSize;
        this.logbackPrecompiled = logbackPrecompiled;
        this.staticServiceSlots = staticServiceSlots;
        this.staticServicesCoreVersion = staticServicesCoreVersion;
        this.definitionPrefetch = false;
    }

    /**
     * A copy of another result that says whether the bean definition prefetch was packaged.
     *
     * @param result             the result to copy
     * @param definitionPrefetch whether the bean definition prefetch was packaged
     */
    private RunnerJarResult(RunnerJarResult result, boolean definitionPrefetch) {
        this.output = result.output;
        this.jarCount = result.jarCount;
        this.entryCount = result.entryCount;
        this.applicationEntryCount = result.applicationEntryCount;
        this.mergedServiceEntryCount = result.mergedServiceEntryCount;
        this.archiveSize = result.archiveSize;
        this.warnings = result.warnings;
        this.effectiveOptions = result.effectiveOptions;
        this.logbackPrecompiled = result.logbackPrecompiled;
        this.transforms = result.transforms;
        this.staticServiceSlots = result.staticServiceSlots;
        this.staticServicesCoreVersion = result.staticServicesCoreVersion;
        this.definitionPrefetch = definitionPrefetch;
    }

    /**
     * This result, saying whether the bean definition prefetch was packaged; a result says it was not unless it
     * comes from here.
     *
     * @param value whether the bean definition prefetch was packaged
     * @return the result
     */
    RunnerJarResult withDefinitionPrefetch(boolean value) {
        return new RunnerJarResult(this, value);
    }

    /**
     * The archive that was written.
     *
     * @return the output file
     */
    public Path output() {
        return output;
    }

    /**
     * The number of jars in the index, the application layer included, so the number of nested dependencies
     * is one less.
     *
     * @return the jar count
     */
    public int jarCount() {
        return jarCount;
    }

    /**
     * The number of index records: physical entries, versioned aliases and synthesised directories of every
     * jar.
     *
     * @return the record count
     */
    public int entryCount() {
        return entryCount;
    }

    /**
     * The number of distinct entries the application output contributed, which is what ends up under
     * {@code MICRONAUT-INF/classes/}. The merged service entries belong to jar {@code 0} as well but are
     * counted separately.
     *
     * @return the application entry count
     */
    public int applicationEntryCount() {
        return applicationEntryCount;
    }

    /**
     * The number of distinct {@code META-INF/micronaut/<service>/<name>} entries merged into the outer
     * archive root.
     *
     * @return the merged service entry count
     */
    public int mergedServiceEntryCount() {
        return mergedServiceEntryCount;
    }

    /**
     * The length of the archive.
     *
     * @return the size in bytes
     */
    public long archiveSize() {
        return archiveSize;
    }

    /**
     * Every warning the build reported, in the order it reported them.
     *
     * @return the warnings, unmodifiable
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * The number of nested dependency jars, which is every jar but the application layer. A dependency the
     * builder skipped because it is not a ZIP archive is not counted.
     *
     * @return the dependency count
     */
    public int dependencyCount() {
        return Math.max(0, jarCount - 1);
    }

    /**
     * The value every {@link RunnerJarOption} had in this build, keyed by option name in table order, as
     * {@link RunnerJarSpec#effectiveOptions()} reports them.
     *
     * @return the effective options, unmodifiable
     */
    public Map<String, String> effectiveOptions() {
        return effectiveOptions;
    }

    /**
     * Whether the build compiled the application's {@code logback.xml} into a Logback {@code Configurator}, which
     * {@link RunnerJarSpec#precompileLogback()} requests. It is {@code false} whenever nothing was generated; the
     * build log names the reason.
     *
     * @return whether the archive carries a precompiled Logback configuration
     */
    public boolean logbackPrecompiled() {
        return logbackPrecompiled;
    }

    /**
     * What each build-time class transform did to the classes it ran over, one report per transform that
     * ran, in the order they ran: {@code desugarLambdas}, which runs over the dependencies and the application
     * layer, then {@code stripLocalVariables}, which runs over the dependencies. It is empty when none ran:
     * every transform is turned off, the dependencies are nested with {@link Compression#PRESERVE}, or a
     * transform was turned off for the build because the class path needs what it would remove.
     *
     * @return the reports, unmodifiable
     */
    public List<TransformReport> transforms() {
        return transforms;
    }

    /**
     * The number of implementation names the static service table lists, which
     * {@link RunnerJarSpec#staticServices()} requests. It is {@code 0} when no table was generated; the build
     * log names the reason.
     *
     * @return the slot count of the static service table
     */
    public int staticServiceSlots() {
        return staticServiceSlots;
    }

    /**
     * The micronaut-core version the static service table was generated for, as that JAR's manifest states
     * it.
     *
     * @return the version, or empty when no table was generated
     */
    public Optional<String> staticServicesCoreVersion() {
        return Optional.ofNullable(staticServicesCoreVersion);
    }

    /**
     * Whether the build packaged the bean definition prefetch, which {@link RunnerJarSpec#definitionPrefetch()}
     * requests: the entry stub then starts Micronaut's bean definition loading before the application's
     * {@code main}. It is {@code false} whenever the prefetch was left out, whatever the option said; the build
     * log names the reason. {@link #effectiveOptions()} reports what was requested.
     *
     * @return whether the archive carries the bean definition prefetch
     */
    public boolean definitionPrefetch() {
        return definitionPrefetch;
    }

    /**
     * One line that reports the build, for a plugin to log:
     * {@code Runner jar written to <output> (N dependencies, M index records, S bytes)}.
     *
     * @return the summary
     */
    public String summary() {
        return "Runner jar written to " + output + " (" + dependencyCount() + " dependencies, " + entryCount
                + " index records, " + archiveSize + " bytes)";
    }

    @Override
    public String toString() {
        return summary();
    }
}
