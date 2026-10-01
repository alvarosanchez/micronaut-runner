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
package io.micronaut.runner.build.aotcache;

import io.micronaut.runner.build.AotTarget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The defaults both plugins start from, their validation, and the target's names. */
class AotCacheSettingsTest {

    @Test
    void theDefaults() {
        AotCacheSettings defaults = AotCacheSettings.defaults();

        assertFalse(defaults.strict());
        assertEquals(List.of(), defaults.jvmArgs());
        assertEquals(10, defaults.verifyProbes());
        assertEquals(0.95, defaults.minCoverage());
        assertTrue(defaults.enforceCoverage());
        assertEquals(values(defaults), values(AotCacheSettings.builder().build()));
        assertEquals(List.of(true, List.of("-Xmx512m"), 3, 0.5, false), values(AotCacheSettings.builder()
                .strict(true).jvmArgs(List.of("-Xmx512m")).verifyProbes(3).minCoverage(0.5).enforceCoverage(false)
                .build()));
        assertEquals(List.of(false, List.of(), 10, 0.95, false), values(defaults.withEnforceCoverage(false)));
    }

    @Test
    void anUnusableValueFailsNamingTheSetting() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AotCacheSettings.builder().verifyProbes(0)
                .build()).getMessage().contains("verifyProbes"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AotCacheSettings.builder().minCoverage(1.5)
                .build()).getMessage().contains("minCoverage"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AotCacheSettings.builder().minCoverage(Double.NaN)
                .build()).getMessage().contains("minCoverage"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AotCacheSettings.builder()
                .jvmArgs(List.of(" ")).build()).getMessage().contains("jvmArgs"));
        for (String reserved : List.of("-XX:AOTCache=other.aot", "-XX:AOTMode=on", "-Xshare:off",
                "-XX:SharedArchiveFile=app.jsa", "-cp")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> AotCacheSettings.builder().jvmArgs(List.of(reserved)).build());
            assertTrue(failure.getMessage().contains(reserved), failure.getMessage());
        }
        assertEquals(List.of("-XX:+UseSerialGC", "-XX:AOTCacheX"),
                AotCacheSettings.builder().jvmArgs(List.of("-XX:+UseSerialGC", "-XX:AOTCacheX")).build().jvmArgs());
    }

    @Test
    void theTargetIsParsedByItsBuildScriptName() {
        assertSame(AotTarget.LAYOUT, AotTarget.parse("layout"));
        assertSame(AotTarget.SINGLE_JAR, AotTarget.parse("singleJar"));
        assertSame(AotTarget.LAYOUT, AotTarget.DEFAULT);
        assertEquals("singleJar", AotTarget.SINGLE_JAR.value());
        for (String value : new String[] {"LAYOUT", "single-jar", "", null}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> AotTarget.parse(value));
            assertTrue(failure.getMessage().contains("'layout'") && failure.getMessage().contains("'singleJar'"),
                    failure.getMessage());
        }
    }

    /** Every setting, in a list that compares by value. */
    private static List<Object> values(AotCacheSettings settings) {
        return List.of(settings.strict(), settings.jvmArgs(), settings.verifyProbes(), settings.minCoverage(),
                settings.enforceCoverage());
    }
}
