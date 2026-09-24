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

import io.micronaut.runner.Index;
import io.micronaut.runner.IndexFormat;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

/**
 * Turns an application's own output and its resolved dependencies into a runner jar.
 *
 * <p>The archive it writes is an ordinary ZIP file whose every entry is stored uncompressed, laid out in
 * the order {@code IndexFormat} documents: the manifest, the index, the launcher's classes, the merged
 * Micronaut service directory, the application layer exploded under {@link IndexFormat#CLASSES_PREFIX} and
 * finally the dependencies, each an intact nested jar under {@link IndexFormat#LIB_PREFIX}.</p>
 *
 * <h2>Two passes, one archive</h2>
 * <p>The index stores the absolute offset of every entry's data, and the index is itself the second entry
 * of the archive, so its own length shifts everything it describes. The way out is that an index's length
 * depends only on names and counts, never on offsets: {@code IndexWriter.layout()} reports it before
 * anything is written. The builder therefore lays the index out, runs the whole archive through a
 * {@code ZipWriter} that discards its output to learn where every entry will land, fills those offsets into
 * the index and only then writes the file for real, checking as it goes that every entry landed exactly
 * where the dry run said it would.</p>
 *
 * <p>The result is reproducible: entries are visited in a fixed order, every one of them is dated with
 * {@link RunnerJarSpec#timestamp()} converted in UTC, and nothing about the machine that ran the build
 * reaches the bytes. Building the same inputs twice, in different time zones, produces identical files.</p>
 *
 * <h2>Dependency staging</h2>
 * <p>Each dependency is staged on up to {@code min(availableProcessors(), 8)} daemon threads. In STORED its
 * stage repacks it into a nested jar in the work directory. In PRESERVE the dependency is nested as it is:
 * its stage writes nothing and only checksums the file, and the archive is written from the dependency
 * itself, through a read that fails the build if the file no longer matches that checksum. The threads are
 * created for each build and have stopped before {@code build} returns; with one processor, or at most one
 * dependency, staging runs on the calling thread. A staging thread holds one open {@code ZipReader}: its
 * parsed central directory and manifest and, outside the heap, a read-only mapping of the dependency, which
 * is released when the stage closes the reader. It also holds at most one {@code Inflater} at a time and at
 * most three 64 KiB buffers: in STORED, the nested jar's output buffer and the reader's two transfer buffers.
 * In PRESERVE the reader reads the manifest at its exact size and never allocates its transfer buffers, so
 * the stage holds one: the buffer it checksums the dependency through. The archive's bytes do not depend on
 * the thread count: every nested jar's name and work file are fixed in class-path order before staging
 * starts, warnings are emitted in that order afterwards, and the outer archive is written on one thread.</p>
 *
 * <h2>Application jars</h2>
 * <p>An application output that is a jar is opened once, on the calling thread, and stays open until the
 * archive has been written. Its entries are streamed from it into the archive, each inflated and checked
 * against its size, CRC-32 and DEFLATE framing as it is written, so none of them is held in memory or copied
 * to a work file; only the main class and the application's own manifest, which the build parses, are read
 * into memory. The calling thread closes every such reader as soon as the archive has been written, before
 * it is verified, or when the build fails, before the work directory is deleted.</p>
 *
 * <h2>Dependency files</h2>
 * <p>A dependency is nested when it is a ZIP archive, which is when its first four bytes are a local file
 * header ({@code PK\3\4}) or, for an empty archive, an end of central directory record ({@code PK\5\6}). A
 * directory fails the build before anything is written, because a runner jar nests only JAR files. Any other
 * file, such as a POM, is skipped with a warning: it contributes nothing to a {@code java -cp} class path
 * either. A dependency that does not exist fails the build.</p>
 *
 * <h2>Path safety</h2>
 * <p>Configured input paths may themselves be symbolic links. The builder resolves their real identities,
 * and resolves a not-yet-created output through its nearest existing ancestor, before comparing them. It
 * rejects an output that aliases an application jar, dependency or manifest source, or whose directory is
 * canonically at or below an application directory. Symbolic links found inside an application directory
 * are followed, as they are on a class path, and packaged under the link's own name. Three kinds are
 * rejected: a link that does not resolve, a directory link that leads back into a directory the walk is
 * already inside (a cycle), and a directory link to the directory that holds the output and the work
 * directory, or to one of its ancestors.</p>
 *
 * <p>These checks guard against accidental aliases, not against concurrent replacement of path
 * components.</p>
 *
 * @since 1.0
 */
public final class RunnerJarBuilder {

    /** Set to {@code "true"} to checksum every entry of the finished archive instead of a sample. */
    static final String VERIFY_ALL_PROPERTY = "micronaut.runner.build.verifyAll";

    /** The name of every dependency staging thread, followed by its number, starting at 1. */
    static final String STAGE_THREAD_PREFIX = "micronaut-runner-stage-";

    /** The bundled launcher, put on this library's own class path by its build. */
    private static final String LAUNCHER_RESOURCE = "/META-INF/micronaut-runner/launcher.jar";

    /** The only entries copied out of the bundled launcher jar. */
    private static final String LAUNCHER_PREFIX = "io/micronaut/runner/";

    /** How many entries the verification pass checksums when it is not checking all of them. */
    private static final int VERIFY_SAMPLE_SIZE = 64;

    /** Buffer size for the streaming copies. */
    private static final int BUFFER_SIZE = 64 * 1024;

    /** Classes and manifests are the only application entries intentionally materialised. */
    private static final int MAX_IN_MEMORY_METADATA_SIZE = 16 * 1024 * 1024;

    /** The content of every zero-length entry, which is written without opening anything. */
    private static final byte[] EMPTY_BYTES = new byte[0];

    /** The content every zero-length dependency contributor to the merged Micronaut metadata shares. */
    private static final ApplicationEntry EMPTY_CONTENT = ApplicationEntry.ofBytes(EMPTY_BYTES, 0);

    /** The most threads that stage dependencies; beyond it the largest jar is the critical path. */
    private static final int MAX_STAGE_THREADS = 8;

    /** How long a build waits for its staging threads to stop once it has interrupted them. */
    private static final Duration STAGE_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    private final RunnerJarSpec spec;
    private final BuildLogger logger;
    private final int parallelism;
    /** Runs just before the real archive is written, after the dry pass has fixed the index. */
    private final Runnable beforeWrite;
    private final List<String> warnings = new ArrayList<>();
    /**
     * The reader of every application output that is a jar, open from collection until the archive has been
     * written. They belong to the calling thread, which opens, reads and closes them; a stage never sees one.
     */
    private final List<ZipReader> applicationReaders = new ArrayList<>();
    /** The dependencies that are nested: the spec's, less the files that are not ZIP archives. */
    private final List<Dependency> dependencies = new ArrayList<>();
    private final List<PlannedEntry> plan = new ArrayList<>();
    private final List<NestedJar> nested = new ArrayList<>();
    private final Map<String, ApplicationEntry> application = new LinkedHashMap<>();
    private final Set<String> serviceNames = new TreeSet<>();
    private final IndexWriter writer = new IndexWriter();
    /**
     * The buffer {@link #crc32(Path)} reads every application file through. It belongs to the calling
     * thread, which collects the application directories before any dependency is staged; a stage never
     * calls {@code crc32(Path)} and checksums with its own {@link CRC32} and buffers.
     */
    private final byte[] fileBuffer = new byte[BUFFER_SIZE];
    private final Path output;
    private final int dosTime;
    /** The real path of the directory that holds the output and the work directory, set by validation. */
    private Path outputDirectory;
    /**
     * The real path of each application output that is a directory, parallel to
     * {@link RunnerJarSpec#applicationOutput()}; {@code null} where the output is a jar. Set by validation.
     */
    private Path[] applicationDirectories;
    private IndexWriter.JarSpec applicationJar;
    private PlannedEntry indexEntry;
    private Manifest applicationManifest;
    private String launcherVersion;
    private String entryStubClass;
    private int mergedServiceEntryCount;

    private RunnerJarBuilder(RunnerJarSpec spec, BuildLogger logger, int parallelism, Runnable beforeWrite) {
        this.spec = spec;
        this.logger = logger;
        this.parallelism = parallelism;
        this.beforeWrite = beforeWrite;
        this.output = spec.output().toAbsolutePath().normalize();
        this.dosTime = ZipWriter.toDosTime(spec.timestamp());
    }

    /**
     * Packages an application.
     *
     * @param spec   what to package and how
     * @param logger where to report progress and anything that looked wrong
     * @return the counts and warnings of the build
     * @throws IOException          if an input cannot be read, the output cannot be written, or the archive
     *                              that was written does not describe itself correctly
     * @throws NullPointerException if an argument is {@code null}
     */
    public static RunnerJarResult build(RunnerJarSpec spec, BuildLogger logger) throws IOException {
        return build(spec, logger, Math.min(Runtime.getRuntime().availableProcessors(), MAX_STAGE_THREADS));
    }

    /**
     * Packages an application, staging its dependencies on at most {@code parallelism} threads.
     *
     * @param spec        what to package and how
     * @param logger      where to report progress and anything that looked wrong
     * @param parallelism the most dependencies staged at once; {@code 1} stages them on the calling thread
     * @return the counts and warnings of the build
     * @throws IOException              if an input cannot be read, the output cannot be written, or the
     *                                  archive that was written does not describe itself correctly
     * @throws NullPointerException     if {@code spec} or {@code logger} is {@code null}
     * @throws IllegalArgumentException if {@code parallelism} is below {@code 1}
     */
    static RunnerJarResult build(RunnerJarSpec spec, BuildLogger logger, int parallelism) throws IOException {
        return build(spec, logger, parallelism, () -> { });
    }

    /**
     * Packages an application, running a hook between the pass that lays the archive out and the pass that
     * writes it. Tests use the hook to change an input after it was collected.
     *
     * @param spec        what to package and how
     * @param logger      where to report progress and anything that looked wrong
     * @param parallelism the most dependencies staged at once; {@code 1} stages them on the calling thread
     * @param beforeWrite what to run, on the calling thread, just before the archive is written for real
     * @return the counts and warnings of the build
     * @throws IOException              if an input cannot be read, the output cannot be written, or the
     *                                  archive that was written does not describe itself correctly
     * @throws NullPointerException     if {@code spec}, {@code logger} or {@code beforeWrite} is {@code null}
     * @throws IllegalArgumentException if {@code parallelism} is below {@code 1}
     */
    static RunnerJarResult build(RunnerJarSpec spec, BuildLogger logger, int parallelism, Runnable beforeWrite)
            throws IOException {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(beforeWrite, "beforeWrite");
        if (parallelism < 1) {
            throw new IllegalArgumentException("The staging parallelism must be at least 1: " + parallelism);
        }
        return new RunnerJarBuilder(spec, logger, parallelism, beforeWrite).run();
    }

    private static long crc32(byte[] content) {
        CRC32 crc = new CRC32();
        crc.update(content, 0, content.length);
        return crc.getValue();
    }

    private static String attribute(Attributes attributes, Attributes.Name name) {
        if (attributes == null) {
            return null;
        }
        String value = attributes.getValue(name);
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * The value of an attribute that describes a jar or one of its packages.
     *
     * <p>Unlike {@link #attribute(Attributes, Attributes.Name)}, an attribute that is present but empty
     * keeps its empty value instead of collapsing to {@code null}. {@code URLClassLoader} reports such an
     * attribute as {@code ""} on the {@link Package} it defines, and code that null-checks an attribute to
     * decide whether a jar declared it at all has to get the same answer inside a runner jar.</p>
     *
     * @param attributes the attribute set, or {@code null} when there is none
     * @param name       the attribute name
     * @return the value, or {@code null} only when the attribute is absent
     */
    private static String describedAttribute(Attributes attributes, Attributes.Name name) {
        return attributes == null ? null : attributes.getValue(name);
    }

    private static boolean isTrue(Attributes attributes, String name) {
        return attributes != null && "true".equalsIgnoreCase(attributes.getValue(name));
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (Files.isDirectory(child)) {
                    deleteRecursively(child);
                } else {
                    Files.deleteIfExists(child);
                }
            }
        } catch (IOException e) {
            return;
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException e) {
            // A leftover empty directory is not worth failing a build that otherwise succeeded.
            return;
        }
    }

    private RunnerJarResult run() throws IOException {
        logger.info(spec.effectiveOptions().entrySet().stream()
                .map(option -> option.getKey() + "=" + option.getValue())
                .collect(Collectors.joining(", ", "Runner options: ", "")));
        validate();
        Path directory = output.getParent();
        Files.createDirectories(directory);
        Path work = Files.createTempDirectory(directory, ".micronaut-runner-");
        Throwable failure = null;
        try {
            collectApplication();
            requireMainClass();
            generateEntryStub();
            readApplicationManifest();
            collectDependencies(work);
            loadLauncher(work);

            plan.add(PlannedEntry.ofBytes("META-INF/MANIFEST.MF", manifestBytes()));
            indexEntry = PlannedEntry.placeholder(IndexFormat.INDEX_ENTRY_NAME);
            plan.add(indexEntry);
            planLauncherClasses(work);
            describeApplicationJar();
            planMergedServices();
            planApplicationEntries();
            planNestedJars();

            IndexWriter.Layout layout = writer.layout();
            indexEntry.size = layout.length();
            long archiveSize = writeArchive(null);
            applyOffsets(archiveSize);
            indexEntry.bytes = writer.write(layout, archiveSize);
            if (indexEntry.bytes.length != indexEntry.size) {
                throw new IOException("The index changed length between the two passes: expected "
                        + indexEntry.size + " bytes, wrote " + indexEntry.bytes.length);
            }
            Path archive = work.resolve("runner.jar");
            beforeWrite.run();
            long written = writeArchive(archive);
            // Before verification, so a reader that cannot be closed fails the build before anything is moved.
            closeApplicationReaders();
            if (written != archiveSize) {
                throw new IOException("The archive changed length between the two passes: expected "
                        + archiveSize + " bytes, wrote " + written);
            }
            verify(archive, layout);
            move(archive);

            // The caller reports the build with the result's summary(), so the builder logs no line of its own.
            return new RunnerJarResult(output, writer.jars().size(), layout.entryCount(),
                    application.size(), mergedServiceEntryCount, archiveSize, warnings, spec.effectiveOptions());
        } catch (Throwable e) {
            failure = e;
            throw e;
        } finally {
            try {
                // A no-op after a successful write; after a failure, the readers are still open.
                closeApplicationReaders();
            } catch (IOException e) {
                if (failure == null) {
                    throw e;
                }
                failure.addSuppressed(e);
            } finally {
                deleteRecursively(work);
            }
        }
    }

    /**
     * Closes the reader of every application jar, on the calling thread that opened it, and forgets it, so
     * that calling this again does nothing. A reader left open would keep its jar mapped for as long as the
     * JVM lives, a Gradle daemon's included, and on Windows the jar could then be neither replaced nor
     * deleted.
     *
     * @throws IOException if a reader cannot be closed: the first failure, with the others suppressed
     */
    private void closeApplicationReaders() throws IOException {
        IOException failure = null;
        for (ZipReader reader : applicationReaders) {
            try {
                reader.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        applicationReaders.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private void warn(String message) {
        warnings.add(message);
        logger.warn(message);
    }

    /**
     * Checks everything that can be checked before any work is done: that the inputs exist, which
     * dependencies are nested, that the output is not one of the inputs, and that neither the output nor the
     * directory that will hold it sits inside an application directory. It runs once per build, before the
     * work directory is created, and keeps the real paths the application walk compares against and the
     * dependencies that are nested.
     *
     * @throws IOException if an input is missing, a dependency is a directory, or the output would destroy an
     *                     input
     */
    private void validate() throws IOException {
        List<Path> applicationOutput = spec.applicationOutput();
        for (Path input : applicationOutput) {
            if (!Files.exists(input)) {
                throw new IOException("The application output " + input + " does not exist");
            }
        }
        for (Dependency dependency : spec.dependencies()) {
            if (isNestable(dependency.path())) {
                dependencies.add(dependency);
            }
        }
        Optional<Path> manifestSource = spec.applicationManifest().isPresent()
                ? Optional.empty()
                : spec.applicationManifestSource();
        Path directory = output.getParent();
        if (directory == null) {
            throw new IOException("The output " + output + " has no parent directory");
        }
        // The output's own directory, not the directory of what an existing output links to: the work
        // directory and the published file both go here, and Files.move replaces a link, not its target.
        outputDirectory = resolveExistingAncestor(directory);
        Path resolvedOutput = resolveExistingAncestor(output);
        boolean outputExists = Files.exists(output);
        applicationDirectories = new Path[applicationOutput.size()];
        for (int i = 0; i < applicationOutput.size(); i++) {
            Path input = applicationOutput.get(i);
            Path resolvedInput = input.toRealPath();
            boolean collision;
            if (Files.isDirectory(resolvedInput)) {
                applicationDirectories[i] = resolvedInput;
                collision = resolvedOutput.startsWith(resolvedInput) || outputDirectory.startsWith(resolvedInput);
            } else {
                collision = isOutput(input, resolvedInput, resolvedOutput, outputExists);
            }
            if (collision) {
                throw new IOException("The output " + output + " is inside the application output "
                        + resolvedInput + "; packaging it would read what it is writing");
            }
        }
        for (Dependency dependency : dependencies) {
            Path dependencyPath = dependency.path();
            if (isOutput(dependencyPath, dependencyPath.toRealPath(), resolvedOutput, outputExists)) {
                throw new IOException("The output " + output + " is also a dependency of the application");
            }
        }
        if (manifestSource.isPresent()) {
            Path manifest = manifestSource.get();
            if (Files.isRegularFile(manifest)
                    && isOutput(manifest, manifest.toRealPath(), resolvedOutput, outputExists)) {
                throw new IOException("The output " + output + " is also the application manifest source");
            }
        }
    }

    /**
     * Whether a file input is the output: the same real path, or, when the output already exists, the same
     * file under another name, such as a hard link or a case alias.
     */
    private boolean isOutput(Path input, Path resolvedInput, Path resolvedOutput, boolean outputExists)
            throws IOException {
        return resolvedOutput.equals(resolvedInput) || outputExists && Files.isSameFile(output, input);
    }

    /**
     * Applies the dependency file rule: a ZIP archive is nested, a directory fails the build, and any other
     * file is skipped with one warning. Build tools resolve the same class path to different kinds of files
     * (Maven resolves a reactor module that was not packaged to its classes directory, and a {@code pom}
     * dependency to its POM), so the rule lives here rather than in each plugin.
     *
     * @param dependency the dependency's path
     * @return whether the dependency is nested
     * @throws IOException if the dependency does not exist or is a directory, or its first bytes cannot be
     *                     read
     */
    private boolean isNestable(Path dependency) throws IOException {
        if (!Files.exists(dependency)) {
            throw new IOException("The dependency " + dependency + " does not exist");
        }
        if (Files.isDirectory(dependency)) {
            throw new IOException("The dependency " + dependency + " is a directory, and Micronaut Runner nests"
                    + " only JAR files. Package it as a JAR first (for a reactor module, run the reactor through"
                    + " the package phase), or add it to the application output instead");
        }
        if (Files.isRegularFile(dependency) && isZip(dependency)) {
            return true;
        }
        warn("The dependency " + dependency + " is not a JAR file and was skipped; it contributes nothing to"
                + " a class path");
        return false;
    }

    /**
     * Whether a file starts the way every ZIP archive does: with a local file header, or with the end of
     * central directory record of an archive that has no entries.
     */
    private static boolean isZip(Path file) throws IOException {
        byte[] signature = new byte[4];
        try (InputStream in = Files.newInputStream(file)) {
            if (in.readNBytes(signature, 0, signature.length) < signature.length) {
                return false;
            }
        }
        return signature[0] == 'P' && signature[1] == 'K'
                && (signature[2] == 3 && signature[3] == 4 || signature[2] == 5 && signature[3] == 6);
    }

    private static Path resolveExistingAncestor(Path path) throws IOException {
        Path existing = path.toAbsolutePath().normalize();
        List<Path> suffix = new ArrayList<>();
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name == null || existing.getParent() == null) {
                throw new IOException("No existing ancestor of " + path);
            }
            suffix.add(name);
            existing = existing.getParent();
        }
        Path resolved = existing.toRealPath();
        for (int i = suffix.size() - 1; i >= 0; i--) {
            resolved = resolved.resolve(suffix.get(i));
        }
        return resolved.normalize();
    }

    /**
     * Walks the application output in order, turning it into the logical names of jar {@code 0}. The first
     * input that carries a name wins, exactly as it would on a class path.
     *
     * @throws IOException if an input cannot be read or carries an entry name the format cannot store
     */
    private void collectApplication() throws IOException {
        List<Path> applicationOutput = spec.applicationOutput();
        for (int i = 0; i < applicationOutput.size(); i++) {
            Path input = applicationOutput.get(i);
            Path realDirectory = applicationDirectories[i];
            if (realDirectory != null) {
                Set<Path> walking = new HashSet<>();
                walking.add(realDirectory);
                collectDirectory(input, input, realDirectory, walking);
            } else {
                collectApplicationJar(input);
            }
        }
    }

    /**
     * Adds the files below one directory of an application directory, following symbolic links as a class
     * path does: an entry reached through a link keeps the link's own name.
     *
     * @param root      the application directory, which entry names are relative to
     * @param directory the directory to list, named through any links that led to it
     * @param real      the real path of {@code directory}
     * @param walking   the real paths of {@code directory} and of every directory above it up to {@code root}
     * @throws IOException if an entry cannot be read, a link does not resolve, leads into a directory the walk
     *                     is already inside, or reaches the directory that holds the output
     */
    private void collectDirectory(Path root, Path directory, Path real, Set<Path> walking) throws IOException {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                children.add(child);
            }
        }
        // Sorted, so that the archive does not depend on the order the file system happens to report.
        children.sort(Comparator.comparing(RunnerJarBuilder::fileName));
        for (Path child : children) {
            BasicFileAttributes attributes =
                    Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean link = attributes.isSymbolicLink();
            if (link) {
                attributes = followLink(root, child);
            }
            if (attributes.isDirectory()) {
                // A plain subdirectory's real path follows from its parent's without a system call.
                Path childReal = link ? linkedDirectory(root, child) : real.resolve(child.getFileName());
                if (!walking.add(childReal)) {
                    throw new IOException("The application output " + root + " reaches " + child
                            + ", which leads back to " + childReal
                            + "; symbolic-link cycles are not supported");
                }
                collectDirectory(root, child, childReal, walking);
                walking.remove(childReal);
            } else if (attributes.isRegularFile()) {
                String name = root.relativize(child).toString().replace(File.separatorChar, '/');
                requireSafeName(name, root.toString());
                addApplicationEntry(name, ApplicationEntry.ofFile(child, attributes.size(), crc32(child)),
                        root);
            }
        }
    }

    /**
     * Checksums one application file through {@link #fileBuffer}, so the whole walk shares one buffer.
     * Only the calling thread may call it.
     */
    private long crc32(Path file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = fileBuffer;
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.read(buffer);
            while (read > 0) {
                crc.update(buffer, 0, read);
                read = in.read(buffer);
            }
        }
        return crc.getValue();
    }

    /**
     * Resolves a symbolic link to a directory inside an application directory, refusing one that leads to
     * the directory that holds the output or to one of its ancestors. Validation has already refused an
     * output directory below an application directory, so every other directory the walk enters is below
     * the root or below a link checked here.
     *
     * @throws IOException if the link reaches the directory where the work directory and the output go
     */
    private Path linkedDirectory(Path root, Path link) throws IOException {
        Path real = link.toRealPath();
        if (outputDirectory.startsWith(real)) {
            throw new IOException("The application output " + root + " contains the symbolic link " + link
                    + " to " + real + ", which is or contains the directory of the output " + output
                    + "; packaging it would read what it is writing");
        }
        return real;
    }

    /**
     * Reads the attributes of what a symbolic link inside an application directory leads to.
     *
     * @throws IOException if the link does not resolve, including a link that leads back to itself
     */
    private static BasicFileAttributes followLink(Path root, Path link) throws IOException {
        try {
            return Files.readAttributes(link, BasicFileAttributes.class);
        } catch (IOException e) {
            throw new IOException("The application output " + root + " contains the symbolic link " + link
                    + " to " + Files.readSymbolicLink(link) + ", which does not resolve"
                    + "; maven-resources-plugin 3.3.1 copies symbolic links into target/classes verbatim, so a"
                    + " relative link that works from src/main/resources can dangle there. Use an absolute"
                    + " link, a copy, or maven-resources-plugin 3.4.0 or later", e);
        }
    }

    /**
     * Adds the entries of an application jar without reading any of their content. The jar's reader stays
     * open, in {@link #applicationReaders}, until the archive has been written: each entry is then streamed
     * from it, and verified, exactly once.
     *
     * @param jar the application jar
     * @throws IOException if the jar cannot be read or carries an entry name the format cannot store
     */
    private void collectApplicationJar(Path jar) throws IOException {
        ZipReader reader = ZipReader.open(jar);
        // Registered at once, so that a failure later in the build still closes it.
        applicationReaders.add(reader);
        for (ZipEntryInfo entry : reader.entries()) {
            String name = entry.name();
            if (entry.directory()) {
                continue;
            }
            if (ZipReader.isSignatureFile(name) || ZipReader.isIndexList(name)) {
                continue;
            }
            requireSafeName(name, jar.toString());
            addApplicationEntry(name, ApplicationEntry.ofZip(reader, entry), jar);
        }
    }

    private void addApplicationEntry(String name, ApplicationEntry entry, Path origin) {
        ApplicationEntry existing = application.putIfAbsent(name, entry);
        if (existing != null) {
            warn("Ignoring the duplicate application entry '" + name + "' from " + origin
                    + "; the first application output that carried it wins");
        }
    }

    private void requireMainClass() throws IOException {
        String name = mainClassEntryName();
        if (!application.containsKey(name)) {
            throw new IOException("The main class " + spec.mainClass() + " is not in the application output;"
                    + " expected to find '" + name + "' in " + spec.applicationOutput());
        }
    }

    private String mainClassEntryName() {
        return spec.mainClass().replace('.', '/') + ".class";
    }

    /**
     * Generates the entry stub (specification section 4.3.3) into the application layer, when the
     * application can be entered through one.
     *
     * <p>The stub is what lets the launcher start the application with an interface call instead of
     * reflection, so it is worth having; but a stub that does not fit the application fails when the
     * application starts, whereas not generating one costs nothing but the reflective fallback the launcher
     * implements anyway. Every doubt therefore resolves to "no stub", reported at an informational level:
     * the flag turned off, a main class this packager cannot prove is callable, or - impossible short of an
     * application that generates classes into the runner's own package - the name already being taken.</p>
     *
     * <p>The base main class and every versioned variant the runner can select are <em>parsed</em>, never
     * loaded: a packager that loaded application classes would run their static initialisers in the build
     * JVM. A versioned directory matters only when the application layer is declared multi-release, and it
     * is recognised with the same rules the index writer uses to create aliases.</p>
     *
     * @throws IOException if the main class cannot be read back from the application output
     */
    private void generateEntryStub() throws IOException {
        if (!spec.entryStub()) {
            logger.info("No entry stub was generated because it was not requested; the launcher will start "
                    + spec.mainClass() + " reflectively");
            return;
        }
        String reason = entryStubIneligibilityReason();
        if (reason != null) {
            logger.info("No entry stub was generated because " + reason + "; the launcher will start "
                    + spec.mainClass() + " reflectively");
            return;
        }
        ApplicationEntry taken = application.putIfAbsent(EntryStubGenerator.STUB_RESOURCE_NAME,
                ApplicationEntry.ofBytes(EntryStubGenerator.generate(spec.mainClass())));
        if (taken != null) {
            warn("The application output already carries '" + EntryStubGenerator.STUB_RESOURCE_NAME
                    + "'; no entry stub was generated and the launcher will start " + spec.mainClass()
                    + " reflectively");
            return;
        }
        entryStubClass = EntryStubGenerator.STUB_CLASS;
        logger.info("Generated the entry stub " + EntryStubGenerator.STUB_CLASS + ", which enters "
                + spec.mainClass() + " without reflection");
    }

    private String entryStubIneligibilityReason() throws IOException {
        String mainName = mainClassEntryName();
        String reason = EntryStubGenerator.ineligibilityReason(spec.mainClass(),
                applicationBytes(application.get(mainName)));
        if (reason != null || !spec.multiRelease()) {
            return reason;
        }
        for (Map.Entry<String, ApplicationEntry> candidate : application.entrySet()) {
            String name = candidate.getKey();
            if (IndexWriter.versionOf(name) == 0 || !mainName.equals(IndexWriter.pathOf(name))) {
                continue;
            }
            reason = EntryStubGenerator.ineligibilityReason(spec.mainClass(),
                    applicationBytes(candidate.getValue()));
            if (reason != null) {
                return "the multi-release variant '" + name + "' is not directly callable: " + reason;
            }
        }
        return null;
    }

    private static byte[] applicationBytes(ApplicationEntry entry) throws IOException {
        if (entry.size > MAX_IN_MEMORY_METADATA_SIZE) {
            throw new IOException("Application metadata entry is " + entry.size + " bytes; the in-memory limit is "
                    + MAX_IN_MEMORY_METADATA_SIZE);
        }
        if (entry.bytes != null) {
            return entry.bytes;
        }
        if (entry.reader != null) {
            // Inflated at its exact size and checked against its CRC-32.
            return entry.reader.read(entry.zipEntry);
        }
        byte[] content = new byte[(int) entry.size];
        int offset = 0;
        try (InputStream input = entry.open()) {
            while (offset < content.length) {
                int read = input.read(content, offset, content.length - offset);
                if (read < 0) {
                    throw new IOException("Application metadata ended after " + offset + " of "
                            + content.length + " bytes");
                }
                offset += read;
            }
        }
        return content;
    }

    /**
     * Resolves the manifest that describes the application itself, which a directory of class files cannot
     * carry. It supplies the {@code Implementation-*} attributes of the runner jar's own manifest and the
     * jar {@code 0} record of the index, per-package sections included.
     *
     * @throws IOException if the configured manifest source cannot be read
     */
    private void readApplicationManifest() throws IOException {
        Optional<Manifest> configured = spec.applicationManifest();
        if (configured.isPresent()) {
            applicationManifest = configured.get();
            return;
        }
        Optional<Path> source = spec.applicationManifestSource();
        if (source.isPresent()) {
            Path path = source.get();
            if (!Files.isRegularFile(path)) {
                warn("The application manifest source " + path + " does not exist; the runner jar will carry"
                        + " no application attributes");
                return;
            }
            String name = fileName(path).toLowerCase(Locale.ROOT);
            if (name.endsWith(".jar") || name.endsWith(".zip")) {
                try (ZipReader reader = ZipReader.open(path)) {
                    applicationManifest = reader.manifest().orElse(null);
                }
            } else {
                try (InputStream in = Files.newInputStream(path)) {
                    applicationManifest = new Manifest(in);
                }
            }
            return;
        }
        ApplicationEntry own = application.get("META-INF/MANIFEST.MF");
        if (own != null) {
            applicationManifest = new Manifest(new ByteArrayInputStream(applicationBytes(own)));
        }
    }

    /**
     * Prepares every dependency as a nested jar and reads what the index has to know about it: its manifest
     * attributes, its per-package sections, whether it was signed and where each of its entries ends up
     * inside it. In STORED a dependency is repacked into a nested jar in the work directory; in PRESERVE the
     * dependency itself is the nested jar, and is only checksummed.
     *
     * <p>Every nested jar's entry name and work file are fixed first, in class-path order, so neither
     * depends on which dependency is staged first. When {@link #parallelism} or the number of dependencies
     * is at most one, the stages then run on the calling thread, one after the other; otherwise they run on
     * a pool created for this build, largest dependency first. Either way each stage is joined in class-path
     * order on the calling thread, which is the only thread that emits a warning or touches the builder.</p>
     *
     * @param work the directory the repacked nested jars are built in
     * @throws IOException if a dependency cannot be read or its nested jar cannot be written; when several
     *                     cannot, the failure of the first one on the class path
     */
    private void collectDependencies(Path work) throws IOException {
        List<DependencyStage> stages = new ArrayList<>(dependencies.size());
        Set<String> taken = new HashSet<>();
        for (int position = 0; position < dependencies.size(); position++) {
            Dependency dependency = dependencies.get(position);
            stages.add(new DependencyStage(dependency,
                    IndexFormat.LIB_PREFIX + uniqueName(taken, fileName(dependency.path())),
                    work.resolve("lib-" + position + ".jar"), spec.compression()));
        }
        int threads = Math.min(parallelism, stages.size());
        if (threads <= 1) {
            for (DependencyStage stage : stages) {
                join(stage.call());
            }
        } else {
            stageInParallel(stages, threads);
        }
    }

    /**
     * Runs the stages on a fixed pool of daemon threads created for this build, never on a shared pool, and
     * stops that pool before returning, whether the stages succeeded or not.
     *
     * @param stages  every stage, in class-path order
     * @param threads the size of the pool, at least two
     * @throws IOException if a stage failed, the calling thread was interrupted or the pool did not stop
     */
    private void stageInParallel(List<DependencyStage> stages, int threads) throws IOException {
        Integer[] submissionOrder = largestFirst(stages);
        StageThreads factory = new StageThreads();
        ExecutorService pool = Executors.newFixedThreadPool(threads, factory);
        Throwable failure = null;
        try {
            List<Future<StagedDependency>> futures = new ArrayList<>(Collections.nCopies(stages.size(), null));
            for (int position : submissionOrder) {
                futures.set(position, pool.submit(stages.get(position)));
            }
            for (Future<StagedDependency> future : futures) {
                join(awaitStage(future));
            }
        } catch (Throwable e) {
            failure = e;
            throw e;
        } finally {
            stopStaging(pool, factory, failure, STAGE_SHUTDOWN_TIMEOUT);
        }
    }

    /**
     * The order in which stages are submitted: largest source first, so the longest stage is not the last
     * one to start, and in class-path order among sources of the same size.
     *
     * <p>A source whose size cannot be read goes last; its stage reports why it cannot be read, exactly as
     * it would on the calling thread.</p>
     *
     * @param stages every stage, in class-path order
     * @return the class-path positions, in submission order
     */
    private static Integer[] largestFirst(List<DependencyStage> stages) {
        long[] sizes = new long[stages.size()];
        Integer[] order = new Integer[sizes.length];
        for (int position = 0; position < sizes.length; position++) {
            order[position] = position;
            try {
                sizes[position] = Files.size(stages.get(position).dependency.path());
            } catch (IOException e) {
                sizes[position] = -1;
            }
        }
        Arrays.sort(order, Comparator.<Integer>comparingLong(position -> sizes[position]).reversed()
                .thenComparingInt(position -> position));
        return order;
    }

    /**
     * Waits for a stage and hands back what it produced.
     *
     * @param future the submitted stage
     * @param <T>    what the stage produces
     * @return what the stage produced
     * @throws IOException            what the stage threw, unwrapped; a checked exception other than an
     *                                {@link IOException} is wrapped in one
     * @throws InterruptedIOException if the calling thread is interrupted while it waits, which leaves its
     *                                interrupt flag set
     */
    static <T> T awaitStage(Future<T> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException(
                    "Interrupted while waiting for the dependencies to be staged");
            interrupted.initCause(e);
            throw interrupted;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("A dependency could not be staged: " + cause, cause);
        }
    }

    /**
     * Interrupts a staging pool and waits, up to {@code timeout}, until each of its threads has terminated,
     * so none of them is still writing to the work directory when the build deletes it. The wait happens
     * even when the calling thread has been interrupted: its flag is cleared for the wait and restored
     * afterwards.
     *
     * @param pool    the pool, which is shut down
     * @param threads the threads the pool created
     * @param failure what the build is already failing with, or {@code null}
     * @param timeout how long to wait
     * @throws IOException if a thread did not terminate in time and the build is not already failing; when
     *                     it is, that exception is added to {@code failure} as suppressed instead
     */
    static void stopStaging(ExecutorService pool, StageThreads threads, Throwable failure, Duration timeout)
            throws IOException {
        pool.shutdownNow();
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean interrupted = Thread.interrupted();
        boolean stopped;
        try {
            while (true) {
                try {
                    stopped = pool.awaitTermination(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                            && threads.join(deadline);
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (!stopped) {
            IOException stuck = new IOException("The dependency staging threads did not stop within "
                    + timeout.toMillis() + " ms of being interrupted");
            if (failure == null) {
                throw stuck;
            }
            failure.addSuppressed(stuck);
        }
    }

    /**
     * Takes one stage's result on the calling thread: its warnings, in the order a sequential build emits
     * them, then its nested jar.
     *
     * @param staged what the stage produced
     */
    private void join(StagedDependency staged) {
        if (staged.classPathWarning() != null) {
            warn(staged.classPathWarning());
        }
        if (staged.signatureWarning() != null) {
            warn(staged.signatureWarning());
        }
        nested.add(staged.jar());
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
        String classPath = attribute(manifest.getMainAttributes(), Attributes.Name.CLASS_PATH);
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

    /**
     * Copies the launcher out of the jar this library carries as a resource, keeping only its classes: the
     * runner jar's own manifest replaces the launcher's, and nothing else in that jar has any business in
     * an application artifact.
     *
     * @param work the directory the resource is unpacked into
     * @throws IOException if the resource is missing or cannot be read
     */
    private void loadLauncher(Path work) throws IOException {
        Path copy = work.resolve("launcher.jar");
        try (InputStream in = RunnerJarBuilder.class.getResourceAsStream(LAUNCHER_RESOURCE)) {
            if (in == null) {
                throw new IOException("The packaging library carries no launcher at " + LAUNCHER_RESOURCE
                        + "; it was built incorrectly");
            }
            Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
        }
        try (ZipReader reader = ZipReader.open(copy)) {
            Manifest manifest = reader.manifest().orElse(null);
            launcherVersion = attribute(manifest == null ? null : manifest.getMainAttributes(),
                    Attributes.Name.IMPLEMENTATION_VERSION);
        }
    }

    private void planLauncherClasses(Path work) throws IOException {
        try (ZipReader reader = ZipReader.open(work.resolve("launcher.jar"))) {
            for (ZipEntryInfo entry : reader.entries()) {
                String name = entry.name();
                if (entry.directory() || !name.startsWith(LAUNCHER_PREFIX) || !name.endsWith(".class")) {
                    continue;
                }
                plan.add(PlannedEntry.ofBytes(name, reader.read(entry), entry.crc32()));
            }
        }
    }

    /**
     * Describes jar {@code 0}: the application layer, which is the outer archive itself.
     *
     * <p>It has to be the first jar added, because the format reserves index {@code 0} for it.</p>
     */
    private void describeApplicationJar() {
        writer.startClass(spec.mainClass())
                .entryStubClass(entryStubClass)
                .launcherVersion(launcherVersion)
                .headerFlags((spec.compression() == Compression.STORED
                        ? IndexFormat.HEADER_FLAG_NESTED_STORED : 0)
                        | (spec.multiRelease() ? IndexFormat.HEADER_FLAG_APP_MULTI_RELEASE : 0));
        applicationJar = writer.addJar(IndexFormat.CLASSES_PREFIX);
        if (spec.multiRelease()) {
            applicationJar.addFlags(IndexFormat.JAR_FLAG_MULTI_RELEASE);
        }
        if (application.containsKey("META-INF/MANIFEST.MF")) {
            applicationJar.addFlags(IndexFormat.JAR_FLAG_HAS_MANIFEST);
        }
        describeManifest(applicationJar, applicationManifest);
    }

    /**
     * Writes the union of every {@code META-INF/micronaut/} entry into the root of the outer archive.
     *
     * <p>Micronaut discovers its services by asking the class loader for that directory and listing what is
     * in it. One merged copy at the root of the archive turns that scan into a listing of a zip file the
     * runtime already has open, instead of one mount per nested jar. Almost every such entry is empty -
     * what carries the information is its name - but an entry that does have content keeps it: the class
     * loader answers every lookup under that prefix from the merged copy, so a merged copy written empty
     * would serve zero bytes for a file that is not empty, without a word in the build log.</p>
     *
     * <p>The first contributor on the class path wins, even when it is empty, and there is no size limit.
     * A zero-length entry costs no I/O at all, here or when the archive is written: every one of them shares
     * a single in-memory empty content. The rare dependency entry that has content is read once, through the
     * build's own {@link ZipReader}, while its nested jar's entries are collected.</p>
     *
     * @throws IOException if a non-empty entry cannot be read back out of the nested jar that holds it, or an
     *                     application contributor cannot be compared with another
     */
    private void planMergedServices() throws IOException {
        Map<String, ApplicationEntry> contents = new LinkedHashMap<>();
        for (Map.Entry<String, ApplicationEntry> item : application.entrySet()) {
            String name = item.getKey();
            if (!isMergedServiceName(name)) {
                continue;
            }
            serviceNames.add(name);
            contents.put(name, item.getValue());
        }
        for (NestedJar jar : nested) {
            collectMergedServices(jar, contents);
        }
        if (serviceNames.isEmpty()) {
            return;
        }
        mergedServiceEntryCount = serviceNames.size();
        Set<String> merged = new TreeSet<>(serviceNames);
        merged.add(IndexFormat.MICRONAUT_SERVICES_PREFIX);
        for (String name : serviceNames) {
            for (int slash = name.indexOf('/', IndexFormat.MICRONAUT_SERVICES_PREFIX.length());
                 slash >= 0; slash = name.indexOf('/', slash + 1)) {
                merged.add(name.substring(0, slash + 1));
            }
        }
        // Sorted, so a directory always precedes what it contains.
        for (String name : merged) {
            boolean directory = name.endsWith("/");
            PlannedEntry entry = directory
                    ? PlannedEntry.ofDirectory(name)
                    : PlannedEntry.ofSource(name, contents.get(name));
            entry.indexEntry = applicationJar.addEntry(name)
                    .sizes(entry.size, entry.size)
                    .crc32(entry.crc32)
                    .dosTime(dosTime);
            plan.add(entry);
        }
        StringBuilder message = new StringBuilder();
        message.append("Merged ").append(mergedServiceEntryCount)
                .append(" Micronaut service entries into the archive root");
        logger.info(message.toString());
    }

    /**
     * Whether a name is one of the {@code META-INF/micronaut/} files the merged copy at the archive root
     * has to carry: a file, under the prefix, and not the prefix itself.
     *
     * @param name the logical entry name
     * @return {@code true} when the entry is merged
     */
    private static boolean isMergedServiceName(String name) {
        return name.startsWith(IndexFormat.MICRONAUT_SERVICES_PREFIX)
                && name.length() > IndexFormat.MICRONAUT_SERVICES_PREFIX.length()
                && !name.endsWith("/");
    }

    /**
     * Adds one dependency's {@code META-INF/micronaut/} entries to the merged set. A zero-length entry is a
     * contributor in its own right: it reserves the name just as an empty class-path resource does.
     *
     * <p>The entries come from {@link NestedJar#result}, which describes the nested jar as it was staged, the
     * repacked copy in STORED and the dependency itself in PRESERVE, so the nested jar is not parsed again. A
     * zero-length entry becomes the shared {@link #EMPTY_CONTENT} and costs no I/O. An entry with content is
     * read into memory with {@link ZipReader#read(ZipEntryInfo)}, which inflates it when it is compressed and
     * verifies its CRC-32; the reader is opened at the first such entry, at most once for the jar, and closed
     * before this method returns. A jar whose metadata entries are all empty, or that has none, is never
     * opened.</p>
     *
     * @param jar      the dependency, already staged as a nested jar
     * @param contents the merged content so far, keyed by logical name, in class path order
     * @throws IOException if a non-empty entry cannot be read back out of the nested jar, or does not match
     *                     its recorded size or CRC-32
     */
    private void collectMergedServices(NestedJar jar, Map<String, ApplicationEntry> contents) throws IOException {
        Set<String> contributed = new HashSet<>();
        ZipReader reader = null;
        try {
            for (ZipEntryInfo entry : jar.result.entries()) {
                String name = entry.name();
                if (entry.directory() || !isMergedServiceName(name) || !contributed.add(name)) {
                    continue;
                }
                serviceNames.add(name);
                ApplicationEntry candidate;
                if (entry.uncompressedSize() == 0 && entry.crc32() == 0) {
                    candidate = EMPTY_CONTENT;
                } else {
                    // Also the path for a zero-length entry that records a non-zero CRC-32, which only
                    // a damaged PRESERVE input can carry: reading it verifies the CRC and fails loudly.
                    if (reader == null) {
                        reader = ZipReader.open(jar.file);
                    }
                    candidate = ApplicationEntry.ofBytes(readMergedService(jar, reader, entry), entry.crc32());
                }
                ApplicationEntry existing = contents.putIfAbsent(name, candidate);
                if (existing != null && !sameContent(existing, candidate)) {
                    warn("Two class path entries contribute a different '" + name
                            + "'. The copy merged into the root of the archive is the first on the class path;"
                            + " the one in " + jar.dependency.path()
                            + " is reachable only through that jar");
                }
            }
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    private static byte[] readMergedService(NestedJar jar, ZipReader reader, ZipEntryInfo entry)
            throws IOException {
        try {
            return reader.read(entry);
        } catch (IOException e) {
            // In STORED the reader names the repacked copy; the dependency is what has to be fixed.
            throw new IOException("The dependency " + jar.dependency.path() + " cannot be packaged: "
                    + e.getMessage(), e);
        }
    }

    private static boolean sameContent(ApplicationEntry first, ApplicationEntry second) throws IOException {
        if (first.size != second.size || first.crc32 != second.crc32) {
            return false;
        }
        if (first.size == 0) {
            return true;
        }
        try (InputStream left = first.open(); InputStream right = second.open()) {
            byte[] leftBuffer = new byte[BUFFER_SIZE];
            byte[] rightBuffer = new byte[BUFFER_SIZE];
            while (true) {
                int leftRead = readChunk(left, leftBuffer);
                int rightRead = readChunk(right, rightBuffer);
                if (leftRead != rightRead) {
                    return false;
                }
                if (leftRead < 0) {
                    return true;
                }
                if (!Arrays.equals(leftBuffer, 0, leftRead, rightBuffer, 0, rightRead)) {
                    return false;
                }
            }
        }
    }

    private static int readChunk(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = input.read(buffer, total, buffer.length - total);
            if (read < 0) {
                return total == 0 ? -1 : total;
            }
            if (read == 0) {
                int value = input.read();
                if (value < 0) {
                    return total == 0 ? -1 : total;
                }
                buffer[total++] = (byte) value;
            } else {
                total += read;
            }
        }
        return total;
    }

    private void planApplicationEntries() {
        // A real directory entry for the application layer. Without it the code source URL stamped on
        // every application class, jar:file:/app.jar!/MICRONAUT-INF/classes/, names something the outer
        // archive does not carry, and opening it - which is what code that asks where it is running from
        // does - fails where the same call on a URLClassLoader's directory code source succeeds. It is
        // deliberately not an index record: the index knows the layer as jar 0, not as an entry.
        plan.add(PlannedEntry.ofDirectory(IndexFormat.CLASSES_PREFIX));
        for (Map.Entry<String, ApplicationEntry> item : application.entrySet()) {
            String logicalName = item.getKey();
            ApplicationEntry source = item.getValue();
            PlannedEntry entry = source.bytes == null
                    ? PlannedEntry.ofSource(IndexFormat.CLASSES_PREFIX + logicalName, source)
                    : PlannedEntry.ofBytes(IndexFormat.CLASSES_PREFIX + logicalName, source.bytes, source.crc32);
            entry.indexEntry = applicationJar.addEntry(logicalName)
                    .sizes(entry.size, entry.size)
                    .crc32(entry.crc32)
                    .dosTime(dosTime);
            plan.add(entry);
        }
    }

    private void planNestedJars() {
        boolean preserved = spec.compression() == Compression.PRESERVE;
        for (NestedJar jar : nested) {
            // A repacked jar's length is the offset its writer ended at; a preserved one's is its reader's.
            long length = jar.result.length();
            // A preserved jar is the dependency itself, which nothing stops from changing after its stage
            // checksummed it: it is copied through a verified read, which fails the build before the archive
            // is published unless the bytes it copies are the ones that checksum describes. A repacked jar is
            // the build's own work file and is copied as it is.
            jar.entry = preserved
                    ? PlannedEntry.ofSource(jar.entryName, ApplicationEntry.ofFile(jar.file, length, jar.crc32))
                    : PlannedEntry.ofFile(jar.entryName, jar.file, length, jar.crc32);
            plan.add(jar.entry);
            Attributes main = jar.manifest == null ? null : jar.manifest.getMainAttributes();
            jar.jar = writer.addJar(jar.entryName).coordinates(jar.dependency.coordinates().orElse(null));
            if (jar.hasManifest) {
                jar.jar.addFlags(IndexFormat.JAR_FLAG_HAS_MANIFEST);
            }
            if (isTrue(main, "Multi-Release")) {
                jar.jar.addFlags(IndexFormat.JAR_FLAG_MULTI_RELEASE);
            }
            if (jar.result.hadSignatureFiles()) {
                jar.jar.addFlags(IndexFormat.JAR_FLAG_SIGNED_ORIGINAL);
            }
            describeManifest(jar.jar, jar.manifest);
            for (ZipEntryInfo entry : jar.result.entries()) {
                jar.entries.add(jar.jar.addEntry(entry.name(), entry));
            }
        }
    }

    /**
     * Copies a jar's manifest into its index record: the six attributes {@code Package} exposes, whether it
     * seals its packages by default, and one record per {@code Name:} section that overrides any of them.
     *
     * @param jar      the jar record being described
     * @param manifest the jar's manifest, or {@code null} when it has none
     */
    private void describeManifest(IndexWriter.JarSpec jar, Manifest manifest) {
        if (manifest == null) {
            return;
        }
        Attributes main = manifest.getMainAttributes();
        jar.manifest(describedAttribute(main, Attributes.Name.SPECIFICATION_TITLE),
                describedAttribute(main, Attributes.Name.SPECIFICATION_VERSION),
                describedAttribute(main, Attributes.Name.SPECIFICATION_VENDOR),
                describedAttribute(main, Attributes.Name.IMPLEMENTATION_TITLE),
                describedAttribute(main, Attributes.Name.IMPLEMENTATION_VERSION),
                describedAttribute(main, Attributes.Name.IMPLEMENTATION_VENDOR));
        if (isTrue(main, "Sealed")) {
            jar.addFlags(IndexFormat.JAR_FLAG_SEALED_BY_DEFAULT);
        }
        // Sorted, because java.util.jar.Manifest keeps its sections in a hash map and the index must not
        // depend on that order.
        Map<String, Attributes> sections = new TreeMap<>(manifest.getEntries());
        for (Map.Entry<String, Attributes> section : sections.entrySet()) {
            String name = section.getKey();
            if (!name.endsWith("/")) {
                // A section naming a file, such as the digests of a signed jar, says nothing about a package.
                continue;
            }
            Attributes attributes = section.getValue();
            String sealed = attributes.getValue("Sealed");
            String specificationTitle = describedAttribute(attributes, Attributes.Name.SPECIFICATION_TITLE);
            String specificationVersion =
                    describedAttribute(attributes, Attributes.Name.SPECIFICATION_VERSION);
            String specificationVendor = describedAttribute(attributes, Attributes.Name.SPECIFICATION_VENDOR);
            String implementationTitle = describedAttribute(attributes, Attributes.Name.IMPLEMENTATION_TITLE);
            String implementationVersion =
                    describedAttribute(attributes, Attributes.Name.IMPLEMENTATION_VERSION);
            String implementationVendor =
                    describedAttribute(attributes, Attributes.Name.IMPLEMENTATION_VENDOR);
            if (sealed == null && specificationTitle == null && specificationVersion == null
                    && specificationVendor == null && implementationTitle == null
                    && implementationVersion == null && implementationVendor == null) {
                continue;
            }
            IndexWriter.PackageSpec record = jar
                    .addPackage(name.substring(0, name.length() - 1).replace('/', '.'))
                    .attributes(specificationTitle, specificationVersion, specificationVendor,
                            implementationTitle, implementationVersion, implementationVendor);
            if (sealed != null) {
                record.sealed("true".equalsIgnoreCase(sealed));
            }
        }
    }

    /**
     * Builds the manifest of the runner jar itself.
     *
     * @return the manifest bytes, which are the first entry of the archive
     * @throws IOException if an attribute the caller configured is not a legal manifest attribute
     */
    private byte[] manifestBytes() throws IOException {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(Attributes.Name.MAIN_CLASS, IndexFormat.LAUNCHER_CLASS);
        main.putValue(IndexFormat.ATTR_FORMAT, Integer.toString(IndexFormat.FORMAT_VERSION));
        if (launcherVersion != null) {
            main.putValue(IndexFormat.ATTR_VERSION, launcherVersion);
        }
        main.putValue(IndexFormat.ATTR_START_CLASS, spec.mainClass());
        if (applicationManifest != null) {
            Attributes source = applicationManifest.getMainAttributes();
            copyAttribute(main, source, Attributes.Name.IMPLEMENTATION_TITLE);
            copyAttribute(main, source, Attributes.Name.IMPLEMENTATION_VERSION);
            copyAttribute(main, source, Attributes.Name.IMPLEMENTATION_VENDOR);
            copyAttribute(main, source, Attributes.Name.SPECIFICATION_TITLE);
            copyAttribute(main, source, Attributes.Name.SPECIFICATION_VERSION);
            copyAttribute(main, source, Attributes.Name.SPECIFICATION_VENDOR);
        }
        for (Map.Entry<String, String> attribute : spec.manifestAttributes().entrySet()) {
            try {
                main.putValue(attribute.getKey(), attribute.getValue());
            } catch (IllegalArgumentException e) {
                throw new IOException("'" + attribute.getKey() + "' is not a legal manifest attribute name",
                        e);
            }
        }
        if (!spec.addOpens().isEmpty()) {
            main.putValue("Add-Opens", String.join(" ", spec.addOpens()));
        }
        if (!spec.addExports().isEmpty()) {
            main.putValue("Add-Exports", String.join(" ", spec.addExports()));
        }
        if (spec.enableNativeAccess()) {
            main.putValue("Enable-Native-Access", "ALL-UNNAMED");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        manifest.write(bytes);
        return bytes.toByteArray();
    }

    private void copyAttribute(Attributes target, Attributes source, Attributes.Name name) {
        String value = attribute(source, name);
        if (value != null) {
            target.put(name, value);
        }
    }

    /**
     * Writes the archive, either for real or as metadata-only layout.
     *
     * <p>The dry pass registers the same entry metadata with a {@link ZipWriter}, but advances over declared
     * payload lengths without reading or writing fake bytes. The writer therefore remains the sole authority
     * on header and ZIP64 geometry while dry work stays proportional to metadata. The real pass then checks
     * every offset against what the dry pass recorded, so a disagreement between the two can never reach an
     * archive.</p>
     *
     * <p>An entry of an application jar is streamed from that jar's open reader, which checks its size,
     * CRC-32 and DEFLATE framing as it goes; an empty one too, because only inflating it checks its framing.
     * Any other zero-length entry is written from a constant in the real pass too, whatever it is backed by,
     * so it never opens a stream, a channel or a mapping.</p>
     *
     * @param archive the file to write, or {@code null} for the dry pass
     * @return the length of the archive
     * @throws IOException if an entry cannot be written, does not land where it was planned to, is empty but
     *                     records a non-zero CRC-32, or no longer matches the size or CRC-32 it was collected
     *                     with
     */
    private long writeArchive(Path archive) throws IOException {
        boolean dry = archive == null;
        OutputStream out = dry
                ? OutputStream.nullOutputStream()
                : new BufferedOutputStream(Files.newOutputStream(archive), BUFFER_SIZE);
        ZipWriter writer = dry ? ZipWriter.layout(out, spec.timestamp()) : new ZipWriter(out, spec.timestamp());
        try (ZipWriter zip = writer) {
            for (PlannedEntry entry : plan) {
                long localHeaderOffset = zip.offset();
                long dataOffset;
                if (entry.directory) {
                    dataOffset = zip.writeDirectoryEntry(entry.name, dosTime);
                } else if (dry) {
                    dataOffset = zip.layoutEntry(entry.name, entry.size, entry.crc32, dosTime);
                } else if (entry.source != null && entry.source.reader != null) {
                    // Before the zero-length branch: an empty deflated entry can still carry a broken stream,
                    // and its reader is already open.
                    dataOffset = zip.writeEntry(entry.name, entry.source.reader, entry.source.zipEntry, dosTime);
                } else if (entry.size == 0) {
                    // Nothing to copy, so nothing to open: no stream, channel or mapping for an empty entry,
                    // whatever else it is backed by. Its recorded CRC-32 is the only thing left to check.
                    if (entry.crc32 != 0) {
                        throw new IOException("The empty entry '" + entry.name + "' records CRC-32 "
                                + Long.toHexString(entry.crc32));
                    }
                    dataOffset = zip.writeEntry(entry.name, EMPTY_BYTES, 0, 0, dosTime);
                } else if (entry.bytes != null) {
                    dataOffset = zip.writeEntry(entry.name, entry.bytes, 0, entry.bytes.length, dosTime);
                } else if (entry.source != null) {
                    try (InputStream in = entry.source.open()) {
                        dataOffset = zip.writeEntry(entry.name, in, entry.size, entry.crc32, dosTime);
                    }
                } else {
                    try (InputStream in = Files.newInputStream(entry.file)) {
                        dataOffset = zip.writeEntry(entry.name, in, entry.size, entry.crc32, dosTime);
                    }
                }
                if (dry) {
                    entry.localHeaderOffset = localHeaderOffset;
                    entry.dataOffset = dataOffset;
                } else if (dataOffset != entry.dataOffset || localHeaderOffset != entry.localHeaderOffset) {
                    throw new IOException("The entry '" + entry.name + "' landed at " + dataOffset
                            + " but the index says " + entry.dataOffset);
                }
            }
            zip.finish();
            return zip.offset();
        }
    }

    /**
     * Feeds the offsets the dry pass discovered back into the index.
     *
     * @param archiveSize the length of the finished archive, which is also the length of jar {@code 0}
     */
    private void applyOffsets(long archiveSize) {
        applicationJar.location(0, archiveSize, 0);
        for (PlannedEntry entry : plan) {
            if (entry.indexEntry != null) {
                entry.indexEntry.dataOffset(entry.dataOffset);
            }
        }
        for (NestedJar jar : nested) {
            long base = jar.entry.dataOffset;
            jar.jar.location(base, jar.entry.size, jar.entry.localHeaderOffset);
            List<ZipEntryInfo> infos = jar.result.entries();
            for (int i = 0; i < infos.size(); i++) {
                jar.entries.get(i).dataOffset(infos.get(i).dataOffset() + base);
            }
        }
    }

    private void move(Path archive) throws IOException {
        try {
            Files.move(archive, output, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException e) {
            // Not every file system can do it atomically; the archive is complete either way.
            Files.move(archive, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reopens the archive with the launcher's own reader and checks that it says about itself what it was
     * built to say: the right counts, a local file header where every nested jar is supposed to start, and
     * content that matches the checksums the index records.
     *
     * <p>This runs on the archive while it is still in the work directory, before it is moved onto the
     * output path. A build that fails here therefore leaves whatever was at the output path alone, rather
     * than publishing an artifact the launcher has just refused to read. The messages name the output,
     * because that is the artifact the caller asked for.</p>
     *
     * @param archive the archive as it was written, in the work directory
     * @param layout  the layout the index was written from
     * @throws IOException if the archive cannot be read or disagrees with its index
     */
    private void verify(Path archive, IndexWriter.Layout layout) throws IOException {
        boolean all = "true".equals(System.getProperty(VERIFY_ALL_PROPERTY));
        try (RunnerJarReader reader = RunnerJarReader.open(archive)) {
            Index index = reader.index();
            if (index.jarCount() != writer.jars().size() || index.entryCount() != layout.entryCount()) {
                throw new IOException("The index of " + output + " describes " + index.jarCount() + " jars and "
                        + index.entryCount() + " entries, but " + writer.jars().size() + " and "
                        + layout.entryCount() + " were written");
            }
            if (!spec.mainClass().equals(index.startClass())) {
                throw new IOException("The index of " + output + " names " + index.startClass()
                        + " as the application main class");
            }
            if (!Objects.equals(entryStubClass, index.entryStubClass())) {
                throw new IOException("The index of " + output + " names " + index.entryStubClass()
                        + " as the entry stub, but " + entryStubClass + " was generated");
            }
            if (entryStubClass != null && index.findClass(entryStubClass) == IndexFormat.NO_INDEX) {
                throw new IOException("The index of " + output + " names " + entryStubClass
                        + " as the entry stub but does not know the class; the launcher would not start");
            }
            index.validateStringReferences();
            for (int jarId = 0; jarId < index.jarCount(); jarId++) {
                index.validateJar(jarId);
            }
            int total = index.entryCount();
            int step = all ? 1 : Math.max(1, total / VERIFY_SAMPLE_SIZE);
            byte[] verifyBuffer = new byte[BUFFER_SIZE];
            for (int record = 0; record < total; record += step) {
                verifyEntry(reader, index, record, verifyBuffer);
            }
        }
    }

    private void verifyEntry(RunnerJarReader reader, Index index, int record, byte[] buffer) throws IOException {
        if (!index.entryPhysical(record) || index.entryDirectory(record)) {
            return;
        }
        long expected = index.entryCrc32(record);
        CRC32 crc = new CRC32();
        try (InputStream input = reader.stream(record)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                crc.update(buffer, 0, read);
            }
        }
        long actual = crc.getValue();
        if (expected != actual) {
            throw new IOException("The entry '" + index.entryName(record) + "' of " + output
                    + " does not match the CRC-32 the index records: expected "
                    + Long.toHexString(expected) + ", read " + Long.toHexString(actual));
        }
    }

    private static void requireSafeName(String name, String origin) throws IOException {
        if (!ZipReader.isSafeEntryName(name)) {
            throw new IOException("The entry name '" + name + "' from " + origin
                    + " cannot be stored in a runner jar");
        }
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    /**
     * One entry of the outer archive as it is planned, before and after the pass that discovers where it
     * lands.
     */
    private static final class PlannedEntry {

        private final String name;
        private final boolean directory;
        private byte[] bytes;
        private Path file;
        private ApplicationEntry source;
        private long size;
        private long crc32;
        private long dataOffset;
        private long localHeaderOffset;
        private IndexWriter.EntrySpec indexEntry;

        private PlannedEntry(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }

        private static PlannedEntry ofBytes(String name, byte[] content) {
            return ofBytes(name, content, crc32(content));
        }

        private static PlannedEntry ofBytes(String name, byte[] content, long crc32) {
            PlannedEntry entry = new PlannedEntry(name, false);
            entry.bytes = content;
            entry.size = content.length;
            entry.crc32 = crc32;
            return entry;
        }

        private static PlannedEntry ofFile(String name, Path file, long size, long crc32) {
            PlannedEntry entry = new PlannedEntry(name, false);
            entry.file = file;
            entry.size = size;
            entry.crc32 = crc32;
            return entry;
        }

        private static PlannedEntry ofSource(String name, ApplicationEntry source) {
            PlannedEntry entry = new PlannedEntry(name, false);
            entry.source = Objects.requireNonNull(source, "source");
            entry.size = source.size;
            entry.crc32 = source.crc32;
            return entry;
        }

        private static PlannedEntry ofDirectory(String name) {
            return new PlannedEntry(name, true);
        }

        private static PlannedEntry placeholder(String name) {
            return new PlannedEntry(name, false);
        }
    }

    /**
     * The content of one entry the builder copies rather than generates, with the size and CRC-32 it was
     * collected with. It is one of three things:
     * <ul>
     *     <li>bytes in memory: a generated class, or a dependency's non-empty merged Micronaut metadata;</li>
     *     <li>an entry of an application jar, read through that jar's {@link ZipReader}, which stays open until
     *     the archive has been written and inflates and verifies the entry each time it is read;</li>
     *     <li>a file, read through a {@link VerifiedFileInputStream} that fails unless it reads exactly the
     *     bytes the CRC-32 describes: a file of an application directory, or, in PRESERVE, the dependency
     *     that is nested as it is.</li>
     * </ul>
     */
    private static final class ApplicationEntry {

        private final byte[] bytes;
        private final Path file;
        private final ZipReader reader;
        private final ZipEntryInfo zipEntry;
        private final long size;
        private final long crc32;

        private ApplicationEntry(byte[] bytes, Path file, ZipReader reader, ZipEntryInfo zipEntry, long size,
                                 long crc32) {
            this.bytes = bytes;
            this.file = file;
            this.reader = reader;
            this.zipEntry = zipEntry;
            this.size = size;
            this.crc32 = crc32;
        }

        private static ApplicationEntry ofBytes(byte[] content) {
            return ofBytes(content, RunnerJarBuilder.crc32(content));
        }

        private static ApplicationEntry ofBytes(byte[] content, long crc32) {
            return new ApplicationEntry(content, null, null, null, content.length, crc32);
        }

        private static ApplicationEntry ofFile(Path file, long size, long crc32) {
            return new ApplicationEntry(null, file, null, null, size, crc32);
        }

        /**
         * An entry of an application jar, described by its central directory record and read through the
         * jar's reader, which only the calling thread may use.
         */
        private static ApplicationEntry ofZip(ZipReader reader, ZipEntryInfo entry) {
            return new ApplicationEntry(null, null, reader, entry, entry.uncompressedSize(), entry.crc32());
        }

        /**
         * Opens the content. An entry of an application jar is inflated and verified into memory: only
         * {@code sameContent} opens one, for a non-empty duplicate Micronaut metadata entry, and
         * {@code writeArchive} streams it from the reader instead.
         *
         * @return the content, which the caller closes
         * @throws IOException if it cannot be read
         */
        private InputStream open() throws IOException {
            if (bytes != null) {
                return new ByteArrayInputStream(bytes);
            }
            if (reader != null) {
                return new ByteArrayInputStream(reader.read(zipEntry));
            }
            return new VerifiedFileInputStream(file, size, crc32);
        }
    }

    /**
     * One dependency, as it has been prepared for nesting.
     */
    private static final class NestedJar {

        private final Dependency dependency;
        private final String entryName;
        private final Path file;
        private final ZipRepacker.RepackResult result;
        private final Manifest manifest;
        private final boolean hasManifest;
        private final long crc32;
        private final List<IndexWriter.EntrySpec> entries = new ArrayList<>();
        private PlannedEntry entry;
        private IndexWriter.JarSpec jar;

        private NestedJar(Dependency dependency, String entryName, Path file,
                          ZipRepacker.RepackResult result, Manifest manifest, boolean hasManifest,
                          long crc32) {
            this.dependency = dependency;
            this.entryName = entryName;
            this.file = file;
            this.result = result;
            this.manifest = manifest;
            this.hasManifest = hasManifest;
            this.crc32 = crc32;
        }
    }

    /**
     * What one dependency's stage hands back to the calling thread: the nested jar and the texts of the
     * warnings the calling thread emits for it.
     *
     * @param jar              the dependency, written as a nested jar
     * @param classPathWarning the {@code Class-Path} warning, or {@code null}
     * @param signatureWarning the signed-dependency warning, or {@code null}
     */
    private record StagedDependency(NestedJar jar, String classPathWarning, String signatureWarning) {
    }

    /**
     * Stages one dependency: repacks it into its nested jar, or, in PRESERVE, checksums it where it is, and
     * describes the result.
     *
     * <p>A stage may run on a worker thread, so it reads nothing but its own fields and touches no builder
     * state. It opens, uses and closes its {@link ZipReader}, streams, {@link CRC32} and buffers on the
     * thread that runs it; only the {@link StagedDependency} it returns reaches another thread.</p>
     */
    private static final class DependencyStage implements Callable<StagedDependency> {

        private final Dependency dependency;
        private final String entryName;
        /** The work file a repacked nested jar is written to; a preserved dependency leaves it unused. */
        private final Path target;
        private final Compression compression;

        private DependencyStage(Dependency dependency, String entryName, Path target, Compression compression) {
            this.dependency = dependency;
            this.entryName = entryName;
            this.target = target;
            this.compression = compression;
        }

        @Override
        public StagedDependency call() throws IOException {
            Path file;
            long crc32;
            ZipRepacker.RepackResult result;
            Manifest manifest;
            boolean hasManifest;
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
                    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), BUFFER_SIZE);
                         CheckedOutputStream checked = new CheckedOutputStream(out, crc)) {
                        result = ZipRepacker.repack(reader, checked);
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
                requireSafeName(entry.name(), dependency.path().toString());
            }
            return new StagedDependency(
                    new NestedJar(dependency, entryName, file, result, manifest, hasManifest, crc32),
                    classPathWarning(dependency, manifest),
                    signatureWarning(dependency, compression, result));
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
    }

    /**
     * Creates one build's staging threads, daemons named {@code micronaut-runner-stage-1} onwards, and
     * remembers them so the build can wait until each one has terminated.
     */
    static final class StageThreads implements ThreadFactory {

        private final List<Thread> threads = new ArrayList<>();

        @Override
        public synchronized Thread newThread(Runnable task) {
            Thread thread = new Thread(task, STAGE_THREAD_PREFIX + (threads.size() + 1));
            thread.setDaemon(true);
            threads.add(thread);
            return thread;
        }

        /**
         * Waits until every thread created so far has terminated, or the deadline passes.
         *
         * @param deadline the {@link System#nanoTime()} to wait until
         * @return whether every thread has terminated
         * @throws InterruptedException if the calling thread is interrupted while it waits
         */
        boolean join(long deadline) throws InterruptedException {
            List<Thread> created;
            synchronized (this) {
                created = List.copyOf(threads);
            }
            for (Thread thread : created) {
                // A thread the pool failed to start has nothing to wait for, and join would reject it.
                if (thread.getState() != Thread.State.NEW
                        && !thread.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))) {
                    return false;
                }
            }
            return true;
        }
    }

}
