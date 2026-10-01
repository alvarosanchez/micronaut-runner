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

import io.micronaut.core.annotation.Internal;
import io.micronaut.runner.build.AotCacheOutput;
import io.micronaut.runner.build.AotLayout;
import io.micronaut.runner.build.AotTarget;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@code io.micronaut.runner.build.aotcache} generic and internal: its sources import nothing of Runner's
 * but the build logger and the training driver, and every public type there, and the Runner-specific types built
 * on it, nested types included, carries {@code @Internal}, which japicmp reads from the class files. The import
 * check reads the sources, so it runs from the project directory, as Gradle runs tests.
 */
class AotCachePackageBoundaryTest {

    private static final Path SOURCES = Path.of("src/main/java/io/micronaut/runner/build");

    private static final Pattern RUNNER_IMPORT = Pattern.compile("^import\\s+(static\\s+)?(io\\.micronaut\\.runner\\.[\\w.*]+)\\s*;",
            Pattern.MULTILINE);

    /** The Runner-specific types built on this package, which the build plugins call. */
    private static final List<Class<?>> BUILD_TYPES = List.of(AotCacheOutput.class, AotLayout.class, AotTarget.class);

    /**
     * The public types of this package that are not {@code @Internal}: public only because {@code AotCacheOutput},
     * in another package, calls them, and package-private once this package joins that one.
     */
    private static final Set<String> PACKAGE_PRIVATE_AFTER_MERGE = Set.of(AotCacheBuilder.class.getName(),
            AotLaunchOptions.class.getName());

    @Test
    void thePackageImportsOnlyTheLoggerAndTheTrainingDriverOfRunner() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : sources()) {
            Matcher imports = RUNNER_IMPORT.matcher(Files.readString(source, StandardCharsets.UTF_8));
            while (imports.find()) {
                String imported = imports.group(2);
                boolean allowed = imported.equals("io.micronaut.runner.build.BuildLogger")
                        || imported.startsWith("io.micronaut.runner.build.training.");
                if (!allowed) {
                    offenders.add(source.getFileName() + " imports " + imported);
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "the aotcache package must stay generic: " + offenders);
    }

    @Test
    void everyPublicTypeIsInternal() throws IOException, ClassNotFoundException {
        Set<String> checked = new TreeSet<>();
        List<String> missing = new ArrayList<>();
        for (String name : classNames()) {
            boolean inScope = name.startsWith(getClass().getPackageName() + ".") || BUILD_TYPES.stream()
                    .anyMatch(type -> name.equals(type.getName()) || name.startsWith(type.getName() + "$"));
            if (!inScope || PACKAGE_PRIVATE_AFTER_MERGE.contains(name)) {
                continue;
            }
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            if (!exported(type)) {
                continue;
            }
            checked.add(type.getName());
            // Its own annotation, as micronaut-build reads it: an @Internal enclosing type does not cover it.
            if (type.getDeclaredAnnotation(Internal.class) == null) {
                missing.add(type.getName());
            }
        }
        assertTrue(checked.containsAll(List.of(AotCacheOutput.class.getName(), AotLayout.class.getName(),
                AotLayout.Result.class.getName(), AotTarget.class.getName(), AotCacheGate.class.getName(),
                AotCacheGate.Coverage.class.getName(), AotCacheReport.class.getName(),
                AotCacheSettings.class.getName(), AotCacheSettings.Builder.class.getName(), JdkProbe.class.getName())),
                checked::toString);
        assertTrue(missing.isEmpty(), () -> "public, and not @Internal: " + missing);
    }

    @Test
    void theTwoExceptionsAreStillPublic() throws ClassNotFoundException {
        for (String name : PACKAGE_PRIVATE_AFTER_MERGE) {
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            assertTrue(exported(type), name + " is no longer public: drop it from the list");
            assertEquals(getClass().getPackageName(), type.getPackageName());
        }
    }

    /** Whether a type is public, and nested only in public types. */
    private static boolean exported(Class<?> type) {
        if (type.isAnonymousClass() || type.isLocalClass() || type.isSynthetic()) {
            return false;
        }
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (!Modifier.isPublic(current.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    /** Every class of runner-build's main code, read from where this test's class path loads it from. */
    private static List<String> classNames() throws IOException {
        Path location;
        try {
            location = Path.of(AotCacheSettings.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(location)) {
            try (Stream<Path> files = Files.walk(location)) {
                for (Path file : files.filter(file -> file.toString().endsWith(".class")).toList()) {
                    names.add(className(location.relativize(file).toString()
                            .replace(file.getFileSystem().getSeparator(), "/")));
                }
            }
        } else {
            try (JarFile jar = new JarFile(location.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String entry = entries.nextElement().getName();
                    if (entry.endsWith(".class") && !entry.startsWith("META-INF/")) {
                        names.add(className(entry));
                    }
                }
            }
        }
        names.removeIf(name -> name.endsWith("package-info") || name.endsWith("module-info"));
        assertFalse(names.isEmpty(), () -> "no classes at " + location);
        return names;
    }

    private static String className(String path) {
        return path.substring(0, path.length() - ".class".length()).replace('/', '.');
    }

    private static List<Path> sources() throws IOException {
        Path directory = SOURCES.resolve("aotcache");
        assertTrue(Files.isDirectory(directory), () -> directory.toAbsolutePath() + " is not there: run the test from"
                + " the runner-build project directory");
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> sources = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !file.getFileName().toString().equals("package-info.java"))
                    .sorted()
                    .toList();
            assertFalse(sources.isEmpty());
            return sources;
        }
    }
}
