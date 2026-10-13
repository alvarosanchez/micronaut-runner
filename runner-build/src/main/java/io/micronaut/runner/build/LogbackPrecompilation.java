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

import io.micronaut.aot.logback.LogbackPrecompiler;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The packaging step that precompiles the application's {@code logback.xml} with Micronaut AOT's
 * {@code micronaut-aot-logback}, the engine the Micronaut build plugins share, and reports what came of it.
 *
 * <p>The module decides what is compiled and documents its rules. A runner jar is the whole class path of the
 * application, so the call closes it: the module then generates the configurator, the class it falls back to Joran
 * with and the service file that registers the configurator, with no class-path guard. Every doubt resolves to
 * "nothing generated" and Logback configures itself with Joran at startup, as it would without this step. The build
 * never fails because of it: a stand-down is an informational line, and a failure, a generated name the application
 * layer already holds or a module that does not match the one this library was built against is a warning.</p>
 *
 * <p>This is the only class of this library that names a type of that module, whose API is internal to its callers
 * and may change in any release.</p>
 *
 * @since 1.0
 */
final class LogbackPrecompilation {

    /**
     * Runs just before the module is called. Tests replace it to force the {@link LinkageError} of a module
     * version that does not match; it does nothing in production.
     */
    static volatile Runnable beforeCall = () -> { };

    private static final String NOT_PRECOMPILED = "No Logback configuration was precompiled because ";

    private static final String JORAN_AT_STARTUP = "; Logback will configure itself with Joran at startup";

    private LogbackPrecompilation() {
    }

    /**
     * Calls the module, unless the {@code precompileLogback} option is off, and logs what it did: one informational
     * line, then each of its warnings; or one warning.
     *
     * @param spec        what is being packaged
     * @param classPath   every nested dependency, in class-path order
     * @param application the names the application layer holds
     * @param logger      where the informational line goes
     * @param warnings    where the warnings go
     * @return the generated entries, in the module's order; empty when nothing was generated
     */
    static Map<String, byte[]> precompile(RunnerJarSpec spec, List<Path> classPath, Collection<String> application,
                                          BuildLogger logger, Consumer<String> warnings) {
        if (!spec.precompileLogback()) {
            logger.info(NOT_PRECOMPILED + "the precompileLogback option is false" + JORAN_AT_STARTUP);
            return Map.of();
        }
        Map<String, byte[]> entries;
        try {
            beforeCall.run();
            LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(LogbackPrecompiler.Request.builder()
                    .applicationOutput(spec.applicationOutput())
                    .runtimeClasspath(classPath)
                    .targetRelease(ClassPathModel.RUNTIME_FEATURE)
                    .closedClassPath(true)
                    .build());
            LogbackPrecompiler.Status status = result.status();
            if (status == LogbackPrecompiler.Status.STOOD_DOWN) {
                logger.info(result.message());
                return Map.of();
            }
            if (status != LogbackPrecompiler.Status.GENERATED) {
                warnings.accept(result.message());
                return Map.of();
            }
            entries = new LinkedHashMap<>(result.entries());
            for (String name : entries.keySet()) {
                if (application.contains(name)) {
                    warnings.accept("The application output already carries '" + name + "'; no Logback"
                            + " configuration was precompiled and Logback will configure itself with Joran at startup");
                    return Map.of();
                }
            }
            logger.info(result.message());
            result.warnings().forEach(warnings);
        } catch (LinkageError e) {
            // The build tool resolved a micronaut-aot-logback whose API differs from the one this library was
            // built against. A VirtualMachineError is not a LinkageError, and propagates.
            warnings.accept(NOT_PRECOMPILED + "the micronaut-aot-logback on the build's class path does not match"
                    + " the one this packager was built against: " + e + JORAN_AT_STARTUP);
            return Map.of();
        }
        return entries;
    }
}
