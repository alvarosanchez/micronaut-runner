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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the JDK's executable-JAR module attributes in fresh JVMs, not merely as manifest text. */
class ManifestModuleAccessTest {

    private static final String EXPORT_MAIN = "com.example.ExportAccess";
    private static final String OPEN_MAIN = "com.example.OpenAccess";
    private static final List<String> EXPORTS = List.of(
            "java.base/sun.nio.ch",
            "java.base/jdk.internal.misc",
            "java.base/jdk.internal.access",
            "java.base/jdk.internal.module");
    private static final String EXPORT_VALUE = String.join(" ", EXPORTS);

    private static final String EXPORT_SOURCE = """
            package com.example;

            import java.nio.ByteBuffer;

            public final class ExportAccess {
                public static void main(String[] args) {
                    long address = ((sun.nio.ch.DirectBuffer) ByteBuffer.allocateDirect(1)).address();
                    if (address == 0) {
                        throw new AssertionError("direct buffer has no address");
                    }
                    System.out.println("EXPORT OK " + address);
                }
            }
            """;

    private static final String OPEN_SOURCE = """
            package com.example;

            import java.lang.reflect.Field;

            public final class OpenAccess {
                public static void main(String[] args) throws Exception {
                    Field value = String.class.getDeclaredField("value");
                    value.setAccessible(true);
                    Object bytes = value.get("opened");
                    if (java.lang.reflect.Array.getLength(bytes) == 0) {
                        throw new AssertionError("String.value is empty");
                    }
                    System.out.println("OPEN OK " + bytes.getClass().getName());
                }
            }
            """;

    @TempDir
    static Path workspace;

    private static Path classes;

    @BeforeAll
    static void compileAccessFixtures() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the module-access test requires a JDK");
        classes = workspace.resolve("classes");
        Path sources = workspace.resolve("sources");
        Path export = write(sources.resolve("com/example/ExportAccess.java"), EXPORT_SOURCE);
        Path open = write(sources.resolve("com/example/OpenAccess.java"), OPEN_SOURCE);
        Files.createDirectories(classes);

        int status = compiler.run(null, null, null,
                "--source", "25",
                "--target", "25",
                "--add-exports", "java.base/sun.nio.ch=ALL-UNNAMED",
                "-d", classes.toString(),
                export.toString(), open.toString());
        assertEquals(0, status, "could not compile the module-access fixtures");
    }

    @Test
    void addExportsGrantsRealInternalApiAccessAndPreservesWrappedReproducibleManifestBytes()
            throws Exception {
        Path withoutExport = runnerJar("without-export.jar", EXPORT_MAIN, List.of(), List.of());
        Forked denied = runJar(withoutExport);
        assertDenied(denied, "IllegalAccessError", "sun.nio.ch.DirectBuffer");

        Path first = runnerJar("with-export.jar", EXPORT_MAIN, EXPORTS, List.of());
        Path second = runnerJar("with-export-again.jar", EXPORT_MAIN, EXPORTS, List.of());
        Forked allowed = runJar(first);
        assertEquals(0, allowed.status(), allowed::output);
        assertTrue(allowed.output().contains("EXPORT OK"), allowed::output);

        assertEquals(EXPORT_VALUE, manifest(first).getMainAttributes().getValue("Add-Exports"));
        String raw = rawManifest(first);
        assertTrue(raw.contains("Add-Exports: "), () -> "missing Add-Exports:\n" + raw);
        assertTrue(raw.substring(raw.indexOf("Add-Exports: ")).contains("\r\n "),
                () -> "the long Add-Exports value was not wrapped:\n" + raw);
        assertFalse(raw.contains("=ALL-UNNAMED"), () -> "command-line syntax leaked into the manifest:\n" + raw);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second),
                "the same ordered module/package list must produce the same archive bytes");
    }

    @Test
    void commandLineExportSuffixIsIgnoredByTheJarLauncher() throws Exception {
        Path bad = plainExecutableJar("command-line-syntax.jar", EXPORT_MAIN,
                Map.of("Add-Exports", "java.base/sun.nio.ch=ALL-UNNAMED"));

        Forked denied = runJar(bad);

        assertDenied(denied, "IllegalAccessError", "sun.nio.ch.DirectBuffer");
    }

    @Test
    void addOpensGrantsDeepReflectionSeparatelyFromExports() throws Exception {
        Path withoutOpen = runnerJar("without-open.jar", OPEN_MAIN, List.of(), List.of());
        Forked denied = runJar(withoutOpen);
        assertDenied(denied, "InaccessibleObjectException", "java.lang");

        Path withOpen = runnerJar("with-open.jar", OPEN_MAIN, List.of(),
                List.of("java.base/java.lang", "java.base/java.util"));
        Forked allowed = runJar(withOpen);
        assertEquals(0, allowed.status(), allowed::output);
        assertTrue(allowed.output().contains("OPEN OK"), allowed::output);
        assertEquals("java.base/java.lang java.base/java.util",
                manifest(withOpen).getMainAttributes().getValue("Add-Opens"));
    }

    private static Path runnerJar(String name, String mainClass, List<String> exports, List<String> opens)
            throws IOException {
        Path output = workspace.resolve(name);
        RunnerJarBuilder.build(RunnerJarSpec.builder()
                .mainClass(mainClass)
                .applicationOutput(List.of(classes))
                .output(output)
                .entryStub(false)
                .addExports(exports)
                .addOpens(opens)
                .build(), BuildLogger.noOp());
        return output;
    }

    private static Path plainExecutableJar(String name, String mainClass, Map<String, String> extraAttributes)
            throws IOException {
        Path output = workspace.resolve(name);
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
        extraAttributes.forEach(attributes::putValue);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(output), manifest)) {
            String entryName = mainClass.replace('.', '/') + ".class";
            out.putNextEntry(new ZipEntry(entryName));
            Files.copy(classes.resolve(entryName), out);
            out.closeEntry();
        }
        return output;
    }

    private static Forked runJar(Path archive) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-jar");
        command.add(archive.toAbsolutePath().toString());
        ProcessBuilder builder = new ProcessBuilder(command)
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
        return new Forked(process.waitFor(), output);
    }

    private static void assertDenied(Forked forked, String exception, String detail) {
        assertTrue(forked.status() != 0, () -> "access unexpectedly succeeded:\n" + forked.output());
        assertTrue(forked.output().contains(exception), forked::output);
        assertTrue(forked.output().contains(detail), forked::output);
    }

    private static Manifest manifest(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile());
             InputStream in = zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF"))) {
            return new Manifest(in);
        }
    }

    private static String rawManifest(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile());
             InputStream in = zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF"))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private record Forked(int status, String output) {
    }
}
