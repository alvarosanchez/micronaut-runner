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
package io.micronaut.runner.build;

import io.micronaut.core.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the short-lived JVMs of a cache build: the probes, the creation and {@code jcmd}. Each is bounded, runs
 * without the {@linkplain TrainingDriver#AMBIENT_JVM_OPTIONS ambient JVM option variables}, and is gone, with
 * anything it started, when the call returns or throws.
 */
final class Forks {

    /** How many lines of a fork's output a failure quotes. */
    private static final int TAIL_LINES = 30;

    /** How much of the end of a log file is read to find those lines. */
    private static final int TAIL_BYTES = 64 * 1024;

    /** How long a killed process is given to disappear. */
    private static final long KILL_MILLIS = 5_000;

    private Forks() {
    }

    /**
     * Runs a command and captures its output, standard error merged into standard output.
     *
     * @param command   the command
     * @param directory the working directory, or {@code null} for the build's
     * @param timeout   how long it may run
     * @return the exit status and the output
     * @throws IOException          if it cannot be started or does not exit in time; the message quotes the end
     *                              of its output
     * @throws InterruptedException if the thread is interrupted; the process has been killed by then
     */
    static Result capture(List<String> command, @Nullable Path directory, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder builder = builder(command, directory);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<IOException> drainFailure = new AtomicReference<>();
        Thread drain = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(output);
            } catch (IOException e) {
                drainFailure.set(e);
            }
        }, "micronaut-runner-aot-cache-output");
        drain.setDaemon(true);
        drain.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                drain.join(KILL_MILLIS);
                throw new IOException(command.get(0) + " did not exit within " + timeout + ": "
                        + String.join(" ", command) + tail(text(output)));
            }
            drain.join(KILL_MILLIS);
        } finally {
            kill(process);
        }
        if (drainFailure.get() != null) {
            throw new IOException("Could not read the output of " + String.join(" ", command), drainFailure.get());
        }
        return new Result(process.exitValue(), text(output));
    }

    /**
     * Runs a command with its output appended to a file, standard error merged into standard output.
     *
     * @param command   the command
     * @param directory the working directory
     * @param log       the file the output is appended to
     * @param timeout   how long it may run
     * @return the exit status
     * @throws IOException          if it cannot be started or does not exit in time
     * @throws InterruptedException if the thread is interrupted; the process has been killed by then
     */
    static int toFile(List<String> command, Path directory, Path log, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder builder = builder(command, directory)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new IOException(command.get(0) + " did not exit within " + timeout + ": "
                        + String.join(" ", command) + ". Its output is in " + log + tail(log));
            }
        } finally {
            kill(process);
        }
        return process.exitValue();
    }

    /**
     * The last lines of a log file, for a failure message.
     *
     * @param log the file
     * @return the lines under a heading, or a note that there are none
     */
    static String tail(Path log) {
        byte[] bytes;
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            bytes = new byte[(int) Math.min(length, TAIL_BYTES)];
            file.seek(length - bytes.length);
            file.readFully(bytes);
        } catch (IOException e) {
            return "\n(" + log.getFileName() + " could not be read: " + e.getMessage() + ")";
        }
        return tail(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * The last lines of an output, for a failure message.
     *
     * @param output the output
     * @return the lines under a heading, or a note that there are none
     */
    static String tail(String output) {
        List<String> lines = output.lines().toList();
        if (lines.isEmpty()) {
            return "\n(no output)";
        }
        int first = Math.max(0, lines.size() - TAIL_LINES);
        StringBuilder tail = new StringBuilder("\n--- last ").append(lines.size() - first).append(" lines ---");
        for (int i = first; i < lines.size(); i++) {
            tail.append('\n').append(lines.get(i));
        }
        return tail.toString();
    }

    private static ProcessBuilder builder(List<String> command, @Nullable Path directory) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        // Windows looks a variable up without regard to case, and so does this map there.
        Map<String, String> environment = builder.environment();
        TrainingDriver.AMBIENT_JVM_OPTIONS.forEach(environment::remove);
        return builder;
    }

    private static void kill(Process process) throws InterruptedException {
        if (!process.isAlive()) {
            return;
        }
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
            // A process table that cannot be read: the process itself is still killed.
        }
        process.destroyForcibly();
        process.waitFor(KILL_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static String text(ByteArrayOutputStream output) {
        synchronized (output) {
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * How a captured fork ended.
     *
     * @param exitStatus its exit status
     * @param output     everything it printed
     */
    record Result(int exitStatus, String output) {
    }
}
