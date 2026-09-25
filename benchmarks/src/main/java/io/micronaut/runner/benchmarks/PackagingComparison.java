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

import io.micronaut.runner.benchmarks.SampleBuild.ComparisonSpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Times Runner and Shadow packaging of a copy of benchmark-large in one warm, dedicated Gradle daemon. Each round
 * runs an untimed {@code classes} settle, the {@code rerun} block ({@code <task> --rerun}: the cold packaging path)
 * and the {@code edit} block (a bytecode-changing edit, then {@code <task>}), each in a fresh seeded order. Task
 * time comes from the init script's {@value #MARKER} line, wall time spans {@code gradlew}; pairs match by round.
 */
public final class PackagingComparison {

    static final String MARKER = "PACKAGING_TASK";
    static final List<String> VARIANTS = List.of("runner-stored", "runner-preserve", "shadow", "shadow-stored");
    static final List<String> SCENARIOS = List.of("rerun", "edit");
    static final List<ComparisonSpec> COMPARISONS = List.of(
            new ComparisonSpec("runner-stored", "shadow", "Defaults"),
            new ComparisonSpec("runner-stored", "shadow-stored", "Compression-matched, neither deflated"),
            new ComparisonSpec("runner-preserve", "shadow", "Compression-matched, both deflated"));
    static final int WARMUP_ROUNDS = 3;
    private static final String CATALOG = "'../../../gradle/libs.versions.toml'";
    private static final String ANCHOR = "public final class Application {";

    private PackagingComparison() {
    }

    /** One timed invocation; {@code round} counts the warm-up rounds too. */
    record Attempt(int round, boolean warmup, String scenario, String variant,
                   double taskMillis, double wallMillis, long archiveBytes) {
    }

    /** What the init script printed for the requested task. */
    record Marker(double millis, Path archive) {
    }

    /**
     * Entry point of the {@code packagingComparison} task: {@code --sample --repo --version --out --work}, and
     * optionally {@code --iterations --seed}. Fails if a nested build fails or prints no marker.
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        Path sample = Path.of(options.get("--sample")).toAbsolutePath().normalize();
        Path out = Files.createDirectories(Path.of(options.get("--out")));
        Path work = Path.of(options.get("--work")).toAbsolutePath().normalize();
        int iterations = Integer.parseInt(options.getOrDefault("--iterations", "10"));
        long seed = Long.parseLong(options.getOrDefault("--seed", "20260921"));
        if (iterations < 1) {
            throw new IllegalArgumentException("--iterations must be at least 1");
        }
        Path root = Path.of(System.getProperty("runner.benchmark.sourceRoot", sample.resolve("../../..").toString()))
                .toAbsolutePath().normalize();
        Files.deleteIfExists(out.resolve("summary.md"));
        Files.deleteIfExists(out.resolve("results.json"));
        Path copy = copySample(sample, work.resolve("sample"), root.resolve("gradle/libs.versions.toml"));
        Path init = work.resolve("packaging-comparison.init.gradle");
        try (InputStream in = PackagingComparison.class.getResourceAsStream(init.getFileName().toString())) {
            Files.copy(Objects.requireNonNull(in, "init script resource"), init, StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> common = List.of("--project-dir", copy.toString(), "-Prunner.repo=" + options.get("--repo"),
                "-Prunner.version=" + options.get("--version"), "--init-script", init.toString(),
                "--no-build-cache", "--no-configuration-cache", "--console=plain", "--daemon",
                // A JVM option unique to this checkout keeps the harness off every other daemon, and ps finds it.
                "-Dorg.gradle.jvmargs=-Xmx1g \"-XX:ErrorFile=" + work.resolve("hs_err_pid%p.log") + "\"",
                "-Dorg.gradle.java.home=" + System.getProperty("java.home"), "-Dorg.gradle.daemon.idletimeout=60000");
        // A missing anchor leaves the task UP-TO-DATE, which fails the marker check; a repeated one fails javac.
        Path source = copy.resolve("src/main/java/com/example/Application.java");
        String original = Files.readString(source);
        Random random = new Random(seed);
        List<Attempt> attempts = new ArrayList<>();
        int invocation = 0;
        for (int round = 0; round < WARMUP_ROUNDS + iterations; round++) {
            // Untimed settle: resolves and compiles on the first round, then recompiles the restored source.
            run(copy, common, List.of("classes"));
            for (String scenario : SCENARIOS) {
                boolean edit = scenario.equals("edit");
                List<String> order = new ArrayList<>(VARIANTS);
                Collections.shuffle(order, random);
                for (String variant : order) {
                    int mark = ++invocation;
                    if (edit) {
                        Files.writeString(source, original.replace(ANCHOR, ANCHOR
                                + "\n    static final String PACKAGING_COMPARISON_MARK = \"" + mark + "\";"));
                    }
                    try {
                        long start = System.nanoTime();
                        String output = run(copy, common, arguments(variant, !edit));
                        double wall = (System.nanoTime() - start) / 1e6;
                        Marker marker = marker(output, ":" + task(variant));
                        Attempt attempt = new Attempt(round, round < WARMUP_ROUNDS, scenario, variant, marker.millis(),
                                wall, Files.size(marker.archive()));
                        attempts.add(attempt);
                        System.out.println("[packaging-comparison] " + attempt);
                    } finally {
                        if (edit) {
                            Files.writeString(source, original);
                        }
                    }
                }
            }
        }
        // Gzip is never measured inside a timed invocation, and both Runner modes share one task's archive.
        Map<String, DeploymentSize> sizes = new LinkedHashMap<>();
        for (String variant : VARIANTS) {
            Path archive = marker(run(copy, common, arguments(variant, true)), ":" + task(variant)).archive();
            sizes.put(variant, DeploymentSize.measure(DeploymentSize.input("archive", archive)));
        }
        BenchmarkProvenance machine = BenchmarkProvenance.capture(root, sample, Map.of());
        BenchmarkProvenance.SourceState state = machine.runnerSource();
        String os = machine.osName() + " " + machine.osVersion();
        Files.writeString(out.resolve("summary.md"), summary(String.format(Locale.ROOT, "Source `%s` (%s) on %s / %s,"
                + " %d CPUs, JDK %s (%s). Seed %d; %d warm-up and %d measured rounds.", state.revision(), state.state(),
                os, machine.osArch(), machine.availableProcessors(), machine.javaRuntimeVersion(), machine.javaVendor(),
                seed, WARMUP_ROUNDS, iterations), attempts, sizes, seed));
        List<String> records = attempts.stream().map(a -> String.format(Locale.ROOT, "    {\"round\": %d, \"warmup\":"
                + " %b, \"scenario\": \"%s\", \"variant\": \"%s\", \"taskMillis\": %.3f, \"wallMillis\": %.3f,"
                + " \"archiveBytes\": %d}", a.round(), a.warmup(), a.scenario(), a.variant(), a.taskMillis(),
                a.wallMillis(), a.archiveBytes())).toList();
        List<String> archives = sizes.entrySet().stream().map(e -> String.format(Locale.ROOT, "    \"%s\":"
                + " {\"rawBytes\": %d, \"gzipBytes\": %d}", e.getKey(), e.getValue().totalBytes(),
                e.getValue().totalGzipBytes())).toList();
        Files.writeString(out.resolve("results.json"), String.format(Locale.ROOT, "{\n  \"schemaVersion\": 1,\n"
                + "  \"generatedAt\": \"%s\",\n  \"runnerSource\": {\"revision\": \"%s\", \"state\": \"%s\"},\n"
                + "  \"environment\": {\"javaRuntimeVersion\": \"%s\", \"javaVendor\": \"%s\", \"os\": \"%s\","
                + " \"osArch\": \"%s\", \"availableProcessors\": %d, \"totalMemoryBytes\": %d},\n  \"sample\":"
                + " \"benchmark-large\",\n  \"seed\": %d,\n  \"warmupRounds\": %d,\n  \"measuredRounds\": %d,\n"
                + "  \"attempts\": [\n%s\n  ],\n  \"sizes\": {\n%s\n  }\n}\n", Instant.now(), state.revision(),
                state.state(), machine.javaRuntimeVersion(), machine.javaVendor(), os, machine.osArch(),
                machine.availableProcessors(), machine.totalMemoryBytes(), seed, WARMUP_ROUNDS, iterations,
                String.join(",\n", records), String.join(",\n", archives)));
    }

    /** Copies the sample without its build state and points the copy at the repository's version catalog. */
    static Path copySample(Path sample, Path copy, Path catalog) throws IOException {
        SampleBuild.recreate(copy);
        try (var paths = Files.walk(sample)) {
            for (Path path : paths.filter(p -> !p.equals(sample)).sorted().toList()) {
                Path relative = sample.relativize(path);
                if (!List.of("build", ".gradle").contains(relative.getName(0).toString())) {
                    Files.copy(path, copy.resolve(relative.toString()));
                }
            }
        }
        Path settings = copy.resolve("settings.gradle");
        String text = Files.readString(settings);
        if (text.indexOf(CATALOG) < 0 || text.indexOf(CATALOG) != text.lastIndexOf(CATALOG)) {
            throw new IOException(settings + " must contain " + CATALOG + " exactly once");
        }
        String absolute = "'" + catalog.toAbsolutePath().normalize().toString().replace('\\', '/') + "'";
        Files.writeString(settings, text.replace(CATALOG, absolute));
        return copy;
    }

    static String task(String variant) {
        return variant.startsWith("runner-") ? "micronautRunnerJar"
                : variant.equals("shadow") ? "shadowJar" : "shadowJarStored";
    }

    private static List<String> arguments(String variant, boolean rerun) {
        String compression = variant.startsWith("runner-")
                ? "-PpackagingComparison.compression=" + variant.substring(7).toUpperCase(Locale.ROOT) : null;
        return Stream.of(compression, task(variant), rerun ? "--rerun" : null).filter(Objects::nonNull).toList();
    }

    private static String run(Path copy, List<String> common, List<String> tasks) throws Exception {
        List<String> arguments = new ArrayList<>(common);
        arguments.addAll(tasks);
        SampleBuild.GradleResult result = SampleBuild.gradle(copy, arguments, Duration.ofMinutes(30));
        if (result.exitCode() != 0) {
            throw new IOException("gradlew " + tasks + " failed with status " + result.exitCode() + result.tail());
        }
        return result.output();
    }

    /** Reads the requested task's marker: a task that did not execute printed none, which is a failure. */
    static Marker marker(String output, String taskPath) throws IOException {
        String prefix = MARKER + " " + taskPath + " ";
        for (String line : output.lines().toList().reversed()) {
            int space = line.indexOf(' ', prefix.length());
            if (line.startsWith(prefix) && space > 0) {
                return new Marker(Double.parseDouble(line.substring(prefix.length(), space)),
                        Path.of(line.substring(space + 1)));
            }
        }
        throw new IOException("No " + MARKER + " line for " + taskPath + "; did it run UP-TO-DATE or FROM-CACHE?"
                + new SampleBuild.GradleResult(0, output).tail());
    }

    /** Measured attempts of one scenario and variant, keyed by round; warm-ups are left out. */
    static Map<Integer, Attempt> measured(List<Attempt> attempts, String scenario, String variant) {
        return attempts.stream()
                .filter(a -> !a.warmup() && a.scenario().equals(scenario) && a.variant().equals(variant))
                .collect(Collectors.toMap(Attempt::round, a -> a, (a, b) -> b, TreeMap::new));
    }

    static Statistics variant(List<Attempt> attempts, String scenario, String variant,
                              ToDoubleFunction<Attempt> metric, long seed) {
        return Statistics.of(measured(attempts, scenario, variant).values().stream().mapToDouble(metric).toArray(),
                seed);
    }

    /** Candidate − baseline, one difference per round in which both were measured. */
    static Statistics comparison(List<Attempt> attempts, String scenario, ComparisonSpec spec,
                                 ToDoubleFunction<Attempt> metric, long seed) {
        Map<Integer, Attempt> candidate = measured(attempts, scenario, spec.candidate());
        Map<Integer, Attempt> baseline = measured(attempts, scenario, spec.baseline());
        return Statistics.of(candidate.keySet().stream().filter(baseline::containsKey).mapToDouble(round ->
                metric.applyAsDouble(candidate.get(round)) - metric.applyAsDouble(baseline.get(round))).toArray(),
                seed);
    }

    static String summary(String header, List<Attempt> attempts, Map<String, DeploymentSize> sizes, long seed) {
        StringBuilder out = new StringBuilder("# Packaging comparison: Runner vs Shadow on benchmark-large\n\n")
                .append(header).append(" One warm, dedicated Gradle daemon; variant order shuffled per round and"
                + " scenario.\n\n- `rerun`: `<task> --rerun` re-executes only the packaging task. `micronautRunnerJar`"
                + " keeps no packaging state between executions, so this is the cold packaging path; there is no"
                + " separate cold scenario.\n- `edit`: a bytecode-changing edit of `Application.java`, then `<task>`.\n"
                + "- Task: first to last action of the packaging task. Wall: the whole `gradlew` process. Sizes: one"
                + " untimed `--rerun` per variant.\n- Compression-matched: `runner-stored` − `shadow-stored` (neither"
                + " archive deflated) and `runner-preserve` − `shadow` (both deflated).\n");
        for (String scenario : SCENARIOS) {
            out.append("\n## `").append(scenario).append("`\n\n| Variant | n | Task ms, median (min–max) | Wall ms,"
                    + " median (min–max) | Raw bytes | Raw / `shadow` | gzip -6 bytes | gzip / `shadow` |\n"
                    + "|---|---:|---:|---:|---:|---:|---:|---:|\n");
            DeploymentSize shadow = sizes.get("shadow");
            for (String name : VARIANTS) {
                Statistics task = variant(attempts, scenario, name, Attempt::taskMillis, seed);
                Statistics wall = variant(attempts, scenario, name, Attempt::wallMillis, seed);
                DeploymentSize size = sizes.get(name);
                out.append(String.format(Locale.ROOT, "| `%s` | %d | %.1f (%.1f–%.1f) | %.0f (%.0f–%.0f) | %,d |"
                        + " %.2f | %,d | %.2f |\n", name, task.count(), task.median(), task.min(), task.max(),
                        wall.median(), wall.min(), wall.max(), size.totalBytes(),
                        size.totalBytes() / (double) shadow.totalBytes(), size.totalGzipBytes(),
                        size.totalGzipBytes() / (double) shadow.totalGzipBytes()));
            }
            out.append("\n| Comparison, candidate − baseline | Pairs | Task Δ ms, median [95% CI] (min to max) |"
                    + " Wall Δ ms, median [95% CI] (min to max) |\n|---|---:|---:|---:|\n");
            for (ComparisonSpec spec : COMPARISONS) {
                Statistics task = comparison(attempts, scenario, spec, Attempt::taskMillis, seed);
                Statistics wall = comparison(attempts, scenario, spec, Attempt::wallMillis, seed);
                out.append(String.format(Locale.ROOT, "| %s: `%s` − `%s` | %d | %s | %s |\n", spec.label(),
                        spec.candidate(), spec.baseline(), task.count(), difference(task), difference(wall)));
            }
        }
        return out.toString();
    }

    private static String difference(Statistics s) {
        String interval = s.hasConfidenceInterval()
                ? String.format(Locale.ROOT, "[%+.1f, %+.1f]", s.ciLow(), s.ciHigh()) : "[none: " + s.ciReason() + "]";
        return String.format(Locale.ROOT, "**%+.1f** %s (%+.1f to %+.1f)", s.median(), interval, s.min(), s.max());
    }
}
