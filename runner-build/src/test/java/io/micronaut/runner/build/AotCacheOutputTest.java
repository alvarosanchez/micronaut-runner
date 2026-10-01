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

import io.micronaut.runner.RunnerClassLoader;
import io.micronaut.runner.build.aotcache.AotCacheReport;
import io.micronaut.runner.build.aotcache.AotCacheSettings;
import io.micronaut.runner.build.training.TrainingSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single-JAR target without forks: its warning, decided on canned reports whose counts are those the guard
 * job measured for benchmark-large's single JAR on JDK 25 and 27 before the recording reported {@code file:} code
 * sources, and the refusal of the training property in the cache's JVM arguments.
 */
class AotCacheOutputTest {

    @TempDir
    Path directory;

    @Test
    void aJdkThatLeavesRunnerClassLoadersClassesOutWarnsAndRewritesTheReport() throws IOException {
        // JDK 27 on benchmark-large: 13 of 2,304 io.micronaut classes, all of them the launcher's own.
        AotCacheReport report = report(13, 2304);
        report.write(directory);
        Logged log = new Logged();

        AotCacheReport warned = AotCacheOutput.warnUnlessRunnerClassesAreCached(report, directory, log);

        assertEquals(List.of(AotCacheOutput.SINGLE_JAR_WARNING), warned.warnings());
        assertTrue(AotCacheOutput.SINGLE_JAR_WARNING.contains("JDK-8380291"), AotCacheOutput.SINGLE_JAR_WARNING);
        assertEquals(List.of(AotCacheOutput.SINGLE_JAR_WARNING), log.warnings);
        assertEquals(warned.toJson(), Files.readString(directory.resolve(AotCacheReport.FILE)),
                "the report on disk carries the warning");
        assertTrue(warned.passed(), "the warning does not fail the build");
    }

    @Test
    void aJdkThatCachesMostOfThemDoesNotWarn() throws IOException {
        // JDK 25 on benchmark-large: 1,848 of 2,224.
        AotCacheReport report = report(1848, 2224);
        Logged log = new Logged();

        assertSame(report, AotCacheOutput.warnUnlessRunnerClassesAreCached(report, directory, log));
        assertTrue(log.warnings.isEmpty(), log.warnings::toString);
        assertFalse(Files.exists(directory.resolve(AotCacheReport.FILE)), "the report is not written again");
    }

    @Test
    void theRecordingOnlyArgumentIsTheLaunchersTrainingProperty() {
        assertEquals("-D" + RunnerClassLoader.AOT_TRAINING_PROPERTY + "=true", AotCacheOutput.AOT_TRAINING_ARGUMENT);
        assertEquals("-Dmicronaut.runner.aot.training=true", AotCacheOutput.AOT_TRAINING_ARGUMENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-Dmicronaut.runner.aot.training=true", "-Dmicronaut.runner.aot.training",
        "-Dmicronaut.runner.aot.training=false"})
    void writeRefusesTheTrainingPropertyInTheCacheJvmArgsBeforeStagingAnything(String argument) throws Exception {
        Path out = directory.resolve("out");
        Files.createDirectories(out);
        Path kept = Files.writeString(out.resolve("kept.txt"), "not replaced");
        AotCacheSettings settings = AotCacheSettings.builder().jvmArgs(List.of("-XX:+UseG1GC", argument)).build();

        for (AotTarget target : AotTarget.values()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> AotCacheOutput.write(target, settings, directory.resolve("no-java"),
                            directory.resolve("no-such.jar"), out, TrainingSettings.defaults(), new Logged()));
            assertTrue(failure.getMessage().contains(argument) && failure.getMessage().contains("app.jvmopts"),
                    failure.getMessage());
        }
        assertEquals("not replaced", Files.readString(kept), "the output directory was not touched");
        assertEquals(List.of(kept), listed(out));
    }

    @Test
    void otherPropertiesPassTheTrainingPropertyCheck() {
        AotCacheOutput.requireNoTrainingProperty(AotCacheSettings.builder()
                .jvmArgs(List.of("-Dmicronaut.runner.verify=true", "-Dmicronaut.runner.aot=x",
                        "-Dmicronaut.runner.aot.training.timeout=30", "-Dmicronaut.runner.aot.trainingx=true",
                        "-XX:+UseG1GC")).build());
    }

    @Test
    void halfOfThemIsEnoughAndLessIsNot() throws IOException {
        assertTrue(AotCacheOutput.warnUnlessRunnerClassesAreCached(report(5, 10), directory, new Logged())
                .warnings().isEmpty());
        assertTrue(AotCacheOutput.warnUnlessRunnerClassesAreCached(report(0, 0), directory, new Logged())
                .warnings().isEmpty(), "no io.micronaut class loaded, nothing to warn about");
        assertEquals(List.of(AotCacheOutput.SINGLE_JAR_WARNING),
                AotCacheOutput.warnUnlessRunnerClassesAreCached(report(4, 9), directory, new Logged()).warnings());
    }

    private static List<Path> listed(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.toList();
        }
    }

    private static AotCacheReport report(int micronautFromCache, int micronautLoaded) {
        return new AotCacheReport("27+36", "Linux", "amd64", Map.of(AotCacheOutput.TARGET_LABEL, "singleJar"),
                List.of(), AotCacheReport.STOP_JCMD, 20, 0, 5763, 2075, 2075 / 5763.0, micronautLoaded,
                micronautFromCache, List.of(), 458, List.of(), List.of(), AotCacheReport.PASSED);
    }

    /** Keeps the warnings. */
    private static final class Logged implements BuildLogger {

        private final List<String> warnings = new ArrayList<>();

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }
    }
}
