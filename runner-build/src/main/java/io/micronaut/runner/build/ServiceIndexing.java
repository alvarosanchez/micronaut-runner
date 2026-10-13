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

import io.micronaut.aot.serviceindex.ServiceIndexGenerator;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The packaging step that generates micronaut-core's service index with Micronaut AOT's
 * {@code micronaut-aot-service-index}, the generator the Micronaut build plugins share, and reports what came of it.
 *
 * <p>The index lists, for every service type, the names micronaut-core's scan finds, so that the application does
 * not scan {@code META-INF/services} and {@code META-INF/micronaut} when it starts. micronaut-core builds it itself,
 * from the class path it is shown, which has to be the class path {@code RunnerClassLoader} gives the application.
 * That loader answers a lookup under {@code META-INF/micronaut/} from the merged copy at the archive root only, and
 * any other one from the application layer first, then from each nested jar. So the module is shown a view jar of
 * the application layer, written to the work directory and deleted after the call, then the nested jars as they
 * were staged, in index order. The view holds the merged copy as the archive stores it, directory entries included,
 * the application layer's service files, and the output of an earlier generation, which the module replaces. It
 * holds no other class: the index holds names only. The index lists no class path, because the names the jars have
 * at run time are not known here, so micronaut-core compares none.</p>
 *
 * <p>Every doubt resolves to "no index", and micronaut-core then scans at startup, as it would without this step.
 * The build never fails because of it, short of an I/O error while the view jar is written: the module's reason is
 * an informational line, or a warning when it failed, when a dependency already carries an index, or when the module
 * does not match the one this library was built against.</p>
 *
 * <p>This is the only class of this library that names a type of that module, whose API is internal to its callers
 * and may change in any release.</p>
 *
 * @since 1.0
 */
final class ServiceIndexing {

    /** The package of the module's generated classes, which its {@code Result} documents. */
    static final String GENERATED_PREFIX = "io/micronaut/aot/serviceindex/generated/";

    /** The loader the module generates, which registers an index. */
    static final String GENERATED_LOADER = GENERATED_PREFIX + "ServiceIndexLoader.class";

    /**
     * Runs just before the module is called. Tests replace it to force the {@link LinkageError} of a module
     * version that does not match; it does nothing in production.
     */
    static volatile Runnable beforeCall = () -> { };

    private static final String NOT_GENERATED = "No service index was generated because ";

    private static final String SCAN = "; the application scans its class path for services at run time";

    private static final String SERVICES_PREFIX = "META-INF/services/";

    private static final String VERSIONS_PREFIX = "META-INF/versions/";

    private ServiceIndexing() {
    }

    /**
     * Logs that the step does not run because the {@code staticServices} option is off.
     *
     * @param logger where the line goes
     */
    static void notRequested(BuildLogger logger) {
        logger.info(NOT_GENERATED + "the staticServices option is false" + SCAN);
    }

    /**
     * Whether an entry of the application layer goes into the view jar besides the merged copy: a service file that
     * a lookup can find, {@code META-INF/services/<type>} or its versioned copy in a multi-release application
     * layer, or the output of an earlier generation.
     *
     * @param name         the entry's logical name
     * @param multiRelease whether the application layer is multi-release
     * @return whether the view holds it
     */
    static boolean inView(String name, boolean multiRelease) {
        if (name.startsWith(GENERATED_PREFIX)) {
            return true;
        }
        String base = name;
        if (multiRelease && name.startsWith(VERSIONS_PREFIX)) {
            int slash = name.indexOf('/', VERSIONS_PREFIX.length());
            base = slash < 0 ? name : name.substring(slash + 1);
        }
        return base.startsWith(SERVICES_PREFIX) && base.length() > SERVICES_PREFIX.length()
                && base.indexOf('/', SERVICES_PREFIX.length()) < 0;
    }

    /**
     * Writes the view jar, calls the module and logs what it did: one informational line, or one warning.
     *
     * @param work         the work directory, which holds the view jar and the copies the call needs
     * @param view         the entries of the view jar, in order: the merged copy, then the application layer's
     * @param multiRelease whether the application layer is multi-release
     * @param nested       the nested jars, in index order
     * @param logger       where the informational line goes
     * @param warnings     where a warning goes
     * @return what to write into the application layer and delete from it, whatever the module decided
     * @throws IOException if the view jar or a copy cannot be written or deleted
     */
    static Outcome generate(Path work, List<ViewEntry> view, boolean multiRelease, List<NestedLayer> nested,
                            BuildLogger logger, Consumer<String> warnings) throws IOException {
        Path holder = work.resolve("service-index-view.jar");
        writeView(holder, view, multiRelease);
        List<Path> classPath = new ArrayList<>(nested.size() + 1);
        classPath.add(holder);
        Set<Path> files = new HashSet<>();
        List<Path> copies = new ArrayList<>();
        Map<String, String> dependencies = new LinkedHashMap<>();
        Path carriedIndex = null;
        for (NestedLayer layer : nested) {
            Path file = layer.file();
            if (!files.add(file.toAbsolutePath().normalize())) {
                // In PRESERVE a dependency listed twice is nested twice from one file, and both copies are on the
                // class path the archive gives the application: the module takes each entry once, so it gets a copy.
                file = Files.copy(file, work.resolve("service-index-duplicate-" + classPath.size() + ".jar"));
                copies.add(file);
            }
            classPath.add(file);
            String staged = RunnerJarBuilder.fileName(file);
            if (!staged.equals(RunnerJarBuilder.fileName(layer.dependency()))) {
                dependencies.put(staged, "the dependency " + layer.dependency());
            }
            if (carriedIndex == null && layer.carriesAnIndex()) {
                carriedIndex = layer.dependency();
            }
        }
        Outcome outcome = call(holder, classPath, dependencies, carriedIndex, logger, warnings);
        Files.delete(holder);
        for (Path copy : copies) {
            Files.delete(copy);
        }
        return outcome;
    }

    private static void writeView(Path file, List<ViewEntry> view, boolean multiRelease) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            if (multiRelease) {
                Manifest manifest = new Manifest();
                manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
                out.putNextEntry(entry("META-INF/MANIFEST.MF"));
                manifest.write(out);
                out.closeEntry();
            }
            for (ViewEntry item : view) {
                out.putNextEntry(entry(item.name()));
                if (item.content() != null) {
                    out.write(item.content());
                }
                out.closeEntry();
            }
        }
    }

    private static ZipEntry entry(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        return entry;
    }

    private static Outcome call(Path holder, List<Path> classPath, Map<String, String> dependencies,
                                Path carriedIndex, BuildLogger logger, Consumer<String> warnings) {
        try {
            beforeCall.run();
            ServiceIndexGenerator.Request.Builder request = ServiceIndexGenerator.Request.builder();
            for (Path entry : classPath) {
                request.entry(entry);
            }
            ServiceIndexGenerator.Result result = ServiceIndexGenerator.generate(request.holder(holder).build());
            ServiceIndexGenerator.Status status = result.status();
            if (status != ServiceIndexGenerator.Status.GENERATED && carriedIndex != null) {
                warnings.accept(NOT_GENERATED + "the dependency " + carriedIndex + " already carries one, and a JVM"
                        + " can register only one. micronaut-core uses that index, which was built for another class"
                        + " path, so it can miss services of this application; start the application with"
                        + " -Dmicronaut.service.index.enabled=false to scan for them instead");
            } else if (status == ServiceIndexGenerator.Status.FAILED) {
                warnings.accept(named(result.message(), dependencies));
            } else {
                logger.info(named(result.message(), dependencies));
            }
            return new Outcome(status == ServiceIndexGenerator.Status.GENERATED, result.entries(), result.removals());
        } catch (LinkageError e) {
            // The build tool resolved a micronaut-aot-service-index whose API differs from the one this library
            // was built against. A VirtualMachineError is not a LinkageError, and propagates.
            warnings.accept(NOT_GENERATED + "the micronaut-aot-service-index on the build's class path does not"
                    + " match the one this packager was built against: " + e + SCAN);
            return new Outcome(false, Map.of(), Set.of());
        }
    }

    /**
     * A message of the module with each staged nested jar it names replaced by its dependency, which is what the
     * user knows: in STORED and HYBRID a nested jar is a copy in the work directory.
     */
    private static String named(String message, Map<String, String> dependencies) {
        String named = message;
        for (Map.Entry<String, String> dependency : dependencies.entrySet()) {
            named = named.replace(dependency.getKey(), dependency.getValue());
        }
        return named;
    }

    /**
     * One entry of the view jar.
     *
     * @param name    the entry name
     * @param content the file's content, or {@code null} for a directory
     */
    record ViewEntry(String name, byte[] content) {
    }

    /**
     * One nested jar as it was staged.
     *
     * @param file           the nested jar: the repacked copy, or the dependency itself in PRESERVE
     * @param dependency     the dependency it was staged from, which a message names
     * @param carriesAnIndex whether it holds the module's index loader
     */
    record NestedLayer(Path file, Path dependency, boolean carriesAnIndex) {
    }

    /**
     * What the step leaves to the builder.
     *
     * @param generated whether an index was generated
     * @param entries   the entries to write into the application layer, by name, in order, each replacing an entry
     *                  of the same name
     * @param removals  the entries to delete from the application layer
     */
    record Outcome(boolean generated, Map<String, byte[]> entries, Set<String> removals) {
    }
}
