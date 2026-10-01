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
 * What one build-time class transform did to the classes of a runner jar it ran over: how many it rewrote,
 * how many it left as they were, and how many it had to give up on. Those are the dependency classes, and for
 * {@code desugarLambdas} the application's own classes too.
 *
 * <p>Every class a transform considered is counted exactly once: {@link #rewritten()},
 * {@link #unchanged()} and {@link #fallbacks()} add up to the number of classes it considered. A fallback is
 * a class the transform would have rewritten but did not, because the transform failed on it or its rewritten
 * bytes verified worse than the original; the class is then nested as the other transforms left it, and the
 * build log says why. A class a transform generates is not counted: it only lowers {@link #bytesSaved()}.</p>
 *
 * <p>Package-private, like the result accessor that returns it: the transforms are
 * {@link RunnerJarOption.Exposure#PASSTHROUGH PASSTHROUGH} options, and a report becomes public API only with the
 * release that makes one of them {@link RunnerJarOption.Exposure#TYPED TYPED}.</p>
 */
final class TransformReport {

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
     * {@code desugarLambdas} or {@code stripLocalVariables}.
     *
     * @return the name
     */
    String step() {
        return step;
    }

    /**
     * The number of classes the transform rewrote.
     *
     * @return the rewritten count
     */
    int rewritten() {
        return rewritten;
    }

    /**
     * The number of classes the transform left byte for byte as they were: it had nothing to
     * change in them, or it does not apply to them.
     *
     * @return the unchanged count
     */
    int unchanged() {
        return unchanged;
    }

    /**
     * The number of classes the transform would have rewritten but gave up on.
     *
     * @return the fallback count
     */
    int fallbacks() {
        return fallbacks;
    }

    /**
     * How many bytes smaller the classes the transform rewrote became, together, less the size of the classes
     * it generated. It is negative when the classes grew, as they do when lambdas are desugared.
     *
     * @return the bytes saved
     */
    long bytesSaved() {
        return bytesSaved;
    }

    /**
     * The number of classes the transform considered.
     *
     * @return {@code rewritten() + unchanged() + fallbacks()}
     */
    int classes() {
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
