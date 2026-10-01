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

import io.micronaut.runner.IndexFormat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.ZipFile;

/**
 * Recognises a runner jar.
 *
 * <p>A build plugin that replaces a project's main artifact uses {@link #isRunnerJar(Path)} to tell a runner jar
 * it wrote earlier from the thin jar it has to keep.</p>
 *
 * @since 1.0
 */
public final class RunnerJarReader {

    private RunnerJarReader() {
    }

    /**
     * Whether a file is a runner jar, which is whether it carries the runner index entry. Nothing else about
     * the archive is checked, so this is cheap enough to call on every build.
     *
     * @param file a ZIP archive
     * @return whether the archive carries the runner index
     * @throws IOException          if the file cannot be read or is not a ZIP archive
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public static boolean isRunnerJar(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (ZipFile jar = new ZipFile(file.toFile())) {
            return jar.getEntry(IndexFormat.INDEX_ENTRY_NAME) != null;
        }
    }
}
