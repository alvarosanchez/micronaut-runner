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
package io.micronaut.runner.build;

import io.micronaut.runner.IndexFormat;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

/**
 * Stages one dependency: repacks it into its nested jar, or, in PRESERVE, checksums it where it is, and
 * describes the result.
 *
 * <p>A stage may run on a worker thread, so it reads nothing but its own fields and touches no builder
 * state. It opens, uses and closes its {@link ZipReader}, streams, {@link CRC32} and buffers on the
 * thread that runs it; only the {@link Staged} result it returns reaches another thread. When Micronaut AOT's class
 * path transform rewrote the dependency's classes, the stage nests the transform's copy instead of the dependency,
 * and reads the dependency's central directory to tell the classes the transform generated from the dependency's
 * own.</p>
 */
final class DependencyStage implements Callable<DependencyStage.Staged> {

    /**
     * The warning of a HYBRID build without a startup class list. It names no build tool: each plugin's docs name
     * its own recording task.
     */
    static final String HYBRID_WITHOUT_LIST = "HYBRID compression needs a startup-class list; the nested jars were"
            + " written STORED. Record the startup classes and build again";

    /** The warning of a HYBRID build whose startup class list names no class of any dependency. */
    static final String HYBRID_WITHOUT_HOT_ENTRY = "HYBRID compression found none of the listed startup classes in"
            + " any dependency; the nested jars were written STORED. Record the startup classes again from a jar built"
            + " with the same options and build again";

    /** The size of the nested jar's output buffer and of the buffer a preserved dependency is checksummed with. */
    private static final int BUFFER_SIZE = 64 * 1024;

    private final Dependency dependency;
    /** What is nested: the dependency itself, or the copy Micronaut AOT's class path transform wrote of it. */
    private final Path source;
    private final String entryName;
    /** The work file a repacked nested jar is written to; a preserved dependency leaves it unused. */
    private final Path target;
    private final Compression compression;
    /** The startup class ranks and the HYBRID flag a repack applies, shared and read-only. */
    private final RepackOptions options;

    DependencyStage(Dependency dependency, Path source, String entryName, Path target, Compression compression,
                    RepackOptions options) {
        this.dependency = dependency;
        this.source = source;
        this.entryName = entryName;
        this.target = target;
        this.compression = compression;
        this.options = options;
    }

    /**
     * One stage per dependency, in class-path order. Each nested jar's entry name and work file are fixed here, in
     * class-path order, so neither depends on which dependency is staged first: the entry name is the dependency's
     * file name, made unique in the archive ignoring case.
     *
     * @param dependencies the dependencies that are nested, in class-path order
     * @param sources      what to nest for each dependency, in the same order: the dependency itself, or the copy
     *                     Micronaut AOT's class path transform wrote of it
     * @param work         the directory the repacked nested jars are built in
     * @param compression  how the stages nest their dependencies
     * @param options      the startup class ranks and HYBRID flag a repack applies
     * @return the stages, in class-path order
     */
    static List<DependencyStage> of(List<Dependency> dependencies, List<Path> sources, Path work,
                                    Compression compression, RepackOptions options) {
        List<DependencyStage> stages = new ArrayList<>(dependencies.size());
        Set<String> taken = new HashSet<>();
        for (int position = 0; position < dependencies.size(); position++) {
            Dependency dependency = dependencies.get(position);
            stages.add(new DependencyStage(dependency, sources.get(position),
                    IndexFormat.LIB_PREFIX + uniqueName(taken, dependency.fileName()),
                    work.resolve("lib-" + position + ".jar"), compression, options));
        }
        return stages;
    }

    /**
     * Runs every stage once, on the build's pool, largest dependency first, or on the calling thread one after the
     * other, and collects the results in class-path order.
     *
     * @param stages the stages, in class-path order
     * @param pool   the build's pool, or {@code null} to stage on the calling thread
     * @return what each stage produced, in class-path order
     * @throws IOException if a dependency cannot be staged: the failure of the first one on the class path
     */
    static List<Staged> stageAll(List<DependencyStage> stages, ExecutorService pool) throws IOException {
        List<Staged> staged = new ArrayList<>(stages.size());
        if (pool == null) {
            for (DependencyStage stage : stages) {
                staged.add(stage.call());
            }
            return staged;
        }
        List<Dependency> dependencies = new ArrayList<>(stages.size());
        for (DependencyStage stage : stages) {
            dependencies.add(stage.dependency);
        }
        List<Future<Staged>> futures = new ArrayList<>(Collections.nCopies(stages.size(), null));
        for (int position : RunnerJarBuilder.largestFirst(dependencies)) {
            futures.set(position, pool.submit(stages.get(position)));
        }
        for (Future<Staged> future : futures) {
            staged.add(RunnerJarBuilder.awaitStage(future));
        }
        return staged;
    }

    /**
     * How many hot entries the stages wrote, over every dependency.
     *
     * @param staged what the stages produced
     * @return the number of startup classes, and their versioned variants, the nested jars hold first
     */
    static long hotEntries(List<Staged> staged) {
        long hot = 0;
        for (Staged result : staged) {
            hot += result.hotEntries();
        }
        return hot;
    }

    private static String uniqueName(Set<String> taken, String fileName) {
        String candidate = fileName;
        int suffix = 1;
        while (!taken.add(candidate.toLowerCase(Locale.ROOT))) {
            int dot = fileName.lastIndexOf('.');
            String base = dot < 0 ? fileName : fileName.substring(0, dot);
            String extension = dot < 0 ? "" : fileName.substring(dot);
            candidate = base + "-" + suffix + extension;
            suffix++;
        }
        return candidate;
    }

    @Override
    public Staged call() throws IOException {
        Path file;
        long crc32;
        ZipRepacker.RepackResult result;
        Manifest manifest;
        boolean hasManifest;
        Classes classes;
        try (ZipReader reader = ZipReader.open(source)) {
            manifest = reader.manifest().orElse(null);
            hasManifest = reader.entry("META-INF/MANIFEST.MF").isPresent();
            Set<String> generated = Set.of();
            if (source.equals(dependency.path())) {
                classes = new Classes(Classes.count(reader.entries()), 0);
            } else {
                // The transform's copy: a class the dependency does not hold is one the transform generated.
                try (ZipReader original = ZipReader.open(dependency.path())) {
                    generated = generated(reader.entries(), original.entries());
                    classes = new Classes(Classes.count(original.entries()),
                            Classes.size(original.entries()) - Classes.size(reader.entries()));
                }
            }
            if (compression == Compression.PRESERVE) {
                // Nested as it is, so nothing is written: the reader has already resolved every offset
                // relative to the source's first byte, and the source is the nested jar.
                file = source;
                result = new ZipRepacker.RepackResult(reader.entries(), reader.fileLength(),
                        reader.hasSignatureFiles(), List.of());
                crc32 = checksum(file, reader.fileLength(), new byte[BUFFER_SIZE]);
            } else {
                file = target;
                CRC32 crc = new CRC32();
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), BUFFER_SIZE);
                     CheckedOutputStream checked = new CheckedOutputStream(out, crc)) {
                    result = ZipRepacker.repack(reader, checked, options, generated);
                }
                crc32 = crc.getValue();
            }
        } catch (IOException e) {
            // Without this the message names only the entry, and a build with dozens of dependencies
            // says nothing about which jar has to be looked at. It covers a file that starts like a ZIP
            // archive but whose central directory cannot be read, such as a truncated download, and a
            // manifest that fails its CRC-32, which the reader only finds when manifest() first parses it.
            throw new IOException("The dependency " + dependency.path() + " cannot be packaged: "
                    + e.getMessage(), e);
        }
        for (ZipEntryInfo entry : result.entries()) {
            RunnerJarBuilder.requireSafeName(entry.name(), dependency.path().toString());
        }
        return new Staged(
                new RunnerJarBuilder.NestedJar(dependency, entryName, file, result, manifest, hasManifest, crc32),
                classPathWarning(dependency, manifest),
                signatureWarning(dependency, compression, result),
                result.hotEntries(),
                classes);
    }

    /**
     * The class entries of the transform's copy that the dependency does not hold.
     */
    private static Set<String> generated(List<ZipEntryInfo> copy, List<ZipEntryInfo> original) {
        Set<String> own = new HashSet<>(original.size() * 2);
        for (ZipEntryInfo entry : original) {
            own.add(entry.name());
        }
        Set<String> generated = new HashSet<>();
        for (ZipEntryInfo entry : copy) {
            if (entry.isClass() && !own.contains(entry.name())) {
                generated.add(entry.name());
            }
        }
        return generated;
    }

    /**
     * Computes the CRC-32 of a dependency that is nested as it is, through the stage's own buffer and
     * {@link CRC32}, never the builder's: stages run on several threads at once.
     *
     * @param file   the dependency
     * @param length its length when its reader opened it
     * @param buffer the stage's buffer
     * @return the CRC-32 of its content
     * @throws IOException if it cannot be read, or its length is no longer {@code length}
     */
    private static long checksum(Path file, long length, byte[] buffer) throws IOException {
        CRC32 crc = new CRC32();
        long read = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int count = in.read(buffer);
            while (count > 0) {
                crc.update(buffer, 0, count);
                read += count;
                count = in.read(buffer);
            }
        }
        if (read != length) {
            throw new IOException("Read " + read + " of " + length + " bytes of " + file
                    + "; it changed while it was being packaged");
        }
        return crc.getValue();
    }

    /**
     * The warning for a dependency whose manifest declares {@code Class-Path}.
     *
     * @param dependency the dependency
     * @param manifest   its manifest, or {@code null} when it has none
     * @return the warning, or {@code null} when there is nothing to warn about
     */
    private static String classPathWarning(Dependency dependency, Manifest manifest) {
        if (manifest == null) {
            return null;
        }
        String classPath = RunnerJarBuilder.attribute(manifest.getMainAttributes(), Attributes.Name.CLASS_PATH);
        if (classPath == null) {
            return null;
        }
        return "The dependency " + dependency.path() + " declares Class-Path: " + classPath
                + ", which a nested jar cannot resolve. Add those jars to the class path instead";
    }

    /**
     * The warning for a dependency that carried signature files.
     *
     * @param dependency  the dependency
     * @param compression how it is nested
     * @param result      what staging it produced
     * @return the warning, or {@code null} when the dependency was not signed
     */
    private static String signatureWarning(Dependency dependency, Compression compression,
                                           ZipRepacker.RepackResult result) {
        if (!result.hadSignatureFiles()) {
            return null;
        }
        return "The dependency " + dependency.path() + " is signed; its signature files "
                + (compression != Compression.PRESERVE
                    ? "were removed because a repacked jar cannot verify against them"
                    : "were kept but no longer verify, because the jar is nested")
                + ". The classes it contains are not treated as signed code";
    }

    /**
     * What one dependency's stage hands back to the calling thread: the nested jar and the texts of the
     * warnings the calling thread emits for it.
     *
     * @param jar              the dependency, written as a nested jar
     * @param classPathWarning the {@code Class-Path} warning, or {@code null}
     * @param signatureWarning the signed-dependency warning, or {@code null}
     * @param hotEntries       how many startup classes, and versioned variants of them, its nested jar holds first
     * @param classes          the dependency's own class entries, which the report of {@code desugarLambdas} counts
     */
    record Staged(RunnerJarBuilder.NestedJar jar, String classPathWarning, String signatureWarning, int hotEntries,
                  Classes classes) {
    }

    /**
     * The class entries of a dependency, as the report of {@code desugarLambdas} counts them.
     *
     * @param count      how many class entries the dependency holds
     * @param bytesSaved how many bytes smaller the class entries of the transform's copy are, together, than the
     *                   dependency's: negative when they grew, {@code 0} without a copy
     */
    record Classes(int count, long bytesSaved) {

        private static int count(List<ZipEntryInfo> entries) {
            int count = 0;
            for (ZipEntryInfo entry : entries) {
                if (entry.isClass()) {
                    count++;
                }
            }
            return count;
        }

        private static long size(List<ZipEntryInfo> entries) {
            long size = 0;
            for (ZipEntryInfo entry : entries) {
                if (entry.isClass()) {
                    size += entry.uncompressedSize();
                }
            }
            return size;
        }
    }
}
