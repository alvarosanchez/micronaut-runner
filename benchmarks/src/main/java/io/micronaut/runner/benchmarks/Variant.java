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
 * @param spec               the row of {@link SampleBuild}'s table: name, description, requested entry mode and
 *                           whether a trained JDK AOT cache is part of the launch
 * @param command            the full command line, the {@code java} executable included
 * @param workingDirectory   the directory the process is started in
 * @param artifact           the file (or directory) the variant launches from
 * @param deploymentSize     the measured complete deployment, or {@code null}
 * @param effectiveEntryMode how the built artifact enters the application, or {@code null} when unavailable
 * @param buildNote          what the packaging reported about the built bytes, such as the static service
 *                           table, appended to the description; {@code null} for nothing
 * @param launchInputs       ordered files/directories whose bytes define the launched application
 * @param cache              the trained JDK AOT cache, or {@code null}
 * @param unavailableReason  why the variant could not be built, or {@code null} when it could
 */
record Variant(SampleBuild.VariantSpec spec,
               List<String> command,
               Path workingDirectory,
               Path artifact,
               DeploymentSize deploymentSize,
               EntryMode effectiveEntryMode,
               String buildNote,
               List<Path> launchInputs,
               CacheInfo cache,
               String unavailableReason) {

    Variant {
        command = List.copyOf(command);
        launchInputs = List.copyOf(launchInputs);
    }

    /**
     * A variant that was built and can be measured.
     *
     * @param spec               its row
     * @param command            the command line
     * @param workingDirectory   where to start it
     * @param artifact           the file or directory it runs from
     * @param deploymentSize     the complete deployment, or {@code null}
     * @param effectiveEntryMode how the built artifact enters the application
     * @param buildNote          what the packaging reported about the built bytes, or {@code null}
     * @param launchInputs       the ordered launch inputs
     * @return the variant
     */
    static Variant available(SampleBuild.VariantSpec spec,
                             List<String> command,
                             Path workingDirectory,
                             Path artifact,
                             DeploymentSize deploymentSize,
                             EntryMode effectiveEntryMode,
                             String buildNote,
                             List<Path> launchInputs) {
        return new Variant(spec, command, workingDirectory, artifact, deploymentSize, effectiveEntryMode, buildNote,
                launchInputs, null, null);
    }

    /**
     * A variant that could not be built.
     *
     * @param spec   its row
     * @param reason what went wrong, in a form a reader can act on
     * @return the variant
     */
    static Variant unavailable(SampleBuild.VariantSpec spec, String reason) {
        return new Variant(spec, List.of(), null, null, null, null, null, List.of(), null, reason);
    }

    String name() {
        return spec.name();
    }

    /**
     * The row's description, followed by what the packaging reported about the bytes that were built.
     *
     * @return the one-line explanation
     */
    String description() {
        return buildNote == null ? spec.description() : spec.description() + "; " + buildNote;
    }

    EntryMode requestedEntryMode() {
        return spec.entryMode();
    }

    boolean available() {
        return unavailableReason == null;
    }
}
