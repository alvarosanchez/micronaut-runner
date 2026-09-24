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
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
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
 * The task is wiring for the option table: every {@link RunnerJarOption.Exposure#TYPED typed} option has an
 * optional input whose convention is the packaging library's default, and every other input is a build-tool
 * fact. The assertions iterate over the table, so an option that becomes typed is covered without a new test,
 * and a property added without a table entry fails.
 */
class TypedOptionConformanceTest {

    /** The task properties that carry build-tool facts rather than packaging options. */
    private static final Set<String> BUILD_TOOL_FACTS = Set.of(
            "mainClass", "applicationOutput", "applicationJar", "classpath", "coordinates", "dependencyInputs",
            "inheritedManifest", "inheritedManifestAttributes", "archiveFile", "archiveClassifier", "options");

    @Test
    void everyTypedOptionIsAnOptionalInput() throws NoSuchMethodException {
        for (RunnerJarOption option : typedOptions()) {
            Method getter = getter(option);
            assertNotNull(getter.getAnnotation(Optional.class), () -> getter + " is not @Optional");
            Class<? extends Annotation> input = option.valueType() == Path.class ? InputFile.class : Input.class;
            assertNotNull(getter.getAnnotation(input), () -> getter + " is not @" + input.getSimpleName());
        }
    }

    @Test
    void theConventionOfEveryTypedOptionIsItsDefault(@TempDir Path directory) throws ReflectiveOperationException {
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(MicronautRunnerPlugin.class);
        MicronautRunnerJar task = project.getTasks()
                .named(MicronautRunnerPlugin.TASK_NAME, MicronautRunnerJar.class).get();

        for (RunnerJarOption option : typedOptions()) {
            Provider<?> property = (Provider<?>) getter(option).invoke(task);
            if (option.defaultValue().isPresent()) {
                assertTrue(property.isPresent(), () -> option.optionName() + " has no convention");
                assertEquals(option.defaultValue().get(), grammar(property.get()),
                        () -> option.optionName() + "'s convention is not the packaging library's default");
            } else {
                assertFalse(property.isPresent(),
                        () -> option.optionName() + " has a conditional default but the task sets a convention");
            }
        }
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

    private static Method getter(RunnerJarOption option) throws NoSuchMethodException {
        String name = option.optionName();
        return MicronautRunnerJar.class.getMethod("get" + name.substring(0, 1).toUpperCase(Locale.ROOT)
                + name.substring(1));
    }

    private static String propertyName(Method getter) {
        String name = getter.getName().substring("get".length());
        return name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
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
