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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The packaging step that generates micronaut-core's service index with Micronaut AOT's
 * {@code micronaut-aot-service-index}, the generator the Micronaut build plugins share, and reports what came of it.
 *
 * <p>The index lists, for every service type, the names micronaut-core's scan finds, so that the application does
 * not scan {@code META-INF/services} and {@code META-INF/micronaut} when it starts. micronaut-core builds it itself,
 * from the class path it is shown, which is the class path {@code RunnerClassLoader} gives the application: a view
 * jar of the application layer, holding its service files and the merged {@code META-INF/micronaut} copy as the
 * archive stores it, then the nested jars in index order. The index lists no class path, because the names the
 * jars have at run time are not known here, so micronaut-core compares none.</p>
 *
 * <p>Every doubt resolves to "no index", and micronaut-core then scans at startup, as it would without this step.
 * The build never fails because of it: the module's reason is an informational line, or a warning when it failed,
 * when a dependency already carries an index, or when the module does not match the one this library was built
 * against.</p>
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

    private static final String NOT_GENERATED = "No service index was generated because ";

    private static final String SCAN = "; the application scans its class path for services at run time";

    /**
     * Runs just before the module is called. Tests replace it to force the {@link LinkageError} of a module
     * version that does not match; it does nothing in production.
     */
    static volatile Runnable beforeCall = () -> { };

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
     * Calls the module and logs what it did: one informational line, or one warning.
     *
     * @param view          the view jar of the application layer, which holds what the module returns
     * @param classPath     the view jar, then every nested jar as it was staged, in index order
     * @param dependencies  what a message names instead of the file name of a staged nested jar: the dependency
     * @param carriedIndex  the dependency that already carries an index loader, or {@code null}; the module then
     *                      stands down, and micronaut-core uses that index, which is worth a warning of its own
     * @param logger        where the informational line goes
     * @param warnings      where a warning goes
     * @return what to write into the application layer and delete from it, whatever the module decided
     */
    static Outcome generate(Path view, List<Path> classPath, Map<String, String> dependencies, String carriedIndex,
                            BuildLogger logger, Consumer<String> warnings) {
        try {
            beforeCall.run();
            ServiceIndexGenerator.Request.Builder request = ServiceIndexGenerator.Request.builder();
            for (Path entry : classPath) {
                request.entry(entry);
            }
            ServiceIndexGenerator.Result result = ServiceIndexGenerator.generate(request.holder(view).build());
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
