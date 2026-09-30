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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the static service table against real micronaut-core jars: a fixture application, packaged with
 * micronaut-core and micronaut-inject and started with {@code java -jar}, asks Micronaut's own APIs for its
 * services, once answered by the table and once by the scan.
 *
 * <p>Every test runs once per micronaut-core line the table serves, which is 5.1.x: the build resolves that
 * micronaut-core for this test alone. The expected orders are also spelled out here, so a table and a scan that
 * were both wrong would not pass. The fixture is packaged with the micronaut-core 5.2.x of the test class path
 * as well, which gets no table; adding {@code "5.2"} to {@link #servedCores()} runs every test on it, and is how
 * a wider range is confirmed.</p>
 *
 * <p>The verify switch is what the other checks of a table rest on, so it is run both ways: on the class path
 * the table describes, as the single jar and as the layout extracted from it, and on that layout with a jar
 * added, which it has to refuse.</p>
 */
class StaticServiceTableRuntimeTest {

    private static final String GREETER = "com.example.spi.Greeter";
    private static final String MARKER = "com.example.spi.Marker";
    private static final String BEAN_DEFINITION_REFERENCE = "io.micronaut.inject.BeanDefinitionReference";
    private static final String BEAN_CONFIGURATION = "io.micronaut.inject.BeanConfiguration";
    private static final String BEAN_INTROSPECTION_REFERENCE = "io.micronaut.core.beans.BeanIntrospectionReference";
    private static final String KILL_SWITCH = "-Dmicronaut.runner.static-services=false";
    private static final String VERIFY = "-Dmicronaut.runner.static-services.verify=true";
    private static final String TRACE = "-Dmicronaut.runner.static-services.trace=true";
    private static final String TRACE_LINE = "[micronaut-runner] static services: get ";
    private static final String NO_MISMATCH = " entries, 0 mismatches";
    private static final String SINGLE_JAR_ORDER = " types list the same names in the single jar's order, which"
            + " the scan of this extracted layout does not use";
    private static final String MISMATCH = "java.lang.IllegalStateException: [micronaut-runner] static services do"
            + " not match Micronaut's scan: ";

    /** Makes the fixture application fail with what a lookup throws, as an application does, not print it. */
    private static final String RETHROW = "-Dfixture.rethrow=true";

    /** How many plain classes each layer lists as bean definitions, which is what the fan-out is observed on. */
    private static final int PLAIN_CLASSES = 8;

    private static final List<String> ORDER_PROBES = List.of(
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

    private static final List<String> PARITY_PROBES = List.of(
            "collectAll:com.example.fragile.Mixed",
            "iterator:com.example.fragile.Mixed",
            "firstAvailable:com.example.fragile.Mixed",
            "collectAll:com.example.fragile.OnlyHidden",
            "firstAvailable:com.example.fragile.OnlyHidden",
            "collectAll:com.example.fragile.OnlyNeedy",
            "firstAvailable:com.example.fragile.OnlyNeedy",
            "collectAll:com.example.fragile.OnlyExplosive",
            "firstAvailable:com.example.fragile.OnlyExplosive",
            "findMeta:" + BEAN_DEFINITION_REFERENCE,
            "findMeta:" + BEAN_CONFIGURATION,
            "collectAll:" + BEAN_INTROSPECTION_REFERENCE);

    @TempDir
    static Path work;

    /** The packaged fixtures, by micronaut-core line. */
    private static final Map<String, Fixture> FIXTURES = new LinkedHashMap<>();

    /** The micronaut-core lines the table serves, which every test runs on. */
    static List<String> servedCores() {
        return List.of("5.1");
    }

    @BeforeAll
    static void packageFixtures() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "this JDK has no java compiler");
        Assumptions.assumeTrue(
                RunnerJarBuilder.class.getResource("/META-INF/micronaut-runner/launcher.jar") != null,
                "the bundled launcher jar is not on the test class path");

        // The fixture only calls what both lines declare identically, so it is compiled once.
        Path classes = work.resolve("classes");
        compile(compiler, work.resolve("sources"), classes,
                StaticServiceTableGeneratorTest.classPathJar("micronaut-core-"), sources());

        Path application = work.resolve("application");
        copyPackage(classes, application, "com/example/app");
        write(application, "META-INF/services/" + GREETER,
                "# the application's own\ncom.example.app.AppGreeter2\ncom.example.app.AppGreeter1\n");
        write(application, "META-INF/micronaut/" + GREETER + "/com.example.app.AppMetaGreeter", "");
        write(application, "META-INF/micronaut/" + MARKER + "/com.example.app.AppMarker", "");
        write(application, "META-INF/micronaut/" + BEAN_CONFIGURATION + "/com.example.app.AppConfiguration", "");
        for (int i = 0; i < PLAIN_CLASSES; i++) {
            write(application, "META-INF/micronaut/" + BEAN_DEFINITION_REFERENCE + "/com.example.app.Plain" + i, "");
        }

        Map<String, byte[]> spi = packageEntries(classes, "com/example/spi");
        spi.put("META-INF/services/" + GREETER, bytes("com.example.spi.SpiGreeterB\n\ncom.example.spi.SpiGreeterA"
                + "#listed by the next jar too\n"));
        spi.put("META-INF/micronaut/" + GREETER + "/com.example.spi.SpiMetaGreeter", new byte[0]);
        spi.put("META-INF/micronaut/" + MARKER + "/com.example.spi.SpiMarker", new byte[0]);
        for (int i = 0; i < PLAIN_CLASSES; i++) {
            spi.put("META-INF/micronaut/" + BEAN_DEFINITION_REFERENCE + "/com.example.spi.Plain" + i, new byte[0]);
        }
        Path spiJar = writeJar(work.resolve("libs/fixture-spi.jar"), spi);

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
        Path extraJar = writeJar(work.resolve("libs/fixture-extra.jar"), extra);

        Map<String, byte[]> fragile = packageEntries(classes, "com/example/fragile");
        String fragilePackage = "com.example.fragile.";
        fragile.put("META-INF/services/com.example.fragile.Mixed",
                bytes(fragilePackage + "Good\n" + fragilePackage + "Hidden\n" + fragilePackage + "Needy\n"));
        fragile.put("META-INF/services/com.example.fragile.OnlyHidden", bytes(fragilePackage + "Hidden\n"));
        fragile.put("META-INF/services/com.example.fragile.OnlyNeedy", bytes(fragilePackage + "Needy\n"));
        fragile.put("META-INF/services/com.example.fragile.OnlyExplosive", bytes(fragilePackage + "Explosive\n"));
        for (String listed : List.of("Good", "Hidden", "Needy")) {
            fragile.put("META-INF/micronaut/" + BEAN_DEFINITION_REFERENCE + "/" + fragilePackage + listed,
                    new byte[0]);
        }
        fragile.put("META-INF/micronaut/" + BEAN_CONFIGURATION + "/" + fragilePackage + "Explosive", new byte[0]);
        for (String listed : List.of("Good", "Explosive")) {
            fragile.put("META-INF/micronaut/" + BEAN_INTROSPECTION_REFERENCE + "/" + fragilePackage + listed,
                    new byte[0]);
        }
        // com.example.gone.Gone, which Needy's constructor needs, is compiled and then left out of every jar.
        Path fragileJar = writeJar(work.resolve("libs/fixture-fragile.jar"), fragile);

        Map<String, List<Path>> cores = new LinkedHashMap<>();
        cores.put("5.1", StaticServiceTableGeneratorTest.micronautCore51());
        cores.put("5.2", classPathCore());
        for (Map.Entry<String, List<Path>> line : cores.entrySet()) {
            // micronaut-core first, then micronaut-inject and what its own bean definitions link against.
            List<Dependency> dependencies = new ArrayList<>();
            for (Path jar : line.getValue()) {
                dependencies.add(Dependency.of(jar));
            }
            dependencies.add(Dependency.of(spiJar));
            dependencies.add(Dependency.of(extraJar));
            String name = line.getValue().get(0).getFileName().toString();
            String coreVersion = name.substring("micronaut-core-".length(), name.length() - ".jar".length());
            assertTrue(coreVersion.startsWith(line.getKey() + "."), name);

            Path orderJar = work.resolve("order-" + line.getKey() + ".jar");
            RunnerJarResult orderResult = build(application, dependencies, orderJar);
            dependencies.add(Dependency.of(fragileJar));
            Path parityJar = work.resolve("parity-" + line.getKey() + ".jar");
            RunnerJarResult parityResult = build(application, dependencies, parityJar);
            FIXTURES.put(line.getKey(), new Fixture(coreVersion, orderJar, parityJar, orderResult, parityResult));
        }
    }

    /** micronaut-core and micronaut-inject of the test class path, with what micronaut-inject links against. */
    private static List<Path> classPathCore() {
        List<Path> jars = new ArrayList<>();
        jars.add(StaticServiceTableGeneratorTest.classPathJar("micronaut-core-"));
        jars.add(StaticServiceTableGeneratorTest.classPathJar("micronaut-inject-"));
        for (String prefix : List.of("slf4j-api-", "jakarta.inject-api-", "jakarta.annotation-api-")) {
            optionalClassPathJar(prefix).ifPresent(jars::add);
        }
        return jars;
    }

    @Test
    void packagesNoTableForMicronautCore52() throws Exception {
        Fixture fixture = FIXTURES.get("5.2");

        assertEquals(0, fixture.orderResult().staticServiceSlots());
        assertEquals(Optional.empty(), fixture.orderResult().staticServicesCoreVersion());
        Launch traced = launch(fixture.orderJar(), List.of(TRACE, VERIFY), List.of("collectAll:" + GREETER));
        assertFalse(traced.err().contains("static services"), traced.err());
        assertEquals(11, answers(traced).get("collectAll " + GREETER).split(",").length,
                "Micronaut scans, and finds what the table of the 5.1 fixture lists");
    }

    @ParameterizedTest
    @MethodSource("servedCores")
    void packagesATableForThisMicronautCore(String core) {
        Fixture fixture = FIXTURES.get(core);

        assertTrue(fixture.orderResult().staticServiceSlots() > 3 * PLAIN_CLASSES, "the fixture got a table: "
                + fixture.orderResult().staticServiceSlots());
        assertEquals(Optional.of(fixture.coreVersion()), fixture.orderResult().staticServicesCoreVersion());
        assertTrue(fixture.parityResult().staticServiceSlots() > fixture.orderResult().staticServiceSlots());
    }

    @ParameterizedTest
    @MethodSource("servedCores")
    void theTableAndTheScanOfThisMicronautCoreGiveTheSameNamesInTheSameOrder(String core) throws Exception {
        Path orderJar = FIXTURES.get(core).orderJar();
        Launch table = launch(orderJar, List.of(), ORDER_PROBES);
        Launch scan = launch(orderJar, List.of(KILL_SWITCH), ORDER_PROBES);

        assertEquals(scan.out(), table.out(), "the table must answer exactly as the scan does");

        Map<String, String> answers = answers(table);
        List<String> greeters = new ArrayList<>();
        greeters.addAll(hashSetOrder("com.example.app.AppGreeter2", "com.example.app.AppGreeter1"));
        greeters.addAll(hashSetOrder("com.example.spi.SpiGreeterB", "com.example.spi.SpiGreeterA"));
        greeters.addAll(hashSetOrder("com.example.extra.ExtraGreeter1", "com.example.extra.ExtraGreeter2",
                "com.example.extra.ExtraGreeter3", "com.example.spi.SpiGreeterA"));
        // The merged copy stores them sorted; Micronaut walks it backwards.
        greeters.addAll(List.of("com.example.spi.SpiMetaGreeter", "com.example.extra.ExtraMetaGreeter",
                "com.example.app.AppMetaGreeter"));
        assertEquals(String.join(",", greeters), answers.get("collectAll " + GREETER));
        assertEquals(String.join(",", greeters), answers.get("iterator " + GREETER));
        assertEquals(greeters.get(0), answers.get("firstAvailable " + GREETER));

        String markers = "com.example.spi.SpiMarker,com.example.extra.ExtraMarker,com.example.app.AppMarker";
        assertEquals(markers, answers.get("collectAll " + MARKER));
        assertEquals(markers, answers.get("iterator " + MARKER));
        assertEquals(markers, answers.get("findMeta " + MARKER));

        assertEquals("com.example.extra.ExtraConfiguration,com.example.app.AppConfiguration",
                answers.get("findMeta " + BEAN_CONFIGURATION));
        String definitions = answers.get("findMeta " + BEAN_DEFINITION_REFERENCE);
        List<String> plain = Stream.of(definitions.split(",")).filter(name -> name.contains(".Plain")).toList();
        List<String> expected = new ArrayList<>();
        for (String layer : List.of("spi", "extra", "app")) {
            for (int i = PLAIN_CLASSES - 1; i >= 0; i--) {
                expected.add("com.example." + layer + ".Plain" + i);
            }
        }
        assertEquals(expected, plain);
        assertTrue(definitions.contains("io.micronaut.inject.provider.BeanProviderDefinition"),
                "micronaut-inject's own definitions are served too: " + definitions);
        assertEquals("", answers.get("collectAll com.example.spi.Unprovided"));
        assertEquals("none", answers.get("firstAvailable com.example.spi.Unprovided"));
        assertFalse(answers.get("iterator io.micronaut.context.env.PropertySourceImporter").isEmpty());
    }

    @ParameterizedTest
    @MethodSource("servedCores")
    void verifyFindsNoMismatchWithThisMicronautCore(String core) throws Exception {
        Path orderJar = FIXTURES.get(core).orderJar();
        Path parityJar = FIXTURES.get(core).parityJar();
        Launch verified = launch(orderJar, List.of(VERIFY), ORDER_PROBES);

        assertTrue(verified.err().contains("[micronaut-runner] static services verified: "), verified.err());
        assertTrue(verified.err().contains(NO_MISMATCH), verified.err());
        assertFalse(verified.err().contains(SINGLE_JAR_ORDER), "the single jar is compared in order: "
                + verified.err());
        assertEquals(launch(orderJar, List.of(), ORDER_PROBES).out(), verified.out());

        Launch parity = launch(parityJar, List.of(VERIFY), List.of("iterator:" + GREETER));
        assertTrue(parity.err().contains(NO_MISMATCH), parity.err());
    }

    @ParameterizedTest
    @MethodSource("servedCores")
    void verifyAcceptsTheSingleJarOrderInTheExtractedLayout(String core) throws Exception {
        Path applicationJar = extracted(core);
        Launch verified = launch(applicationJar, List.of(VERIFY), ORDER_PROBES);
        Launch scan = launch(applicationJar, List.of(KILL_SWITCH), ORDER_PROBES);

        assertTrue(verified.err().contains(NO_MISMATCH), verified.err());
        // The scan of this layout reads META-INF/micronaut jar by jar, not from one merged copy.
        assertTrue(verified.err().contains(SINGLE_JAR_ORDER), verified.err());
        assertNotEquals(scan.out(), verified.out(), "the scan of the extracted layout finds another order");
        assertEquals(launch(FIXTURES.get(core).orderJar(), List.of(), ORDER_PROBES).out(), verified.out(),
                "an extracted application is answered in the order of the jar it was extracted from");
    }

    @ParameterizedTest
    @MethodSource("servedCores")
    void verifyStopsAnApplicationWhoseClassPathTheTableDoesNotDescribe(String core) throws Exception {
        // A jar added after packaging: one more line for a type the table lists, and a provider of a type the
        // table answers "nothing" for. Verify instantiates nothing, so the names need no class of their own.
        Map<String, byte[]> added = new LinkedHashMap<>();
        added.put("META-INF/services/" + GREETER, bytes("com.example.app.AppMetaGreeter\n"));
        added.put("META-INF/micronaut/com.example.spi.Unprovided/com.example.app.AppMarker", new byte[0]);
        Path addedJar = writeJar(work.resolve("libs/added-" + core + ".jar"), added);
        List<String> application = List.of("-cp", extracted(core) + File.pathSeparator + addedJar,
                "com.example.app.Main", "collectAll:" + GREETER, "iterator:" + MARKER);

        Launch stopped = java(concat(List.of(VERIFY, RETHROW), application));

        assertNotEquals(0, stopped.exit(), "the application does not start: " + stopped.out() + stopped.err());
        assertTrue(stopped.err().contains(MISMATCH), stopped.err());
        assertTrue(stopped.err().contains(" entries, 2 mismatches. Start with"
                + " -Dmicronaut.runner.static-services=false, and report this."), stopped.err());
        assertTrue(stopped.err().contains("\n  " + GREETER + ": the table lists [com.example.app.AppGreeter"),
                stopped.err());
        assertTrue(stopped.err().contains(", com.example.app.AppMetaGreeter], the scan finds [com.example.app"),
                stopped.err());
        assertTrue(stopped.err().contains("\n  com.example.spi.Unprovided: the table lists nothing, the scan finds"
                + " [com.example.app.AppMarker]"), stopped.err());
        assertFalse(stopped.err().contains(NO_MISMATCH), stopped.err());
        assertEquals("", stopped.out(), "no lookup was answered");

        // Not only the lookup that ran the comparison: every later one fails with the same message.
        Launch caught = java(concat(List.of(VERIFY), application));
        assertEquals(0, caught.exit(), caught.out() + caught.err());
        Map<String, String> answers = answers(caught);
        assertTrue(answers.get("collectAll " + GREETER).startsWith("! " + MISMATCH), caught.out());
        assertTrue(answers.get("iterator " + MARKER).startsWith("! " + MISMATCH), caught.out());

        // What the message says to do: without the table the application starts, and sees the added jar.
        Launch scan = java(concat(List.of(VERIFY, RETHROW, KILL_SWITCH), application));
        assertEquals(0, scan.exit(), scan.out() + scan.err());
        assertFalse(scan.err().contains("static services"), scan.err());
        assertEquals(12, answers(scan).get("collectAll " + GREETER).split(",").length, scan.out());
    }

    @ParameterizedTest
    @MethodSource("servedCores")
    void instantiatesAndFailsAsTheScanDoes(String core) throws Exception {
        Path parityJar = FIXTURES.get(core).parityJar();
        Launch table = launch(parityJar, List.of(), PARITY_PROBES);
        Launch scan = launch(parityJar, List.of(KILL_SWITCH), PARITY_PROBES);

        // Two failures name the class that made the reflective call, which is Micronaut's in one and the
        // table's in the other.
        assertEquals(normalized(scan.out()), normalized(table.out()));

        Map<String, String> answers = answers(table);
        List<String> mixed = hashSetOrder("com.example.fragile.Good", "com.example.fragile.Hidden",
                "com.example.fragile.Needy");
        mixed.remove("com.example.fragile.Needy");
        assertEquals(String.join(",", mixed), answers.get("collectAll com.example.fragile.Mixed"),
                "a private constructor is reached through reflection; a constructor that needs a missing class is"
                        + " skipped");
        assertEquals("com.example.fragile.Hidden", answers.get("collectAll com.example.fragile.OnlyHidden"));
        assertTrue(answers.get("firstAvailable com.example.fragile.OnlyHidden")
                .startsWith("! java.lang.IllegalAccessException"), answers.toString());
        assertEquals("", answers.get("collectAll com.example.fragile.OnlyNeedy"));
        assertEquals("! java.lang.ClassNotFoundException: com.example.gone.Gone",
                answers.get("firstAvailable com.example.fragile.OnlyNeedy"));
        assertEquals("! java.lang.IllegalStateException: boom",
                answers.get("collectAll com.example.fragile.OnlyExplosive"));
        assertEquals("! java.lang.IllegalStateException: boom",
                answers.get("firstAvailable com.example.fragile.OnlyExplosive"));
        assertEquals("! java.lang.IllegalStateException: boom", answers.get("findMeta " + BEAN_CONFIGURATION));
        assertEquals("! java.lang.IllegalStateException: boom",
                answers.get("collectAll " + BEAN_INTROSPECTION_REFERENCE), "the forked load fails the same way");
        String definitions = answers.get("findMeta " + BEAN_DEFINITION_REFERENCE);
        assertTrue(definitions.contains("com.example.fragile.Good"), definitions);
        assertFalse(definitions.contains("com.example.fragile.Hidden"),
                "Micronaut's bean definition lookup does not fall back to reflection");
        assertFalse(definitions.contains("com.example.fragile.Needy"), definitions);
    }

    @ParameterizedTest
    @MethodSource("servedCores")
    void keepsTheForkJoinFanOutAndItsOrder(String core) throws Exception {
        Path orderJar = FIXTURES.get(core).orderJar();
        List<String> probe = List.of("table:" + BEAN_DEFINITION_REFERENCE);
        Launch forked = launch(orderJar, List.of("-Djava.util.concurrent.ForkJoinPool.common.parallelism=4"), probe);
        Launch sequential = launch(orderJar, List.of("-Djava.util.concurrent.ForkJoinPool.common.parallelism=1"),
                probe);

        String inPool = answers(forked).get("table " + BEAN_DEFINITION_REFERENCE);
        String onCaller = answers(sequential).get("table " + BEAN_DEFINITION_REFERENCE);
        assertTrue(inPool.contains("@pool"), "at least one definition is constructed by a pool worker: " + inPool);
        assertFalse(onCaller.contains("@pool"), "with one worker every definition is constructed by the caller: "
                + onCaller);
        assertTrue(onCaller.contains("@caller"), onCaller);
        assertEquals(onCaller.replace("@caller", ""), inPool.replace("@caller", "").replace("@pool", ""),
                "the order does not depend on which thread finished first");
    }

    @ParameterizedTest
    @MethodSource("servedCores")
    void theKillSwitchAndTheSystemParentLeaveMicronautOnItsScan(String core) throws Exception {
        Path orderJar = FIXTURES.get(core).orderJar();
        List<String> probes = List.of("collectAll:" + GREETER, "findMeta:" + BEAN_CONFIGURATION);
        Launch traced = launch(orderJar, List.of(TRACE), probes);
        Launch killed = launch(orderJar, List.of(TRACE, KILL_SWITCH), probes);
        Launch systemParent = launch(orderJar, List.of(TRACE, "-Dmicronaut.runner.parent=system"), probes);

        assertTrue(traced.err().contains(TRACE_LINE + GREETER + " -> 11"), traced.err());
        assertTrue(traced.err().contains(TRACE_LINE + BEAN_CONFIGURATION + " -> 2"), traced.err());
        assertFalse(killed.err().contains(TRACE_LINE), killed.err());
        assertFalse(systemParent.err().contains(TRACE_LINE), systemParent.err());
        assertEquals(traced.out(), killed.out());
        assertEquals(traced.out(), systemParent.out());
    }

    @ParameterizedTest
    @MethodSource("servedCores")
    void tracesAnUnprovidedTypeAsEmpty(String core) throws Exception {
        Path orderJar = FIXTURES.get(core).orderJar();
        Launch traced = launch(orderJar, List.of(TRACE), List.of("collectAll:com.example.spi.Unprovided"));

        assertTrue(traced.err().contains(TRACE_LINE + "com.example.spi.Unprovided -> empty"), traced.err());
    }

    // -------------------------------------------------------------------------------------------- fixtures

    private static Map<String, String> sources() {
        Map<String, String> sources = new TreeMap<>();
        sources.put("com/example/app/Main.java", """
                package com.example.app;

                import com.example.spi.Recorded;
                import io.micronaut.core.io.service.MicronautMetaServiceLoaderUtils;
                import io.micronaut.core.io.service.ServiceDefinition;
                import io.micronaut.core.io.service.SoftServiceLoader;
                import io.micronaut.core.optim.StaticOptimizations;
                import java.util.List;

                public final class Main {
                    public static void main(String[] args) throws Throwable {
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
                                    case "table" -> table(typeName);
                                    default -> throw new IllegalArgumentException(api);
                                };
                            } catch (Throwable e) {
                                if (Boolean.getBoolean("fixture.rethrow")) {
                                    throw e;
                                }
                                Throwable root = e;
                                while (root.getCause() != null) {
                                    root = root.getCause();
                                }
                                answer = "! " + root.getClass().getName() + ": " + root.getMessage();
                            }
                            System.out.println(api + " " + typeName + " = " + answer);
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

                    /** Straight through the table's own map, so that what forks is the table and not the scan. */
                    private static String table(String typeName) throws Exception {
                        StaticOptimizations.Loader<?> hook = (StaticOptimizations.Loader<?>) Class
                                .forName("io.micronaut.runner.generated.services.RunnerStaticServices")
                                .getConstructor().newInstance();
                        SoftServiceLoader.Optimizations optimizations = (SoftServiceLoader.Optimizations) hook.load();
                        List<?> values = optimizations.getServiceLoaders().get(typeName).load(null);
                        StringBuilder text = new StringBuilder();
                        for (Object value : values) {
                            text.append(text.length() == 0 ? "" : ",").append(value.getClass().getName());
                            if (value instanceof Recorded recorded) {
                                text.append(recorded.inPool ? "@pool" : "@caller");
                            }
                        }
                        return text.toString();
                    }
                }
                """);
        sources.put("com/example/spi/Greeter.java", "package com.example.spi;\npublic interface Greeter {\n}\n");
        sources.put("com/example/spi/Marker.java", "package com.example.spi;\npublic interface Marker {\n}\n");
        sources.put("com/example/spi/Unprovided.java",
                "package com.example.spi;\npublic interface Unprovided {\n}\n");
        sources.put("com/example/spi/Recorded.java", """
                package com.example.spi;

                import java.util.concurrent.ForkJoinTask;

                /** Remembers whether a pool worker constructed it, after long enough for the pool to start. */
                public abstract class Recorded {
                    public final boolean inPool = ForkJoinTask.inForkJoinPool();

                    protected Recorded() {
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                """);
        for (String name : List.of("app.AppGreeter1", "app.AppGreeter2", "app.AppMetaGreeter", "spi.SpiGreeterA",
                "spi.SpiGreeterB", "spi.SpiMetaGreeter", "extra.ExtraGreeter1", "extra.ExtraGreeter2",
                "extra.ExtraGreeter3", "extra.ExtraMetaGreeter")) {
            sources.put("com/example/" + name.replace('.', '/') + ".java",
                    type("com.example." + name, "implements com.example.spi.Greeter", ""));
        }
        for (String name : List.of("app.AppMarker", "spi.SpiMarker", "extra.ExtraMarker")) {
            sources.put("com/example/" + name.replace('.', '/') + ".java",
                    type("com.example." + name, "implements com.example.spi.Marker", ""));
        }
        for (String name : List.of("app.AppConfiguration", "extra.ExtraConfiguration")) {
            sources.put("com/example/" + name.replace('.', '/') + ".java", type("com.example." + name, "", ""));
        }
        for (String layer : List.of("app", "spi", "extra")) {
            for (int i = 0; i < PLAIN_CLASSES; i++) {
                String name = layer + ".Plain" + i;
                sources.put("com/example/" + name.replace('.', '/') + ".java",
                        type("com.example." + name, "extends com.example.spi.Recorded", ""));
            }
        }
        String all = "implements Mixed, OnlyHidden, OnlyNeedy, OnlyExplosive";
        for (String name : List.of("Mixed", "OnlyHidden", "OnlyNeedy", "OnlyExplosive")) {
            sources.put("com/example/fragile/" + name + ".java",
                    "package com.example.fragile;\npublic interface " + name + " {\n}\n");
        }
        sources.put("com/example/fragile/Good.java", type("com.example.fragile.Good", all, ""));
        sources.put("com/example/fragile/Hidden.java", type("com.example.fragile.Hidden", all,
                "    private Hidden() {\n    }\n"));
        sources.put("com/example/fragile/Needy.java", type("com.example.fragile.Needy", all,
                "    public Needy() {\n        new com.example.gone.Gone();\n    }\n"));
        sources.put("com/example/fragile/Explosive.java", type("com.example.fragile.Explosive", all,
                "    public Explosive() {\n        throw new IllegalStateException(\"boom\");\n    }\n"));
        sources.put("com/example/gone/Gone.java", type("com.example.gone.Gone", "", ""));
        return sources;
    }

    private static String type(String name, String supertypes, String body) {
        int dot = name.lastIndexOf('.');
        return "package " + name.substring(0, dot) + ";\npublic class " + name.substring(dot + 1) + " "
                + supertypes + " {\n" + body + "}\n";
    }

    /** The order Micronaut instantiates the names of one service file in. */
    private static List<String> hashSetOrder(String... names) {
        HashSet<String> set = new HashSet<>();
        for (String name : names) {
            set.add(name);
        }
        return new ArrayList<>(set);
    }

    private static RunnerJarResult build(Path application, List<Dependency> dependencies, Path output)
            throws IOException {
        return RunnerJarBuilder.build(RunnerJarSpec.builder()
                .mainClass("com.example.app.Main")
                .applicationOutput(List.of(application))
                .dependencies(List.copyOf(dependencies))
                .output(output)
                .build(), BuildLogger.noOp());
    }

    private static Optional<Path> optionalClassPathJar(String prefix) {
        for (String element : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path = Path.of(element);
            String name = path.getFileName().toString();
            if (name.startsWith(prefix) && name.endsWith(".jar")) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    private static void compile(JavaCompiler compiler, Path sources, Path classes, Path classPath,
                                Map<String, String> files) throws IOException {
        Files.createDirectories(classes);
        List<String> arguments = new ArrayList<>(List.of("--release", "25", "-proc:none", "-cp",
                classPath.toString(), "-d", classes.toString()));
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

    private static void copyPackage(Path classes, Path target, String directory) throws IOException {
        for (Map.Entry<String, byte[]> entry : packageEntries(classes, directory).entrySet()) {
            Path file = target.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
    }

    private static Map<String, byte[]> packageEntries(Path classes, String directory) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(classes.resolve(directory))) {
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
     * Writes a jar as build tools do, with an entry for every directory: on a plain class path, which the
     * extracted layout is, Micronaut finds {@code META-INF/micronaut/} only in a jar that stores the directory.
     */
    private static Path writeJar(Path file, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(file.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            // The stream wrote META-INF/ itself, in front of the manifest.
            Set<String> directories = new HashSet<>(List.of("META-INF/"));
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                String name = entry.getKey();
                for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
                    String directory = name.substring(0, slash + 1);
                    if (directories.add(directory)) {
                        out.putNextEntry(new ZipEntry(directory));
                        out.closeEntry();
                    }
                }
                out.putNextEntry(new ZipEntry(entry.getKey()));
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

    private static String normalized(String output) {
        return output
                .replace("io.micronaut.core.io.service.DefaultServiceDefinition", "<caller>")
                .replace("io.micronaut.runner.generated.services.RunnerServiceSupplier", "<caller>");
    }

    /** Runs a fixture jar with {@code java -jar}, which has to succeed. */
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

    /**
     * The layout extracted from the order fixture of one micronaut-core line: its application jar, which names
     * the jars under {@code lib/} in its manifest. Extracted on first use.
     */
    private static synchronized Path extracted(String core) throws Exception {
        Path orderJar = FIXTURES.get(core).orderJar();
        Path destination = work.resolve("extracted-" + core);
        Path applicationJar = destination.resolve(orderJar.getFileName());
        if (!Files.isRegularFile(applicationJar)) {
            Launch extract = java(List.of("-Dmicronaut.runner.mode=extract", "-jar", orderJar.toString(),
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

    /** The fixture application packaged with one micronaut-core: once as it is, once with the fragile providers. */
    private record Fixture(String coreVersion, Path orderJar, Path parityJar, RunnerJarResult orderResult,
                           RunnerJarResult parityResult) {
    }
}
