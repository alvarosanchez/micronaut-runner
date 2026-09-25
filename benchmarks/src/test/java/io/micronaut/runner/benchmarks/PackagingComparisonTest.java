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

import io.micronaut.runner.benchmarks.PackagingComparison.Attempt;
import io.micronaut.runner.benchmarks.SampleBuild.ComparisonSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain unit tests: no nested Gradle build and no forked JVM, so no benchmark-integration tag. */
class PackagingComparisonTest {

    private static final ComparisonSpec DEFAULTS = PackagingComparison.COMPARISONS.getFirst();

    @Test
    void markerKeepsSpacesInTheArchivePathAndIsRequired() throws Exception {
        String output = """
                > Task :shadowJar
                PACKAGING_TASK :shadowJar 812.5 /tmp/a b/x.jar

                BUILD SUCCESSFUL in 1s
                """;
        PackagingComparison.Marker marker = PackagingComparison.marker(output, ":shadowJar");
        assertEquals(812.5, marker.millis());
        assertEquals(Path.of("/tmp/a b/x.jar"), marker.archive());

        // Another task's marker does not count, even one whose path starts with the requested one.
        String upToDate = """
                > Task :shadowJar UP-TO-DATE
                > Task :shadowJarStored
                PACKAGING_TASK :shadowJarStored 700.0 /tmp/y.jar
                BUILD SUCCESSFUL in 1s
                """;
        IOException missing = assertThrows(IOException.class, () -> PackagingComparison.marker(upToDate, ":shadowJar"));
        assertTrue(missing.getMessage().contains("No PACKAGING_TASK line for :shadowJar"), missing.getMessage());
        assertTrue(missing.getMessage().contains("> Task :shadowJar UP-TO-DATE"), "the output tail is reported");
    }

    @Test
    void variantStatisticsExcludeWarmUpsAndComparisonsPairByRound() {
        List<Attempt> attempts = new ArrayList<>();
        for (int round = 0; round < PackagingComparison.WARMUP_ROUNDS; round++) {
            attempts.add(attempt(round, "rerun", "runner-stored", 10_000));
            attempts.add(attempt(round, "rerun", "shadow", 1));
        }
        // Rounds 3, 4, 5. Candidate in round order, baseline listed 4, 5, 3 as a shuffle would leave it.
        attempts.add(attempt(3, "rerun", "runner-stored", 100));
        attempts.add(attempt(4, "rerun", "runner-stored", 300));
        attempts.add(attempt(5, "rerun", "runner-stored", 200));
        attempts.add(attempt(4, "rerun", "shadow", 310));
        attempts.add(attempt(5, "rerun", "shadow", 400));
        attempts.add(attempt(3, "rerun", "shadow", 150));
        attempts.add(attempt(3, "edit", "runner-stored", 5_000));

        Statistics candidate = PackagingComparison.variant(attempts, "rerun", "runner-stored",
                Attempt::taskMillis, 1L);
        assertEquals(3, candidate.count());
        assertEquals(200.0, candidate.median());
        assertEquals(100.0, candidate.min());
        assertEquals(300.0, candidate.max());

        // By round: -50, -10, -200. By position it would be -100, and the difference of medians -110.
        Statistics difference = PackagingComparison.comparison(attempts, "rerun", DEFAULTS, Attempt::taskMillis, 1L);
        assertEquals(3, difference.count());
        assertEquals(-50.0, difference.median());
        assertEquals(-200.0, difference.min());
        assertEquals(-10.0, difference.max());
        assertEquals(-50.0 + 1_000, PackagingComparison.comparison(attempts, "rerun", DEFAULTS,
                Attempt::wallMillis, 1L).median(), "the candidate's wall time is its task time + 1000 ms");
    }

    @Test
    void summaryReportsEveryVariantAndComparisonWithAnIntervalFromTenRounds(@TempDir Path directory)
            throws Exception {
        List<Attempt> attempts = new ArrayList<>();
        int rounds = PackagingComparison.WARMUP_ROUNDS + 10;
        for (int round = 0; round < rounds; round++) {
            boolean warmup = round < PackagingComparison.WARMUP_ROUNDS;
            for (String scenario : PackagingComparison.SCENARIOS) {
                for (String variant : PackagingComparison.VARIANTS) {
                    double base = variant.startsWith("runner-") ? 250 : 750;
                    double millis = warmup ? 99_999 : base + round;
                    attempts.add(new Attempt(round, warmup, scenario, variant, millis, millis + 600, 1_000));
                }
            }
        }
        Collections.shuffle(attempts, new Random(7));
        Map<String, DeploymentSize> sizes = new LinkedHashMap<>();
        for (String variant : PackagingComparison.VARIANTS) {
            Path archive = directory.resolve(variant + ".jar");
            Files.write(archive, new byte[variant.equals("shadow") ? 1_000 : 2_000]);
            sizes.put(variant, DeploymentSize.measure(DeploymentSize.input("archive", archive)));
        }

        String summary = PackagingComparison.summary("HEADER.", attempts, sizes, 1L);

        assertTrue(summary.contains("this is the cold packaging path; there is no separate cold scenario"), summary);
        assertFalse(summary.contains("99999") || summary.contains("99,999"), "warm-ups never reach the summary");
        for (String scenario : PackagingComparison.SCENARIOS) {
            String section = summary.substring(summary.indexOf("## `" + scenario + "`"));
            assertTrue(section.contains(
                    "| `runner-stored` | 10 | 257.5 (253.0–262.0) | 858 (853–862) | 2,000 | 2.00 |"), section);
            assertTrue(section.contains(
                    "| `shadow` | 10 | 757.5 (753.0–762.0) | 1358 (1353–1362) | 1,000 | 1.00 |"), section);
            for (ComparisonSpec spec : PackagingComparison.COMPARISONS) {
                String row = section.lines()
                        .filter(line -> line.startsWith("| " + spec.label() + ": `" + spec.candidate() + "` − `"
                                + spec.baseline() + "` | 10 | "))
                        .findFirst().orElseThrow(() -> new AssertionError(spec + " missing from\n" + section));
                // Every comparison is a Runner row against a Shadow row: 500 ms faster in each round.
                assertTrue(row.contains("**-500.0** [-500.0, -500.0] (-500.0 to -500.0)"), row);
            }
        }
    }

    @Test
    void copyLeavesBuildStateBehindAndPointsAtTheRootCatalog(@TempDir Path directory) throws Exception {
        Path sample = Files.createDirectories(directory.resolve("sample"));
        Files.writeString(sample.resolve("settings.gradle"),
                "versionCatalogs { libs { from(files('../../../gradle/libs.versions.toml')) } }\n");
        Files.createDirectories(sample.resolve("src/main/java")).resolve("A.java").toFile().createNewFile();
        Files.createDirectories(sample.resolve("build/libs")).resolve("stale.jar").toFile().createNewFile();
        Files.createDirectories(sample.resolve(".gradle")).resolve("state.bin").toFile().createNewFile();
        Path copy = directory.resolve("work/sample");
        Files.createDirectories(copy).resolve("left-over.txt").toFile().createNewFile();
        Path catalog = directory.resolve("root/gradle/libs.versions.toml");

        PackagingComparison.copySample(sample, copy, catalog);

        assertTrue(Files.isRegularFile(copy.resolve("src/main/java/A.java")));
        assertFalse(Files.exists(copy.resolve("build")));
        assertFalse(Files.exists(copy.resolve(".gradle")));
        assertFalse(Files.exists(copy.resolve("left-over.txt")), "the copy is recreated on every run");
        assertEquals("versionCatalogs { libs { from(files('" + catalog.toAbsolutePath().normalize()
                + "')) } }\n", Files.readString(copy.resolve("settings.gradle")));

        Files.writeString(sample.resolve("settings.gradle"), "'../../../gradle/libs.versions.toml'\n"
                + "'../../../gradle/libs.versions.toml'\n");
        assertThrows(IOException.class, () -> PackagingComparison.copySample(sample, copy, catalog));
    }

    private static Attempt attempt(int round, String scenario, String variant, double millis) {
        double wall = variant.equals("runner-stored") ? millis + 1_000 : millis;
        return new Attempt(round, round < PackagingComparison.WARMUP_ROUNDS, scenario, variant, millis, wall, 1);
    }
}
