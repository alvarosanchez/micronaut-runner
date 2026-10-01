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
package io.micronaut.runner.build.aotcache;

import java.util.List;
import java.util.Map;

/**
 * Test-only access, for {@code AotCacheOutputTest} in {@code io.micronaut.runner.build}, to the members of
 * {@link AotCacheReport} that are package-private because no main code outside this package calls them.
 *
 * <p>It is test code, never packaged, and it keeps the report's constructor and accessors off runner-build's
 * public API. The test stays in its own package because it checks {@code AotCacheOutput}'s package-private
 * warning logic.</p>
 */
public final class AotCacheTestAccess {

    private AotCacheTestAccess() {
    }

    /**
     * A report as the gate writes it, with every field given. See {@link AotCacheReport}'s constructor.
     *
     * @param jdk                   the exact VM build
     * @param os                    the operating system
     * @param arch                  the CPU architecture
     * @param labels                what the caller added
     * @param jar                   the file name of the JAR the cache serves
     * @param recordStop            how the recording ended
     * @param probes                how many strict probes ran
     * @param classesLoaded         the classes the smoke launch loaded
     * @param classesFromCache      how many of them came from the cache
     * @param micronautLoaded       the {@code io.micronaut} classes the smoke launch loaded
     * @param micronautFromCache    how many of them came from the cache
     * @param runtimeLambdas        the lambda proxy classes the smoke launch spun
     * @param verdict               {@code passed} or {@code failed}
     * @return the report, with no creation flag, probe failure, warning or failure
     */
    public static AotCacheReport report(String jdk, String os, String arch, Map<String, String> labels, String jar,
                                        String recordStop, int probes, int classesLoaded, int classesFromCache,
                                        int micronautLoaded, int micronautFromCache, int runtimeLambdas,
                                        String verdict) {
        return new AotCacheReport(jdk, os, arch, labels, jar, List.of(), recordStop, probes, 0, classesLoaded,
                classesFromCache, (double) classesFromCache / classesLoaded, micronautLoaded, micronautFromCache,
                List.of(), runtimeLambdas, List.of(), List.of(), verdict);
    }

    /**
     * See {@link AotCacheReport#warnings()}.
     *
     * @param report the report
     * @return the warnings
     */
    public static List<String> warnings(AotCacheReport report) {
        return report.warnings();
    }

    /**
     * See {@link AotCacheReport#verdict()}.
     *
     * @param report the report
     * @return the verdict
     */
    public static String verdict(AotCacheReport report) {
        return report.verdict();
    }
}
