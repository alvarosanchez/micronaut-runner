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

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
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
 * thread that runs it; only the {@link Staged} result it returns reaches another thread.</p>
 */
final class DependencyStage implements Callable<DependencyStage.Staged> {

    /** The size of the nested jar's output buffer and of the buffer a preserved dependency is checksummed with. */
    private static final int BUFFER_SIZE = 64 * 1024;

    private final Dependency dependency;
    private final String entryName;
    /** The work file a repacked nested jar is written to; a preserved dependency leaves it unused. */
    private final Path target;
    private final Compression compression;
    /** The class transforms a repack runs, shared and read-only; {@code null} when none runs. */
    private final ClassTransformPipeline pipeline;

    DependencyStage(Dependency dependency, String entryName, Path target, Compression compression,
                    ClassTransformPipeline pipeline) {
        this.dependency = dependency;
        this.entryName = entryName;
        this.target = target;
        this.compression = compression;
        this.pipeline = pipeline;
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
                        : pipeline.start(new ClassTransformPipeline.Layer(entryName, false,
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
                transforms);
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
                + (compression == Compression.STORED
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
     */
    record Staged(RunnerJarBuilder.NestedJar jar, String classPathWarning, String signatureWarning,
                  ClassTransformPipeline.JarReport transforms) {
    }
}
