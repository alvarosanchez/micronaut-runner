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

import io.micronaut.core.annotation.Internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What the verification gate found, written as {@code aot-report.json} next to the cache.
 *
 * <p>Internal to Runner's interim build plugins, which log its {@link #summary()}: it may change in any
 * release. It is a final class rather than a record so that only what another package calls is public:
 * {@code AotCacheOutput} reads {@link #micronautLoaded()} and {@link #micronautFromCache()}, adds a warning and
 * writes the report again.</p>
 *
 * @since 1.0
 */
@Internal
public final class AotCacheReport {

    /** The report file, relative to the output directory. */
    static final String FILE = "aot-report.json";

    /** The verdict of a gate whose enforced checks all held. */
    static final String PASSED = "passed";

    /** The verdict of a gate with a failed check. */
    static final String FAILED = "failed";

    /** {@link #recordStop()} when {@code jcmd} ended the recording. */
    static final String STOP_JCMD = "jcmd";

    /** {@link #recordStop()} when the training stop ended the recording. */
    static final String STOP_DRIVER = "driver";

    /** {@link #recordStop()} when the application ran to its exit. */
    static final String STOP_EXIT = "exit";

    private final String jdk;
    private final String os;
    private final String arch;
    private final Map<String, String> labels;
    private final String jar;
    private final List<String> creationFlags;
    private final String recordStop;
    private final int probes;
    private final int probeFailures;
    private final int classesLoaded;
    private final int classesFromCache;
    private final double coverage;
    private final int micronautLoaded;
    private final int micronautFromCache;
    private final List<String> micronautNotFromCache;
    private final int runtimeLambdas;
    private final List<String> warnings;
    private final List<String> failures;
    private final String verdict;

    /**
     * Copies the collections.
     *
     * @param jdk                   the exact VM build, {@code java.vm.version}
     * @param os                    the operating system, {@code os.name}
     * @param arch                  the CPU architecture, {@code os.arch}
     * @param labels                what the caller added, such as the {@code target}
     * @param jar                   the file name of the JAR the cache serves, which {@code java -jar} launches
     *                              from the cache's directory
     * @param creationFlags         the flags the cache was created with
     * @param recordStop            how the recording ended: {@code jcmd} ({@code jcmd <pid> AOT.end_recording}),
     *                              {@code driver} (the training stop: a signal or the stop path) or
     *                              {@code exit} (an application that ran to its exit)
     * @param probes                how many strict probes ran
     * @param probeFailures         how many of them failed
     * @param classesLoaded         the classes the strict smoke launch loaded up to the end of its workload
     * @param classesFromCache      how many of them came from the cache
     * @param coverage              {@code classesFromCache / classesLoaded}
     * @param micronautLoaded       the {@code io.micronaut} classes the smoke launch loaded
     * @param micronautFromCache    how many of them came from the cache
     * @param micronautNotFromCache the names of the others
     * @param runtimeLambdas        the lambda proxy classes the smoke launch spun instead of loading them from
     *                              the cache
     * @param warnings              what the build warned about
     * @param failures              why the gate failed; empty when it passed
     * @param verdict               {@value #PASSED} or {@value #FAILED}
     * @throws NullPointerException if a value is {@code null}
     */
    AotCacheReport(String jdk,
                   String os,
                   String arch,
                   Map<String, String> labels,
                   String jar,
                   List<String> creationFlags,
                   String recordStop,
                   int probes,
                   int probeFailures,
                   int classesLoaded,
                   int classesFromCache,
                   double coverage,
                   int micronautLoaded,
                   int micronautFromCache,
                   List<String> micronautNotFromCache,
                   int runtimeLambdas,
                   List<String> warnings,
                   List<String> failures,
                   String verdict) {
        this.jdk = Objects.requireNonNull(jdk, "jdk");
        this.os = Objects.requireNonNull(os, "os");
        this.arch = Objects.requireNonNull(arch, "arch");
        this.labels = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(labels, "labels")));
        this.jar = Objects.requireNonNull(jar, "jar");
        this.creationFlags = List.copyOf(creationFlags);
        this.recordStop = Objects.requireNonNull(recordStop, "recordStop");
        this.probes = probes;
        this.probeFailures = probeFailures;
        this.classesLoaded = classesLoaded;
        this.classesFromCache = classesFromCache;
        this.coverage = coverage;
        this.micronautLoaded = micronautLoaded;
        this.micronautFromCache = micronautFromCache;
        this.micronautNotFromCache = List.copyOf(micronautNotFromCache);
        this.runtimeLambdas = runtimeLambdas;
        this.warnings = List.copyOf(warnings);
        this.failures = List.copyOf(failures);
        this.verdict = Objects.requireNonNull(verdict, "verdict");
    }

    /**
     * The {@code io.micronaut} classes the smoke launch loaded.
     *
     * @return the number of classes
     */
    public int micronautLoaded() {
        return micronautLoaded;
    }

    /**
     * How many of the {@code io.micronaut} classes the smoke launch loaded came from the cache.
     *
     * @return the number of classes
     */
    public int micronautFromCache() {
        return micronautFromCache;
    }

    /**
     * The exact VM build, {@code java.vm.version}.
     *
     * @return the VM build
     */
    String jdk() {
        return jdk;
    }

    /**
     * The operating system, {@code os.name}.
     *
     * @return the operating system
     */
    String os() {
        return os;
    }

    /**
     * The CPU architecture, {@code os.arch}.
     *
     * @return the architecture
     */
    String arch() {
        return arch;
    }

    /**
     * What the caller added, such as the {@code target}.
     *
     * @return the labels, in the caller's order
     */
    Map<String, String> labels() {
        return labels;
    }

    /**
     * The file name of the JAR the cache serves, which {@code java -jar} launches from the cache's directory.
     *
     * @return the file name
     */
    String jar() {
        return jar;
    }

    /**
     * The flags the cache was created with.
     *
     * @return the flags
     */
    List<String> creationFlags() {
        return creationFlags;
    }

    /**
     * How the recording ended: {@value #STOP_JCMD}, {@value #STOP_DRIVER} or {@value #STOP_EXIT}.
     *
     * @return how the recording ended
     */
    String recordStop() {
        return recordStop;
    }

    /**
     * How many strict probes ran.
     *
     * @return the number of probes
     */
    int probes() {
        return probes;
    }

    /**
     * How many strict probes failed.
     *
     * @return the number of failed probes
     */
    int probeFailures() {
        return probeFailures;
    }

    /**
     * The classes the strict smoke launch loaded up to the end of its workload.
     *
     * @return the number of classes
     */
    int classesLoaded() {
        return classesLoaded;
    }

    /**
     * How many of the classes the smoke launch loaded came from the cache.
     *
     * @return the number of classes
     */
    int classesFromCache() {
        return classesFromCache;
    }

    /**
     * {@code classesFromCache / classesLoaded}.
     *
     * @return the share of the classes from the cache
     */
    double coverage() {
        return coverage;
    }

    /**
     * The names of the {@code io.micronaut} classes the smoke launch did not load from the cache.
     *
     * @return the class names
     */
    List<String> micronautNotFromCache() {
        return micronautNotFromCache;
    }

    /**
     * The lambda proxy classes the smoke launch spun instead of loading them from the cache.
     *
     * @return the number of lambda proxy classes
     */
    int runtimeLambdas() {
        return runtimeLambdas;
    }

    /**
     * What the build warned about.
     *
     * @return the warnings
     */
    List<String> warnings() {
        return warnings;
    }

    /**
     * Why the gate failed.
     *
     * @return the failures; empty when it passed
     */
    List<String> failures() {
        return failures;
    }

    /**
     * {@value #PASSED} or {@value #FAILED}.
     *
     * @return the verdict
     */
    String verdict() {
        return verdict;
    }

    /**
     * Whether every enforced check held.
     *
     * @return {@code true} when the verdict is {@value #PASSED}
     */
    boolean passed() {
        return PASSED.equals(verdict);
    }

    /**
     * The same report with one more warning.
     *
     * @param warning the warning
     * @return the report
     */
    public AotCacheReport withWarning(String warning) {
        List<String> all = new ArrayList<>(warnings);
        all.add(warning);
        return new AotCacheReport(jdk, os, arch, labels, jar, creationFlags, recordStop, probes, probeFailures,
                classesLoaded, classesFromCache, coverage, micronautLoaded, micronautFromCache, micronautNotFromCache,
                runtimeLambdas, all, failures, verdict);
    }

    /**
     * One line that reports the cache, for a plugin to log, such as {@code Trained and verified the JDK AOT cache
     * for the layout target: 97.3% of the classes and 812 of 830 io.micronaut classes from the cache, 0 of 10
     * strict probes failed. Launch it from its directory with: java @app.jvmopts -jar app.jar}. Each label reads
     * as {@code the <value> <name>}.
     *
     * @return the summary
     */
    public String summary() {
        StringBuilder summary = new StringBuilder(passed() ? "Trained and verified" : "Failed to verify")
                .append(" the JDK AOT cache");
        String separator = " for ";
        for (Map.Entry<String, String> label : labels.entrySet()) {
            summary.append(separator).append("the ").append(label.getValue()).append(' ').append(label.getKey());
            separator = " and ";
        }
        return summary.append(String.format(Locale.ROOT, ": %.1f%% of the classes and %d of %d io.micronaut classes"
                        + " from the cache, %d of %d strict probes failed. Launch it from its directory with: java @%s"
                        + " -jar %s", coverage * 100, micronautFromCache, micronautLoaded, probeFailures, probes,
                AotLaunchOptions.ARGFILE, jar)).toString();
    }

    /**
     * The report as a JSON object, with the fields in the order of the components and the labels as top-level
     * fields after {@code arch}.
     *
     * @return the JSON text, ending with a line break
     */
    String toJson() {
        StringBuilder json = new StringBuilder("{\n");
        field(json, "jdk", string(jdk));
        field(json, "os", string(os));
        field(json, "arch", string(arch));
        labels.forEach((name, value) -> field(json, name, string(value)));
        field(json, "jar", string(jar));
        field(json, "creationFlags", strings(creationFlags));
        field(json, "recordStop", string(recordStop));
        field(json, "probes", Integer.toString(probes));
        field(json, "probeFailures", Integer.toString(probeFailures));
        field(json, "classesLoaded", Integer.toString(classesLoaded));
        field(json, "classesFromCache", Integer.toString(classesFromCache));
        field(json, "coverage", String.format(Locale.ROOT, "%.4f", coverage));
        field(json, "micronautLoaded", Integer.toString(micronautLoaded));
        field(json, "micronautFromCache", Integer.toString(micronautFromCache));
        field(json, "micronautNotFromCache", strings(micronautNotFromCache));
        field(json, "runtimeLambdas", Integer.toString(runtimeLambdas));
        field(json, "warnings", strings(warnings));
        field(json, "failures", strings(failures));
        json.append("  \"verdict\": ").append(string(verdict)).append("\n}\n");
        return json.toString();
    }

    /**
     * Writes the report as JSON to {@code aot-report.json} in a directory.
     *
     * @param directory the output directory
     * @return the file
     * @throws IOException if it cannot be written
     */
    public Path write(Path directory) throws IOException {
        Path file = directory.resolve(FILE);
        Files.writeString(file, toJson(), StandardCharsets.UTF_8);
        return file;
    }

    private static void field(StringBuilder json, String name, String value) {
        json.append("  ").append(string(name)).append(": ").append(value).append(",\n");
    }

    private static String strings(List<String> values) {
        if (values.isEmpty()) {
            return "[]";
        }
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            array.append(i == 0 ? "" : ", ").append(string(values.get(i)));
        }
        return array.append(']').toString();
    }

    private static String string(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
