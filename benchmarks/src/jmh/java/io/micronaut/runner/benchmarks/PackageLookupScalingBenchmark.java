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

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Measures steady and first package metadata lookup as the per-jar package table grows. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class PackageLookupScalingBenchmark {

    /** A zero-record control, small tables and the large tables from the original issue fixture. */
    @State(Scope.Thread)
    public static class LookupState {

        /** Manifest package sections in the selected dependency. */
        @Param({"0", "2", "30", "300", "1000"})
        public int packageSections;

        private ArchiveSource source;
        private Index index;
        private int jarId;
        private String[] present;
        private String[] missing;
        private int cursor;

        /** Opens one index and initializes its bounded cache outside steady-state measurement. */
        @Setup(Level.Trial)
        public void setUp() throws IOException {
            source = ArchiveSource.open(SyntheticArchive.forPackageLookup(packageSections)
                    .storedRunnerJar().toFile());
            index = Index.open(source);
            jarId = packageSections == 0 ? firstJarWithNoPackages(index) : firstJarWithPackages(index);
            present = packageSections == 0 ? new String[] {"org.synthetic.absent"} : packageNames(index, jarId);
            missing = new String[present.length];
            for (int i = 0; i < present.length; i++) {
                missing[i] = present[i] + ".absent";
            }
            index.findPackage(jarId, present[0]);
        }

        /** Closes the mapped source after the trial. */
        @TearDown(Level.Trial)
        public void tearDown() {
            source.close();
        }

        private int next() {
            int selected = cursor;
            cursor = (cursor + 1) % present.length;
            return selected;
        }
    }

    /** Reopens the immutable index before each invocation so the timed lookup initializes the cache. */
    @State(Scope.Thread)
    public static class FirstLookupState {

        /** Manifest package sections in the selected dependency. */
        @Param({"0", "2", "30", "300", "1000"})
        public int packageSections;

        private ArchiveSource source;
        private Index index;
        private int jarId;
        private String name;

        /** Opens the archive and resolves one package name without initializing the measured index. */
        @Setup(Level.Trial)
        public void setUpTrial() throws IOException {
            source = ArchiveSource.open(SyntheticArchive.forPackageLookup(packageSections)
                    .storedRunnerJar().toFile());
            Index reference = Index.open(source);
            jarId = packageSections == 0 ? firstJarWithNoPackages(reference) : firstJarWithPackages(reference);
            name = packageSections == 0 ? "org.synthetic.absent" : reference.packageName(
                    reference.jarFirstPackage(jarId));
        }

        /** Creates an uninitialized reader; JMH excludes invocation setup from the score. */
        @Setup(Level.Invocation)
        public void setUpInvocation() throws IOException {
            index = Index.open(source);
        }

        /** Closes the mapped source after the trial. */
        @TearDown(Level.Trial)
        public void tearDown() {
            source.close();
        }
    }

    /** A hit spread across every declared package, or the zero-record control when the parameter is zero. */
    @Benchmark
    public int findPresent(LookupState state) {
        return state.index.findPackage(state.jarId, state.present[state.next()]);
    }

    /** A miss through a table already initialized from fixed package metadata. */
    @Benchmark
    public int findMissing(LookupState state) {
        return state.index.findPackage(state.jarId, state.missing[state.next()]);
    }

    /** Repeated class definitions in one package resolve the same fixed metadata name. */
    @Benchmark
    public int findSamePackage(LookupState state) {
        return state.index.findPackage(state.jarId, state.present[0]);
    }

    /** Includes lazy decoded-name table construction, while excluding index-open setup. */
    @Benchmark
    public int initializeAndFind(FirstLookupState state) {
        return state.index.findPackage(state.jarId, state.name);
    }

    private static int firstJarWithPackages(Index index) {
        for (int jar = 0; jar < index.jarCount(); jar++) {
            if (index.jarPackageCount(jar) > 0) {
                return jar;
            }
        }
        throw new IllegalStateException("Expected package metadata");
    }

    private static int firstJarWithNoPackages(Index index) {
        for (int jar = 0; jar < index.jarCount(); jar++) {
            if (index.jarPackageCount(jar) == 0) {
                return jar;
            }
        }
        throw new IllegalStateException("Expected a zero-package jar");
    }

    private static String[] packageNames(Index index, int jarId) {
        int first = index.jarFirstPackage(jarId);
        int count = index.jarPackageCount(jarId);
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = index.packageName(first + i);
        }
        return names;
    }
}
