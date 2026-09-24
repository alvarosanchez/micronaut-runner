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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepresentativeWorkloadTest {

    @Test
    void workloadCatalogVariesScaleAndDeclaresEveryRequiredArchiveFeature() {
        List<WorkloadShape> shapes = WorkloadShape.standard();

        assertEquals(List.of("no-manifest", "small", "representative", "wide"),
                shapes.stream().map(WorkloadShape::name).toList());
        assertTrue(shapes.stream().map(WorkloadShape::jarCount).distinct().count() > 1);
        assertTrue(shapes.stream().map(WorkloadShape::entriesPerJar).distinct().count() > 1);
        assertTrue(shapes.stream().map(WorkloadShape::classPayloadBytes).distinct().count() > 1);
        assertTrue(shapes.stream().allMatch(shape -> shape.streamResourceBytes() > 0));
        assertTrue(shapes.stream().allMatch(shape -> shape.serviceDescriptorsPerJar() > 0));
        assertTrue(shapes.stream().anyMatch(shape -> shape.manifestPackageSections() > 0));
        assertTrue(shapes.stream().anyMatch(shape -> shape.duplicateNames()));
        assertTrue(shapes.stream().anyMatch(shape -> shape.multiReleaseEntries()));
    }

    @Test
    void largerMicronautSampleHasRealDependencyBeanAndResourceWork() throws Exception {
        java.nio.file.Path sample = java.nio.file.Path.of(System.getProperty("runner.benchmark.largeSample"));
        String build = java.nio.file.Files.readString(sample.resolve("build.gradle"));
        String beans = java.nio.file.Files.readString(sample.resolve(
                "src/main/java/com/example/RepresentativeBeans.java"));
        long singletonCount = beans.lines().filter(line -> line.contains("@Singleton public static final class")).count();

        assertTrue(build.contains("micronaut-management"));
        assertTrue(build.contains("micronaut-http-client"));
        assertTrue(build.contains("representative-payload.bin"));
        assertEquals(48, singletonCount);
        assertTrue(java.nio.file.Files.isRegularFile(sample.resolve(
                "src/main/java/com/example/RepresentativeController.java")));
    }

    @Test
    void packagingProfileSeparatesModesEditScenariosAndMetrics(@org.junit.jupiter.api.io.TempDir java.nio.file.Path output)
            throws Exception {
        PackagingProfile.Report report = PackagingProfile.run(List.of("no-manifest"), 2, output);

        assertEquals(20, report.attempts().size());
        assertEquals(java.util.Set.of("stored", "preserve"),
                report.attempts().stream().map(PackagingProfile.Attempt::compression).collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.Set.of("first-build", "unchanged-rebuild", "application-edit", "dependency-edit",
                        "relocated-cache-restored"),
                report.attempts().stream().map(PackagingProfile.Attempt::scenario).collect(java.util.stream.Collectors.toSet()));
        assertTrue(report.attempts().stream().allMatch(attempt -> attempt.elapsedNanos() > 0));
        assertTrue(report.attempts().stream().allMatch(attempt -> attempt.inputBytes() > 0));
        assertTrue(report.attempts().stream().allMatch(attempt -> attempt.outputBytes() > 0));
        assertTrue(report.attempts().stream().allMatch(attempt -> attempt.peakHeapBytes() > 0));
        assertTrue(report.attempts().stream().filter(attempt -> attempt.scenario().equals("first-build"))
                .allMatch(attempt -> attempt.cacheHits() == 0 && attempt.cacheMisses() > 0));
        assertTrue(report.attempts().stream().filter(attempt -> attempt.scenario().equals("unchanged-rebuild"))
                .allMatch(attempt -> attempt.cacheHits() > 0 && attempt.cacheMisses() == 0));
        assertTrue(report.attempts().stream().filter(attempt -> attempt.scenario().equals("application-edit"))
                .allMatch(attempt -> attempt.cacheHits() > 0 && attempt.cacheMisses() == 0));
        assertTrue(report.attempts().stream().filter(attempt -> attempt.scenario().equals("dependency-edit"))
                .allMatch(attempt -> attempt.cacheHits() > 0 && attempt.cacheMisses() == 1));
        assertTrue(report.attempts().stream().filter(attempt -> attempt.scenario().equals("relocated-cache-restored"))
                .allMatch(attempt -> attempt.cacheHits() > 0 && attempt.cacheMisses() == 0));
        assertTrue(report.attempts().stream().filter(attempt -> attempt.cacheMisses() == 0)
                .allMatch(attempt -> attempt.dependencyStageBytesWritten() == 0));
        assertTrue(report.attempts().stream().filter(attempt -> attempt.scenario().equals("first-build")
                        || attempt.scenario().equals("dependency-edit"))
                .allMatch(attempt -> attempt.dependencyStageBytesWritten() > 0));
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            assertTrue(report.attempts().stream().allMatch(attempt -> attempt.physicalBlockInputs() >= 0
                    && attempt.physicalBlockOutputs() >= 0));
            assertTrue(report.attempts().stream()
                    .allMatch(attempt -> attempt.rssMethod().equals("time-rusage-maxrss")));
        }
        for (String compression : List.of("stored", "preserve")) {
            for (int iteration = 0; iteration < 2; iteration++) {
                int current = iteration;
                String firstHash = report.attempts().stream()
                        .filter(attempt -> attempt.compression().equals(compression)
                                && attempt.iteration() == current && attempt.scenario().equals("first-build"))
                        .findFirst().orElseThrow().outputSha256();
                assertTrue(report.attempts().stream()
                        .filter(attempt -> attempt.compression().equals(compression)
                                && attempt.iteration() == current
                                && (attempt.scenario().equals("unchanged-rebuild")
                                || attempt.scenario().equals("relocated-cache-restored")))
                        .allMatch(attempt -> attempt.outputSha256().equals(firstHash)),
                        compression + " cache reuse must keep final bytes reproducible");
                PackagingProfile.Attempt dependencyEdit = report.attempts().stream()
                        .filter(attempt -> attempt.compression().equals(compression)
                                && attempt.iteration() == current && attempt.scenario().equals("dependency-edit"))
                        .findFirst().orElseThrow();
                Path stages = output.resolve("work/no-manifest").resolve(compression)
                        .resolve("dependency-edit").resolve(Integer.toString(iteration))
                        .resolve("timing/dependency-stages");
                assertTrue(stageSizes(stages).contains(dependencyEdit.dependencyStageBytesWritten()),
                        "stage writes must report the replaced stage's full bytes, not net cache growth");
            }
        }
        assertTrue(java.nio.file.Files.isRegularFile(output.resolve("packaging-results.json")));
        assertTrue(java.nio.file.Files.isRegularFile(output.resolve("packaging-summary.md")));
    }

    @Test
    void resourceWorkloadSupportsWideLowEntryShape() throws Exception {
        SyntheticArchive archive = SyntheticArchive.forWorkload("wide");
        try (RepresentativeResourceWorkload workload = RepresentativeResourceWorkload.url(archive)) {
            assertEquals(16, workload.localLookupCount());
            assertEquals(16, workload.spreadLookupCount());
        }
    }

    @Test
    void resourceWorkloadExercisesSteadyStateOperations() throws Exception {
        SyntheticArchive archive = SyntheticArchive.forWorkload("small");
        try (RepresentativeResourceWorkload workload = RepresentativeResourceWorkload.url(archive)) {
            assertEquals(16, workload.localLookupCount());
            assertEquals(16, workload.spreadLookupCount());
            assertEquals(archive.shape().jarCount(), workload.serviceDiscoveryCount());
            assertEquals(archive.shape().jarCount(), workload.duplicateResourceCount());
            assertEquals(archive.shape().jarCount(), workload.serviceProviderCount());
            assertEquals("library-0", workload.duplicateResourceValue());
            assertEquals(archive.shape().streamResourceBytes(), workload.streamBytes());
            assertEquals("version-25", workload.multiReleaseValue());
        }
    }

    @Test
    void shadedJarFlattensTheSameInputsTheWayShadowDoes(@TempDir Path tempDir) throws Exception {
        SyntheticArchive archive = SyntheticArchive.forWorkload("small");
        WorkloadShape shape = archive.shape();
        Path shaded = archive.shadedJar();

        assertEquals(archive.storedRunnerJar().resolveSibling("shaded.jar"), shaded,
                "the flat JAR lives in the fixture root, where the shutdown hook deletes it");
        assertEquals(shaded, archive.shadedJar(), "the flat JAR is built once per fixture");
        assertArrayEquals(new URL[] {shaded.toUri().toURL()}, archive.shadedClassPathUrls());

        try (ZipFile zip = new ZipFile(shaded.toFile())) {
            List<ZipEntry> entries = Collections.list(zip.entries()).stream().map(ZipEntry.class::cast).toList();
            List<String> names = entries.stream().map(ZipEntry::getName).toList();

            assertEquals(1, names.stream().filter("META-INF/MANIFEST.MF"::equals).count());
            assertEquals("META-INF/MANIFEST.MF", names.getFirst());
            assertEquals(List.of(
                            "META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/"
                                    + "org.synthetic.app.$Main$Definition",
                            "application.properties",
                            "org/synthetic/app/Main.class"),
                    names.subList(1, 4), "application files come first, in sorted order");
            List<String> libraryOrder = names.stream()
                    .filter(name -> name.startsWith("org/synthetic/lib") && name.endsWith(".class"))
                    .map(name -> name.substring("org/synthetic/lib".length(), "org/synthetic/lib".length() + 3))
                    .distinct()
                    .toList();
            assertEquals(libraryOrder.stream().sorted().toList(), libraryOrder,
                    "libraries follow in class-path order");
            assertEquals(shape.jarCount(), libraryOrder.size());

            for (ZipEntry entry : entries) {
                assertEquals(ZipEntry.DEFLATED, entry.getMethod(), entry.getName());
                assertEquals(315532800000L, entry.getTime(), entry.getName());
            }
            int firstDirectory = names.indexOf(names.stream().filter(name -> name.endsWith("/")).findFirst()
                    .orElseThrow());
            assertTrue(names.subList(firstDirectory, names.size()).stream().allMatch(name -> name.endsWith("/")),
                    "directory entries follow every file");
            for (String name : names.subList(0, firstDirectory)) {
                for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
                    ZipEntry directory = zip.getEntry(name.substring(0, slash + 1));
                    assertNotNull(directory, "no directory entry for the parent of " + name);
                    assertTrue(directory.isDirectory(), directory.getName());
                }
            }

            List<String> classes = new ArrayList<>(archive.classNames());
            classes.add(SyntheticArchive.MAIN_CLASS);
            for (String className : classes) {
                assertNotNull(zip.getEntry(className.replace('.', '/') + ".class"), className);
            }
            for (Path library : archive.libraryJars()) {
                try (ZipFile source = new ZipFile(library.toFile())) {
                    for (ZipEntry entry : Collections.list(source.entries())) {
                        if (entry.getName().endsWith(".class")) {
                            assertArrayEquals(read(source, entry), read(zip, zip.getEntry(entry.getName())),
                                    "class bytes are copied unchanged: " + entry.getName());
                        }
                    }
                }
            }

            List<String> providers = new String(read(zip, zip.getEntry(SyntheticArchive.SERVICE_RESOURCE)),
                    StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).toList();
            assertEquals(shape.jarCount(), providers.size());
            assertEquals("org.synthetic.lib000.Provider00", providers.getFirst());
            assertEquals("org.synthetic.lib015.Provider00", providers.getLast());
        }

        try (JarFile jar = new JarFile(shaded.toFile())) {
            Manifest manifest = jar.getManifest();
            Attributes main = manifest.getMainAttributes();
            assertEquals(SyntheticArchive.MAIN_CLASS, main.getValue(Attributes.Name.MAIN_CLASS));
            assertEquals("true", main.getValue(Attributes.Name.MULTI_RELEASE));
            assertNull(main.getValue(Attributes.Name.IMPLEMENTATION_TITLE), "no dependency manifest survives");
            assertEquals(Map.of(), manifest.getEntries(), "no Name: section survives");

            assertEquals("library-0", text(jar, SyntheticArchive.DUPLICATE_RESOURCE));
            assertEquals("base", text(jar, SyntheticArchive.VERSIONED_RESOURCE));
            assertEquals("version-25", text(jar, "META-INF/versions/25/" + SyntheticArchive.VERSIONED_RESOURCE));
        }

        Path copy = tempDir.resolve("copy.jar");
        archive.writeShadedJar(copy);
        assertArrayEquals(Files.readAllBytes(shaded), Files.readAllBytes(copy), "the flat JAR is reproducible");

        try (RepresentativeResourceWorkload workload =
                     RepresentativeResourceWorkload.url(archive, archive.shadedClassPathUrls())) {
            assertEquals(16, workload.localLookupCount());
            assertEquals(16, workload.spreadLookupCount());
            assertEquals(0, workload.missingLookupCount());
            assertEquals(1, workload.serviceDiscoveryCount());
            assertEquals(1, workload.duplicateResourceCount());
            assertEquals(shape.jarCount(), workload.serviceProviderCount());
            assertEquals("library-0", workload.duplicateResourceValue());
            assertEquals(shape.streamResourceBytes(), workload.streamBytes());
            assertEquals("version-25", workload.multiReleaseValue());
        }
    }

    @Test
    void resourceEnumerationFixtureHasTheRequestedContributors() throws Exception {
        for (int contributors : List.of(1, 30, 300)) {
            SyntheticArchive archive = SyntheticArchive.forResourceEnumeration(contributors);
            try (RepresentativeResourceWorkload workload = RepresentativeResourceWorkload.url(archive)) {
                assertEquals(contributors, workload.duplicateResourceCount());
                assertEquals(contributors, workload.serviceDiscoveryCount());
            }
        }
    }

    @Test
    void syntheticArchiveExercisesLocalityResourcesServicesManifestsDuplicatesAndMultiRelease() throws Exception {
        WorkloadShape shape = WorkloadShape.named("small");
        SyntheticArchive archive = SyntheticArchive.forWorkload(shape.name());

        assertEquals(shape, archive.shape());
        assertEquals(shape.jarCount(), archive.libraryJars().size());
        assertEquals(shape.jarCount() * shape.entriesPerJar(), archive.classNames().size());
        assertTrue(List.of(archive.localSample(12)).stream().allMatch(name -> name.contains(".lib000.")));
        assertTrue(List.of(archive.spreadSample(12)).stream().map(RepresentativeWorkloadTest::libraryOf)
                .distinct().count() > 1);

        try (JarFile first = new JarFile(archive.libraryJars().getFirst().toFile())) {
            assertTrue(first.getEntry(archive.classNames().getFirst().replace('.', '/') + ".class").getSize()
                    >= shape.classPayloadBytes());
            assertTrue(first.getManifest().getEntries().size() >= shape.manifestPackageSections());
        }

        try (URLClassLoader loader = new URLClassLoader(archive.classPathUrls(),
                ClassLoader.getPlatformClassLoader())) {
            assertEquals(shape.jarCount(), Collections.list(loader.getResources(SyntheticArchive.SERVICE_RESOURCE)).size());
            assertEquals(shape.jarCount(), Collections.list(loader.getResources(SyntheticArchive.DUPLICATE_RESOURCE)).size());
            try (InputStream stream = loader.getResourceAsStream(SyntheticArchive.STREAM_RESOURCE)) {
                assertEquals(shape.streamResourceBytes(), stream.readAllBytes().length);
            }
            try (InputStream stream = loader.getResourceAsStream(SyntheticArchive.VERSIONED_RESOURCE)) {
                assertEquals("version-25", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private static List<Long> stageSizes(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".jar")).map(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        assertNotNull(entry);
        try (InputStream input = zip.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static String text(JarFile jar, String name) throws IOException {
        return new String(read(jar, jar.getEntry(name)), StandardCharsets.UTF_8);
    }

    private static String libraryOf(String className) {
        int start = className.indexOf(".lib") + 4;
        return className.substring(start, start + 3);
    }
}
