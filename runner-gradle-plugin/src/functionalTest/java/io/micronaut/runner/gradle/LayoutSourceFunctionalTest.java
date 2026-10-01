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

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code micronautRunnerLayout} extracts the JAR of the internal layout-source task, which packages
 * {@code micronautRunnerJar}'s inputs and options with every lambda kept: on a fixture with a lambda in a dependency
 * and one in the application, the layout is the extract of a JAR built with {@code desugarLambdas=false}, not of the
 * default {@code micronautRunnerJar} archive, and for a build that keeps lambdas anyway, with
 * {@code desugarLambdas=false} or {@code PRESERVE}, it is the extract of that build's own archive. No JDK AOT cache
 * is trained here; {@link JdkAotCacheFunctionalTest} trains one on the same kind of layout.
 */
class LayoutSourceFunctionalTest extends AbstractFunctionalTest {

    static final String LAYOUT_SOURCE_TASK = ":micronautRunnerLayoutSource";

    /** The dependency of the fixtures, whose class has a lambda the default desugars. */
    static final String LAMBDA_LIBRARY = "libs/gamma.jar";

    /** What a fixture build adds for {@link #LAMBDA_LIBRARY}. */
    static final String LAMBDA_DEPENDENCY = "dependencies { implementation files('" + LAMBDA_LIBRARY + "') }";

    private static final String LAYOUT_TASK = ":micronautRunnerLayout";

    private static final String JAR_NAME = DEFAULT_ARCHIVE.substring("build/libs/".length());

    private static final String LAMBDA_CLASS = "com.example.lambda.Twice";

    private static final String LAMBDA_SOURCE = """
            package com.example.lambda;

            import java.util.function.UnaryOperator;

            public final class Twice {
                public static String twice(String value) {
                    UnaryOperator<String> twice = text -> text + text;
                    return twice.apply(value);
                }
            }
            """;

    /** An application class with a lambda, beside the fixture application. */
    private static final String APPLICATION_LAMBDA = """
            package com.example;

            import java.util.function.Supplier;

            public final class Greeting {
                public static Supplier<String> of(String name) {
                    return () -> "hello " + name;
                }
            }
            """;

    /**
     * Adds the dependency with a lambda, and a class with one to the application.
     *
     * @param directory the fixture project
     * @throws IOException if the files cannot be written
     */
    static void writeLambdas(Path directory) throws IOException {
        debugJar(directory.resolve(LAMBDA_LIBRARY), LAMBDA_CLASS, LAMBDA_SOURCE);
        write(directory.resolve("src/main/java/com/example/Greeting.java"), APPLICATION_LAMBDA);
    }

    @Test
    void theLayoutKeepsLambdasAndIsTheExtractOfAJarThatKeepsThem(@TempDir Path directory) throws Exception {
        writeFixture(directory, LAMBDA_DEPENDENCY, "");
        writeLambdas(directory);
        Path buildFile = directory.resolve("build.gradle");
        String build = Files.readString(buildFile);
        Path shipped = directory.resolve(DEFAULT_ARCHIVE);
        Path layout = directory.resolve("build/micronaut-runner/layout");

        // assemble alone does not package the layout source, and the layout alone does not package the shipped JAR.
        BuildResult assemble = build(directory, "assemble", "--dry-run");
        assertTrue(scheduled(assemble, RUNNER_JAR_TASK), assemble::getOutput);
        assertFalse(scheduled(assemble, LAYOUT_SOURCE_TASK), assemble::getOutput);
        BuildResult layoutOnly = build(directory, "micronautRunnerLayout", "--dry-run");
        assertTrue(scheduled(layoutOnly, LAYOUT_SOURCE_TASK), layoutOnly::getOutput);
        assertFalse(scheduled(layoutOnly, RUNNER_JAR_TASK), layoutOnly::getOutput);

        // The default desugars the shipped JAR, and the layout keeps every lambda under the shipped JAR's name.
        BuildResult first = build(directory, "micronautRunnerJar", "micronautRunnerLayout");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(first, LAYOUT_SOURCE_TASK), first::getOutput);
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(first, LAYOUT_TASK), first::getOutput);
        assertTrue(Files.isRegularFile(layout.resolve(JAR_NAME)), first::getOutput);
        assertTrue(entryNames(shipped).stream().anyMatch(name -> name.contains("$$Lambda$R")),
                "the shipped JAR desugars the application's lambda");
        assertTrue(nestedEntryMethods(shipped, "MICRONAUT-INF/lib/gamma.jar").keySet().stream()
                .anyMatch(name -> name.contains("$$Lambda$R")), "and the dependency's");
        List<String> generated = generatedLambdaClasses(layout);
        assertTrue(generated.isEmpty(), generated::toString);
        Map<String, String> defaultLayout = digests(layout);
        Map<String, String> handExtract = extract(shipped, directory.resolve("extract-default"));
        assertEquals(handExtract.keySet(), defaultLayout.keySet());
        assertNotEquals(handExtract.get(JAR_NAME), defaultLayout.get(JAR_NAME));
        assertNotEquals(handExtract.get("lib/gamma.jar"), defaultLayout.get("lib/gamma.jar"));
        assertEquals(handExtract.get("lib/alpha.jar"), defaultLayout.get("lib/alpha.jar"),
                "a JAR without a lambda is the same either way");

        // An unchanged build is up to date; a changed dependency packages and writes the layout again.
        BuildResult unchanged = build(directory, "micronautRunnerLayout");
        assertEquals(TaskOutcome.UP_TO_DATE, outcomeOf(unchanged, LAYOUT_SOURCE_TASK), unchanged::getOutput);
        assertEquals(TaskOutcome.UP_TO_DATE, outcomeOf(unchanged, LAYOUT_TASK), unchanged::getOutput);
        Path library = directory.resolve(LAMBDA_LIBRARY);
        byte[] original = Files.readAllBytes(library);
        debugJar(library, LAMBDA_CLASS, LAMBDA_SOURCE.replace("text + text", "text + 2"));
        BuildResult changed = build(directory, "micronautRunnerLayout");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(changed, LAYOUT_SOURCE_TASK), changed::getOutput);
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(changed, LAYOUT_TASK), changed::getOutput);
        Files.write(library, original);

        // desugarLambdas=false: the layout is the extract of micronautRunnerJar's own archive, and the default layout
        // was exactly that.
        write(buildFile, build + "\nmicronautRunner { options.put('desugarLambdas', 'false') }\n");
        BuildResult kept = build(directory, "micronautRunnerJar", "micronautRunnerLayout");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(kept, LAYOUT_SOURCE_TASK), kept::getOutput);
        Map<String, String> keptLayout = digests(layout);
        assertEquals(extract(shipped, directory.resolve("extract-kept")), keptLayout);
        assertEquals(defaultLayout, keptLayout, "the default layout is the extract of a JAR that keeps lambdas");

        // PRESERVE, set on micronautRunnerJar itself: the layout source follows the task, and runs no transform.
        write(buildFile, build + "\ntasks.named('micronautRunnerJar') { compression = 'PRESERVE' }\n");
        BuildResult preserve = build(directory, "micronautRunnerJar", "micronautRunnerLayout");
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(preserve, LAYOUT_SOURCE_TASK), preserve::getOutput);
        assertEquals(TaskOutcome.SUCCESS, outcomeOf(preserve, LAYOUT_TASK), preserve::getOutput);
        Map<String, String> preserved = digests(layout);
        assertEquals(extract(shipped, directory.resolve("extract-preserve")), preserved);
        assertEquals(sha256(directory.resolve(LAMBDA_LIBRARY)), preserved.get("lib/gamma.jar"),
                "PRESERVE nests the dependency byte for byte");
    }

    /**
     * A committed startup profile, recorded from the shipped JAR, names the classes its desugaring generated. The
     * layout source keeps the profile, which orders the nested entries, without those names and without a warning,
     * so its layout is the extract of a {@code desugarLambdas=false} JAR built with the profile less those names.
     */
    @Test
    void theLayoutSourceKeepsTheStartupProfileWithoutTheGeneratedLambdaClasses(@TempDir Path directory)
            throws Exception {
        writeFixture(directory, LAMBDA_DEPENDENCY, "");
        writeLambdas(directory);
        Path profile = directory.resolve("src/main/micronaut-runner/startup-classes.txt");
        write(profile, """
                com.example.App
                com.example.Greeting
                com.example.Greeting$$Lambda$R0
                com.example.lambda.Twice
                com.example.lambda.Twice$$Lambda$R0
                com.example.lib.Greeter
                """);
        Path layout = directory.resolve("build/micronaut-runner/layout");

        // The packaging library's info lines, among them what it did with the list.
        BuildResult written = build(directory, "micronautRunnerLayout", "--info");

        assertTrue(written.getOutput().contains("Left 2 generated lambda classes out of the startup class list,"
                + " because the layout-source JAR keeps every lambda"), written::getOutput);
        assertFalse(written.getOutput().contains("startup classes: not in this archive"), written::getOutput);
        Map<String, String> files = digests(layout);

        // The JAR a user would build to write the same layout by hand.
        write(profile, """
                com.example.App
                com.example.Greeting
                com.example.lambda.Twice
                com.example.lib.Greeter
                """);
        Path buildFile = directory.resolve("build.gradle");
        write(buildFile, Files.readString(buildFile) + "\nmicronautRunner { options.put('desugarLambdas', 'false') }\n");
        build(directory, "micronautRunnerJar");
        assertEquals(extract(directory.resolve(DEFAULT_ARCHIVE), directory.resolve("extract-kept")), files);
    }

    /**
     * Whether a dry run schedules a task.
     *
     * @param result the dry run
     * @param path   the task path
     * @return whether a line names it
     */
    static boolean scheduled(BuildResult result, String path) {
        return result.getOutput().lines().anyMatch(line -> line.startsWith(path + " "));
    }

    /**
     * The generated lambda classes of every JAR in a layout.
     *
     * @param layout the layout
     * @return {@code jar!/entry} for each entry whose name has {@code $$Lambda$R}
     * @throws IOException if a JAR cannot be read
     */
    static List<String> generatedLambdaClasses(Path layout) throws IOException {
        List<Path> jars;
        try (Stream<Path> walk = Files.walk(layout)) {
            jars = walk.filter(file -> file.toString().endsWith(".jar")).sorted().toList();
        }
        List<String> generated = new ArrayList<>();
        for (Path jar : jars) {
            for (String name : entryNames(jar)) {
                if (name.contains("$$Lambda$R")) {
                    generated.add(layout.relativize(jar) + "!/" + name);
                }
            }
        }
        return generated;
    }
}
