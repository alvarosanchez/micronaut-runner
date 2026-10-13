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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/**
 * The micronaut-core jars of the test class path, which the service index tests package into fixture applications
 * as dependencies. The build puts the catalog's micronaut-core there, which has the service index.
 */
final class MicronautCoreJars {

    private MicronautCoreJars() {
    }

    /**
     * @return micronaut-core and slf4j-api, which is what micronaut-core needs to build and register an index
     */
    static List<Path> core() {
        return List.of(jar("micronaut-core-"), jar("slf4j-api-"));
    }

    /**
     * @return micronaut-core, micronaut-inject and what micronaut-inject links against, micronaut-core first
     */
    static List<Path> inject() {
        List<Path> jars = new ArrayList<>(core());
        jars.add(1, jar("micronaut-inject-"));
        for (String prefix : List.of("jakarta.inject-api-", "jakarta.annotation-api-")) {
            optionalJar(prefix).ifPresent(jars::add);
        }
        return jars;
    }

    /**
     * @return the version the manifest of micronaut-core states
     */
    static String version() {
        try (JarFile core = new JarFile(jar("micronaut-core-").toFile())) {
            return core.getManifest().getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Finds a jar of the test class path by the start of its file name.
     *
     * @param prefix what the file name starts with, such as {@code micronaut-core-}
     * @return the jar
     */
    static Path jar(String prefix) {
        return optionalJar(prefix)
                .orElseThrow(() -> new IllegalStateException("No " + prefix + "*.jar on the test class path"));
    }

    private static Optional<Path> optionalJar(String prefix) {
        for (String element : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path = Path.of(element);
            String name = path.getFileName().toString();
            if (name.startsWith(prefix) && name.endsWith(".jar") && name.length() > prefix.length()
                    && Character.isDigit(name.charAt(prefix.length()))) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }
}
