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
import io.micronaut.runner.RunnerBuildTestAccess;
import io.micronaut.runner.build.ZipReaderTest.Payload;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static io.micronaut.runner.build.ZipReaderTest.deflated;
import static io.micronaut.runner.build.ZipReaderTest.deflatedWithTrailingByte;
import static io.micronaut.runner.build.ZipReaderTest.manifestCrcMismatch;
import static io.micronaut.runner.build.ZipReaderTest.readAll;
import static io.micronaut.runner.build.ZipReaderTest.stored;
import static io.micronaut.runner.build.ZipReaderTest.transferBoundarySizes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End to end tests for {@link RunnerJarBuilder}: real class files compiled by the JDK, real dependency jars
 * written with {@code java.util.zip}, packaged and then read back through {@link RunnerJarArchive}, which is
 * the launcher's own reader.
 *
 * <p>The assertions that matter most are the ones that compare the index with the archive it describes: the
 * launcher never parses the ZIP structure, so an offset the index gets wrong is a failure that only shows up
 * at runtime. {@link #theIndexAgreesWithTheArchiveItDescribes()} therefore checks every application record
 * against the outer central directory.</p>
 */
class RunnerJarBuilderTest {

    @TempDir
    static Path fixtures;

    /** A fixed MS-DOS timestamp for the fixture jars, so a rebuild of a fixture changes nothing. */
    private static final long FIXTURE_TIME = 1_000_000_000_000L;

    /** How long a junction test lets a build run that is not a cycle, so that a walk caught in one fails. */
    private static final Duration JUNCTION_TIMEOUT = Duration.ofSeconds(60);

    /** What an output path holds before a build that must fail without replacing it. */
    private static final byte[] PREVIOUS_OUTPUT = "the existing good output".getBytes(StandardCharsets.UTF_8);

    private static final String SERVICE_DIRECTORY =
            "META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/";

    private static Path applicationClasses;
    private static Path applicationResources;
    private static Path plainDependency;
    private static Path multiReleaseDependency;
    private static Path signedDependency;
    private static int counter;

    @BeforeAll
    static void createFixtures() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "this JDK has no java compiler");
        Assumptions.assumeTrue(
                RunnerJarBuilder.class.getResource("/META-INF/micronaut-runner/launcher.jar") != null,
                "the bundled launcher jar is not on the test class path");

        Path sources = fixtures.resolve("sources");
        applicationClasses = fixtures.resolve("app/classes");
        applicationResources = fixtures.resolve("app/resources");
        Path libraryClasses = fixtures.resolve("lib/classes");
        Files.createDirectories(applicationClasses);
        Files.createDirectories(applicationResources);
        Files.createDirectories(libraryClasses);

        compile(compiler, sources, applicationClasses, Map.of(
                "com/example/Application.java", """
                        package com.example;
                        public class Application {
                            public static void main(String[] args) {
                                System.out.println(new Greeter().greet());
                            }
                        }
                        """,
                "com/example/Greeter.java", """
                        package com.example;
                        public class Greeter {
                            public String greet() {
                                return "hello";
                            }
                        }
                        """));
        compile(compiler, sources, libraryClasses, Map.of(
                "com/example/dep/Dep.java", """
                        package com.example.dep;
                        public class Dep {
                        }
                        """,
                "com/example/api/Api.java", """
                        package com.example.api;
                        public interface Api {
                        }
                        """));

        write(applicationResources.resolve("application.yml"), "micronaut:\n  application:\n    name: test\n");
        write(applicationResources.resolve("static/index.html"), "<html></html>");
        write(applicationResources.resolve(SERVICE_DIRECTORY + "com.example.$Application$Definition"), "");

        Manifest plain = manifest(attributes -> {
            attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, "dep-lib");
            attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, "2.0.1");
            attributes.putValue("Sealed", "false");
        });
        Attributes api = new Attributes();
        api.put(Attributes.Name.SPECIFICATION_TITLE, "Dep API");
        api.put(Attributes.Name.IMPLEMENTATION_VERSION, "2.0.1");
        api.putValue("Sealed", "true");
        plain.getEntries().put("com/example/api/", api);
        Attributes digest = new Attributes();
        digest.putValue("SHA-256-Digest", "not-a-real-digest");
        plain.getEntries().put("com/example/dep/Dep.class", digest);

        Map<String, byte[]> plainEntries = new LinkedHashMap<>();
        plainEntries.put("com/example/dep/Dep.class",
                Files.readAllBytes(libraryClasses.resolve("com/example/dep/Dep.class")));
        plainEntries.put("com/example/api/Api.class",
                Files.readAllBytes(libraryClasses.resolve("com/example/api/Api.class")));
        plainEntries.put(SERVICE_DIRECTORY + "com.example.dep.DepBean", new byte[0]);
        plainEntries.put("META-INF/services/com.example.Service",
                "com.example.dep.Dep\n".getBytes(StandardCharsets.UTF_8));
        plainEntries.put("resources/dep.txt", "from the dependency".getBytes(StandardCharsets.UTF_8));
        plainDependency = fixtures.resolve("libs/dep-lib.jar");
        writeJar(plainDependency, plain, plainEntries);

        Manifest multiRelease = manifest(attributes -> attributes.putValue("Multi-Release", "true"));
        Map<String, byte[]> versioned = new LinkedHashMap<>();
        versioned.put("com/example/mr/Feature.class", "base".getBytes(StandardCharsets.UTF_8));
        versioned.put("META-INF/versions/17/com/example/mr/Feature.class",
                "seventeen".getBytes(StandardCharsets.UTF_8));
        versioned.put("META-INF/versions/17/META-INF/extra.txt",
                "not aliased".getBytes(StandardCharsets.UTF_8));
        multiReleaseDependency = fixtures.resolve("libs/mr-lib.jar");
        writeJar(multiReleaseDependency, multiRelease, versioned);

        Map<String, byte[]> signed = new LinkedHashMap<>();
        signed.put("com/example/signed/Signed.class", "signed".getBytes(StandardCharsets.UTF_8));
        signed.put("META-INF/MY.SF", "Signature-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
        signed.put("META-INF/MY.RSA", new byte[] {1, 2, 3, 4});
        signed.put("META-INF/INDEX.LIST", "JarIndex-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
        signedDependency = fixtures.resolve("libs/signed-lib.jar");
        writeJar(signedDependency, manifest(attributes -> { }), signed);
    }

    @Test
    void writesTheManifestAttributesInTheOrderTheyWereConfigured() throws IOException {
        // Map.copyOf iterates in an order derived from a per-JVM salt, so keeping the attributes in one
        // made the same inputs produce different archives in different build JVMs, which is exactly what
        // the reproducibility this packager promises rules out.
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Alpha-Attribute", "1");
        attributes.put("Bravo-Attribute", "2");
        attributes.put("Charlie-Attribute", "3");
        attributes.put("Delta-Attribute", "4");
        attributes.put("Echo-Attribute", "5");
        RunnerJarSpec spec = spec(output()).manifestAttributes(attributes).build();

        assertEquals(new ArrayList<>(attributes.keySet()), new ArrayList<>(spec.manifestAttributes().keySet()),
                "the spec keeps the caller's order");

        RunnerJarBuilder.build(spec, BuildLogger.noOp());
        List<String> written = new ArrayList<>();
        for (String line : manifestLines(spec.output())) {
            if (line.endsWith("-Attribute")) {
                written.add(line);
            }
        }
        assertEquals(new ArrayList<>(attributes.keySet()), written,
                "and the manifest is written in that order");
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {
            "mAnIfEsT-vErSiOn",
            "mAiN-cLaSs",
            "mIcRoNaUt-RuNnEr-FoRmAt",
            "mIcRoNaUt-RuNnEr-vErSiOn",
            "mIcRoNaUt-RuNnEr-sTaRt-ClAsS",
            "mIcRoNaUt-RuNnEr-fUtUrE"
    })
    void rejectsReservedManifestAttributesCaseInsensitivelyInAnyLocale(String key) throws IOException {
        Path output = output();
        Files.createDirectories(output.getParent());
        byte[] previous = "the existing runner jar".getBytes(StandardCharsets.UTF_8);
        Files.write(output, previous);
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> RunnerJarBuilder.build(spec(output)
                            .manifestAttributes(Map.of(key, "missing.Override"))
                            .build(), BuildLogger.noOp()));
            assertTrue(failure.getMessage().contains(key), failure.getMessage());
        } finally {
            Locale.setDefault(original);
        }
        assertArrayEquals(previous, Files.readAllBytes(output),
                "invalid configuration must leave the existing output alone");
    }

    @Test
    void acceptsLegalManifestAttributesAndBuildsARunnableArtifact()
            throws IOException, InterruptedException {
        Manifest application = manifest(attributes ->
                attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, "from application"));
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Implementation-Title", "configured");
        attributes.put("First-Legal-Attribute", "one");
        attributes.put("Second-Legal-Attribute", "two");
        Path output = output();

        RunnerJarBuilder.build(spec(output)
                .applicationManifest(application)
                .manifestAttributes(attributes)
                .build(), BuildLogger.noOp());

        try (ZipReader archive = ZipReader.open(output)) {
            Attributes written = archive.manifest().orElseThrow().getMainAttributes();
            assertEquals("configured", written.getValue(Attributes.Name.IMPLEMENTATION_TITLE),
                    "a legal configured attribute may override the application manifest");
            assertEquals("one", written.getValue("First-Legal-Attribute"));
            assertEquals("two", written.getValue("Second-Legal-Attribute"));
        }
        List<String> names = manifestLines(output);
        assertTrue(names.indexOf("First-Legal-Attribute") < names.indexOf("Second-Legal-Attribute"),
                "legal configured attributes keep their order");

        Process process = new ProcessBuilder(javaExecutable().toString(), "-jar", output.toString())
                .redirectErrorStream(true)
                .start();
        String forkedOutput;
        try (InputStream in = process.getInputStream()) {
            forkedOutput = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int status = process.waitFor();
        assertEquals(0, status, forkedOutput);
        assertTrue(forkedOutput.contains("hello"), forkedOutput);
    }

    @Test
    void packagesADependencyThatCarriesTheSameNameTwice() throws IOException {
        // Legal ZIP, readable by java.util.zip, and produced in practice by `zip -g` and by some shading
        // pipelines. The index keys on the record, so the two entries become a same-name chain.
        Path dependency = fixtures.resolve("libs/dup-dep.jar");
        Files.createDirectories(dependency.getParent());
        byte[] firstClass = sameClass("first");
        byte[] secondClass = sameClass("second");
        try (OutputStream out = Files.newOutputStream(dependency);
             ZipWriter writer = new ZipWriter(out, ZipWriter.DEFAULT_TIMESTAMP, false)) {
            writer.writeEntry("dup/same.txt", "first".getBytes(StandardCharsets.UTF_8));
            writer.writeEntry("dup/Same.class", firstClass);
            writer.writeEntry("dup/same.txt", "second".getBytes(StandardCharsets.UTF_8));
            writer.writeEntry("dup/Same.class", secondClass);
        }
        // The listed class is duplicated: hot-first order moves both copies and keeps their relative order.
        Path list = startupClasses("dup.Same\n");

        for (Compression compression : Compression.values()) {
            Path output = output();
            RunnerJarBuilder.build(spec(output)
                    .dependencies(List.of(Dependency.of(dependency)))
                    .compression(compression)
                    .startupClasses(list)
                    .build(), BuildLogger.noOp());

            try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
                Index index = reader.index();
                List<String> names = logicalNames(index, 1);
                assertEquals(2, names.stream().filter("dup/same.txt"::equals).count(),
                        compression + ": both records survive");
                assertEquals(2, names.stream().filter("dup/Same.class"::equals).count(),
                        compression + ": both class records survive");
                int firstRecord = RunnerBuildTestAccess.jarFirstEntry(index, 1);
                int limit = firstRecord + RunnerBuildTestAccess.jarEntryCount(index, 1);
                List<String> physicalNames = new ArrayList<>();
                List<String> physicalContents = new ArrayList<>();
                List<byte[]> physicalClasses = new ArrayList<>();
                for (int physical = firstRecord; physical < limit; physical++) {
                    if (!index.entryPhysical(physical)) {
                        continue;
                    }
                    physicalNames.add(index.entryName(physical));
                    if ("dup/same.txt".equals(index.entryName(physical))) {
                        physicalContents.add(new String(reader.read(physical), StandardCharsets.UTF_8));
                    } else if ("dup/Same.class".equals(index.entryName(physical))) {
                        physicalClasses.add(reader.read(physical));
                    }
                }
                assertEquals(compression == Compression.PRESERVE
                                ? List.of("dup/same.txt", "dup/Same.class", "dup/same.txt", "dup/Same.class")
                                : List.of("dup/Same.class", "dup/Same.class", "dup/same.txt", "dup/same.txt"),
                        physicalNames, compression + ": the listed class first, except in PRESERVE");
                assertEquals(List.of("first", "second"), physicalContents,
                        compression + ": physical enumeration keeps central-directory order and content");
                assertEquals(2, physicalClasses.size());
                assertArrayEquals(firstClass, physicalClasses.get(0), compression + ": the first copy first");
                assertArrayEquals(secondClass, physicalClasses.get(1), compression + ": the second copy second");

                List<String> lookupContents = new ArrayList<>();
                int record = index.find("dup/same.txt");
                while (record != IndexFormat.NO_INDEX) {
                    lookupContents.add(new String(reader.read(record), StandardCharsets.UTF_8));
                    record = RunnerBuildTestAccess.next(index, record);
                }
                assertEquals(List.of("second", "first"), lookupContents,
                        compression + ": lookup chain starts with the JDK-compatible last duplicate");
                List<byte[]> lookupClasses = new ArrayList<>();
                record = index.find("dup/Same.class");
                while (record != IndexFormat.NO_INDEX) {
                    lookupClasses.add(reader.read(record));
                    record = RunnerBuildTestAccess.next(index, record);
                }
                assertEquals(2, lookupClasses.size());
                assertArrayEquals(secondClass, lookupClasses.get(0),
                        compression + ": the listed class's chain starts with the JDK-compatible last duplicate");
                assertArrayEquals(firstClass, lookupClasses.get(1));
            }
        }
    }

    /** A class {@code dup.Same} whose static {@code value()} returns the given text. */
    private static byte[] sameClass(String value) {
        return java.lang.classfile.ClassFile.of().build(java.lang.constant.ClassDesc.of("dup.Same"),
                type -> type.withFlags(java.lang.classfile.ClassFile.ACC_PUBLIC)
                        .withSuperclass(java.lang.constant.ConstantDescs.CD_Object)
                        .withMethodBody("value", java.lang.constant.MethodTypeDesc.of(
                                        java.lang.constant.ConstantDescs.CD_String),
                                java.lang.classfile.ClassFile.ACC_PUBLIC | java.lang.classfile.ClassFile.ACC_STATIC,
                                code -> code.ldc(value).areturn()));
    }

    @Test
    void namesTheDependencyThatCannotBePackaged() throws IOException {
        Path dependency = corruptedDependency("libs/corrupt-stored.jar");

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output())
                .dependencies(List.of(Dependency.of(dependency)))
                .build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains(dependency.toString()),
                "a build with dozens of dependencies has to say which one: " + failure.getMessage());
    }

    // ------------------------------------------------------ the dependency file rule

    @Test
    void failsOnADirectoryDependencyBeforeWritingAnything() throws IOException {
        // What Maven resolves a reactor module to when the reactor did not run through package.
        Path module = Files.createDirectories(fixtures.resolve("file-rule/module/target/classes"));
        Path output = Files.createDirectories(fixtures.resolve("file-rule/directory-output")).resolve("app.jar");

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output)
                .addDependency(Dependency.of(module, "com.example:module:1.0"))
                .build(), BuildLogger.noOp()));

        String message = failure.getMessage();
        assertTrue(message.contains(module.toString()), message);
        assertTrue(message.contains("nests only JAR files"), message);
        assertTrue(message.contains("Package it as a JAR first"), message);
        assertTrue(message.contains("add it to the application output"), message);
        assertFalse(Files.exists(output), "a directory dependency must fail before anything is written");
        assertNoWorkDirectory(output.getParent());
    }

    @Test
    void skipsADependencyThatIsNotAZipArchiveWithOneWarning() throws IOException {
        Path pom = fixtures.resolve("file-rule/dep-lib-2.0.1.pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, "<project/>\n");
        Path output = output();

        RunnerJarResult result = RunnerJarBuilder.build(spec(output)
                .addDependency(Dependency.of(pom, "com.example:dep-lib:pom:2.0.1"))
                .build(), BuildLogger.noOp());

        assertEquals(3, result.dependencyCount(), "the three JAR dependencies are nested and the POM is not");
        List<String> mentions = result.warnings().stream()
                .filter(warning -> warning.contains(pom.toString()))
                .toList();
        assertEquals(1, mentions.size(), () -> "the POM must be reported exactly once: " + result.warnings());
        assertTrue(mentions.get(0).contains("is not a JAR file and was skipped"), mentions::toString);
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            assertEquals(4, reader.index().jarCount());
        }
    }

    @Test
    void nestsAnEmptyZipArchive() throws IOException {
        // An end of central directory record and nothing else, which is how an archive with no entries starts.
        byte[] endOfCentralDirectory = new byte[22];
        endOfCentralDirectory[0] = 'P';
        endOfCentralDirectory[1] = 'K';
        endOfCentralDirectory[2] = 5;
        endOfCentralDirectory[3] = 6;
        Path empty = fixtures.resolve("file-rule/empty.jar");
        Files.createDirectories(empty.getParent());
        Files.write(empty, endOfCentralDirectory);

        // HYBRID without a startup class list is STORED with one warning; see the HYBRID tests.
        for (Compression compression : new Compression[] {Compression.STORED, Compression.PRESERVE}) {
            Path output = output();
            RunnerJarResult result = RunnerJarBuilder.build(spec(output)
                    .dependencies(List.of(Dependency.of(empty)))
                    .compression(compression)
                    .build(), BuildLogger.noOp());

            assertEquals(1, result.dependencyCount(), compression::toString);
            assertTrue(result.warnings().isEmpty(), () -> compression + ": " + result.warnings());
            try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
                assertEquals("MICRONAUT-INF/lib/empty.jar", reader.index().jarName(1), compression::toString);
                assertEquals(0, RunnerBuildTestAccess.jarEntryCount(reader.index(), 1), compression::toString);
            }
        }
    }

    @Test
    void aTruncatedZipArchiveCannotBePackaged() throws IOException {
        byte[] whole = Files.readAllBytes(plainDependency);
        assertEquals("PK\u0003\u0004", new String(whole, 0, 4, StandardCharsets.ISO_8859_1));
        Path truncated = fixtures.resolve("file-rule/truncated.jar");
        Files.createDirectories(truncated.getParent());
        Files.write(truncated, Arrays.copyOf(whole, whole.length / 2));

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output())
                .dependencies(List.of(Dependency.of(truncated)))
                .build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains("The dependency " + truncated + " cannot be packaged"),
                failure.getMessage());
    }

    @Test
    void aMissingDependencyStillDoesNotExist() {
        Path missing = fixtures.resolve("file-rule/missing.jar");

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output())
                .addDependency(Dependency.of(missing))
                .build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains(missing + " does not exist"), failure.getMessage());
    }

    @Test
    void logsTheEffectiveOptionsOnceAndNoSummary() throws IOException {
        List<String> info = new ArrayList<>();
        BuildLogger logger = new BuildLogger() {
            @Override
            public void info(String message) {
                info.add(message);
            }

            @Override
            public void warn(String message) {
            }
        };

        RunnerJarResult result = RunnerJarBuilder.build(spec(output()).option("compression", "PRESERVE").build(),
                logger);

        List<String> options = info.stream().filter(line -> line.startsWith("Runner options: ")).toList();
        assertEquals(1, options.size(), () -> "the options are logged once: " + info);
        assertTrue(options.get(0).startsWith("Runner options: compression=PRESERVE, entryStub=true, "),
                options::toString);
        assertEquals(Arrays.stream(RunnerJarOption.values()).map(option -> option.optionName() + "="
                        + result.effectiveOptions().get(option.optionName())).collect(Collectors.joining(", ",
                        "Runner options: ", "")), options.get(0), "every option, in table order");
        assertEquals("PRESERVE", result.effectiveOptions().get("compression"));
        assertTrue(info.stream().noneMatch(line -> line.contains("Runner jar written to")),
                () -> "the plugins log the summary, not the builder: " + info);
    }

    @ParameterizedTest
    @EnumSource(Compression.class)
    void namesTheDependencyWhoseManifestFailsItsCrc(Compression compression) throws IOException {
        Path dependency = manifestCrcMismatch(fixtures.resolve("libs/manifest-crc-" + compression + ".jar"));
        Path output = output();
        Files.createDirectories(output.getParent());
        Files.write(output, PREVIOUS_OUTPUT);

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output)
                .dependencies(List.of(Dependency.of(dependency)))
                .compression(compression)
                .build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains(dependency.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("META-INF/MANIFEST.MF"), failure.getMessage());
        assertTrue(failure.getMessage().contains("CRC-32"), failure.getMessage());
        assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output));
    }

    @Test
    void parallelStagingIsByteIdenticalToSequentialStaging() throws IOException {
        Path directory = Files.createDirectories(fixtures.resolve("parallel"));
        Path classPath = directory.resolve("class-path-lib.jar");
        writeJar(classPath, manifest(attributes -> attributes.put(Attributes.Name.CLASS_PATH, "missing.jar")),
                Map.of("com/example/cp/Cp.class", "cp".getBytes(StandardCharsets.UTF_8)));
        Path noManifest = directory.resolve("no-manifest.jar");
        try (ZipWriter writer = ZipWriter.create(noManifest, ZipWriter.DEFAULT_TIMESTAMP)) {
            writer.writeEntry("com/example/bare/Bare.class", "bare".getBytes(StandardCharsets.UTF_8));
        }
        Path duplicate = directory.resolve("dup-dep.jar");
        try (OutputStream out = Files.newOutputStream(duplicate);
             ZipWriter writer = new ZipWriter(out, ZipWriter.DEFAULT_TIMESTAMP, false)) {
            writer.writeEntry("dup/same.txt", "first".getBytes(StandardCharsets.UTF_8));
            writer.writeEntry("dup/same.txt", "second".getBytes(StandardCharsets.UTF_8));
        }
        Path sameFileName = Files.createDirectories(directory.resolve("copy")).resolve("dep-lib.jar");
        Files.copy(plainDependency, sameFileName);
        // Last on the class path and first by size, so the submission order is not the class-path order.
        Path large = directory.resolve("large-lib.jar");
        byte[] payload = new byte[256 * 1024];
        new Random(130).nextBytes(payload);
        try (ZipWriter writer = ZipWriter.create(large, ZipWriter.DEFAULT_TIMESTAMP)) {
            writer.writeEntry("large/payload.bin", payload);
        }
        // Compiled with -g, so the strip step rewrites its classes and the pipeline runs inside the stages.
        Path debug = ClassFixtures.jar(directory.resolve("debug-lib.jar"), ClassFixtures.classes(
                ClassFixtures.compile(directory.resolve("debug-src"), directory.resolve("debug-classes"),
                        List.of("-g", "--release", "25"), Map.of(
                                "com/example/debug/Debug.java", """
                                        package com.example.debug;
                                        public class Debug {
                                            public static int sum(int[] values) {
                                                int total = 0;
                                                for (int value : values) {
                                                    total += value;
                                                }
                                                return total;
                                            }
                                        }
                                        """))));
        // Lambdas across two dependencies: what is generated for the first one's call sites depends on the
        // second one's classes, whichever of the two is staged first.
        Map<String, byte[]> lambdas = ClassFixtures.classes(ClassFixtures.compile(directory.resolve("lambda-src"),
                directory.resolve("lambda-classes"), List.of("-g", "--release", "25"), Map.of(
                        "com/example/caller/Caller.java", """
                                package com.example.caller;
                                import com.example.callee.Callee;
                                import java.util.function.Function;
                                import java.util.function.Supplier;
                                public class Caller {
                                    public static Function<String, String> across() {
                                        return Callee::twice;
                                    }
                                    public Supplier<String> own(String value) {
                                        return () -> value + across().apply(value);
                                    }
                                }
                                """,
                        "com/example/callee/Callee.java", """
                                package com.example.callee;
                                import java.util.function.Supplier;
                                public class Callee {
                                    public static String twice(String value) {
                                        return value + value;
                                    }
                                    public static Supplier<String> constant() {
                                        return () -> "constant";
                                    }
                                }
                                """)));
        Path caller = ClassFixtures.jar(directory.resolve("caller-lib.jar"),
                LambdaFixtures.select(lambdas, "com/example/caller/"));
        Path callee = ClassFixtures.jar(directory.resolve("callee-lib.jar"),
                LambdaFixtures.select(lambdas, "com/example/callee/"));
        List<Dependency> dependencies = Stream.of(classPath, plainDependency, multiReleaseDependency,
                        signedDependency, noManifest, duplicate, sameFileName, debug, caller, callee, large)
                .map(Dependency::of)
                .toList();

        // A startup class list in every mode: STORED orders the listed classes first, HYBRID also deflates the
        // others, afresh where a transform rewrote them, and PRESERVE ignores it.
        Path list = startupClasses("com.example.caller.Caller\ncom.example.dep.Dep\ncom.example.debug.Debug\n");

        for (Compression compression : Compression.values()) {
            Path sequential = output();
            Path parallel = output();
            Path wider = output();
            StagingLogger inline = new StagingLogger();
            StagingLogger pooled = new StagingLogger();
            RunnerJarResult sequentialResult = RunnerJarBuilder.build(spec(sequential)
                    .dependencies(dependencies).compression(compression).startupClasses(list).build(), inline, 1);
            RunnerJarResult parallelResult = RunnerJarBuilder.build(spec(parallel)
                    .dependencies(dependencies).compression(compression).startupClasses(list).build(), pooled, 4);
            RunnerJarResult widerResult = RunnerJarBuilder.build(spec(wider)
                    .dependencies(dependencies).compression(compression).startupClasses(list).build(),
                    BuildLogger.noOp(), 8);

            assertEquals(-1, Files.mismatch(sequential, parallel), compression + ": the same bytes");
            assertEquals(-1, Files.mismatch(sequential, wider), compression + ": the same bytes on 8 threads");
            assertEquals(sequentialResult.transforms(), parallelResult.transforms(),
                    compression + ": the same transform reports");
            assertEquals(sequentialResult.transforms(), widerResult.transforms(),
                    compression + ": the same transform reports on 8 threads");
            if (compression == Compression.HYBRID) {
                assertTrue(inspectHeader(parallel, "Nested compression").matches("\\d+ stored, [1-9]\\d* deflated"),
                        "HYBRID compressed the cold classes");
            }
            if (compression != Compression.PRESERVE) {
                assertEquals(List.of(LambdaDesugarer.NAME, LocalVariableStripper.NAME),
                        parallelResult.transforms().stream().map(TransformReport::step).toList(),
                        "the steps in the order they run");
                for (TransformReport report : parallelResult.transforms()) {
                    assertTrue(report.rewritten() > 0, parallelResult.transforms()::toString);
                }
                try (RunnerJarArchive reader = RunnerJarArchive.open(parallel)) {
                    assertTrue(reader.index().findClass("com.example.caller.Caller$$Lambda$R0")
                            != IndexFormat.NO_INDEX, "the call site into the other dependency is desugared");
                    assertTrue(reader.index().findClass("com.example.callee.Callee$$Lambda$R0")
                            != IndexFormat.NO_INDEX);
                }
            } else {
                assertEquals(List.of(), parallelResult.transforms());
            }
            List<String> warnings = parallelResult.warnings();
            assertEquals(sequentialResult.warnings(), warnings, compression + ": the same warnings");
            int classPathWarning = indexOfWarning(warnings, "declares Class-Path");
            int signatureWarning = indexOfWarning(warnings, "is signed");
            assertTrue(classPathWarning >= 0 && classPathWarning < signatureWarning,
                    compression + ": warnings in class-path order: " + warnings);
            assertEquals(Set.of(), inline.stageThreads, compression + ": parallelism 1 creates no thread");
            assertEquals(Set.of("micronaut-runner-stage-1", "micronaut-runner-stage-2",
                            "micronaut-runner-stage-3", "micronaut-runner-stage-4"), pooled.stageThreads,
                    compression + ": four daemon staging threads");
            assertEquals(Set.of(Thread.currentThread()), pooled.warningThreads,
                    compression + ": warnings come from the calling thread only");
            assertNoStagingThreads();
            try (RunnerJarArchive reader = RunnerJarArchive.open(parallel)) {
                assertEquals("MICRONAUT-INF/lib/dep-lib-1.jar", reader.index().jarName(7),
                        compression + ": the second dep-lib.jar gets a unique name");
            }
        }
    }

    @Test
    void theDesugarReportIsThereWithTheOptionOnAndAbsentWithItOff() throws IOException {
        Path directory = Files.createDirectories(fixtures.resolve("desugar-report"));
        Path dependency = ClassFixtures.jar(directory.resolve("lambda-lib.jar"), ClassFixtures.classes(
                ClassFixtures.compile(directory.resolve("src"), directory.resolve("classes"),
                        List.of("--release", "25"), Map.of("com/example/lambda/Lambdas.java", """
                                package com.example.lambda;
                                import java.util.function.Supplier;
                                public class Lambdas {
                                    public static Supplier<String> constant() {
                                        return () -> "constant";
                                    }
                                }
                                """))));
        List<String> info = new ArrayList<>();
        BuildLogger logger = new BuildLogger() {
            @Override
            public void info(String message) {
                info.add(message);
            }

            @Override
            public void warn(String message) {
            }
        };
        Path on = output();
        Path off = output();

        RunnerJarResult defaults = RunnerJarBuilder.build(spec(on)
                .dependencies(List.of(Dependency.of(dependency))).build(), logger);
        RunnerJarResult disabled = RunnerJarBuilder.build(spec(off)
                .dependencies(List.of(Dependency.of(dependency))).option("desugarLambdas", "false").build(),
                BuildLogger.noOp());

        TransformReport report = defaults.transforms().stream()
                .filter(transform -> transform.step().equals("desugarLambdas")).findFirst().orElseThrow();
        assertEquals(1, report.rewritten(), report::toString);
        assertEquals(0, report.fallbacks(), report::toString);
        assertTrue(report.bytesSaved() < 0, "the step adds classes: " + report);
        assertEquals("true", defaults.effectiveOptions().get("desugarLambdas"));
        assertTrue(disabled.transforms().stream().noneMatch(transform -> transform.step().equals("desugarLambdas")),
                disabled.transforms()::toString);
        assertEquals("false", disabled.effectiveOptions().get("desugarLambdas"));
        assertEquals(1, info.stream().filter(line -> line.startsWith("Desugared 1 lambda call sites into 1"
                + " generated classes in 1 dependencies")).count(), info::toString);
        try (ZipFile zip = new ZipFile(on.toFile())) {
            String transforms = new String(zip.getInputStream(zip.getEntry(IndexFormat.TRANSFORMS_ENTRY_NAME))
                    .readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(transforms.contains("MICRONAUT-INF/lib/lambda-lib.jar\tdesugarLambdas\t1\t0\t0\t"),
                    transforms);
            assertTrue(transforms.contains("lambdas\tMICRONAUT-INF/lib/lambda-lib.jar\trewritten=1\tgenerated=1"
                    + "\tbridges=0\tnestFallbacks=0\tleft=0\n"), transforms);
        }
        try (RunnerJarArchive reader = RunnerJarArchive.open(on)) {
            assertTrue(reader.index().findClass("com.example.lambda.Lambdas$$Lambda$R0") != IndexFormat.NO_INDEX);
        }
        try (RunnerJarArchive reader = RunnerJarArchive.open(off)) {
            assertEquals(IndexFormat.NO_INDEX, reader.index().findClass("com.example.lambda.Lambdas$$Lambda$R0"));
        }
    }

    @Test
    void reportsTheFirstFailingDependencyInClassPathOrder() throws IOException {
        Path corruptA = corruptedDependency("first-failure/corrupt-a.jar");
        Path corruptB = corruptedDependency("first-failure/corrupt-b.jar");
        Path output = Files.createDirectories(fixtures.resolve("first-failure-output")).resolve("runner.jar");
        Files.write(output, PREVIOUS_OUTPUT);

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output)
                .dependencies(Stream.of(plainDependency, corruptA, multiReleaseDependency, corruptB)
                        .map(Dependency::of)
                        .toList())
                .compression(Compression.STORED)
                .build(), BuildLogger.noOp(), 4));

        assertTrue(failure.getMessage().contains(corruptA.toString()), failure.getMessage());
        assertFalse(failure.getMessage().contains(corruptB.toString()), failure.getMessage());
        assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output));
        assertNoWorkDirectory(output.getParent());
        assertNoStagingThreads();
    }

    @Test
    void rejectsAStagingParallelismBelowOne() {
        assertThrows(IllegalArgumentException.class,
                () -> RunnerJarBuilder.build(spec(output()).build(), BuildLogger.noOp(), 0));
    }

    @Test
    void surfacesAnInterruptedWaitForAStageAsAnInterruptedIOException() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> RunnerJarBuilder.awaitStage(new CompletableFuture<>()));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is restored");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void waitsForStagingThreadsThroughAnInterruptAndFailsWhenTheyDoNotStop() throws Exception {
        RunnerJarBuilder.StageThreads threads = new RunnerJarBuilder.StageThreads();
        ExecutorService pool = Executors.newFixedThreadPool(1, threads);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pool.execute(() -> {
            started.countDown();
            // A stage that does not answer an interrupt, like one blocked in uninterruptible I/O.
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // Keep waiting.
                }
            }
        });
        started.await();
        IOException building = new IOException("what the build is already failing with");
        Duration timeout = Duration.ofMillis(200);

        long start = System.nanoTime();
        Thread.currentThread().interrupt();
        try {
            RunnerJarBuilder.stopStaging(pool, threads, building, timeout);
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is restored");
        } finally {
            Thread.interrupted();
        }
        assertTrue(System.nanoTime() - start >= timeout.toNanos(), "an interrupt does not cut the wait short");
        assertEquals(1, building.getSuppressed().length, "the timeout is added to the build's own failure");
        assertThrows(IOException.class, () -> RunnerJarBuilder.stopStaging(pool, threads, null, Duration.ZERO),
                "without a failure already on its way, the timeout fails the build");

        release.countDown();
        RunnerJarBuilder.stopStaging(pool, threads, null, Duration.ofSeconds(30));
        assertNoStagingThreads();
    }

    private static int indexOfWarning(List<String> warnings, String text) {
        for (int i = 0; i < warnings.size(); i++) {
            if (warnings.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    private static void assertNoStagingThreads() {
        List<String> alive = Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith(RunnerJarBuilder.STAGE_THREAD_PREFIX))
                .toList();
        assertEquals(List.of(), alive, "staging threads outlived the build");
    }

    @Test
    void leavesTheOutputAloneWhenTheArchiveFailsVerification() throws IOException {
        // Verification has to happen before the archive is moved onto the output path, or a build that
        // fails still publishes the artifact it has just refused.
        Path dependency = corruptedDependency("libs/corrupt-preserve.jar");
        Path output = output();
        Files.createDirectories(output.getParent());
        byte[] previous = "the artifact that was already there".getBytes(StandardCharsets.UTF_8);
        Files.write(output, previous);

        System.setProperty(RunnerJarBuilder.VERIFY_ALL_PROPERTY, "true");
        try {
            assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output)
                    .dependencies(List.of(Dependency.of(dependency)))
                    .compression(Compression.PRESERVE)
                    .build(), BuildLogger.noOp()));
        } finally {
            System.clearProperty(RunnerJarBuilder.VERIFY_ALL_PROPERTY);
        }

        assertArrayEquals(previous, Files.readAllBytes(output),
                "a failed build must not replace what was at the output path");
    }

    @Test
    void packagesValidStoredDeflatedAndEmptyApplicationEntries() throws IOException {
        Path applicationJar = fixtures.resolve("valid-mixed-application.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(applicationJar))) {
            deflated(zip, "com/example/Application.class",
                    Files.readAllBytes(applicationClasses.resolve("com/example/Application.class")));
            stored(zip, "stored.txt", "STORED".getBytes(StandardCharsets.UTF_8));
            deflated(zip, "deflated.txt", "DEFLATED".getBytes(StandardCharsets.UTF_8));
            stored(zip, "empty-stored.txt", new byte[0]);
            deflated(zip, "empty-deflated.txt", new byte[0]);
        }

        Path output = output();
        RunnerJarBuilder.build(spec(output)
                .applicationOutput(List.of(applicationJar))
                .dependencies(List.of())
                .build(), BuildLogger.noOp());

        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertEquals("STORED", new String(reader.read(index.find("stored.txt")), StandardCharsets.UTF_8));
            assertEquals("DEFLATED", new String(reader.read(index.find("deflated.txt")), StandardCharsets.UTF_8));
            assertArrayEquals(new byte[0], reader.read(index.find("empty-stored.txt")));
            assertArrayEquals(new byte[0], reader.read(index.find("empty-deflated.txt")));
        }
    }

    @ParameterizedTest(name = "{0}, {1} bytes")
    @MethodSource("io.micronaut.runner.build.ZipReaderTest#transferBoundaryEntries")
    void storesADependencyEntryWhateverItsSizeIsNextToTheTransferBuffer(Payload payload, int size)
            throws IOException {
        // A STORED build inflates every entry of a dependency through the reader's 64 KiB transfer buffers.
        // An entry that fills one exactly when its compressed bytes run out is as valid as any other.
        String name = "data/boundary.bin";
        byte[] content = payload.bytes(size);
        Path directory = Files.createDirectories(fixtures.resolve("boundary-dependency/" + payload + "-" + size));
        Path dependency = directory.resolve("boundary-lib.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(dependency))) {
            deflated(zip, name, content);
        }
        Path output = directory.resolve("runner.jar");

        RunnerJarBuilder.build(spec(output)
                .dependencies(List.of(Dependency.of(dependency)))
                .compression(Compression.STORED)
                .build(), BuildLogger.noOp());

        Path nested = directory.resolve("nested.jar");
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertTrue(RunnerBuildTestAccess.nestedStored(index));
            int record = index.find(name);
            assertEquals(IndexFormat.METHOD_STORED, index.entryMethod(record));
            assertArrayEquals(content, reader.read(record), "the entry as the launcher reads it");
            Files.write(nested, reader.source().readFully(index.jarDataOffset(1), (int) index.jarDataLength(1)));
        }
        try (ZipFile oracle = new ZipFile(nested.toFile())) {
            ZipEntry entry = oracle.getEntry(name);
            assertEquals(ZipEntry.STORED, entry.getMethod());
            assertArrayEquals(content, readAll(oracle, entry), "the entry of the nested jar");
        }
        // The largest case leaves some 25 MiB behind, and the fixture directory lives as long as the class.
        Files.delete(nested);
        Files.delete(output);
        Files.delete(dependency);
    }

    @ParameterizedTest
    @EnumSource(Compression.class)
    void packagesApplicationJarEntriesOfEverySizeNextToTheTransferBuffer(Compression compression)
            throws IOException {
        // The entries of an application jar are streamed from the jar in either mode, the same way.
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (Payload payload : Payload.values()) {
            for (int size : transferBoundarySizes()) {
                contents.put("boundary/" + payload + "-" + size + ".bin", payload.bytes(size));
            }
        }
        Path directory = Files.createDirectories(fixtures.resolve("boundary-application/" + compression));
        Path applicationJar = directory.resolve("application.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(applicationJar))) {
            deflated(zip, "com/example/Application.class",
                    Files.readAllBytes(applicationClasses.resolve("com/example/Application.class")));
            for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
                deflated(zip, entry.getKey(), entry.getValue());
            }
        }
        Path output = directory.resolve("runner.jar");

        RunnerJarBuilder.build(spec(output)
                .applicationOutput(List.of(applicationJar))
                .dependencies(List.of())
                .compression(compression)
                .build(), BuildLogger.noOp());

        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
                assertArrayEquals(entry.getValue(), reader.read(index.find(entry.getKey())), entry.getKey());
            }
        }
        Files.delete(output);
        Files.delete(applicationJar);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsCorruptStoredApplicationEntriesWithoutChangingInputOrOutput(boolean verifyAll) throws IOException {
        Path applicationJar = fixtures.resolve("application-crc-mismatch-" + verifyAll + ".jar");
        try (ZipWriter writer = ZipWriter.create(applicationJar, ZipWriter.DEFAULT_TIMESTAMP)) {
            writer.writeEntry("com/example/Application.class",
                    Files.readAllBytes(applicationClasses.resolve("com/example/Application.class")));
            writer.writeEntry("data.txt", "GOOD".getBytes(StandardCharsets.UTF_8));
        }
        long dataOffset;
        try (ZipReader reader = ZipReader.open(applicationJar)) {
            dataOffset = reader.entry("data.txt").orElseThrow().dataOffset();
        }
        byte[] applicationBytes = Files.readAllBytes(applicationJar);
        applicationBytes[(int) dataOffset] = 'B';
        Files.write(applicationJar, applicationBytes);

        Path output = output();
        Files.createDirectories(output.getParent());
        byte[] previousOutput = "previous application artifact".getBytes(StandardCharsets.UTF_8);
        Files.write(output, previousOutput);
        byte[] corruptInput = Files.readAllBytes(applicationJar);
        String previousVerifyAll = System.getProperty(RunnerJarBuilder.VERIFY_ALL_PROPERTY);
        System.setProperty(RunnerJarBuilder.VERIFY_ALL_PROPERTY, Boolean.toString(verifyAll));
        IOException failure;
        try {
            failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output)
                    .applicationOutput(List.of(applicationJar))
                    .dependencies(List.of())
                    .build(), BuildLogger.noOp()));
        } finally {
            if (previousVerifyAll == null) {
                System.clearProperty(RunnerJarBuilder.VERIFY_ALL_PROPERTY);
            } else {
                System.setProperty(RunnerJarBuilder.VERIFY_ALL_PROPERTY, previousVerifyAll);
            }
        }

        assertTrue(failure.getMessage().contains(applicationJar.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("data.txt"), failure.getMessage());
        assertTrue(failure.getMessage().contains("CRC-32"), failure.getMessage());
        assertArrayEquals(corruptInput, Files.readAllBytes(applicationJar),
                "rejecting a damaged application jar must leave the input byte-identical");
        assertArrayEquals(previousOutput, Files.readAllBytes(output),
                "rejecting a damaged application jar must not replace the previous output");
    }

    @Test
    void rejectsUnusedCompressedBytesInAnApplicationJarWithoutReplacingOutput() throws IOException {
        Path applicationJar = deflatedWithTrailingByte(fixtures.resolve("application-trailing-byte.jar"),
                "com/example/Application.class",
                Files.readAllBytes(applicationClasses.resolve("com/example/Application.class")));
        Path output = output();
        Files.createDirectories(output.getParent());
        byte[] previous = "previous application artifact".getBytes(StandardCharsets.UTF_8);
        Files.write(output, previous);

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output)
                .applicationOutput(List.of(applicationJar))
                .dependencies(List.of())
                .build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains("Application.class"), failure.getMessage());
        assertTrue(failure.getMessage().contains("compressed"), failure.getMessage());
        assertArrayEquals(previous, Files.readAllBytes(output),
                "a malformed application jar must not replace the previous output");
    }

    @ParameterizedTest
    @EnumSource(Compression.class)
    void rejectsUnusedCompressedBytesInAnEmptyApplicationJarEntry(Compression compression) throws IOException {
        // Empty, so only streaming it from the jar checks its DEFLATE framing: an entry written from a constant
        // because it is empty would be packaged without a word.
        String name = SERVICE_DIRECTORY + "com.example.Empty";
        Path jar = deflatedWithTrailingByte(fixtures.resolve("empty-entry-trailing-byte-" + compression + ".jar"),
                name, new byte[0]);
        Path output = existingOutput();

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output)
                .applicationOutput(List.of(applicationClasses, jar))
                .compression(compression)
                .build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains(name), failure.getMessage());
        assertTrue(failure.getMessage().contains("unused compressed bytes"), failure.getMessage());
        assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output),
                "a malformed application jar must not replace the previous output");
        assertNoWorkDirectory(output.getParent());
        // A reader the failed build left open would keep the jar mapped, and Windows could not delete it.
        Files.delete(jar);
    }

    @Test
    void rejectsUnusedCompressedBytesInADependencyWithoutReplacingOutput() throws IOException {
        Path dependency = deflatedWithTrailingByte(fixtures.resolve("libs/dependency-trailing-byte.jar"),
                "data.txt", "ABCDEF".getBytes(StandardCharsets.UTF_8));
        Path output = output();
        Files.createDirectories(output.getParent());
        byte[] previous = "previous dependency artifact".getBytes(StandardCharsets.UTF_8);
        Files.write(output, previous);

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output)
                .dependencies(List.of(Dependency.of(dependency)))
                .build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains(dependency.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("data.txt"), failure.getMessage());
        assertTrue(failure.getMessage().contains("compressed"), failure.getMessage());
        assertArrayEquals(previous, Files.readAllBytes(output),
                "a malformed dependency must not replace the previous output");
    }

    @Test
    void fullPreserveVerificationRejectsUnusedCompressedBytesWithoutReplacingOutput() throws IOException {
        Path dependency = deflatedWithTrailingByte(fixtures.resolve("libs/preserved-trailing-byte.jar"),
                "data.txt", "ABCDEF".getBytes(StandardCharsets.UTF_8));

        IOException failure = assertPreserveRejectedWithoutReplacingOutput(dependency, true);

        assertTrue(failure.getMessage().contains("unused compressed bytes"), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsLocalCentralNameDisagreementBeforePreserving(boolean verifyAll) throws IOException {
        Path dependency = mismatchedLocalNameDependency("libs/mismatched-name-" + verifyAll + ".jar");

        IOException failure = assertPreserveRejectedWithoutReplacingOutput(dependency, verifyAll);

        assertTrue(failure.getMessage().contains(dependency.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("safe.txt"), failure.getMessage());
        assertTrue(failure.getMessage().contains("local"), failure.getMessage());
        assertTrue(failure.getMessage().contains("name"), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsStoredSizeDisagreementBeforePreserving(boolean verifyAll) throws IOException {
        Path dependency = mismatchedStoredSizeDependency("libs/mismatched-size-" + verifyAll + ".jar");

        IOException failure = assertPreserveRejectedWithoutReplacingOutput(dependency, verifyAll);

        assertTrue(failure.getMessage().contains(dependency.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("bad.txt"), failure.getMessage());
        assertTrue(failure.getMessage().contains("STORED"), failure.getMessage());
        assertTrue(failure.getMessage().contains("size"), failure.getMessage());
    }

    @Test
    void rejectsADependencyReachedThroughAnOutputDirectoryAlias() throws IOException {
        Path source = Files.createDirectories(fixtures.resolve("dependency-alias-source"));
        Path dependency = source.resolve("dep.jar");
        Files.copy(plainDependency, dependency);
        byte[] original = Files.readAllBytes(dependency);
        Path alias = createSymbolicLink(fixtures.resolve("dependency-alias"), source);

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(alias.resolve("dep.jar"))
                .dependencies(List.of(Dependency.of(dependency)))
                .build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains("also a dependency"), failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(dependency),
                "rejecting an aliased output must leave the dependency byte-identical");
    }

    @Test
    void rejectsAnExistingHardLinkToADependency() throws IOException {
        Path dependency = fixtures.resolve("hard-link-dependency.jar");
        Files.copy(plainDependency, dependency);
        byte[] original = Files.readAllBytes(dependency);
        Path output = createHardLink(fixtures.resolve("hard-link-output.jar"), dependency);

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output)
                .dependencies(List.of(Dependency.of(dependency)))
                .build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains("also a dependency"), failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(dependency),
                "rejecting an existing-file alias must leave the dependency byte-identical");
    }

    @Test
    void rejectsACaseAliasToADependencyOnCaseInsensitiveFileSystems() throws IOException {
        Path directory = Files.createDirectories(fixtures.resolve("case-alias"));
        Path dependency = directory.resolve("dependency.jar");
        Files.copy(plainDependency, dependency);
        Path output = directory.resolve("DEPENDENCY.JAR");
        Assumptions.assumeTrue(Files.exists(output), "the test file system is case-sensitive");
        byte[] original = Files.readAllBytes(dependency);

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output)
                .dependencies(List.of(Dependency.of(dependency)))
                .build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains("also a dependency"), failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(dependency),
                "rejecting a case alias must leave the dependency byte-identical");
    }

    @Test
    void rejectsAnApplicationJarReachedThroughAnOutputAlias() throws IOException {
        Path applicationJar = fixtures.resolve("application-input.jar");
        Files.copy(plainDependency, applicationJar);
        byte[] original = Files.readAllBytes(applicationJar);
        Path output = createSymbolicLink(fixtures.resolve("application-input-alias.jar"), applicationJar);

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output)
                .applicationOutput(List.of(applicationJar))
                .dependencies(List.of())
                .build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains("inside the application output"), failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(applicationJar),
                "rejecting an aliased output must leave the application jar byte-identical");
    }

    @Test
    void rejectsANonexistentOutputBelowAnAliasedApplicationDirectory() throws IOException {
        Path alias = createSymbolicLink(fixtures.resolve("application-directory-alias"), applicationClasses);
        Path output = alias.resolve("new/subdirectory/runner.jar");

        IOException failure = assertThrows(IOException.class,
                () -> RunnerJarBuilder.build(spec(output).build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains("inside the application output"), failure.getMessage());
        assertFalse(Files.exists(output), "validation must fail before creating the output directories");
    }

    @Test
    void rejectsAManifestSourceReachedThroughAnOutputDirectoryAlias() throws IOException {
        Path source = Files.createDirectories(fixtures.resolve("manifest-alias-source"));
        Path manifest = source.resolve("MANIFEST.MF");
        byte[] original = "Manifest-Version: 1.0\nImplementation-Title: original\n\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(manifest, original);
        Path alias = createSymbolicLink(fixtures.resolve("manifest-alias"), source);

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(alias.resolve("MANIFEST.MF"))
                .applicationManifest(manifest)
                .build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains("also the application manifest source"), failure.getMessage());
        assertArrayEquals(original, Files.readAllBytes(manifest),
                "rejecting an aliased output must leave the manifest source byte-identical");
    }

    @Test
    void keepsAMissingManifestSourceOptional() throws IOException {
        Path missing = fixtures.resolve("missing-application-manifest.jar");
        Path output = output();

        RunnerJarResult result = RunnerJarBuilder.build(spec(output)
                .applicationManifest(missing)
                .build(), BuildLogger.noOp());

        assertTrue(Files.isRegularFile(output));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("does not exist")),
                "the missing optional source is reported: " + result.warnings());
    }

    @Test
    void followsSymbolicLinksInsideApplicationDirectoriesLikeAClassPath() throws IOException {
        Path shared = fixtures.resolve("links/shared");
        Files.createDirectories(shared.resolve("dir"));
        Map<String, byte[]> expected = new LinkedHashMap<>();
        expected.put("logback.xml", "<configuration/>".getBytes(StandardCharsets.UTF_8));
        expected.put("relative.txt", "reached through a relative link".getBytes(StandardCharsets.UTF_8));
        expected.put("linked/inner.txt", "inside a linked directory".getBytes(StandardCharsets.UTF_8));
        Files.write(shared.resolve("logback.xml"), expected.get("logback.xml"));
        Files.write(shared.resolve("relative.txt"), expected.get("relative.txt"));
        Files.write(shared.resolve("dir/inner.txt"), expected.get("linked/inner.txt"));
        Path tree = applicationTree(fixtures.resolve("links/app"));
        createSymbolicLink(tree.resolve("logback.xml"), shared.resolve("logback.xml"));
        createSymbolicLink(tree.resolve("relative.txt"), Path.of("..", "shared", "relative.txt"));
        createSymbolicLink(tree.resolve("linked"), shared.resolve("dir"));
        Path output = existingOutput();

        buildApplicationTree(tree, output);

        try (ZipReader archive = ZipReader.open(output)) {
            for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
                String name = IndexFormat.CLASSES_PREFIX + entry.getKey();
                assertArrayEquals(entry.getValue(), archive.read(archive.entry(name).orElseThrow()),
                        name + " carries the bytes of the link's target");
            }
        }
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
                int record = reader.index().find(entry.getKey());
                assertNotEquals(IndexFormat.NO_INDEX, record, entry.getKey() + " should resolve at runtime");
                assertArrayEquals(entry.getValue(), reader.read(record),
                        "the logical runtime lookup of " + entry.getKey() + " returns the target's bytes");
            }
        }
        Path again = output();
        buildApplicationTree(tree, again);
        assertArrayEquals(Files.readAllBytes(output), Files.readAllBytes(again),
                "a tree with symbolic links packages reproducibly");
    }

    @Test
    void rejectsADirectoryLinkToItsOwnDirectory() throws IOException {
        Path tree = applicationTree(fixtures.resolve("cycle-self/app"));
        Path loop = createSymbolicLink(Files.createDirectories(tree.resolve("a")).resolve("loop"), Path.of("."));

        assertCycleRejected(tree, loop);
    }

    @Test
    void rejectsTwoSiblingLinksToTheirOwnDirectory() throws IOException {
        Path tree = applicationTree(fixtures.resolve("cycle-siblings/app"));
        Path directory = Files.createDirectories(tree.resolve("a"));
        Path first = createSymbolicLink(directory.resolve("l1"), Path.of("."));
        createSymbolicLink(directory.resolve("l2"), Path.of("."));

        assertCycleRejected(tree, first);
    }

    @Test
    void rejectsADirectoryLinkThatLeadsBackAboveTheApplicationDirectory() throws IOException {
        // The link reaches <fixtures>/cycle-up, whose only child is the application directory: the walk comes
        // back to its own root, far from the directory that holds the output.
        Path tree = applicationTree(fixtures.resolve("cycle-up/app"));
        Path up = createSymbolicLink(Files.createDirectories(tree.resolve("a")).resolve("up"), Path.of("..", ".."));

        assertCycleRejected(tree, up);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../missing/logback.xml", "logback.xml"})
    void rejectsASymbolicLinkThatDoesNotResolve(String target) throws IOException {
        // The second target is the link itself, which no file system can resolve either.
        Path tree = applicationTree(fixtures.resolve("dangling-" + (target.contains("/") ? "missing" : "self"))
                .resolve("app"));
        Path link = createSymbolicLink(tree.resolve("logback.xml"), Path.of(target));
        Path output = existingOutput();

        IOException failure = assertThrows(IOException.class, () -> buildApplicationTree(tree, output));

        assertTrue(failure.getMessage().contains(link.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains(" to " + Path.of(target) + ","), failure.getMessage());
        assertTrue(failure.getMessage().contains("maven-resources-plugin 3.4.0"), failure.getMessage());
        assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output),
                "a dangling link must leave an existing good output intact");
        assertNoWorkDirectory(output.getParent());
    }

    @Test
    void rejectsADirectoryLinkToTheOutputDirectoryOrOneOfItsAncestors() throws IOException {
        Path output = existingOutput();
        List<Path> targets = List.of(output.getParent(), fixtures);
        for (int i = 0; i < targets.size(); i++) {
            Path tree = applicationTree(fixtures.resolve("output-guard-" + i + "/app"));
            Path link = createSymbolicLink(tree.resolve("linked"), targets.get(i));

            IOException failure = assertThrows(IOException.class, () -> buildApplicationTree(tree, output));

            assertTrue(failure.getMessage().contains(link.toString()), failure.getMessage());
            assertTrue(failure.getMessage().contains("would read what it is writing"), failure.getMessage());
            assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output),
                    "a link to " + targets.get(i) + " must leave an existing good output intact");
            assertNoWorkDirectory(output.getParent());
        }
    }

    @Test
    void rejectsAnOutputThatIsASymbolicLinkInsideAnApplicationDirectory() throws IOException {
        Path tree = applicationTree(fixtures.resolve("output-link/app"));
        Path outside = tree.resolveSibling("outside.jar");
        Files.write(outside, PREVIOUS_OUTPUT);
        Path output = createSymbolicLink(tree.resolve("runner.jar"), outside);

        IOException failure = assertThrows(IOException.class, () -> buildApplicationTree(tree, output));

        assertTrue(failure.getMessage().contains("is inside the application output"), failure.getMessage());
        assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(outside),
                "rejecting the output link must leave the file it points to byte-identical");
        assertNoWorkDirectory(tree);
    }

    private void assertCycleRejected(Path tree, Path link) throws IOException {
        Path output = existingOutput();

        IOException failure = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThrows(IOException.class, () -> buildApplicationTree(tree, output)));

        assertTrue(failure.getMessage().contains(link.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("symbolic-link cycles are not supported"), failure.getMessage());
        assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output),
                "a symbolic-link cycle must leave an existing good output intact");
        assertNoWorkDirectory(output.getParent());
    }

    /** A new application directory holding only the main class, for the symbolic-link tests to add to. */
    private static Path applicationTree(Path root) throws IOException {
        Path mainClass = root.resolve("com/example/Application.class");
        Files.createDirectories(mainClass.getParent());
        Files.copy(applicationClasses.resolve("com/example/Application.class"), mainClass);
        return root;
    }

    /** A new output path that already holds {@link #PREVIOUS_OUTPUT}, as a previous good build would. */
    private Path existingOutput() throws IOException {
        Path output = output();
        Files.createDirectories(output.getParent());
        Files.write(output, PREVIOUS_OUTPUT);
        return output;
    }

    private RunnerJarResult buildApplicationTree(Path tree, Path output) throws IOException {
        return RunnerJarBuilder.build(spec(output)
                .applicationOutput(List.of(tree))
                .dependencies(List.of())
                .build(), BuildLogger.noOp());
    }

    private static void assertNoWorkDirectory(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            List<Path> left = children
                    .filter(child -> child.getFileName().toString().startsWith(".micronaut-runner-"))
                    .toList();
            assertTrue(left.isEmpty(), "work directories left behind: " + left);
        }
    }

    // The junction tests below mirror the symbolic-link tests above with a Windows directory junction, which
    // Java reports as a directory rather than as a link (#225). Each one has its own temporary directory, which
    // holds its output too, runs every build within a time limit, and removes every junction left in that
    // directory, so that nothing, JUnit's own cleanup included, walks through one afterwards.

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void followsDirectoryJunctionsInsideApplicationDirectoriesLikeAClassPath(@TempDir Path parent) throws Throwable {
        withJunctions(parent, () -> {
            Path target = Files.createDirectories(parent.resolve("shared/dir/nested"));
            Map<String, byte[]> expected = new LinkedHashMap<>();
            expected.put("linked/inner.txt", "inside a junction".getBytes(StandardCharsets.UTF_8));
            expected.put("linked/nested/deeper.txt", "below a junction".getBytes(StandardCharsets.UTF_8));
            Files.write(target.resolveSibling("inner.txt"), expected.get("linked/inner.txt"));
            Files.write(target.resolve("deeper.txt"), expected.get("linked/nested/deeper.txt"));
            Path tree = applicationTree(parent.resolve("app"));
            createJunction(tree.resolve("linked"), target.getParent());
            Path output = existingOutput(parent);

            buildWithin(JUNCTION_TIMEOUT, tree, output);

            try (ZipReader archive = ZipReader.open(output)) {
                for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
                    String name = IndexFormat.CLASSES_PREFIX + entry.getKey();
                    assertArrayEquals(entry.getValue(), archive.read(archive.entry(name).orElseThrow()),
                            name + " carries the bytes of the junction's target");
                }
            }
            try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
                for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
                    int record = reader.index().find(entry.getKey());
                    assertNotEquals(IndexFormat.NO_INDEX, record, entry.getKey() + " should resolve at runtime");
                    assertArrayEquals(entry.getValue(), reader.read(record),
                            "the logical runtime lookup of " + entry.getKey() + " returns the target's bytes");
                }
            }
            Path again = output.resolveSibling("again.jar");
            buildWithin(JUNCTION_TIMEOUT, tree, again);
            assertArrayEquals(Files.readAllBytes(output), Files.readAllBytes(again),
                    "a tree with a directory junction packages reproducibly");
        });
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsADirectoryJunctionToItsOwnDirectory(@TempDir Path parent) throws Throwable {
        withJunctions(parent, () -> {
            Path tree = applicationTree(parent.resolve("app"));
            Path directory = Files.createDirectories(tree.resolve("a"));
            Path loop = createJunction(directory.resolve("loop"), directory);

            assertJunctionCycleRejected(tree, loop, loop, existingOutput(parent));
        });
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsTwoSiblingJunctionsToTheirOwnDirectory(@TempDir Path parent) throws Throwable {
        withJunctions(parent, () -> {
            Path tree = applicationTree(parent.resolve("app"));
            Path directory = Files.createDirectories(tree.resolve("a"));
            Path first = createJunction(directory.resolve("l1"), directory);
            createJunction(directory.resolve("l2"), directory);

            assertJunctionCycleRejected(tree, first, first, existingOutput(parent));
        });
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsADirectoryJunctionThatLeadsBackAboveTheApplicationDirectory(@TempDir Path parent)
            throws Throwable {
        withJunctions(parent, () -> {
            // The junction reaches <parent>/cycle-up, whose only child is the application directory: the walk
            // comes back to its own root, far from the directory that holds the output. It notices that at the
            // plain directory a/up/app, below the junction, and still names the junction.
            Path above = parent.resolve("cycle-up");
            Path tree = applicationTree(above.resolve("app"));
            Path up = createJunction(Files.createDirectories(tree.resolve("a")).resolve("up"), above);

            assertJunctionCycleRejected(tree, up.resolve("app"), up, existingOutput(parent));
        });
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void keepsTheSymbolicLinkWordingForACycleBelowAJunctionThatIsNotPartOfIt(@TempDir Path parent)
            throws Throwable {
        withJunctions(parent, () -> {
            // The junction only leads into the directory where a symbolic link to itself closes the cycle, so
            // the cycle runs through no junction, and the message neither names one nor blames one.
            Path outside = Files.createDirectories(parent.resolve("outside"));
            Path tree = applicationTree(parent.resolve("app"));
            Path linked = createJunction(tree.resolve("linked"), outside);
            createSymbolicLink(outside.resolve("loop"), Path.of("."));
            Path output = existingOutput(parent);

            IOException failure = assertThrows(IOException.class,
                    () -> buildWithin(Duration.ofSeconds(10), tree, output));

            String message = failure.getMessage();
            assertTrue(message.startsWith("The application output " + tree + " reaches " + linked.resolve("loop")
                    + ", which leads back to "), message);
            assertTrue(message.endsWith("; symbolic-link cycles are not supported"), message);
            assertFalse(message.contains("junction"), message);
            assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output),
                    "a symbolic-link cycle below a junction must leave an existing good output intact");
            assertNoWorkDirectory(output.getParent());
        });
    }

    @ParameterizedTest(name = "a junction to {0}")
    @ValueSource(strings = {"the output's directory", "an ancestor of the output's directory"})
    @EnabledOnOs(OS.WINDOWS)
    void rejectsADirectoryJunctionToTheOutputDirectoryOrOneOfItsAncestors(String target, @TempDir Path parent)
            throws Throwable {
        withJunctions(parent, () -> {
            Path output = existingOutput(parent);
            Path tree = applicationTree(parent.resolve("app"));
            Path junction = createJunction(tree.resolve("linked"),
                    target.startsWith("an ancestor") ? parent : output.getParent());

            IOException failure = assertThrows(IOException.class,
                    () -> buildWithin(JUNCTION_TIMEOUT, tree, output));

            String message = failure.getMessage();
            assertTrue(message.startsWith("The application output " + tree + " contains the directory junction "
                    + junction + " to "), message);
            assertTrue(message.endsWith(", which is or contains the directory of the output " + output
                    + "; packaging it would read what it is writing"), message);
            assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output),
                    "a junction to " + target + " must leave an existing good output intact");
            assertNoWorkDirectory(output.getParent());
        });
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsADirectoryJunctionWhoseTargetIsGone(@TempDir Path parent) throws Throwable {
        withJunctions(parent, () -> {
            Path gone = Files.createDirectories(parent.resolve("gone"));
            Path tree = applicationTree(parent.resolve("app"));
            Path junction = createJunction(tree.resolve("linked"), gone);
            Files.delete(gone);
            assertTrue(Files.exists(junction, LinkOption.NOFOLLOW_LINKS), "the junction outlives its target");
            Path output = existingOutput(parent);

            IOException failure = assertThrows(IOException.class,
                    () -> buildWithin(JUNCTION_TIMEOUT, tree, output));

            // Runner's own message, not a NotLinkException from reading the junction as a symbolic link, nor the
            // raw exception from listing it.
            assertEquals(IOException.class, failure.getClass(), failure::toString);
            assertEquals("The application output " + tree + " contains the directory junction " + junction
                    + ", which does not resolve", failure.getMessage());
            assertNotNull(failure.getCause(), "the reason it does not resolve is kept");
            assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output),
                    "a junction whose target is gone must leave an existing good output intact");
            assertNoWorkDirectory(output.getParent());
        });
    }

    /**
     * Asserts Runner's own cycle message, which names the junction the cycle runs through, rather than a path
     * that merely starts with the junction's: an operating-system error from a walk that went round the cycle
     * until Windows refused the path carries such a path too.
     */
    private void assertJunctionCycleRejected(Path tree, Path closing, Path junction, Path output) throws Throwable {
        IOException failure = assertThrows(IOException.class,
                () -> buildWithin(Duration.ofSeconds(10), tree, output));

        String message = failure.getMessage();
        assertTrue(message.startsWith("The application output " + tree + " reaches " + closing
                + ", which leads back to "), message);
        assertTrue(message.endsWith(" through the directory junction " + junction
                + "; cycles through directory junctions or symbolic links are not supported"), message);
        assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output),
                "a directory-junction cycle must leave an existing good output intact");
        assertNoWorkDirectory(output.getParent());
    }

    /** A new output path below a junction test's own directory that already holds {@link #PREVIOUS_OUTPUT}. */
    private static Path existingOutput(Path parent) throws IOException {
        Path output = Files.createDirectories(parent.resolve("out")).resolve("runner.jar");
        Files.write(output, PREVIOUS_OUTPUT);
        return output;
    }

    /** Builds an application tree, failing rather than hanging past the time limit. */
    private void buildWithin(Duration timeout, Path tree, Path output) {
        assertTimeoutPreemptively(timeout, () -> buildApplicationTree(tree, output));
    }

    @Test
    void mergesMicronautMetadataFromTwoJarsIntoTheArchiveRoot() throws IOException {
        String prefix = "META-INF/micronaut/example.Service/";
        String firstName = prefix + "impl.A";
        String secondName = prefix + "impl.B";
        Path first = fixtures.resolve("libs/metadata-a.jar");
        Path second = fixtures.resolve("libs/metadata-b.jar");
        writeJar(first, manifest(attributes -> { }), Map.of(firstName, new byte[0]));
        writeJar(second, manifest(attributes -> { }), Map.of(secondName, new byte[0]));

        Path output = output();
        RunnerJarResult result = RunnerJarBuilder.build(spec(output)
                .applicationOutput(List.of(applicationClasses))
                .dependencies(List.of(Dependency.of(first), Dependency.of(second)))
                .build(), BuildLogger.noOp());

        assertEquals(2, result.mergedServiceEntryCount());
        try (ZipReader archive = ZipReader.open(output)) {
            assertTrue(archive.entry(firstName).isPresent(), "the first JAR's marker is in the merged root");
            assertTrue(archive.entry(secondName).isPresent(), "the second JAR's marker is in the merged root");
        }
    }

    @Test
    void mergesTheContentOfANonEmptyMicronautServiceEntry() throws IOException {
        // Every lookup under META-INF/micronaut/ is answered from the merged copy at the archive root, so
        // a merged copy written empty serves zero bytes for a file that is not empty, silently.
        Path resources = fixtures.resolve("merged/resources");
        write(resources.resolve("META-INF/micronaut/notes.txt"), "IMPORTANT-APP-CONTENT");
        write(resources.resolve(SERVICE_DIRECTORY + "com.example.Marker"), "");
        Path dependency = fixtures.resolve("libs/merged-dep.jar");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/micronaut/dep-notes.txt", "DEP-CONTENT-HERE".getBytes(StandardCharsets.UTF_8));
        entries.put(SERVICE_DIRECTORY + "com.example.DepMarker", new byte[0]);
        writeJar(dependency, manifest(attributes -> { }), entries);

        Path output = output();
        RunnerJarBuilder.build(spec(output)
                .applicationOutput(List.of(applicationClasses, resources))
                .dependencies(List.of(Dependency.of(dependency)))
                .build(), BuildLogger.noOp());

        try (ZipReader archive = ZipReader.open(output)) {
            assertEquals("IMPORTANT-APP-CONTENT",
                    new String(archive.read(archive.entry("META-INF/micronaut/notes.txt").orElseThrow()),
                            StandardCharsets.UTF_8));
            assertEquals("DEP-CONTENT-HERE",
                    new String(archive.read(archive.entry("META-INF/micronaut/dep-notes.txt").orElseThrow()),
                            StandardCharsets.UTF_8));
            assertEquals(0, archive.entry(SERVICE_DIRECTORY + "com.example.Marker").orElseThrow()
                    .uncompressedSize(), "a marker entry stays empty");
        }
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertEquals("IMPORTANT-APP-CONTENT", new String(
                    reader.read(index.resolveInJar(index.find("META-INF/micronaut/notes.txt"),
                            Index.effectiveMultiReleaseVersion(), IndexFormat.APPLICATION_JAR_ID)),
                    StandardCharsets.UTF_8), "and the record the class loader resolves to says so too");
        }
    }

    @Test
    void keepsTheFirstMergedServiceContributorEvenWhenItIsEmpty() throws IOException {
        String name = "META-INF/micronaut/first-wins.txt";
        byte[] empty = new byte[0];
        byte[] second = "SECOND".getBytes(StandardCharsets.UTF_8);
        Path emptyDependency = fixtures.resolve("libs/first-wins-empty.jar");
        Path secondDependency = fixtures.resolve("libs/first-wins-second.jar");
        writeJar(emptyDependency, manifest(attributes -> { }), Map.of(name, empty));
        writeJar(secondDependency, manifest(attributes -> { }), Map.of(name, second));

        for (Compression compression : Compression.values()) {
            Path output = output();
            RunnerJarResult result = RunnerJarBuilder.build(spec(output)
                    .applicationOutput(List.of(applicationClasses))
                    .dependencies(List.of(
                            Dependency.of(emptyDependency),
                            Dependency.of(secondDependency)))
                    .compression(compression)
                    .build(), BuildLogger.noOp());

            assertMergedContent(output, name, empty);
            assertEquals(1, warningsFor(result, name).size(),
                    compression + ": different later content is reported even when the first value is empty");

            Path reversed = output();
            RunnerJarResult reversedResult = RunnerJarBuilder.build(spec(reversed)
                    .applicationOutput(List.of(applicationClasses))
                    .dependencies(List.of(
                            Dependency.of(secondDependency),
                            Dependency.of(emptyDependency)))
                    .compression(compression)
                    .build(), BuildLogger.noOp());

            assertMergedContent(reversed, name, second);
            assertEquals(1, warningsFor(reversedResult, name).size(),
                    compression + ": reversing the contributors reverses the selected content");

            Path equal = output();
            RunnerJarResult equalResult = RunnerJarBuilder.build(spec(equal)
                    .applicationOutput(List.of(applicationClasses))
                    .dependencies(List.of(
                            Dependency.of(emptyDependency),
                            Dependency.of(emptyDependency)))
                    .compression(compression)
                    .build(), BuildLogger.noOp());

            assertMergedContent(equal, name, empty);
            assertTrue(warningsFor(equalResult, name).isEmpty(),
                    compression + ": equal empty contributors are not a conflict");
        }
    }

    @Test
    void appliesFirstContributorSemanticsWhenTheApplicationContributesMetadata() throws IOException {
        String name = "META-INF/micronaut/application-first.txt";
        byte[] empty = new byte[0];
        byte[] second = "SECOND".getBytes(StandardCharsets.UTF_8);
        Path dependency = fixtures.resolve("libs/application-first.jar");
        writeJar(dependency, manifest(attributes -> { }), Map.of(name, second));

        Path emptyApplication = fixtures.resolve("application-first/empty");
        Files.createDirectories(emptyApplication.resolve("META-INF/micronaut"));
        Files.write(emptyApplication.resolve(name), empty);
        Path nonEmptyApplication = fixtures.resolve("application-first/non-empty");
        Files.createDirectories(nonEmptyApplication.resolve("META-INF/micronaut"));
        Files.write(nonEmptyApplication.resolve(name), second);

        for (Compression compression : Compression.values()) {
            Path different = output();
            RunnerJarResult differentResult = RunnerJarBuilder.build(spec(different)
                    .applicationOutput(List.of(applicationClasses, emptyApplication))
                    .dependencies(List.of(Dependency.of(dependency)))
                    .compression(compression)
                    .build(), BuildLogger.noOp());
            assertMergedContent(different, name, empty);
            assertEquals(1, warningsFor(differentResult, name).size(),
                    compression + ": an empty application value reserves the name");

            Path equal = output();
            RunnerJarResult equalResult = RunnerJarBuilder.build(spec(equal)
                    .applicationOutput(List.of(applicationClasses, nonEmptyApplication))
                    .dependencies(List.of(Dependency.of(dependency)))
                    .compression(compression)
                    .build(), BuildLogger.noOp());
            assertMergedContent(equal, name, second);
            assertTrue(warningsFor(equalResult, name).isEmpty(),
                    compression + ": equal application and dependency values are not a conflict");
        }
    }

    @Test
    void mergesEqualZeroLengthContributorsWithoutWarning() throws IOException {
        String name = SERVICE_DIRECTORY + "com.example.Shared";
        Path application = fixtures.resolve("zero-length/application");
        Files.createDirectories(application.resolve(SERVICE_DIRECTORY));
        Files.write(application.resolve(name), new byte[0]);
        Path first = fixtures.resolve("zero-length/first.jar");
        Path second = fixtures.resolve("zero-length/second.jar");
        writeJar(first, manifest(attributes -> { }), Map.of(name, new byte[0]));
        writeJar(second, manifest(attributes -> { }), Map.of(name, new byte[0]));

        for (Compression compression : Compression.values()) {
            Path output = output();
            RunnerJarResult result = RunnerJarBuilder.build(spec(output)
                    .applicationOutput(List.of(applicationClasses, application))
                    .dependencies(List.of(Dependency.of(first), Dependency.of(second)))
                    .compression(compression)
                    .build(), BuildLogger.noOp());

            assertTrue(warningsFor(result, name).isEmpty(),
                    compression + ": equal zero-length contributors are not a conflict");
            assertEquals(1, result.mergedServiceEntryCount(), compression + ": one name, merged once");
            try (ZipReader archive = ZipReader.open(output)) {
                ZipEntryInfo root = archive.entry(name).orElseThrow();
                assertEquals(0, root.uncompressedSize(), compression + ": the merged copy is empty");
                assertEquals(0, root.crc32(), compression + ": and records the CRC-32 of nothing");
                ZipEntryInfo layer = archive.entry(IndexFormat.CLASSES_PREFIX + name).orElseThrow();
                assertEquals(0, layer.uncompressedSize(), compression + ": so is the application's own copy");
                assertEquals(0, layer.crc32(), compression + ": with the CRC-32 of nothing");
            }
            assertMergedContent(output, name, new byte[0]);
        }
    }

    @Test
    void rejectsAZeroLengthMicronautEntryThatRecordsANonZeroCrc() throws IOException {
        // A zero-length entry with a CRC-32 other than 0 is damaged. A STORED build finds out while it
        // repacks the jar; a PRESERVE build copies the jar verbatim and finds out when the entry is merged.
        String name = SERVICE_DIRECTORY + "com.example.Damaged";
        Path dependency = fixtures.resolve("libs/zero-length-bad-crc.jar");
        Files.createDirectories(dependency.getParent());
        try (ZipWriter writer = ZipWriter.create(dependency, ZipWriter.DEFAULT_TIMESTAMP)) {
            writer.writeEntry(name, new byte[0]);
        }
        byte[] bytes = Files.readAllBytes(dependency);
        int end = bytes.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int central = littleEndianInt(bytes, end + 16);
        putLittleEndianInt(bytes, 14, 0x1234_5678L);
        putLittleEndianInt(bytes, central + 16, 0x1234_5678L);
        Files.write(dependency, bytes);
        try (ZipReader reader = ZipReader.open(dependency)) {
            ZipEntryInfo entry = reader.entry(name).orElseThrow();
            assertEquals(0, entry.uncompressedSize());
            assertEquals(0x1234_5678L, entry.crc32());
        }

        for (Compression compression : Compression.values()) {
            IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output())
                    .applicationOutput(List.of(applicationClasses))
                    .dependencies(List.of(Dependency.of(dependency)))
                    .compression(compression)
                    .build(), BuildLogger.noOp()));

            assertTrue(failure.getMessage().contains(dependency.toString()),
                    compression + ": " + failure.getMessage());
            assertTrue(failure.getMessage().contains(name), compression + ": " + failure.getMessage());
            assertTrue(failure.getMessage().contains("CRC-32"), compression + ": " + failure.getMessage());
        }
    }

    @Test
    void preservesMergedServiceSizeBoundariesAcrossSourceFormsAndCompressionModes() throws IOException {
        String name = "META-INF/micronaut/large.txt";
        int oneMiB = 1 << 20;
        for (int size : new int[] {oneMiB - 1, oneMiB, oneMiB + 1}) {
            byte[] content = new byte[size];
            for (int i = 0; i < content.length; i++) {
                content[i] = (byte) (i * 31 + size);
            }
            Path directory = fixtures.resolve("large/directory-" + size);
            Files.createDirectories(directory.resolve("META-INF/micronaut"));
            Files.write(directory.resolve(name), content);

            Path applicationJar = fixtures.resolve("large/application-" + size + ".jar");
            Map<String, byte[]> applicationEntries = new LinkedHashMap<>();
            applicationEntries.put("com/example/Application.class",
                    Files.readAllBytes(applicationClasses.resolve("com/example/Application.class")));
            applicationEntries.put(name, content);
            writeJar(applicationJar, manifest(attributes -> { }), applicationEntries);

            Path dependency = fixtures.resolve("large/dependency-" + size + ".jar");
            writeJar(dependency, manifest(attributes -> { }), Map.of(name, content));

            for (Compression compression : Compression.values()) {
                Path fromDirectory = output();
                RunnerJarBuilder.build(spec(fromDirectory)
                        .applicationOutput(List.of(applicationClasses, directory))
                        .dependencies(List.of())
                        .compression(compression)
                        .build(), BuildLogger.noOp());
                assertMergedContent(fromDirectory, name, content);

                Path fromApplicationJar = output();
                RunnerJarBuilder.build(spec(fromApplicationJar)
                        .applicationOutput(List.of(applicationJar))
                        .dependencies(List.of())
                        .compression(compression)
                        .build(), BuildLogger.noOp());
                assertMergedContent(fromApplicationJar, name, content);

                Path fromDependency = output();
                RunnerJarBuilder.build(spec(fromDependency)
                        .applicationOutput(List.of(applicationClasses))
                        .dependencies(List.of(Dependency.of(dependency)))
                        .compression(compression)
                        .build(), BuildLogger.noOp());
                assertMergedContent(fromDependency, name, content);
            }
        }
    }

    @Test
    void recordsAManifestAttributeThatIsPresentButEmpty() throws IOException {
        // URLClassLoader reports a present but empty attribute as "", not null, on the Package it defines.
        // Code that null-checks an attribute to decide whether the jar declared it has to agree.
        Path dependency = fixtures.resolve("libs/empty-attributes.jar");
        Manifest empty = manifest(attributes -> {
            attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, "");
            attributes.put(Attributes.Name.SPECIFICATION_VERSION, "");
            attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, "3.0");
        });
        writeJar(dependency, empty, Map.of("com/example/empty/E.class", new byte[] {1}));

        Path output = output();
        RunnerJarBuilder.build(spec(output)
                .dependencies(List.of(Dependency.of(dependency)))
                .build(), BuildLogger.noOp());

        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertEquals("", RunnerBuildTestAccess.jarImplTitle(index, 1), "present but empty is not absent");
            assertEquals("", RunnerBuildTestAccess.jarSpecVersion(index, 1));
            assertEquals("3.0", RunnerBuildTestAccess.jarImplVersion(index, 1));
            assertNull(RunnerBuildTestAccess.jarSpecTitle(index, 1), "an attribute that really is absent stays null");
        }
    }

    @Test
    void aliasesVersionedDirectoriesExactlyAsJarFileSelectsThem() throws IOException {
        // Two corners where the packager and the JDK used to disagree, both of them silent: a directory
        // whose version has a leading zero is not a versioned directory to the JDK, and a directory naming
        // version 8 is one, even though JEP 238 talks about 9 and up.
        Path dependency = fixtures.resolve("libs/versions-lib.jar");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("m/leadingzero.txt", "lz-base".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/versions/09/m/leadingzero.txt", "lz9".getBytes(StandardCharsets.UTF_8));
        entries.put("m/toolow.txt", "toolow-base".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/versions/8/m/toolow.txt", "toolow-8".getBytes(StandardCharsets.UTF_8));
        writeJar(dependency, manifest(attributes -> attributes.putValue("Multi-Release", "true")), entries);

        // The oracle: what the same jar resolves to on an ordinary class path, on this very JDK.
        try (JarFile oracle = new JarFile(dependency.toFile(), false, ZipFile.OPEN_READ,
                JarFile.runtimeVersion())) {
            assertEquals("m/leadingzero.txt", oracle.getJarEntry("m/leadingzero.txt").getRealName());
            assertEquals("META-INF/versions/8/m/toolow.txt",
                    oracle.getJarEntry("m/toolow.txt").getRealName());
        }

        Path output = output();
        RunnerJarBuilder.build(spec(output)
                .dependencies(List.of(Dependency.of(dependency)))
                .build(), BuildLogger.noOp());

        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertEquals("lz-base", resolved(reader, index, "m/leadingzero.txt"),
                    "META-INF/versions/09 is not a version directory, so the base entry wins");
            assertEquals("toolow-8", resolved(reader, index, "m/toolow.txt"),
                    "META-INF/versions/8 is one, and JarFile selects it");

            System.setProperty("jdk.util.jar.enableMultiRelease", "false");
            try {
                assertEquals("toolow-base", resolved(reader, index, "m/toolow.txt"),
                        "with multi-release off the base entry wins, as it does for JarFile");
            } finally {
                System.clearProperty("jdk.util.jar.enableMultiRelease");
            }
        }
    }

    private static String resolved(RunnerJarArchive reader, Index index, String name) throws IOException {
        int record = RunnerBuildTestAccess.resolve(index, index.find(name), Index.effectiveMultiReleaseVersion());
        assertNotEquals(IndexFormat.NO_INDEX, record, name + " should resolve");
        return new String(reader.read(record), StandardCharsets.UTF_8);
    }

    private static List<String> warningsFor(RunnerJarResult result, String name) {
        return result.warnings().stream().filter(warning -> warning.contains(name)).toList();
    }

    private static void assertMergedContent(Path output, String name, byte[] expected) throws IOException {
        try (ZipReader archive = ZipReader.open(output)) {
            assertArrayEquals(expected, archive.read(archive.entry(name).orElseThrow()),
                    "the root merged copy preserves the selected contributor");
        }
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            int record = reader.index().find(name);
            assertNotEquals(IndexFormat.NO_INDEX, record, name + " should resolve at runtime");
            assertArrayEquals(expected, reader.read(record),
                    "the logical runtime lookup returns the merged bytes");
        }
    }

    private IOException assertPreserveRejectedWithoutReplacingOutput(Path dependency, boolean verifyAll)
            throws IOException {
        Path output = output();
        Files.createDirectories(output.getParent());
        byte[] previous = "previous artifact".getBytes(StandardCharsets.UTF_8);
        Files.write(output, previous);
        String oldVerifyAll = System.getProperty(RunnerJarBuilder.VERIFY_ALL_PROPERTY);
        if (verifyAll) {
            System.setProperty(RunnerJarBuilder.VERIFY_ALL_PROPERTY, "true");
        } else {
            System.clearProperty(RunnerJarBuilder.VERIFY_ALL_PROPERTY);
        }
        try {
            IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output)
                    .dependencies(List.of(Dependency.of(dependency)))
                    .compression(Compression.PRESERVE)
                    .build(), BuildLogger.noOp()));
            assertArrayEquals(previous, Files.readAllBytes(output),
                    "a structurally invalid dependency must be rejected before publication");
            return failure;
        } finally {
            if (oldVerifyAll == null) {
                System.clearProperty(RunnerJarBuilder.VERIFY_ALL_PROPERTY);
            } else {
                System.setProperty(RunnerJarBuilder.VERIFY_ALL_PROPERTY, oldVerifyAll);
            }
        }
    }

    private static Path mismatchedLocalNameDependency(String name) throws IOException {
        Path jar = fixtures.resolve(name);
        Files.createDirectories(jar.getParent());
        try (ZipWriter writer = ZipWriter.create(jar, ZipWriter.DEFAULT_TIMESTAMP)) {
            writer.writeEntry("safe.txt", "SAFE".getBytes(StandardCharsets.UTF_8));
        }
        byte[] bytes = Files.readAllBytes(jar);
        System.arraycopy("../x.txt".getBytes(StandardCharsets.UTF_8), 0, bytes, 30, 8);
        Files.write(jar, bytes);
        try (ZipFile centralView = new ZipFile(jar.toFile());
             ZipInputStream localView = new ZipInputStream(Files.newInputStream(jar))) {
            assertEquals("safe.txt", centralView.entries().nextElement().getName());
            assertEquals("../x.txt", localView.getNextEntry().getName());
        }
        return jar;
    }

    private static Path mismatchedStoredSizeDependency(String name) throws IOException {
        Path jar = fixtures.resolve(name);
        Files.createDirectories(jar.getParent());
        try (ZipWriter writer = ZipWriter.create(jar, ZipWriter.DEFAULT_TIMESTAMP)) {
            writer.writeEntry("bad.txt", new byte[] {'X'});
        }
        byte[] bytes = Files.readAllBytes(jar);
        int end = bytes.length - IndexFormat.END_OF_CENTRAL_DIRECTORY_SIZE;
        int central = littleEndianInt(bytes, end + 16);
        putLittleEndianInt(bytes, 14, 0);
        putLittleEndianInt(bytes, 22, 0);
        putLittleEndianInt(bytes, central + 16, 0);
        putLittleEndianInt(bytes, central + 24, 0);
        Files.write(jar, bytes);
        try (ZipInputStream localView = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry local = localView.getNextEntry();
            assertEquals("bad.txt", local.getName());
            assertEquals(1, local.getCompressedSize());
            assertEquals(0, local.getSize());
        }
        return jar;
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static void putLittleEndianInt(byte[] bytes, int offset, long value) {
        for (int i = 0; i < 4; i++) {
            bytes[offset + i] = (byte) (value >>> (i * 8));
        }
    }

    /**
     * A dependency jar whose central directory records a CRC-32 its content no longer matches, which is
     * what a jar that was damaged after it was built looks like.
     *
     * @param name the file to create under the fixture directory
     * @return the jar
     * @throws IOException if it cannot be written
     */
    private static Path corruptedDependency(String name) throws IOException {
        Path jar = fixtures.resolve(name);
        Files.createDirectories(jar.getParent());
        try (ZipWriter writer = ZipWriter.create(jar, ZipWriter.DEFAULT_TIMESTAMP)) {
            writer.writeEntry("com/example/broken/Broken.class", "content".getBytes(StandardCharsets.UTF_8));
        }
        long at;
        try (ZipReader reader = ZipReader.open(jar)) {
            at = reader.entry("com/example/broken/Broken.class").orElseThrow().dataOffset();
        }
        byte[] bytes = Files.readAllBytes(jar);
        bytes[(int) at] ^= 0xFF;
        Files.write(jar, bytes);
        return jar;
    }

    private static List<String> manifestLines(Path output) throws IOException {
        try (ZipReader archive = ZipReader.open(output)) {
            byte[] bytes = archive.read(archive.entry("META-INF/MANIFEST.MF").orElseThrow());
            List<String> names = new ArrayList<>();
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\r\\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    names.add(line.substring(0, colon));
                }
            }
            return names;
        }
    }

    private static Path javaExecutable() {
        String home = System.getProperty("runner.test.javaHome", System.getProperty("java.home"));
        Path candidate = Path.of(home, "bin", "java");
        if (!Files.isExecutable(candidate)) {
            candidate = Path.of(home, "bin", "java.exe");
        }
        Assumptions.assumeTrue(Files.isExecutable(candidate), "the JDK has no java executable");
        return candidate;
    }

    @Test
    void packagesAndFullyVerifiesLargeJarResourcesInAConstrainedHeap() throws Exception {
        String classpath = System.getProperty("java.class.path");
        Path workspace = Files.createDirectories(fixtures.resolve("bounded-memory-worker"));
        Path workerLog = workspace.resolve("worker.log");
        Process process = new ProcessBuilder(
                javaExecutable().toString(),
                "-Xms16m", "-Xmx32m",
                "-cp", classpath,
                BoundedMemoryPackagingProbe.class.getName(),
                workspace.toString(),
                applicationClasses.resolve("com/example/Application.class").toString())
                .redirectErrorStream(true)
                .redirectOutput(workerLog.toFile())
                .start();
        if (!process.waitFor(3, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "constrained packaging worker could not be stopped");
            fail("constrained packaging worker timed out");
        }
        String processOutput = Files.readString(workerLog);
        assertEquals(0, process.exitValue(), processOutput);
        try (var children = Files.list(workspace)) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith(".micronaut-runner-")),
                    "the build must clean every spool/work directory");
        }
    }

    private static void compile(JavaCompiler compiler, Path sources, Path classes, Map<String, String> files)
            throws IOException {
        List<String> arguments = new ArrayList<>(List.of("--release", "25", "-d", classes.toString()));
        for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
            Path source = sources.resolve(file.getKey());
            Files.createDirectories(source.getParent());
            Files.writeString(source, file.getValue());
            arguments.add(source.toString());
        }
        int status = compiler.run(null, null, null, arguments.toArray(new String[0]));
        if (status != 0) {
            throw new IOException("Could not compile the fixture classes");
        }
    }

    private static Manifest manifest(Consumer<Attributes> configure) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        configure.accept(manifest.getMainAttributes());
        return manifest;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void writeJar(Path file, Manifest manifest, Map<String, byte[]> entries)
            throws IOException {
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry record = new ZipEntry(entry.getKey());
                record.setTime(FIXTURE_TIME);
                out.putNextEntry(record);
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static List<String> names(ZipReader reader) {
        List<String> names = new ArrayList<>();
        for (ZipEntryInfo entry : reader.entries()) {
            names.add(entry.name());
        }
        return names;
    }

    private static List<String> logicalNames(Index index, int jarId) {
        List<String> names = new ArrayList<>();
        int first = RunnerBuildTestAccess.jarFirstEntry(index, jarId);
        for (int i = first; i < first + RunnerBuildTestAccess.jarEntryCount(index, jarId); i++) {
            names.add(index.entryName(i));
        }
        return names;
    }

    private static Path createSymbolicLink(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "symbolic links are not supported: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Runs a junction test, then removes every junction still under its temporary directory, so that no walk,
     * JUnit's cleanup included, can go through one afterwards. A failure to remove one never changes the test's
     * result: it is added to the test's own failure rather than hiding it, and after a passing test it is only
     * reported on the error stream.
     */
    private static void withJunctions(Path root, Executable test) throws Throwable {
        try {
            test.execute();
        } catch (Throwable failure) {
            try {
                removeJunctions(root);
            } catch (IOException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        try {
            removeJunctions(root);
        } catch (IOException | RuntimeException cleanup) {
            // Every junction these tests make points inside their own temporary directory, so one left behind can
            // only lead JUnit's cleanup to files it deletes anyway: report it without failing a passing test.
            System.err.println("Could not remove every directory junction under " + root + " after the test passed:");
            cleanup.printStackTrace();
        }
    }

    /**
     * Makes a directory junction with {@code mklink /J}, which needs neither administrator rights nor Developer
     * Mode, and fails the test (never skips it) when Windows does not make one.
     */
    private static Path createJunction(Path junction, Path target) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(),
                target.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int status = process.waitFor();
        assertEquals(0, status, "mklink /J " + junction + " " + target + " failed: " + output);
        BasicFileAttributes attributes = Files.readAttributes(junction, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        // The premise of #225: Java reports a junction as a directory, and as "other", never as a link.
        assertTrue(attributes.isDirectory() && attributes.isOther() && !attributes.isSymbolicLink(),
                junction + " is not reported the way a junction is");
        return junction;
    }

    /**
     * Removes, as a junction, every directory junction under a directory. Each entry's attributes are read
     * before it is listed, so the sweep never goes through a junction, and {@code Files.delete} never touches a
     * junction's target.
     */
    private static void removeJunctions(Path directory) throws IOException {
        List<Path> children;
        try (Stream<Path> listed = Files.list(directory)) {
            children = listed.toList();
        }
        IOException failure = null;
        for (Path child : children) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory() && attributes.isOther()) {
                    Files.delete(child);
                } else if (attributes.isDirectory()) {
                    removeJunctions(child);
                }
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static Path createHardLink(Path link, Path target) throws IOException {
        try {
            return Files.createLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "hard links are not supported: " + e.getMessage());
            throw e;
        }
    }

    private RunnerJarSpec.Builder spec(Path output) {
        return RunnerJarSpec.builder()
                .mainClass("com.example.Application")
                .applicationOutput(List.of(applicationClasses, applicationResources))
                .dependencies(List.of(
                        Dependency.of(plainDependency, "com.example:dep-lib:2.0.1"),
                        Dependency.of(multiReleaseDependency, "com.example:mr-lib:1.0"),
                        Dependency.of(signedDependency)))
                .output(output);
    }

    private Path output() {
        counter++;
        return fixtures.resolve("out/runner-" + counter + ".jar");
    }

    @Test
    void recordsPositionalReadsAndTheLargestStoredClassOnlyWhenAskedTo() throws IOException {
        Path positional = output();
        RunnerJarBuilder.build(spec(positional).archiveReads(ArchiveReads.POSITIONAL).build(), BuildLogger.noOp());
        Path mapped = output();
        RunnerJarBuilder.build(spec(mapped).build(), BuildLogger.noOp());

        try (RunnerJarArchive reader = RunnerJarArchive.open(positional)) {
            Index index = reader.index();
            assertTrue(index.positionalReads());
            assertTrue(RunnerBuildTestAccess.nestedStored(index), "POSITIONAL leaves the compression alone");
            long expected = 0;
            for (int record = 0; record < index.entryCount(); record++) {
                if (index.entryMethod(record) == IndexFormat.METHOD_STORED
                        && index.entryName(record).endsWith(".class")) {
                    expected = Math.max(expected, index.entryUncompressedSize(record));
                }
            }
            assertTrue(RunnerBuildTestAccess.largestStoredClass(index) > 0, "the fixture has STORED classes");
            assertEquals(expected, RunnerBuildTestAccess.largestStoredClass(index));
        }
        try (RunnerJarArchive reader = RunnerJarArchive.open(mapped)) {
            assertFalse(reader.index().positionalReads());
            assertEquals(0, RunnerBuildTestAccess.largestStoredClass(reader.index()));
        }
    }

    @Test
    void anArchiveReadsOptionSetByNameReachesTheLauncher() throws IOException {
        Path output = output();
        RunnerJarBuilder.build(spec(output).option("archiveReads", "positional").build(), BuildLogger.noOp());

        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            assertTrue(reader.index().positionalReads(), "the index header carries the positional-reads flag");
            assertTrue(RunnerBuildTestAccess.largestStoredClass(reader.index()) > 0,
                    "a positional archive records the largest STORED class");
            // The launcher's reader of the archive followed the flag when it opened the index: only the index is
            // mapped, and classes are read with positional reads.
            assertTrue(RunnerBuildTestAccess.indexOnly(reader.source()), "the reader did not switch to the index mode");
        }
    }

    @Test
    void packagesTheApplicationAndItsDependencies() throws IOException {
        Path output = output();
        RunnerJarResult result = RunnerJarBuilder.build(spec(output).build(), BuildLogger.noOp());

        assertTrue(Files.isRegularFile(output));
        assertEquals(Files.size(output), result.archiveSize());
        assertEquals(4, result.jarCount());
        assertEquals(3, result.dependencyCount());

        try (ZipReader archive = ZipReader.open(output)) {
            List<String> names = names(archive);
            assertEquals("META-INF/MANIFEST.MF", names.get(0));
            assertEquals(IndexFormat.INDEX_ENTRY_NAME, names.get(1), "the index is always the second entry");
            assertTrue(names.contains("io/micronaut/runner/IndexFormat.class"),
                    "the launcher's classes are copied to the root");
            assertTrue(names.contains("MICRONAUT-INF/classes/com/example/Application.class"));
            assertTrue(names.contains("MICRONAUT-INF/classes/application.yml"));
            assertTrue(names.contains("MICRONAUT-INF/lib/dep-lib.jar"));
            assertTrue(names.contains("MICRONAUT-INF/lib/mr-lib.jar"));
            assertTrue(names.contains("MICRONAUT-INF/lib/signed-lib.jar"));
            for (String name : names) {
                assertFalse(name.startsWith("io/micronaut/runner/") && !name.endsWith(".class"),
                        "only the launcher's classes are copied, not " + name);
            }
            // The bundled launcher.jar, which is the published artifact, compiled with -g:source,lines: every
            // application carries these classes, and they run on every start.
            int launcherClasses = 0;
            int launcherMethods = 0;
            for (ZipEntryInfo entry : archive.entries()) {
                if (entry.name().startsWith("io/micronaut/runner/") && entry.name().endsWith(".class")) {
                    launcherClasses++;
                    launcherMethods += ClassFixtures.assertLineNumbersWithoutLocalVariables(entry.name(),
                            archive.read(entry));
                }
            }
            assertTrue(launcherClasses > 1, "launcher classes: " + launcherClasses);
            assertTrue(launcherMethods > launcherClasses, "launcher methods with code: " + launcherMethods);
            assertFalse(names.contains("META-INF/services/com.example.Service"),
                    "plain service files stay in the jar they came from");

            Manifest manifest = archive.manifest().orElseThrow();
            Attributes main = manifest.getMainAttributes();
            assertEquals(IndexFormat.LAUNCHER_CLASS, main.getValue(Attributes.Name.MAIN_CLASS));
            assertEquals("1", main.getValue(IndexFormat.ATTR_FORMAT));
            assertEquals("com.example.Application", main.getValue(IndexFormat.ATTR_START_CLASS));
            assertNull(main.getValue("Class-Path"));
            assertNull(main.getValue("Multi-Release"));
        }

        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertEquals(4, index.jarCount());
            assertEquals(result.entryCount(), index.entryCount());
            assertEquals("com.example.Application", index.startClass());
            assertTrue(RunnerBuildTestAccess.nestedStored(index));
            assertFalse(index.applicationMultiRelease());
            assertEquals(IndexFormat.CLASSES_PREFIX, index.jarName(0));
            assertEquals("MICRONAUT-INF/lib/dep-lib.jar", index.jarName(1));
            assertEquals("com.example:dep-lib:2.0.1", RunnerBuildTestAccess.jarCoordinates(index, 1));
            assertNull(RunnerBuildTestAccess.jarCoordinates(index, 3), "a dependency without coordinates records none");
            assertEquals(Files.size(output), index.jarDataLength(0), "jar 0 is the outer archive itself");

            // Logical names: the application layer drops the prefix, a nested jar keeps its own names.
            int application = index.find("com/example/Application.class");
            assertNotEquals(IndexFormat.NO_INDEX, application);
            assertEquals(0, RunnerBuildTestAccess.entryJarId(index, application));
            assertArrayEquals(
                    Files.readAllBytes(applicationClasses.resolve("com/example/Application.class")),
                    reader.read(application));

            int dependency = index.find("com/example/dep/Dep.class");
            assertEquals(1, RunnerBuildTestAccess.entryJarId(index, dependency));
            assertArrayEquals(
                    Files.readAllBytes(fixtures.resolve("lib/classes/com/example/dep/Dep.class")),
                    reader.read(dependency));
            assertEquals(IndexFormat.METHOD_STORED, index.entryMethod(dependency),
                    "STORED repacks every nested entry");

            int resource = index.find("resources/dep.txt");
            assertEquals("from the dependency",
                    new String(reader.read(resource), StandardCharsets.UTF_8));
            assertEquals(IndexFormat.NO_INDEX, index.find("com/example/Missing.class"));
        }
    }

    @Test
    void mergesTheMicronautServiceDirectoryIntoTheArchiveRoot() throws IOException {
        Path output = output();
        RunnerJarResult result = RunnerJarBuilder.build(spec(output).build(), BuildLogger.noOp());

        assertEquals(2, result.mergedServiceEntryCount(),
                "one service entry from the application and one from a dependency");

        try (ZipReader archive = ZipReader.open(output)) {
            ZipEntryInfo directory = archive.entry(IndexFormat.MICRONAUT_SERVICES_PREFIX).orElseThrow();
            assertTrue(directory.directory(), "the merged directory is a real directory entry");
            ZipEntryInfo service = archive.entry(SERVICE_DIRECTORY).orElseThrow();
            assertTrue(service.directory());
            ZipEntryInfo fromApplication =
                    archive.entry(SERVICE_DIRECTORY + "com.example.$Application$Definition").orElseThrow();
            assertEquals(0, fromApplication.uncompressedSize(), "merged entries carry only their name");
            assertTrue(archive.entry(SERVICE_DIRECTORY + "com.example.dep.DepBean").isPresent());

            List<String> names = names(archive);
            assertTrue(names.indexOf(IndexFormat.MICRONAUT_SERVICES_PREFIX) < names.indexOf(SERVICE_DIRECTORY),
                    "a directory is written before what it contains");
        }

        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            int merged = index.find(SERVICE_DIRECTORY + "com.example.dep.DepBean");
            assertNotEquals(IndexFormat.NO_INDEX, merged);
            assertEquals(0, RunnerBuildTestAccess.entryJarId(index, merged),
                    "the merged copy belongs to the outer archive");
            int directory = index.find(IndexFormat.MICRONAUT_SERVICES_PREFIX);
            assertTrue(index.entryDirectory(directory));
            assertFalse(RunnerBuildTestAccess.entrySyntheticDirectory(index, directory),
                    "it is stored, not synthesised");

            // The application's own copy is still in its layer, behind the merged one in the chain.
            int chain = index.find(SERVICE_DIRECTORY + "com.example.$Application$Definition");
            assertNotEquals(IndexFormat.NO_INDEX, RunnerBuildTestAccess.entryNextSameName(index, chain));
        }
    }

    @Test
    void aliasesVersionedEntriesOfAMultiReleaseDependency() throws IOException {
        Path output = output();
        RunnerJarBuilder.build(spec(output).build(), BuildLogger.noOp());

        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertTrue(index.jarMultiRelease(2));
            assertFalse(index.jarMultiRelease(1));

            int feature = index.find("com/example/mr/Feature.class");
            assertEquals(17, RunnerBuildTestAccess.entryMrVersion(index, feature));
            assertTrue(RunnerBuildTestAccess.entryVersionedAlias(index, feature));
            assertEquals("seventeen", new String(reader.read(feature), StandardCharsets.UTF_8));
            int base = RunnerBuildTestAccess.entryNextSameName(index, feature);
            assertEquals(0, RunnerBuildTestAccess.entryMrVersion(index, base));
            assertEquals("base", new String(reader.read(base), StandardCharsets.UTF_8));
            assertEquals(base, RunnerBuildTestAccess.resolve(index, feature, 11),
                    "an older runtime sees the base entry");
            assertEquals(feature, RunnerBuildTestAccess.resolve(index, feature, 21));

            assertEquals(IndexFormat.NO_INDEX, index.find("META-INF/extra.txt"),
                    "a versioned entry under META-INF is never aliased");
            assertNotEquals(IndexFormat.NO_INDEX, index.find("META-INF/versions/17/META-INF/extra.txt"));
        }
    }

    @Test
    void synthesisesTheDirectoriesTheApplicationLayerOnlyImplies() throws IOException {
        Path output = output();
        RunnerJarBuilder.build(spec(output).build(), BuildLogger.noOp());

        try (ZipReader archive = ZipReader.open(output)) {
            List<String> names = names(archive);
            assertTrue(names.contains(IndexFormat.CLASSES_PREFIX),
                    "the layer's own root is a real directory entry, so its code source URL can be opened");
            for (String name : names) {
                assertFalse(name.startsWith(IndexFormat.CLASSES_PREFIX) && name.endsWith("/")
                                && name.length() > IndexFormat.CLASSES_PREFIX.length(),
                        "the application layer stores no directory entries inside it, " + name + " is one");
            }
        }
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            for (String name : new String[] {"com/", "com/example/", "static/", SERVICE_DIRECTORY}) {
                int record = index.find(name);
                assertNotEquals(IndexFormat.NO_INDEX, record, name + " should resolve");
                assertTrue(index.entryDirectory(record), name + " should be a directory");
            }
            assertTrue(RunnerBuildTestAccess.entrySyntheticDirectory(index, index.find("com/example/")));
            assertFalse(index.entryPhysical(index.find("com/example/")));
        }
    }

    @Test
    void dropsTheSignatureFilesOfASignedDependency() throws IOException {
        Path output = output();
        RunnerJarResult result = RunnerJarBuilder.build(spec(output).build(), BuildLogger.noOp());

        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("signed-lib.jar")),
                "the build warns that a signature was dropped: " + result.warnings());
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertEquals("MICRONAUT-INF/lib/signed-lib.jar", index.jarName(3));
            assertTrue((RunnerBuildTestAccess.jarFlags(index, 3) & IndexFormat.JAR_FLAG_SIGNED_ORIGINAL) != 0);
            List<String> names = logicalNames(index, 3);
            assertTrue(names.contains("com/example/signed/Signed.class"));
            assertFalse(names.contains("META-INF/MY.SF"));
            assertFalse(names.contains("META-INF/MY.RSA"));
            assertFalse(names.contains("META-INF/INDEX.LIST"));
            assertTrue(names.contains("META-INF/MANIFEST.MF"), "the manifest is kept verbatim");
            assertTrue((RunnerBuildTestAccess.jarFlags(index, 3) & IndexFormat.JAR_FLAG_HAS_MANIFEST) != 0);
        }
    }

    @Test
    void readsThePerPackageSectionsOfADependencyManifest() throws IOException {
        Path output = output();
        RunnerJarBuilder.build(spec(output).build(), BuildLogger.noOp());

        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertEquals("dep-lib", RunnerBuildTestAccess.jarImplTitle(index, 1));
            assertEquals("2.0.1", RunnerBuildTestAccess.jarImplVersion(index, 1));
            assertFalse(RunnerBuildTestAccess.jarSealedByDefault(index, 1));

            int record = index.findPackage(1, "com.example.api");
            assertNotEquals(IndexFormat.NO_INDEX, record);
            assertEquals("Dep API", RunnerBuildTestAccess.packageSpecTitle(index, record));
            assertEquals("2.0.1", RunnerBuildTestAccess.packageImplVersion(index, record));
            assertTrue(RunnerBuildTestAccess.packageSealedSpecified(index, record));
            assertTrue(RunnerBuildTestAccess.packageSealedValue(index, record));
            assertEquals(1, index.jarPackageCount(1),
                    "a section naming a class file is not a package section");
            assertEquals(IndexFormat.NO_INDEX, index.findPackage(1, "com.example.dep"));
        }
    }

    @Test
    void theApplicationManifestReachesTheArchiveAndTheIndex() throws IOException {
        Path output = output();
        Manifest application = manifest(attributes -> {
            attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, "demo");
            attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, "3.2.1");
            attributes.putValue("Sealed", "true");
        });
        Attributes section = new Attributes();
        section.put(Attributes.Name.SPECIFICATION_VENDOR, "Example");
        application.getEntries().put("com/example/", section);

        RunnerJarResult result = RunnerJarBuilder.build(
                spec(output).applicationManifest(application)
                        .manifestAttributes(Map.of("Built-By", "the test"))
                        .addOpens(List.of("java.base/java.lang"))
                        .addExports(List.of("java.base/jdk.internal.misc"))
                        .enableNativeAccess(true)
                        .build(),
                BuildLogger.noOp());

        assertEquals(output, result.output());
        try (ZipReader archive = ZipReader.open(output)) {
            Attributes main = archive.manifest().orElseThrow().getMainAttributes();
            assertEquals("demo", main.getValue(Attributes.Name.IMPLEMENTATION_TITLE));
            assertEquals("3.2.1", main.getValue(Attributes.Name.IMPLEMENTATION_VERSION));
            assertEquals("the test", main.getValue("Built-By"));
            assertEquals("java.base/java.lang", main.getValue("Add-Opens"));
            assertEquals("java.base/jdk.internal.misc", main.getValue("Add-Exports"));
            assertEquals("ALL-UNNAMED", main.getValue("Enable-Native-Access"));
        }
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertEquals("demo", RunnerBuildTestAccess.jarImplTitle(index, 0));
            assertTrue(RunnerBuildTestAccess.jarSealedByDefault(index, 0));
            assertEquals("Example", RunnerBuildTestAccess.packageSpecVendor(index, index.findPackage(0,
                    "com.example")));
        }
    }

    @Test
    void theIndexAgreesWithTheArchiveItDescribes() throws IOException {
        Path output = output();
        RunnerJarBuilder.build(spec(output).build(), BuildLogger.noOp());

        try (RunnerJarArchive reader = RunnerJarArchive.open(output);
             ZipReader archive = ZipReader.open(output)) {
            Index index = reader.index();
            assertEquals(Files.size(output), RunnerBuildTestAccess.outerFileLength(index));

            // Every physical record of jar 0 has to name a real entry of the outer archive, at the very
            // offset the index sends the launcher to. The offset identifies the entry: an archive cannot
            // hold two entries whose data starts in the same place.
            Map<Long, ZipEntryInfo> byOffset = new LinkedHashMap<>();
            for (ZipEntryInfo entry : archive.entries()) {
                byOffset.put(entry.dataOffset(), entry);
            }
            int first = RunnerBuildTestAccess.jarFirstEntry(index, 0);
            for (int record = first; record < first + RunnerBuildTestAccess.jarEntryCount(index, 0); record++) {
                if (!index.entryPhysical(record)) {
                    continue;
                }
                String logical = index.entryName(record);
                ZipEntryInfo entry = byOffset.get(index.entryDataOffset(record));
                assertNotNull(entry, logical + " points at an offset no entry starts at");
                // A merged service entry keeps its literal name at the root; everything else is prefixed.
                assertTrue(entry.name().equals(logical)
                                || entry.name().equals(IndexFormat.CLASSES_PREFIX + logical),
                        logical + " points at the data of " + entry.name());
                assertEquals(entry.uncompressedSize(), index.entryUncompressedSize(record), logical);
                assertEquals(entry.crc32(), index.entryCrc32(record), logical);
                assertEquals(entry.dosTime() & 0xFFFFFFFFL, index.entryDosTime(record), logical);
            }

            for (int jarId = 1; jarId < index.jarCount(); jarId++) {
                ZipEntryInfo entry = archive.entry(index.jarName(jarId)).orElseThrow();
                assertEquals(entry.dataOffset(), index.jarDataOffset(jarId));
                assertEquals(entry.localHeaderOffset(), RunnerBuildTestAccess.jarLocalHeaderOffset(index, jarId));
                assertEquals(entry.uncompressedSize(), index.jarDataLength(jarId));
                index.validateJar(jarId);
            }
        }
    }

    @Test
    void preserveKeepsTheBytesOfADependency() throws IOException {
        Path output = output();
        RunnerJarBuilder.build(spec(output).compression(Compression.PRESERVE).build(), BuildLogger.noOp());

        byte[] original = Files.readAllBytes(plainDependency);
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertFalse(RunnerBuildTestAccess.nestedStored(index));
            assertEquals(original.length, index.jarDataLength(1));
            byte[] nested = reader.source().readFully(index.jarDataOffset(1), original.length);
            assertArrayEquals(original, nested, "PRESERVE copies a dependency byte for byte");

            int deflated = index.find("META-INF/services/com.example.Service");
            assertEquals(IndexFormat.METHOD_DEFLATED, index.entryMethod(deflated),
                    "the entry keeps the compression it had");
            assertEquals("com.example.dep.Dep\n", new String(reader.read(deflated), StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest
    @EnumSource(Compression.class)
    void writesAWorkFileOnlyForADependencyItRepacks(Compression compression) throws IOException {
        Path applicationJar = fixtures.resolve("work-files-application-" + compression + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(applicationJar))) {
            deflated(zip, "com/example/Application.class",
                    Files.readAllBytes(applicationClasses.resolve("com/example/Application.class")));
            deflated(zip, "application.yml", "from the jar".getBytes(StandardCharsets.UTF_8));
        }
        Path output = Files.createDirectories(fixtures.resolve("work-files-" + compression)).resolve("runner.jar");
        List<String> workFiles = new ArrayList<>();

        RunnerJarBuilder.build(spec(output)
                .applicationOutput(List.of(applicationJar))
                .compression(compression)
                .build(), BuildLogger.noOp(), 1, () -> workFiles.addAll(workFiles(output.getParent())));

        // What the work directory holds just before the archive is written: no copy of a preserved
        // dependency and nothing for the application jar, whose entries are streamed from the jar itself.
        assertEquals(compression == Compression.PRESERVE
                        ? List.of("launcher.jar")
                        : List.of("launcher.jar", "lib-0.jar", "lib-1.jar", "lib-2.jar"),
                workFiles);
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertEquals("from the jar",
                    new String(reader.read(index.find("application.yml")), StandardCharsets.UTF_8));
        }
        assertNoWorkDirectory(output.getParent());
        // A reader the build left open would keep the jar mapped, and Windows could not delete it.
        Files.delete(applicationJar);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aPreservedDependencyThatChangesDuringTheBuildFailsIt(boolean truncate) throws IOException {
        Path directory = Files.createDirectories(fixtures.resolve("changing-dependency-" + truncate));
        Path copy = directory.resolve("changing-lib.jar");
        Files.copy(plainDependency, copy);
        Path output = Files.createDirectories(directory.resolve("out")).resolve("runner.jar");
        Files.write(output, PREVIOUS_OUTPUT);
        // After the dependency was checksummed and laid out, before it is copied: a staged copy would hide
        // the change, so this also shows that the dependency itself is what gets nested.
        Runnable change = () -> {
            try (FileChannel channel = FileChannel.open(copy, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                if (truncate) {
                    channel.truncate(channel.size() - 1);
                } else {
                    long at = channel.size() / 2;
                    ByteBuffer one = ByteBuffer.allocate(1);
                    channel.read(one, at);
                    one.put(0, (byte) ~one.get(0)).rewind();
                    channel.write(one, at);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output)
                .compression(Compression.PRESERVE)
                .dependencies(List.of(Dependency.of(copy)))
                .build(), BuildLogger.noOp(), 1, change));

        assertTrue(failure.getMessage().contains(copy.toString()), failure.getMessage());
        if (!truncate) {
            assertTrue(failure.getMessage().contains("CRC-32"), failure.getMessage());
        }
        assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output),
                "a dependency that changed must not reach the output");
        assertNoWorkDirectory(output.getParent());
    }

    /** The names of the files in the one work directory under {@code directory}, sorted. */
    private static List<String> workFiles(Path directory) {
        try (Stream<Path> children = Files.list(directory)) {
            List<Path> work = children
                    .filter(child -> child.getFileName().toString().startsWith(".micronaut-runner-"))
                    .toList();
            assertEquals(1, work.size(), "one work directory: " + work);
            try (Stream<Path> files = Files.list(work.get(0))) {
                return files.map(file -> file.getFileName().toString()).sorted().toList();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void buildsTheSameBytesTwiceEvenInAnotherTimeZone() throws IOException {
        Path first = output();
        Path second = output();
        Path third = output();
        RunnerJarBuilder.build(spec(first).build(), BuildLogger.noOp());
        RunnerJarBuilder.build(spec(second).build(), BuildLogger.noOp());
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            RunnerJarBuilder.build(spec(third).build(), BuildLogger.noOp());
        } finally {
            TimeZone.setDefault(original);
        }

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second),
                "the same inputs have to produce the same bytes");
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(third),
                "and they have to produce them in any time zone");
    }

    @Test
    void laterApplicationOutputsNeverOverrideEarlierOnes() throws IOException {
        Path overlay = fixtures.resolve("overlay");
        write(overlay.resolve("application.yml"), "overlay\n");
        write(overlay.resolve("only-here.txt"), "only here\n");
        Path output = output();

        RunnerJarResult result = RunnerJarBuilder.build(
                spec(output).applicationOutput(
                        List.of(applicationClasses, applicationResources, overlay)).build(),
                BuildLogger.noOp());

        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("application.yml")),
                "the duplicate is reported: " + result.warnings());
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertEquals("micronaut:\n  application:\n    name: test\n",
                    new String(reader.read(index.find("application.yml")), StandardCharsets.UTF_8));
            assertEquals("only here\n",
                    new String(reader.read(index.find("only-here.txt")), StandardCharsets.UTF_8));
        }
    }

    @Test
    void verifiesEveryEntryWhenAsked() throws IOException {
        Path output = output();
        System.setProperty("micronaut.runner.build.verifyAll", "true");
        try {
            RunnerJarResult result = RunnerJarBuilder.build(spec(output).build(), BuildLogger.systemErr());
            assertTrue(result.entryCount() > 0);
        } finally {
            System.clearProperty("micronaut.runner.build.verifyAll");
        }
    }

    @Test
    void refusesToPackageWhatItCannotPackage() throws IOException {
        Path output = output();
        IOException missingMain = assertThrows(IOException.class, () -> RunnerJarBuilder.build(
                spec(output).mainClass("com.example.NotThere").build(), BuildLogger.noOp()));
        assertTrue(missingMain.getMessage().contains("com/example/NotThere.class"), missingMain.getMessage());

        IOException missingInput = assertThrows(IOException.class, () -> RunnerJarBuilder.build(
                spec(output).applicationOutput(List.of(fixtures.resolve("nowhere"))).build(),
                BuildLogger.noOp()));
        assertTrue(missingInput.getMessage().contains("does not exist"), missingInput.getMessage());

        IOException inside = assertThrows(IOException.class, () -> RunnerJarBuilder.build(
                spec(applicationClasses.resolve("app.jar")).build(), BuildLogger.noOp()));
        assertTrue(inside.getMessage().contains("is inside the application output"), inside.getMessage());
    }

    // ------------------------------------------------------------------ precompiled Logback configuration

    private static final String LOGBACK_XML = """
            <configuration>
                <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder>
                        <pattern>%msg%n</pattern>
                    </encoder>
                </appender>
                <root level="WARN">
                    <appender-ref ref="STDOUT"/>
                </root>
            </configuration>
            """;

    private static final List<String> GENERATED_LOGBACK_ENTRIES = List.of(LogbackPrecompiler.CONFIGURATOR_ENTRY,
            LogbackPrecompiler.FALLBACK_ENTRY, LogbackPrecompiler.SERVICE_ENTRY);

    private static final String STAND_DOWN = "No Logback configuration was precompiled because ";

    @Test
    void withoutLogbackThePrecompileFlagChangesNoByte() throws IOException {
        Path on = output();
        Path off = output();
        LogbackBuild first = logbackBuild(spec(on));
        RunnerJarResult second = RunnerJarBuilder.build(spec(off).option("precompileLogback", "false").build(),
                BuildLogger.noOp());

        assertFalse(first.result().logbackPrecompiled());
        assertFalse(second.logbackPrecompiled());
        assertEquals(List.of(STAND_DOWN + "logback-classic is not on the class path; Logback will configure itself"
                + " with Joran at startup"), first.logback());
        assertArrayEquals(Files.readAllBytes(on), Files.readAllBytes(off));
    }

    @Test
    void precompilesLogbackXmlIntoTheApplicationLayer() throws IOException {
        Path resources = logbackResources("precompiled", Map.of("logback.xml", LOGBACK_XML));
        Path output = output();

        LogbackBuild build = logbackBuild(logbackSpec(output, resources, realLogback()));

        assertTrue(build.result().logbackPrecompiled());
        assertEquals(List.of(), build.result().warnings());
        assertEquals(1, build.logback().size(), build.logback()::toString);
        assertTrue(build.logback().get(0).startsWith("Precompiled logback.xml (application layer) into "
                + LogbackPrecompiler.CONFIGURATOR_CLASS + ": 1 appender, 1 pattern, "), build.logback()::toString);
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            for (String name : GENERATED_LOGBACK_ENTRIES) {
                assertNotEquals(IndexFormat.NO_INDEX, index.find(name), name);
            }
            assertNotEquals(IndexFormat.NO_INDEX, index.findClass(LogbackPrecompiler.CONFIGURATOR_CLASS));
            assertEquals(LogbackPrecompiler.CONFIGURATOR_CLASS + "\n", new String(
                    reader.read(index.find(LogbackPrecompiler.SERVICE_ENTRY)), StandardCharsets.UTF_8));
            assertEquals(LOGBACK_XML, new String(reader.read(index.find("logback.xml")), StandardCharsets.UTF_8),
                    "logback.xml stays in the archive for the fallbacks");
        }
    }

    @Test
    void precompilingIsTheSameForBothCompressionModesAndReproducible() throws IOException {
        Path resources = logbackResources("reproducible", Map.of("logback.xml", LOGBACK_XML));
        Path first = output();
        Path second = output();
        Path preserved = output();
        logbackBuild(logbackSpec(first, resources, realLogback()));
        logbackBuild(logbackSpec(second, resources, realLogback()));
        LogbackBuild preserve = logbackBuild(logbackSpec(preserved, resources, realLogback())
                .compression(Compression.PRESERVE));

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        assertTrue(preserve.result().logbackPrecompiled());
        try (RunnerJarArchive stored = RunnerJarArchive.open(first);
             RunnerJarArchive nested = RunnerJarArchive.open(preserved)) {
            for (String name : GENERATED_LOGBACK_ENTRIES) {
                assertArrayEquals(stored.read(stored.index().find(name)), nested.read(nested.index().find(name)),
                        name);
            }
        }
    }

    /**
     * A {@code logback.xml} that only a dependency carries is read from that dependency's nested jar, which is a
     * repacked copy in STORED and the dependency itself in PRESERVE, and the log names the dependency.
     */
    @ParameterizedTest
    @EnumSource(value = Compression.class, names = {"STORED", "PRESERVE"})
    void precompilesALogbackXmlThatOnlyADependencyCarries(Compression compression) throws IOException {
        Path resources = logbackResources("dependency-only-" + compression, Map.of());
        Path configuration = fixtures.resolve("libs/logging-configuration.jar");
        writeJar(configuration, manifest(attributes -> { }),
                Map.of("logback.xml", LOGBACK_XML.getBytes(StandardCharsets.UTF_8)));
        List<Dependency> dependencies = realLogback();
        dependencies.add(Dependency.of(configuration));
        Path output = output();

        LogbackBuild build = logbackBuild(logbackSpec(output, resources, dependencies).compression(compression));

        assertTrue(build.result().logbackPrecompiled(), build.logback()::toString);
        assertEquals(List.of(), build.result().warnings());
        assertEquals(1, build.logback().size(), build.logback()::toString);
        assertTrue(build.logback().get(0).startsWith("Precompiled logback.xml (logging-configuration.jar) into "
                + LogbackPrecompiler.CONFIGURATOR_CLASS + ": 1 appender, 1 pattern, "), build.logback()::toString);
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            for (String name : GENERATED_LOGBACK_ENTRIES) {
                int entry = index.find(name);
                assertNotEquals(IndexFormat.NO_INDEX, entry, name);
                assertEquals(0, RunnerBuildTestAccess.entryJarId(index, entry),
                        name + " belongs to the application layer");
            }
            int logbackXml = index.find("logback.xml");
            assertNotEquals(IndexFormat.NO_INDEX, logbackXml);
            assertNotEquals(0, RunnerBuildTestAccess.entryJarId(index, logbackXml),
                    "logback.xml stays in the dependency");
        }
    }

    /**
     * A packaged configuration that sets logger levels, as most do, does not stand the precompiler down: only a
     * {@code config} key next to the word {@code logger} does.
     */
    @Test
    void aPackagedConfigurationWithoutAConfigKeyIsStillPrecompiled() throws IOException {
        Path resources = logbackResources("logger-levels", Map.of("logback.xml", LOGBACK_XML,
                "application.yml", """
                        micronaut:
                          application:
                            name: demo
                          config-client:
                            enabled: false
                        logger:
                          levels:
                            com.example: DEBUG
                        """,
                "application-test.toml", "[logger.levels]\n\"com.example\" = \"DEBUG\"\n",
                "config/application.json", "{\"logger\":{\"levels\":{\"com.example\":\"DEBUG\"}}}\n"));
        Path output = output();

        LogbackBuild build = logbackBuild(logbackSpec(output, resources, realLogback()));

        assertTrue(build.result().logbackPrecompiled(), build.logback()::toString);
    }

    @ParameterizedTest
    @ValueSource(strings = {"no logback.xml", "logback-test.xml", "logback.groovy", "versioned logback.xml",
        "application Configurator", "dependency Configurator", "application.properties logger.config",
        "application.yml logger config", "bootstrap.yml configurationFile", "application.toml logger table",
        "application.toml inline table", "application.yml flow style", "application.json on one line",
        "application.groovy closure", "application.yml merged anchor", "Logback outside the range",
        "mismatched versions", "subset rejection", "flag off"})
    void standsDownWithOneInformationalLine(String condition) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("logback.xml", LOGBACK_XML);
        List<Dependency> dependencies = realLogback();
        String reason;
        switch (condition) {
            case "no logback.xml" -> {
                files.remove("logback.xml");
                reason = "there is no logback.xml";
            }
            case "logback-test.xml" -> {
                files.put("logback-test.xml", LOGBACK_XML);
                reason = "application layer has logback-test.xml";
            }
            case "logback.groovy" -> {
                files.put("logback.groovy", "root(WARN)\n");
                reason = "application layer has logback.groovy";
            }
            case "versioned logback.xml" -> {
                files.put("META-INF/versions/21/logback.xml", LOGBACK_XML);
                reason = "application layer has META-INF/versions/21/logback.xml";
            }
            case "application Configurator" -> {
                files.put(LogbackPrecompiler.SERVICE_ENTRY, "com.example.MyConfigurator\n");
                reason = "application layer already registers a Logback Configurator ("
                        + LogbackPrecompiler.SERVICE_ENTRY + ")";
            }
            case "dependency Configurator" -> {
                Path aot = fixtures.resolve("libs/aot-configurator.jar");
                writeJar(aot, manifest(attributes -> { }), Map.of(LogbackPrecompiler.SERVICE_ENTRY,
                        "io.micronaut.aot.StaticLogbackConfiguration\n".getBytes(StandardCharsets.UTF_8)));
                dependencies.add(Dependency.of(aot));
                reason = "aot-configurator.jar already registers a Logback Configurator";
            }
            case "application.properties logger.config" -> {
                files.put("application.properties", "micronaut.application.name=demo\nlogger.config=custom.xml\n");
                reason = "the packaged application.properties may set logger.config";
            }
            case "application.yml logger config" -> {
                files.put("application.yml", "logger:\n  levels:\n    com.example: DEBUG\n  config: custom.xml\n");
                reason = "the packaged application.yml may set logger.config";
            }
            case "bootstrap.yml configurationFile" -> {
                files.put("config/bootstrap.yml", "logback:\n  configurationFile: custom.xml\n");
                reason = "the packaged config/bootstrap.yml may set";
            }
            case "application.toml logger table" -> {
                files.put("application.toml", "[micronaut.application]\nname = \"demo\"\n\n[logger]\n"
                        + "config = \"custom.xml\"\n");
                reason = "the packaged application.toml may set logger.config";
            }
            case "application.toml inline table" -> {
                files.put("application-prod.toml", "logger = { config = \"custom.xml\" }\n");
                reason = "the packaged application-prod.toml may set logger.config";
            }
            case "application.yml flow style" -> {
                files.put("application.yml", "logger: {config: custom.xml}\n");
                reason = "the packaged application.yml may set logger.config";
            }
            case "application.json on one line" -> {
                files.put("application.json", "{\"logger\":{\"config\":\"custom.xml\"}}");
                reason = "the packaged application.json may set logger.config";
            }
            case "application.groovy closure" -> {
                files.put("application.groovy", "logger { config = 'custom.xml' }\n");
                reason = "the packaged application.groovy may set logger.config";
            }
            case "application.yml merged anchor" -> {
                // The config key stands before the logger key, and on another level.
                files.put("application.yml", "shared: &shared\n  Config: custom.xml\nLogger:\n  <<: *shared\n");
                reason = "the packaged application.yml may set logger.config";
            }
            case "Logback outside the range" -> {
                dependencies = fakeLogback("1.4.14", "1.4.14");
                reason = "Logback 1.4.14 is outside the tested range [1.5.37, 1.6)";
            }
            case "mismatched versions" -> {
                dependencies = fakeLogback("1.5.37", "1.5.38");
                reason = "logback-classic 1.5.37 and logback-core 1.5.38 differ";
            }
            case "subset rejection" -> {
                files.put("logback.xml", LOGBACK_XML.replace("<configuration>",
                        "<configuration>\n    <property name=\"APP\" value=\"demo\"/>"));
                reason = "logback.xml (application layer) is outside what the precompiler can reproduce exactly:"
                        + " <property> (line 2) is not supported";
            }
            case "flag off" -> reason = "the precompileLogback option is false";
            default -> throw new IllegalArgumentException(condition);
        }
        Path resources = logbackResources(condition.replace(' ', '-'), files);
        Path output = output();
        RunnerJarSpec.Builder spec = logbackSpec(output, resources, dependencies);
        if (condition.equals("flag off")) {
            spec.option("precompileLogback", "false");
        }

        LogbackBuild build = logbackBuild(spec);

        assertFalse(build.result().logbackPrecompiled());
        assertEquals(List.of(), build.result().warnings());
        assertEquals(1, build.logback().size(), build.logback()::toString);
        String line = build.logback().get(0);
        assertTrue(line.startsWith(STAND_DOWN), line);
        assertTrue(line.contains(reason), () -> line + "\ndoes not name: " + reason);
        assertTrue(line.endsWith("; Logback will configure itself with Joran at startup"), line);
        assertNoGeneratedLogbackEntries(output);
    }

    @Test
    void aTakenGeneratedNameIsAWarning() throws IOException {
        Path resources = logbackResources("taken", Map.of("logback.xml", LOGBACK_XML,
                LogbackPrecompiler.CONFIGURATOR_ENTRY, "not ours"));
        Path output = output();

        LogbackBuild build = logbackBuild(logbackSpec(output, resources, realLogback()));

        assertFalse(build.result().logbackPrecompiled());
        assertEquals(List.of(), build.logback());
        assertEquals(1, build.result().warnings().size(), build.result().warnings()::toString);
        assertTrue(build.result().warnings().get(0).contains("already carries '"
                + LogbackPrecompiler.CONFIGURATOR_ENTRY + "'"), build.result().warnings()::toString);
        try (ZipReader archive = ZipReader.open(output)) {
            assertFalse(archive.entry(IndexFormat.CLASSES_PREFIX + LogbackPrecompiler.FALLBACK_ENTRY).isPresent());
            assertFalse(archive.entry(IndexFormat.CLASSES_PREFIX + LogbackPrecompiler.SERVICE_ENTRY).isPresent());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aFrontEndOrEmitterFailureIsOneWarningAndTheBuildSucceeds(boolean frontEnd) throws IOException {
        Path resources = logbackResources("failure-" + frontEnd, Map.of("logback.xml", LOGBACK_XML));
        Path output = output();
        LogbackPrecompiler.descriptionHook = description -> {
            if (frontEnd) {
                throw new IllegalStateException("forced front-end failure");
            }
            // An int setter handed a String: the emitted class no longer verifies.
            Map<String, Object> broken = new LinkedHashMap<>(description);
            List<Object> operations = new ArrayList<>((List<?>) broken.get("operations"));
            @SuppressWarnings("unchecked")
            Map<String, Object> appender = new LinkedHashMap<>((Map<String, Object>) operations.get(0));
            appender.put("steps", List.of(Map.of("step", "property", "method", "setName",
                    "descriptor", "(I)V", "value", "not an int")));
            operations.set(0, appender);
            broken.put("operations", operations);
            return broken;
        };
        LogbackBuild build;
        try {
            build = logbackBuild(logbackSpec(output, resources, realLogback()));
        } finally {
            LogbackPrecompiler.descriptionHook = java.util.function.UnaryOperator.identity();
        }

        assertTrue(Files.isRegularFile(output));
        assertFalse(build.result().logbackPrecompiled());
        assertEquals(List.of(), build.logback());
        assertEquals(1, build.result().warnings().size(), build.result().warnings()::toString);
        String warning = build.result().warnings().get(0);
        assertTrue(warning.startsWith(STAND_DOWN + "it could not be compiled: "), warning);
        assertTrue(warning.contains(frontEnd ? "forced front-end failure" : "does not verify"), warning);
        assertNoGeneratedLogbackEntries(output);
    }

    private static void assertNoGeneratedLogbackEntries(Path output) throws IOException {
        try (ZipReader archive = ZipReader.open(output)) {
            for (ZipEntryInfo entry : archive.entries()) {
                assertFalse(entry.name().startsWith(IndexFormat.CLASSES_PREFIX + LogbackPrecompiler.PACKAGE_PATH),
                        entry.name());
            }
        }
    }

    private RunnerJarSpec.Builder logbackSpec(Path output, Path resources, List<Dependency> dependencies) {
        return spec(output).applicationOutput(List.of(applicationClasses, resources)).dependencies(dependencies);
    }

    private static Path logbackResources(String name, Map<String, String> files) throws IOException {
        Path directory = fixtures.resolve("logback-resources/" + name);
        for (Map.Entry<String, String> file : files.entrySet()) {
            write(directory.resolve(file.getKey()), file.getValue());
        }
        Files.createDirectories(directory);
        return directory;
    }

    /** The logback-classic, logback-core and slf4j-api jars of this test class path. */
    private static List<Dependency> realLogback() {
        List<Dependency> dependencies = new ArrayList<>();
        for (Class<?> type : List.of(ch.qos.logback.classic.LoggerContext.class, ch.qos.logback.core.Context.class,
                org.slf4j.ILoggerFactory.class)) {
            dependencies.add(Dependency.of(LogbackPrecompilerTest.jarOf(type)));
        }
        return dependencies;
    }

    /** Jars that look like Logback to the precompiler's survey, at the given versions, and hold nothing else. */
    private static List<Dependency> fakeLogback(String classicVersion, String coreVersion) throws IOException {
        Path classic = fixtures.resolve("libs/fake-logback-classic-" + classicVersion + ".jar");
        Path core = fixtures.resolve("libs/fake-logback-core-" + coreVersion + ".jar");
        Path slf4j = fixtures.resolve("libs/fake-slf4j-api.jar");
        writeJar(classic, manifest(attributes -> attributes.put(Attributes.Name.IMPLEMENTATION_VERSION,
                classicVersion)), Map.of("ch/qos/logback/classic/LoggerContext.class", new byte[] {1}));
        writeJar(core, manifest(attributes -> attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, coreVersion)),
                Map.of("ch/qos/logback/core/Context.class", new byte[] {1}));
        writeJar(slf4j, manifest(attributes -> { }), Map.of("org/slf4j/ILoggerFactory.class", new byte[] {1}));
        return new ArrayList<>(List.of(Dependency.of(classic), Dependency.of(core), Dependency.of(slf4j)));
    }

    /** Builds, keeping the informational lines about Logback. */
    private static LogbackBuild logbackBuild(RunnerJarSpec.Builder spec) throws IOException {
        List<String> logback = new ArrayList<>();
        RunnerJarResult result = RunnerJarBuilder.build(spec.build(), new BuildLogger() {
            @Override
            public void info(String message) {
                if (message.startsWith(STAND_DOWN) || message.startsWith("Precompiled logback.xml")) {
                    logback.add(message);
                }
            }

            @Override
            public void warn(String message) {
            }
        });
        return new LogbackBuild(result, logback);
    }

    /**
     * One build and what it said about Logback.
     *
     * @param result  the result
     * @param logback the informational lines that mention Logback
     */
    private record LogbackBuild(RunnerJarResult result, List<String> logback) {
    }

    // ------------------------------------------------------------ startup class list

    @Test
    void embedsTheStartupClassesTheArchiveHoldsInListOrder() throws IOException {
        // A second jar that also holds Dep, after the first on the class path.
        Path shadowing = fixtures.resolve("libs/dep-copy.jar");
        Files.copy(plainDependency, shadowing, StandardCopyOption.REPLACE_EXISTING);
        Path list = startupClasses("""
                # recorded by hand
                com.example.mr.Feature
                com.example.dep.Dep

                com.example.Gone
                com.example.Application
                com.example.dep.Dep
                jrt:java.util.zip.CRC32
                """);
        Path output = output();
        List<Dependency> dependencies = new ArrayList<>(spec(output).build().dependencies());
        dependencies.add(Dependency.of(shadowing));
        RecordingLogger logger = new RecordingLogger();

        RunnerJarResult result = RunnerJarBuilder.build(
                spec(output).dependencies(dependencies).startupClasses(list).build(), logger);

        List<String> expected = List.of("com.example.mr.Feature", "com.example.dep.Dep", "com.example.Application");
        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertEquals(expected.size(), index.preloadCount());
            for (int position = 0; position < expected.size(); position++) {
                String name = expected.get(position);
                int record = index.preloadRecord(position);
                assertEquals(index.findClass(name), record, () -> name + " is not the record a class lookup finds");
                assertEquals(name.replace('.', '/') + ".class", index.entryName(record));
            }
            int feature = index.preloadRecord(0);
            assertTrue(RunnerBuildTestAccess.entryVersionedAlias(index, feature)
                    && RunnerBuildTestAccess.entryMrVersion(index, feature) == 17,
                    "a multi-release class is listed by the head of its chain, which holds the versioned alias");
            assertEquals("MICRONAUT-INF/lib/dep-lib.jar",
                    index.jarName(RunnerBuildTestAccess.entryJarId(index, index.preloadRecord(1))),
                    "a class in two jars is listed from the jar that comes first on the class path");
            assertEquals(1, index.jdkPreloadCount());
            assertEquals("java.util.zip.CRC32", RunnerBuildTestAccess.jdkPreloadName(index, 0));
        }

        List<String> dropped = logger.warnings.stream().filter(line -> line.contains("startup classes")).toList();
        assertEquals(1, dropped.size(), () -> "one warning carries the count: " + logger.warnings);
        assertTrue(dropped.get(0).contains("dropped 2 of 5 startup classes: not in this archive or listed twice"),
                dropped.get(0));
        assertTrue(result.warnings().contains(dropped.get(0)), "the result carries the warning too");
        assertEquals(1, logger.infos.stream().filter(line -> line.startsWith("Embedded 3 startup classes")).count(),
                () -> logger.infos.toString());
        assertEquals(list.toString(), result.effectiveOptions().get("startupClasses"));
    }

    @Test
    void embedsTheArchiveClassesOfARawClassLoadLog() throws IOException {
        Path output = output();
        String archive = "jar:file:" + output.toUri().getRawPath() + "!/MICRONAUT-INF/";
        Path list = startupClasses("""
                [0.011s][info][class,load] java.lang.Object source: shared objects file
                [0.052s][info][class,load] io.micronaut.runner.Launcher source: file:/work/app.jar
                [0.071s][info][class,load] com.example.Application source: %1$sclasses/
                [0.072s][info][class,load] java.util.zip.CRC32 source: jrt:/java.base
                [0.074s][info][class,load] com.example.Greeter source: %1$sclasses/
                [0.075s][info][class,load] com.example.Greeter$$Lambda/0x0000000800c01234 source: com.example.Greeter
                [0.076s][info][class,load] java.lang.invoke.LambdaForm$MH/0x0000000800c04400 source: __JVM_LookupDefineClass__
                [0.080s][info][class,load] com.example.dep.Dep source: %1$slib/dep-lib.jar!/
                [0.081s][info][class,load] java.sql.Timestamp source: jrt:/java.sql
                [0.082s][info][class,load] java.util.zip.CRC32 source: jrt:/java.base
                """.formatted(archive));
        RecordingLogger logger = new RecordingLogger();

        RunnerJarBuilder.build(spec(output).startupClasses(list).build(), logger);

        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            Index index = reader.index();
            assertEquals(3, index.preloadCount());
            assertEquals(index.findClass("com.example.Application"), index.preloadRecord(0));
            assertEquals(index.findClass("com.example.Greeter"), index.preloadRecord(1));
            assertEquals(index.findClass("com.example.dep.Dep"), index.preloadRecord(2));
            assertEquals(2, index.jdkPreloadCount(), "the JDK classes of the log are kept by name, in order");
            assertEquals("java.util.zip.CRC32", RunnerBuildTestAccess.jdkPreloadName(index, 0));
            assertEquals("java.sql.Timestamp", RunnerBuildTestAccess.jdkPreloadName(index, 1));
        }
        assertTrue(logger.warnings.stream().noneMatch(line -> line.contains("startup class")),
                () -> "the skipped sources are not reported: " + logger.warnings);
        assertEquals(1, logger.infos.stream()
                        .filter(line -> line.startsWith("Embedded 3 startup classes and 2 JDK classes")).count(),
                () -> logger.infos.toString());
    }

    @ParameterizedTest(name = "a recording whose classes all log as ''{0}''")
    @ValueSource(strings = {"shared objects file", "shared objects file (top)", "file:/work/app.jar"})
    void aRecordingWithoutArchiveClassesEmbedsNothingAndSaysWhy(String source) throws IOException {
        Path list = startupClasses("""
                [0.011s][info][class,load] java.lang.Object source: shared objects file
                [0.071s][info][class,load] com.example.Application source: %1$s
                [0.074s][info][class,load] com.example.Greeter source: %1$s
                [0.082s][info][class,load] java.util.zip.CRC32 source: jrt:/java.base
                """.formatted(source));
        Path plain = output();
        RunnerJarBuilder.build(spec(plain).build(), BuildLogger.noOp());
        Path output = output();
        RecordingLogger logger = new RecordingLogger();

        RunnerJarResult result = RunnerJarBuilder.build(spec(output).startupClasses(list).build(), logger);

        try (RunnerJarArchive reader = RunnerJarArchive.open(output)) {
            assertEquals(0, reader.index().preloadCount());
            assertEquals(0, reader.index().jdkPreloadCount(), "nothing of such a recording is embedded");
        }
        List<String> warnings = logger.warnings.stream().filter(line -> line.contains("startup class")).toList();
        assertEquals(1, warnings.size(), () -> logger.warnings.toString());
        assertTrue(warnings.get(0).contains("CDS or AOT cache") && warnings.get(0).contains("'file:' code source")
                        && warnings.get(0).contains("-Dmicronaut.runner.aot.training=true"), warnings.get(0));
        assertTrue(result.warnings().contains(warnings.get(0)));
        assertArrayEquals(Files.readAllBytes(plain), Files.readAllBytes(output),
                "a list that embeds nothing leaves the archive byte for byte what it is without one");
    }

    @Test
    void aStartupClassListThatDoesNotExistFailsTheBuildBeforeAnythingIsWritten() throws IOException {
        Path output = existingOutput();
        Path missing = fixtures.resolve("lists/missing.log");

        IOException failure = assertThrows(IOException.class,
                () -> RunnerJarBuilder.build(spec(output).startupClasses(missing).build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains("startup class list") && failure.getMessage().contains("missing.log"),
                failure.getMessage());
        assertArrayEquals(PREVIOUS_OUTPUT, Files.readAllBytes(output));
    }

    @ParameterizedTest(name = "{0} unknown names of 20, re-record hint {1}")
    @CsvSource({"3, true", "1, false"})
    void theDroppedNamesWarningSaysAProfileLooksStaleAboveATenth(int unknown, boolean hint) throws IOException {
        // 20 names, none of them twice, because a repeat is dropped too: some the archive does not hold, and
        // the rest from a dependency written for this test.
        List<String> names = new ArrayList<>();
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            if (i < unknown) {
                names.add("com.example.Gone" + i);
            } else {
                names.add("com.example.many.Known" + i);
                classes.put("com/example/many/Known" + i + ".class", "known".getBytes(StandardCharsets.UTF_8));
            }
        }
        Path many = fixtures.resolve("libs/many-" + unknown + ".jar");
        writeJar(many, manifest(attributes -> { }), classes);
        Path list = startupClasses(String.join("\n", names) + "\n");
        Path output = output();
        List<Dependency> dependencies = new ArrayList<>(spec(output).build().dependencies());
        dependencies.add(Dependency.of(many));
        RecordingLogger logger = new RecordingLogger();

        RunnerJarBuilder.build(spec(output).dependencies(dependencies).startupClasses(list).build(), logger);

        List<String> warnings = logger.warnings.stream().filter(line -> line.contains("startup classes")).toList();
        assertEquals(1, warnings.size(), () -> "one warning carries the count: " + logger.warnings);
        assertTrue(warnings.get(0).contains("dropped " + unknown + " of 20 startup classes"), warnings.get(0));
        assertEquals(hint, warnings.get(0).endsWith("The startup profile looks stale; re-record it with the"
                + " build's startup-profile task or goal."), warnings.get(0));
    }

    private Path startupClasses(String content) throws IOException {
        counter++;
        Path file = fixtures.resolve("lists/startup-classes-" + counter + ".log");
        write(file, content);
        return file;
    }

    @Test
    void aStartupClassListOrdersEachNestedJarReproducibly() throws IOException {
        Path list = startupClasses("com.example.api.Api\ncom.example.mr.Feature\n");
        Path first = output();
        RunnerJarBuilder.build(spec(first).startupClasses(list).build(), BuildLogger.noOp());
        Path again = output();
        RunnerJarBuilder.build(spec(again).startupClasses(list).build(), BuildLogger.noOp());
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(again), "the same list builds the same bytes");
        try (RunnerJarReader reader = RunnerJarReader.open(first)) {
            List<String> physical = physicalNames(reader.index(), 1);
            assertEquals(List.of("META-INF/MANIFEST.MF", "com/example/api/Api.class", "com/example/dep/Dep.class"),
                    physical.subList(0, 3), "the manifest, then the listed class, then the jar's own order");
            assertTrue(RunnerBuildTestAccess.nestedStored(reader.index()), "ordering compresses nothing");
        }

        Path absent = startupClasses("com.example.Absent\n");
        Path withAbsent = output();
        RunnerJarBuilder.build(spec(withAbsent).startupClasses(absent).build(), BuildLogger.noOp());
        Path plain = output();
        RunnerJarBuilder.build(spec(plain).build(), BuildLogger.noOp());
        assertArrayEquals(Files.readAllBytes(plain), Files.readAllBytes(withAbsent),
                "a list of classes the archive does not hold changes no byte");

        // The lists differ in dep-lib's class only: every other nested jar is the same file.
        Path other = startupClasses("com.example.dep.Dep\ncom.example.mr.Feature\n");
        Path otherOutput = output();
        RunnerJarBuilder.build(spec(otherOutput).startupClasses(other).build(), BuildLogger.noOp());
        Map<String, ZipEntry> before = libEntries(first);
        Map<String, ZipEntry> after = libEntries(otherOutput);
        assertEquals(before.keySet(), after.keySet());
        assertEquals(3, before.size(), before::toString);
        for (String name : before.keySet()) {
            assertEquals(before.get(name).getSize(), after.get(name).getSize(), name + ": reordering keeps the length");
            if (name.endsWith("/dep-lib.jar")) {
                assertNotEquals(before.get(name).getCrc(), after.get(name).getCrc(), name);
            } else {
                assertEquals(before.get(name).getCrc(), after.get(name).getCrc(), name);
            }
        }
    }

    @Test
    void hybridStoresTheListedClassesAndCompressesTheOthers() throws Exception {
        HybridFixture fixture = hybridFixture();
        Path list = startupClasses("hyapp.Main\nhy.Listed\n");
        Path output = output();
        RecordingLogger logger = new RecordingLogger();
        RunnerJarBuilder.build(fixture.spec(output, Compression.HYBRID).startupClasses(list).build(), logger);
        assertEquals(List.of(), logger.warnings);

        Map<String, byte[]> raw = rawRegions(fixture.dependency());
        byte[] debugSource = fixture.classes().get("hy/Debug.class");
        assertTrue(hasLocalVariableTable(debugSource), "the fixture class was compiled with -g");
        long debugCrc;
        long plainCrc;
        Map<String, Long> nestedCrcs = new TreeMap<>();
        try (RunnerJarReader reader = RunnerJarReader.open(output)) {
            Index index = reader.index();
            assertFalse(RunnerBuildTestAccess.nestedStored(index), "an archive with deflated nested entries does not claim to be stored");
            assertEquals(List.of("META-INF/MANIFEST.MF", "hy/Listed.class"), physicalNames(index, 1).subList(0, 2));
            assertEquals(IndexFormat.METHOD_STORED, index.entryMethod(physicalRecord(index, "hy/Listed.class")));
            assertEquals(IndexFormat.METHOD_STORED, index.entryMethod(physicalRecord(index, "hy/data.txt")));
            for (String cold : List.of("hy/Debug.class", "hy/Plain.class", "hy/Versioned.class",
                    "META-INF/versions/17/hy/Versioned.class")) {
                assertEquals(IndexFormat.METHOD_DEFLATED, index.entryMethod(physicalRecord(index, cold)), cold);
            }
            int debug = physicalRecord(index, "hy/Debug.class");
            assertFalse(Arrays.equals(raw.get("hy/Debug.class"), region(output, index, debug)),
                    "the stripped class was deflated afresh");
            byte[] stripped = reader.read(debug);
            assertFalse(hasLocalVariableTable(stripped), "and it inflates to the stripped class");
            debugCrc = index.entryCrc32(debug);
            int plain = physicalRecord(index, "hy/Plain.class");
            assertArrayEquals(raw.get("hy/Plain.class"), region(output, index, plain),
                    "a class with nothing to strip keeps the source entry's compressed bytes exactly");
            plainCrc = index.entryCrc32(plain);
            int first = RunnerBuildTestAccess.jarFirstEntry(index, 1);
            for (int record = first; record < first + RunnerBuildTestAccess.jarEntryCount(index, 1); record++) {
                if (index.entryPhysical(record) && !index.entryDirectory(record)) {
                    nestedCrcs.put(index.entryName(record), index.entryCrc32(record));
                }
            }
        }
        assertEquals("3 stored, 4 deflated", inspectHeader(output, "Nested compression"));

        Path keep = output();
        RunnerJarBuilder.build(fixture.spec(keep, Compression.HYBRID).startupClasses(list).stripLocalVariables(false)
                .build(), BuildLogger.noOp());
        try (RunnerJarReader reader = RunnerJarReader.open(keep)) {
            assertArrayEquals(raw.get("hy/Debug.class"),
                    region(keep, reader.index(), physicalRecord(reader.index(), "hy/Debug.class")),
                    "without stripping the -g class keeps its compressed bytes too");
        }

        // The launcher defines all three classes, serves the bytes the pipeline wrote, streams them through
        // jar: URLs, opens the nested jar as a JarFile and picks the right version of the unlisted class.
        Forked run = fork(output, List.of("-Dmicronaut.runner.verify=true"));
        assertEquals(0, run.status(), run::output);
        List<String> lines = run.output().lines().toList();
        assertTrue(lines.containsAll(List.of("listed=listed", "debug=debug012", "plain=plain", "versioned=seventeen")),
                run::output);
        assertTrue(lines.contains("resource hy/Debug.class " + debugCrc + " " + debugCrc), run::output);
        assertTrue(lines.contains("resource hy/Plain.class " + plainCrc + " " + plainCrc), run::output);
        Map<String, Long> streamed = new TreeMap<>();
        for (String line : lines) {
            if (line.startsWith("nested ")) {
                String[] fields = line.split(" ");
                streamed.put(fields[1], Long.parseLong(fields[2]));
            }
        }
        assertEquals(nestedCrcs, streamed, "the nested JarFile enumerates and reads every entry");
    }

    @Test
    void hybridWithoutAStartupClassListOrWithoutAHotEntryWritesWhatStoredWrites() throws IOException {
        HybridFixture fixture = hybridFixture();
        Path stored = output();
        RunnerJarBuilder.build(fixture.spec(stored, Compression.STORED).build(), BuildLogger.noOp());
        Path hybrid = output();
        RecordingLogger logger = new RecordingLogger();
        RunnerJarBuilder.build(fixture.spec(hybrid, Compression.HYBRID).build(), logger);
        assertEquals(List.of(DependencyStage.HYBRID_WITHOUT_LIST), logger.warnings);
        assertArrayEquals(Files.readAllBytes(stored), Files.readAllBytes(hybrid));

        // A list that names no class of any dependency: one warning, and the STORED output with that list.
        Path list = startupClasses("hyapp.Main\n");
        Path storedWithList = output();
        RunnerJarBuilder.build(fixture.spec(storedWithList, Compression.STORED).startupClasses(list).build(),
                BuildLogger.noOp());
        Path hybridWithList = output();
        RecordingLogger listLogger = new RecordingLogger();
        RunnerJarBuilder.build(fixture.spec(hybridWithList, Compression.HYBRID).startupClasses(list).build(),
                listLogger);
        assertEquals(List.of(DependencyStage.HYBRID_WITHOUT_HOT_ENTRY), listLogger.warnings);
        assertArrayEquals(Files.readAllBytes(storedWithList), Files.readAllBytes(hybridWithList));
        try (RunnerJarReader reader = RunnerJarReader.open(hybridWithList)) {
            assertTrue(RunnerBuildTestAccess.nestedStored(reader.index()), "a HYBRID build that compressed nothing is a STORED one");
            assertEquals(1, reader.index().preloadCount(), "and it embeds the list");
        }
        assertEquals("7 stored, 0 deflated", inspectHeader(hybridWithList, "Nested compression"));

        Path preserve = output();
        RunnerJarBuilder.build(fixture.spec(preserve, Compression.PRESERVE).startupClasses(list).build(),
                BuildLogger.noOp());
        try (RunnerJarReader reader = RunnerJarReader.open(stored)) {
            assertTrue(RunnerBuildTestAccess.nestedStored(reader.index()));
        }
        try (RunnerJarReader reader = RunnerJarReader.open(preserve)) {
            assertFalse(RunnerBuildTestAccess.nestedStored(reader.index()));
        }
        assertEquals("0 stored, 7 deflated", inspectHeader(preserve, "Nested compression"),
                "PRESERVE keeps each entry's own method");
    }

    @Test
    void aHybridBuildFailsOnABrokenColdClassAsAStoredBuildDoes() throws IOException {
        // With every transform off, HYBRID verifies the cold class's original bytes as it writes them. With a
        // transform on, the class path scan reads every class before any stage runs and fails first, in both
        // modes alike; ZipRepackerTest covers the pipeline's own read of a broken cold class. Another dependency
        // holds the listed class, so the HYBRID staging has a hot entry and does not fall back to STORED.
        Path hot = ClassFixtures.jar(fixtures.resolve("libs/hybrid-hot.jar"),
                Map.of("y/Hot.class", ZipReaderTest.repeat("a-hot-class-", 40)));
        Path trailing = deflatedWithTrailingByte(fixtures.resolve("libs/hybrid-trailing.jar"), "x/Cold.class",
                ZipReaderTest.repeat("a-cold-class-", 40));
        Path overproduced = ZipReaderTest.deflatedWithRecordedContent(fixtures.resolve("libs/hybrid-over.jar"),
                "x/Cold.class", ZipReaderTest.repeat("a-cold-class-", 40), ZipReaderTest.repeat("a-cold-class-", 20));
        Path list = startupClasses("y.Hot\n");
        for (Path dependency : List.of(trailing, overproduced)) {
            List<String> messages = new ArrayList<>();
            for (Compression compression : new Compression[] {Compression.STORED, Compression.HYBRID}) {
                IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output())
                        .dependencies(List.of(Dependency.of(hot), Dependency.of(dependency)))
                        .compression(compression)
                        .startupClasses(list)
                        .desugarLambdas(false)
                        .stripLocalVariables(false)
                        .build(), BuildLogger.noOp()));
                messages.add(failure.getMessage());
            }
            assertEquals(messages.get(0), messages.get(1), dependency::toString);
            assertTrue(messages.get(0).contains("x/Cold.class"), messages.get(0));
        }
    }

    /** The physical entry names of one jar of the index, in the order the nested jar holds them. */
    private static List<String> physicalNames(Index index, int jarId) {
        List<String> names = new ArrayList<>();
        int first = RunnerBuildTestAccess.jarFirstEntry(index, jarId);
        for (int record = first; record < first + RunnerBuildTestAccess.jarEntryCount(index, jarId); record++) {
            if (index.entryPhysical(record)) {
                names.add(index.entryName(record));
            }
        }
        return names;
    }

    /** The physical record of a name in jar 1. */
    private static int physicalRecord(Index index, String name) {
        int first = RunnerBuildTestAccess.jarFirstEntry(index, 1);
        for (int record = first; record < first + RunnerBuildTestAccess.jarEntryCount(index, 1); record++) {
            if (index.entryPhysical(record) && index.entryName(record).equals(name)) {
                return record;
            }
        }
        throw new AssertionError("no physical record " + name);
    }

    /** A record's data as the outer archive holds it: its compressed bytes when it is deflated. */
    private static byte[] region(Path archive, Index index, int record) throws IOException {
        return ZipReaderTest.bytesAt(archive, index.entryDataOffset(record), (int) index.entryCompressedSize(record));
    }

    /** Every entry's compressed region, by name. */
    private static Map<String, byte[]> rawRegions(Path jar) throws IOException {
        Map<String, byte[]> regions = new LinkedHashMap<>();
        try (ZipReader reader = ZipReader.open(jar)) {
            for (ZipEntryInfo entry : reader.entries()) {
                regions.put(entry.name(), reader.readRaw(entry));
            }
        }
        return regions;
    }

    private static boolean hasLocalVariableTable(byte[] bytes) {
        for (java.lang.classfile.MethodModel method : java.lang.classfile.ClassFile.of().parse(bytes).methods()) {
            if (method.code().isPresent() && method.code().get()
                    .findAttribute(java.lang.classfile.Attributes.localVariableTable()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** The nested jars of an archive, by entry name. */
    private static Map<String, ZipEntry> libEntries(Path archive) throws IOException {
        Map<String, ZipEntry> entries = new TreeMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            zip.stream().filter(entry -> entry.getName().startsWith(IndexFormat.LIB_PREFIX) && !entry.isDirectory())
                    .forEach(entry -> entries.put(entry.getName(), entry));
        }
        return entries;
    }

    /** One header value of the archive's {@code inspect} output. */
    private static String inspectHeader(Path archive, String label) throws IOException {
        String inspected;
        try (RunnerJarReader reader = RunnerJarReader.open(archive)) {
            java.io.PrintStream original = System.out;
            java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
            System.setOut(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
            try {
                RunnerBuildTestAccess.inspect(archive.toFile(), reader.index(), reader.source());
            } finally {
                System.setOut(original);
            }
            inspected = captured.toString(StandardCharsets.UTF_8);
        }
        for (String line : inspected.lines().toList()) {
            if (line.startsWith(label + " ")) {
                return line.substring(label.length()).strip();
            }
        }
        throw new AssertionError("no " + label + " in\n" + inspected);
    }

    private static Forked fork(Path archive, List<String> jvmArguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.addAll(jvmArguments);
        command.add("-jar");
        command.add(archive.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new Forked(process.waitFor(), output);
    }

    private record Forked(int status, String output) {
    }

    private static HybridFixture hybrid;

    /**
     * A dependency whose entries are all DEFLATED: a class to list, an unlisted class compiled with {@code -g}, an
     * unlisted one compiled without, a resource and a multi-release class; and an application whose main checks
     * what the launcher serves from it.
     */
    private static synchronized HybridFixture hybridFixture() throws IOException {
        if (hybrid != null) {
            return hybrid;
        }
        Path root = fixtures.resolve("hybrid");
        Path debugClasses = ClassFixtures.compile(root.resolve("src-g"), root.resolve("classes-g"), List.of("-g"),
                Map.of("hy/Listed.java", """
                                package hy;
                                public class Listed {
                                    public static String hello() {
                                        String greeting = "listed";
                                        return greeting;
                                    }
                                }
                                """,
                        "hy/Debug.java", """
                                package hy;
                                public class Debug {
                                    public static String hello() {
                                        StringBuilder text = new StringBuilder("debug");
                                        for (int index = 0; index < 3; index++) {
                                            text.append(index);
                                        }
                                        return text.toString();
                                    }
                                }
                                """));
        Path plainClasses = ClassFixtures.compile(root.resolve("src"), root.resolve("classes"), List.of(), Map.of(
                "hy/Plain.java", """
                        package hy;
                        public class Plain {
                            public static String hello() {
                                StringBuilder text = new StringBuilder("plain");
                                return text.toString();
                            }
                        }
                        """,
                "hy/Versioned.java", versionedSource("base")));
        Path seventeen = ClassFixtures.compile(root.resolve("src-17"), root.resolve("classes-17"), List.of(),
                Map.of("hy/Versioned.java", versionedSource("seventeen")));
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.putAll(ClassFixtures.classes(debugClasses));
        classes.putAll(ClassFixtures.classes(plainClasses));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("hy/Debug.class", classes.get("hy/Debug.class"));
        entries.put("hy/Listed.class", classes.get("hy/Listed.class"));
        entries.put("hy/Plain.class", classes.get("hy/Plain.class"));
        entries.put("hy/data.txt", ZipReaderTest.repeat("a resource of the hybrid fixture\n", 20));
        entries.put("hy/Versioned.class", classes.get("hy/Versioned.class"));
        entries.put("META-INF/versions/17/hy/Versioned.class",
                ClassFixtures.classes(seventeen).get("hy/Versioned.class"));
        Path dependency = root.resolve("hy-lib.jar");
        writeJar(dependency, manifest(attributes -> attributes.putValue("Multi-Release", "true")), entries);
        Path application = ClassFixtures.compile(root.resolve("app-src"), root.resolve("app-classes"),
                List.of("-cp", debugClasses + java.io.File.pathSeparator + plainClasses), Map.of(
                        "hyapp/Main.java", """
                                package hyapp;

                                import java.io.InputStream;
                                import java.net.JarURLConnection;
                                import java.net.URI;
                                import java.net.URL;
                                import java.util.Enumeration;
                                import java.util.jar.JarEntry;
                                import java.util.jar.JarFile;
                                import java.util.zip.CRC32;

                                public class Main {
                                    public static void main(String[] args) throws Exception {
                                        System.out.println("listed=" + hy.Listed.hello());
                                        System.out.println("debug=" + hy.Debug.hello());
                                        System.out.println("plain=" + hy.Plain.hello());
                                        System.out.println("versioned=" + hy.Versioned.which());
                                        ClassLoader loader = Main.class.getClassLoader();
                                        for (String name : new String[] {"hy/Debug.class", "hy/Plain.class"}) {
                                            long direct;
                                            try (InputStream in = loader.getResourceAsStream(name)) {
                                                direct = crc(in.readAllBytes());
                                            }
                                            URL url = new URI(loader.getResource(name).toString()).toURL();
                                            long streamed;
                                            try (InputStream in = url.openStream()) {
                                                streamed = crc(in.readAllBytes());
                                            }
                                            System.out.println("resource " + name + " " + direct + " " + streamed);
                                        }
                                        URL data = new URI(loader.getResource("hy/data.txt").toString()).toURL();
                                        JarURLConnection connection = (JarURLConnection) data.openConnection();
                                        connection.setUseCaches(false);
                                        try (JarFile nested = connection.getJarFile()) {
                                            Enumeration<JarEntry> entries = nested.entries();
                                            while (entries.hasMoreElements()) {
                                                JarEntry entry = entries.nextElement();
                                                if (entry.isDirectory()) {
                                                    continue;
                                                }
                                                try (InputStream in = nested.getInputStream(entry)) {
                                                    System.out.println("nested " + entry.getName() + " "
                                                            + crc(in.readAllBytes()));
                                                }
                                            }
                                        }
                                    }

                                    private static long crc(byte[] bytes) {
                                        CRC32 crc = new CRC32();
                                        crc.update(bytes);
                                        return crc.getValue();
                                    }
                                }
                                """));
        hybrid = new HybridFixture(dependency, application, Map.copyOf(classes));
        return hybrid;
    }

    private static String versionedSource(String answer) {
        return """
                package hy;
                public class Versioned {
                    public static String which() {
                        return "%s";
                    }
                }
                """.formatted(answer);
    }

    /**
     * The HYBRID fixture.
     *
     * @param dependency  the dependency jar, every entry DEFLATED
     * @param application the application's classes
     * @param classes     the dependency's classes as compiled, by entry name
     */
    private record HybridFixture(Path dependency, Path application, Map<String, byte[]> classes) {

        RunnerJarSpec.Builder spec(Path output, Compression compression) {
            return RunnerJarSpec.builder()
                    .mainClass("hyapp.Main")
                    .applicationOutput(List.of(application))
                    .dependencies(List.of(Dependency.of(dependency)))
                    .compression(compression)
                    .output(output);
        }
    }

    /** Keeps every line the builder logs, by level. */
    private static final class RecordingLogger implements BuildLogger {

        private final List<String> infos = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        @Override
        public void info(String message) {
            infos.add(message);
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }
    }

    // ------------------------------------------------------------------------ static service table

    private static final String GENERATED_SERVICES = "io/micronaut/runner/generated/services/";
    private static final String LOADER_REGISTRATION =
            "META-INF/services/io.micronaut.core.optim.StaticOptimizations$Loader";
    private static final String STATIC_SERVICES_LOADER =
            "io.micronaut.runner.generated.services.RunnerStaticServices";

    /** The fixture plus a micronaut-core the table serves: an application that gets a table. */
    private RunnerJarSpec.Builder specWithMicronautCore(Path output) {
        return spec(output).dependencies(List.of(
                Dependency.of(StaticServiceTableGeneratorTest.micronautCore51().get(0)),
                Dependency.of(plainDependency, "com.example:dep-lib:2.0.1")));
    }

    @ParameterizedTest
    @EnumSource(value = Compression.class, names = {"STORED", "PRESERVE"})
    void generatesAStaticServiceTableForAnApplicationWithMicronautCore(Compression compression)
            throws IOException {
        List<String> info = new ArrayList<>();
        Path output = output();

        RunnerJarResult result = RunnerJarBuilder.build(
                specWithMicronautCore(output).compression(compression).build(), recording(info));

        assertEquals(4, result.staticServiceSlots());
        String version = result.staticServicesCoreVersion().orElseThrow();
        assertEquals(List.of(), result.warnings());
        List<String> generated = info.stream().filter(line -> line.contains("static Micronaut service table")).toList();
        assertEquals(1, generated.size(), info::toString);
        assertTrue(generated.get(0).startsWith("Generated a static Micronaut service table: 3 types, 4 slots"
                + " (none left to Micronaut's scan) for micronaut-core " + version + " in "), generated::toString);
        assertTrue(generated.get(0).endsWith(" ms"), generated::toString);

        try (RunnerJarArchive reader = RunnerJarArchive.open(output); ZipReader archive = ZipReader.open(output)) {
            Index index = reader.index();
            List<String> application = logicalNames(index, 0);
            assertTrue(application.contains(GENERATED_SERVICES + "RunnerStaticServices.class"), application::toString);
            assertTrue(application.contains(GENERATED_SERVICES + "RunnerServiceTable.class"), application::toString);
            assertNotEquals(IndexFormat.NO_INDEX, index.findClass(STATIC_SERVICES_LOADER),
                    "the application layer defines the loader Micronaut's hook instantiates");
            assertEquals(STATIC_SERVICES_LOADER + "\n", new String(archive.read(archive.entry(
                    IndexFormat.CLASSES_PREFIX + LOADER_REGISTRATION).orElseThrow()), StandardCharsets.UTF_8));

            StaticServiceTableGeneratorTest.Table table = StaticServiceTableGeneratorTest.Table.of(archive.read(
                    archive.entry(IndexFormat.CLASSES_PREFIX + GENERATED_SERVICES + "RunnerServiceTable.class")
                            .orElseThrow()));
            // The order is derived from what planMergedServices stored, not sorted a second time.
            List<String> stored = new ArrayList<>();
            for (String name : names(archive)) {
                if (name.startsWith(SERVICE_DIRECTORY) && !name.endsWith("/")) {
                    stored.add(name.substring(SERVICE_DIRECTORY.length()));
                }
            }
            assertEquals(List.of("com.example.$Application$Definition", "com.example.dep.DepBean"), stored);
            assertEquals(stored.reversed(), table.names("io.micronaut.inject.BeanDefinitionReference"));
            assertEquals(List.of("com.example.dep.Dep"), table.names("com.example.Service"));
            assertEquals(List.of(STATIC_SERVICES_LOADER),
                    table.names("io.micronaut.core.optim.StaticOptimizations$Loader"));
        }
    }

    @Test
    void generatesNoStaticServiceTableWhenTheOptionIsOff() throws IOException {
        List<String> info = new ArrayList<>();
        Path output = output();

        RunnerJarResult result = RunnerJarBuilder.build(
                specWithMicronautCore(output).option("staticServices", "false").build(), recording(info));

        assertEquals(0, result.staticServiceSlots());
        assertEquals(java.util.Optional.empty(), result.staticServicesCoreVersion());
        assertEquals("false", result.effectiveOptions().get("staticServices"));
        assertNoStaticServiceTable(output);
        assertTrue(info.contains("No static Micronaut service table was generated because it was not requested;"
                + " Micronaut will scan for its services when the application starts"), info::toString);
    }

    @ParameterizedTest
    @EnumSource(Compression.class)
    void generatesNoStaticServiceTableWithoutMicronautCore(Compression compression) throws IOException {
        List<String> info = new ArrayList<>();
        Path output = output();

        RunnerJarResult result = RunnerJarBuilder.build(spec(output).compression(compression).build(),
                recording(info));

        assertTrue(spec(output).build().staticServices(), "the option is on, and the archive is what it was");
        assertEquals(0, result.staticServiceSlots());
        assertEquals(java.util.Optional.empty(), result.staticServicesCoreVersion());
        assertNoStaticServiceTable(output);
        assertTrue(info.contains("No static Micronaut service table was generated because the application has no"
                + " micronaut-core; Micronaut will scan for its services when the application starts"),
                info::toString);
    }

    @Test
    void putsItsRegistrationInFrontOfTheOneTheApplicationAlreadyHas() throws IOException {
        Path resources = fixtures.resolve("app/own-loader");
        write(resources.resolve(LOADER_REGISTRATION), "com.example.OwnLoader");
        Path loaderClass = resources.resolve("com/example/OwnLoader.class");
        Files.createDirectories(loaderClass.getParent());
        Files.write(loaderClass, StaticServiceTableGeneratorTest.classFile("com.example.OwnLoader",
                "java.lang.Object", "io.micronaut.core.optim.StaticOptimizations$Loader"));
        Path output = output();

        RunnerJarResult result = RunnerJarBuilder.build(specWithMicronautCore(output)
                .applicationOutput(List.of(applicationClasses, applicationResources, resources))
                .build(), BuildLogger.noOp());

        assertEquals(5, result.staticServiceSlots());
        assertEquals(List.of(), result.warnings());
        try (ZipReader archive = ZipReader.open(output)) {
            assertEquals(STATIC_SERVICES_LOADER + "\ncom.example.OwnLoader\n", new String(archive.read(
                    archive.entry(IndexFormat.CLASSES_PREFIX + LOADER_REGISTRATION).orElseThrow()),
                    StandardCharsets.UTF_8));
        }
    }

    @Test
    void warnsAndGeneratesNoStaticServiceTableWhenAGeneratedNameIsTaken() throws IOException {
        Path resources = fixtures.resolve("app/taken");
        byte[] own = "not a class".getBytes(StandardCharsets.UTF_8);
        Path taken = resources.resolve(GENERATED_SERVICES + "RunnerStaticServices.class");
        Files.createDirectories(taken.getParent());
        Files.write(taken, own);
        Path output = output();

        RunnerJarResult result = RunnerJarBuilder.build(specWithMicronautCore(output)
                .applicationOutput(List.of(applicationClasses, applicationResources, resources))
                .build(), BuildLogger.noOp());

        assertEquals(0, result.staticServiceSlots());
        assertEquals(List.of("No static Micronaut service table was generated because the application output"
                + " already carries '" + GENERATED_SERVICES + "RunnerStaticServices.class'; Micronaut will scan for"
                + " its services when the application starts"), result.warnings());
        try (RunnerJarArchive reader = RunnerJarArchive.open(output); ZipReader archive = ZipReader.open(output)) {
            assertEquals(List.of(GENERATED_SERVICES + "RunnerStaticServices.class"),
                    logicalNames(reader.index(), 0).stream()
                            .filter(name -> name.startsWith(GENERATED_SERVICES) && !name.endsWith("/")).toList());
            assertArrayEquals(own, archive.read(archive.entry(IndexFormat.CLASSES_PREFIX + GENERATED_SERVICES
                    + "RunnerStaticServices.class").orElseThrow()), "the application's own entry is untouched");
            assertTrue(archive.entry(IndexFormat.CLASSES_PREFIX + LOADER_REGISTRATION).isEmpty());
        }
    }

    /**
     * A dependency with a class file the ClassFile API cannot read, which the JVM would refuse to load as
     * well: the archive is packaged as it is without a table, and the service type that names the class is
     * left to Micronaut's scan.
     */
    @ParameterizedTest
    @EnumSource(Compression.class)
    void aClassFileThatCannotBeReadLeavesItsTypeToTheScanAndDoesNotFailTheBuild(Compression compression)
            throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/services/com.example.Spi",
                "com.example.broken.Provider\n".getBytes(StandardCharsets.UTF_8));
        entries.put("com/example/broken/Provider.class", StaticServiceTableGeneratorTest.withSuperclassOutOfRange(
                StaticServiceTableGeneratorTest.classFile("com.example.broken.Provider", "java.lang.Object")));
        Path broken = fixtures.resolve("libs/broken-provider-" + compression + ".jar");
        writeJar(broken, manifest(attributes -> { }), entries);
        List<String> info = new ArrayList<>();
        Path output = output();

        RunnerJarResult result = RunnerJarBuilder.build(specWithMicronautCore(output).compression(compression)
                .dependencies(List.of(Dependency.of(StaticServiceTableGeneratorTest.micronautCore51().get(0)),
                        Dependency.of(plainDependency, "com.example:dep-lib:2.0.1"), Dependency.of(broken)))
                .build(), recording(info));

        assertEquals(4, result.staticServiceSlots(), "the other types are served as they are without the jar");
        assertTrue(info.stream().anyMatch(line -> line.startsWith("Generated a static Micronaut service table: "
                + "3 types, 4 slots (1 left to Micronaut's scan: com.example.Spi) for micronaut-core ")),
                info::toString);
    }

    @Test
    void theStaticServiceTableIsReproducible() throws IOException {
        Path first = output();
        Path second = output();
        RunnerJarBuilder.build(specWithMicronautCore(first).build(), BuildLogger.noOp());
        RunnerJarBuilder.build(specWithMicronautCore(second).build(), BuildLogger.noOp(), 1);

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    private static void assertNoStaticServiceTable(Path output) throws IOException {
        try (RunnerJarArchive reader = RunnerJarArchive.open(output); ZipReader archive = ZipReader.open(output)) {
            for (String name : logicalNames(reader.index(), 0)) {
                assertFalse(name.startsWith(GENERATED_SERVICES), name);
                assertNotEquals(LOADER_REGISTRATION, name, "no loader line without a table");
            }
            for (String name : names(archive)) {
                assertFalse(name.contains(GENERATED_SERVICES), name);
            }
        }
    }

    private static BuildLogger recording(List<String> info) {
        return new BuildLogger() {
            @Override
            public void info(String message) {
                info.add(message);
            }

            @Override
            public void warn(String message) {
            }
        };
    }

    /**
     * Records, at every warning, the thread that emitted it and the daemon staging threads alive then. The
     * pool is only shut down after every stage has been joined, so its threads are all still alive.
     */
    private static final class StagingLogger implements BuildLogger {

        private final Set<Thread> warningThreads = ConcurrentHashMap.newKeySet();
        private final Set<String> stageThreads = ConcurrentHashMap.newKeySet();

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
            warningThreads.add(Thread.currentThread());
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.getName().startsWith(RunnerJarBuilder.STAGE_THREAD_PREFIX) && thread.isDaemon()) {
                    stageThreads.add(thread.getName());
                }
            }
        }
    }
}
