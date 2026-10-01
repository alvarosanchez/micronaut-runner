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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a CPU limit's command prefix goes in the one command every launch spawns, timed or not: training and
 * verification launches go through the same {@link StartupHarness}. Nothing here spawns a process.
 */
class CpuLimitedLaunchTest {

    private static final List<String> TASKSET = List.of("taskset", "-c", "0");
    private static final List<String> COMMAND = List.of("/jdk/bin/java", "-jar", "/work/runner-stored.jar");

    @Test
    void withoutAPrefixTheSpawnedCommandIsExactlyTheVariantsCommand() {
        assertEquals(COMMAND, StartupHarness.processCommand(List.of(), COMMAND, List.of()));
        assertEquals(List.of("/jdk/bin/java", "-Xlog:class+load=info:file=/logs/x.log", "-jar",
                        "/work/runner-stored.jar"),
                StartupHarness.processCommand(List.of(), COMMAND, List.of("-Xlog:class+load=info:file=/logs/x.log")));
    }

    @Test
    void jvmArgumentsGoAfterJavaNotAfterTaskset() {
        assertEquals(List.of("taskset", "-c", "0", "/jdk/bin/java", "-jar", "/work/runner-stored.jar"),
                StartupHarness.processCommand(TASKSET, COMMAND, List.of()));
        assertEquals(List.of("taskset", "-c", "0", "/jdk/bin/java", "-XX:AOTCacheOutput=/work/app.training.aot",
                        "-jar", "/work/runner-stored.jar"),
                StartupHarness.processCommand(TASKSET, COMMAND, List.of("-XX:AOTCacheOutput=/work/app.training.aot")));
    }
}
