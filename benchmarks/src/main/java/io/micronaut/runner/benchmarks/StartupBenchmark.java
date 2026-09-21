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
 *   [ --warmup     &lt;n&gt;  ]  discarded runs per variant (default: min(3, iterations))
 *   [ --seed       &lt;n&gt;  ]  seed of the interleaving shuffle and the bootstrap
 *   [ --readiness  &lt;p&gt;  ]  the HTTP path polled for readiness (default /hello)
 *   [ --timeout    &lt;s&gt;  ]  how long one start may take (default 120)
 *   [ --diagnostics     ]  additionally make one -Xlog:class+load run per variant
 * </pre>
 *
 * <h2>The methodology, and why each rule is there</h2>
 * <ol>
 *   <li><strong>Identical application bytes across variants.</strong> One Gradle build of the sample
 *       produces the classes and the resolved dependency jars; all six packagings are made from those.
 *       Otherwise a difference between two formats could be a difference between two compilations.</li>
 *   <li><strong>One monotonic clock, spawn to first response.</strong> See {@link StartupHarness}.</li>
 *   <li><strong>Interleaved, not blocked.</strong> Within each iteration the variants are run in a fresh
 *       random order. Running twenty of one and then twenty of the next hands every slow moment of the
 *       machine - a background build, a thermal dip, a page-cache eviction - entirely to whichever variant
 *       happened to be running then, and a block design cannot tell that apart from a real effect.</li>
 *   <li><strong>Warm-up runs are discarded.</strong> The first start of a given artifact pays for reading
 *       it off disk into the page cache. That cost is real, but it is a cold-start cost and mixing a
 *       couple of them into a warm measurement moves the median for no reason anybody can see.</li>
 *   <li><strong>Every raw sample is kept.</strong> {@code results.json} holds each run, warm-ups flagged,
 *       so the summary can be recomputed or disputed without running anything again.</li>
 *   <li><strong>A variant that cannot be built is reported, not dropped.</strong></li>
 * </ol>
 *
 * <p>Exit status is {@code 0} when at least one variant was measured, {@code 1} otherwise. Both reports
 * are written either way, because "nothing could be built, and here is why" is the most useful thing the
 * harness can say on a bad day.</p>
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
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("micronaut-runner startup benchmark: " + e.getMessage());
            System.err.println(Options.usage());
            System.exit(2);
            return;
        }

        Path artifacts = options.outputDirectory().resolve("artifacts");
        Files.createDirectories(artifacts);

        List<Variant> variants;
        String buildFailure = null;
        try {
            SampleBuild build = SampleBuild.prepare(options.sample(), options.repository(),
                    options.runnerVersion(), artifacts, log);
            variants = build.variants();
        } catch (IOException | InterruptedException e) {
            buildFailure = e.getMessage();
            log.println("[startup-benchmark] the sample could not be built: " + buildFailure);
            variants = List.of(Variant.unavailable("all",
                    "every variant of the sample application",
                    "the sample's Gradle build failed: " + oneLine(buildFailure)));
        }

        List<VariantResult> results;
        List<StartupHarness.ClassLoadCount> diagnostics = new ArrayList<>();
        try (StartupHarness harness = new StartupHarness(options.readinessPath(), options.timeout())) {
            results = measure(harness, variants, options, log);
            if (options.diagnostics()) {
                diagnostics.addAll(collectDiagnostics(harness, variants, options, log));
            }
        }

        RunContext context = new RunContext(options.sample(), options.repository(),
                options.runnerVersion(), options.outputDirectory(), options.iterations(),
                options.warmupIterations(), options.seed(), options.readinessPath(),
                options.diagnostics(), Instant.now().toString());
        Reports.write(options.outputDirectory(), context, results, diagnostics);
        log.println("[startup-benchmark] wrote " + options.outputDirectory().resolve(Reports.RESULTS_FILE));
        log.println("[startup-benchmark] wrote " + options.outputDirectory().resolve(Reports.SUMMARY_FILE));

        boolean measuredSomething = results.stream().anyMatch(result -> result.readiness() != null);
        if (!measuredSomething) {
            System.err.println("[startup-benchmark] no variant produced a single measurement;"
                    + " see " + options.outputDirectory().resolve(Reports.SUMMARY_FILE));
            System.exit(1);
        }
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
    private static List<VariantResult> measure(StartupHarness harness,
                                               List<Variant> variants,
                                               Options options,
                                               PrintStream log) throws InterruptedException {
        List<Variant> runnable = variants.stream().filter(Variant::available).toList();
        List<List<StartupSample>> samples = new ArrayList<>();
        List<List<String>> failures = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            samples.add(new ArrayList<>());
            failures.add(new ArrayList<>());
        }

        Random random = new Random(options.seed());
        int total = options.warmupIterations() + options.iterations();
        for (int iteration = 0; iteration < total; iteration++) {
            boolean warmup = iteration < options.warmupIterations();
            List<Variant> order = new ArrayList<>(runnable);
            // A fresh order every iteration: this is what keeps a transient slowdown of the machine from
            // landing entirely on one variant.
            Collections.shuffle(order, random);
            for (Variant variant : order) {
                int slot = variants.indexOf(variant);
                try {
                    StartupSample sample = harness.run(variant, iteration, warmup);
                    samples.get(slot).add(sample);
                    log.printf(Locale.ROOT,
                            "[startup-benchmark] %s %-18s ready %7.1f ms | log line %7.1f ms |"
                                    + " framework says %s%n",
                            warmup ? "warmup " : "measure", variant.name(), sample.readinessMillis(),
                            sample.logLineMillis(),
                            sample.frameworkMillis() < 0
                                    ? "nothing"
                                    : String.format(Locale.ROOT, "%.0f ms", sample.frameworkMillis()));
                } catch (IOException e) {
                    String reason = oneLine(e.getMessage());
                    failures.get(slot).add(reason);
                    log.println("[startup-benchmark] " + variant.name() + " failed: " + reason);
                }
            }
        }

        List<VariantResult> results = new ArrayList<>(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            Variant variant = variants.get(i);
            List<StartupSample> variantSamples = samples.get(i);
            double[] readiness = variantSamples.stream()
                    .filter(sample -> !sample.warmup())
                    .mapToDouble(StartupSample::readinessMillis)
                    .toArray();
            double[] logLine = variantSamples.stream()
                    .filter(sample -> !sample.warmup() && sample.logLineMillis() >= 0)
                    .mapToDouble(StartupSample::logLineMillis)
                    .toArray();
            double[] framework = variantSamples.stream()
                    .filter(sample -> !sample.warmup() && sample.frameworkMillis() >= 0)
                    .mapToDouble(StartupSample::frameworkMillis)
                    .toArray();
            results.add(new VariantResult(variant, SampleBuild.sizeOf(variant.artifact()),
                    List.copyOf(variantSamples),
                    Statistics.of(readiness, options.seed()),
                    Statistics.of(logLine, options.seed()),
                    Statistics.of(framework, options.seed()),
                    List.copyOf(failures.get(i))));
        }
        return results;
    }

    private static List<StartupHarness.ClassLoadCount> collectDiagnostics(StartupHarness harness,
                                                                          List<Variant> variants,
                                                                          Options options,
                                                                          PrintStream log)
            throws InterruptedException {
        List<StartupHarness.ClassLoadCount> counts = new ArrayList<>();
        Path logs = options.outputDirectory().resolve("diagnostics");
        for (Variant variant : variants) {
            if (!variant.available()) {
                continue;
            }
            try {
                Files.createDirectories(logs);
                counts.add(harness.diagnose(variant, logs.resolve(variant.name() + "-class-load.log")));
                log.println("[startup-benchmark] diagnostic run of " + variant.name() + " done");
            } catch (IOException e) {
                log.println("[startup-benchmark] diagnostic run of " + variant.name()
                        + " failed: " + oneLine(e.getMessage()));
            }
        }
        return counts;
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
     * @param iterations       measured runs per variant
     * @param warmupIterations discarded runs per variant
     * @param seed             the seed of the shuffle and the bootstrap
     * @param readinessPath    the HTTP path polled for readiness
     * @param timeout          how long one start may take
     * @param diagnostics      whether to make separate class-load counting runs
     */
    record Options(Path sample,
                   String repository,
                   String runnerVersion,
                   Path outputDirectory,
                   int iterations,
                   int warmupIterations,
                   long seed,
                   String readinessPath,
                   Duration timeout,
                   boolean diagnostics) {

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
            Integer iterations = null;
            Integer warmup = null;
            long seed = DEFAULT_SEED;
            String readiness = DEFAULT_READINESS_PATH;
            int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
            boolean diagnostics = false;

            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                switch (argument) {
                    case "--sample" -> sample = Path.of(value(args, ++i, argument));
                    case "--repo" -> repository = value(args, ++i, argument);
                    case "--version" -> version = value(args, ++i, argument);
                    case "--out" -> out = Path.of(value(args, ++i, argument));
                    case "--iterations" -> iterations = number(value(args, ++i, argument), argument);
                    case "--warmup" -> warmup = number(value(args, ++i, argument), argument);
                    case "--seed" -> seed = number(value(args, ++i, argument), argument);
                    case "--readiness" -> readiness = value(args, ++i, argument);
                    case "--timeout" -> timeoutSeconds = number(value(args, ++i, argument), argument);
                    case "--diagnostics" -> diagnostics = true;
                    default -> throw new IllegalArgumentException("unknown option " + argument);
                }
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
            return new Options(sample.toAbsolutePath().normalize(), repository, version,
                    out.toAbsolutePath().normalize(), iterations, effectiveWarmup, seed,
                    readiness.startsWith("/") ? readiness : "/" + readiness,
                    Duration.ofSeconds(timeoutSeconds), diagnostics);
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
                                          [--warmup <n>] [--seed <n>] [--readiness <path>] \
                   [--timeout <seconds>] [--diagnostics]""";
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
