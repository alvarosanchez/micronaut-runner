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
 * resource it reads resolves through one of these two methods. The index promises O(1) with no allocation
 * on any path, so the numbers to watch are the absolute nanoseconds and, just as much, the distance
 * between the hit and the miss - a miss that costs much more than a hit means the probe is walking.</p>
 *
 * <h2>What the four methods separate</h2>
 * <ul>
 *   <li>{@link #findClassHit()} / {@link #findClassMiss()} - {@link Index#findClass(String)}, which takes
 *       a binary name and never builds the {@code a/b/C.class} resource name at all.</li>
 *   <li>{@link #findHit()} / {@link #findMiss()} - {@link Index#find(String)} over the same entries by
 *       their resource names, which is the path every {@code getResource} call takes.</li>
 * </ul>
 *
 * <p>The misses use names shaped exactly like the hits with a suffix appended, so they hash into the same
 * region of the table and are not trivially rejected by a length comparison.</p>
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

    private int next() {
        return (cursor = cursor + 1) & (NAMES - 1);
    }
}
