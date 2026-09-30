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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs the short helper commands the run conditions need ({@code taskset}, {@code sync}, {@code lscpu}, ...). */
final class Commands {

    private Commands() {
    }

    /**
     * Runs a command to completion with its standard error merged into its output. Ambient JVM option variables
     * are removed, as for every child the harness starts.
     *
     * @param command     the command
     * @param environment variables to add to the harness's environment
     * @param timeout     how long it may take
     * @return its exit status and output; status {@code -1} when it could not be started or did not finish
     */
    static Result run(List<String> command, Map<String, String> environment, Duration timeout) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().putAll(environment);
            StartupHarness.removeInheritedJvmOptions(builder);
            process = builder.start();
            process.getOutputStream().close();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Process running = process;
            Thread drain = new Thread(() -> {
                try (InputStream input = running.getInputStream()) {
                    input.transferTo(output);
                } catch (IOException ignored) {
                    // The exit status decides; a truncated output only shortens a message.
                }
            }, "benchmark-command-output");
            drain.setDaemon(true);
            drain.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return new Result(-1, String.join(" ", command) + " did not finish within " + timeout);
            }
            drain.join(timeout.toMillis());
            return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Result(-1, String.join(" ", command) + " could not be started: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, String.join(" ", command) + " was interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * What a command did.
     *
     * @param exitCode its exit status, or {@code -1} when it could not be started or did not finish
     * @param output   its standard output and error, or why it did not run
     */
    record Result(int exitCode, String output) {

        /**
         * The trimmed output of a successful command.
         *
         * @return the output, or {@code null} when the command failed or printed nothing
         */
        String outputIfSuccessful() {
            if (exitCode != 0) {
                return null;
            }
            String trimmed = output.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }
}
