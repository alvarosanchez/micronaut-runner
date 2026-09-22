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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Separate-process fixture for manifest reproducibility tests. */
public final class ManifestReproducibilityProbe {

    private ManifestReproducibilityProbe() {
    }

    /**
     * Builds the same archive from either forward or reverse attribute iteration order.
     *
     * @param args output, application inputs, dependencies and order
     * @throws IOException if the archive cannot be built
     */
    public static void main(String[] args) throws IOException {
        Map<String, String> attributes = new LinkedHashMap<>();
        if ("forward".equals(args[6])) {
            attributes.put("Alpha-Attribute", "1");
            attributes.put("Bravo-Attribute", "2");
            attributes.put("Charlie-Attribute", "3");
            attributes.put("Delta-Attribute", "4");
            attributes.put("Echo-Attribute", "5");
        } else {
            attributes.put("Echo-Attribute", "5");
            attributes.put("Delta-Attribute", "4");
            attributes.put("Charlie-Attribute", "3");
            attributes.put("Bravo-Attribute", "2");
            attributes.put("Alpha-Attribute", "1");
        }
        RunnerJarSpec spec = RunnerJarSpec.builder()
                .mainClass("com.example.Application")
                .applicationOutput(List.of(Path.of(args[1]), Path.of(args[2])))
                .dependencies(List.of(
                        new Dependency(Path.of(args[3]), "com.example:dep-lib:2.0.1"),
                        new Dependency(Path.of(args[4]), "com.example:mr-lib:1.0"),
                        new Dependency(Path.of(args[5]), null)))
                .output(Path.of(args[0]))
                .canonicalManifestAttributes(attributes)
                .build();
        RunnerJarBuilder.build(spec, BuildLogger.noOp());
    }
}