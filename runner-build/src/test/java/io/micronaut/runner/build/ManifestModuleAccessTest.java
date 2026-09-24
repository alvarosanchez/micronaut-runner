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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the JDK's executable-JAR module attributes in a fresh JVM, not merely as manifest text. */
class ManifestModuleAccessTest {

    private static final String MAIN_CLASS = "com.example.ModuleAccess";

    private static final String SOURCE = """
            package com.example;

            import java.lang.reflect.Field;
            import java.nio.ByteBuffer;

            public final class ModuleAccess {
                public static void main(String[] args) throws Exception {
                    long address = ((sun.nio.ch.DirectBuffer) ByteBuffer.allocateDirect(1)).address();
                    if (address == 0) {
                        throw new AssertionError("direct buffer has no address");
                    }
                    System.out.println("EXPORT OK");

                    Field value = String.class.getDeclaredField("value");
                    value.setAccessible(true);
                    Object bytes = value.get("opened");
                    if (java.lang.reflect.Array.getLength(bytes) == 0) {
                        throw new AssertionError("String.value is empty");
                    }
                    System.out.println("OPEN OK");
                }
            }
            """;

    /**
     * One archive grants an export and an open, and a fresh JVM makes a call that needs each.
     *
     * <p>There is no negative control: the JDK exports {@code sun.nio.ch} and opens {@code java.lang} to no
     * unnamed module by default, and the child JVM runs with its option environment variables cleared, so
     * only the manifest can grant either access.</p>
     *
     * @param workspace a fresh directory for the fixture and the archive
     * @throws Exception if the fixture cannot be compiled, packaged or launched
     */
    @Test
    void addExportsAndAddOpensGrantAccessInAFreshJvm(@TempDir Path workspace) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the module-access test requires a JDK");
        Path classes = Files.createDirectories(workspace.resolve("classes"));
        Path source = workspace.resolve("sources/com/example/ModuleAccess.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, SOURCE, StandardCharsets.UTF_8);
        int status = compiler.run(null, null, null,
                "--source", "25",
                "--target", "25",
                "--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED",
                "-d", classes.toString(),
                source.toString());
        assertEquals(0, status, "could not compile the module-access fixture");

        Path archive = workspace.resolve("module-access.jar");
        RunnerJarBuilder.build(RunnerJarSpec.builder()
                .mainClass(MAIN_CLASS)
                .applicationOutput(List.of(classes))
                .output(archive)
                .entryStub(false)
                .addExports(List.of("java.base/sun.nio.ch"))
                .addOpens(List.of("java.base/java.lang"))
                .build(), BuildLogger.noOp());

        Attributes attributes = manifest(archive).getMainAttributes();
        assertEquals("java.base/sun.nio.ch", attributes.getValue("Add-Exports"));
        assertEquals("java.base/java.lang", attributes.getValue("Add-Opens"));

        ProcessBuilder builder = new ProcessBuilder(javaExecutable().toString(), "-jar",
                archive.toAbsolutePath().toString())
                .directory(workspace.toFile())
                .redirectErrorStream(true);
        for (String variable : List.of("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(variable);
        }
        Process process = builder.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(0, process.waitFor(), output);
        assertTrue(output.contains("EXPORT OK"), output);
        assertTrue(output.contains("OPEN OK"), output);
    }

    private static Manifest manifest(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile());
             InputStream in = zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF"))) {
            return new Manifest(in);
        }
    }

    private static Path javaExecutable() {
        String home = System.getProperty("runner.test.javaHome", System.getProperty("java.home"));
        Path executable = Path.of(home, "bin", "java");
        if (!Files.isExecutable(executable)) {
            executable = Path.of(home, "bin", "java.exe");
        }
        assertTrue(Files.isExecutable(executable), () -> "no java executable under " + home);
        return executable;
    }
}
