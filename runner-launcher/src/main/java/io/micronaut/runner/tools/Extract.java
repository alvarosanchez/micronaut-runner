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
package io.micronaut.runner.tools;

import io.micronaut.runner.ArchiveSource;
import io.micronaut.runner.Index;
import io.micronaut.runner.IndexFormat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * The {@code extract} mode: turns a runner jar into an ordinary {@code Main-Class} plus {@code Class-Path}
 * layout that the JDK's own application class loader runs.
 *
 * <pre>
 * java -Dmicronaut.runner.mode=extract -jar app.jar [--destination &lt;dir&gt;] [--force]
 * </pre>
 *
 * <p>The result is a directory holding one jar per dependency under {@code lib/} and the application's own
 * jar beside them:</p>
 *
 * <pre>
 * app/
 *   app.jar          the application layer, with Main-Class and Class-Path in its manifest
 *   lib/a.jar        the dependencies, byte for byte the nested jars the runner jar carries
 *   lib/b.jar
 * </pre>
 *
 * <h2>Why the mode exists</h2>
 * <p>Nested mode loads application and dependency classes through {@code RunnerClassLoader}, which is a
 * user-defined loader. HotSpot's AOT cache (JEP 483) refuses to <em>link</em> classes loaded by anything
 * but a built-in loader, so the most valuable half of an AOT cache is out of reach of any single-file
 * format, this one included. The extracted layout has no user-defined loader anywhere: every class comes
 * from the JDK's application class loader, which is exactly the shape the AOT cache is built for. Nothing
 * else about the application changes, because the jars under {@code lib/} are the same bytes the runner
 * jar carried.</p>
 *
 * <h2>What is left out of the application jar, and why</h2>
 * <ul>
 *   <li><strong>{@code io/micronaut/runner/generated/**}</strong> - the generated entry stub. It belongs
 *       to the runner format: it implements {@code io.micronaut.runner.Entry} and registers itself with
 *       {@code Launcher}, neither of which exists in the extracted layout. The manifest names the real
 *       application main class instead, so the stub would have no reader and could only fail to link.</li>
 *   <li><strong>the merged {@code META-INF/micronaut/} copy at the archive root</strong> - the packager
 *       writes one merged, zero-length copy of every Micronaut service entry of the application
 *       <em>and of every dependency</em> into the outer root, so that the runner class loader can answer
 *       Micronaut's one directory listing from a single place. Under the JDK's loader that scan is a
 *       {@code getResources} walk over every jar on the class path, which finds each dependency's own
 *       entries inside its own jar under {@code lib/} and the application's own entries inside the
 *       application jar. The merged copy would therefore duplicate all of them, and worse, it would make
 *       the application jar claim service entries that belong to dependencies: replacing one jar in
 *       {@code lib/} would leave the application jar announcing a bean that the dependency no longer
 *       provides. So the merged copy is dropped.
 *       <strong>The application's own {@code META-INF/micronaut/} entries survive</strong>, because they
 *       are physically part of the application layer ({@code MICRONAUT-INF/classes/}) and this mode
 *       copies the application layer, not the archive root.</li>
 *   <li><strong>{@code MICRONAUT-INF/index.bin} and {@code io/micronaut/runner/*.class}</strong> - the
 *       index and the launcher itself, for the same reason: they are the format, not the application.</li>
 * </ul>
 *
 * <h2>The application jar's manifest</h2>
 * <p>It is the application's own manifest (the attributes the packager recorded, per-package sections
 * included) plus {@code Main-Class}, plus a {@code Class-Path} naming every dependency in index order -
 * which is class path order, the order in which every lookup resolves - plus the {@code Add-Opens},
 * {@code Add-Exports} and {@code Enable-Native-Access} attributes the runner jar carried, plus
 * {@code Multi-Release} when the application layer is multi-release. One consequence worth knowing: a
 * dependency's own {@code Class-Path} attribute, which nested mode ignores, becomes active again here,
 * because the JDK's loader honours it.</p>
 *
 * <h2>Safety</h2>
 * <p>Everything is written into a temporary directory beside the destination and renamed into place, so an
 * interrupted extraction leaves no half-written tree. A destination that already has anything in it is
 * refused unless {@code --force} is given, a destination that contains the runner jar itself is always
 * refused, and every name is checked to resolve inside the destination before anything is written. All
 * timestamps are fixed, so extracting one archive twice produces the same tree and an AOT training run
 * matches the production copy.</p>
 *
 * <p>This class is loaded only when the mode selects it, so it is written in ordinary Java: the rules that
 * keep {@code io.micronaut.runner} free of lambdas, streams and {@code String.format} do not apply to
 * {@code io.micronaut.runner.tools}.</p>
 *
 * @since 1.0
 */
public final class Extract {

    /** Option naming the directory to extract into. */
    public static final String OPTION_DESTINATION = "--destination";

    /** Option allowing a destination that is not empty to be replaced. */
    public static final String OPTION_FORCE = "--force";

    /** Directory, relative to the destination, the dependencies are written to. */
    public static final String LIBRARY_DIRECTORY = "lib";

    /** How to call the mode, appended to every message a user can act on. */
    private static final String USAGE = "Usage: java -Dmicronaut.runner.mode=extract -jar <archive> ["
            + OPTION_DESTINATION + " <directory>] [" + OPTION_FORCE + "]";

    /** The manifest entry name, as the jar specification spells it. */
    private static final String MANIFEST_NAME = "META-INF/MANIFEST.MF";

    /** Entry name prefix of the generated entry stub, which belongs to the format and not to the app. */
    private static final String GENERATED_PREFIX = "io/micronaut/runner/generated/";

    /** Attribute marking a multi-release jar. */
    private static final String MULTI_RELEASE = "Multi-Release";

    /** Attribute sealing every package of a jar, or one package in a {@code Name:} section. */
    private static final String SEALED = "Sealed";

    /** The NUL character, which no entry name may contain. */
    private static final char NUL = 0;

    /**
     * Attributes of the runner jar's manifest that describe the runner jar rather than the application,
     * and are therefore not copied. Compared without regard to case, as manifest names are.
     */
    private static final String[] NOT_COPIED = {
        "Manifest-Version", "Main-Class", "Class-Path", MULTI_RELEASE,
        IndexFormat.ATTR_FORMAT, IndexFormat.ATTR_VERSION, IndexFormat.ATTR_START_CLASS
    };

    /**
     * The timestamp every extracted entry and file gets: the MS-DOS epoch plus a day, which is the
     * packager's own default. It is written as a local date and time, so that the DOS fields of the jar do
     * not depend on the time zone the extraction ran in.
     */
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 2, 1, 0, 0, 0);

    /** The same instant, for the modification times of the files on disk. */
    private static final FileTime FILE_TIME = FileTime.from(Instant.parse("1980-02-01T00:00:00Z"));

    private Extract() {
    }

    /**
     * Extracts the runner jar the launcher was started from.
     *
     * @param args    the program arguments: {@value #OPTION_DESTINATION} and {@value #OPTION_FORCE}
     * @param archive the runner jar
     * @param index   the index read from it
     * @param source  the archive's bytes, from which the nested jars are copied
     * @throws IOException if the arguments are not understood, the destination cannot be used, the
     *                     archive disagrees with its index, or a file cannot be written
     */
    public static void run(String[] args, File archive, Index index, ArchiveSource source)
            throws IOException {
        Path archivePath = archive.getAbsoluteFile().toPath().normalize();
        Options options = parse(args, archivePath);
        Path destination = options.destination();
        if (archivePath.startsWith(destination)) {
            throw new IOException("The destination " + destination + " holds the runner jar itself;"
                    + " extract into a directory that does not contain " + archivePath + ". " + USAGE);
        }
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IOException("The destination " + destination + " has no parent directory. " + USAGE);
        }
        checkDestination(destination, options.force());
        Files.createDirectories(parent);
        Path work = Files.createTempDirectory(parent, ".micronaut-runner-extract-");
        boolean complete = false;
        try {
            List<Library> libraries = writeLibraries(work, index, source);
            String applicationJar = applicationJarName(archivePath);
            int entries;
            try (JarFile outer = new JarFile(archive, false)) {
                Manifest manifest = manifest(outer, index, libraries);
                entries = writeApplicationJar(resolveWithin(work, applicationJar), outer, manifest);
            }
            stamp(work);
            moveIntoPlace(work, destination);
            complete = true;
            report(archivePath, destination, applicationJar, entries, index, libraries);
        } finally {
            if (!complete) {
                deleteRecursively(work);
            }
        }
    }

    /**
     * Reads the command line.
     *
     * @param args    the program arguments
     * @param archive the runner jar, which names the default destination
     * @return the options
     * @throws IOException if an argument is not one this mode understands
     */
    private static Options parse(String[] args, Path archive) throws IOException {
        Path destination = null;
        boolean force = false;
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (OPTION_FORCE.equals(argument)) {
                force = true;
            } else if (OPTION_DESTINATION.equals(argument)) {
                if (i + 1 == args.length) {
                    throw new IOException(OPTION_DESTINATION + " needs a directory. " + USAGE);
                }
                i++;
                destination = directory(args[i]);
            } else if (argument.startsWith(OPTION_DESTINATION + "=")) {
                destination = directory(argument.substring(OPTION_DESTINATION.length() + 1));
            } else {
                throw new IOException("Unknown extract option '" + argument + "'. " + USAGE);
            }
        }
        return new Options(destination == null ? defaultDestination(archive) : destination, force);
    }

    /**
     * Turns a command line value into an absolute directory path.
     *
     * @param value the value given on the command line
     * @return the path
     * @throws IOException if the value is empty or is not a path on this platform
     */
    private static Path directory(String value) throws IOException {
        if (value.isEmpty()) {
            throw new IOException(OPTION_DESTINATION + " needs a directory. " + USAGE);
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IOException("'" + value + "' is not a usable directory name: " + e.getMessage(), e);
        }
    }

    /**
     * The destination when none is given: a directory named after the archive, beside it.
     *
     * @param archive the runner jar
     * @return the directory
     * @throws IOException if the archive has no parent directory
     */
    private static Path defaultDestination(Path archive) throws IOException {
        Path parent = archive.getParent();
        Path name = archive.getFileName();
        if (parent == null || name == null) {
            throw new IOException("Cannot name a destination beside " + archive + ". " + USAGE);
        }
        return parent.resolve(baseName(name.toString()));
    }

    /**
     * The name of the application jar inside the destination: the archive's name with any extension
     * replaced by {@code .jar}.
     *
     * @param archive the runner jar
     * @return the file name
     * @throws IOException if the archive has no file name
     */
    private static String applicationJarName(Path archive) throws IOException {
        Path name = archive.getFileName();
        if (name == null) {
            throw new IOException("The runner jar " + archive + " has no file name");
        }
        return baseName(name.toString()) + ".jar";
    }

    /**
     * A file name without its extension, with a suffix added when there is no extension to drop, so that
     * the default destination can never be the archive itself.
     *
     * @param fileName the file name
     * @return the base name
     */
    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            return fileName.substring(0, dot);
        }
        return fileName + "-extracted";
    }

    /**
     * Checks that the destination can be written to.
     *
     * @param destination the destination
     * @param force       whether the user allowed a destination that is not empty to be replaced
     * @throws IOException if the destination is a file, or is a directory with anything in it and
     *                     {@value #OPTION_FORCE} was not given
     */
    private static void checkDestination(Path destination, boolean force) throws IOException {
        if (!Files.exists(destination)) {
            return;
        }
        if (!Files.isDirectory(destination)) {
            throw new IOException("The destination " + destination + " exists and is not a directory");
        }
        if (force) {
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(destination)) {
            if (children.iterator().hasNext()) {
                throw new IOException("The destination " + destination + " is not empty. Pass "
                        + OPTION_FORCE + " to replace it, or " + OPTION_DESTINATION
                        + " to extract somewhere else.");
            }
        }
    }

    /**
     * Writes every dependency to {@code lib/}, byte for byte as the runner jar stores it.
     *
     * @param work   the directory being built, which is renamed onto the destination
     * @param index  the index
     * @param source the archive's bytes
     * @return the dependencies, in index order, which is class path order
     * @throws IOException if a jar name would escape the destination, two jars share a name, the archive
     *                     no longer agrees with its index, or a file cannot be written
     */
    private static List<Library> writeLibraries(Path work, Index index, ArchiveSource source)
            throws IOException {
        List<Library> libraries = new ArrayList<>();
        int jars = index.jarCount();
        if (jars <= 1) {
            return libraries;
        }
        Path directory = work.resolve(LIBRARY_DIRECTORY);
        Files.createDirectories(directory);
        Set<String> taken = new HashSet<>();
        for (int jarId = 1; jarId < jars; jarId++) {
            // Throws when the archive was rebuilt underneath the index, before anything has been written.
            index.validateJar(jarId);
            String fileName = fileName(index.jarName(jarId), jarId);
            if (!taken.add(fileName.toLowerCase(Locale.ROOT))) {
                throw new IOException("Two dependencies of this runner jar are both named '" + fileName
                        + "', so it cannot be extracted into one directory. " + Index.REBUILD_MESSAGE);
            }
            Path target = resolveWithin(directory, fileName);
            long length = index.jarDataLength(jarId);
            try (InputStream in = source.stream(index.jarDataOffset(jarId), length, length,
                        IndexFormat.METHOD_STORED);
                    OutputStream out = Files.newOutputStream(target)) {
                in.transferTo(out);
            }
            libraries.add(new Library(LIBRARY_DIRECTORY + "/" + fileName, length));
        }
        return libraries;
    }

    /**
     * Writes the application jar: the application layer at the root, manifest first.
     *
     * @param target   where to write it
     * @param outer    the runner jar, read as an ordinary jar
     * @param manifest the manifest to give it
     * @return the number of entries written, not counting the manifest
     * @throws IOException if an entry name would escape the destination or the jar cannot be written
     */
    private static int writeApplicationJar(Path target, JarFile outer, Manifest manifest)
            throws IOException {
        int written = 0;
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
            out.putNextEntry(entry(MANIFEST_NAME));
            manifest.write(out);
            out.closeEntry();
            // Central directory order, which is the order the packager planned and therefore stable.
            Enumeration<JarEntry> entries = outer.entries();
            while (entries.hasMoreElements()) {
                JarEntry source = entries.nextElement();
                String name = source.getName();
                if (source.isDirectory() || !name.startsWith(IndexFormat.CLASSES_PREFIX)) {
                    continue;
                }
                String logical = name.substring(IndexFormat.CLASSES_PREFIX.length());
                if (logical.isEmpty() || MANIFEST_NAME.equals(logical)
                        || logical.startsWith(GENERATED_PREFIX)) {
                    continue;
                }
                requireSafeName(logical);
                out.putNextEntry(entry(logical));
                try (InputStream in = outer.getInputStream(source)) {
                    in.transferTo(out);
                }
                out.closeEntry();
                written++;
            }
        }
        return written;
    }

    /**
     * Builds the application jar's manifest.
     *
     * <p>The application's own attributes come first, from its manifest inside the application layer when
     * it has one and from the runner jar's manifest, into which the packager copied the
     * {@code Implementation-*} and {@code Specification-*} attributes and everything the build configured,
     * {@code Add-Opens}, {@code Add-Exports} and {@code Enable-Native-Access} among them. The per-package
     * sections are rebuilt from the index, which is where the packager recorded them, so that sealing and
     * per-package versions survive into the extracted layout. Only then are the attributes that describe
     * the new layout written, so that nothing the archive carried can override them.</p>
     *
     * @param outer     the runner jar, read as an ordinary jar
     * @param index     the index
     * @param libraries the dependencies, in class path order
     * @return the manifest
     * @throws IOException if a manifest cannot be read or the index names no main class
     */
    private static Manifest manifest(JarFile outer, Index index, List<Library> libraries)
            throws IOException {
        Manifest result = new Manifest();
        Attributes main = result.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        Manifest own = applicationManifest(outer);
        if (own != null) {
            copy(own.getMainAttributes(), main);
            result.getEntries().putAll(own.getEntries());
        }
        Manifest runner = outer.getManifest();
        if (runner != null) {
            copy(runner.getMainAttributes(), main);
        }
        if (index.jarSealedByDefault(IndexFormat.APPLICATION_JAR_ID)) {
            main.putValue(SEALED, "true");
        }
        sections(index, result);
        String start = index.startClass();
        if (start == null || start.isEmpty()) {
            throw new IOException("The runner jar names no application main class, so the extracted jar"
                    + " would not start. " + Index.REBUILD_MESSAGE);
        }
        main.put(Attributes.Name.MAIN_CLASS, start);
        if (!libraries.isEmpty()) {
            StringBuilder classPath = new StringBuilder(libraries.size() * 24);
            for (Library library : libraries) {
                if (classPath.length() > 0) {
                    classPath.append(' ');
                }
                classPath.append(library.path());
            }
            // java.util.jar.Manifest wraps the value at 72 bytes and continues it with a leading space.
            main.put(Attributes.Name.CLASS_PATH, classPath.toString());
        }
        if (index.applicationMultiRelease()) {
            main.putValue(MULTI_RELEASE, "true");
        }
        return result;
    }

    /**
     * The manifest the application layer carries itself, when the application output had one.
     *
     * @param outer the runner jar, read as an ordinary jar
     * @return the manifest, or {@code null} when the application layer has none
     * @throws IOException if it cannot be read
     */
    private static Manifest applicationManifest(JarFile outer) throws IOException {
        JarEntry entry = outer.getJarEntry(IndexFormat.CLASSES_PREFIX + MANIFEST_NAME);
        if (entry == null) {
            return null;
        }
        try (InputStream in = outer.getInputStream(entry)) {
            return new Manifest(in);
        }
    }

    /**
     * Copies the attributes that describe the application, leaving behind the ones that describe the
     * runner jar and the ones this mode writes itself.
     *
     * @param from the attributes to read
     * @param to   the attributes to write
     */
    private static void copy(Attributes from, Attributes to) {
        for (Object key : from.keySet()) {
            String name = key.toString();
            if (!skipped(name)) {
                to.putValue(name, from.getValue(name));
            }
        }
    }

    /**
     * Whether an attribute describes the runner jar rather than the application.
     *
     * @param name the attribute name
     * @return {@code true} when it must not be copied
     */
    private static boolean skipped(String name) {
        for (String candidate : NOT_COPIED) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuilds the {@code Name:} sections of the application layer from the index, where the packager
     * recorded the per-package attributes and the sealing of the application's own manifest.
     *
     * @param index    the index
     * @param manifest the manifest being built
     */
    private static void sections(Index index, Manifest manifest) {
        int first = index.jarFirstPackage(IndexFormat.APPLICATION_JAR_ID);
        int count = index.jarPackageCount(IndexFormat.APPLICATION_JAR_ID);
        for (int record = first; record < first + count; record++) {
            String name = index.packageName(record);
            if (name == null || name.isEmpty()) {
                continue;
            }
            String section = name.replace('.', '/') + "/";
            Attributes attributes = manifest.getEntries().get(section);
            if (attributes == null) {
                attributes = new Attributes();
                manifest.getEntries().put(section, attributes);
            }
            put(attributes, Attributes.Name.SPECIFICATION_TITLE, index.packageSpecTitle(record));
            put(attributes, Attributes.Name.SPECIFICATION_VERSION, index.packageSpecVersion(record));
            put(attributes, Attributes.Name.SPECIFICATION_VENDOR, index.packageSpecVendor(record));
            put(attributes, Attributes.Name.IMPLEMENTATION_TITLE, index.packageImplTitle(record));
            put(attributes, Attributes.Name.IMPLEMENTATION_VERSION, index.packageImplVersion(record));
            put(attributes, Attributes.Name.IMPLEMENTATION_VENDOR, index.packageImplVendor(record));
            if (index.packageSealedSpecified(record)) {
                attributes.putValue(SEALED, index.packageSealedValue(record) ? "true" : "false");
            }
        }
    }

    /**
     * Sets an attribute, unless the index recorded no value for it.
     *
     * @param attributes the attributes to write to
     * @param name       the attribute
     * @param value      the value, or {@code null} to leave the attribute alone
     */
    private static void put(Attributes attributes, Attributes.Name name, String value) {
        if (value != null && !value.isEmpty()) {
            attributes.put(name, value);
        }
    }

    /**
     * A jar entry with the fixed timestamp, so that two extractions of one archive agree.
     *
     * @param name the entry name
     * @return the entry
     */
    private static ZipEntry entry(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(ENTRY_TIME);
        return entry;
    }

    /**
     * The file name of a nested jar, checked to be a plain name.
     *
     * @param name  the jar's name in the index, normally {@code MICRONAUT-INF/lib/<file>.jar}
     * @param jarId the jar, for the error message
     * @return the file name
     * @throws IOException if the index records no usable name for the jar
     */
    private static String fileName(String name, int jarId) throws IOException {
        if (name == null || name.isEmpty() || name.endsWith("/")) {
            throw new IOException("The index records no file name for jar " + jarId + ". "
                    + Index.REBUILD_MESSAGE);
        }
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    /**
     * Resolves a name inside the directory being built and refuses anything that would land outside it.
     *
     * @param root the directory the name must stay inside
     * @param name the name
     * @return the path to write
     * @throws IOException if the name is unsafe or resolves outside {@code root}
     */
    private static Path resolveWithin(Path root, String name) throws IOException {
        requireSafeName(name);
        Path resolved;
        try {
            resolved = root.resolve(name).normalize();
        } catch (InvalidPathException e) {
            throw new IOException("'" + name + "' cannot be written on this platform: " + e.getMessage(), e);
        }
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IOException("'" + name + "' would be written outside the destination");
        }
        Path parent = resolved.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return resolved;
    }

    /**
     * Refuses a name that could escape the directory it is extracted into.
     *
     * <p>The rules are the packager's: no empty name, no absolute name, no drive letter, no backslash or
     * NUL character, no empty segment and no {@code .} or {@code ..} segment. They are checked again here
     * because an archive can be edited after it was packaged.</p>
     *
     * @param name the name
     * @throws IOException if the name is not safe to extract
     */
    private static void requireSafeName(String name) throws IOException {
        boolean safe = !name.isEmpty() && name.charAt(0) != '/' && name.indexOf('\\') < 0
                && name.indexOf(NUL) < 0 && !(name.length() > 1 && name.charAt(1) == ':');
        int start = 0;
        while (safe && start < name.length()) {
            int slash = name.indexOf('/', start);
            int end = slash < 0 ? name.length() : slash;
            String segment = name.substring(start, end);
            // A single trailing slash marks a directory and is the only empty segment allowed.
            if (segment.isEmpty() && end != name.length()) {
                safe = false;
            } else if (".".equals(segment) || "..".equals(segment)) {
                safe = false;
            }
            start = end + 1;
        }
        if (!safe) {
            throw new IOException("The entry name '" + name + "' cannot be extracted safely: it would"
                    + " resolve outside the destination");
        }
    }

    /**
     * Gives every extracted file and directory the fixed timestamp, deepest first.
     *
     * @param work the directory being built
     * @throws IOException if a timestamp cannot be set
     */
    private static void stamp(Path work) throws IOException {
        Files.walkFileTree(work, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.setLastModifiedTime(file, FILE_TIME);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.setLastModifiedTime(directory, FILE_TIME);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Renames the finished tree onto the destination, replacing whatever was there.
     *
     * @param work        the finished tree
     * @param destination where it belongs
     * @throws IOException if the destination cannot be replaced
     */
    private static void moveIntoPlace(Path work, Path destination) throws IOException {
        if (Files.exists(destination)) {
            deleteRecursively(destination);
        }
        try {
            Files.move(work, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(work, destination);
        }
    }

    /**
     * Deletes a directory tree, ignoring what is gone already.
     *
     * @param directory the tree
     * @throws IOException if a file cannot be deleted
     */
    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path visited, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.deleteIfExists(visited);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Prints what was written and how to run it.
     *
     * @param archive        the runner jar
     * @param destination    the directory it was extracted into
     * @param applicationJar the name of the application jar
     * @param entries        how many entries it holds
     * @param index          the index
     * @param libraries      the dependencies that were written
     */
    private static void report(Path archive, Path destination, String applicationJar, int entries,
            Index index, List<Library> libraries) {
        PrintStream out = System.out;
        out.println("Extracted " + archive + " into " + destination);
        out.println("  " + applicationJar + " (" + entries + (entries == 1 ? " entry" : " entries")
                + ", Main-Class " + index.startClass() + ")");
        long bytes = 0;
        for (Library library : libraries) {
            out.println("  " + library.path() + " (" + library.bytes() + " bytes)");
            bytes += library.bytes();
        }
        out.println("  " + libraries.size() + (libraries.size() == 1 ? " dependency, " : " dependencies, ")
                + bytes + " bytes");
        out.println("Run it with: java -jar " + destination.resolve(applicationJar));
    }

    /**
     * What the command line asked for.
     *
     * @param destination the directory to extract into, absolute
     * @param force       whether a destination that is not empty may be replaced
     */
    private record Options(Path destination, boolean force) {
    }

    /**
     * One extracted dependency.
     *
     * @param path  its path relative to the destination, as the {@code Class-Path} attribute spells it
     * @param bytes its length
     */
    private record Library(String path, long bytes) {
    }
}
