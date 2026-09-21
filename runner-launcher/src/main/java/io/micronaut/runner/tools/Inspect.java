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
package io.micronaut.runner.tools;

import io.micronaut.runner.ArchiveSource;
import io.micronaut.runner.Index;
import io.micronaut.runner.IndexFormat;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code inspect} mode: prints, in a form a human reads, what a runner jar says about itself.
 *
 * <p>Selected with {@code -Dmicronaut.runner.mode=inspect}, it answers the questions that come up when an
 * archive does not behave: which main class will be entered, whether the packager could generate an entry
 * stub, which version of the packaging library produced the archive, how many records the index holds and
 * how well they hash, and which dependency is at which position of the class path with which flags.</p>
 *
 * <p>Nothing here is on any hot path. This class is loaded only when the mode selects it, so it is written
 * in ordinary Java: the rules that keep {@code io.micronaut.runner} free of lambdas, streams and
 * {@code String.format} do not apply to {@code io.micronaut.runner.tools}.</p>
 *
 * @since 1.0
 */
public final class Inspect {

    /** Header of the jar table, which doubles as the source of the column labels. */
    private static final String[] COLUMNS = {"#", "NAME", "COORDINATES", "ENTRIES", "RECORDS", "FLAGS"};

    /** Printed where a value is absent, so that a column is never silently empty. */
    private static final String ABSENT = "-";

    private Inspect() {
    }

    /**
     * Prints the index of the runner jar the launcher was started from.
     *
     * @param args    the program arguments; this mode takes none
     * @param archive the runner jar
     * @param index   the index read from it
     * @param source  the archive's bytes, reported on so that the reading mode is visible
     * @throws IOException if an argument is given that this mode does not understand
     */
    public static void run(String[] args, File archive, Index index, ArchiveSource source)
            throws IOException {
        if (args.length > 0) {
            throw new IOException("The inspect mode takes no arguments, but got '" + String.join(" ", args)
                    + "'. Usage: java -Dmicronaut.runner.mode=inspect -jar <archive>");
        }
        PrintStream out = System.out;
        printHeader(out, archive, index, source);
        out.println();
        printJars(out, index);
    }

    /**
     * Prints everything the index header records, plus the two facts that are not in it: which file this
     * is and how its bytes are being read.
     *
     * @param out     where to print
     * @param archive the runner jar
     * @param index   the index
     * @param source  the archive's bytes
     */
    private static void printHeader(PrintStream out, File archive, Index index, ArchiveSource source) {
        label(out, "Archive", archive.getPath());
        // Index.open accepts exactly one format version, so a successfully opened index has this one.
        label(out, "Format version", Integer.toString(IndexFormat.FORMAT_VERSION));
        label(out, "Packaged by", value(index.launcherVersion()));
        label(out, "Main class", value(index.startClass()));
        String stub = index.entryStubClass();
        label(out, "Entry stub", stub == null ? "none (the launcher enters main reflectively)" : stub);
        label(out, "Jars", Integer.toString(index.jarCount()));
        label(out, "Index records", Integer.toString(index.entryCount()));
        label(out, "Application entries", Integer.toString(physicalEntries(index,
                IndexFormat.APPLICATION_JAR_ID)));
        label(out, "Package overrides", Integer.toString(index.packageCount()));
        label(out, "Hash slots", Integer.toString(index.hashSlots()));
        label(out, "Maximum probe", Integer.toString(index.maxProbe()));
        label(out, "Outer file length", index.outerFileLength() + " bytes");
        label(out, "Nested compression", index.nestedStored() ? "stored" : "preserved from the original");
        label(out, "Application layer", index.applicationMultiRelease() ? "multi-release" : "single release");
        label(out, "Read through", source.mapped() ? "a memory mapping" : "positional reads");
    }

    /**
     * Prints one line per jar: the application layer first, then the dependencies in class path order.
     *
     * @param out   where to print
     * @param index the index
     */
    private static void printJars(PrintStream out, Index index) {
        int jars = index.jarCount();
        List<String[]> rows = new ArrayList<>(jars + 1);
        rows.add(COLUMNS);
        for (int jarId = 0; jarId < jars; jarId++) {
            rows.add(new String[] {
                Integer.toString(jarId),
                index.jarName(jarId),
                value(index.jarCoordinates(jarId)),
                Integer.toString(physicalEntries(index, jarId)),
                Integer.toString(index.jarEntryCount(jarId)),
                flags(index, jarId)
            });
        }
        print(out, rows);
        out.println();
        out.println("ENTRIES counts the entries the jar really has; RECORDS adds the index records the"
                + " packager derived from them (multi-release aliases and synthesised directories).");
    }

    /**
     * Describes a jar's flags in words, in a fixed order so that two archives can be compared line by line.
     *
     * @param index the index
     * @param jarId the jar
     * @return the flags, or {@value #ABSENT} when the jar has none worth reporting
     */
    private static String flags(Index index, int jarId) {
        List<String> flags = new ArrayList<>(4);
        int mask = index.jarFlags(jarId);
        if ((mask & IndexFormat.JAR_FLAG_IS_OUTER) != 0) {
            flags.add("application layer");
        }
        if ((mask & IndexFormat.JAR_FLAG_MULTI_RELEASE) != 0) {
            flags.add("multi-release");
        }
        if ((mask & IndexFormat.JAR_FLAG_SIGNED_ORIGINAL) != 0) {
            flags.add("signed original");
        }
        if ((mask & IndexFormat.JAR_FLAG_SEALED_BY_DEFAULT) != 0) {
            flags.add("sealed by default");
        }
        if ((mask & IndexFormat.JAR_FLAG_HAS_MANIFEST) == 0) {
            flags.add("no manifest");
        }
        return flags.isEmpty() ? ABSENT : String.join(", ", flags);
    }

    /**
     * Counts the entries of a jar that physically exist in the archive, as opposed to the alias and
     * directory records the packager derived from them.
     *
     * @param index the index
     * @param jarId the jar
     * @return the number of physical entries
     */
    private static int physicalEntries(Index index, int jarId) {
        int first = index.jarFirstEntry(jarId);
        int count = index.jarEntryCount(jarId);
        int physical = 0;
        for (int record = first; record < first + count; record++) {
            if (index.entryPhysical(record)) {
                physical++;
            }
        }
        return physical;
    }

    /**
     * Prints a table, padding every column to the widest cell in it.
     *
     * @param out  where to print
     * @param rows the rows, the first of which is the header
     */
    private static void print(PrintStream out, List<String[]> rows) {
        int columns = COLUMNS.length;
        int[] widths = new int[columns];
        for (String[] row : rows) {
            for (int column = 0; column < columns; column++) {
                widths[column] = Math.max(widths[column], row[column].length());
            }
        }
        StringBuilder line = new StringBuilder(120);
        for (String[] row : rows) {
            line.setLength(0);
            for (int column = 0; column < columns; column++) {
                if (column > 0) {
                    line.append("  ");
                }
                String cell = row[column];
                line.append(cell);
                if (column < columns - 1) {
                    for (int pad = cell.length(); pad < widths[column]; pad++) {
                        line.append(' ');
                    }
                }
            }
            out.println(line);
        }
    }

    /**
     * Prints one {@code label   value} line of the header block.
     *
     * @param out   where to print
     * @param label the label
     * @param text  the value
     */
    private static void label(PrintStream out, String label, String text) {
        StringBuilder line = new StringBuilder(80);
        line.append(label);
        for (int pad = label.length(); pad < 20; pad++) {
            line.append(' ');
        }
        line.append("  ").append(text);
        out.println(line);
    }

    /**
     * A value as it should be printed, turning an absent string into {@value #ABSENT}.
     *
     * @param text the value, possibly {@code null}
     * @return the text to print
     */
    private static String value(String text) {
        return text == null || text.isEmpty() ? ABSENT : text;
    }
}
