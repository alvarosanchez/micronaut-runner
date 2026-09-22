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

import io.micronaut.runner.Entry;
import io.micronaut.runner.Index;
import io.micronaut.runner.IndexFormat;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.AccessFlag;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EntryStubGenerator} and for the way {@link RunnerJarBuilder} uses it.
 *
 * <p>There are four layers of assertion here, and only the last one really proves anything: the bytes of
 * the generated class (its version above all, which no other test would notice going wrong), its structure,
 * that the packager puts it in the archive and names it in the index header, and finally
 * {@link #startsTheApplicationThroughTheStub()}, which forks {@code java -jar} and has the application
 * print its own stack trace from inside {@code main}. A frame of
 * {@code io.micronaut.runner.generated.AppEntry} in that trace is the only evidence that the stub was used
 * rather than the launcher's reflective fallback, which starts the very same application just as happily;
 * {@link #startsTheApplicationWithoutTheStubWhenItIsNotRequested()} runs the same fixture with the stub
 * turned off and asserts the opposite trace, so the evidence is known to discriminate.</p>
 */
class EntryStubGeneratorTest {

    @TempDir
    static Path fixtures;

    /** Where the application fixture stores what it saw, so that the assertions can name the difference. */
    private static final String PROBE_PROPERTY = "micronaut.runner.test.entryStub";

    /** The line the forked application prints when it reached the end of {@code main}. */
    private static final String RESULT_OK = "RESULT OK";

    /** An eligible main class, and the one the forked application uses. */
    private static final String APPLICATION_CLASS = "com.example.Application";

    /** The application fixture, which prints the stack it was entered through. */
    private static final String APPLICATION_SOURCE = """
            package com.example;

            public class Application {

                public static void main(String[] args) {
                    StringBuilder trace = new StringBuilder();
                    for (StackTraceElement frame : new Throwable().getStackTrace()) {
                        trace.append(frame.getClassName()).append('#')
                                .append(frame.getMethodName()).append(' ');
                    }
                    System.out.println("STACK " + trace);
                    System.out.println("ARGS " + String.join("|", args));
                    System.out.println("RESULT OK");
                }
            }
            """;

    /** One main class per ineligibility rule, plus the two that are eligible. */
    private static final Map<String, String> FIXTURE_SOURCES = Map.of(
            "com/example/Application.java", APPLICATION_SOURCE,
            "probe/Probe.java", """
                    package probe;

                    public class Probe {
                        public static void main(String[] args) {
                            System.setProperty("%s", String.join("|", args));
                        }
                    }
                    """.formatted(PROBE_PROPERTY),
            "variants/NotPublic.java", """
                    package variants;

                    class NotPublic {
                        public static void main(String[] args) {
                        }
                    }
                    """,
            "variants/Base.java", """
                    package variants;

                    public class Base {
                        public static void main(String[] args) {
                        }
                    }
                    """,
            "variants/Inheriting.java", """
                    package variants;

                    public class Inheriting extends Base {
                    }
                    """,
            "variants/InstanceMain.java", """
                    package variants;

                    public class InstanceMain {
                        public void main(String[] args) {
                        }
                    }
                    """,
            "variants/NoArguments.java", """
                    package variants;

                    public class NoArguments {
                        public static void main() {
                        }
                    }
                    """,
            "variants/ProtectedMain.java", """
                    package variants;

                    public class ProtectedMain {
                        protected static void main(String[] args) {
                        }
                    }
                    """,
            "variants/Returning.java", """
                    package variants;

                    public class Returning {
                        public static String main(String[] args) {
                            return "not void";
                        }
                    }
                    """,
            "Unpackaged.java", """
                    public class Unpackaged {
                        public static void main(String[] args) {
                        }
                    }
                    """);

    /** The rest of them: {@link Map#of} takes at most ten pairs. */
    private static final Map<String, String> MORE_FIXTURE_SOURCES = Map.of(
            "variants/AbstractMain.java", """
                    package variants;

                    public abstract class AbstractMain {
                        public static void main(String[] args) {
                        }
                    }
                    """,
            "variants/MainInterface.java", """
                    package variants;

                    public interface MainInterface {
                        static void main(String[] args) {
                        }
                    }
                    """);

    private static Path classes;
    private static Path noArgumentVariant;
    private static Path protectedVariant;
    private static Path compatibleVariant;
    private static Path stubArchive;
    private static Path reflectiveArchive;
    private static int counter;

    @BeforeAll
    static void createFixtures() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "this JDK has no java compiler");
        Assumptions.assumeTrue(
                RunnerJarBuilder.class.getResource("/META-INF/micronaut-runner/launcher.jar") != null,
                "the bundled launcher jar is not on the test class path");

        classes = fixtures.resolve("classes");
        Map<String, String> sources = new TreeMap<>(FIXTURE_SOURCES);
        sources.putAll(MORE_FIXTURE_SOURCES);
        compile(compiler, fixtures.resolve("sources"), classes, sources);

        noArgumentVariant = fixtures.resolve("variants/no-argument");
        compile(compiler, fixtures.resolve("variant-sources/no-argument"), noArgumentVariant, Map.of(
                "com/example/Application.java", """
                        package com.example;

                        public class Application {
                            public static void main() {
                                printTrace("MR NO ARGUMENT");
                            }

                            private static void printTrace(String result) {
                                StringBuilder trace = new StringBuilder();
                                for (StackTraceElement frame : new Throwable().getStackTrace()) {
                                    trace.append(frame.getClassName()).append('#')
                                            .append(frame.getMethodName()).append(' ');
                                }
                                System.out.println("STACK " + trace);
                                System.out.println(result);
                            }
                        }
                        """));
        protectedVariant = fixtures.resolve("variants/protected");
        compile(compiler, fixtures.resolve("variant-sources/protected"), protectedVariant, Map.of(
                "com/example/Application.java", """
                        package com.example;

                        public class Application {
                            protected static void main(String[] args) {
                                StringBuilder trace = new StringBuilder();
                                for (StackTraceElement frame : new Throwable().getStackTrace()) {
                                    trace.append(frame.getClassName()).append('#')
                                            .append(frame.getMethodName()).append(' ');
                                }
                                System.out.println("STACK " + trace);
                                System.out.println("MR PROTECTED " + String.join("|", args));
                            }
                        }
                        """));
        compatibleVariant = fixtures.resolve("variants/compatible");
        compile(compiler, fixtures.resolve("variant-sources/compatible"), compatibleVariant, Map.of(
                "com/example/Application.java", """
                        package com.example;

                        public class Application {
                            public static void main(String[] args) {
                                StringBuilder trace = new StringBuilder();
                                for (StackTraceElement frame : new Throwable().getStackTrace()) {
                                    trace.append(frame.getClassName()).append('#')
                                            .append(frame.getMethodName()).append(' ');
                                }
                                System.out.println("STACK " + trace);
                                System.out.println("MR COMPATIBLE " + String.join("|", args));
                            }
                        }
                        """));

        stubArchive = output();
        RunnerJarBuilder.build(spec(stubArchive).entryStub(true).build(), BuildLogger.noOp());
        reflectiveArchive = output();
        RunnerJarBuilder.build(spec(reflectiveArchive).entryStub(false).build(), BuildLogger.noOp());
    }

    @Test
    void generatesAJavaTwentyFiveClassFileWhateverTheJdkThatPackages() {
        byte[] stub = EntryStubGenerator.generate(APPLICATION_CLASS);

        assertEquals(0xCAFEBABE, ((stub[0] & 0xFF) << 24) | ((stub[1] & 0xFF) << 16)
                | ((stub[2] & 0xFF) << 8) | (stub[3] & 0xFF), "the magic number");
        // The version is the point of this test: java.lang.classfile defaults to the version of the JDK
        // running the packager, so packaging on a newer JDK would emit a class the JDK 25 that runs the
        // application cannot load. These are the raw bytes of the class file, u2 minor then u2 major.
        assertEquals(0, ((stub[4] & 0xFF) << 8) | (stub[5] & 0xFF), "the minor version");
        assertEquals(69, ((stub[6] & 0xFF) << 8) | (stub[7] & 0xFF), "the major version, 69 = Java 25");
        assertEquals(69, EntryStubGenerator.CLASS_FILE_MAJOR_VERSION);
        assertEquals(0, EntryStubGenerator.CLASS_FILE_MINOR_VERSION);
        assertTrue(ClassFile.latestMajorVersion() >= 69,
                "a JDK older than 25 cannot build this project at all");

        ClassModel model = ClassFile.of().parse(stub);
        assertEquals(69, model.majorVersion());
        assertEquals(0, model.minorVersion());

        assertArrayEquals(stub, EntryStubGenerator.generate(APPLICATION_CLASS),
                "the same application has to produce the same bytes, or a rebuild is not reproducible");
    }

    @Test
    void generatesTheEntryTheLauncherExpects() {
        ClassModel model = ClassFile.of().parse(EntryStubGenerator.generate(APPLICATION_CLASS));

        assertEquals("io/micronaut/runner/generated/AppEntry", model.thisClass().asInternalName());
        assertEquals("io.micronaut.runner.generated.AppEntry", EntryStubGenerator.STUB_CLASS);
        assertEquals("io/micronaut/runner/generated/AppEntry.class", EntryStubGenerator.STUB_RESOURCE_NAME);
        assertEquals("java/lang/Object", model.superclass().orElseThrow().asInternalName());
        assertTrue(model.flags().has(AccessFlag.PUBLIC), "the launcher loads it by name, so it is public");
        assertTrue(model.flags().has(AccessFlag.FINAL));
        assertEquals(1, model.interfaces().size());
        assertEquals("io/micronaut/runner/Entry", model.interfaces().get(0).asInternalName());
        assertEquals(3, model.methods().size(), "a constructor, a static initialiser and run");

        MethodModel constructor = method(model, "<init>");
        assertEquals("()V", constructor.methodType().stringValue());
        assertTrue(constructor.flags().has(AccessFlag.PUBLIC), "the static initialiser calls it");

        MethodModel initialiser = method(model, "<clinit>");
        assertTrue(initialiser.flags().has(AccessFlag.STATIC));
        assertTrue(invocations(initialiser).contains(
                        "io/micronaut/runner/Launcher#register:(Lio/micronaut/runner/Entry;)V"),
                "the stub has to register itself: " + invocations(initialiser));

        MethodModel run = method(model, "run");
        assertEquals("([Ljava/lang/String;)V", run.methodType().stringValue());
        assertTrue(run.flags().has(AccessFlag.PUBLIC));
        assertFalse(run.flags().has(AccessFlag.STATIC));
        ExceptionsAttribute thrown = run.findAttribute(Attributes.exceptions()).orElseThrow();
        assertEquals(1, thrown.exceptions().size());
        assertEquals("java/lang/Throwable", thrown.exceptions().get(0).asInternalName(),
                "the launcher rethrows what the application throws, unchanged");
        assertTrue(invocations(run).contains("com/example/Application#main:([Ljava/lang/String;)V"),
                "run has to call the application main method directly: " + invocations(run));
    }

    /**
     * Defines the generated class in this JVM and calls it: the whole contract in one test, including the
     * part no structural assertion covers - that the class verifies, that its static initialiser reaches
     * the real {@code Launcher.register}, and that the instance it hands over runs the main method.
     *
     * <p>A runner jar starts once per JVM, so this is the only test that may register an entry point.</p>
     */
    @Test
    void registersItselfWithTheLauncherAndRunsTheMainMethod() throws Throwable {
        byte[] stub = EntryStubGenerator.generate("probe.Probe");
        System.clearProperty(PROBE_PROPERTY);

        try (Defining loader = new Defining(classes)) {
            Class<?> type = loader.define(EntryStubGenerator.STUB_CLASS, stub);
            assertSame(Entry.class, type.getInterfaces()[0],
                    "the stub implements the launcher's own Entry, not a copy of it");

            // Instantiating runs the static initialiser, which is what registers the entry point; the
            // launcher does the same thing with Class.forName(stub, true, loader).
            Entry entry = (Entry) type.getDeclaredConstructor().newInstance();
            assertNull(System.getProperty(PROBE_PROPERTY), "the main method must not run before run()");

            entry.run(new String[] {"alpha", "beta"});
            assertEquals("alpha|beta", System.getProperty(PROBE_PROPERTY),
                    "run() has to call the application main method with the arguments it was given");
        } finally {
            System.clearProperty(PROBE_PROPERTY);
        }
    }

    @Test
    void onlyAPublicClassDeclaringItsOwnPublicStaticMainIsEligible() throws IOException {
        assertNull(reasonFor(APPLICATION_CLASS), "the fixture application is eligible");
        assertNull(reasonFor("probe.Probe"));

        assertReason("variants.NotPublic", "is not public");
        assertReason("variants.Inheriting", "does not declare its own public static void main(String[])");
        assertReason("variants.InstanceMain", "is not static");
        assertReason("variants.NoArguments", "does not declare its own public static void main(String[])");
        assertReason("variants.ProtectedMain", "is not public");
        assertReason("variants.Returning", "does not declare its own public static void main(String[])");
        assertReason("variants.AbstractMain", "is abstract");
        assertReason("variants.MainInterface", "is an interface");
        assertReason("Unpackaged", "default package");
    }

    @Test
    void refusesWhatItCannotParseOrCannotTrust() throws IOException {
        String mismatch = EntryStubGenerator.ineligibilityReason(APPLICATION_CLASS, classFile("probe.Probe"));
        assertNotNull(mismatch);
        assertTrue(mismatch.contains("declares itself to be probe.Probe"), mismatch);

        String unparseable = EntryStubGenerator.ineligibilityReason(APPLICATION_CLASS,
                "not a class file".getBytes(StandardCharsets.UTF_8));
        assertNotNull(unparseable);
        assertTrue(unparseable.contains("cannot be parsed"), unparseable);

        // A name the generator could not describe - a typo in a plugin's configuration reaches here -
        // has to come back as a reason rather than as an exception out of the packager.
        String unnameable = EntryStubGenerator.ineligibilityReason("com.example.",
                classFile("probe.Probe"));
        assertNotNull(unnameable);
        assertTrue(unnameable.contains("not a name the generated code can refer to"), unnameable);
    }

    @Test
    void theArchiveCarriesTheStubAndTheIndexHeaderNamesIt() throws IOException {
        Recording logger = new Recording();
        Path archive = output();
        RunnerJarBuilder.build(spec(archive).entryStub(true).build(), logger);

        try (RunnerJarReader reader = RunnerJarReader.open(archive)) {
            Index index = reader.index();
            assertEquals(EntryStubGenerator.STUB_CLASS, index.entryStubClass(),
                    "the header is what sends the launcher to the stub");
            assertEquals(APPLICATION_CLASS, index.startClass());

            int record = index.findClass(EntryStubGenerator.STUB_CLASS);
            assertTrue(record != IndexFormat.NO_INDEX, "the launcher has to be able to find the class");
            assertEquals(0, index.entryJarId(record), "the stub belongs to the application layer");
            assertEquals(EntryStubGenerator.STUB_RESOURCE_NAME, index.entryName(record));

            ClassModel stored = ClassFile.of().parse(reader.read(record));
            assertEquals(69, stored.majorVersion(), "the archived bytes are the Java 25 ones");
            assertTrue(invocations(method(stored, "run"))
                    .contains("com/example/Application#main:([Ljava/lang/String;)V"));
        }
        try (ZipReader archiveEntries = ZipReader.open(archive)) {
            assertTrue(archiveEntries.entry(
                            IndexFormat.CLASSES_PREFIX + EntryStubGenerator.STUB_RESOURCE_NAME).isPresent(),
                    "the stub is stored as an application-layer entry");
        }
        assertTrue(logger.infos.stream().anyMatch(line -> line.contains("Generated the entry stub")),
                logger.infos.toString());
    }

    @Test
    void generatesNoStubWhenItIsNotRequested() throws IOException {
        Recording logger = new Recording();
        Path archive = output();
        RunnerJarBuilder.build(spec(archive).entryStub(false).build(), logger);

        assertNoStub(archive);
        assertTrue(logger.infos.stream().anyMatch(line -> line.contains("it was not requested")),
                logger.infos.toString());
    }

    @Test
    void generatesNoStubForAnIneligibleMainClass() throws IOException {
        Recording logger = new Recording();
        Path archive = output();
        RunnerJarBuilder.build(
                spec(archive).mainClass("variants.Inheriting").entryStub(true).build(), logger);

        assertNoStub(archive);
        assertTrue(logger.infos.stream().anyMatch(line -> line.contains("does not declare its own")),
                "the reason is reported at info level: " + logger.infos);
    }

    @Test
    void generatesNoStubWhenTheApplicationAlreadyCarriesTheName() throws IOException {
        Path collision = fixtures.resolve("collision");
        Path occupied = collision.resolve(EntryStubGenerator.STUB_RESOURCE_NAME);
        Files.createDirectories(occupied.getParent());
        Files.write(occupied, EntryStubGenerator.generate("probe.Probe"));
        Recording logger = new Recording();
        Path archive = output();

        RunnerJarBuilder.build(spec(archive)
                .applicationOutput(List.of(classes, collision)).entryStub(true).build(), logger);

        try (RunnerJarReader reader = RunnerJarReader.open(archive)) {
            Index index = reader.index();
            assertNull(index.entryStubClass(),
                    "the packager must not claim a class it did not write");
            assertTrue(index.findClass(EntryStubGenerator.STUB_CLASS) != IndexFormat.NO_INDEX,
                    "the application's colliding class remains packaged and proves the lookup can find it");
        }
        assertTrue(logger.warnings.stream().anyMatch(line -> line.contains("already carries")),
                "taking the name over silently would be worse: " + logger.warnings);
    }

    @Test
    void fallsBackForANoArgumentMultiReleaseMainFromADirectory() throws Exception {
        Assumptions.assumeTrue(javaExecutable() != null, "no JDK to fork; set runner.test.javaHome");
        Path application = multiReleaseDirectory("no-argument-main", "25", noArgumentVariant);
        Path archive = output();
        Recording logger = new Recording();

        RunnerJarBuilder.build(spec(archive)
                .applicationOutput(List.of(application))
                .multiRelease(true)
                .entryStub(true)
                .build(), logger);

        assertNoStub(archive);
        assertTrue(logger.infos.stream().anyMatch(line -> line.contains("META-INF/versions/25")
                        && line.contains("does not declare its own public static void main(String[])")),
                "the selected variant and its reason are reported: " + logger.infos);
        Forked run = fork(archive, List.of("ignored"));
        assertEquals(0, run.status(), () -> "the JVM exited with " + run.status() + "\n" + run.output());
        assertTrue(run.output().contains("MR NO ARGUMENT"), run::output);
        assertTrue(run.output().contains("io.micronaut.runner.Launcher#invokeMain"), run::output);
        assertFalse(run.output().contains("io.micronaut.runner.generated.AppEntry"), run::output);
    }

    @Test
    void fallsBackForAProtectedMultiReleaseMain() throws Exception {
        Assumptions.assumeTrue(javaExecutable() != null, "no JDK to fork; set runner.test.javaHome");
        Path application = multiReleaseDirectory("protected-main", "25", protectedVariant);
        Path archive = output();

        RunnerJarBuilder.build(spec(archive)
                .applicationOutput(List.of(application))
                .multiRelease(true)
                .entryStub(true)
                .build(), BuildLogger.noOp());

        assertNoStub(archive);
        Forked run = fork(archive, List.of("alpha", "beta"));
        assertEquals(0, run.status(), () -> "the JVM exited with " + run.status() + "\n" + run.output());
        assertTrue(run.output().contains("MR PROTECTED alpha|beta"), run::output);
        assertTrue(run.output().contains("io.micronaut.runner.Launcher#invokeMain"), run::output);
    }

    @Test
    void checksFutureVariantsOfTheConfiguredMainInAnApplicationJar() throws Exception {
        Assumptions.assumeTrue(javaExecutable() != null, "no JDK to fork; set runner.test.javaHome");
        String futureVersion = Integer.toString(Runtime.version().feature() + 1);
        Path application = multiReleaseJar("future-main.jar", futureVersion, protectedVariant, "probe.Probe");
        Path archive = output();

        RunnerJarBuilder.build(spec(archive)
                .applicationOutput(List.of(application))
                .multiRelease(true)
                .entryStub(true)
                .build(), BuildLogger.noOp());

        assertNoStub(archive);
        try (RunnerJarReader reader = RunnerJarReader.open(archive)) {
            assertEquals(APPLICATION_CLASS, reader.index().startClass(),
                    "the configured main, not the application JAR manifest main, is launched");
        }
        Forked run = fork(archive, List.of("alpha"));
        assertEquals(0, run.status(), () -> "the JVM exited with " + run.status() + "\n" + run.output());
        assertTrue(run.output().contains(RESULT_OK), run::output);
        assertTrue(run.output().contains("io.micronaut.runner.Launcher#invokeMain"), run::output);
    }

    @Test
    void keepsTheStubWhenEveryMultiReleaseMainVariantIsCompatible() throws Exception {
        Assumptions.assumeTrue(javaExecutable() != null, "no JDK to fork; set runner.test.javaHome");
        Path application = multiReleaseDirectory("compatible-main", "25", compatibleVariant);
        Path first = output();
        Path second = output();

        RunnerJarBuilder.build(spec(first)
                .applicationOutput(List.of(application))
                .multiRelease(true)
                .entryStub(true)
                .build(), BuildLogger.noOp());
        RunnerJarBuilder.build(spec(second)
                .applicationOutput(List.of(application))
                .multiRelease(true)
                .entryStub(true)
                .build(), BuildLogger.noOp());

        assertHasStub(first);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second),
                "checking multi-release variants must not make the archive irreproducible");
        Forked run = fork(first, List.of("alpha", "beta"));
        assertEquals(0, run.status(), () -> "the JVM exited with " + run.status() + "\n" + run.output());
        assertTrue(run.output().contains("MR COMPATIBLE alpha|beta"), run::output);
        assertTrue(run.output().contains("io.micronaut.runner.generated.AppEntry#run"), run::output);
    }

    @Test
    void versionDirectoriesDoNotAffectAStubWhenMultiReleaseIsDisabled() throws Exception {
        Assumptions.assumeTrue(javaExecutable() != null, "no JDK to fork; set runner.test.javaHome");
        Path application = multiReleaseDirectory("disabled-main", "25", noArgumentVariant);
        Path archive = output();

        RunnerJarBuilder.build(spec(archive)
                .applicationOutput(List.of(application))
                .multiRelease(false)
                .entryStub(true)
                .build(), BuildLogger.noOp());

        assertHasStub(archive);
        Forked run = fork(archive, List.of("alpha"));
        assertEquals(0, run.status(), () -> "the JVM exited with " + run.status() + "\n" + run.output());
        assertTrue(run.output().contains(RESULT_OK), run::output);
        assertTrue(run.output().contains("io.micronaut.runner.generated.AppEntry#run"), run::output);
        assertFalse(run.output().contains("MR NO ARGUMENT"), run::output);
    }

    @Test
    void invalidVersionDirectoriesDoNotAffectAStub() throws IOException {
        Path application = baseApplication("invalid-version-main");
        for (String version : List.of("7", "09", "25x", "256")) {
            copyVariant(noArgumentVariant,
                    application.resolve("META-INF/versions/" + version + "/" + mainClassEntryName()));
        }
        Path archive = output();

        RunnerJarBuilder.build(spec(archive)
                .applicationOutput(List.of(application))
                .multiRelease(true)
                .entryStub(true)
                .build(), BuildLogger.noOp());

        assertHasStub(archive);
    }

    /**
     * The test that matters: the packaged application really is entered through the generated class.
     */
    @Test
    void startsTheApplicationThroughTheStub() throws Exception {
        Assumptions.assumeTrue(javaExecutable() != null, "no JDK to fork; set runner.test.javaHome");

        Forked run = fork(stubArchive, List.of("alpha", "beta"));

        assertEquals(0, run.status(), () -> "the JVM exited with " + run.status() + "\n" + run.output());
        assertTrue(run.output().contains(RESULT_OK), () -> "the application did not run\n" + run.output());
        assertTrue(run.output().contains("ARGS alpha|beta"),
                () -> "the arguments did not reach the application\n" + run.output());
        assertTrue(run.output().contains("io.micronaut.runner.generated.AppEntry#run"),
                () -> "the application was not entered through the stub\n" + run.output());
        assertFalse(run.output().contains("io.micronaut.runner.Launcher#invokeMain"),
                () -> "the launcher fell back to reflection\n" + run.output());
    }

    /**
     * The control: the same fixture packaged without the stub is entered reflectively, which is what makes
     * the stack trace assertions above evidence rather than decoration.
     */
    @Test
    void startsTheApplicationWithoutTheStubWhenItIsNotRequested() throws Exception {
        Assumptions.assumeTrue(javaExecutable() != null, "no JDK to fork; set runner.test.javaHome");

        Forked run = fork(reflectiveArchive, List.of("alpha", "beta"));

        assertEquals(0, run.status(), () -> "the JVM exited with " + run.status() + "\n" + run.output());
        assertTrue(run.output().contains(RESULT_OK), () -> "the application did not run\n" + run.output());
        assertTrue(run.output().contains("io.micronaut.runner.Launcher#invokeMain"),
                () -> "the reflective fallback was not used\n" + run.output());
        assertFalse(run.output().contains("io.micronaut.runner.generated.AppEntry"),
                () -> "a stub was generated although none was requested\n" + run.output());
    }

    private static void assertNoStub(Path archive) throws IOException {
        try (RunnerJarReader reader = RunnerJarReader.open(archive)) {
            Index index = reader.index();
            assertNull(index.entryStubClass(), "the header field stays empty, so the launcher reflects");
            assertEquals(IndexFormat.NO_INDEX, index.findClass(EntryStubGenerator.STUB_CLASS),
                    "and nothing was packaged under the generated name");
        }
    }

    private static void assertHasStub(Path archive) throws IOException {
        try (RunnerJarReader reader = RunnerJarReader.open(archive)) {
            Index index = reader.index();
            assertEquals(EntryStubGenerator.STUB_CLASS, index.entryStubClass());
            assertTrue(index.findClass(EntryStubGenerator.STUB_CLASS) != IndexFormat.NO_INDEX,
                    "the generated stub must be indexed");
        }
    }

    private static void assertReason(String mainClass, String expected) throws IOException {
        String reason = reasonFor(mainClass);
        assertNotNull(reason, mainClass + " must not be eligible");
        assertTrue(reason.contains(expected),
                () -> mainClass + " was rejected for the wrong reason: " + reason);
    }

    private static String reasonFor(String mainClass) throws IOException {
        return EntryStubGenerator.ineligibilityReason(mainClass, classFile(mainClass));
    }

    private static byte[] classFile(String binaryName) throws IOException {
        return Files.readAllBytes(classes.resolve(binaryName.replace('.', '/') + ".class"));
    }

    private static String mainClassEntryName() {
        return APPLICATION_CLASS.replace('.', '/') + ".class";
    }

    private static Path baseApplication(String name) throws IOException {
        Path application = fixtures.resolve("mr-applications/" + name);
        Path main = application.resolve(mainClassEntryName());
        Files.createDirectories(main.getParent());
        Files.copy(classes.resolve(mainClassEntryName()), main);
        return application;
    }

    private static Path multiReleaseDirectory(String name, String version, Path variant) throws IOException {
        Path application = baseApplication(name);
        copyVariant(variant,
                application.resolve("META-INF/versions/" + version + "/" + mainClassEntryName()));
        return application;
    }

    private static void copyVariant(Path variant, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.copy(variant.resolve(mainClassEntryName()), destination);
    }

    private static Path multiReleaseJar(String name, String version, Path variant, String manifestMain)
            throws IOException {
        Path jar = fixtures.resolve("mr-applications/" + name);
        Files.createDirectories(jar.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MAIN_CLASS, manifestMain);
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(mainClassEntryName(), classFile(APPLICATION_CLASS));
        entries.put("probe/Probe.class", classFile("probe.Probe"));
        entries.put("META-INF/versions/" + version + "/" + mainClassEntryName(),
                Files.readAllBytes(variant.resolve(mainClassEntryName())));
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry record = new ZipEntry(entry.getKey());
                record.setTime(0L);
                out.putNextEntry(record);
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    private static MethodModel method(ClassModel model, String name) {
        for (MethodModel candidate : model.methods()) {
            if (name.equals(candidate.methodName().stringValue())) {
                return candidate;
            }
        }
        throw new AssertionError("the generated class has no method " + name);
    }

    /**
     * Every method this method invokes, as {@code owner#name:descriptor}, in the order of the bytecode.
     */
    private static List<String> invocations(MethodModel method) {
        List<String> found = new ArrayList<>();
        for (CodeElement element : method.code().orElseThrow().elementList()) {
            if (element instanceof InvokeInstruction invoke) {
                found.add(invoke.owner().asInternalName() + "#" + invoke.name().stringValue() + ":"
                        + invoke.type().stringValue());
            }
        }
        return found;
    }

    private static RunnerJarSpec.Builder spec(Path output) {
        return RunnerJarSpec.builder()
                .mainClass(APPLICATION_CLASS)
                .applicationOutput(List.of(classes))
                .output(output);
    }

    private static Path output() {
        counter++;
        return fixtures.resolve("out/app-" + counter + ".jar");
    }

    private static Forked fork(Path archive, List<String> arguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-jar");
        command.add(archive.toAbsolutePath().toString());
        command.addAll(arguments);
        Process process = new ProcessBuilder(command)
                .directory(fixtures.toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new Forked(process.waitFor(), output);
    }

    /**
     * The {@code java} of the JDK the build runs on, which is the JDK the forked application must use.
     */
    private static Path javaExecutable() {
        String home = System.getProperty("runner.test.javaHome", System.getProperty("java.home"));
        if (home == null || home.isEmpty()) {
            return null;
        }
        Path candidate = Path.of(home, "bin", "java");
        if (!Files.isExecutable(candidate)) {
            candidate = Path.of(home, "bin", "java.exe");
        }
        return Files.isExecutable(candidate) ? candidate : null;
    }

    private static void compile(JavaCompiler compiler, Path sources, Path target,
            Map<String, String> files) throws IOException {
        List<String> arguments = new ArrayList<>(List.of("--release", "25", "-d", target.toString()));
        for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
            Path source = sources.resolve(file.getKey());
            Files.createDirectories(source.getParent());
            Files.writeString(source, file.getValue());
            arguments.add(source.toString());
        }
        if (compiler.run(null, null, null, arguments.toArray(new String[0])) != 0) {
            throw new IOException("Could not compile the fixture sources under " + sources);
        }
    }

    /**
     * A logger that keeps what it was told, so a test can assert that the packager explained itself.
     */
    private static final class Recording implements BuildLogger {

        private final List<String> infos = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        @Override
        public void info(String message) {
            infos.add(message);
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }
    }

    /**
     * A loader that can define the generated class and load the fixture classes it calls, with the test's
     * own loader as its parent so that {@link Entry} and the launcher are the same types the test sees.
     */
    private static final class Defining extends URLClassLoader {

        private Defining(Path directory) throws IOException {
            super(new URL[] {directory.toUri().toURL()}, EntryStubGeneratorTest.class.getClassLoader());
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /**
     * The outcome of one forked JVM.
     *
     * @param status the exit status
     * @param output standard output and standard error, interleaved
     */
    private record Forked(int status, String output) {
    }
}
