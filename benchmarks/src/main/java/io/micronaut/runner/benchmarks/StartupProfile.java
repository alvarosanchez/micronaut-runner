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
import java.nio.file.Path;

/**
 * The startup profile of one harness run: recorded at most once, from the list-free {@code runner-stored} jar, and
 * shared by every row that packages it ({@code runner-stored-preload}, {@code runner-stored-ordered} and
 * {@code runner-stored-hybrid}). A failed recording is remembered too, so each of those rows reports the same
 * reason and nothing records twice.
 */
final class StartupProfile {

    private final Recording recording;
    private Path profile;
    private IOException failure;

    /**
     * A profile that the given recording makes on first use.
     *
     * @param recording records the profile from the list-free jar
     */
    StartupProfile(Recording recording) {
        this.recording = recording;
    }

    /**
     * The run's profile, recorded from {@code stored} the first time it is asked for.
     *
     * @param stored the list-free {@code runner-stored} variant
     * @return the profile
     * @throws IOException          if this or an earlier call failed to record it
     * @throws InterruptedException if the recording is interrupted
     */
    synchronized Path get(Variant stored) throws IOException, InterruptedException {
        if (profile != null) {
            return profile;
        }
        if (failure != null) {
            throw new IOException(failure.getMessage(), failure);
        }
        try {
            profile = recording.record(stored);
            return profile;
        } catch (IOException e) {
            failure = e;
            throw e;
        }
    }

    /** Records the profile. */
    @FunctionalInterface
    interface Recording {

        /**
         * Records the profile.
         *
         * @param stored the list-free {@code runner-stored} variant
         * @return the profile
         * @throws IOException          if the recording fails
         * @throws InterruptedException if it is interrupted
         */
        Path record(Variant stored) throws IOException, InterruptedException;
    }
}
