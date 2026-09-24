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

import io.micronaut.runner.build.RunnerJarOption;
import org.gradle.api.Project;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.HasMultipleValues;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
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
 * The task and the extension are wiring for the option table: every {@link RunnerJarOption.Exposure#TYPED typed}
 * option has an optional task input and an extension property of the same type, the extension's convention is
 * the packaging library's default, the task's convention is the extension's value, and every other task input
 * is a build-tool fact. The assertions iterate over the table, so an option that becomes typed is covered
 * without a new test, and a property added without a table entry fails.
 */
class TypedOptionConformanceTest {

    /** The task properties that carry build-tool facts rather than packaging options. */
    private static final Set<String> BUILD_TOOL_FACTS = Set.of(
            "mainClass", "applicationOutput", "applicationJar", "classpath", "coordinates", "dependencyInputs",
            "inheritedManifest", "inheritedManifestAttributes", "archiveFile", "archiveBaseName", "archiveVersion",
            "archiveClassifier", "destinationDirectory", "options");

    /** The extension's members that are not typed options. */
    private static final Set<String> EXTENSION_MEMBERS = Set.of("enabled", "options");

    @Test
    void everyTypedOptionIsAnOptionalInput() throws NoSuchMethodException {
        for (RunnerJarOption option : typedOptions()) {
            Method getter = getter(MicronautRunnerJar.class, option.optionName());
            assertNotNull(getter.getAnnotation(Optional.class), () -> getter + " is not @Optional");
            Class<? extends Annotation> input = option.valueType() == Path.class ? InputFile.class : Input.class;
            assertNotNull(getter.getAnnotation(input), () -> getter + " is not @" + input.getSimpleName());
        }
    }

    @Test
    void everyTypedOptionIsAnExtensionPropertyOfTheTasksType() throws NoSuchMethodException {
        for (RunnerJarOption option : typedOptions()) {
            Method onTask = getter(MicronautRunnerJar.class, option.optionName());
            Method onExtension = getter(MicronautRunnerExtension.class, option.optionName());
            assertEquals(onTask.getGenericReturnType(), onExtension.getGenericReturnType(),
                    () -> option.optionName() + " has another type on the extension than on the task");
        }
    }

    @Test
    void theExtensionHoldsTheTypedOptionsAndNothingElseTyped() {
        Set<String> typed = typedOptions().stream().map(RunnerJarOption::optionName).collect(Collectors.toSet());
        for (Method method : MicronautRunnerExtension.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || !method.getName().startsWith("get")) {
                continue;
            }
            String name = propertyName(method);
            assertTrue(typed.contains(name) || EXTENSION_MEMBERS.contains(name),
                    () -> method + " is neither a typed option nor a listed member of the extension. A packaging"
                            + " option belongs in RunnerJarOption and is set through options until it is typed.");
        }
        for (String member : EXTENSION_MEMBERS) {
            assertTrue(RunnerJarOption.named(member).isEmpty(), () -> member + " is both a member and an option");
        }
    }

    @Test
    void theConventionOfEveryTypedOptionIsItsDefault(@TempDir Path directory) throws ReflectiveOperationException {
        Project project = project(directory);
        MicronautRunnerExtension extension = project.getExtensions().getByType(MicronautRunnerExtension.class);
        MicronautRunnerJar task = task(project);

        Map<String, Object> owners = Map.of("the extension", extension, "the task", task);
        for (RunnerJarOption option : typedOptions()) {
            for (Map.Entry<String, Object> owner : owners.entrySet()) {
                Provider<?> property = (Provider<?>) getter(owner.getValue().getClass(), option.optionName())
                        .invoke(owner.getValue());
                String where = owner.getKey();
                if (option.defaultValue().isPresent()) {
                    assertTrue(property.isPresent(), () -> option.optionName() + " has no convention on " + where);
                    assertEquals(option.defaultValue().get(), grammar(property.get()),
                            () -> option.optionName() + "'s convention on " + where
                                    + " is not the packaging library's default");
                } else {
                    assertFalse(property.isPresent(), () -> option.optionName()
                            + " has a conditional default but " + where + " sets a convention");
                }
            }
        }
        assertEquals(true, extension.getEnabled().get(), "the plugin is disabled by default");
    }

    @Test
    void theTaskTakesItsOptionsFromTheExtensionAndASettingOnTheTaskWins(@TempDir Path directory)
            throws ReflectiveOperationException {
        Project project = project(directory);
        MicronautRunnerExtension extension = project.getExtensions().getByType(MicronautRunnerExtension.class);
        MicronautRunnerJar task = task(project);

        for (RunnerJarOption option : typedOptions()) {
            Object onExtension = getter(MicronautRunnerExtension.class, option.optionName()).invoke(extension);
            Object onTask = getter(MicronautRunnerJar.class, option.optionName()).invoke(task);
            set(onExtension, option, "extension");
            assertEquals(sample(option, "extension"), ((Provider<?>) onTask).get(),
                    () -> option.optionName() + " on the task does not follow the extension");
            set(onTask, option, "task");
            assertEquals(sample(option, "task"), ((Provider<?>) onTask).get(),
                    () -> option.optionName() + " set on the task does not win over the extension");
        }

        extension.getOptions().put("compression", "PRESERVE");
        assertEquals(Map.of("compression", "PRESERVE"), task.getOptions().get(),
                "the task's options do not follow the extension");
    }

    private static Project project(Path directory) {
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(MicronautRunnerPlugin.class);
        return project;
    }

    private static MicronautRunnerJar task(Project project) {
        return project.getTasks().named(MicronautRunnerPlugin.TASK_NAME, MicronautRunnerJar.class).get();
    }

    @Test
    void everyOtherInputIsABuildToolFact() {
        Set<String> typed = typedOptions().stream().map(RunnerJarOption::optionName).collect(Collectors.toSet());
        for (Method method : MicronautRunnerJar.class.getDeclaredMethods()) {
            boolean annotatedGetter = method.getName().startsWith("get") && method.getParameterCount() == 0
                    && Arrays.stream(method.getAnnotations())
                            .anyMatch(annotation -> annotation.annotationType().getPackageName()
                                    .equals(Input.class.getPackageName()));
            if (!annotatedGetter || typed.contains(propertyName(method))) {
                continue;
            }
            assertTrue(BUILD_TOOL_FACTS.contains(propertyName(method)),
                    () -> method + " is neither a typed option nor a listed build-tool fact. A packaging option"
                            + " belongs in RunnerJarOption and is set through options until it is typed.");
        }
        for (String fact : BUILD_TOOL_FACTS) {
            assertTrue(RunnerJarOption.named(fact).isEmpty(), () -> fact + " is both a fact and an option");
        }
    }

    private static List<RunnerJarOption> typedOptions() {
        return Arrays.stream(RunnerJarOption.values())
                .filter(option -> option.exposure() == RunnerJarOption.Exposure.TYPED)
                .toList();
    }

    private static Method getter(Class<?> type, String property) throws NoSuchMethodException {
        return type.getMethod("get" + property.substring(0, 1).toUpperCase(Locale.ROOT) + property.substring(1));
    }

    private static String propertyName(Method getter) {
        String name = getter.getName().substring("get".length());
        return name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
    }

    /** A value of an option's type that no default has, distinct for each owner. */
    private static Object sample(RunnerJarOption option, String owner) {
        Class<?> type = option.valueType();
        if (type == Boolean.class) {
            return !Boolean.parseBoolean(option.defaultValue().orElse("false")) ^ "task".equals(owner);
        }
        if (type == List.class) {
            return List.of("java.base/" + owner);
        }
        if (type == Map.class) {
            return Map.of("X-" + owner, owner);
        }
        return owner + "-value";
    }

    @SuppressWarnings("unchecked")
    private static void set(Object property, RunnerJarOption option, String owner) {
        Object value = sample(option, owner);
        switch (property) {
            case HasMultipleValues<?> list -> ((HasMultipleValues<Object>) list).set((Iterable<Object>) value);
            case MapProperty<?, ?> map -> ((MapProperty<Object, Object>) map).set((Map<Object, Object>) value);
            case Property<?> scalar -> ((Property<Object>) scalar).set(value);
            default -> throw new AssertionError(option.optionName() + " is not a property: " + property);
        }
    }

    /** Writes a property value in the grammar the option table uses for defaults. */
    private static String grammar(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue())
                    .collect(Collectors.joining("\n"));
        }
        if (value instanceof RegularFile file) {
            return file.getAsFile().getAbsolutePath();
        }
        return String.valueOf(value);
    }
}
