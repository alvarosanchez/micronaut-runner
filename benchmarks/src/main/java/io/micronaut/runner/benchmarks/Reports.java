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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Writes the two files the module promises: {@code results.json}, which keeps every raw sample, and
 * {@code summary.md}, which is what a human reads and what the CI workflow appends to the job summary.
 *
 * <p>The JSON is written by hand. This module has exactly two dependencies, the launcher and the packaging
 * library, and adding a JSON library to a benchmark harness for this fixed report schema
 * would be a poor trade.</p>
 */
final class Reports {

    /** The file name the CI workflow reads; changing it breaks the job summary. */
    static final String SUMMARY_FILE = "summary.md";

    /** The file every raw sample goes to. */
    static final String RESULTS_FILE = "results.json";

    private Reports() {
    }

    /**
     * Writes both reports.
     *
     * @param outputDirectory where they go
     * @param context         what this run was
     * @param results         one entry per variant, in report order
     * @param diagnostics     class-load counts from separate, explicitly labelled runs
     * @throws IOException if either file cannot be written
     */
    static void write(Path outputDirectory,
                      RunContext context,
                      List<VariantResult> results,
                      List<StartupHarness.ClassLoadCount> diagnostics) throws IOException {
        Files.createDirectories(outputDirectory);
        Files.writeString(outputDirectory.resolve(RESULTS_FILE), json(context, results, diagnostics),
                StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve(SUMMARY_FILE), markdown(context, results, diagnostics),
                StandardCharsets.UTF_8);
    }

    private static String json(RunContext context,
                               List<VariantResult> results,
                               List<StartupHarness.ClassLoadCount> diagnostics) throws IOException {
        StringBuilder out = new StringBuilder(64 * 1024);
        BenchmarkStatus status = BenchmarkStatus.evaluate(context, results);
        out.append("{\n");
        out.append("  \"schemaVersion\": ").append(BenchmarkProvenance.SCHEMA_VERSION).append(",\n");
        out.append("  \"generatedAt\": ").append(quote(context.generatedAt())).append(",\n");
        out.append("  \"sample\": \"sample\",\n");
        out.append("  \"repository\": \"<redacted:repository-location>\",\n");
        out.append("  \"runnerVersion\": ").append(quote(context.runnerVersion())).append(",\n");
        out.append("  \"readinessPath\": ").append(quote(context.readinessPath())).append(",\n");
        out.append("  \"measuredIterations\": ").append(context.iterations()).append(",\n");
        out.append("  \"warmupIterations\": ").append(context.warmupIterations()).append(",\n");
        out.append("  \"completenessPolicy\": ")
                .append(quote(context.completenessPolicy().externalName())).append(",\n");
        out.append("  \"complete\": ").append(status.complete()).append(",\n");
        out.append("  \"exitCode\": ").append(status.exitCode()).append(",\n");
        out.append("  \"requiredVariants\": [");
        for (int i = 0; i < context.requiredVariants().size(); i++) {
            out.append(i == 0 ? "" : ", ").append(quote(context.requiredVariants().get(i)));
        }
        out.append("],\n");
        out.append("  \"seed\": ").append(context.seed()).append(",\n");
        out.append("  \"interleaved\": true,\n");
        out.append("  \"timingRunsCarryLoggingFlags\": false,\n");
        out.append("  \"jvmProcessState\": \"fresh per sample\",\n");
        out.append("  \"osPageCacheState\": \"uncontrolled\",\n");
        out.append("  \"applicationCacheMode\": \"none\",\n");
        appendProvenance(out, context.provenance());
        out.append(",\n");
        out.append("  \"environment\": {\n");
        BenchmarkProvenance provenance = context.provenance();
        out.append("    \"javaVersion\": ").append(quote(provenance.javaVersion())).append(",\n");
        out.append("    \"javaRuntimeVersion\": ").append(quote(provenance.javaRuntimeVersion())).append(",\n");
        out.append("    \"javaVendor\": ").append(quote(provenance.javaVendor())).append(",\n");
        out.append("    \"javaVmName\": ").append(quote(provenance.javaVmName())).append(",\n");
        out.append("    \"osName\": ").append(quote(provenance.osName())).append(",\n");
        out.append("    \"osVersion\": ").append(quote(provenance.osVersion())).append(",\n");
        out.append("    \"osArch\": ").append(quote(provenance.osArch())).append(",\n");
        out.append("    \"availableProcessors\": ").append(provenance.availableProcessors()).append(",\n");
        out.append("    \"totalMemoryBytes\": ").append(provenance.totalMemoryBytes()).append('\n');
        out.append("  },\n");
        out.append("  \"variants\": [\n");
        for (int i = 0; i < results.size(); i++) {
            appendVariant(out, context, results.get(i));
            out.append(i == results.size() - 1 ? "\n" : ",\n");
        }
        out.append("  ],\n");
        appendComparisons(out, context, results);
        out.append(",\n");
        appendAttempts(out, context, results);
        out.append(",\n");
        out.append("  \"diagnostics\": {\n");
        out.append("    \"comment\": \"Separate runs carrying -Xlog:class+load. NOT timing runs and not")
                .append(" comparable with the readiness numbers above.\",\n");
        out.append("    \"sharedArchiveInterpretation\": \"aggregate shared counts do not prove trained")
                .append(" application-class reuse\",\n");
        out.append("    \"runs\": [\n");
        for (int i = 0; i < diagnostics.size(); i++) {
            StartupHarness.ClassLoadCount count = diagnostics.get(i);
            out.append("      {\"variant\": ").append(quote(count.variant()))
                    .append(", \"classesLoaded\": ").append(count.classesLoaded())
                    .append(", \"fromSharedArchive\": ").append(count.fromSharedArchive())
                    .append(", \"horizon\": ").append(quote(count.horizon()))
                    .append(", \"readinessMillisWithLogging\": ").append(number(count.readinessMillis()))
                    .append(", \"command\": [");
            for (int argument = 0; argument < count.command().size(); argument++) {
                out.append(argument == 0 ? "" : ", ").append(quote(count.command().get(argument)));
            }
            out.append("]}").append(i == diagnostics.size() - 1 ? "\n" : ",\n");
        }
        out.append("    ]\n");
        out.append("  }\n");
        out.append("}\n");
        return out.toString();
    }

    private static void appendProvenance(StringBuilder out, BenchmarkProvenance provenance) {
        out.append("  \"provenance\": {\n");
        appendSource(out, "runnerSource", provenance.runnerSource());
        out.append(",\n");
        appendSource(out, "sampleSource", provenance.sampleSource());
        out.append(",\n");
        out.append("    \"jvmOptionEnvironment\": {\n");
        int index = 0;
        for (var entry : provenance.optionEnvironmentPresence().entrySet()) {
            out.append("      ").append(quote(entry.getKey())).append(": {\"present\": ")
                    .append(entry.getValue()).append(", \"action\": \"removed\", \"value\": ")
                    .append(quote(BenchmarkProvenance.REDACTED_JVM_OPTIONS)).append('}')
                    .append(index++ == provenance.optionEnvironmentPresence().size() - 1 ? "\n" : ",\n");
        }
        out.append("    },\n");
        out.append("    \"environmentScope\": \"allowlisted deltas only; full environment not recorded\"\n");
        out.append("  }");
    }

    private static void appendSource(StringBuilder out,
                                     String name,
                                     BenchmarkProvenance.SourceState source) {
        out.append("    ").append(quote(name)).append(": {\"revision\": ")
                .append(quote(source.revision())).append(", \"state\": ")
                .append(quote(source.state())).append('}');
    }

    private static void appendVariant(StringBuilder out, RunContext context, VariantResult result)
            throws IOException {
        Variant variant = result.variant();
        out.append("    {\n");
        out.append("      \"name\": ").append(quote(variant.name())).append(",\n");
        out.append("      \"description\": ").append(quote(variant.description())).append(",\n");
        out.append("      \"requestedEntryMode\": ")
                .append(quote(variant.requestedEntryMode().externalName())).append(",\n");
        out.append("      \"effectiveEntryMode\": ")
                .append(quote(variant.effectiveEntryMode() == null
                        ? null : variant.effectiveEntryMode().externalName())).append(",\n");
        out.append("      \"available\": ").append(variant.available()).append(",\n");
        out.append("      \"required\": ").append(context.requiredVariants().contains(variant.name()))
                .append(",\n");
        out.append("      \"unavailableReason\": ")
                .append(quote(redact(context, variant, variant.unavailableReason()))).append(",\n");
        out.append("      \"artifact\": ")
                .append(quote(variant.artifact() == null ? null : "artifact:" + variant.name())).append(",\n");
        out.append("      \"deploymentSize\": ");
        appendDeploymentSize(out, variant.deploymentSize());
        out.append(",\n");
        List<String> command = BenchmarkProvenance.relocatableCommand(variant);
        out.append("      \"command\": [");
        for (int i = 0; i < command.size(); i++) {
            out.append(i == 0 ? "" : ", ").append(quote(command.get(i)));
        }
        out.append("],\n");
        out.append("      \"orderedLaunchInputs\": [");
        List<BenchmarkProvenance.InputIdentity> identities = BenchmarkProvenance.inputIdentities(variant);
        for (int i = 0; i < identities.size(); i++) {
            BenchmarkProvenance.InputIdentity identity = identities.get(i);
            out.append(i == 0 ? "" : ", ")
                    .append("{\"id\": ").append(quote(identity.id()))
                    .append(", \"kind\": ").append(quote(identity.kind()))
                    .append(", \"bytes\": ").append(identity.bytes())
                    .append(", \"sha256\": ").append(quote(identity.sha256())).append('}');
        }
        out.append("],\n");
        out.append("      \"readiness\": ").append(statistics(result.readiness())).append(",\n");
        out.append("      \"logLine\": ").append(statistics(result.logLine())).append(",\n");
        out.append("      \"framework\": ").append(statistics(result.framework())).append(",\n");
        out.append("      \"warmup\": ");
        appendCounts(out, result.warmup());
        out.append(",\n");
        out.append("      \"measured\": ");
        appendCounts(out, result.measured());
        out.append(",\n");
        out.append("      \"failures\": [");
        for (int i = 0; i < result.failures().size(); i++) {
            out.append(i == 0 ? "" : ", ")
                    .append(quote(redact(context, variant, result.failures().get(i))));
        }
        out.append("],\n");
        out.append("      \"samples\": [\n");
        for (int i = 0; i < result.samples().size(); i++) {
            StartupSample sample = result.samples().get(i);
            out.append("        {\"iteration\": ").append(sample.iteration())
                    .append(", \"warmup\": ").append(sample.warmup())
                    .append(", \"port\": ").append(sample.port())
                    .append(", \"readinessMillis\": ").append(number(sample.readinessMillis()))
                    .append(", \"logLineMillis\": ")
                    .append(sample.logLineMillis() < 0 ? "null" : number(sample.logLineMillis()))
                    .append(", \"frameworkMillis\": ")
                    .append(sample.frameworkMillis() < 0 ? "null" : number(sample.frameworkMillis()))
                    .append(", \"pollGapMillis\": ").append(number(sample.pollGapMillis()))
                    .append(", \"exitCode\": ").append(sample.exitCode())
                    .append('}').append(i == result.samples().size() - 1 ? "\n" : ",\n");
        }
        out.append("      ]\n");
        out.append("    }");
    }

    private static void appendDeploymentSize(StringBuilder out, DeploymentSize deploymentSize) {
        if (deploymentSize == null) {
            out.append("null");
            return;
        }
        out.append("{\"boundary\": ").append(quote(DeploymentSize.BOUNDARY))
                .append(", \"unit\": ").append(quote(DeploymentSize.UNIT))
                .append(", \"symlinkPolicy\": ").append(quote(DeploymentSize.SYMLINK_POLICY))
                .append(", \"hardLinkPolicy\": ").append(quote(DeploymentSize.HARD_LINK_POLICY))
                .append(", \"totalBytes\": ").append(deploymentSize.totalBytes())
                .append(", \"components\": [");
        for (int i = 0; i < deploymentSize.components().size(); i++) {
            DeploymentSize.Component component = deploymentSize.components().get(i);
            out.append(i == 0 ? "" : ", ")
                    .append("{\"name\": ").append(quote(component.name()))
                    .append(", \"bytes\": ").append(component.bytes()).append('}');
        }
        out.append("]}");
    }

    private static void appendCounts(StringBuilder out, PhaseCounts counts) {
        out.append("{\"requested\": ").append(counts.requested())
                .append(", \"attempted\": ").append(counts.attempted())
                .append(", \"successful\": ").append(counts.successful())
                .append(", \"failed\": ").append(counts.failed())
                .append(", \"skipped\": ").append(counts.skipped()).append('}');
    }

    private static void appendAttempts(StringBuilder out, RunContext context, List<VariantResult> results) {
        List<RunAttempt> attempts = results.stream()
                .flatMap(result -> result.attempts().stream())
                .sorted(java.util.Comparator.comparingInt(RunAttempt::globalOrder))
                .toList();
        out.append("  \"attempts\": [\n");
        for (int i = 0; i < attempts.size(); i++) {
            RunAttempt attempt = attempts.get(i);
            Variant variant = results.stream()
                    .map(VariantResult::variant)
                    .filter(candidate -> candidate.name().equals(attempt.variant()))
                    .findFirst().orElse(null);
            StartupSample sample = attempt.sample();
            out.append("    {\"variant\": ").append(quote(attempt.variant()))
                    .append(", \"iteration\": ").append(attempt.iteration())
                    .append(", \"phaseIteration\": ").append(attempt.phaseIteration())
                    .append(", \"globalOrder\": ").append(attempt.globalOrder())
                    .append(", \"warmup\": ").append(attempt.warmup())
                    .append(", \"outcome\": ").append(quote(attempt.outcome().externalName()))
                    .append(", \"failureReason\": ")
                    .append(quote(redact(context, variant, attempt.failureReason())))
                    .append(", \"exitCode\": ")
                    .append(attempt.exitCode() == null ? "null" : attempt.exitCode())
                    .append(", \"timing\": ");
            if (sample == null) {
                out.append("null");
            } else {
                out.append("{\"port\": ").append(sample.port())
                        .append(", \"readinessMillis\": ").append(number(sample.readinessMillis()))
                        .append(", \"logLineMillis\": ")
                        .append(sample.logLineMillis() < 0 ? "null" : number(sample.logLineMillis()))
                        .append(", \"frameworkMillis\": ")
                        .append(sample.frameworkMillis() < 0 ? "null" : number(sample.frameworkMillis()))
                        .append(", \"pollGapMillis\": ").append(number(sample.pollGapMillis()))
                        .append('}');
            }
            out.append('}').append(i == attempts.size() - 1 ? "\n" : ",\n");
        }
        out.append("  ]");
    }

    private static void appendComparisons(StringBuilder out,
                                          RunContext context,
                                          List<VariantResult> results) {
        List<PairedComparison> comparisons = comparisons(context, results);
        out.append("  \"comparisons\": [\n");
        for (int i = 0; i < comparisons.size(); i++) {
            PairedComparison comparison = comparisons.get(i);
            out.append("    {\"leftVariant\": ").append(quote(comparison.leftVariant()))
                    .append(", \"rightVariant\": ").append(quote(comparison.rightVariant()))
                    .append(", \"estimator\": ").append(quote(PairedComparison.ESTIMATOR))
                    .append(", \"resamplingUnit\": ").append(quote(PairedComparison.RESAMPLING_UNIT))
                    .append(", \"requestedPairs\": ").append(comparison.requestedPairs())
                    .append(", \"pairedCount\": ").append(comparison.pairedCount())
                    .append(", \"excludedCount\": ").append(comparison.excludedCount())
                    .append(", \"descriptiveOnly\": ").append(comparison.descriptiveOnly())
                    .append(", \"medianDifferenceMillis\": ")
                    .append(nullableNumber(comparison.medianDifferenceMillis()))
                    .append(", \"ci95Low\": ").append(nullableNumber(comparison.ciLow()))
                    .append(", \"ci95High\": ").append(nullableNumber(comparison.ciHigh()))
                    .append(", \"ciConfidence\": ").append(number(Statistics.CONFIDENCE))
                    .append(", \"ciMethod\": \"percentile bootstrap of paired median differences\"")
                    .append(", \"ciResamples\": ").append(Statistics.RESAMPLES)
                    .append(", \"ciMinimumPairs\": ").append(Statistics.MIN_CONFIDENCE_SAMPLES)
                    .append(", \"ciReason\": ").append(quote(comparison.ciReason()))
                    .append(", \"seed\": ").append(comparison.seed())
                    .append(", \"includedIterations\": [");
            for (int pair = 0; pair < comparison.pairs().size(); pair++) {
                out.append(pair == 0 ? "" : ", ").append(comparison.pairs().get(pair).iteration());
            }
            out.append("], \"pairs\": [");
            for (int pair = 0; pair < comparison.pairs().size(); pair++) {
                PairedComparison.Pair value = comparison.pairs().get(pair);
                out.append(pair == 0 ? "" : ", ")
                        .append("{\"iteration\": ").append(value.iteration())
                        .append(", \"leftMillis\": ").append(number(value.leftMillis()))
                        .append(", \"rightMillis\": ").append(number(value.rightMillis()))
                        .append(", \"differenceMillis\": ").append(number(value.differenceMillis()))
                        .append('}');
            }
            out.append("], \"exclusions\": [");
            for (int exclusion = 0; exclusion < comparison.exclusions().size(); exclusion++) {
                PairedComparison.Exclusion value = comparison.exclusions().get(exclusion);
                out.append(exclusion == 0 ? "" : ", ")
                        .append("{\"iteration\": ").append(value.iteration())
                        .append(", \"leftOutcome\": ").append(quote(value.leftOutcome()))
                        .append(", \"rightOutcome\": ").append(quote(value.rightOutcome()))
                        .append('}');
            }
            out.append("]}").append(i == comparisons.size() - 1 ? "\n" : ",\n");
        }
        out.append("  ]");
    }

    private static List<PairedComparison> comparisons(RunContext context, List<VariantResult> results) {
        java.util.ArrayList<PairedComparison> comparisons = new java.util.ArrayList<>();
        for (int left = 0; left < results.size(); left++) {
            for (int right = left + 1; right < results.size(); right++) {
                comparisons.add(PairedComparison.of(results.get(left), results.get(right),
                        context.iterations(), context.seed()));
            }
        }
        return comparisons;
    }

    private static String statistics(Statistics statistics) {
        if (statistics == null) {
            return "null";
        }
        return "{\"count\": " + statistics.count()
                + ", \"min\": " + number(statistics.min())
                + ", \"median\": " + number(statistics.median())
                + ", \"p90\": " + number(statistics.p90())
                + ", \"mean\": " + number(statistics.mean())
                + ", \"max\": " + number(statistics.max())
                + ", \"descriptiveOnly\": " + !statistics.hasConfidenceInterval()
                + ", \"ci95Low\": " + nullableNumber(statistics.ciLow())
                + ", \"ci95High\": " + nullableNumber(statistics.ciHigh())
                + ", \"ciConfidence\": " + number(Statistics.CONFIDENCE)
                + ", \"ciMethod\": \"percentile bootstrap of the median\""
                + ", \"ciResamples\": " + Statistics.RESAMPLES
                + ", \"ciMinimumSamples\": " + Statistics.MIN_CONFIDENCE_SAMPLES
                + ", \"ciReason\": " + quote(statistics.ciReason()) + "}";
    }

    private static String markdown(RunContext context,
                                   List<VariantResult> results,
                                   List<StartupHarness.ClassLoadCount> diagnostics) {
        StringBuilder out = new StringBuilder(8 * 1024);
        BenchmarkStatus status = BenchmarkStatus.evaluate(context, results);
        out.append("# Startup benchmark\n\n");
        if (status.complete()) {
            out.append("**COMPLETE required comparison** — every required variant completed every requested")
                    .append(" measured run.\n\n");
        } else if (context.completenessPolicy() == CompletenessPolicy.REQUIRED) {
            out.append("**INCOMPLETE required comparison** — this invocation exits nonzero.\n\n");
        } else {
            out.append("**INCOMPLETE exploratory comparison** — successful-only timing summaries below are")
                    .append(" not a complete comparison.\n\n");
            if (status.anyMeasuredSuccess()) {
                out.append("Partial policy permits exit 0 because at least one measured run succeeded;")
                        .append(" missing cells remain failures, not timings.\n\n");
            } else {
                out.append("Partial policy still exits nonzero because no measured run succeeded.\n\n");
            }
        }
        out.append("Time from process spawn to the first successful HTTP response, for the same Micronaut")
                .append(" application across the required packaging and entry-path matrix.\n\n");
        out.append("- **Sample**: `sample` (relocatable identifier; source revision is in `results.json`)\n");
        out.append("- **Machine**: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(" · ")
                .append(System.getProperty("os.arch")).append(" · ")
                .append(Runtime.getRuntime().availableProcessors()).append(" CPUs\n");
        out.append("- **JDK**: ").append(System.getProperty("java.version")).append(" (")
                .append(System.getProperty("java.vendor")).append(")\n");
        out.append("- **Run**: ").append(context.iterations())
                .append(" measured iterations per variant after ").append(context.warmupIterations())
                .append(" discarded warm-up iterations; the variants are **interleaved in a random order")
                .append(" within each iteration** (seed ").append(context.seed()).append(")\n");
        out.append("- **Completeness policy**: `")
                .append(context.completenessPolicy().externalName()).append("`\n");
        out.append("- **JVM process**: fresh for every sample\n");
        out.append("- **OS page cache**: uncontrolled; discarded warm-ups do not establish a controlled")
                .append(" warm-cache or cold-filesystem-cache state\n");
        out.append("- **Application cache**: no trained CDS archive or AOT cache; default JDK class")
                .append(" sharing may still be active\n");
        out.append("- **Readiness**: first HTTP 200 from `").append(context.readinessPath())
                .append("`, polled every 2 ms with one persistent client, timed on a single monotonic")
                .append(" clock that starts immediately before the process is spawned\n");
        out.append("- **Timing runs carry no `-Xlog` flags.** Class-load counts, when collected, come from")
                .append(" separate runs and are labelled as such below\n");
        out.append("- **Generated**: ").append(context.generatedAt()).append("\n\n");

        out.append("## Matrix status\n\n");
        out.append("Skipped means a scheduled cell was not attempted because the variant was unavailable;")
                .append(" it is distinct from a failed process attempt. Warm-up failures are reported but")
                .append(" do not make an otherwise complete measured matrix fail.\n\n");
        out.append("| Variant | Phase | Requested | Attempted | Successful | Failed | Skipped |\n");
        out.append("|---|---|---:|---:|---:|---:|---:|\n");
        for (VariantResult result : results) {
            appendCountRow(out, result.variant().name(), "warm-up", result.warmup());
            appendCountRow(out, result.variant().name(), "measured", result.measured());
        }
        out.append('\n');

        out.append("## Successful measured runs only\n\n");
        out.append("Timing distributions condition on successful measured attempts; failed and skipped")
                .append(" attempts are never replaced with zeroes or invented durations.\n\n");

        out.append("| Variant | Runs | Readiness, median | p90 | 95% CI of median | Min | Max |")
                .append(" To startup line, median | Framework's own figure | Complete deployment |\n");
        out.append("|---|---:|---:|---:|---|---:|---:|---:|---:|---:|\n");
        for (VariantResult result : results) {
            Variant variant = result.variant();
            if (!variant.available() || result.readiness() == null) {
                out.append("| `").append(variant.name())
                        .append("` | **not measured** | — | — | — | — | — | — | — | — |\n");
                continue;
            }
            Statistics readiness = result.readiness();
            out.append("| `").append(variant.name()).append("` | ").append(readiness.count())
                    .append(" | **").append(millis(readiness.median())).append("** | ")
                    .append(millis(readiness.p90())).append(" | ")
                    .append(confidenceInterval(readiness))
                    .append(" | ").append(millis(readiness.min()))
                    .append(" | ").append(millis(readiness.max()))
                    .append(" | ")
                    .append(result.logLine() == null ? "not seen" : millis(result.logLine().median()))
                    .append(" | ")
                    .append(result.framework() == null ? "not seen" : millis(result.framework().median()))
                    .append(" | ").append(size(result.deploymentBytes())).append(" |\n");
        }
        out.append('\n');

        appendDeploymentSizes(out, results);
        appendPairedComparisons(out, context, results);
        appendIncompleteDetails(out, context, results, status);

        out.append("## What each variant is\n\n");
        out.append("| Variant | Requested entry | Effective entry | Packaging |\n|---|---|---|---|\n");
        for (VariantResult result : results) {
            Variant variant = result.variant();
            out.append("| `").append(variant.name()).append("` | ")
                    .append(variant.requestedEntryMode().externalName()).append(" | ")
                    .append(variant.effectiveEntryMode() == null
                            ? "unavailable" : variant.effectiveEntryMode().externalName()).append(" | ")
                    .append(variant.description()).append(" |\n");
        }
        out.append('\n');

        if (!diagnostics.isEmpty()) {
            out.append("## Diagnostic runs — NOT timing runs\n\n");
            out.append("These runs carry `-Xlog:class+load=info`, which costs milliseconds and costs them")
                    .append(" unevenly. Their times are here only so the size of that penalty is visible;")
                    .append(" they must never be compared with the table above. Counts cover **process")
                    .append(" spawn through completed shutdown**, so they can include classes loaded after")
                    .append(" readiness and by shutdown hooks.\n\n");
            out.append("| Variant | Classes loaded | From a shared archive | Readiness *with logging* |\n");
            out.append("|---|---:|---:|---:|\n");
            for (StartupHarness.ClassLoadCount count : diagnostics) {
                out.append("| `").append(count.variant()).append("` | ").append(count.classesLoaded())
                        .append(" | ").append(count.fromSharedArchive()).append(" | ")
                        .append(millis(count.readinessMillis())).append(" |\n");
            }
            out.append('\n');
        }

        out.append("## How to read this\n\n");
        out.append("- All variants are built from **one** compilation of the sample and **one**")
                .append(" dependency resolution, so any difference is a difference in packaging.\n");
        out.append("- The median and the 90th percentile are reported instead of a mean and a standard")
                .append(" deviation because process start times have a hard floor and a long right tail.\n");
        out.append("- Runs with fewer than ").append(Statistics.MIN_CONFIDENCE_SAMPLES)
                .append(" successful measured samples are **descriptive only**: medians and percentiles")
                .append(" remain visible, but no confidence interval is emitted. This reporting threshold")
                .append(" is not a universal guarantee of precision.\n");
        out.append("- When the threshold is met, the interval is a percentile bootstrap of the median (")
                .append(Statistics.RESAMPLES).append(" resamples). Per-variant intervals are not a test of")
                .append(" a difference and do not by themselves establish a startup speedup.\n");
        out.append("- **Readiness**, **to startup line** and **the framework's own figure** are three")
                .append(" different quantities and the gaps between them are informative. Readiness")
                .append(" includes serving the first request, which on a cold JVM is not free. The startup")
                .append(" line is this process observing the child's console on the same clock, which is")
                .append(" what a careful measurement by hand produces. The framework's figure is the")
                .append(" application counting itself, starting well after the JVM did: the smallest of the")
                .append(" three and the only one that is not an external observation.\n");
        out.append("- A variant marked **not measured** either could not be built or never answered. Matrix")
                .append(" status and attempt records distinguish skipped cells from failed processes.\n");
        out.append("- Every raw sample, warm-up runs included, is in `").append(RESULTS_FILE).append("`.\n");
        return out.toString();
    }

    private static void appendDeploymentSizes(StringBuilder out, List<VariantResult> results) {
        out.append("## Deployment sizes\n\n");
        out.append("Complete deployment is the sum of required regular-file lengths in logical bytes;")
                .append(" allocated filesystem blocks and compressed transfer sizes are not reported. Repeated")
                .append(" normalized paths are counted once and attributed to their first component. Symbolic")
                .append(" links are not followed or counted; distinct hard-linked paths count separately.\n\n");
        out.append("| Variant | Component | Component bytes | Complete deployment |\n")
                .append("|---|---|---:|---:|\n");
        for (VariantResult result : results) {
            DeploymentSize deploymentSize = result.variant().deploymentSize();
            if (deploymentSize == null || deploymentSize.components().isEmpty()) {
                out.append("| `").append(result.variant().name()).append("` | — | — | — |\n");
                continue;
            }
            for (DeploymentSize.Component component : deploymentSize.components()) {
                out.append("| `").append(result.variant().name()).append("` | ")
                        .append(component.name()).append(" | ").append(exactSize(component.bytes()))
                        .append(" | ").append(exactSize(deploymentSize.totalBytes())).append(" |\n");
            }
        }
        out.append('\n');
    }

    private static void appendPairedComparisons(StringBuilder out,
                                                RunContext context,
                                                List<VariantResult> results) {
        List<PairedComparison> comparisons = comparisons(context, results);
        if (comparisons.isEmpty()) {
            return;
        }
        out.append("## Paired readiness comparisons\n\n");
        out.append("The predeclared estimator is the median iteration-level readiness difference")
                .append(" (**left − right**). Only attempts from the same measured iteration form a pair;")
                .append(" incomplete iterations are excluded and listed rather than silently re-paired.\n\n");
        out.append("| Comparison | Pairs | Median difference | 95% paired interval | Excluded |\n")
                .append("|---|---:|---:|---|---:|\n");
        for (PairedComparison comparison : comparisons) {
            out.append("| `").append(comparison.leftVariant()).append("` − `")
                    .append(comparison.rightVariant()).append("` | ")
                    .append(comparison.pairedCount()).append(" complete / ")
                    .append(comparison.requestedPairs()).append(" requested | ")
                    .append(comparison.medianDifferenceMillis() == null
                            ? "—" : millis(comparison.medianDifferenceMillis()))
                    .append(" | ");
            if (comparison.descriptiveOnly()) {
                out.append("descriptive only (").append(comparison.pairedCount()).append('/')
                        .append(Statistics.MIN_CONFIDENCE_SAMPLES).append(" pairs)");
            } else {
                out.append(millis(comparison.ciLow())).append(" – ").append(millis(comparison.ciHigh()));
            }
            out.append(" | ").append(comparison.excludedCount()).append(" |\n");
        }
        out.append('\n');
        for (PairedComparison comparison : comparisons) {
            if (comparison.exclusions().isEmpty()) {
                continue;
            }
            out.append("- Excluded from `").append(comparison.leftVariant()).append("` − `")
                    .append(comparison.rightVariant()).append("`: ");
            for (int i = 0; i < comparison.exclusions().size(); i++) {
                PairedComparison.Exclusion exclusion = comparison.exclusions().get(i);
                out.append(i == 0 ? "" : "; ").append("iteration ").append(exclusion.iteration())
                        .append(" (").append(comparison.leftVariant()).append(": ")
                        .append(exclusion.leftOutcome()).append("; ").append(comparison.rightVariant())
                        .append(": ").append(exclusion.rightOutcome()).append(')');
            }
            out.append(".\n");
        }
        out.append("\nIntervals use ").append(Statistics.RESAMPLES)
                .append(" bootstrap resamples of complete iteration pairs and are emitted only with at")
                .append(" least ").append(Statistics.MIN_CONFIDENCE_SAMPLES)
                .append(" complete pairs. The threshold is a reporting policy, not a universal guarantee;")
                .append(" an interval does not by itself establish a startup speedup.\n\n");
    }

    /**
     * Names every variant the table above has no number for, whether it could not be built or was built
     * and never answered.
     *
     * <p>Both cases matter and they are different. A benchmark that quietly leaves a variant out reads as
     * though it measured everything it listed, so this section exists to make the hole impossible to
     * miss - and to say which kind of hole it is, because "the Shadow plugin produced no jar" and "the
     * application came up and returned 404" lead to completely different investigations.</p>
     *
     * @param out     the report being built
     * @param results every variant's result
     */
    private static void appendCountRow(StringBuilder out, String variant, String phase, PhaseCounts counts) {
        out.append("| `").append(variant).append("` | ").append(phase)
                .append(" | ").append(counts.requested())
                .append(" | ").append(counts.attempted())
                .append(" | ").append(counts.successful())
                .append(" | ").append(counts.failed())
                .append(" | ").append(counts.skipped()).append(" |\n");
    }

    private static void appendIncompleteDetails(StringBuilder out,
                                                RunContext context,
                                                List<VariantResult> results,
                                                BenchmarkStatus status) {
        if (status.complete()) {
            out.append("All ").append(context.requiredVariants().size())
                    .append(" required variants completed all ").append(context.iterations())
                    .append(" requested measured runs.\n\n");
            return;
        }
        out.append("## Incomplete matrix details\n\n");
        out.append("| Required variant | What happened | Detail |\n|---|---|---|\n");
        for (String required : context.requiredVariants()) {
            VariantResult result = results.stream()
                    .filter(candidate -> candidate.variant().name().equals(required))
                    .findFirst().orElse(null);
            String what;
            String detail;
            if (result == null) {
                what = "missing from results";
                detail = "no variant status was recorded";
            } else if (!result.variant().available()) {
                what = "could not be built";
                detail = result.variant().unavailableReason();
            } else if (result.measured().successful() < context.iterations()) {
                what = result.measured().successful() + " of " + context.iterations()
                        + " measured runs succeeded";
                detail = result.failures().isEmpty() ? "measured cells were skipped"
                        : result.failures().get(0);
            } else {
                continue;
            }
            out.append("| `").append(required).append("` | ").append(what).append(" | ")
                    .append(escapeCell(reason(redact(context, result == null ? null : result.variant(), detail))))
                    .append(" |\n");
        }
        out.append('\n');
    }

    /**
     * Trims the captured process output off the end of a failure message. The full text, tail included,
     * is in {@code results.json}; a table cell wants the sentence that names the cause.
     *
     * @param message the failure message
     * @return the message without the appended output tail
     */
    private static String reason(String message) {
        if (message == null) {
            return null;
        }
        int tail = message.indexOf("--- last ");
        return tail < 0 ? message : message.substring(0, tail).trim();
    }

    private static String millis(double value) {
        return String.format(Locale.ROOT, "%.1f ms", value);
    }

    private static String confidenceInterval(Statistics statistics) {
        if (!statistics.hasConfidenceInterval()) {
            return "descriptive only (" + statistics.count() + "/" + Statistics.MIN_CONFIDENCE_SAMPLES
                    + " samples; at least " + Statistics.MIN_CONFIDENCE_SAMPLES
                    + " successful measured samples required)";
        }
        return millis(statistics.ciLow()) + " – " + millis(statistics.ciHigh());
    }

    private static String exactSize(long bytes) {
        return bytes < 0 ? "—" : bytes + " B";
    }

    private static String size(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String nullableNumber(Double value) {
        return value == null ? "null" : number(value);
    }

    private static String escapeCell(String value) {
        if (value == null) {
            return "—";
        }
        String single = value.replace('\n', ' ').replace('\r', ' ').replace("|", "\\|");
        return single.length() > 400 ? single.substring(0, 400) + " …" : single;
    }

    private static String redact(RunContext context, Variant variant, String value) {
        if (value == null) {
            return null;
        }
        String redacted = value.replace(context.repository(), "<redacted:repository-location>")
                .replace(context.sample().toAbsolutePath().normalize().toString(), "${sample}")
                .replace(context.outputDirectory().toAbsolutePath().normalize().toString(), "${output}");
        String home = System.getProperty("user.home", "");
        if (!home.isEmpty()) {
            redacted = redacted.replace(home, "${user-home-redacted}");
        }
        if (variant != null) {
            if (variant.workingDirectory() != null) {
                redacted = redacted.replace(variant.workingDirectory().toAbsolutePath().normalize().toString(),
                        "${workdir}");
            }
            for (int i = 0; i < variant.launchInputs().size(); i++) {
                redacted = redacted.replace(variant.launchInputs().get(i).toAbsolutePath().normalize().toString(),
                        "${input:" + i + "}");
            }
        }
        return redacted;
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
