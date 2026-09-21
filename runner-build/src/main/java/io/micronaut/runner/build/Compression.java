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

/**
 * How a dependency is stored inside the runner jar.
 *
 * <p>Every entry of the <em>outer</em> archive is always stored uncompressed, because the launcher maps the
 * file and slices it; this option only decides what happens to the <em>inner</em> entries of the nested
 * dependency jars.</p>
 *
 * @since 1.0
 */
public enum Compression {

    /**
     * Rewrite every dependency so that all of its entries are stored uncompressed.
     *
     * <p>This is the default, and the mode the format is designed for: a stored nested class can be handed
     * to {@code ClassLoader.defineClass} as a slice of the memory mapped outer archive, with no inflater and
     * no intermediate array. The price is a larger artifact.</p>
     */
    STORED,

    /**
     * Copy every dependency byte for byte, keeping its original compression.
     *
     * <p>The artifact stays as small as the dependencies made it and their bytes are untouched, which is
     * what a build that has to reproduce a published jar exactly needs. Classes then have to be inflated
     * when they are loaded.</p>
     */
    PRESERVE
}
