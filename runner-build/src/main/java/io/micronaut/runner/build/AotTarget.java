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

/**
 * What a JDK AOT cache is trained for: the extracted layout, which every JDK caches in full, or a copy of the
 * single Runner JAR, recorded with {@code micronaut.runner.aot.training} so that JDK 27 and later cache its
 * classes.
 *
 * <p>Internal to Runner's interim build plugins, which read their {@code target} setting with {@link #parse}: it
 * may change in any release.</p>
 *
 * @since 1.0
 */
@Internal
public enum AotTarget {

    /** The extracted layout: the application JAR and {@code lib/}, run by the JDK's own class loader. */
    LAYOUT("layout"),

    /**
     * A copy of the Runner JAR, its modification time kept, recorded with {@code micronaut.runner.aot.training} so
     * that JDK 27 and later cache its classes: the single-file option.
     */
    SINGLE_JAR("singleJar");

    /** The target the build plugins use unless the user picks another: {@link #LAYOUT}. */
    public static final AotTarget DEFAULT = LAYOUT;

    private final String value;

    AotTarget(String value) {
        this.value = value;
    }

    /**
     * The name a build script spells the target with.
     *
     * @return {@code layout} or {@code singleJar}
     */
    public String value() {
        return value;
    }

    /**
     * Reads a target as a build script spells it.
     *
     * @param value {@code layout} or {@code singleJar}
     * @return the target
     * @throws IllegalArgumentException for any other value; the message names both
     */
    public static AotTarget parse(String value) {
        for (AotTarget target : values()) {
            if (target.value.equals(value == null ? null : value.trim())) {
                return target;
            }
        }
        throw new IllegalArgumentException("The JDK AOT cache target must be 'layout' or 'singleJar', not '" + value
                + "'");
    }

    @Override
    public String toString() {
        return value;
    }
}
