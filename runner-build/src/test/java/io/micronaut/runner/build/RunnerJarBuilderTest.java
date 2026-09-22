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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static io.micronaut.runner.build.ZipReaderTest.deflatedWithTrailingByte;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end tests for {@link RunnerJarBuilder}: real class files compiled by the JDK, real dependency jars
 * written with {@code java.util.zip}, packaged and then read back through {@link RunnerJarReader}, which is
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

    @Test
    void canonicalManifestAttributesSortArbitraryMaps() {
        RunnerJarSpec spec = spec(output()).canonicalManifestAttributes(Map.of(
                "Echo-Attribute", "5",
                "Bravo-Attribute", "2",
                "Delta-Attribute", "4",
                "Alpha-Attribute", "1",
                "Charlie-Attribute", "3")).build();

        assertEquals(List.of(
                "Alpha-Attribute",
                "Bravo-Attribute",
                "Charlie-Attribute",
                "Delta-Attribute",
                "Echo-Attribute"), new ArrayList<>(spec.manifestAttributes().keySet()));
    }

    @Test
    void canonicalManifestAttributesRejectCaseInsensitiveCollisionsInAnyLocale() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Example-Attribute", "first");
        attributes.put("example-attribute", "second");
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> spec(output()).canonicalManifestAttributes(attributes));
            assertTrue(failure.getMessage().contains("Example-Attribute"), failure.getMessage());
            assertTrue(failure.getMessage().contains("example-attribute"), failure.getMessage());
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void canonicalManifestAttributesProduceIdenticalArchivesInSeparateJvms()
            throws IOException, InterruptedException {
        Path first = output();
        Path second = output();

        runManifestProbe(first, "forward");
        runManifestProbe(second, "reverse");

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second),
                "logical map equality, not source iteration order, is the canonical mode's input");
        assertEquals(List.of(
                "Alpha-Attribute",
                "Bravo-Attribute",
                "Charlie-Attribute",
                "Delta-Attribute",
                "Echo-Attribute"), manifestLines(first).stream()
                        .filter(name -> name.endsWith("-Attribute"))
                        .toList());
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

    @ParameterizedTest(name = "canonically rejects {0}")
    @ValueSource(strings = {
            "mAnIfEsT-vErSiOn",
            "mAiN-cLaSs",
            "mIcRoNaUt-RuNnEr-FoRmAt",
            "mIcRoNaUt-RuNnEr-vErSiOn",
            "mIcRoNaUt-RuNnEr-sTaRt-ClAsS",
            "mIcRoNaUt-RuNnEr-fUtUrE"
    })
    void canonicalManifestAttributesRejectReservedKeysWithoutReplacingOutput(String key) throws IOException {
        Path output = output();
        Files.createDirectories(output.getParent());
        byte[] previous = "the existing runner jar".getBytes(StandardCharsets.UTF_8);
        Files.write(output, previous);
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> RunnerJarBuilder.build(spec(output)
                            .canonicalManifestAttributes(Map.of(key, "missing.Override"))
                            .build(), BuildLogger.noOp()));
            assertTrue(failure.getMessage().contains(key), failure.getMessage());
        } finally {
            Locale.setDefault(original);
        }
        assertArrayEquals(previous, Files.readAllBytes(output),
                "invalid canonical configuration must leave the existing output alone");
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
        try (OutputStream out = Files.newOutputStream(dependency);
             ZipWriter writer = new ZipWriter(out, ZipWriter.DEFAULT_TIMESTAMP, false)) {
            writer.writeEntry("dup/same.txt", "first".getBytes(StandardCharsets.UTF_8));
            writer.writeEntry("dup/same.txt", "second".getBytes(StandardCharsets.UTF_8));
        }

        for (Compression compression : new Compression[] {Compression.STORED, Compression.PRESERVE}) {
            Path output = output();
            RunnerJarBuilder.build(spec(output)
                    .dependencies(List.of(new Dependency(dependency, null)))
                    .compression(compression)
                    .build(), BuildLogger.noOp());

            try (RunnerJarReader reader = RunnerJarReader.open(output)) {
                Index index = reader.index();
                List<String> names = logicalNames(index, 1);
                assertEquals(2, names.stream().filter("dup/same.txt"::equals).count(),
                        compression + ": both records survive");
                List<String> physicalContents = new ArrayList<>();
                int firstRecord = index.jarFirstEntry(1);
                int limit = firstRecord + index.jarEntryCount(1);
                for (int physical = firstRecord; physical < limit; physical++) {
                    if (index.entryPhysical(physical) && "dup/same.txt".equals(index.entryName(physical))) {
                        physicalContents.add(new String(reader.read(physical), StandardCharsets.UTF_8));
                    }
                }
                assertEquals(List.of("first", "second"), physicalContents,
                        compression + ": physical enumeration keeps central-directory order and content");

                List<String> lookupContents = new ArrayList<>();
                int record = index.find("dup/same.txt");
                while (record != IndexFormat.NO_INDEX) {
                    lookupContents.add(new String(reader.read(record), StandardCharsets.UTF_8));
                    record = index.next(record);
                }
                assertEquals(List.of("second", "first"), lookupContents,
                        compression + ": lookup chain starts with the JDK-compatible last duplicate");
            }
        }
    }

    @Test
    void namesTheDependencyThatCannotBePackaged() throws IOException {
        Path dependency = corruptedDependency("libs/corrupt-stored.jar");

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output())
                .dependencies(List.of(new Dependency(dependency, null)))
                .build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains(dependency.toString()),
                "a build with dozens of dependencies has to say which one: " + failure.getMessage());
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
                    .dependencies(List.of(new Dependency(dependency, null)))
                    .compression(Compression.PRESERVE)
                    .build(), BuildLogger.noOp()));
        } finally {
            System.clearProperty(RunnerJarBuilder.VERIFY_ALL_PROPERTY);
        }

        assertArrayEquals(previous, Files.readAllBytes(output),
                "a failed build must not replace what was at the output path");
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

    @Test
    void rejectsUnusedCompressedBytesInADependencyWithoutReplacingOutput() throws IOException {
        Path dependency = deflatedWithTrailingByte(fixtures.resolve("libs/dependency-trailing-byte.jar"),
                "data.txt", "ABCDEF".getBytes(StandardCharsets.UTF_8));
        Path output = output();
        Files.createDirectories(output.getParent());
        byte[] previous = "previous dependency artifact".getBytes(StandardCharsets.UTF_8);
        Files.write(output, previous);

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output)
                .dependencies(List.of(new Dependency(dependency, null)))
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
                .dependencies(List.of(new Dependency(dependency, null)))
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
                .dependencies(List.of(new Dependency(dependency, null)))
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
                .dependencies(List.of(new Dependency(dependency, null)))
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
    void rejectsSymbolicLinksInsideApplicationDirectories() throws IOException {
        Path application = fixtures.resolve("application-with-link");
        Path mainClass = application.resolve("com/example/Application.class");
        Files.createDirectories(mainClass.getParent());
        Files.copy(applicationClasses.resolve("com/example/Application.class"), mainClass);
        createSymbolicLink(application.resolve("linked-resources"), applicationResources);
        Path output = output();
        Files.createDirectories(output.getParent());
        byte[] previous = "the existing good output".getBytes(StandardCharsets.UTF_8);
        Files.write(output, previous);

        IOException failure = assertThrows(IOException.class, () -> RunnerJarBuilder.build(spec(output)
                .applicationOutput(List.of(application))
                .dependencies(List.of())
                .build(), BuildLogger.noOp()));

        assertTrue(failure.getMessage().contains("symbolic link"), failure.getMessage());
        assertArrayEquals(previous, Files.readAllBytes(output),
                "a rejected application tree must leave an existing good output intact");
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
                .dependencies(List.of(new Dependency(first, null), new Dependency(second, null)))
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
                .dependencies(List.of(new Dependency(dependency, null)))
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
        try (RunnerJarReader reader = RunnerJarReader.open(output)) {
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
                            new Dependency(emptyDependency, null),
                            new Dependency(secondDependency, null)))
                    .compression(compression)
                    .build(), BuildLogger.noOp());

            assertMergedContent(output, name, empty);
            assertEquals(1, warningsFor(result, name).size(),
                    compression + ": different later content is reported even when the first value is empty");

            Path reversed = output();
            RunnerJarResult reversedResult = RunnerJarBuilder.build(spec(reversed)
                    .applicationOutput(List.of(applicationClasses))
                    .dependencies(List.of(
                            new Dependency(secondDependency, null),
                            new Dependency(emptyDependency, null)))
                    .compression(compression)
                    .build(), BuildLogger.noOp());

            assertMergedContent(reversed, name, second);
            assertEquals(1, warningsFor(reversedResult, name).size(),
                    compression + ": reversing the contributors reverses the selected content");

            Path equal = output();
            RunnerJarResult equalResult = RunnerJarBuilder.build(spec(equal)
                    .applicationOutput(List.of(applicationClasses))
                    .dependencies(List.of(
                            new Dependency(emptyDependency, null),
                            new Dependency(emptyDependency, null)))
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
                    .dependencies(List.of(new Dependency(dependency, null)))
                    .compression(compression)
                    .build(), BuildLogger.noOp());
            assertMergedContent(different, name, empty);
            assertEquals(1, warningsFor(differentResult, name).size(),
                    compression + ": an empty application value reserves the name");

            Path equal = output();
            RunnerJarResult equalResult = RunnerJarBuilder.build(spec(equal)
                    .applicationOutput(List.of(applicationClasses, nonEmptyApplication))
                    .dependencies(List.of(new Dependency(dependency, null)))
                    .compression(compression)
                    .build(), BuildLogger.noOp());
            assertMergedContent(equal, name, second);
            assertTrue(warningsFor(equalResult, name).isEmpty(),
                    compression + ": equal application and dependency values are not a conflict");
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
                        .dependencies(List.of(new Dependency(dependency, null)))
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
                .dependencies(List.of(new Dependency(dependency, null)))
                .build(), BuildLogger.noOp());

        try (RunnerJarReader reader = RunnerJarReader.open(output)) {
            Index index = reader.index();
            assertEquals("", index.jarImplTitle(1), "present but empty is not absent");
            assertEquals("", index.jarSpecVersion(1));
            assertEquals("3.0", index.jarImplVersion(1));
            assertNull(index.jarSpecTitle(1), "an attribute that really is absent stays null");
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
                .dependencies(List.of(new Dependency(dependency, null)))
                .build(), BuildLogger.noOp());

        try (RunnerJarReader reader = RunnerJarReader.open(output)) {
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

    private static String resolved(RunnerJarReader reader, Index index, String name) throws IOException {
        int record = index.resolve(index.find(name), Index.effectiveMultiReleaseVersion());
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
        try (RunnerJarReader reader = RunnerJarReader.open(output)) {
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
                    .dependencies(List.of(new Dependency(dependency, null)))
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

    private static void runManifestProbe(Path output, String order)
            throws IOException, InterruptedException {
        String classpath = System.getProperty("runner.test.runtimeClasspath");
        assertNotNull(classpath, "the test task supplies the forked JVM class path");
        Process process = new ProcessBuilder(
                javaExecutable().toString(),
                "-cp", classpath,
                ManifestReproducibilityProbe.class.getName(),
                output.toString(),
                applicationClasses.toString(),
                applicationResources.toString(),
                plainDependency.toString(),
                multiReleaseDependency.toString(),
                signedDependency.toString(),
                order)
                .redirectErrorStream(true)
                .start();
        String processOutput;
        try (InputStream in = process.getInputStream()) {
            processOutput = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(0, process.waitFor(), processOutput);
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
        int first = index.jarFirstEntry(jarId);
        for (int i = first; i < first + index.jarEntryCount(jarId); i++) {
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
                        new Dependency(plainDependency, "com.example:dep-lib:2.0.1"),
                        new Dependency(multiReleaseDependency, "com.example:mr-lib:1.0"),
                        new Dependency(signedDependency, null)))
                .output(output);
    }

    private Path output() {
        counter++;
        return fixtures.resolve("out/runner-" + counter + ".jar");
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

        try (RunnerJarReader reader = RunnerJarReader.open(output)) {
            Index index = reader.index();
            assertEquals(4, index.jarCount());
            assertEquals(result.entryCount(), index.entryCount());
            assertEquals("com.example.Application", index.startClass());
            assertTrue(index.nestedStored());
            assertFalse(index.applicationMultiRelease());
            assertEquals(IndexFormat.CLASSES_PREFIX, index.jarName(0));
            assertEquals("MICRONAUT-INF/lib/dep-lib.jar", index.jarName(1));
            assertEquals("com.example:dep-lib:2.0.1", index.jarCoordinates(1));
            assertNull(index.jarCoordinates(3), "a dependency without coordinates records none");
            assertEquals(Files.size(output), index.jarDataLength(0), "jar 0 is the outer archive itself");

            // Logical names: the application layer drops the prefix, a nested jar keeps its own names.
            int application = index.find("com/example/Application.class");
            assertNotEquals(IndexFormat.NO_INDEX, application);
            assertEquals(0, index.entryJarId(application));
            assertArrayEquals(
                    Files.readAllBytes(applicationClasses.resolve("com/example/Application.class")),
                    reader.read(application));

            int dependency = index.find("com/example/dep/Dep.class");
            assertEquals(1, index.entryJarId(dependency));
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

        try (RunnerJarReader reader = RunnerJarReader.open(output)) {
            Index index = reader.index();
            int merged = index.find(SERVICE_DIRECTORY + "com.example.dep.DepBean");
            assertNotEquals(IndexFormat.NO_INDEX, merged);
            assertEquals(0, index.entryJarId(merged), "the merged copy belongs to the outer archive");
            int directory = index.find(IndexFormat.MICRONAUT_SERVICES_PREFIX);
            assertTrue(index.entryDirectory(directory));
            assertFalse(index.entrySyntheticDirectory(directory), "it is stored, not synthesised");

            // The application's own copy is still in its layer, behind the merged one in the chain.
            int chain = index.find(SERVICE_DIRECTORY + "com.example.$Application$Definition");
            assertNotEquals(IndexFormat.NO_INDEX, index.entryNextSameName(chain));
        }
    }

    @Test
    void aliasesVersionedEntriesOfAMultiReleaseDependency() throws IOException {
        Path output = output();
        RunnerJarBuilder.build(spec(output).build(), BuildLogger.noOp());

        try (RunnerJarReader reader = RunnerJarReader.open(output)) {
            Index index = reader.index();
            assertTrue(index.jarMultiRelease(2));
            assertFalse(index.jarMultiRelease(1));

            int feature = index.find("com/example/mr/Feature.class");
            assertEquals(17, index.entryMrVersion(feature));
            assertTrue(index.entryVersionedAlias(feature));
            assertEquals("seventeen", new String(reader.read(feature), StandardCharsets.UTF_8));
            int base = index.entryNextSameName(feature);
            assertEquals(0, index.entryMrVersion(base));
            assertEquals("base", new String(reader.read(base), StandardCharsets.UTF_8));
            assertEquals(base, index.resolve(feature, 11), "an older runtime sees the base entry");
            assertEquals(feature, index.resolve(feature, 21));

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
        try (RunnerJarReader reader = RunnerJarReader.open(output)) {
            Index index = reader.index();
            for (String name : new String[] {"com/", "com/example/", "static/", SERVICE_DIRECTORY}) {
                int record = index.find(name);
                assertNotEquals(IndexFormat.NO_INDEX, record, name + " should resolve");
                assertTrue(index.entryDirectory(record), name + " should be a directory");
            }
            assertTrue(index.entrySyntheticDirectory(index.find("com/example/")));
            assertFalse(index.entryPhysical(index.find("com/example/")));
        }
    }

    @Test
    void dropsTheSignatureFilesOfASignedDependency() throws IOException {
        Path output = output();
        RunnerJarResult result = RunnerJarBuilder.build(spec(output).build(), BuildLogger.noOp());

        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("signed-lib.jar")),
                "the build warns that a signature was dropped: " + result.warnings());
        try (RunnerJarReader reader = RunnerJarReader.open(output)) {
            Index index = reader.index();
            assertEquals("MICRONAUT-INF/lib/signed-lib.jar", index.jarName(3));
            assertTrue((index.jarFlags(3) & IndexFormat.JAR_FLAG_SIGNED_ORIGINAL) != 0);
            List<String> names = logicalNames(index, 3);
            assertTrue(names.contains("com/example/signed/Signed.class"));
            assertFalse(names.contains("META-INF/MY.SF"));
            assertFalse(names.contains("META-INF/MY.RSA"));
            assertFalse(names.contains("META-INF/INDEX.LIST"));
            assertTrue(names.contains("META-INF/MANIFEST.MF"), "the manifest is kept verbatim");
            assertTrue((index.jarFlags(3) & IndexFormat.JAR_FLAG_HAS_MANIFEST) != 0);
        }
    }

    @Test
    void readsThePerPackageSectionsOfADependencyManifest() throws IOException {
        Path output = output();
        RunnerJarBuilder.build(spec(output).build(), BuildLogger.noOp());

        try (RunnerJarReader reader = RunnerJarReader.open(output)) {
            Index index = reader.index();
            assertEquals("dep-lib", index.jarImplTitle(1));
            assertEquals("2.0.1", index.jarImplVersion(1));
            assertFalse(index.jarSealedByDefault(1));

            int record = index.findPackage(1, "com.example.api");
            assertNotEquals(IndexFormat.NO_INDEX, record);
            assertEquals("Dep API", index.packageSpecTitle(record));
            assertEquals("2.0.1", index.packageImplVersion(record));
            assertTrue(index.packageSealedSpecified(record));
            assertTrue(index.packageSealedValue(record));
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
        try (RunnerJarReader reader = RunnerJarReader.open(output)) {
            Index index = reader.index();
            assertEquals("demo", index.jarImplTitle(0));
            assertTrue(index.jarSealedByDefault(0));
            assertEquals("Example", index.packageSpecVendor(index.findPackage(0, "com.example")));
        }
    }

    @Test
    void theIndexAgreesWithTheArchiveItDescribes() throws IOException {
        Path output = output();
        RunnerJarBuilder.build(spec(output).build(), BuildLogger.noOp());

        try (RunnerJarReader reader = RunnerJarReader.open(output);
             ZipReader archive = ZipReader.open(output)) {
            Index index = reader.index();
            assertEquals(Files.size(output), index.outerFileLength());

            // Every physical record of jar 0 has to name a real entry of the outer archive, at the very
            // offset the index sends the launcher to. The offset identifies the entry: an archive cannot
            // hold two entries whose data starts in the same place.
            Map<Long, ZipEntryInfo> byOffset = new LinkedHashMap<>();
            for (ZipEntryInfo entry : archive.entries()) {
                byOffset.put(entry.dataOffset(), entry);
            }
            int first = index.jarFirstEntry(0);
            for (int record = first; record < first + index.jarEntryCount(0); record++) {
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
                assertEquals(entry.localHeaderOffset(), index.jarLocalHeaderOffset(jarId));
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
        try (RunnerJarReader reader = RunnerJarReader.open(output)) {
            Index index = reader.index();
            assertFalse(index.nestedStored());
            assertEquals(original.length, index.jarDataLength(1));
            byte[] nested = reader.source().readFully(index.jarDataOffset(1), original.length);
            assertArrayEquals(original, nested, "PRESERVE copies a dependency byte for byte");

            int deflated = index.find("META-INF/services/com.example.Service");
            assertEquals(IndexFormat.METHOD_DEFLATED, index.entryMethod(deflated),
                    "the entry keeps the compression it had");
            assertEquals("com.example.dep.Dep\n", new String(reader.read(deflated), StandardCharsets.UTF_8));
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
        try (RunnerJarReader reader = RunnerJarReader.open(output)) {
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
}
