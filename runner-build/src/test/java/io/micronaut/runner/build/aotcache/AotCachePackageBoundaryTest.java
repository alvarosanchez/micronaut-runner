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

import io.micronaut.runner.build.AotCacheOutput;
import io.micronaut.runner.build.AotLayout;
import io.micronaut.runner.build.AotTarget;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@code io.micronaut.runner.build.aotcache} generic: its sources import nothing of Runner's but the build
 * logger and the training driver, and every public type there, and the Runner-specific types built on it, is
 * {@code @Experimental}. It reads the sources, so it runs from the project directory, as Gradle runs tests.
 */
class AotCachePackageBoundaryTest {

    private static final Path SOURCES = Path.of("src/main/java/io/micronaut/runner/build");

    private static final Pattern RUNNER_IMPORT = Pattern.compile("^import\\s+(static\\s+)?(io\\.micronaut\\.runner\\.[\\w.*]+)\\s*;",
            Pattern.MULTILINE);

    private static final Pattern PUBLIC_TYPE = Pattern.compile(
            "^public\\s+(final\\s+|abstract\\s+|sealed\\s+)*(class|record|enum|interface)\\s+(\\w+)", Pattern.MULTILINE);

    @Test
    void thePackageImportsOnlyTheLoggerAndTheTrainingDriverOfRunner() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : sources()) {
            Matcher imports = RUNNER_IMPORT.matcher(Files.readString(source, StandardCharsets.UTF_8));
            while (imports.find()) {
                String imported = imports.group(2);
                boolean allowed = imported.equals("io.micronaut.runner.build.BuildLogger")
                        || imported.startsWith("io.micronaut.runner.build.training.");
                if (!allowed) {
                    offenders.add(source.getFileName() + " imports " + imported);
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "the aotcache package must stay generic: " + offenders);
    }

    @Test
    void everyPublicTypeIsExperimental() throws IOException {
        List<Path> checked = new ArrayList<>(sources());
        for (Class<?> type : List.of(AotLayout.class, AotCacheOutput.class, AotTarget.class)) {
            checked.add(SOURCES.resolve(type.getSimpleName() + ".java"));
        }
        List<String> missing = new ArrayList<>();
        for (Path source : checked) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            Matcher type = PUBLIC_TYPE.matcher(text);
            if (!type.find()) {
                continue;
            }
            String before = text.substring(0, type.start());
            if (!before.contains("@Experimental") || !text.contains("import io.micronaut.core.annotation.Experimental;")) {
                missing.add(type.group(3));
            }
        }
        assertFalse(checked.isEmpty());
        assertTrue(missing.isEmpty(), () -> "not @Experimental: " + missing);
    }

    private static List<Path> sources() throws IOException {
        Path directory = SOURCES.resolve("aotcache");
        assertTrue(Files.isDirectory(directory), () -> directory.toAbsolutePath() + " is not there: run the test from"
                + " the runner-build project directory");
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> sources = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !file.getFileName().toString().equals("package-info.java"))
                    .sorted()
                    .toList();
            assertFalse(sources.isEmpty());
            return sources;
        }
    }
}
