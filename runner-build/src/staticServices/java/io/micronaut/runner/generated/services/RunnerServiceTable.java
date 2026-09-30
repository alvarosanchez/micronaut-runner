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
 * The shape of the table the packager generates for each application, for the other classes of this package
 * to compile against. This class is never packaged: {@code StaticServiceTableGenerator} writes the real one.
 *
 * <p>The fields are assigned in the static initialiser so that none of them is a compile-time constant, which
 * {@code javac} would copy into the classes that read it.</p>
 *
 * @since 1.0
 */
public final class RunnerServiceTable {

    /**
     * One line per service type, joined with {@code \n}: {@code <type>\t<first slot>\t<slot count>\t<flags>}.
     */
    public static final String TYPES;

    /** The number of slots, which is the number of names in all chunks. */
    public static final int SLOT_COUNT;

    /** The number of chunks {@link #names(int)} answers. */
    public static final int NAME_CHUNKS;

    static {
        TYPES = "";
        SLOT_COUNT = 0;
        NAME_CHUNKS = 0;
    }

    private RunnerServiceTable() {
    }

    /**
     * One chunk of the slot names, joined with {@code \n}, in slot order.
     *
     * @param chunk the chunk, from {@code 0} to {@link #NAME_CHUNKS} - 1
     * @return the names
     */
    public static String names(int chunk) {
        throw new IllegalArgumentException("No chunk " + chunk);
    }
}
