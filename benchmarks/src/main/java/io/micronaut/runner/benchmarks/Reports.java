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
 * library, and adding a JSON library to a benchmark harness so that it can emit an object with six fields
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
                               List<StartupHarness.ClassLoadCount> diagnostics) {
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append("{\n");
        out.append("  \"generatedAt\": ").append(quote(context.generatedAt())).append(",\n");
        out.append("  \"sample\": ").append(quote(context.sample().toString())).append(",\n");
        out.append("  \"repository\": ").append(quote(context.repository())).append(",\n");
        out.append("  \"runnerVersion\": ").append(quote(context.runnerVersion())).append(",\n");
        out.append("  \"readinessPath\": ").append(quote(context.readinessPath())).append(",\n");
        out.append("  \"measuredIterations\": ").append(context.iterations()).append(",\n");
        out.append("  \"warmupIterations\": ").append(context.warmupIterations()).append(",\n");
        out.append("  \"seed\": ").append(context.seed()).append(",\n");
        out.append("  \"interleaved\": true,\n");
        out.append("  \"timingRunsCarryLoggingFlags\": false,\n");
        out.append("  \"jvmProcessState\": \"fresh per sample\",\n");
        out.append("  \"osPageCacheState\": \"uncontrolled\",\n");
        out.append("  \"applicationCacheMode\": \"none\",\n");
        out.append("  \"environment\": {\n");
        out.append("    \"javaVersion\": ").append(quote(System.getProperty("java.version"))).append(",\n");
        out.append("    \"javaVendor\": ").append(quote(System.getProperty("java.vendor"))).append(",\n");
        out.append("    \"javaHome\": ").append(quote(System.getProperty("java.home"))).append(",\n");
        out.append("    \"osName\": ").append(quote(System.getProperty("os.name"))).append(",\n");
        out.append("    \"osVersion\": ").append(quote(System.getProperty("os.version"))).append(",\n");
        out.append("    \"osArch\": ").append(quote(System.getProperty("os.arch"))).append(",\n");
        out.append("    \"availableProcessors\": ").append(Runtime.getRuntime().availableProcessors())
                .append('\n');
        out.append("  },\n");
        out.append("  \"variants\": [\n");
        for (int i = 0; i < results.size(); i++) {
            appendVariant(out, results.get(i));
            out.append(i == results.size() - 1 ? "\n" : ",\n");
        }
        out.append("  ],\n");
        out.append("  \"diagnostics\": {\n");
        out.append("    \"comment\": \"Separate runs carrying -Xlog:class+load. NOT timing runs and not")
                .append(" comparable with the readiness numbers above.\",\n");
        out.append("    \"runs\": [\n");
        for (int i = 0; i < diagnostics.size(); i++) {
            StartupHarness.ClassLoadCount count = diagnostics.get(i);
            out.append("      {\"variant\": ").append(quote(count.variant()))
                    .append(", \"classesLoaded\": ").append(count.classesLoaded())
                    .append(", \"fromSharedArchive\": ").append(count.fromSharedArchive())
                    .append(", \"readinessMillisWithLogging\": ").append(number(count.readinessMillis()))
                    .append('}').append(i == diagnostics.size() - 1 ? "\n" : ",\n");
        }
        out.append("    ]\n");
        out.append("  }\n");
        out.append("}\n");
        return out.toString();
    }

    private static void appendVariant(StringBuilder out, VariantResult result) {
        Variant variant = result.variant();
        out.append("    {\n");
        out.append("      \"name\": ").append(quote(variant.name())).append(",\n");
        out.append("      \"description\": ").append(quote(variant.description())).append(",\n");
        out.append("      \"available\": ").append(variant.available()).append(",\n");
        out.append("      \"unavailableReason\": ").append(quote(variant.unavailableReason())).append(",\n");
        out.append("      \"artifact\": ")
                .append(quote(variant.artifact() == null ? null : variant.artifact().toString())).append(",\n");
        out.append("      \"artifactBytes\": ").append(result.sizeBytes()).append(",\n");
        out.append("      \"command\": [");
        for (int i = 0; i < variant.command().size(); i++) {
            out.append(i == 0 ? "" : ", ").append(quote(variant.command().get(i)));
        }
        out.append("],\n");
        out.append("      \"readiness\": ").append(statistics(result.readiness())).append(",\n");
        out.append("      \"logLine\": ").append(statistics(result.logLine())).append(",\n");
        out.append("      \"framework\": ").append(statistics(result.framework())).append(",\n");
        out.append("      \"failures\": [");
        for (int i = 0; i < result.failures().size(); i++) {
            out.append(i == 0 ? "" : ", ").append(quote(result.failures().get(i)));
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
                + ", \"ci95Low\": " + number(statistics.ciLow())
                + ", \"ci95High\": " + number(statistics.ciHigh())
                + ", \"ciMethod\": \"percentile bootstrap of the median, "
                + Statistics.RESAMPLES + " resamples\"}";
    }

    private static String markdown(RunContext context,
                                   List<VariantResult> results,
                                   List<StartupHarness.ClassLoadCount> diagnostics) {
        StringBuilder out = new StringBuilder(8 * 1024);
        out.append("# Startup benchmark\n\n");
        out.append("Time from process spawn to the first successful HTTP response, for the same Micronaut")
                .append(" application packaged six ways.\n\n");
        out.append("- **Sample**: `").append(context.sample()).append("`\n");
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

        out.append("| Variant | Runs | Readiness, median | p90 | 95% CI of median | Min | Max |")
                .append(" To startup line, median | Framework's own figure | Artifact |\n");
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
                    .append(millis(readiness.ciLow())).append(" – ").append(millis(readiness.ciHigh()))
                    .append(" | ").append(millis(readiness.min()))
                    .append(" | ").append(millis(readiness.max()))
                    .append(" | ")
                    .append(result.logLine() == null ? "not seen" : millis(result.logLine().median()))
                    .append(" | ")
                    .append(result.framework() == null ? "not seen" : millis(result.framework().median()))
                    .append(" | ").append(size(result.sizeBytes())).append(" |\n");
        }
        out.append('\n');

        appendNotMeasured(out, results);

        out.append("## What each variant is\n\n");
        out.append("| Variant | Packaging |\n|---|---|\n");
        for (VariantResult result : results) {
            out.append("| `").append(result.variant().name()).append("` | ")
                    .append(result.variant().description()).append(" |\n");
        }
        out.append('\n');

        if (!diagnostics.isEmpty()) {
            out.append("## Diagnostic runs — NOT timing runs\n\n");
            out.append("These runs carry `-Xlog:class+load=info`, which costs milliseconds and costs them")
                    .append(" unevenly. Their times are here only so the size of that penalty is visible;")
                    .append(" they must never be compared with the table above.\n\n");
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
        out.append("- All six variants are built from **one** compilation of the sample and **one**")
                .append(" dependency resolution, so any difference is a difference in packaging.\n");
        out.append("- The median and the 90th percentile are reported instead of a mean and a standard")
                .append(" deviation because process start times have a hard floor and a long right tail.\n");
        out.append("- The interval is a percentile bootstrap of the median (")
                .append(Statistics.RESAMPLES)
                .append(" resamples). With few iterations it comes out wide, which is the honest answer:")
                .append(" two overlapping intervals are not a result.\n");
        out.append("- **Readiness**, **to startup line** and **the framework's own figure** are three")
                .append(" different quantities and the gaps between them are informative. Readiness")
                .append(" includes serving the first request, which on a cold JVM is not free. The startup")
                .append(" line is this process observing the child's console on the same clock, which is")
                .append(" what a careful measurement by hand produces. The framework's figure is the")
                .append(" application counting itself, starting well after the JVM did: the smallest of the")
                .append(" three and the only one that is not an external observation.\n");
        out.append("- A variant marked **not measured** either could not be built or never answered. Look")
                .append(" at the sections above before reading the remaining rows as a complete")
                .append(" comparison.\n");
        out.append("- Every raw sample, warm-up runs included, is in `").append(RESULTS_FILE).append("`.\n");
        return out.toString();
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
    private static void appendNotMeasured(StringBuilder out, List<VariantResult> results) {
        List<VariantResult> missing = results.stream()
                .filter(result -> result.readiness() == null)
                .toList();
        if (missing.isEmpty()) {
            out.append("All ").append(results.size()).append(" variants were built and measured.\n\n");
            return;
        }
        out.append("## Variants that were NOT measured\n\n");
        out.append("**").append(missing.size()).append(" of ").append(results.size())
                .append(" variants produced no measurement**, so the table above is not a complete")
                .append(" comparison.\n\n");
        out.append("| Variant | What happened | Detail |\n|---|---|---|\n");
        for (VariantResult result : missing) {
            String what;
            String detail;
            if (!result.variant().available()) {
                what = "could not be built";
                detail = result.variant().unavailableReason();
            } else if (!result.failures().isEmpty()) {
                what = "built, but " + result.failures().size()
                        + (result.failures().size() == 1 ? " run never answered" : " runs never answered");
                detail = result.failures().get(0);
            } else {
                what = "built, but never started";
                detail = null;
            }
            out.append("| `").append(result.variant().name()).append("` | ").append(what).append(" | ")
                    .append(escapeCell(reason(detail))).append(" |\n");
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

    private static String escapeCell(String value) {
        if (value == null) {
            return "—";
        }
        String single = value.replace('\n', ' ').replace('\r', ' ').replace("|", "\\|");
        return single.length() > 400 ? single.substring(0, 400) + " …" : single;
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
