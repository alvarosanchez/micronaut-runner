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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The last step of a class load: handing bytes to {@code ClassLoader.defineClass}, from a memory-mapped
 * slice of the archive against a {@code byte[]}, and from a STORED nested entry against a DEFLATE one.
 *
 * <h2>What "zero copy" would mean, and why this is not it</h2>
 * <p>The tempting claim about the mapped-buffer path is that the bytes never get copied. On JDK 26 that is
 * not true, and it was only ever true in a narrow sense on JDK 25. {@code ClassLoader.defineClass} with a
 * {@link ByteBuffer} hands the buffer to the VM, and for a class loader that is not one of the JDK's
 * built-in loaders the VM copies the contents into its own native buffer before parsing them. A runner jar
 * is always loaded by {@code RunnerClassLoader}, which is by definition not built in.</p>
 * <p>So what this benchmark measures is narrower and honest: the mapped path avoids the
 * <em>application-side</em> copy - the {@code byte[]} that the archive would otherwise allocate, fill from
 * the mapping and hand over, one allocation and one memory traversal per class, all of it immediate
 * garbage. The VM-side copy happens either way. A result showing the mapped path ahead is showing the cost
 * of that one avoided copy and of the allocation behind it, not a zero-copy path.</p>
 *
 * <h2>Fresh loader per invocation</h2>
 * <p>A name can be defined in a loader exactly once, so each invocation builds a throwaway loader. That
 * constant is inside all four results equally; compare the differences, not the ratios.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-XX:MaxMetaspaceSize=512m"})
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class DefineClassBenchmark {

    /** How many distinct classes the benchmark rotates through; a power of two so the wrap is a mask. */
    private static final int CLASSES = 64;

    private ArchiveSource storedSource;
    private ArchiveSource deflatedSource;
    private Record[] stored;
    private Record[] deflated;
    private int cursor;

    /**
     * Opens both archives and resolves the same classes in each.
     *
     * @throws IOException if an archive cannot be opened
     */
    @Setup(Level.Trial)
    public void setUp() throws IOException {
        SyntheticArchive archive = SyntheticArchive.shared();
        storedSource = ArchiveSource.open(archive.storedRunnerJar().toFile());
        deflatedSource = ArchiveSource.open(archive.preserveRunnerJar().toFile());
        Index storedIndex = Index.open(storedSource);
        Index deflatedIndex = Index.open(deflatedSource);

        String[] names = archive.spreadSample(CLASSES);
        stored = records(storedIndex, names, IndexFormat.METHOD_STORED);
        deflated = records(deflatedIndex, names, IndexFormat.METHOD_DEFLATED);
    }

    /** Closes both archives. */
    @TearDown(Level.Trial)
    public void tearDown() {
        if (storedSource != null) {
            storedSource.close();
        }
        if (deflatedSource != null) {
            deflatedSource.close();
        }
    }

    /**
     * STORED entry, defined straight from a slice of the mapping: no application-side copy.
     *
     * @return the defined class
     * @throws IOException if the slice cannot be taken
     */
    @Benchmark
    public Class<?> storedFromMappedBuffer() throws IOException {
        Record record = stored[next()];
        ByteBuffer bytes = storedSource.slice(record.offset(), record.uncompressedSize());
        return new DefiningLoader().define(record.name(), bytes);
    }

    /**
     * STORED entry, copied into a {@code byte[]} first: what the loader would do without the mapping.
     *
     * @return the defined class
     * @throws IOException if the entry cannot be read
     */
    @Benchmark
    public Class<?> storedFromByteArray() throws IOException {
        Record record = stored[next()];
        byte[] bytes = storedSource.readFully(record.offset(), record.uncompressedSize());
        return new DefiningLoader().define(record.name(), bytes);
    }

    /**
     * DEFLATE entry, inflated into a {@code byte[]}: the only shape a compressed entry can take.
     *
     * @return the defined class
     * @throws IOException if the entry cannot be inflated
     */
    @Benchmark
    public Class<?> deflatedFromByteArray() throws IOException {
        Record record = deflated[next()];
        byte[] bytes = deflatedSource.inflate(record.offset(), record.compressedSize(),
                record.uncompressedSize());
        return new DefiningLoader().define(record.name(), bytes);
    }

    /**
     * DEFLATE entry, inflated and then wrapped in a heap {@link ByteBuffer}, which shows that taking the
     * buffer overload does not recover anything once the bytes had to be materialised anyway.
     *
     * @return the defined class
     * @throws IOException if the entry cannot be inflated
     */
    @Benchmark
    public Class<?> deflatedThroughBuffer() throws IOException {
        Record record = deflated[next()];
        byte[] bytes = deflatedSource.inflate(record.offset(), record.compressedSize(),
                record.uncompressedSize());
        return new DefiningLoader().define(record.name(), ByteBuffer.wrap(bytes));
    }

    private int next() {
        return (cursor = cursor + 1) & (CLASSES - 1);
    }

    private static Record[] records(Index index, String[] names, int expectedMethod) {
        List<Record> found = new ArrayList<>(names.length);
        for (String name : names) {
            int record = index.findClass(name);
            if (record == IndexFormat.NO_INDEX) {
                throw new IllegalStateException("Fixture does not contain " + name);
            }
            if (index.entryMethod(record) != expectedMethod) {
                throw new IllegalStateException(name + " is stored with method "
                        + index.entryMethod(record) + ", expected " + expectedMethod
                        + "; the fixture's compression modes are not what this benchmark assumes");
            }
            found.add(new Record(name, index.entryDataOffset(record),
                    (int) index.entryCompressedSize(record), (int) index.entryUncompressedSize(record)));
        }
        return found.toArray(new Record[0]);
    }

    /** One resolved entry: everything needed to read it without touching the index again. */
    private record Record(String name, long offset, int compressedSize, int uncompressedSize) {
    }

    /**
     * A throwaway loader whose only job is to make {@code defineClass} reachable. It has no parent
     * delegation to speak of and the generated classes extend {@code Object} only, so nothing else is
     * loaded while a definition is being timed.
     */
    private static final class DefiningLoader extends ClassLoader {

        DefiningLoader() {
            super("define-class-benchmark", ClassLoader.getPlatformClassLoader());
        }

        Class<?> define(String name, ByteBuffer bytes) {
            return defineClass(name, bytes, null);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length, null);
        }
    }
}
