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
import io.micronaut.runner.TestArchiveBuilder;
import io.micronaut.runner.TestIndexBuilder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the three tool modes against a runner archive built the way the packager builds one: an
 * application layer under {@code MICRONAUT-INF/classes/}, two nested dependencies, a merged
 * {@code META-INF/micronaut/} directory at the archive root, a generated entry stub, a multi-release
 * dependency and a per-package manifest section recorded in the index.
 *
 * <p>The archive is assembled with the launcher's own {@code TestArchiveBuilder} and
 * {@code TestIndexBuilder} rather than with the packager, because {@code runner-build} is not on this
 * module's test class path.</p>
 *
 * <p>The last test is the one that matters most: it extracts the archive and starts the result with
 * {@code java -jar} in a forked JVM, which is the only way to observe the claim the mode is built on -
 * that the extracted layout runs entirely on the JDK's own application class loader, the loader whose
 * classes an AOT cache can link. It lives here rather than in {@code runner-build}'s end-to-end test
 * because the tools are launcher classes and the extracted jar needs nothing from the packager.</p>
 */
class ToolsTest {

    @TempDir
    static Path workspace;

    /** The application: it reports what it can see, so that a failure names the promise that broke. */
    private static final String APPLICATION_SOURCE = """
            package com.example;

            import java.io.InputStream;
            import java.nio.charset.StandardCharsets;

            public final class App {

                public static void main(String[] args) throws Exception {
                    ClassLoader loader = App.class.getClassLoader();
                    System.out.println("LOADER " + loader.getClass().getName());
                    System.out.println("BUILTIN " + (loader == ClassLoader.getSystemClassLoader()));
                    System.out.println("ARGS " + String.join("|", args));
                    System.out.println("DEP " + org.depone.DepOne.hello());
                    System.out.println("APPLICATION-SERVICE " + (loader.getResource(
                            "META-INF/micronaut/com.example.Svc/com.example.App") != null));
                    System.out.println("DEPENDENCY-SERVICE " + (loader.getResource(
                            "META-INF/micronaut/io.other.Svc/org.depone.DepOne") != null));
                    System.out.println("STUB " + (loader.getResource(
                            "io/micronaut/runner/generated/AppEntry.class") != null));
                    System.out.println("INDEX " + (loader.getResource("MICRONAUT-INF/index.bin") != null));
                    try (InputStream in = loader.getResourceAsStream("application.yml")) {
                        System.out.println("CONFIG "
                                + new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
                    }
                    System.out.println("PACKAGE " + App.class.getPackage().getImplementationVersion());
                    System.out.println("RESULT OK");
                }
            }
            """;

    /** The one class the first dependency contributes. */
    private static final String DEPENDENCY_SOURCE = """
            package org.depone;

            public final class DepOne {
                public static String hello() {
                    return "dep-one";
                }
            }
            """;

    private static final String MAIN_CLASS = "com.example.App";
    private static final String STUB_CLASS = "io.micronaut.runner.generated.AppEntry";
    private static final String LAUNCHER_VERSION = "1.0.0-TEST";
    private static final String DEPENDENCY_ONE = IndexFormat.LIB_PREFIX + "dep-one.jar";
    private static final String DEPENDENCY_TWO = IndexFormat.LIB_PREFIX + "dep-two.jar";
    private static final String ENCODED_DEPENDENCY_ONE =
            IndexFormat.LIB_PREFIX + "dep space#?.jar";
    private static final String ENCODED_DEPENDENCY_TWO =
            IndexFormat.LIB_PREFIX + "dep%20name-雪-with-a-very-long-dependency-name.jar";
    private static final String APPLICATION_SERVICE =
            "META-INF/micronaut/com.example.Svc/com.example.App";
    private static final String DEPENDENCY_SERVICE =
            "META-INF/micronaut/io.other.Svc/org.depone.DepOne";
    private static final String CONFIGURATION = "application.yml";
    private static final String STUB_ENTRY = "io/micronaut/runner/generated/AppEntry.class";
    private static final long DOS_TIME = 0x00210000L;
    private static final String TRANSFORMS_COUNTS = DEPENDENCY_ONE + "\tstripLocalVariables\t1\t0\t0\t120";

    private static final byte[] EMPTY = new byte[0];
    private static final byte[] CONFIGURATION_BYTES = bytes("greeting: hello\n");
    private static final byte[] STUB_BYTES = bytes("not a real class file");
    private static final byte[] BASE_DATA = bytes("base");
    private static final byte[] VERSIONED_DATA = bytes("v21");
    private static final byte[] RUNNER_MANIFEST = bytes("Manifest-Version: 1.0\r\n"
            + "Main-Class: io.micronaut.runner.Launcher\r\n"
            + "Micronaut-Runner-Format: 1\r\n"
            + "Micronaut-Runner-Version: " + LAUNCHER_VERSION + "\r\n"
            + "Micronaut-Runner-Start-Class: " + MAIN_CLASS + "\r\n"
            + "Implementation-Title: Demo Application\r\n"
            + "Implementation-Version: 1.2.3\r\n"
            + "Add-Opens: java.base/java.lang\r\n"
            + "Add-Exports: java.base/jdk.internal.misc\r\n"
            + "Enable-Native-Access: ALL-UNNAMED\r\n"
            + "\r\n");
    private static final byte[] APPLICATION_MANIFEST = bytes("Manifest-Version: 1.0\r\n"
            + "Implementation-Title: Demo Application\r\n"
            + "Implementation-Vendor: Example\r\n"
            + "\r\n");
    private static final byte[] DEPENDENCY_ONE_MANIFEST = bytes("Manifest-Version: 1.0\r\n"
            + "Implementation-Title: Dependency One\r\n"
            + "Implementation-Version: 1.0.0\r\n"
            + "\r\n");
    private static final byte[] DEPENDENCY_TWO_MANIFEST = bytes("Manifest-Version: 1.0\r\n"
            + "Multi-Release: true\r\n"
            + "Implementation-Title: Dependency Two\r\n"
            + "Implementation-Version: 2.0.0\r\n"
            + "\r\n");

    private static byte[] applicationClass;
    private static byte[] dependencyClass;
    private static byte[] dependencyOneJar;
    private static byte[] dependencyTwoJar;
    private static File archive;
    private static ArchiveSource source;
    private static Index index;

    @BeforeAll
    static void packageTheApplication() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "this JDK has no java compiler");
        Path classes = workspace.resolve("classes");
        compile(compiler, workspace.resolve("sources/dep"), classes, null,
                Map.of("org/depone/DepOne.java", DEPENDENCY_SOURCE));
        dependencyClass = Files.readAllBytes(classes.resolve("org/depone/DepOne.class"));
        Path applicationClasses = workspace.resolve("app-classes");
        compile(compiler, workspace.resolve("sources/app"), applicationClasses, classes,
                Map.of("com/example/App.java", APPLICATION_SOURCE));
        applicationClass = Files.readAllBytes(applicationClasses.resolve("com/example/App.class"));

        archive = writeArchive(workspace.resolve("out/app.jar"), Flavour.PLAIN);
        source = ArchiveSource.open(archive);
        index = Index.open(source);
    }

    @AfterAll
    static void closeTheArchive() {
        if (source != null) {
            source.close();
            source = null;
        }
    }

    @Test
    void inspectPrintsTheHeaderAndOneLinePerJar() throws Throwable {
        String output = capture(() -> Inspect.run(new String[0], archive, index, source));

        assertEquals("1", header(output, "Format version"), output);
        assertEquals(LAUNCHER_VERSION, header(output, "Packaged by"), output);
        assertEquals(MAIN_CLASS, header(output, "Main class"), output);
        assertEquals(STUB_CLASS, header(output, "Entry stub"), output);
        assertEquals("3", header(output, "Jars"), output);
        assertEquals(Integer.toString(index.entryCount()), header(output, "Index records"), output);
        assertEquals(Integer.toString(applicationEntries()), header(output, "Application entries"),
                output);
        assertEquals(Integer.toString(index.hashSlots()), header(output, "Hash slots"), output);
        assertEquals(Integer.toString(index.maxProbe()), header(output, "Maximum probe"), output);
        assertEquals(archive.length() + " bytes", header(output, "Outer file length"), output);
        assertEquals("none", header(output, "Build transforms"), output);

        String[] application = columns(line(output, IndexFormat.CLASSES_PREFIX));
        assertEquals("0", application[0], output);
        assertEquals("application layer", application[5], output);
        String[] first = columns(line(output, DEPENDENCY_ONE));
        assertEquals("1", first[0], output);
        assertEquals("org.example:dep-one:1.0.0", first[2], output);
        assertEquals("sealed by default", first[5], output);
        String[] second = columns(line(output, DEPENDENCY_TWO));
        assertEquals("2", second[0], output);
        assertEquals("org.example:dep-two:2.0.0", second[2], output);
        assertEquals("multi-release, signed original", second[5], output);
        // Three entries, plus the alias of the versioned one and the directories derived from the names.
        assertEquals("3", second[3], output);
        assertEquals(Integer.toString(index.jarEntryCount(2)), second[4], output);
    }

    @Test
    void inspectPrintsTheBuildTransformsWhenTheArchiveRecordsThem() throws Throwable {
        File transformed = writeArchive(workspace.resolve("transforms/app.jar"), Flavour.TRANSFORMS);
        try (ArchiveSource transformedSource = ArchiveSource.open(transformed)) {
            Index transformedIndex = Index.open(transformedSource);
            String output = capture(() -> Inspect.run(new String[0], transformed, transformedIndex,
                    transformedSource));

            // Any line break: System.out ends each line with the platform's separator.
            List<String> lines = List.of(output.split("\\R"));
            int heading = lines.indexOf("Build transforms");
            assertTrue(heading >= 0, output);
            assertEquals("  Micronaut-Runner-Version\t" + LAUNCHER_VERSION, lines.get(heading + 1), output);
            assertEquals("  " + TRANSFORMS_COUNTS, lines.get(heading + 2), output);
            assertEquals(null, header(output, "Build transforms"), "no \"none\" when the entry is present");
            assertEquals(MAIN_CLASS, header(output, "Main class"), output);
        }
    }

    @Test
    void inspectRefusesArgumentsItDoesNotUnderstand() {
        IOException failure = assertThrows(IOException.class,
                () -> Inspect.run(new String[] {"--why"}, archive, index, source));
        assertTrue(failure.getMessage().contains("takes no arguments"), failure.getMessage());
    }

    @Test
    void listNamesEveryRecordAndTheJarItComesFrom() throws Throwable {
        String output = capture(() -> ListEntries.run(new String[0], archive, index, source));

        assertTrue(line(output, "com/example/App.class").contains("(application)"), output);
        assertTrue(line(output, CONFIGURATION).contains("(application)"), output);
        assertTrue(line(output, "org/depone/DepOne.class").contains("dep-one.jar"), output);
        // The application's own service entry loses to the merged copy in the archive root, and the
        // listing says so: that is the whole point of the mode.
        List<String> service = lines(output, APPLICATION_SERVICE);
        assertEquals(2, service.size(), output);
        assertEquals("", notes(service.get(0)), output);
        assertTrue(notes(service.get(1)).contains("shadowed by (application)"), output);
        // A directory nobody packaged, which the index synthesised so that a lookup of it succeeds.
        assertTrue(line(output, "com/example/").contains("synthesised directory"), output);
        assertTrue(output.contains(index.entryCount() + " records in " + archive.getName()), output);
    }

    @Test
    void listMarksVersionedAliasesAndFiltersByPrefix() throws Throwable {
        String all = capture(() -> ListEntries.run(new String[0], archive, index, source));
        // Two records answer to data.txt: the base entry, in central directory order, and the alias of
        // the versioned copy, which the index puts in front of it.
        List<String> both = lines(all, "data.txt");
        assertEquals(2, both.size(), all);
        String base = both.get(0);
        String alias = both.get(1);
        assertTrue(alias.contains("dep-two.jar"), alias);
        assertTrue(notes(alias).contains("versioned 21"), alias);
        assertTrue(notes(alias).contains("aliases META-INF/versions/21/data.txt"), alias);
        // On this runtime the alias wins, so the base entry is reported as shadowed by it.
        assertTrue(notes(base).contains("shadowed by dep-two.jar (versioned 21)"), base);
        assertFalse(notes(alias).contains("shadowed"), alias);

        String filtered = capture(() -> ListEntries.run(new String[] {"com/example/"}, archive, index,
                source));
        for (String printed : filtered.split("\n")) {
            String trimmed = printed.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("NAME")
                    || trimmed.endsWith(archive.getName())) {
                continue;
            }
            assertTrue(trimmed.startsWith("com/example/"), filtered);
        }
        assertTrue(filtered.contains("2 of " + index.entryCount() + " records start with"), filtered);
        assertTrue(filtered.contains("com/example/App.class"), filtered);
        assertFalse(filtered.contains(CONFIGURATION), filtered);
    }

    @Test
    void listRefusesMoreThanOnePrefix() {
        IOException failure = assertThrows(IOException.class,
                () -> ListEntries.run(new String[] {"a", "b"}, archive, index, source));
        assertTrue(failure.getMessage().contains("at most one argument"), failure.getMessage());
    }

    @Test
    void extractWritesTheDependenciesByteForByteAndTheApplicationLayerAtTheRoot() throws Throwable {
        Path destination = workspace.resolve("extract/plain");

        String output = capture(() -> Extract.run(
                new String[] {Extract.OPTION_DESTINATION, destination.toString()}, archive, index,
                source));

        assertTrue(Files.isDirectory(destination), output);
        assertArrayEquals(dependencyOneJar, Files.readAllBytes(destination.resolve("lib/dep-one.jar")));
        assertArrayEquals(dependencyTwoJar, Files.readAllBytes(destination.resolve("lib/dep-two.jar")));
        Path application = destination.resolve("app.jar");
        assertTrue(Files.isRegularFile(application), output);
        try (JarFile jar = new JarFile(application.toFile())) {
            assertNotNull(jar.getEntry("com/example/App.class"));
            assertNotNull(jar.getEntry(CONFIGURATION));
            assertNotNull(jar.getEntry("META-INF/MANIFEST.MF"));
            // The application's own Micronaut metadata is part of the application layer and survives.
            assertNotNull(jar.getEntry(APPLICATION_SERVICE));
            // The merged root copy is the runner format's own index of every jar's service entries. In
            // the extracted layout the JDK's loader finds the dependency's copy inside lib/dep-one.jar,
            // so the merged copy would only duplicate it - and would make the application jar claim an
            // entry that belongs to a dependency.
            assertNull(jar.getEntry(DEPENDENCY_SERVICE));
            // The entry stub implements an interface that does not exist outside the runner format.
            assertNull(jar.getEntry(STUB_ENTRY));
            assertNull(jar.getEntry(IndexFormat.INDEX_ENTRY_NAME));
            assertNull(jar.getEntry(IndexFormat.CLASSES_PREFIX));
            assertArrayEquals(CONFIGURATION_BYTES, read(jar, CONFIGURATION));
            assertArrayEquals(applicationClass, read(jar, "com/example/App.class"));
            // One fixed timestamp everywhere, so that two extractions of one archive agree.
            long time = jar.getEntry("com/example/App.class").getTime();
            assertEquals(time, jar.getEntry(CONFIGURATION).getTime());
        }
        assertTrue(output.contains("Extracted "), output);
        assertTrue(output.contains("app.jar (3 entries, Main-Class " + MAIN_CLASS + ")"), output);
        assertTrue(output.contains("lib/dep-one.jar (" + dependencyOneJar.length + " bytes)"), output);
        assertTrue(output.contains("2 dependencies"), output);
    }

    @Test
    void theExtractedManifestNamesTheMainClassAndTheDependenciesInIndexOrder() throws Throwable {
        Path destination = workspace.resolve("extract/manifest");

        capture(() -> Extract.run(new String[] {Extract.OPTION_DESTINATION + "=" + destination},
                archive, index, source));

        Attributes main = manifestOf(destination.resolve("app.jar")).getMainAttributes();
        assertEquals(MAIN_CLASS, main.getValue(Attributes.Name.MAIN_CLASS));
        assertEquals("lib/dep-one.jar lib/dep-two.jar", main.getValue(Attributes.Name.CLASS_PATH));
        // Carried over from the runner jar's manifest.
        assertEquals("java.base/java.lang", main.getValue("Add-Opens"));
        assertEquals("java.base/jdk.internal.misc", main.getValue("Add-Exports"));
        assertEquals("ALL-UNNAMED", main.getValue("Enable-Native-Access"));
        assertEquals("Demo Application", main.getValue(Attributes.Name.IMPLEMENTATION_TITLE));
        assertEquals("1.2.3", main.getValue(Attributes.Name.IMPLEMENTATION_VERSION));
        // From the manifest the application layer carries itself.
        assertEquals("Example", main.getValue(Attributes.Name.IMPLEMENTATION_VENDOR));
        // Nothing that describes the runner jar rather than the application.
        assertNull(main.getValue(IndexFormat.ATTR_FORMAT));
        assertNull(main.getValue(IndexFormat.ATTR_VERSION));
        assertNull(main.getValue(IndexFormat.ATTR_START_CLASS));
        assertNull(main.getValue("Multi-Release"));
        // The per-package section the packager recorded in the index, rebuilt.
        Attributes section = manifestOf(destination.resolve("app.jar")).getAttributes("com/example/");
        assertNotNull(section);
        assertEquals("9.9", section.getValue(Attributes.Name.IMPLEMENTATION_VERSION));
        assertEquals("Demo Package", section.getValue(Attributes.Name.IMPLEMENTATION_TITLE));
    }

    @Test
    void extractedClassPathEncodesDependencyUrlsAndTheJdkLoadsThemInIndexOrder() throws Throwable {
        assumeFileNamesSupported("dep space#?.jar", "dep%20name-雪-with-a-very-long-dependency-name.jar");
        File encoded = writeArchive(workspace.resolve("encoded/app.jar"), Flavour.ENCODED_DEPENDENCIES);
        Path destination = workspace.resolve("extract/encoded");
        try (ArchiveSource other = ArchiveSource.open(encoded)) {
            Index otherIndex = Index.open(other);
            capture(() -> Extract.run(new String[] {Extract.OPTION_DESTINATION, destination.toString()},
                    encoded, otherIndex, other));
        }

        Path application = destination.resolve("app.jar");
        String expected = "lib/dep%20space%23%3F.jar "
                + "lib/dep%2520name-%E9%9B%AA-with-a-very-long-dependency-name.jar";
        assertEquals(expected,
                manifestOf(application).getMainAttributes().getValue(Attributes.Name.CLASS_PATH));
        assertTrue(Files.isRegularFile(destination.resolve("lib/dep space#?.jar")));
        assertTrue(Files.isRegularFile(
                destination.resolve("lib/dep%20name-雪-with-a-very-long-dependency-name.jar")));
        try (JarFile jar = new JarFile(application.toFile())) {
            String raw = new String(read(jar, "META-INF/MANIFEST.MF"), StandardCharsets.UTF_8);
            assertTrue(raw.contains("\r\n "), "the long encoded Class-Path should be wrapped:\n" + raw);
        }

        // Give the JDK only the application jar. It must consume Class-Path itself: the class is in the
        // first specially named dependency and data.txt is only in the second, preserving index order.
        try (URLClassLoader loader = new URLClassLoader(new URL[] {application.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            assertEquals("org.depone.DepOne", loader.loadClass("org.depone.DepOne").getName());
            try (InputStream in = loader.getResourceAsStream("data.txt")) {
                assertNotNull(in, "the JDK did not resolve the second encoded Class-Path URL");
                assertArrayEquals(VERSIONED_DATA, in.readAllBytes());
            }
        }

        Assumptions.assumeTrue(javaExecutable() != null, "no JDK to fork; set runner.test.javaHome");
        Forked run = fork(application, List.of());
        assertEquals(0, run.status(), run.output());
        assertTrue(run.output().contains("DEP dep-one"), run.output());
        assertTrue(run.output().contains("DEPENDENCY-SERVICE true"), run.output());
        assertTrue(run.output().contains("RESULT OK"), run.output());
    }

    @Test
    void theExtractedManifestIsMultiReleaseWhenTheApplicationLayerIs() throws Throwable {
        File multiRelease = writeArchive(workspace.resolve("mr/app.jar"), Flavour.MULTI_RELEASE);
        Path destination = workspace.resolve("extract/multi-release");
        try (ArchiveSource other = ArchiveSource.open(multiRelease)) {
            Index otherIndex = Index.open(other);
            capture(() -> Extract.run(new String[] {Extract.OPTION_DESTINATION, destination.toString()},
                    multiRelease, otherIndex, other));
        }

        Attributes main = manifestOf(destination.resolve("app.jar")).getMainAttributes();
        assertEquals("true", main.getValue("Multi-Release"));
    }

    @Test
    void anApplicationWithNoDependenciesGetsNoLibraryDirectoryAndNoClassPath() throws Throwable {
        File solo = writeArchive(workspace.resolve("solo/app.jar"), Flavour.NO_DEPENDENCIES);
        Path destination = workspace.resolve("extract/solo");
        try (ArchiveSource other = ArchiveSource.open(solo)) {
            Index otherIndex = Index.open(other);
            capture(() -> Extract.run(new String[] {Extract.OPTION_DESTINATION, destination.toString()},
                    solo, otherIndex, other));
        }

        assertFalse(Files.exists(destination.resolve(Extract.LIBRARY_DIRECTORY)));
        Attributes main = manifestOf(destination.resolve("app.jar")).getMainAttributes();
        assertEquals(MAIN_CLASS, main.getValue(Attributes.Name.MAIN_CLASS));
        assertNull(main.getValue(Attributes.Name.CLASS_PATH));
    }

    @Test
    void extractWritesTheDirectoryEntriesMicronautScansFor() throws Throwable {
        // The application layer of a runner jar stores no directory entries, because the index
        // synthesises them. A real jar has to carry them: Micronaut discovers beans by listing the
        // directory META-INF/micronaut/, and ZipFile.getEntry only answers a name ending in '/' when such
        // an entry exists. Without them an extracted application starts and then serves nothing, because
        // its dependencies still carry theirs and only its own beans go missing.
        Path destination = workspace.resolve("extract/directories");

        Extract.run(new String[] {Extract.OPTION_DESTINATION, destination.toString()}, archive, index,
                source);

        try (JarFile jar = new JarFile(destination.resolve("app.jar").toFile())) {
            int slash = APPLICATION_SERVICE.lastIndexOf('/');
            String serviceDirectory = APPLICATION_SERVICE.substring(0, slash + 1);
            assertNotNull(jar.getEntry(serviceDirectory),
                    "no directory entry for " + serviceDirectory + ", so Micronaut would find no beans");
            assertTrue(jar.getEntry(serviceDirectory).isDirectory(), serviceDirectory);
            assertNotNull(jar.getEntry("META-INF/micronaut/"), "no directory entry for META-INF/micronaut/");
            assertNotNull(jar.getEntry("META-INF/"), "no directory entry for META-INF/");
            assertNotNull(jar.getEntry("com/example/"), "no directory entry for com/example/");
            // Every directory is written once, outermost first, so the jar stays readable.
            assertNotNull(jar.getEntry("com/"));
        }
    }

    @Test
    void extractDefaultsToADirectoryNamedAfterTheArchiveBesideIt() throws Throwable {
        Path copy = workspace.resolve("default/service-1.0-all.jar");
        Files.createDirectories(copy.getParent());
        Files.copy(archive.toPath(), copy);
        try (ArchiveSource other = ArchiveSource.open(copy.toFile())) {
            Index otherIndex = Index.open(other);
            capture(() -> Extract.run(new String[0], copy.toFile(), otherIndex, other));
        }

        Path destination = workspace.resolve("default/service-1.0-all");
        assertTrue(Files.isRegularFile(destination.resolve("service-1.0-all.jar")));
        assertTrue(Files.isRegularFile(destination.resolve("lib/dep-one.jar")));
    }

    @Test
    void extractRefusesADestinationThatIsNotEmpty() throws IOException {
        Path destination = workspace.resolve("extract/occupied");
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("precious.txt"), "keep me");

        IOException failure = assertThrows(IOException.class, () -> Extract.run(
                new String[] {Extract.OPTION_DESTINATION, destination.toString()}, archive, index,
                source));

        assertTrue(failure.getMessage().contains("is not empty"), failure.getMessage());
        assertTrue(failure.getMessage().contains(Extract.OPTION_FORCE), failure.getMessage());
        assertEquals("keep me", Files.readString(destination.resolve("precious.txt")));
        assertNoLeftovers(destination.getParent());
    }

    @Test
    void extractReplacesADestinationThatIsNotEmptyWhenForced() throws Throwable {
        Path destination = workspace.resolve("extract/forced");
        Files.createDirectories(destination);
        Files.writeString(destination.resolve("stale.txt"), "old");

        capture(() -> Extract.run(new String[] {Extract.OPTION_DESTINATION, destination.toString(),
                Extract.OPTION_FORCE}, archive, index, source));

        assertFalse(Files.exists(destination.resolve("stale.txt")));
        assertTrue(Files.isRegularFile(destination.resolve("app.jar")));
        assertNoLeftovers(destination.getParent());
    }

    @Test
    void extractPublishesOverAnExistingEmptyDestinationWithoutForce() throws Throwable {
        Path destination = Files.createDirectories(workspace.resolve("extract/existing-empty"));

        capture(() -> Extract.run(new String[] {
            Extract.OPTION_DESTINATION, destination.toString()
        }, archive, index, source));

        assertTrue(Files.isRegularFile(destination.resolve("app.jar")));
        assertNoLeftovers(destination.getParent());
    }

    @Test
    void extractRefusesASymbolicLinkDestinationUpFrontWithoutForce() throws IOException {
        Path parent = Files.createDirectories(workspace.resolve("publication/unforced-link"));
        Path target = Files.createDirectories(parent.resolve("empty"));
        Path link = createSymbolicLink(parent.resolve("link"), target);

        IOException failure = assertThrows(IOException.class, () -> Extract.run(
                new String[] {Extract.OPTION_DESTINATION, link.toString()}, archive, index, source));

        assertTrue(failure.getMessage().contains(link + " is a symbolic link"), failure.getMessage());
        assertTrue(failure.getMessage().contains(Extract.OPTION_FORCE), failure.getMessage());
        assertFalse(failure.getMessage().contains("changed during extraction"), failure.getMessage());
        assertFalse(failure.getMessage().contains("became occupied"), failure.getMessage());
        assertEquals(target, Files.readSymbolicLink(link));
        assertArrayEquals(new String[0], target.toFile().list(), "the link's target must stay empty");
        assertNoLeftovers(parent);
    }

    @Test
    void extractRefusesARegularFileDestinationWithAndWithoutForce() throws IOException {
        Path file = Files.createDirectories(workspace.resolve("publication/file")).resolve("app");
        Files.writeString(file, "keep me");
        for (String[] arguments : new String[][] {{Extract.OPTION_DESTINATION, file.toString()},
            {Extract.OPTION_DESTINATION, file.toString(), Extract.OPTION_FORCE}}) {
            IOException failure = assertThrows(IOException.class,
                    () -> Extract.run(arguments, archive, index, source));
            assertTrue(failure.getMessage().contains("exists and is not a directory"), failure.getMessage());
            assertEquals("keep me", Files.readString(file));
        }
        assertNoLeftovers(file.getParent());
    }

    @Test
    void publishWithoutForcePreservesAnOccupantThatArrivedAfterTheCheck() throws IOException {
        Path parent = Files.createDirectories(workspace.resolve("publication/late"));
        Path directory = Files.createDirectories(parent.resolve("directory"));
        Files.writeString(directory.resolve("precious.txt"), "keep me");
        Path file = Files.writeString(parent.resolve("file"), "keep me");
        Path staged = Files.createDirectories(workspace.resolve("publication/late-staged"));
        // Each occupant arrived after the up-front check (#52). The link is last: it may be unsupported.
        for (Path occupant : List.of(directory, file, parent.resolve("link"))) {
            if (occupant.endsWith("link")) {
                createSymbolicLink(occupant, directory);
            }
            IOException failure = assertThrows(IOException.class,
                    () -> Extract.publish(staged, occupant, false));
            assertTrue(failure.getMessage().contains("became occupied"), failure.getMessage());
            assertArrayEquals(new String[] {"precious.txt"}, directory.toFile().list());
            assertEquals("keep me", Files.readString(directory.resolve("precious.txt")));
            assertEquals("keep me", Files.readString(file));
        }
        assertEquals(directory, Files.readSymbolicLink(parent.resolve("link")));
    }

    @Test
    void publishWithoutForceReplacesAnEmptyDirectory() throws IOException {
        Path staged = Files.createDirectories(workspace.resolve("publication/empty/staged"));
        Files.writeString(staged.resolve("app.jar"), "layout");
        Path destination = Files.createDirectories(workspace.resolve("publication/empty/destination"));

        Extract.publish(staged, destination, false);

        assertEquals("layout", Files.readString(destination.resolve("app.jar")));
    }

    @Test
    void forcedPublishRestoresThePreviousDestinationWhenTheLayoutCannotMoveIn() throws IOException {
        Path parent = Files.createDirectories(workspace.resolve("publication/rollback"));
        Path destination = Files.createDirectories(parent.resolve("destination"));
        Files.writeString(destination.resolve("precious.txt"), "keep me");

        IOException failure = assertThrows(IOException.class,
                () -> Extract.publish(parent.resolve("missing"), destination, true));

        assertTrue(failure.getMessage().contains("restored"), failure.getMessage());
        assertEquals("keep me", Files.readString(destination.resolve("precious.txt")));
        assertNoLeftovers(parent);
    }

    @Test
    void extractReplacesOnlyTheSymbolicLinkWhenForced() throws Throwable {
        Path parent = Files.createDirectories(workspace.resolve("publication/forced-link"));
        Path target = Files.createDirectories(parent.resolve("target"));
        Files.writeString(target.resolve("precious.txt"), "keep me");
        Path link = createSymbolicLink(parent.resolve("link"), target);

        capture(() -> Extract.run(new String[] {Extract.OPTION_DESTINATION, link.toString(),
                Extract.OPTION_FORCE}, archive, index, source));

        assertFalse(Files.isSymbolicLink(link));
        assertTrue(Files.isRegularFile(link.resolve("app.jar")));
        assertArrayEquals(new String[] {"precious.txt"}, target.toFile().list());
        assertEquals("keep me", Files.readString(target.resolve("precious.txt")));
        assertNoLeftovers(parent);
    }

    @Test
    void extractPublishesADirectoryWithTheModeOfAnyNewDirectory() throws Throwable {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path parent = Files.createDirectories(workspace.resolve("publication/mode"));
        Path destination = parent.resolve("destination");

        capture(() -> Extract.run(new String[] {Extract.OPTION_DESTINATION, destination.toString()},
                archive, index, source));

        // Compared with a sibling rather than with a fixed mode, so that the test holds under any umask.
        assertEquals(Files.getPosixFilePermissions(Files.createDirectory(parent.resolve("sibling"))),
                Files.getPosixFilePermissions(destination));
    }

    @Test
    void extractRefusesAnApplicationEntryThatWouldEscapeTheDestination() throws IOException {
        File escaping = writeArchive(workspace.resolve("escaping-entry/app.jar"), Flavour.ESCAPING_ENTRY);
        Path destination = workspace.resolve("extract/escaping-entry");
        try (ArchiveSource other = ArchiveSource.open(escaping)) {
            Index otherIndex = Index.open(other);

            IOException failure = assertThrows(IOException.class, () -> Extract.run(
                    new String[] {Extract.OPTION_DESTINATION, destination.toString()}, escaping,
                    otherIndex, other));

            assertTrue(failure.getMessage().contains("../evil.txt"), failure.getMessage());
            assertTrue(failure.getMessage().contains("outside the destination"), failure.getMessage());
        }
        // The libraries were already written when the bad entry was found: nothing survives the failure.
        assertFalse(Files.exists(destination));
        assertNoLeftovers(destination.getParent());
    }

    @Test
    void extractRefusesADependencyNameThatWouldEscapeTheDestination() throws IOException {
        File escaping = writeArchive(workspace.resolve("escaping-lib/app.jar"), Flavour.ESCAPING_LIBRARY);
        Path destination = workspace.resolve("extract/escaping-lib");
        try (ArchiveSource other = ArchiveSource.open(escaping)) {
            Index otherIndex = Index.open(other);

            IOException failure = assertThrows(IOException.class, () -> Extract.run(
                    new String[] {Extract.OPTION_DESTINATION, destination.toString()}, escaping,
                    otherIndex, other));

            assertTrue(failure.getMessage().contains("outside the destination"), failure.getMessage());
        }
        assertFalse(Files.exists(destination));
        assertNoLeftovers(destination.getParent());
    }

    @Test
    void extractStillRefusesAPlatformSeparatorInADependencyFileName() throws IOException {
        File separated = writeArchive(workspace.resolve("separated-lib/app.jar"),
                Flavour.BACKSLASH_LIBRARY);
        Path destination = workspace.resolve("extract/separated-lib");
        try (ArchiveSource other = ArchiveSource.open(separated)) {
            Index otherIndex = Index.open(other);

            IOException failure = assertThrows(IOException.class, () -> Extract.run(
                    new String[] {Extract.OPTION_DESTINATION, destination.toString()}, separated,
                    otherIndex, other));

            assertTrue(failure.getMessage().contains("cannot be extracted safely"), failure.getMessage());
        }
        assertFalse(Files.exists(destination));
        assertNoLeftovers(destination.getParent());
    }

    @Test
    void extractRefusesASourceReachedThroughADirectoryAliasWithAndWithoutForce() throws IOException {
        for (boolean force : List.of(false, true)) {
            Path real = Files.createDirectories(workspace.resolve("source-alias-" + force + "/real"));
            Path sourceArchive = real.resolve("original.jar");
            Files.copy(archive.toPath(), sourceArchive);
            byte[] original = Files.readAllBytes(sourceArchive);
            Path sentinel = real.resolve("precious.txt");
            Files.writeString(sentinel, "keep me");
            Path alias = createSymbolicLink(real.resolveSibling("alias"), real);
            File aliasedArchive = alias.resolve("original.jar").toFile();

            try (ArchiveSource aliasedSource = ArchiveSource.open(aliasedArchive)) {
                Index aliasedIndex = Index.open(aliasedSource);
                List<String> arguments = new ArrayList<>(List.of(
                        Extract.OPTION_DESTINATION, real.toString()));
                if (force) {
                    arguments.add(Extract.OPTION_FORCE);
                }

                IOException failure = assertThrows(IOException.class, () -> Extract.run(
                        arguments.toArray(new String[0]), aliasedArchive, aliasedIndex, aliasedSource));

                assertTrue(failure.getMessage().contains("holds the runner jar itself"), failure.getMessage());
            }
            assertArrayEquals(original, Files.readAllBytes(sourceArchive),
                    "rejecting an aliased destination must leave the runner archive byte-identical");
            try (JarFile jar = new JarFile(sourceArchive.toFile())) {
                assertNotNull(jar.getEntry(IndexFormat.INDEX_ENTRY_NAME));
            }
            assertEquals("keep me", Files.readString(sentinel));
            assertNoLeftovers(real.getParent());
        }
    }

    @Test
    void extractRefusesADestinationDirectoryAlias() throws IOException {
        Path real = Files.createDirectories(workspace.resolve("destination-alias/real"));
        Path sourceArchive = real.resolve("original.jar");
        Files.copy(archive.toPath(), sourceArchive);
        byte[] original = Files.readAllBytes(sourceArchive);
        Path sentinel = real.resolve("precious.txt");
        Files.writeString(sentinel, "keep me");
        Path destination = createSymbolicLink(real.resolveSibling("alias"), real);

        try (ArchiveSource other = ArchiveSource.open(sourceArchive.toFile())) {
            Index otherIndex = Index.open(other);
            IOException failure = assertThrows(IOException.class, () -> Extract.run(new String[] {
                Extract.OPTION_DESTINATION, destination.toString(), Extract.OPTION_FORCE
            }, sourceArchive.toFile(), otherIndex, other));

            assertTrue(failure.getMessage().contains("holds the runner jar itself"), failure.getMessage());
        }
        assertArrayEquals(original, Files.readAllBytes(sourceArchive));
        assertEquals("keep me", Files.readString(sentinel));
        assertNoLeftovers(real.getParent());
    }

    @Test
    void extractRefusesACaseAliasOnCaseInsensitiveFileSystems() throws IOException {
        Path real = Files.createDirectories(workspace.resolve("case-alias/Destination"));
        Path destination = real.resolveSibling(real.getFileName().toString().toUpperCase(Locale.ROOT));
        Assumptions.assumeTrue(Files.exists(destination), "the test file system is case-sensitive");
        Path sourceArchive = real.resolve("original.jar");
        Files.copy(archive.toPath(), sourceArchive);
        byte[] original = Files.readAllBytes(sourceArchive);
        Path sentinel = real.resolve("precious.txt");
        Files.writeString(sentinel, "keep me");

        try (ArchiveSource other = ArchiveSource.open(sourceArchive.toFile())) {
            Index otherIndex = Index.open(other);
            IOException failure = assertThrows(IOException.class, () -> Extract.run(new String[] {
                Extract.OPTION_DESTINATION, destination.toString(), Extract.OPTION_FORCE
            }, sourceArchive.toFile(), otherIndex, other));

            assertTrue(failure.getMessage().contains("holds the runner jar itself"), failure.getMessage());
        }
        assertArrayEquals(original, Files.readAllBytes(sourceArchive));
        assertEquals("keep me", Files.readString(sentinel));
        assertNoLeftovers(real.getParent());
    }

    @Test
    void extractAllowsANonexistentDestinationBelowAnAliasedAncestor() throws Throwable {
        Path real = Files.createDirectories(workspace.resolve("missing-tail/real"));
        Path alias = createSymbolicLink(real.resolveSibling("alias"), real);
        Path destination = alias.resolve("new/missing");

        capture(() -> Extract.run(new String[] {
            Extract.OPTION_DESTINATION, destination.toString()
        }, archive, index, source));

        assertTrue(Files.isRegularFile(real.resolve("new/missing/app.jar")));
        assertNoLeftovers(real.resolve("new"));
    }

    @Test
    void extractRefusesADestinationThatHoldsTheArchiveItself() {
        IOException failure = assertThrows(IOException.class, () -> Extract.run(
                new String[] {Extract.OPTION_DESTINATION, archive.getParent()}, archive, index, source));

        assertTrue(failure.getMessage().contains("holds the runner jar itself"), failure.getMessage());
    }

    @Test
    void extractRefusesAnUnknownOption() {
        IOException failure = assertThrows(IOException.class,
                () -> Extract.run(new String[] {"--wat"}, archive, index, source));
        assertTrue(failure.getMessage().contains("Unknown extract option '--wat'"), failure.getMessage());
    }

    @Test
    void theExtractedApplicationStartsOnTheJdkApplicationClassLoader() throws Throwable {
        Assumptions.assumeTrue(javaExecutable() != null, "no JDK to fork; set runner.test.javaHome");
        Path destination = workspace.resolve("extract/forked");
        capture(() -> Extract.run(new String[] {Extract.OPTION_DESTINATION, destination.toString()},
                archive, index, source));

        Forked run = fork(destination.resolve("app.jar"), List.of("alpha", "beta"));

        assertEquals(0, run.status(), run.output());
        assertTrue(run.output().contains("RESULT OK"), run.output());
        // The claim this mode exists for: no user-defined class loader is involved any more, which is
        // what JEP 483 requires before it will link a cached class.
        assertTrue(run.output().contains("BUILTIN true"), run.output());
        assertTrue(run.output().contains("LOADER jdk.internal.loader."), run.output());
        assertTrue(run.output().contains("ARGS alpha|beta"), run.output());
        assertTrue(run.output().contains("DEP dep-one"), run.output());
        assertTrue(run.output().contains("CONFIG greeting: hello"), run.output());
        // The application's own service metadata is still found, and so is the dependency's - from
        // inside the dependency's own jar, which is why the merged copy is not needed here.
        assertTrue(run.output().contains("APPLICATION-SERVICE true"), run.output());
        assertTrue(run.output().contains("DEPENDENCY-SERVICE true"), run.output());
        // Neither the stub nor the index came along.
        assertTrue(run.output().contains("STUB false"), run.output());
        assertTrue(run.output().contains("INDEX false"), run.output());
        // The per-package manifest section the index carried is a Package again.
        assertTrue(run.output().contains("PACKAGE 9.9"), run.output());
    }

    /**
     * Builds one runner archive: manifest, index, merged service directory, application layer, nested
     * jars, in the order the packager writes them.
     *
     * @param file    where to write it
     * @param flavour what to vary
     * @return the archive
     * @throws IOException if it cannot be written
     */
    private static File writeArchive(Path file, Flavour flavour) throws IOException {
        TestArchiveBuilder first = new TestArchiveBuilder();
        long[] one = new long[3];
        one[0] = first.stored("META-INF/MANIFEST.MF", DEPENDENCY_ONE_MANIFEST);
        one[1] = first.stored("org/depone/DepOne.class", dependencyClass);
        one[2] = first.stored(DEPENDENCY_SERVICE, EMPTY);
        dependencyOneJar = first.build();

        TestArchiveBuilder second = new TestArchiveBuilder();
        long[] two = new long[3];
        two[0] = second.stored("META-INF/MANIFEST.MF", DEPENDENCY_TWO_MANIFEST);
        two[1] = second.stored("data.txt", BASE_DATA);
        two[2] = second.stored("META-INF/versions/21/data.txt", VERSIONED_DATA);
        dependencyTwoJar = second.build();

        TestArchiveBuilder outer = new TestArchiveBuilder();
        outer.stored("META-INF/MANIFEST.MF", RUNNER_MANIFEST);
        byte[] draft = buildIndex(null, one, two, flavour);
        outer.reserve(IndexFormat.INDEX_ENTRY_NAME, draft.length);
        outer.stored("META-INF/micronaut/", EMPTY);
        outer.stored("META-INF/micronaut/com.example.Svc/", EMPTY);
        outer.stored(APPLICATION_SERVICE, EMPTY);
        outer.stored("META-INF/micronaut/io.other.Svc/", EMPTY);
        outer.stored(DEPENDENCY_SERVICE, EMPTY);
        outer.stored(IndexFormat.CLASSES_PREFIX + "META-INF/MANIFEST.MF", APPLICATION_MANIFEST);
        outer.stored(IndexFormat.CLASSES_PREFIX + APPLICATION_SERVICE, EMPTY);
        outer.stored(IndexFormat.CLASSES_PREFIX + CONFIGURATION, CONFIGURATION_BYTES);
        outer.stored(IndexFormat.CLASSES_PREFIX + "com/example/App.class", applicationClass);
        outer.stored(IndexFormat.CLASSES_PREFIX + STUB_ENTRY, STUB_BYTES);
        if (flavour == Flavour.ESCAPING_ENTRY) {
            outer.stored(IndexFormat.CLASSES_PREFIX + "../evil.txt", bytes("gotcha"));
        }
        if (flavour == Flavour.TRANSFORMS) {
            outer.stored(IndexFormat.TRANSFORMS_ENTRY_NAME, bytes("Micronaut-Runner-Version\t" + LAUNCHER_VERSION
                    + "\n" + TRANSFORMS_COUNTS + "\n"));
        }
        if (flavour != Flavour.NO_DEPENDENCIES) {
            outer.stored(flavour == Flavour.ENCODED_DEPENDENCIES
                    ? ENCODED_DEPENDENCY_ONE : DEPENDENCY_ONE, dependencyOneJar);
            outer.stored(flavour == Flavour.ENCODED_DEPENDENCIES
                    ? ENCODED_DEPENDENCY_TWO : DEPENDENCY_TWO, dependencyTwoJar);
        }
        byte[] real = buildIndex(outer, one, two, flavour);
        assertEquals(draft.length, real.length, "the index size must not depend on the offsets");
        outer.replace(IndexFormat.INDEX_ENTRY_NAME, real);

        Files.createDirectories(file.getParent());
        return outer.writeTo(file.toFile());
    }

    /**
     * Describes the archive to the index writer. Called twice: once with no archive, to learn how long
     * the index is, and once with the finished layout, to record the real offsets.
     *
     * @param outer   the archive being built, or {@code null} for the first pass
     * @param one     the offsets of the first dependency's entries inside it
     * @param two     the offsets of the second dependency's entries inside it
     * @param flavour what to vary
     * @return the index bytes
     */
    private static byte[] buildIndex(TestArchiveBuilder outer, long[] one, long[] two, Flavour flavour) {
        TestIndexBuilder builder = new TestIndexBuilder()
                .startClass(MAIN_CLASS)
                .entryStubClass(STUB_CLASS)
                .launcherVersion(LAUNCHER_VERSION)
                .headerFlags(IndexFormat.HEADER_FLAG_NESTED_STORED
                        | (flavour == Flavour.MULTI_RELEASE ? IndexFormat.HEADER_FLAG_APP_MULTI_RELEASE
                            : 0));

        TestIndexBuilder.Jar application = builder.addJar(IndexFormat.CLASSES_PREFIX)
                .flags(IndexFormat.JAR_FLAG_IS_OUTER | IndexFormat.JAR_FLAG_HAS_MANIFEST)
                .manifest(null, null, null, "Demo Application", "1.2.3", "Example");
        if (flavour == Flavour.MULTI_RELEASE) {
            application.multiRelease();
        }
        application.addPackage("com.example")
                .attributes(null, null, null, "Demo Package", "9.9", null)
                .end();
        // The merged Micronaut service directory in the archive root, which the packager writes first.
        entry(application, outer, "META-INF/micronaut/", "META-INF/micronaut/", 0);
        entry(application, outer, "META-INF/micronaut/com.example.Svc/",
                "META-INF/micronaut/com.example.Svc/", 0);
        entry(application, outer, APPLICATION_SERVICE, APPLICATION_SERVICE, 0);
        entry(application, outer, "META-INF/micronaut/io.other.Svc/",
                "META-INF/micronaut/io.other.Svc/", 0);
        entry(application, outer, DEPENDENCY_SERVICE, DEPENDENCY_SERVICE, 0);
        // Then the application layer itself, whose logical names drop the prefix.
        entry(application, outer, IndexFormat.CLASSES_PREFIX + "META-INF/MANIFEST.MF",
                "META-INF/MANIFEST.MF", APPLICATION_MANIFEST.length);
        entry(application, outer, IndexFormat.CLASSES_PREFIX + APPLICATION_SERVICE,
                APPLICATION_SERVICE, 0);
        entry(application, outer, IndexFormat.CLASSES_PREFIX + CONFIGURATION, CONFIGURATION,
                CONFIGURATION_BYTES.length);
        entry(application, outer, IndexFormat.CLASSES_PREFIX + "com/example/App.class",
                "com/example/App.class", applicationClass.length);
        entry(application, outer, IndexFormat.CLASSES_PREFIX + STUB_ENTRY, STUB_ENTRY,
                STUB_BYTES.length);

        if (flavour == Flavour.NO_DEPENDENCIES) {
            return builder.build();
        }
        String firstName = switch (flavour) {
            case ESCAPING_LIBRARY -> IndexFormat.LIB_PREFIX + "..";
            case BACKSLASH_LIBRARY -> IndexFormat.LIB_PREFIX + "folder\\dep.jar";
            case ENCODED_DEPENDENCIES -> ENCODED_DEPENDENCY_ONE;
            default -> DEPENDENCY_ONE;
        };
        String firstPhysical = flavour == Flavour.ENCODED_DEPENDENCIES
                ? ENCODED_DEPENDENCY_ONE : DEPENDENCY_ONE;
        long firstBase = offset(outer, firstPhysical);
        TestIndexBuilder.Jar dependencyOne = builder.addJar(firstName)
                .location(firstBase, dependencyOneJar.length, header(outer, firstPhysical))
                .coordinates("org.example:dep-one:1.0.0")
                .flags(IndexFormat.JAR_FLAG_HAS_MANIFEST | IndexFormat.JAR_FLAG_SEALED_BY_DEFAULT)
                .manifest(null, null, null, "Dependency One", "1.0.0", null);
        nested(dependencyOne, firstBase + one[0], DEPENDENCY_ONE_MANIFEST.length,
                "META-INF/MANIFEST.MF");
        nested(dependencyOne, firstBase + one[1], dependencyClass.length, "org/depone/DepOne.class");
        nested(dependencyOne, firstBase + one[2], 0, DEPENDENCY_SERVICE);

        String secondName = flavour == Flavour.ENCODED_DEPENDENCIES
                ? ENCODED_DEPENDENCY_TWO : DEPENDENCY_TWO;
        long secondBase = offset(outer, secondName);
        TestIndexBuilder.Jar dependencyTwo = builder.addJar(secondName)
                .location(secondBase, dependencyTwoJar.length, header(outer, secondName))
                .coordinates("org.example:dep-two:2.0.0")
                .flags(IndexFormat.JAR_FLAG_HAS_MANIFEST | IndexFormat.JAR_FLAG_SIGNED_ORIGINAL)
                .multiRelease()
                .manifest(null, null, null, "Dependency Two", "2.0.0", null);
        nested(dependencyTwo, secondBase + two[0], DEPENDENCY_TWO_MANIFEST.length,
                "META-INF/MANIFEST.MF");
        nested(dependencyTwo, secondBase + two[1], BASE_DATA.length, "data.txt");
        nested(dependencyTwo, secondBase + two[2], VERSIONED_DATA.length,
                "META-INF/versions/21/data.txt");
        return builder.build();
    }

    private static void entry(TestIndexBuilder.Jar jar, TestArchiveBuilder outer, String physical,
            String logical, int size) {
        jar.addEntry(logical).data(offset(outer, physical), size, size).dosTime(DOS_TIME).end();
    }

    private static void nested(TestIndexBuilder.Jar jar, long at, int size, String name) {
        jar.addEntry(name).data(at, size, size).dosTime(DOS_TIME).end();
    }

    private static long offset(TestArchiveBuilder outer, String name) {
        return outer == null ? 0 : outer.dataOffset(name);
    }

    private static long header(TestArchiveBuilder outer, String name) {
        return outer == null ? 0 : outer.localHeaderOffset(name);
    }

    /** Runs an action with standard output captured, and hands back what it printed. */
    private static String capture(Executable action) throws Throwable {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.execute();
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    /** The first printed line that names something, with its leading blanks removed. */
    private static String line(String output, String what) {
        List<String> found = lines(output, what);
        assertFalse(found.isEmpty(), "nothing in the output named " + what + "\n" + output);
        return found.get(0);
    }

    /** Every printed line whose first column is exactly the given name. */
    private static List<String> lines(String output, String what) {
        List<String> found = new ArrayList<>();
        for (String candidate : output.split("\n")) {
            String trimmed = candidate.strip();
            if (trimmed.equals(what) || trimmed.startsWith(what + " ")
                    || trimmed.startsWith(what + "  ")) {
                found.add(trimmed);
            }
        }
        if (found.isEmpty()) {
            for (String candidate : output.split("\n")) {
                String trimmed = candidate.strip();
                if (trimmed.contains(what)) {
                    found.add(trimmed);
                }
            }
        }
        return found;
    }

    /** What a listing line says after its name and jar columns. */
    private static String notes(String listed) {
        String[] columns = columns(listed);
        return columns.length < 3 ? "" : columns[2];
    }

    /** The cells of one printed row, which are separated by at least two blanks. */
    private static String[] columns(String printed) {
        return printed.split("\\s{2,}");
    }

    /** The value {@code Inspect} printed for one label of its header block. */
    private static String header(String output, String label) {
        for (String candidate : output.split("\n")) {
            String trimmed = candidate.strip();
            if (trimmed.startsWith(label + " ")) {
                return trimmed.substring(label.length()).strip();
            }
        }
        return null;
    }

    private static int applicationEntries() {
        int first = index.jarFirstEntry(IndexFormat.APPLICATION_JAR_ID);
        int count = index.jarEntryCount(IndexFormat.APPLICATION_JAR_ID);
        int physical = 0;
        for (int record = first; record < first + count; record++) {
            if (index.entryPhysical(record)) {
                physical++;
            }
        }
        return physical;
    }

    private static Manifest manifestOf(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getManifest();
        }
    }

    private static byte[] read(JarFile jar, String name) throws IOException {
        ZipEntry entry = jar.getEntry(name);
        assertNotNull(entry, name);
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /** Skips filename cases the current file system cannot represent, such as {@code ?} on Windows. */
    private static void assumeFileNamesSupported(String... names) throws IOException {
        Path probe = Files.createDirectories(workspace.resolve("filename-probe"));
        for (String name : names) {
            try {
                Path file = probe.resolve(name);
                Files.write(file, EMPTY);
                Files.delete(file);
            } catch (RuntimeException | IOException e) {
                Assumptions.assumeTrue(false,
                        "the file system cannot represent dependency name '" + name + "': " + e.getMessage());
            }
        }
    }

    /** Creates a symbolic link, or reports that this platform cannot exercise the fixture. */
    private static Path createSymbolicLink(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "symbolic links are not supported: " + e.getMessage());
            throw e;
        }
    }

    /** Fails when a temporary extraction directory was left behind. */
    private static void assertNoLeftovers(Path directory) throws IOException {
        try (var children = Files.list(directory)) {
            List<Path> temporary = children
                    .filter(child -> child.getFileName().toString().startsWith(".micronaut-runner-"))
                    .toList();
            assertTrue(temporary.isEmpty(), "left behind " + temporary);
        }
    }

    private static Forked fork(Path jar, List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        command.addAll(arguments);
        Process process = new ProcessBuilder(command)
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new Forked(process.waitFor(), output);
    }

    private static Path javaExecutable() {
        String home = System.getProperty("runner.test.javaHome", System.getProperty("java.home"));
        if (home == null || home.isEmpty()) {
            return null;
        }
        Path candidate = Path.of(home, "bin", "java");
        if (!Files.isExecutable(candidate)) {
            candidate = Path.of(home, "bin", "java.exe");
        }
        return Files.isExecutable(candidate) ? candidate : null;
    }

    private static void compile(JavaCompiler compiler, Path sources, Path classes, Path classpath,
            Map<String, String> files) throws IOException {
        List<String> arguments = new ArrayList<>(List.of("--release", "25", "-d", classes.toString()));
        if (classpath != null) {
            arguments.add("-cp");
            arguments.add(classpath.toString());
        }
        for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
            Path source = sources.resolve(file.getKey());
            Files.createDirectories(source.getParent());
            Files.writeString(source, file.getValue());
            arguments.add(source.toString());
        }
        if (compiler.run(null, null, null, arguments.toArray(new String[0])) != 0) {
            throw new IOException("Could not compile the fixture sources under " + sources);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** What one fixture archive varies from the plain one. */
    private enum Flavour {

        /** A well-formed archive. */
        PLAIN,

        /** The application layer declares itself multi-release. */
        MULTI_RELEASE,

        /** An application entry whose name climbs out of the destination. */
        ESCAPING_ENTRY,

        /** A dependency whose name in the index climbs out of the destination. */
        ESCAPING_LIBRARY,

        /** A dependency whose name contains the Windows path separator. */
        BACKSLASH_LIBRARY,

        /** Dependencies whose names require URL encoding in a manifest Class-Path. */
        ENCODED_DEPENDENCIES,

        /** An application with no dependencies at all, so the index holds only jar 0. */
        NO_DEPENDENCIES,

        /** An archive that records what the build-time class transforms did. */
        TRANSFORMS
    }

    /**
     * The outcome of one forked JVM.
     *
     * @param status the exit status
     * @param output standard output and standard error, interleaved
     */
    private record Forked(int status, String output) {
    }
}
