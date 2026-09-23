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
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/** Shared operation definitions; concrete subclasses keep each loader/layout in independent JVM forks. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
public abstract class RepresentativeResourceBenchmark {

    /** Scale/locality/resource shape. */
    @Param({"no-manifest", "small", "representative", "wide"})
    public String workload;

    private RepresentativeResourceWorkload operations;

    /** Opens the concrete loader outside measured iterations. */
    @Setup(Level.Trial)
    public void setUp() throws Exception {
        SyntheticArchive archive = SyntheticArchive.forWorkload(workload);
        operations = open(archive);
        verifyFixture(archive);
    }

    /** Closes loader, mappings and any global runner registration. */
    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        if (operations != null) {
            operations.close();
        }
    }

    /** 16 same-JAR class-resource lookups; throughput is normalized to lookup operations. */
    @Benchmark
    @OperationsPerInvocation(16)
    public int localLookup() {
        return operations.localLookupCount();
    }

    /** 16 class-resource lookups spread across dependency order. */
    @Benchmark
    @OperationsPerInvocation(16)
    public int spreadLookup() {
        return operations.spreadLookupCount();
    }

    /** 16 realistic late-failing misses. */
    @Benchmark
    @OperationsPerInvocation(16)
    public int missingLookup() {
        return operations.missingLookupCount();
    }

    /** Enumerates one service descriptor from every dependency. */
    @Benchmark
    public int serviceDiscovery() throws Exception {
        return operations.serviceDiscoveryCount();
    }

    /** Enumerates a duplicate same-name resource from every dependency. */
    @Benchmark
    public int duplicateResources() throws Exception {
        return operations.duplicateResourceCount();
    }

    /** Streams the shape's deterministic payload in full. */
    @Benchmark
    public int resourceStream() throws Exception {
        return operations.streamBytes();
    }

    /** Resolves and reads one effective multi-release resource. */
    @Benchmark
    public String multiReleaseResource() throws Exception {
        return operations.multiReleaseValue();
    }

    /** Opens the loader/layout owned by one concrete benchmark fork. */
    protected abstract RepresentativeResourceWorkload open(SyntheticArchive archive) throws Exception;

    private void verifyFixture(SyntheticArchive archive) throws Exception {
        if (operations.localLookupCount() != 16 || operations.spreadLookupCount() != 16
                || operations.missingLookupCount() != 0
                || operations.serviceDiscoveryCount() != archive.shape().jarCount()
                || operations.duplicateResourceCount() != archive.shape().jarCount()
                || operations.streamBytes() != archive.shape().streamResourceBytes()
                || !"version-25".equals(operations.multiReleaseValue())) {
            throw new IllegalStateException("Representative fixture verification failed for " + workload);
        }
    }
}
