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

import io.micronaut.core.annotation.Internal;
import io.micronaut.runner.RunnerClassLoader;
import io.micronaut.runner.build.training.TrainingSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Builds the self-contained directory of a cached deployment from a Runner JAR: the program, a verified JDK AOT
 * cache trained in place, the launch argfile, the identity file, the report and the logs. It is what the build
 * plugins call.
 *
 * <p>With {@link AotTarget#LAYOUT} the program is the extracted layout ({@link AotLayout}), which every JDK
 * caches in full. It is extracted from the layout-source JAR ({@link AotLayout#sourceSpec(RunnerJarSpec, Path)}),
 * which keeps every lambda, so the cache matches that layout only, never an extract of a shipped Runner JAR that
 * desugars lambdas. With {@link AotTarget#SINGLE_JAR} it is a copy of the Runner JAR with its modification time
 * kept, and its recording launch, and no other launch, runs with {@code -Dmicronaut.runner.aot.training=true}:
 * the classes {@code RunnerClassLoader} defines then report the archive's {@code file:} URL, which JDK 27 and
 * later require to cache them (JDK-8380291). Its coverage is reported but not enforced, and a warning says when
 * the JDK still did not cache most of those classes.</p>
 *
 * <p>The identity file and the report record the SHA-256 of the Runner JAR the build ships as
 * {@code runnerJarSha256}, and the layout target's also that of the JAR the layout was extracted from as
 * {@code layoutSourceSha256}, so a cache can be traced to both.</p>
 *
 * <p>From the directory the application launches as {@code java @app.jvmopts -jar <jar>}. The cache is valid
 * only as long as the JARs keep their size and modification time, so a copy of the directory keeps its times,
 * as {@code cp -p} and Docker {@code COPY} do.</p>
 *
 * <p>The cache engine behind it trains, verifies and describes a JDK AOT cache (JEP 483, JEP 514) for any
 * application launched with {@code java -jar}: a two-step recording and creation through the
 * {@code TrainingDriver}, a verification gate, the launch argfile and the identity file. That engine,
 * {@code AotCacheBuilder}, {@code AotCacheGate}, {@code AotCacheReport}, {@code AotCacheSettings},
 * {@code AotLaunchOptions}, {@code Forks} and {@code JdkProbe}, knows nothing about the Runner JAR, although it
 * shares this package with it: it takes a directory and the name of the JAR to launch in it, and it uses
 * {@code java.base}, {@link BuildLogger}, {@link TrainingSettings} and the {@code TrainingDriver} only, which a
 * test checks in the class files. Runner-only; the Micronaut Docker support trains inside images with
 * plugin-local scripts (micronaut-projects/micronaut-gradle-plugin#1379,
 * micronaut-projects/micronaut-maven-plugin#1721).</p>
 *
 * <p>Internal to Runner's interim build plugins, which call {@link #write}: it may change in any release. Of the
 * engine, {@link AotCacheSettings} with its {@code Builder} and {@link AotCacheReport} are public for the plugins,
 * and {@link JdkProbe} and {@link AotCacheGate} with its {@code Coverage} for Runner's benchmarks. Each of them is
 * {@code @Internal}, and the rest of the engine is package-private.</p>
 *
 * @since 1.0
 */
@Internal
public final class AotCacheOutput {

    /** The label of the identity file and the report that names the target. */
    static final String TARGET_LABEL = "target";

    /**
     * What the single-JAR target's recording launch adds, and no other launch: the launcher's AOT training mode,
     * {@link RunnerClassLoader#AOT_TRAINING_PROPERTY}.
     */
    static final String AOT_TRAINING_ARGUMENT = "-D" + RunnerClassLoader.AOT_TRAINING_PROPERTY + "=true";

    /** What the single-JAR target warns when the JDK left most of the Runner JAR's classes out of the cache. */
    static final String SINGLE_JAR_WARNING = "Fewer than half of the io.micronaut classes came from the cache,"
            + " although the training run reported file: code sources, as JDK 27 and later require (JDK-8380291)."
            + " The cache left out most of the classes RunnerClassLoader defines; use the layout target.";

    /** The label that holds the SHA-256 of the Runner JAR the build ships, in hexadecimal. */
    static final String RUNNER_JAR_LABEL = "runnerJarSha256";

    /** The layout target's label that holds the SHA-256 of the JAR the layout was extracted from, in hexadecimal. */
    static final String LAYOUT_SOURCE_LABEL = "layoutSourceSha256";

    /** The JVM argument that sets the training property without a value, which production must never get. */
    private static final String AOT_TRAINING_DEFINE = "-D" + RunnerClassLoader.AOT_TRAINING_PROPERTY;

    private AotCacheOutput() {
    }

    /**
     * Stages the program in a directory, replacing what the directory held, trains and verifies the cache.
     *
     * @param target       what to train the cache for
     * @param settings     the cache settings; coverage is enforced for {@link AotTarget#LAYOUT} only, whatever
     *                     they say
     * @param java         the {@code java} executable to train with, of the exact JDK build production runs
     * @param runnerJar    the Runner JAR the build ships, which the single-JAR target copies
     * @param layoutSource the Runner JAR the layout target extracts: the one built from
     *                     {@link AotLayout#sourceSpec(RunnerJarSpec, Path)} of the shipped JAR's spec, or
     *                     {@code runnerJar} itself when that is empty. The single-JAR target ignores it
     * @param out          the output directory, replaced as a whole
     * @param training     how to reach, exercise and stop the application
     * @param log          where the phases are reported
     * @return the gate's report
     * @throws IOException              if staging, training or verification fails; the cache and the argfile are
     *                                  deleted then, and the logs stay
     * @throws IllegalArgumentException if the settings' {@code jvmArgs} set {@code micronaut.runner.aot.training},
     *                                  which would reach {@code app.jvmopts}; nothing is staged then
     * @throws InterruptedException     if the thread is interrupted; every process has been reaped by then
     */
    public static AotCacheReport write(AotTarget target,
                                       AotCacheSettings settings,
                                       Path java,
                                       Path runnerJar,
                                       Path layoutSource,
                                       Path out,
                                       TrainingSettings training,
                                       BuildLogger log) throws IOException, InterruptedException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(log, "log");
        requireNoTrainingProperty(settings);
        // Every fork runs in the output directory or its parent, where a relative path would name another file.
        Path javaExecutable = java.toAbsolutePath();
        Path directory = out.toAbsolutePath().normalize();
        Path archive = runnerJar(runnerJar, directory);
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(TARGET_LABEL, target.value());
        labels.put(RUNNER_JAR_LABEL, sha256(archive));
        long started = System.nanoTime();
        String jarName;
        Path source = archive;
        if (target == AotTarget.LAYOUT) {
            source = runnerJar(layoutSource, directory);
            labels.put(LAYOUT_SOURCE_LABEL, sha256(source));
            jarName = AotLayout.write(javaExecutable, source, directory, AotLayout.DEFAULT_TIMEOUT).applicationJar()
                    .getFileName().toString();
            if (!source.equals(archive)) {
                log.info("JDK AOT cache: the layout is extracted from " + source + ", which keeps every lambda, not"
                        + " from " + archive);
            }
        } else {
            deleteRecursively(directory);
            Files.createDirectories(directory);
            Path fileName = Objects.requireNonNull(archive.getFileName(), "the Runner JAR has no file name");
            jarName = fileName.toString();
            Files.copy(archive, directory.resolve(jarName), StandardCopyOption.COPY_ATTRIBUTES);
        }
        log.info("JDK AOT cache: staged " + target + " in " + directory + " ("
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms)");

        boolean complete = false;
        try {
            // The layout's classes load through the JDK's own loader from file: JARs, so it needs no training mode.
            List<String> recordOnly = target == AotTarget.SINGLE_JAR ? List.of(AOT_TRAINING_ARGUMENT) : List.of();
            AotCacheReport report = AotCacheBuilder.build(settings.withEnforceCoverage(target == AotTarget.LAYOUT),
                    javaExecutable, directory, jarName, training, recordOnly, labels, log);
            if (target == AotTarget.LAYOUT) {
                AotLayout.verify(directory, source);
            } else {
                report = warnUnlessRunnerClassesAreCached(report, directory, log);
            }
            complete = true;
            return report;
        } finally {
            if (!complete) {
                Files.deleteIfExists(directory.resolve(AotLaunchOptions.CACHE_FILE));
                Files.deleteIfExists(directory.resolve(AotLaunchOptions.ARGFILE));
            }
        }
    }

    /**
     * Checks that a file is a Runner JAR outside the output directory.
     *
     * @param jar       the file
     * @param directory the output directory, absolute and normalized
     * @return the file's absolute, normalized path
     * @throws IOException              if the file is not a Runner JAR
     * @throws IllegalArgumentException if it is inside the output directory, which is replaced
     */
    private static Path runnerJar(Path jar, Path directory) throws IOException {
        Path archive = jar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(archive) || !RunnerJarReader.isRunnerJar(archive)) {
            throw new IOException(archive + " is not a Runner JAR, so there is nothing to train a JDK AOT cache for");
        }
        if (archive.startsWith(directory)) {
            throw new IllegalArgumentException("The Runner JAR " + archive + " must not be inside the output"
                    + " directory " + directory + ", which is replaced");
        }
        return archive;
    }

    /**
     * The SHA-256 of a file's content, in lower-case hexadecimal.
     *
     * @param file the file
     * @return the digest
     * @throws IOException if the file cannot be read
     */
    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Refuses cache settings whose {@code jvmArgs} set the launcher's AOT training property, with or without a
     * value: those arguments go on every launch of the cache and into {@code app.jvmopts}, the options production
     * launches with, and the property belongs to the recording launch only, which the single-JAR target gives it.
     * A property whose name only starts with the same text, such as {@code micronaut.runner.aot.training.x}, is
     * another property and passes.
     *
     * @param settings the cache settings
     * @throws IllegalArgumentException if an argument sets the property
     */
    static void requireNoTrainingProperty(AotCacheSettings settings) {
        for (String argument : settings.jvmArgs()) {
            if (argument.equals(AOT_TRAINING_DEFINE) || argument.startsWith(AOT_TRAINING_DEFINE + "=")) {
                throw new IllegalArgumentException("jdkAotCache jvmArgs must not contain " + argument + ": they"
                        + " also go into " + AotLaunchOptions.ARGFILE + ", the options production launches with,"
                        + " and " + RunnerClassLoader.AOT_TRAINING_PROPERTY + " is for the recording launch only,"
                        + " which the singleJar target already runs with " + AOT_TRAINING_ARGUMENT);
            }
        }
    }

    /**
     * The single-JAR target's check of what the cache holds: when fewer than half of the {@code io.micronaut}
     * classes the smoke launch loaded came from the cache, the JDK did not cache the classes
     * {@code RunnerClassLoader} defines, although the recording reported {@code file:} code sources, so this
     * logs {@link #SINGLE_JAR_WARNING}, adds it to the report and writes the report again. The measurement
     * decides, not the JDK version.
     *
     * @param report    the gate's report
     * @param directory the output directory, where the report is written
     * @param log       where the warning goes
     * @return the report, with the warning when it applies
     * @throws IOException if the report cannot be written
     */
    static AotCacheReport warnUnlessRunnerClassesAreCached(AotCacheReport report, Path directory, BuildLogger log)
            throws IOException {
        if (report.micronautFromCache() * 2L >= report.micronautLoaded()) {
            return report;
        }
        log.warn(SINGLE_JAR_WARNING);
        AotCacheReport warned = report.withWarning(SINGLE_JAR_WARNING);
        warned.write(directory);
        return warned;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(directory);
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
