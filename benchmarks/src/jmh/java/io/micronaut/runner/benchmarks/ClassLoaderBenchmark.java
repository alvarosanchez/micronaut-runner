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
import io.micronaut.runner.Handlers;
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
import java.net.URLClassLoader;
import java.util.concurrent.TimeUnit;

/**
 * Loading a burst of classes through {@link RunnerClassLoader} against the same burst through a
 * {@link URLClassLoader} over the same jars - the comparison the whole format exists to win.
 *
 * <h2>The per-invocation setup is inside the measurement, on purpose</h2>
 * <p>Both benchmarks create their loader, load {@value #CLASSES} classes and dispose of the loader, all
 * inside the timed method. That is not an oversight and it is not a JMH mistake to be cleaned up by
 * moving the work into {@code @Setup(Level.Invocation)}:</p>
 * <ul>
 *   <li>A class can be defined in a given loader exactly once. Reusing a loader across invocations would
 *       measure {@link ClassLoader#findLoadedClass}, a hash map hit, for every invocation after the
 *       first - the same very small number for both candidates, and nothing to do with either design.</li>
 *   <li>Opening the archive is <em>part of the cost</em> that a real startup pays: the runner jar is
 *       memory-mapped and its index is validated once, and a {@code URLClassLoader} opens and parses the
 *       central directory of each jar the first time it needs it. Timing only the second class load of a
 *       warm loader would hide exactly the trade the format makes.</li>
 *   <li>{@code Level.Invocation} setup has a documented accuracy problem for operations this short, and
 *       it would in any case attribute the archive open to neither side.</li>
 * </ul>
 * <p>The consequence to keep in mind when reading the numbers: a constant, identical-for-both overhead
 * (creating a loader object, the {@code Blackhole} calls) is inside both results, so the <em>ratio</em>
 * between the two is conservative while the <em>difference</em> is the honest one.</p>
 *
 * <p>The {@link ArchiveSource} is closed in a {@code finally} block on every invocation. It owns a
 * {@code MemorySegment} arena; leaving those to a garbage collector that has no idea how much address
 * space they are holding is how a benchmark ends up measuring the operating system's page reclaim.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
// A metaspace ceiling, because every invocation defines CLASSES fresh classes in a fresh loader. The
// limit is what makes the collector unload the dead loaders promptly instead of letting the JVM grow
// until the measurement is really a measurement of memory pressure.
@Fork(value = 1, jvmArgsAppend = {"-XX:MaxMetaspaceSize=512m"})
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class ClassLoaderBenchmark {

    /** How many classes each invocation loads. Enough to dominate the loader construction, small enough
     *  that an invocation stays in the hundreds of microseconds. */
    private static final int CLASSES = 200;

    private SyntheticArchive archive;
    private String[] names;
    private ArchiveSource registrationSource;

    /**
     * Builds the fixture and registers the {@code jar:} protocol handler once.
     *
     * <p>The handler registration is deliberately not part of the measurement: it is a one-shot per JVM in
     * production too. It has to happen, though, because without it the code source URL of every nested jar
     * would be {@code null} and {@code ClassLoader.defineClass} would skip work it does for real.</p>
     *
     * @throws IOException if the archive cannot be opened
     */
    @Setup(Level.Trial)
    public void setUp() throws IOException {
        archive = SyntheticArchive.shared();
        names = archive.spreadSample(CLASSES);
        registrationSource = ArchiveSource.open(archive.storedRunnerJar().toFile());
        Index registrationIndex = Index.open(registrationSource);
        Handlers.register(archive.storedRunnerJar().toFile(), registrationIndex, registrationSource);
    }

    /** Closes the archive the handler registration holds open. */
    @TearDown(Level.Trial)
    public void tearDown() {
        if (registrationSource != null) {
            registrationSource.close();
        }
    }

    /**
     * A fresh {@link RunnerClassLoader} over the STORED runner jar, loading {@value #CLASSES} classes.
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

    /**
     * A fresh {@link RunnerClassLoader} over the PRESERVE runner jar, whose nested entries are deflated,
     * loading the same classes. The difference against {@link #runnerClassLoader} is what STORED buys.
     *
     * @param blackhole consumes the loaded classes
     * @throws Exception if a class cannot be loaded
     */
    @Benchmark
    public void runnerClassLoaderDeflated(Blackhole blackhole) throws Exception {
        ArchiveSource source = ArchiveSource.open(archive.preserveRunnerJar().toFile());
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

    /**
     * A fresh {@link URLClassLoader} over the same jars, loading the same classes: the baseline every
     * user already has.
     *
     * @param blackhole consumes the loaded classes
     * @throws Exception if a class cannot be loaded
     */
    @Benchmark
    public void urlClassLoader(Blackhole blackhole) throws Exception {
        URLClassLoader loader = new URLClassLoader("benchmark", archive.classPathUrls(),
                ClassLoader.getPlatformClassLoader());
        try {
            for (int i = 0; i < CLASSES; i++) {
                blackhole.consume(loader.loadClass(names[i]));
            }
        } finally {
            loader.close();
        }
    }

    /**
     * {@link ClassLoader#getResource} for a resource that exists, through both loaders' lookup path. The
     * loader is created inside the measurement for the same reason as above; a resource lookup is cheap
     * enough that the loader construction shows, so read this one as a difference too.
     *
     * @param blackhole consumes the URLs
     * @throws IOException if the archive cannot be opened
     */
    @Benchmark
    public void runnerGetResource(Blackhole blackhole) throws IOException {
        ArchiveSource source = ArchiveSource.open(archive.storedRunnerJar().toFile());
        try {
            Index index = Index.open(source);
            RunnerClassLoader loader = new RunnerClassLoader(index, source, RunnerClassLoader.defaultParent());
            for (int i = 0; i < CLASSES; i++) {
                blackhole.consume(loader.getResource(names[i].replace('.', '/') + ".class"));
                blackhole.consume(loader.getResource(names[i].replace('.', '/') + ".absent"));
            }
        } finally {
            source.close();
        }
    }

    /**
     * The same resource lookups through a {@link URLClassLoader}.
     *
     * @param blackhole consumes the URLs
     * @throws IOException if a jar cannot be read
     */
    @Benchmark
    public void urlGetResource(Blackhole blackhole) throws IOException {
        URLClassLoader loader = new URLClassLoader("benchmark", archive.classPathUrls(),
                ClassLoader.getPlatformClassLoader());
        try {
            for (int i = 0; i < CLASSES; i++) {
                blackhole.consume(loader.getResource(names[i].replace('.', '/') + ".class"));
                blackhole.consume(loader.getResource(names[i].replace('.', '/') + ".absent"));
            }
        } finally {
            loader.close();
        }
    }
}
