/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Isolates manifest package-table lookup from class definition and archive I/O. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class PackageLookupBenchmark {

    /** Shapes differ in jar count and in per-jar manifest package-section count. */
    @Param({"small", "representative", "wide"})
    public String workload;

    private ArchiveSource source;
    private ArchiveSource noManifestSource;
    private Index index;
    private Index noManifestIndex;
    private List<PackageRecord> records;
    private int noManifestJar;
    private int cursor;

    /** Opens both the selected manifest-rich index and a zero-package control index. */
    @Setup
    public void setUp() throws IOException {
        source = ArchiveSource.open(SyntheticArchive.forWorkload(workload).storedRunnerJar().toFile());
        index = Index.open(source);
        records = records(index);
        if (records.isEmpty()) {
            throw new IllegalStateException("Expected package records for " + workload);
        }

        noManifestSource = ArchiveSource.open(SyntheticArchive.forWorkload("no-manifest").storedRunnerJar().toFile());
        noManifestIndex = Index.open(noManifestSource);
        noManifestJar = firstJarWithNoPackages(noManifestIndex);
    }

    /** Closes mapped archive sources after each independent fork's trial. */
    @TearDown
    public void tearDown() throws Exception {
        noManifestSource.close();
        source.close();
    }

    /** Existing package lookup through the current per-jar linear scan. */
    @Benchmark
    public int findPackagePresent() {
        PackageRecord record = next();
        return index.findPackage(record.jarId(), record.name());
    }

    /** Missing package lookup through all package sections for one jar. */
    @Benchmark
    public int findPackageMissing() {
        PackageRecord record = next();
        return index.findPackage(record.jarId(), record.name() + ".absent");
    }

    /** Control for the zero-package fast path. */
    @Benchmark
    public int findPackageWithNoManifestSections() {
        return noManifestIndex.findPackage(noManifestJar, "org.synthetic.absent");
    }

    private PackageRecord next() {
        PackageRecord record = records.get(cursor);
        cursor = (cursor + 1) % records.size();
        return record;
    }

    private static List<PackageRecord> records(Index index) {
        List<PackageRecord> result = new ArrayList<>();
        for (int jar = 0; jar < index.jarCount(); jar++) {
            int first = index.jarFirstPackage(jar);
            int count = index.jarPackageCount(jar);
            for (int packageIndex = 0; packageIndex < count; packageIndex++) {
                int record = first + packageIndex;
                result.add(new PackageRecord(jar, index.packageName(record)));
            }
        }
        return result;
    }

    private static int firstJarWithNoPackages(Index index) {
        for (int jar = 0; jar < index.jarCount(); jar++) {
            if (index.jarPackageCount(jar) == 0) {
                return jar;
            }
        }
        throw new IllegalStateException("Expected an index with a zero-package jar");
    }

    private record PackageRecord(int jarId, String name) {
    }
}
