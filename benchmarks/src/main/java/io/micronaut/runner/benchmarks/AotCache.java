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

import io.micronaut.runner.RunnerClassLoader;
import io.micronaut.runner.build.AotCacheGate;
import io.micronaut.runner.build.JdkProbe;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Trains, identifies and verifies a JDK AOT cache. The {@link StartupHarness} that times the variants also runs
 * the training and verification launches, through {@link StartupHarness#exercise}, so they get the timed runs'
 * environment, command prefix, port selection and readiness polling.
 */
final class AotCache {

    /**
     * The launcher's AOT training mode, which the training launch of a source that enters through the Runner
     * launcher adds, as runner-build's single-JAR target adds it to its recording launch.
     */
    private static final String AOT_TRAINING_ARGUMENT = "-D" + RunnerClassLoader.AOT_TRAINING_PROPERTY + "=true";

    /** The training JDK's probe result, taken once per harness run. */
    private static CreationProbe creationProbe;

    /**
     * What a cache is trained with, besides the harness and the variant.
     *
     * @param cacheRoot        where caches live, one directory per identity
     * @param workloadPaths    the paths requested once ready
     * @param applicationClass the class verification requires to come from the cache
     * @param relevantJvmFlags what else shapes the trained cache, hashed into its identity; {@code cpus=<n>} under
     *                         a CPU limit, so a cache is only reused under the VM ergonomics it was trained with
     * @param log              where progress goes
     */
    record Request(Path cacheRoot,
                   List<String> workloadPaths,
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

    /**
     * Reuses the verified cache of a variant, or trains and verifies a new one, and returns the cached row.
     *
     * <p>An existing nonempty cache is reused only after it verifies; otherwise it is deleted and trained again.
     * Training writes a temporary file that is moved into place, and verification follows every training. Any
     * failure propagates, so the cached row becomes unavailable and is never timed without its cache.</p>
     *
     * @param harness the harness whose settings the training and verification launches use
     * @param source  the variant the cache is trained on
     * @param spec    the cached row
     * @param request the run's cache request
     * @return the cached variant: the source's launch with the cache, which is also a launch input and a component
     *         of the deployment
     * @throws IOException          if the cache cannot be trained or verified
     * @throws InterruptedException if a launch is interrupted
     */
    static Variant prepare(StartupHarness harness, Variant source, SampleBuild.VariantSpec spec, Request request)
            throws IOException, InterruptedException {
        long preparationStarted = System.nanoTime();
        if (!source.available()) {
            throw new IOException("cannot train AOT cache because " + source.name() + " is unavailable");
        }
        if (source.launchInputs().isEmpty()) {
            throw new IOException("cannot train AOT cache without immutable ordered launch inputs");
        }
        List<String> creationFlags = creationFlags();
        String identity = identity(source, harness.readinessPath(), request, creationFlags);
        Path cache = request.cacheRoot().resolve(identity).resolve("app.aot");
        Files.createDirectories(cache.getParent());
        String name = spec.name();

        boolean reuse = Files.isRegularFile(cache) && Files.size(cache) > 0;
        if (reuse) {
            try {
                verify(harness, source, cache, request);
                request.log().println("[startup-benchmark] reusing AOT cache " + identity + " for " + name);
            } catch (IOException failure) {
                request.log().println("[startup-benchmark] invalid cached AOT cache " + identity + " for " + name
                        + "; retraining: " + oneLine(failure.getMessage()));
                Files.deleteIfExists(cache);
                reuse = false;
            }
        }

        long trainingMillis = -1;
        if (!reuse) {
            long trainingStarted = System.nanoTime();
            train(harness, source, cache, request, creationFlags);
            trainingMillis = elapsedMillis(trainingStarted);
            request.log().println("[startup-benchmark] trained AOT cache " + identity + " for " + name
                    + " in " + trainingMillis + " ms");
            verify(harness, source, cache, request);
        }
        String cacheSha256 = sha256(cache);
        request.log().println("[startup-benchmark] " + name + " AOT cache sha256 " + cacheSha256
                + (reuse ? " (reused)" : " (trained this run)"));

        List<Path> launchInputs = new ArrayList<>(source.launchInputs());
        launchInputs.add(cache);
        CacheInfo cacheInfo = new CacheInfo(identity, Files.size(cache), cacheSha256,
                elapsedMillis(preparationStarted), trainingMillis, reuse);
        // The launch cannot start without the cache, so it is part of the complete deployment. Measured after
        // CacheInfo so that preparationMillis stays training plus verification.
        DeploymentSize deploymentSize = source.deploymentSize() == null
                ? null : source.deploymentSize().withFile("cache", cache);
        return new Variant(spec, launchCommand(source, cache), source.workingDirectory(), source.artifact(),
                deploymentSize, source.effectiveEntryMode(), source.buildNote(), launchInputs, cacheInfo, null);
    }

    /**
     * What only the training launch of a cache adds, which is also part of the cache's identity: the launcher's AOT
     * training mode for a source that enters through the Runner launcher, so that its classes report the archive's
     * {@code file:} URL, which JDK 27 and later require to cache them (JDK-8380291). A source whose classes the JDK's
     * own loaders define gets nothing. The measured and verified launches never carry it.
     *
     * @param source the variant the cache is trained on
     * @return the arguments, placed after {@code -XX:AOTCacheOutput}
     */
    static List<String> trainingArguments(Variant source) {
        return source.requestedEntryMode() == EntryMode.STANDARD_LOADER ? List.of()
                : List.of(AOT_TRAINING_ARGUMENT);
    }

    /**
     * The identity of the cache a request trains for a variant on this JDK. The command prefix is not part of it;
     * a CPU limit enters through {@link Request#relevantJvmFlags()}.
     *
     * @param source        the variant the cache is trained on
     * @param readinessPath the path the harness polls for readiness
     * @param request       the training request
     * @param creationFlags the JDK-specific creation flags
     * @return the identity
     * @throws IOException if an input cannot be read
     */
    static String identity(Variant source, String readinessPath, Request request, List<String> creationFlags)
            throws IOException {
        List<String> flags = new ArrayList<>(creationFlags);
        flags.addAll(request.relevantJvmFlags());
        flags.addAll(source.command().subList(1, source.command().size()));
        flags.add("readiness=" + readinessPath);
        request.workloadPaths().forEach(path -> flags.add("workload=" + path));
        trainingArguments(source).forEach(argument -> flags.add("training=" + argument));
        return identity(source.launchInputs(),
                System.getProperty("java.runtime.version", "<unavailable>") + "|"
                        + System.getProperty("java.vm.version", "<unavailable>"),
                System.getProperty("java.vm.name", "<unavailable>"),
                System.getProperty("os.arch", "<unavailable>"),
                flags);
    }

    /**
     * The content identity of a cache: SHA-256 over a schema salt, the JDK build, VM, architecture and flags, and
     * each ordered input's position, file name and content digest. It names the cache's directory, so a cache
     * belongs to exactly one set of input contents.
     *
     * <p>Each input's modification time is pinned through {@link LaunchInputs#pin(Path)} as it is hashed, so the
     * (size, time) pair the JDK validates at startup is the same in every run whose bytes are the same, and a
     * cache trained in one run passes that check in the next. The time never has to tell two contents apart,
     * because different bytes give a different identity and so a different directory.</p>
     *
     * @param orderedInputs the launch inputs, in class path order: files, or directories hashed file by file
     * @param jdkBuild      the exact JDK and VM build
     * @param vmName        the VM name
     * @param architecture  the CPU architecture
     * @param relevantFlags the launch options, readiness path, workload and training-only arguments that shape the
     *                      trained cache
     * @return the lowercase hexadecimal identity
     * @throws IOException if an input is missing, cannot be pinned or cannot be read
     */
    static String identity(List<Path> orderedInputs,
                           String jdkBuild,
                           String vmName,
                           String architecture,
                           List<String> relevantFlags) throws IOException {
        MessageDigest digest = sha256();
        update(digest, "aot-cache-schema", "2");
        update(digest, "jdk-build", jdkBuild);
        update(digest, "vm", vmName);
        update(digest, "architecture", architecture);
        for (int i = 0; i < relevantFlags.size(); i++) {
            update(digest, "flag:" + i, relevantFlags.get(i));
        }
        for (int i = 0; i < orderedInputs.size(); i++) {
            Path input = orderedInputs.get(i);
            LaunchInputs.pin(input);
            BenchmarkProvenance.InputIdentity content = BenchmarkProvenance.InputIdentity.capture("input:" + i, input);
            update(digest, "input-name:" + i, input.getFileName().toString());
            update(digest, "input-sha256:" + i, content.sha256());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * The SHA-256 of a file's bytes. A trained cache is not byte-reproducible, so this names one training.
     *
     * @param file the file
     * @return the lowercase hexadecimal digest
     * @throws IOException if the file cannot be read
     */
    static String sha256(Path file) throws IOException {
        return BenchmarkProvenance.InputIdentity.capture("cache", file).sha256();
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
        return StartupHarness.processCommand(List.of(), source.command(), cacheArguments(cache));
    }

    private static List<String> cacheArguments(Path cache) {
        return List.of("-XX:AOTMode=on", "-XX:AOTCache=" + cache.toAbsolutePath().normalize());
    }

    private static synchronized List<String> creationFlags() throws IOException, InterruptedException {
        if (creationProbe == null) {
            try {
                // The engine's probe decides the flag, as it does for the plugins' caches.
                creationProbe = new CreationProbe(JdkProbe.probe(SampleBuild.javaExecutable()).creationFlags(), null);
            } catch (IOException failure) {
                creationProbe = new CreationProbe(List.of(), failure.getMessage());
            }
        }
        if (creationProbe.failure() != null) {
            throw new IOException(creationProbe.failure());
        }
        return creationProbe.flags();
    }

    private static void train(StartupHarness harness, Variant source, Path cache, Request request,
                              List<String> creationFlags) throws IOException, InterruptedException {
        Path temporary = cache.resolveSibling("app.training.aot");
        Files.deleteIfExists(temporary);
        // Creation flags go on the training command line: JDK 27 (build 27) runs the create step in a child JVM
        // that inherits it. The child logs "Picked up JAVA_TOOL_OPTIONS: -XX:+UnlockDiagnosticVMOptions
        // -XX:+AOTCompatibleOopCompression ... -XX:AOTMode=create", and the cache then reports
        // AOTCompatibleOopCompression = true. JDK_AOT_VM_OPTIONS cannot carry them: the harness removes it from
        // every child JVM.
        List<String> arguments = new ArrayList<>(creationFlags);
        arguments.add("-XX:AOTCacheOutput=" + temporary.toAbsolutePath().normalize());
        arguments.addAll(trainingArguments(source));
        String output = harness.exercise(source, arguments, request.workloadPaths());
        try {
            requireUsableCache(temporary);
        } catch (IOException failure) {
            throw new IOException(failure.getMessage() + " after training" + output, failure);
        }
        try {
            Files.move(temporary, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void verify(StartupHarness harness, Variant source, Path cache, Request request)
            throws IOException, InterruptedException {
        requireUsableCache(cache);
        Path classLog = cache.resolveSibling("verification-class-load.log");
        Files.deleteIfExists(classLog);
        List<String> arguments = new ArrayList<>(cacheArguments(cache));
        arguments.add("-Xlog:class+load=info:file=" + classLog.toAbsolutePath().normalize());
        harness.exercise(source, arguments, request.workloadPaths());
        if (!Files.isRegularFile(classLog)) {
            throw new IOException("AOT verification produced no class-load log");
        }
        List<String> lines = Files.readAllLines(classLog, StandardCharsets.ISO_8859_1);
        boolean shared = lines.stream().anyMatch(line -> line.contains("[class,load]")
                && line.contains(request.applicationClass()) && line.contains("shared objects file"));
        if (!shared) {
            throw new IOException("AOT cache loaded but application class " + request.applicationClass()
                    + " was not reused from it");
        }
        request.log().println("[startup-benchmark] verified application class " + request.applicationClass()
                + " is reused from AOT cache");
        // Informational: the gate's coverage line. The one-step training logs no skipped classes, so nothing is
        // allowlisted here and nothing is enforced.
        AotCacheGate.Coverage coverage = AotCacheGate.coverage(lines, List.of());
        request.log().println("[startup-benchmark] AOT cache of " + source.name() + ", coverage: "
                + coverage.summary());

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
