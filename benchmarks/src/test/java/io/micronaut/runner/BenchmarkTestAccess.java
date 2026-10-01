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
package io.micronaut.runner;

/**
 * Test-only access, for the benchmarks' tests, to the launcher members that are package-private because no main
 * code outside {@code io.micronaut.runner} calls them.
 *
 * <p>It lives in the launcher's package under the benchmarks' test sources: the test class path has one class
 * loader and the launcher seals no package, so package-private access works. It is test code, never packaged,
 * and it keeps those members off the launcher's public API.</p>
 */
public final class BenchmarkTestAccess {

    private BenchmarkTestAccess() {
    }

    /**
     * See {@link Index#largestStoredClass()}.
     *
     * @param index the index
     * @return the recorded size
     */
    public static long largestStoredClass(Index index) {
        return index.largestStoredClass();
    }
}
