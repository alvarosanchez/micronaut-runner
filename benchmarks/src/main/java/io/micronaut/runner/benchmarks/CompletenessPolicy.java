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

/** Determines whether an incomplete measured matrix is allowed to exit successfully. */
enum CompletenessPolicy {
    /** Every required variant must complete every requested measured run. */
    REQUIRED("required"),
    /** Incomplete results are useful for investigation when at least one measured run succeeds. */
    PARTIAL("partial");

    private final String externalName;

    CompletenessPolicy(String externalName) {
        this.externalName = externalName;
    }

    String externalName() {
        return externalName;
    }

    static CompletenessPolicy parse(String value) {
        for (CompletenessPolicy policy : values()) {
            if (policy.externalName.equals(value)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("unknown completeness policy '" + value + "'");
    }
}
