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

import io.micronaut.runner.ArchiveSource;
import io.micronaut.runner.Handlers;
import io.micronaut.runner.Index;

import java.io.IOException;
import java.nio.file.Path;

/** Keeps one matching runner archive registered for the lifetime of a benchmark trial. */
final class RunnerRegistration implements AutoCloseable {

    private ArchiveSource source;

    private RunnerRegistration(ArchiveSource source) {
        this.source = source;
    }

    /**
     * Opens and registers exactly the supplied archive.
     *
     * @param archive runner archive to register
     * @return registration that must remain open for the whole trial
     * @throws IOException if the archive cannot be opened
     */
    static RunnerRegistration open(Path archive) throws IOException {
        if (Handlers.registered()) {
            throw new IllegalStateException("A runner archive is already registered: " + Handlers.archive());
        }
        ArchiveSource source = ArchiveSource.open(archive.toFile());
        boolean registered = false;
        try {
            Index index = Index.open(source);
            Handlers.register(archive.toFile(), index, source);
            if (!archive.toFile().getAbsoluteFile().equals(Handlers.archive())
                    || Handlers.index() != index
                    || Handlers.source() != source) {
                throw new IllegalStateException("Runner handler did not register " + archive.toAbsolutePath());
            }
            registered = true;
            return new RunnerRegistration(source);
        } finally {
            if (!registered) {
                Handlers.unregister();
                source.close();
            }
        }
    }

    @Override
    public void close() {
        ArchiveSource open = source;
        if (open != null) {
            source = null;
            Handlers.unregister();
            open.close();
        }
    }
}
