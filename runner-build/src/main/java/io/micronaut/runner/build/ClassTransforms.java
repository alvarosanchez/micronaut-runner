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

import io.micronaut.aot.bytecode.ClassPathTransform;
import io.micronaut.runner.IndexFormat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * The class transforms of one build: which steps run, the {@link ClassPathModel} they share, and, once every
 * dependency is staged, what they did.
 *
 * <p>It belongs to the calling thread of {@link RunnerJarBuilder}. Only its {@link #pipeline()} reaches the stage
 * tasks, which share it read-only and hand their {@link ClassTransformPipeline.JarReport} back with their result;
 * the calling thread adds the reports in class-path order and logs them.</p>
 *
 * <p>The {@code stripLocalVariables} option runs Micronaut AOT's {@link ClassPathTransform} before any dependency
 * is staged, in every compression mode: a stage then reads the dependency's rewritten copy ({@link #sources()}).
 * What it did is logged and written into {@code transforms.txt} with the pipeline's steps.</p>
 */
final class ClassTransforms {

    /** The name of the stripping step in reports: the option's. */
    private static final String STRIP = RunnerJarOption.STRIP_LOCAL_VARIABLES.optionName();

    /** What warnings and the class path model call the application layer. */
    private static final String APPLICATION_LAYER = "the application output";

    /** What notes and {@code transforms.txt} call the application layer: its directory in the archive. */
    private static final String APPLICATION_NAME =
            IndexFormat.CLASSES_PREFIX.substring(0, IndexFormat.CLASSES_PREFIX.length() - 1);

    private ClassTransformPipeline pipeline;
    private final List<ClassTransformPipeline.JarReport> reports = new ArrayList<>();
    /** What every step did to each dependency, in class-path order, for {@code transforms.txt}. */
    private final List<ClassTransformPipeline.JarReport> described = new ArrayList<>();
    /** What the pipeline did to the application layer, first on the class path; {@code null} if it did not run. */
    private ClassTransformPipeline.JarReport applicationReport;
    /** What stripping did; {@code null} when it did not run. */
    private final Stripped stripped;
    /** The file each stage reads, by dependency position. */
    private final List<Path> sources;

    private ClassTransforms(ClassTransformPipeline pipeline, Stripped stripped, List<Path> sources) {
        this.pipeline = pipeline;
        this.stripped = stripped;
        this.sources = sources;
    }

    /**
     * Decides which class transforms run, runs Micronaut AOT's local-variable stripping when it is on, and scans
     * the class path that desugaring needs when it is on.
     *
     * <p>Stripping runs first, in every compression mode, over the dependencies that are not
     * {@linkplain Dependency#projectModule() project modules}, on at most {@code parallelism} threads of its own;
     * its warnings are reported at once. The pipeline's transforms run only in STORED and HYBRID: in PRESERVE every
     * dependency is nested byte for byte, which is reported at info when lambdas would be desugared, and the
     * application layer is left alone too. Without desugaring nothing is scanned, and the pipeline, if the
     * options need one, has no step. Otherwise one scan task per dependency runs on the pool, each through a
     * {@link ZipReader} of its own, while the calling thread scans the application layer, and the scans, which
     * record the member tables of every class, are merged in class-path order.</p>
     *
     * @param spec         the build's spec
     * @param dependencies the dependencies that are nested, in class-path order
     * @param work         the build's work directory, which holds the stripped copies until it is deleted
     * @param parallelism  the most threads stripping runs on; {@code 1} runs it on the calling thread
     * @param pool         the staging pool, or {@code null} to scan on the calling thread
     * @param application  scans the application layer, on the calling thread
     * @param logger       where to report that an option has no effect
     * @param warn         where to report what stripping warns about
     * @param options      the startup class ranks and the HYBRID flag every stage applies; ignored in PRESERVE
     * @return the build's transforms
     * @throws IOException if a dependency or an application class cannot be read, or a stripped copy cannot be
     *                     written; when several dependencies cannot be read, the failure of the first one on the
     *                     class path
     */
    static ClassTransforms prepare(RunnerJarSpec spec, List<Dependency> dependencies, Path work, int parallelism,
                                   ExecutorService pool, ApplicationClasses application, BuildLogger logger,
                                   Consumer<String> warn, ClassTransformPipeline.Options options)
            throws IOException {
        List<Path> sources = new ArrayList<>(dependencies.size());
        for (Dependency dependency : dependencies) {
            sources.add(dependency.path());
        }
        Stripped stripped = spec.stripLocalVariables() ? strip(spec, dependencies, work, parallelism, sources) : null;
        if (stripped != null) {
            stripped.result.warnings().forEach(warn);
        }
        boolean desugar = spec.desugarLambdas();
        if (spec.compression() == Compression.PRESERVE) {
            if (desugar) {
                logger.info("The " + LambdaDesugarer.NAME + " option has no effect with PRESERVE compression, which"
                        + " nests every dependency byte for byte");
            }
            return new ClassTransforms(null, stripped, sources);
        }
        if (!desugar) {
            return new ClassTransforms(options.any() ? ClassTransformPipeline.ordering(options) : null, stripped,
                    sources);
        }
        ClassPathModel model = scan(spec, dependencies, sources, pool, application);
        return new ClassTransforms(new ClassTransformPipeline(List.of(new LambdaDesugarer(model)), model, options),
                stripped, sources);
    }

    /**
     * Runs Micronaut AOT's local-variable stripping over the dependencies that are not project modules, against
     * the whole class path, and points {@code sources} at the copies it wrote.
     *
     * @return what it did, or {@code null} when every dependency is a project module
     */
    private static Stripped strip(RunnerJarSpec spec, List<Dependency> dependencies, Path work, int parallelism,
                                  List<Path> sources) throws IOException {
        List<Path> thirdParty = new ArrayList<>(dependencies.size());
        for (Dependency dependency : dependencies) {
            if (!dependency.projectModule()) {
                thirdParty.add(dependency.path());
            }
        }
        if (thirdParty.isEmpty()) {
            return null;
        }
        List<Path> classPath = new ArrayList<>(spec.applicationOutput());
        int first = classPath.size();
        classPath.addAll(sources);
        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(classPath)
                .outputDirectory(work.resolve("stripped"))
                .stripLocalVariables(thirdParty)
                .parallelism(parallelism)
                .build());
        List<ClassPathTransform.Result.Entry> entries = new ArrayList<>(Collections.nCopies(sources.size(), null));
        int next = 0;
        for (int position = 0; position < sources.size(); position++) {
            sources.set(position, result.classPath().get(first + position));
            // One entry per jar named to strip, in class-path order; none when a library reads the tables.
            if (!dependencies.get(position).projectModule() && next < result.entries().size()) {
                entries.set(position, result.entries().get(next++));
            }
        }
        return new Stripped(result, entries);
    }

    /**
     * Turns HYBRID off for the stages that follow, keeping every step and the startup class ranks, so that they
     * write the nested jars as STORED does with the same list. The builder calls it when a HYBRID staging found
     * no startup class in any dependency.
     */
    void storeColdClasses() {
        if (pipeline != null && pipeline.options().hybrid()) {
            pipeline = pipeline.withOptions(new ClassTransformPipeline.Options(pipeline.options().ranks(), false));
        }
    }

    /**
     * Runs the transforms that apply to the application layer, which no stage repacks: today desugaring
     * lambdas, whose nests are planned, rewritten and gated here, on the calling thread.
     *
     * @param classes the application layer's classes
     * @return what to write for each rewritten class, keyed by entry name, with the classes generated for it;
     * empty when no transform runs over the application layer
     * @throws IOException if an application class cannot be read
     */
    Map<String, ClassTransformPipeline.Planned> application(ClassTransformPipeline.JarClasses classes)
            throws IOException {
        if (pipeline == null || !pipeline.plansApplication()) {
            return Map.of();
        }
        ClassTransformPipeline.JarRun run = pipeline.start(
                new ClassTransformPipeline.Layer(APPLICATION_NAME, 0, true, false));
        run.plan(classes);
        Map<String, ClassTransformPipeline.Planned> rewritten = new HashMap<>();
        for (ClassTransformPipeline.ClassEntry entry : classes.classes()) {
            ClassTransformPipeline.Planned planned = run.planned(entry.name());
            if (planned == null) {
                run.pass(entry.name());
            } else {
                rewritten.put(entry.name(), planned);
            }
        }
        applicationReport = run.report();
        return rewritten;
    }

    /**
     * The pipeline the stages run.
     *
     * @return the pipeline, or {@code null} when no class transform runs
     */
    ClassTransformPipeline pipeline() {
        return pipeline;
    }

    /**
     * The file each stage reads: the dependency's stripped copy, or the dependency itself.
     *
     * @return one path per dependency, in class-path order
     */
    List<Path> sources() {
        return sources;
    }

    /**
     * Adds what the transforms did to one dependency, in class-path order.
     *
     * @param entryName the dependency's nested entry name
     * @param report    what the pipeline did to it, or {@code null} when its stage ran no pipeline
     */
    void add(String entryName, ClassTransformPipeline.JarReport report) {
        ClassPathTransform.Result.Entry entry = stripped == null ? null : stripped.entries.get(described.size());
        if (report != null) {
            reports.add(report);
        }
        if (entry == null) {
            described.add(report);
            return;
        }
        List<ClassTransformPipeline.StepCount> counts = new ArrayList<>();
        counts.add(new ClassTransformPipeline.StepCount(STRIP, entry.classesStripped(),
                entry.classesUnchanged(), entry.fallbacks(), entry.bytesSaved()));
        List<String> notes = new ArrayList<>();
        for (String note : entry.notes()) {
            // The note names the jar by its path; transforms.txt names it by its entry.
            notes.add(entryName + note.substring(note.indexOf('\t')));
        }
        if (report != null) {
            counts.addAll(report.counts());
            notes.addAll(report.notes());
        }
        described.add(new ClassTransformPipeline.JarReport(entryName, false, counts, notes,
                report == null ? null : report.desugared()));
    }

    /**
     * Reports what the transforms did once every dependency is staged: each fallback note at info, in
     * class-path order, then one info line per step, stripping first.
     *
     * @param logger where to report
     * @return one report per step that ran, empty when none ran
     */
    List<TransformReport> report(BuildLogger logger) {
        List<TransformReport> totals = new ArrayList<>();
        if (stripped != null) {
            int rewritten = 0;
            int unchanged = 0;
            int fallbacks = 0;
            long saved = 0;
            for (ClassPathTransform.Result.Entry entry : stripped.result.entries()) {
                entry.notes().forEach(note -> logNote(logger, note));
                rewritten += entry.classesStripped();
                unchanged += entry.classesUnchanged();
                fallbacks += entry.fallbacks();
                saved += entry.bytesSaved();
            }
            logger.info(stripped.result.summary());
            if (!stripped.result.entries().isEmpty()) {
                totals.add(new TransformReport(STRIP, rewritten, unchanged, fallbacks, saved));
            }
        }
        if (pipeline != null) {
            List<ClassTransformPipeline.JarReport> all = reports();
            for (ClassTransformPipeline.JarReport report : all) {
                report.notes().forEach(note -> logNote(logger, note));
            }
            for (String line : pipeline.summaries(all)) {
                logger.info(line);
            }
            totals.addAll(pipeline.totals(all));
        }
        return totals;
    }

    private static void logNote(BuildLogger logger, String note) {
        String[] fields = note.split("\t", 4);
        logger.info("Kept " + fields[1] + " of " + fields[0] + " without " + fields[2] + ": " + fields[3]);
    }

    /**
     * The content of {@code MICRONAUT-INF/transforms.txt}.
     *
     * @param version the Runner version, or {@code null} when it is unknown
     * @return the content, or {@code null} when the archive carries no such entry
     */
    byte[] describe(String version) {
        if (pipeline == null && stripped == null) {
            return null;
        }
        List<ClassTransformPipeline.JarReport> all = new ArrayList<>(described.size() + 1);
        if (applicationReport != null) {
            all.add(applicationReport);
        }
        for (ClassTransformPipeline.JarReport report : described) {
            if (report != null) {
                all.add(report);
            }
        }
        return ClassTransformPipeline.describe(version, all);
    }

    /**
     * Every layer's report, in class-path order: the application layer, when a transform ran over it, then the
     * dependencies.
     */
    private List<ClassTransformPipeline.JarReport> reports() {
        if (applicationReport == null) {
            return reports;
        }
        List<ClassTransformPipeline.JarReport> all = new ArrayList<>(reports.size() + 1);
        all.add(applicationReport);
        all.addAll(reports);
        return all;
    }

    private static ClassPathModel scan(RunnerJarSpec spec, List<Dependency> dependencies, List<Path> sources,
                                       ExecutorService pool, ApplicationClasses application) throws IOException {
        ClassPathModel.Interner strings = new ClassPathModel.Interner();
        List<Callable<ClassPathModel.LayerScan>> tasks = new ArrayList<>(dependencies.size());
        for (int position = 0; position < dependencies.size(); position++) {
            Dependency dependency = dependencies.get(position);
            Path source = sources.get(position);
            int layer = position + 1;
            tasks.add(() -> scanDependency(layer, dependency, source, strings));
        }
        List<Future<ClassPathModel.LayerScan>> futures = null;
        if (pool != null) {
            futures = new ArrayList<>(Collections.nCopies(tasks.size(), null));
            for (int position : RunnerJarBuilder.largestFirst(dependencies)) {
                futures.set(position, pool.submit(tasks.get(position)));
            }
        }
        List<ClassPathModel.LayerScan> scans = new ArrayList<>(tasks.size() + 1);
        ClassPathModel.LayerScan applicationScan = ClassPathModel.scan(0, APPLICATION_LAYER, spec.multiRelease(),
                true, strings);
        application.scan(applicationScan);
        scans.add(applicationScan);
        for (int position = 0; position < tasks.size(); position++) {
            if (futures == null) {
                scans.add(scanDependency(position + 1, dependencies.get(position), sources.get(position), strings));
            } else {
                scans.add(RunnerJarBuilder.awaitStage(futures.get(position)));
            }
        }
        return ClassPathModel.merge(scans, true);
    }

    /**
     * Scans one dependency, through a {@link ZipReader} of its own on the file its stage reads, on whichever
     * thread runs it.
     */
    private static ClassPathModel.LayerScan scanDependency(int layer, Dependency dependency, Path source,
                                                           ClassPathModel.Interner strings) throws IOException {
        try (ZipReader reader = ZipReader.open(source)) {
            Manifest manifest = reader.manifest().orElse(null);
            Attributes main = manifest == null ? null : manifest.getMainAttributes();
            boolean multiRelease = main != null && "true".equalsIgnoreCase(main.getValue("Multi-Release"));
            ClassPathModel.LayerScan scan = ClassPathModel.scan(layer, "the dependency " + dependency.path(),
                    multiRelease, true, strings);
            for (ZipEntryInfo entry : reader.entries()) {
                if (!entry.directory() && scan.wants(entry.name(), entry.uncompressedSize())) {
                    scan.accept(entry.name(), reader.read(entry));
                }
            }
            return scan;
        } catch (IOException e) {
            // Named as a stage names it, so the first failing dependency reads the same either way.
            throw new IOException("The dependency " + dependency.path() + " cannot be packaged: "
                    + e.getMessage(), e);
        }
    }

    /**
     * What stripping did.
     *
     * @param result  what Micronaut AOT returned
     * @param entries what it did to each dependency, by position; {@code null} where it did not run over it
     */
    private record Stripped(ClassPathTransform.Result result, List<ClassPathTransform.Result.Entry> entries) {
    }

    /**
     * Scans the application layer, whose readers belong to the calling thread.
     */
    @FunctionalInterface
    interface ApplicationClasses {

        /**
         * Hands every class of the application layer the scan wants to it.
         *
         * @param scan the application layer's scan
         * @throws IOException if a class cannot be read
         */
        void scan(ClassPathModel.LayerScan scan) throws IOException;
    }
}
