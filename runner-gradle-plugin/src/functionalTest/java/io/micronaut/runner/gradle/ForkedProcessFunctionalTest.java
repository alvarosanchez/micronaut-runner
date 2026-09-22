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
package io.micronaut.runner.gradle;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the process helper used by the packaging functional tests.
 */
class ForkedProcessFunctionalTest extends AbstractFunctionalTest {

    @TempDir
    static Path directory;

    private static final String MARKER = "forked-process-marker";
    private static final int NOISY_BYTES = 256 * 1024;

    private static Path application;

    @BeforeAll
    static void buildApplication() throws IOException {
        Path source = directory.resolve("source/ProcessFixture.java");
        Path classes = directory.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, """
                import java.nio.file.Files;
                import java.nio.file.Path;

                public final class ProcessFixture {
                    public static void main(String[] args) throws Exception {
                        switch (args[0]) {
                            case "normal" -> {
                                System.out.println("normal-start");
                                System.err.println("normal-end");
                            }
                            case "nonzero" -> {
                                System.out.println("nonzero-diagnostic");
                                System.exit(7);
                            }
                            case "noisy" -> {
                                System.out.println("noisy-begin");
                                System.out.print("x".repeat(256 * 1024));
                                System.out.println("noisy-end");
                            }
                            case "delayed" -> {
                                System.out.println("forked-process-marker PID=" + ProcessHandle.current().pid());
                                Thread.sleep(2_000);
                            }
                            case "hang" -> {
                                System.out.println("forked-process-marker PID=" + ProcessHandle.current().pid());
                                System.out.flush();
                                Thread.sleep(Long.MAX_VALUE);
                            }
                            case "quiet-hang", "interrupt" -> {
                                Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                                Thread.sleep(Long.MAX_VALUE);
                            }
                            default -> throw new IllegalArgumentException(args[0]);
                        }
                    }
                }
                """, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the functional tests require a JDK");
        assertEquals(0, compiler.run(null, null, null, "--release", "25", "-d", classes.toString(),
                source.toString()));

        application = directory.resolve("process-fixture.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "ProcessFixture");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(application), manifest)) {
            ZipEntry entry = new ZipEntry("ProcessFixture.class");
            out.putNextEntry(entry);
            Files.copy(classes.resolve("ProcessFixture.class"), out);
            out.closeEntry();
        }
    }

    @Test
    void deadlineStartsBeforeOutputReachesEof() {
        IOException failure = assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThrows(IOException.class,
                        () -> runJar(application, Duration.ofMillis(200), "delayed")));

        assertTrue(failure.getMessage().contains("did not finish within PT0.2S"), failure::getMessage);
        assertTrue(failure.getMessage().contains(MARKER), failure::getMessage);
        assertTrue(failure.getMessage().contains(application.toString()), failure::getMessage);
        assertReaped(pidFrom(failure.getMessage()));
    }

    @Test
    void nonterminatingChildIsTerminatedWithCapturedDiagnostics() {
        IOException failure = assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThrows(IOException.class,
                        () -> runJar(application, Duration.ofMillis(300), "hang")));

        assertTrue(failure.getMessage().contains(MARKER), failure::getMessage);
        assertReaped(pidFrom(failure.getMessage()));
    }

    @Test
    void quietNonterminatingChildIsTerminated() {
        Path pidFile = directory.resolve("quiet.pid");

        IOException failure = assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThrows(IOException.class,
                        () -> runJar(application, Duration.ofMillis(300), "quiet-hang", pidFile.toString())));

        assertTrue(failure.getMessage().contains("--- command ---"), failure::getMessage);
        assertTrue(failure.getMessage().contains("(nothing)"), failure::getMessage);
        assertReaped(Long.parseLong(read(pidFile)));
    }

    @Test
    void largeOutputDoesNotDeadlockAndIsCapturedCompletely() throws Exception {
        Forked forked = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                runJar(application, Duration.ofSeconds(5), "noisy"));

        assertEquals(0, forked.status());
        assertTrue(forked.output().startsWith("noisy-begin\n"), () -> forked.output().substring(0, 100));
        assertTrue(forked.output().endsWith("noisy-end\n"),
                () -> forked.output().substring(forked.output().length() - 100));
        assertEquals(NOISY_BYTES, forked.output().chars().filter(character -> character == 'x').count());
    }

    @Test
    void normalAndNonzeroExitsRetainDiagnostics() throws Exception {
        Forked normal = runJar(application, Duration.ofSeconds(5), "normal");
        Forked nonzero = runJar(application, Duration.ofSeconds(5), "nonzero");

        assertEquals(0, normal.status());
        assertTrue(normal.output().contains("normal-start"));
        assertTrue(normal.output().contains("normal-end"));
        assertEquals(7, nonzero.status());
        assertTrue(nonzero.output().contains("nonzero-diagnostic"));
    }

    @Test
    void interruptionTerminatesChildAndOutputDrain() throws Exception {
        Path pidFile = directory.resolve("interrupted.pid");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiting = Thread.ofPlatform().name("interrupted-process-test").start(() -> {
            try {
                runJar(application, Duration.ofSeconds(30), "interrupt", pidFile.toString());
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        });
        long pid = awaitPid(pidFile);

        waiting.interrupt();
        waiting.join(Duration.ofSeconds(5));

        assertFalse(waiting.isAlive(), "the interrupted caller did not return");
        assertInstanceOf(InterruptedException.class, thrown.get());
        assertReaped(pid);
        assertFalse(hasDrainThread(pid), "the output drain thread survived cleanup");
    }

    private static long awaitPid(Path file) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.isRegularFile(file) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.isRegularFile(file), "the child did not write its pid");
        return Long.parseLong(read(file));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static long pidFrom(String output) {
        int start = output.indexOf("PID=");
        assertTrue(start >= 0, () -> "no pid in diagnostics:\n" + output);
        start += "PID=".length();
        int end = start;
        while (end < output.length() && Character.isDigit(output.charAt(end))) {
            end++;
        }
        return Long.parseLong(output.substring(start, end));
    }

    private static void assertReaped(long pid) {
        assertTrue(ProcessHandle.of(pid).isEmpty(), () -> "child " + pid + " is still present");
    }

    private static boolean hasDrainThread(long pid) {
        String name = "forked-application-output-" + pid;
        return Thread.getAllStackTraces().keySet().stream().anyMatch(thread -> thread.getName().equals(name));
    }
}
