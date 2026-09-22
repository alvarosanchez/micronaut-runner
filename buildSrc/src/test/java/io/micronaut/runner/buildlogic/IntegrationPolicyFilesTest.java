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
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationPolicyFilesTest {

    @Test
    void integrationModeAndMavenIdentityAreDeclaredTestInputs() throws IOException {
        String plugin = read("buildSrc/src/main/groovy/io.micronaut.build.internal.runner-testing.gradle");

        assertTrue(plugin.contains("inputs.property('integrationMode', integrationMode)"));
        assertTrue(plugin.contains("inputs.property('mavenVersion', mavenVersion)"));
        assertTrue(plugin.contains("inputs.property('mavenSha512', mavenSha512)"));
        assertTrue(plugin.contains("systemProperty 'runner.test.mode', integrationMode"));
        assertTrue(plugin.contains("systemProperty 'runner.test.mavenHome'"));
    }

    @Test
    void requiredScenariosUseConfiguredRepositoriesInsteadOfAnUnrelatedHostProbe() throws IOException {
        String samples = read("test-suite/src/test/java/io/micronaut/runner/suite/Samples.java");

        assertFalse(samples.contains("repo1.maven.org"));
        assertFalse(samples.contains("InetSocketAddress"));
        assertFalse(samples.contains("new Socket"));
    }

    @Test
    void mavenDistributionHasAnExplicitVersionAndSha512Pin() throws IOException {
        Properties pin = new Properties();
        try (var input = Files.newInputStream(repositoryRoot().resolve("gradle/maven-distribution.properties"))) {
            pin.load(input);
        }

        String version = pin.getProperty("version");
        String sha512 = pin.getProperty("sha512");
        assertTrue(version != null && !version.isBlank());
        assertTrue(sha512 != null && sha512.matches("[0-9a-f]{128}"));
        String catalog = read("gradle/libs.versions.toml");
        long matchingVersionLines = catalog.lines()
                .filter(line -> line.matches("maven\\s*=\\s*\"" + java.util.regex.Pattern.quote(version) + "\".*"))
                .count();
        assertEquals(1, matchingVersionLines, "distribution and Maven library versions must stay aligned");
    }

    @Test
    void ciRequiresIntegrationCoverageAndRetainsItsEvidence() throws IOException {
        String runnerCi = read(".github/workflows/runner-ci.yml");

        assertTrue(runnerCi.contains("-Prunner.integration=required"));
        assertTrue(runnerCi.contains("integration-scenario-evidence"));
        assertTrue(runnerCi.contains("test-suite/build/test-results/test/TEST-*.xml"));
        assertTrue(runnerCi.contains("test-suite/build/reports/integration-scenarios.txt"));
    }

    @Test
    void contributorDocumentationNamesRequiredAndOfflinePolicies() throws IOException {
        String contributing = read("CONTRIBUTING.md");

        assertTrue(contributing.contains("-Prunner.integration=required"));
        assertTrue(contributing.contains("-Prunner.integration=offline"));
        assertTrue(contributing.contains("RUNNER_TEST_OFFLINE=true"));
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
