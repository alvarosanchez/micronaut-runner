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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Starts a fixture application packaged with micronaut-core's service index, against the micronaut-core of the test
 * class path: it asks micronaut-core's own APIs for its services, once answered by the index and once by the scan.
 *
 * <p>The index is built from the view of the class path that {@code RunnerClassLoader} gives the application. The
 * application layer and three dependencies contribute {@code META-INF/services} and {@code META-INF/micronaut}
 * entries, and one of the dependencies stores no directory entries, which a scan of the extracted layout cannot
 * see.</p>
 */
class ServiceIndexRuntimeTest {

    private static final String GREETER = "com.example.spi.Greeter";
    private static final String MARKER = "com.example.spi.Marker";
    private static final String BEAN_DEFINITION_REFERENCE = "io.micronaut.inject.BeanDefinitionReference";
    private static final String BEAN_CONFIGURATION = "io.micronaut.inject.BeanConfiguration";
    private static final String DISABLED = "-Dmicronaut.service.index.enabled=false";
    private static final String VALIDATE = "-Dmicronaut.service.index.validate=true";
    private static final String USING = "Using the service index registered for class loader ";
    private static final String NOT_COMPARED = "(class path not compared: the index does not list one)";
    private static final String VALIDATED = "(class path not compared: the index does not list one, names validated"
            + " against a scan)";
    private static final String OWN_LOADER = "[fixture] the application's own StaticOptimizations loader ran";

    /** How many plain classes each layer lists as bean definitions. */
    private static final int PLAIN_CLASSES = 4;

    private static final List<String> PROBES = List.of(
            "collectAll:" + GREETER,
            "iterator:" + GREETER,
            "firstAvailable:" + GREETER,
            "collectAll:" + MARKER,
            "iterator:" + MARKER,
            "findMeta:" + MARKER,
            "findMeta:" + BEAN_DEFINITION_REFERENCE,
            "collectAll:" + BEAN_DEFINITION_REFERENCE,
            "findMeta:" + BEAN_CONFIGURATION,
            "iterator:io.micronaut.context.env.PropertySourceImporter",
            "iterator:io.micronaut.core.convert.TypeConverterRegistrar",
            "iterator:io.micronaut.core.type.TypeInformationProvider",
            "collectAll:com.example.spi.Unprovided",
            "iterator:com.example.spi.Unprovided",
            "firstAvailable:com.example.spi.Unprovided");

    @TempDir
    static Path work;

    private static Path classes;
    private static Path runnerJar;
    private static RunnerJarResult result;
    private static List<Dependency> dependencies;
    private static Path application;

    @BeforeAll
    static void packageTheFixture() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "this JDK has no java compiler");
        Assumptions.assumeTrue(
                RunnerJarBuilder.class.getResource("/META-INF/micronaut-runner/launcher.jar") != null,
                "the bundled launcher jar is not on the test class path");

        classes = work.resolve("classes");
        compile(compiler, work.resolve("sources"), classes, MicronautCoreJars.jar("micronaut-core-"), sources());

        application = work.resolve("application");
        copyPackage(classes, application, "com/example/app");
        write(application, "META-INF/services/" + GREETER,
                "# the application's own\ncom.example.app.AppGreeter2\ncom.example.app.AppGreeter1\n");
        write(application, "META-INF/services/io.micronaut.core.optim.StaticOptimizations$Loader",
                "com.example.app.OwnLoader\n");
        write(application, "META-INF/micronaut/" + GREETER + "/com.example.app.AppMetaGreeter", "");
        write(application, "META-INF/micronaut/" + MARKER + "/com.example.app.AppMarker", "");
        write(application, "META-INF/micronaut/" + BEAN_CONFIGURATION + "/com.example.app.AppConfiguration", "");
        for (int i = 0; i < PLAIN_CLASSES; i++) {
            write(application, "META-INF/micronaut/" + BEAN_DEFINITION_REFERENCE + "/com.example.app.Plain" + i, "");
        }
        // micronaut-core's INFO line on standard error, and nothing else.
        write(application, "logback.xml", """
                <configuration>
                    <appender name="ERR" class="ch.qos.logback.core.ConsoleAppender">
                        <target>System.err</target>
                        <encoder>
                            <pattern>%level %logger %msg%n</pattern>
                        </encoder>
                    </appender>
                    <logger name="io.micronaut.core.io.service" level="INFO"/>
                    <root level="WARN">
                        <appender-ref ref="ERR"/>
                    </root>
                </configuration>
                """);

        Map<String, byte[]> spi = packageEntries(classes, "com/example/spi");
        spi.put("META-INF/services/" + GREETER, bytes("com.example.spi.SpiGreeterB\n\ncom.example.spi.SpiGreeterA"
                + "#listed by the next jar too\n"));
        spi.put("META-INF/micronaut/" + GREETER + "/com.example.spi.SpiMetaGreeter", new byte[0]);
        spi.put("META-INF/micronaut/" + MARKER + "/com.example.spi.SpiMarker", new byte[0]);
        for (int i = 0; i < PLAIN_CLASSES; i++) {
            spi.put("META-INF/micronaut/" + BEAN_DEFINITION_REFERENCE + "/com.example.spi.Plain" + i, new byte[0]);
        }
        Path spiJar = writeJar(work.resolve("libs/fixture-spi.jar"), spi, true);

        Map<String, byte[]> extra = packageEntries(classes, "com/example/extra");
        extra.put("META-INF/services/" + GREETER, bytes("com.example.extra.ExtraGreeter1\n"
                + "com.example.extra.ExtraGreeter2\ncom.example.extra.ExtraGreeter3\ncom.example.spi.SpiGreeterA\n"));
        extra.put("META-INF/micronaut/" + GREETER + "/com.example.extra.ExtraMetaGreeter", new byte[0]);
        extra.put("META-INF/micronaut/" + MARKER + "/com.example.extra.ExtraMarker", new byte[0]);
        extra.put("META-INF/micronaut/" + BEAN_CONFIGURATION + "/com.example.extra.ExtraConfiguration", new byte[0]);
        for (int i = 0; i < PLAIN_CLASSES; i++) {
            extra.put("META-INF/micronaut/" + BEAN_DEFINITION_REFERENCE + "/com.example.extra.Plain" + i,
                    new byte[0]);
        }
        Path extraJar = writeJar(work.resolve("libs/fixture-extra.jar"), extra, true);

        // No directory entries: getResources("META-INF/micronaut/") does not find this jar on a plain class path.
        Map<String, byte[]> noDirectories = packageEntries(classes, "com/example/nodirs");
        noDirectories.put("META-INF/services/" + GREETER, bytes("com.example.nodirs.NoDirsGreeter\n"));
        noDirectories.put("META-INF/micronaut/" + MARKER + "/com.example.nodirs.NoDirsMarker", new byte[0]);
        noDirectories.put("META-INF/micronaut/" + BEAN_DEFINITION_REFERENCE + "/com.example.nodirs.Plain0",
                new byte[0]);
        Path noDirectoriesJar = writeJar(work.resolve("libs/fixture-nodirs.jar"), noDirectories, false);

        dependencies = new ArrayList<>();
        for (Path jar : MicronautCoreJars.inject()) {
            dependencies.add(Dependency.of(jar));
        }
        dependencies.add(Dependency.of(MicronautCoreJars.jar("logback-classic-")));
        dependencies.add(Dependency.of(MicronautCoreJars.jar("logback-core-")));
        dependencies.add(Dependency.of(spiJar));
        dependencies.add(Dependency.of(extraJar));
        dependencies.add(Dependency.of(noDirectoriesJar));
        runnerJar = work.resolve("fixture.jar");
        result = build(dependencies, runnerJar, BuildLogger.noOp());
    }

    @Test
    void theApplicationIsPackagedWithAnIndexThatRunnerClassLoaderIsServed() throws Exception {
        assertEquals(java.util.Optional.of(MicronautCoreJars.version()), result.staticServicesCoreVersion());
        assertEquals(List.of(), result.warnings());

        Launch indexed = launch(runnerJar, List.of(), PROBES);

        assertTrue(indexed.err().contains("INFO io.micronaut.core.io.service.ServiceIndex " + USING
                + "io.micronaut.runner.RunnerClassLoader@"), indexed.err());
        assertTrue(indexed.err().contains(NOT_COMPARED), indexed.err());
        assertTrue(indexed.err().contains(OWN_LOADER), "the application's own loader still runs: " + indexed.err());
    }

    @Test
    void theIndexAndTheScanGiveTheSameNamesInTheSameOrder() throws Exception {
        Launch indexed = launch(runnerJar, List.of(), PROBES);
        Launch scanned = launch(runnerJar, List.of(DISABLED), PROBES);

        assertFalse(scanned.err().contains(USING), scanned.err());
        assertEquals(scanned.out(), indexed.out(), "the index must answer exactly as the scan does");

        Map<String, String> answers = answers(indexed);
        Set<String> greeters = new HashSet<>(List.of(answers.get("collectAll " + GREETER).split(",")));
        assertEquals(Set.of("com.example.app.AppGreeter1", "com.example.app.AppGreeter2",
                "com.example.spi.SpiGreeterA", "com.example.spi.SpiGreeterB", "com.example.extra.ExtraGreeter1",
                "com.example.extra.ExtraGreeter2", "com.example.extra.ExtraGreeter3", "com.example.nodirs.NoDirsGreeter",
                "com.example.app.AppMetaGreeter", "com.example.spi.SpiMetaGreeter",
                "com.example.extra.ExtraMetaGreeter"), greeters);
        assertEquals(answers.get("collectAll " + GREETER).replace("?", ""), answers.get("iterator " + GREETER));
        assertEquals(Set.of("com.example.app.AppMarker", "com.example.spi.SpiMarker", "com.example.extra.ExtraMarker",
                "com.example.nodirs.NoDirsMarker"), new HashSet<>(List.of(answers.get("findMeta " + MARKER).split(","))),
                "the merged copy holds the names of the jar without directory entries");
        assertEquals(answers.get("findMeta " + MARKER), answers.get("collectAll " + MARKER));
        assertEquals(Set.of("com.example.app.AppConfiguration", "com.example.extra.ExtraConfiguration"),
                new HashSet<>(List.of(answers.get("findMeta " + BEAN_CONFIGURATION).split(","))));
        String definitions = answers.get("findMeta " + BEAN_DEFINITION_REFERENCE);
        assertTrue(definitions.contains("com.example.nodirs.Plain0"), definitions);
        assertTrue(definitions.contains("io.micronaut.inject.provider.BeanProviderDefinition"),
                "micronaut-inject's own definitions are indexed too: " + definitions);
        assertEquals("", answers.get("collectAll com.example.spi.Unprovided"));
        assertEquals("none", answers.get("firstAvailable com.example.spi.Unprovided"));
        assertFalse(answers.get("iterator io.micronaut.context.env.PropertySourceImporter").isEmpty());
    }

    @Test
    void theIndexValidatesAgainstAScan() throws Exception {
        Launch validated = launch(runnerJar, List.of(VALIDATE), PROBES);

        assertTrue(validated.err().contains(USING + "io.micronaut.runner.RunnerClassLoader@"), validated.err());
        assertTrue(validated.err().contains(VALIDATED), validated.err());
        assertEquals(launch(runnerJar, List.of(), PROBES).out(), validated.out());
    }

    @Test
    void theExtractedLayoutIsAnsweredInTheSingleJarsOrder() throws Exception {
        Path applicationJar = extracted();
        Launch indexed = launch(applicationJar, List.of(), PROBES);

        assertTrue(indexed.err().contains(USING + "jdk.internal.loader.ClassLoaders$AppClassLoader@"), indexed.err());
        assertTrue(indexed.err().contains(NOT_COMPARED), indexed.err());
        assertEquals(launch(runnerJar, List.of(), PROBES).out(), indexed.out(),
                "an extracted application is answered as the jar it was extracted from");
        Launch scanned = launch(applicationJar, List.of(DISABLED), PROBES);
        assertNotEquals(scanned.out(), indexed.out(), "the scan of the extracted layout finds another order and"
                + " misses the jar without directory entries");
    }

    @Test
    void validatingTheExtractedLayoutReportsTheJarWithoutDirectoryEntries() throws Exception {
        Launch validated = java(List.of(VALIDATE, "-jar", extracted().toString(), "findMeta:" + MARKER));

        assertEquals(0, validated.exit(), validated.out() + validated.err());
        String answer = answers(validated).get("findMeta " + MARKER);
        assertTrue(answer.startsWith("! java.util.ServiceConfigurationError: The service index registered for class"
                + " loader jdk.internal.loader.ClassLoaders$AppClassLoader@"), answer);
        assertTrue(answer.contains("META-INF/micronaut/" + MARKER), answer);
        assertTrue(answer.contains("com.example.nodirs.NoDirsMarker"), answer);
    }

    @Test
    void theSystemParentScansTheRealClassPath() throws Exception {
        Map<String, byte[]> provider = packageEntries(classes, "com/example/sys");
        provider.put("META-INF/services/" + GREETER, bytes("com.example.sys.SysGreeter\n"));
        Path providerJar = writeJar(work.resolve("libs/system-provider.jar"), provider, true);
        List<String> command = List.of("-cp", runnerJar + File.pathSeparator + providerJar,
                "io.micronaut.runner.Launcher", "collectAll:" + GREETER);

        Launch systemParent = java(concat(List.of("-Dmicronaut.runner.parent=system"), command));
        Launch explicit = java(concat(List.of("-Dmicronaut.runner.parent=system",
                "-Dmicronaut.service.index.enabled=true"), command));

        assertEquals(0, systemParent.exit(), systemParent.out() + systemParent.err());
        assertTrue(answers(systemParent).get("collectAll " + GREETER).contains("com.example.sys.SysGreeter"),
                systemParent.out());
        assertFalse(systemParent.err().contains(USING), systemParent.err());

        assertEquals(0, explicit.exit(), explicit.out() + explicit.err());
        assertTrue(explicit.err().contains(USING + "io.micronaut.runner.RunnerClassLoader@"),
                "the launcher leaves a property the user set alone: " + explicit.err());
        assertFalse(answers(explicit).get("collectAll " + GREETER).contains("com.example.sys.SysGreeter"),
                explicit.out());
    }

    @Test
    void anApplicationWhoseDependencyCarriesAnIndexGetsNoSecondOneAndStarts() throws Exception {
        Map<String, byte[]> carried = new LinkedHashMap<>();
        try (ZipReader archive = ZipReader.open(runnerJar)) {
            for (ZipEntryInfo entry : archive.entries()) {
                String name = entry.name();
                if (name.startsWith("MICRONAUT-INF/classes/io/micronaut/aot/serviceindex/generated/")
                        && !entry.directory()) {
                    carried.put(name.substring("MICRONAUT-INF/classes/".length()), archive.read(entry));
                }
            }
        }
        carried.put("META-INF/services/io.micronaut.core.optim.StaticOptimizations$Loader",
                bytes("io.micronaut.aot.serviceindex.generated.ServiceIndexLoader\n"));
        Path indexedJar = writeJar(work.resolve("libs/indexed.jar"), carried, true);
        List<Dependency> withIndex = new ArrayList<>(dependencies);
        withIndex.add(Dependency.of(indexedJar));
        Path output = work.resolve("carried.jar");
        List<String> warnings = new ArrayList<>();

        RunnerJarResult carriedResult = build(withIndex, output, new BuildLogger() {
            @Override
            public void info(String message) {
            }

            @Override
            public void warn(String message) {
                warnings.add(message);
            }
        });

        assertEquals(java.util.Optional.empty(), carriedResult.staticServicesCoreVersion());
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.get(0).startsWith("No service index was generated because the dependency " + indexedJar
                + " already carries one"), warnings::toString);
        try (ZipReader archive = ZipReader.open(output)) {
            assertEquals("com.example.app.OwnLoader\n", new String(archive.read(archive.entry(
                    "MICRONAUT-INF/classes/META-INF/services/io.micronaut.core.optim.StaticOptimizations$Loader")
                    .orElseThrow()), StandardCharsets.UTF_8), "no second index loader");
        }
        Launch started = launch(output, List.of(), List.of("collectAll:" + GREETER));
        assertTrue(started.err().contains(USING), "the dependency's index is the one in use: " + started.err());
    }

    // -------------------------------------------------------------------------------------------- fixtures

    private static Map<String, String> sources() {
        Map<String, String> sources = new TreeMap<>();
        sources.put("com/example/app/Main.java", """
                package com.example.app;

                import io.micronaut.core.io.service.MicronautMetaServiceLoaderUtils;
                import io.micronaut.core.io.service.ServiceDefinition;
                import io.micronaut.core.io.service.SoftServiceLoader;
                import io.micronaut.core.optim.StaticOptimizations;
                import java.util.List;

                public final class Main {
                    public static void main(String[] args) {
                        // Runs the StaticOptimizations loaders, as an application context does when it starts.
                        StaticOptimizations.isEnvironmentCached();
                        ClassLoader loader = Main.class.getClassLoader();
                        for (String probe : args) {
                            int colon = probe.indexOf(':');
                            String api = probe.substring(0, colon);
                            String typeName = probe.substring(colon + 1);
                            String answer;
                            try {
                                @SuppressWarnings("unchecked")
                                Class<Object> type = (Class<Object>) Class.forName(typeName, false, loader);
                                answer = switch (api) {
                                    case "collectAll" -> names(SoftServiceLoader.load(type).collectAll());
                                    case "iterator" -> definitions(SoftServiceLoader.load(type));
                                    case "firstAvailable" -> SoftServiceLoader.load(type).firstAvailable()
                                            .map(value -> value.getClass().getName()).orElse("none");
                                    case "findMeta" -> names(MicronautMetaServiceLoaderUtils
                                            .findMetaMicronautServiceEntries(loader, type, null));
                                    default -> throw new IllegalArgumentException(api);
                                };
                            } catch (Throwable e) {
                                Throwable root = e;
                                while (root.getCause() != null) {
                                    root = root.getCause();
                                }
                                answer = "! " + root.getClass().getName() + ": " + root.getMessage();
                            }
                            System.out.println(api + " " + typeName + " = " + answer.replace('\\n', '|'));
                        }
                    }

                    private static String names(List<?> values) {
                        StringBuilder text = new StringBuilder();
                        for (Object value : values) {
                            text.append(text.length() == 0 ? "" : ",").append(value.getClass().getName());
                        }
                        return text.toString();
                    }

                    private static String definitions(SoftServiceLoader<Object> services) {
                        StringBuilder text = new StringBuilder();
                        for (ServiceDefinition<Object> definition : services) {
                            text.append(text.length() == 0 ? "" : ",").append(definition.getName())
                                    .append(definition.isPresent() ? "" : "?");
                        }
                        return text.toString();
                    }
                }
                """);
        sources.put("com/example/app/OwnLoader.java", """
                package com.example.app;

                import io.micronaut.core.optim.StaticOptimizations;

                /** Stands for the loaders Micronaut AOT generates into an application. */
                public final class OwnLoader implements StaticOptimizations.Loader<OwnLoader.Marker> {
                    @Override
                    public Marker load() {
                        System.err.println("[fixture] the application's own StaticOptimizations loader ran");
                        return new Marker();
                    }

                    public static final class Marker {
                    }
                }
                """);
        sources.put("com/example/spi/Greeter.java", "package com.example.spi;\npublic interface Greeter {\n}\n");
        sources.put("com/example/spi/Marker.java", "package com.example.spi;\npublic interface Marker {\n}\n");
        sources.put("com/example/spi/Unprovided.java",
                "package com.example.spi;\npublic interface Unprovided {\n}\n");
        for (String name : List.of("app.AppGreeter1", "app.AppGreeter2", "app.AppMetaGreeter", "spi.SpiGreeterA",
                "spi.SpiGreeterB", "spi.SpiMetaGreeter", "extra.ExtraGreeter1", "extra.ExtraGreeter2",
                "extra.ExtraGreeter3", "extra.ExtraMetaGreeter", "nodirs.NoDirsGreeter")) {
            sources.put("com/example/" + name.replace('.', '/') + ".java",
                    type("com.example." + name, "implements com.example.spi.Greeter"));
        }
        for (String name : List.of("app.AppMarker", "spi.SpiMarker", "extra.ExtraMarker", "nodirs.NoDirsMarker")) {
            sources.put("com/example/" + name.replace('.', '/') + ".java",
                    type("com.example." + name, "implements com.example.spi.Marker"));
        }
        // Only the system class loader has it, so it cannot implement a type of the archive.
        sources.put("com/example/sys/SysGreeter.java", type("com.example.sys.SysGreeter", ""));
        for (String name : List.of("app.AppConfiguration", "extra.ExtraConfiguration")) {
            sources.put("com/example/" + name.replace('.', '/') + ".java", type("com.example." + name, ""));
        }
        for (String layer : List.of("app", "spi", "extra", "nodirs")) {
            for (int i = 0; i < PLAIN_CLASSES; i++) {
                String name = layer + ".Plain" + i;
                sources.put("com/example/" + name.replace('.', '/') + ".java", type("com.example." + name, ""));
            }
        }
        return sources;
    }

    private static String type(String name, String supertypes) {
        int dot = name.lastIndexOf('.');
        return "package " + name.substring(0, dot) + ";\npublic class " + name.substring(dot + 1) + " "
                + supertypes + " {\n}\n";
    }

    private static RunnerJarResult build(List<Dependency> classPath, Path output, BuildLogger logger)
            throws IOException {
        return RunnerJarBuilder.build(RunnerJarSpec.builder()
                .mainClass("com.example.app.Main")
                .applicationOutput(List.of(application))
                .dependencies(List.copyOf(classPath))
                .output(output)
                .build(), logger);
    }

    private static void compile(JavaCompiler compiler, Path sources, Path target, Path classPath,
                                Map<String, String> files) throws IOException {
        Files.createDirectories(target);
        List<String> arguments = new ArrayList<>(List.of("--release", "25", "-proc:none", "-cp",
                classPath.toString(), "-d", target.toString()));
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path source = sources.resolve(file.getKey());
            Files.createDirectories(source.getParent());
            Files.writeString(source, file.getValue());
            arguments.add(source.toString());
        }
        if (compiler.run(null, null, null, arguments.toArray(new String[0])) != 0) {
            throw new IOException("Could not compile the fixture classes");
        }
    }

    private static void copyPackage(Path from, Path target, String directory) throws IOException {
        for (Map.Entry<String, byte[]> entry : packageEntries(from, directory).entrySet()) {
            Path file = target.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
    }

    private static Map<String, byte[]> packageEntries(Path from, String directory) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(from.resolve(directory))) {
            for (Path file : files.sorted().toList()) {
                entries.put(directory + "/" + file.getFileName(), Files.readAllBytes(file));
            }
        }
        return entries;
    }

    private static void write(Path root, String name, String content) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Writes a jar, with an entry for every directory as build tools write them, or with none: on a plain class
     * path, which the extracted layout is, micronaut-core finds {@code META-INF/micronaut/} only in a jar that
     * stores the directory.
     */
    private static Path writeJar(Path file, Map<String, byte[]> entries, boolean directories) throws IOException {
        Files.createDirectories(file.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (ZipOutputStream out = directories ? new JarOutputStream(Files.newOutputStream(file), manifest)
                : new ZipOutputStream(Files.newOutputStream(file))) {
            Set<String> written = new HashSet<>(List.of("META-INF/"));
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                String name = entry.getKey();
                for (int slash = name.indexOf('/'); directories && slash >= 0; slash = name.indexOf('/', slash + 1)) {
                    String directory = name.substring(0, slash + 1);
                    if (written.add(directory)) {
                        out.putNextEntry(new ZipEntry(directory));
                        out.closeEntry();
                    }
                }
                out.putNextEntry(new ZipEntry(name));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    private static Map<String, String> answers(Launch launch) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (String line : launch.out().split("\\R")) {
            int separator = line.indexOf(" = ");
            if (separator > 0) {
                answers.put(line.substring(0, separator), line.substring(separator + 3));
            } else if (line.endsWith(" =")) {
                answers.put(line.substring(0, line.length() - 2), "");
            }
        }
        return answers;
    }

    /** Runs a jar with {@code java -jar}, which has to succeed. */
    private static Launch launch(Path jar, List<String> jvmArguments, List<String> probes) throws Exception {
        Launch launch = java(concat(jvmArguments, concat(List.of("-jar", jar.toString()), probes)));
        assertEquals(0, launch.exit(),
                jar + " " + jvmArguments + " " + probes + "\n" + launch.out() + launch.err());
        return launch;
    }

    /** Runs {@code java} with the given arguments and waits for it, whatever it exits with. */
    private static Launch java(List<String> arguments) throws Exception {
        String home = System.getProperty("runner.test.javaHome", System.getProperty("java.home"));
        Path java = Path.of(home, "bin", "java");
        if (!Files.isExecutable(java)) {
            java = Path.of(home, "bin", "java.exe");
        }
        Assumptions.assumeTrue(Files.isExecutable(java), "the JDK has no java executable");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.addAll(arguments);
        Path out = Files.createTempFile(work, "launch", ".out");
        Path err = Files.createTempFile(work, "launch", ".err");
        Process process = new ProcessBuilder(command)
                .redirectOutput(out.toFile())
                .redirectError(err.toFile())
                .start();
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            fail("the fixture application did not finish: " + command);
        }
        return new Launch(Files.readString(out), Files.readString(err), process.exitValue());
    }

    /** The layout extracted from the fixture: its application jar, which names the jars under {@code lib/}. */
    private static synchronized Path extracted() throws Exception {
        Path destination = work.resolve("extracted");
        Path applicationJar = destination.resolve(runnerJar.getFileName());
        if (!Files.isRegularFile(applicationJar)) {
            Launch extract = java(List.of("-Dmicronaut.runner.mode=extract", "-jar", runnerJar.toString(),
                    "--destination", destination.toString()));
            assertEquals(0, extract.exit(), extract.out() + extract.err());
            assertTrue(Files.isRegularFile(applicationJar), extract.out() + extract.err());
        }
        return applicationJar;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /** What one run of the fixture printed, and what it exited with. */
    private record Launch(String out, String err, int exit) {
    }
}
