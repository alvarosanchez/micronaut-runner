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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rewrites the classes of a runner jar's class path with Micronaut AOT's class path transform,
 * {@link ClassPathTransform}: it desugars lambdas and strips local-variable tables, in one run. It is the only class
 * of this library that names a type of micronaut-aot-bytecode.
 *
 * <p>The transform runs once per build, over the whole class path: the application layer, which the builder writes
 * into a jar of its own first, then every dependency in class-path order. It writes a copy of each entry it
 * rewrites, with the classes it generates right after their hosts, and leaves every other entry where it is. It
 * decides which sites are rewritten and what a generated class looks like, on an open class path: a name that two
 * entries hold, or that has a {@code META-INF/versions/} copy, stays {@code invokedynamic}. Each class is parsed,
 * rewritten and verified once, desugared first and stripped next, so a class both steps rewrite is stripped as it
 * was desugared.</p>
 *
 * <p>Runner adds the launcher's own package, {@code io.micronaut.runner}, as a foreign package, whose classes are
 * never a host, a nest host, an implementation owner, a functional interface or a captured receiver of a rewritten
 * site: {@code RunnerClassLoader} asks the launcher's loader for it first, so a class there may not be the
 * archive's copy. Of the classes the packager generates below it, the entry stub, which holds no lambda call site,
 * is the only one in the application layer during this run, so passing the package whole changes nothing for
 * them.</p>
 *
 * <p>{@code RunnerClassLoader} asks its parent first only for the packages of the boot and the platform loaders'
 * modules, so the packages of the boot-layer modules that the application class loader defines, such as
 * {@code jdk.compiler}'s, are not the JDK's to a runner jar: Runner names them as foreign packages too, read from the
 * JDK that runs the build. micronaut-aot 3.2.0-M1 still reads a package of the JDK from the JDK whatever the foreign
 * packages say, so until it honours them there, such a site is rewritten when the class path holds no class of its
 * package (#250).</p>
 */
final class ClassPathRewrite {

    /** The name of the desugaring step, which is the name of the option that enables it. */
    static final String DESUGAR = RunnerJarOption.DESUGAR_LAMBDAS.optionName();

    /** The name of the stripping step, which is the name of the option that enables it. */
    static final String STRIP = RunnerJarOption.STRIP_LOCAL_VARIABLES.optionName();

    /** The packages the transform treats as foreign, as dotted names: see the class documentation. */
    static final Set<String> FOREIGN_PACKAGES = foreignPackages();

    /** The launcher's own package. */
    private static final String LAUNCHER_PACKAGE = "io.micronaut.runner";

    private ClassPathRewrite() {
    }

    /**
     * The launcher's package, and the packages of the boot-layer modules that neither the boot nor the platform
     * loader defines, as {@code RunnerClassLoader} tells them apart.
     */
    private static Set<String> foreignPackages() {
        Set<String> packages = new TreeSet<>();
        packages.add(LAUNCHER_PACKAGE);
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        for (Module module : ModuleLayer.boot().modules()) {
            ClassLoader loader = module.getClassLoader();
            if (loader != null && loader != platform) {
                packages.addAll(module.getPackages());
            }
        }
        return Collections.unmodifiableSet(packages);
    }

    /**
     * Rewrites the classes of a class path.
     *
     * @param application  the application layer, written into a jar; the first entry of the class path, never
     *                     stripped
     * @param dependencies the dependencies, in class-path order
     * @param desugar      whether to desugar the lambdas of every entry
     * @param strip        the dependencies whose local-variable tables are stripped; empty to strip nothing
     * @param output       the directory the copies are written to, which the caller deletes
     * @param parallelism  the number of threads the transform may use
     * @return what to package for each entry, and what the transform did to it
     * @throws IOException if an entry cannot be read or a copy cannot be written
     */
    static Result run(Path application, List<Path> dependencies, boolean desugar, Collection<Path> strip,
                      Path output, int parallelism) throws IOException {
        List<Path> classPath = new ArrayList<>(dependencies.size() + 1);
        classPath.add(application);
        classPath.addAll(dependencies);
        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(classPath)
                .outputDirectory(output)
                .desugarLambdas(desugar)
                .foreignPackages(FOREIGN_PACKAGES)
                .stripLocalVariables(strip)
                .parallelism(parallelism)
                .build());
        // The transform lists the entries it ran over, in class-path order, each under the path the request gave
        // it: every jar named to strip, unless stripping stood down, and every entry with a lambda call site.
        List<ClassPathTransform.Result.Entry> listed = result.entries();
        List<Counts> counts = new ArrayList<>(classPath.size());
        int next = 0;
        for (Path entry : classPath) {
            if (next < listed.size() && listed.get(next).path().equals(entry)) {
                counts.add(Counts.of(listed.get(next)));
                next++;
            } else {
                counts.add(null);
            }
        }
        if (next != listed.size()) {
            throw new IllegalStateException("The class path transform reported an entry that is not on the class"
                    + " path: " + listed.get(next).path());
        }
        List<String> summary = new ArrayList<>();
        for (String line : result.summary().split("\n")) {
            if (!line.isBlank()) {
                summary.add(line);
            }
        }
        // One line per step, in step order: the builder words desugaring's line itself.
        return new Result(result.classPath(), counts, desugar ? summary.subList(1, summary.size()) : summary,
                result.warnings());
    }

    /**
     * What a run produced.
     *
     * @param classPath what to package for each entry of the class path, in order: its rewritten copy, or the
     *                  entry itself when nothing in it changed
     * @param counts    what the transform did to each entry, in the same order; {@code null} for an entry it did
     *                  not run over
     * @param summary   the lines to log about stripping, when it was asked for: what it did, or why it did nothing
     * @param warnings  the lines to log as warnings
     */
    record Result(List<Path> classPath, List<Counts> counts, List<String> summary, List<String> warnings) {

        /**
         * Makes the lists immutable. {@code counts} may hold {@code null}.
         *
         * @param classPath the class path to package
         * @param counts    the counts of each entry
         * @param summary   the summary lines
         * @param warnings  the warnings
         */
        Result {
            classPath = List.copyOf(classPath);
            counts = Collections.unmodifiableList(new ArrayList<>(counts));
            summary = List.copyOf(summary);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * What the transform did to one class path entry.
     *
     * @param classesDesugared the existing classes desugaring rewrote: hosts, and nest hosts whose
     *                         {@code NestMembers} grew
     * @param sites            the lambda call sites it rewrote
     * @param generated        the classes it generated
     * @param bridges          the bridge methods it added
     * @param nestFallbacks    the planned nests that fell back to their original classes
     * @param left             the sites left as {@code invokedynamic}, by reason, in the transform's order of the
     *                         reasons, without the reasons that have none
     * @param stripped         what stripping did to the entry, or {@code null} when it did not run over it; its
     *                         bytes saved include desugaring's change of a class both steps rewrote
     * @param notes            one line per fallback of either step, tab-separated: the entry as the transform
     *                         names it, the class or {@code the nest of} its nest host, the step and the first error
     */
    record Counts(int classesDesugared, int sites, int generated, int bridges, int nestFallbacks,
                  Map<String, Integer> left, TransformReport stripped, List<String> notes) {

        /**
         * Makes the map and the list immutable, keeping the order of the reasons.
         *
         * @param classesDesugared the classes desugared
         * @param sites            the sites rewritten
         * @param generated        the classes generated
         * @param bridges          the bridges added
         * @param nestFallbacks    the nests that fell back
         * @param left             the sites left, by reason
         * @param stripped         what stripping did, or {@code null}
         * @param notes            the fallback notes
         */
        Counts {
            left = Collections.unmodifiableMap(new LinkedHashMap<>(left));
            notes = List.copyOf(notes);
        }

        private static Counts of(ClassPathTransform.Result.Entry entry) {
            Map<String, Integer> left = entry.sitesLeft();
            int leftTotal = 0;
            for (int count : left.values()) {
                leftTotal += count;
            }
            // The transform lists an entry for stripping or for its lambda call sites. Stripping counts every class
            // of an entry it ran over, and an entry with a site has a class: so an entry with a site and no class
            // counted ran only desugaring, because stripping stood down or was not asked for it.
            boolean stripped = entry.sitesRewritten() + leftTotal == 0
                    || entry.classesStripped() + entry.classesUnchanged() + entry.fallbacks() > 0;
            return new Counts(entry.classesDesugared(), entry.sitesRewritten(), entry.classesGenerated(),
                    entry.bridges(), entry.nestFallbacks(), left,
                    stripped ? new TransformReport(STRIP, entry.classesStripped(), entry.classesUnchanged(),
                            entry.fallbacks(), entry.bytesSaved()) : null,
                    entry.notes());
        }

        /**
         * The number of sites left as {@code invokedynamic}.
         *
         * @return the sum over every reason
         */
        int leftTotal() {
            int total = 0;
            for (int count : left.values()) {
                total += count;
            }
            return total;
        }
    }
}
