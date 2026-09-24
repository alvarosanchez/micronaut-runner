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
package io.micronaut.runner.suite;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A packaged application started the way a user starts one: {@code java -jar <archive>}, in a separate
 * process, with the JDK the build runs on.
 *
 * <p>Everything the process writes to either stream is captured on a daemon thread from the moment it
 * starts. That is the whole point of this class: a readiness poll that times out tells you nothing on its
 * own, while the same timeout with the application's own output attached usually tells you exactly what
 * went wrong - a missing class, a port already in use, a stack trace from the class loader.</p>
 *
 * <p>Instances are {@link AutoCloseable}; the process is destroyed on close, whether the test passed,
 * failed or timed out.</p>
 */
final class ForkedApplication implements AutoCloseable {

    /** How often the readiness endpoint is polled. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    /** How long a single HTTP attempt may take before it is treated as "not ready yet". */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** How long the process is given to die politely before it is killed. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(10);

    private final Process process;
    private final StringBuilder output = new StringBuilder();
    private final Thread drain;
    private final List<String> command;

    private ForkedApplication(Process process, List<String> command) {
        this.process = process;
        this.command = command;
        this.drain = new Thread(this::drain, "forked-application-output");
        this.drain.setDaemon(true);
        this.drain.start();
    }

    /**
     * Starts {@code java -jar archive} and returns immediately.
     *
     * @param archive          the runner jar to start
     * @param workingDirectory the directory to start it in
     * @param environment      extra environment variables, such as {@code SERVER_PORT}
     * @return the running application
     * @throws IOException if the process could not be started
     */
    static ForkedApplication start(Path archive, Path workingDirectory, Map<String, String> environment)
            throws IOException {
        return start(archive, workingDirectory, environment, List.of());
    }

    /**
     * Starts {@code java <jvmArguments> -jar archive} and returns immediately.
     *
     * <p>The child does not inherit {@code JAVA_TOOL_OPTIONS}, {@code JDK_JAVA_OPTIONS} or
     * {@code _JAVA_OPTIONS}, so the JVM options it runs with are exactly the ones passed here.</p>
     *
     * @param archive          the runner jar to start
     * @param workingDirectory the directory to start it in
     * @param environment      extra environment variables, such as {@code SERVER_PORT}
     * @param jvmArguments     JVM options, inserted before {@code -jar}
     * @return the running application
     * @throws IOException if the process could not be started
     */
    static ForkedApplication start(Path archive, Path workingDirectory, Map<String, String> environment,
                                   List<String> jvmArguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Samples.javaExecutable().toString());
        command.addAll(jvmArguments);
        command.add("-jar");
        command.add(archive.toAbsolutePath().toString());

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        Map<String, String> childEnvironment = builder.environment();
        childEnvironment.remove("JAVA_TOOL_OPTIONS");
        childEnvironment.remove("JDK_JAVA_OPTIONS");
        childEnvironment.remove("_JAVA_OPTIONS");
        childEnvironment.putAll(environment);
        return new ForkedApplication(builder.start(), command);
    }

    /**
     * Polls an endpoint until it answers, the process dies, or the deadline passes.
     *
     * @param uri     the endpoint to poll
     * @param timeout how long to keep trying
     * @return the first successful response body
     */
    String awaitBody(URI uri, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException lastFailure = null;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build()) {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new AssertionError("The application exited with status " + process.exitValue()
                            + " before answering " + uri + describe());
                }
                try {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        return response.body();
                    }
                    lastFailure = new IOException("HTTP " + response.statusCode() + ": " + response.body());
                } catch (IOException e) {
                    lastFailure = e;
                }
                Thread.sleep(POLL_INTERVAL);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + uri + describe(), e);
        }
        throw new AssertionError(uri + " did not answer within " + timeout
                + (lastFailure == null ? "" : " (last attempt: " + lastFailure + ")") + describe());
    }

    /**
     * Waits for the process to exit.
     *
     * @param timeout how long to wait
     * @return the exit status
     */
    int awaitExit(Duration timeout) {
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("The application was still running after " + timeout + describe());
            }
            drain.join(SHUTDOWN_GRACE.toMillis());
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the application to exit" + describe(), e);
        }
    }

    /**
     * Everything the application has written so far, for a failure message.
     *
     * @return the captured output
     */
    String output() {
        synchronized (output) {
            return output.toString();
        }
    }

    /**
     * The command and the output, formatted to be appended to an assertion message.
     *
     * @return the description
     */
    String describe() {
        String captured = output();
        return "\n--- command ---\n" + String.join(" ", command)
                + "\n--- output ---\n" + (captured.isEmpty() ? "(nothing)" : captured) + "\n--------------";
    }

    @Override
    public void close() {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private void drain() {
        byte[] buffer = new byte[8192];
        try (InputStream in = process.getInputStream()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                synchronized (output) {
                    output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            synchronized (output) {
                output.append("\n[output capture stopped: ").append(e).append(']');
            }
        }
    }
}
