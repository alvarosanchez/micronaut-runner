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
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Trains, identifies and verifies a JDK AOT cache. */
final class AotCache {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(60);
    private static final List<String> CACHE_FLAGS = List.of("AOTCacheOutput", "AOTCache");

    /** Creation flags that let strict JDK 27 launches accept the cache wherever ASLR places the heap. */
    private static final List<String> COMPATIBLE_OOP_COMPRESSION = List.of(
            "-XX:+UnlockDiagnosticVMOptions", "-XX:+AOTCompatibleOopCompression");

    /** The training JDK's probe result, taken once per harness run. */
    private static CreationProbe creationProbe;

    /** Everything needed to exercise and terminate the training application deterministically. */
    record Request(Path cacheRoot,
                   String readinessPath,
                   List<String> workloadPaths,
                   String terminationPath,
                   Duration timeout,
                   String applicationClass,
                   List<String> relevantJvmFlags,
                   PrintStream log) {

        Request {
            workloadPaths = List.copyOf(workloadPaths);
            relevantJvmFlags = List.copyOf(relevantJvmFlags);
        }
    }

    private record CreationProbe(List<String> flags, String failure) {
    }

    private AotCache() {
    }

    static Variant prepare(Variant source, String name, Request request) throws IOException, InterruptedException {
        long preparationStarted = System.nanoTime();
        if (!source.available()) {
            throw new IOException("cannot train AOT cache because " + source.name() + " is unavailable");
        }
        if (source.launchInputs().isEmpty()) {
            throw new IOException("cannot train AOT cache without immutable ordered launch inputs");
        }
        List<String> creationFlags = creationFlags();

        String identity = identity(source.launchInputs(),
                System.getProperty("java.runtime.version", "<unavailable>") + "|"
                        + System.getProperty("java.vm.version", "<unavailable>"),
                System.getProperty("java.vm.name", "<unavailable>"),
                System.getProperty("os.arch", "<unavailable>"),
                identityFlags(source, request, creationFlags));
        Path directory = request.cacheRoot().resolve(identity);
        Path cache = directory.resolve("app.aot");
        Files.createDirectories(directory);

        boolean reuse = Files.isRegularFile(cache) && Files.size(cache) > 0;
        if (reuse) {
            try {
                verify(source, cache, request);
                request.log().println("[startup-benchmark] reusing AOT cache " + identity);
            } catch (IOException failure) {
                request.log().println("[startup-benchmark] invalid cached AOT cache " + identity
                        + "; retraining: " + oneLine(failure.getMessage()));
                Files.deleteIfExists(cache);
                reuse = false;
            }
        }

        long trainingMillis = -1;
        if (!reuse) {
            long trainingStarted = System.nanoTime();
            train(source, cache, request, creationFlags);
            trainingMillis = elapsedMillis(trainingStarted);
            request.log().println("[startup-benchmark] trained AOT cache " + identity);
            verify(source, cache, request);
        }

        List<Path> launchInputs = new ArrayList<>(source.launchInputs());
        launchInputs.add(cache);
        CacheInfo cacheInfo = new CacheInfo("aot", identity, Files.size(cache),
                elapsedMillis(preparationStarted), trainingMillis, reuse,
                "trained or reused after bounded readiness, workload and normal termination; verified before timing",
                "application class reused from the AOT cache in a separate diagnostic launch");
        // The launch cannot start without the cache, so it is part of the complete deployment. Measured after
        // CacheInfo so that preparationMillis stays training plus verification.
        DeploymentSize deploymentSize = source.deploymentSize() == null
                ? null : source.deploymentSize().withFile("cache", cache);
        return new Variant(name,
                source.description() + "; verified JDK AOT cache",
                launchCommand(source, cache), source.workingDirectory(), source.artifact(), deploymentSize,
                source.requestedEntryMode(), source.effectiveEntryMode(), true, null, launchInputs, cacheInfo);
    }

    static String identity(List<Path> orderedInputs,
                           String jdkBuild,
                           String vmName,
                           String architecture,
                           List<String> relevantFlags) throws IOException {
        MessageDigest digest = sha256();
        update(digest, "aot-cache-schema", "1");
        update(digest, "jdk-build", jdkBuild);
        update(digest, "vm", vmName);
        update(digest, "architecture", architecture);
        for (int i = 0; i < relevantFlags.size(); i++) {
            update(digest, "flag:" + i, relevantFlags.get(i));
        }
        for (int i = 0; i < orderedInputs.size(); i++) {
            Path input = orderedInputs.get(i);
            if (!Files.isRegularFile(input)) {
                throw new IOException("AOT cache input is not a regular file: " + input);
            }
            update(digest, "input-name:" + i, input.getFileName().toString());
            update(digest, "input-bytes:" + i, Long.toString(Files.size(input)));
            try (InputStream stream = Files.newInputStream(input)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void requireUsableCache(Path cache) throws IOException {
        if (!Files.isRegularFile(cache)) {
            throw new IOException("AOT cache does not exist: " + cache);
        }
        if (Files.size(cache) == 0) {
            throw new IOException("AOT cache is empty: " + cache);
        }
    }

    /**
     * The measured and verified command. {@code -XX:AOTMode=on} makes an absent, corrupt or mismatched cache
     * a launch failure; the default {@code auto} logs {@code [error][aot]} and runs uncached.
     */
    static List<String> launchCommand(Variant source, Path cache) {
        return withJvmArguments(source.command(),
                List.of("-XX:AOTMode=on", "-XX:AOTCache=" + cache.toAbsolutePath().normalize()));
    }

    /**
     * Reads the output of {@code -XX:+UnlockDiagnosticVMOptions -XX:+PrintFlagsFinal -version}.
     *
     * @param printFlagsFinal the output lines
     * @return {@code -XX:+UnlockDiagnosticVMOptions -XX:+AOTCompatibleOopCompression} when the JDK has that
     *         flag, otherwise nothing
     */
    static List<String> compatibleOopCompressionFlags(List<String> printFlagsFinal) {
        for (String line : printFlagsFinal) {
            String[] tokens = line.trim().split("\\s+");
            if (tokens.length > 1 && tokens[0].equals("bool") && tokens[1].equals("AOTCompatibleOopCompression")) {
                return COMPATIBLE_OOP_COMPRESSION;
            }
        }
        return List.of();
    }

    private static synchronized List<String> creationFlags() throws IOException, InterruptedException {
        if (creationProbe == null) {
            try {
                creationProbe = new CreationProbe(probeCreationFlags(SampleBuild.javaExecutable()), null);
            } catch (IOException failure) {
                creationProbe = new CreationProbe(List.of(), failure.getMessage());
            }
        }
        if (creationProbe.failure() != null) {
            throw new IOException(creationProbe.failure());
        }
        return creationProbe.flags();
    }

    private static List<String> probeCreationFlags(Path java) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(java.toString(),
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+PrintFlagsFinal", "-version")
                .redirectErrorStream(true);
        StartupHarness.removeInheritedJvmOptions(builder);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<IOException> drainFailure = new AtomicReference<>();
        Process process = builder.start();
        Thread drain = drain(process, output, drainFailure);
        try {
            if (!process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("JDK flag probe did not exit within " + PROBE_TIMEOUT + tail(output));
            }
            drain.join(REQUEST_TIMEOUT.toMillis());
            if (process.exitValue() != 0 || drainFailure.get() != null) {
                throw new IOException("JDK flag probe " + String.join(" ", builder.command())
                        + " exited with status " + process.exitValue() + tail(output), drainFailure.get());
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
        return compatibleOopCompressionFlags(output.toString(StandardCharsets.UTF_8).lines().toList());
    }

    private static List<String> identityFlags(Variant source, Request request, List<String> creationFlags) {
        List<String> flags = new ArrayList<>(CACHE_FLAGS.size() + creationFlags.size()
                + request.relevantJvmFlags().size() + source.command().size() + request.workloadPaths().size() + 3);
        flags.addAll(CACHE_FLAGS);
        flags.addAll(creationFlags);
        flags.addAll(request.relevantJvmFlags());
        flags.addAll(source.command().subList(1, source.command().size()));
        flags.add("readiness=" + request.readinessPath());
        request.workloadPaths().forEach(path -> flags.add("workload=" + path));
        flags.add("termination=" + request.terminationPath());
        return List.copyOf(flags);
    }

    private static void train(Variant source, Path cache, Request request, List<String> creationFlags)
            throws IOException, InterruptedException {
        Path temporary = cache.resolveSibling("app.training.aot");
        Files.deleteIfExists(temporary);
        // Creation flags go on the training command line: JDK 27 (build 27) runs the create step in a child JVM
        // that inherits it. The child logs "Picked up JAVA_TOOL_OPTIONS: -XX:+UnlockDiagnosticVMOptions
        // -XX:+AOTCompatibleOopCompression ... -XX:AOTMode=create", and the cache then reports
        // AOTCompatibleOopCompression = true. JDK_AOT_VM_OPTIONS cannot carry them: runLifecycle removes it
        // from every child JVM.
        List<String> arguments = new ArrayList<>(creationFlags);
        arguments.add("-XX:AOTCacheOutput=" + temporary.toAbsolutePath().normalize());
        runLifecycle(source, withJvmArguments(source.command(), arguments), request, null);
        requireUsableCache(temporary);
        try {
            Files.move(temporary, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING);
        }
        requireUsableCache(cache);
    }

    private static void verify(Variant source, Path cache, Request request)
            throws IOException, InterruptedException {
        requireUsableCache(cache);
        Path classLog = cache.resolveSibling("verification-class-load.log");
        Files.deleteIfExists(classLog);
        List<String> command = withJvmArguments(launchCommand(source, cache),
                List.of("-Xlog:class+load=info:file=" + classLog.toAbsolutePath().normalize()));
        runLifecycle(source, command, request, cache);
        if (!Files.isRegularFile(classLog)) {
            throw new IOException("AOT verification produced no class-load log");
        }
        boolean shared = Files.readAllLines(classLog, StandardCharsets.UTF_8).stream()
                .anyMatch(line -> line.contains("[class,load]")
                        && line.contains(request.applicationClass())
                        && line.contains("shared objects file"));
        if (!shared) {
            throw new IOException("AOT cache loaded but application class " + request.applicationClass()
                    + " was not reused from it");
        }
        request.log().println("[startup-benchmark] verified application class " + request.applicationClass()
                + " is reused from AOT cache");
    }

    private static void runLifecycle(Variant source,
                                     List<String> command,
                                     Request request,
                                     Path cache) throws IOException, InterruptedException {
        int port = StartupHarness.freePort();
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(source.workingDirectory().toFile())
                .redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.putAll(System.getenv());
        StartupHarness.removeInheritedJvmOptions(builder);
        environment.put("SERVER_PORT", Integer.toString(port));
        environment.put("MICRONAUT_ENVIRONMENTS", "cds-training");

        Process process = null;
        Thread drain = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<IOException> drainFailure = new AtomicReference<>();
        long deadline = System.nanoTime() + request.timeout().toNanos();
        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(REQUEST_TIMEOUT)
                .build()) {
            process = builder.start();
            drain = drain(process, output, drainFailure);
            awaitReadiness(client, process, port, request.readinessPath(), deadline, output);
            for (String path : request.workloadPaths()) {
                requireOk(client, port, path, "training workload", false);
            }
            requireOk(client, port, request.terminationPath(), "normal termination request", true);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                throw new IOException("AOT lifecycle did not terminate normally within "
                        + request.timeout() + tail(output));
            }
            if (process.exitValue() != 0) {
                throw new IOException("AOT lifecycle exited with status " + process.exitValue()
                        + (cache == null ? " while training" : " while verifying " + cache.getFileName())
                        + tail(output));
            }
            drain.join(Math.max(1, REQUEST_TIMEOUT.toMillis()));
            if (drainFailure.get() != null) {
                throw new IOException("could not capture AOT lifecycle output", drainFailure.get());
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly().waitFor(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
            if (drain != null) {
                drain.join(REQUEST_TIMEOUT.toMillis());
            }
        }
    }

    private static void awaitReadiness(HttpClient client,
                                       Process process,
                                       int port,
                                       String path,
                                       long deadline,
                                       ByteArrayOutputStream output) throws IOException, InterruptedException {
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("AOT lifecycle exited with status " + process.exitValue()
                        + " before readiness" + tail(output));
            }
            try {
                HttpResponse<String> response = send(client, port, path, false);
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // The process has not started listening yet.
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new IOException("AOT lifecycle did not reach " + path + " before its timeout" + tail(output));
    }

    private static void requireOk(HttpClient client, int port, String path, String phase, boolean post)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send(client, port, path, post);
        if (response.statusCode() != 200) {
            throw new IOException(phase + " " + path + " returned HTTP " + response.statusCode());
        }
    }

    private static HttpResponse<String> send(HttpClient client, int port, String path, boolean post)
            throws IOException, InterruptedException {
        URI uri = URI.create("http://127.0.0.1:" + port + (path.startsWith("/") ? path : "/" + path));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
        HttpRequest request = (post ? builder.POST(HttpRequest.BodyPublishers.noBody()) : builder.GET()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> withJvmArguments(List<String> command, List<String> arguments) {
        List<String> result = new ArrayList<>(command.size() + arguments.size());
        result.add(command.get(0));
        result.addAll(arguments);
        result.addAll(command.subList(1, command.size()));
        return List.copyOf(result);
    }

    private static Thread drain(Process process,
                                ByteArrayOutputStream output,
                                AtomicReference<IOException> failure) {
        Thread thread = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                input.transferTo(output);
            } catch (IOException e) {
                failure.set(e);
            }
        }, "aot-cache-process-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String tail(ByteArrayOutputStream output) {
        String text = output.toString(StandardCharsets.UTF_8);
        String[] lines = text.split("\\R");
        int first = Math.max(0, lines.length - 30);
        StringBuilder result = new StringBuilder("\n--- last ").append(lines.length - first)
                .append(" lines ---\n");
        for (int i = first; i < lines.length; i++) {
            result.append(lines[i]).append('\n');
        }
        return result.toString();
    }

    private static void update(MessageDigest digest, String key, String value) {
        digest.update(key.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0xff);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Every Java runtime provides SHA-256", e);
        }
    }

    private static String oneLine(String message) {
        return message == null ? "no message" : message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
