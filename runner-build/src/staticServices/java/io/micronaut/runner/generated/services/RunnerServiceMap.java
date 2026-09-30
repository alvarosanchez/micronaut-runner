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

import io.micronaut.core.io.service.SoftServiceLoader;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The map Micronaut consults before it scans for a service type, answered from {@link RunnerServiceTable}.
 *
 * <p>The table describes a closed world: the packager saw every service file of the application. A type is
 * therefore in one of three states, and the map reports each the way {@code SoftServiceLoader} reads it:</p>
 * <ul>
 *     <li>served: {@link #get(Object)} returns its loader;</li>
 *     <li>provided by nothing on the class path: {@link #get(Object)} returns a shared empty loader, so
 *     Micronaut does not scan for it either;</li>
 *     <li>left dynamic, because the packager could not prove the table equal to the scan:
 *     {@link #get(Object)} returns {@code null} and {@link #containsKey(Object)} {@code false}, and
 *     Micronaut scans.</li>
 * </ul>
 *
 * <p>{@link #entrySet()} lists only the served types; nothing in Micronaut iterates the map.</p>
 *
 * @since 1.0
 */
final class RunnerServiceMap extends AbstractMap<String, SoftServiceLoader.StaticServiceLoader<?>> {

    private static final int UNVERIFIED = 0;
    private static final int VERIFYING = 1;
    private static final int VERIFIED = 2;
    private static final int MISMATCHED = 3;

    private final HashMap<String, RunnerServiceLoader> types;
    private final RunnerServiceLoader empty;
    private final ClassLoader loader;
    private final boolean verify;
    private final boolean trace;
    private volatile int verification;
    private String mismatch;
    private Set<Map.Entry<String, SoftServiceLoader.StaticServiceLoader<?>>> entries;

    private RunnerServiceMap(HashMap<String, RunnerServiceLoader> types, RunnerServiceLoader empty,
                             ClassLoader loader, boolean verify, boolean trace) {
        this.types = types;
        this.empty = empty;
        this.loader = loader;
        this.verify = verify;
        this.trace = trace;
    }

    /**
     * Reads {@link RunnerServiceTable} into a map. It splits two constants and allocates one loader per type;
     * it loads no service class and touches no resource.
     *
     * @param loader the class loader the table was generated for, which defines every listed class
     * @param verify whether to compare the table with Micronaut's scan on the first lookup
     * @param trace  whether to print each lookup
     * @return the map
     */
    static RunnerServiceMap parse(ClassLoader loader, boolean verify, boolean trace) {
        String[] names = new String[RunnerServiceTable.SLOT_COUNT];
        int slot = 0;
        int chunks = RunnerServiceTable.NAME_CHUNKS;
        for (int chunk = 0; chunk < chunks; chunk++) {
            String text = RunnerServiceTable.names(chunk);
            int start = 0;
            while (true) {
                int end = text.indexOf('\n', start);
                if (end < 0) {
                    names[slot++] = text.substring(start);
                    break;
                }
                names[slot++] = text.substring(start, end);
                start = end + 1;
            }
        }
        if (slot != names.length) {
            throw new IllegalStateException("The static service table names " + slot + " of " + names.length
                    + " slots");
        }
        String table = RunnerServiceTable.TYPES;
        HashMap<String, RunnerServiceLoader> types = new HashMap<>();
        int length = table.length();
        int start = 0;
        while (start < length) {
            int end = table.indexOf('\n', start);
            if (end < 0) {
                end = length;
            }
            int firstTab = table.indexOf('\t', start);
            int secondTab = table.indexOf('\t', firstTab + 1);
            int thirdTab = table.indexOf('\t', secondTab + 1);
            String type = table.substring(start, firstTab);
            int first = Integer.parseInt(table.substring(firstTab + 1, secondTab));
            int count = Integer.parseInt(table.substring(secondTab + 1, thirdTab));
            int flags = Integer.parseInt(table.substring(thirdTab + 1, end));
            if (first < 0 || count < 0 || first + count > names.length) {
                throw new IllegalStateException("The static service table gives " + type + " slots " + first
                        + " to " + (first + count) + " of " + names.length);
            }
            types.put(type, new RunnerServiceLoader(type, names, first, count, flags, loader));
            start = end + 1;
        }
        return new RunnerServiceMap(types, new RunnerServiceLoader("", names, 0, 0, 0, loader), loader, verify,
                trace);
    }

    @Override
    public SoftServiceLoader.StaticServiceLoader<?> get(Object key) {
        if (!(key instanceof String)) {
            return null;
        }
        if (verify) {
            verify();
        }
        RunnerServiceLoader found = types.get(key);
        if (found == null) {
            if (trace) {
                trace((String) key, "empty");
            }
            return empty;
        }
        if (found.dynamic()) {
            if (trace) {
                trace((String) key, "dynamic");
            }
            return null;
        }
        if (trace) {
            trace((String) key, Integer.toString(found.size()));
        }
        return found;
    }

    @Override
    public boolean containsKey(Object key) {
        if (!(key instanceof String)) {
            return false;
        }
        if (verify) {
            verify();
        }
        RunnerServiceLoader found = types.get(key);
        return found == null || !found.dynamic();
    }

    @Override
    public Set<Map.Entry<String, SoftServiceLoader.StaticServiceLoader<?>>> entrySet() {
        Set<Map.Entry<String, SoftServiceLoader.StaticServiceLoader<?>>> served = entries;
        if (served == null) {
            HashMap<String, SoftServiceLoader.StaticServiceLoader<?>> map = new HashMap<>();
            for (RunnerServiceLoader type : types.values()) {
                if (!type.dynamic()) {
                    map.put(type.type(), type);
                }
            }
            served = Collections.unmodifiableMap(map).entrySet();
            entries = served;
        }
        return served;
    }

    private static void trace(String type, String answer) {
        System.err.println(RunnerStaticServices.LOG_PREFIX + ": get " + type + " -> " + answer);
    }

    /**
     * Compares the table with Micronaut's scan, once, on the first lookup: by then {@code SoftServiceLoader}
     * is initialised, which it is not while {@link RunnerStaticServices#load()} runs. A mismatch fails this
     * lookup and every later one, so the application does not start on a table that differs from the scan.
     */
    private void verify() {
        if (verification < VERIFIED) {
            // Another thread waits here until the comparison is done. The comparing thread itself gets past
            // the monitor, which is reentrant, and past the state check, so a nested lookup is answered.
            synchronized (this) {
                if (verification == UNVERIFIED) {
                    verification = VERIFYING;
                    mismatch = RunnerServiceVerify.run(types, loader);
                    verification = mismatch == null ? VERIFIED : MISMATCHED;
                }
            }
        }
        if (verification == MISMATCHED) {
            throw new IllegalStateException(mismatch);
        }
    }
}
