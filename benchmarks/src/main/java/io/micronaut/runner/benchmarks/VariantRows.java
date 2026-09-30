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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a selection of benchmark rows from a row table, each row at most once, together with exactly the rows
 * the selected ones derive from.
 *
 * <p>Every row is a memoized supplier. A derived row gets its source by asking {@link Sources} for the source
 * row, which builds that row on first use and hands back the same {@link Variant} afterwards. Building a
 * selection therefore calls only the selected rows' factories and, through them, their prerequisites: there is
 * no hand-kept dependency map to drift from how the rows are really built. A prerequisite built only for
 * another row is returned by nobody, so it is neither measured nor reported.</p>
 *
 * @param <S> what the factories build with: the real sample build, or a fake in a unit test
 */
final class VariantRows<S> {

    private final List<Row<S>> table;
    private final S steps;
    private final Attempt attempt;
    private final Map<String, Variant> built = new HashMap<>();
    private final Set<String> building = new HashSet<>();

    private VariantRows(List<Row<S>> table, S steps, Attempt attempt) {
        this.table = List.copyOf(table);
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
        for (String name : selection) {
            rows.row(name);
        }
        List<Variant> variants = new ArrayList<>(selection.size());
        for (Row<S> row : table) {
            if (selection.contains(row.name())) {
                variants.add(rows.get(row.name()));
            }
        }
        return List.copyOf(variants);
    }

    /**
     * Every row's name, in report order.
     *
     * @param table the row table
     * @return the names
     */
    static List<String> names(List<? extends Row<?>> table) {
        return table.stream().map(Row::name).toList();
    }

    /**
     * The core rows' names, in report order.
     *
     * @param table the row table
     * @return the names of the rows that are always built and gate the exit code
     */
    static List<String> coreNames(List<? extends Row<?>> table) {
        return table.stream().filter(Row::core).map(Row::name).toList();
    }

    private Variant get(String name) {
        Variant variant = built.get(name);
        if (variant != null) {
            return variant;
        }
        Row<S> row = row(name);
        if (!building.add(name)) {
            throw new IllegalStateException("row " + name + " derives from itself");
        }
        try {
            variant = attempt.run(row.name(), row.description(), () -> row.factory().create(steps, this::get));
        } finally {
            building.remove(name);
        }
        built.put(name, variant);
        return variant;
    }

    private Row<S> row(String name) {
        for (Row<S> row : table) {
            if (row.name().equals(name)) {
                return row;
            }
        }
        throw new IllegalArgumentException("no row named " + name);
    }

    /**
     * One row of the matrix.
     *
     * @param name        the variant name
     * @param description what the variant is, also used for its unavailable placeholder
     * @param core        whether the row is always built and gates the exit code, rather than opt-in
     * @param factory     builds the row, asking {@link Sources} for any row it derives from
     * @param <S>         the factory's build context
     */
    record Row<S>(String name, String description, boolean core, Factory<S> factory) {
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
         * @param steps   the build context
         * @param sources the other rows, each built at most once
         * @return the variant
         * @throws Exception if it cannot be built; the row then becomes unavailable
         */
        Variant create(S steps, Sources sources) throws Exception;
    }

    /** The rows a derived row can build from; each is built on first use and memoized. */
    @FunctionalInterface
    interface Sources {

        /**
         * Returns a row, building it first if nothing has asked for it yet.
         *
         * @param name the row
         * @return the variant, possibly unavailable
         */
        Variant get(String name);
    }

    /** Runs one factory and turns its failure into an unavailable variant. */
    @FunctionalInterface
    interface Attempt {

        /**
         * Runs the factory.
         *
         * @param name        the row
         * @param description what the row is
         * @param create      the factory call
         * @return the variant, unavailable when {@code create} failed
         */
        Variant run(String name, String description, Creation create);
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
