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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Isolates {@code RunnerClassLoader.findResources} over one same-name record chain.
 *
 * <p>The synthetic runner archive and loader are built once in trial setup, outside the measured method.
 * Each dependency contributes the measured resource exactly once, so the parameter is both the result
 * count and the number of JAR groups the chain traversal must visit. This is a launcher microbenchmark;
 * it does not measure or imply a whole-application startup improvement.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class ResourceEnumerationBenchmark {

    /** Number of dependency JARs contributing the measured resource. */
    @Param({"1", "30", "300"})
    public int contributors;

    private RepresentativeResourceWorkload operations;

    /** Builds and verifies the complete fixture before measurement begins. */
    @Setup(Level.Trial)
    public void setUp() throws Exception {
        SyntheticArchive archive = SyntheticArchive.forResourceEnumeration(contributors);
        operations = RepresentativeResourceWorkload.runner(archive, false);
        int actual = operations.duplicateResourceCount();
        if (actual != contributors) {
            throw new IllegalStateException("Expected " + contributors + " contributors but found " + actual);
        }
    }

    /** Releases the mapping and handler registration after the trial. */
    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        if (operations != null) {
            operations.close();
        }
    }

    /** Enumerates one effective URL per contributing JAR, in class-path order. */
    @Benchmark
    public int enumerateSameNameResource() throws Exception {
        return operations.duplicateResourceCount();
    }
}
