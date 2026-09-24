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
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Memory and loaded-class counts of one child JVM, read once after its readiness is final and outside the
 * timed interval. Every field is {@code -1} when it is unavailable on this platform or for this process.
 *
 * <p>Memory is read in-process, in microseconds: {@code /proc/<pid>/status} on Linux and one
 * {@code proc_pid_rusage} call on macOS. Class counts come from one {@code jstat -snap} of the child's JDK,
 * which reads the child's hsperfdata file without attaching to it and adds no flag to the child. Heap and
 * metaspace counters in hsperfdata update only at GC, so they are deliberately not read.</p>
 *
 * @param probeMillis        how long after readiness the snapshot was complete, set by the harness
 * @param rssBytes           resident set size: {@code VmRSS} on Linux, {@code ri_resident_size} on macOS
 * @param peakRssBytes       the lifetime peak resident set size, {@code VmHWM} (Linux only)
 * @param anonBytes          resident anonymous memory, {@code RssAnon} (Linux only): the private memory
 * @param fileBytes          resident file-backed memory, {@code RssFile} (Linux only)
 * @param footprintBytes     the physical footprint, {@code ri_phys_footprint} (macOS only): the private memory
 * @param peakFootprintBytes the lifetime peak physical footprint, {@code ri_lifetime_max_phys_footprint}
 *                           (macOS only)
 * @param loadedClasses      every class loaded so far, shared ones included
 * @param sharedClasses      the classes of those that came from a CDS or AOT archive
 */
record ReadinessSnapshot(double probeMillis,
                         long rssBytes,
                         long peakRssBytes,
                         long anonBytes,
                         long fileBytes,
                         long footprintBytes,
                         long peakFootprintBytes,
                         long loadedClasses,
                         long sharedClasses) {

    /** A snapshot of which nothing could be read. */
    static final ReadinessSnapshot UNAVAILABLE = new ReadinessSnapshot(-1, -1, -1, -1, -1, -1, -1, -1, -1);

    /** How long {@code jstat} may take before the class counts are given up. */
    private static final long JSTAT_TIMEOUT_SECONDS = 5;

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    /**
     * Copies this snapshot with the time at which it was complete.
     *
     * @param probeMillis milliseconds from readiness to the end of the snapshot
     * @return the copy
     */
    ReadinessSnapshot withProbeMillis(double probeMillis) {
        return new ReadinessSnapshot(probeMillis, rssBytes, peakRssBytes, anonBytes, fileBytes, footprintBytes,
                peakFootprintBytes, loadedClasses, sharedClasses);
    }

    /**
     * Reads memory first, which takes microseconds and is therefore effectively at readiness, then class
     * counts. It never throws: anything that cannot be read is {@code -1}.
     *
     * @param pid            the child JVM
     * @param javaExecutable the child's {@code java}, whose sibling {@code jstat} reads the class counts
     * @return the snapshot, with {@code probeMillis} left at {@code -1} for the caller to set
     */
    static ReadinessSnapshot take(long pid, Path javaExecutable) {
        ReadinessSnapshot memory = memory(pid);
        ReadinessSnapshot classes = classes(pid, javaExecutable);
        return new ReadinessSnapshot(-1, memory.rssBytes, memory.peakRssBytes, memory.anonBytes, memory.fileBytes,
                memory.footprintBytes, memory.peakFootprintBytes, classes.loadedClasses, classes.sharedClasses);
    }

    /**
     * The resident set size of a process, the one memory figure both platforms share.
     *
     * @param pid the process
     * @return {@code VmRSS} on Linux, {@code ri_resident_size} on macOS, and {@code -1} elsewhere or when the
     *         process cannot be read
     */
    static long rssBytes(long pid) {
        return memory(pid).rssBytes;
    }

    /** Whether memory is read the macOS way, which names the private and peak columns of the report. */
    static boolean macOs() {
        return OS.contains("mac") || OS.contains("darwin");
    }

    private static boolean linux() {
        return OS.contains("linux");
    }

    private static ReadinessSnapshot memory(long pid) {
        if (linux()) {
            try {
                return parseProcStatus(Files.readString(Path.of("/proc", Long.toString(pid), "status"),
                        StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                return UNAVAILABLE;
            }
        }
        if (macOs()) {
            return Darwin.rusage(pid);
        }
        return UNAVAILABLE;
    }

    private static ReadinessSnapshot classes(long pid, Path javaExecutable) {
        Path jstat = javaExecutable.resolveSibling("jstat");
        if (!Files.isExecutable(jstat)) {
            return UNAVAILABLE;
        }
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("readiness-jstat-", ".txt");
            ProcessBuilder builder = new ProcessBuilder(jstat.toString(), "-J-Djstat.showUnsupported=true",
                    "-snap", Long.toString(pid))
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            StartupHarness.removeInheritedJvmOptions(builder);
            process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(JSTAT_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return UNAVAILABLE;
            }
            return parseJstatSnap(Files.readString(output, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return UNAVAILABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UNAVAILABLE;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                    // A leftover temporary file does not invalidate the snapshot.
                }
            }
        }
    }

    /**
     * Parses the text of {@code /proc/<pid>/status}.
     *
     * @param status the file's text
     * @return a snapshot with only {@code rssBytes}, {@code peakRssBytes}, {@code anonBytes} and
     *         {@code fileBytes} set, each {@code -1} when its line is missing or unparsable
     */
    static ReadinessSnapshot parseProcStatus(String status) {
        long rss = -1;
        long peak = -1;
        long anon = -1;
        long file = -1;
        for (String line : status.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            switch (key) {
                case "VmRSS" -> rss = kilobytes(line.substring(colon + 1));
                case "VmHWM" -> peak = kilobytes(line.substring(colon + 1));
                case "RssAnon" -> anon = kilobytes(line.substring(colon + 1));
                case "RssFile" -> file = kilobytes(line.substring(colon + 1));
                default -> {
                    // Not recorded.
                }
            }
        }
        return new ReadinessSnapshot(-1, rss, peak, anon, file, -1, -1, -1, -1);
    }

    /**
     * Parses {@code jstat -J-Djstat.showUnsupported=true -snap} output. {@code java.cls.loadedClasses}
     * excludes the classes loaded from a shared archive, so the total is the sum of the two counters.
     *
     * @param snap jstat's standard output
     * @return a snapshot with only {@code loadedClasses} and {@code sharedClasses} set, each {@code -1} when
     *         a counter it needs is missing or unparsable
     */
    static ReadinessSnapshot parseJstatSnap(String snap) {
        long loaded = -1;
        long shared = -1;
        for (String line : snap.split("\n")) {
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            if (key.equals("java.cls.loadedClasses")) {
                loaded = count(line.substring(equals + 1));
            } else if (key.equals("java.cls.sharedLoadedClasses")) {
                shared = count(line.substring(equals + 1));
            }
        }
        long total = loaded < 0 || shared < 0 ? -1 : loaded + shared;
        return new ReadinessSnapshot(-1, -1, -1, -1, -1, -1, -1, total, shared);
    }

    /** {@code "   123456 kB"} as bytes, or {@code -1}. */
    private static long kilobytes(String value) {
        String trimmed = value.trim();
        if (!trimmed.endsWith(" kB")) {
            return -1;
        }
        long kilobytes = count(trimmed.substring(0, trimmed.length() - 3));
        return kilobytes < 0 ? -1 : kilobytes * 1024L;
    }

    /** A non-negative decimal integer, or {@code -1}. */
    private static long count(String value) {
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? -1 : parsed;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * {@code proc_pid_rusage}, which only exists on macOS: nothing else touches this class, so the downcall
     * handle is created lazily, once, and only there.
     */
    private static final class Darwin {

        /** {@code RUSAGE_INFO_V4}. */
        private static final int FLAVOR = 4;

        /** {@code sizeof(struct rusage_info_v4)}. */
        private static final long SIZE = 296;

        private static final long RESIDENT_SIZE = 64;
        private static final long PHYS_FOOTPRINT = 72;
        private static final long LIFETIME_MAX_PHYS_FOOTPRINT = 240;

        private static final MethodHandle PROC_PID_RUSAGE = handle();

        private Darwin() {
        }

        private static MethodHandle handle() {
            try {
                Linker linker = Linker.nativeLinker();
                return linker.defaultLookup().find("proc_pid_rusage")
                        .map(symbol -> linker.downcallHandle(symbol,
                                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)))
                        .orElse(null);
            } catch (RuntimeException e) {
                return null;
            }
        }

        static ReadinessSnapshot rusage(long pid) {
            if (PROC_PID_RUSAGE == null) {
                return UNAVAILABLE;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buffer = arena.allocate(SIZE, 8);
                int result = (int) PROC_PID_RUSAGE.invokeExact(Math.toIntExact(pid), FLAVOR, buffer);
                if (result != 0) {
                    return UNAVAILABLE;
                }
                return new ReadinessSnapshot(-1, buffer.get(JAVA_LONG, RESIDENT_SIZE), -1, -1, -1,
                        buffer.get(JAVA_LONG, PHYS_FOOTPRINT), buffer.get(JAVA_LONG, LIFETIME_MAX_PHYS_FOOTPRINT),
                        -1, -1);
            } catch (Throwable e) {
                return UNAVAILABLE;
            }
        }
    }
}
