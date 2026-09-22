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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassLoaderBenchmarkIsolationTest {

    @Test
    void urlClassLoaderBaselineStartsWithoutRunnerGlobalState() throws Exception {
        assertProbe("baseline");
    }

    @Test
    void runnerVariantsRegisterMatchingArchivesAndReleaseState() throws Exception {
        assertProbe("stored");
        assertProbe("preserve");
        assertProbe("stored-then-preserve");
    }

    private static void assertProbe(String mode) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(List.of(
                javaExecutable(),
                "-cp", System.getProperty("java.class.path"),
                ClassLoaderIsolationProbe.class.getName(),
                mode))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();

        assertEquals(0, exit, output);
        assertTrue(output.contains("OK " + mode), output);
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command().orElseThrow();
    }
}
