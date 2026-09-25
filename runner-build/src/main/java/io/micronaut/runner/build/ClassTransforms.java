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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    private final ClassTransformPipeline pipeline;
    private final List<ClassTransformPipeline.JarReport> reports = new ArrayList<>();

    private ClassTransforms(ClassTransformPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Decides which class transforms run and, when any does, scans the class path they need.
     *
     * <p>Stripping runs only in STORED: in PRESERVE every dependency is nested byte for byte, which is reported
     * once at info. It is turned off for the whole build, with one warning, when a layer contains a library that
     * reads local-variable tables at run time. With every transform off, or no dependency, nothing is scanned.
     * Otherwise one scan task per dependency runs on the pool, each through a {@link ZipReader} of its own,
     * while the calling thread scans the application layer, and the scans are merged in class-path order.</p>
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
        List<ClassTransformPipeline.Step> steps = new ArrayList<>();
        if (spec.stripLocalVariables()) {
            if (spec.compression() == Compression.PRESERVE) {
                logger.info("The stripLocalVariables option has no effect with PRESERVE compression, which nests"
                        + " every dependency byte for byte");
            } else {
                steps.add(new LocalVariableStripper());
            }
        }
        if (steps.isEmpty() || dependencies.isEmpty()) {
            return new ClassTransforms(null);
        }
        boolean members = false;
        for (ClassTransformPipeline.Step step : steps) {
            members |= step.needsMemberTables();
        }
        ClassPathModel model = scan(spec, dependencies, pool, application, members);
        Optional<ClassPathModel.Watched> reader = model.watched();
        if (reader.isPresent()) {
            warn.accept("No local-variable table was stripped, because " + reader.get().layer() + " contains "
                    + reader.get().entry() + ", which reads local-variable tables at run time. To silence this"
                    + " warning, set the stripLocalVariables option to false");
            steps.removeIf(step -> step instanceof LocalVariableStripper);
        }
        return new ClassTransforms(steps.isEmpty() ? null : new ClassTransformPipeline(steps, model));
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
        for (ClassTransformPipeline.JarReport report : reports) {
            for (String note : report.notes()) {
                String[] fields = note.split("\t", 4);
                logger.info("Kept " + fields[1] + " of " + fields[0] + " without " + fields[2] + ": " + fields[3]);
            }
        }
        List<TransformReport> totals = pipeline.totals(reports);
        List<ClassTransformPipeline.Step> steps = pipeline.steps();
        for (int i = 0; i < steps.size(); i++) {
            logger.info(steps.get(i).summary(totals.get(i), reports.size()));
        }
        return totals;
    }

    /**
     * The content of {@code MICRONAUT-INF/transforms.txt}.
     *
     * @param version the Runner version, or {@code null} when it is unknown
     * @return the content, or {@code null} when the archive carries no such entry
     */
    byte[] describe(String version) {
        return pipeline == null ? null : pipeline.describe(version, reports);
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
