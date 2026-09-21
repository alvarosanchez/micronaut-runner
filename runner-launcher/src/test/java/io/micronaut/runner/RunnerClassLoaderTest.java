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
package io.micronaut.runner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the class loader against a real runner archive.
 *
 * <p>One archive is built for the whole class and registered with the jar URL handler once, because
 * registration is global to the JVM: a runner jar starts one application per process. Every test then
 * builds its own {@link RunnerClassLoader} over that archive, so that package definition, sealing and the
 * protection domain cache always start from a clean slate.</p>
 *
 * <p>The archive carries six jars: the application layer with the merged Micronaut service directory at
 * the outer root, a DEFLATE dependency, a multi-release dependency, and three dependencies whose only
 * purpose is to collide over a sealed package.</p>
 */
class RunnerClassLoaderTest {

    @TempDir
    static Path temporary;

    private static final String ALPHA = "MICRONAUT-INF/lib/alpha.jar";
    private static final String BETA = "MICRONAUT-INF/lib/beta.jar";
    private static final String SEALED = "MICRONAUT-INF/lib/sealed.jar";
    private static final String PLAIN = "MICRONAUT-INF/lib/plain.jar";
    private static final String SEALER = "MICRONAUT-INF/lib/sealer.jar";
    private static final String SERVICE = "META-INF/micronaut/io.micronaut.Svc/";
    private static final String APP_SERVICE = SERVICE + "org.example.App";
    private static final String ALPHA_SERVICE = SERVICE + "org.alpha.Alpha";
    private static final int GENERATED_CLASSES = 24;

    private static File archive;
    private static ArchiveSource source;
    private static Index index;

    @BeforeAll
    static void buildArchive() throws IOException {
        Fixture fixture = new Fixture();
        Fixture.Jar application = fixture.addJar(IndexFormat.CLASSES_PREFIX);
        application.addRoot("META-INF/micronaut/", new byte[0]);
        application.addRoot(SERVICE, new byte[0]);
        application.addRoot(APP_SERVICE, text("merged-app"));
        application.addRoot(ALPHA_SERVICE, text("merged-alpha"));
        application.add(APP_SERVICE, text("classes-copy"));
        application.add("org/example/App.class", classBytes("org.example.App", "app"));
        application.add("org/example/Two.class", classBytes("org.example.Two", "two"));
        application.add("org/example/Shared.class", classBytes("org.example.Shared", "jar0"));
        application.add("javax/net/ServerSocketFactory.class",
                classBytes("javax.net.ServerSocketFactory", "shadow"));
        application.add("javax/net/Shadow.class", classBytes("javax.net.Shadow", "shadow"));
        application.add("io/micronaut/runner/generated/AppEntry.class",
                classBytes("io.micronaut.runner.generated.AppEntry", "generated"));
        application.add("org/example/Corrupt.class",
                classBytes("org.example.Corrupt", "corrupt")).corruptCrc();
        application.add("shared.txt", text("jar0-shared"));
        application.add("org/example/only.txt", text("only-in-app"));
        for (int i = 0; i < GENERATED_CLASSES; i++) {
            application.add("org/example/gen/C" + i + ".class",
                    classBytes("org.example.gen.C" + i, "gen" + i));
        }

        Fixture.Jar alpha = fixture.addJar(ALPHA).deflate()
                .manifest("AlphaSpec", "1.0", "AlphaVendor", "AlphaImpl", "2.0", "AlphaImplVendor");
        alpha.addPackage("org.alpha.pkg", null, "9.9", null, "SectionImpl", null, null);
        alpha.add("org/alpha/Alpha.class", classBytes("org.alpha.Alpha", "alpha"));
        alpha.add("org/alpha/pkg/Attrs.class", classBytes("org.alpha.pkg.Attrs", "attrs"));
        alpha.add("org/example/Shared.class", classBytes("org.example.Shared", "jar1"));
        alpha.add("shared.txt", text("jar1-shared"));
        alpha.add(ALPHA_SERVICE, text("alpha-copy"));

        Fixture.Jar beta = fixture.addJar(BETA).multiRelease();
        beta.add("org/beta/Beta.class", classBytes("org.beta.Beta", "base"));
        beta.add("META-INF/versions/9/org/beta/Beta.class", classBytes("org.beta.Beta", "v9"));
        beta.add("META-INF/versions/99/org/beta/Future.class",
                classBytes("org.beta.Future", "future"));
        beta.add("shared.txt", text("jar2-shared"));
        beta.add("META-INF/versions/9/shared.txt", text("jar2-v9-shared"));

        fixture.addJar(SEALED).sealed()
                .add("org/sealed/First.class", classBytes("org.sealed.First", "sealed-first"));
        Fixture.Jar plain = fixture.addJar(PLAIN);
        plain.add("org/sealed/Second.class", classBytes("org.sealed.Second", "sealed-second"));
        plain.add("org/unsealed/First.class", classBytes("org.unsealed.First", "unsealed-first"));
        Fixture.Jar sealer = fixture.addJar(SEALER);
        sealer.addSealedPackage("org.unsealed");
        sealer.add("org/unsealed/Second.class", classBytes("org.unsealed.Second", "unsealed-second"));

        archive = fixture.writeTo(temporary.resolve("runner-classloader-test.jar").toFile());
        source = ArchiveSource.open(archive);
        index = Index.open(source);
        index.validateStringReferences();
        // Registration is global to the JVM and the first archive wins, so this test class takes the
        // handler for the duration of the class and hands it back in closeArchive().
        Handlers.unregister();
        Handlers.register(archive, index, source);
    }

    @AfterAll
    static void closeArchive() {
        Handlers.unregister();
        if (source != null) {
            source.close();
        }
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(RunnerClassLoader.VERIFY_PROPERTY);
        System.clearProperty(ArchiveSource.MMAP_PROPERTY);
        System.clearProperty("jdk.util.jar.enableMultiRelease");
    }

    @Test
    void loadsClassesFromTheApplicationLayerAndFromNestedJars() throws Exception {
        RunnerClassLoader loader = newLoader();

        Class<?> application = loader.loadClass("org.example.App");
        assertSame(loader, application.getClassLoader());
        assertEquals("app", id(application));

        Class<?> nested = loader.loadClass("org.alpha.Alpha");
        assertSame(loader, nested.getClassLoader());
        assertEquals("alpha", id(nested), "a DEFLATE entry of a nested jar must be inflated exactly");

        assertSame(application, loader.loadClass("org.example.App"));
    }

    @Test
    void loadsClassesWhenTheArchiveIsNotMemoryMapped() throws Exception {
        System.setProperty(ArchiveSource.MMAP_PROPERTY, "false");
        try (ArchiveSource fallback = ArchiveSource.open(archive)) {
            assertFalse(fallback.mapped());
            Index fallbackIndex = Index.open(fallback);
            RunnerClassLoader loader = new RunnerClassLoader(fallbackIndex, fallback,
                    ClassLoader.getPlatformClassLoader());

            assertEquals("app", id(loader.loadClass("org.example.App")),
                    "a STORED class must define from a read-only heap buffer too");
            assertEquals("alpha", id(loader.loadClass("org.alpha.Alpha")));
            assertEquals("jar0-shared", string(loader.getResourceAsStream("shared.txt")));
        }
    }

    @Test
    void theFirstJarOnTheClasspathWins() throws Exception {
        assertEquals("jar0", id(newLoader().loadClass("org.example.Shared")));
    }

    @Test
    void selectsTheVersionedEntryOfAMultiReleaseJar() throws Exception {
        assertEquals("v9", id(newLoader().loadClass("org.beta.Beta")));
    }

    @Test
    void ignoresVersionedEntriesWhenMultiReleaseIsDisabled() throws Exception {
        System.setProperty("jdk.util.jar.enableMultiRelease", "false");
        assertEquals("base", id(newLoader().loadClass("org.beta.Beta")));
    }

    @Test
    void ignoresVersionedEntriesNewerThanTheRuntime() {
        RunnerClassLoader loader = newLoader();
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass("org.beta.Future"));
    }

    @Test
    void delegatesTheLauncherOwnPackageButNotTheGeneratedOne() throws Exception {
        RunnerClassLoader loader = newLoader();

        assertSame(Entry.class, loader.loadClass("io.micronaut.runner.Entry"),
                "the launcher's types must be the same on both sides of the boundary");
        assertSame(Index.class, loader.loadClass("io.micronaut.runner.Index"));

        Class<?> generated = loader.loadClass("io.micronaut.runner.generated.AppEntry");
        assertSame(loader, generated.getClassLoader(), "generated classes belong to the application");
        assertEquals("generated", id(generated));
    }

    @Test
    void asksTheParentFirstOnlyForPackagesTheParentOwns() throws Exception {
        RunnerClassLoader loader = newLoader();

        assertTrue(loader.isParentVisible("java.lang.String"));
        assertTrue(loader.isParentVisible("javax.net.ServerSocketFactory"));
        assertFalse(loader.isParentVisible("org.example.App"));
        assertFalse(loader.isParentVisible("io.micronaut.runner.generated.AppEntry"));

        assertSame(String.class, loader.loadClass("java.lang.String"));
        assertSame(javax.net.ServerSocketFactory.class,
                loader.loadClass("javax.net.ServerSocketFactory"),
                "a class of a parent package must come from the parent, not from the archive");

        Class<?> shadow = loader.loadClass("javax.net.Shadow");
        assertSame(loader, shadow.getClassLoader(),
                "a parent package the parent does not have must fall back to the archive");
    }

    @Test
    void reportsAGenuineMiss() {
        RunnerClassLoader loader = newLoader();
        ClassNotFoundException failure = assertThrows(ClassNotFoundException.class,
                () -> loader.loadClass("org.example.Missing"));
        assertEquals("org.example.Missing", failure.getMessage());
    }

    @Test
    void definesPackagesFromTheManifestMainAttributes() throws Exception {
        Package defined = newLoader().loadClass("org.alpha.Alpha").getPackage();

        assertEquals("org.alpha", defined.getName());
        assertEquals("AlphaSpec", defined.getSpecificationTitle());
        assertEquals("1.0", defined.getSpecificationVersion());
        assertEquals("AlphaVendor", defined.getSpecificationVendor());
        assertEquals("AlphaImpl", defined.getImplementationTitle());
        assertEquals("2.0", defined.getImplementationVersion());
        assertEquals("AlphaImplVendor", defined.getImplementationVendor());
        assertFalse(defined.isSealed());
    }

    @Test
    void aPackageSectionOverridesOnlyTheAttributesItDeclares() throws Exception {
        Package defined = newLoader().loadClass("org.alpha.pkg.Attrs").getPackage();

        assertEquals("9.9", defined.getSpecificationVersion(), "declared by the section");
        assertEquals("SectionImpl", defined.getImplementationTitle(), "declared by the section");
        assertEquals("AlphaSpec", defined.getSpecificationTitle(), "inherited from the jar");
        assertEquals("AlphaVendor", defined.getSpecificationVendor(), "inherited from the jar");
        assertEquals("2.0", defined.getImplementationVersion(), "inherited from the jar");
        assertEquals("AlphaImplVendor", defined.getImplementationVendor(), "inherited from the jar");
    }

    @Test
    void rejectsAClassJoiningASealedPackageFromAnotherJar() throws Exception {
        assumeHandlersRegistered();
        RunnerClassLoader loader = newLoader();

        assertTrue(loader.loadClass("org.sealed.First").getPackage().isSealed());

        SecurityException failure = assertThrows(SecurityException.class,
                () -> loader.loadClass("org.sealed.Second"));
        assertEquals("sealing violation: package org.sealed is sealed", failure.getMessage());
    }

    @Test
    void rejectsSealingAPackageThatIsAlreadyLoaded() throws Exception {
        assumeHandlersRegistered();
        RunnerClassLoader loader = newLoader();

        assertFalse(loader.loadClass("org.unsealed.First").getPackage().isSealed());

        SecurityException failure = assertThrows(SecurityException.class,
                () -> loader.loadClass("org.unsealed.Second"));
        assertEquals("sealing violation: can't seal package org.unsealed: already loaded",
                failure.getMessage());
    }

    @Test
    void sharesOneProtectionDomainPerJar() throws Exception {
        assumeHandlersRegistered();
        RunnerClassLoader loader = newLoader();

        java.security.ProtectionDomain first = loader.loadClass("org.example.App").getProtectionDomain();
        java.security.ProtectionDomain second = loader.loadClass("org.example.Two").getProtectionDomain();
        java.security.ProtectionDomain other = loader.loadClass("org.alpha.Alpha").getProtectionDomain();

        assertSame(first, second, "one domain per jar, not one per class");
        assertNotSame(first, other);
        assertNotNull(first.getCodeSource());
        assertNotNull(first.getCodeSource().getLocation());
        assertNull(first.getCodeSource().getCodeSigners(), "runner jars are never verified at runtime");
        assertSame(loader, first.getClassLoader());
    }

    @Test
    void verifiesChecksumsOnlyWhenAskedTo() throws Exception {
        assertEquals("corrupt", id(newLoader().loadClass("org.example.Corrupt")),
                "the checksum must not be verified by default");

        System.setProperty(RunnerClassLoader.VERIFY_PROPERTY, "true");
        RunnerClassLoader verifying = newLoader();
        ClassNotFoundException failure = assertThrows(ClassNotFoundException.class,
                () -> verifying.loadClass("org.example.Corrupt"));
        assertNotNull(failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("org/example/Corrupt.class"),
                failure.getCause().getMessage());
        assertEquals("app", id(verifying.loadClass("org.example.App")),
                "an intact entry still loads with verification on");
    }

    @Test
    void findsOneUrlPerContributingJarInClasspathOrder() throws Exception {
        assumeHandlersRegistered();
        RunnerClassLoader loader = newLoader();

        List<URL> urls = list(loader.findResources("shared.txt"));

        assertEquals(3, urls.size(), "one URL per jar, never two for the same jar: " + urls);
        assertEquals(loader.findResource("shared.txt"), urls.get(0));
        assertTrue(urls.get(0).toString().endsWith("shared.txt"), urls.get(0).toString());
        assertTrue(urls.get(1).toString().contains("alpha.jar"), urls.get(1).toString());
        assertTrue(urls.get(2).toString().endsWith("META-INF/versions/9/shared.txt"),
                "a multi-release jar must report the versioned entry it selects: " + urls.get(2));
    }

    @Test
    void resolvesMicronautServiceMetadataAgainstTheOuterRootOnly() throws Exception {
        assumeHandlersRegistered();
        RunnerClassLoader loader = newLoader();

        // Two rules meet here. The count is this loader's: one URL for the merged directory, never one
        // per dependency. The shape is Handlers': the merged records live at the root of the outer
        // archive, so their URL names the root and not MICRONAUT-INF/classes/, because whoever receives
        // it is entitled to look the entry name up in the outer archive itself, which is exactly what
        // micronaut-core does.
        List<URL> directory = list(loader.getResources(IndexFormat.MICRONAUT_SERVICES_PREFIX));
        assertEquals(1, directory.size(), "the merged directory is one URL, not one per jar: " + directory);
        String url = directory.get(0).toString();
        assertTrue(url.endsWith(Handlers.SEPARATOR + IndexFormat.MICRONAUT_SERVICES_PREFIX), url);
        assertFalse(url.contains(IndexFormat.CLASSES_PREFIX),
                "the merged directory is addressed at the outer root: " + url);

        List<URL> alphaService = list(loader.findResources(ALPHA_SERVICE));
        assertEquals(1, alphaService.size(), "the copy inside alpha.jar must stay invisible: " + alphaService);
        assertEquals("merged-alpha", string(loader.getResourceAsStream(ALPHA_SERVICE)));
        assertEquals("merged-app", string(loader.getResourceAsStream(APP_SERVICE)),
                "the merged root copy wins over the application's own copy");
    }

    @Test
    void servesResourcesAsStreamsWithoutBuildingUrls() throws Exception {
        RunnerClassLoader loader = newLoader();

        assertEquals("jar0-shared", string(loader.getResourceAsStream("shared.txt")));
        assertEquals("jar0-shared", string(loader.getResourceAsStream("/shared.txt")),
                "a single leading slash is stripped");
        assertEquals("jar0-shared", string(loader.getResourceAsStream("org/example/../../shared.txt")),
                "a .. segment is resolved inside the archive");
        assertEquals("only-in-app", string(loader.getResourceAsStream("org/beta/../example/only.txt")));
        assertEquals("only-in-app", string(loader.getResourceAsStream("org/example/only.txt")));
        assertNull(loader.getResourceAsStream("../shared.txt"), "a name that escapes the root is not found");
        assertNull(loader.getResourceAsStream("org/example/absent.txt"));
    }

    @Test
    void findsResourcesByNormalisedName() {
        assumeHandlersRegistered();
        RunnerClassLoader loader = newLoader();

        URL direct = loader.findResource("shared.txt");
        assertNotNull(direct);
        assertEquals(direct, loader.findResource("/shared.txt"));
        assertEquals(direct, loader.findResource("org/example/../../shared.txt"));
        assertNull(loader.findResource("../shared.txt"));
        assertNull(loader.findResource("org/example/absent.txt"));
        assertEquals(direct, loader.getResource("shared.txt"));
        assertNotNull(loader.findResource("org/example/"), "a directory is a resource too");
    }

    @Test
    void normalisesResourceNames() {
        assertEquals("a/b", RunnerClassLoader.normalizeResourceName("a/b"));
        assertEquals("a/b", RunnerClassLoader.normalizeResourceName("/a/b"));
        assertEquals("b", RunnerClassLoader.normalizeResourceName("a/../b"));
        assertEquals("a/b", RunnerClassLoader.normalizeResourceName("a/./b"));
        assertEquals("a/b/", RunnerClassLoader.normalizeResourceName("a/c/../b/"));
        assertEquals("a.b/c.txt", RunnerClassLoader.normalizeResourceName("a.b/c.txt"));
        assertNull(RunnerClassLoader.normalizeResourceName("../a"));
        assertNull(RunnerClassLoader.normalizeResourceName("a/../../b"));
        assertNull(RunnerClassLoader.normalizeResourceName(null));
    }

    @Test
    void definesClassesFromSeveralThreadsAtOnce() throws Exception {
        RunnerClassLoader loader = newLoader();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<Class<?>>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(new Callable<List<Class<?>>>() {
                    @Override
                    public List<Class<?>> call() throws Exception {
                        List<Class<?>> loaded = new ArrayList<>();
                        for (int i = 0; i < GENERATED_CLASSES; i++) {
                            loaded.add(loader.loadClass("org.example.gen.C" + i));
                        }
                        loaded.add(loader.loadClass("org.alpha.Alpha"));
                        return loaded;
                    }
                }));
            }
            List<Class<?>> reference = futures.get(0).get(30, TimeUnit.SECONDS);
            for (int i = 0; i < GENERATED_CLASSES; i++) {
                assertEquals("gen" + i, id(reference.get(i)));
            }
            for (Future<List<Class<?>>> future : futures) {
                List<Class<?>> loaded = future.get(30, TimeUnit.SECONDS);
                assertEquals(reference.size(), loaded.size());
                for (int i = 0; i < reference.size(); i++) {
                    assertSame(reference.get(i), loaded.get(i), "every thread must see one class");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static RunnerClassLoader newLoader() {
        return new RunnerClassLoader(index, source, ClassLoader.getPlatformClassLoader());
    }

    private static void assumeHandlersRegistered() {
        URL url = Handlers.codeSourceUrlFor(IndexFormat.APPLICATION_JAR_ID);
        Assumptions.assumeTrue(url != null && url.toString().contains(archive.getName()),
                "another archive owns the jar URL handler in this JVM");
    }

    private static String id(Class<?> type) throws Exception {
        return (String) type.getMethod("id").invoke(null);
    }

    private static String string(InputStream stream) throws IOException {
        assertNotNull(stream);
        try (InputStream open = stream) {
            return new String(open.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<URL> list(Enumeration<URL> urls) {
        return Collections.list(urls);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds a class with a single {@code public static String id()} returning a constant, so that a test
     * can tell two copies of the same class name apart by what they answer.
     *
     * @param binaryName the class name
     * @param value      the value {@code id()} returns
     * @return the class file
     */
    private static byte[] classBytes(String binaryName, String value) {
        return ClassFile.of().build(ClassDesc.of(binaryName), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withMethodBody("id", MethodTypeDesc.of(ConstantDescs.CD_String),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        code -> code.loadConstant(value).areturn()));
    }

    /**
     * Assembles a runner archive: the index first, then the application layer exploded under
     * {@code MICRONAUT-INF/classes/}, then one nested jar per dependency.
     *
     * <p>The index records the absolute offset of every entry in the outer file, and the index is itself
     * an entry of that file, so it is written twice: once to learn its size, which does not depend on the
     * offsets it stores, and once with the real offsets into the space reserved for it.</p>
     */
    private static final class Fixture {

        private final List<Jar> jars = new ArrayList<>();

        Jar addJar(String name) {
            Jar jar = new Jar(name, jars.size());
            jars.add(jar);
            return jar;
        }

        File writeTo(File file) throws IOException {
            int size = index().length;
            TestArchiveBuilder builder = new TestArchiveBuilder();
            builder.reserve(IndexFormat.INDEX_ENTRY_NAME, size);
            Jar application = jars.get(IndexFormat.APPLICATION_JAR_ID);
            for (Item item : application.items) {
                String outer = item.root ? item.name : IndexFormat.CLASSES_PREFIX + item.name;
                item.offset = store(builder, outer, item, application.deflate);
                item.compressed = builder.storedSize(outer);
            }
            for (int i = 1; i < jars.size(); i++) {
                Jar jar = jars.get(i);
                TestArchiveBuilder nested = new TestArchiveBuilder();
                for (Item item : jar.items) {
                    item.offset = store(nested, item.name, item, jar.deflate);
                    item.compressed = nested.storedSize(item.name);
                }
                byte[] bytes = nested.build();
                jar.dataOffset = builder.stored(jar.name, bytes);
                jar.dataLength = bytes.length;
                jar.localHeaderOffset = builder.localHeaderOffset(jar.name);
                for (Item item : jar.items) {
                    item.offset += jar.dataOffset;
                }
            }
            byte[] bytes = index();
            assertEquals(size, bytes.length, "the index size must not depend on the offsets it stores");
            builder.replace(IndexFormat.INDEX_ENTRY_NAME, bytes);
            return builder.writeTo(file);
        }

        private static long store(TestArchiveBuilder builder, String name, Item item, boolean deflate) {
            return deflate ? builder.deflated(name, item.content) : builder.stored(name, item.content);
        }

        private byte[] index() {
            TestIndexBuilder builder = new TestIndexBuilder().startClass("org.example.App");
            for (Jar jar : jars) {
                TestIndexBuilder.Jar record = builder.addJar(jar.name).flags(jar.flags);
                if (jar.manifest != null) {
                    record.manifest(jar.manifest[0], jar.manifest[1], jar.manifest[2], jar.manifest[3],
                            jar.manifest[4], jar.manifest[5]);
                }
                if (jar.id != IndexFormat.APPLICATION_JAR_ID) {
                    record.location(jar.dataOffset, jar.dataLength, jar.localHeaderOffset);
                }
                for (Item item : jar.items) {
                    record.addEntry(item.name)
                            .data(item.offset, item.compressed, item.content.length)
                            .method(jar.deflate ? IndexFormat.METHOD_DEFLATED : IndexFormat.METHOD_STORED)
                            .crc32(item.crc());
                }
                for (PackageSection section : jar.packages) {
                    TestIndexBuilder.PackageSection built = record.addPackage(section.name)
                            .attributes(section.values[0], section.values[1], section.values[2],
                                    section.values[3], section.values[4], section.values[5]);
                    if (section.sealed) {
                        built.sealed(true);
                    }
                }
            }
            return builder.build();
        }

        /**
         * One jar of the archive under construction: the application layer when its id is zero, a nested
         * jar otherwise.
         */
        private static final class Jar {

            private final String name;
            private final int id;
            private final List<Item> items = new ArrayList<>();
            private final List<PackageSection> packages = new ArrayList<>();
            private String[] manifest;
            private boolean deflate;
            private int flags;
            private long dataOffset;
            private long dataLength;
            private long localHeaderOffset;

            private Jar(String name, int id) {
                this.name = name;
                this.id = id;
                this.flags = id == IndexFormat.APPLICATION_JAR_ID ? IndexFormat.JAR_FLAG_IS_OUTER : 0;
            }

            Jar deflate() {
                this.deflate = true;
                return this;
            }

            Jar multiRelease() {
                this.flags |= IndexFormat.JAR_FLAG_MULTI_RELEASE;
                return this;
            }

            Jar sealed() {
                this.flags |= IndexFormat.JAR_FLAG_SEALED_BY_DEFAULT;
                return this;
            }

            Jar manifest(String specTitle, String specVersion, String specVendor, String implTitle,
                         String implVersion, String implVendor) {
                this.manifest = new String[] {specTitle, specVersion, specVendor, implTitle, implVersion,
                        implVendor};
                return this;
            }

            Jar addPackage(String packageName, String specTitle, String specVersion, String specVendor,
                           String implTitle, String implVersion, String implVendor) {
                PackageSection section = new PackageSection(packageName);
                section.values = new String[] {specTitle, specVersion, specVendor, implTitle, implVersion,
                        implVendor};
                packages.add(section);
                return this;
            }

            Jar addSealedPackage(String packageName) {
                PackageSection section = new PackageSection(packageName);
                section.sealed = true;
                packages.add(section);
                return this;
            }

            Item add(String name, byte[] content) {
                Item item = new Item(name, content, false);
                items.add(item);
                return item;
            }

            Item addRoot(String name, byte[] content) {
                Item item = new Item(name, content, true);
                items.add(item);
                return item;
            }
        }

        /** One entry: a class, a resource, or a directory when its name ends with a slash. */
        private static final class Item {

            private final String name;
            private final byte[] content;
            private final boolean root;
            private long offset;
            private int compressed;
            private boolean corrupt;

            private Item(String name, byte[] content, boolean root) {
                this.name = name;
                this.content = content;
                this.root = root;
            }

            void corruptCrc() {
                this.corrupt = true;
            }

            long crc() {
                CRC32 checksum = new CRC32();
                checksum.update(content, 0, content.length);
                long value = checksum.getValue();
                return corrupt ? (value ^ 0xFFFFFFFFL) : value;
            }
        }

        /** One per-package manifest section of a jar. */
        private static final class PackageSection {

            private final String name;
            private String[] values = new String[6];
            private boolean sealed;

            private PackageSection(String name) {
                this.name = name;
            }
        }
    }
}
