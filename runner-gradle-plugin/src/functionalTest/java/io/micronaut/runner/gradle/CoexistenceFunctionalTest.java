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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interim plugin refuses to run beside micronaut-gradle-plugin's {@code io.micronaut.runner}, whatever the
 * order the two are applied in, and every order fails before any task runs.
 *
 * <p>{@code io.micronaut.runner} is stubbed by a precompiled script plugin in the fixture's {@code buildSrc}:
 * like the real one, it creates {@code micronaut} if it is absent, and then {@code micronaut.runner}. The build
 * declares the interim plugin with {@code apply false} and applies the plugins in the order the
 * {@code pluginOrder} Gradle property lists, as {@link ShadowCollisionFunctionalTest} picks its Shadow id.</p>
 */
class CoexistenceFunctionalTest extends AbstractFunctionalTest {

    /** The id of micronaut-gradle-plugin's Runner plugin, which the fixture's {@code buildSrc} stubs. */
    private static final String UPSTREAM_ID = "io.micronaut.runner";

    /** The first sentence of the migration message. */
    private static final String MIGRATION = "The io.micronaut.runner plugin from micronaut-gradle-plugin builds"
            + " the Runner JAR in this build. Remove io.micronaut.runner.standalone";

    /** Applies the plugins in the order given on the command line. */
    private static final String APPLY_IN_ORDER = """
            providers.gradleProperty('pluginOrder').get().split(',').each { id ->
                apply plugin: id
            }
            """;

    /**
     * Upstream first fails in the interim's {@code apply}, upstream last once upstream's {@code apply} has
     * returned, both with the migration message. When a Micronaut plugin comes first, the interim has already
     * added {@code micronaut.runner}, so upstream's own {@code create("runner", ...)} fails first.
     *
     * @param directory a fresh project directory
     * @throws IOException if the fixture cannot be written
     */
    @Test
    void theInterimPluginRefusesToRunBesideTheUpstreamPlugin(@TempDir Path directory) throws IOException {
        writeSettings(directory, "");
        write(directory.resolve("build.gradle"),
                buildScript("id '" + PLUGIN_ID + "' apply false", APPLY_IN_ORDER));
        writeApplication(directory);
        writeMicronautStubs(directory);

        String upstreamFirst = failWhileConfiguring(directory, UPSTREAM_ID + "," + PLUGIN_ID);
        assertTrue(upstreamFirst.contains("Failed to apply plugin '" + PLUGIN_ID + "'")
                        && upstreamFirst.contains(MIGRATION),
                () -> "applying the interim after upstream did not fail with the migration message:\n"
                        + upstreamFirst);

        String upstreamLast = failWhileConfiguring(directory, PLUGIN_ID + "," + UPSTREAM_ID);
        assertTrue(upstreamLast.contains("Failed to apply plugin '" + UPSTREAM_ID + "'")
                        && upstreamLast.contains(MIGRATION),
                () -> "applying upstream after the interim did not fail with the migration message:\n"
                        + upstreamLast);

        String micronautFirst = failWhileConfiguring(directory, "micronaut-stub," + PLUGIN_ID + "," + UPSTREAM_ID);
        assertTrue(micronautFirst.contains("Failed to apply plugin '" + UPSTREAM_ID + "'")
                        && micronautFirst.contains("Cannot add extension with name 'runner'"),
                () -> "upstream's own extension did not fail to apply:\n" + micronautFirst);
    }

    /**
     * Runs a build that must fail while the build script is evaluated, before any task runs.
     *
     * @param directory the project directory
     * @param order     the plugin ids, in the order they are applied
     * @return the build output
     */
    private static String failWhileConfiguring(Path directory, String order) {
        String output = buildAndFail(directory, "help", "-PpluginOrder=" + order).getOutput();
        assertTrue(output.contains("A problem occurred evaluating root project"),
                () -> "applying " + order + " did not fail while the build was configured:\n" + output);
        return output;
    }
}
