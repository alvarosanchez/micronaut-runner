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
package io.micronaut.runner.build.aotcache;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.runner.build.BuildLogger;
import io.micronaut.runner.build.training.TrainingDriver;
import io.micronaut.runner.build.training.TrainingSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The verification gate of a trained cache. It passes only when every enforced check holds:
 *
 * <ol start="0">
 *     <li><b>Identity.</b> The verifying JDK is the build, operating system and architecture the identity file
 *     records. A mismatch fails before any launch.</li>
 *     <li><b>Strict probes.</b> {@code java -XX:AOTMode=on -XX:AOTCache=app.aot -cp <jar> -version}, run
 *     {@link AotCacheSettings#verifyProbes()} times. All of them run, and one failure fails the gate: some
 *     failures depend on where ASLR puts the heap.</li>
 *     <li><b>Strict smoke launch.</b> The application launched with the cache in strict mode, through the
 *     {@link TrainingDriver}, reaches readiness and serves the workload.</li>
 *     <li><b>Coverage.</b> From that launch's class-load log, up to the end of its workload: at least
 *     {@link AotCacheSettings#minCoverage()} of the classes come from the cache, and every {@code io.micronaut}
 *     class that does not is a lambda proxy or one the recording named as skipped because it failed
 *     verification or is a JFR event class. With {@link AotCacheSettings#enforceCoverage()} off, the numbers are
 *     only reported.</li>
 * </ol>
 *
 * <p>Internal: {@link #coverage(List, List)} is public only for Runner's own benchmarks, which count what a cached
 * launch took from the cache. It may change in any release.</p>
 *
 * @since 1.0
 */
@Internal
public final class AotCacheGate {

    /** The strict smoke launch's output, relative to the output directory. */
    static final String SMOKE_LOG = "aot-verify.log";

    /** The strict smoke launch's class-load log, up to the end of its workload, relative to the output directory. */
    static final String CLASS_LOAD_LOG = "aot-verify-class-load.log";

    /** The whole class-load log, which the JVM writes until it exits. */
    private static final String CLASS_LOAD_LOG_FULL = "aot-verify-class-load.full.log";

    /** How many offending classes a coverage problem that does not fail the gate names. */
    private static final int NAMED_OFFENDERS = 10;

    /** What a coverage problem that does not fail the gate is reported with. */
    private static final String NOT_ENFORCED = "Not enforced: ";

    /** The prefix of the classes the coverage check looks at by name. */
    private static final String MICRONAUT = "io.micronaut.";

    /** What marks a hidden lambda proxy class. */
    private static final String LAMBDA = "$$Lambda";

    /** What precedes the class name on a {@code -Xlog:class+load} line. */
    private static final String CLASS_LOAD_TAG = "[class,load] ";

    /** What precedes the source on a {@code -Xlog:class+load} line. */
    private static final String SOURCE = " source: ";

    /** The source of a class the cache served. */
    private static final String FROM_CACHE = "source: shared objects file";

    /** A recording's note about a class it could not store, and why, at any log level. */
    private static final Pattern SKIPPED = Pattern.compile("Skipping ([^\\s:]+): (Failed verification|JFR event class)");

    /** How long one strict probe may take. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(60);

    /** How long the class-load log has to stay the same size to count as complete. */
    private static final long QUIET_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    /** How long to wait for that at most. */
    private static final long QUIET_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);

    private AotCacheGate() {
    }

    /**
     * Runs the checks and writes {@value AotCacheReport#FILE} into the directory, whether they pass or not.
     *
     * @param settings   the cache settings; their {@link AotCacheSettings#jvmArgs()} go on every launch
     * @param jdk        what the verifying {@code java} reported
     * @param java       the {@code java} executable to verify with; a relative path is resolved against this JVM's
     *                   working directory
     * @param directory  the directory that holds the JAR, the cache and the identity file, and the working
     *                   directory of every launch
     * @param jarName    the JAR's file name in that directory
     * @param training   how to reach, exercise and stop the application; its own {@code jvmArgs} are replaced by
     *                   the settings'
     * @param recordStop how the recording ended, for the report
     * @param log        where the checks are reported
     * @return the report of a gate that passed
     * @throws IOException          if an enforced check fails; the message says which and why, and the report
     *                              has been written
     * @throws InterruptedException if the thread is interrupted; every launch has been reaped by then
     */
    static AotCacheReport verify(AotCacheSettings settings,
                                 JdkProbe jdk,
                                 Path java,
                                 Path directory,
                                 String jarName,
                                 TrainingSettings training,
                                 String recordStop,
                                 BuildLogger log) throws IOException, InterruptedException {
        Path dir = directory.toAbsolutePath().normalize();
        // Every launch runs in the directory, where a relative path would name another file.
        Path executable = java.toAbsolutePath();
        Map<String, String> identity = AotLaunchOptions.readIdentity(dir.resolve(AotLaunchOptions.IDENTITY_FILE));
        Findings findings = new Findings(jdk, identity, jarName, recordStop, settings.verifyProbes());

        // 0. Identity, before any launch.
        String mismatch = identityMismatch(jdk, identity);
        if (mismatch != null) {
            throw findings.fail(dir, mismatch);
        }

        // 1. Strict probes.
        long started = System.nanoTime();
        List<String> probe = new ArrayList<>();
        probe.add(executable.toString());
        probe.addAll(settings.jvmArgs());
        probe.addAll(List.of("-XX:AOTMode=on", "-XX:AOTCache=" + AotLaunchOptions.CACHE_FILE, "-cp", jarName,
                "-version"));
        ProbeRun probes = runProbes(settings.verifyProbes(), () -> Forks.capture(probe, dir, PROBE_TIMEOUT));
        findings.probeFailures = probes.failures();
        log.info("JDK AOT cache probes: " + probes.summary() + " (" + millis(started) + ")");
        if (probes.failures() > 0) {
            throw findings.fail(dir, probes.summary() + ": " + String.join(" ", probe) + ". The first failure, "
                    + probes.firstFailure());
        }

        // 2. Strict smoke launch, whose class-load log is check 3's.
        started = System.nanoTime();
        Path classLoadLog = dir.resolve(CLASS_LOAD_LOG);
        Path fullLog = dir.resolve(CLASS_LOAD_LOG_FULL);
        Path smokeLog = dir.resolve(SMOKE_LOG);
        Files.deleteIfExists(classLoadLog);
        Files.deleteIfExists(fullLog);
        Files.deleteIfExists(smokeLog);
        try {
            TrainingDriver.run(executable, dir.resolve(jarName),
                    List.of("-XX:AOTMode=on", "-XX:AOTCache=" + AotLaunchOptions.CACHE_FILE,
                            "-Xlog:class+load=info:file=" + CLASS_LOAD_LOG_FULL + "::filecount=0"),
                    AotLaunchOptions.trainingSettings(settings, training), dir, smokeLog, application -> {
                        if (application.isAlive()) {
                            awaitQuiet(fullLog);
                        }
                        snapshot(fullLog, classLoadLog);
                    }, log);
        } catch (IOException e) {
            throw findings.fail(dir, "The strict smoke launch failed: " + e.getMessage());
        } finally {
            Files.deleteIfExists(fullLog);
        }
        log.info("JDK AOT cache smoke launch: ready and served its workload with the cache in strict mode ("
                + millis(started) + ")");

        // 3. Coverage.
        List<String> recordLog = readLines(dir.resolve(AotCacheBuilder.RECORD_LOG));
        Coverage coverage = coverage(readLines(classLoadLog), recordLog);
        findings.coverage = coverage;
        log.info("JDK AOT cache coverage: " + coverage.summary());
        List<String> problems = coverageProblems(coverage, settings.minCoverage(), settings.enforceCoverage());
        if (settings.enforceCoverage() && !problems.isEmpty()) {
            throw findings.fail(dir, problems);
        }
        problems.forEach(problem -> {
            findings.warnings.add(NOT_ENFORCED + problem);
            log.info(NOT_ENFORCED + problem);
        });
        AotCacheReport report = findings.report(AotCacheReport.PASSED);
        report.write(dir);
        return report;
    }

    /**
     * Counts what a strict launch took from the cache.
     *
     * @param classLoadLog the lines of its {@code -Xlog:class+load=info} output
     * @param recordLog    the lines of the recording's output with {@code -Xlog:aot=info}, which name the classes
     *                     the recording skipped
     * @return the counts
     */
    public static Coverage coverage(List<String> classLoadLog, List<String> recordLog) {
        Set<String> allowed = allowlist(recordLog);
        int loaded = 0;
        int fromCache = 0;
        int micronautLoaded = 0;
        int micronautFromCache = 0;
        int lambdas = 0;
        List<String> notFromCache = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();
        for (String line : classLoadLog) {
            if (!line.contains(SOURCE)) {
                continue;
            }
            loaded++;
            boolean cached = line.contains(FROM_CACHE);
            if (cached) {
                fromCache++;
            }
            String name = className(line);
            if (name == null) {
                continue;
            }
            boolean lambda = name.contains(LAMBDA);
            if (lambda && !cached) {
                lambdas++;
            }
            if (name.startsWith(MICRONAUT)) {
                micronautLoaded++;
                if (cached) {
                    micronautFromCache++;
                } else {
                    notFromCache.add(name);
                    if (!lambda && !allowed.contains(name)) {
                        unexpected.add(name);
                    }
                }
            }
        }
        return new Coverage(loaded, fromCache, micronautLoaded, micronautFromCache, List.copyOf(notFromCache),
                List.copyOf(unexpected), lambdas);
    }

    /**
     * What check 3 finds wrong with a coverage: too few classes from the cache, and every {@code io.micronaut}
     * class that neither is a lambda proxy nor was skipped by the recording.
     *
     * @param coverage    the coverage
     * @param minCoverage the minimum share of classes from the cache
     * @param listAll     whether every offending class is named; otherwise the first ten are, and the report's
     *                    {@code micronautNotFromCache} has them all
     * @return the problems, empty when there are none
     */
    static List<String> coverageProblems(Coverage coverage, double minCoverage, boolean listAll) {
        List<String> problems = new ArrayList<>();
        if (coverage.ratio() < minCoverage) {
            problems.add(String.format(Locale.ROOT, "%.1f%% of the classes came from the cache, less than the"
                    + " minimum of %.1f%%", coverage.ratio() * 100, minCoverage * 100));
        }
        if (!coverage.unexpected().isEmpty()) {
            List<String> named = listAll || coverage.unexpected().size() <= NAMED_OFFENDERS ? coverage.unexpected()
                    : coverage.unexpected().subList(0, NAMED_OFFENDERS);
            problems.add(coverage.unexpected().size() + " io.micronaut classes did not come from the cache, and"
                    + " the recording did not skip them for failing verification or as JFR event classes: "
                    + String.join(", ", named)
                    + (named.size() < coverage.unexpected().size()
                    ? " and " + (coverage.unexpected().size() - named.size()) + " more" : ""));
        }
        return problems;
    }

    /**
     * Runs every strict probe, whatever the earlier ones did.
     *
     * @param count how many to run
     * @param probe one probe
     * @return how many failed, and what the first failure printed
     * @throws IOException          if a probe cannot be run
     * @throws InterruptedException if the thread is interrupted
     */
    static ProbeRun runProbes(int count, Probe probe) throws IOException, InterruptedException {
        int failures = 0;
        String first = null;
        for (int i = 0; i < count; i++) {
            Forks.Result result = probe.run();
            if (result.exitStatus() != 0) {
                failures++;
                if (first == null) {
                    first = "exit status " + result.exitStatus() + Forks.tail(result.output());
                }
            }
        }
        return new ProbeRun(count, failures, first);
    }

    /**
     * The classes a recording named as skipped because they failed verification or are JFR event classes, at
     * any log level, as binary names.
     *
     * @param recordLog the lines of the recording's output
     * @return the names
     */
    static Set<String> allowlist(List<String> recordLog) {
        Set<String> names = new HashSet<>();
        for (String line : recordLog) {
            Matcher matcher = SKIPPED.matcher(line);
            if (matcher.find()) {
                names.add(matcher.group(1).replace('/', '.'));
            }
        }
        return names;
    }

    /**
     * Compares the verifying JDK with a cache's identity.
     *
     * @param jdk      what the verifying JDK reported
     * @param identity the identity file's entries
     * @return a message naming both values of the first mismatch, or {@code null} when they agree
     */
    static @Nullable String identityMismatch(JdkProbe jdk, Map<String, String> identity) {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put(AotLaunchOptions.VM_VERSION, jdk.vmVersion());
        actual.put(AotLaunchOptions.OS_NAME, jdk.osName());
        actual.put(AotLaunchOptions.OS_ARCH, jdk.osArch());
        for (Map.Entry<String, String> entry : actual.entrySet()) {
            String recorded = identity.get(entry.getKey());
            if (!entry.getValue().equals(recorded)) {
                return "The cache was built for " + entry.getKey() + " " + recorded + ", and the JDK that verifies"
                        + " it has " + entry.getKey() + " " + entry.getValue() + ". A JDK AOT cache is valid only"
                        + " for the exact JDK build, operating system and architecture that created it";
            }
        }
        return null;
    }

    private static @Nullable String className(String line) {
        int tag = line.indexOf(CLASS_LOAD_TAG);
        if (tag < 0) {
            return null;
        }
        int start = tag + CLASS_LOAD_TAG.length();
        int end = line.indexOf(' ', start);
        return end < 0 ? line.substring(start) : line.substring(start, end);
    }

    private static List<String> readLines(Path file) throws IOException {
        try {
            // Class names are ASCII; Latin-1 decodes any byte a path in a source might hold.
            return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
        } catch (NoSuchFileException e) {
            return List.of();
        }
    }

    /** Waits until the class-load log has stopped growing, so that the classes of the last request are in it. */
    private static void awaitQuiet(Path classLoadLog) throws InterruptedException {
        long deadline = System.nanoTime() + QUIET_TIMEOUT_NANOS;
        long size = size(classLoadLog);
        long since = System.nanoTime();
        while (System.nanoTime() < deadline) {
            Thread.sleep(25);
            long now = size(classLoadLog);
            if (now != size) {
                size = now;
                since = System.nanoTime();
            } else if (System.nanoTime() - since >= QUIET_NANOS) {
                return;
            }
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Copies the whole lines of the class-load log, before the application stops: the classes only a shutdown
     * loads were never recorded, so they would count against the cache.
     */
    private static void snapshot(Path classLoadLog, Path snapshot) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(classLoadLog);
        } catch (NoSuchFileException e) {
            throw new IOException("The strict smoke launch wrote no class-load log at " + classLoadLog
                    + ". It is what -Xlog:class+load writes, which needs a HotSpot JVM.", e);
        }
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] != '\n') {
            end--;
        }
        Files.write(snapshot, Arrays.copyOf(bytes, end));
    }

    private static String millis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms";
    }

    /**
     * What a strict launch took from the cache.
     *
     * <p>Internal: {@link #summary()} is public only for Runner's own benchmarks. It may change in any
     * release.</p>
     */
    @Internal
    public static final class Coverage {

        private final int classesLoaded;
        private final int classesFromCache;
        private final int micronautLoaded;
        private final int micronautFromCache;
        private final List<String> micronautNotFromCache;
        private final List<String> unexpected;
        private final int runtimeLambdas;

        /**
         * Counts what a strict launch took from the cache.
         *
         * @param classesLoaded         the classes it loaded
         * @param classesFromCache      how many of them came from the cache
         * @param micronautLoaded       the {@code io.micronaut} classes it loaded
         * @param micronautFromCache    how many of them came from the cache
         * @param micronautNotFromCache the names of the others, in load order
         * @param unexpected            those of them that are neither lambda proxies nor skipped by the recording
         * @param runtimeLambdas        the lambda proxy classes it spun instead of loading them from the cache
         */
        Coverage(int classesLoaded, int classesFromCache, int micronautLoaded, int micronautFromCache,
                 List<String> micronautNotFromCache, List<String> unexpected, int runtimeLambdas) {
            this.classesLoaded = classesLoaded;
            this.classesFromCache = classesFromCache;
            this.micronautLoaded = micronautLoaded;
            this.micronautFromCache = micronautFromCache;
            this.micronautNotFromCache = List.copyOf(micronautNotFromCache);
            this.unexpected = List.copyOf(unexpected);
            this.runtimeLambdas = runtimeLambdas;
        }

        int classesLoaded() {
            return classesLoaded;
        }

        int classesFromCache() {
            return classesFromCache;
        }

        int micronautLoaded() {
            return micronautLoaded;
        }

        int micronautFromCache() {
            return micronautFromCache;
        }

        List<String> micronautNotFromCache() {
            return micronautNotFromCache;
        }

        List<String> unexpected() {
            return unexpected;
        }

        int runtimeLambdas() {
            return runtimeLambdas;
        }

        /**
         * The share of the classes that came from the cache.
         *
         * @return from 0 to 1; 0 when nothing was loaded
         */
        double ratio() {
            return classesLoaded == 0 ? 0 : (double) classesFromCache / classesLoaded;
        }

        /**
         * One line for a log.
         *
         * @return the summary
         */
        public String summary() {
            return String.format(Locale.ROOT, "%.1f%% (%d of %d classes from the cache), io.micronaut %d of %d,"
                            + " %d lambdas spun at run time", ratio() * 100, classesFromCache, classesLoaded,
                    micronautFromCache, micronautLoaded, runtimeLambdas);
        }
    }

    /** One strict probe. */
    @FunctionalInterface
    interface Probe {

        /**
         * Runs the probe.
         *
         * @return how it ended
         * @throws IOException          if it cannot be run
         * @throws InterruptedException if the thread is interrupted
         */
        Forks.Result run() throws IOException, InterruptedException;
    }

    /**
     * How a series of strict probes went.
     *
     * @param probes       how many ran
     * @param failures     how many failed
     * @param firstFailure the first failure's exit status and output, or {@code null} when none failed
     */
    record ProbeRun(int probes, int failures, @Nullable String firstFailure) {

        /**
         * The summary a log and a failure give.
         *
         * @return {@code k of N strict probes failed}
         */
        String summary() {
            return failures + " of " + probes + " strict probes failed";
        }
    }

    /** What the checks found so far. */
    private static final class Findings {

        private final JdkProbe jdk;
        private final Map<String, String> labels = new LinkedHashMap<>();
        private final String jar;
        private final List<String> creationFlags;
        private final String recordStop;
        private final int probes;
        private final List<String> warnings = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private int probeFailures;
        private Coverage coverage = new Coverage(0, 0, 0, 0, List.of(), List.of(), 0);

        private Findings(JdkProbe jdk, Map<String, String> identity, String jar, String recordStop, int probes) {
            this.jdk = jdk;
            this.jar = jar;
            this.recordStop = recordStop;
            this.probes = probes;
            identity.forEach((name, value) -> {
                if (!AotLaunchOptions.IDENTITY_ENTRIES.contains(name)) {
                    labels.put(name, value);
                }
            });
            String flags = identity.getOrDefault(AotLaunchOptions.CREATION_FLAGS, "");
            this.creationFlags = flags.isBlank() ? List.of() : List.of(flags.trim().split("\\s+"));
        }

        private IOException fail(Path directory, String failure) throws IOException {
            return fail(directory, List.of(failure));
        }

        private IOException fail(Path directory, List<String> reasons) throws IOException {
            failures.addAll(reasons);
            report(AotCacheReport.FAILED).write(directory);
            return new IOException("The JDK AOT cache failed verification: " + String.join("; ", reasons)
                    + ". The logs are in " + directory);
        }

        private AotCacheReport report(String verdict) {
            return new AotCacheReport(jdk.vmVersion(), jdk.osName(), jdk.osArch(), labels, jar, creationFlags,
                    recordStop, probes, probeFailures, coverage.classesLoaded(), coverage.classesFromCache(),
                    coverage.ratio(), coverage.micronautLoaded(), coverage.micronautFromCache(),
                    coverage.micronautNotFromCache(), coverage.runtimeLambdas(), warnings, failures, verdict);
        }
    }
}
