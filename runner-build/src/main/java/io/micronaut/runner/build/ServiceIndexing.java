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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    /**
     * Runs just before the module is called. Tests replace it to force the {@link LinkageError} of a module
     * version that does not match; it does nothing in production.
     */
    static volatile Runnable beforeCall = () -> { };

    /** The package of the module's generated classes, which its {@code Result} documents. */
    private static final String GENERATED_PREFIX = "io/micronaut/aot/serviceindex/generated/";

    /** The loader the module generates, which registers an index. */
    private static final String GENERATED_LOADER = GENERATED_PREFIX + "ServiceIndexLoader.class";

    private static final String NOT_GENERATED = "No service index was generated because ";

    private static final String SCAN = "; the application scans its class path for services at run time";

    private static final String SERVICES_PREFIX = "META-INF/services/";

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
     * a lookup can find, {@code META-INF/services/<type>}, or the output of an earlier generation.
     *
     * @param name the entry's logical name
     * @return whether the view holds it
     */
    static boolean inView(String name) {
        if (name.startsWith(GENERATED_PREFIX)) {
            return true;
        }
        return name.startsWith(SERVICES_PREFIX) && name.length() > SERVICES_PREFIX.length()
                && name.indexOf('/', SERVICES_PREFIX.length()) < 0;
    }

    /**
     * Whether a nested jar holds the module's index loader, which makes the module stand down.
     *
     * @param entries the nested jar's entries
     * @return whether one of them is the loader
     */
    static boolean carriesAnIndex(List<ZipEntryInfo> entries) {
        for (ZipEntryInfo entry : entries) {
            if (entry.name().equals(GENERATED_LOADER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes the view jar, calls the module and logs what it did: one informational line, or one warning.
     *
     * @param work     the work directory, which holds the view jar and the copies the call needs
     * @param view     the entries of the view jar, in order: the merged copy, then the application layer's
     * @param nested   the nested jars, in index order
     * @param logger   where the informational line goes
     * @param warnings where a warning goes
     * @return what to write into the application layer and delete from it, whatever the module decided
     * @throws IOException if the view jar or a copy cannot be written or deleted
     */
    static Outcome generate(Path work, List<ViewEntry> view, List<NestedLayer> nested, BuildLogger logger,
                            Consumer<String> warnings) throws IOException {
        Path holder = work.resolve("service-index-view.jar");
        writeView(holder, view);
        List<Path> classPath = new ArrayList<>(nested.size() + 1);
        classPath.add(holder);
        Set<Path> files = new HashSet<>();
        List<Path> copies = new ArrayList<>();
        List<Path> dependencies = new ArrayList<>(nested.size());
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
            dependencies.add(layer.dependency());
            if (carriedIndex == null && layer.carriesAnIndex()) {
                carriedIndex = layer.dependency();
            }
        }
        Map<String, String> names = names(classPath.subList(1, classPath.size()), dependencies);
        Outcome outcome = call(holder, classPath, names, carriedIndex, logger, warnings);
        Files.delete(holder);
        for (Path copy : copies) {
            Files.delete(copy);
        }
        return outcome;
    }

    private static void writeView(Path file, List<ViewEntry> view) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
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

    private static Outcome call(Path holder, List<Path> classPath, Map<String, String> names,
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
                warnings.accept(named(result.message(), names));
            } else {
                logger.info(named(result.message(), names));
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
     * What a message of the module may name a nested jar by, and what it names instead: the dependency, which is
     * what the user knows. In STORED and HYBRID a nested jar is a copy in the work directory, and so is a dependency
     * PRESERVE nests twice. A copy's path becomes the dependency's path, and a file name becomes
     * {@code the dependency <path>}; a file name two dependencies share stays as it is.
     *
     * @param files        the nested jars as the module is shown them, in order
     * @param dependencies the dependency of each
     * @return what to replace, by what
     */
    static Map<String, String> names(List<Path> files, List<Path> dependencies) {
        Map<String, String> names = new HashMap<>();
        Set<String> shared = new HashSet<>();
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            Path dependency = dependencies.get(i);
            if (!file.equals(dependency)) {
                names.put(file.toString(), dependency.toString());
            }
            String name = RunnerJarBuilder.fileName(file);
            String replacement = "the dependency " + dependency;
            String earlier = names.putIfAbsent(name, replacement);
            if (earlier != null && !earlier.equals(replacement)) {
                shared.add(name);
            }
        }
        names.keySet().removeAll(shared);
        return names;
    }

    /**
     * A message of the module with each name of {@link #names(List, List)} replaced, in one pass, so that a
     * replacement is never read again, and only where the name stands alone: not inside a longer file name or path.
     *
     * @param message the module's message
     * @param names   what to replace, by what
     * @return the message for the user
     */
    static String named(String message, Map<String, String> names) {
        if (names.isEmpty()) {
            return message;
        }
        String alternatives = names.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        Matcher matcher = Pattern.compile("(?<![\\w.\\-/\\\\])(?:" + alternatives + ")(?![\\w-]|\\.\\w)")
                .matcher(message);
        StringBuilder named = new StringBuilder(message.length());
        while (matcher.find()) {
            matcher.appendReplacement(named, Matcher.quoteReplacement(names.get(matcher.group())));
        }
        matcher.appendTail(named);
        return named.toString();
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
