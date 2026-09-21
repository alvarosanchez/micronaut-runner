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
package io.micronaut.runner.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packages the fixture with every Gradle version the build declares in {@code runner.testkit.gradleVersions},
 * which reaches the tests as the system property {@code runner.test.gradleVersions}.
 *
 * <p>A version that cannot run at all on the JDK executing the build is skipped rather than failed: the
 * matrix is a list of the versions the plugin supports, not a list of the versions any one CI JDK can
 * start. Gradle 9.1, for instance, runs on JDK 25 but not on JDK 26.</p>
 */
class GradleVersionMatrixFunctionalTest extends AbstractFunctionalTest {

    /** Holds one fresh project per Gradle version, so the versions do not share build state. */
    @TempDir
    static Path root;

    /**
     * The first Gradle version able to run on each JDK. A JDK missing from this table stops the matrix with
     * an assumption rather than a failure, because the right answer is to extend the table, not to guess.
     */
    private static final Map<Integer, String> FIRST_GRADLE_ON_JDK = Map.of(
            21, "8.5",
            22, "8.8",
            23, "8.10",
            24, "8.14",
            25, "9.1",
            26, "9.4");

    /** The system property the build fills from the {@code runner.testkit.gradleVersions} property. */
    private static final String VERSIONS_PROPERTY = "runner.test.gradleVersions";

    /**
     * One test per declared Gradle version: package the fixture with it and run what came out.
     *
     * @return the dynamic tests
     */
    @TestFactory
    List<DynamicTest> packagesOnEveryDeclaredGradleVersion() {
        List<String> versions = declaredVersions();
        if (versions.isEmpty()) {
            return List.of(DynamicTest.dynamicTest("no Gradle versions declared", () ->
                    Assumptions.abort("The build declared no versions in " + VERSIONS_PROPERTY)));
        }
        List<DynamicTest> tests = new ArrayList<>(versions.size());
        for (String version : versions) {
            tests.add(DynamicTest.dynamicTest("Gradle " + version, () -> packageWith(version)));
        }
        return tests;
    }

    /**
     * Packages the fixture with one Gradle version, unless that version cannot run on this JDK.
     *
     * @param version the Gradle version
     * @throws Exception if the fixture cannot be written, packaged or run
     */
    private static void packageWith(String version) throws Exception {
        int jdk = Runtime.version().feature();
        String first = FIRST_GRADLE_ON_JDK.get(jdk);
        Assumptions.assumeTrue(first != null, () -> "Java " + jdk + " is not in this test's table of the"
                + " first Gradle version able to run on each JDK; add it to FIRST_GRADLE_ON_JDK");
        Assumptions.assumeTrue(compare(version, first) >= 0,
                () -> "Gradle " + version + " cannot run on Java " + jdk + ", which needs " + first + " or later");

        Path directory = writeFixture(root.resolve("gradle-" + version));

        BuildResult result = runner(directory, "micronautRunnerJar").withGradleVersion(version).build();

        assertEquals(TaskOutcome.SUCCESS, outcomeOf(result, RUNNER_JAR_TASK));
        Path archive = directory.resolve(DEFAULT_ARCHIVE);
        assertTrue(Files.isRegularFile(archive),
                () -> "Gradle " + version + " produced no archive:\n" + result.getOutput());
        runJarSuccessfully(archive);
    }

    /**
     * The versions the build declared, in the order it declared them.
     *
     * @return the versions, possibly empty
     */
    private static List<String> declaredVersions() {
        String declared = System.getProperty(VERSIONS_PROPERTY, "");
        List<String> versions = new ArrayList<>();
        for (String candidate : declared.split(",", -1)) {
            String version = candidate.trim();
            if (!version.isEmpty()) {
                versions.add(version);
            }
        }
        return versions;
    }

    /**
     * Compares two dotted version strings component by component, treating a missing component as zero.
     *
     * @param left  the first version
     * @param right the second version
     * @return a negative number, zero or a positive number as the first is older, equal or newer
     */
    private static int compare(String left, String right) {
        String[] leftParts = left.split("\\.", -1);
        String[] rightParts = right.split("\\.", -1);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int difference = Integer.compare(
                    numberAt(leftParts, i),
                    numberAt(rightParts, i));
            if (difference != 0) {
                return difference;
            }
        }
        return 0;
    }

    /**
     * The leading number of one component of a version, or zero when there is no such component.
     *
     * @param parts the components
     * @param index the component to read
     * @return the number
     */
    private static int numberAt(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        String part = parts[index];
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(part.substring(0, end));
    }
}
