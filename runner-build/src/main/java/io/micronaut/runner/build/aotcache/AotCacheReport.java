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

import io.micronaut.core.annotation.Experimental;

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
 * What the verification gate found, written as {@value #FILE} next to the cache.
 *
 * @param jdk                   the exact VM build, {@code java.vm.version}
 * @param os                    the operating system, {@code os.name}
 * @param arch                  the CPU architecture, {@code os.arch}
 * @param labels                what the caller added, such as the {@code target}
 * @param creationFlags         the flags the cache was created with
 * @param recordStop            how the recording ended: {@value #STOP_JCMD} ({@code jcmd <pid> AOT.end_recording}),
 *                              {@value #STOP_DRIVER} (the training stop: a signal or the stop path) or
 *                              {@value #STOP_EXIT} (an application that ran to its exit)
 * @param probes                how many strict probes ran
 * @param probeFailures         how many of them failed
 * @param classesLoaded         the classes the strict smoke launch loaded up to the end of its workload
 * @param classesFromCache      how many of them came from the cache
 * @param coverage              {@code classesFromCache / classesLoaded}
 * @param micronautLoaded       the {@code io.micronaut} classes the smoke launch loaded
 * @param micronautFromCache    how many of them came from the cache
 * @param micronautNotFromCache the names of the others
 * @param runtimeLambdas        the lambda proxy classes the smoke launch spun instead of loading them from the cache
 * @param warnings              what the build warned about
 * @param failures              why the gate failed; empty when it passed
 * @param verdict               {@value #PASSED} or {@value #FAILED}
 * @since 1.0
 */
@Experimental
public record AotCacheReport(String jdk,
                             String os,
                             String arch,
                             Map<String, String> labels,
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

    /** The report file, relative to the output directory. */
    public static final String FILE = "aot-report.json";

    /** The verdict of a gate whose enforced checks all held. */
    public static final String PASSED = "passed";

    /** The verdict of a gate with a failed check. */
    public static final String FAILED = "failed";

    /** {@link #recordStop()} when {@code jcmd} ended the recording. */
    public static final String STOP_JCMD = "jcmd";

    /** {@link #recordStop()} when the training stop ended the recording. */
    public static final String STOP_DRIVER = "driver";

    /** {@link #recordStop()} when the application ran to its exit. */
    public static final String STOP_EXIT = "exit";

    /**
     * Copies the collections.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public AotCacheReport {
        Objects.requireNonNull(jdk, "jdk");
        Objects.requireNonNull(os, "os");
        Objects.requireNonNull(arch, "arch");
        labels = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(labels, "labels")));
        creationFlags = List.copyOf(creationFlags);
        Objects.requireNonNull(recordStop, "recordStop");
        micronautNotFromCache = List.copyOf(micronautNotFromCache);
        warnings = List.copyOf(warnings);
        failures = List.copyOf(failures);
        Objects.requireNonNull(verdict, "verdict");
    }

    /**
     * Whether every enforced check held.
     *
     * @return {@code true} when the verdict is {@value #PASSED}
     */
    public boolean passed() {
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
        return new AotCacheReport(jdk, os, arch, labels, creationFlags, recordStop, probes, probeFailures,
                classesLoaded, classesFromCache, coverage, micronautLoaded, micronautFromCache, micronautNotFromCache,
                runtimeLambdas, all, failures, verdict);
    }

    /**
     * The report as a JSON object, with the fields in the order of the components and the labels as top-level
     * fields after {@code arch}.
     *
     * @return the JSON text, ending with a line break
     */
    public String toJson() {
        StringBuilder json = new StringBuilder("{\n");
        field(json, "jdk", string(jdk));
        field(json, "os", string(os));
        field(json, "arch", string(arch));
        labels.forEach((name, value) -> field(json, name, string(value)));
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
     * Writes {@link #toJson()} to {@value #FILE} in a directory.
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
