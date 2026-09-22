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

import io.micronaut.runner.build.BuildLogger;
import io.micronaut.runner.build.Compression;
import io.micronaut.runner.build.Dependency;
import io.micronaut.runner.build.RunnerJarBuilder;
import io.micronaut.runner.build.RunnerJarSpec;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The fixture every micro-benchmark in this package measures against: a synthetic application whose shape
 * is that of a small Micronaut service - roughly thirty dependency jars holding a few thousand small
 * classes - packaged into runner jars with both compression modes.
 *
 * <h2>Why it is synthetic</h2>
 * <p>Nothing is downloaded. The class files are generated with {@link java.lang.classfile}, which makes
 * the fixture reproducible, offline, and quick enough to build inside a JMH trial setup. What matters for
 * the index and the class loader is the number of entries, the length and shape of their names and the
 * size of their bodies, and those are modelled on a real application; the bytecode inside is not
 * interesting to any of the paths under test.</p>
 *
 * <h2>One fixture per JVM</h2>
 * <p>Building it costs a second or two, so {@link #shared()} builds it once per forked JVM and every
 * {@code @Setup(Level.Trial)} in this package asks for that one instance. The tree is deleted by a
 * shutdown hook, which is the only cleanup a forked JMH JVM can be relied on to run.</p>
 */
final class SyntheticArchive {

    /** Number of synthetic dependency jars. */
    static final int LIBRARY_COUNT = 30;

    /** Packages per dependency jar. */
    static final int PACKAGES_PER_LIBRARY = 4;

    /** Classes per package, so {@code 30 * 4 * 30 = 3600} library classes in total. */
    static final int CLASSES_PER_PACKAGE = 30;

    /** The main class of the synthetic application layer. */
    static final String MAIN_CLASS = "org.synthetic.app.Main";

    /** Package segments the generated packages cycle through, modelled on a Micronaut dependency. */
    private static final String[] SEGMENTS = {
        "core.util", "http.server.netty", "inject.annotation", "runtime.context.scope"
    };

    /** Class name prefixes the generator cycles through. */
    private static final String[] PREFIXES = {"Default", "Abstract", "", "Simple"};

    /** Class name stems the generator cycles through. */
    private static final String[] STEMS = {
        "WidgetService", "BeanDefinition", "HttpMessageHandler", "ConfigurationReader"
    };

    private static volatile SyntheticArchive shared;

    private final Path root;
    private final Path applicationClasses;
    private final List<Path> libraryJars;
    private final List<String> classNames;
    private final Path storedRunnerJar;
    private final Path preserveRunnerJar;

    private SyntheticArchive(Path root,
                             Path applicationClasses,
                             List<Path> libraryJars,
                             List<String> classNames,
                             Path storedRunnerJar,
                             Path preserveRunnerJar) {
        this.root = root;
        this.applicationClasses = applicationClasses;
        this.libraryJars = List.copyOf(libraryJars);
        this.classNames = List.copyOf(classNames);
        this.storedRunnerJar = storedRunnerJar;
        this.preserveRunnerJar = preserveRunnerJar;
    }

    /**
     * The one fixture of this JVM, built on first use.
     *
     * @return the shared fixture
     */
    static synchronized SyntheticArchive shared() {
        SyntheticArchive existing = shared;
        if (existing != null) {
            return existing;
        }
        try {
            SyntheticArchive built = build();
            Runtime.getRuntime().addShutdownHook(new Thread(built::delete, "synthetic-archive-cleanup"));
            shared = built;
            return built;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the synthetic benchmark fixture", e);
        }
    }

    /**
     * The runner jar whose nested dependencies were re-packed uncompressed.
     *
     * @return the STORED runner jar
     */
    Path storedRunnerJar() {
        return storedRunnerJar;
    }

    /**
     * The runner jar whose nested dependencies are byte-identical copies of the deflated source jars.
     *
     * @return the PRESERVE runner jar
     */
    Path preserveRunnerJar() {
        return preserveRunnerJar;
    }

    /**
     * The dependency jars, in class path order.
     *
     * @return the library jars
     */
    List<Path> libraryJars() {
        return libraryJars;
    }

    /**
     * The same class path a runner jar carries, as URLs, for a {@link java.net.URLClassLoader} baseline:
     * the application's classes directory first, then every dependency jar in order.
     *
     * @return the class path URLs
     */
    URL[] classPathUrls() {
        List<URL> urls = new ArrayList<>(libraryJars.size() + 1);
        try {
            urls.add(applicationClasses.toUri().toURL());
            for (Path jar : libraryJars) {
                urls.add(jar.toUri().toURL());
            }
        } catch (MalformedURLException e) {
            throw new IllegalStateException("A generated path is not a URL", e);
        }
        return urls.toArray(new URL[0]);
    }

    /**
     * Every generated library class, in generation order.
     *
     * @return the binary names
     */
    List<String> classNames() {
        return classNames;
    }

    /**
     * A spread-out sample of class names, taken with a stride so that consecutive names fall in different
     * jars and different index hash buckets rather than walking one package in order.
     *
     * @param count how many names are wanted
     * @return exactly {@code count} names that all exist in the archive
     */
    String[] spreadSample(int count) {
        String[] sample = new String[count];
        int size = classNames.size();
        // A stride coprime with the number of classes lands on a different jar, a different package and a
        // different hash bucket at every step, instead of walking one package in declaration order and
        // measuring a cache-friendly pattern no real application produces.
        int stride = 977;
        for (int i = 0; i < count; i++) {
            sample[i] = classNames.get((int) (((long) i * stride) % size));
        }
        return sample;
    }

    /**
     * Names shaped exactly like the ones in the archive but absent from it, so a miss costs what a miss
     * really costs: the full hash, the probe and, on a collision, a byte comparison that fails late.
     *
     * @param count how many names are wanted
     * @return exactly {@code count} names that do not exist in the archive
     */
    String[] missingSample(int count) {
        String[] sample = new String[count];
        String[] present = spreadSample(count);
        for (int i = 0; i < count; i++) {
            sample[i] = present[i] + "Absent";
        }
        return sample;
    }

    /** Removes the whole fixture. */
    void delete() {
        deleteRecursively(root);
    }

    private static SyntheticArchive build() throws IOException {
        Path root = Files.createTempDirectory("micronaut-runner-bench");
        Path libraries = Files.createDirectories(root.resolve("lib"));
        Path applicationClasses = Files.createDirectories(root.resolve("app").resolve("classes"));

        List<String> classNames = new ArrayList<>(LIBRARY_COUNT * PACKAGES_PER_LIBRARY * CLASSES_PER_PACKAGE);
        List<Path> libraryJars = new ArrayList<>(LIBRARY_COUNT);
        for (int library = 0; library < LIBRARY_COUNT; library++) {
            Path jar = libraries.resolve(String.format("synthetic-lib-%02d-1.0.0.jar", library));
            libraryJars.add(jar);
            // Deflated, the way a jar downloaded from a repository is. The STORED runner jar re-packs
            // these; the PRESERVE one copies them as they are, which is what makes the two archives a
            // fair STORED-versus-DEFLATE comparison over identical class bytes.
            writeLibrary(jar, library, classNames);
        }
        writeApplication(applicationClasses);

        Path stored = root.resolve("runner-stored.jar");
        Path preserve = root.resolve("runner-preserve.jar");
        pack(applicationClasses, libraryJars, stored, Compression.STORED);
        pack(applicationClasses, libraryJars, preserve, Compression.PRESERVE);
        return new SyntheticArchive(root, applicationClasses, libraryJars, classNames, stored, preserve);
    }

    private static void pack(Path applicationClasses,
                             List<Path> libraryJars,
                             Path output,
                             Compression compression) throws IOException {
        RunnerJarSpec spec = RunnerJarSpec.builder()
                .mainClass(MAIN_CLASS)
                .applicationOutput(List.of(applicationClasses))
                .dependencies(libraryJars.stream().map(Dependency::new).toList())
                .output(output)
                .compression(compression)
                .build();
        RunnerJarBuilder.build(spec, BuildLogger.noOp());
    }

    private static void writeLibrary(Path jar, int library, List<String> classNames) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        String name = String.format("synthetic-lib-%02d", library);
        entries.put("META-INF/MANIFEST.MF", ("Manifest-Version: 1.0\r\n"
                + "Implementation-Title: " + name + "\r\n"
                + "Implementation-Version: 1.0.0\r\n"
                + "Implementation-Vendor: Synthetic\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        for (int pkg = 0; pkg < PACKAGES_PER_LIBRARY; pkg++) {
            String packageName = String.format("org.synthetic.lib%02d.%s", library, SEGMENTS[pkg % SEGMENTS.length]);
            for (int clazz = 0; clazz < CLASSES_PER_PACKAGE; clazz++) {
                int ordinal = classNames.size();
                String simple = PREFIXES[clazz % PREFIXES.length]
                        + STEMS[(clazz / PREFIXES.length) % STEMS.length]
                        + String.format("%03d", clazz);
                // Every eighth class is a nested type, because real archives are full of them and their
                // names are longer, which is what the index's byte-comparison path has to chew through.
                if (clazz % 8 == 7) {
                    simple = simple + "$Definition";
                }
                String binaryName = packageName + "." + simple;
                classNames.add(binaryName);
                entries.put(binaryName.replace('.', '/') + ".class", classBytes(binaryName, ordinal));
            }
            entries.put(packageName.replace('.', '/') + "/package-info.class",
                    classBytes(packageName + ".package-info", -1));
        }
        entries.put("META-INF/services/org.synthetic.Service",
                (String.format("org.synthetic.lib%02d.core.util.DefaultWidgetService000", library) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 3; i++) {
            entries.put("META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/"
                    + String.format("org.synthetic.lib%02d.core.util.$Bean%d$Definition", library, i),
                    new byte[0]);
        }
        entries.put(String.format("org/synthetic/lib%02d/messages.properties", library),
                ("greeting=hello from " + name + "\n").getBytes(StandardCharsets.UTF_8));
        writeJar(jar, entries);
    }

    private static void writeApplication(Path classes) throws IOException {
        Path main = classes.resolve(MAIN_CLASS.replace('.', '/') + ".class");
        Files.createDirectories(main.getParent());
        Files.write(main, mainClassBytes());
        Files.write(classes.resolve("application.properties"),
                "synthetic.application.name=benchmark\n".getBytes(StandardCharsets.UTF_8));
        Path services = classes.resolve("META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference");
        Files.createDirectories(services);
        Files.write(services.resolve("org.synthetic.app.$Main$Definition"), new byte[0]);
    }

    private static void writeJar(Path jar, Map<String, byte[]> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setMethod(ZipOutputStream.DEFLATED);
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                // Fixed time so two runs of the fixture produce identical archives.
                zipEntry.setTime(315532800000L);
                CRC32 crc = new CRC32();
                crc.update(entry.getValue());
                zipEntry.setCrc(crc.getValue());
                zipEntry.setSize(entry.getValue().length);
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private static byte[] classBytes(String binaryName, int ordinal) {
        ClassDesc self = ClassDesc.of(binaryName);
        return ClassFile.of().build(self, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            builder.withField("name", ConstantDescs.CD_String,
                    ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
            builder.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void,
                    ClassFile.ACC_PUBLIC, code -> code
                            .aload(0)
                            .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                    ConstantDescs.MTD_void)
                            .aload(0)
                            .loadConstant(binaryName)
                            .putfield(self, "name", ConstantDescs.CD_String)
                            .return_());
            builder.withMethodBody("name", MethodTypeDesc.of(ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC, code -> code
                            .aload(0)
                            .getfield(self, "name", ConstantDescs.CD_String)
                            .areturn());
            builder.withMethodBody("ordinal", MethodTypeDesc.of(ConstantDescs.CD_int),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code
                            .loadConstant(ordinal)
                            .ireturn());
            builder.withMethodBody("describe", MethodTypeDesc.of(ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code
                            .loadConstant(binaryName + " #" + ordinal)
                            .areturn());
        });
    }

    private static byte[] mainClassBytes() {
        ClassDesc self = ClassDesc.of(MAIN_CLASS);
        return ClassFile.of().build(self, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER);
            builder.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void,
                    ClassFile.ACC_PUBLIC, code -> code
                            .aload(0)
                            .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                    ConstantDescs.MTD_void)
                            .return_());
            builder.withMethodBody("main",
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String.arrayType()),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.return_());
        });
    }

    private static void deleteRecursively(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // A leftover temporary directory is not worth failing a benchmark run over.
            System.err.println("Could not delete the synthetic fixture at " + directory + ": " + e);
        }
    }
}
