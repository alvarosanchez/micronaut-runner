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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The classes a fresh JVM loads to read a small archive through {@link ZipReader}.
 *
 * <p>Reading a mapping through the {@code MemorySegment} accessors initialises {@code ValueLayout} constants and links
 * a {@code VarHandle} per layout on first use, and the array overload of {@code MemorySegment.copy} bootstraps a type
 * switch: milliseconds that every packaging JVM which starts fresh pays once. An archive that fits a buffer view is
 * read through the view, which needs none of them. Each case runs a probe in a JVM of its own with
 * {@code -Xlog:class+load}: it opens a small jar, lists its entries, reads its manifest, reads and transfers a stored
 * and a deflated entry, and closes it. The segment case is the control: it shows that the probe and the pattern do
 * see those classes when the segment accessors run.</p>
 */
class ZipReaderClassLoadingTest {

    /** The classes the segment accessors load on JDK 25, and the type switch the array overload of a copy spins. */
    private static final Pattern SEGMENT_READS =
            Pattern.compile("ValueLayout\\$Of|ValueLayouts\\$Of|VarHandleSegmentAs|SwitchBootstraps|\\$\\$TypeSwitch");

    @TempDir
    Path temp;

    @Test
    void readingASmallArchiveThroughTheViewLoadsNoneOfTheSegmentAccessorsClasses() throws Exception {
        List<String> loaded = loadedClasses(true);

        assertTrue(loaded.contains(ZipReader.class.getName()), () -> "the probe did not load ZipReader: " + loaded);
        assertEquals(List.of(), loaded.stream().filter(SEGMENT_READS.asPredicate()).toList());
    }

    @Test
    void theSegmentReadsLoadThem() throws Exception {
        List<String> loaded = loadedClasses(false);

        assertFalse(loaded.stream().filter(SEGMENT_READS.asPredicate()).toList().isEmpty(),
                () -> "the segment reads loaded none of the classes the view must avoid: " + loaded);
    }

    /** Runs the probe over a fresh archive and returns the name of every class its JVM loaded, in order. */
    private List<String> loadedClasses(boolean bufferView) throws IOException, InterruptedException {
        Path jar = temp.resolve("small.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            ZipReaderTest.deflated(zip, "META-INF/MANIFEST.MF", ZipReaderTest.manifestBytes("Created-By", "test"));
            ZipReaderTest.stored(zip, "a/data.bin", "stored content".getBytes(StandardCharsets.UTF_8));
            ZipReaderTest.deflated(zip, "a/B.class", ZipReaderTest.repeat("deflated-content-", 200));
        }
        Path log = temp.resolve("probe.log");
        // To standard output, not to a file: -Xlog would split a Windows path at its drive letter's colon.
        ProcessBuilder builder = new ProcessBuilder(javaExecutable().toString(),
                "-Xlog:class+load=info:stdout",
                "-cp", System.getProperty("java.class.path"),
                Probe.class.getName(), jar.toString(), Boolean.toString(bufferView))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        Map<String, String> environment = builder.environment();
        // Options the environment would add to the forked JVM, such as an agent that loads classes of its own.
        environment.remove("JAVA_TOOL_OPTIONS");
        environment.remove("JDK_JAVA_OPTIONS");
        environment.remove("_JAVA_OPTIONS");
        Process process = builder.start();
        boolean exited = process.waitFor(2, TimeUnit.MINUTES);
        if (!exited) {
            process.destroyForcibly().waitFor();
        }
        String output = Files.readString(log);
        assertTrue(exited, () -> "the probe did not exit within 2 minutes:\n" + output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("probe: done"), output);
        // [0.012s][info][class,load] java.lang.Object source: shared objects file
        return output.lines()
                .filter(line -> line.contains("[class,load] "))
                .map(line -> line.substring(line.indexOf("[class,load] ") + "[class,load] ".length()))
                .map(line -> line.split(" ", 2)[0])
                .toList();
    }

    private static Path javaExecutable() {
        String home = System.getProperty("runner.test.javaHome", System.getProperty("java.home"));
        Path executable = Path.of(home, "bin", "java");
        if (!Files.isExecutable(executable)) {
            executable = Path.of(home, "bin", "java.exe");
        }
        Path found = executable;
        assertTrue(Files.isExecutable(found), () -> "no java executable under " + home);
        return found;
    }

    /**
     * The forked JVM: makes every call a packaging stage makes on one small archive. Since JDK 25 the launcher runs a
     * {@code main} method that is neither public nor in a public class.
     */
    static final class Probe {

        private Probe() {
        }

        /**
         * Reads the archive and prints a line once it has closed it.
         *
         * @param args the archive, and whether to read it through a buffer view
         * @throws IOException if the archive cannot be read
         */
        static void main(String[] args) throws IOException {
            long sink;
            try (ZipReader reader = ZipReader.open(Path.of(args[0]), Boolean.parseBoolean(args[1]))) {
                sink = reader.entries().size();
                sink += reader.manifest().orElseThrow().getMainAttributes().size();
                ZipEntryInfo stored = reader.entry("a/data.bin").orElseThrow();
                ZipEntryInfo deflated = reader.entry("a/B.class").orElseThrow();
                sink += reader.read(stored).length;
                sink += reader.read(deflated).length;
                sink += reader.transfer(stored, OutputStream.nullOutputStream());
                sink += reader.transfer(deflated, OutputStream.nullOutputStream());
            }
            System.out.println("probe: done " + sink);
        }
    }
}
