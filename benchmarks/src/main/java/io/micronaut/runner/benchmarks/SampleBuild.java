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

import io.micronaut.runner.IndexFormat;
import io.micronaut.runner.build.BuildLogger;
import io.micronaut.runner.build.Compression;
import io.micronaut.runner.build.Dependency;
import io.micronaut.runner.build.RunnerJarBuilder;
import io.micronaut.runner.build.RunnerJarReader;
import io.micronaut.runner.build.RunnerJarSpec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Turns the sample application into every packaging the benchmark compares.
 *
 * <h2>Identical application bytes</h2>
 * <p>All variants are built from the same compiled classes and the same resolved dependency jars,
 * taken from one Gradle build of the sample. That is the only way the comparison means anything: if the
 * shaded jar were built from one compilation and the runner jar from another, any difference could be a
 * difference in the application rather than in the format.</p>
 *
 * <p>The two runner jars are built here by calling {@link RunnerJarBuilder} directly rather than by asking
 * the Gradle plugin for them. Not because the plugin is in doubt - the end-to-end test suite covers that -
 * but because the plugin produces one compression mode per build and the benchmark needs two, from bytes
 * that are identical to the other four variants'.</p>
 *
 * <h2>Failure is data</h2>
 * <p>Every variant is built inside its own try/catch. One that fails becomes an unavailable
 * {@link Variant} carrying the reason, and the run carries on with the rest.</p>
 *
 * <h2>Every variant is self-contained</h2>
 * <p>Nothing that gets measured is read out of the sample's own {@code build} directory. The class files,
 * the dependency jars and the shaded jar are all copied into the harness's artifacts directory first, and
 * the commands point only at those copies. The reason is not tidiness: a benchmark run takes minutes, and
 * anything else that builds the sample in that window - a developer, the end-to-end test suite, a second
 * agent - runs {@code clean} and takes the artifacts out from under a run in flight. That failure mode is
 * genuinely confusing when it happens ("the jar was there when I checked"), and copying makes it
 * impossible.</p>
 */
final class SampleBuild {

    private static final String EXPLODED_CLASSPATH = "exploded-cp";
    private static final String THIN_JAR = "thin-jar";
    private static final String SHADOW = "shadow";
    private static final String SHADOW_AOT = "shadow-aot";
    private static final String RUNNER_STORED = "runner-stored";
    private static final String RUNNER_STORED_CDS = "runner-stored-cds";
    private static final String RUNNER_STORED_REFLECTION = "runner-stored-reflection";
    private static final String RUNNER_PRESERVE = "runner-preserve";
    private static final String RUNNER_PRESERVE_REFLECTION = "runner-preserve-reflection";
    private static final String RUNNER_EXTRACTED = "runner-extracted";
    private static final String RUNNER_EXTRACTED_AOT = "runner-extracted-aot";

    private static final String GENERATED_ENTRY_STUB = "io.micronaut.runner.generated.AppEntry";

    /** The task the init script registers on the sample's build. */
    private static final String METADATA_TASK = "runnerBenchmarkMetadata";

    /** Where that task writes, relative to the sample's project directory. */
    private static final String METADATA_FILE = "build/runner-benchmark-metadata.txt";

    /** The init script, carried as a resource of this module. */
    private static final String INIT_SCRIPT = "/io/micronaut/runner/benchmarks/sample-metadata.init.gradle";

    /** How long the sample's Gradle build may take; it resolves the whole Micronaut platform. */
    private static final long BUILD_TIMEOUT_MINUTES = 30;

    /** How long the extraction of a runner jar may take. */
    private static final long EXTRACT_TIMEOUT_SECONDS = 120;

    /** How long cache training, workload, verification and normal termination may take. */
    private static final long CDS_TIMEOUT_SECONDS = 120;

    private final Path sample;
    private final Path artifacts;
    private final PrintStream log;
    private final String mainClass;
    private final String projectName;
    private final List<Path> applicationOutput;
    private final List<Path> dependencies;
    private final Path shadowJar;

    private SampleBuild(Path sample,
                        Path artifacts,
                        PrintStream log,
                        Metadata metadata) {
        this.sample = sample;
        this.artifacts = artifacts;
        this.log = log;
        this.mainClass = metadata.mainClass();
        this.projectName = metadata.projectName();
        this.applicationOutput = metadata.applicationOutput();
        this.dependencies = metadata.dependencies();
        this.shadowJar = metadata.shadowJar();
    }

    /**
     * Builds the sample and reads back what it produced.
     *
     * @param sample    the sample's project directory
     * @param repo      the Maven repository the runner plugins are published to, as a URI string
     * @param version   the version they were published under
     * @param artifacts where the variants' artifacts are written
     * @param log       where build progress goes
     * @return the prepared build
     * @throws IOException          if the build fails, times out, or writes no metadata
     * @throws InterruptedException if the wait is interrupted
     */
    static SampleBuild prepare(Path sample, String repo, String version, Path artifacts, PrintStream log)
            throws IOException, InterruptedException {
        Path init = artifacts.resolve("sample-metadata.init.gradle");
        Files.createDirectories(artifacts);
        try (InputStream in = SampleBuild.class.getResourceAsStream(INIT_SCRIPT)) {
            if (in == null) {
                throw new IOException("The init script " + INIT_SCRIPT + " is not on the class path");
            }
            Files.copy(in, init, StandardCopyOption.REPLACE_EXISTING);
        }

        Path gradlew = findGradlew(sample);
        List<String> command = List.of(
                gradlew.toString(),
                "--project-dir", sample.toAbsolutePath().toString(),
                "-Prunner.repo=" + repo,
                "-Prunner.version=" + version,
                "--init-script", init.toAbsolutePath().toString(),
                // The init script's task reads the project at execution time, which a configuration cache
                // would refuse. Nothing here is hot enough to want the cache.
                "--no-configuration-cache",
                "--stacktrace",
                METADATA_TASK,
                "shadowJar");
        log.println("[startup-benchmark] building the sample: " + String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(sample.toFile())
                .redirectErrorStream(true);
        // The nested build must run on the same JDK as this harness, which is the JDK the packaged
        // applications will be started with.
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread drain = drain(process, output);
        boolean finished = process.waitFor(BUILD_TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("The sample build did not finish within " + BUILD_TIMEOUT_MINUTES
                    + " minutes" + tail(output));
        }
        drain.join(5_000);
        if (process.exitValue() != 0) {
            throw new IOException("The sample build failed with status " + process.exitValue() + tail(output));
        }

        Path metadataFile = sample.resolve(METADATA_FILE);
        if (!Files.isRegularFile(metadataFile)) {
            throw new IOException("The sample build produced no " + metadataFile + tail(output));
        }
        Metadata metadata = Metadata.read(metadataFile);
        log.println("[startup-benchmark] sample built: " + metadata.dependencies().size()
                + " dependency jars, main class " + metadata.mainClass());
        return new SampleBuild(sample, artifacts, log, metadata);
    }

    /**
     * The sample's project directory.
     *
     * @return the directory
     */
    Path sample() {
        return sample;
    }

    /**
     * Names every variant in report order without building their artifacts.
     *
     * @return the canonical variant names
     */
    static List<String> variantNames() {
        return List.of(EXPLODED_CLASSPATH, THIN_JAR, SHADOW, SHADOW_AOT,
                RUNNER_STORED, RUNNER_STORED_CDS, RUNNER_STORED_REFLECTION,
                RUNNER_PRESERVE, RUNNER_PRESERVE_REFLECTION,
                RUNNER_EXTRACTED, RUNNER_EXTRACTED_AOT);
    }

    /** Keeps the complete required matrix visible when the shared sample build fails. */
    static List<Variant> unavailableVariants(String reason) {
        return List.of(
                Variant.unavailable(EXPLODED_CLASSPATH,
                        "Class files and dependency jars on an explicit, ordered -cp", reason),
                Variant.unavailable(THIN_JAR,
                        "Application jar with a Class-Path manifest pointing at lib/", reason),
                Variant.unavailable(SHADOW,
                        "Everything flattened into one jar by the Shadow plugin", reason),
                Variant.unavailable(SHADOW_AOT,
                        "The same Shadow jar with a verified built-in-loader JDK AOT cache", reason),
                Variant.unavailable(RUNNER_STORED,
                        "Runner jar, nested dependencies re-packed uncompressed; plugin-default entry stub", reason),
                Variant.unavailable(RUNNER_STORED_CDS,
                        "The same default-entry Runner jar with a verified, strict CDS archive", reason),
                Variant.unavailable(RUNNER_STORED_REFLECTION,
                        "Runner jar, nested dependencies re-packed uncompressed; reflection ablation", reason),
                Variant.unavailable(RUNNER_PRESERVE,
                        "Runner jar, nested dependencies copied byte for byte; plugin-default entry stub", reason),
                Variant.unavailable(RUNNER_PRESERVE_REFLECTION,
                        "Runner jar, nested dependencies copied byte for byte; reflection ablation", reason),
                Variant.unavailable(RUNNER_EXTRACTED,
                        "Runner jar unpacked and run by the JDK's own loader", reason),
                Variant.unavailable(RUNNER_EXTRACTED_AOT,
                        "The same extracted layout with a verified built-in-loader JDK AOT cache", reason));
    }

    /**
     * Builds every variant, in report order.
     *
     * @return the variants, available and unavailable alike
     */
    List<Variant> variants() {
        List<Variant> variants = new ArrayList<>(variantNames().size());
        variants.add(attempt(EXPLODED_CLASSPATH,
                "Class files and dependency jars on an explicit, ordered -cp",
                this::explodedClasspath));
        variants.add(attempt(THIN_JAR,
                "Application jar with a Class-Path manifest pointing at lib/",
                this::thinJar));
        Variant shadow = attempt(SHADOW,
                "Everything flattened into one jar by the Shadow plugin",
                this::shadowJar);
        variants.add(shadow);
        variants.add(attempt(SHADOW_AOT,
                "The same Shadow jar with a verified built-in-loader JDK AOT cache",
                () -> AotCache.prepare(shadow, SHADOW_AOT, aotRequest())));
        Variant stored = attempt(RUNNER_STORED,
                "Runner jar, nested dependencies re-packed uncompressed; plugin-default entry stub",
                () -> runnerJar(RUNNER_STORED, Compression.STORED, EntryMode.STUB));
        variants.add(stored);
        variants.add(attempt(RUNNER_STORED_CDS,
                "The same default-entry Runner jar with a verified, strict CDS archive",
                () -> CdsCache.prepare(stored, RUNNER_STORED_CDS, new CdsCache.Request(
                        artifacts.resolve("managed-cds"), "/hello", List.of("/hello"),
                        "/cds-training/stop", java.time.Duration.ofSeconds(CDS_TIMEOUT_SECONDS),
                        mainClass, List.of(), log))));
        variants.add(attempt(RUNNER_STORED_REFLECTION,
                "Runner jar, nested dependencies re-packed uncompressed; reflection ablation",
                () -> runnerJar(RUNNER_STORED_REFLECTION, Compression.STORED, EntryMode.REFLECTION)));
        variants.add(attempt(RUNNER_PRESERVE,
                "Runner jar, nested dependencies copied byte for byte; plugin-default entry stub",
                () -> runnerJar(RUNNER_PRESERVE, Compression.PRESERVE, EntryMode.STUB)));
        variants.add(attempt(RUNNER_PRESERVE_REFLECTION,
                "Runner jar, nested dependencies copied byte for byte; reflection ablation",
                () -> runnerJar(RUNNER_PRESERVE_REFLECTION, Compression.PRESERVE, EntryMode.REFLECTION)));
        Variant extracted = attempt(RUNNER_EXTRACTED,
                "Runner jar unpacked with -Dmicronaut.runner.mode=extract, run by the JDK's own loader",
                () -> extracted(stored));
        variants.add(extracted);
        variants.add(attempt(RUNNER_EXTRACTED_AOT,
                "The same extracted layout with a verified built-in-loader JDK AOT cache",
                () -> AotCache.prepare(extracted, RUNNER_EXTRACTED_AOT, aotRequest())));
        return variants;
    }

    private AotCache.Request aotRequest() {
        return new AotCache.Request(artifacts.resolve("managed-aot"), "/hello", List.of("/hello"),
                "/cds-training/stop", java.time.Duration.ofSeconds(CDS_TIMEOUT_SECONDS),
                mainClass, List.of(), log);
    }

    private Variant attempt(String name, String description, VariantFactory factory) {
        try {
            Variant variant = factory.create();
            log.println("[startup-benchmark] prepared " + name);
            return variant;
        } catch (Exception e) {
            String reason = oneLine(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.println("[startup-benchmark] " + name + " is unavailable: " + reason);
            return Variant.unavailable(name, description, reason);
        }
    }

    private Variant explodedClasspath() throws IOException {
        Path directory = recreate(artifacts.resolve("exploded"));
        List<Path> application = new ArrayList<>(applicationOutput.size());
        List<String> classPath = new ArrayList<>(applicationOutput.size() + dependencies.size());
        for (int i = 0; i < applicationOutput.size(); i++) {
            Path target = directory.resolve("app-" + i);
            copyDirectory(applicationOutput.get(i), target);
            application.add(target);
            classPath.add(target.toAbsolutePath().toString());
        }
        List<Path> dependencyCopies = copyDependenciesTo(directory.resolve("lib"));
        for (Path dependency : dependencyCopies) {
            classPath.add(dependency.toAbsolutePath().toString());
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-cp");
        command.add(String.join(java.io.File.pathSeparator, classPath));
        command.add(mainClass);
        DeploymentSize deploymentSize = DeploymentSize.measure(
                DeploymentSize.input("application", application),
                DeploymentSize.input("dependencies", dependencyCopies));
        List<Path> launchInputs = new ArrayList<>(application);
        launchInputs.addAll(dependencyCopies);
        return Variant.available(EXPLODED_CLASSPATH,
                "Class files and dependency jars on an explicit, ordered -cp",
                command, directory, directory, deploymentSize, launchInputs);
    }

    private Variant thinJar() throws IOException {
        Path directory = recreate(artifacts.resolve("thin"));
        List<String> classPath = new ArrayList<>(dependencies.size());
        List<Path> dependencyCopies = copyDependenciesTo(directory.resolve("lib"));
        for (Path copy : dependencyCopies) {
            classPath.add("lib/" + encodeClassPathEntry(copy.getFileName().toString()));
        }

        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(Attributes.Name.MAIN_CLASS, mainClass);
        main.put(Attributes.Name.CLASS_PATH, String.join(" ", classPath));

        Path jar = directory.resolve(projectName + "-thin.jar");
        Set<String> written = new LinkedHashSet<>();
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            written.add("META-INF/MANIFEST.MF");
            for (Path root : applicationOutput) {
                copyTree(root, jarOut, written);
            }
        }

        List<String> command = List.of(javaExecutable().toString(), "-jar", jar.toAbsolutePath().toString());
        DeploymentSize deploymentSize = DeploymentSize.measure(
                DeploymentSize.input("application", jar),
                DeploymentSize.input("dependencies", dependencyCopies));
        List<Path> launchInputs = new ArrayList<>();
        launchInputs.add(jar);
        launchInputs.addAll(dependencyCopies);
        return Variant.available(THIN_JAR,
                "Application jar with a Class-Path manifest pointing at lib/",
                command, directory, jar, deploymentSize, launchInputs);
    }

    private Variant shadowJar() throws IOException {
        if (shadowJar == null) {
            throw new IOException("The sample's build declares no shadowJar task");
        }
        if (!Files.isRegularFile(shadowJar)) {
            throw new IOException("The Shadow plugin produced no " + shadowJar);
        }
        Path directory = recreate(artifacts.resolve("shadow"));
        Path copy = directory.resolve(shadowJar.getFileName().toString());
        Files.copy(shadowJar, copy, StandardCopyOption.REPLACE_EXISTING);
        List<String> command = List.of(javaExecutable().toString(), "-jar",
                copy.toAbsolutePath().toString());
        DeploymentSize deploymentSize = DeploymentSize.measure(DeploymentSize.input("archive", copy));
        return Variant.available(SHADOW,
                "Everything flattened into one jar by the Shadow plugin",
                command, directory, copy, deploymentSize);
    }

    private Variant runnerJar(String name, Compression compression, EntryMode requestedEntryMode) throws IOException {
        return runnerJar(artifacts, name, mainClass, applicationOutput, dependencies,
                compression, requestedEntryMode);
    }

    static Variant runnerJar(Path artifacts,
                             String name,
                             String mainClass,
                             List<Path> applicationOutput,
                             List<Path> dependencies,
                             Compression compression,
                             EntryMode requestedEntryMode) throws IOException {
        Path output = artifacts.resolve(name + ".jar");
        Files.deleteIfExists(output);
        RunnerJarSpec spec = RunnerJarSpec.builder()
                .mainClass(mainClass)
                .applicationOutput(applicationOutput)
                .dependencies(dependencies.stream().map(Dependency::of).toList())
                .output(output)
                .compression(compression)
                .entryStub(requestedEntryMode == EntryMode.STUB)
                .build();
        RunnerJarBuilder.build(spec, BuildLogger.noOp());
        EntryMode effectiveEntryMode = inspectEntryMode(output, requestedEntryMode);
        List<String> command = List.of(javaExecutable().toString(), "-jar",
                output.toAbsolutePath().toString());
        DeploymentSize deploymentSize = DeploymentSize.measure(DeploymentSize.input("archive", output));
        return Variant.available(name,
                (compression == Compression.STORED
                        ? "Runner jar, nested dependencies re-packed uncompressed"
                        : "Runner jar, nested dependencies copied byte for byte")
                        + (requestedEntryMode == EntryMode.STUB
                        ? "; plugin-default entry stub" : "; reflection ablation"),
                command, artifacts, output, deploymentSize, requestedEntryMode, effectiveEntryMode);
    }

    private static EntryMode inspectEntryMode(Path output, EntryMode requestedEntryMode) throws IOException {
        try (RunnerJarReader reader = RunnerJarReader.open(output)) {
            String indexedStub = reader.index().entryStubClass();
            boolean generatedClassPresent = reader.index().findClass(GENERATED_ENTRY_STUB)
                    != IndexFormat.NO_INDEX;
            if (requestedEntryMode == EntryMode.STUB) {
                if (!GENERATED_ENTRY_STUB.equals(indexedStub) || !generatedClassPresent) {
                    throw new IOException("entry stub was requested, but " + output
                            + " does not both contain and index the generated class " + GENERATED_ENTRY_STUB);
                }
                return EntryMode.STUB;
            }
            if (indexedStub != null || generatedClassPresent) {
                throw new IOException("reflection was requested, but " + output + " contains or indexes "
                        + GENERATED_ENTRY_STUB);
            }
            return EntryMode.REFLECTION;
        }
    }

    private Variant extracted(Variant stored) throws IOException, InterruptedException {
        return extractedRunner(artifacts, stored, RUNNER_EXTRACTED);
    }

    static Variant extractedRunner(Path artifacts, Variant stored, String name)
            throws IOException, InterruptedException {
        if (!stored.available()) {
            throw new IOException("there is no runner jar to extract: " + stored.unavailableReason());
        }
        Path destination = artifacts.resolve("extracted");
        deleteRecursively(destination);
        List<String> command = List.of(
                javaExecutable().toString(),
                "-Dmicronaut.runner.mode=extract",
                "-jar", stored.artifact().toAbsolutePath().toString(),
                "--destination", destination.toAbsolutePath().toString(),
                "--force");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(artifacts.toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread drain = drain(process, output);
        if (!process.waitFor(EXTRACT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("extraction did not finish within " + EXTRACT_TIMEOUT_SECONDS + "s");
        }
        drain.join(5_000);
        if (process.exitValue() != 0) {
            throw new IOException("extraction failed with status " + process.exitValue() + tail(output));
        }
        Path applicationJar = singleJarIn(destination);
        List<String> run = List.of(javaExecutable().toString(), "-jar",
                applicationJar.toAbsolutePath().toString());
        DeploymentSize deploymentSize = DeploymentSize.measure(
                DeploymentSize.input("extracted-layout", destination));
        List<Path> launchInputs = manifestClassPath(applicationJar);
        return Variant.available(name,
                "Runner jar unpacked with -Dmicronaut.runner.mode=extract, run by the JDK's own loader",
                run, destination, destination, deploymentSize, launchInputs);
    }

    private static List<Path> manifestClassPath(Path applicationJar) throws IOException {
        List<Path> inputs = new ArrayList<>();
        inputs.add(applicationJar);
        try (JarFile jar = new JarFile(applicationJar.toFile())) {
            Manifest manifest = jar.getManifest();
            String classPath = manifest == null ? null
                    : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null || classPath.isBlank()) {
                return List.copyOf(inputs);
            }
            for (String entry : classPath.trim().split("\\s+")) {
                java.net.URI resolved = applicationJar.toUri().resolve(entry);
                if (!"file".equalsIgnoreCase(resolved.getScheme())) {
                    throw new IOException("extracted manifest Class-Path entry is not a file URI: " + entry);
                }
                Path input = Path.of(resolved).toAbsolutePath().normalize();
                if (!Files.isRegularFile(input)) {
                    throw new IOException("extracted manifest Class-Path entry does not exist: " + entry);
                }
                inputs.add(input);
            }
        }
        return List.copyOf(inputs);
    }

    /**
     * Copies the dependency jars into one directory, keeping class path order and de-duplicating names.
     *
     * @param lib the directory to fill, created if it is not there
     * @return the copies, in class path order
     * @throws IOException if a jar cannot be copied
     */
    private List<Path> copyDependenciesTo(Path lib) throws IOException {
        Files.createDirectories(lib);
        List<Path> copies = new ArrayList<>(dependencies.size());
        Set<String> used = new LinkedHashSet<>();
        for (int i = 0; i < dependencies.size(); i++) {
            Path dependency = dependencies.get(i);
            String fileName = dependency.getFileName().toString();
            if (!used.add(fileName)) {
                // Two jars with the same file name from different groups. Both have to survive, and the
                // one that arrived second keeps its position on the class path.
                fileName = i + "-" + fileName;
                used.add(fileName);
            }
            Path copy = lib.resolve(fileName);
            Files.copy(dependency, copy, StandardCopyOption.REPLACE_EXISTING);
            copies.add(copy);
        }
        return copies;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(source)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        for (Path file : files) {
            Path destination = target.resolve(source.relativize(file).toString());
            Files.createDirectories(destination.getParent());
            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path singleJarIn(Path directory) throws IOException {
        List<Path> jars;
        try (var stream = Files.list(directory)) {
            jars = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        if (jars.size() != 1) {
            throw new IOException("expected exactly one jar directly under " + directory
                    + ", found " + jars.size());
        }
        return jars.get(0);
    }

    private static void copyTree(Path root, JarOutputStream out, Set<String> written) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        if (Files.isRegularFile(root)) {
            throw new IOException(root + " is a jar, not a directory; the harness packages exploded"
                    + " application output only");
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        for (Path file : files) {
            String name = root.relativize(file).toString().replace('\\', '/');
            if (name.equals("META-INF/MANIFEST.MF")) {
                // The thin jar's manifest is the one this class wrote; the application's own would
                // overwrite Main-Class and Class-Path.
                continue;
            }
            // Directory entries matter: Micronaut lists META-INF/micronaut/ with getResources, and a jar
            // without directory entries answers that listing with nothing at all.
            int slash = 0;
            while ((slash = name.indexOf('/', slash + 1)) > 0) {
                String directory = name.substring(0, slash + 1);
                if (written.add(directory)) {
                    out.putNextEntry(new ZipEntry(directory));
                    out.closeEntry();
                }
            }
            if (!written.add(name)) {
                continue;
            }
            out.putNextEntry(new ZipEntry(name));
            Files.copy(file, out);
            out.closeEntry();
        }
    }

    private static String encodeClassPathEntry(String fileName) {
        byte[] bytes = fileName.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte value : bytes) {
            int c = value & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(hex[c >>> 4]).append(hex[c & 0x0f]);
            }
        }
        return encoded.toString();
    }

    /**
     * The {@code java} of the JDK running this harness, which is the JDK every variant is started with.
     *
     * @return the executable
     */
    static Path javaExecutable() {
        Path home = Path.of(System.getProperty("java.home"));
        Path candidate = home.resolve("bin").resolve("java");
        if (!Files.isExecutable(candidate)) {
            candidate = home.resolve("bin").resolve("java.exe");
        }
        return candidate;
    }

    private static Path findGradlew(Path sample) throws IOException {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "gradlew.bat" : "gradlew";
        Path directory = sample.toAbsolutePath().normalize();
        while (directory != null) {
            Path candidate = directory.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IOException("No " + name + " above " + sample + "; the harness drives the sample's"
                + " build with the repository's own wrapper");
    }

    private static Thread drain(Process process, StringBuilder into) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream in = process.getInputStream()) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    synchronized (into) {
                        into.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException e) {
                synchronized (into) {
                    into.append("\n[output capture stopped: ").append(e).append(']');
                }
            }
        }, "sample-build-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String tail(StringBuilder output) {
        String text;
        synchronized (output) {
            text = output.toString();
        }
        String[] lines = text.split("\n");
        int from = Math.max(0, lines.length - 40);
        StringBuilder result = new StringBuilder("\n--- last ").append(lines.length - from)
                .append(" lines ---\n");
        for (int i = from; i < lines.length; i++) {
            result.append(lines[i]).append('\n');
        }
        return result.toString();
    }

    private static String oneLine(String message) {
        return message == null ? "no message" : message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static Path recreate(Path directory) throws IOException {
        deleteRecursively(directory);
        return Files.createDirectories(directory);
    }

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
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Builds one variant, or explains why it cannot. */
    @FunctionalInterface
    private interface VariantFactory {
        Variant create() throws Exception;
    }

    /** What the init script's task wrote: the application's class path, in order. */
    private record Metadata(String projectName,
                            String projectVersion,
                            String mainClass,
                            List<Path> applicationOutput,
                            List<Path> dependencies,
                            Path shadowJar) {

        static Metadata read(Path file) throws IOException {
            String projectName = "application";
            String projectVersion = "";
            String mainClass = null;
            List<Path> applicationOutput = new ArrayList<>();
            List<Path> dependencies = new ArrayList<>();
            Path shadowJar = null;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int separator = line.indexOf('=');
                if (separator < 0) {
                    continue;
                }
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                switch (key) {
                    case "projectName" -> projectName = value;
                    case "projectVersion" -> projectVersion = value;
                    case "mainClass" -> mainClass = value;
                    case "classes", "resources" -> applicationOutput.add(Path.of(value));
                    case "dependency" -> dependencies.add(Path.of(value));
                    case "shadowJar" -> shadowJar = Path.of(value);
                    default -> {
                    }
                }
            }
            if (mainClass == null) {
                throw new IOException(file + " names no main class");
            }
            List<Path> existing = applicationOutput.stream().filter(Files::exists).toList();
            if (existing.isEmpty()) {
                throw new IOException(file + " names no application output that exists");
            }
            if (dependencies.isEmpty()) {
                throw new IOException(file + " names no dependencies");
            }
            return new Metadata(projectName, projectVersion, mainClass, existing,
                    List.copyOf(dependencies), shadowJar);
        }
    }
}
