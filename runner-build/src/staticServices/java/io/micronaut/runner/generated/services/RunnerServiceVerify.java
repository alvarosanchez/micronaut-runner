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
package io.micronaut.runner.generated.services;

import io.micronaut.core.io.service.MicronautMetaServiceLoaderUtils;
import io.micronaut.core.io.service.SoftServiceLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The {@code micronaut.runner.static-services.verify} switch: compares the table with what Micronaut's own
 * scan finds, type by type, without creating a single service. It is loaded only when the switch is on.
 *
 * <p>In a runner jar the two must agree name for name and in order. An extracted application is scanned jar
 * by jar, which finds the {@code META-INF/micronaut} names in another order than the single jar's merged
 * copy; the table keeps the single jar's order there, so that an extracted application registers its beans
 * in the same order as the jar it was extracted from. There the names are compared as multisets, and an
 * order difference is reported for information only.</p>
 *
 * @since 1.0
 */
final class RunnerServiceVerify {

    /** How many mismatching types a failure describes. */
    private static final int DESCRIBED = 3;

    private RunnerServiceVerify() {
    }

    /**
     * Compares every type the table serves with the scan, and the scan's {@code META-INF/micronaut} types
     * with the table. On success it prints one line to standard error.
     *
     * @param types  the table, by service type
     * @param loader the class loader the table was generated for
     * @return {@code null} when the table matches, otherwise the message to fail with
     */
    static String run(HashMap<String, RunnerServiceLoader> types, ClassLoader loader) {
        try {
            return compare(types, loader);
        } catch (Throwable e) {
            return RunnerStaticServices.LOG_PREFIX + " could not be verified: " + e;
        }
    }

    private static String compare(HashMap<String, RunnerServiceLoader> types, ClassLoader loader)
            throws Exception {
        boolean nested = loader != null && RunnerStaticServices.RUNNER_LOADER.equals(loader.getName());
        RunnerServiceNames names = new RunnerServiceNames();
        StringBuilder differences = new StringBuilder();
        int served = 0;
        int entries = 0;
        int mismatches = 0;
        int reordered = 0;
        // Sorted, so the message does not depend on hash order.
        for (RunnerServiceLoader table : new TreeMap<>(types).values()) {
            if (table.dynamic()) {
                continue;
            }
            List<String> expected = table.names();
            List<String> scanned;
            if (table.micronautOnly()) {
                scanned = new ArrayList<>(
                        MicronautMetaServiceLoaderUtils.findMicronautMetaServiceEntries(loader, table.type()));
            } else {
                scanned = new ArrayList<>();
                SoftServiceLoader.newCollector(table.type(), names, loader, names).collect(scanned, false);
            }
            served++;
            entries += expected.size();
            if (expected.equals(scanned)) {
                continue;
            }
            if (!nested && sorted(expected).equals(sorted(scanned))) {
                reordered++;
                continue;
            }
            mismatches++;
            if (mismatches <= DESCRIBED) {
                differences.append("\n  ").append(table.type()).append(": the table lists ").append(expected)
                        .append(", the scan finds ").append(scanned);
            }
        }
        // The table answers "nothing" for a type it does not list, so the scan must find nothing for it either.
        Map<String, Set<String>> merged = MicronautMetaServiceLoaderUtils.findAllMicronautMetaServices(loader);
        for (Map.Entry<String, Set<String>> scanned : new TreeMap<>(merged).entrySet()) {
            if (!types.containsKey(scanned.getKey()) && !scanned.getValue().isEmpty()) {
                mismatches++;
                if (mismatches <= DESCRIBED) {
                    differences.append("\n  ").append(scanned.getKey())
                            .append(": the table lists nothing, the scan finds ").append(scanned.getValue());
                }
            }
        }
        if (mismatches > 0) {
            return RunnerStaticServices.LOG_PREFIX + " do not match Micronaut's scan: " + served + " types, "
                    + entries + " entries, " + mismatches + " mismatches. Start with -D"
                    + RunnerStaticServices.ENABLED_PROPERTY + "=false, and report this." + differences;
        }
        if (reordered > 0) {
            System.err.println(RunnerStaticServices.LOG_PREFIX + ": " + reordered + " types list the same names in"
                    + " the single jar's order, which the scan of this extracted layout does not use");
        }
        System.err.println(RunnerStaticServices.LOG_PREFIX + " verified: " + served + " types, " + entries
                + " entries, 0 mismatches");
        return null;
    }

    private static List<String> sorted(List<String> names) {
        ArrayList<String> copy = new ArrayList<>(names);
        Collections.sort(copy);
        return copy;
    }
}
