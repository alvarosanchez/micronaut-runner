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

import io.micronaut.runner.build.BuildLogger;
import io.micronaut.runner.build.training.TrainingSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The recording's arguments and the caller's record-only ones, checked without a JVM: the blank-argument cases name
 * a {@code java} that does not exist, which only a launch would find out.
 */
class AotCacheBuilderArgumentsTest {

    private static final String JAR = "app.jar";

    @TempDir
    Path temp;

    @Test
    void theRecordOnlyArgumentsComeAfterTheRecordingsOwnOptionsInTheirOrder() {
        assertEquals(List.of("-XX:AOTMode=record", "-XX:AOTConfiguration=app.aotconf", "-Xlog:aot=info", "-Dx=1"),
                AotCacheBuilder.recordArguments(List.of("-Dx=1")));
        assertEquals(List.of("-XX:AOTMode=record", "-XX:AOTConfiguration=app.aotconf", "-Xlog:aot=info", "-Dz=3",
                "-Dx=1", "-Xlog:class+load=info"),
                AotCacheBuilder.recordArguments(List.of("-Dz=3", "-Dx=1", "-Xlog:class+load=info")));
        assertEquals(List.of("-XX:AOTMode=record", "-XX:AOTConfiguration=app.aotconf", "-Xlog:aot=info"),
                AotCacheBuilder.recordArguments(List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "\n"})
    void aBlankRecordOnlyArgumentIsRejectedBeforeAnythingIsStaged(String blank) throws IOException {
        Path dir = earlierOutput();
        Map<String, String> before = contents(dir);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> build(dir, List.of("-Dx=1", blank)));

        assertEquals("recordJvmArgs must not contain a blank argument", failure.getMessage());
        assertEquals(before, contents(dir), "nothing in the output directory was deleted or written");
    }

    @Test
    void aMissingListOrArgumentIsRejectedBeforeAnythingIsStaged() throws IOException {
        Path dir = earlierOutput();
        Map<String, String> before = contents(dir);

        NullPointerException noList = assertThrows(NullPointerException.class, () -> build(dir, null));
        assertEquals("recordJvmArgs", noList.getMessage());
        assertThrows(NullPointerException.class, () -> build(dir, Arrays.asList("-Dx=1", null)));
        assertEquals(before, contents(dir), "nothing in the output directory was deleted or written");
    }

    private void build(Path dir, List<String> recordJvmArgs) throws IOException, InterruptedException {
        AotCacheBuilder.build(AotCacheSettings.defaults(), temp.resolve("no-such-jdk/bin/java"), dir, JAR,
                TrainingSettings.builder().readinessPath("/ready").build(), recordJvmArgs, Map.of(),
                BuildLogger.noOp());
    }

    /** An output directory as an earlier build left it, which a build deletes from before it trains. */
    private Path earlierOutput() throws IOException {
        Path dir = Files.createDirectories(temp.resolve("out"));
        Files.writeString(dir.resolve(JAR), "the application");
        for (String file : List.of(AotLaunchOptions.CACHE_FILE, AotLaunchOptions.ARGFILE,
                AotLaunchOptions.IDENTITY_FILE, AotCacheReport.FILE, AotCacheBuilder.RECORD_LOG,
                AotCacheBuilder.CREATE_LOG, AotCacheGate.SMOKE_LOG, AotCacheGate.CLASS_LOAD_LOG)) {
            Files.writeString(dir.resolve(file), "earlier " + file);
        }
        return dir;
    }

    private static Map<String, String> contents(Path dir) throws IOException {
        Map<String, String> contents = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                contents.put(file.getFileName().toString(), Files.readString(file));
            }
        }
        return contents;
    }
}
