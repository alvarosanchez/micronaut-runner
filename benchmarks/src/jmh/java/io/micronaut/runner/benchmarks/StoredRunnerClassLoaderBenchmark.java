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
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Measures fresh runner class loaders against a matching STORED archive registration.
 *
 * <p>The runner variants use separate benchmark classes because handler registration belongs to one outer
 * archive for the life of a JVM. JMH forks each benchmark independently. Registration and fixture sanity
 * checks happen once per trial, while every measured invocation still opens the archive, validates its
 * index, creates a loader, defines the sampled classes, looks up already loaded JDK classes or performs
 * resource lookups, and closes the mapping.</p>
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
    private static final int JDK_BASE_CLASSES = 500;
    private static final int JDK_SQL_CLASSES = 20;

    private SyntheticArchive archive;
    private String[] names;
    private String[] samePackageNames;
    private String[] jdkNames;
    private RunnerRegistration registration;

    /** Opens the STORED registration outside measurement and keeps it alive for this trial. */
    @Setup(Level.Trial)
    public void setUp() throws IOException, ClassNotFoundException {
        archive = SyntheticArchive.shared();
        names = archive.spreadSample(CLASSES);
        samePackageNames = archive.samePackageSample(SAME_PACKAGE_CLASSES);
        jdkNames = jdkNames();
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
     * Creates a fresh loader and archive mapping, then looks up JDK classes through it: 500 of
     * {@code java.base}, whose packages belong to the boot loader, and 20 of {@code java.sql}, whose package
     * belongs to the platform loader. Every one is already loaded, so this measures delegation alone.
     *
     * @param blackhole consumes the loaded classes
     * @throws Exception if a class cannot be loaded
     */
    @Benchmark
    public void runnerJdkDelegation(Blackhole blackhole) throws Exception {
        ArchiveSource source = ArchiveSource.open(archive.storedRunnerJar().toFile());
        try {
            Index index = Index.open(source);
            RunnerClassLoader loader = new RunnerClassLoader(index, source, RunnerClassLoader.defaultParent());
            for (String name : jdkNames) {
                blackhole.consume(loader.loadClass(name));
            }
        } finally {
            source.close();
        }
    }

    /**
     * The JDK class names {@link #runnerJdkDelegation} looks up: the first {@value #JDK_BASE_CLASSES}
     * top-level classes of {@code java.base} under {@code java/} and the first {@value #JDK_SQL_CLASSES} of
     * {@code java.sql} under {@code java/sql/}, each in sorted order, all loaded once here so that nothing is
     * defined while measuring.
     *
     * <p>Each name is loaded through the loader that defines it. Loading a {@code java.base} class through
     * the platform loader would make the platform loader one of its initiating loaders, and its
     * {@code findLoadedClass} would then answer at once: a startup rarely sees that, since the platform loader
     * was already an initiating loader for 18 of the about 380 JDK names a benchmark-large startup asks
     * {@link RunnerClassLoader} for.</p>
     *
     * @return the names
     * @throws IOException            if the runtime image cannot be listed
     * @throws ClassNotFoundException if one of the names cannot be loaded
     */
    private static String[] jdkNames() throws IOException, ClassNotFoundException {
        FileSystem image = FileSystems.getFileSystem(URI.create("jrt:/"));
        List<String> base = topLevelClasses(image.getPath("/modules", "java.base"), "java", JDK_BASE_CLASSES);
        List<String> sql = topLevelClasses(image.getPath("/modules", "java.sql"), "java/sql", JDK_SQL_CLASSES);
        for (String name : base) {
            Class.forName(name, false, null);
        }
        for (String name : sql) {
            Class.forName(name, false, ClassLoader.getPlatformClassLoader());
        }
        List<String> selected = new ArrayList<>(JDK_BASE_CLASSES + JDK_SQL_CLASSES);
        selected.addAll(base);
        selected.addAll(sql);
        return selected.toArray(new String[0]);
    }

    private static List<String> topLevelClasses(Path module, String directory, int limit) throws IOException {
        Path root = module.resolve(directory);
        try (Stream<Path> files = Files.walk(root)) {
            List<String> classes = files
                    .map(file -> module.relativize(file).toString())
                    .filter(name -> name.endsWith(".class") && name.indexOf('$') < 0
                            && !name.endsWith("module-info.class") && !name.endsWith("package-info.class"))
                    .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                    .sorted()
                    .limit(limit)
                    .toList();
            if (classes.size() != limit) {
                throw new IllegalStateException(root + " holds " + classes.size() + " top-level classes, fewer than "
                        + limit);
            }
            return classes;
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
