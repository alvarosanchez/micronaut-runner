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
import io.micronaut.runner.RunnerClassLoader;
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
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Measures fresh runner class loaders against a matching STORED archive registration.
 *
 * <p>The runner variants use separate benchmark classes because handler registration belongs to one outer
 * archive for the life of a JVM. JMH forks each benchmark independently. Registration and fixture sanity
 * checks happen once per trial, while every measured invocation still opens the archive, validates its
 * index, creates a loader, defines the sampled classes or performs resource lookups, and closes the mapping.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-XX:MaxMetaspaceSize=512m"})
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class StoredRunnerClassLoaderBenchmark {

    private static final int CLASSES = 200;
    private static final int SAME_PACKAGE_CLASSES = 12;

    private SyntheticArchive archive;
    private String[] names;
    private String[] samePackageNames;
    private RunnerRegistration registration;

    /** Opens the STORED registration outside measurement and keeps it alive for this trial. */
    @Setup(Level.Trial)
    public void setUp() throws IOException, ClassNotFoundException {
        archive = SyntheticArchive.shared();
        names = archive.spreadSample(CLASSES);
        samePackageNames = archive.samePackageSample(SAME_PACKAGE_CLASSES);
        RunnerRegistration open = RunnerRegistration.open(archive.storedRunnerJar());
        try {
            ClassLoaderBenchmarkSanity.verifyRunnerArchive(archive.storedRunnerJar(), names[0]);
            registration = open;
        } finally {
            if (registration == null) {
                open.close();
            }
        }
    }

    /** Unregisters the archive before closing its mapping. */
    @TearDown(Level.Trial)
    public void tearDown() {
        if (registration != null) {
            registration.close();
        }
    }

    /**
     * Creates a fresh loader and archive mapping, then defines the sampled classes.
     *
     * @param blackhole consumes the loaded classes
     * @throws Exception if a class cannot be loaded
     */
    @Benchmark
    public void runnerClassLoader(Blackhole blackhole) throws Exception {
        ArchiveSource source = ArchiveSource.open(archive.storedRunnerJar().toFile());
        try {
            Index index = Index.open(source);
            RunnerClassLoader loader = new RunnerClassLoader(index, source, RunnerClassLoader.defaultParent());
            for (int i = 0; i < CLASSES; i++) {
                blackhole.consume(loader.loadClass(names[i]));
            }
        } finally {
            source.close();
        }
    }

    /** Defines several different classes from one package through one fresh loader. */
    @Benchmark
    public void runnerClassesInSamePackage(Blackhole blackhole) throws Exception {
        ArchiveSource source = ArchiveSource.open(archive.storedRunnerJar().toFile());
        try {
            Index index = Index.open(source);
            RunnerClassLoader loader = new RunnerClassLoader(index, source, RunnerClassLoader.defaultParent());
            for (String name : samePackageNames) {
                blackhole.consume(loader.loadClass(name));
            }
        } finally {
            source.close();
        }
    }

    /**
     * Creates a fresh loader and archive mapping, then performs hit and miss resource lookups.
     *
     * @param blackhole consumes the resource URLs
     * @throws IOException if the archive cannot be opened
     */
    @Benchmark
    public void runnerGetResource(Blackhole blackhole) throws IOException {
        ArchiveSource source = ArchiveSource.open(archive.storedRunnerJar().toFile());
        try {
            Index index = Index.open(source);
            RunnerClassLoader loader = new RunnerClassLoader(index, source, RunnerClassLoader.defaultParent());
            for (int i = 0; i < CLASSES; i++) {
                String resource = names[i].replace('.', '/') + ".class";
                blackhole.consume(loader.getResource(resource));
                blackhole.consume(loader.getResource(resource + ".absent"));
            }
        } finally {
            source.close();
        }
    }
}
