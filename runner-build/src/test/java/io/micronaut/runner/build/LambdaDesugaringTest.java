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

import io.micronaut.runner.IndexFormat;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Runner adds to Micronaut AOT's lambda desugaring: the rules of its loader, which go in as foreign packages,
 * where the classes the engine rewrites and generates end up in a runner jar, and what the build reports. Which
 * sites are eligible and what a generated class looks like are the engine's, and its own tests cover them.
 */
class LambdaDesugaringTest {

    private static final String METAFACTORY = "java/lang/invoke/LambdaMetafactory";

    @TempDir
    static Path temp;

    private static byte[] mainClass;

    @ParameterizedTest(name = "--release {0}")
    @ValueSource(ints = {8, 25})
    void theEngineRewritesTheScenarioAndItBehavesAsCompiled(int release) throws Exception {
        List<LambdaFixtures.Layer> layers = LambdaFixtures.scenario(temp.resolve("scenario-" + release), release);
        List<String> expected = LambdaFixtures.run(LambdaFixtures.classPath(
                layers.stream().map(LambdaFixtures.Layer::entries).toList()), LambdaFixtures.APPLICATION);

        LambdaFixtures.Outcome outcome = LambdaFixtures.desugar(temp.resolve("scenario-out-" + release), layers);
        List<String> actual = LambdaFixtures.run(LambdaFixtures.classPath(outcome.outputs()),
                LambdaFixtures.APPLICATION);

        assertEquals(withoutHidden(expected), withoutHidden(actual), "the same results, line by line");
        assertEquals("hidden=falsefalse", actual.get(actual.size() - 2), "a generated class is not hidden");
        List<String> application = List.copyOf(outcome.outputs().get(0).keySet());
        assertEquals("app/Main.class", application.get(0));
        assertTrue(application.size() > 1 && application.subList(1, application.size()).stream()
                .allMatch(name -> name.startsWith("app/Main$$Lambda$R")), application::toString);
        for (ClassPathRewrite.Counts counts : outcome.counts()) {
            if (counts != null) {
                assertEquals(List.of(), counts.notes(), "nothing fell back");
                assertEquals(0, counts.nestFallbacks());
            }
        }
        assertNull(outcome.counts().get(2), "a layer without a lambda call site is not reported");
        assertEquals(outcome.jars().get(2), outcome.sources().get(2), "and it is nested as it is");
    }

    /**
     * With {@code stripLocalVariables}, the same run of the engine strips the dependencies it desugars, and leaves
     * the application layer's tables alone.
     */
    @ParameterizedTest(name = "--release {0}")
    @ValueSource(ints = {8, 25})
    void desugaredAndStrippedClassesBehaveAsCompiled(int release) throws Exception {
        List<LambdaFixtures.Layer> layers = LambdaFixtures.scenario(temp.resolve("stripped-" + release), release);
        List<String> expected = LambdaFixtures.run(LambdaFixtures.classPath(
                layers.stream().map(LambdaFixtures.Layer::entries).toList()), LambdaFixtures.APPLICATION);

        LambdaFixtures.Outcome outcome = LambdaFixtures.rewrite(temp.resolve("stripped-out-" + release), layers,
                true);
        List<String> actual = LambdaFixtures.run(LambdaFixtures.classPath(outcome.outputs()),
                LambdaFixtures.APPLICATION);

        assertEquals(withoutHidden(expected), withoutHidden(actual), "the same results, line by line");
        ClassPathRewrite.Counts library = outcome.counts().get(1);
        assertEquals(List.of(), library.notes());
        assertTrue(library.sites() > 0, library::toString);
        TransformReport stripped = library.stripped();
        assertTrue(stripped.rewritten() > 0 && stripped.fallbacks() == 0, stripped::toString);
        byte[] scenario = outcome.outputs().get(1).get("fix/Scenario.class");
        assertEquals(0, LambdaFixtures.sites(scenario, METAFACTORY), "the host is desugared");
        assertFalse(hasLocalVariableTable(scenario), "and stripped");
        // The application layer is user code: desugared, never stripped.
        assertNull(outcome.counts().get(0).stripped());
        byte[] application = outcome.outputs().get(0).get("app/Main.class");
        assertEquals(0, LambdaFixtures.sites(application, METAFACTORY));
        assertTrue(hasLocalVariableTable(application));
    }

    /**
     * {@code RunnerClassLoader} asks the launcher's own loader first for {@code io.micronaut.runner}, so a class
     * there may not be the archive's copy: it is never a host, a nest host or an owner.
     */
    @Test
    void aHostOrAnOwnerInTheLaunchersPackageKeepsItsSites() throws Exception {
        Map<String, byte[]> compiled = compile("launcher", 25, Map.of(
                "io/micronaut/runner/fixture/Host.java", """
                        package io.micronaut.runner.fixture;
                        import java.util.function.Supplier;
                        public class Host {
                            public static Supplier<String> make() {
                                return () -> "launcher";
                            }
                            public static class Member {
                                public static Supplier<String> make() {
                                    return () -> "member";
                                }
                            }
                        }
                        """,
                "io/micronaut/runner/fixture/Owner.java", """
                        package io.micronaut.runner.fixture;
                        public class Owner {
                            public static String greet(String name) {
                                return "hi " + name;
                            }
                        }
                        """,
                "app/Uses.java", """
                        package app;
                        import java.util.function.Function;
                        import java.util.function.Supplier;
                        public class Uses {
                            public static Function<String, String> greeter() {
                                return io.micronaut.runner.fixture.Owner::greet;
                            }
                            public static Supplier<String> plain() {
                                return () -> "plain";
                            }
                        }
                        """));

        LambdaFixtures.Outcome outcome = LambdaFixtures.desugar(temp.resolve("launcher-out"),
                List.of(LambdaFixtures.Layer.application(compiled)));

        Map<String, byte[]> output = outcome.outputs().get(0);
        for (String name : List.of("io/micronaut/runner/fixture/Host.class",
                "io/micronaut/runner/fixture/Host$Member.class")) {
            assertArrayEquals(compiled.get(name), output.get(name), name + " keeps its bytes");
        }
        assertEquals(1, LambdaFixtures.sites(output.get("app/Uses.class"), METAFACTORY),
                "the site whose owner is in the launcher's package stays");
        assertTrue(output.containsKey("app/Uses$$Lambda$R0.class"), "the other site of the host is rewritten");
        assertTrue(output.keySet().stream().noneMatch(name -> name.startsWith("io/micronaut/runner/")
                && name.contains("$$Lambda$R")), output.keySet()::toString);
        ClassPathRewrite.Counts counts = outcome.counts().get(0);
        assertEquals(1, counts.sites(), counts::toString);
        assertEquals(Map.of("shadowedOrUncertain", 3), counts.left(), "a host, a nest member and an owner");
    }

    /**
     * {@code RunnerClassLoader} asks its parent first only for the packages of the boot and the platform loaders,
     * so the packages of a module the application class loader defines, such as {@code jdk.compiler}'s, go to the
     * engine as foreign packages, next to the launcher's, and no other package of the JDK does.
     */
    @Test
    void theApplicationLoadersModulesArePassedAsForeignPackages() {
        assertTrue(ClassPathRewrite.FOREIGN_PACKAGES.contains("io.micronaut.runner"));
        assertTrue(ClassPathRewrite.FOREIGN_PACKAGES.contains("com.sun.source.util"),
                ClassPathRewrite.FOREIGN_PACKAGES::toString);
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        for (Module module : ModuleLayer.boot().modules()) {
            if (module.getClassLoader() == null || module.getClassLoader() == platform) {
                for (String name : module.getPackages()) {
                    assertTrue(ClassPathRewrite.FOREIGN_PACKAGES.stream()
                            .noneMatch(foreign -> name.equals(foreign) || name.startsWith(foreign + ".")),
                            () -> name + " is the JDK's to a runner jar");
                }
            }
        }
    }

    /**
     * With the default platform parent, a site that names a class of {@code jdk.compiler} cannot link in a runner
     * jar, and its capture fails where the generated class's would not, so it stays. micronaut-aot 3.2.0-M1 reads a
     * package of the JDK from the JDK whatever the foreign packages say, and rewrites it.
     */
    @Disabled("Waits for micronaut-aot to keep the sites of a foreign package of the JDK (#250)")
    @Test
    void anOwnerInAPackageOfTheApplicationLoadersModulesKeepsItsSite() throws Exception {
        Map<String, byte[]> compiled = compile("compiler", 25, Map.of("app/Compiler.java", """
                package app;
                import com.sun.source.util.Trees;
                import java.util.function.Function;
                import java.util.function.Supplier;
                import javax.annotation.processing.ProcessingEnvironment;
                public class Compiler {
                    public static Function<ProcessingEnvironment, Trees> trees() {
                        return Trees::instance;
                    }
                    public static Supplier<String> plain() {
                        return () -> "plain";
                    }
                }
                """));

        LambdaFixtures.Outcome outcome = LambdaFixtures.desugar(temp.resolve("compiler-out"),
                List.of(LambdaFixtures.Layer.application(compiled)));

        Map<String, byte[]> output = outcome.outputs().get(0);
        assertEquals(1, LambdaFixtures.sites(output.get("app/Compiler.class"), METAFACTORY),
                "the site whose owner is com.sun.source.util.Trees stays");
        assertEquals(List.of("app/Compiler.class", "app/Compiler$$Lambda$R0.class"), List.copyOf(output.keySet()));
        ClassPathRewrite.Counts counts = outcome.counts().get(0);
        assertEquals(1, counts.sites(), counts::toString);
        assertEquals(Map.of("shadowedOrUncertain", 1), counts.left());
    }

    @Test
    void aSignedJarKeepsEveryByteAndItsSitesAreOnlyCounted() throws Exception {
        Map<String, byte[]> compiled = compile("signed", 25, Map.of(
                "signed/Host.java", """
                        package signed;
                        import java.util.function.Supplier;
                        public class Host {
                            public static Supplier<String> make() {
                                return () -> "value";
                            }
                        }
                        """,
                "plain/Host.java", """
                        package plain;
                        import java.util.function.Supplier;
                        public class Host {
                            public static Supplier<String> make() {
                                return () -> "value";
                            }
                        }
                        """));
        Path application = Files.createDirectories(temp.resolve("signed-app"));
        Files.write(application.resolve("Main.class"), mainClass());
        Path dependency = LambdaFixtures.Layer.signed(LambdaFixtures.select(compiled, "signed/"))
                .write(temp.resolve("signed-lib/signed.jar"));
        Path plain = ClassFixtures.jar(temp.resolve("signed-lib/plain.jar"), LambdaFixtures.select(compiled, "plain/"));
        Path output = temp.resolve("signed-out/runner.jar");

        RunnerJarResult result = RunnerJarBuilder.build(RunnerJarSpec.builder().mainClass("Main")
                .applicationOutput(List.of(application))
                .dependencies(List.of(Dependency.of(dependency), Dependency.of(plain)))
                .output(output).build(), BuildLogger.noOp());

        try (RunnerJarArchive archive = RunnerJarArchive.open(output)) {
            assertEquals(IndexFormat.NO_INDEX, archive.index().findClass("signed.Host$$Lambda$R0"),
                    "no class is generated");
            assertArrayEquals(compiled.get("signed/Host.class"),
                    archive.read(archive.index().find("signed/Host.class")), "the host keeps its bytes");
        }
        String transforms = transforms(output);
        assertTrue(transforms.contains("MICRONAUT-INF/lib/signed.jar\tdesugarLambdas\t0\t1\t0\t0\n"), transforms);
        assertTrue(transforms.contains("lambdas\tMICRONAUT-INF/lib/signed.jar\trewritten=0\tgenerated=0\tbridges=0"
                + "\tnestFallbacks=0\tleft=1\tleft.signedJar=1\n"), transforms);
        TransformReport report = result.transforms().get(0);
        assertEquals(1, report.rewritten(), "the unsigned jar's host: " + report);
        assertEquals(2, report.unchanged(), "the application's main class and the signed jar's host: " + report);
    }

    /**
     * A dependency is nested from the engine's copy, which holds each generated class right after its host. A
     * startup class list moves a listed host to the front with its unlisted generated classes, and a listed
     * generated class to its own rank (#148).
     */
    @Test
    void generatedClassesFollowTheirHostOrTakeTheirOwnRank() throws Exception {
        Map<String, byte[]> compiled = compile("ranks", 25, Map.of(
                "lib/A.java", """
                        package lib;
                        import java.util.function.Supplier;
                        public class A {
                            public static Supplier<String> make() {
                                return () -> "a";
                            }
                            public static Runnable run() {
                                return () -> { };
                            }
                        }
                        """,
                "lib/B.java", """
                        package lib;
                        import java.util.function.Supplier;
                        public class B {
                            public static Supplier<String> make() {
                                return () -> "b";
                            }
                        }
                        """,
                "lib/C.java", "package lib; public class C { }\n"));
        Map<String, byte[]> ordered = new LinkedHashMap<>();
        for (String name : List.of("lib/A.class", "lib/B.class", "lib/C.class")) {
            ordered.put(name, compiled.get(name));
        }
        Path application = Files.createDirectories(temp.resolve("ranks-app"));
        Files.write(application.resolve("Main.class"), mainClass());
        Path dependency = ClassFixtures.jar(temp.resolve("ranks-lib/lib.jar"), ordered);
        Path list = temp.resolve("ranks-lib/startup-classes.txt");
        Files.writeString(list, "lib.B$$Lambda$R0\nlib.A\n");

        Path plain = temp.resolve("ranks-out/plain.jar");
        Path listed = temp.resolve("ranks-out/listed.jar");
        RunnerJarBuilder.build(RunnerJarSpec.builder().mainClass("Main").applicationOutput(List.of(application))
                .dependencies(List.of(Dependency.of(dependency))).output(plain).build(), BuildLogger.noOp());
        RunnerJarBuilder.build(RunnerJarSpec.builder().mainClass("Main").applicationOutput(List.of(application))
                .dependencies(List.of(Dependency.of(dependency))).output(listed).startupClasses(list).build(),
                BuildLogger.noOp());

        assertEquals(List.of("META-INF/MANIFEST.MF", "lib/A.class", "lib/A$$Lambda$R0.class",
                        "lib/A$$Lambda$R1.class", "lib/B.class", "lib/B$$Lambda$R0.class", "lib/C.class"),
                nestedClasses(plain), "each generated class right after its host, in the same nested jar");
        assertEquals(List.of("META-INF/MANIFEST.MF", "lib/B$$Lambda$R0.class", "lib/A.class",
                        "lib/A$$Lambda$R0.class", "lib/A$$Lambda$R1.class", "lib/B.class", "lib/C.class"),
                nestedClasses(listed), "the listed generated class at its rank, then the listed host with its"
                        + " unlisted generated classes; the rest in jar order");
    }

    @Test
    void theApplicationLayerIsDesugaredAndItsGeneratedClassesFollowTheirHosts() throws Exception {
        Path application = ClassFixtures.compile(temp.resolve("app-src"), temp.resolve("app-classes"),
                List.of("--release", "25"), Map.of(
                        "app/Main.java", """
                                package app;
                                import java.util.function.Supplier;
                                public class Main {
                                    public static void main(String[] args) {
                                        Supplier<String> greeting = () -> "hello";
                                        System.out.println(greeting.get() + Other.make().get());
                                    }
                                }
                                """,
                        "app/Other.java", """
                                package app;
                                import java.util.function.Supplier;
                                public class Other {
                                    public static Supplier<String> make() {
                                        return () -> "!";
                                    }
                                }
                                """));
        Path output = temp.resolve("app-out/runner.jar");

        RunnerJarResult result = RunnerJarBuilder.build(RunnerJarSpec.builder().mainClass("app.Main")
                .applicationOutput(List.of(application)).dependencies(List.of()).output(output).build(),
                BuildLogger.noOp());

        List<String> classes = new ArrayList<>();
        try (ZipFile zip = new ZipFile(output.toFile())) {
            zip.stream().map(java.util.zip.ZipEntry::getName)
                    .filter(name -> name.startsWith("MICRONAUT-INF/classes/app/")).forEach(classes::add);
        }
        assertEquals(List.of("MICRONAUT-INF/classes/app/Main.class",
                "MICRONAUT-INF/classes/app/Main$$Lambda$R0.class", "MICRONAUT-INF/classes/app/Other.class",
                "MICRONAUT-INF/classes/app/Other$$Lambda$R0.class"), classes);
        String transforms = transforms(output);
        assertTrue(transforms.contains("MICRONAUT-INF/classes\tdesugarLambdas\t2\t"), transforms);
        assertTrue(transforms.contains("lambdas\tMICRONAUT-INF/classes\trewritten=2\tgenerated=2\tbridges=0"
                + "\tnestFallbacks=0\tleft=0\n"), transforms);
        assertEquals(2, result.transforms().get(0).rewritten(), result.transforms()::toString);
    }

    @Test
    void preserveDesugarsNothingAndSaysSoOnce() throws Exception {
        Map<String, byte[]> compiled = compile("preserve", 25, Map.of("lib/Host.java", """
                package lib;
                import java.util.function.Supplier;
                public class Host {
                    public static Supplier<String> make() {
                        return () -> "value";
                    }
                }
                """));
        Path application = ClassFixtures.compile(temp.resolve("preserve-app-src"),
                temp.resolve("preserve-app-classes"), List.of("--release", "25"), Map.of("app/Main.java", """
                        package app;
                        public class Main {
                            public static void main(String[] args) {
                                Runnable run = () -> System.out.println("hello");
                                run.run();
                            }
                        }
                        """));
        Path dependency = ClassFixtures.jar(temp.resolve("preserve-lib/lib.jar"), compiled);
        Path output = temp.resolve("preserve-out/runner.jar");
        List<String> info = new ArrayList<>();
        BuildLogger logger = new BuildLogger() {
            @Override
            public void info(String message) {
                info.add(message);
            }

            @Override
            public void warn(String message) {
                throw new AssertionError(message);
            }
        };

        RunnerJarResult result = RunnerJarBuilder.build(RunnerJarSpec.builder().mainClass("app.Main")
                .applicationOutput(List.of(application)).dependencies(List.of(Dependency.of(dependency)))
                .output(output).compression(Compression.PRESERVE).build(), logger);

        assertEquals(List.of(), result.transforms());
        assertEquals(1, info.stream().filter(line -> line.equals("The desugarLambdas option has no effect with"
                + " PRESERVE compression, which does not repack the dependencies")).count(), info::toString);
        try (RunnerJarArchive archive = RunnerJarArchive.open(output)) {
            assertEquals(IndexFormat.NO_INDEX, archive.index().findClass("app.Main$$Lambda$R0"));
            assertEquals(IndexFormat.NO_INDEX, archive.index().findClass("lib.Host$$Lambda$R0"));
            byte[] original = Files.readAllBytes(dependency);
            assertArrayEquals(original, archive.source().readFully(archive.index().jarDataOffset(1),
                    original.length), "the dependency is nested byte for byte");
        }
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertNull(zip.getEntry(IndexFormat.TRANSFORMS_ENTRY_NAME));
        }
    }

    /**
     * One run of Micronaut AOT's engine both desugars and strips, over the application layer written once, and each
     * stage nests the copy it wrote.
     */
    @Test
    void oneRunOfTheEngineDesugarsAndStrips() throws Exception {
        Map<String, byte[]> compiled = compile("one-run", 25, Map.of("lib/Host.java", """
                package lib;
                import java.util.function.Supplier;
                public class Host {
                    public static Supplier<String> make() {
                        String value = "value";
                        return () -> value;
                    }
                }
                """));
        Path dependency = ClassFixtures.jar(temp.resolve("one-run-lib/lib.jar"), compiled);
        Path work = Files.createDirectories(temp.resolve("one-run-work"));
        int[] writes = new int[1];
        ClassTransforms.ApplicationLayer application = new ClassTransforms.ApplicationLayer() {
            @Override
            public ClassTransforms.Written write(Path jar, boolean multiRelease) throws IOException {
                writes[0]++;
                ClassFixtures.jar(jar, Map.of("Main.class", mainClass()));
                return new ClassTransforms.Written(1, mainClass().length);
            }

            @Override
            public long replace(Path copy) {
                throw new AssertionError("the application layer has no lambda");
            }
        };
        List<Dependency> dependencies = List.of(Dependency.of(dependency));
        for (boolean strip : new boolean[] {false, true}) {
            writes[0] = 0;
            RunnerJarSpec spec = RunnerJarSpec.builder().mainClass("Main").applicationOutput(List.of(work))
                    .dependencies(dependencies).output(work.resolve("runner.jar")).stripLocalVariables(strip)
                    .build();
            ClassTransforms transforms = ClassTransforms.prepare(spec, dependencies, 1,
                    Files.createTempDirectory(work, "build-"), application, BuildLogger.noOp(), message -> {
                        throw new AssertionError(message);
                    });
            assertEquals(1, writes[0], "the application layer is written once");
            Path copy = transforms.sources().get(0);
            assertFalse(copy.equals(dependency), "the engine rewrote the dependency");
            byte[] host;
            try (ZipReader reader = ZipReader.open(copy)) {
                host = reader.read(reader.entry("lib/Host.class").orElseThrow());
            }
            assertEquals(0, LambdaFixtures.sites(host, METAFACTORY), "desugared");
            assertEquals(!strip, hasLocalVariableTable(host), "stripped: " + strip);
        }
    }

    private static List<String> nestedClasses(Path archive) throws IOException {
        try (RunnerJarArchive reader = RunnerJarArchive.open(archive)) {
            int jar = 1;
            List<String> names = new ArrayList<>();
            byte[] nested = reader.source().readFully(reader.index().jarDataOffset(jar),
                    (int) reader.index().jarDataLength(jar));
            Path file = Files.createTempFile(temp, "nested-", ".jar");
            Files.write(file, nested);
            try (ZipReader zip = ZipReader.open(file)) {
                for (ZipEntryInfo entry : zip.entries()) {
                    names.add(entry.name());
                }
            }
            return names;
        }
    }

    private static String transforms(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            return new String(zip.getInputStream(zip.getEntry(IndexFormat.TRANSFORMS_ENTRY_NAME)).readAllBytes(),
                    StandardCharsets.UTF_8);
        }
    }

    /** A main class in the unnamed package, with no lambda. */
    private static byte[] mainClass() throws IOException {
        if (mainClass == null) {
            mainClass = compile("main", 25, Map.of("Main.java", """
                    public class Main {
                        public static void main(String[] args) {
                        }
                    }
                    """)).get("Main.class");
        }
        return mainClass;
    }

    private static boolean hasLocalVariableTable(byte[] type) {
        return ClassFile.of().parse(type).methods().stream()
                .flatMap(method -> method.code().stream())
                .anyMatch(code -> code.findAttribute(Attributes.localVariableTable()).isPresent());
    }

    private static List<String> withoutHidden(List<String> lines) {
        return lines.stream().filter(line -> !line.startsWith("hidden=")).toList();
    }

    private static Map<String, byte[]> compile(String directory, int release, Map<String, String> sources)
            throws IOException {
        Path root = temp.resolve("compile").resolve(directory);
        Files.createDirectories(root);
        return ClassFixtures.classes(ClassFixtures.compile(root.resolve("src"), root.resolve("classes"),
                List.of("-g", "--release", Integer.toString(release)), sources));
    }
}
