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
package io.micronaut.runner.generated.services;

import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.core.optim.StaticOptimizations;

/**
 * Hands Micronaut the service table the packager computed, so that a service lookup reads names from a
 * constant instead of scanning the class path.
 *
 * <p>Micronaut calls {@link #load()} once, inside the static initialiser of {@code StaticOptimizations}, on
 * whichever thread first touches {@code SoftServiceLoader}. It therefore only parses constants: it never
 * forks, waits, reads a resource or initialises {@code SoftServiceLoader} itself, which would read the
 * optimization before it is set. It never returns {@code null} either, because {@code StaticOptimizations}
 * calls {@code getClass()} on what it gets: a table that must not be used becomes a
 * {@link RunnerServicesDisabled} marker, which nothing reads.</p>
 *
 * <p>This class and its package are copied into each application as they are, so they use no lambda, method
 * reference or {@code invokedynamic} string concatenation: nothing here bootstraps a call site at startup.</p>
 *
 * @since 1.0
 */
public final class RunnerStaticServices implements StaticOptimizations.Loader<Object> {

    /** Set to {@code false} to ignore the table: Micronaut then scans for services as it does without one. */
    static final String ENABLED_PROPERTY = "micronaut.runner.static-services";

    /** Set to {@code true} to compare the table with Micronaut's own scan on the first lookup. */
    static final String VERIFY_PROPERTY = "micronaut.runner.static-services.verify";

    /** Set to {@code true} to print every lookup the table answers to standard error. */
    static final String TRACE_PROPERTY = "micronaut.runner.static-services.trace";

    /** The launcher's property that selects the parent of the runner class loader. */
    static final String PARENT_PROPERTY = "micronaut.runner.parent";

    /** The value of {@link #PARENT_PROPERTY} that puts the real class path in front of the archive. */
    static final String PARENT_SYSTEM = "system";

    /** The name of the class loader a runner jar's launcher creates. */
    static final String RUNNER_LOADER = "micronaut-runner";

    /** What every line this package prints starts with. */
    static final String LOG_PREFIX = "[micronaut-runner] static services";

    /**
     * Called by {@link java.util.ServiceLoader}.
     */
    public RunnerStaticServices() {
    }

    @Override
    public Object load() {
        if ("false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY))) {
            return RunnerServicesDisabled.INSTANCE;
        }
        if (PARENT_SYSTEM.equals(System.getProperty(PARENT_PROPERTY))) {
            // The real class path is then visible to Micronaut's scan, and this table knows nothing about it.
            return RunnerServicesDisabled.INSTANCE;
        }
        try {
            ClassLoader loader = RunnerStaticServices.class.getClassLoader();
            return new SoftServiceLoader.Optimizations(RunnerServiceMap.parse(loader,
                    "true".equals(System.getProperty(VERIFY_PROPERTY)),
                    "true".equals(System.getProperty(TRACE_PROPERTY))));
        } catch (LinkageError | RuntimeException e) {
            // A micronaut-core that does not have the shape this package was compiled against, or a table
            // that does not parse. Whatever leaves this method is thrown inside a static initialiser of
            // Micronaut, which would stop the application; without a table it starts as it always did.
            return RunnerServicesDisabled.INSTANCE;
        }
    }
}
