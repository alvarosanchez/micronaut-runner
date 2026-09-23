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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Reproducible, deliberately bounded provenance for one benchmark invocation. */
final class BenchmarkProvenance {

    static final int SCHEMA_VERSION = 2;
    static final String REDACTED_JVM_OPTIONS = "<redacted:ambient-jvm-options>";
    private static final List<String> JVM_OPTION_ENVIRONMENT = List.of(
            "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");

    private final SourceState runnerSource;
    private final SourceState sampleSource;
    private final Map<String, Boolean> optionEnvironmentPresence;
    private final String javaVersion;
    private final String javaRuntimeVersion;
    private final String javaVendor;
    private final String javaVmName;
    private final String osName;
    private final String osVersion;
    private final String osArch;
    private final int availableProcessors;
    private final long totalMemoryBytes;

    private BenchmarkProvenance(SourceState runnerSource,
                                SourceState sampleSource,
                                Map<String, Boolean> optionEnvironmentPresence) {
        this.runnerSource = runnerSource;
        this.sampleSource = sampleSource;
        this.optionEnvironmentPresence = Collections.unmodifiableMap(new LinkedHashMap<>(optionEnvironmentPresence));
        this.javaVersion = System.getProperty("java.version", "<unavailable>");
        this.javaRuntimeVersion = System.getProperty("java.runtime.version", "<unavailable>");
        this.javaVendor = System.getProperty("java.vendor", "<unavailable>");
        this.javaVmName = System.getProperty("java.vm.name", "<unavailable>");
        this.osName = System.getProperty("os.name", "<unavailable>");
        this.osVersion = System.getProperty("os.version", "<unavailable>");
        this.osArch = System.getProperty("os.arch", "<unavailable>");
        this.availableProcessors = Runtime.getRuntime().availableProcessors();
        this.totalMemoryBytes = detectTotalMemoryBytes();
    }

    static BenchmarkProvenance capture(Path runnerSource, Path sampleSource, Map<String, String> environment) {
        Map<String, Boolean> optionPresence = new LinkedHashMap<>();
        for (String variable : JVM_OPTION_ENVIRONMENT) {
            optionPresence.put(variable, environment.containsKey(variable));
        }
        SourceState runnerState = runnerSource == null
                ? SourceState.unavailable() : SourceState.captureRepository(runnerSource);
        return new BenchmarkProvenance(runnerState, SourceState.capture(sampleSource), optionPresence);
    }

    static BenchmarkProvenance unavailable() {
        Map<String, Boolean> optionPresence = new LinkedHashMap<>();
        JVM_OPTION_ENVIRONMENT.forEach(variable -> optionPresence.put(variable, false));
        return new BenchmarkProvenance(SourceState.unavailable(), SourceState.unavailable(), optionPresence);
    }

    SourceState runnerSource() {
        return runnerSource;
    }

    SourceState sampleSource() {
        return sampleSource;
    }

    Map<String, Boolean> optionEnvironmentPresence() {
        return optionEnvironmentPresence;
    }

    String javaVersion() {
        return javaVersion;
    }

    String javaRuntimeVersion() {
        return javaRuntimeVersion;
    }

    String javaVendor() {
        return javaVendor;
    }

    String javaVmName() {
        return javaVmName;
    }

    String osName() {
        return osName;
    }

    String osVersion() {
        return osVersion;
    }

    String osArch() {
        return osArch;
    }

    int availableProcessors() {
        return availableProcessors;
    }

    long totalMemoryBytes() {
        return totalMemoryBytes;
    }

    static List<InputIdentity> inputIdentities(Variant variant) throws IOException {
        List<InputIdentity> identities = new ArrayList<>(variant.launchInputs().size());
        for (int i = 0; i < variant.launchInputs().size(); i++) {
            identities.add(InputIdentity.capture("input:" + i, variant.launchInputs().get(i)));
        }
        return List.copyOf(identities);
    }

    static List<String> relocatableCommand(Variant variant) {
        return relocatableCommand(variant, List.of());
    }

    static List<String> relocatableCommand(Variant variant, List<String> extraJvmArguments) {
        List<Replacement> replacements = new ArrayList<>();
        replacements.add(new Replacement(SampleBuild.javaExecutable().toAbsolutePath().normalize().toString(),
                "${java}"));
        for (int i = 0; i < variant.launchInputs().size(); i++) {
            replacements.add(new Replacement(variant.launchInputs().get(i).toAbsolutePath().normalize().toString(),
                    "${input:" + i + "}"));
        }
        if (variant.workingDirectory() != null) {
            replacements.add(new Replacement(variant.workingDirectory().toAbsolutePath().normalize().toString(),
                    "${workdir}"));
        }
        replacements.sort(Comparator.comparingInt((Replacement replacement) -> replacement.value().length())
                .reversed());
        String home = System.getProperty("user.home", "");
        List<String> effective = new ArrayList<>(variant.command().size() + extraJvmArguments.size());
        if (!variant.command().isEmpty()) {
            effective.add(variant.command().get(0));
            effective.addAll(extraJvmArguments);
            effective.addAll(variant.command().subList(1, variant.command().size()));
        }
        List<String> command = new ArrayList<>(effective.size());
        boolean redactNextValue = false;
        for (String original : effective) {
            String relocated = original;
            for (Replacement replacement : replacements) {
                relocated = relocated.replace(replacement.value(), replacement.token());
            }
            if (!home.isEmpty()) {
                relocated = relocated.replace(home, "${user-home-redacted}");
            }
            if (redactNextValue) {
                command.add("<redacted:command-value>");
                redactNextValue = false;
            } else {
                command.add(redactSensitiveArgument(relocated));
                redactNextValue = relocated.indexOf('=') < 0
                        && relocated.startsWith("--") && isSensitiveName(relocated);
            }
        }
        return List.copyOf(command);
    }

    private static String redactSensitiveArgument(String argument) {
        int equals = argument.indexOf('=');
        if (equals < 0) {
            return argument;
        }
        if (isSensitiveName(argument.substring(0, equals))) {
            return argument.substring(0, equals + 1) + "<redacted:command-value>";
        }
        return argument;
    }

    private static boolean isSensitiveName(String argument) {
        String name = argument.toLowerCase(java.util.Locale.ROOT);
        return name.contains("password") || name.contains("secret") || name.contains("token")
                || name.contains("credential") || name.contains("api_key") || name.contains("apikey");
    }

    private static long detectTotalMemoryBytes() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
            return extended.getTotalMemorySize();
        }
        return -1;
    }

    /** Revision and dirty state only; source checkout paths are intentionally not retained. */
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

    /** Content identity with a relocatable ordinal rather than a filesystem path. */
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
            return new InputIdentity(id, kind, bytes, hex(digest.digest()));
        }

        private static long digestFile(MessageDigest digest, Path file) throws IOException {
            long total = 0;
            byte[] buffer = new byte[8192];
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

        private static String hex(byte[] bytes) {
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                output.append(Character.forDigit((value >>> 4) & 0xf, 16));
                output.append(Character.forDigit(value & 0xf, 16));
            }
            return output.toString();
        }
    }

    private record Replacement(String value, String token) {
    }
}
