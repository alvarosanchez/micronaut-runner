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

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Evicts the OS page cache before each launch, for {@link PageCacheMode#EVICT_ARTIFACTS} and
 * {@link PageCacheMode#DROP_ALL}. The harness calls it immediately before its clock starts, so eviction is never
 * part of a timed interval.
 *
 * <p>{@code evict-artifacts} evicts every regular file under the variant's launch inputs and artifact, walking
 * directories without following symbolic links: the jar, the AOT cache file, the thin and exploded {@code lib/}
 * JARs and class trees, and the whole extracted layout. It never walks the working directory, which for Runner
 * rows is the shared work directory holding every other row and cache, and it leaves the JDK cached, so only the
 * packaging differs between rows. The downcalls run in the harness JVM only; no application command carries
 * {@code --enable-native-access}.</p>
 */
final class PageCacheEviction implements StartupHarness.BeforeLaunch {

    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);

    private final PageCacheMode mode;
    private final boolean macOs;
    private final Map<String, List<Path>> evictionSets = new HashMap<>();

    private PageCacheEviction(PageCacheMode mode, String osName) {
        this.mode = mode;
        this.macOs = PageCacheMode.macOs(osName);
    }

    /**
     * The hook for a mode.
     *
     * @param mode   the page-cache mode
     * @param osName the {@code os.name} system property
     * @return {@link StartupHarness.BeforeLaunch#NONE} for {@code uncontrolled}, otherwise an evicting hook
     */
    static StartupHarness.BeforeLaunch forMode(PageCacheMode mode, String osName) {
        return mode == PageCacheMode.UNCONTROLLED ? StartupHarness.BeforeLaunch.NONE
                : new PageCacheEviction(mode, osName);
    }

    @Override
    public void run(Variant variant) throws IOException {
        switch (mode) {
            case EVICT_ARTIFACTS -> {
                List<Path> files = evictionSets.get(variant.name());
                if (files == null) {
                    files = evictionSet(variant);
                    evictionSets.put(variant.name(), files);
                }
                for (Path file : files) {
                    evict(file);
                }
            }
            case DROP_ALL -> dropAll(macOs);
            case UNCONTROLLED -> {
                // Never constructed for this mode.
            }
        }
    }

    /**
     * Every regular file under the variant's launch inputs and artifact, in a stable order, each once. Directories
     * are walked without following symbolic links, and the working directory is never walked.
     *
     * @param variant the variant about to be launched
     * @return the files to evict
     * @throws IOException if a directory cannot be walked
     */
    static List<Path> evictionSet(Variant variant) throws IOException {
        List<Path> roots = new ArrayList<>(variant.launchInputs());
        if (variant.artifact() != null) {
            roots.add(variant.artifact());
        }
        Set<Path> files = new LinkedHashSet<>();
        for (Path root : roots) {
            Path normalized = root.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                files.add(normalized);
            } else if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                try (var walk = Files.walk(normalized)) {
                    walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .sorted()
                            .forEach(files::add);
                }
            }
        }
        return List.copyOf(files);
    }

    /**
     * Writes every dirty page back once after preparation: {@code POSIX_FADV_DONTNEED} does not drop dirty pages,
     * and every artifact was just written.
     *
     * @throws IOException if {@code sync} fails
     */
    static void sync() throws IOException {
        require(List.of("sync"));
    }

    /**
     * Whether {@code sudo -n true} succeeds, which {@code drop-all} needs.
     *
     * @return {@code true} when sudo needs no password
     */
    static boolean passwordlessSudo() {
        return Commands.run(List.of("sudo", "-n", "true"), Map.of(), Duration.ofSeconds(30)).exitCode() == 0;
    }

    /**
     * Drops the file's clean pages from the page cache: {@code open}, {@code posix_fadvise(POSIX_FADV_DONTNEED)}
     * over the whole file, {@code close}. Linux only.
     *
     * @param file the file
     * @throws IOException if {@code open} or {@code posix_fadvise} fails
     */
    static void evict(Path file) throws IOException {
        Fadvise.dontNeed(file);
    }

    private static void dropAll(boolean macOs) throws IOException {
        sync();
        require(macOs ? List.of("sudo", "-n", "purge")
                : List.of("sudo", "-n", "sh", "-c", "echo 3 > /proc/sys/vm/drop_caches"));
    }

    private static void require(List<String> command) throws IOException {
        Commands.Result result = Commands.run(command, Map.of(), COMMAND_TIMEOUT);
        if (result.exitCode() != 0) {
            throw new IOException(String.join(" ", command) + " exited with " + result.exitCode() + ": "
                    + result.output().trim());
        }
    }

    /** The libc downcalls, linked once and only when a file is first evicted. */
    private static final class Fadvise {

        private static final int O_RDONLY = 0;
        private static final int POSIX_FADV_DONTNEED = 4;

        private static final MethodHandle OPEN;
        private static final MethodHandle POSIX_FADVISE;
        private static final MethodHandle CLOSE;

        static {
            Linker linker = Linker.nativeLinker();
            SymbolLookup libc = linker.defaultLookup();
            // open is variadic: the mode argument is only passed with O_CREAT, so no variadic argument follows.
            OPEN = linker.downcallHandle(libc.find("open").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), Linker.Option.firstVariadicArg(2));
            POSIX_FADVISE = linker.downcallHandle(libc.find("posix_fadvise").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT));
            CLOSE = linker.downcallHandle(libc.find("close").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        }

        private Fadvise() {
        }

        static void dontNeed(Path file) throws IOException {
            try (Arena arena = Arena.ofConfined()) {
                int fd = (int) OPEN.invokeExact(arena.allocateFrom(file.toString()), O_RDONLY);
                if (fd < 0) {
                    throw new IOException("open(" + file + ", O_RDONLY) failed while evicting it");
                }
                try {
                    // offset 0, length 0: the whole file. posix_fadvise returns the error number itself.
                    int rc = (int) POSIX_FADVISE.invokeExact(fd, 0L, 0L, POSIX_FADV_DONTNEED);
                    if (rc != 0) {
                        throw new IOException("posix_fadvise(" + file + ", POSIX_FADV_DONTNEED) returned " + rc);
                    }
                } finally {
                    int ignored = (int) CLOSE.invokeExact(fd);
                }
            } catch (IOException | RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new IOException("could not evict " + file, e);
            }
        }
    }
}
