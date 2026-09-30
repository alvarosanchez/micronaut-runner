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

import io.micronaut.core.annotation.Experimental;
import io.micronaut.runner.build.aotcache.AotCacheBuilder;
import io.micronaut.runner.build.aotcache.AotCacheReport;
import io.micronaut.runner.build.aotcache.AotCacheSettings;
import io.micronaut.runner.build.aotcache.AotLaunchOptions;
import io.micronaut.runner.build.training.TrainingSettings;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Builds the self-contained directory of a cached deployment from a Runner JAR: the program, a verified JDK AOT
 * cache trained in place, the launch argfile, the identity file, the report and the logs. It is what the build
 * plugins call.
 *
 * <p>With {@link AotTarget#LAYOUT} the program is the extracted layout ({@link AotLayout}), which every JDK
 * caches in full. With {@link AotTarget#SINGLE_JAR} it is a copy of the Runner JAR with its modification time
 * kept; its coverage is reported but not enforced, and a warning says when the JDK did not cache the classes
 * {@code RunnerClassLoader} defines.</p>
 *
 * <p>From the directory the application launches as {@code java @app.jvmopts -jar <jar>}. The cache is valid
 * only as long as the JARs keep their size and modification time, so a copy of the directory keeps its times,
 * as {@code cp -p} and Docker {@code COPY} do.</p>
 *
 * @since 1.0
 */
@Experimental
public final class AotCacheOutput {

    /** The label of the identity file and the report that names the target. */
    public static final String TARGET_LABEL = "target";

    /** What the single-JAR target warns when the JDK left most of the Runner JAR's classes out of the cache. */
    public static final String SINGLE_JAR_WARNING = "This JDK does not cache classes defined by RunnerClassLoader:"
            + " JDK 27 and later archive only classes from file: code sources (JDK-8380291). Use the layout target.";

    private AotCacheOutput() {
    }

    /**
     * Stages the program in a directory, replacing what the directory held, trains and verifies the cache.
     *
     * @param target    what to train the cache for
     * @param settings  the cache settings; coverage is enforced for {@link AotTarget#LAYOUT} only, whatever
     *                  they say
     * @param java      the {@code java} executable to train with, of the exact JDK build production runs
     * @param runnerJar the Runner JAR
     * @param out       the output directory, replaced as a whole
     * @param training  how to reach, exercise and stop the application
     * @param log       where the phases are reported
     * @return the gate's report
     * @throws IOException          if staging, training or verification fails; the cache and the argfile are
     *                              deleted then, and the logs stay
     * @throws InterruptedException if the thread is interrupted; every process has been reaped by then
     */
    public static AotCacheReport write(AotTarget target,
                                       AotCacheSettings settings,
                                       Path java,
                                       Path runnerJar,
                                       Path out,
                                       TrainingSettings training,
                                       BuildLogger log) throws IOException, InterruptedException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(log, "log");
        // Every fork runs in the output directory or its parent, where a relative path would name another file.
        Path javaExecutable = java.toAbsolutePath();
        Path directory = out.toAbsolutePath().normalize();
        Path archive = runnerJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(archive) || !RunnerJarReader.isRunnerJar(archive)) {
            throw new IOException(archive + " is not a Runner JAR, so there is nothing to train a JDK AOT cache for");
        }
        if (archive.startsWith(directory)) {
            throw new IllegalArgumentException("The Runner JAR " + archive + " must not be inside the output"
                    + " directory " + directory + ", which is replaced");
        }
        long started = System.nanoTime();
        String jarName;
        if (target == AotTarget.LAYOUT) {
            jarName = AotLayout.write(javaExecutable, archive, directory, AotLayout.DEFAULT_TIMEOUT).applicationJar()
                    .getFileName().toString();
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
            AotCacheReport report = AotCacheBuilder.build(settings.withEnforceCoverage(target == AotTarget.LAYOUT),
                    javaExecutable, directory, jarName, training, Map.of(TARGET_LABEL, target.value()), log);
            if (target == AotTarget.LAYOUT) {
                AotLayout.verify(directory, archive);
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
     * The single-JAR target's check of what the cache holds: when fewer than half of the {@code io.micronaut}
     * classes the smoke launch loaded came from the cache, the JDK did not cache the classes
     * {@code RunnerClassLoader} defines, so this logs {@link #SINGLE_JAR_WARNING}, adds it to the report and
     * writes the report again. The measurement decides, not the JDK version.
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
