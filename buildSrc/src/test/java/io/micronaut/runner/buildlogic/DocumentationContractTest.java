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
package io.micronaut.runner.buildlogic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentationContractTest {

    @Test
    void publicDocumentationBoundsLookupAndPerformanceClaims() throws IOException {
        String readme = read("README.md");
        String introduction = read("src/main/docs/guide/introduction.adoc");
        String compatibility = read("src/main/docs/guide/compatibility.adoc");
        String benchmarks = read("src/main/docs/guide/benchmarks.adoc");

        assertFalse(readme.contains("single hash probe"));
        assertFalse(introduction.contains("single hash probe"));
        assertTrue(readme.contains("bounded linear-probe sequence"));
        assertTrue(readme.contains("`META-INF/micronaut/**` metadata is deliberately merged"));
        assertTrue(compatibility.contains("Deliberate Micronaut metadata exception"));
        assertTrue(compatibility.contains("returns one merged URL"));

        assertFalse(benchmarks.contains("12% below"));
        assertFalse(benchmarks.contains("15% below"));
        assertFalse(benchmarks.contains("worth about 35%"));
        assertFalse(benchmarks.contains("worth about\n2%"));
        assertTrue(benchmarks.contains("immutable source revision"));
        assertTrue(benchmarks.contains("raw `results.json`"));
        assertTrue(benchmarks.contains("matched ablation"));
    }

    @Test
    void quickStartPutsJava25ChecksBeforeEitherBuildToolFlow() throws IOException {
        String guide = read("src/main/docs/guide/quickStart.adoc");
        int prerequisites = guide.indexOf("== Prerequisites");
        int gradle = guide.indexOf("== Gradle");
        int maven = guide.indexOf("== Maven");

        assertTrue(prerequisites >= 0, "the quick start must have a prerequisites section");
        assertTrue(prerequisites < gradle, "prerequisites must precede the Gradle flow");
        assertTrue(prerequisites < maven, "prerequisites must precede the Maven flow");
        assertTrue(guide.contains("JDK 25"));
        assertTrue(guide.contains("Java 25"));
        assertTrue(guide.contains("JAVA_HOME"));
        assertTrue(guide.contains("build-tool JVM"));
        assertTrue(guide.contains("toolchain"));
        assertTrue(guide.contains("deployment"));
        assertTrue(guide.contains("java -version"));
        assertTrue(guide.contains("./gradlew --version"));
        assertTrue(guide.contains("mvn -version"));
    }

    @Test
    void entryStubJavadocsMatchImplementedDefaultsAndFallback() throws IOException {
        String spec = read("runner-build/src/main/java/io/micronaut/runner/build/RunnerJarSpec.java");
        String gradlePlugin = read("runner-gradle-plugin/src/main/java/io/micronaut/runner/gradle/"
                + "MicronautRunnerPlugin.java");
        String mavenPlugin = read("runner-maven-plugin/src/main/java/io/micronaut/runner/maven/PackageMojo.java");

        assertTrue(spec.contains("private boolean entryStub;"), "the raw boolean builder default is false");
        assertTrue(gradlePlugin.contains("task.getEntryStub().convention(Boolean.TRUE)"));
        assertTrue(mavenPlugin.contains("defaultValue = \"true\""));
        assertFalse(spec.contains("Generating the stub is not implemented yet"));
        assertFalse(spec.contains("not implemented yet and is ignored"));
        assertTrue(spec.contains("The raw builder defaults to {@code false}"));
        assertTrue(spec.contains("Gradle and Maven plugins default to {@code true}"));
        assertTrue(spec.contains("multi-release variant"));
        assertTrue(spec.contains("reflective fallback"));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(repositoryRoot().resolve(relative));
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    && Files.isDirectory(current.resolve("test-suite"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the repository root from " + System.getProperty("user.dir"));
    }
}
