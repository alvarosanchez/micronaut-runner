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
package io.micronaut.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises unsupported live archive mutation without ever exposing the Gradle test worker to the mapped
 * file. Every archive is a private disposable copy, and every access after mutation happens in a child JVM.
 */
class ArchiveMutationTest {

    private static final String DEPENDENCY = "MICRONAUT-INF/lib/mutation-fixture.jar";
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path temporary;

    @Test
    void recordsMutationAndTruncationOutcomesOnlyInIsolatedJvms() throws Exception {
        List<Result> results = new ArrayList<>();
        for (Operation operation : Operation.values()) {
            for (Timing timing : Timing.values()) {
                for (boolean mapped : List.of(true, false)) {
                    results.add(runScenario(operation, timing, mapped));
                }
            }
        }

        assertEquals(8, results.size());
        assertEquals(4, results.stream().filter(Result::mapped).count());
        assertEquals(4, results.stream().filter(result -> !result.mapped()).count());
        assertEquals(4, results.stream().filter(result -> result.operation() == Operation.MUTATE).count());
        assertEquals(4, results.stream().filter(result -> result.operation() == Operation.TRUNCATE).count());
        for (Result result : results) {
            System.out.println(result.report());
        }
    }

    private Result runScenario(Operation operation, Timing timing, boolean mapped) throws Exception {
        String name = operation.name().toLowerCase(Locale.ROOT) + "-"
                + timing.name().toLowerCase(Locale.ROOT) + "-" + (mapped ? "mapped" : "positional");
        Path directory = Files.createDirectory(temporary.resolve(name));
        Fixture fixture = fixture();
        Path archive = directory.resolve("private-runner.jar");
        Files.write(archive, fixture.bytes());
        FileStore store = Files.getFileStore(archive);
        String fileSystem = archive.getFileSystem().provider().getClass().getName() + "/" + store.type();

        Process process = startProbe(directory, archive, mapped, timing.prevalidated());
        OutputDrain drain = new OutputDrain(process.getInputStream());
        Thread drainThread = new Thread(drain, "archive-mutation-output-" + process.pid());
        drainThread.setDaemon(true);
        drainThread.start();

        MutationAttempt attempt = null;
        int status = Integer.MIN_VALUE;
        try {
            if (!drain.ready().await(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                fail("mutation probe did not become ready within " + READY_TIMEOUT + describe(process, drain));
            }
            assertTrue(drain.output().contains("READY mapped=" + mapped
                    + " prevalidated=" + timing.prevalidated()), describe(process, drain));

            attempt = mutate(archive, fixture, operation, timing);
            try (OutputStream command = process.getOutputStream()) {
                command.write("GO\n".getBytes(StandardCharsets.UTF_8));
                command.flush();
            }
            if (!process.waitFor(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                fail("mutation probe did not finish within " + EXIT_TIMEOUT + describe(process, drain));
            }
            status = process.exitValue();
            drainThread.join(CLEANUP_TIMEOUT.toMillis());
            assertFalse(drainThread.isAlive(), "output drain did not finish" + describe(process, drain));
            if (drain.failure() != null) {
                throw new IOException("could not drain mutation probe output", drain.failure());
            }
        } finally {
            reap(process);
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
            drainThread.join(CLEANUP_TIMEOUT.toMillis());
        }

        String output = drain.output();
        assertTrue(output.contains("JAVA_RUNTIME="), output);
        assertTrue(output.contains("JAVA_VERSION="), output);
        assertTrue(output.contains("OS="), output);
        String outcome;
        if (attempt != null && !attempt.applied()) {
            outcome = "FILESYSTEM_REJECTION:" + attempt.detail();
        } else if (status != 0) {
            outcome = "ABNORMAL_TERMINATION:" + status;
        } else {
            outcome = resultLine(output);
        }
        List<String> crashArtifacts = crashArtifacts(directory);
        return new Result(operation, timing, mapped, status, fileSystem, outcome, crashArtifacts, output);
    }

    private Process startProbe(Path directory, Path archive, boolean mapped, boolean prevalidated)
            throws IOException {
        List<String> java = new ArrayList<>();
        java.add(javaExecutable());
        java.add("-XX:ErrorFile=" + directory.resolve("hs_err_pid%p.log"));
        java.add("-XX:-CreateCoredumpOnCrash");
        java.add("-cp");
        java.add(System.getProperty("java.class.path"));
        java.add(ArchiveMutationProbe.class.getName());
        java.add(archive.toAbsolutePath().toString());
        java.add(Boolean.toString(mapped));
        java.add(Boolean.toString(prevalidated));

        List<String> command = java;
        Path shell = Path.of("/bin/sh");
        if (Files.isExecutable(shell)) {
            command = new ArrayList<>();
            command.add(shell.toString());
            command.add("-c");
            command.add("ulimit -c 0 >/dev/null 2>&1 || true; exec \"$@\"");
            command.add("archive-mutation-probe");
            command.addAll(java);
        }
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true);
        for (String variable : List.of("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(variable);
        }
        return builder.start();
    }

    private static MutationAttempt mutate(Path archive, Fixture fixture, Operation operation, Timing timing) {
        try (FileChannel channel = FileChannel.open(archive, StandardOpenOption.WRITE)) {
            if (operation == Operation.MUTATE) {
                long offset = timing.prevalidated() ? fixture.payloadOffset() : fixture.headerOffset();
                byte[] replacement = timing.prevalidated()
                        ? ArchiveMutationProbe.replacementPayload() : new byte[Integer.BYTES];
                ByteBuffer bytes = ByteBuffer.wrap(replacement);
                while (bytes.hasRemaining()) {
                    channel.write(bytes, offset + bytes.position());
                }
            } else {
                long size = timing.prevalidated() ? fixture.payloadOffset() : fixture.headerOffset();
                channel.truncate(size);
            }
            channel.force(true);
            return new MutationAttempt(true, "applied");
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            return new MutationAttempt(false, failure.getClass().getName() + ":" + clean(failure.getMessage()));
        }
    }

    private static Fixture fixture() {
        byte[] application = ArchiveMutationProbe.applicationPayload();
        byte[] original = ArchiveMutationProbe.originalPayload();
        TestArchiveBuilder nested = new TestArchiveBuilder();
        nested.stored("padding.bin", new byte[64 * 1024]);
        long innerPayload = nested.stored(ArchiveMutationProbe.TARGET_ENTRY, original);
        byte[] nestedBytes = nested.build();

        TestArchiveBuilder outer = new TestArchiveBuilder();
        outer.stored("META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        byte[] draft = index(0, application.length, 0, innerPayload, nestedBytes.length, 0, original.length);
        outer.reserve(IndexFormat.INDEX_ENTRY_NAME, draft.length);
        long applicationOffset = outer.stored(IndexFormat.CLASSES_PREFIX + ArchiveMutationProbe.APPLICATION_ENTRY,
                application);
        long dependencyOffset = outer.stored(DEPENDENCY, nestedBytes);
        long headerOffset = outer.localHeaderOffset(DEPENDENCY);
        byte[] index = index(applicationOffset, application.length, dependencyOffset, innerPayload,
                nestedBytes.length, headerOffset, original.length);
        if (draft.length != index.length) {
            throw new IllegalStateException("fixture index length changed with real offsets");
        }
        outer.replace(IndexFormat.INDEX_ENTRY_NAME, index);
        return new Fixture(outer.build(), headerOffset, dependencyOffset + innerPayload);
    }

    private static byte[] index(long applicationOffset, int applicationLength, long dependencyOffset,
                                long innerPayload, long dependencyLength, long headerOffset, int payloadLength) {
        TestIndexBuilder builder = new TestIndexBuilder();
        builder.addJar(IndexFormat.CLASSES_PREFIX)
                .addEntry(ArchiveMutationProbe.APPLICATION_ENTRY)
                .data(applicationOffset, applicationLength, applicationLength);
        TestIndexBuilder.Jar dependency = builder.addJar(DEPENDENCY)
                .location(dependencyOffset, dependencyLength, headerOffset);
        dependency.addEntry(ArchiveMutationProbe.TARGET_ENTRY)
                .data(dependencyOffset + innerPayload, payloadLength, payloadLength);
        return builder.build();
    }

    private static String resultLine(String output) {
        return Arrays.stream(output.split("\\R"))
                .filter(line -> line.startsWith("RESULT="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("probe exited normally without a result:\n" + output));
    }

    private static List<String> crashArtifacts(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("hs_err_pid") || name.startsWith("core"))
                    .sorted()
                    .toList();
        }
    }

    private static String describe(Process process, OutputDrain drain) {
        return "\nprocess=" + process.pid() + " alive=" + process.isAlive() + "\n--- output ---\n"
                + drain.output() + "\n--------------";
    }

    private static void reap(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(CLEANUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(CLEANUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
        assertFalse(process.isAlive(), "mutation probe survived forcible cleanup");
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command().orElseThrow();
    }

    private static String clean(String value) {
        return value == null ? "(null)" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private enum Operation {
        MUTATE,
        TRUNCATE
    }

    private enum Timing {
        BEFORE_LAZY_VALIDATION(false),
        AFTER_LAZY_VALIDATION(true);

        private final boolean prevalidated;

        Timing(boolean prevalidated) {
            this.prevalidated = prevalidated;
        }

        boolean prevalidated() {
            return prevalidated;
        }
    }

    private record Fixture(byte[] bytes, long headerOffset, long payloadOffset) {
    }

    private record MutationAttempt(boolean applied, String detail) {
    }

    private record Result(Operation operation, Timing timing, boolean mapped, int status, String fileSystem,
                          String outcome, List<String> crashArtifacts, String childOutput) {
        String report() {
            return "ARCHIVE_MUTATION_RESULT operation=" + operation + " timing=" + timing + " mode="
                    + (mapped ? "MMAP" : "POSITIONAL") + " filesystem=" + fileSystem + " status=" + status
                    + " outcome=" + outcome + " crashArtifacts=" + crashArtifacts + " child={"
                    + clean(childOutput).trim() + "}";
        }
    }

    private static final class OutputDrain implements Runnable {
        private final InputStream input;
        private final StringBuilder output = new StringBuilder();
        private final CountDownLatch ready = new CountDownLatch(1);
        private IOException failure;

        private OutputDrain(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                    if (line.startsWith("READY ")) {
                        ready.countDown();
                    }
                }
            } catch (IOException e) {
                failure = e;
            }
        }

        CountDownLatch ready() {
            return ready;
        }

        String output() {
            synchronized (output) {
                return output.toString();
            }
        }

        IOException failure() {
            return failure;
        }
    }
}
