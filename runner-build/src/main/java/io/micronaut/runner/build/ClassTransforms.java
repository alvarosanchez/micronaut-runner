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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The class transforms of one build: what Micronaut AOT's class path transform rewrote, what each stage nests, and,
 * once every dependency is staged, what the transforms did.
 *
 * <p>The transform runs once, on the calling thread, before any stage is submitted ({@link ClassPathRewrite}): one
 * run over the application layer and every dependency, which desugars lambdas in STORED and HYBRID and, with the
 * opt-in {@code stripLocalVariables}, strips the dependencies that are not project modules, in every mode. The
 * application layer takes the rewritten and generated classes at once, and each stage then nests the transform's
 * copy of its dependency, when it wrote one. No stage rewrites a class.</p>
 *
 * <p>It belongs to the calling thread of {@link RunnerJarBuilder}. Only its {@link #sources()} reach the stage
 * tasks; the calling thread adds their results in class-path order and reports them.</p>
 */
final class ClassTransforms {

    /** What notes and {@code transforms.txt} call the application layer: its directory in the archive. */
    private static final String APPLICATION_NAME =
            IndexFormat.CLASSES_PREFIX.substring(0, IndexFormat.CLASSES_PREFIX.length() - 1);

    /** The jar the application layer is written into for the transform, in the build's work directory. */
    private static final String APPLICATION_JAR = "application-layer.jar";

    /** The directory the transform writes its copies into, in the build's work directory. */
    private static final String REWRITTEN = "rewritten";

    /** What each stage nests, in class-path order: the dependency, or the transform's copy of it. */
    private final List<Path> sources;
    /** Whether lambdas were desugared. */
    private final boolean desugared;
    /**
     * What the transform did to each layer, the application layer first, then each dependency; an element is
     * {@code null} for a layer it did not run over, and the list is {@code null} when it did not run.
     */
    private final List<ClassPathRewrite.Counts> counts;
    /** The lines the transform logs about stripping; empty when stripping was not asked for. */
    private final List<String> summary;
    /** The application layer's class entries, as the transform saw them; {@code null} when it did not run. */
    private final DependencyStage.Classes applicationClasses;
    /** What each stage reported, in class-path order. */
    private final List<DependencyStage.Staged> staged = new ArrayList<>();

    private ClassTransforms(List<Path> sources, boolean desugared, List<ClassPathRewrite.Counts> counts,
                            List<String> summary, DependencyStage.Classes applicationClasses) {
        this.sources = List.copyOf(sources);
        this.desugared = desugared;
        this.counts = counts;
        this.summary = summary;
        this.applicationClasses = applicationClasses;
    }

    /**
     * Runs Micronaut AOT's class path transform when an option asks for it, and lets the application layer take
     * what it rewrote.
     *
     * <p>Lambdas are desugared only in STORED and HYBRID: in PRESERVE every dependency, or its stripped copy, is
     * nested as it is, which is reported once at info when the option is on, and the application layer is left
     * alone too. Stripping runs in every mode, over the dependencies that are not
     * {@linkplain Dependency#projectModule() project modules}; the transform turns it off for the whole build, with
     * one warning, when the class path holds a library that reads local-variable tables at run time. Without a step
     * to run, the transform does not run.</p>
     *
     * @param spec         the build's spec
     * @param dependencies the dependencies that are nested, in class-path order
     * @param parallelism  the number of threads the transform may use
     * @param work         the build's work directory, which the build deletes
     * @param application  the application layer, which only the calling thread may read
     * @param logger       where to report that an option has no effect
     * @param warn         where to report what the transform warns about
     * @return the build's transforms
     * @throws IOException if a dependency or an application class cannot be read, or a copy cannot be written
     */
    static ClassTransforms prepare(RunnerJarSpec spec, List<Dependency> dependencies, int parallelism, Path work,
                                   ApplicationLayer application, BuildLogger logger, Consumer<String> warn)
            throws IOException {
        boolean preserve = spec.compression() == Compression.PRESERVE;
        boolean desugar = spec.desugarLambdas() && !preserve;
        List<Path> paths = new ArrayList<>(dependencies.size());
        List<Path> strip = new ArrayList<>(dependencies.size());
        for (Dependency dependency : dependencies) {
            paths.add(dependency.path());
            if (spec.stripLocalVariables() && !dependency.projectModule()) {
                strip.add(dependency.path());
            }
        }
        ClassTransforms transforms = new ClassTransforms(paths, false, null, List.of(), null);
        if (desugar || !strip.isEmpty()) {
            transforms = rewrite(spec, paths, desugar, strip, parallelism, work, application, warn);
        }
        if (preserve && spec.desugarLambdas()) {
            logger.info("The " + ClassPathRewrite.DESUGAR + " option has no effect with PRESERVE compression,"
                    + " which does not repack the dependencies");
        }
        return transforms;
    }

    /**
     * Runs the transform over the application layer, written into a jar, and every dependency, and replaces the
     * application layer's classes with the ones it rewrote.
     */
    private static ClassTransforms rewrite(RunnerJarSpec spec, List<Path> paths, boolean desugar, List<Path> strip,
                                           int parallelism, Path work, ApplicationLayer application,
                                           Consumer<String> warn) throws IOException {
        Path jar = work.resolve(APPLICATION_JAR);
        Written written = application.write(jar, spec.multiRelease());
        ClassPathRewrite.Result result = ClassPathRewrite.run(jar, paths, desugar, strip, work.resolve(REWRITTEN),
                parallelism);
        result.warnings().forEach(warn);
        Path copy = result.classPath().get(0);
        long saved = 0;
        if (!copy.equals(jar)) {
            saved = written.size() - application.replace(copy);
            Files.delete(copy);
            Files.delete(copy.getParent());
        }
        // The application layer has taken what it needs: only the dependencies' copies stay until staging.
        Files.delete(jar);
        return new ClassTransforms(result.classPath().subList(1, result.classPath().size()), desugar,
                result.counts(), result.summary(), new DependencyStage.Classes(written.classes(), saved));
    }

    /**
     * What each stage nests.
     *
     * @return one path per dependency, in class-path order: the dependency, or the copy the transform wrote of it
     */
    List<Path> sources() {
        return sources;
    }

    /**
     * Adds what one dependency's stage did, in class-path order.
     *
     * @param result the dependency's stage result
     */
    void add(DependencyStage.Staged result) {
        staged.add(result);
    }

    /**
     * Reports what the transforms did once every dependency is staged: each fallback note at info, in
     * class-path order, then the lines of each transform, desugaring first.
     *
     * @param logger where to report
     * @return one report per transform that ran, in the order they ran; empty when none ran
     */
    List<TransformReport> report(BuildLogger logger) {
        List<Layer> layers = layers();
        for (Layer layer : layers) {
            for (String note : layer.notes()) {
                String[] fields = note.split("\t", 4);
                logger.info("Kept " + fields[1] + " of " + fields[0] + " without " + fields[2] + ": " + fields[3]);
            }
        }
        List<TransformReport> totals = new ArrayList<>(2);
        if (desugared) {
            TransformReport total = total(ClassPathRewrite.DESUGAR, layers);
            if (total == null) {
                // No layer holds a class.
                total = new TransformReport(ClassPathRewrite.DESUGAR, 0, 0, 0, 0);
            }
            totals.add(total);
            logger.info(desugarSummary(total, layers));
        }
        summary.forEach(logger::info);
        TransformReport stripped = total(ClassPathRewrite.STRIP, layers);
        if (stripped != null) {
            totals.add(stripped);
        }
        return totals;
    }

    /**
     * Writes {@code MICRONAUT-INF/transforms.txt}: tab-separated lines in class-path order, with nested entry
     * names and no absolute path or timestamp. The first line names the Runner version, the second the columns,
     * then one line per jar and transform with its counts, then one {@code lambdas} line per jar with a lambda
     * call site, which gives the sites rewritten, the classes generated, the bridges added, the nests that fell
     * back and the sites left as {@code invokedynamic}, in total and by reason, and last one line per note. The
     * application layer has a line only for desugaring, the one transform that runs over it.
     *
     * @param version the Runner version, or {@code null} when it is unknown
     * @return the content, or {@code null} when no transform changed any class and nothing was noted
     */
    byte[] describe(String version) {
        List<Layer> layers = layers();
        boolean anything = false;
        for (Layer layer : layers) {
            anything |= !layer.notes().isEmpty();
            for (TransformReport count : layer.counts()) {
                anything |= count.rewritten() > 0 || count.fallbacks() > 0;
            }
        }
        if (!anything) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        text.append("Micronaut-Runner-Version\t").append(version == null ? "unknown" : version).append('\n');
        text.append("jar\tstep\trewritten\tunchanged\tfallbacks\tbytesSaved\n");
        for (Layer layer : layers) {
            for (TransformReport count : layer.counts()) {
                text.append(layer.name()).append('\t').append(count.step()).append('\t')
                        .append(count.rewritten()).append('\t').append(count.unchanged()).append('\t')
                        .append(count.fallbacks()).append('\t').append(count.bytesSaved()).append('\n');
            }
        }
        for (Layer layer : layers) {
            ClassPathRewrite.Counts counts = layer.lambdas();
            if (counts == null || counts.sites() + counts.leftTotal() == 0) {
                continue;
            }
            text.append("lambdas\t").append(layer.name())
                    .append("\trewritten=").append(counts.sites())
                    .append("\tgenerated=").append(counts.generated())
                    .append("\tbridges=").append(counts.bridges())
                    .append("\tnestFallbacks=").append(counts.nestFallbacks())
                    .append("\tleft=").append(counts.leftTotal());
            for (Map.Entry<String, Integer> left : counts.left().entrySet()) {
                text.append("\tleft.").append(left.getKey()).append('=').append(left.getValue());
            }
            text.append('\n');
        }
        for (Layer layer : layers) {
            for (String note : layer.notes()) {
                text.append("fallback\t").append(note).append('\n');
            }
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Every layer's counts and notes, in class-path order: the application layer, when lambdas were desugared,
     * then the dependencies; none when the transform did not run.
     */
    private List<Layer> layers() {
        if (counts == null) {
            return List.of();
        }
        List<Layer> layers = new ArrayList<>(staged.size() + 1);
        if (desugared && applicationClasses.count() > 0) {
            ClassPathRewrite.Counts application = counts.get(0);
            layers.add(new Layer(APPLICATION_NAME, List.of(desugarCount(application, applicationClasses)),
                    application, notes(APPLICATION_NAME, application)));
        }
        for (int position = 0; position < staged.size(); position++) {
            DependencyStage.Staged result = staged.get(position);
            String name = result.jar().entryName();
            ClassPathRewrite.Counts entry = counts.get(position + 1);
            List<TransformReport> lines = new ArrayList<>(2);
            if (desugared) {
                lines.add(desugarCount(entry, result.classes()));
            }
            if (entry != null && entry.stripped() != null) {
                lines.add(entry.stripped());
            }
            layers.add(new Layer(name, lines, desugared ? entry : null, notes(name, entry)));
        }
        return layers;
    }

    /**
     * The count line of desugaring for one layer. A nest that fell back counts once, as a fallback; every other
     * class the transform did not rewrite counts as unchanged. The bytes saved are the change of the layer's
     * classes that stripping does not count already.
     *
     * @param counts  what the transform did to the layer, or {@code null} when it did not run over it
     * @param classes the layer's class entries
     */
    private static TransformReport desugarCount(ClassPathRewrite.Counts counts, DependencyStage.Classes classes) {
        int rewritten = counts == null ? 0 : counts.classesDesugared();
        int fallbacks = counts == null ? 0 : counts.nestFallbacks();
        long stripped = counts == null || counts.stripped() == null ? 0 : counts.stripped().bytesSaved();
        return new TransformReport(ClassPathRewrite.DESUGAR, rewritten,
                Math.max(0, classes.count() - rewritten - fallbacks), fallbacks, classes.bytesSaved() - stripped);
    }

    /**
     * The transform's fallback notes of one layer, with the layer's name in the archive instead of the path the
     * transform read.
     */
    private static List<String> notes(String name, ClassPathRewrite.Counts counts) {
        if (counts == null || counts.notes().isEmpty()) {
            return List.of();
        }
        List<String> notes = new ArrayList<>(counts.notes().size());
        for (String note : counts.notes()) {
            int tab = note.indexOf('\t');
            notes.add(tab < 0 ? name + '\t' + note : name + note.substring(tab));
        }
        return notes;
    }

    /**
     * The total of one transform over every layer.
     *
     * @return the total, or {@code null} when no layer has a line of the transform
     */
    private static TransformReport total(String step, List<Layer> layers) {
        boolean ran = false;
        int rewritten = 0;
        int unchanged = 0;
        int fallbacks = 0;
        long saved = 0;
        for (Layer layer : layers) {
            for (TransformReport count : layer.counts()) {
                if (count.step().equals(step)) {
                    ran = true;
                    rewritten += count.rewritten();
                    unchanged += count.unchanged();
                    fallbacks += count.fallbacks();
                    saved += count.bytesSaved();
                }
            }
        }
        return ran ? new TransformReport(step, rewritten, unchanged, fallbacks, saved) : null;
    }

    private static String desugarSummary(TransformReport report, List<Layer> layers) {
        int sites = 0;
        int generated = 0;
        int bridges = 0;
        int left = 0;
        int nestFallbacks = 0;
        int involved = 0;
        boolean application = false;
        for (Layer layer : layers) {
            ClassPathRewrite.Counts counts = layer.lambdas();
            if (counts == null) {
                continue;
            }
            sites += counts.sites();
            generated += counts.generated();
            bridges += counts.bridges();
            left += counts.leftTotal();
            nestFallbacks += counts.nestFallbacks();
            if (counts.sites() > 0) {
                if (layer.name().equals(APPLICATION_NAME)) {
                    application = true;
                } else {
                    involved++;
                }
            }
        }
        return "Desugared " + sites + " lambda call sites into " + generated + " generated classes in " + involved
                + " dependencies" + (application ? " and the application layer" : "") + " (" + report.rewritten()
                + " classes rewritten, " + bridges + " bridges, " + left + " sites left as invokedynamic, "
                + nestFallbacks + " nest fallbacks, " + report.fallbacks() + " fallbacks)";
    }

    /**
     * What the transforms did to one layer.
     *
     * @param name    the layer's name in the archive
     * @param counts  one count per transform that ran over it, desugaring first
     * @param lambdas what desugaring did to its lambda call sites, or {@code null}
     * @param notes   its fallback notes
     */
    private record Layer(String name, List<TransformReport> counts, ClassPathRewrite.Counts lambdas,
                         List<String> notes) {
    }

    /**
     * The classes {@link ApplicationLayer#write(Path, boolean)} wrote.
     *
     * @param classes how many classes it wrote
     * @param size    their uncompressed size together
     */
    record Written(int classes, long size) {
    }

    /**
     * The application layer, as the transforms read and change it. Only the calling thread may use it: it reads
     * the application jars through readers that belong to it.
     */
    interface ApplicationLayer {

        /**
         * Writes every class of the application layer into a jar, in the layer's order, for the transform to read.
         *
         * @param jar          the jar to write
         * @param multiRelease whether the jar's manifest says {@code Multi-Release: true}, as the layer is declared
         * @return what it wrote
         * @throws IOException if a class cannot be read or the jar cannot be written
         */
        Written write(Path jar, boolean multiRelease) throws IOException;

        /**
         * Takes the transform's copy of the jar {@link #write(Path, boolean)} wrote: each class it rewrote
         * replaces the layer's, and the classes it generated follow their hosts.
         *
         * @param copy the transform's copy
         * @return the uncompressed size of the copy's classes together
         * @throws IOException if the copy cannot be read
         */
        long replace(Path copy) throws IOException;
    }
}
