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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What a benchmark invocation ran on: the source revisions and the machine. Paths never enter a report as they
 * are; {@link #relocate} turns them into tokens.
 *
 * @param runnerSource        the Runner checkout's revision and clean/dirty state
 * @param sampleSource        the sample's revision and clean/dirty state
 * @param javaVersion         {@code java.version}
 * @param javaRuntimeVersion  {@code java.runtime.version}
 * @param javaVendor          {@code java.vendor}
 * @param javaVmName          {@code java.vm.name}
 * @param osName              {@code os.name}
 * @param osVersion           {@code os.version}
 * @param osArch              {@code os.arch}
 * @param availableProcessors the logical processors, captured before a CPU limit pins the harness
 * @param totalMemoryBytes    the physical memory, or {@code -1}
 */
record BenchmarkProvenance(SourceState runnerSource,
                           SourceState sampleSource,
                           String javaVersion,
                           String javaRuntimeVersion,
                           String javaVendor,
                           String javaVmName,
                           String osName,
                           String osVersion,
                           String osArch,
                           int availableProcessors,
                           long totalMemoryBytes) {

    /**
     * Captures the provenance of this invocation.
     *
     * @param runnerSource the Runner checkout, or {@code null} when unknown
     * @param sampleSource the sample's project directory
     * @return the provenance
     */
    static BenchmarkProvenance capture(Path runnerSource, Path sampleSource) {
        return machine(runnerSource == null ? SourceState.unavailable() : SourceState.captureRepository(runnerSource),
                SourceState.capture(sampleSource));
    }

    /**
     * This machine, with no source revision.
     *
     * @return the provenance
     */
    static BenchmarkProvenance unavailable() {
        return machine(SourceState.unavailable(), SourceState.unavailable());
    }

    private static BenchmarkProvenance machine(SourceState runnerSource, SourceState sampleSource) {
        long totalMemory = ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean extended ? extended.getTotalMemorySize() : -1;
        return new BenchmarkProvenance(runnerSource, sampleSource, property("java.version"),
                property("java.runtime.version"), property("java.vendor"), property("java.vm.name"),
                property("os.name"), property("os.version"), property("os.arch"),
                Runtime.getRuntime().availableProcessors(), totalMemory);
    }

    private static String property(String name) {
        return System.getProperty(name, "<unavailable>");
    }

    static List<InputIdentity> inputIdentities(Variant variant) throws IOException {
        List<InputIdentity> identities = new ArrayList<>(variant.launchInputs().size());
        for (int i = 0; i < variant.launchInputs().size(); i++) {
            identities.add(InputIdentity.capture("input:" + i, variant.launchInputs().get(i)));
        }
        return List.copyOf(identities);
    }

    /**
     * Replaces every path the report knows of with its token, longest path first: {@code ${java}},
     * {@code ${input:i}} and {@code ${workdir}} of the variant, then {@code ${sample}}, {@code ${work}},
     * {@code ${output}} and {@code ${user-home}}. Commands and failure text go through this one function, so they
     * name the same file the same way.
     *
     * @param text    a command argument or a failure message, or {@code null}
     * @param context the run, whose sample, work and output directories are replaced
     * @param variant the variant the text belongs to, or {@code null}
     * @return the relocated text, or {@code null}
     */
    static String relocate(String text, RunContext context, Variant variant) {
        if (text == null) {
            return null;
        }
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put(absolute(SampleBuild.javaExecutable()), "${java}");
        if (variant != null) {
            for (int i = 0; i < variant.launchInputs().size(); i++) {
                tokens.putIfAbsent(absolute(variant.launchInputs().get(i)), "${input:" + i + "}");
            }
            if (variant.workingDirectory() != null) {
                tokens.putIfAbsent(absolute(variant.workingDirectory()), "${workdir}");
            }
        }
        tokens.putIfAbsent(absolute(context.sample()), "${sample}");
        tokens.putIfAbsent(absolute(context.workDirectory()), "${work}");
        tokens.putIfAbsent(absolute(context.outputDirectory()), "${output}");
        String home = System.getProperty("user.home", "");
        if (home.length() > 1) {
            tokens.putIfAbsent(absolute(Path.of(home)), "${user-home}");
        }
        String relocated = text;
        for (Map.Entry<String, String> token : tokens.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length())
                        .reversed())
                .toList()) {
            relocated = relocated.replace(token.getKey(), token.getValue());
        }
        return relocated;
    }

    /**
     * Relocates every argument of a command; see {@link #relocate(String, RunContext, Variant)}.
     *
     * @param command the command
     * @param context the run
     * @param variant the variant the command launches
     * @return the relocated command
     */
    static List<String> relocate(List<String> command, RunContext context, Variant variant) {
        return command.stream().map(argument -> relocate(argument, context, variant)).toList();
    }

    private static String absolute(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    /**
     * Revision and dirty state only; source checkout paths are intentionally not retained.
     *
     * @param revision the commit, or {@code <unavailable>}
     * @param state    {@code clean}, {@code dirty} or {@code unavailable}
     */
    record SourceState(String revision, String state) {

        static SourceState captureRepository(Path directory) {
            String root = git(directory, "rev-parse", "--show-toplevel");
            if (root == null || root.isBlank()) {
                return unavailable();
            }
            return capture(Path.of(root.trim()));
        }

        static SourceState capture(Path directory) {
            String revision = git(directory, "rev-parse", "HEAD");
            if (revision == null || !revision.matches("[0-9a-fA-F]{40}")) {
                return unavailable();
            }
            String status = git(directory, "status", "--porcelain", "--", ".");
            if (status == null) {
                return new SourceState(revision, "unavailable");
            }
            return new SourceState(revision, status.isBlank() ? "clean" : "dirty");
        }

        static SourceState unavailable() {
            return new SourceState("<unavailable>", "unavailable");
        }

        private static String git(Path directory, String... arguments) {
            List<String> command = new ArrayList<>(arguments.length + 1);
            command.add("git");
            command.addAll(List.of(arguments));
            return runCommand(directory, command, Duration.ofSeconds(5));
        }

        static String runCommand(Path directory, List<String> command, Duration timeout) {
            Process process = null;
            try {
                process = new ProcessBuilder(command)
                        .directory(directory.toAbsolutePath().normalize().toFile())
                        .redirectErrorStream(true)
                        .start();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                AtomicReference<IOException> readFailure = new AtomicReference<>();
                Process running = process;
                Thread drain = new Thread(() -> {
                    try (InputStream input = running.getInputStream()) {
                        input.transferTo(output);
                    } catch (IOException e) {
                        readFailure.set(e);
                    }
                }, "benchmark-provenance-command-output");
                drain.setDaemon(true);
                drain.start();
                long timeoutMillis = Math.max(1, timeout.toMillis());
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
                    closeQuietly(process.getInputStream());
                    drain.join(timeoutMillis);
                    return null;
                }
                drain.join(timeoutMillis);
                if (drain.isAlive() || readFailure.get() != null) {
                    closeQuietly(process.getInputStream());
                    return null;
                }
                return process.exitValue() == 0 ? output.toString(StandardCharsets.UTF_8).trim() : null;
            } catch (IOException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    closeQuietly(process.getInputStream());
                }
            }
        }

        private static void closeQuietly(InputStream input) {
            try {
                input.close();
            } catch (IOException ignored) {
                // Best effort: this path is already handling an unavailable provenance command.
            }
        }
    }

    /**
     * Content identity with a relocatable ordinal rather than a filesystem path. A directory is hashed file by
     * file, each relative path with its bytes, in sorted order.
     *
     * @param id     the ordinal, for example {@code input:0}
     * @param kind   {@code file}, {@code directory} or {@code missing}
     * @param bytes  the content length, {@code 0} for a directory, {@code -1} when missing
     * @param sha256 the lowercase hexadecimal digest, or {@code <unavailable>}
     */
    record InputIdentity(String id, String kind, long bytes, String sha256) {

        static InputIdentity capture(String id, Path path) throws IOException {
            MessageDigest digest = sha256Digest();
            long bytes;
            String kind;
            if (Files.isRegularFile(path)) {
                kind = "file";
                bytes = digestFile(digest, path);
            } else if (Files.isDirectory(path)) {
                kind = "directory";
                bytes = 0;
                List<Path> files;
                try (var walk = Files.walk(path)) {
                    files = walk.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(file -> path.relativize(file).toString()))
                            .toList();
                }
                for (Path file : files) {
                    String relative = path.relativize(file).toString().replace('\\', '/');
                    digest.update(relative.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    bytes += digestFile(digest, file);
                    digest.update((byte) 0);
                }
            } else {
                return new InputIdentity(id, "missing", -1, "<unavailable>");
            }
            return new InputIdentity(id, kind, bytes, HexFormat.of().formatHex(digest.digest()));
        }

        private static long digestFile(MessageDigest digest, Path file) throws IOException {
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    total += read;
                }
            }
            return total;
        }

        private static MessageDigest sha256Digest() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError("Every Java runtime provides SHA-256", e);
            }
        }
    }
}
