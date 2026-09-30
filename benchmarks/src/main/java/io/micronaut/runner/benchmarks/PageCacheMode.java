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

import java.util.Arrays;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** How the harness treats the OS page cache before each launch. */
enum PageCacheMode {

    /** No eviction, no {@code sync} and no {@code sudo}: whatever the build and earlier starts left cached. */
    UNCONTROLLED("uncontrolled"),

    /**
     * Linux, no root: every regular file of the launched variant's artifact and launch inputs is evicted with
     * {@code posix_fadvise(POSIX_FADV_DONTNEED)} before every launch. The JDK stays cached, so only the packaging
     * differs.
     */
    EVICT_ARTIFACTS("evict-artifacts"),

    /**
     * Passwordless {@code sudo}: {@code sync} and {@code drop_caches=3} (Linux) or {@code purge} (macOS) before
     * every launch. Pages a live process maps, the harness JVM's own JDK among them, stay resident.
     */
    DROP_ALL("drop-all");

    private final String externalName;

    PageCacheMode(String externalName) {
        this.externalName = externalName;
    }

    /**
     * The name used on the command line and in the reports.
     *
     * @return for example {@code evict-artifacts}
     */
    String externalName() {
        return externalName;
    }

    /**
     * Parses a {@code --page-cache} value.
     *
     * @param value the value
     * @return the mode
     * @throws IllegalArgumentException if it names no mode
     */
    static PageCacheMode parse(String value) {
        for (PageCacheMode mode : values()) {
            if (mode.externalName.equals(value.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("--page-cache must be one of " + String.join(", ",
                Arrays.stream(values()).map(PageCacheMode::externalName).toList()) + "; got '" + value + "'");
    }

    /**
     * Fails fast on a combination that cannot work, before the sample build. There are no fallbacks.
     *
     * @param osName           the {@code os.name} system property
     * @param passwordlessSudo runs {@code sudo -n true}; consulted only by {@link #DROP_ALL}
     * @throws IllegalArgumentException if the mode cannot be applied here
     */
    void validate(String osName, BooleanSupplier passwordlessSudo) {
        switch (this) {
            case UNCONTROLLED -> {
                // Nothing to check, and deliberately no sudo probe.
            }
            case EVICT_ARTIFACTS -> {
                if (!linux(osName)) {
                    throw new IllegalArgumentException("--page-cache evict-artifacts needs Linux"
                            + " (posix_fadvise); this is " + osName);
                }
            }
            case DROP_ALL -> {
                if (!linux(osName) && !macOs(osName)) {
                    throw new IllegalArgumentException("--page-cache drop-all needs Linux or macOS; this is "
                            + osName);
                }
                if (!passwordlessSudo.getAsBoolean()) {
                    throw new IllegalArgumentException("--page-cache drop-all needs passwordless sudo:"
                            + " 'sudo -n true' failed");
                }
            }
        }
    }

    /**
     * How this mode evicts, for the reports.
     *
     * @param osName the {@code os.name} system property
     * @return the method, or {@code null} when nothing is evicted
     */
    String evictionMethod(String osName) {
        return switch (this) {
            case UNCONTROLLED -> null;
            case EVICT_ARTIFACTS -> "posix_fadvise(POSIX_FADV_DONTNEED) per file";
            case DROP_ALL -> macOs(osName) ? "purge" : "drop_caches=3";
        };
    }

    static boolean linux(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("linux");
    }

    static boolean macOs(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        return name.contains("mac") || name.contains("darwin");
    }
}
