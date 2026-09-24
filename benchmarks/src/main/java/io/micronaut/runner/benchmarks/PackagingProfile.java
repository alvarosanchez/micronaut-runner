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

import io.micronaut.runner.build.BuildLogger;
import io.micronaut.runner.build.Compression;
import io.micronaut.runner.build.Dependency;
import io.micronaut.runner.build.RunnerJarBuilder;
import io.micronaut.runner.build.RunnerJarSpec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Profiles clean and edit-cycle packaging over deterministic input shapes.
 *
 * <p>Every record combines two independent invocations over byte-identical copied inputs: an uninstrumented
 * wall-time/allocation attempt and a diagnostic attempt sampled for process RSS and JVM pool peak usage.
 * Diagnostic elapsed time is deliberately not reported as packaging timing. OS page-cache state is
 * uncontrolled and explicitly labelled as such.</p>
 */
public final class PackagingProfile {

    static final String RESULTS_FILE = "packaging-results.json";
    static final String SUMMARY_FILE = "packaging-summary.md";
    private static final List<String> SCENARIOS = List.of(
            "first-build", "unchanged-rebuild", "application-edit", "dependency-edit");

    private PackagingProfile() {
    }

    /**
     * CLI entry point used by the Gradle {@code packagingProfile} task.
     *
     * @param args workload, iteration and output options
     * @throws Exception if fixture construction or a packaging worker fails
     */
    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        run(options.workloads(), options.iterations(), options.output());
    }

    /** Executes the requested shapes and writes raw JSON plus a human-readable summary. */
    static Report run(List<String> workloadNames, int iterations, Path output) throws Exception {
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be at least 1");
        }
        Files.createDirectories(output);
        List<Attempt> attempts = new ArrayList<>();
        for (String workloadName : workloadNames) {
            WorkloadShape shape = WorkloadShape.named(workloadName);
            SyntheticArchive archive = SyntheticArchive.forWorkload(shape.name());
            for (Compression compression : List.of(Compression.STORED, Compression.PRESERVE)) {
                Path modeRoot = output.resolve("work").resolve(shape.name())
                        .resolve(compression.name().toLowerCase(Locale.ROOT));
                Inputs timing = Inputs.copy(archive, modeRoot.resolve("timing"));
                Inputs diagnostic = Inputs.copy(archive, modeRoot.resolve("diagnostic"));
                for (String scenario : SCENARIOS) {
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        timing.prepare(scenario, iteration);
                        diagnostic.prepare(scenario, iteration);
                        Measurement measured = measure(timing, compression, false);
                        Measurement profiled = measure(diagnostic, compression, true);
                        attempts.add(new Attempt(shape.name(), compression.name().toLowerCase(Locale.ROOT),
                                scenario, iteration, measured.elapsedNanos(), measured.allocatedBytes(),
                                profiled.peakHeapBytes(), profiled.peakRssBytes(), timing.inputBytes(),
                                measured.outputBytes(), measured.outputSha256(), "uncontrolled",
                                profiled.peakRssBytes() < 0 ? "unsupported" : "ps-rss-sampled",
                                "JVM memory-pool peak diagnostic invocation"));
                    }
                }
            }
        }
        Path sourceRoot = configuredSourceRoot();
        BenchmarkProvenance.SourceState source = sourceRoot == null
                ? BenchmarkProvenance.SourceState.unavailable()
                : BenchmarkProvenance.SourceState.captureRepository(sourceRoot);
        Report report = new Report(3, Instant.now().toString(), source.revision(), source.state(),
                System.getProperty("java.runtime.version"), System.getProperty("os.name"),
                System.getProperty("os.version"), System.getProperty("os.arch"), List.copyOf(attempts));
        Files.writeString(output.resolve(RESULTS_FILE), json(report), StandardCharsets.UTF_8);
        Files.writeString(output.resolve(SUMMARY_FILE), markdown(report), StandardCharsets.UTF_8);
        return report;
    }

    private static Measurement measure(Inputs inputs, Compression compression, boolean diagnostic)
            throws Exception {
        if (inputs.deleteOutputBeforeRun()) {
            Files.deleteIfExists(inputs.output());
        }
        List<String> command = new ArrayList<>();
        command.add(SampleBuild.javaExecutable().toString());
        command.add("-Xms128m");
        command.add("-Xmx512m");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Worker.class.getName());
        command.add(inputs.application().toAbsolutePath().toString());
        command.add(inputs.output().toAbsolutePath().toString());
        command.add(compression.name());
        inputs.dependencies().forEach(path -> command.add(path.toAbsolutePath().toString()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        StartupHarness.removeInheritedJvmOptions(builder);
        Process process = builder.start();
        RssSampler sampler = diagnostic ? RssSampler.start(process.pid()) : null;
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exit = process.waitFor();
        if (sampler != null) {
            sampler.close();
        }
        if (exit != 0) {
            throw new IOException("packaging worker exited " + exit + ": " + output);
        }
        String[] fields = output.split("\\t");
        if (fields.length != 5) {
            throw new IOException("packaging worker returned malformed metrics: " + output);
        }
        return new Measurement(Long.parseLong(fields[0]), Long.parseLong(fields[1]),
                Long.parseLong(fields[2]), sampler == null ? -1 : sampler.peakBytes(),
                Long.parseLong(fields[3]), fields[4]);
    }

    private static long allocatedBytes() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean extended
                && extended.isThreadAllocatedMemorySupported()) {
            if (!extended.isThreadAllocatedMemoryEnabled()) {
                extended.setThreadAllocatedMemoryEnabled(true);
            }
            return extended.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
        return -1;
    }

    private static Path configuredSourceRoot() {
        String value = System.getProperty("runner.benchmark.sourceRoot");
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static String json(Report report) {
        StringBuilder out = new StringBuilder(32 * 1024);
        out.append("{\n  \"schemaVersion\": ").append(report.schemaVersion())
                .append(",\n  \"generatedAt\": ").append(quote(report.generatedAt()))
                .append(",\n  \"runnerSource\": {\"revision\": ").append(quote(report.revision()))
                .append(", \"state\": ").append(quote(report.sourceState())).append("},")
                .append("\n  \"environment\": {\"javaRuntimeVersion\": ")
                .append(quote(report.javaRuntimeVersion())).append(", \"osName\": ")
                .append(quote(report.osName())).append(", \"osVersion\": ")
                .append(quote(report.osVersion())).append(", \"osArch\": ")
                .append(quote(report.osArch())).append("},")
                .append("\n  \"timingDiagnosticSeparation\": ")
                .append(quote("elapsed/allocation and RSS/peak-heap come from independent invocations"))
                .append(",\n  \"attempts\": [\n");
        for (int i = 0; i < report.attempts().size(); i++) {
            Attempt a = report.attempts().get(i);
            out.append("    {\"workload\": ").append(quote(a.workload()))
                    .append(", \"compression\": ").append(quote(a.compression()))
                    .append(", \"scenario\": ").append(quote(a.scenario()))
                    .append(", \"iteration\": ").append(a.iteration())
                    .append(", \"elapsedNanos\": ").append(a.elapsedNanos())
                    .append(", \"allocatedBytes\": ").append(nullable(a.allocatedBytes()))
                    .append(", \"peakHeapBytes\": ").append(a.peakHeapBytes())
                    .append(", \"peakRssBytes\": ").append(nullable(a.peakRssBytes()))
                    .append(", \"inputBytes\": ").append(a.inputBytes())
                    .append(", \"outputBytes\": ").append(a.outputBytes())
                    .append(", \"outputSha256\": ").append(quote(a.outputSha256()))
                    .append(", \"osPageCacheState\": ").append(quote(a.osPageCacheState()))
                    .append(", \"rssMethod\": ").append(quote(a.rssMethod()))
                    .append(", \"heapMethod\": ").append(quote(a.heapMethod())).append('}')
                    .append(i + 1 == report.attempts().size() ? "\n" : ",\n");
        }
        return out.append("  ]\n}\n").toString();
    }

    private static String markdown(Report report) {
        StringBuilder out = new StringBuilder("# Packaging profile\n\n")
                .append("Source `").append(report.revision()).append("` (").append(report.sourceState())
                .append(") on ").append(report.osName()).append(' ').append(report.osVersion())
                .append(" / ").append(report.osArch()).append(" / JDK ")
                .append(report.javaRuntimeVersion()).append(".\n\n")
                .append("OS page-cache state is **uncontrolled**. Elapsed/allocation samples are independent")
                .append(" from RSS/peak-heap diagnostic invocations; diagnostic elapsed time is not reported.")
                .append(" Input/output columns are logical file bytes, not filesystem-block or kernel I/O counters.\n\n")
                .append("| Workload | Compression | Scenario | Iteration | Elapsed ms | Allocated bytes |")
                .append(" Peak heap bytes | Peak RSS bytes | Input bytes | Output bytes |\n")
                .append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Attempt a : report.attempts()) {
            out.append("| ").append(a.workload()).append(" | ").append(a.compression())
                    .append(" | ").append(a.scenario()).append(" | ").append(a.iteration())
                    .append(" | ").append(String.format(Locale.ROOT, "%.3f", a.elapsedNanos() / 1_000_000.0))
                    .append(" | ").append(a.allocatedBytes() < 0 ? "unsupported" : a.allocatedBytes())
                    .append(" | ").append(a.peakHeapBytes())
                    .append(" | ").append(a.peakRssBytes() < 0 ? "unsupported" : a.peakRssBytes())
                    .append(" | ").append(a.inputBytes()).append(" | ").append(a.outputBytes()).append(" |\n");
        }
        return out.toString();
    }

    private static String nullable(long value) {
        return value < 0 ? "null" : Long.toString(value);
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + '"';
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    /** Raw report; every attempt remains available for independent analysis. */
    record Report(int schemaVersion,
                  String generatedAt,
                  String revision,
                  String sourceState,
                  String javaRuntimeVersion,
                  String osName,
                  String osVersion,
                  String osArch,
                  List<Attempt> attempts) {
    }

    /** One uninstrumented timing attempt paired with one independent memory diagnostic invocation. */
    record Attempt(String workload,
                   String compression,
                   String scenario,
                   int iteration,
                   long elapsedNanos,
                   long allocatedBytes,
                   long peakHeapBytes,
                   long peakRssBytes,
                   long inputBytes,
                   long outputBytes,
                   String outputSha256,
                   String osPageCacheState,
                   String rssMethod,
                   String heapMethod) {
    }

    private record Measurement(long elapsedNanos,
                               long allocatedBytes,
                               long peakHeapBytes,
                               long peakRssBytes,
                               long outputBytes,
                               String outputSha256) {
    }

    /** Fresh-JVM worker so every attempt has independent allocation and peak-heap state. */
    public static final class Worker {
        private Worker() {
        }

        /**
         * Builds one archive and emits tab-separated raw metrics for the parent process.
         *
         * @param args application, output, compression and dependency paths
         * @throws Exception if packaging or metric collection fails
         */
        public static void main(String[] args) throws Exception {
            if (args.length < 4) {
                throw new IllegalArgumentException("application output, destination, compression and dependencies required");
            }
            Path application = Path.of(args[0]);
            Path output = Path.of(args[1]);
            Compression compression = Compression.valueOf(args[2]);
            List<Path> dependencies = new ArrayList<>();
            for (int i = 3; i < args.length; i++) {
                dependencies.add(Path.of(args[i]));
            }
            ManagementFactory.getMemoryPoolMXBeans().forEach(MemoryPoolMXBean::resetPeakUsage);
            long allocatedBefore = allocatedBytes();
            long start = System.nanoTime();
            RunnerJarSpec spec = RunnerJarSpec.builder()
                    .mainClass(SyntheticArchive.MAIN_CLASS)
                    .applicationOutput(List.of(application))
                    .dependencies(dependencies.stream().map(Dependency::of).toList())
                    .output(output)
                    .compression(compression)
                    .build();
            RunnerJarBuilder.build(spec, BuildLogger.noOp());
            long elapsed = System.nanoTime() - start;
            long allocatedAfter = allocatedBytes();
            long allocation = allocatedBefore < 0 || allocatedAfter < allocatedBefore
                    ? -1 : allocatedAfter - allocatedBefore;
            long peakHeap = ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                    .map(MemoryPoolMXBean::getPeakUsage)
                    .filter(java.util.Objects::nonNull)
                    .mapToLong(java.lang.management.MemoryUsage::getUsed)
                    .sum();
            System.out.println(elapsed + "\t" + allocation + "\t" + peakHeap + "\t"
                    + Files.size(output) + "\t" + sha256(output));
        }
    }

    private static final class Inputs {
        private final Path application;
        private final List<Path> dependencies;
        private final Path output;
        private boolean deleteOutputBeforeRun;

        private Inputs(Path application, List<Path> dependencies, Path output) {
            this.application = application;
            this.dependencies = dependencies;
            this.output = output;
        }

        static Inputs copy(SyntheticArchive archive, Path root) throws IOException {
            deleteRecursively(root);
            Path application = root.resolve("application");
            copyTree(archive.applicationClasses(), application);
            Path lib = Files.createDirectories(root.resolve("lib"));
            List<Path> dependencies = new ArrayList<>();
            for (Path source : archive.libraryJars()) {
                Path target = lib.resolve(source.getFileName().toString());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                dependencies.add(target);
            }
            return new Inputs(application, List.copyOf(dependencies), root.resolve("runner.jar"));
        }

        void prepare(String scenario, int iteration) throws IOException {
            deleteOutputBeforeRun = scenario.equals("first-build");
            if (scenario.equals("application-edit")) {
                Files.writeString(application.resolve("edit-marker.txt"), "application-edit-" + iteration + "\n",
                        StandardCharsets.UTF_8);
            } else if (scenario.equals("dependency-edit")) {
                rewriteDependency(dependencies.get(0), iteration);
            }
        }

        long inputBytes() throws IOException {
            long total = treeBytes(application);
            for (Path dependency : dependencies) {
                total += Files.size(dependency);
            }
            return total;
        }

        Path application() {
            return application;
        }

        List<Path> dependencies() {
            return dependencies;
        }

        Path output() {
            return output;
        }

        boolean deleteOutputBeforeRun() {
            return deleteOutputBeforeRun;
        }
    }

    private static final class RssSampler implements AutoCloseable {
        private final long pid;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicLong peak = new AtomicLong(-1);
        private final Thread thread;

        private RssSampler(long pid) {
            this.pid = pid;
            thread = new Thread(this::sample, "packaging-rss-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        static RssSampler start(long pid) {
            return new RssSampler(pid);
        }

        private void sample() {
            while (running.get()) {
                long value = rssBytes(pid);
                if (value >= 0) {
                    peak.accumulateAndGet(value, Math::max);
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        long peakBytes() {
            return peak.get();
        }

        @Override
        public void close() throws InterruptedException {
            running.set(false);
            thread.join(2_000);
        }

        private static long rssBytes(long pid) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                return -1;
            }
            try {
                Process process = new ProcessBuilder("ps", "-o", "rss=", "-p",
                        Long.toString(pid)).start();
                String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (process.waitFor() != 0 || value.isEmpty()) {
                    return -1;
                }
                return Long.parseLong(value) * 1024L;
            } catch (IOException | InterruptedException | NumberFormatException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        }
    }

    private static void rewriteDependency(Path jar, int iteration) throws IOException {
        Path replacement = jar.resolveSibling(jar.getFileName() + ".editing");
        try (JarFile source = new JarFile(jar.toFile(), true, JarFile.OPEN_READ, JarFile.runtimeVersion());
             OutputStream output = Files.newOutputStream(replacement);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            Enumeration<JarEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().equals("META-INF/runner-benchmark-edit.txt")) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(315532800000L);
                zip.putNextEntry(copy);
                if (!entry.isDirectory()) {
                    try (InputStream input = source.getInputStream(entry)) {
                        input.transferTo(zip);
                    }
                }
                zip.closeEntry();
            }
            ZipEntry marker = new ZipEntry("META-INF/runner-benchmark-edit.txt");
            marker.setTime(315532800000L);
            zip.putNextEntry(marker);
            zip.write(("dependency-edit-" + iteration + "\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.move(replacement, jar, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static long treeBytes(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).sum();
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record Options(List<String> workloads, int iterations, Path output) {
        static Options parse(String[] args) {
            List<String> workloads = WorkloadShape.standard().stream().map(WorkloadShape::name).toList();
            int iterations = 3;
            Path output = Path.of("build/reports/packaging");
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--workloads" -> workloads = List.of(args[++i].split(","));
                    case "--iterations" -> iterations = Integer.parseInt(args[++i]);
                    case "--out" -> output = Path.of(args[++i]);
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            return new Options(List.copyOf(workloads), iterations, output.toAbsolutePath().normalize());
        }
    }
}
