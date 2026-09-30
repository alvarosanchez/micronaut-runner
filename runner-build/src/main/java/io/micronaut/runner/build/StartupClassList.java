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

import io.micronaut.runner.Index;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The recorded startup class list of an application: the classes a run of its runner jar loaded, in the
 * order it first loaded them. The packager embeds the list in the index and the launcher replays it on a
 * background thread.
 *
 * <p>The list has two parts. The classes of the archive, which the runner class loader defined, are resolved
 * against the archive when it is packaged. The JDK classes of the same run that the JDK's default CDS archive
 * did not serve are kept by name and loaded through the platform class loader; a runtime that lacks one skips
 * it.</p>
 *
 * <p>A file is read line by line, and each line is one of two things:</p>
 * <ul>
 *     <li><strong>A line of {@code -Xlog:class+load=info} output</strong>, which is any line containing
 *     {@code " source: "}. The class is the token right before that marker, so any set of {@code -Xlog}
 *     decorators works. The line is an archive class when its source contains {@code !/MICRONAUT-INF/}, which
 *     is how the runner class loader reports a class it defined, and a JDK class when its source starts with
 *     {@code jrt:/}. Every other source is skipped without a word: a class served from a CDS or AOT cache
 *     ({@code shared objects file}), a hidden class ({@code __JVM_LookupDefineClass__} or the name of its
 *     host class) and the launcher's own classes ({@code file:}).</li>
 *     <li><strong>A binary class name.</strong> The line is trimmed, and a blank line or one that starts with
 *     {@code #} is ignored. A name written as {@code jrt:<binary name>} is a JDK class.</li>
 * </ul>
 *
 * <p>The order of the file is kept in each part, and of a name that appears more than once the first
 * occurrence wins.</p>
 */
final class StartupClassList {

    /** What separates the class name from its source on a class-load log line. */
    private static final String SOURCE_MARKER = " source: ";

    /** What the source of a class the runner class loader defined contains. */
    private static final String ARCHIVE_SOURCE = "!/MICRONAUT-INF/";

    /** What the source of a JDK class loaded from the runtime image starts with. */
    private static final String JDK_SOURCE = "jrt:/";

    /** What marks a JDK class in a file of binary names. */
    private static final String JDK_NAME_PREFIX = "jrt:";

    /** What a build without a startup class list has: nothing to embed and nothing to report. */
    private static final StartupClassList NONE = new StartupClassList(null, Set.of(), Set.of(), 0, 0);

    private final Path file;
    private final List<String> classes;
    private final List<String> jdkClasses;
    private final int listed;
    private final int logLines;

    private StartupClassList(Path file, Set<String> classes, Set<String> jdkClasses, int listed, int logLines) {
        this.file = file;
        this.classes = List.copyOf(classes);
        this.jdkClasses = List.copyOf(jdkClasses);
        this.listed = listed;
        this.logLines = logLines;
    }

    /**
     * Reads a startup class list.
     *
     * <p>The file is decoded as UTF-8, which is what the JVM writes class names in. A byte sequence that is
     * not UTF-8, such as a path in another encoding in a source, is replaced rather than rejected.</p>
     *
     * @param file the recorded class-load log or file of binary class names, or {@code null} for none
     * @return the list, which is empty and reports nothing when there is no file
     * @throws IOException if the file does not exist or cannot be read
     */
    static StartupClassList read(Path file) throws IOException {
        if (file == null) {
            return NONE;
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("The startup class list " + file + " does not exist");
        }
        try (InputStream in = Files.newInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                     .onMalformedInput(CodingErrorAction.REPLACE)
                     .onUnmappableCharacter(CodingErrorAction.REPLACE)))) {
            return parse(file, reader.lines().toList());
        }
    }

    /**
     * Parses the lines of a startup class list that came from no file.
     *
     * @param lines the lines, without their terminators
     * @return the list
     */
    static StartupClassList parse(List<String> lines) {
        return parse(null, lines);
    }

    /**
     * Parses the lines of a startup class list.
     *
     * @param file  the file the lines were read from, which the reports name
     * @param lines the lines, without their terminators
     * @return the list
     */
    private static StartupClassList parse(Path file, List<String> lines) {
        Set<String> classes = new LinkedHashSet<>();
        Set<String> jdkClasses = new LinkedHashSet<>();
        int listed = 0;
        int logLines = 0;
        for (String line : lines) {
            int marker = line.indexOf(SOURCE_MARKER);
            if (marker >= 0) {
                logLines++;
                String name = line.substring(line.lastIndexOf(' ', marker - 1) + 1, marker);
                int source = marker + SOURCE_MARKER.length();
                if (name.isEmpty()) {
                    continue;
                }
                if (line.startsWith(JDK_SOURCE, source)) {
                    jdkClasses.add(name);
                } else if (line.indexOf(ARCHIVE_SOURCE, source) >= 0) {
                    listed++;
                    classes.add(name);
                }
                continue;
            }
            String name = line.trim();
            if (name.isEmpty() || name.charAt(0) == '#') {
                continue;
            }
            if (name.startsWith(JDK_NAME_PREFIX)) {
                String jdkName = name.substring(JDK_NAME_PREFIX.length()).trim();
                if (!jdkName.isEmpty()) {
                    jdkClasses.add(jdkName);
                }
                continue;
            }
            listed++;
            classes.add(name);
        }
        return new StartupClassList(file, classes, jdkClasses, listed, logLines);
    }

    /**
     * Lays the index out with this list in it, and reports what became of the list: one warning when the
     * recording holds no class of the archive, one when names were dropped, and one line with the numbers
     * embedded. Without a list it only lays the index out.
     *
     * <p>A recording without a single archive class was taken the wrong way, so none of it is embedded, its
     * JDK classes included.</p>
     *
     * @param writer the writer of the archive's index, with every jar and entry added
     * @param warn   where a warning goes
     * @param logger where the line with the numbers goes
     * @return the layout of the index
     */
    IndexWriter.Layout layout(IndexWriter writer, Consumer<String> warn, BuildLogger logger) {
        if (file == null) {
            return writer.layout();
        }
        if (recordedWithoutArchiveClasses()) {
            warn.accept("The startup class list " + file + " is a class-load log in which no class was loaded"
                    + " from the runner jar, so nothing is preloaded. Either the recording ran with a CDS or AOT"
                    + " cache, which logs classes as 'shared objects file', or it ran with the training-only"
                    + " property that makes the runner class loader report the jar's own 'file:' code source."
                    + " Record it again without either.");
        } else {
            writer.startupClasses(classes).jdkStartupClasses(jdkClasses);
        }
        IndexWriter.Layout layout = writer.layout();
        int dropped = listed - layout.preloadCount();
        if (dropped > 0) {
            warn.accept("The startup class list " + file + " dropped " + dropped + " of " + listed
                    + " startup classes: not in this archive or listed twice");
        }
        logger.info("Embedded " + layout.preloadCount() + " startup classes and " + layout.jdkPreloadCount()
                + " JDK classes from " + file + " for the launcher to preload");
        return layout;
    }

    /**
     * Checks that an index read back carries the preload tables that were laid out: the same number of
     * classes, each a record the index holds whose name is a class file, and the same number of JDK classes,
     * whose names {@link Index#validateStringReferences()} checks.
     *
     * @param index  the index as the launcher reads it
     * @param layout the layout the index was written from
     * @param output the archive, for the messages
     * @throws IOException if a table disagrees with the layout or names something that is not a class
     */
    static void verify(Index index, IndexWriter.Layout layout, Path output) throws IOException {
        int count = index.preloadCount();
        if (count != layout.preloadCount() || index.jdkPreloadCount() != layout.jdkPreloadCount()) {
            throw new IOException("The index of " + output + " lists " + count + " startup classes and "
                    + index.jdkPreloadCount() + " JDK classes, but " + layout.preloadCount() + " and "
                    + layout.jdkPreloadCount() + " were written");
        }
        int entries = index.entryCount();
        for (int position = 0; position < count; position++) {
            int record = index.preloadRecord(position);
            if (Integer.compareUnsigned(record, entries) >= 0) {
                throw new IOException("Startup class " + position + " of " + output + " is entry record "
                        + Integer.toUnsignedString(record) + ", but the index holds " + entries);
            }
            String name = index.entryName(record);
            if (!name.endsWith(".class")) {
                throw new IOException("Startup class " + position + " of " + output + " is the entry '" + name
                        + "', which is not a class file");
            }
        }
    }

    /**
     * The binary names of the archive's classes to preload, in the order they were recorded and once each.
     *
     * @return the names, unmodifiable
     */
    List<String> classes() {
        return classes;
    }

    /**
     * The binary names of the JDK classes to preload after the archive's, in the order they were recorded
     * and once each. They are not resolved when the archive is packaged.
     *
     * @return the names, unmodifiable
     */
    List<String> jdkClasses() {
        return jdkClasses;
    }

    /**
     * How many archive class names the file listed, counting a repeated name every time. It is the total the
     * packager's "dropped N of M" warning counts against.
     *
     * @return the number of kept archive log lines and plain names
     */
    int listed() {
        return listed;
    }

    /**
     * Whether the file is a class-load log that names no class of the archive at all. That is what a
     * recording taken with a CDS or AOT cache looks like, because a class served from a cache is logged with
     * the source {@code shared objects file}.
     *
     * @return {@code true} when the file has class-load log lines and no archive class was kept
     */
    boolean recordedWithoutArchiveClasses() {
        return logLines > 0 && listed == 0;
    }
}
