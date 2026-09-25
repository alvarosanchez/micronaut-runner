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

import java.util.Objects;

/**
 * What one build-time class transform did to the dependency classes of a runner jar: how many it rewrote,
 * how many it left as they were, and how many it had to give up on.
 *
 * <p>Every class a transform considered is counted exactly once: {@link #rewritten()},
 * {@link #unchanged()} and {@link #fallbacks()} add up to the number of classes it considered. A fallback is
 * a class the transform would have rewritten but did not, because the transform failed on it or its rewritten
 * bytes verified worse than the original; the class is then nested as the other transforms left it, and the
 * build log says why.</p>
 *
 * <p>Only the builder creates a report, so later releases can add accessors without breaking callers.</p>
 *
 * @since 1.0
 */
public final class TransformReport {

    private final String step;
    private final int rewritten;
    private final int unchanged;
    private final int fallbacks;
    private final long bytesSaved;

    /**
     * Validates the counts.
     *
     * @param step       the transform's name, the name of the option that enables it
     * @param rewritten  the classes it rewrote
     * @param unchanged  the classes it left as they were
     * @param fallbacks  the classes it gave up on
     * @param bytesSaved how much smaller the classes it rewrote became, together
     * @throws NullPointerException     if {@code step} is {@code null}
     * @throws IllegalArgumentException if a count is negative
     */
    TransformReport(String step, int rewritten, int unchanged, int fallbacks, long bytesSaved) {
        this.step = Objects.requireNonNull(step, "step");
        if (rewritten < 0 || unchanged < 0 || fallbacks < 0) {
            throw new IllegalArgumentException("Negative count in the report of " + step);
        }
        this.rewritten = rewritten;
        this.unchanged = unchanged;
        this.fallbacks = fallbacks;
        this.bytesSaved = bytesSaved;
    }

    /**
     * The transform's name, which is the name of the {@link RunnerJarOption} that enables it, such as
     * {@code stripLocalVariables}.
     *
     * @return the name
     */
    public String step() {
        return step;
    }

    /**
     * The number of dependency classes the transform rewrote.
     *
     * @return the rewritten count
     */
    public int rewritten() {
        return rewritten;
    }

    /**
     * The number of dependency classes the transform left byte for byte as they were: it had nothing to
     * change in them, or it does not apply to them.
     *
     * @return the unchanged count
     */
    public int unchanged() {
        return unchanged;
    }

    /**
     * The number of dependency classes the transform would have rewritten but gave up on.
     *
     * @return the fallback count
     */
    public int fallbacks() {
        return fallbacks;
    }

    /**
     * How many bytes smaller the classes the transform rewrote became, together. It is negative when they
     * grew.
     *
     * @return the bytes saved
     */
    public long bytesSaved() {
        return bytesSaved;
    }

    /**
     * The number of dependency classes the transform considered.
     *
     * @return {@code rewritten() + unchanged() + fallbacks()}
     */
    public int classes() {
        return rewritten + unchanged + fallbacks;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof TransformReport that
                && step.equals(that.step) && rewritten == that.rewritten && unchanged == that.unchanged
                && fallbacks == that.fallbacks && bytesSaved == that.bytesSaved;
    }

    @Override
    public int hashCode() {
        return Objects.hash(step, rewritten, unchanged, fallbacks, bytesSaved);
    }

    @Override
    public String toString() {
        return step + ": " + rewritten + " rewritten, " + unchanged + " unchanged, " + fallbacks
                + " fallbacks, " + bytesSaved + " bytes saved";
    }
}
