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
package io.micronaut.runner.build.api;

import io.micronaut.runner.build.BuildLogger;
import io.micronaut.runner.build.StartupProfileRecorder;
import io.micronaut.runner.build.training.TrainingSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The tripwire of the startup-profile API: every member that the Micronaut Gradle and Maven plugins call in
 * Wave 2 of micronaut-projects/micronaut-gradle-plugin#1378 and micronaut-projects/micronaut-maven-plugin#1720,
 * and every other public member of {@link StartupProfileRecorder}, {@link TrainingSettings} and
 * {@link TrainingSettings.Builder}, used the way a plugin uses them. It guards the API until japicmp has a
 * 1.0.0 release to compare with.
 *
 * <p>It lives outside the API's packages, so it sees only what a plugin sees. Each result is assigned to its
 * exact declared type, never to {@code var} or a wider type, and {@code record} is called in a {@code try} that
 * catches exactly the checked exceptions it declares, in a test method that declares none. So a removed member,
 * or a changed parameter, return type or {@code throws} clause, fails to compile wherever javac can tell. Javac
 * accepts some changes that break binary compatibility, such as an {@code int} result that becomes
 * {@code Integer} or an {@code int} parameter that becomes {@code long}, so the signatures are also checked by
 * reflection.</p>
 *
 * <p><b>Changing this test changes the API.</b> Before 1.0.0, a change made on purpose after the review of
 * 1.0.0-M1, which micronaut-projects/micronaut-maven-plugin#1720 allows before GA, updates this test in the same
 * pull request and is announced on the upstream draft pull requests. From 1.0.0 on, changing or removing
 * anything this test checks breaks the binary compatibility of 1.x: only additions are allowed, such as a new
 * setter or a new overload of {@code record}.</p>
 */
class StartupProfileApiTest {

    /** The setters of the builder, with their parameter types, in the order of the settings. */
    private static final Map<String, String> SETTERS = new LinkedHashMap<>();

    /** The accessors of the settings, with their return types. */
    private static final Map<String, String> ACCESSORS = new LinkedHashMap<>();

    static {
        setting("readinessPath", "java.lang.String", "java.util.Optional<java.lang.String>");
        setting("workloadPaths", "java.util.List<java.lang.String>", "java.util.List<java.lang.String>");
        setting("workloadRepeat", "int", "int");
        setting("workloadCommand", "java.util.List<java.lang.String>", "java.util.List<java.lang.String>");
        setting("runToExit", "boolean", "boolean");
        setting("stopPath", "java.lang.String", "java.util.Optional<java.lang.String>");
        setting("jvmArgs", "java.util.List<java.lang.String>", "java.util.List<java.lang.String>");
        setting("environment", "java.util.Map<java.lang.String, java.lang.String>",
                "java.util.Map<java.lang.String, java.lang.String>");
        setting("portVariable", "java.lang.String", "java.lang.String");
        setting("readinessTimeout", "java.time.Duration", "java.time.Duration");
        setting("workloadTimeout", "java.time.Duration", "java.time.Duration");
        setting("stopTimeout", "java.time.Duration", "java.time.Duration");
    }

    @TempDir
    Path temp;

    @Test
    void everyMemberCompilesWithItsExactType() {
        String profileLocation = StartupProfileRecorder.PROFILE_LOCATION;
        String urlVariable = TrainingSettings.URL_VARIABLE;
        assertEquals("src/main/micronaut-runner/startup-classes.txt", profileLocation, "fixed for 1.x");
        assertEquals("MICRONAUT_RUNNER_TRAINING_URL", urlVariable, "fixed for 1.x");

        // The conventions of Gradle's training { } block.
        TrainingSettings defaults = TrainingSettings.defaults();
        int workloadRepeat = defaults.workloadRepeat();
        boolean runToExit = defaults.runToExit();
        String portVariable = defaults.portVariable();
        Duration readinessTimeout = defaults.readinessTimeout();
        Duration workloadTimeout = defaults.workloadTimeout();
        Duration stopTimeout = defaults.stopTimeout();
        Optional<String> readinessPath = defaults.readinessPath();
        Optional<String> stopPath = defaults.stopPath();
        List<String> workloadPaths = defaults.workloadPaths();
        List<String> workloadCommand = defaults.workloadCommand();
        List<String> jvmArgs = defaults.jvmArgs();
        Map<String, String> environment = defaults.environment();

        // The mapping of either plugin's settings.
        TrainingSettings.Builder builder = TrainingSettings.builder();
        TrainingSettings.Builder same = builder.readinessPath(readinessPath.orElse(null));
        same = same.workloadPaths(workloadPaths);
        same = same.workloadRepeat(workloadRepeat);
        same = same.workloadCommand(workloadCommand);
        same = same.runToExit(runToExit);
        same = same.stopPath(stopPath.orElse(null));
        same = same.jvmArgs(jvmArgs);
        same = same.environment(environment);
        same = same.portVariable(portVariable);
        same = same.readinessTimeout(readinessTimeout);
        same = same.workloadTimeout(workloadTimeout);
        same = same.stopTimeout(stopTimeout);
        TrainingSettings settings = same.build();
        TrainingSettings.Builder copy = settings.toBuilder();
        assertEquals(defaults, settings);
        assertEquals(defaults, copy.build());
        assertSame(builder, same, "every setter returns its builder");

        // The recording, as the plugins call it. A missing archive fails before anything is launched.
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path runnerJar = temp.resolve("build/libs/app-all.jar");
        Path workDirectory = temp.resolve("build/micronaut-runner/record-startup-profile");
        Path profile = temp.resolve(profileLocation);
        String rerecordCommand = "./gradlew recordStartupProfile";
        BuildLogger log = BuildLogger.noOp();
        try {
            int classes = StartupProfileRecorder.record(java, runnerJar, settings, workDirectory, profile,
                    rerecordCommand, log);
            fail("a missing archive was recorded, with " + classes + " classes");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("is not a runner jar"), expected.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
        assertFalse(Files.exists(workDirectory));
        assertFalse(Files.exists(profile));
    }

    @Test
    void theSignaturesAreTheOnesThePluginsWereBuiltAgainst() throws NoSuchMethodException, NoSuchFieldException {
        assertPublicFinal(StartupProfileRecorder.class);
        assertEquals(0, StartupProfileRecorder.class.getConstructors().length);
        Method record = StartupProfileRecorder.class.getMethod("record", Path.class, Path.class,
                TrainingSettings.class, Path.class, Path.class, String.class, BuildLogger.class);
        assertTrue(Modifier.isStatic(record.getModifiers()));
        assertEquals(int.class, record.getReturnType());
        assertEquals(List.of(IOException.class, InterruptedException.class), List.of(record.getExceptionTypes()));
        assertConstant(StartupProfileRecorder.class.getField("PROFILE_LOCATION"));

        assertPublicFinal(TrainingSettings.class);
        assertEquals(0, TrainingSettings.class.getConstructors().length, "the builder is the only way in");
        assertConstant(TrainingSettings.class.getField("URL_VARIABLE"));
        assertMethod(TrainingSettings.class.getMethod("defaults"), true, TrainingSettings.class.getName());
        assertMethod(TrainingSettings.class.getMethod("builder"), true, TrainingSettings.Builder.class.getName());
        assertMethod(TrainingSettings.class.getMethod("toBuilder"), false, TrainingSettings.Builder.class.getName());
        for (Map.Entry<String, String> accessor : ACCESSORS.entrySet()) {
            assertMethod(TrainingSettings.class.getMethod(accessor.getKey()), false, accessor.getValue());
        }

        Class<TrainingSettings.Builder> builder = TrainingSettings.Builder.class;
        assertPublicFinal(builder);
        assertTrue(Modifier.isStatic(builder.getModifiers()));
        assertEquals(0, builder.getConstructors().length, "TrainingSettings.builder() is the only way in");
        for (Map.Entry<String, String> setter : SETTERS.entrySet()) {
            Method method = builder.getMethod(setter.getKey(), erasure(setter.getValue()));
            assertMethod(method, false, builder.getName());
            assertEquals(setter.getValue(), method.getGenericParameterTypes()[0].getTypeName(), method::toString);
        }
        assertMethod(builder.getMethod("build"), false, TrainingSettings.class.getName());
    }

    @Test
    void eachNullArgumentOfRecordFailsWithANullPointerExceptionThatNamesIt() {
        String[] names = {"java", "runnerJar", "settings", "workDirectory", "profile", "rerecordCommand", "log"};
        Path workDirectory = temp.resolve("work");
        Object[] valid = {Path.of(System.getProperty("java.home"), "bin", "java"), temp.resolve("app-all.jar"),
            TrainingSettings.defaults(), workDirectory, temp.resolve("startup-classes.txt"), "./gradlew record",
            BuildLogger.noOp()};
        assertThrows(IOException.class, () -> record(valid), "with every argument, the missing archive fails");

        for (int i = 0; i < names.length; i++) {
            Object[] arguments = valid.clone();
            arguments[i] = null;
            NullPointerException failure = assertThrows(NullPointerException.class, () -> record(arguments),
                    names[i]);
            assertEquals(names[i], failure.getMessage());
        }
        assertFalse(Files.exists(workDirectory), "nothing is created for a refused call");
    }

    private static void record(Object[] arguments) throws IOException, InterruptedException {
        StartupProfileRecorder.record((Path) arguments[0], (Path) arguments[1], (TrainingSettings) arguments[2],
                (Path) arguments[3], (Path) arguments[4], (String) arguments[5], (BuildLogger) arguments[6]);
    }

    private static void setting(String name, String parameterType, String returnType) {
        SETTERS.put(name, parameterType);
        ACCESSORS.put(name, returnType);
    }

    private static Class<?> erasure(String type) {
        return switch (type) {
            case "int" -> int.class;
            case "boolean" -> boolean.class;
            case "java.lang.String" -> String.class;
            case "java.time.Duration" -> Duration.class;
            default -> type.startsWith("java.util.List<") ? List.class : Map.class;
        };
    }

    private static void assertPublicFinal(Class<?> type) {
        assertTrue(Modifier.isPublic(type.getModifiers()) && Modifier.isFinal(type.getModifiers()),
                () -> type + " is not public and final");
    }

    private static void assertConstant(Field field) {
        int modifiers = field.getModifiers();
        assertTrue(Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers),
                field::toString);
        assertEquals(String.class, field.getType(), field::toString);
    }

    private static void assertMethod(Method method, boolean isStatic, String returnType) {
        assertTrue(Modifier.isPublic(method.getModifiers()), method::toString);
        assertEquals(isStatic, Modifier.isStatic(method.getModifiers()), method::toString);
        assertEquals(returnType, method.getGenericReturnType().getTypeName(), method::toString);
        assertEquals(0, method.getExceptionTypes().length, () -> method + " declares exceptions");
    }
}
