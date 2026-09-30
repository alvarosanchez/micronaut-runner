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

import io.micronaut.runner.build.training.TrainingSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The argfile, the identity file and the one list of JVM arguments that reaches every launch. */
class AotLaunchOptionsTest {

    @TempDir
    Path temp;

    @Test
    void theArgfileNamesTheCacheRelativelyOneOptionPerLine() {
        assertEquals("-XX:AOTCache=app.aot\n", AotLaunchOptions.argfile(AotCacheSettings.defaults()));

        AotCacheSettings strict = AotCacheSettings.builder()
                .strict(true)
                .jvmArgs(List.of("-XX:+UseSerialGC", "-Xmx512m", "-XX:+UseCompactObjectHeaders"))
                .build();
        assertEquals(List.of("-XX:AOTCache=app.aot", "-XX:AOTMode=on", "-XX:+UseSerialGC", "-Xmx512m",
                "-XX:+UseCompactObjectHeaders"), AotLaunchOptions.options(strict));
        assertEquals("""
                -XX:AOTCache=app.aot
                -XX:AOTMode=on
                -XX:+UseSerialGC
                -Xmx512m
                -XX:+UseCompactObjectHeaders
                """, AotLaunchOptions.argfile(strict));

        AotCacheSettings lenient = AotCacheSettings.builder().strict(false).jvmArgs(List.of("-Xmx512m")).build();
        assertEquals("-XX:AOTCache=app.aot\n-Xmx512m\n", AotLaunchOptions.argfile(lenient));
    }

    @Test
    void anOptionTheLauncherWouldSplitIsQuoted() {
        AotCacheSettings settings = AotCacheSettings.builder()
                .jvmArgs(List.of("-Dgreeting=hello world", "-Dpath=C:\\app", "-Dq=\"x\"", "-Dhash=#1"))
                .build();
        assertEquals("""
                -XX:AOTCache=app.aot
                "-Dgreeting=hello world"
                "-Dpath=C:\\\\app"
                "-Dq=\\"x\\""
                "-Dhash=#1"
                """, AotLaunchOptions.argfile(settings));
    }

    @Test
    void theDriverGetsTheCacheJvmArgsAndEveryOtherTrainingSetting() {
        TrainingSettings training = TrainingSettings.builder()
                .readinessPath("/health")
                .workloadPaths(List.of("/hello", "/orders"))
                .workloadRepeat(3)
                .workloadCommand(List.of("warm", "up"))
                .stopPath("/stop")
                .jvmArgs(List.of("-Xlog:disable", "-Dprofile.only=true"))
                .environment(Map.of("ENDPOINTS_STOP_ENABLED", "true"))
                .portVariable("SERVER_PORT")
                .readinessTimeout(Duration.ofSeconds(90))
                .workloadTimeout(Duration.ofSeconds(45))
                .stopTimeout(Duration.ofSeconds(10))
                .build();
        AotCacheSettings settings = AotCacheSettings.builder().jvmArgs(List.of("-XX:+UseSerialGC")).build();

        TrainingSettings copy = AotLaunchOptions.trainingSettings(settings, training);

        assertEquals(List.of("-XX:+UseSerialGC"), copy.jvmArgs(), "the cache's jvmArgs, not training.jvmArgs");
        TrainingSettings expected = new TrainingSettings(training.readinessPath(), training.workloadPaths(),
                training.workloadRepeat(), training.workloadCommand(), training.runToExit(), training.stopPath(),
                List.of("-XX:+UseSerialGC"), training.environment(), training.portVariable(),
                training.readinessTimeout(), training.workloadTimeout(), training.stopTimeout());
        assertEquals(expected, copy, "every other setting is kept");
        assertEquals(List.of(), AotLaunchOptions.trainingSettings(AotCacheSettings.defaults(), training).jvmArgs());
    }

    @Test
    void theIdentityFileRoundTrips() throws IOException {
        JdkProbe jdk = new JdkProbe("27", "27", "Mac OS X", "aarch64", true);
        Map<String, String> identity = AotLaunchOptions.identity(jdk, jdk.creationFlags(),
                List.of("-Xmx512m", "-Dgreeting=hello world"), Map.of("target", "layout"));
        Path file = temp.resolve(AotLaunchOptions.IDENTITY_FILE);

        AotLaunchOptions.writeIdentity(file, identity);

        String content = Files.readString(file);
        assertTrue(content.contains("java.vm.version=27\n") && content.contains("os.name=Mac OS X\n")
                && content.contains("creationFlags=-XX\\:+UnlockDiagnosticVMOptions -XX\\:+AOTCompatibleOopCompression\n")
                && content.contains("target=layout\n") && !content.contains("2026"), content);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("java.vm.version", "27");
        expected.put("java.runtime.version", "27");
        expected.put("os.name", "Mac OS X");
        expected.put("os.arch", "aarch64");
        expected.put("creationFlags", "-XX:+UnlockDiagnosticVMOptions -XX:+AOTCompatibleOopCompression");
        expected.put("jvmArgs", "-Xmx512m \"-Dgreeting=hello world\"");
        expected.put("target", "layout");
        assertEquals(expected, identity);
        assertEquals(expected, AotLaunchOptions.readIdentity(file));
    }
}
