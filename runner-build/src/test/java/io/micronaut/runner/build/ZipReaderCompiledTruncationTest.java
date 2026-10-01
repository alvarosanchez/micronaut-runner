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

import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Truncation while a reader is open, on the path the JIT compiler's code for {@link ZipReader#read(ZipEntryInfo)}
 * and {@link ZipReader#readRaw(ZipEntryInfo)} takes.
 *
 * <p>When a truncation leaves a mapped page without backing, HotSpot throws the {@link InternalError} of reading
 * it later than the read, at the thread's next delivery point. Once C2 has compiled the call, that point can lie
 * after the call has returned, outside every handler of the reader: for a STORED entry whose lost bytes were all
 * zeros, whose CRC-32 still matches, and for {@code readRaw}, which checks none. Whether a test in the ordinary
 * test JVM runs compiled code depends on what ran before it, so each case here runs in a JVM of its own with
 * {@code -Xbatch -XX:-TieredCompilation -XX:CompileThreshold=1000}: C2 compiles the call at about its 1,000th
 * invocation and the caller waits for it, and the probe makes 5,000 before it truncates. {@code -Xcomp} does not
 * reach this path. The method under test is never inlined into the probe, so that C2 compiles it on its own and
 * {@code -XX:+PrintCompilation} says so: a case also fails when the call it truncates under was not compiled
 * code.</p>
 *
 * <p>Each case expects the reader's {@link java.io.IOException} that names the file and says it may have been
 * truncated, and then runs a loop and closes the reader, so that an error thrown late fails it too.</p>
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows cannot truncate a file that is mapped")
class ZipReaderCompiledTruncationTest {

    /** What the probe prints once warm-up is over and before it truncates the file. */
    private static final String TRUNCATING = "probe: truncating";

    private static final int WARM_UP_CALLS = 5_000;

    @TempDir
    Path temp;

    /** One call under one kind of entry; every case truncates the file to 0 bytes. */
    enum Case {
        /** The case that escaped before reads went through the channel: the copy left zeros, which match. */
        READ_STORED_ZEROS(false, true, ZipReaderTest.Payload.ZEROS),
        READ_STORED_RANDOM(false, true, ZipReaderTest.Payload.RANDOM),
        READ_DEFLATED_ZEROS(false, false, ZipReaderTest.Payload.ZEROS),
        READ_DEFLATED_RANDOM(false, false, ZipReaderTest.Payload.RANDOM),
        /** Escaped as well: nothing checks what {@code readRaw} returns. */
        READ_RAW_STORED_RANDOM(true, true, ZipReaderTest.Payload.RANDOM),
        READ_RAW_DEFLATED_RANDOM(true, false, ZipReaderTest.Payload.RANDOM);

        private final boolean raw;
        private final boolean stored;
        private final ZipReaderTest.Payload payload;

        Case(boolean raw, boolean stored, ZipReaderTest.Payload payload) {
            this.raw = raw;
            this.stored = stored;
            this.payload = payload;
        }

        /** How {@code -XX:+PrintCompilation} names the method under test. */
        String compiledMethod() {
            return ZipReader.class.getName() + (raw ? "::readRaw (" : "::read (");
        }
    }

    @ParameterizedTest
    @EnumSource(Case.class)
    void aCompiledReadOfAFileTruncatedWhileItIsOpenFailsWithAnIOException(Case probe) throws Exception {
        Path jar = temp.resolve("truncated-while-open.jar");
        String output = fork(probe, temp);

        List<String> lines = output.lines().toList();
        int truncating = lines.indexOf(TRUNCATING);
        assertTrue(truncating >= 0, () -> "the probe did not reach the truncation:\n" + output);
        assertTrue(compiledBefore(lines, truncating, probe.compiledMethod()),
                () -> probe.compiledMethod() + " was not compiled code when the file was truncated:\n" + output);
        // What the probe printed after the truncation, without the JIT's log.
        String report = String.join("\n", lines.subList(truncating, lines.size()).stream()
                .filter(line -> !line.matches("\\s*\\d+\\s+\\d+\\s.*")).toList());
        assertEquals(IOException.class.getName(), value(lines, "outcome"), report);
        String message = value(lines, "message");
        assertTrue(message.contains(jar.toString()), report);
        assertTrue(message.contains("truncated"), report);
        assertEquals("none", value(lines, "late"), report);
    }

    /**
     * Whether the last line {@code -XX:+PrintCompilation} printed about {@code method} before the truncation says
     * that it was compiled, rather than nothing or that its code was made not entrant.
     */
    private static boolean compiledBefore(List<String> lines, int end, String method) {
        boolean compiled = false;
        for (String line : lines.subList(0, end)) {
            if (line.contains(method) && !line.contains("%")) {
                compiled = !line.contains("made not entrant") && !line.contains("made zombie");
            }
        }
        return compiled;
    }

    private static String value(List<String> lines, String key) {
        String prefix = "probe: " + key + "=";
        return lines.stream().filter(line -> line.startsWith(prefix)).map(line -> line.substring(prefix.length()))
                .findFirst().orElse("<not printed>");
    }

    private static String fork(Case probe, Path directory) throws IOException, InterruptedException {
        String home = System.getProperty("runner.test.javaHome", System.getProperty("java.home"));
        Path java = Path.of(home, "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(),
                "-Xbatch", "-XX:-TieredCompilation", "-XX:CompileThreshold=1000", "-XX:+PrintCompilation",
                "-XX:CompileCommand=quiet",
                "-XX:CompileCommand=dontinline," + ZipReader.class.getName() + "::read",
                "-XX:CompileCommand=dontinline," + ZipReader.class.getName() + "::readRaw",
                "-Xmx128m",
                "-cp", System.getProperty("java.class.path"),
                Probe.class.getName(), probe.name(), directory.toString())
                .redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        // Options the environment would add to the forked JVM, such as another compiler configuration.
        environment.remove("JAVA_TOOL_OPTIONS");
        environment.remove("JDK_JAVA_OPTIONS");
        environment.remove("_JAVA_OPTIONS");
        Process process = builder.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("the probe did not exit:\n" + output);
        }
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    /** The forked JVM: warms the call up on a small entry, truncates a second archive under it and calls it. */
    public static final class Probe {

        private static long sink;

        private Probe() {
        }

        /**
         * Runs one case and prints what happened.
         *
         * @param args the {@link Case} and the directory to write the two archives in
         * @throws Exception if the archives cannot be written
         */
        public static void main(String[] args) throws Exception {
            Case probe = Case.valueOf(args[0]);
            Path directory = Path.of(args[1]);
            Path warm = archive(directory.resolve("warm-up.jar"), probe, probe.payload.bytes(4 * 1024));
            Path jar = archive(directory.resolve("truncated-while-open.jar"), probe,
                    probe.payload.bytes(256 * 1024));

            try (ZipReader reader = ZipReader.open(warm)) {
                ZipEntryInfo entry = reader.entry("data.bin").orElseThrow();
                for (int i = 0; i < WARM_UP_CALLS; i++) {
                    sink += call(probe, reader, entry).length;
                }
            }

            ZipReader reader = ZipReader.open(jar);
            ZipEntryInfo entry = reader.entry("data.bin").orElseThrow();
            System.out.println(TRUNCATING);
            try (FileChannel channel = FileChannel.open(jar, StandardOpenOption.WRITE)) {
                channel.truncate(0);
            }
            Throwable outcome = null;
            try {
                sink += call(probe, reader, entry).length;
            } catch (Throwable failure) {
                outcome = failure;
            }
            // Gives an error the JVM has not thrown yet the delivery points it needs: calls, loop polls and the
            // native calls of closing the reader.
            Throwable late = null;
            try {
                for (int i = 0; i < 10_000; i++) {
                    sink += reader.entry("data.bin").orElseThrow().compressedSize();
                }
                reader.close();
            } catch (Throwable failure) {
                late = failure;
            }
            System.out.println("probe: outcome=" + (outcome == null ? "returned" : outcome.getClass().getName()));
            System.out.println("probe: message=" + (outcome == null ? "" : outcome.getMessage()));
            System.out.println("probe: late=" + (late == null ? "none" : late));
            if (outcome != null && !(outcome instanceof IOException)) {
                outcome.printStackTrace(System.out);
            }
            if (late != null) {
                late.printStackTrace(System.out);
            }
            System.out.println("probe: sink=" + sink);
        }

        /** The one call site of the method under test, which the warm-up compiles. */
        private static byte[] call(Case probe, ZipReader reader, ZipEntryInfo entry) throws IOException {
            return probe.raw ? reader.readRaw(entry) : reader.read(entry);
        }

        private static Path archive(Path jar, Case probe, byte[] content) throws IOException {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
                if (probe.stored) {
                    ZipReaderTest.stored(zip, "data.bin", content);
                } else {
                    ZipReaderTest.deflated(zip, "data.bin", content);
                }
            }
            return jar;
        }
    }
}
