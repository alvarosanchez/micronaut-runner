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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The options of a build that every repack applies to its nested jar: the rank of each startup class, which
 * orders each nested jar's entries hot-first, and whether the build is {@link Compression#HYBRID}, which keeps the
 * cold classes compressed. Neither changes a class. Empty options, {@link #NONE}, leave every entry where it is and
 * stored.
 *
 * @param ranks  each startup class's position in the recorded list, keyed by its entry name, such as
 *               {@code a/b/C.class}
 * @param hybrid whether cold classes keep their compressed bytes
 */
record RepackOptions(Map<String, Integer> ranks, boolean hybrid) {

    /** No startup class and not {@code HYBRID}. */
    static final RepackOptions NONE = new RepackOptions(Map.of(), false);

    /** The prefix of a multi-release entry, whose rank is that of the class it versions. */
    private static final String VERSIONS = "META-INF/versions/";

    /**
     * Makes the map immutable.
     *
     * @param ranks  the ranks
     * @param hybrid whether the build is {@code HYBRID}
     */
    RepackOptions {
        ranks = Map.copyOf(ranks);
    }

    /**
     * The options of a startup class list.
     *
     * @param startupClasses the binary names of the startup classes, in the order they were recorded and once each,
     *                       as {@link StartupClassList#classes()} gives them
     * @param hybrid         whether the build is {@code HYBRID}
     * @return the options
     */
    static RepackOptions of(List<String> startupClasses, boolean hybrid) {
        Map<String, Integer> ranks = new HashMap<>(Math.max(16, startupClasses.size() * 2));
        for (int rank = 0; rank < startupClasses.size(); rank++) {
            ranks.putIfAbsent(startupClasses.get(rank).replace('.', '/') + ".class", rank);
        }
        return new RepackOptions(ranks, hybrid);
    }

    /**
     * Whether the options do anything: there is a startup class, or the build is {@code HYBRID}.
     *
     * @return whether a repack has to order entries or choose their methods
     */
    boolean any() {
        return hybrid || !ranks.isEmpty();
    }

    /**
     * The same startup class ranks, with every nested jar written {@code STORED}.
     *
     * @return the options
     */
    RepackOptions stored() {
        return hybrid ? new RepackOptions(ranks, false) : this;
    }

    /**
     * The rank of an entry of a nested jar: its class's position in the startup class list, for the class itself
     * and for each of its {@code META-INF/versions/N/} variants.
     *
     * @param entryName the entry name
     * @return the rank, or {@code -1} when the entry is cold
     */
    int rank(String entryName) {
        if (ranks.isEmpty()) {
            return -1;
        }
        Integer rank = ranks.get(entryName);
        if (rank == null && entryName.startsWith(VERSIONS) && IndexWriter.versionOf(entryName) != 0) {
            rank = ranks.get(IndexWriter.pathOf(entryName));
        }
        return rank == null ? -1 : rank;
    }
}
