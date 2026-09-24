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

import io.micronaut.runner.build.Compression;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchInputsTest {

    private static final String JDK = "25.0.4.1+0|25.0.4.1+0";
    private static final String VM = "OpenJDK 64-Bit Server VM";
    private static final String ARCH = "aarch64";
    private static final List<String> FLAGS = List.of("AOTCacheOutput", "AOTCache", "-jar", "readiness=/hello");

    @Test
    void recopyingAnUnchangedInputKeepsTheJdkVisibleSizeAndTime(@TempDir Path directory) throws Exception {
        Path source = Files.writeString(directory.resolve("dependency.jar"), "dependency", StandardCharsets.UTF_8);
        Path lib = Files.createDirectories(directory.resolve("run").resolve("lib"));

        LaunchInputs.JdkView first = LaunchInputs.jdkView(LaunchInputs.copy(source, lib.resolve("dependency.jar")));

        // The next run: Gradle touched the source, and the variant's directory is recreated before the copy.
        Files.setLastModifiedTime(source, now());
        deleteTree(directory.resolve("run"));
        Files.createDirectories(lib);
        Path plain = Files.copy(source, lib.resolve("dependency.jar"));
        assertNotEquals(first.modified(), Files.getLastModifiedTime(plain),
                "a plain copy moves the time the JDK checks, which is what invalidated trained caches");
        Files.delete(plain);
        LaunchInputs.JdkView second = LaunchInputs.jdkView(LaunchInputs.copy(source, lib.resolve("dependency.jar")));

        assertEquals(first, second);
        assertEquals(LaunchInputs.PINNED_MODIFICATION_TIME, second.modified());
        assertEquals(Files.size(source), second.size());
        assertEquals(first.modified().to(java.util.concurrent.TimeUnit.SECONDS),
                second.modified().to(java.util.concurrent.TimeUnit.SECONDS),
                "the JDK records whole seconds; they match too");
    }

    @Test
    void rebuiltRunnerJarKeepsItsBytesAndItsJdkVisibleSizeAndTime(@TempDir Path directory) throws Exception {
        Path classes = Path.of(AotCacheFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        Variant first = SampleBuild.runnerJar(directory, "runner-stored", AotCacheFixture.class.getName(),
                List.of(classes), List.of(), Compression.STORED, EntryMode.STUB);
        byte[] firstBytes = Files.readAllBytes(first.artifact());
        LaunchInputs.JdkView firstView = LaunchInputs.jdkView(first.artifact());
        Variant second = SampleBuild.runnerJar(directory, "runner-stored", AotCacheFixture.class.getName(),
                List.of(classes), List.of(), Compression.STORED, EntryMode.STUB);

        assertArrayEquals(firstBytes, Files.readAllBytes(second.artifact()),
                "the harness rebuilds runner-stored.jar in every run; reuse needs the same bytes");
        assertEquals(firstView, LaunchInputs.jdkView(second.artifact()));
        assertEquals(LaunchInputs.PINNED_MODIFICATION_TIME, firstView.modified());
    }

    @Test
    void changingOneDependencysBytesRetrainsOnlyTheRowsThatLaunchIt(@TempDir Path directory) throws Exception {
        Path sources = Files.createDirectories(directory.resolve("sample"));
        Path application = Files.writeString(sources.resolve("application.jar"), "application",
                StandardCharsets.UTF_8);
        Path changed = Files.writeString(sources.resolve("changed.jar"), "class-v1", StandardCharsets.UTF_8);
        Path stable = Files.writeString(sources.resolve("stable.jar"), "stable", StandardCharsets.UTF_8);
        Path cacheRoot = directory.resolve("managed-aot");

        Run firstRun = prepare(directory.resolve("first"), application, changed, stable);
        String affected = AotCache.identity(firstRun.affectedRow(), JDK, VM, ARCH, FLAGS);
        String unaffected = AotCache.identity(firstRun.unaffectedRow(), JDK, VM, ARCH, FLAGS);
        train(AotCache.cacheFile(cacheRoot, affected));
        train(AotCache.cacheFile(cacheRoot, unaffected));

        // A rebuilt sample with one class changed: same length, different bytes.
        Files.writeString(changed, "class-v2", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(changed, now());
        Run secondRun = prepare(directory.resolve("second"), application, changed, stable);

        assertEquals(LaunchInputs.jdkView(firstRun.changedCopy()), LaunchInputs.jdkView(secondRun.changedCopy()),
                "size and pinned time cannot tell the two contents apart; the content identity must");
        String affectedAgain = AotCache.identity(secondRun.affectedRow(), JDK, VM, ARCH, FLAGS);
        String unaffectedAgain = AotCache.identity(secondRun.unaffectedRow(), JDK, VM, ARCH, FLAGS);
        assertNotEquals(affected, affectedAgain);
        assertFalse(AotCache.hasCandidate(AotCache.cacheFile(cacheRoot, affectedAgain)),
                "the row that launches the changed dependency finds no cache under its new identity and trains");
        assertTrue(AotCache.hasCandidate(AotCache.cacheFile(cacheRoot, affected)),
                "the old training stays in its own directory, never selected for the new bytes");
        assertEquals(unaffected, unaffectedAgain);
        assertTrue(AotCache.hasCandidate(AotCache.cacheFile(cacheRoot, unaffectedAgain)),
                "the row that does not launch it keeps its identity and reuses its cache");
    }

    @Test
    void identityPinsEveryInputItHashes(@TempDir Path directory) throws Exception {
        Path application = Files.writeString(directory.resolve("app.jar"), "application", StandardCharsets.UTF_8);
        Path dependency = Files.writeString(directory.resolve("dependency.jar"), "dependency",
                StandardCharsets.UTF_8);
        Files.setLastModifiedTime(application, now());
        Files.setLastModifiedTime(dependency, now());

        String identity = AotCache.identity(List.of(application, dependency), JDK, VM, ARCH, FLAGS);

        assertEquals(LaunchInputs.PINNED_MODIFICATION_TIME, Files.getLastModifiedTime(application));
        assertEquals(LaunchInputs.PINNED_MODIFICATION_TIME, Files.getLastModifiedTime(dependency));
        Files.setLastModifiedTime(dependency, now());
        assertEquals(identity, AotCache.identity(List.of(application, dependency), JDK, VM, ARCH, FLAGS),
                "the time is not part of the identity");
    }

    @Test
    void pinCoversEveryFileBelowADirectoryAndRejectsMissingInputs(@TempDir Path directory) throws Exception {
        Path classes = Files.createDirectories(directory.resolve("app-0").resolve("com").resolve("example"));
        Path clazz = Files.writeString(classes.resolve("Application.class"), "class", StandardCharsets.UTF_8);
        Path resource = Files.writeString(directory.resolve("app-0").resolve("application.properties"), "a=b",
                StandardCharsets.UTF_8);

        LaunchInputs.pin(List.of(directory.resolve("app-0")));

        assertEquals(LaunchInputs.PINNED_MODIFICATION_TIME, Files.getLastModifiedTime(clazz));
        assertEquals(LaunchInputs.PINNED_MODIFICATION_TIME, Files.getLastModifiedTime(resource));
        IOException missing = assertThrows(IOException.class,
                () -> LaunchInputs.pin(directory.resolve("absent.jar")));
        assertTrue(missing.getMessage().contains("absent.jar"), missing.getMessage());
    }

    /** One run's copies: two rows share the application; only the first also launches the changed jar. */
    private record Run(Path changedCopy, List<Path> affectedRow, List<Path> unaffectedRow) {
    }

    private static Run prepare(Path run, Path application, Path changed, Path stable) throws IOException {
        Path lib = Files.createDirectories(run.resolve("lib"));
        Path applicationCopy = LaunchInputs.copy(application, run.resolve("application.jar"));
        Path changedCopy = LaunchInputs.copy(changed, lib.resolve("changed.jar"));
        Path stableCopy = LaunchInputs.copy(stable, lib.resolve("stable.jar"));
        return new Run(changedCopy, List.of(applicationCopy, changedCopy), List.of(applicationCopy, stableCopy));
    }

    private static void train(Path cache) throws IOException {
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, "trained", StandardCharsets.UTF_8);
    }

    private static FileTime now() {
        return FileTime.from(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private static void deleteTree(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
