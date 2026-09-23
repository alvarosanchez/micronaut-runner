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
package io.micronaut.runner.benchmarks;

import java.util.List;

/**
 * A deterministic synthetic archive shape used by the representative runtime and packaging benchmarks.
 *
 * @param name                    stable command-line/JMH identifier
 * @param jarCount                dependency jars in class-path order
 * @param entriesPerJar           generated classes per dependency, excluding metadata/resources
 * @param classPayloadBytes       approximate extra constant-pool payload per generated class
 * @param streamResourceBytes     bytes in the resource streamed by each runtime trial
 * @param serviceDescriptorsPerJar service descriptor files contributed by each dependency
 * @param manifestPackageSections named package sections in each dependency manifest
 * @param duplicateNames          whether dependencies contribute the same logical resource names
 * @param multiReleaseEntries     whether dependencies carry base and Java 25 variants
 */
record WorkloadShape(String name,
                     int jarCount,
                     int entriesPerJar,
                     int classPayloadBytes,
                     int streamResourceBytes,
                     int serviceDescriptorsPerJar,
                     int manifestPackageSections,
                     boolean duplicateNames,
                     boolean multiReleaseEntries) {

    private static final List<WorkloadShape> STANDARD = List.of(
            new WorkloadShape("no-manifest", 8, 48, 256, 64 * 1024, 1, 0, true, true),
            new WorkloadShape("small", 16, 80, 512, 64 * 1024, 1, 2, true, true),
            new WorkloadShape("representative", 48, 160, 2 * 1024, 1024 * 1024, 2, 12, true, true),
            new WorkloadShape("wide", 300, 24, 768, 16 * 1024, 1, 4, true, true));

    WorkloadShape {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("workload name is required");
        }
        if (jarCount < 1 || entriesPerJar < 1 || classPayloadBytes < 0 || streamResourceBytes < 1
                || serviceDescriptorsPerJar < 1 || manifestPackageSections < 0) {
            throw new IllegalArgumentException("invalid workload shape " + name);
        }
    }

    static List<WorkloadShape> standard() {
        return STANDARD;
    }

    static WorkloadShape named(String name) {
        return STANDARD.stream()
                .filter(shape -> shape.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown workload '" + name
                        + "'; expected one of " + STANDARD.stream().map(WorkloadShape::name).toList()));
    }
}
