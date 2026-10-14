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
package io.micronaut.runner.suite;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packages the {@code flyway} sample as a Runner JAR and checks that Flyway applies every migration at startup:
 * two from the application's own resources, which the archive stores in its application layer, and one from a
 * dependency, which it stores as a nested JAR.
 *
 * <p>Flyway lists the {@code JarFile} of the connection of each {@code db/migration} URL for the entries whose
 * names start with {@code db/migration/}. It found none of the application's migrations while that connection
 * described the outer archive, where they are named {@code MICRONAUT-INF/classes/db/migration/...}: Flyway created
 * an empty history table and the application's first query failed.</p>
 */
@Timeout(value = 30, unit = TimeUnit.MINUTES)
class FlywaySampleTest {

    private static final String TASK = ":micronautRunnerJar";
    private static final String ARCHIVE = "build/libs/flyway-0.1-all.jar";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);

    @BeforeAll
    static void assumeTheSuiteCanRun() {
        Samples.assumeTheBuildProvidedItsProperties();
        Samples.requireIntegrationScenario();
        Samples.requirePublishedArtifact("io/micronaut/runner/standalone/"
                + "io.micronaut.runner.standalone.gradle.plugin/" + Samples.VERSION
                + "/io.micronaut.runner.standalone.gradle.plugin-" + Samples.VERSION + ".pom");
    }

    @Test
    void appliesTheMigrationsOfTheApplicationLayerAndOfANestedJar() throws IOException {
        Path sample = Samples.sample("flyway");
        Path archive = sample.resolve(ARCHIVE);
        Samples.gradle(sample, "clean", TASK);
        assertTrue(Files.isRegularFile(archive), () -> "the plugin did not write " + archive);
        assertMigrationsAreInBothLayers(archive);

        int port = Samples.freePort();
        try (ForkedApplication application = ForkedApplication.start(archive, sample,
                Map.of("SERVER_PORT", Integer.toString(port)))) {
            String base = Samples.loopback(port);
            String migrations = application.awaitBody(URI.create(base + "/migrations"), STARTUP_TIMEOUT);
            assertEquals("1,2,3", migrations, () -> "Flyway did not apply every migration" + application.describe());
            assertEquals("Music,Books", application.awaitBody(URI.create(base + "/genres"), STARTUP_TIMEOUT),
                    application::describe);
            assertEquals("From a dependency", application.awaitBody(URI.create(base + "/reviews"), STARTUP_TIMEOUT),
                    application::describe);
        }
    }

    /**
     * The test is only worth something if the archive really has migrations in both places.
     */
    private static void assertMigrationsAreInBothLayers(Path archive) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            assertNotNull(jar.getEntry("MICRONAUT-INF/classes/db/migration/V1__schema.sql"));
            assertNotNull(jar.getEntry("MICRONAUT-INF/classes/db/migration/V2__genres.sql"));
            JarEntry nested = jar.getJarEntry("MICRONAUT-INF/lib/migrations-0.1.jar");
            assertNotNull(nested, "the dependency is not a nested jar of the archive");
            boolean found = false;
            try (InputStream in = jar.getInputStream(nested); JarInputStream dependency = new JarInputStream(in)) {
                for (JarEntry entry = dependency.getNextJarEntry(); entry != null; entry = dependency.getNextJarEntry()) {
                    found |= entry.getName().equals("db/migration/V3__reviews.sql");
                }
            }
            assertTrue(found, "the nested jar does not carry its migration");
        }
    }
}
