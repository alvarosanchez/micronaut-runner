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
import java.io.RandomAccessFile;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    private static final String ENUMERATION_ONE = "enumeration/one.txt";
    private static final String ENUMERATION_THIRTY = "enumeration/thirty.txt";
    private static final String ENUMERATION_MANY = "enumeration/three-hundred.txt";
    private static final String ENUMERATION_ADVERSARIAL = "enumeration/adversarial.txt";
    private static final String ENUMERATION_COLLISION = "enumeration/collision";
    private static final int ENUMERATION_JARS = 300;
    private static final int GENERATED_CLASSES = 24;
    private static final String INTERRUPT_STORED = "MICRONAUT-INF/lib/interrupt-stored.jar";
    private static final String INTERRUPT_DEFLATED = "MICRONAUT-INF/lib/interrupt-deflated.jar";
    private static final String INTERRUPT_STORED_PACKAGE = "org.interrupt.stored";
    private static final String INTERRUPT_DEFLATED_PACKAGE = "org.interrupt.deflated";
    private static final int INTERRUPT_CLASSES_PER_JAR = 256;
    private static final int INTERRUPT_THREADS = 8;
    /** Threads that define classes at once in the pool test: twice the pool's buffers. */
    private static final int POOL_THREADS = 32;
    private static final String REENTRANT_PACKAGE = "org.reentrant";
    private static final long INTERRUPT_SPIN_NANOS = 20_000;
    private static final String AWKWARD = "MICRONAUT-INF/lib/a b-é.jar";
    private static final String AWKWARD_CLASS = "org.awkward.Awkward";

    private static File archive;
    private static File alphaClasspathJar;
    private static File betaClasspathJar;
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
        application.add("io/micronaut/runner/app/Sneaky.class",
                classBytes("io.micronaut.runner.app.Sneaky", "sneaky"));
        application.add("org/example/Corrupt.class",
                classBytes("org.example.Corrupt", "corrupt")).corruptCrc();
        application.add("shared.txt", text("jar0-shared"));
        application.add("org/example/only.txt", text("only-in-app"));
        application.add("corrupt-stored.txt", text("corrupt stored resource")).corruptCrc();
        for (int i = 0; i < GENERATED_CLASSES; i++) {
            application.add("org/example/gen/C" + i + ".class",
                    classBytes("org.example.gen.C" + i, "gen" + i));
        }
        addReentrantFixture(application);

        Fixture.Jar alpha = fixture.addJar(ALPHA).deflate()
                .manifest("AlphaSpec", "1.0", "AlphaVendor", "AlphaImpl", "2.0", "AlphaImplVendor");
        alpha.addPackage("org.alpha.pkg", null, "9.9", null, "SectionImpl", null, null);
        alpha.add("org/alpha/Alpha.class", classBytes("org.alpha.Alpha", "alpha"));
        alpha.add("org/alpha/pkg/Attrs.class", classBytes("org.alpha.pkg.Attrs", "attrs"));
        for (int i = 0; i < 8; i++) {
            alpha.add("org/alpha/pkg/Concurrent" + i + ".class",
                    classBytes("org.alpha.pkg.Concurrent" + i, "concurrent" + i));
        }
        alpha.add("org/example/Shared.class", classBytes("org.example.Shared", "jar1"));
        alpha.add("shared.txt", text("jar1-shared"));
        alpha.add("duplicate.txt", text("alpha-first"));
        alpha.add("duplicate.txt", text("alpha-last!"));
        alpha.add("duplicate-dir/", new byte[0]);
        alpha.add("duplicate-dir/", new byte[0]);
        alpha.add("slashless-collision/", new byte[0]);
        alpha.add("reverse-collision", text("alpha-file"));
        alpha.add("same-jar-collision", text("alpha-exact"));
        alpha.add("same-jar-collision/", new byte[0]);
        alpha.add("only-directory/", new byte[0]);
        alpha.add("mr-slashless-collision/", new byte[0]);
        alpha.add("META-INF/micronaut", text("dependency-file"));
        alpha.add("corrupt-deflated.txt", text("corrupt deflated resource")).corruptCrc();
        alpha.add(ALPHA_SERVICE, text("alpha-copy"));

        Fixture.Jar beta = fixture.addJar(BETA).multiRelease();
        beta.add("org/beta/Beta.class", classBytes("org.beta.Beta", "base"));
        beta.add("META-INF/versions/9/org/beta/Beta.class", classBytes("org.beta.Beta", "v9"));
        beta.add("org/beta/Duplicate.class", classBytes("org.beta.Duplicate", "base1"));
        beta.add("org/beta/Duplicate.class", classBytes("org.beta.Duplicate", "base2"));
        beta.add("META-INF/versions/9/org/beta/Duplicate.class",
                classBytes("org.beta.Duplicate", "version1"));
        beta.add("META-INF/versions/9/org/beta/Duplicate.class",
                classBytes("org.beta.Duplicate", "version2"));
        beta.add("META-INF/versions/99/org/beta/Future.class",
                classBytes("org.beta.Future", "future"));
        beta.add("shared.txt", text("jar2-shared"));
        beta.add("META-INF/versions/9/shared.txt", text("jar2-v9-shared"));
        beta.add("duplicate.txt", text("beta-base1"));
        beta.add("duplicate.txt", text("beta-base2"));
        beta.add("META-INF/versions/9/duplicate.txt", text("beta-version1"));
        beta.add("META-INF/versions/9/duplicate.txt", text("beta-version2"));
        beta.add("slashless-collision", text("beta-file"));
        beta.add("reverse-collision/", new byte[0]);
        beta.add("only-file", text("beta-only"));
        beta.add("mr-slashless-collision", text("beta-base"));
        beta.add("META-INF/versions/9/mr-slashless-collision", text("beta-version"));

        fixture.addJar(SEALED).sealed()
                .add("org/sealed/First.class", classBytes("org.sealed.First", "sealed-first"));
        Fixture.Jar plain = fixture.addJar(PLAIN);
        plain.add("org/sealed/Second.class", classBytes("org.sealed.Second", "sealed-second"));
        plain.add("org/unsealed/First.class", classBytes("org.unsealed.First", "unsealed-first"));
        Fixture.Jar sealer = fixture.addJar(SEALER);
        sealer.addSealedPackage("org.unsealed");
        sealer.add("org/unsealed/Second.class", classBytes("org.unsealed.Second", "unsealed-second"));

        addEnumerationFixtures(fixture);
        // Appended last so that every jar above keeps its id.
        addInterruptFixtures(fixture);
        // After the interrupt fixtures, for the same reason.
        fixture.addJar(AWKWARD).add(AWKWARD_CLASS.replace('.', '/') + ".class",
                classBytes(AWKWARD_CLASS, "awkward"));

        archive = fixture.writeTo(temporary.resolve("runner-classloader-test.jar").toFile());
        TestArchiveBuilder alphaClasspath = new TestArchiveBuilder();
        alphaClasspath.stored("slashless-collision/", new byte[0]);
        alphaClasspath.stored("reverse-collision", text("alpha-file"));
        alphaClasspath.stored("same-jar-collision", text("alpha-exact"));
        alphaClasspath.stored("same-jar-collision/", new byte[0]);
        alphaClasspath.stored("only-directory/", new byte[0]);
        alphaClasspathJar = alphaClasspath.writeTo(temporary.resolve("classpath-alpha.jar").toFile());
        TestArchiveBuilder betaClasspath = new TestArchiveBuilder();
        betaClasspath.stored("slashless-collision", text("beta-file"));
        betaClasspath.stored("reverse-collision/", new byte[0]);
        betaClasspath.stored("only-file", text("beta-only"));
        betaClasspathJar = betaClasspath.writeTo(temporary.resolve("classpath-beta.jar").toFile());
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
                    "a STORED class must define from a heap buffer too");
            assertEquals("alpha", id(loader.loadClass("org.alpha.Alpha")));
            assertEquals("jar0-shared", string(loader.getResourceAsStream("shared.txt")));
        }
    }

    @ParameterizedTest(name = "mmap={0}")
    @ValueSource(strings = {"true", "index", "false"})
    void loadsClassesOnAnInterruptedThread(String mmap) throws Exception {
        // Future.cancel(true), ExecutorService.shutdownNow() or Thread.interrupt() leave a thread's interrupt
        // status set, and that thread may then load a class for the first time. A positional read used to
        // close the archive for every thread at that point.
        System.setProperty(ArchiveSource.MMAP_PROPERTY, mmap);
        try (ArchiveSource modeSource = ArchiveSource.open(archive)) {
            assertEquals("true".equals(mmap), modeSource.mapped());
            assertEquals("index".equals(mmap), modeSource.indexOnly());
            RunnerClassLoader loader = new RunnerClassLoader(Index.open(modeSource), modeSource,
                    ClassLoader.getPlatformClassLoader());

            Thread.currentThread().interrupt();
            try {
                assertEquals("alpha", id(loader.loadClass("org.alpha.Alpha")),
                        "the first class of a DEFLATE jar, which also checks that jar's local header");
                assertEquals("app", id(loader.loadClass("org.example.App")), "a STORED class");
                assertEquals("jar0-shared", string(loader.getResourceAsStream("shared.txt")));
                assertTrue(Thread.currentThread().isInterrupted(), "the interrupt status must still be set");
            } finally {
                Thread.interrupted();
            }
        }
    }

    @ParameterizedTest(name = "mmap={0}")
    @ValueSource(strings = {"true", "index", "false"})
    void definesClassesWhileThreadsAreInterrupted(String mmap) throws Exception {
        List<String> names = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < INTERRUPT_CLASSES_PER_JAR; i++) {
            names.add(INTERRUPT_STORED_PACKAGE + ".C" + i);
            ids.add("stored-" + i);
            names.add(INTERRUPT_DEFLATED_PACKAGE + ".C" + i);
            ids.add("deflated-" + i);
        }
        System.setProperty(ArchiveSource.MMAP_PROPERTY, mmap);
        try (ArchiveSource modeSource = ArchiveSource.open(archive)) {
            assertEquals("true".equals(mmap), modeSource.mapped());
            assertEquals("index".equals(mmap), modeSource.indexOnly());
            RunnerClassLoader loader = new RunnerClassLoader(Index.open(modeSource), modeSource,
                    ClassLoader.getPlatformClassLoader());
            Class<?>[][] loaded = new Class<?>[INTERRUPT_THREADS][names.size()];
            AtomicInteger failures = new AtomicInteger();
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();
            AtomicInteger interrupts = new AtomicInteger();
            AtomicBoolean workersDone = new AtomicBoolean();
            Thread[] workers = new Thread[INTERRUPT_THREADS];
            for (int t = 0; t < workers.length; t++) {
                int worker = t;
                workers[t] = new Thread(() -> {
                    // Each thread starts at a different name, so that the threads define different classes.
                    int start = worker * names.size() / INTERRUPT_THREADS;
                    for (int i = 0; i < names.size(); i++) {
                        int at = (start + i) % names.size();
                        try {
                            loaded[worker][at] = loader.loadClass(names.get(at));
                        } catch (ClassNotFoundException | RuntimeException | LinkageError e) {
                            failures.incrementAndGet();
                            firstFailure.compareAndSet(null, e);
                        }
                    }
                }, "interrupted-class-loader-" + t);
                workers[t].setDaemon(true);
            }
            Thread interrupter = new Thread(() -> {
                SplittableRandom random = new SplittableRandom(149);
                while (!workersDone.get()) {
                    workers[random.nextInt(workers.length)].interrupt();
                    interrupts.incrementAndGet();
                    long until = System.nanoTime() + INTERRUPT_SPIN_NANOS;
                    while (System.nanoTime() < until) {
                        Thread.onSpinWait();
                    }
                }
            }, "class-loader-interrupter");
            interrupter.setDaemon(true);
            try {
                for (Thread worker : workers) {
                    worker.start();
                }
                interrupter.start();
            } finally {
                for (Thread worker : workers) {
                    worker.join(TimeUnit.SECONDS.toMillis(60));
                }
                workersDone.set(true);
                interrupter.join(TimeUnit.SECONDS.toMillis(30));
            }
            for (Thread worker : workers) {
                assertFalse(worker.isAlive(), worker.getName() + " must have finished");
            }
            assertFalse(interrupter.isAlive(), "the interrupter must have finished");

            assertEquals(0, failures.get(), () -> failures.get() + " loads failed while " + interrupts.get()
                    + " interrupts were sent; the first failure was " + firstFailure.get());
            for (int i = 0; i < names.size(); i++) {
                Class<?> type = loaded[0][i];
                assertSame(loader, type.getClassLoader());
                assertEquals(ids.get(i), id(type), names.get(i));
                for (int t = 1; t < INTERRUPT_THREADS; t++) {
                    assertSame(type, loaded[t][i], "every thread must see one class");
                }
            }
            // The interrupts must not have left the archive unreadable for anyone else.
            AtomicReference<Object> later = new AtomicReference<>();
            Thread fresh = new Thread(() -> {
                try {
                    later.set(id(loader.loadClass("org.example.gen.C0")));
                } catch (Exception | LinkageError e) {
                    later.set(e);
                }
            }, "fresh-class-loader");
            fresh.start();
            fresh.join(TimeUnit.SECONDS.toMillis(30));
            assertEquals("gen0", later.get(), "a later load on a fresh thread");
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
    void duplicateLookupsMatchJdkPrecedenceInMappedAndPositionalModes() throws Exception {
        for (boolean mapped : List.of(true, false)) {
            System.setProperty(ArchiveSource.MMAP_PROPERTY, Boolean.toString(mapped));
            try (ArchiveSource modeSource = ArchiveSource.open(archive)) {
                assertEquals(mapped, modeSource.mapped());
                Index modeIndex = Index.open(modeSource);
                RunnerClassLoader loader = new RunnerClassLoader(modeIndex, modeSource,
                        ClassLoader.getPlatformClassLoader());
                assertEquals("version2", id(loader.loadClass("org.beta.Duplicate")),
                        "the last class in the highest applicable MR version wins");
                assertEquals("alpha-last!", string(loader.getResourceAsStream("duplicate.txt")),
                        "the first dependency still wins, selecting its last duplicate");

                System.setProperty("jdk.util.jar.enableMultiRelease", "false");
                RunnerClassLoader baseLoader = new RunnerClassLoader(modeIndex, modeSource,
                        ClassLoader.getPlatformClassLoader());
                assertEquals("base2", id(baseLoader.loadClass("org.beta.Duplicate")),
                        "the last base duplicate wins when MR lookup is disabled");
                System.clearProperty("jdk.util.jar.enableMultiRelease");
            }
        }

        assumeHandlersRegistered();
        RunnerClassLoader loader = newLoader();
        assertEquals("alpha-last!", string(loader.findResource("duplicate.txt").openStream()));
        List<URL> urls = list(loader.findResources("duplicate.txt"));
        assertEquals(2, urls.size(), "resource enumeration returns one selected duplicate per jar");
        assertEquals("alpha-last!", string(urls.get(0).openStream()));
        assertEquals("beta-version2", string(urls.get(1).openStream()));
        assertNotNull(loader.findResource("duplicate-dir"), "duplicate directories remain lookup-visible");
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
    void loadsAnApplicationClassUnderTheLauncherPrefix() throws Exception {
        // Nothing stops an application, or a dependency, from using a package under io.micronaut.runner.
        // The launcher's loader is preferred for that prefix so that its own types are shared, but it does
        // not have this class, and reporting it missing told the user their runner jar had been modified
        // when there was nothing wrong with it.
        RunnerClassLoader loader = newLoader();

        Class<?> sneaky = loader.loadClass("io.micronaut.runner.app.Sneaky");

        assertSame(loader, sneaky.getClassLoader(), "it is an application class like any other");
        assertEquals("sneaky", id(sneaky));
        assertSame(Entry.class, loader.loadClass("io.micronaut.runner.Entry"),
                "and the launcher's own types still come from the launcher's loader");
        assertThrows(ClassNotFoundException.class,
                () -> loader.loadClass("io.micronaut.runner.app.Absent"),
                "a class neither side has is still missing");
    }

    @Test
    void findsADirectoryResourceWithoutATrailingSlash() throws Exception {
        // ZipFile.getEntry, and NestedJarFile.getEntry with it, retry a name that missed with a trailing
        // slash. A URLClassLoader over a jar answers getResource("some/package") that way, and code that
        // probes for a package directory by name depends on it.
        assumeHandlersRegistered();
        RunnerClassLoader loader = newLoader();

        assertNotNull(loader.getResource("org/example/"));
        assertEquals(loader.getResource("org/example/"), loader.getResource("org/example"),
                "the same directory, asked for the other way");
        assertNotNull(loader.getResource("org/alpha"), "a directory of a nested jar too");
        assertEquals(list(loader.findResources("org/example/")), list(loader.findResources("org/example")),
                "and every jar that has it is still enumerated");
        assertNull(loader.getResource("org/nowhere"), "a directory that does not exist is still absent");
    }

    @Test
    void appliesSlashlessDirectoryFallbackWithinEachJarInClasspathOrder() throws Exception {
        assumeHandlersRegistered();
        RunnerClassLoader loader = newLoader();
        try (URLClassLoader oracle = new URLClassLoader(new URL[] {
                alphaClasspathJar.toURI().toURL(), betaClasspathJar.toURI().toURL()
        }, ClassLoader.getPlatformClassLoader())) {
            assertEquals(resourceContents(oracle.getResources("slashless-collision")),
                    resourceContents(loader.getResources("slashless-collision")),
                    "an earlier directory precedes a later exact-name file");
            assertEquals(resourceContents(oracle.getResources("reverse-collision")),
                    resourceContents(loader.getResources("reverse-collision")),
                    "a later directory remains visible after an earlier exact-name file");
            assertEquals(resourceContents(oracle.getResources("same-jar-collision")),
                    resourceContents(loader.getResources("same-jar-collision")),
                    "the exact name wins over the fallback within one jar");
            assertEquals(resourceContents(oracle.getResources("only-directory")),
                    resourceContents(loader.getResources("only-directory")));
            assertEquals(resourceContents(oracle.getResources("only-file")),
                    resourceContents(loader.getResources("only-file")));
            assertEquals(resourceContents(oracle.getResources("slashless-collision/")),
                    resourceContents(loader.getResources("slashless-collision/")),
                    "a slash-suffixed request does not also select exact-name files");
            assertEquals(string(oracle.getResourceAsStream("slashless-collision")),
                    string(loader.getResourceAsStream("slashless-collision")),
                    "direct streams use the same first resource");
        }

        List<URL> multiRelease = list(loader.getResources("mr-slashless-collision"));
        assertEquals(2, multiRelease.size());
        assertTrue(multiRelease.get(0).toString().contains("alpha.jar"), multiRelease.toString());
        assertEquals("", string(multiRelease.get(0).openStream()));
        assertTrue(multiRelease.get(1).toString().endsWith("META-INF/versions/9/mr-slashless-collision"),
                multiRelease.toString());
        assertEquals("beta-version", string(multiRelease.get(1).openStream()));
    }

    @Test
    void enumeratesZeroOneThirtyAndThreeHundredContributorsInClasspathOrder() throws Exception {
        assumeHandlersRegistered();
        RunnerClassLoader loader = newLoader();

        assertEquals(List.of(), list(loader.findResources("enumeration/absent.txt")));
        assertEnumeration(loader, ENUMERATION_ONE, 1, false);
        assertEnumeration(loader, ENUMERATION_THIRTY, 30, false);
        assertEnumeration(loader, ENUMERATION_MANY, ENUMERATION_JARS, false);
        assertEnumeration(loader, ENUMERATION_ADVERSARIAL, ENUMERATION_JARS, true);

        List<URL> collisions = list(loader.findResources(ENUMERATION_COLLISION));
        assertEquals(ENUMERATION_JARS, collisions.size());
        assertEquals(collisions.getFirst(), loader.findResource(ENUMERATION_COLLISION));
        for (int i = 0; i < collisions.size(); i++) {
            int jarId = i + 1;
            assertTrue(collisions.get(i).toString().contains(index.jarName(jarId)),
                    "URL " + i + " must identify jar " + jarId);
            String expected = jarId == 1 || (jarId & 1) == 0 ? value(jarId, "exact") : "";
            assertEquals(expected, string(collisions.get(i).openStream()), "collision jar " + jarId);
        }
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
    void rethrowsTheParentsOwnExceptionForAMiss() throws Exception {
        SentinelParent sentinel = new SentinelParent();
        RunnerClassLoader loader = new RunnerClassLoader(index, source, sentinel);

        assertSame(sentinel.missing, assertThrows(ClassNotFoundException.class,
                () -> loader.loadClass("org.example.Missing")),
                "an index-first miss lets the parent's exception through instead of building a second one");
        assertSame(sentinel.missing, assertThrows(ClassNotFoundException.class,
                () -> loader.loadClass("javax.net.Missing")),
                "a parent-visible miss rethrows the parent's exception once the archive misses too");
        assertTrue(sentinel.asked.contains("org.example.Missing"), sentinel.asked.toString());
        assertTrue(sentinel.asked.contains("javax.net.Missing"), sentinel.asked.toString());

        assertSame(String.class, loader.loadClass("java.lang.String"));
        assertTrue(sentinel.asked.contains("java.lang.String"),
                "a parent that is not one of the JDK's own loaders keeps being asked for JDK names");
    }

    @Test
    void reportsABootPackageMissByItsBinaryName() {
        RunnerClassLoader loader = newLoader();
        ClassNotFoundException failure = assertThrows(ClassNotFoundException.class,
                () -> loader.loadClass("java.lang.Missing"));
        assertEquals("java.lang.Missing", failure.getMessage(), "the binary name, not the internal one");
    }

    @Test
    void resolvesTheSameClassesUnderThePlatformAndTheSystemParent() throws Exception {
        RunnerClassLoader platform = newLoader();
        RunnerClassLoader system = new RunnerClassLoader(index, source, ClassLoader.getSystemClassLoader());

        Class<?> driver = Class.forName("java.sql.Driver", false, ClassLoader.getPlatformClassLoader());
        assertSame(driver, platform.loadClass("java.sql.Driver"), "a platform-module package");
        assertSame(driver, system.loadClass("java.sql.Driver"));
        assertSame(String.class, platform.loadClass("java.lang.String"), "a boot-module package");
        assertSame(String.class, system.loadClass("java.lang.String"));

        for (RunnerClassLoader loader : List.of(platform, system)) {
            Class<?> shadow = loader.loadClass("javax.net.Shadow");
            assertSame(loader, shadow.getClassLoader(),
                    "a boot-module package the boot loader does not have falls back to the archive");
            assertEquals("shadow", id(shadow));
            Class<?> application = loader.loadClass("org.example.App");
            assertSame(loader, application.getClassLoader());
            assertEquals("app", id(application));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void computesTheStringFormOfACodeSourceLocationOnce() throws Exception {
        assumeHandlersRegistered();
        RunnerClassLoader loader = newLoader();
        Class<?> awkward = loader.loadClass(AWKWARD_CLASS);
        URL location = awkward.getProtectionDomain().getCodeSource().getLocation();

        String expected = "jar:" + archive.toURI() + "!/MICRONAUT-INF/lib/a%20b-%C3%A9.jar!/";
        assertEquals(expected, location.toString());
        assertEquals(expected, location.toExternalForm());
        assertSame(location.toString(), location.toString(), "defineClass asks for it once per class");

        String resource = AWKWARD_CLASS.replace('.', '/') + ".class";
        URL derived = new URL(location, resource);
        assertEquals(expected + resource, derived.toString(), "a derived URL does not reuse the cached form");
        assertEquals(loader.getResource(resource), derived);
        try (InputStream in = derived.openStream()) {
            assertArrayEquals(classBytes(AWKWARD_CLASS, "awkward"), in.readAllBytes());
        }
    }

    @Test
    void sharesTheJarsManifestAttributesAcrossItsPackages() throws Exception {
        RunnerClassLoader loader = newLoader();
        Package alpha = loader.loadClass("org.alpha.Alpha").getPackage();
        Package attrs = loader.loadClass("org.alpha.pkg.Attrs").getPackage();

        assertEquals("2.0", alpha.getImplementationVersion());
        assertSame(alpha.getImplementationVersion(), attrs.getImplementationVersion(),
                "inherited from the jar, which is decoded once rather than once per package");
        assertEquals("AlphaSpec", alpha.getSpecificationTitle());
        assertSame(alpha.getSpecificationTitle(), attrs.getSpecificationTitle());
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
    void concurrentDefinitionsSharePackageOverrideMetadata() throws Exception {
        RunnerClassLoader loader = newLoader();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Class<?>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String name = "org.alpha.pkg.Concurrent" + i;
                futures.add(pool.submit(() -> loader.loadClass(name)));
            }
            Package defined = null;
            for (int i = 0; i < futures.size(); i++) {
                Class<?> type = futures.get(i).get(30, TimeUnit.SECONDS);
                assertEquals("concurrent" + i, id(type));
                if (defined == null) {
                    defined = type.getPackage();
                } else {
                    assertSame(defined, type.getPackage());
                }
            }
            assertEquals("9.9", defined.getSpecificationVersion());
            assertEquals("SectionImpl", defined.getImplementationTitle());
            assertEquals("AlphaSpec", defined.getSpecificationTitle());
            assertEquals("2.0", defined.getImplementationVersion());
        } finally {
            pool.shutdownNow();
        }
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

        // The index reads the flag when it is opened, so the verifying loader needs an index of its own.
        System.setProperty(RunnerClassLoader.VERIFY_PROPERTY, "true");
        try (ArchiveSource verifyingSource = ArchiveSource.open(archive)) {
            RunnerClassLoader verifying = new RunnerClassLoader(Index.open(verifyingSource), verifyingSource,
                    ClassLoader.getPlatformClassLoader());
            ClassNotFoundException failure = assertThrows(ClassNotFoundException.class,
                    () -> verifying.loadClass("org.example.Corrupt"));
            assertNotNull(failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("org/example/Corrupt.class"),
                    failure.getCause().getMessage());
            assertEquals("app", id(verifying.loadClass("org.example.App")),
                    "an intact entry still loads with verification on");
        }
    }

    @ParameterizedTest(name = "mmap={0}")
    @ValueSource(strings = {"true", "index", "false"})
    void verifiesClassChecksumsInEveryMode(String mmap) throws Exception {
        System.setProperty(RunnerClassLoader.VERIFY_PROPERTY, "true");
        System.setProperty(ArchiveSource.MMAP_PROPERTY, mmap);
        try (ArchiveSource modeSource = ArchiveSource.open(archive)) {
            RunnerClassLoader verifying = new RunnerClassLoader(Index.open(modeSource), modeSource,
                    ClassLoader.getPlatformClassLoader());
            assertEquals("index".equals(mmap), modeSource.indexOnly());
            ClassNotFoundException failure = assertThrows(ClassNotFoundException.class,
                    () -> verifying.loadClass("org.example.Corrupt"));
            String message = failure.getCause().getMessage();
            assertTrue(message.contains("org/example/Corrupt.class") && message.contains("has checksum"), message);
            assertEquals("app", id(verifying.loadClass("org.example.App")),
                    "an intact entry still loads with verification on");
            if ("index".equals(mmap)) {
                assertEquals(1, modeSource.createdBuffers(), "both classes were read into one pooled buffer");
                assertEquals(1, modeSource.idleBuffers(), "the failed define returned its buffer too");
            }
        }
    }

    /**
     * The VM resolves a class's supertypes while it is still parsing the class's bytes, so a define nests
     * other defines on the same thread. A buffer reused per thread would be overwritten under the parser: the
     * hierarchy here is the one that reproduced that as a {@link ClassFormatError}.
     */
    @ParameterizedTest(name = "mmap={0}")
    @ValueSource(strings = {"true", "index", "false"})
    void definesNestedSupertypesOnTheSameThread(String mmap) throws Exception {
        System.setProperty(ArchiveSource.MMAP_PROPERTY, mmap);
        try (ArchiveSource modeSource = ArchiveSource.open(archive)) {
            RunnerClassLoader loader = new RunnerClassLoader(Index.open(modeSource), modeSource,
                    ClassLoader.getPlatformClassLoader());

            Class<?> type = loader.loadClass(REENTRANT_PACKAGE + ".A");
            Object instance = type.getConstructor().newInstance();
            for (String method : List.of("a", "b", "i", "j", "k")) {
                assertEquals(method, type.getMethod(method).invoke(instance), method + "()");
            }
            assertSame(loader, type.getSuperclass().getClassLoader());
            if ("index".equals(mmap)) {
                assertTrue(modeSource.createdBuffers() >= 2,
                        "a nested define borrows a buffer of its own: " + modeSource.createdBuffers());
                assertEquals(modeSource.createdBuffers(), modeSource.idleBuffers(), "every buffer came back");
            }
        }
    }

    @Test
    void thirtyTwoThreadsDefineClassesThroughAtMostSixteenPooledBuffers() throws Exception {
        System.setProperty(ArchiveSource.MMAP_PROPERTY, "index");
        try (ArchiveSource modeSource = ArchiveSource.open(archive)) {
            RunnerClassLoader loader = new RunnerClassLoader(Index.open(modeSource), modeSource,
                    ClassLoader.getPlatformClassLoader());
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService threads = Executors.newFixedThreadPool(POOL_THREADS);
            try {
                List<Future<List<Class<?>>>> futures = new ArrayList<>();
                for (int t = 0; t < POOL_THREADS; t++) {
                    int offset = t * INTERRUPT_CLASSES_PER_JAR / POOL_THREADS;
                    futures.add(threads.submit(() -> {
                        start.await();
                        List<Class<?>> loaded = new ArrayList<>();
                        for (int i = 0; i < INTERRUPT_CLASSES_PER_JAR; i++) {
                            int at = (offset + i) % INTERRUPT_CLASSES_PER_JAR;
                            loaded.add(loader.loadClass(INTERRUPT_STORED_PACKAGE + ".C" + at));
                        }
                        return loaded;
                    }));
                }
                start.countDown();
                for (Future<List<Class<?>>> future : futures) {
                    for (Class<?> type : future.get(60, TimeUnit.SECONDS)) {
                        assertEquals("stored-" + type.getSimpleName().substring(1), id(type));
                    }
                }
            } finally {
                threads.shutdownNow();
            }

            assertTrue(modeSource.createdBuffers() >= 1, "the classes were read into pooled buffers");
            assertTrue(modeSource.createdBuffers() <= ArchiveSource.POOL_MAX_BUFFERS,
                    () -> modeSource.createdBuffers() + " direct buffers were created");
            assertEquals(modeSource.createdBuffers(), modeSource.idleBuffers(), "every buffer came back");
            modeSource.startupFinished();
            assertTrue(modeSource.idleBuffers() <= ArchiveSource.POOL_IDLE_AFTER_STARTUP,
                    () -> modeSource.idleBuffers() + " buffers are idle after startup");
        }
    }

    @Test
    void aClassLargerThanThePooledBuffersLoadsThroughTheHeap() throws Exception {
        Fixture fixture = new Fixture().headerFlags(IndexFormat.HEADER_FLAG_POSITIONAL_READS).largestStoredClass(4096);
        Fixture.Jar application = fixture.addJar(IndexFormat.CLASSES_PREFIX);
        String longValue = "x".repeat(6_000);
        byte[] large = classBytes("org.large.Large", longValue);
        assertTrue(large.length > 4096, "the class must not fit a pooled buffer");
        application.add("org/large/Large.class", large);
        application.add("org/large/Small.class", classBytes("org.large.Small", "small"));
        File file = fixture.writeTo(temporary.resolve("large-class.jar").toFile());

        System.clearProperty(ArchiveSource.MMAP_PROPERTY);
        try (ArchiveSource largeSource = ArchiveSource.open(file)) {
            RunnerClassLoader loader = new RunnerClassLoader(Index.open(largeSource), largeSource,
                    ClassLoader.getPlatformClassLoader());
            assertTrue(largeSource.indexOnly());
            assertEquals(4096, largeSource.poolBufferSize());

            assertEquals(longValue, id(loader.loadClass("org.large.Large")));
            assertEquals(0, largeSource.createdBuffers(), "the large class was read into a heap array");
            assertEquals("small", id(loader.loadClass("org.large.Small")));
            assertEquals(1, largeSource.createdBuffers());
        }
    }

    @Test
    void anArchiveThatAsksForPositionalReadsLoadsItsClassesAndCanBeDeletedOnceClosed() throws Exception {
        Fixture fixture = new Fixture().headerFlags(IndexFormat.HEADER_FLAG_NESTED_STORED
                | IndexFormat.HEADER_FLAG_POSITIONAL_READS);
        Fixture.Jar application = fixture.addJar(IndexFormat.CLASSES_PREFIX);
        application.add("org/switched/App.class", classBytes("org.switched.App", "switched-app"));
        application.add("switched.txt", text("switched-resource"));
        fixture.addJar("MICRONAUT-INF/lib/switched-stored.jar")
                .add("org/switched/stored/S.class", classBytes("org.switched.stored.S", "switched-stored"));
        fixture.addJar("MICRONAUT-INF/lib/switched-deflated.jar").deflate()
                .add("org/switched/deflated/D.class", classBytes("org.switched.deflated.D", "switched-deflated"));
        File file = fixture.writeTo(temporary.resolve("switched.jar").toFile());

        System.clearProperty(ArchiveSource.MMAP_PROPERTY);
        ArchiveSource switched = ArchiveSource.open(file);
        try {
            assertTrue(switched.mapped(), "until the index is read, the whole file is mapped");
            RunnerClassLoader loader = new RunnerClassLoader(Index.open(switched), switched,
                    ClassLoader.getPlatformClassLoader());
            assertFalse(switched.mapped());
            assertTrue(switched.indexOnly());

            assertEquals("switched-app", id(loader.loadClass("org.switched.App")));
            assertEquals("switched-stored", id(loader.loadClass("org.switched.stored.S")));
            assertEquals("switched-deflated", id(loader.loadClass("org.switched.deflated.D")));
            assertEquals("switched-resource", string(loader.getResourceAsStream("switched.txt")));
            assertEquals(1, switched.createdBuffers());
        } finally {
            switched.close();
        }
        assertTrue(file.delete(), "an archive must be deletable once the source is closed");
    }


    @Test
    void verifiesStoredAndDeflatedResourceStreamsOnlyWhenAskedTo() throws Exception {
        RunnerClassLoader unchecked = newLoader();
        assertEquals("corrupt stored resource", string(unchecked.getResourceAsStream("corrupt-stored.txt")));
        assertEquals("corrupt deflated resource",
                string(unchecked.getResourceAsStream("corrupt-deflated.txt")));

        System.setProperty(RunnerClassLoader.VERIFY_PROPERTY, "true");
        try (ArchiveSource verifyingSource = ArchiveSource.open(archive)) {
            RunnerClassLoader verifying = new RunnerClassLoader(Index.open(verifyingSource), verifyingSource,
                    ClassLoader.getPlatformClassLoader());
            IOException stored = assertThrows(IOException.class,
                    () -> string(verifying.getResourceAsStream("corrupt-stored.txt")));
            assertTrue(stored.getMessage().contains("corrupt-stored.txt"), stored.getMessage());
            IOException deflated = assertThrows(IOException.class,
                    () -> string(verifying.getResourceAsStream("corrupt-deflated.txt")));
            assertTrue(deflated.getMessage().contains("corrupt-deflated.txt"), deflated.getMessage());
            assertEquals("jar0-shared", string(verifying.getResourceAsStream("shared.txt")),
                    "an intact resource still reads with verification on");
        }
    }

    @Test
    void verifiesResourceStreamsInMappedAndPositionalModes() throws Exception {
        System.setProperty(RunnerClassLoader.VERIFY_PROPERTY, "true");
        for (String mode : List.of("mapped", "false", "index")) {
            boolean mapped = "mapped".equals(mode);
            if (mapped) {
                System.clearProperty(ArchiveSource.MMAP_PROPERTY);
            } else {
                System.setProperty(ArchiveSource.MMAP_PROPERTY, mode);
            }
            try (ArchiveSource modeSource = ArchiveSource.open(archive)) {
                assertEquals(mapped, modeSource.mapped());
                assertEquals("index".equals(mode), modeSource.indexOnly());
                RunnerClassLoader loader = new RunnerClassLoader(Index.open(modeSource), modeSource,
                        ClassLoader.getPlatformClassLoader());
                assertThrows(IOException.class,
                        () -> string(loader.getResourceAsStream("corrupt-stored.txt")));
                assertThrows(IOException.class,
                        () -> string(loader.getResourceAsStream("corrupt-deflated.txt")));
            }
        }
    }

    @Test
    void directResourceStreamsPerformLazyNestedHeaderValidation() throws Exception {
        Fixture fixture = new Fixture();
        fixture.addJar(IndexFormat.CLASSES_PREFIX);
        Fixture.Jar dependency = fixture.addJar("MICRONAUT-INF/lib/stale.jar");
        dependency.add("payload.txt", text("payload"));
        File stale = fixture.writeTo(temporary.resolve("stale-resource.jar").toFile());

        System.setProperty(ArchiveSource.MMAP_PROPERTY, "false");
        try (ArchiveSource staleSource = ArchiveSource.open(stale)) {
            Index staleIndex = Index.open(staleSource);
            RunnerClassLoader loader = new RunnerClassLoader(staleIndex, staleSource,
                    ClassLoader.getPlatformClassLoader());
            try (RandomAccessFile editable = new RandomAccessFile(stale, "rw")) {
                editable.seek(dependency.localHeaderOffset);
                editable.write(new byte[4]);
            }

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> loader.getResourceAsStream("payload.txt"));
            assertTrue(failure.getMessage().contains("no local file header"), failure.getMessage());
        }
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
    void treatsTheSlashlessMicronautServiceDirectoryAsMergedApplicationMetadata() throws Exception {
        assumeHandlersRegistered();
        RunnerClassLoader loader = newLoader();

        URL directory = loader.getResource(IndexFormat.MICRONAUT_SERVICES_PREFIX);
        assertEquals(directory, loader.getResource("META-INF/micronaut"));
        assertEquals(List.of(directory), list(loader.getResources("META-INF/micronaut")),
                "a dependency exact-name file must not escape the merged-resource policy");
        assertEquals("", string(loader.getResourceAsStream("META-INF/micronaut")));
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
        // An empty segment is collapsed whether or not a dot segment happens to be in the same name.
        assertEquals("a/b", RunnerClassLoader.normalizeResourceName("a//b"));
        assertEquals("a/b", RunnerClassLoader.normalizeResourceName("a/.//b"));
        assertEquals("a/b", RunnerClassLoader.normalizeResourceName("//a/b"));
        assertEquals("a/", RunnerClassLoader.normalizeResourceName("a//"));
        assertEquals("a/", RunnerClassLoader.normalizeResourceName("a/"),
                "one trailing slash is how a directory is asked for, and it survives");
        assertEquals("", RunnerClassLoader.normalizeResourceName(""));
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

    /**
     * {@code A extends B implements I, J}, with {@code J extends K} and {@code B implements K}: defining
     * {@code A} defines its supertypes while the VM is still parsing {@code A}, and {@code J} defines
     * {@code K} while it is being parsed in turn. Each declares one method returning its own lower-case name.
     */
    private static void addReentrantFixture(Fixture.Jar application) {
        String path = REENTRANT_PACKAGE.replace('.', '/') + "/";
        String name = REENTRANT_PACKAGE + ".";
        application.add(path + "K.class", interfaceBytes(name + "K", List.of(), "k"));
        application.add(path + "I.class", interfaceBytes(name + "I", List.of(), "i"));
        application.add(path + "J.class", interfaceBytes(name + "J", List.of(name + "K"), "j"));
        application.add(path + "B.class", subclassBytes(name + "B", "java.lang.Object", List.of(name + "K"), "b"));
        application.add(path + "A.class", subclassBytes(name + "A", name + "B", List.of(name + "I", name + "J"),
                "a"));
    }

    private static byte[] interfaceBytes(String binaryName, List<String> superinterfaces, String method) {
        return ClassFile.of().build(ClassDesc.of(binaryName), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT)
                .withInterfaceSymbols(superinterfaces.stream().map(ClassDesc::of).toList())
                .withMethodBody(method, MethodTypeDesc.of(ConstantDescs.CD_String), ClassFile.ACC_PUBLIC,
                        code -> code.loadConstant(method).areturn()));
    }

    private static byte[] subclassBytes(String binaryName, String superclass, List<String> interfaces,
                                        String method) {
        ClassDesc parent = ClassDesc.of(superclass);
        return ClassFile.of().build(ClassDesc.of(binaryName), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withSuperclass(parent)
                .withInterfaceSymbols(interfaces.stream().map(ClassDesc::of).toList())
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
                        code -> code.aload(0)
                                .invokespecial(parent, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                                .return_())
                .withMethodBody(method, MethodTypeDesc.of(ConstantDescs.CD_String), ClassFile.ACC_PUBLIC,
                        code -> code.loadConstant(method).areturn()));
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

    private static List<String> resourceContents(Enumeration<URL> urls) throws IOException {
        List<String> contents = new ArrayList<>();
        for (URL url : list(urls)) {
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            contents.add(string(connection.getInputStream()));
        }
        return contents;
    }

    private static void assertEnumeration(RunnerClassLoader loader, String name, int count, boolean adversarial)
            throws IOException {
        List<URL> urls = list(loader.findResources(name));
        assertEquals(count, urls.size(), name);
        assertEquals(urls.getFirst(), loader.findResource(name));
        for (int i = 0; i < urls.size(); i++) {
            int jarId = i + 1;
            URL url = urls.get(i);
            assertTrue(url.toString().contains(index.jarName(jarId)),
                    "URL " + i + " must identify jar " + jarId + ": " + url);
            String suffix = adversarial && jarId % 3 == 0 ? "v17-last" : adversarial ? "base-last" : "value";
            assertEquals(value(jarId, suffix), string(url.openStream()), name + " jar " + jarId);
            if (adversarial && jarId % 3 == 0) {
                assertTrue(url.toString().contains("META-INF/versions/17/"), url.toString());
            }
        }
    }

    private static void addEnumerationFixtures(Fixture fixture) {
        while (fixture.jars.size() <= ENUMERATION_JARS) {
            int jarId = fixture.jars.size();
            fixture.addJar(String.format("MICRONAUT-INF/lib/enumeration-%03d.jar", jarId));
        }
        for (int jarId = 1; jarId <= ENUMERATION_JARS; jarId++) {
            Fixture.Jar jar = fixture.jars.get(jarId);
            jar.add(ENUMERATION_MANY, text(value(jarId, "value")));
            if (jarId <= 30) {
                jar.add(ENUMERATION_THIRTY, text(value(jarId, "value")));
            }
            if (jarId == 1) {
                jar.add(ENUMERATION_ONE, text(value(jarId, "value")));
            }
            jar.add(ENUMERATION_ADVERSARIAL, text(value(jarId, "base-first")));
            jar.add(ENUMERATION_ADVERSARIAL, text(value(jarId, "base-last")));
            if (jarId % 3 == 0) {
                jar.multiRelease();
                jar.add("META-INF/versions/17/" + ENUMERATION_ADVERSARIAL,
                        text(value(jarId, "v17-first")));
                jar.add("META-INF/versions/17/" + ENUMERATION_ADVERSARIAL,
                        text(value(jarId, "v17-last")));
                jar.add("META-INF/versions/99/" + ENUMERATION_ADVERSARIAL,
                        text(value(jarId, "future")));
            }
            if (jarId == 1 || (jarId & 1) == 0) {
                jar.add(ENUMERATION_COLLISION, text(value(jarId, "exact")));
            }
            if ((jarId & 1) != 0) {
                jar.add(ENUMERATION_COLLISION + "/", new byte[0]);
            }
        }
    }

    /**
     * Two jars of generated classes for the interrupt tests: one STORED and one DEFLATE, each with packages
     * of its own so that nothing else in the archive shares their names.
     */
    private static void addInterruptFixtures(Fixture fixture) {
        Fixture.Jar stored = fixture.addJar(INTERRUPT_STORED);
        Fixture.Jar deflated = fixture.addJar(INTERRUPT_DEFLATED).deflate();
        for (int i = 0; i < INTERRUPT_CLASSES_PER_JAR; i++) {
            stored.add(INTERRUPT_STORED_PACKAGE.replace('.', '/') + "/C" + i + ".class",
                    classBytes(INTERRUPT_STORED_PACKAGE + ".C" + i, "stored-" + i));
            deflated.add(INTERRUPT_DEFLATED_PACKAGE.replace('.', '/') + "/C" + i + ".class",
                    classBytes(INTERRUPT_DEFLATED_PACKAGE + ".C" + i, "deflated-" + i));
        }
    }

    private static String value(int jarId, String suffix) {
        return String.format("jar-%03d-%s", jarId, suffix);
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
     * A parent that is not one of the JDK's own loaders: it records every name it is asked for, throws one
     * pre-built exception for every name ending in {@code .Missing}, and hands everything else to the
     * platform loader.
     */
    private static final class SentinelParent extends ClassLoader {

        private final ClassNotFoundException missing = new ClassNotFoundException("sentinel");
        private final List<String> asked = Collections.synchronizedList(new ArrayList<>());

        private SentinelParent() {
            super(null);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            asked.add(name);
            if (name.endsWith(".Missing")) {
                throw missing;
            }
            return ClassLoader.getPlatformClassLoader().loadClass(name);
        }
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
        private int headerFlags;
        private long largestStoredClass = -1;

        Fixture headerFlags(int value) {
            this.headerFlags = value;
            return this;
        }

        Fixture largestStoredClass(long value) {
            this.largestStoredClass = value;
            return this;
        }

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
                item.compressed = builder.latestStoredSize(outer);
            }
            for (int i = 1; i < jars.size(); i++) {
                Jar jar = jars.get(i);
                TestArchiveBuilder nested = new TestArchiveBuilder();
                for (Item item : jar.items) {
                    item.offset = store(nested, item.name, item, jar.deflate);
                    item.compressed = nested.latestStoredSize(item.name);
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
            TestIndexBuilder builder = new TestIndexBuilder().startClass("org.example.App")
                    .headerFlags(headerFlags).largestStoredClass(largestStoredClass);
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
