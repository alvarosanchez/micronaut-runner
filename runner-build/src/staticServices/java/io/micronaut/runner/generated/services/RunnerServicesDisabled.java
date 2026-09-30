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

/**
 * What {@link RunnerStaticServices#load()} returns when the table must not be used. Micronaut stores it under
 * this class, which nothing asks for, so every service lookup scans the class path as it does without a table.
 *
 * @since 1.0
 */
final class RunnerServicesDisabled {

    /** The one marker. */
    static final RunnerServicesDisabled INSTANCE = new RunnerServicesDisabled();

    private RunnerServicesDisabled() {
    }
}
