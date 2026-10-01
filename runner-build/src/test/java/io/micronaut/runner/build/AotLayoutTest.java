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
package io.micronaut.runner.build;

import io.micronaut.runner.build.aotcache.AotCacheFixture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AotLayout}: a Runner JAR with two dependencies, extracted once with the JDK at
 * {@code runner.test.javaHome}, then copied with its modification times and broken in the ways a JDK AOT cache
 * trained on it would reject, each of which {@link AotLayout#verify(Path, Path)} has to name: a JAR whose
 * modification time changed, a {@code Class-Path} out of class-path order, and a {@code Class-Path} entry that is
 * missing from {@code lib/}.
 */
class AotLayoutTest {

    private static final String ALPHA = "alpha.jar";

    private static final String BETA = "beta.jar";

    @TempDir
    static Path temp;

    private static Path java;
    private static Path runnerJar;
    private static Path extracted;
    private static AotLayout.Result result;

    @BeforeAll
    static void extractOnce() throws Exception {
        java = javaExecutable();
        Assumptions.assumeTrue(
                RunnerJarBuilder.class.getResource("/META-INF/micronaut-runner/launcher.jar") != null,
                "the bundled launcher jar is not on the test class path");
        runnerJar = packageFixture(temp.resolve("package"));
        extracted = temp.resolve("extracted");
        result = AotLayout.write(java, runnerJar, extracted, AotLayout.DEFAULT_TIMEOUT);
    }

    @Test
    void theExtractedLayoutFollowsTheClassPathOrderAndVerifies() throws IOException {
        assertEquals(extracted.resolve(AotLayout.applicationJarName(runnerJar)), result.applicationJar());
        assertEquals(List.of(extracted.resolve("lib").resolve(ALPHA), extracted.resolve("lib").resolve(BETA)),
                result.libraries());
        assertEquals(List.of("lib/" + ALPHA, "lib/" + BETA), classPath(result.applicationJar()));
        assertEquals(result, AotLayout.verify(extracted, runnerJar));
        assertEquals("Wrote the layout " + AotLayout.applicationJarName(runnerJar) + " with 2 JARs in lib/ to "
                + extracted.toAbsolutePath().normalize(), result.summary());
    }

    @Test
    void aJavaPathRelativeToTheWorkingDirectoryExtracts() throws Exception {
        Path relative;
        try {
            relative = Path.of("").toAbsolutePath().relativize(java.toAbsolutePath());
        } catch (IllegalArgumentException e) {
            Assumptions.abort("the JDK and the working directory are on different roots: " + e.getMessage());
            return;
        }
        Assumptions.assumeTrue(relative.getParent() != null, "the JDK is in the working directory");

        // The extraction runs in the destination's parent, which is not this JVM's working directory. It is deeper
        // than the path climbs, so the path cannot reach the JDK from there by climbing to the root.
        Path destination = deeperThan(temp.resolve("relative-java"), relative).resolve("layout");
        AotLayout.Result relativeResult = AotLayout.write(relative, runnerJar, destination, AotLayout.DEFAULT_TIMEOUT);

        assertEquals(2, relativeResult.libraries().size());
    }

    /**
     * A directory below another, with more levels than a relative path has {@code ..} segments.
     *
     * @param base     the directory to start from
     * @param relative the relative path
     * @return the directory
     */
    private static Path deeperThan(Path base, Path relative) {
        Path directory = base;
        for (Path element : relative) {
            if ("..".equals(element.toString())) {
                directory = directory.resolve("d");
            }
        }
        return directory.resolve("d");
    }

    @Test
    void aTouchedJarFailsVerificationNamingIt() throws IOException {
        Path library = copy("touched-library");
        Files.setLastModifiedTime(library.resolve("lib").resolve(BETA), FileTime.from(Instant.now()));

        IOException libraryFailure = assertThrows(IOException.class, () -> AotLayout.verify(library, runnerJar));

        assertTrue(libraryFailure.getMessage().startsWith("lib/" + BETA + " was modified at "),
                libraryFailure.getMessage());
        assertTrue(libraryFailure.getMessage().contains("not at " + AotLayout.FILE_TIME),
                libraryFailure.getMessage());

        Path application = copy("touched-application");
        String applicationJar = AotLayout.applicationJarName(runnerJar);
        Files.setLastModifiedTime(application.resolve(applicationJar), FileTime.from(Instant.now()));

        IOException applicationFailure = assertThrows(IOException.class,
                () -> AotLayout.verify(application, runnerJar));

        assertTrue(applicationFailure.getMessage().startsWith(applicationJar + " was modified at "),
                applicationFailure.getMessage());
    }

    @Test
    void aClassPathOutOfOrderFailsVerificationNamingTheEntry() throws IOException {
        Path layout = copy("reordered");
        Path applicationJar = layout.resolve(AotLayout.applicationJarName(runnerJar));
        rewriteClassPath(applicationJar, "lib/" + BETA + " lib/" + ALPHA);
        assertEquals(AotLayout.FILE_TIME, Files.getLastModifiedTime(applicationJar), "only the order is wrong");

        IOException failure = assertThrows(IOException.class, () -> AotLayout.verify(layout, runnerJar));

        assertTrue(failure.getMessage().contains("does not follow the class-path order of "
                + runnerJar.getFileName() + ": entry 1 is lib/" + BETA + ", not lib/" + ALPHA), failure.getMessage());
    }

    @Test
    void aClassPathEntryMissingFromLibFailsVerificationNamingIt() throws IOException {
        Path layout = copy("missing");
        Files.delete(layout.resolve("lib").resolve(ALPHA));

        IOException failure = assertThrows(IOException.class, () -> AotLayout.verify(layout, runnerJar));

        assertTrue(failure.getMessage().contains("has no lib/" + ALPHA + ", which "
                + AotLayout.applicationJarName(runnerJar) + " names in its Class-Path"), failure.getMessage());
    }

    /** Copies the extracted layout with its modification times, as {@code cp -p} does. */
    private static Path copy(String name) throws IOException {
        Path target = temp.resolve("copies").resolve(name);
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(extracted)) {
            sources = walk.sorted().toList();
        }
        for (Path source : sources) {
            Path destination = target.resolve(extracted.relativize(source).toString());
            if (Files.isDirectory(source)) {
                Files.createDirectories(destination);
            } else {
                Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        return target;
    }

    /** Writes the JAR again with another {@code Class-Path}, and gives it back the extracted modification time. */
    private static void rewriteClassPath(Path jar, String classPath) throws IOException {
        Path rewritten = jar.resolveSibling(jar.getFileName() + ".rewritten");
        try (JarFile in = new JarFile(jar.toFile(), false)) {
            Manifest manifest = new Manifest(in.getManifest());
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(rewritten), manifest)) {
                for (Enumeration<JarEntry> entries = in.entries(); entries.hasMoreElements(); ) {
                    JarEntry entry = entries.nextElement();
                    if (JarFile.MANIFEST_NAME.equalsIgnoreCase(entry.getName())) {
                        continue;
                    }
                    out.putNextEntry(new ZipEntry(entry.getName()));
                    try (InputStream data = in.getInputStream(entry)) {
                        data.transferTo(out);
                    }
                    out.closeEntry();
                }
            }
        }
        Files.move(rewritten, jar, StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(jar, AotLayout.FILE_TIME);
    }

    private static List<String> classPath(Path jar) throws IOException {
        try (JarFile in = new JarFile(jar.toFile(), false)) {
            String value = in.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            List<String> tokens = new ArrayList<>();
            Collections.addAll(tokens, value.trim().split("\\s+"));
            return tokens;
        }
    }

    /** Packs one application class into a STORED Runner JAR, and two dependencies in class-path order. */
    private static Path packageFixture(Path directory) throws IOException {
        Path classes = directory.resolve("classes");
        String packagePath = AotCacheFixture.class.getPackageName().replace('.', '/');
        Path applicationClass = classes.resolve(packagePath).resolve(AotCacheFixture.class.getSimpleName() + ".class");
        Files.createDirectories(applicationClass.getParent());
        try (InputStream in = AotCacheFixture.class.getResourceAsStream(
                AotCacheFixture.class.getSimpleName() + ".class")) {
            assertNotNull(in, "the fixture class is not on the test class path");
            Files.write(applicationClass, in.readAllBytes());
        }
        List<Dependency> dependencies = new ArrayList<>();
        for (String name : List.of(ALPHA, BETA)) {
            Path library = directory.resolve(name);
            try (OutputStream file = Files.newOutputStream(library);
                 JarOutputStream out = new JarOutputStream(file)) {
                out.putNextEntry(new ZipEntry(name.replace(".jar", "") + "/resource.txt"));
                out.write(name.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            dependencies.add(Dependency.of(library, "com.example:" + name.replace(".jar", "") + ":1.0"));
        }
        Path archive = directory.resolve("layout-fixture-1.0-all.jar");
        RunnerJarBuilder.build(RunnerJarSpec.builder()
                .mainClass(AotCacheFixture.class.getName())
                .applicationOutput(List.of(classes))
                .dependencies(dependencies)
                .compression(Compression.STORED)
                .entryStub(true)
                .output(archive)
                .build(), BuildLogger.noOp());
        return archive;
    }

    private static Path javaExecutable() {
        String home = System.getProperty("runner.test.javaHome", System.getProperty("java.home"));
        Path candidate = Path.of(home, "bin", "java");
        if (!Files.isExecutable(candidate)) {
            candidate = Path.of(home, "bin", "java.exe");
        }
        Path found = candidate;
        assertTrue(Files.isExecutable(found), () -> "no java executable under " + home);
        return found;
    }
}
