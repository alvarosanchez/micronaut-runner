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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two file formats of a startup class list. The log lines are what JDK 25 writes for
 * {@code -Xlog:class+load=info}, to a file and to standard output, with the sources a runner jar produces.
 */
class StartupClassListTest {

    private static final String ARCHIVE = "jar:file:/srv/app/app-all.jar!/MICRONAUT-INF/";

    @Test
    void keepsOnlyTheClassesTheRunnerClassLoaderDefinedFromARawLog() {
        StartupClassList list = StartupClassList.parse(List.of(
                "[0.009s][info][class,load] java.lang.Object source: shared objects file",
                "[0.041s][info][class,load] io.micronaut.runner.Launcher source: file:/srv/app/app-all.jar",
                "[0.060s][info][class,load] io.micronaut.runner.generated.AppEntry source: " + ARCHIVE + "classes/",
                "[0.061s][info][class,load] com.example.Application source: " + ARCHIVE + "classes/",
                "[0.062s][info][class,load] jdk.internal.loader.URLClassPath$JarLoader source: jrt:/java.base",
                "[0.070s][info][class,load] io.netty.util.internal.PlatformDependent source: "
                        + ARCHIVE + "lib/netty-common-4.2.18.Final.jar!/",
                "[0.071s][info][class,load] com.example.Application$$Lambda/0x00000ff801001234 source:"
                        + " com.example.Application",
                "[0.072s][info][class,load] java.lang.invoke.LambdaForm$MH/0x00000ff801004400 source:"
                        + " __JVM_LookupDefineClass__",
                "[0.073s][info][class,load] com.example.Cached source: shared objects file (top)"));

        assertEquals(List.of("io.micronaut.runner.generated.AppEntry", "com.example.Application",
                "io.netty.util.internal.PlatformDependent"), list.classes());
        assertEquals(List.of("jdk.internal.loader.URLClassPath$JarLoader"), list.jdkClasses(),
                "a class the runtime image served is a JDK class");
        assertEquals(3, list.listed());
        assertFalse(list.recordedWithoutArchiveClasses());
    }

    @Test
    void keepsTheJdkClassesOfALogAndOfAListOfNamesInOrderAndOnce() {
        StartupClassList log = StartupClassList.parse(List.of(
                "[0.050s][info][class,load] java.util.zip.Adler32 source: jrt:/java.base",
                "[0.061s][info][class,load] com.example.Application source: " + ARCHIVE + "classes/",
                "[0.062s][info][class,load] java.sql.Timestamp source: jrt:/java.sql",
                "[0.063s][info][class,load] java.util.zip.Adler32 source: jrt:/java.base",
                "[0.064s][info][class,load] java.lang.String source: shared objects file"));
        StartupClassList names = StartupClassList.parse(List.of(
                "jrt:java.util.zip.Adler32",
                "com.example.Application",
                "  jrt: java.sql.Timestamp ",
                "jrt:java.util.zip.Adler32",
                "jrt:"));

        for (StartupClassList list : List.of(log, names)) {
            assertEquals(List.of("java.util.zip.Adler32", "java.sql.Timestamp"), list.jdkClasses());
            assertEquals(List.of("com.example.Application"), list.classes());
            assertEquals(1, list.listed(), "JDK classes are not resolved, so they are not counted as listed");
        }
    }

    @Test
    void readsTheClassNameWhateverDecoratorsTheLogHas() {
        StartupClassList list = StartupClassList.parse(List.of(
                "com.example.Bare source: " + ARCHIVE + "classes/",
                "[2026-09-30T10:15:30.123+0200][0.061s][12345][info ][class,load  ] com.example.Decorated source: "
                        + ARCHIVE + "classes/",
                "[class,load] com.example.TagOnly source: " + ARCHIVE + "lib/a b.jar!/",
                " source: " + ARCHIVE + "classes/"));

        assertEquals(List.of("com.example.Bare", "com.example.Decorated", "com.example.TagOnly"), list.classes());
    }

    @Test
    void readsOneBinaryNamePerLineSkippingCommentsAndBlankLines() {
        StartupClassList list = StartupClassList.parse(List.of(
                "# recorded on JDK 25",
                "",
                "com.example.Application",
                "   com.example.Service$Inner\t",
                "   ",
                "  # indented comment",
                "org.example.Last"));

        assertEquals(List.of("com.example.Application", "com.example.Service$Inner", "org.example.Last"),
                list.classes());
        assertEquals(3, list.listed());
        assertFalse(list.recordedWithoutArchiveClasses());
    }

    @Test
    void theLayoutSourceLeavesTheGeneratedLambdaClassesOutAndKeepsTheOrder() {
        StartupClassList list = StartupClassList.parse(List.of(
                "com.example.App",
                "com.example.App$$Lambda$R0",
                "jrt:java.lang.Thread",
                "com.example.Lib",
                "com.example.App$$Lambda$R0",
                "com.example.Lib$$Lambda$R12",
                "com.example.Other$$Lambda/0x0000000801001234"));

        assertEquals(3, list.generatedLambdaListings(), "a repeated name counts every time it is listed");
        StartupClassList kept = list.withoutGeneratedLambdaClasses();

        assertEquals(List.of("com.example.App", "com.example.Lib", "com.example.Other$$Lambda/0x0000000801001234"),
                kept.classes(), "only the classes desugaring generates are left out");
        assertEquals(List.of("java.lang.Thread"), kept.jdkClasses());
        assertEquals(list.listed() - 3, kept.listed(), "they do not count against the dropped-names warning");
        assertEquals(0, kept.generatedLambdaListings());
        StartupClassList none = StartupClassList.parse(List.of("com.example.App"));
        assertSame(none, none.withoutGeneratedLambdaClasses(), "a list without them is kept as it is");
    }

    @Test
    void keepsTheOrderOfTheFileAndTheFirstOccurrenceOfARepeatedName() {
        StartupClassList list = StartupClassList.parse(List.of(
                "z.Last", "a.First", "m.Middle", "a.First",
                "[0.1s][info][class,load] z.Last source: " + ARCHIVE + "classes/",
                "b.Second"));

        assertEquals(List.of("z.Last", "a.First", "m.Middle", "b.Second"), list.classes());
        assertEquals(6, list.listed(), "a repeated name counts every time, so the packager can report it");
    }

    @Test
    void aLogWithoutAnArchiveClassIsARecordingTakenWithACacheOrAFileCodeSource() {
        StartupClassList cached = StartupClassList.parse(List.of(
                "[0.009s][info][class,load] java.lang.Object source: shared objects file",
                "[0.061s][info][class,load] com.example.Application source: shared objects file (top)"));
        StartupClassList fileSource = StartupClassList.parse(List.of(
                "[0.009s][info][class,load] java.lang.Object source: jrt:/java.base",
                "[0.061s][info][class,load] com.example.Application source: file:/srv/app/app-all.jar"));

        for (StartupClassList list : List.of(cached, fileSource)) {
            assertTrue(list.classes().isEmpty());
            assertEquals(0, list.listed());
            assertTrue(list.recordedWithoutArchiveClasses());
        }
        assertEquals(List.of("java.lang.Object"), fileSource.jdkClasses(),
                "the JDK classes of such a log are still parsed; the packager embeds none of it");
        assertFalse(StartupClassList.parse(List.of()).recordedWithoutArchiveClasses(), "an empty file is not a log");
        assertFalse(StartupClassList.parse(List.of("# nothing yet")).recordedWithoutArchiveClasses());
    }

    @Test
    void readsAFileAsUtf8AndSurvivesBytesThatAreNot(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("startup-classes.log");
        byte[] head = ("com.example.Größe\n[0.1s][info][class,load] com.example.Application source: " + ARCHIVE
                + "classes/\n[0.2s][info][class,load] x.Y source: file:/caf").getBytes(StandardCharsets.UTF_8);
        byte[] tail = "/app.jar\r\norg.example.Last".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[head.length + 1 + tail.length];
        System.arraycopy(head, 0, bytes, 0, head.length);
        // A Latin-1 e-acute in a path, which is not valid UTF-8.
        bytes[head.length] = (byte) 0xE9;
        System.arraycopy(tail, 0, bytes, head.length + 1, tail.length);
        Files.write(file, bytes);

        assertEquals(List.of("com.example.Größe", "com.example.Application", "org.example.Last"),
                StartupClassList.read(file).classes());
    }

    @Test
    void aMissingFileIsAnError(@TempDir Path directory) {
        Path missing = directory.resolve("nowhere.log");
        IOException failure = assertThrows(IOException.class, () -> StartupClassList.read(missing));
        assertTrue(failure.getMessage().contains(missing.toString()), failure.getMessage());
        assertThrows(IOException.class, () -> StartupClassList.read(directory), "a directory is not a list");
    }
}
