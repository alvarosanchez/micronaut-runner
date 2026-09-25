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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The page-cache modes: parsing and fail-fast validation on injected inputs, and the eviction set. */
class PageCacheModeTest {

    @Test
    void theThreeModesParseAndAnythingElseIsRejected(@TempDir Path output) {
        assertEquals(PageCacheMode.UNCONTROLLED, PageCacheMode.parse("uncontrolled"));
        assertEquals(PageCacheMode.EVICT_ARTIFACTS, PageCacheMode.parse("evict-artifacts"));
        assertEquals(PageCacheMode.DROP_ALL, PageCacheMode.parse("drop-all"));
        for (String unknown : new String[] {"", "cold", "EVICT-ARTIFACTS", "evict_artifacts"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> PageCacheMode.parse(unknown), unknown);
            assertTrue(failure.getMessage().contains("uncontrolled, evict-artifacts, drop-all"),
                    failure.getMessage());
        }

        String[] base = {"--sample", output.toString(), "--repo", "file:/repo", "--version", "1.0",
                "--iterations", "1", "--out", output.toString()};
        assertEquals(PageCacheMode.UNCONTROLLED, StartupBenchmark.Options.parse(base).pageCache());
        assertEquals(PageCacheMode.EVICT_ARTIFACTS,
                StartupBenchmark.Options.parse(append(base, "--page-cache", "evict-artifacts")).pageCache());
        assertThrows(IllegalArgumentException.class,
                () -> StartupBenchmark.Options.parse(append(base, "--page-cache", "cold")));
    }

    @Test
    void unsupportedCombinationsFailFastWithoutFallbacks() {
        AtomicInteger sudoProbes = new AtomicInteger();

        PageCacheMode.UNCONTROLLED.validate("Mac OS X", () -> sudoProbes.incrementAndGet() < 0);
        PageCacheMode.UNCONTROLLED.validate("Windows 11", () -> sudoProbes.incrementAndGet() < 0);
        assertEquals(0, sudoProbes.get(), "uncontrolled never probes sudo");

        assertDoesNotThrow(() -> PageCacheMode.EVICT_ARTIFACTS.validate("Linux", () -> false));
        for (String os : new String[] {"Mac OS X", "Windows 11"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> PageCacheMode.EVICT_ARTIFACTS.validate(os, () -> true), os);
            assertTrue(failure.getMessage().contains("needs Linux"), failure.getMessage());
        }

        assertDoesNotThrow(() -> PageCacheMode.DROP_ALL.validate("Linux", () -> true));
        assertDoesNotThrow(() -> PageCacheMode.DROP_ALL.validate("Mac OS X", () -> true));
        for (String os : new String[] {"Linux", "Mac OS X"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> PageCacheMode.DROP_ALL.validate(os, () -> false), os);
            assertTrue(failure.getMessage().contains("sudo -n true"), failure.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> PageCacheMode.DROP_ALL.validate("Windows 11", () -> true));
    }

    @Test
    void eachModeNamesItsEvictionMethod() {
        assertNull(PageCacheMode.UNCONTROLLED.evictionMethod("Linux"));
        assertEquals("posix_fadvise(POSIX_FADV_DONTNEED) per file", PageCacheMode.EVICT_ARTIFACTS.evictionMethod("Linux"));
        assertEquals("drop_caches=3", PageCacheMode.DROP_ALL.evictionMethod("Linux"));
        assertEquals("purge", PageCacheMode.DROP_ALL.evictionMethod("Mac OS X"));
    }

    @Test
    void uncontrolledAddsNoHookAndEveryOtherModeFailsASurvivingChild() {
        assertSame(StartupHarness.BeforeLaunch.NONE, PageCacheEviction.forMode(PageCacheMode.UNCONTROLLED, "Linux"));
        assertFalse(StartupHarness.BeforeLaunch.NONE.evicts());
        assertTrue(PageCacheEviction.forMode(PageCacheMode.EVICT_ARTIFACTS, "Linux").evicts());
        assertTrue(PageCacheEviction.forMode(PageCacheMode.DROP_ALL, "Linux").evicts());
    }

    @Test
    void theEvictionSetIsEveryRegularFileOfTheInputsAndArtifactButNotTheWorkingDirectory(@TempDir Path work)
            throws IOException {
        Path layout = Files.createDirectories(work.resolve("extracted"));
        Path application = Files.writeString(layout.resolve("app.jar"), "app", StandardCharsets.UTF_8);
        Path lib = Files.createDirectories(layout.resolve("lib"));
        Path dependency = Files.writeString(lib.resolve("dep.jar"), "dep", StandardCharsets.UTF_8);
        Path cache = Files.writeString(Files.createDirectories(work.resolve("managed-aot/abc")).resolve("app.aot"),
                "cache", StandardCharsets.UTF_8);
        Path neighbour = Files.writeString(work.resolve("runner-preserve.jar"), "other row", StandardCharsets.UTF_8);
        Path outside = Files.writeString(Files.createDirectories(work.resolve("outside")).resolve("secret.jar"),
                "outside", StandardCharsets.UTF_8);
        boolean linked;
        try {
            Files.createSymbolicLink(lib.resolve("linked"), outside.getParent());
            linked = true;
        } catch (IOException | UnsupportedOperationException e) {
            linked = false;
        }
        Variant variant = new Variant("runner-extracted-aot", "fixture", List.of("java", "-jar", "app.jar"), work,
                layout, null, EntryMode.STANDARD_LOADER, EntryMode.STANDARD_LOADER, true, null,
                List.of(application, dependency, cache), null);

        List<Path> files = PageCacheEviction.evictionSet(variant);

        assertEquals(List.of(application.toAbsolutePath().normalize(), dependency.toAbsolutePath().normalize(),
                cache.toAbsolutePath().normalize()), files);
        assertFalse(files.contains(neighbour.toAbsolutePath().normalize()), "the working directory is not walked");
        if (linked) {
            assertFalse(files.stream().anyMatch(file -> file.toString().contains("secret")),
                    "symbolic links are not followed: " + files);
        }
    }

    /**
     * The eviction call on a real file. {@code tmpfs} ignores eviction, so the file lives under the module's
     * {@code build/} directory rather than {@code java.io.tmpdir}.
     */
    @Test
    @Tag("benchmark-integration")
    @EnabledOnOs(OS.LINUX)
    void evictionDropsAWrittenAndReadFileFromThePageCache() throws Exception {
        assumeTrue(CpuLimit.onPath("fincore", System.getenv("PATH")), "fincore is not installed");
        Path directory = Files.createDirectories(Path.of("build", "tmp", "pageCacheEviction")).toAbsolutePath();
        Path file = directory.resolve("evict-me.bin");
        try {
            byte[] chunk = new byte[1024 * 1024];
            for (int i = 0; i < chunk.length; i++) {
                chunk[i] = (byte) (i * 31);
            }
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (int i = 0; i < 64; i++) {
                    ByteBuffer buffer = ByteBuffer.wrap(chunk);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                }
                // Dirty pages are not dropped by POSIX_FADV_DONTNEED: write them back first.
                channel.force(true);
            }
            try (InputStream in = Files.newInputStream(file)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            assertTrue(residentBytes(file) > 0, "a file just read is resident");

            PageCacheEviction.evict(file);

            assertEquals(0, residentBytes(file), "nothing of the file is resident after eviction");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static long residentBytes(Path file) {
        Commands.Result result = Commands.run(List.of("fincore", "--bytes", "--noheadings", "--output", "RES",
                file.toString()), Map.of(), Duration.ofSeconds(30));
        assertEquals(0, result.exitCode(), result.output());
        return Long.parseLong(result.output().trim());
    }

    private static String[] append(String[] arguments, String... more) {
        String[] result = new String[arguments.length + more.length];
        System.arraycopy(arguments, 0, result, 0, arguments.length);
        System.arraycopy(more, 0, result, arguments.length, more.length);
        return result;
    }
}
