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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a selection of benchmark rows from a row table, each row at most once, together with exactly the rows
 * the selected ones derive from.
 *
 * <p>Every row is a memoized supplier. A row whose {@link SampleBuild.VariantSpec#source()} names another row is
 * handed that row's variant, built on first use, so the table is the only place that says which row derives from
 * which. A prerequisite built only for another row is returned by nobody, so it is neither measured nor
 * reported.</p>
 *
 * @param <S> what the factories build with: the real sample build, or a fake in a unit test
 */
final class VariantRows<S> {

    private final List<Row<S>> table;
    private final S steps;
    private final Attempt attempt;
    private final Map<String, Variant> built = new HashMap<>();

    private VariantRows(List<Row<S>> table, S steps, Attempt attempt) {
        this.table = table;
        this.steps = steps;
        this.attempt = attempt;
    }

    /**
     * Builds the selected rows.
     *
     * @param table     every row, in report order
     * @param steps     what the factories build with
     * @param selection the names to build and return
     * @param attempt   runs one factory and turns a failure into an unavailable variant
     * @param <S>       the factories' build context
     * @return the selected rows only, in report order, available or not
     * @throws IllegalArgumentException if the selection names a row the table does not have
     */
    static <S> List<Variant> build(List<Row<S>> table, S steps, List<String> selection, Attempt attempt) {
        VariantRows<S> rows = new VariantRows<>(table, steps, attempt);
        selection.forEach(rows::row);
        List<Variant> variants = new ArrayList<>(selection.size());
        for (Row<S> row : table) {
            if (selection.contains(row.spec().name())) {
                variants.add(rows.get(row.spec().name()));
            }
        }
        return List.copyOf(variants);
    }

    private Variant get(String name) {
        Variant variant = built.get(name);
        if (variant == null) {
            Row<S> row = row(name);
            Variant source = row.spec().source() == null ? null : get(row.spec().source());
            variant = attempt.run(row.spec(), () -> row.factory().create(steps, row.spec(), source));
            built.put(name, variant);
        }
        return variant;
    }

    private Row<S> row(String name) {
        return table.stream().filter(row -> row.spec().name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no row named " + name));
    }

    /**
     * One row of the matrix: what it is, and how it is built.
     *
     * @param spec    the row's name, description, entry mode, cache flag, core/opt-in flag and source
     * @param factory builds the row
     * @param <S>     the factory's build context
     */
    record Row<S>(SampleBuild.VariantSpec spec, Factory<S> factory) {
    }

    /**
     * Builds one row.
     *
     * @param <S> the build context
     */
    @FunctionalInterface
    interface Factory<S> {

        /**
         * Builds the row.
         *
         * @param steps  the build context
         * @param spec   the row
         * @param source the variant of the row {@code spec.source()} names, possibly unavailable, or {@code null}
         * @return the variant
         * @throws Exception if it cannot be built; the row then becomes unavailable
         */
        Variant create(S steps, SampleBuild.VariantSpec spec, Variant source) throws Exception;
    }

    /** Runs one factory and turns its failure into an unavailable variant. */
    @FunctionalInterface
    interface Attempt {

        /**
         * Runs the factory.
         *
         * @param spec   the row
         * @param create the factory call
         * @return the variant, unavailable when {@code create} failed
         */
        Variant run(SampleBuild.VariantSpec spec, Creation create);
    }

    /** A factory call bound to its context. */
    @FunctionalInterface
    interface Creation {

        /**
         * Builds the variant.
         *
         * @return the variant
         * @throws Exception if it cannot be built
         */
        Variant create() throws Exception;
    }
}
