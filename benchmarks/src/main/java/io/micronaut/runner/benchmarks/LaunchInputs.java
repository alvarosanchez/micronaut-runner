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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Gives every launch input one fixed modification time, so a JDK AOT cache trained in one run stays valid in
 * the next.
 *
 * <p>At startup the JDK checks each class-path JAR's size and modification time against the ones it recorded
 * when the cache was trained, and rejects the cache when either differs ("timestamp has changed"). The harness
 * re-copies or rebuilds every launch input in every run, so the bytes stay the same while the modification time
 * moves, and a strict launch of an otherwise valid cache fails.</p>
 *
 * <p>Pinning the time is safe because {@link AotCache#identity} hashes each input's content: different bytes
 * select a different cache directory, so the modification time never has to tell two contents apart. That is
 * also why the pin and the hash go through this one helper: {@link AotCache#identity} pins each input as it
 * hashes it, and {@link SampleBuild} pins every variant's inputs as it prepares them, so the (size, time) pair
 * the JDK validates and the content the identity names always describe the same file.</p>
 */
final class LaunchInputs {

    /**
     * The one modification time every launch input carries. It is the instant Runner's own extraction
     * ({@code -Dmicronaut.runner.mode=extract}) already writes, so the extracted layout needs no change and a
     * cache trained on it before pinning stays valid.
     */
    static final FileTime PINNED_MODIFICATION_TIME = FileTime.from(Instant.parse("1980-02-01T00:00:00Z"));

    private LaunchInputs() {
    }

    /**
     * What the JDK compares for a class-path entry before it accepts an AOT cache.
     *
     * @param size     the length in bytes
     * @param modified the last-modification time
     */
    record JdkView(long size, FileTime modified) {
    }

    /**
     * Pins the modification time of every input: a regular file directly, a directory through every regular
     * file beneath it.
     *
     * @param inputs the launch inputs, in any order
     * @throws IOException if an input is missing or its time cannot be set
     */
    static void pin(List<Path> inputs) throws IOException {
        for (Path input : inputs) {
            pin(input);
        }
    }

    /**
     * Pins one input's modification time; see {@link #pin(List)}.
     *
     * @param input a regular file, or a directory whose regular files are pinned
     * @throws IOException if the input is missing or its time cannot be set
     */
    static void pin(Path input) throws IOException {
        if (Files.isDirectory(input)) {
            List<Path> files = new ArrayList<>();
            try (var stream = Files.walk(input)) {
                stream.filter(Files::isRegularFile).forEach(files::add);
            }
            for (Path file : files) {
                pinFile(file);
            }
            return;
        }
        if (!Files.isRegularFile(input)) {
            throw new IOException("launch input is neither a regular file nor a directory: " + input);
        }
        pinFile(input);
    }

    /**
     * Copies a launch input into a variant's directory and pins the copy, the way every copied input is
     * prepared.
     *
     * @param source the file to copy
     * @param target where the copy goes, replaced if it exists
     * @return the pinned copy
     * @throws IOException if the copy or the pin fails
     */
    static Path copy(Path source, Path target) throws IOException {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        pinFile(target);
        return target;
    }

    /**
     * Reads the (size, modification time) pair the JDK validates.
     *
     * @param input a regular file
     * @return its current pair
     * @throws IOException if it cannot be read
     */
    static JdkView jdkView(Path input) throws IOException {
        return new JdkView(Files.size(input), Files.getLastModifiedTime(input));
    }

    private static void pinFile(Path file) throws IOException {
        if (!PINNED_MODIFICATION_TIME.equals(Files.getLastModifiedTime(file))) {
            Files.setLastModifiedTime(file, PINNED_MODIFICATION_TIME);
        }
    }
}
