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

import io.micronaut.runner.build.StartupProfileRecorder;
import io.micronaut.runner.build.training.TrainingSettings;
import org.gradle.api.Project;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code training { }} block is wiring for {@link TrainingSettings}: one optional input per component,
 * the library's defaults as the extension's conventions, and the extension's values as the task's. Also the
 * wiring of {@code recordStartupProfile} and of the committed profile's convention.
 */
class TrainingSpecTest {

    @Test
    void theBlockHasOneOptionalInputForEachSetting() throws NoSuchMethodException {
        Set<String> settings = Arrays.stream(TrainingSettings.class.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet());
        for (String name : settings) {
            Method getter = TrainingSpec.class.getMethod(
                    "get" + name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1));
            assertNotNull(getter.getAnnotation(Input.class), () -> getter + " is not @Input");
            assertNotNull(getter.getAnnotation(Optional.class), () -> getter + " is not @Optional");
        }
        Set<String> properties = Arrays.stream(TrainingSpec.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("get") && method.getParameterCount() == 0)
                .map(method -> method.getName().substring(3, 4).toLowerCase(Locale.ROOT)
                        + method.getName().substring(4))
                .collect(Collectors.toSet());
        assertEquals(settings, properties, "the block has a property that is not a setting, or lacks one");
    }

    @Test
    void theExtensionShowsTheDefaultsAndMapsBackOntoThem(@TempDir Path directory) {
        Project project = project(directory);
        TrainingSpec training = extension(project).getTraining();

        assertEquals(TrainingSettings.defaults(), TrainingSpec.settings(training));
        assertEquals(1, training.getWorkloadRepeat().get());
        assertEquals("MICRONAUT_SERVER_PORT", training.getPortVariable().get());
        assertEquals(Duration.ofSeconds(60), training.getReadinessTimeout().get());
        assertFalse(training.getReadinessPath().isPresent());
    }

    @Test
    void theTaskTakesTheBlockFromTheExtensionAndASettingOnTheTaskWins(@TempDir Path directory) {
        Project project = project(directory);
        MicronautRunnerExtension extension = extension(project);
        extension.training(training -> {
            training.getReadinessPath().set("/health");
            training.getWorkloadPaths().set(List.of("/hello"));
            training.getEnvironment().put("A", "b");
            training.getStopTimeout().set(Duration.ofSeconds(5));
        });
        RecordStartupProfile task = project.getTasks()
                .named(MicronautRunnerPlugin.RECORD_TASK_NAME, RecordStartupProfile.class).get();
        task.getTraining().getWorkloadPaths().set(List.of("/orders"));

        TrainingSettings settings = TrainingSpec.settings(task.getTraining());
        assertEquals("/health", settings.readinessPath());
        assertEquals(List.of("/orders"), settings.workloadPaths());
        assertEquals(Map.of("A", "b"), settings.environment());
        assertEquals(Duration.ofSeconds(5), settings.stopTimeout());
        assertEquals(TrainingSettings.DEFAULT_PORT_VARIABLE, settings.portVariable());
    }

    @Test
    void theTaskRecordsTheProductionArchiveIntoTheConventionalFileAndIsPartOfNoLifecycle(@TempDir Path directory) {
        Project project = project(directory);
        RecordStartupProfile task = project.getTasks()
                .named(MicronautRunnerPlugin.RECORD_TASK_NAME, RecordStartupProfile.class).get();
        MicronautRunnerJar runnerJar = project.getTasks()
                .named(MicronautRunnerPlugin.TASK_NAME, MicronautRunnerJar.class).get();

        assertEquals(runnerJar.getArchiveFile().get().getAsFile(), task.getArchiveFile().get().getAsFile());
        assertEquals(project.file(StartupProfileRecorder.PROFILE_LOCATION), task.getProfileFile().get().getAsFile());
        assertEquals(project.getLayout().getBuildDirectory().dir("micronaut-runner/record-startup-profile").get()
                .getAsFile(), task.getWorkDirectory().get().getAsFile());
        assertTrue(task.getJavaLauncher().isPresent(), "the toolchain's launcher, or Gradle's own JVM");
        assertEquals("build", task.getGroup());
        for (String lifecycle : List.of("assemble", "build", "check")) {
            Set<Object> dependencies = project.getTasks().getByName(lifecycle).getDependsOn();
            assertFalse(dependencies.stream().anyMatch(dependency -> dependency == task
                    || String.valueOf(dependency).contains(MicronautRunnerPlugin.RECORD_TASK_NAME)),
                    () -> lifecycle + " depends on " + MicronautRunnerPlugin.RECORD_TASK_NAME + ": " + dependencies);
        }
    }

    @Test
    void theCommittedProfileIsTheConventionOfStartupClasses(@TempDir Path directory) throws Exception {
        Project project = project(directory);
        MicronautRunnerExtension extension = extension(project);
        MicronautRunnerJar runnerJar = project.getTasks()
                .named(MicronautRunnerPlugin.TASK_NAME, MicronautRunnerJar.class).get();
        assertFalse(runnerJar.getStartupClasses().isPresent(), "without the file there is no list");

        Path profile = directory.resolve(StartupProfileRecorder.PROFILE_LOCATION);
        Files.createDirectories(profile.getParent());
        Files.writeString(profile, "com.example.App\n");
        File conventional = project.file(StartupProfileRecorder.PROFILE_LOCATION);
        assertEquals(conventional, extension.getStartupClasses().get().getAsFile());
        assertEquals(conventional, runnerJar.getStartupClasses().get().getAsFile());

        File other = project.file("other.txt");
        extension.getStartupClasses().set(other);
        assertEquals(other, runnerJar.getStartupClasses().get().getAsFile(), "an explicit list wins");
        extension.getStartupClasses().set((File) null);
        assertEquals(conventional, runnerJar.getStartupClasses().get().getAsFile(),
                "null falls back to the convention: there is no build-time off switch but deleting the file");
    }

    private static Project project(Path directory) {
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(MicronautRunnerPlugin.class);
        return project;
    }

    private static MicronautRunnerExtension extension(Project project) {
        return project.getExtensions().getByType(MicronautRunnerExtension.class);
    }
}
