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
 * thread that runs it, and in HYBRID the one {@link java.util.zip.Deflater} its repack creates and ends; only
 * the {@link Staged} result it returns reaches another thread. When lambdas are desugared, the repack plans the
 * dependency's nests before it writes the first entry, and the stage holds their accepted classes until the
 * entry loop has written them.</p>
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
    /** The dependency's layer in the class path model: its class-path position plus one. */
    private final int layer;
    private final String entryName;
    /** The work file a repacked nested jar is written to; a preserved dependency leaves it unused. */
    private final Path target;
    private final Compression compression;
    /**
     * The class transforms a repack runs, with the startup class ranks and the HYBRID flag it applies, shared and
     * read-only; {@code null} when there is nothing to transform, order or compress.
     */
    private final ClassTransformPipeline pipeline;

    DependencyStage(Dependency dependency, int layer, String entryName, Path target, Compression compression,
                    ClassTransformPipeline pipeline) {
        this.dependency = dependency;
        this.layer = layer;
        this.entryName = entryName;
        this.target = target;
        this.compression = compression;
        this.pipeline = pipeline;
    }

    /**
     * One stage per dependency, in class-path order. Each nested jar's entry name and work file are fixed here, in
     * class-path order, so neither depends on which dependency is staged first: the entry name is the dependency's
     * file name, made unique in the archive ignoring case.
     *
     * @param dependencies the dependencies that are nested, in class-path order
     * @param work         the directory the repacked nested jars are built in
     * @param compression  how the stages nest their dependencies
     * @param pipeline     the class transforms, startup class ranks and HYBRID flag a repack applies, or {@code null}
     * @return the stages, in class-path order
     */
    static List<DependencyStage> of(List<Dependency> dependencies, Path work, Compression compression,
                                    ClassTransformPipeline pipeline) {
        List<DependencyStage> stages = new ArrayList<>(dependencies.size());
        Set<String> taken = new HashSet<>();
        for (int position = 0; position < dependencies.size(); position++) {
            Dependency dependency = dependencies.get(position);
            stages.add(new DependencyStage(dependency, position + 1,
                    IndexFormat.LIB_PREFIX + uniqueName(taken, dependency.fileName()),
                    work.resolve("lib-" + position + ".jar"), compression, pipeline));
        }
        return stages;
    }

    /**
     * Runs every stage once, on the build's pool, largest dependency first, or on the calling thread one after the
     * other, and collects the results in class-path order. The calling thread runs {@code alongside} first: while
     * the pool stages, or before the first stage without a pool.
     *
     * @param stages    the stages, in class-path order
     * @param pool      the build's pool, or {@code null} to stage on the calling thread
     * @param alongside what the calling thread does meanwhile
     * @return what each stage produced, in class-path order
     * @throws IOException if a dependency cannot be staged: the failure of the first one on the class path; or what
     *                     {@code alongside} threw
     */
    static List<Staged> stageAll(List<DependencyStage> stages, ExecutorService pool, Alongside alongside)
            throws IOException {
        List<Staged> staged = new ArrayList<>(stages.size());
        if (pool == null) {
            alongside.run();
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
        alongside.run();
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
        ClassTransformPipeline.JarReport transforms = null;
        try (ZipReader reader = ZipReader.open(dependency.path())) {
            manifest = reader.manifest().orElse(null);
            hasManifest = reader.entry("META-INF/MANIFEST.MF").isPresent();
            if (compression == Compression.PRESERVE) {
                // Nested as it is, so nothing is written: the reader has already resolved every offset
                // relative to the dependency's first byte, and the dependency is the nested jar.
                file = dependency.path();
                result = new ZipRepacker.RepackResult(reader.entries(), reader.fileLength(),
                        reader.hasSignatureFiles(), List.of());
                crc32 = checksum(file, reader.fileLength(), new byte[BUFFER_SIZE]);
            } else {
                file = target;
                CRC32 crc = new CRC32();
                // The run is decided before any entry is read: a signed jar keeps every class as it is.
                ClassTransformPipeline.JarRun run = pipeline == null ? null
                        : pipeline.start(new ClassTransformPipeline.Layer(entryName, layer, false,
                                reader.hasSignatureFiles(), dependency.projectModule()));
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), BUFFER_SIZE);
                     CheckedOutputStream checked = new CheckedOutputStream(out, crc)) {
                    result = ZipRepacker.repack(reader, checked, run);
                }
                crc32 = crc.getValue();
                transforms = run == null ? null : run.report();
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
                transforms,
                result.hotEntries());
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
     * @param transforms       what the class transform pipeline did to it, or {@code null} when none ran
     * @param hotEntries       how many startup classes, and versioned variants of them, its nested jar holds first
     */
    record Staged(RunnerJarBuilder.NestedJar jar, String classPathWarning, String signatureWarning,
                  ClassTransformPipeline.JarReport transforms, int hotEntries) {
    }

    /** What the calling thread does while the stages run. */
    @FunctionalInterface
    interface Alongside {

        /**
         * Does it.
         *
         * @throws IOException if it fails
         */
        void run() throws IOException;
    }
}
