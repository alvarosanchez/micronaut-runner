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
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.jar.Attributes;
import java.util.jar.Manifest;
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
 * depends only on names and counts, never on offsets: {@link IndexWriter#layout()} reports it before
 * anything is written. The builder therefore lays the index out, runs the whole archive through a
 * {@link ZipWriter} that discards its output to learn where every entry will land, fills those offsets into
 * the index and only then writes the file for real, checking as it goes that every entry landed exactly
 * where the dry run said it would.</p>
 *
 * <p>The result is reproducible: entries are visited in a fixed order, every one of them is dated with
 * {@link RunnerJarSpec#timestamp()} converted in UTC, and nothing about the machine that ran the build
 * reaches the bytes. Building the same inputs twice, in different time zones, produces identical files.</p>
 *
 * <h2>Path safety</h2>
 * <p>Configured input paths may themselves be symbolic links. The builder resolves their real identities,
 * and resolves a not-yet-created output through its nearest existing ancestor, before comparing them. It
 * rejects an output that aliases an application jar, dependency or manifest source, or is canonically below
 * an application directory. The same checks run again immediately before publication. Symbolic links found
 * inside an application directory are rejected rather than followed; this also rejects directory-link
 * cycles and prevents the scan from escaping the configured tree.</p>
 *
 * <p>These checks protect normal builds from accidental aliases. The standard {@link Path} API cannot make
 * checking and replacement one indivisible operation, so a hostile process that can replace path components
 * concurrently can still race them. Output and input directories must therefore be writable only by trusted
 * build participants.</p>
 *
 * @since 1.0
 */
public final class RunnerJarBuilder {

    /** Set to {@code "true"} to checksum every entry of the finished archive instead of a sample. */
    static final String VERIFY_ALL_PROPERTY = "micronaut.runner.build.verifyAll";

    /** The bundled launcher, put on this library's own class path by its build. */
    private static final String LAUNCHER_RESOURCE = "/META-INF/micronaut-runner/launcher.jar";

    /** The only entries copied out of the bundled launcher jar. */
    private static final String LAUNCHER_PREFIX = "io/micronaut/runner/";

    /** How many entries the verification pass checksums when it is not checking all of them. */
    private static final int VERIFY_SAMPLE_SIZE = 64;

    /** Buffer size for the streaming copies. */
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Largest {@code META-INF/micronaut/} entry whose content is duplicated into the merged copy at the
     * root of the archive.
     *
     * <p>Those entries are service markers, almost always empty and never more than a few hundred bytes.
     * The limit exists so that a pathological input cannot make the packager hold a large file in memory
     * and store it twice; a build that hits it says so.</p>
     */
    private static final long MAX_MERGED_SERVICE_SIZE = 1L << 20;

    /** The content of an entry that has none. */
    private static final byte[] EMPTY_CONTENT = new byte[0];

    private final RunnerJarSpec spec;
    private final BuildLogger logger;
    private final List<String> warnings = new ArrayList<>();
    private final List<PlannedEntry> plan = new ArrayList<>();
    private final List<NestedJar> nested = new ArrayList<>();
    private final Map<String, ApplicationEntry> application = new LinkedHashMap<>();
    private final Set<String> serviceNames = new TreeSet<>();
    private final IndexWriter writer = new IndexWriter();
    private final Path output;
    private final int dosTime;
    private IndexWriter.JarSpec applicationJar;
    private PlannedEntry indexEntry;
    private Manifest applicationManifest;
    private String launcherVersion;
    private String entryStubClass;
    private int mergedServiceEntryCount;

    private RunnerJarBuilder(RunnerJarSpec spec, BuildLogger logger) {
        this.spec = spec;
        this.logger = logger;
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
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(logger, "logger");
        return new RunnerJarBuilder(spec, logger).run();
    }

    private static long crc32(Path file) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.read(buffer);
            while (read > 0) {
                crc.update(buffer, 0, read);
                read = in.read(buffer);
            }
        }
        return crc.getValue();
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
        validate();
        Path directory = output.getParent();
        if (directory == null) {
            throw new IOException("The output " + output + " has no parent directory");
        }
        Files.createDirectories(directory);
        Path work = Files.createTempDirectory(directory, ".micronaut-runner-");
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
            long written = writeArchive(archive);
            if (written != archiveSize) {
                throw new IOException("The archive changed length between the two passes: expected "
                        + archiveSize + " bytes, wrote " + written);
            }
            verify(archive, layout);
            move(archive);

            StringBuilder message = new StringBuilder();
            message.append("Packaged ").append(spec.mainClass()).append(" into ").append(output)
                    .append(" (").append(nested.size()).append(" dependencies, ")
                    .append(layout.entryCount()).append(" index records, ")
                    .append(archiveSize).append(" bytes)");
            logger.info(message.toString());
            return new RunnerJarResult(output, writer.jars().size(), layout.entryCount(),
                    application.size(), mergedServiceEntryCount, archiveSize, warnings);
        } finally {
            deleteRecursively(work);
        }
    }

    private void warn(String message) {
        warnings.add(message);
        logger.warn(message);
    }

    /**
     * Checks everything that can be checked before any work is done: that the inputs exist, that the output
     * is not one of them and does not sit inside one of them, and that the compression mode is one this
     * release implements.
     *
     * @throws IOException if an input is missing or the output would destroy an input
     */
    private void validate() throws IOException {
        for (Path input : spec.applicationOutput()) {
            if (!Files.exists(input)) {
                throw new IOException("The application output " + input + " does not exist");
            }
        }
        for (Dependency dependency : spec.dependencies()) {
            if (!Files.isRegularFile(dependency.path())) {
                throw new IOException("The dependency " + dependency.path() + " does not exist");
            }
        }
        Optional<Path> manifestSource = spec.applicationManifestSource();
        if (manifestSource.isPresent() && !Files.isRegularFile(manifestSource.get())) {
            throw new IOException("The application manifest source " + manifestSource.get() + " does not exist");
        }
        Path resolvedOutput = resolveExistingAncestor(output);
        for (Path input : spec.applicationOutput()) {
            Path resolvedInput = input.toRealPath();
            boolean directory = Files.isDirectory(input);
            boolean collision = directory
                    ? resolvedOutput.startsWith(resolvedInput)
                    : sameFile(output, input, resolvedOutput, resolvedInput);
            if (collision) {
                throw new IOException("The output " + output + " is inside the application output "
                        + resolvedInput + "; packaging it would read what it is writing");
            }
        }
        for (Dependency dependency : spec.dependencies()) {
            Path dependencyPath = dependency.path();
            if (sameFile(output, dependencyPath, resolvedOutput, dependencyPath.toRealPath())) {
                throw new IOException("The output " + output + " is also a dependency of the application");
            }
        }
        if (manifestSource.isPresent()) {
            Path manifest = manifestSource.get();
            if (sameFile(output, manifest, resolvedOutput, manifest.toRealPath())) {
                throw new IOException("The output " + output + " is also the application manifest source");
            }
        }
    }

    private static boolean sameFile(Path candidate, Path input, Path resolvedCandidate, Path resolvedInput)
            throws IOException {
        return Files.exists(candidate) && Files.isSameFile(candidate, input)
                || resolvedCandidate.equals(resolvedInput);
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
        for (Path input : spec.applicationOutput()) {
            if (Files.isDirectory(input)) {
                collectDirectory(input, input);
            } else {
                collectApplicationJar(input);
            }
        }
    }

    private void collectDirectory(Path root, Path directory) throws IOException {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                children.add(child);
            }
        }
        // Sorted, so that the archive does not depend on the order the file system happens to report.
        children.sort(Comparator.comparing(RunnerJarBuilder::fileName));
        for (Path child : children) {
            if (Files.isSymbolicLink(child)) {
                throw new IOException("The application output " + root + " contains the symbolic link " + child
                        + "; symbolic links inside application directories are not supported");
            }
            if (Files.isDirectory(child)) {
                collectDirectory(root, child);
            } else if (Files.isRegularFile(child)) {
                String name = root.relativize(child).toString().replace(File.separatorChar, '/');
                requireSafeName(name, root.toString());
                addApplicationEntry(name, ApplicationEntry.ofFile(child, Files.size(child), crc32(child)),
                        root);
            }
        }
    }

    private void collectApplicationJar(Path jar) throws IOException {
        try (ZipReader reader = ZipReader.open(jar)) {
            for (ZipEntryInfo entry : reader.entries()) {
                String name = entry.name();
                if (entry.directory()) {
                    continue;
                }
                if (ZipReader.isSignatureFile(name) || ZipReader.isIndexList(name)) {
                    continue;
                }
                requireSafeName(name, jar.toString());
                byte[] content = reader.read(entry);
                addApplicationEntry(name, ApplicationEntry.ofBytes(content), jar);
            }
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
     * <p>The main class is <em>parsed</em>, never loaded: a packager that loaded application classes would
     * run their static initialisers in the build JVM.</p>
     *
     * @throws IOException if the main class cannot be read back from the application output
     */
    private void generateEntryStub() throws IOException {
        if (!spec.entryStub()) {
            logger.info("No entry stub was generated because it was not requested; the launcher will start "
                    + spec.mainClass() + " reflectively");
            return;
        }
        ApplicationEntry main = application.get(mainClassEntryName());
        byte[] classFile = main.bytes != null ? main.bytes : Files.readAllBytes(main.file);
        String reason = EntryStubGenerator.ineligibilityReason(spec.mainClass(), classFile);
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
        if (own != null && own.bytes != null) {
            applicationManifest = new Manifest(new ByteArrayInputStream(own.bytes));
        }
    }

    /**
     * Produces every dependency as a nested jar in the work directory and reads what the index has to know
     * about it: its manifest attributes, its per-package sections, whether it was signed and where each of
     * its entries ended up inside it.
     *
     * @param work the directory the nested jars are built in
     * @throws IOException if a dependency cannot be read or its nested copy cannot be written
     */
    private void collectDependencies(Path work) throws IOException {
        Set<String> taken = new HashSet<>();
        int position = 0;
        for (Dependency dependency : spec.dependencies()) {
            String entryName = IndexFormat.LIB_PREFIX + uniqueName(taken, fileName(dependency.path()));
            Path target = work.resolve("lib-" + position + ".jar");
            position++;
            CRC32 crc = new CRC32();
            ZipRepacker.RepackResult result;
            Manifest manifest;
            boolean hasManifest;
            try (ZipReader reader = ZipReader.open(dependency.path())) {
                manifest = reader.manifest().orElse(null);
                hasManifest = reader.entry("META-INF/MANIFEST.MF").isPresent();
                warnAboutClassPath(dependency, manifest);
                try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(target), BUFFER_SIZE);
                     CheckedOutputStream checked = new CheckedOutputStream(file, crc)) {
                    result = spec.compression() == Compression.STORED
                            ? ZipRepacker.repack(reader, checked)
                            : ZipRepacker.copy(reader, checked);
                } catch (IOException e) {
                    // Without this the message names only the entry, and a build with dozens of
                    // dependencies says nothing about which jar has to be looked at.
                    throw new IOException("The dependency " + dependency.path() + " cannot be packaged: "
                            + e.getMessage(), e);
                }
            }
            if (result.hadSignatureFiles()) {
                warn("The dependency " + dependency.path() + " is signed; its signature files "
                        + (spec.compression() == Compression.STORED
                            ? "were removed because a repacked jar cannot verify against them"
                            : "were kept but no longer verify, because the jar is nested")
                        + ". The classes it contains are not treated as signed code");
            }
            for (ZipEntryInfo entry : result.entries()) {
                requireSafeName(entry.name(), dependency.path().toString());
            }
            nested.add(new NestedJar(dependency, entryName, target, result, manifest, hasManifest,
                    crc.getValue()));
        }
    }

    private void warnAboutClassPath(Dependency dependency, Manifest manifest) {
        if (manifest == null) {
            return;
        }
        String classPath = attribute(manifest.getMainAttributes(), Attributes.Name.CLASS_PATH);
        if (classPath != null) {
            warn("The dependency " + dependency.path() + " declares Class-Path: " + classPath
                    + ", which a nested jar cannot resolve. Add those jars to the class path instead");
        }
    }

    private String uniqueName(Set<String> taken, String fileName) {
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
                plan.add(PlannedEntry.ofBytes(name, reader.read(entry)));
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
     * @throws IOException if a contributed entry cannot be read back out of the jar that holds it
     */
    private void planMergedServices() throws IOException {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (Map.Entry<String, ApplicationEntry> item : application.entrySet()) {
            String name = item.getKey();
            if (!isMergedServiceName(name)) {
                continue;
            }
            serviceNames.add(name);
            contents.put(name, applicationContent(name, item.getValue()));
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
            PlannedEntry entry;
            if (directory) {
                entry = PlannedEntry.ofDirectory(name);
            } else {
                byte[] content = contents.get(name);
                entry = PlannedEntry.ofBytes(name, content == null ? EMPTY_CONTENT : content);
            }
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
     * The content of an application entry that is merged into the archive root.
     *
     * @param name   the logical name, for the warning
     * @param source the entry
     * @return the content, empty when there is none or when it is too large to duplicate
     * @throws IOException if the file cannot be read
     */
    private byte[] applicationContent(String name, ApplicationEntry source) throws IOException {
        if (source.bytes != null) {
            return source.bytes;
        }
        if (source.size <= 0) {
            return EMPTY_CONTENT;
        }
        if (source.size > MAX_MERGED_SERVICE_SIZE) {
            warn(oversizedMergedService(name, source.size));
            return EMPTY_CONTENT;
        }
        return Files.readAllBytes(source.file);
    }

    /**
     * Adds one dependency's {@code META-INF/micronaut/} entries to the merged set, reading back the content
     * of those that have any.
     *
     * @param jar      the dependency, already written as a nested jar
     * @param contents the merged content so far, keyed by logical name, in class path order
     * @throws IOException if the nested jar cannot be read
     */
    private void collectMergedServices(NestedJar jar, Map<String, byte[]> contents) throws IOException {
        List<String> withContent = null;
        for (ZipEntryInfo entry : jar.result.entries()) {
            String name = entry.name();
            if (entry.directory() || !isMergedServiceName(name)) {
                continue;
            }
            serviceNames.add(name);
            if (entry.uncompressedSize() <= 0) {
                continue;
            }
            if (entry.uncompressedSize() > MAX_MERGED_SERVICE_SIZE) {
                warn(oversizedMergedService(name, entry.uncompressedSize()));
                continue;
            }
            if (withContent == null) {
                withContent = new ArrayList<>();
            }
            withContent.add(name);
        }
        if (withContent == null) {
            return;
        }
        try (ZipReader reader = ZipReader.open(jar.file)) {
            for (String name : withContent) {
                Optional<ZipEntryInfo> found = reader.entry(name);
                if (found.isEmpty()) {
                    continue;
                }
                byte[] content = reader.read(found.get());
                byte[] existing = contents.get(name);
                if (existing == null) {
                    contents.put(name, content);
                } else if (!Arrays.equals(existing, content)) {
                    warn("Two jars contribute a different '" + name + "'. The copy merged into the root of"
                            + " the archive is the first on the class path; the one in "
                            + jar.dependency.path() + " is reachable only through that jar");
                }
            }
        }
    }

    private static String oversizedMergedService(String name, long size) {
        return "The entry '" + name + "' is " + size + " bytes, too large to duplicate into the merged"
                + " Micronaut service directory at the root of the archive; the merged copy is empty and"
                + " the content is reachable only through the jar that carries it";
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
                    ? PlannedEntry.ofFile(IndexFormat.CLASSES_PREFIX + logicalName, source.file, source.size,
                        source.crc32)
                    : PlannedEntry.ofBytes(IndexFormat.CLASSES_PREFIX + logicalName, source.bytes);
            entry.indexEntry = applicationJar.addEntry(logicalName)
                    .sizes(entry.size, entry.size)
                    .crc32(entry.crc32)
                    .dosTime(dosTime);
            plan.add(entry);
        }
    }

    private void planNestedJars() throws IOException {
        for (NestedJar jar : nested) {
            long length = Files.size(jar.file);
            jar.entry = PlannedEntry.ofFile(jar.entryName, jar.file, length, jar.crc32);
            plan.add(jar.entry);
            Attributes main = jar.manifest == null ? null : jar.manifest.getMainAttributes();
            jar.jar = writer.addJar(jar.entryName).coordinates(jar.dependency.coordinates());
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
     * Writes the archive, either for real or to nowhere.
     *
     * <p>The dry pass exists to learn offsets: it pushes the right number of bytes through a
     * {@link ZipWriter} that throws them away, which makes the writer, rather than a second copy of its
     * header arithmetic here, the authority on where everything lands. The real pass then checks every
     * offset against what the dry pass recorded, so a disagreement between the two can never reach an
     * archive.</p>
     *
     * @param archive the file to write, or {@code null} for the dry pass
     * @return the length of the archive
     * @throws IOException if an entry cannot be written or does not land where it was planned to
     */
    private long writeArchive(Path archive) throws IOException {
        boolean dry = archive == null;
        OutputStream out = dry
                ? OutputStream.nullOutputStream()
                : new BufferedOutputStream(Files.newOutputStream(archive), BUFFER_SIZE);
        try (ZipWriter zip = new ZipWriter(out, spec.timestamp())) {
            for (PlannedEntry entry : plan) {
                long localHeaderOffset = zip.offset();
                long dataOffset;
                if (entry.directory) {
                    dataOffset = zip.writeDirectoryEntry(entry.name, dosTime);
                } else if (dry) {
                    try (InputStream zeros = new ZeroInputStream(entry.size)) {
                        dataOffset = zip.writeEntry(entry.name, zeros, entry.size, 0L, dosTime);
                    }
                } else if (entry.bytes != null) {
                    dataOffset = zip.writeEntry(entry.name, entry.bytes, 0, entry.bytes.length, dosTime);
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
        // Inputs and path components may have changed while the archive was assembled. This narrows the
        // accidental race window; see the class documentation for the remaining hostile-race boundary.
        validate();
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
            for (int record = 0; record < total; record += step) {
                verifyEntry(reader, index, record);
            }
        }
    }

    private void verifyEntry(RunnerJarReader reader, Index index, int record) throws IOException {
        if (!index.entryPhysical(record) || index.entryDirectory(record)) {
            return;
        }
        if (index.entryUncompressedSize(record) > Integer.MAX_VALUE - 8) {
            return;
        }
        long expected = index.entryCrc32(record);
        long actual = crc32(reader.read(record));
        if (expected != actual) {
            throw new IOException("The entry '" + index.entryName(record) + "' of " + output
                    + " does not match the CRC-32 the index records: expected "
                    + Long.toHexString(expected) + ", read " + Long.toHexString(actual));
        }
    }

    private void requireSafeName(String name, String origin) throws IOException {
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
            PlannedEntry entry = new PlannedEntry(name, false);
            entry.bytes = content;
            entry.size = content.length;
            entry.crc32 = crc32(content);
            return entry;
        }

        private static PlannedEntry ofFile(String name, Path file, long size, long crc32) {
            PlannedEntry entry = new PlannedEntry(name, false);
            entry.file = file;
            entry.size = size;
            entry.crc32 = crc32;
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
     * One entry of the application layer, held either in memory (it came out of a jar) or as the file it
     * still is on disk.
     */
    private static final class ApplicationEntry {

        private byte[] bytes;
        private Path file;
        private long size;
        private long crc32;

        private static ApplicationEntry ofBytes(byte[] content) {
            ApplicationEntry entry = new ApplicationEntry();
            entry.bytes = content;
            entry.size = content.length;
            entry.crc32 = crc32(content);
            return entry;
        }

        private static ApplicationEntry ofFile(Path file, long size, long crc32) {
            ApplicationEntry entry = new ApplicationEntry();
            entry.file = file;
            entry.size = size;
            entry.crc32 = crc32;
            return entry;
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
     * A stream of as many zero bytes as an entry is long, which is all the dry pass needs: it is measuring
     * offsets, not writing content.
     */
    private static final class ZeroInputStream extends InputStream {

        private long remaining;

        private ZeroInputStream(long length) {
            this.remaining = length;
        }

        @Override
        public int read() {
            if (remaining <= 0) {
                return -1;
            }
            remaining--;
            return 0;
        }

        @Override
        public int read(byte[] destination, int offset, int count) {
            if (remaining <= 0) {
                return -1;
            }
            int produced = (int) Math.min(count, remaining);
            Arrays.fill(destination, offset, offset + produced, (byte) 0);
            remaining -= produced;
            return produced;
        }
    }
}
