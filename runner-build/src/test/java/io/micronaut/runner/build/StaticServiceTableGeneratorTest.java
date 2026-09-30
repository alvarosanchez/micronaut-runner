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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.constant.ClassDesc;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link StaticServiceTableGenerator} over synthetic layers: the hook classes are the real ones of
 * the micronaut-core on the test class path, under whatever version a test gives their manifest, and
 * everything else is a class file written with the ClassFile API. Nothing here starts a JVM; the generated
 * table is read back by loading the one data class it consists of.
 */
class StaticServiceTableGeneratorTest {

    private static final String SPI = "com.example.Spi";
    private static final String SERVICES = "META-INF/services/";
    private static final String MICRONAUT = "META-INF/micronaut/";
    private static final String BEAN_DEFINITION_REFERENCE = "io.micronaut.inject.BeanDefinitionReference";
    private static final String BEAN_CONFIGURATION = "io.micronaut.inject.BeanConfiguration";
    private static final String BEAN_INTROSPECTION_REFERENCE = "io.micronaut.core.beans.BeanIntrospectionReference";
    private static final String OPTIMIZATIONS_LOADER = "io.micronaut.core.optim.StaticOptimizations$Loader";
    private static final String LOADER = "io.micronaut.runner.generated.services.RunnerStaticServices";

    /** Every entry of the micronaut-core jar on the test class path, by name. */
    private static Map<String, byte[]> coreEntries;

    @BeforeAll
    static void readCore() throws IOException {
        coreEntries = jarEntries(classPathJar("micronaut-core-"));
        assertTrue(coreEntries.containsKey("io/micronaut/core/io/service/SoftServiceLoader.class"));
    }

    /**
     * Finds a jar of the test class path by the start of its file name.
     *
     * @param prefix what the file name starts with, such as {@code micronaut-core-}
     * @return the jar
     */
    static Path classPathJar(String prefix) {
        for (String element : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path = Path.of(element);
            String name = path.getFileName().toString();
            if (name.startsWith(prefix) && name.endsWith(".jar")
                    && Character.isDigit(name.charAt(prefix.length()))) {
                return path;
            }
        }
        throw new IllegalStateException("No " + prefix + "*.jar on the test class path");
    }

    /**
     * The micronaut-core 5.1.x jars the build resolves for the static service table tests: micronaut-core
     * first, then micronaut-inject, then what micronaut-inject links against.
     *
     * @return the jars
     */
    static List<Path> micronautCore51() {
        String property = System.getProperty("runner.test.micronautCore51");
        if (property == null || property.isBlank()) {
            throw new IllegalStateException("The build did not pass runner.test.micronautCore51");
        }
        List<Path> jars = new ArrayList<>();
        for (String element : property.split(File.pathSeparator)) {
            jars.add(Path.of(element));
        }
        jars.sort(java.util.Comparator.comparingInt((Path jar) -> {
            String name = jar.getFileName().toString();
            return name.startsWith("micronaut-core-") ? 0 : name.startsWith("micronaut-inject-") ? 1 : 2;
        }).thenComparing(jar -> jar.getFileName().toString()));
        if (!jars.get(0).getFileName().toString().startsWith("micronaut-core-5.1.")) {
            throw new IllegalStateException("No micronaut-core 5.1.x in " + property);
        }
        return jars;
    }

    private static Map<String, byte[]> jarEntries(Path jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (ZipEntry entry : file.stream().toList()) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), file.getInputStream(entry).readAllBytes());
                }
            }
        }
        return entries;
    }

    // ---------------------------------------------------------------------------------------------- order

    @Test
    void listsServiceFilesInLayerOrderAndEachFileInHashSetOrder() throws IOException {
        List<String> first = List.of("com.example.Zeta", "com.example.Alpha", "com.example.Mu",
                "com.example.Beta", "com.example.Omega");
        List<String> second = List.of("com.example.Alpha", "com.example.Kappa");
        List<String> third = List.of("com.example.Zeta");
        // The point of replaying the HashSet: its order is not the order of the file.
        assertNotEquals(first, hashSetOrder(first), "pick names whose hash order differs from file order");

        MapLayer application = new MapLayer("application").put(SERVICES + SPI, lines(first));
        MapLayer one = new MapLayer("one").put(SERVICES + SPI, lines(second));
        MapLayer two = new MapLayer("two").put(SERVICES + SPI, lines(third));
        MapLayer classes = new MapLayer("classes").type(SPI);
        for (String name : List.of("com.example.Zeta", "com.example.Alpha", "com.example.Mu", "com.example.Beta",
                "com.example.Omega", "com.example.Kappa")) {
            classes.implementation(name, SPI);
        }

        Table table = Table.of(generate(List.of(application, one, core("5.1.15"), two, classes), Map.of()));

        List<String> expected = new ArrayList<>(hashSetOrder(first));
        expected.addAll(hashSetOrder(second));
        expected.addAll(hashSetOrder(third));
        assertEquals(expected, table.names(SPI), "layer order, then each file's HashSet order, duplicates kept");
        assertEquals(0, table.flags(SPI));
    }

    @Test
    void readsAServiceFileExactlyAsMicronautDoes() throws IOException {
        byte[] file = ("# a comment\n"
                + "\n"
                + "com.example.A\r\n"
                + "com.example.B # a trailing comment\n"
                + "com.example.C#x\n"
                + "  com.example.D\n"
                + "com.example.A\n"
                + "#com.example.E").getBytes(StandardCharsets.UTF_8);

        List<String> names = StaticServiceTableGenerator.serviceNames(file);

        // Nothing is trimmed: the space before a comment and the indentation both survive.
        assertEquals(hashSetOrder(List.of("com.example.A", "com.example.B ", "com.example.C", "  com.example.D")),
                names);
    }

    @Test
    void appendsTheMicronautNamesInTheReverseOfTheirStoredOrder() throws IOException {
        MapLayer application = new MapLayer("application").put(SERVICES + SPI, lines(List.of("com.example.A")));
        MapLayer classes = new MapLayer("classes").type(SPI)
                .implementation("com.example.A", SPI)
                .implementation("com.example.M1", SPI)
                .implementation("com.example.M2", SPI)
                .implementation("com.example.M3", SPI);
        Map<String, Long> merged = merged(
                MICRONAUT + BEAN_DEFINITION_REFERENCE + "/com.example.$A$Definition", 0L,
                MICRONAUT + BEAN_DEFINITION_REFERENCE + "/com.example.$B$Definition", 0L,
                MICRONAUT + BEAN_DEFINITION_REFERENCE + "/com.example.$C$Definition", 0L,
                MICRONAUT + SPI + "/com.example.M1", 0L,
                MICRONAUT + SPI + "/com.example.M2", 0L,
                MICRONAUT + SPI + "/com.example.M3", 0L);

        Table table = Table.of(generate(List.of(application, core("5.1.15"), classes), merged));

        assertEquals(List.of("com.example.$C$Definition", "com.example.$B$Definition", "com.example.$A$Definition"),
                table.names(BEAN_DEFINITION_REFERENCE));
        assertEquals(List.of("com.example.A", "com.example.M3", "com.example.M2", "com.example.M1"),
                table.names(SPI), "META-INF/services first, then META-INF/micronaut backwards");
        assertEquals(StaticServiceTableGenerator.FLAG_PARALLEL | StaticServiceTableGenerator.FLAG_MICRONAUT_ONLY,
                table.flags(BEAN_DEFINITION_REFERENCE));
    }

    @Test
    void flagsTheTypesMicronautLoadsInParallelAndReadsOnlyFromItsOwnDirectory() throws IOException {
        MapLayer classes = new MapLayer("classes")
                .implementation("com.example.$Introspection", "java.lang.Runnable");
        Map<String, Long> merged = merged(
                MICRONAUT + BEAN_INTROSPECTION_REFERENCE + "/com.example.$Introspection", 0L,
                MICRONAUT + BEAN_CONFIGURATION + "/com.example.$BeanConfiguration", 0L,
                MICRONAUT + BEAN_DEFINITION_REFERENCE + "/com.example.$A$Definition", 0L);

        Table table = Table.of(generate(List.of(new MapLayer("application"), core("5.1.15"), classes), merged));

        assertEquals(StaticServiceTableGenerator.FLAG_PARALLEL, table.flags(BEAN_INTROSPECTION_REFERENCE));
        assertEquals(StaticServiceTableGenerator.FLAG_MICRONAUT_ONLY, table.flags(BEAN_CONFIGURATION));
        assertEquals(StaticServiceTableGenerator.FLAG_PARALLEL | StaticServiceTableGenerator.FLAG_MICRONAUT_ONLY,
                table.flags(BEAN_DEFINITION_REFERENCE));
    }

    // ------------------------------------------------------------------------------------ per-type checks

    @Test
    void aMissingClassKeepsABeanDefinitionButMakesASoftServiceLoaderTypeDynamic() throws IOException {
        MapLayer application = new MapLayer("application")
                .put(SERVICES + SPI, lines(List.of("com.example.Present", "com.example.Missing")));
        MapLayer classes = new MapLayer("classes").type(SPI).implementation("com.example.Present", SPI);
        Map<String, Long> merged = merged(
                MICRONAUT + BEAN_DEFINITION_REFERENCE + "/com.example.Missing", 0L,
                MICRONAUT + BEAN_DEFINITION_REFERENCE + "/com.example.Present", 0L);

        StaticServiceTableGenerator.Result result =
                generate(List.of(application, core("5.1.15"), classes), merged);
        Table table = Table.of(result);

        assertEquals(List.of("com.example.Present", "com.example.Missing"), table.names(BEAN_DEFINITION_REFERENCE),
                "Micronaut ignores a bean definition it cannot load, and so does the table");
        assertTrue(table.dynamic(SPI), "a definition that is always 'present' must name a class that is");
        assertEquals(List.of(SPI), result.dynamicTypes());
    }

    @Test
    void aMissingSupertypeMakesASoftServiceLoaderTypeDynamic() throws IOException {
        MapLayer application = new MapLayer("application")
                .put(SERVICES + SPI, lines(List.of("com.example.Injected")))
                .put(SERVICES + "com.example.Other", lines(List.of("com.example.Deep")));
        // javax.inject is on no layer and in no JDK: the class cannot be linked at run time.
        MapLayer classes = new MapLayer("classes").type(SPI).type("com.example.Other")
                .implementation("com.example.Injected", SPI, "javax.inject.Provider")
                .implementation("com.example.Base", "com.example.Gone")
                .put("com/example/Deep.class", classFile("com.example.Deep", "com.example.Base", "com.example.Other"));
        Map<String, Long> merged = merged(
                MICRONAUT + BEAN_DEFINITION_REFERENCE + "/com.example.Injected", 0L);

        Table table = Table.of(generate(List.of(application, core("5.1.15"), classes), merged));

        assertTrue(table.dynamic(SPI), "an interface that is missing");
        assertTrue(table.dynamic("com.example.Other"), "an interface of a superclass that is missing");
        assertEquals(List.of("com.example.Injected"), table.names(BEAN_DEFINITION_REFERENCE));
    }

    @Test
    void supertypesOfTheBuildJdkCount() throws IOException {
        MapLayer application = new MapLayer("application")
                .put(SERVICES + "java.sql.Driver", lines(List.of("com.example.Driver")))
                .put(SERVICES + "java.lang.Runnable", lines(List.of("com.example.Task")));
        MapLayer classes = new MapLayer("classes")
                .implementation("com.example.Driver", "java.sql.Driver")
                .implementation("com.example.Task", "java.lang.Runnable");

        Table table = Table.of(generate(List.of(application, core("5.1.15"), classes), Map.of()));

        assertEquals(List.of("com.example.Driver"), table.names("java.sql.Driver"));
        assertEquals(List.of("com.example.Task"), table.names("java.lang.Runnable"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"com.example.Present ", " com.example.Present", "com.example..Present",
        "com/example/Present", "com.example.Present."})
    void anInvalidNameMakesItsTypeDynamic(String name) throws IOException {
        MapLayer application = new MapLayer("application").put(SERVICES + SPI, lines(List.of(name)));
        MapLayer classes = new MapLayer("classes").type(SPI).implementation("com.example.Present", SPI);

        Table table = Table.of(generate(List.of(application, core("5.1.15"), classes), Map.of()));

        assertTrue(table.dynamic(SPI));
    }

    @Test
    void aBeanDefinitionReferenceWithAServiceFileStaysDynamic() throws IOException {
        MapLayer application = new MapLayer("application")
                .put(SERVICES + BEAN_DEFINITION_REFERENCE, lines(List.of("com.example.$A$Definition")));
        Map<String, Long> merged = merged(
                MICRONAUT + BEAN_DEFINITION_REFERENCE + "/com.example.$A$Definition", 0L,
                MICRONAUT + BEAN_CONFIGURATION + "/com.example.$BeanConfiguration", 0L);

        Table table = Table.of(generate(List.of(application, core("5.1.15")), merged));

        assertTrue(table.dynamic(BEAN_DEFINITION_REFERENCE));
        assertEquals(List.of("com.example.$BeanConfiguration"), table.names(BEAN_CONFIGURATION));
    }

    @Test
    void aMergedEntryWithContentMakesItsTypeDynamicAndAnEmptyOneDoesNot() throws IOException {
        Map<String, Long> merged = merged(
                MICRONAUT + BEAN_CONFIGURATION + "/com.example.$BeanConfiguration", 0L,
                MICRONAUT + BEAN_DEFINITION_REFERENCE + "/com.example.$A$Definition", 0L,
                MICRONAUT + BEAN_DEFINITION_REFERENCE + "/com.example.$B$Definition", 17L);

        Table table = Table.of(generate(List.of(new MapLayer("application"), core("5.1.15")), merged));

        assertTrue(table.dynamic(BEAN_DEFINITION_REFERENCE), "a names-only table would drop the descriptor");
        assertEquals(List.of("com.example.$BeanConfiguration"), table.names(BEAN_CONFIGURATION));
    }

    @Test
    void aServiceFileStoredTwiceInOneJarMakesItsTypeDynamic() throws IOException {
        MapLayer twice = new MapLayer("twice")
                .put(SERVICES + SPI, lines(List.of("com.example.A")))
                .duplicate(SERVICES + SPI, lines(List.of("com.example.B")));
        MapLayer classes = new MapLayer("classes").type(SPI)
                .implementation("com.example.A", SPI).implementation("com.example.B", SPI);

        Table table = Table.of(generate(List.of(new MapLayer("application"), core("5.1.15"), twice, classes),
                Map.of()));

        assertTrue(table.dynamic(SPI));
    }

    @Test
    void aVersionedClassAboveTheBaselineMakesItsTypeDynamic() throws IOException {
        MapLayer application = new MapLayer("application")
                .put(SERVICES + SPI, lines(List.of("com.example.Versioned")))
                .put(SERVICES + "com.example.Other", lines(List.of("com.example.Selected")));
        MapLayer classes = new MapLayer("classes").asMultiRelease().type(SPI).type("com.example.Other")
                .implementation("com.example.Versioned", SPI)
                .put("META-INF/versions/99/com/example/Versioned.class",
                        classFile("com.example.Versioned", "java.lang.Object", SPI))
                // The copy every supported runtime selects is the one whose supertypes are checked.
                .implementation("com.example.Selected", "com.example.Gone")
                .put("META-INF/versions/21/com/example/Selected.class",
                        classFile("com.example.Selected", "java.lang.Object", "com.example.Other"));

        Table table = Table.of(generate(List.of(application, core("5.1.15"), classes), Map.of()));

        assertTrue(table.dynamic(SPI), "only some runtimes load the versioned copy");
        assertEquals(List.of("com.example.Selected"), table.names("com.example.Other"));
    }

    @Test
    void aClassFileThatCannotBeReadMakesItsTypeDynamic() throws IOException {
        MapLayer application = new MapLayer("application")
                .put(SERVICES + SPI, lines(List.of("com.example.NoSuperclass")))
                .put(SERVICES + "com.example.Other", lines(List.of("com.example.Child")))
                .put(SERVICES + "com.example.Third", lines(List.of("com.example.Truncated")));
        byte[] whole = classFile("com.example.Truncated", "java.lang.Object", "com.example.Third");
        // The first two parse: it is reading a supertype out of them that fails.
        MapLayer classes = new MapLayer("classes").type(SPI).type("com.example.Other").type("com.example.Third")
                .put("com/example/NoSuperclass.class",
                        withSuperclassOutOfRange(classFile("com.example.NoSuperclass", "java.lang.Object", SPI)))
                .put("com/example/NoInterface.class",
                        withFirstInterfaceOutOfRange(classFile("com.example.NoInterface", "java.lang.Object", SPI)))
                .put("com/example/Child.class",
                        classFile("com.example.Child", "com.example.NoInterface", "com.example.Other"))
                .put("com/example/Truncated.class", Arrays.copyOf(whole, whole.length / 2));
        Map<String, Long> merged = merged(
                MICRONAUT + BEAN_DEFINITION_REFERENCE + "/com.example.NoSuperclass", 0L);

        StaticServiceTableGenerator.Result result =
                generate(List.of(application, core("5.1.15"), classes), merged);
        Table table = Table.of(result);

        assertTrue(table.dynamic(SPI), "a class whose superclass cannot be read");
        assertTrue(table.dynamic("com.example.Other"), "a superclass whose interfaces cannot be read");
        assertTrue(table.dynamic("com.example.Third"), "a class file that does not parse");
        assertEquals(List.of("com.example.Other", SPI, "com.example.Third"), result.dynamicTypes());
        assertEquals(List.of("com.example.NoSuperclass"), table.names(BEAN_DEFINITION_REFERENCE),
                "Micronaut ignores a bean definition it cannot load, and so does the table");
    }

    @Test
    void omitsATypeWithoutNamesAndReadsNothingItDoesNotNeed() throws IOException {
        MapLayer application = new MapLayer("application")
                .put(SERVICES + SPI, "# nothing here\n".getBytes(StandardCharsets.UTF_8));
        MapLayer unrelated = new MapLayer("unrelated")
                .put("com/example/Unrelated.class", classFile("com.example.Unrelated", "java.lang.Object"))
                .put("data.bin", new byte[] {1, 2, 3});

        StaticServiceTableGenerator.Result result =
                generate(List.of(application, core("5.1.15"), unrelated), Map.of());
        Table table = Table.of(result);

        assertFalse(table.types.containsKey(SPI), "the shared empty loader answers for it");
        assertEquals(List.of(), unrelated.read, "a layer nothing names is never read");
        // The table's own registration is part of the closed world it describes.
        assertEquals(List.of(LOADER), table.names(OPTIMIZATIONS_LOADER));
        assertEquals(1, result.typeCount());
        assertEquals(1, result.slotCount());
    }

    // --------------------------------------------------------------------------------------- version gate

    @ParameterizedTest
    @ValueSource(strings = {"5.1.10", "5.1.15", "5.1.10-oracle-00001", "5.1.99"})
    void generatesATableForMicronautCoreInTheSupportedRange(String version) throws IOException {
        StaticServiceTableGenerator.Result result =
                generate(List.of(new MapLayer("application"), core(version)), Map.of());

        assertTrue(result.generated(), result.reason());
        assertEquals(version, result.coreVersion());
        assertNull(result.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.1.9", "5.1", "5.2.0", "5.2.0-M1", "5.2.2", "5.3.0", "5.3.0-SNAPSHOT", "4.10.7",
        "6.0.0", "five", "5"})
    void generatesNoTableForMicronautCoreOutsideTheRange(String version) throws IOException {
        StaticServiceTableGenerator.Result result =
                generate(List.of(new MapLayer("application"), core(version)), Map.of());

        assertNoTable(result, "micronaut-core " + version + " is outside the supported range [5.1.10, 5.2)");
    }

    @Test
    void generatesNoTableWithoutAnImplementationVersion() throws IOException {
        StaticServiceTableGenerator.Result result =
                generate(List.of(new MapLayer("application"), core(null)), Map.of());

        assertNoTable(result, "the manifest of core has no Implementation-Version");
    }

    @Test
    void generatesNoTableWithoutMicronautCore() throws IOException {
        MapLayer application = new MapLayer("application")
                .put(SERVICES + SPI, lines(List.of("com.example.A")));

        StaticServiceTableGenerator.Result result = generate(List.of(application), Map.of());

        assertNoTable(result, "the application has no micronaut-core");
        assertEquals(List.of(), application.read, "nothing is read for an application without micronaut-core");
    }

    @Test
    void generatesNoTableForAMicronautCoreWithItsOwnServiceIndex() throws IOException {
        MapLayer core = core("5.1.15").put("io/micronaut/core/io/service/ServiceIndex.class",
                classFile("io.micronaut.core.io.service.ServiceIndex", "java.lang.Object"));

        StaticServiceTableGenerator.Result result = generate(List.of(new MapLayer("application"), core), Map.of());

        assertNoTable(result, "micronaut-core 5.1.15 has its own service index");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "io/micronaut/core/io/service/SoftServiceLoader$StaticDefinition|of|(Ljava/lang/String;Ljava/lang/Class;)",
        "io/micronaut/core/io/service/SoftServiceLoader$StaticDefinition|of|(Ljava/lang/String;Ljava/util/function/Supplier;)",
        "io/micronaut/core/io/service/SoftServiceLoader$StaticServiceLoader|findAll|(",
        "io/micronaut/core/io/service/SoftServiceLoader$StaticServiceLoader|load|(Ljava/util/function/Predicate;Ljava/util/function/Predicate;)",
        "io/micronaut/core/io/service/SoftServiceLoader$Optimizations|<init>|(",
        "io/micronaut/core/optim/StaticOptimizations$Loader|load|("})
    void generatesNoTableWhenAHookMemberIsMissing(String member) throws IOException {
        String[] parts = member.split("\\|");
        MapLayer core = core("5.1.15");
        String entry = parts[0] + ".class";
        core.put(entry, withoutMethod(core.entries.get(entry), parts[1], parts[2]));

        StaticServiceTableGenerator.Result result = generate(List.of(new MapLayer("application"), core), Map.of());

        assertNoTable(result, "micronaut-core 5.1.15 has no public " + parts[0].replace('/', '.') + "." + parts[1]);
    }

    @Test
    void generatesNoTableWhenAHookClassIsMissing() throws IOException {
        MapLayer core = core("5.1.15");
        core.entries.remove("io/micronaut/core/io/service/SoftServiceLoader$StaticDefinition.class");

        StaticServiceTableGenerator.Result result = generate(List.of(new MapLayer("application"), core), Map.of());

        assertNoTable(result, "micronaut-core 5.1.15 has no io.micronaut.core.io.service.SoftServiceLoader$StaticDefinition");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void generatesNoTableWhenAHookClassCannotBeRead(boolean parses) throws IOException {
        MapLayer core = core("5.1.15");
        String entry = "io/micronaut/core/io/service/SoftServiceLoader$StaticDefinition.class";
        byte[] whole = core.entries.get(entry);
        // Either the class file parses and its first method has a name that cannot be read, or it does not parse.
        core.put(entry, parses ? withFirstMethodNameOutOfRange(whole) : Arrays.copyOf(whole, whole.length / 2));

        StaticServiceTableGenerator.Result result = generate(List.of(new MapLayer("application"), core), Map.of());

        assertNoTable(result, "micronaut-core 5.1.15 has no readable"
                + " io.micronaut.core.io.service.SoftServiceLoader$StaticDefinition");
    }

    // ----------------------------------------------------------------------------------------- stand-down

    @Test
    void standsDownForALoaderThatSuppliesServiceLoadersItself() throws IOException {
        // What a generated loader of Micronaut AOT 2.x looks like to a constant pool scan.
        byte[] rival = ClassFile.of().build(ClassDesc.of("com.example.RivalLoader"), builder -> builder
                .withInterfaceSymbols(ClassDesc.of(OPTIMIZATIONS_LOADER))
                .withMethodBody("load", java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_Object),
                        ClassFile.ACC_PUBLIC, code -> code
                                .new_(ClassDesc.of("io.micronaut.core.io.service.SoftServiceLoader$Optimizations"))
                                .areturn()));
        MapLayer dependency = new MapLayer("dependency")
                .put(SERVICES + OPTIMIZATIONS_LOADER, lines(List.of("com.example.RivalLoader")))
                .put("com/example/RivalLoader.class", rival);

        StaticServiceTableGenerator.Result result =
                generate(List.of(new MapLayer("application"), core("5.1.15"), dependency), Map.of());

        assertNoTable(result, "the registered StaticOptimizations loader com.example.RivalLoader already supplies"
                + " Micronaut's service loaders");
    }

    @Test
    void standsDownForALoaderWhoseClassIsMissing() throws IOException {
        MapLayer application = new MapLayer("application")
                .put(SERVICES + OPTIMIZATIONS_LOADER, lines(List.of("com.example.GoneLoader")));

        StaticServiceTableGenerator.Result result = generate(List.of(application, core("5.1.15")), Map.of());

        assertNoTable(result, "the class of the registered StaticOptimizations loader com.example.GoneLoader is"
                + " missing or cannot be read");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void standsDownForALoaderWhoseClassCannotBeRead(boolean parses) throws IOException {
        byte[] whole = classFile("com.example.BrokenLoader", "java.lang.Object", OPTIMIZATIONS_LOADER);
        // The constant pool is what is searched: either one of its entries points nowhere, or it is cut short.
        MapLayer application = new MapLayer("application")
                .put(SERVICES + OPTIMIZATIONS_LOADER, lines(List.of("com.example.BrokenLoader")))
                .put("com/example/BrokenLoader.class",
                        parses ? withFirstClassEntryOutOfRange(whole) : Arrays.copyOf(whole, whole.length / 2));

        StaticServiceTableGenerator.Result result = generate(List.of(application, core("5.1.15")), Map.of());

        assertNoTable(result, "the class of the registered StaticOptimizations loader com.example.BrokenLoader is"
                + " missing or cannot be read");
    }

    @Test
    void keepsALoaderThatSuppliesSomethingElseAndPutsItsOwnLineFirst() throws IOException {
        byte[] existing = "# written by another tool\ncom.example.OtherLoader".getBytes(StandardCharsets.UTF_8);
        MapLayer application = new MapLayer("application")
                .put(SERVICES + OPTIMIZATIONS_LOADER, existing)
                .implementation("com.example.OtherLoader", OPTIMIZATIONS_LOADER);

        StaticServiceTableGenerator.Result result = generate(List.of(application, core("5.1.15")), Map.of());

        assertTrue(result.generated(), result.reason());
        // First, so that Micronaut sets the table before another loader can read it.
        assertEquals(LOADER + "\n# written by another tool\ncom.example.OtherLoader\n",
                new String(result.entries().get(SERVICES + OPTIMIZATIONS_LOADER), StandardCharsets.UTF_8));
        assertEquals(hashSetOrder(List.of(LOADER, "com.example.OtherLoader")),
                Table.of(result).names(OPTIMIZATIONS_LOADER));
    }

    @Test
    void generatesNoTableAndWarnsWhenAGeneratedNameIsTaken() throws IOException {
        MapLayer application = new MapLayer("application")
                .put("io/micronaut/runner/generated/services/RunnerServiceTable.class", new byte[] {1});

        StaticServiceTableGenerator.Result result = generate(List.of(application, core("5.1.15")), Map.of());

        assertFalse(result.generated());
        assertTrue(result.warn());
        assertEquals("the application output already carries"
                + " 'io/micronaut/runner/generated/services/RunnerServiceTable.class'", result.reason());
        assertEquals(Map.of(), result.entries());
    }

    @ParameterizedTest
    @ValueSource(strings = {"META-INF/micronaut/stray.txt",
        "META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/nested/deeper"})
    void generatesNoTableForMergedMetadataMicronautVersionsReadDifferently(String name) throws IOException {
        Map<String, Long> merged = merged(
                MICRONAUT + BEAN_DEFINITION_REFERENCE + "/com.example.$A$Definition", 0L,
                name, 0L);

        StaticServiceTableGenerator.Result result =
                generate(List.of(new MapLayer("application"), core("5.1.15")), merged);

        assertNoTable(result, "the merged Micronaut metadata has the entry '" + name + "'");
    }

    // ------------------------------------------------------------------------------------ bytecode guards

    @Test
    void everyGeneratedAndTemplateClassIsJava25WithoutInvokeDynamic() throws IOException {
        StaticServiceTableGenerator.Result result = generate(fixture(), fixtureMerged());

        int classes = 0;
        for (Map.Entry<String, byte[]> entry : result.entries().entrySet()) {
            if (!entry.getKey().endsWith(".class")) {
                continue;
            }
            classes++;
            ClassModel model = ClassFile.of().parse(entry.getValue());
            assertEquals(69, model.majorVersion(), entry.getKey());
            assertEquals(0, model.minorVersion(), entry.getKey());
            assertTrue(model.findAttribute(Attributes.bootstrapMethods()).isEmpty(),
                    entry.getKey() + " has a BootstrapMethods attribute");
            for (MethodModel method : model.methods()) {
                if (method.code().isEmpty()) {
                    continue;
                }
                for (CodeElement element : method.code().get()) {
                    assertFalse(element instanceof InvokeDynamicInstruction,
                            entry.getKey() + "." + method.methodName().stringValue() + " uses invokedynamic");
                }
            }
            assertTrue(entry.getKey().startsWith("io/micronaut/runner/generated/services/"), entry.getKey());
        }
        assertEquals(StaticServiceTableGenerator.templates().size() + 1, classes,
                "the template classes and the one generated table");
        assertFalse(StaticServiceTableGenerator.templates()
                        .containsKey("io/micronaut/runner/generated/services/RunnerServiceTable.class"),
                "the compile-only shape of the table is not bundled");
    }

    @Test
    void noGeneratedClassRefersToAListedClass() throws IOException {
        StaticServiceTableGenerator.Result result = generate(fixture(), fixtureMerged());
        Set<String> listed = new HashSet<>();
        Table table = Table.of(result);
        for (String type : table.types.keySet()) {
            if (table.dynamic(type)) {
                continue;
            }
            for (String name : table.names(type)) {
                listed.add(name.replace('.', '/'));
            }
        }
        listed.remove(LOADER.replace('.', '/'));
        assertFalse(listed.isEmpty());

        for (Map.Entry<String, byte[]> entry : result.entries().entrySet()) {
            if (!entry.getKey().endsWith(".class")) {
                continue;
            }
            for (PoolEntry constant : ClassFile.of().parse(entry.getValue()).constantPool()) {
                if (constant instanceof ClassEntry type) {
                    assertFalse(listed.contains(type.asInternalName()),
                            entry.getKey() + " links to " + type.asInternalName() + ": the table is names only");
                }
            }
        }
    }

    @Test
    void splitsNamesBeyondOneConstantIntoChunksThatRoundTrip() throws IOException {
        List<String> names = new ArrayList<>();
        Map<String, Long> merged = new LinkedHashMap<>();
        for (int i = 0; i < 2_000; i++) {
            // 2,000 names of about 100 bytes: three times what one constant holds. Sorted, as the archive is.
            String name = "com.example.generated.package%04d.$AVeryLongBeanDefinitionClassNameNumber%04d$Definition"
                    .formatted(i, i) + "é".repeat(5);
            names.add(name);
            merged.put(MICRONAUT + BEAN_DEFINITION_REFERENCE + "/" + name, 0L);
        }

        StaticServiceTableGenerator.Result result =
                generate(List.of(new MapLayer("application"), core("5.1.15")), merged);
        Table table = Table.of(result);

        assertTrue(table.chunks > 3, "chunks: " + table.chunks);
        List<String> expected = new ArrayList<>(names);
        java.util.Collections.reverse(expected);
        assertEquals(expected, table.names(BEAN_DEFINITION_REFERENCE));
        assertEquals(2_001, result.slotCount());
        for (String chunk : StaticServiceTableGenerator.chunk(expected)) {
            assertTrue(StaticServiceTableGenerator.modifiedUtf8Length(chunk)
                    <= StaticServiceTableGenerator.MAX_CONSTANT_BYTES);
        }
    }

    @Test
    void countsModifiedUtf8AsAClassFileStoresIt() {
        assertEquals(3, StaticServiceTableGenerator.modifiedUtf8Length("abc"));
        assertEquals(2, StaticServiceTableGenerator.modifiedUtf8Length("\u0000"));
        assertEquals(2, StaticServiceTableGenerator.modifiedUtf8Length("é"));
        assertEquals(3, StaticServiceTableGenerator.modifiedUtf8Length("€"));
        assertEquals(6, StaticServiceTableGenerator.modifiedUtf8Length("😀"), "a surrogate pair");
    }

    // ---------------------------------------------------------------------------------------- determinism

    @Test
    void twoRunsOverTheSameInputsGiveIdenticalBytes() throws IOException {
        StaticServiceTableGenerator.Result first = generate(fixture(), fixtureMerged());
        StaticServiceTableGenerator.Result second = generate(fixture(), fixtureMerged());

        assertEquals(new ArrayList<>(first.entries().keySet()), new ArrayList<>(second.entries().keySet()));
        for (Map.Entry<String, byte[]> entry : first.entries().entrySet()) {
            assertArrayEquals(entry.getValue(), second.entries().get(entry.getKey()), entry.getKey());
        }
        assertEquals(first.describe(), second.describe());
        assertEquals("3 types, 8 slots (1 left to Micronaut's scan: com.example.Broken)", first.describe());
    }

    @Test
    void parsesTheVersionPrefixOnly() {
        assertArrayEquals(new int[] {5, 2, 2}, StaticServiceTableGenerator.parseVersion("5.2.2"));
        assertArrayEquals(new int[] {5, 3, 0}, StaticServiceTableGenerator.parseVersion("5.3.0-M1"));
        assertArrayEquals(new int[] {5, 2, 0}, StaticServiceTableGenerator.parseVersion("5.2"));
        assertArrayEquals(new int[] {5, 2, 0}, StaticServiceTableGenerator.parseVersion("5.2.0-M1"));
        assertArrayEquals(new int[] {5, 1, 10}, StaticServiceTableGenerator.parseVersion("5.1.10.Final"));
        assertNull(StaticServiceTableGenerator.parseVersion("5"));
        assertNull(StaticServiceTableGenerator.parseVersion("v5.2.2"));
        assertNull(StaticServiceTableGenerator.parseVersion(""));
        assertNull(StaticServiceTableGenerator.parseVersion("99999999999.1.1"));
    }

    // -------------------------------------------------------------------------------------------- fixtures

    private static List<StaticServiceTableGenerator.Layer> fixture() {
        MapLayer application = new MapLayer("application")
                .put(SERVICES + SPI, lines(List.of("com.example.Zeta", "com.example.Alpha", "com.example.Mu")))
                .put(SERVICES + "com.example.Broken", lines(List.of("com.example.Nowhere")))
                .type(SPI)
                .implementation("com.example.Zeta", SPI)
                .implementation("com.example.Alpha", SPI)
                .implementation("com.example.Mu", SPI);
        MapLayer dependency = new MapLayer("dependency")
                .put(SERVICES + SPI, lines(List.of("com.example.Kappa")))
                .implementation("com.example.Kappa", SPI);
        return List.of(application, core("5.1.15"), dependency);
    }

    private static Map<String, Long> fixtureMerged() {
        return merged(
                MICRONAUT + BEAN_DEFINITION_REFERENCE + "/com.example.$A$Definition", 0L,
                MICRONAUT + BEAN_DEFINITION_REFERENCE + "/com.example.$B$Definition", 0L,
                MICRONAUT + BEAN_DEFINITION_REFERENCE + "/com.example.$C$Definition", 0L);
    }

    private static StaticServiceTableGenerator.Result generate(List<? extends StaticServiceTableGenerator.Layer> layers,
                                                              Map<String, Long> merged) throws IOException {
        return StaticServiceTableGenerator.generate(new ArrayList<>(layers), merged);
    }

    private static void assertNoTable(StaticServiceTableGenerator.Result result, String reason) {
        assertFalse(result.generated());
        assertNotNull(result.reason());
        assertTrue(result.reason().startsWith(reason), result.reason());
        assertFalse(result.warn(), "an ineligible application is information, not a warning");
        assertEquals(Map.of(), result.entries());
        assertEquals(0, result.slotCount());
        assertEquals(0, result.typeCount());
        assertNull(result.coreVersion());
    }

    /** The real hook classes of micronaut-core, under the given version. */
    private static MapLayer core(String version) {
        MapLayer layer = new MapLayer("core").version(version);
        for (Map.Entry<String, byte[]> entry : coreEntries.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("io/micronaut/core/io/service/") || name.startsWith("io/micronaut/core/optim/")) {
                layer.put(name, entry.getValue());
            }
        }
        return layer;
    }

    private static byte[] withoutMethod(byte[] classFile, String name, String descriptorPrefix) {
        ClassModel model = ClassFile.of().parse(classFile);
        return ClassFile.of().transformClass(model, ClassTransform.dropping(element ->
                element instanceof MethodModel method && method.methodName().equalsString(name)
                        && method.methodType().stringValue().startsWith(descriptorPrefix)));
    }

    private static byte[] lines(List<String> names) {
        return (String.join("\n", names) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** The order Micronaut instantiates the names of one service file in. */
    private static List<String> hashSetOrder(List<String> names) {
        return new ArrayList<>(new HashSet<>(names));
    }

    private static Map<String, Long> merged(Object... namesAndSizes) {
        // Sorted, as RunnerJarBuilder stores the merged copy.
        Map<String, Long> sorted = new TreeMap<>();
        for (int i = 0; i < namesAndSizes.length; i += 2) {
            sorted.put((String) namesAndSizes[i], (Long) namesAndSizes[i + 1]);
        }
        return new LinkedHashMap<>(sorted);
    }

    /**
     * Writes a public class with the given supertypes and nothing else: enough for a generator that only
     * parses.
     *
     * @param name       the binary name
     * @param superclass the binary name of the superclass
     * @param interfaces the binary names of the interfaces
     * @return the class file
     */
    static byte[] classFile(String name, String superclass, String... interfaces) {
        return ClassFile.of().build(ClassDesc.of(name), builder -> {
            builder.withVersion(69, 0).withFlags(ClassFile.ACC_PUBLIC).withSuperclass(ClassDesc.of(superclass));
            builder.withInterfaceSymbols(Arrays.stream(interfaces).map(ClassDesc::of).toList());
        });
    }

    /*
     * Class files that parse and then cannot be read. The ClassFile API follows a constant pool index when the
     * accessor that needs it is called, so each of these is accepted by ClassFile.parse and throws later.
     */

    /** Points the {@code super_class} of a class file at a constant pool entry it does not have. */
    static byte[] withSuperclassOutOfRange(byte[] classFile) {
        return withIndexOutOfRange(classFile, constantPoolOffset(classFile, 0) + 4);
    }

    /** Points the first of a class file's {@code interfaces} at a constant pool entry it does not have. */
    private static byte[] withFirstInterfaceOutOfRange(byte[] classFile) {
        int interfaces = constantPoolOffset(classFile, 0) + 6;
        assertNotEquals(0, ByteBuffer.wrap(classFile).getShort(interfaces), "the class implements nothing");
        return withIndexOutOfRange(classFile, interfaces + 2);
    }

    /** Points the name of the first {@code CONSTANT_Class} of a class file outside its constant pool. */
    private static byte[] withFirstClassEntryOutOfRange(byte[] classFile) {
        return withIndexOutOfRange(classFile, constantPoolOffset(classFile, 7) + 1);
    }

    /** Points the name of the first method of a class file at a constant pool entry it does not have. */
    private static byte[] withFirstMethodNameOutOfRange(byte[] classFile) {
        ByteBuffer buffer = ByteBuffer.wrap(classFile);
        int offset = constantPoolOffset(classFile, 0) + 6;
        offset += 2 + 2 * buffer.getShort(offset);
        int fields = buffer.getShort(offset);
        offset += 2;
        for (int field = 0; field < fields; field++) {
            int attributes = buffer.getShort(offset + 6);
            offset += 8;
            for (int attribute = 0; attribute < attributes; attribute++) {
                offset += 6 + buffer.getInt(offset + 2);
            }
        }
        assertNotEquals(0, buffer.getShort(offset), "the class declares no method");
        // methods_count, then the first method's access_flags and name_index.
        return withIndexOutOfRange(classFile, offset + 4);
    }

    private static byte[] withIndexOutOfRange(byte[] classFile, int offset) {
        byte[] broken = classFile.clone();
        ByteBuffer.wrap(broken).putShort(offset, (short) 0xFFFF);
        return broken;
    }

    /**
     * Walks the constant pool of a class file.
     *
     * @param classFile the class file
     * @param tag       a constant pool tag to stop at, or {@code 0} to walk the whole pool
     * @return where the first entry with that tag starts, or where {@code access_flags} starts
     */
    private static int constantPoolOffset(byte[] classFile, int tag) {
        ByteBuffer buffer = ByteBuffer.wrap(classFile);
        int count = buffer.getShort(8) & 0xFFFF;
        int offset = 10;
        for (int index = 1; index < count; index++) {
            int found = classFile[offset];
            if (found == tag) {
                return offset;
            }
            switch (found) {
                case 1 -> offset += 3 + (buffer.getShort(offset + 1) & 0xFFFF);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> offset += 5;
                case 5, 6 -> {
                    offset += 9;
                    index++;
                }
                case 7, 8, 16, 19, 20 -> offset += 3;
                case 15 -> offset += 4;
                default -> throw new AssertionError("constant pool tag " + found);
            }
        }
        assertEquals(0, tag, "the class file has no constant with that tag");
        return offset;
    }

    /** An in-memory layer that records what is read from it. */
    static final class MapLayer implements StaticServiceTableGenerator.Layer {

        private final String description;
        private final Map<String, byte[]> entries = new LinkedHashMap<>();
        private final List<String> names = new ArrayList<>();
        private final List<byte[]> contents = new ArrayList<>();
        private final List<String> read = new ArrayList<>();
        private String version;
        private boolean multiRelease;

        MapLayer(String description) {
            this.description = description;
        }

        MapLayer put(String name, byte[] content) {
            entries.put(name, content);
            return this;
        }

        /** Stores a second entry under a name the layer already has. */
        MapLayer duplicate(String name, byte[] content) {
            names.add(name);
            contents.add(content);
            return this;
        }

        MapLayer type(String name) {
            return put(name.replace('.', '/') + ".class", ClassFile.of().build(ClassDesc.of(name), builder ->
                    builder.withVersion(69, 0)
                            .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT)));
        }

        MapLayer implementation(String name, String... interfaces) {
            return put(name.replace('.', '/') + ".class", classFile(name, "java.lang.Object", interfaces));
        }

        MapLayer version(String value) {
            this.version = value;
            return this;
        }

        MapLayer asMultiRelease() {
            this.multiRelease = true;
            return this;
        }

        private List<String> allNames() {
            List<String> all = new ArrayList<>(entries.keySet());
            all.addAll(names);
            return all;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public int size() {
            return entries.size() + names.size();
        }

        @Override
        public String name(int position) {
            return allNames().get(position);
        }

        @Override
        public byte[] read(int position) {
            String name = allNames().get(position);
            read.add(name);
            return position < entries.size() ? entries.get(name) : contents.get(position - entries.size());
        }

        @Override
        public String implementationVersion() {
            return version;
        }

        @Override
        public boolean multiRelease() {
            return multiRelease;
        }
    }

    /** A generated table, read back from the one class that holds it. */
    static final class Table {

        private final Map<String, int[]> types = new TreeMap<>();
        private final List<String> slots = new ArrayList<>();
        private int chunks;

        static Table of(StaticServiceTableGenerator.Result result) {
            assertTrue(result.generated(), result.reason());
            return of(result.entries().get("io/micronaut/runner/generated/services/RunnerServiceTable.class"));
        }

        static Table of(byte[] tableClass) {
            assertNotNull(tableClass, "the result has no table class");
            Class<?> type = new ClassLoader(null) {
                Class<?> define() {
                    return defineClass(null, tableClass, 0, tableClass.length);
                }
            }.define();
            Table table = new Table();
            try {
                assertEquals("io.micronaut.runner.generated.services.RunnerServiceTable", type.getName());
                table.chunks = type.getField("NAME_CHUNKS").getInt(null);
                for (int chunk = 0; chunk < table.chunks; chunk++) {
                    String names = (String) type.getMethod("names", int.class).invoke(null, chunk);
                    table.slots.addAll(List.of(names.split("\n", -1)));
                }
                assertEquals(type.getField("SLOT_COUNT").getInt(null), table.slots.size());
                String lines = (String) type.getField("TYPES").get(null);
                if (!lines.isEmpty()) {
                    for (String line : lines.split("\n", -1)) {
                        String[] fields = line.split("\t", -1);
                        assertEquals(4, fields.length, line);
                        table.types.put(fields[0], new int[] {Integer.parseInt(fields[1]),
                            Integer.parseInt(fields[2]), Integer.parseInt(fields[3])});
                    }
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            return table;
        }

        List<String> names(String type) {
            int[] line = types.get(type);
            assertNotNull(line, "the table does not list " + type + ": " + types.keySet());
            assertFalse(dynamic(type), type + " is dynamic");
            return slots.subList(line[0], line[0] + line[1]);
        }

        int flags(String type) {
            return types.get(type)[2];
        }

        boolean dynamic(String type) {
            int[] line = types.get(type);
            assertNotNull(line, "the table does not list " + type + ": " + types.keySet());
            return (line[2] & StaticServiceTableGenerator.FLAG_DYNAMIC) != 0;
        }
    }
}
