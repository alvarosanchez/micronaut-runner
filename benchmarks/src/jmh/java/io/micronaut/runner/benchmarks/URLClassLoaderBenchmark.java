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

import io.micronaut.runner.Handlers;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Thin/exploded (multi-JAR) JDK class loader baseline: a {@link URLClassLoader} over the application classes
 * directory plus every dependency JAR, the layout of the {@code thin-jar} and {@code exploded-cp} startup
 * variants. It runs in forks that never install or inherit the runner handler.
 *
 * <p>{@link ShadedClassLoaderBenchmark} runs the same benchmarks over the flat single-JAR (Shadow/Shade)
 * layout by overriding {@link #classPath(SyntheticArchive)}.</p>
 *
 * <p>The immutable URL array is created once in trial setup so URL conversion is not charged only to this
 * side. Each measured invocation still constructs and closes a fresh loader, and therefore still includes
 * the JDK's real central-directory and class-definition work without cross-invocation loader caches.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-XX:MaxMetaspaceSize=512m"})
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class URLClassLoaderBenchmark {

    private static final int CLASSES = 200;
    private static final int SAME_PACKAGE_CLASSES = 12;

    private String[] names;
    private String[] samePackageNames;
    private URL[] classPath;

    /** Builds the fixture and caches immutable URL inputs without registering a runner archive. */
    @Setup(Level.Trial)
    public void setUp() {
        if (Handlers.registered()) {
            throw new IllegalStateException("URLClassLoader baseline inherited runner registration");
        }
        String packages = System.getProperty(Handlers.HANDLER_PACKAGES_PROPERTY, "");
        if (Arrays.asList(packages.split("\\|", -1)).contains(Handlers.PROTOCOL_PACKAGE)) {
            throw new IllegalStateException("URLClassLoader baseline inherited runner handler configuration");
        }
        SyntheticArchive archive = SyntheticArchive.shared();
        names = archive.spreadSample(CLASSES);
        samePackageNames = archive.samePackageSample(SAME_PACKAGE_CLASSES);
        classPath = classPath(archive);
        try {
            ClassLoaderBenchmarkSanity.verifyUrlClassPath(classPath, names[0]);
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("URLClassLoader fixture sanity check failed", e);
        }
    }

    /**
     * The layout this baseline loads from.
     *
     * @param archive the fixture
     * @return the application classes directory followed by every dependency JAR
     */
    protected URL[] classPath(SyntheticArchive archive) {
        return archive.classPathUrls();
    }

    /**
     * Creates a fresh URL class loader and defines the sampled classes using cached class-path URLs.
     *
     * @param blackhole consumes the loaded classes
     * @throws Exception if a class cannot be loaded
     */
    @Benchmark
    public void urlClassLoader(Blackhole blackhole) throws Exception {
        URLClassLoader loader = new URLClassLoader("benchmark", classPath, ClassLoader.getPlatformClassLoader());
        try {
            for (int i = 0; i < CLASSES; i++) {
                blackhole.consume(loader.loadClass(names[i]));
            }
        } finally {
            loader.close();
        }
    }

    /**
     * Defines several different classes from one package through one fresh loader.
     *
     * @param blackhole consumes the loaded classes
     * @throws Exception if a class cannot be loaded
     */
    @Benchmark
    public void urlClassesInSamePackage(Blackhole blackhole) throws Exception {
        URLClassLoader loader = new URLClassLoader("benchmark", classPath, ClassLoader.getPlatformClassLoader());
        try {
            for (String name : samePackageNames) {
                blackhole.consume(loader.loadClass(name));
            }
        } finally {
            loader.close();
        }
    }

    /**
     * Creates a fresh URL class loader and performs hit and miss lookups using cached class-path URLs.
     *
     * @param blackhole consumes the resource URLs
     * @throws IOException if a class-path jar cannot be read
     */
    @Benchmark
    public void urlGetResource(Blackhole blackhole) throws IOException {
        URLClassLoader loader = new URLClassLoader("benchmark", classPath, ClassLoader.getPlatformClassLoader());
        try {
            for (int i = 0; i < CLASSES; i++) {
                String resource = names[i].replace('.', '/') + ".class";
                blackhole.consume(loader.getResource(resource));
                blackhole.consume(loader.getResource(resource + ".absent"));
            }
        } finally {
            loader.close();
        }
    }
}
