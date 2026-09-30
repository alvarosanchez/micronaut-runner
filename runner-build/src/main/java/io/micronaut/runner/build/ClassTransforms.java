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

import io.micronaut.runner.IndexFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 */
final class ClassTransforms {

    /** What warnings and the class path model call the application layer. */
    private static final String APPLICATION_LAYER = "the application output";

    /** What notes and {@code transforms.txt} call the application layer: its directory in the archive. */
    private static final String APPLICATION_NAME =
            IndexFormat.CLASSES_PREFIX.substring(0, IndexFormat.CLASSES_PREFIX.length() - 1);

    private final ClassTransformPipeline pipeline;
    private final List<ClassTransformPipeline.JarReport> reports = new ArrayList<>();
    /** What the pipeline did to the application layer, first on the class path; {@code null} when it did not run there. */
    private ClassTransformPipeline.JarReport applicationReport;

    private ClassTransforms(ClassTransformPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Decides which class transforms run and, when any does, scans the class path they need.
     *
     * <p>The transforms run only in STORED: in PRESERVE every dependency is nested byte for byte, which is
     * reported once at info for each enabled option, and the application layer is left alone too. Stripping is
     * turned off for the whole build, with one warning, when a layer contains a library that reads local-variable
     * tables at run time. With every transform off, or with only stripping and no dependency, nothing is
     * scanned. Otherwise one scan task per dependency runs on the pool, each through a {@link ZipReader} of its
     * own, while the calling thread scans the application layer, and the scans are merged in class-path order.
     * Desugaring lambdas needs the member tables of every class, so the scans record them when it is on.</p>
     *
     * @param spec         the build's spec
     * @param dependencies the dependencies that are nested, in class-path order
     * @param pool         the staging pool, or {@code null} to scan on the calling thread
     * @param application  scans the application layer, on the calling thread
     * @param logger       where to report that an option has no effect
     * @param warn         where to report that a transform was turned off
     * @return the build's transforms
     * @throws IOException if a dependency or an application class cannot be read; when several dependencies
     *                     cannot, the failure of the first one on the class path
     */
    static ClassTransforms prepare(RunnerJarSpec spec, List<Dependency> dependencies, ExecutorService pool,
                                   ApplicationClasses application, BuildLogger logger, Consumer<String> warn)
            throws IOException {
        boolean desugar = spec.desugarLambdas();
        boolean strip = spec.stripLocalVariables();
        if (spec.compression() == Compression.PRESERVE) {
            for (String option : new String[] {desugar ? LambdaDesugarer.NAME : null,
                strip ? LocalVariableStripper.NAME : null}) {
                if (option != null) {
                    logger.info("The " + option + " option has no effect with PRESERVE compression, which nests"
                            + " every dependency byte for byte");
                }
            }
            return new ClassTransforms(null);
        }
        if (!desugar && (!strip || dependencies.isEmpty())) {
            return new ClassTransforms(null);
        }
        // The member tables are recorded only for the step that needs them: desugaring.
        ClassPathModel model = scan(spec, dependencies, pool, application, desugar);
        // The order the steps run in: desugaring first, then stripping.
        List<ClassTransformPipeline.Step> steps = new ArrayList<>();
        if (desugar) {
            steps.add(new LambdaDesugarer(model));
        }
        if (strip && !dependencies.isEmpty()) {
            Optional<ClassPathModel.Watched> reader = model.watched();
            if (reader.isPresent()) {
                warn.accept("No local-variable table was stripped, because " + reader.get().layer() + " contains "
                        + reader.get().entry() + ", which reads local-variable tables at run time. To silence this"
                        + " warning, set the stripLocalVariables option to false");
            } else {
                steps.add(new LocalVariableStripper());
            }
        }
        return new ClassTransforms(steps.isEmpty() ? null : new ClassTransformPipeline(steps, model));
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
                new ClassTransformPipeline.Layer(APPLICATION_NAME, 0, true, false, false));
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
     * Adds what the pipeline did to one dependency, in class-path order.
     *
     * @param report the dependency's report, or {@code null} when its stage ran no pipeline
     */
    void add(ClassTransformPipeline.JarReport report) {
        if (report != null) {
            reports.add(report);
        }
    }

    /**
     * Reports what the transforms did once every dependency is staged: each fallback note at info, in
     * class-path order, then one info line per step.
     *
     * @param logger where to report
     * @return one report per step that ran, empty when none ran
     */
    List<TransformReport> report(BuildLogger logger) {
        if (pipeline == null) {
            return List.of();
        }
        List<ClassTransformPipeline.JarReport> all = reports();
        for (ClassTransformPipeline.JarReport report : all) {
            for (String note : report.notes()) {
                String[] fields = note.split("\t", 4);
                logger.info("Kept " + fields[1] + " of " + fields[0] + " without " + fields[2] + ": " + fields[3]);
            }
        }
        for (String line : pipeline.summaries(all)) {
            logger.info(line);
        }
        return pipeline.totals(all);
    }

    /**
     * The content of {@code MICRONAUT-INF/transforms.txt}.
     *
     * @param version the Runner version, or {@code null} when it is unknown
     * @return the content, or {@code null} when the archive carries no such entry
     */
    byte[] describe(String version) {
        return pipeline == null ? null : pipeline.describe(version, reports());
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

    private static ClassPathModel scan(RunnerJarSpec spec, List<Dependency> dependencies, ExecutorService pool,
                                       ApplicationClasses application, boolean members) throws IOException {
        ClassPathModel.Interner strings = new ClassPathModel.Interner();
        List<Callable<ClassPathModel.LayerScan>> tasks = new ArrayList<>(dependencies.size());
        for (int position = 0; position < dependencies.size(); position++) {
            Dependency dependency = dependencies.get(position);
            int layer = position + 1;
            tasks.add(() -> scanDependency(layer, dependency, members, strings));
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
                members, LocalVariableStripper::isKnownReader, strings);
        application.scan(applicationScan);
        scans.add(applicationScan);
        for (int position = 0; position < tasks.size(); position++) {
            if (futures == null) {
                scans.add(scanDependency(position + 1, dependencies.get(position), members, strings));
            } else {
                scans.add(RunnerJarBuilder.awaitStage(futures.get(position)));
            }
        }
        return ClassPathModel.merge(scans, members);
    }

    /**
     * Scans one dependency, through a {@link ZipReader} of its own, on whichever thread runs it.
     */
    private static ClassPathModel.LayerScan scanDependency(int layer, Dependency dependency, boolean members,
                                                           ClassPathModel.Interner strings) throws IOException {
        try (ZipReader reader = ZipReader.open(dependency.path())) {
            Manifest manifest = reader.manifest().orElse(null);
            Attributes main = manifest == null ? null : manifest.getMainAttributes();
            boolean multiRelease = main != null && "true".equalsIgnoreCase(main.getValue("Multi-Release"));
            ClassPathModel.LayerScan scan = ClassPathModel.scan(layer, "the dependency " + dependency.path(),
                    multiRelease, members, LocalVariableStripper::isKnownReader, strings);
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
