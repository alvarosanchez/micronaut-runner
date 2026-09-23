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
 * The fixtures every micro-benchmark in this package measures against: deterministic synthetic applications
 * whose named shapes independently vary dependency count, class-entry count, class payload and resource size.
 *
 * <h2>Why they are synthetic</h2>
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

    /** Ordinary service descriptor contributed by every dependency. */
    static final String SERVICE_RESOURCE = "META-INF/services/org.synthetic.Service";

    /** Same-name resource contributed by every dependency in class-path order. */
    static final String DUPLICATE_RESOURCE = "org/synthetic/shared/duplicate.txt";

    /** Resource large enough to exercise streaming rather than lookup overhead. */
    static final String STREAM_RESOURCE = "org/synthetic/shared/payload.bin";

    /** Logical multi-release resource with base and Java 25 physical entries. */
    static final String VERSIONED_RESOURCE = "org/synthetic/shared/version.txt";

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

    private static final Map<String, SyntheticArchive> SHARED = new LinkedHashMap<>();

    private final WorkloadShape shape;
    private final Path root;
    private final Path applicationClasses;
    private final List<Path> libraryJars;
    private final List<String> classNames;
    private final Path storedRunnerJar;
    private final Path preserveRunnerJar;

    private SyntheticArchive(WorkloadShape shape,
                             Path root,
                             Path applicationClasses,
                             List<Path> libraryJars,
                             List<String> classNames,
                             Path storedRunnerJar,
                             Path preserveRunnerJar) {
        this.shape = shape;
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
        return forWorkload("representative");
    }

    /**
     * Builds or reuses one deterministic fixture for the named workload in this forked JVM.
     *
     * @param name one of {@link WorkloadShape#standard()}
     * @return the fixture
     */
    static synchronized SyntheticArchive forWorkload(String name) {
        return forShape(WorkloadShape.named(name));
    }

    /**
     * Builds or reuses the small, resource-focused fixture used to measure same-name enumeration.
     *
     * @param contributors dependency jars that contribute the measured name
     * @return a prebuilt runner archive with exactly that many contributors
     */
    static synchronized SyntheticArchive forResourceEnumeration(int contributors) {
        if (contributors < 1) {
            throw new IllegalArgumentException("contributors must be positive");
        }
        return forShape(new WorkloadShape("resource-enumeration-" + contributors,
                contributors, 16, 0, 1, 1, 0, true, true));
    }

    private static SyntheticArchive forShape(WorkloadShape shape) {
        String name = shape.name();
        SyntheticArchive existing = SHARED.get(name);
        if (existing != null) {
            return existing;
        }
        try {
            SyntheticArchive built = build(shape);
            Runtime.getRuntime().addShutdownHook(new Thread(built::delete, "synthetic-archive-cleanup"));
            SHARED.put(name, built);
            return built;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the synthetic benchmark fixture", e);
        }
    }

    /** @return the declared fixture shape. */
    WorkloadShape shape() {
        return shape;
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

    /** @return exploded application input for packaging profiles. */
    Path applicationClasses() {
        return applicationClasses;
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
     * A locality-heavy sample from the first dependency, in archive order.
     *
     * @param count number of names, no more than one dependency contains
     * @return local names
     */
    String[] localSample(int count) {
        if (count > shape.entriesPerJar()) {
            throw new IllegalArgumentException("local sample " + count + " exceeds entries per jar "
                    + shape.entriesPerJar());
        }
        return classNames.subList(0, count).toArray(String[]::new);
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

    private static SyntheticArchive build(WorkloadShape shape) throws IOException {
        Path root = Files.createTempDirectory("micronaut-runner-bench-" + shape.name());
        Path libraries = Files.createDirectories(root.resolve("lib"));
        Path applicationClasses = Files.createDirectories(root.resolve("app").resolve("classes"));

        List<String> classNames = new ArrayList<>(shape.jarCount() * shape.entriesPerJar());
        List<Path> libraryJars = new ArrayList<>(shape.jarCount());
        for (int library = 0; library < shape.jarCount(); library++) {
            Path jar = libraries.resolve(String.format("synthetic-lib-%03d-1.0.0.jar", library));
            libraryJars.add(jar);
            // Deflated, the way a jar downloaded from a repository is. The STORED runner jar re-packs
            // these; the PRESERVE one copies them as they are, which is what makes the two archives a
            // fair STORED-versus-DEFLATE comparison over identical class bytes.
            writeLibrary(jar, library, shape, classNames);
        }
        writeApplication(applicationClasses);

        Path stored = root.resolve("runner-stored.jar");
        Path preserve = root.resolve("runner-preserve.jar");
        pack(applicationClasses, libraryJars, stored, Compression.STORED);
        pack(applicationClasses, libraryJars, preserve, Compression.PRESERVE);
        return new SyntheticArchive(shape, root, applicationClasses, libraryJars, classNames, stored, preserve);
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

    private static void writeLibrary(Path jar,
                                     int library,
                                     WorkloadShape shape,
                                     List<String> classNames) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        String name = String.format("synthetic-lib-%03d", library);
        StringBuilder manifest = new StringBuilder("Manifest-Version: 1.0\r\n")
                .append("Implementation-Title: ").append(name).append("\r\n")
                .append("Implementation-Version: 1.0.0\r\n")
                .append("Implementation-Vendor: Synthetic\r\n");
        if (shape.multiReleaseEntries()) {
            manifest.append("Multi-Release: true\r\n");
        }
        manifest.append("\r\n");
        for (int pkg = 0; pkg < shape.manifestPackageSections(); pkg++) {
            manifest.append("Name: ")
                    .append(String.format("org/synthetic/lib%03d/pkg%02d/%s/", library, pkg,
                            SEGMENTS[pkg % SEGMENTS.length]))
                    .append("\r\nImplementation-Version: 1.0.0-").append(pkg)
                    .append("\r\n\r\n");
        }
        entries.put("META-INF/MANIFEST.MF", manifest.toString().getBytes(StandardCharsets.UTF_8));

        int packageCount = Math.max(1, shape.manifestPackageSections());
        int remaining = shape.entriesPerJar();
        for (int pkg = 0; pkg < packageCount && remaining > 0; pkg++) {
            String packageName = String.format("org.synthetic.lib%03d.pkg%02d.%s", library, pkg,
                    SEGMENTS[pkg % SEGMENTS.length]);
            int packagesLeft = packageCount - pkg;
            int classesInPackage = (remaining + packagesLeft - 1) / packagesLeft;
            for (int clazz = 0; clazz < classesInPackage; clazz++) {
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
                entries.put(binaryName.replace('.', '/') + ".class",
                        classBytes(binaryName, ordinal, shape.classPayloadBytes()));
                remaining--;
            }
            entries.put(packageName.replace('.', '/') + "/package-info.class",
                    classBytes(packageName + ".package-info", -1, 0));
        }

        for (int service = 0; service < shape.serviceDescriptorsPerJar(); service++) {
            String resource = service == 0 ? SERVICE_RESOURCE : SERVICE_RESOURCE + service;
            entries.put(resource,
                    String.format("org.synthetic.lib%03d.Provider%02d%n", library, service)
                            .getBytes(StandardCharsets.UTF_8));
        }
        if (shape.duplicateNames()) {
            entries.put(DUPLICATE_RESOURCE, ("library-" + library).getBytes(StandardCharsets.UTF_8));
        }
        if (library == 0) {
            byte[] payload = new byte[shape.streamResourceBytes()];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i * 31 + 17);
            }
            entries.put(STREAM_RESOURCE, payload);
        }
        if (shape.multiReleaseEntries()) {
            entries.put(VERSIONED_RESOURCE, "base".getBytes(StandardCharsets.UTF_8));
            entries.put("META-INF/versions/25/" + VERSIONED_RESOURCE,
                    "version-25".getBytes(StandardCharsets.UTF_8));
        }
        for (int i = 0; i < 3; i++) {
            entries.put("META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/"
                    + String.format("org.synthetic.lib%03d.core.util.$Bean%d$Definition", library, i),
                    new byte[0]);
        }
        entries.put(String.format("org/synthetic/lib%03d/messages.properties", library),
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

    private static byte[] classBytes(String binaryName, int ordinal, int payloadBytes) {
        ClassDesc self = ClassDesc.of(binaryName);
        String payload = "x".repeat(payloadBytes);
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
            builder.withMethodBody("payload", MethodTypeDesc.of(ConstantDescs.CD_String),
                    ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code
                            .loadConstant(payload)
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
