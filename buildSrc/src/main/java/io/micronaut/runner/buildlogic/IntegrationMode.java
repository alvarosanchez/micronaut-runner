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
package io.micronaut.runner.buildlogic;

import java.util.Locale;

/**
 * Resolves the end-to-end test policy from the Gradle property and the developer offline escape hatch.
 */
public enum IntegrationMode {
    /** Every integration scenario is mandatory and missing prerequisites fail the build. */
    REQUIRED("required"),

    /** Network-dependent integration scenarios are reported as skipped. */
    OFFLINE("offline");

    private final String id;

    IntegrationMode(String id) {
        this.id = id;
    }

    /**
     * @return the stable value passed to the test JVM and recorded as a task input
     */
    public String id() {
        return id;
    }

    /**
     * Resolves a fail-closed default while retaining {@code RUNNER_TEST_OFFLINE=true} as a local opt-out.
     *
     * @param configuredMode value of {@code -Prunner.integration}, or {@code null}
     * @param offlineEnvironment value of {@code RUNNER_TEST_OFFLINE}, or {@code null}
     * @return the resolved mode
     */
    public static IntegrationMode resolve(String configuredMode, String offlineEnvironment) {
        Boolean offline = parseOffline(offlineEnvironment);
        IntegrationMode configured = parseMode(configuredMode);
        if (configured == REQUIRED && Boolean.TRUE.equals(offline)) {
            throw new IllegalArgumentException("Integration mode is required, but RUNNER_TEST_OFFLINE=true "
                    + "requests skipped scenarios; select exactly one policy");
        }
        if (configured != null) {
            return configured;
        }
        return Boolean.TRUE.equals(offline) ? OFFLINE : REQUIRED;
    }

    private static IntegrationMode parseMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "required" -> REQUIRED;
            case "offline" -> OFFLINE;
            default -> throw new IllegalArgumentException("runner.integration must be 'required' or 'offline', not '"
                    + value + "'");
        };
    }

    private static Boolean parseOffline(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("RUNNER_TEST_OFFLINE must be 'true' or 'false', not '"
                    + value + "'");
        };
    }
}
