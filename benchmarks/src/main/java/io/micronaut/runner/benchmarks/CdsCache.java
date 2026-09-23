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

/** Trains, identifies and verifies a CDS archive for one immutable Runner JAR. */
final class CdsCache {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final List<String> CACHE_FLAGS = List.of("ArchiveClassesAtExit", "SharedArchiveFile");

    enum SharingPolicy {
        /** Refuse to launch when the selected archive cannot be loaded. */
        STRICT("-Xshare:on"),
        /** Ask HotSpot to continue without the selected archive when it cannot be loaded. */
        FALLBACK("-Xshare:auto");

        private final String option;

        SharingPolicy(String option) {
            this.option = option;
        }
    }

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

    private CdsCache() {
    }

    static Variant prepare(Variant source, String name, Request request) throws IOException, InterruptedException {
        if (!source.available()) {
            throw new IOException("cannot train CDS because " + source.name() + " is unavailable");
        }
        String identity = identity(source.artifact(),
                System.getProperty("java.runtime.version", "<unavailable>") + "|"
                        + System.getProperty("java.vm.version", "<unavailable>"),
                System.getProperty("java.vm.name", "<unavailable>"),
                System.getProperty("os.arch", "<unavailable>"),
                identityFlags(source, request));
        Path directory = request.cacheRoot().resolve(identity);
        Path archive = directory.resolve("app.jsa");
        Files.createDirectories(directory);

        boolean reuse = Files.isRegularFile(archive) && Files.size(archive) > 0;
        if (reuse) {
            try {
                verify(source, archive, request);
                request.log().println("[startup-benchmark] reusing CDS cache " + identity);
            } catch (IOException failure) {
                request.log().println("[startup-benchmark] invalid cached CDS archive " + identity
                        + "; retraining: " + oneLine(failure.getMessage()));
                Files.deleteIfExists(archive);
                reuse = false;
            }
        }
        if (!reuse) {
            train(source, archive, request);
            request.log().println("[startup-benchmark] trained CDS cache " + identity);
            verify(source, archive, request);
        }

        List<String> command = launchCommand(source, archive, SharingPolicy.STRICT);
        DeploymentSize deploymentSize = DeploymentSize.measure(
                DeploymentSize.input("archive", source.artifact()),
                DeploymentSize.input("cds-cache", archive));
        return new Variant(name,
                source.description() + "; verified application-class CDS; strict archive loading",
                command, source.workingDirectory(), source.artifact(), deploymentSize,
                source.requestedEntryMode(), source.effectiveEntryMode(), true, null,
                List.of(source.artifact(), archive));
    }

    static String identity(Path artifact,
                           String jdkBuild,
                           String vmName,
                           String architecture,
                           List<String> relevantFlags) throws IOException {
        MessageDigest digest = sha256();
        update(digest, "cds-cache-schema", "1");
        update(digest, "jdk-build", jdkBuild);
        update(digest, "vm", vmName);
        update(digest, "architecture", architecture);
        for (int i = 0; i < relevantFlags.size(); i++) {
            update(digest, "flag:" + i, relevantFlags.get(i));
        }
        update(digest, "artifact-bytes", Long.toString(Files.size(artifact)));
        try (InputStream input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void requireUsableArchive(Path archive) throws IOException {
        if (!Files.isRegularFile(archive)) {
            throw new IOException("CDS archive does not exist: " + archive);
        }
        if (Files.size(archive) == 0) {
            throw new IOException("CDS archive is empty: " + archive);
        }
    }

    static List<String> launchCommand(Variant source, Path archive, SharingPolicy policy) {
        List<String> arguments = List.of(policy.option,
                "-XX:SharedArchiveFile=" + archive.toAbsolutePath().normalize());
        return withJvmArguments(source.command(), arguments);
    }

    private static List<String> identityFlags(Variant source, Request request) {
        List<String> flags = new ArrayList<>(CACHE_FLAGS.size() + request.relevantJvmFlags().size()
                + source.command().size() + request.workloadPaths().size() + 3);
        flags.addAll(CACHE_FLAGS);
        flags.addAll(request.relevantJvmFlags());
        flags.addAll(source.command().subList(1, source.command().size()));
        flags.add("readiness=" + request.readinessPath());
        request.workloadPaths().forEach(path -> flags.add("workload=" + path));
        flags.add("termination=" + request.terminationPath());
        return List.copyOf(flags);
    }

    private static void train(Variant source, Path archive, Request request)
            throws IOException, InterruptedException {
        Path temporary = archive.resolveSibling("app.jsa.training");
        Files.deleteIfExists(temporary);
        List<String> command = withJvmArguments(source.command(), List.of(
                "-Xshare:auto",
                "-XX:ArchiveClassesAtExit=" + temporary.toAbsolutePath().normalize()));
        runLifecycle(source, command, request, null);
        requireUsableArchive(temporary);
        try {
            Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
        }
        requireUsableArchive(archive);
    }

    private static void verify(Variant source, Path archive, Request request)
            throws IOException, InterruptedException {
        requireUsableArchive(archive);
        Path classLog = archive.resolveSibling("verification-class-load.log");
        Files.deleteIfExists(classLog);
        List<String> command = new ArrayList<>(launchCommand(source, archive, SharingPolicy.STRICT));
        command = withJvmArguments(command, List.of(
                "-Xlog:class+load=info:file=" + classLog.toAbsolutePath().normalize()));
        runLifecycle(source, command, request, archive);
        if (!Files.isRegularFile(classLog)) {
            throw new IOException("CDS verification produced no class-load log");
        }
        boolean shared = Files.readAllLines(classLog, StandardCharsets.UTF_8).stream()
                .anyMatch(line -> line.contains("[class,load]")
                        && line.contains(request.applicationClass())
                        && line.contains("shared objects file"));
        if (!shared) {
            throw new IOException("CDS archive loaded but application class " + request.applicationClass()
                    + " was not reused from it");
        }
        request.log().println("[startup-benchmark] verified application class " + request.applicationClass()
                + " is reused from CDS cache");
    }

    private static void runLifecycle(Variant source,
                                     List<String> command,
                                     Request request,
                                     Path archive) throws IOException, InterruptedException {
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
                throw new IOException("CDS lifecycle did not terminate normally within " + request.timeout()
                        + tail(output));
            }
            if (process.exitValue() != 0) {
                throw new IOException("CDS lifecycle exited with status " + process.exitValue()
                        + (archive == null ? " while training" : " while verifying " + archive.getFileName())
                        + tail(output));
            }
            drain.join(Math.max(1, REQUEST_TIMEOUT.toMillis()));
            if (drainFailure.get() != null) {
                throw new IOException("could not capture CDS lifecycle output", drainFailure.get());
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
                throw new IOException("CDS lifecycle exited with status " + process.exitValue()
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
        throw new IOException("CDS lifecycle did not reach " + path + " before its timeout" + tail(output));
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
        URI uri = URI.create("http://127.0.0.1:" + port + normalizePath(path));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
        HttpRequest request = (post ? builder.POST(HttpRequest.BodyPublishers.noBody()) : builder.GET()).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String normalizePath(String path) {
        return path.startsWith("/") ? path : "/" + path;
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
        }, "cds-cache-process-output");
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
}
