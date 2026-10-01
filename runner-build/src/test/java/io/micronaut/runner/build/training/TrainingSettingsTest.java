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
package io.micronaut.runner.build.training;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every rejection of the training settings, and what they keep. A rejection is a {@link NullPointerException}
 * where a setting that takes no {@code null} was given one, and an {@link IllegalArgumentException} for any
 * other value that is not usable, a {@code null} inside a list or the environment included. Either way the
 * message names the setting, which is also the name of the Gradle property and of the Maven parameter that a
 * user set.
 */
class TrainingSettingsTest {

    /** A path that no setting takes, with the rule it breaks. */
    private static final Map<String, String> BAD_PATHS = Map.of(
            "health", "starts with '/'",
            "/a b", "whitespace",
            "/a\tb", "whitespace",
            "/a\u0001b", "control character",
            "/a\u007fb", "control character",
            "/a|b", "a URL can carry",
            "/%zz", "a URL can carry");

    /** A name that is not an environment variable's. */
    private static final List<String> BAD_NAMES = List.of("", "  ", "A=B", "A\0B");

    @Test
    void theDefaultsAreThoseOfAnApplicationAboutWhichNothingIsKnown() {
        TrainingSettings defaults = TrainingSettings.defaults();

        assertEquals(Optional.empty(), defaults.readinessPath());
        assertEquals(List.of(), defaults.workloadPaths());
        assertEquals(1, defaults.workloadRepeat());
        assertEquals(List.of(), defaults.workloadCommand());
        assertFalse(defaults.runToExit());
        assertEquals(Optional.empty(), defaults.stopPath());
        assertEquals(List.of(), defaults.jvmArgs());
        assertEquals(Map.of(), defaults.environment());
        assertEquals("MICRONAUT_SERVER_PORT", defaults.portVariable());
        assertEquals(Duration.ofSeconds(60), defaults.readinessTimeout());
        assertEquals(Duration.ofSeconds(60), defaults.workloadTimeout());
        assertEquals(Duration.ofSeconds(30), defaults.stopTimeout());
        assertEquals(defaults, TrainingSettings.builder().build());
        assertEquals("MICRONAUT_RUNNER_TRAINING_URL", TrainingSettings.URL_VARIABLE);
    }

    @Test
    void theBuilderSetsEverySettingAndToBuilderKeepsThem() {
        TrainingSettings settings = everySetting().build();

        assertEquals(Optional.of("/health?probe=start%20up"), settings.readinessPath());
        assertEquals(List.of("/hello", "/orders?page=2"), settings.workloadPaths());
        assertEquals(3, settings.workloadRepeat());
        assertEquals(List.of("warm", "up"), settings.workloadCommand());
        assertFalse(settings.runToExit());
        assertEquals(Optional.of("/stop"), settings.stopPath());
        assertEquals(List.of("-Xmx256m"), settings.jvmArgs());
        assertEquals(List.of("B", "A"), List.copyOf(settings.environment().keySet()), "the order they are set in");
        assertEquals("SERVER_PORT", settings.portVariable());
        assertEquals(Duration.ofSeconds(90), settings.readinessTimeout());
        assertEquals(Duration.ofSeconds(45), settings.workloadTimeout());
        assertEquals(Duration.ofSeconds(10), settings.stopTimeout());

        assertEquals(settings, settings.toBuilder().build());
        assertEquals(settings.hashCode(), settings.toBuilder().build().hashCode());
        TrainingSettings changed = settings.toBuilder().readinessPath(null).build();
        assertEquals(Optional.empty(), changed.readinessPath());
        assertNotEquals(settings, changed);
        assertEquals(settings, changed.toBuilder().readinessPath("/health?probe=start%20up").build(),
                "every other setting is kept");
        assertEquals(TrainingSettings.builder().runToExit(true).build(),
                TrainingSettings.defaults().toBuilder().runToExit(true).build());
    }

    @Test
    void theSettingsAreImmutableCopies() {
        List<String> paths = new ArrayList<>(List.of("/a"));
        List<String> command = new ArrayList<>(List.of("run"));
        List<String> jvmArgs = new ArrayList<>(List.of("-Xmx1g"));
        Map<String, String> environment = new LinkedHashMap<>(Map.of("A", "b"));
        TrainingSettings settings = TrainingSettings.builder().workloadPaths(paths).workloadCommand(command)
                .jvmArgs(jvmArgs).environment(environment).build();

        paths.add("/b");
        command.add("more");
        jvmArgs.add("-Xms1g");
        environment.put("C", "d");

        assertEquals(List.of("/a"), settings.workloadPaths());
        assertEquals(List.of("run"), settings.workloadCommand());
        assertEquals(List.of("-Xmx1g"), settings.jvmArgs());
        assertEquals(Map.of("A", "b"), settings.environment());
        assertThrows(UnsupportedOperationException.class, () -> settings.workloadPaths().add("/c"));
        assertThrows(UnsupportedOperationException.class, () -> settings.workloadCommand().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> settings.jvmArgs().add("-x"));
        assertThrows(UnsupportedOperationException.class, () -> settings.environment().put("E", "f"));
    }

    @Test
    void toStringHidesTheValuesOfTheEnvironment() {
        TrainingSettings settings = TrainingSettings.builder()
                .environment(Map.of("API_TOKEN", "s3cr3t-value"))
                .readinessPath("/health")
                .build();

        String text = settings.toString();

        assertFalse(text.contains("s3cr3t-value"), text);
        assertTrue(text.contains("API_TOKEN"), text);
        assertTrue(text.contains("/health"), text);
    }

    @Test
    void aPathThatIsNotARequestPathIsRejectedNamingTheSetting() {
        BAD_PATHS.forEach((path, rule) -> {
            rejected(IllegalArgumentException.class, "readinessPath", rule, builder -> builder.readinessPath(path));
            rejected(IllegalArgumentException.class, "stopPath", rule, builder -> builder.stopPath(path));
            rejected(IllegalArgumentException.class, "workloadPaths", rule,
                    builder -> builder.workloadPaths(List.of("/fine", path)));
        });
    }

    @Test
    void aNullElementOfAListIsRejectedNamingTheSetting() {
        rejected(IllegalArgumentException.class, "workloadPaths", "null",
                builder -> builder.workloadPaths(Arrays.asList("/a", null)));
        rejected(IllegalArgumentException.class, "workloadCommand", "null",
                builder -> builder.workloadCommand(Arrays.asList("run", null)));
        rejected(IllegalArgumentException.class, "jvmArgs", "null",
                builder -> builder.jvmArgs(Arrays.asList("-Xmx1g", null)));
    }

    @Test
    void aNullSettingIsRejectedNamingIt() {
        rejected(NullPointerException.class, "workloadPaths", "", builder -> builder.workloadPaths(null));
        rejected(NullPointerException.class, "workloadCommand", "", builder -> builder.workloadCommand(null));
        rejected(NullPointerException.class, "jvmArgs", "", builder -> builder.jvmArgs(null));
        rejected(NullPointerException.class, "environment", "", builder -> builder.environment(null));
        rejected(NullPointerException.class, "portVariable", "", builder -> builder.portVariable(null));
        rejected(NullPointerException.class, "readinessTimeout", "", builder -> builder.readinessTimeout(null));
        rejected(NullPointerException.class, "workloadTimeout", "", builder -> builder.workloadTimeout(null));
        rejected(NullPointerException.class, "stopTimeout", "", builder -> builder.stopTimeout(null));
    }

    @Test
    void aWorkloadRepeatBelowOneIsRejected() {
        for (int repeat : new int[] {0, -1, Integer.MIN_VALUE}) {
            rejected(IllegalArgumentException.class, "workloadRepeat", "at least 1",
                    builder -> builder.workloadRepeat(repeat));
        }
    }

    @Test
    void aBlankProgramOrJvmArgumentIsRejected() {
        rejected(IllegalArgumentException.class, "workloadCommand", "program",
                builder -> builder.workloadCommand(List.of(" ", "arg")));
        rejected(IllegalArgumentException.class, "workloadCommand", "program",
                builder -> builder.workloadCommand(List.of("")));
        rejected(IllegalArgumentException.class, "jvmArgs", "blank",
                builder -> builder.jvmArgs(List.of("-Xmx1g", " ")));
    }

    @Test
    void aNameThatIsNotAnEnvironmentVariablesIsRejected() {
        for (String name : BAD_NAMES) {
            rejected(IllegalArgumentException.class, "portVariable", "environment variable",
                    builder -> builder.portVariable(name));
            rejected(IllegalArgumentException.class, "environment", "environment variable",
                    builder -> builder.environment(Map.of(name, "value")));
        }
    }

    @Test
    void aNullNameOrValueInTheEnvironmentIsRejectedNamingTheSetting() {
        Map<String, String> nullName = new HashMap<>();
        nullName.put(null, "value");
        rejected(IllegalArgumentException.class, "environment", "null", builder -> builder.environment(nullName));

        Map<String, String> nullValue = new LinkedHashMap<>();
        nullValue.put("FINE", "value");
        nullValue.put("EMPTY", null);
        rejected(IllegalArgumentException.class, "environment", "EMPTY", builder -> builder.environment(nullValue));
    }

    @Test
    void aTimeoutThatIsNotPositiveIsRejected() {
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            rejected(IllegalArgumentException.class, "readinessTimeout", "positive",
                    builder -> builder.readinessTimeout(timeout));
            rejected(IllegalArgumentException.class, "workloadTimeout", "positive",
                    builder -> builder.workloadTimeout(timeout));
            rejected(IllegalArgumentException.class, "stopTimeout", "positive",
                    builder -> builder.stopTimeout(timeout));
        }
    }

    @Test
    void runToExitTakesNoWorkload() {
        rejected(IllegalArgumentException.class, "runToExit", "workloadPaths",
                builder -> builder.runToExit(true).workloadPaths(List.of("/hello")));
        rejected(IllegalArgumentException.class, "runToExit", "workloadCommand",
                builder -> builder.runToExit(true).workloadCommand(List.of("warm")));
        rejected(IllegalArgumentException.class, "runToExit", "workloadRepeat",
                builder -> builder.runToExit(true).workloadRepeat(2));
        assertTrue(TrainingSettings.builder().runToExit(true).readinessPath("/ignored").build().runToExit(),
                "the paths that do not belong to a workload are allowed");
    }

    @Test
    void theSettersOnlyStoreAndBuildRejects() {
        TrainingSettings.Builder builder = TrainingSettings.builder().workloadRepeat(0).portVariable(null);
        builder.workloadRepeat(1).portVariable("PORT");
        assertEquals("PORT", builder.build().portVariable(), "a later call replaces an earlier one");
    }

    private static TrainingSettings.Builder everySetting() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("B", "2");
        environment.put("A", "1");
        return TrainingSettings.builder()
                .readinessPath("/health?probe=start%20up")
                .workloadPaths(List.of("/hello", "/orders?page=2"))
                .workloadRepeat(3)
                .workloadCommand(List.of("warm", "up"))
                .runToExit(false)
                .stopPath("/stop")
                .jvmArgs(List.of("-Xmx256m"))
                .environment(environment)
                .portVariable("SERVER_PORT")
                .readinessTimeout(Duration.ofSeconds(90))
                .workloadTimeout(Duration.ofSeconds(45))
                .stopTimeout(Duration.ofSeconds(10));
    }

    /**
     * Builds settings that differ from the defaults by one change, and expects the build to fail.
     *
     * @param type    the exception expected
     * @param setting the setting the message must name
     * @param detail  what else the message must say, empty for nothing
     * @param change  the change
     */
    private static <T extends RuntimeException> void rejected(Class<T> type, String setting, String detail,
                                                              Consumer<TrainingSettings.Builder> change) {
        TrainingSettings.Builder builder = TrainingSettings.builder();
        change.accept(builder);
        T failure = assertThrows(type, builder::build, () -> setting + " was not rejected");
        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains(setting), () -> "the message does not name " + setting + ": " + message);
        assertTrue(message.contains(detail), () -> "the message does not say " + detail + ": " + message);
    }
}
