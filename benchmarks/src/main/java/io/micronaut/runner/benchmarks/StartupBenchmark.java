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

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The end-to-end startup benchmark: builds the sample application in every packaging the runner competes
 * with, starts each of them many times, and reports how long each takes to answer its first request.
 *
 * <pre>
 * java io.micronaut.runner.benchmarks.StartupBenchmark \
 *     --sample     &lt;dir&gt;   the sample application's project directory
 *     --repo       &lt;uri&gt;   the Maven repository holding the runner plugins under test
 *     --version    &lt;v&gt;     the version they were published under
 *     --iterations &lt;n&gt;     measured runs per variant
 *     --out        &lt;dir&gt;   where results.json and summary.md are written
 *   [ --work       &lt;dir&gt; ] where variants are built and caches trained (default: &lt;out&gt;/artifacts)
 *   [ --warmup     &lt;n&gt;  ]  discarded runs per variant (default: min(3, iterations))
 *   [ --seed       &lt;n&gt;  ]  seed of the interleaving shuffle and the bootstrap
 *   [ --readiness  &lt;p&gt;  ]  the HTTP path polled for readiness (default /hello)
 *   [ --timeout    &lt;s&gt;  ]  how long one start may take (default 120)
 *   [ --diagnostics     ]  additionally make one -Xlog:class+load run per variant
 *   [ --allow-partial   ]  exploratory mode: exit zero if any measured run succeeds
 *   [ --optional-rows   ]  also build and measure the opt-in rows, which are reported but never gate
 *   [ --variants   &lt;a,b&gt;]  build and measure only these rows (plus what they derive from, unreported); naming
 *                         an opt-in row builds it; not combinable with --optional-rows
 *   [ --cpus       &lt;n&gt;  ]  Linux: run every child JVM on the first n CPUs the harness may use (taskset) and
 *                         pin the harness to the rest
 *   [ --page-cache &lt;m&gt;  ]  uncontrolled (default), evict-artifacts (Linux) or drop-all (passwordless sudo)
 * </pre>
 *
 * <h2>The methodology, and why each rule is there</h2>
 * <ol>
 *   <li><strong>Identical application bytes across variants.</strong> One Gradle build of the sample
 *       produces the classes and the resolved dependency jars; every packaging is made from those.
 *       Otherwise a difference between two formats could be a difference between two compilations.</li>
 *   <li><strong>One monotonic clock, spawn to first response.</strong> See {@link StartupHarness}.</li>
 *   <li><strong>Interleaved, not blocked.</strong> Within each iteration the variants are run in a fresh
 *       random order. Running twenty of one and then twenty of the next hands every slow moment of the
 *       machine - a background build, a thermal dip, or a change in filesystem-cache state - entirely to
 *       whichever variant happened to be running then, and a block design cannot tell that apart from a
 *       real effect.</li>
 *   <li><strong>Warm-up runs are discarded, but they do not set the cache state.</strong> Every sample uses
 *       a fresh JVM. By default the harness neither evicts nor otherwise controls the OS page cache, so a
 *       discarded process run must not be interpreted as establishing a cold- or warm-filesystem-cache
 *       condition. {@code --page-cache evict-artifacts} or {@code drop-all} evicts before every launch, outside
 *       the timed interval, and the readiness snapshot's major faults and storage reads confirm it.</li>
 *   <li><strong>Every raw sample is kept.</strong> {@code results.json} holds each run, warm-ups flagged,
 *       so the summary can be recomputed or disputed without running anything again.</li>
 *   <li><strong>A variant that cannot be built is reported, not dropped.</strong></li>
 * </ol>
 *
 * <p>The default required policy exits {@code 0} only when every required variant produced every requested
 * measured run. Explicit partial mode exits {@code 0} when at least one measured run succeeded. Both
 * reports are written before either decision. The required variants are the selected core rows,
 * {@link Options#requiredVariants()}; an opt-in row, whether added by {@code --optional-rows} or named in
 * {@code --variants}, is never required.</p>
 */
public final class StartupBenchmark {

    /** Default HTTP path polled for readiness; the sample serves it. */
    private static final String DEFAULT_READINESS_PATH = "/hello";

    /** Default number of discarded runs per variant, capped by the number of measured runs. */
    private static final int DEFAULT_WARMUP = 3;

    /** Default seed, fixed so that two runs of the harness shuffle and bootstrap identically. */
    private static final long DEFAULT_SEED = 20260921L;

    /** Default per-start timeout. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    private StartupBenchmark() {
    }

    /**
     * Entry point.
     *
     * @param args the options documented above
     * @throws Exception if the sample build or the reporting fails outright
     */
    public static void main(String[] args) throws Exception {
        PrintStream log = System.out;
        String osName = System.getProperty("os.name", "");
        Options options;
        CpuLimit cpuLimit;
        try {
            options = Options.parse(args);
            // Validated before the sample build and before anything is pinned, with no fallbacks.
            cpuLimit = options.cpus() == null ? null : CpuLimit.forThisMachine(options.cpus());
            options.pageCache().validate(osName, PageCacheEviction::passwordlessSudo);
        } catch (IllegalArgumentException e) {
            System.err.println("micronaut-runner startup benchmark: " + e.getMessage());
            System.err.println(Options.usage());
            System.exit(2);
            return;
        }

        // Once per invocation, before the sample build: no variant sets a GC flag, so one probe covers them all.
        CpuLimit.Probe probe = null;
        if (cpuLimit != null) {
            try {
                probe = cpuLimit.probe(SampleBuild.javaExecutable(), System.getProperty("java.class.path"));
            } catch (IOException e) {
                fail("the CPU limit does not hold: " + e.getMessage());
                return;
            }
            log.println("[startup-benchmark] under " + String.join(" ", cpuLimit.commandPrefix()) + " the JVM sees "
                    + probe.availableProcessors() + " CPU(s): " + probe.flags());
        }

        Path artifacts = options.workDirectory();
        Files.createDirectories(artifacts);

        List<String> selection = options.selection();
        List<Variant> variants;
        String buildFailure = null;
        try {
            SampleBuild build = SampleBuild.prepare(options.sample(), options.repository(),
                    options.runnerVersion(), artifacts, cpuLimit, log);
            variants = build.variants(selection);
        } catch (IOException | InterruptedException e) {
            buildFailure = e.getMessage();
            log.println("[startup-benchmark] the sample could not be built: " + buildFailure);
            String reason = "the sample's Gradle build failed: " + oneLine(buildFailure);
            variants = SampleBuild.unavailableVariants(reason, selection);
        }

        // Captured before pinning: afterwards this JVM would report the harness's CPUs, not the machine's.
        BenchmarkProvenance provenance = BenchmarkProvenance.capture(
                configuredRunnerSource(), options.sample(), System.getenv());
        RunConditions conditions = RunConditions.capture(cpuLimit, probe, options.pageCache(), artifacts);
        try {
            if (options.pageCache() == PageCacheMode.EVICT_ARTIFACTS) {
                // POSIX_FADV_DONTNEED cannot drop dirty pages, and every artifact was just written.
                PageCacheEviction.sync();
            }
            if (cpuLimit != null) {
                cpuLimit.pinHarness();
                log.println("[startup-benchmark] pinned the harness to CPUs " + conditions.harnessCpus()
                        + "; children run on CPUs " + conditions.childCpus());
            }
        } catch (IOException e) {
            fail(e.getMessage());
            return;
        }

        List<VariantResult> results;
        List<StartupHarness.DiagnosticRun> diagnostics = new ArrayList<>();
        try (StartupHarness harness = new StartupHarness(options.readinessPath(), options.timeout(),
                cpuLimit == null ? List.of() : cpuLimit.commandPrefix(),
                PageCacheEviction.forMode(options.pageCache(), osName))) {
            results = measure(harness, variants, options, log);
            if (options.diagnostics()) {
                diagnostics.addAll(collectDiagnostics(harness, variants, options, log));
            }
        } catch (StartupHarness.LaunchPreparationFailure e) {
            fail(e.getMessage());
            return;
        }

        RunContext context = new RunContext(options.sample(), options.repository(),
                options.runnerVersion(), options.outputDirectory(), options.iterations(),
                options.warmupIterations(), options.seed(), options.readinessPath(),
                options.diagnostics(), Instant.now().toString(), options.requiredVariants(),
                options.completenessPolicy(), provenance, conditions);
        int exitCode = finish(context, results, diagnostics, log);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /** Ends a run that cannot establish the conditions it promised; no report would describe it truthfully. */
    private static void fail(String message) {
        System.err.println("micronaut-runner startup benchmark: " + oneLine(message));
        System.exit(1);
    }

    private static Path configuredRunnerSource() {
        String configured = System.getProperty("runner.benchmark.sourceRoot");
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    static int finish(RunContext context,
                      List<VariantResult> results,
                      List<StartupHarness.DiagnosticRun> diagnostics,
                      PrintStream log) throws IOException {
        Reports.write(context.outputDirectory(), context, results, diagnostics);
        log.println("[startup-benchmark] wrote " + context.outputDirectory().resolve(Reports.RESULTS_FILE));
        log.println("[startup-benchmark] wrote " + context.outputDirectory().resolve(Reports.SUMMARY_FILE));
        BenchmarkStatus status = BenchmarkStatus.evaluate(context, results);
        if (status.exitCode() != 0) {
            System.err.println("[startup-benchmark] benchmark matrix is incomplete under the "
                    + context.completenessPolicy().externalName() + " policy; see "
                    + context.outputDirectory().resolve(Reports.SUMMARY_FILE));
        }
        return status.exitCode();
    }

    /**
     * Runs every available variant, interleaved, and summarises the measured runs.
     *
     * @param harness  the process harness
     * @param variants every variant, available or not
     * @param options  the parsed options
     * @param log      where progress goes
     * @return one result per variant, in the order the variants were given
     * @throws InterruptedException if a run is interrupted
     */
    static List<VariantResult> measure(StartupRunner harness,
                                       List<Variant> variants,
                                       Options options,
                                       PrintStream log) throws InterruptedException {
        List<Variant> runnable = variants.stream().filter(Variant::available).toList();
        List<List<RunAttempt>> attempts = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            attempts.add(new ArrayList<>());
        }

        Random random = new Random(options.seed());
        int total = options.warmupIterations() + options.iterations();
        int globalOrder = 0;
        for (int iteration = 0; iteration < total; iteration++) {
            boolean warmup = iteration < options.warmupIterations();
            int phaseIteration = warmup ? iteration : iteration - options.warmupIterations();
            List<Variant> order = new ArrayList<>(runnable);
            // A fresh order every iteration: this is what keeps a transient slowdown of the machine from
            // landing entirely on one variant.
            Collections.shuffle(order, random);
            for (Variant variant : order) {
                int slot = variants.indexOf(variant);
                int attemptOrder = globalOrder++;
                try {
                    StartupSample sample = harness.run(variant, iteration, warmup);
                    attempts.get(slot).add(RunAttempt.success(
                            variant.name(), phaseIteration, attemptOrder, sample));
                    log.printf(Locale.ROOT,
                            "[startup-benchmark] %s %-18s ready %7.1f ms | log line %7.1f ms |"
                                    + " framework says %s | at readiness: %s%n",
                            warmup ? "warmup " : "measure", variant.name(), sample.readinessMillis(),
                            sample.logLineMillis(),
                            sample.frameworkMillis() < 0
                                    ? "nothing"
                                    : String.format(Locale.ROOT, "%.0f ms", sample.frameworkMillis()),
                            atReadiness(sample.atReadiness()));
                } catch (IOException e) {
                    String reason = oneLine(e.getMessage());
                    Integer exitCode = e instanceof StartupHarness.RunFailure failure
                            ? failure.exitCode() : null;
                    attempts.get(slot).add(RunAttempt.failure(variant.name(), iteration, phaseIteration,
                            attemptOrder, warmup, reason, exitCode));
                    log.println("[startup-benchmark] " + (warmup ? "warmup" : "measured")
                            + " attempt " + phaseIteration + " (global order " + attemptOrder + ") of "
                            + variant.name() + " failed: " + reason);
                }
            }
        }

        List<VariantResult> results = new ArrayList<>(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            Variant variant = variants.get(i);
            DeploymentSize deploymentSize = variant.deploymentSize();
            long deploymentBytes = deploymentSize == null ? -1 : deploymentSize.totalBytes();
            results.add(VariantResult.summarize(variant, deploymentBytes,
                    attempts.get(i), options.warmupIterations(), options.iterations(), options.seed()));
        }
        return results;
    }

    private static List<StartupHarness.DiagnosticRun> collectDiagnostics(StartupHarness harness,
                                                                         List<Variant> variants,
                                                                         Options options,
                                                                         PrintStream log)
            throws InterruptedException {
        List<StartupHarness.DiagnosticRun> runs = new ArrayList<>();
        Path logs = options.outputDirectory().resolve("diagnostics");
        for (Variant variant : variants) {
            if (!variant.available()) {
                continue;
            }
            try {
                Files.createDirectories(logs);
                runs.add(harness.diagnose(variant, logs.resolve(variant.name() + "-class-load.log")));
                log.println("[startup-benchmark] diagnostic run of " + variant.name() + " done");
            } catch (IOException e) {
                log.println("[startup-benchmark] diagnostic run of " + variant.name()
                        + " failed: " + oneLine(e.getMessage()));
            }
        }
        return runs;
    }

    /** One progress-log fragment for a readiness snapshot; {@code —} marks what could not be read. */
    static String atReadiness(ReadinessSnapshot snapshot) {
        ReadinessSnapshot value = snapshot == null ? ReadinessSnapshot.UNAVAILABLE : snapshot;
        return "rss " + Reports.mebibytes(value.rssBytes())
                + " | private " + Reports.mebibytes(value.privateBytes())
                + " | peak " + Reports.mebibytes(value.peakBytes())
                + " | classes " + (value.loadedClasses() < 0 ? "—" : value.loadedClasses())
                + " (" + (value.sharedClasses() < 0 ? "—" : value.sharedClasses()) + " shared)"
                + " | majflt " + (value.majorFaults() < 0 ? "—" : value.majorFaults())
                + " | read " + Reports.mebibytes(value.readBytes())
                + " | probe " + (value.probeMillis() < 0
                        ? "—" : String.format(Locale.ROOT, "%.1f ms", value.probeMillis()));
    }

    private static String oneLine(String message) {
        return message == null ? "no message" : message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /**
     * The parsed command line.
     *
     * @param sample           the sample application's project directory
     * @param repository       the Maven repository the runner plugins come from
     * @param runnerVersion    the version they were published under
     * @param outputDirectory  where the reports go
     * @param workDirectory    where the variants are built and their caches trained
     * @param iterations       measured runs per variant
     * @param warmupIterations discarded runs per variant
     * @param seed             the seed of the shuffle and the bootstrap
     * @param readinessPath    the HTTP path polled for readiness
     * @param timeout          how long one start may take
     * @param diagnostics      whether to make separate {@code -Xlog:class+load} runs for per-class inspection
     * @param completenessPolicy whether incomplete measured results fail the invocation
     * @param optionalRows     whether to build and measure the opt-in rows as well as the core rows
     * @param variants         the rows named with {@code --variants}, or {@code null} when none were
     * @param cpus             the child CPU limit, or {@code null} for none
     * @param pageCache        how the OS page cache is treated before each launch
     */
    record Options(Path sample,
                   String repository,
                   String runnerVersion,
                   Path outputDirectory,
                   Path workDirectory,
                   int iterations,
                   int warmupIterations,
                   long seed,
                   String readinessPath,
                   Duration timeout,
                   boolean diagnostics,
                   CompletenessPolicy completenessPolicy,
                   boolean optionalRows,
                   List<String> variants,
                   Integer cpus,
                   PageCacheMode pageCache) {

        Options {
            variants = variants == null ? null : List.copyOf(variants);
        }

        /**
         * The one resolved selection of rows this run builds and reports: the {@code --variants} rows when
         * given, otherwise the core rows plus, with {@code --optional-rows}, the opt-in rows. It always follows
         * report order, not the order on the command line.
         *
         * @return the selected row names, in report order
         */
        List<String> selection() {
            List<String> core = SampleBuild.variantNames();
            return SampleBuild.allVariantNames().stream()
                    .filter(name -> variants != null ? variants.contains(name)
                            : optionalRows || core.contains(name))
                    .toList();
        }

        /**
         * The selected core rows, which gate the exit code. A selected opt-in row is built and measured but never
         * gates it.
         *
         * @return the required variant names, in report order
         */
        List<String> requiredVariants() {
            List<String> core = SampleBuild.variantNames();
            return selection().stream().filter(core::contains).toList();
        }

        /**
         * Parses the command line.
         *
         * @param args the arguments
         * @return the options
         * @throws IllegalArgumentException if a required option is missing or a value is not a number
         */
        static Options parse(String[] args) {
            Path sample = null;
            String repository = null;
            String version = null;
            Path out = null;
            Path work = null;
            Integer iterations = null;
            Integer warmup = null;
            long seed = DEFAULT_SEED;
            String readiness = DEFAULT_READINESS_PATH;
            int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
            boolean diagnostics = false;
            CompletenessPolicy completenessPolicy = CompletenessPolicy.REQUIRED;
            boolean optionalRows = false;
            List<String> variants = null;
            Integer cpus = null;
            PageCacheMode pageCache = PageCacheMode.UNCONTROLLED;

            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                switch (argument) {
                    case "--sample" -> sample = Path.of(value(args, ++i, argument));
                    case "--repo" -> repository = value(args, ++i, argument);
                    case "--version" -> version = value(args, ++i, argument);
                    case "--out" -> out = Path.of(value(args, ++i, argument));
                    case "--work" -> work = Path.of(value(args, ++i, argument));
                    case "--iterations" -> iterations = number(value(args, ++i, argument), argument);
                    case "--warmup" -> warmup = number(value(args, ++i, argument), argument);
                    case "--seed" -> seed = number(value(args, ++i, argument), argument);
                    case "--readiness" -> readiness = value(args, ++i, argument);
                    case "--timeout" -> timeoutSeconds = number(value(args, ++i, argument), argument);
                    case "--diagnostics" -> diagnostics = true;
                    case "--allow-partial" -> completenessPolicy = CompletenessPolicy.PARTIAL;
                    case "--optional-rows" -> optionalRows = true;
                    case "--variants" -> variants = variants(value(args, ++i, argument));
                    case "--cpus" -> cpus = number(value(args, ++i, argument), argument);
                    case "--page-cache" -> pageCache = PageCacheMode.parse(value(args, ++i, argument));
                    default -> throw new IllegalArgumentException("unknown option " + argument);
                }
            }
            if (variants != null && optionalRows) {
                throw new IllegalArgumentException("--variants and --optional-rows cannot be combined:"
                        + " name the opt-in rows to run in --variants");
            }

            require(sample, "--sample");
            require(repository, "--repo");
            require(version, "--version");
            require(out, "--out");
            require(iterations, "--iterations");
            if (!Files.isDirectory(sample)) {
                throw new IllegalArgumentException("--sample " + sample + " is not a directory");
            }
            if (iterations < 1) {
                throw new IllegalArgumentException("--iterations must be at least 1");
            }
            // Warm-ups are capped by the measured count: asking for two measured runs and getting three
            // warm-ups would spend most of a quick run on results nobody sees.
            int effectiveWarmup = warmup != null ? warmup : Math.min(DEFAULT_WARMUP, iterations);
            if (effectiveWarmup < 0) {
                throw new IllegalArgumentException("--warmup cannot be negative");
            }
            Path outputDirectory = out.toAbsolutePath().normalize();
            Path workDirectory = work != null ? work.toAbsolutePath().normalize()
                    : outputDirectory.resolve("artifacts");
            return new Options(sample.toAbsolutePath().normalize(), repository, version,
                    outputDirectory, workDirectory, iterations, effectiveWarmup, seed,
                    readiness.startsWith("/") ? readiness : "/" + readiness,
                    Duration.ofSeconds(timeoutSeconds), diagnostics, completenessPolicy, optionalRows,
                    variants, cpus, pageCache);
        }

        /**
         * Parses a {@code --variants} value: comma-separated row names, each trimmed.
         *
         * @param value the value
         * @return the names, in command-line order
         * @throws IllegalArgumentException if a name is empty, unknown or repeated
         */
        private static List<String> variants(String value) {
            List<String> valid = SampleBuild.allVariantNames();
            List<String> names = new ArrayList<>();
            for (String part : value.split(",", -1)) {
                String name = part.trim();
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("--variants has an empty name in '" + value
                            + "'; valid names: " + String.join(", ", valid));
                }
                if (!valid.contains(name)) {
                    throw new IllegalArgumentException("--variants names an unknown row '" + name
                            + "'; valid names: " + String.join(", ", valid));
                }
                if (names.contains(name)) {
                    throw new IllegalArgumentException("--variants names '" + name + "' twice; valid names: "
                            + String.join(", ", valid));
                }
                names.add(name);
            }
            return List.copyOf(names);
        }

        /**
         * The usage text, printed when the arguments do not parse.
         *
         * @return the usage text
         */
        static String usage() {
            return """
                   Usage: StartupBenchmark --sample <dir> --repo <uri> --version <v> \
                   --iterations <n> --out <dir>
                                          [--work <dir>] [--warmup <n>] [--seed <n>] [--readiness <path>] \
                   [--timeout <seconds>] [--diagnostics] [--allow-partial] [--optional-rows | --variants <a,b>] \
                   [--cpus <n>] [--page-cache uncontrolled|evict-artifacts|drop-all]
                   Rows for --variants: \
                   """ + String.join(", ", SampleBuild.allVariantNames());
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return args[index];
        }

        private static int number(String value, String option) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " needs a number, got '" + value + "'");
            }
        }

        private static void require(Object value, String option) {
            if (value == null) {
                throw new IllegalArgumentException(option + " is required");
            }
        }
    }
}
