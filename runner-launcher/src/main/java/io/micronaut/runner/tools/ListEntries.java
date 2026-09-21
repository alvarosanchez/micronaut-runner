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
 * The {@code list} mode: prints every logical name the archive resolves, and where each one comes from.
 *
 * <p>Selected with {@code -Dmicronaut.runner.mode=list}, optionally with one argument that keeps only the
 * names starting with it. The point of the mode is to answer "why does this resource resolve to that
 * copy": the records are printed in class path order, every line names the jar it belongs to, and the
 * lines that do <em>not</em> win a lookup say which record wins instead. A multi-release alias prints the
 * version that put it in front of its base entry, and a directory that no archive actually contains is
 * marked as synthesised, so that a lookup that succeeds for a directory nobody packaged is not a
 * surprise.</p>
 *
 * <p>The winner is computed for the runtime this JVM reports, so running the mode under a different
 * {@code jdk.util.jar.version} prints the resolution that would happen there.</p>
 *
 * <p>This class is loaded only when the mode selects it, so it is written in ordinary Java: the rules
 * that keep {@code io.micronaut.runner} free of lambdas, streams and {@code String.format} do not apply
 * to {@code io.micronaut.runner.tools}.</p>
 *
 * @since 1.0
 */
public final class ListEntries {

    /** Column labels, also the first row of the table. */
    private static final String[] COLUMNS = {"NAME", "JAR", "NOTES"};

    /** How wide a column may grow before longer cells are allowed to push the following ones right. */
    private static final int MAX_COLUMN = 64;

    /** Printed for the application layer, whose index name is a prefix rather than a file name. */
    private static final String APPLICATION = "(application)";

    private ListEntries() {
    }

    /**
     * Prints the logical names the index holds.
     *
     * @param args    the program arguments; at most one, a prefix that filters the output
     * @param archive the runner jar
     * @param index   the index read from it
     * @param source  the archive's bytes; this mode reads none of them
     * @throws IOException if more than one argument is given
     */
    public static void run(String[] args, File archive, Index index, ArchiveSource source)
            throws IOException {
        if (args.length > 1) {
            throw new IOException("The list mode takes at most one argument, a name prefix, but got '"
                    + String.join(" ", args) + "'. Usage: java -Dmicronaut.runner.mode=list -jar <archive>"
                    + " [prefix]");
        }
        String prefix = args.length == 1 ? args[0] : null;
        int version = Index.effectiveMultiReleaseVersion();
        List<String[]> rows = new ArrayList<>();
        rows.add(COLUMNS);
        int entries = index.entryCount();
        int listed = 0;
        for (int record = 0; record < entries; record++) {
            String name = index.entryName(record);
            if (prefix != null && !name.startsWith(prefix)) {
                continue;
            }
            rows.add(new String[] {name, jarLabel(index, index.entryJarId(record)),
                    notes(index, record, name, version)});
            listed++;
        }
        PrintStream out = System.out;
        print(out, rows);
        StringBuilder summary = new StringBuilder(80);
        summary.append(listed);
        if (prefix != null) {
            summary.append(" of ").append(entries).append(" records start with '").append(prefix)
                    .append('\'');
        } else {
            summary.append(listed == 1 ? " record" : " records");
        }
        summary.append(" in ").append(archive.getName());
        out.println();
        out.println(summary);
    }

    /**
     * Describes one record: what kind of record it is, and whether a lookup of its name reaches it.
     *
     * @param index   the index
     * @param record  the record
     * @param name    the record's logical name
     * @param version the runtime feature version multi-release lookups resolve against
     * @return the notes column, which is empty for an ordinary entry that wins its own lookup
     */
    private static String notes(Index index, int record, String name, int version) {
        List<String> notes = new ArrayList<>(3);
        if (index.entrySyntheticDirectory(record)) {
            notes.add("synthesised directory");
        } else if (index.entryDirectory(record)) {
            notes.add("directory");
        }
        if (index.entryVersionedAlias(record)) {
            notes.add("versioned " + index.entryMrVersion(record) + ", aliases "
                    + index.entryName(index.entryPhysicalIndex(record)));
        }
        int winner = index.resolve(index.find(name), version);
        if (winner != IndexFormat.NO_INDEX && winner != record) {
            StringBuilder shadowed = new StringBuilder(48);
            shadowed.append("shadowed by ").append(jarLabel(index, index.entryJarId(winner)));
            if (index.entryVersionedAlias(winner)) {
                shadowed.append(" (versioned ").append(index.entryMrVersion(winner)).append(')');
            }
            notes.add(shadowed.toString());
        }
        return String.join("; ", notes);
    }

    /**
     * The short name of a jar: its file name, or a word for the application layer, whose index name is
     * the {@code MICRONAUT-INF/classes/} prefix.
     *
     * @param index the index
     * @param jarId the jar
     * @return the label to print
     */
    private static String jarLabel(Index index, int jarId) {
        if (jarId == IndexFormat.APPLICATION_JAR_ID) {
            return APPLICATION;
        }
        String name = index.jarName(jarId);
        if (name == null) {
            return "jar " + jarId;
        }
        int slash = name.lastIndexOf('/');
        return slash < 0 || slash == name.length() - 1 ? name : name.substring(slash + 1);
    }

    /**
     * Prints a table, padding every column to the widest cell in it that is not absurdly wide.
     *
     * @param out  where to print
     * @param rows the rows, the first of which is the header
     */
    private static void print(PrintStream out, List<String[]> rows) {
        int columns = COLUMNS.length;
        int[] widths = new int[columns];
        for (String[] row : rows) {
            for (int column = 0; column < columns; column++) {
                int length = row[column].length();
                if (length <= MAX_COLUMN) {
                    widths[column] = Math.max(widths[column], length);
                }
            }
        }
        StringBuilder line = new StringBuilder(160);
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
            // The notes column is often empty; no line ends in trailing blanks.
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) == ' ') {
                end--;
            }
            line.setLength(end);
            out.println(line);
        }
    }
}
