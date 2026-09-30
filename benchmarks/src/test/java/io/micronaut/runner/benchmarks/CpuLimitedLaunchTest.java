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
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Where a CPU limit's command prefix goes, in timing runs and in AOT-cache training and verification, and what it
 * does to the cache identity. Nothing here spawns a process.
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
        assertEquals(List.of("taskset", "-c", "0", "/jdk/bin/java", "-Xlog:class+load=info:file=/logs/x.log",
                        "-jar", "/work/runner-stored.jar"),
                StartupHarness.processCommand(TASKSET, COMMAND, List.of("-Xlog:class+load=info:file=/logs/x.log")));
    }

    @Test
    void trainingAndVerificationRunUnderThePrefixWithTheirFlagsAfterJava(@TempDir Path directory) {
        Path jar = directory.resolve("runner-stored.jar");
        Variant source = Variant.available("runner-stored", "fixture", List.of("/jdk/bin/java", "-jar", jar.toString()),
                directory, jar);
        Path temporary = directory.resolve("app.training.aot");
        Path cache = directory.resolve("app.aot");
        Path classLog = directory.resolve("verification.log");
        AotCache.Request limited = request(directory, CpuLimit.validate(1, "Linux", "0-3", true));

        List<String> training = AotCache.lifecycleCommand(limited,
                AotCache.trainingCommand(source, temporary, List.of("-XX:+UnlockDiagnosticVMOptions")));
        assertEquals(List.of("taskset", "-c", "0", "/jdk/bin/java", "-XX:+UnlockDiagnosticVMOptions",
                "-XX:AOTCacheOutput=" + temporary.toAbsolutePath().normalize(), "-jar", jar.toString()), training);

        List<String> verification = AotCache.lifecycleCommand(limited,
                AotCache.verificationCommand(source, cache, classLog));
        assertEquals(List.of("taskset", "-c", "0", "/jdk/bin/java",
                "-Xlog:class+load=info:file=" + classLog.toAbsolutePath().normalize(), "-XX:AOTMode=on",
                "-XX:AOTCache=" + cache.toAbsolutePath().normalize(), "-jar", jar.toString()), verification);

        AotCache.Request unlimited = request(directory, null);
        assertEquals(AotCache.trainingCommand(source, temporary, List.of()),
                AotCache.lifecycleCommand(unlimited, AotCache.trainingCommand(source, temporary, List.of())));
        // The measured command itself never carries the prefix.
        assertEquals("/jdk/bin/java", AotCache.launchCommand(source, cache).get(0));
    }

    @Test
    void aLimitedCacheHasItsOwnIdentityAndAnUnlimitedOneKeepsTodays(@TempDir Path directory) throws Exception {
        Path jar = Files.writeString(directory.resolve("runner-stored.jar"), "application bytes",
                StandardCharsets.UTF_8);
        Variant source = Variant.available("runner-stored", "fixture",
                List.of(SampleBuild.javaExecutable().toString(), "-jar", jar.toString()), directory, jar);
        List<String> creationFlags = List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:+AOTCompatibleOopCompression");

        AotCache.Request unlimitedRequest = request(directory, null);
        AotCache.Request oneCpu = request(directory, CpuLimit.validate(1, "Linux", "0-3", true));
        AotCache.Request twoCpus = request(directory, CpuLimit.validate(2, "Linux", "0-3", true));
        String unlimited = AotCache.identity(source, unlimitedRequest, creationFlags);

        assertEquals(List.of(), unlimitedRequest.relevantJvmFlags());
        assertEquals(List.of(), unlimitedRequest.commandPrefix());
        assertEquals(List.of("cpus=1"), oneCpu.relevantJvmFlags());
        assertEquals(TASKSET, oneCpu.commandPrefix());
        assertNotEquals(unlimited, AotCache.identity(source, oneCpu, creationFlags));
        assertNotEquals(AotCache.identity(source, oneCpu, creationFlags),
                AotCache.identity(source, twoCpus, creationFlags));

        // What the identity hashed before CPU limits existed: the cache flags, the creation flags, no relevant
        // flags, the command's arguments after java, the readiness path and the workload.
        List<String> before = new ArrayList<>(List.of("AOTCacheOutput", "AOTCache"));
        before.addAll(creationFlags);
        before.addAll(List.of("-jar", jar.toString(), "readiness=/hello", "workload=/hello"));
        assertEquals(AotCache.identity(List.of(jar),
                System.getProperty("java.runtime.version", "<unavailable>") + "|"
                        + System.getProperty("java.vm.version", "<unavailable>"),
                System.getProperty("java.vm.name", "<unavailable>"),
                System.getProperty("os.arch", "<unavailable>"), before), unlimited);
    }

    private static AotCache.Request request(Path directory, CpuLimit limit) {
        return SampleBuild.aotRequest(directory, "com.example.Application", limit,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }
}
