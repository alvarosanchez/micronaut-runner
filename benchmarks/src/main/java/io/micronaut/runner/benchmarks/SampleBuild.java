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
package io.micronaut.runner.benchmarks;

import io.micronaut.runner.IndexFormat;
import io.micronaut.runner.build.AotLayout;
import io.micronaut.runner.build.BuildLogger;
import io.micronaut.runner.build.Compression;
import io.micronaut.runner.build.Dependency;
import io.micronaut.runner.build.RunnerJarBuilder;
import io.micronaut.runner.build.RunnerJarResult;
import io.micronaut.runner.build.RunnerJarSpec;
import io.micronaut.runner.build.StartupProfileRecorder;
import io.micronaut.runner.build.training.TrainingSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Turns the sample application into every packaging the benchmark compares.
 *
 * <h2>Identical application bytes</h2>
 * <p>All variants are built from the same compiled classes and the same resolved dependency jars,
 * taken from one Gradle build of the sample. That is the only way the comparison means anything: if the
 * shaded jar were built from one compilation and the runner jar from another, any difference could be a
 * difference in the application rather than in the format.</p>
 *
 * <p>The runner jars are built here by calling {@link RunnerJarBuilder} directly rather than by asking
 * the Gradle plugin for them. Not because the plugin is in doubt - the end-to-end test suite covers that -
 * but because the plugin produces one compression mode per build and the benchmark needs both, from bytes
 * that are identical to every other variant's. The two Shadow jars come from the sample's own
 * {@code shadowJar} and {@code shadowJarStored} tasks, which differ only in entry compression.</p>
 *
 * <h2>Micronaut AOT rows</h2>
 * <p>A sample that applies {@code io.micronaut.aot} also builds {@code optimizedJitJar}, the AOT-optimized
 * application, and {@code optimizedJitJarAll}, its Shadow jar. The {@code maot} rows measure them:
 * {@code shadow-maot} is {@code optimizedJitJarAll} unmodified, and {@code runner-maot} is {@code runner-stored}
 * with {@code optimizedJitJar} as its application layer, which is what the Gradle plugin's
 * {@code optimizedMicronautRunnerJar} packages. In these names {@code maot} stands for Micronaut AOT, and the
 * {@code -aot} suffix stays the JDK AOT cache.</p>
 *
 * <h2>Core and opt-in rows, and selection</h2>
 * <p>Core rows are built by default and are the run's required variants. Opt-in rows, such as the reflection
 * ablation, are built only on request and never gate the exit code; see {@link #variantNames()}. A run builds
 * one selection of rows from a single row table, and a selected row that derives from another (a cached row from
 * its uncached twin, the extracted layout from the STORED jar) builds that row too, once, without reporting
 * it.</p>
 *
 * <h2>Failure is data</h2>
 * <p>Every variant is built inside its own try/catch. One that fails becomes an unavailable
 * {@link Variant} carrying the reason, and the run carries on with the rest.</p>
 *
 * <h2>Every variant is self-contained</h2>
 * <p>Nothing that gets measured is read out of the sample's own {@code build} directory. The class files,
 * the dependency jars and the shaded jars are all copied into the harness's artifacts directory first, and
 * the commands point only at those copies. The reason is not tidiness: a benchmark run takes minutes, and
 * anything else that builds the sample in that window - a developer, the end-to-end test suite, a second
 * agent - runs {@code clean} and takes the artifacts out from under a run in flight. That failure mode is
 * genuinely confusing when it happens ("the jar was there when I checked"), and copying makes it
 * impossible.</p>
 *
 * <h2>Pinned modification times</h2>
 * <p>Copying and rebuilding give every launch input a new modification time in every run, and the JDK rejects
 * an AOT cache whose class-path JARs' times moved. Every copied, written, rebuilt or extracted launch input is
 * therefore pinned to {@link LaunchInputs#PINNED_MODIFICATION_TIME}, so a cache trained in one run is reused in
 * the next while the inputs' bytes are unchanged; see {@link LaunchInputs}.</p>
 */
final class SampleBuild implements SampleSteps {

    /**
     * One row of the matrix, written once: everything the report says about a row that does not depend on how its
     * build went.
     *
     * @param name        the short identifier used in the report, for example {@code runner-stored}
     * @param description one line explaining what the row is
     * @param entryMode   how the harness asks the row to enter the application
     * @param aotCache    whether the row launches with a trained, verified JDK AOT cache
     * @param optIn       whether the row is built only on request and never gates the exit code, rather than core
     * @param source      the row this row is built from, or {@code null}
     */
    record VariantSpec(String name, String description, EntryMode entryMode, boolean aotCache, boolean optIn,
                       String source) {
    }

    private static final boolean CORE = false;
    private static final boolean OPT_IN = true;

    /** The lambda control's unpacked layout, which is trained but is not a row of its own. */
    private static final VariantSpec EXTRACTED_LAMBDAS = new VariantSpec("runner-extracted-lambdas",
            "The lambda control unpacked and run by the JDK's own loader", EntryMode.STANDARD_LOADER, false, OPT_IN,
            "runner-stored-lambdas");

    private static final String GENERATED_ENTRY_STUB = "io.micronaut.runner.generated.AppEntry";

    /** The configurator runner-build generates from logback.xml when it precompiles it. */
    private static final String GENERATED_LOGBACK_CONFIGURATOR =
            "io.micronaut.runner.generated.logback.LogbackConfigurator";

    /** The startup profile the preload, ordered and hybrid rows share, recorded once per run in the artifacts. */
    private static final String STARTUP_PROFILE = "startup-classes.txt";

    /** The launcher property that turns the preloader off. */
    private static final String PRELOAD_PROPERTY = "micronaut.runner.preload";

    /** The working directory of that recording, in the artifacts directory. */
    private static final String STARTUP_PROFILE_WORK = "record-startup-profile";

    /** What records the profile again, for its header: the harness records a fresh one in every run. */
    private static final String STARTUP_PROFILE_RERECORD = "./gradlew :benchmarks:startupBenchmark";

    /** The request the recording is ready at and its workload, as for the trained caches. */
    private static final String HELLO = "/hello";

    /** The task the init script registers on the sample's build. */
    private static final String METADATA_TASK = "runnerBenchmarkMetadata";

    /** Where that task writes, relative to the sample's project directory. */
    private static final String METADATA_FILE = "build/runner-benchmark-metadata.txt";

    /** The init script, carried as a resource of this module. */
    private static final String INIT_SCRIPT = "/io/micronaut/runner/benchmarks/sample-metadata.init.gradle";

    /** How long the sample's Gradle build may take; it resolves the whole Micronaut platform. */
    private static final long BUILD_TIMEOUT_MINUTES = 30;

    /** How long the extraction of a runner jar may take. */
    private static final long EXTRACT_TIMEOUT_SECONDS = 120;

    private final Path sample;
    private final Path artifacts;
    private final PrintStream log;
    private final String mainClass;
    private final String projectName;
    private final List<Path> applicationOutput;
    private final List<Path> dependencies;
    private final Path shadowJar;
    private final Path shadowStoredJar;
    private final Path optimizedJitJar;
    private final Path optimizedJitJarAll;
    private final CpuLimit cpuLimit;

    /** The harness that trains and verifies the caches; set by {@link #variants(StartupHarness, List)}. */
    private StartupHarness harness;

    /** The startup profile of this run, recorded at most once, for every row that packages one. */
    private final StartupProfile startupProfile = new StartupProfile(this::recordStartupProfile);

    private SampleBuild(Path sample,
                        Path artifacts,
                        CpuLimit cpuLimit,
                        PrintStream log,
                        Metadata metadata) {
        this.sample = sample;
        this.artifacts = artifacts;
        this.cpuLimit = cpuLimit;
        this.log = log;
        this.mainClass = metadata.mainClass();
        this.projectName = metadata.projectName();
        this.applicationOutput = metadata.applicationOutput();
        this.dependencies = metadata.dependencies();
        this.shadowJar = metadata.shadowJar();
        this.shadowStoredJar = metadata.shadowStoredJar();
        this.optimizedJitJar = metadata.optimizedJitJar();
        this.optimizedJitJarAll = metadata.optimizedJitJarAll();
    }

    /**
     * Builds the sample and reads back what it produced.
     *
     * @param sample    the sample's project directory
     * @param repo      the Maven repository the runner plugins are published to, as a URI string
     * @param version   the version they were published under
     * @param artifacts where the variants' artifacts are written
     * @param cpuLimit  the CPU limit AOT caches are trained under and identified by, or {@code null} for none
     * @param log       where build progress goes
     * @return the prepared build
     * @throws IOException          if the build fails, times out, or writes no metadata
     * @throws InterruptedException if the wait is interrupted
     */
    static SampleBuild prepare(Path sample, String repo, String version, Path artifacts, CpuLimit cpuLimit,
                               PrintStream log) throws IOException, InterruptedException {
        return prepare(sample, repo, version, null, artifacts, cpuLimit, log);
    }

    /**
     * Builds the sample, optionally against another micronaut-core, and reads back what it produced.
     *
     * @param sample        the sample's project directory
     * @param repo          the Maven repository the runner plugins are published to, as a URI string
     * @param version       the version they were published under
     * @param micronautCore the version every {@code io.micronaut} module of the sample is aligned to, or
     *                      {@code null} for the versions the sample's platform selects
     * @param artifacts     where the variants' artifacts are written
     * @param cpuLimit      the CPU limit AOT caches are trained under and identified by, or {@code null} for none
     * @param log           where build progress goes
     * @return the prepared build
     * @throws IOException          if the build fails, times out, or writes no metadata
     * @throws InterruptedException if the wait is interrupted
     */
    static SampleBuild prepare(Path sample, String repo, String version, String micronautCore, Path artifacts,
                               CpuLimit cpuLimit, PrintStream log) throws IOException, InterruptedException {
        Path init = artifacts.resolve("sample-metadata.init.gradle");
        Files.createDirectories(artifacts);
        try (InputStream in = SampleBuild.class.getResourceAsStream(INIT_SCRIPT)) {
            if (in == null) {
                throw new IOException("The init script " + INIT_SCRIPT + " is not on the class path");
            }
            Files.copy(in, init, StandardCopyOption.REPLACE_EXISTING);
        }

        List<String> arguments = sampleBuildArguments(sample, repo, version, micronautCore, init);
        log.println("[startup-benchmark] building the sample: gradlew " + String.join(" ", arguments));
        GradleResult result = gradle(sample, arguments, java.time.Duration.ofMinutes(BUILD_TIMEOUT_MINUTES));
        if (result.exitCode() != 0) {
            throw new IOException("The sample build failed with status " + result.exitCode() + result.tail());
        }

        Path metadataFile = sample.resolve(METADATA_FILE);
        if (!Files.isRegularFile(metadataFile)) {
            throw new IOException("The sample build produced no " + metadataFile + result.tail());
        }
        Metadata metadata = Metadata.read(metadataFile);
        log.println("[startup-benchmark] sample built: " + metadata.dependencies().size()
                + " dependency jars, main class " + metadata.mainClass());
        return new SampleBuild(sample, artifacts, cpuLimit, log, metadata);
    }

    /**
     * The Gradle arguments that build the sample.
     *
     * @param micronautCore the micronaut-core override, or {@code null} for none
     */
    static List<String> sampleBuildArguments(Path sample, String repo, String version, String micronautCore,
                                             Path init) {
        List<String> command = new ArrayList<>(List.of(
                "--project-dir", sample.toAbsolutePath().toString(),
                "-Prunner.repo=" + repo,
                "-Prunner.version=" + version));
        if (micronautCore != null) {
            // Read by the sample's build, which aligns every io.micronaut module to it.
            command.add("-PbenchmarkMicronautCore=" + micronautCore);
        }
        command.addAll(List.of(
                "--init-script", init.toAbsolutePath().toString(),
                // The init script's task reads the project at execution time, which a configuration cache
                // would refuse. Nothing here is hot enough to want the cache.
                "--no-configuration-cache",
                "--stacktrace",
                METADATA_TASK,
                "shadowJar"));
        return List.copyOf(command);
    }

    /**
     * The sample's project directory.
     *
     * @return the directory
     */
    Path sample() {
        return sample;
    }

    /**
     * The row table: every row the harness can build, in report order. It is the only list of rows, and each
     * row's {@link VariantSpec} is the only place its name, description, entry mode, cache flag, core/opt-in flag
     * and source are written. Core rows are always built and scheduled, and they are the run's required variants:
     * under the required policy each one gates the exit code. Opt-in rows (diagnostic ablations and experiments)
     * are built and scheduled only when selected, and they are reported with their failure counts but never
     * change the exit code. A derived row is handed the variant of its source row, so building a selection
     * builds exactly the rows it derives from, each once.
     */
    private static final List<VariantRows.Row<SampleSteps>> ROWS = List.of(
            row("exploded-cp", "Class files and dependency jars on an explicit, ordered -cp",
                    EntryMode.STANDARD_LOADER, CORE, null, (steps, spec, source) -> steps.explodedClasspath(spec)),
            row("thin-jar", "Application jar with a Class-Path manifest pointing at lib/",
                    EntryMode.STANDARD_LOADER, CORE, null, (steps, spec, source) -> steps.thinJar(spec)),
            row("shadow", "Everything flattened into one jar by the Shadow plugin",
                    EntryMode.STANDARD_LOADER, CORE, null, (steps, spec, source) -> steps.shadow(spec)),
            row("shadow-stored", "The same Shadow inputs written with STORED entries (compression-matched control)",
                    EntryMode.STANDARD_LOADER, CORE, null, (steps, spec, source) -> steps.shadowStored(spec)),
            cached("shadow-aot", "The same Shadow jar with a verified JDK AOT cache",
                    EntryMode.STANDARD_LOADER, CORE, "shadow"),
            row("runner-stored", "Runner jar, nested dependencies re-packed uncompressed; plugin-default entry stub",
                    EntryMode.STUB, CORE, null, (steps, spec, source) -> steps.runnerJar(spec, Compression.STORED,
                            RunnerJarOptions.DEFAULTS)),
            cached("runner-stored-aot", "The same default-entry Runner jar with a verified JDK AOT cache",
                    EntryMode.STUB, CORE, "runner-stored"),
            row("runner-stored-reflection",
                    "Runner jar, nested dependencies re-packed uncompressed; reflection ablation",
                    EntryMode.REFLECTION, OPT_IN, null, (steps, spec, source) -> steps.runnerJar(spec,
                            Compression.STORED, RunnerJarOptions.DEFAULTS)),
            row("runner-stored-preload", "Runner jar, nested dependencies re-packed uncompressed; startup classes"
                    + " recorded in this run and preloaded", EntryMode.STUB, OPT_IN, "runner-stored",
                    (steps, spec, stored) -> steps.preloadingRunnerJar(stored, spec)),
            cached("runner-stored-preload-aot", "The same preloading Runner jar with a verified JDK AOT cache",
                    EntryMode.STUB, OPT_IN, "runner-stored-preload"),
            // The preload row's jar, launched without the preloader: it differs from runner-stored only in the
            // order of each nested jar's entries, startup classes first.
            row("runner-stored-ordered", "Runner jar, nested dependencies re-packed uncompressed with the startup"
                    + " classes recorded in this run first; preload off", EntryMode.STUB, OPT_IN, "runner-stored",
                    (steps, spec, stored) -> steps.orderedRunnerJar(stored, spec)),
            row("runner-stored-hybrid", "Runner jar, HYBRID: the startup classes recorded in this run stored and"
                    + " first, every other dependency class deflated; preload off", EntryMode.STUB, OPT_IN,
                    "runner-stored", (steps, spec, stored) -> steps.hybridRunnerJar(stored, spec)),
            row("runner-stored-positional", "Runner jar, nested dependencies re-packed uncompressed; archiveReads"
                    + " POSITIONAL (index mapped only)", EntryMode.STUB, OPT_IN, null,
                    (steps, spec, source) -> steps.runnerJar(spec, Compression.STORED,
                            RunnerJarOptions.DEFAULTS.withArchiveReads("POSITIONAL"))),
            cached("runner-stored-positional-aot", "The same POSITIONAL Runner jar with a verified JDK AOT cache",
                    EntryMode.STUB, OPT_IN, "runner-stored-positional"),
            row("runner-stored-joran", "The same stored Runner jar with precompileLogback=false: logback.xml read by"
                    + " Joran (control)", EntryMode.STUB, OPT_IN, "runner-stored",
                    (steps, spec, stored) -> steps.joranControl(stored, spec)),
            cached("runner-stored-joran-aot", "The same Joran control Runner jar with a verified JDK AOT cache",
                    EntryMode.STUB, OPT_IN, "runner-stored-joran"),
            // The default keeps local-variable tables; this opt-in row strips those of the dependencies, so that
            // the transform stays measured while it is an option.
            row("runner-stored-stripdebug", "Runner jar, nested dependencies re-packed uncompressed; plugin-default"
                    + " entry stub; stripLocalVariables=true: dependency local-variable tables stripped",
                    EntryMode.STUB, OPT_IN, null,
                    (steps, spec, source) -> steps.runnerJar(spec, Compression.STORED,
                            RunnerJarOptions.DEFAULTS.withStripLocalVariables(true))),
            cached("runner-stored-stripdebug-aot",
                    "The same local-variable-stripping Runner jar with a verified JDK AOT cache",
                    EntryMode.STUB, OPT_IN, "runner-stored-stripdebug"),
            // Today's jar without the table: whatever else the builder defaults to, it has too.
            row("runner-stored-dynamic-services", "Runner jar, nested dependencies re-packed uncompressed;"
                    + " staticServices false (control)", EntryMode.STUB, OPT_IN, null,
                    (steps, spec, source) -> steps.runnerJar(spec, Compression.STORED,
                            RunnerJarOptions.DEFAULTS.withStaticServices(false))),
            cached("runner-stored-dynamic-services-aot",
                    "The same Runner jar without a static service table, with a verified JDK AOT cache",
                    EntryMode.STUB, OPT_IN, "runner-stored-dynamic-services"),
            // The default desugars lambdas; these controls keep every call site an invokedynamic, with everything
            // else at the defaults. The third is the control's extracted layout, which the JDK's own loader runs:
            // there the JDK archives lambdas itself, so that pair should be neutral.
            row("runner-stored-lambdas", "Runner jar, nested dependencies re-packed uncompressed; plugin-default"
                    + " entry stub; dependency lambdas kept", EntryMode.STUB, OPT_IN, null,
                    (steps, spec, source) -> steps.runnerJar(spec, Compression.STORED,
                            RunnerJarOptions.DEFAULTS.withDesugarLambdas(false))),
            cached("runner-stored-lambdas-aot", "The same lambda control with a verified JDK AOT cache",
                    EntryMode.STUB, OPT_IN, "runner-stored-lambdas"),
            new VariantRows.Row<>(new VariantSpec("runner-extracted-lambdas-aot", "The lambda control unpacked and"
                    + " run by the JDK's own loader, with a verified JDK AOT cache", EntryMode.STANDARD_LOADER, true,
                    OPT_IN, "runner-stored-lambdas"),
                    (steps, spec, lambdas) -> steps.aotCache(steps.extracted(lambdas, EXTRACTED_LAMBDAS), spec)),
            row("runner-preserve", "Runner jar, nested dependencies copied byte for byte; plugin-default entry stub",
                    EntryMode.STUB, CORE, null, (steps, spec, source) -> steps.runnerJar(spec, Compression.PRESERVE,
                            RunnerJarOptions.DEFAULTS)),
            row("runner-extracted", "Runner jar unpacked with -Dmicronaut.runner.mode=extract, run by the JDK's own"
                    + " loader", EntryMode.STANDARD_LOADER, CORE, "runner-stored",
                    (steps, spec, stored) -> steps.extracted(stored, spec)),
            cached("runner-extracted-aot", "The same extracted layout with a verified JDK AOT cache",
                    EntryMode.STANDARD_LOADER, CORE, "runner-extracted"),
            // The Micronaut AOT rows; "maot" is Micronaut AOT and the -aot suffix the JDK AOT cache. The two cached
            // ones are opt-in: training two more caches is what they cost.
            row("shadow-maot", "Micronaut AOT's optimizedJitJarAll: the AOT-optimized application flattened by"
                    + " Shadow", EntryMode.STANDARD_LOADER, CORE, null,
                    (steps, spec, source) -> steps.shadowMaot(spec)),
            cached("shadow-maot-aot", "The same optimizedJitJarAll with a verified JDK AOT cache",
                    EntryMode.STANDARD_LOADER, OPT_IN, "shadow-maot"),
            row("runner-maot", "Runner jar of the Micronaut AOT-optimized application (optimizedJitJar);"
                    + " plugin-default entry stub", EntryMode.STUB, CORE, null,
                    (steps, spec, source) -> steps.runnerMaot(spec)),
            cached("runner-maot-aot", "The same Micronaut AOT Runner jar with a verified JDK AOT cache",
                    EntryMode.STUB, OPT_IN, "runner-maot"));

    private static VariantRows.Row<SampleSteps> row(String name, String description, EntryMode entryMode,
                                                    boolean optIn, String source,
                                                    VariantRows.Factory<SampleSteps> factory) {
        return new VariantRows.Row<>(new VariantSpec(name, description, entryMode, false, optIn, source), factory);
    }

    /** A row that trains a JDK AOT cache on its source row and launches with it. */
    private static VariantRows.Row<SampleSteps> cached(String name, String description, EntryMode entryMode,
                                                       boolean optIn, String source) {
        return new VariantRows.Row<>(new VariantSpec(name, description, entryMode, true, optIn, source),
                (steps, spec, uncached) -> steps.aotCache(uncached, spec));
    }

    /**
     * Names every core variant in report order without building their artifacts. These are the rows a run
     * builds by default, and the ones that can gate its exit code.
     *
     * @return the core variant names
     */
    static List<String> variantNames() {
        return ROWS.stream().map(VariantRows.Row::spec).filter(spec -> !spec.optIn()).map(VariantSpec::name).toList();
    }

    /**
     * Names every row the harness can build, core and opt-in, in report order. These are the valid
     * {@code --variants} names.
     *
     * @return every variant name
     */
    static List<String> allVariantNames() {
        return ROWS.stream().map(row -> row.spec().name()).toList();
    }

    /**
     * The row of the table with a name.
     *
     * @param name the row
     * @return its spec
     * @throws IllegalArgumentException if the table has no such row
     */
    static VariantSpec spec(String name) {
        return ROWS.stream().map(VariantRows.Row::spec).filter(spec -> spec.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no row named " + name));
    }

    /**
     * One predeclared readiness comparison. Its estimate is candidate − baseline, so a negative difference
     * means the candidate is faster.
     *
     * @param candidate the variant being judged
     * @param baseline  the variant it is judged against
     * @param label     what the comparison answers, for the report
     */
    record ComparisonSpec(String candidate, String baseline, String label) {
    }

    /**
     * The predeclared readiness comparisons, in report order, headline first. A row that a later change adds
     * to the matrix appends its own specs here; a spec is skipped in a run whose results lack either variant.
     *
     * @return the ordered comparison specs
     */
    static List<ComparisonSpec> comparisons() {
        return List.of(
                new ComparisonSpec("runner-stored", "shadow", "Runner default vs Shadow"),
                new ComparisonSpec("runner-preserve", "shadow", "Runner PRESERVE vs Shadow"),
                new ComparisonSpec("runner-stored-aot", "shadow-aot", "Runner + AOT cache vs Shadow + AOT cache"),
                new ComparisonSpec("runner-extracted-aot", "shadow-aot",
                        "Extracted Runner + AOT cache vs Shadow + AOT cache"),
                new ComparisonSpec("runner-extracted-aot", "runner-stored-aot",
                        "Extracted layout + AOT vs single JAR + AOT"),
                new ComparisonSpec("runner-stored", "runner-preserve", "STORED vs PRESERVE"),
                new ComparisonSpec("runner-stored", "runner-stored-reflection", "Entry stub vs reflection"),
                new ComparisonSpec("runner-stored", "shadow-stored",
                        "Runner default vs Shadow STORED (compression-matched)"),
                new ComparisonSpec("shadow-stored", "shadow",
                        "Shadow-only control: Shadow STORED vs Shadow default (compression only)"),
                new ComparisonSpec("runner-stored-positional", "runner-stored", "Archive reads: POSITIONAL vs MAPPED"),
                new ComparisonSpec("runner-stored-positional-aot", "runner-stored-aot",
                        "Archive reads + AOT cache: POSITIONAL vs MAPPED"),
                new ComparisonSpec("runner-stored-positional", "shadow", "Runner POSITIONAL vs Shadow"),
                new ComparisonSpec("runner-stored-positional-aot", "shadow-aot",
                        "Runner POSITIONAL + AOT cache vs Shadow + AOT cache"),
                new ComparisonSpec("runner-stored", "runner-stored-joran",
                        "Precompiled Logback vs Joran at startup (Runner-only control)"),
                new ComparisonSpec("runner-stored-aot", "runner-stored-joran-aot",
                        "Precompiled Logback vs Joran at startup (Runner-only control) + AOT cache"),
                new ComparisonSpec("runner-stored-stripdebug", "runner-stored",
                        "Local-variable tables stripped vs kept, the default"),
                new ComparisonSpec("runner-stored-stripdebug-aot", "runner-stored-aot",
                        "Local-variable tables stripped vs kept, the default, with the AOT cache"),
                new ComparisonSpec("runner-stored", "runner-stored-dynamic-services",
                        "Static service table vs Micronaut's scan"),
                new ComparisonSpec("runner-stored-aot", "runner-stored-dynamic-services-aot",
                        "Static service table + AOT cache vs Micronaut's scan + AOT cache"),
                new ComparisonSpec("runner-stored-preload", "runner-stored", "Startup class preload vs none"),
                new ComparisonSpec("runner-stored-preload", "shadow", "Runner + startup class preload vs Shadow"),
                new ComparisonSpec("runner-stored-preload-aot", "runner-stored-aot",
                        "Startup class preload + AOT cache vs AOT cache alone"),
                // Micronaut AOT: the like-for-like pair first, then what a project keeps without Runner's
                // optimized archive, then what Micronaut AOT adds on each side.
                new ComparisonSpec("runner-maot", "shadow-maot", "Runner vs Micronaut AOT Shadow"),
                new ComparisonSpec("runner-stored", "shadow-maot",
                        "Runner without Micronaut AOT vs Micronaut AOT Shadow"),

                new ComparisonSpec("runner-maot", "runner-stored", "Micronaut AOT's gain on Runner"),
                new ComparisonSpec("shadow-maot", "shadow", "Micronaut AOT's gain on Shadow"),
                new ComparisonSpec("runner-maot-aot", "shadow-maot-aot",
                        "Runner vs Micronaut AOT Shadow, both with a JDK AOT cache"),
                new ComparisonSpec("runner-stored", "runner-stored-lambdas", "Lambdas desugared vs kept"),
                new ComparisonSpec("runner-stored-aot", "runner-stored-lambdas-aot",
                        "Lambdas desugared vs kept, with the AOT cache"),
                new ComparisonSpec("runner-extracted-aot", "runner-extracted-lambdas-aot",
                        "Extracted layout + AOT cache: lambdas desugared vs kept"),
                new ComparisonSpec("runner-stored-ordered", "runner-stored",
                        "Startup classes first in each nested jar vs the jar's own order"),
                new ComparisonSpec("runner-stored-hybrid", "runner-stored", "HYBRID vs STORED"),
                new ComparisonSpec("runner-stored-hybrid", "runner-preserve", "HYBRID vs PRESERVE"),
                new ComparisonSpec("runner-stored-hybrid", "shadow", "Runner HYBRID vs Shadow"));
    }

    /**
     * Keeps the selected rows visible when the shared sample build fails.
     *
     * @param reason    why nothing could be built
     * @param selection the selected rows
     * @return one unavailable variant per selected row, in report order
     */
    static List<Variant> unavailableVariants(String reason, List<String> selection) {
        return ROWS.stream().map(VariantRows.Row::spec).filter(spec -> selection.contains(spec.name()))
                .map(spec -> Variant.unavailable(spec, reason)).toList();
    }

    /**
     * Builds the selected rows, each at most once, together with the rows they derive from.
     *
     * @param harness   trains and verifies the JDK AOT caches, with the settings of the timed runs
     * @param selection the selected rows
     * @return the selected variants only, in report order, available and unavailable alike; a prerequisite
     *         built only for another row is not among them
     */
    List<Variant> variants(StartupHarness harness, List<String> selection) {
        this.harness = harness;
        return variants(this, selection, log);
    }

    /**
     * Builds the selected rows of the row table with the given steps; see {@link #variants(StartupHarness, List)}.
     *
     * @param steps     what the rows are built with
     * @param selection the selected rows
     * @param log       where progress goes
     * @return the selected variants only, in report order
     */
    static List<Variant> variants(SampleSteps steps, List<String> selection, PrintStream log) {
        return VariantRows.build(ROWS, steps, selection, (spec, create) -> attempt(spec, create, log));
    }

    /**
     * The AOT-cache request of a run. Under a CPU limit the limit enters the cache identity, so a cache is never
     * reused under different VM ergonomics; the harness runs training and verification under the limit's command
     * prefix, as it runs every launch.
     *
     * @param artifacts the work directory
     * @param mainClass the application class verification requires to come from the cache
     * @param cpuLimit  the CPU limit, or {@code null} for none
     * @param log       where progress goes
     * @return the request
     */
    static AotCache.Request aotRequest(Path artifacts, String mainClass, CpuLimit cpuLimit, PrintStream log) {
        return new AotCache.Request(artifacts.resolve("managed-aot"), List.of(HELLO), mainClass,
                cpuLimit == null ? List.of() : cpuLimit.relevantJvmFlags(), log);
    }

    private static Variant attempt(VariantSpec spec, VariantRows.Creation create, PrintStream log) {
        try {
            Variant variant = create.create();
            log.println("[startup-benchmark] prepared " + spec.name());
            return variant;
        } catch (Exception e) {
            String reason = oneLine(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.println("[startup-benchmark] " + spec.name() + " is unavailable: " + reason);
            return Variant.unavailable(spec, reason);
        }
    }

    @Override
    public Variant shadow(VariantSpec spec) throws IOException {
        return shadowJar(spec, "shadowJar", shadowJar);
    }

    @Override
    public Variant shadowStored(VariantSpec spec) throws IOException {
        return shadowJar(spec, "shadowJarStored", shadowStoredJar);
    }

    @Override
    public Variant shadowMaot(VariantSpec spec) throws IOException {
        return shadowJar(spec, "optimizedJitJarAll", optimizedJitJarAll);
    }

    @Override
    public Variant runnerMaot(VariantSpec spec) throws IOException {
        return optimizedRunnerJar(artifacts, spec, mainClass, optimizedJitJar, dependencies, log);
    }

    /**
     * The Runner jar of the Micronaut AOT-optimized application: built exactly as {@code runner-stored} is, with
     * the same ordered dependencies and the packaging library's defaults, but with a copy of the sample's
     * {@code optimizedJitJar} archive as the application output. The application layer is then the only
     * difference between the two rows, and whatever Runner's packaging defaults are apply to both.
     *
     * @param artifacts       where the variants' artifacts are written
     * @param spec            the row
     * @param mainClass       the application's main class
     * @param optimizedJitJar the archive the metadata names, or {@code null} when the sample has no such task
     * @param dependencies    the dependency jars, in class path order
     * @param log             where the packaging library's lines go, under the row's name
     * @return the variant
     * @throws IOException if the sample declares no such task, it produced no jar, or packaging fails
     */
    static Variant optimizedRunnerJar(Path artifacts, VariantSpec spec, String mainClass, Path optimizedJitJar,
                                      List<Path> dependencies, PrintStream log) throws IOException {
        if (optimizedJitJar == null) {
            throw new IOException("The sample's build declares no optimizedJitJar task (it does not apply"
                    + " io.micronaut.aot)");
        }
        if (!Files.isRegularFile(optimizedJitJar)) {
            throw new IOException("Micronaut AOT produced no " + optimizedJitJar);
        }
        // Copied like every sample output a row uses, so that nothing measured is read from the sample's build.
        Path directory = recreate(artifacts.resolve(spec.name() + "-application"));
        Path copy = LaunchInputs.copy(optimizedJitJar, directory.resolve(optimizedJitJar.getFileName().toString()));
        return runnerJar(artifacts, spec, mainClass, List.of(copy), dependencies, Compression.STORED,
                RunnerJarOptions.DEFAULTS, log);
    }

    @Override
    public Variant aotCache(Variant source, VariantSpec spec) throws IOException, InterruptedException {
        return AotCache.prepare(harness, source, spec, aotRequest(artifacts, mainClass, cpuLimit, log));
    }

    @Override
    public Variant explodedClasspath(VariantSpec spec) throws IOException {
        Path directory = recreate(artifacts.resolve("exploded"));
        List<Path> application = new ArrayList<>(applicationOutput.size());
        List<String> classPath = new ArrayList<>(applicationOutput.size() + dependencies.size());
        for (int i = 0; i < applicationOutput.size(); i++) {
            Path target = directory.resolve("app-" + i);
            copyDirectory(applicationOutput.get(i), target);
            application.add(target);
            classPath.add(target.toAbsolutePath().toString());
        }
        List<Path> dependencyCopies = copyDependenciesTo(directory.resolve("lib"));
        for (Path dependency : dependencyCopies) {
            classPath.add(dependency.toAbsolutePath().toString());
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-cp");
        command.add(String.join(java.io.File.pathSeparator, classPath));
        command.add(mainClass);
        DeploymentSize deploymentSize = DeploymentSize.measure(
                DeploymentSize.input("application", application),
                DeploymentSize.input("dependencies", dependencyCopies));
        List<Path> launchInputs = new ArrayList<>(application);
        launchInputs.addAll(dependencyCopies);
        return Variant.available(spec, command, directory, directory, deploymentSize, EntryMode.STANDARD_LOADER,
                null, launchInputs);
    }

    @Override
    public Variant thinJar(VariantSpec spec) throws IOException {
        Path directory = recreate(artifacts.resolve("thin"));
        List<String> classPath = new ArrayList<>(dependencies.size());
        List<Path> dependencyCopies = copyDependenciesTo(directory.resolve("lib"));
        for (Path copy : dependencyCopies) {
            classPath.add("lib/" + encodeClassPathEntry(copy.getFileName().toString()));
        }

        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(Attributes.Name.MAIN_CLASS, mainClass);
        main.put(Attributes.Name.CLASS_PATH, String.join(" ", classPath));

        Path jar = directory.resolve(projectName + "-thin.jar");
        Set<String> written = new LinkedHashSet<>();
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            written.add("META-INF/MANIFEST.MF");
            for (Path root : applicationOutput) {
                copyTree(root, jarOut, written);
            }
        }
        LaunchInputs.pin(jar);

        List<String> command = List.of(javaExecutable().toString(), "-jar", jar.toAbsolutePath().toString());
        DeploymentSize deploymentSize = DeploymentSize.measure(
                DeploymentSize.input("application", jar),
                DeploymentSize.input("dependencies", dependencyCopies));
        List<Path> launchInputs = new ArrayList<>();
        launchInputs.add(jar);
        launchInputs.addAll(dependencyCopies);
        return Variant.available(spec, command, directory, jar, deploymentSize, EntryMode.STANDARD_LOADER, null,
                launchInputs);
    }

    /**
     * Copies a jar that one of the sample's Shadow tasks built into {@code <artifacts>/<name>/}.
     *
     * @param spec the row, whose name also names the directory
     * @param task the sample task that should have built the jar
     * @param jar  the jar the metadata names, or {@code null} when the sample has no such task
     * @return the variant
     * @throws IOException if the sample declares no such task, it produced no jar, or the copy fails
     */
    private Variant shadowJar(VariantSpec spec, String task, Path jar) throws IOException {
        if (jar == null) {
            throw new IOException("The sample's build declares no " + task + " task");
        }
        if (!Files.isRegularFile(jar)) {
            throw new IOException("The Shadow plugin produced no " + jar);
        }
        Path directory = recreate(artifacts.resolve(spec.name()));
        Path copy = LaunchInputs.copy(jar, directory.resolve(jar.getFileName().toString()));
        List<String> command = List.of(javaExecutable().toString(), "-jar",
                copy.toAbsolutePath().toString());
        DeploymentSize deploymentSize = DeploymentSize.measure(DeploymentSize.input("archive", copy));
        return Variant.available(spec, command, directory, copy, deploymentSize, EntryMode.STANDARD_LOADER, null,
                List.of(copy));
    }

    /**
     * Records the startup profile from the list-free {@code runner-stored} jar, once per run.
     *
     * <p>The recording is the one the build plugins make: {@link StartupProfileRecorder} launches the archive
     * once without a cache through the {@code TrainingDriver}, waits for {@code /hello}, sends it once and reads
     * the class-load log before the stop. So the rows that package it measure the profile a project would
     * commit. It is taken afresh in every run, because the profile is a measurement input and not a cache, and
     * only once: {@code runner-stored-preload}, {@code runner-stored-ordered} and {@code runner-stored-hybrid} all
     * package the same recording. Classes that ForkJoin workers load can land in a slightly different order each
     * time, so the jar, and with it the identity of its AOT cache, may differ between runs; that costs only a
     * retraining.</p>
     *
     * <p>Under a CPU limit the recording launch does not run under the limit's command prefix, because the driver
     * launches {@code java} itself: it runs on every CPU the harness may use, which pins itself only once the
     * variants are prepared. The list hardly depends on the number of CPUs, since the recording pins the common
     * pool to parallelism 0, and the rows are timed under the limit like every other.</p>
     *
     * @param stored the list-free STORED runner jar
     * @return the profile
     * @throws IOException          if there is no jar or the recording launch fails
     * @throws InterruptedException if the recording launch is interrupted
     */
    private Path recordStartupProfile(Variant stored) throws IOException, InterruptedException {
        if (!stored.available()) {
            throw new IOException("there is no runner jar to record the startup classes from: "
                    + stored.unavailableReason());
        }
        Path profile = artifacts.resolve(STARTUP_PROFILE);
        Files.deleteIfExists(profile);
        TrainingSettings settings = TrainingSettings.builder()
                .readinessPath(HELLO)
                .workloadPaths(List.of(HELLO))
                .readinessTimeout(harness.startupTimeout())
                .build();
        try {
            // Recreated: the recorder refuses a directory with content no recording of its own left there.
            StartupProfileRecorder.record(javaExecutable(), stored.artifact(), settings,
                    recreate(artifacts.resolve(STARTUP_PROFILE_WORK)), profile, STARTUP_PROFILE_RERECORD,
                    new HarnessLogger(log));
        } catch (IOException e) {
            throw new IOException("recording the startup classes failed: " + e.getMessage(), e);
        }
        return profile;
    }

    /**
     * Packages the same inputs as {@code runner-stored} again with this run's startup profile, which the launcher
     * preloads.
     *
     * @param stored the list-free STORED runner jar, which the recording launch runs
     * @param spec   the preloading row
     * @return the preloading variant
     * @throws IOException          if the recording launch fails, or the jar embeds no startup class
     * @throws InterruptedException if the recording launch is interrupted
     */
    @Override
    public Variant preloadingRunnerJar(Variant stored, VariantSpec spec) throws IOException, InterruptedException {
        return runnerJar(spec, Compression.STORED,
                RunnerJarOptions.DEFAULTS.withStartupClasses(startupProfile.get(stored)));
    }

    /**
     * The preload row's jar, with this run's startup profile, launched with {@code -Dmicronaut.runner.preload=false}:
     * it differs from {@code runner-stored} only in the order of each nested jar's entries.
     *
     * @param stored the list-free STORED runner jar, which the recording launch runs
     * @param spec   the ordered row
     * @return the ordered variant
     * @throws IOException          if the recording launch fails, or the jar embeds no startup class
     * @throws InterruptedException if the recording launch is interrupted
     */
    @Override
    public Variant orderedRunnerJar(Variant stored, VariantSpec spec) throws IOException, InterruptedException {
        return runnerJar(spec, Compression.STORED, RunnerJarOptions.DEFAULTS
                .withStartupClasses(startupProfile.get(stored)).withPreload(false));
    }

    /**
     * The ordered row's inputs packaged with {@link Compression#HYBRID} and launched without the preloader: it
     * differs from {@code runner-stored-ordered} only in how the cold classes are stored. It is unavailable when the
     * jar holds no deflated nested entry, which means the build fell back to STORED.
     *
     * @param stored the list-free STORED runner jar, which the recording launch runs
     * @param spec   the hybrid row
     * @return the hybrid variant
     * @throws IOException          if the recording launch fails, the jar embeds no startup class, or it compressed
     *                              nothing
     * @throws InterruptedException if the recording launch is interrupted
     */
    @Override
    public Variant hybridRunnerJar(Variant stored, VariantSpec spec) throws IOException, InterruptedException {
        return runnerJar(spec, Compression.HYBRID, RunnerJarOptions.DEFAULTS
                .withStartupClasses(startupProfile.get(stored)).withPreload(false));
    }

    @Override
    public Variant runnerJar(VariantSpec spec, Compression compression, RunnerJarOptions options)
            throws IOException {
        return runnerJar(artifacts, spec, mainClass, applicationOutput, dependencies, compression, options, log);
    }

    /**
     * The Joran control row: the {@code runner-stored} inputs with {@code precompileLogback=false}. It is only a
     * control while {@code runner-stored} really carries the generated configurator and this archive does not.
     *
     * @param stored the {@code runner-stored} row
     * @param spec   the control row
     * @return the control row
     * @throws IOException if either archive is not what the comparison needs
     */
    @Override
    public Variant joranControl(Variant stored, VariantSpec spec) throws IOException {
        if (!stored.available() || !logbackPrecompiled(stored.artifact())) {
            throw new IOException("runner-stored carries no " + GENERATED_LOGBACK_CONFIGURATOR
                    + ", so there is no precompiled Logback configuration to compare Joran with");
        }
        Variant joran = runnerJar(spec, Compression.STORED, RunnerJarOptions.DEFAULTS.withPrecompileLogback(false));
        if (logbackPrecompiled(joran.artifact())) {
            throw new IOException(joran.artifact() + " carries " + GENERATED_LOGBACK_CONFIGURATOR
                    + " although precompileLogback=false");
        }
        return joran;
    }

    /**
     * Whether a runner jar carries the Logback configurator runner-build generates.
     *
     * @param archive the runner jar
     * @return whether its index knows the generated configurator
     * @throws IOException if the archive cannot be read
     */
    static boolean logbackPrecompiled(Path archive) throws IOException {
        try (RunnerJarIndex reader = RunnerJarIndex.open(archive)) {
            return reader.index().findClass(GENERATED_LOGBACK_CONFIGURATOR) != IndexFormat.NO_INDEX;
        }
    }

    /**
     * Builds a runner jar straight from the packaging library, as the plugins would, entered as the row asks.
     * The jar is checked against what the row claims: its entry mode, archive read mode, preload list and service
     * table. Its build note says whether it carries a static service table and how many startup classes it
     * preloads, so that a jar whose table silently stood down is not measured under the name of a row that has
     * one. Every line the packaging library logs goes to the harness log under the row's name, so a run's log
     * shows which build steps ran, stood down or were off for each row.
     *
     * @param spec    the row, which names the jar and its entry mode
     * @param options the packaging options a row sets on top of the builder defaults
     * @param log     where the packaging library's info and warning lines go
     */
    static Variant runnerJar(Path artifacts,
                             VariantSpec spec,
                             String mainClass,
                             List<Path> applicationOutput,
                             List<Path> dependencies,
                             Compression compression,
                             RunnerJarOptions options,
                             PrintStream log) throws IOException {
        Path output = artifacts.resolve(spec.name() + ".jar");
        Files.deleteIfExists(output);
        RunnerJarSpec.Builder builder = RunnerJarSpec.builder()
                .mainClass(mainClass)
                .applicationOutput(applicationOutput)
                .dependencies(dependencies.stream().map(Dependency::of).toList())
                .output(output)
                .compression(compression)
                .entryStub(spec.entryMode() == EntryMode.STUB);
        // The PASSTHROUGH options are set by name, as a build sets them through a plugin's generic options.
        if (options.archiveReads() != null) {
            builder.option("archiveReads", options.archiveReads());
        }
        if (options.precompileLogback() != null) {
            builder.option("precompileLogback", options.precompileLogback().toString());
        }
        if (options.stripLocalVariables() != null) {
            builder.option("stripLocalVariables", options.stripLocalVariables().toString());
        }
        builder.startupClasses(options.startupClasses());
        if (options.staticServices() != null) {
            builder.option("staticServices", options.staticServices().toString());
        }
        if (options.desugarLambdas() != null) {
            builder.option("desugarLambdas", options.desugarLambdas().toString());
        }
        RunnerJarSpec jarSpec = builder.build();
        RunnerJarResult result = RunnerJarBuilder.build(jarSpec, new HarnessLogger(log, spec.name()));
        if (Boolean.FALSE.equals(options.staticServices()) && result.staticServiceSlots() != 0) {
            throw new IOException("staticServices false was requested, but " + output + " carries a table of "
                    + result.staticServiceSlots() + " slots");
        }
        // Rebuilt in every run with the same bytes; the pin keeps the time a trained cache recorded.
        LaunchInputs.pin(output);
        EntryMode effectiveEntryMode = inspectEntryMode(output, spec.entryMode());
        inspectArchiveReads(output, jarSpec.effectiveOptions().get("archiveReads"));
        int preloaded = inspectPreload(output, options.startupClasses());
        // Counted only for HYBRID: reading every nested jar as a stream inflates each deflated entry it skips.
        int[] methods = compression == Compression.HYBRID ? nestedMethods(output) : null;
        if (methods != null && methods[1] == 0) {
            throw new IOException("HYBRID was requested, but " + output + " holds no deflated nested entry: the"
                    + " build fell back to STORED");
        }
        List<String> command = new ArrayList<>(List.of(javaExecutable().toString()));
        boolean preloadOff = Boolean.FALSE.equals(options.preload());
        if (preloadOff) {
            command.add("-D" + PRELOAD_PROPERTY + "=false");
        }
        command.add("-jar");
        command.add(output.toAbsolutePath().toString());
        DeploymentSize deploymentSize = DeploymentSize.measure(DeploymentSize.input("archive", output));
        String buildNote = (result.staticServiceSlots() == 0 ? "dynamic service scan"
                : "static services: " + result.staticServiceSlots() + " slots (core "
                        + result.staticServicesCoreVersion().orElse("unknown") + ")")
                + (options.startupClasses() == null ? ""
                        : preloadOff ? "; " + preloaded + " recorded startup classes first, not preloaded"
                        : "; " + preloaded + " recorded startup classes preloaded")
                + (methods == null ? "" : "; nested entries: " + methods[0] + " stored, " + methods[1] + " deflated");
        return Variant.available(spec, command, artifacts, output, deploymentSize, effectiveEntryMode, buildNote,
                List.of(output));
    }

    /**
     * Counts the file entries of a runner jar's nested jars by compression method, as {@code inspect} does, by
     * reading each nested jar under {@link IndexFormat#LIB_PREFIX} as a ZIP stream.
     *
     * @return the stored count, then the deflated count
     */
    static int[] nestedMethods(Path archive) throws IOException {
        int[] counts = new int[2];
        try (java.util.zip.ZipFile outer = new java.util.zip.ZipFile(archive.toFile())) {
            for (ZipEntry nested : outer.stream().filter(entry -> !entry.isDirectory()
                    && entry.getName().startsWith(IndexFormat.LIB_PREFIX)).toList()) {
                try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(outer.getInputStream(nested))) {
                    for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                        if (!entry.isDirectory()) {
                            counts[entry.getMethod() == ZipEntry.STORED ? 0 : 1]++;
                        }
                    }
                }
            }
        }
        return counts;
    }

    /**
     * Fails a row whose jar does not carry the archive read mode it asked for, so that a row meant to measure
     * positional reads is reported unavailable rather than measuring the mapped launch under its name.
     */
    private static void inspectArchiveReads(Path output, String requested) throws IOException {
        try (RunnerJarIndex reader = RunnerJarIndex.open(output)) {
            boolean positional = reader.index().positionalReads();
            if (positional != "POSITIONAL".equals(requested)) {
                throw new IOException("archiveReads " + requested + " was requested, but the index of " + output
                        + (positional ? " asks for positional reads" : " does not ask for positional reads"));
            }
        }
    }

    /**
     * Fails a row that asked for a startup class list whose jar embeds none, so that a row meant to measure
     * preloading is reported unavailable rather than measuring a plain launch under its name.
     *
     * @return the number of startup classes the index carries, {@code 0} when no list was requested
     */
    private static int inspectPreload(Path output, Path startupClasses) throws IOException {
        if (startupClasses == null) {
            return 0;
        }
        try (RunnerJarIndex reader = RunnerJarIndex.open(output)) {
            int count = reader.index().preloadCount();
            if (count == 0) {
                throw new IOException("a startup class list was requested, but the index of " + output
                        + " embeds no class from " + startupClasses);
            }
            return count;
        }
    }

    /**
     * The packaging options a row sets on top of the packaging library's defaults. A {@code null} field leaves
     * the builder default in place, so a row that does not set an option measures whatever default the option
     * table declares. A row that needs another option adds a field here rather than another overload.
     *
     * @param archiveReads        how the launcher reads the archive, as the option's text such as
     *                            {@code POSITIONAL}, or {@code null} for the builder default
     * @param precompileLogback   whether to precompile {@code logback.xml}, or {@code null} for the builder
     *                            default
     * @param stripLocalVariables whether dependency classes lose their local-variable tables, or {@code null}
     *                            for the builder default
     * @param startupClasses      the recorded startup class list to embed, or {@code null} for none
     * @param staticServices      whether to generate the static service table, or {@code null} for the builder
     *                            default
     * @param desugarLambdas      whether lambda call sites are replaced with generated classes, or {@code null}
     *                            for the builder default
     * @param preload             whether the launcher preloads the embedded startup classes, or {@code null} for
     *                            its default; {@code false} launches with {@code -Dmicronaut.runner.preload=false}
     */
    record RunnerJarOptions(String archiveReads, Boolean precompileLogback, Boolean stripLocalVariables,
                            Path startupClasses, Boolean staticServices, Boolean desugarLambdas,
                            Boolean preload) {

        /** Every option at the builder default. */
        static final RunnerJarOptions DEFAULTS = new RunnerJarOptions(null, null, null, null, null, null, null);

        /**
         * These options with another archive read mode.
         *
         * @param value the archive read mode, as the option's text such as {@code POSITIONAL}
         * @return the new options
         */
        RunnerJarOptions withArchiveReads(String value) {
            return new RunnerJarOptions(value, precompileLogback, stripLocalVariables, startupClasses,
                    staticServices, desugarLambdas, preload);
        }

        /**
         * These options with {@code logback.xml} precompiled or left to Joran.
         *
         * @param value whether to precompile {@code logback.xml}
         * @return the new options
         */
        RunnerJarOptions withPrecompileLogback(boolean value) {
            return new RunnerJarOptions(archiveReads, value, stripLocalVariables, startupClasses,
                    staticServices, desugarLambdas, preload);
        }

        /**
         * These options with local-variable stripping set.
         *
         * @param value whether dependency classes lose their local-variable tables
         * @return the new options
         */
        RunnerJarOptions withStripLocalVariables(boolean value) {
            return new RunnerJarOptions(archiveReads, precompileLogback, value, startupClasses,
                    staticServices, desugarLambdas, preload);
        }

        /**
         * These options with a startup class list.
         *
         * @param value the recorded class-load log or list of binary names, or {@code null} for none
         * @return the new options
         */
        RunnerJarOptions withStartupClasses(Path value) {
            return new RunnerJarOptions(archiveReads, precompileLogback, stripLocalVariables, value,
                    staticServices, desugarLambdas, preload);
        }

        /**
         * These options with the static service table requested or not.
         *
         * @param value whether to generate the table
         * @return the new options
         */
        RunnerJarOptions withStaticServices(boolean value) {
            return new RunnerJarOptions(archiveReads, precompileLogback, stripLocalVariables, startupClasses,
                    value, desugarLambdas, preload);
        }

        /**
         * These options with lambda desugaring set.
         *
         * @param value whether lambda call sites are replaced with generated classes
         * @return the new options
         */
        RunnerJarOptions withDesugarLambdas(boolean value) {
            return new RunnerJarOptions(archiveReads, precompileLogback, stripLocalVariables, startupClasses,
                    staticServices, value, preload);
        }

        /**
         * These options with the launcher's preloader on or off. It is a launch option, not a packaging one: off,
         * the row launches with {@code -Dmicronaut.runner.preload=false}, so a jar that embeds a startup class list
         * is measured for its entry order alone.
         *
         * @param value whether the launcher preloads the embedded startup classes
         * @return the new options
         */
        RunnerJarOptions withPreload(boolean value) {
            return new RunnerJarOptions(archiveReads, precompileLogback, stripLocalVariables, startupClasses,
                    staticServices, desugarLambdas, value);
        }
    }

    private static EntryMode inspectEntryMode(Path output, EntryMode requestedEntryMode) throws IOException {
        try (RunnerJarIndex reader = RunnerJarIndex.open(output)) {
            String indexedStub = reader.index().entryStubClass();
            boolean generatedClassPresent = reader.index().findClass(GENERATED_ENTRY_STUB)
                    != IndexFormat.NO_INDEX;
            if (requestedEntryMode == EntryMode.STUB) {
                if (!GENERATED_ENTRY_STUB.equals(indexedStub) || !generatedClassPresent) {
                    throw new IOException("entry stub was requested, but " + output
                            + " does not both contain and index the generated class " + GENERATED_ENTRY_STUB);
                }
                return EntryMode.STUB;
            }
            if (indexedStub != null || generatedClassPresent) {
                throw new IOException("reflection was requested, but " + output + " contains or indexes "
                        + GENERATED_ENTRY_STUB);
            }
            return EntryMode.REFLECTION;
        }
    }

    @Override
    public Variant extracted(Variant stored, VariantSpec spec) throws IOException, InterruptedException {
        return extractedRunner(artifacts, stored, spec);
    }

    /**
     * Unpacks a runner jar into {@code <artifacts>/<name>} and describes that layout as a variant, which carries
     * the build note of the jar it was extracted from.
     *
     * @param artifacts where the variants' artifacts are written
     * @param stored    the runner jar
     * @param spec      the layout's row
     * @return the variant
     * @throws IOException          if there is no jar or the extraction fails
     * @throws InterruptedException if the extraction is interrupted
     */
    static Variant extractedRunner(Path artifacts, Variant stored, VariantSpec spec)
            throws IOException, InterruptedException {
        if (!stored.available()) {
            throw new IOException("there is no runner jar to extract: " + stored.unavailableReason());
        }
        Path destination = artifacts.resolve(spec.name());
        deleteRecursively(destination);
        // The plugins' layout, checks included: Class-Path in index order and the fixed modification times.
        AotLayout.Result layout = AotLayout.write(javaExecutable(), stored.artifact(), destination,
                java.time.Duration.ofSeconds(EXTRACT_TIMEOUT_SECONDS));
        Path applicationJar = layout.applicationJar();
        List<String> run = List.of(javaExecutable().toString(), "-jar",
                applicationJar.toAbsolutePath().toString());
        DeploymentSize deploymentSize = DeploymentSize.measure(
                DeploymentSize.input("extracted-layout", destination));
        List<Path> launchInputs = new ArrayList<>();
        launchInputs.add(applicationJar);
        launchInputs.addAll(layout.libraries());
        // Extraction already writes this instant; pinning states it rather than relying on it.
        LaunchInputs.pin(launchInputs);
        return Variant.available(spec, run, destination, destination, deploymentSize, EntryMode.STANDARD_LOADER,
                stored.buildNote(), launchInputs);
    }

    /**
     * Copies the dependency jars into one directory, keeping class path order and de-duplicating names.
     *
     * @param lib the directory to fill, created if it is not there
     * @return the copies, in class path order
     * @throws IOException if a jar cannot be copied
     */
    private List<Path> copyDependenciesTo(Path lib) throws IOException {
        Files.createDirectories(lib);
        List<Path> copies = new ArrayList<>(dependencies.size());
        Set<String> used = new LinkedHashSet<>();
        for (int i = 0; i < dependencies.size(); i++) {
            Path dependency = dependencies.get(i);
            String fileName = dependency.getFileName().toString();
            if (!used.add(fileName)) {
                // Two jars with the same file name from different groups. Both have to survive, and the
                // one that arrived second keeps its position on the class path.
                fileName = i + "-" + fileName;
                used.add(fileName);
            }
            copies.add(LaunchInputs.copy(dependency, lib.resolve(fileName)));
        }
        return copies;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(source)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        for (Path file : files) {
            Path destination = target.resolve(source.relativize(file).toString());
            Files.createDirectories(destination.getParent());
            LaunchInputs.copy(file, destination);
        }
    }

    private static void copyTree(Path root, JarOutputStream out, Set<String> written) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        if (Files.isRegularFile(root)) {
            throw new IOException(root + " is a jar, not a directory; the harness packages exploded"
                    + " application output only");
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        for (Path file : files) {
            String name = root.relativize(file).toString().replace('\\', '/');
            if (name.equals("META-INF/MANIFEST.MF")) {
                // The thin jar's manifest is the one this class wrote; the application's own would
                // overwrite Main-Class and Class-Path.
                continue;
            }
            // Directory entries matter: Micronaut lists META-INF/micronaut/ with getResources, and a jar
            // without directory entries answers that listing with nothing at all.
            int slash = 0;
            while ((slash = name.indexOf('/', slash + 1)) > 0) {
                String directory = name.substring(0, slash + 1);
                if (written.add(directory)) {
                    out.putNextEntry(new ZipEntry(directory));
                    out.closeEntry();
                }
            }
            if (!written.add(name)) {
                continue;
            }
            out.putNextEntry(new ZipEntry(name));
            Files.copy(file, out);
            out.closeEntry();
        }
    }

    private static String encodeClassPathEntry(String fileName) {
        byte[] bytes = fileName.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte value : bytes) {
            int c = value & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(hex[c >>> 4]).append(hex[c & 0x0f]);
            }
        }
        return encoded.toString();
    }

    /**
     * The {@code java} of the JDK running this harness, which is the JDK every variant is started with.
     *
     * @return the executable
     */
    static Path javaExecutable() {
        Path home = Path.of(System.getProperty("java.home"));
        Path candidate = home.resolve("bin").resolve("java");
        if (!Files.isExecutable(candidate)) {
            candidate = home.resolve("bin").resolve("java.exe");
        }
        return candidate;
    }

    /**
     * Runs the repository's own Gradle wrapper on the harness JDK and waits for it.
     *
     * @param projectDirectory the build to run, which is also the working directory; the wrapper is the
     *                         nearest {@code gradlew} above it
     * @param arguments        everything after {@code gradlew}
     * @param timeout          how long the build may take; the client is killed after that
     * @return the exit status and everything the build printed
     * @throws IOException          if the build cannot start or does not finish in time
     * @throws InterruptedException if the wait is interrupted
     */
    private static GradleResult gradle(Path projectDirectory, List<String> arguments, java.time.Duration timeout)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(findGradlew(projectDirectory).toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true);
        // The nested build must run on the same JDK as this harness, which is the JDK the packaged
        // applications will be started with.
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread drain = drain(process, output);
        if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("gradlew " + String.join(" ", arguments) + " did not finish within "
                    + timeout + tail(output));
        }
        drain.join(5_000);
        synchronized (output) {
            return new GradleResult(process.exitValue(), output.toString());
        }
    }

    /**
     * How one nested Gradle build ended.
     *
     * @param exitCode the wrapper's exit status
     * @param output   its standard output and error, interleaved
     */
    private record GradleResult(int exitCode, String output) {

        /**
         * The last lines of the output, for an exception message.
         *
         * @return the tail, with a header line
         */
        String tail() {
            return SampleBuild.tail(new StringBuilder(output));
        }
    }

    private static Path findGradlew(Path sample) throws IOException {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "gradlew.bat" : "gradlew";
        Path directory = sample.toAbsolutePath().normalize();
        while (directory != null) {
            Path candidate = directory.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IOException("No " + name + " above " + sample + "; the harness drives the sample's"
                + " build with the repository's own wrapper");
    }

    private static Thread drain(Process process, StringBuilder into) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream in = process.getInputStream()) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    synchronized (into) {
                        into.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException e) {
                synchronized (into) {
                    into.append("\n[output capture stopped: ").append(e).append(']');
                }
            }
        }, "sample-build-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String tail(StringBuilder output) {
        String text;
        synchronized (output) {
            text = output.toString();
        }
        String[] lines = text.split("\n");
        int from = Math.max(0, lines.length - 40);
        StringBuilder result = new StringBuilder("\n--- last ").append(lines.length - from)
                .append(" lines ---\n");
        for (int i = from; i < lines.length; i++) {
            result.append(lines[i]).append('\n');
        }
        return result.toString();
    }

    private static String oneLine(String message) {
        return message == null ? "no message" : message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static Path recreate(Path directory) throws IOException {
        deleteRecursively(directory);
        return Files.createDirectories(directory);
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** What the init script's task wrote: the application's class path, in order. */
    private record Metadata(String projectName,
                            String projectVersion,
                            String mainClass,
                            List<Path> applicationOutput,
                            List<Path> dependencies,
                            Path shadowJar,
                            Path shadowStoredJar,
                            Path optimizedJitJar,
                            Path optimizedJitJarAll) {

        static Metadata read(Path file) throws IOException {
            String projectName = "application";
            String projectVersion = "";
            String mainClass = null;
            List<Path> applicationOutput = new ArrayList<>();
            List<Path> dependencies = new ArrayList<>();
            Path shadowJar = null;
            Path shadowStoredJar = null;
            Path optimizedJitJar = null;
            Path optimizedJitJarAll = null;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int separator = line.indexOf('=');
                if (separator < 0) {
                    continue;
                }
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                switch (key) {
                    case "projectName" -> projectName = value;
                    case "projectVersion" -> projectVersion = value;
                    case "mainClass" -> mainClass = value;
                    case "classes", "resources" -> applicationOutput.add(Path.of(value));
                    case "dependency" -> dependencies.add(Path.of(value));
                    case "shadowJar" -> shadowJar = Path.of(value);
                    case "shadowStoredJar" -> shadowStoredJar = Path.of(value);
                    case "optimizedJitJar" -> optimizedJitJar = Path.of(value);
                    case "optimizedJitJarAll" -> optimizedJitJarAll = Path.of(value);
                    default -> {
                    }
                }
            }
            if (mainClass == null) {
                throw new IOException(file + " names no main class");
            }
            List<Path> existing = applicationOutput.stream().filter(Files::exists).toList();
            if (existing.isEmpty()) {
                throw new IOException(file + " names no application output that exists");
            }
            if (dependencies.isEmpty()) {
                throw new IOException(file + " names no dependencies");
            }
            return new Metadata(projectName, projectVersion, mainClass, existing,
                    List.copyOf(dependencies), shadowJar, shadowStoredJar, optimizedJitJar, optimizedJitJarAll);
        }
    }

    /** Prints what the packaging library reports to the harness's log, under a row's name when it has one. */
    private static final class HarnessLogger implements BuildLogger {

        private final PrintStream log;
        private final String prefix;

        private HarnessLogger(PrintStream log) {
            this.log = log;
            this.prefix = "[startup-benchmark] ";
        }

        private HarnessLogger(PrintStream log, String row) {
            this.log = log;
            this.prefix = "[startup-benchmark] " + row + ": ";
        }

        @Override
        public void info(String message) {
            log.println(prefix + message);
        }

        @Override
        public void warn(String message) {
            log.println(prefix + "WARNING " + message);
        }
    }
}
