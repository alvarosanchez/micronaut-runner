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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The report's summary line, which both build plugins log, and the JAR it names in the JSON. */
class AotCacheReportTest {

    @Test
    void theSummaryReportsTheCoverageTheProbesAndHowToLaunch() {
        AotCacheReport report = report(Map.of("target", "layout"), AotCacheReport.PASSED);

        assertEquals("Trained and verified the JDK AOT cache for the layout target: 97.3% of the classes and 812 of"
                + " 830 io.micronaut classes from the cache, 0 of 10 strict probes failed. Launch it from its"
                + " directory with: java @app.jvmopts -jar app.jar", report.summary());
    }

    @Test
    void theSummaryNamesEveryLabelAndAFailedVerdict() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("target", "singleJar");
        labels.put("variant", "control");

        assertTrue(report(labels, AotCacheReport.FAILED).summary().startsWith("Failed to verify the JDK AOT cache for"
                + " the singleJar target and the control variant: "));
        assertTrue(report(Map.of(), AotCacheReport.PASSED).summary().startsWith("Trained and verified the JDK AOT"
                + " cache: "));
    }

    @Test
    void theJsonNamesTheJarAfterTheLabels() {
        String json = report(Map.of("target", "layout"), AotCacheReport.PASSED).toJson();

        assertTrue(json.contains("\"arch\": \"aarch64\",\n  \"target\": \"layout\",\n  \"jar\": \"app.jar\",\n"
                + "  \"creationFlags\": []"), json);
    }

    private static AotCacheReport report(Map<String, String> labels, String verdict) {
        return new AotCacheReport("25.0.4.1", "Mac OS X", "aarch64", labels, "app.jar", List.of(),
                AotCacheReport.STOP_JCMD, 10, 0, 1000, 973, 0.973, 830, 812, List.of(), 3, List.of(), List.of(),
                verdict);
    }
}
