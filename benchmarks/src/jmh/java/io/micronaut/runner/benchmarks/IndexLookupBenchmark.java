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
import io.micronaut.runner.Index;
import io.micronaut.runner.IndexFormat;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * What a lookup in {@code MICRONAUT-INF/index.bin} costs.
 *
 * <p>This is the single hottest thing the launcher does: every class the application loads and every
 * resource it reads resolves through one of these methods. The index promises O(1) with no allocation on
 * any path, so what the numbers should show is a hit dominated by one comparison of the stored UTF-8 name
 * and a miss that is cheaper still, because a mismatched hash rejects a candidate before its bytes are
 * ever touched.</p>
 *
 * <h2>What the methods separate</h2>
 * <ul>
 *   <li>{@link #findClassHit()} / {@link #findClassMiss()} - {@link Index#findClass(String)}, which starts
 *       from a binary name, folds the {@code .class} suffix into the hash arithmetically and compares
 *       against the stored bytes while mapping dots to slashes, so it never builds the resource name.</li>
 *   <li>{@link #findHit()} / {@link #findMiss()} - {@link Index#find(String)} called with a name the
 *       caller already has, which is what a {@code getResource("META-INF/…")} with a literal argument
 *       looks like.</li>
 *   <li>{@link #findFromBinaryNameHit()} - the same lookup starting from a binary name, building the
 *       resource name first. This is the honest comparison against {@link #findClassHit()}: same input,
 *       same answer, and it is the work {@code findClass} exists to avoid.</li>
 * </ul>
 *
 * <h2>One artifact to know about before reading the numbers</h2>
 * <p>{@code Index.find} hashes with {@link String#hashCode()}, and {@code String} caches that value in the
 * instance. The name arrays here are built once in {@code @Setup}, so from the second invocation onwards
 * {@link #findHit()} and {@link #findMiss()} get their hash for free - which is why the miss comes out at
 * a couple of nanoseconds. That is a real case (a literal, or a name looked up repeatedly) but it is not
 * every case, and it is <em>not</em> what {@code findClass} does: {@code findClass} recomputes its hash
 * character by character on every single call, because the string it would need to cache a hash for is
 * the one it refuses to allocate. Compare {@link #findClassHit()} against
 * {@link #findFromBinaryNameHit()}, never against {@link #findHit()}.</p>
 *
 * <p>The misses use names shaped exactly like the hits with a suffix appended, so they are the same length
 * and the same shape and are not rejected by some shortcut a shorter name would have taken.</p>
 *
 * <h2>These numbers are memory latency, not arithmetic</h2>
 * <p>Every method here rotates over {@value #NAMES} names, which is far more string data than fits in L1,
 * and then compares against a memory-mapped index. A lookup is therefore two or three cache misses and
 * almost no computation, and differences of a few tens of nanoseconds between the methods are differences
 * in how many cold lines each one touches rather than differences in algorithm. It also means the absolute
 * numbers are the right order of magnitude for a real startup, where each class name is looked up once
 * from cold memory, and would be far smaller - and far less useful - if the benchmark looked one name up a
 * million times.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class IndexLookupBenchmark {

    /** How many distinct names each benchmark rotates through; a power of two so the wrap is a mask. */
    private static final int NAMES = 1024;

    private ArchiveSource source;
    private Index index;
    private String[] presentClasses;
    private String[] absentClasses;
    private String[] presentResources;
    private String[] absentResources;
    private int cursor;

    /**
     * Builds the fixture once and opens the index over it.
     *
     * @throws IOException if the archive cannot be opened
     */
    @Setup(Level.Trial)
    public void setUp() throws IOException {
        SyntheticArchive archive = SyntheticArchive.shared();
        source = ArchiveSource.open(archive.storedRunnerJar().toFile());
        index = Index.open(source);
        presentClasses = archive.spreadSample(NAMES);
        absentClasses = archive.missingSample(NAMES);
        presentResources = new String[NAMES];
        absentResources = new String[NAMES];
        for (int i = 0; i < NAMES; i++) {
            presentResources[i] = presentClasses[i].replace('.', '/') + ".class";
            absentResources[i] = absentClasses[i].replace('.', '/') + ".class";
        }
        // A fixture that does not contain what the benchmark claims to look up would measure nothing at
        // all, and would do it very quickly. Check before the first measurement rather than trust it.
        for (int i = 0; i < NAMES; i++) {
            if (index.findClass(presentClasses[i]) == IndexFormat.NO_INDEX) {
                throw new IllegalStateException("Fixture does not contain " + presentClasses[i]);
            }
            if (index.findClass(absentClasses[i]) != IndexFormat.NO_INDEX) {
                throw new IllegalStateException("Fixture unexpectedly contains " + absentClasses[i]);
            }
        }
    }

    /** Closes the archive. */
    @TearDown(Level.Trial)
    public void tearDown() {
        if (source != null) {
            source.close();
        }
    }

    /**
     * A class that is in the archive, looked up by binary name.
     *
     * @return the record index, returned so the JIT cannot delete the call
     */
    @Benchmark
    public int findClassHit() {
        return index.findClass(presentClasses[next()]);
    }

    /**
     * A class that is not in the archive, looked up by binary name.
     *
     * @return {@link IndexFormat#NO_INDEX}
     */
    @Benchmark
    public int findClassMiss() {
        return index.findClass(absentClasses[next()]);
    }

    /**
     * A resource that is in the archive, looked up by logical name.
     *
     * @return the record index
     */
    @Benchmark
    public int findHit() {
        return index.find(presentResources[next()]);
    }

    /**
     * A resource that is not in the archive, looked up by logical name.
     *
     * @return {@link IndexFormat#NO_INDEX}
     */
    @Benchmark
    public int findMiss() {
        return index.find(absentResources[next()]);
    }

    /**
     * The same hit as {@link #findHit()}, but starting from the binary name the class loader is actually
     * handed: the resource name is built inside the measurement, which allocates a string and leaves it
     * with no cached hash code. Subtract {@link #findClassHit()} from this and what is left is what the
     * index's class-name path buys.
     *
     * @return the record index
     */
    @Benchmark
    public int findFromBinaryNameHit() {
        return index.find(presentClasses[next()].replace('.', '/') + ".class");
    }

    private int next() {
        return (cursor = cursor + 1) & (NAMES - 1);
    }
}
