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

import java.nio.file.Path;
import java.util.List;

/**
 * One packaging of the sample application, ready to be started, or a note saying why it is not.
 *
 * <p>A variant that could not be built is kept in the list rather than dropped. A benchmark that quietly
 * measures only part of the matrix it names reads as though it measured every row, and the reader has no way
 * to tell. {@link #available()} is {@code false} and {@link #unavailableReason()} says what happened; the
 * report prints both.</p>
 *
 * @param name              the short identifier used in the report, for example {@code runner-stored}
 * @param description       one line explaining what the format is
 * @param command           the full command line, the {@code java} executable included
 * @param workingDirectory  the directory the process is started in
 * @param artifact          the file (or directory) the variant runs from, for the size column
 * @param requestedEntryMode how the harness asked to enter the application
 * @param effectiveEntryMode how the built artifact enters the application, or {@code null} when unavailable
 * @param available         whether the variant could be built
 * @param unavailableReason why it could not, or {@code null} when it could
 */
record Variant(String name,
               String description,
               List<String> command,
               Path workingDirectory,
               Path artifact,
               EntryMode requestedEntryMode,
               EntryMode effectiveEntryMode,
               boolean available,
               String unavailableReason) {

    /**
     * A variant that was built and can be measured.
     *
     * @param name             the identifier
     * @param description      the one-line explanation
     * @param command          the command line
     * @param workingDirectory where to start it
     * @param artifact         the file or directory it runs from
     * @return the variant
     */
    static Variant available(String name,
                             String description,
                             List<String> command,
                             Path workingDirectory,
                             Path artifact) {
        return available(name, description, command, workingDirectory, artifact,
                EntryMode.STANDARD_LOADER, EntryMode.STANDARD_LOADER);
    }

    static Variant available(String name,
                             String description,
                             List<String> command,
                             Path workingDirectory,
                             Path artifact,
                             EntryMode requestedEntryMode,
                             EntryMode effectiveEntryMode) {
        return new Variant(name, description, List.copyOf(command), workingDirectory, artifact,
                requestedEntryMode, effectiveEntryMode, true, null);
    }

    /**
     * A variant that could not be built.
     *
     * @param name        the identifier
     * @param description the one-line explanation
     * @param reason      what went wrong, in a form a reader can act on
     * @return the variant
     */
    static Variant unavailable(String name, String description, String reason) {
        return unavailable(name, description, EntryMode.requestedBy(name), reason);
    }

    static Variant unavailable(String name, String description, EntryMode requestedEntryMode, String reason) {
        return new Variant(name, description, List.of(), null, null, requestedEntryMode, null, false, reason);
    }
}
