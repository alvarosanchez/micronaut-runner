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

/** How a benchmark variant enters the application main method. */
enum EntryMode {
    STUB("stub"),
    REFLECTION("reflection"),
    STANDARD_LOADER("standard-loader");

    private final String externalName;

    EntryMode(String externalName) {
        this.externalName = externalName;
    }

    String externalName() {
        return externalName;
    }

    static EntryMode requestedBy(String variantName) {
        if (variantName.endsWith("-reflection")) {
            return REFLECTION;
        }
        if (variantName.equals("runner-stored") || variantName.equals("runner-preserve")) {
            return STUB;
        }
        return STANDARD_LOADER;
    }
}
