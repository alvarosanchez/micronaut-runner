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

    @Test
    void mmapGuidanceRequiresLifetimeImmutabilityAndLimitsStalenessChecks() throws IOException {
        String runtime = read("src/main/docs/guide/runtime.adoc");
        String format = read("src/main/docs/guide/format.adoc");
        String archiveSource = read("runner-launcher/src/main/java/io/micronaut/runner/ArchiveSource.java");
        String index = read("runner-launcher/src/main/java/io/micronaut/runner/Index.java");

        assertTrue(runtime.contains("immutable from the moment its JVM opens it until that JVM terminates"));
        assertTrue(runtime.contains("not ongoing file monitoring"));
        assertTrue(runtime.contains("versioned or content-addressed name"));
        assertTrue(runtime.contains("On Windows"));
        assertTrue(runtime.contains("background threads may still load classes"));
        assertTrue(format.contains("not ongoing monitoring or an integrity boundary"));
        assertTrue(archiveSource.contains("cannot guarantee safe access after a concurrent"));
        assertTrue(index.contains("a successful result is then cached"));
        assertFalse(archiveSource.contains("before any stale offset is dereferenced"));
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
