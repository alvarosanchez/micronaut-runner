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
package io.micronaut.runner.benchmarks;

import io.micronaut.runner.IndexFormat;
import io.micronaut.runner.build.ArchiveReads;
import io.micronaut.runner.build.Compression;
import io.micronaut.runner.build.RunnerJarReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkEntryModeTest {

    private static final String GENERATED_ENTRY_STUB = "io.micronaut.runner.generated.AppEntry";

    private static final List<String> CORE_ROWS = List.of(
            "exploded-cp",
            "thin-jar",
            "shadow",
            "shadow-stored",
            "shadow-aot",
            "runner-stored",
            "runner-stored-aot",
            "runner-preserve",
            "runner-extracted",
            "runner-extracted-aot",
            "shadow-maot",
            "runner-maot");

    /** The opt-in rows, in the order they follow {@code runner-stored-aot}. */
    private static final List<String> OPT_IN_ROWS = List.of(
            "runner-stored-reflection",
            "runner-stored-preload",
            "runner-stored-preload-aot",
            "runner-stored-positional",
            "runner-stored-positional-aot",
            "runner-stored-joran",
            "runner-stored-joran-aot",
            "runner-stored-keepdebug",
            "runner-stored-keepdebug-aot",
            "runner-stored-dynamic-services",
            "runner-stored-dynamic-services-aot",
            "runner-stored-lambdas",
            "runner-stored-lambdas-aot",
            "runner-extracted-lambdas-aot",
            "runner-stored-prefetch",
            "runner-stored-prefetch-aot");

    /**
     * The table pins the core rows and, row by row, the entry mode a row asks for, whether it launches with a JDK
     * AOT cache and the row it is built from.
     */
    @Test
    void matrixNamesPluginDefaults() {
        assertEquals(CORE_ROWS, SampleBuild.variantNames());
        assertTrue(SampleBuild.variantNames().stream().noneMatch(name -> name.endsWith("-reflection")));
        for (String name : SampleBuild.allVariantNames()) {
            SampleBuild.VariantSpec spec = SampleBuild.spec(name);
            EntryMode expected = name.endsWith("-reflection") ? EntryMode.REFLECTION
                    : name.startsWith("runner-") && !name.startsWith("runner-extracted") ? EntryMode.STUB
                    : EntryMode.STANDARD_LOADER;
            assertEquals(expected, spec.entryMode(), name);
            assertEquals(name.endsWith("-aot"), spec.aotCache(), name);
            assertEquals(!CORE_ROWS.contains(name), spec.optIn(), name);
            if (spec.aotCache() && !name.equals("runner-extracted-lambdas-aot")) {
                assertEquals(name.substring(0, name.length() - "-aot".length()), spec.source(), name);
            }
        }
        assertEquals("shadow", SampleBuild.spec("shadow-aot").source());
        assertEquals("runner-stored", SampleBuild.spec("runner-extracted").source());
        assertEquals("runner-stored", SampleBuild.spec("runner-stored-preload").source());
        assertEquals("runner-stored-lambdas", SampleBuild.spec("runner-extracted-lambdas-aot").source());
        assertNull(SampleBuild.spec("runner-stored").source());
    }

    @Test
    void sharedBuildFailureSchedulesTheCoreRowsAndOptInRowsOnlyOnRequest() {
        List<String> core = SampleBuild.unavailableVariants("sample build failed", SampleBuild.variantNames())
                .stream()
                .map(Variant::name).toList();
        List<String> withOptIn = SampleBuild.unavailableVariants("sample build failed",
                SampleBuild.allVariantNames()).stream()
                .map(Variant::name).toList();

        assertEquals(CORE_ROWS, core);
        assertTrue(core.stream().noneMatch(name -> name.endsWith("-reflection")), core.toString());

        List<String> expected = new ArrayList<>(CORE_ROWS);
        expected.addAll(expected.indexOf("runner-stored-aot") + 1, OPT_IN_ROWS);
        // Each cached Micronaut AOT row is opt-in and directly follows the row it caches.
        expected.add(expected.indexOf("shadow-maot") + 1, "shadow-maot-aot");
        expected.add(expected.indexOf("runner-maot") + 1, "runner-maot-aot");
        assertEquals(expected, withOptIn);
        assertEquals(CORE_ROWS.size() + OPT_IN_ROWS.size() + 2, withOptIn.size());
        assertTrue(core.stream().noneMatch(name -> name.endsWith("-maot-aot")), core.toString());
        assertTrue(core.stream().noneMatch(name -> name.contains("joran")), core.toString());
        assertEquals(List.of("runner-stored-preload", "runner-stored-preload-aot"),
                withOptIn.subList(withOptIn.indexOf("runner-stored-reflection") + 1,
                        withOptIn.indexOf("runner-stored-reflection") + 3),
                "both preload rows come right after the reflection row");
        assertEquals(List.of("runner-stored-lambdas", "runner-stored-lambdas-aot", "runner-extracted-lambdas-aot"),
                withOptIn.stream().filter(name -> name.contains("lambdas")).toList(),
                "the lambda controls, in this order, after the runner-stored group");
        assertTrue(withOptIn.indexOf("runner-stored-lambdas") > withOptIn.indexOf("runner-stored-dynamic-services-aot")
                && withOptIn.indexOf("runner-extracted-lambdas-aot") < withOptIn.indexOf("runner-preserve"));
        assertEquals(List.of("runner-stored-prefetch", "runner-stored-prefetch-aot"),
                withOptIn.subList(withOptIn.indexOf("runner-extracted-lambdas-aot") + 1,
                        withOptIn.indexOf("runner-preserve")),
                "the prefetch candidates close the runner-stored group");
        assertTrue(core.stream().noneMatch(OPT_IN_ROWS::contains), "opt-in rows never gate: " + core);
    }

    @Test
    void theMicronautAotRowsAreComparedLikeForLikeAndWithThePlainRows() {
        List<SampleBuild.ComparisonSpec> specs = SampleBuild.comparisons();
        // Rows added later append their own comparisons after these five.
        int first = specs.indexOf(new SampleBuild.ComparisonSpec("runner-maot", "shadow-maot",
                "Runner vs Micronaut AOT Shadow"));
        assertTrue(first >= 0, specs.toString());
        assertEquals(List.of(
                new SampleBuild.ComparisonSpec("runner-maot", "shadow-maot", "Runner vs Micronaut AOT Shadow"),
                new SampleBuild.ComparisonSpec("runner-stored", "shadow-maot",
                        "Runner without Micronaut AOT vs Micronaut AOT Shadow"),
                new SampleBuild.ComparisonSpec("runner-maot", "runner-stored", "Micronaut AOT's gain on Runner"),
                new SampleBuild.ComparisonSpec("shadow-maot", "shadow", "Micronaut AOT's gain on Shadow"),
                new SampleBuild.ComparisonSpec("runner-maot-aot", "shadow-maot-aot",
                        "Runner vs Micronaut AOT Shadow, both with a JDK AOT cache")),
                specs.subList(first, first + 5));
    }

    @Test
    void theMicronautAotRunnerRowPackagesACopyOfTheOptimizedJarAsItsApplicationLayer(@TempDir Path output)
            throws Exception {
        Path classes = compile(output.resolve("maot"), "fixture.MaotMain", """
                package fixture;
                public final class MaotMain {
                    public static void main(String[] args) { }
                }
                """);
        // What optimizedJitJar is to the harness: the application as one jar, with no logback.xml.
        Path build = Files.createDirectories(output.resolve("sample/build/libs"));
        Path optimizedJitJar = build.resolve("sample-0.1-jit.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(optimizedJitJar))) {
            jar.putNextEntry(new ZipEntry("fixture/MaotMain.class"));
            jar.write(Files.readAllBytes(classes.resolve("fixture/MaotMain.class")));
            jar.closeEntry();
        }
        Path artifacts = Files.createDirectories(output.resolve("artifacts"));
        Path core = micronautCoreLookalike(output.resolve("core"), "5.1.15");

        Variant maot = SampleBuild.optimizedRunnerJar(artifacts, SampleBuild.spec("runner-maot"), "fixture.MaotMain",
                optimizedJitJar, List.of(core));

        // What every Runner row says about its static service table.
        assertEquals("static services: 1 slots (core 5.1.15)", maot.buildNote());
        assertEquals(artifacts.resolve("runner-maot.jar"), maot.artifact());
        assertEquals(EntryMode.STUB, maot.requestedEntryMode());
        assertEquals(EntryMode.STUB, maot.effectiveEntryMode());
        assertRunnerIndex(maot.artifact(), true);
        assertTrue(entryNames(maot.artifact()).contains("MICRONAUT-INF/classes/fixture/MaotMain.class"));
        assertEquals(LaunchInputs.PINNED_MODIFICATION_TIME, Files.getLastModifiedTime(maot.artifact()));
        // The application layer was read from a copy inside the artifacts directory, never from the sample.
        assertTrue(Files.isRegularFile(artifacts.resolve("runner-maot-application/sample-0.1-jit.jar")));

        IOException noTask = assertThrows(IOException.class, () -> SampleBuild.optimizedRunnerJar(artifacts,
                SampleBuild.spec("runner-maot"), "fixture.MaotMain", null, List.of()));
        assertEquals("The sample's build declares no optimizedJitJar task (it does not apply io.micronaut.aot)",
                noTask.getMessage());
    }

    @Test
    void thePositionalRowsAreComparedWithTheMappedRowsAndWithShadow() {
        List<String> pairs = SampleBuild.comparisons().stream()
                .map(spec -> spec.candidate() + " - " + spec.baseline())
                .toList();
        assertTrue(pairs.containsAll(List.of(
                "runner-stored-positional - runner-stored",
                "runner-stored-positional-aot - runner-stored-aot",
                "runner-stored-positional - shadow",
                "runner-stored-positional-aot - shadow-aot")), pairs.toString());
    }

    @Test
    void thePreloadRowsAreComparedWithTheListFreeRowsAndWithShadow() {
        List<String> pairs = SampleBuild.comparisons().stream()
                .map(spec -> spec.candidate() + " - " + spec.baseline())
                .toList();
        // Rows added later append their own comparisons after these three.
        int first = pairs.indexOf("runner-stored-preload - runner-stored");
        assertTrue(first >= 0, pairs.toString());
        assertEquals(List.of(
                "runner-stored-preload - runner-stored",
                "runner-stored-preload - shadow",
                "runner-stored-preload-aot - runner-stored-aot"), pairs.subList(first, first + 3),
                "the three preload comparisons follow each other in the list");
        // Rows added later append their own comparisons after these three.
        int lambdas = pairs.indexOf("runner-stored - runner-stored-lambdas");
        assertTrue(lambdas > first, pairs::toString);
        assertEquals(List.of(
                "runner-stored - runner-stored-lambdas",
                "runner-stored-aot - runner-stored-lambdas-aot",
                "runner-extracted-aot - runner-extracted-lambdas-aot"), pairs.subList(lambdas, lambdas + 3),
                "and the three lambda comparisons follow each other later");
        assertTrue(first > pairs.indexOf("runner-stored-aot - runner-stored-keepdebug-aot"), pairs::toString);
    }

    @Test
    void aPreloadRowPackagesTheStartupClassesAndFailsWhenNoneIsEmbedded(@TempDir Path output) throws Exception {
        Path classes = compile(output.resolve("preload"), "fixture.PreloadMain", """
                package fixture;
                public final class PreloadMain {
                    public static void main(String[] args) { }
                }
                """);
        Path list = Files.writeString(output.resolve("startup-classes.log"), """
                [0.010s][info][class,load] java.lang.Object source: shared objects file
                [0.050s][info][class,load] fixture.PreloadMain source: jar:file:/work/app.jar!/MICRONAUT-INF/classes/
                """);
        Path cached = Files.writeString(output.resolve("cached.log"), """
                [0.050s][info][class,load] fixture.PreloadMain source: shared objects file
                """);

        Variant preload = runnerJar(output, "runner-stored-preload", "fixture.PreloadMain",
                List.of(classes), List.of(), Compression.STORED, EntryMode.STUB,
                SampleBuild.RunnerJarOptions.DEFAULTS.withStartupClasses(list));
        Variant plain = runnerJar(output, "runner-stored", "fixture.PreloadMain",
                List.of(classes), List.of(), Compression.STORED, EntryMode.STUB);

        assertTrue(preload.buildNote().endsWith("; 1 recorded startup classes preloaded"), preload.buildNote());
        try (RunnerJarReader reader = RunnerJarReader.open(preload.artifact())) {
            assertEquals(1, reader.index().preloadCount());
        }
        try (RunnerJarReader reader = RunnerJarReader.open(plain.artifact())) {
            assertEquals(0, reader.index().preloadCount(), "a row that sets no list preloads nothing");
        }
        IOException failure = assertThrows(IOException.class, () -> runnerJar(output,
                "runner-stored-preload", "fixture.PreloadMain", List.of(classes), List.of(), Compression.STORED,
                EntryMode.STUB, SampleBuild.RunnerJarOptions.DEFAULTS.withStartupClasses(cached)));
        assertTrue(failure.getMessage().contains("embeds no class"), failure.getMessage());
    }

    @Test
    void theStaticServiceTableIsComparedWithTheScanWithAndWithoutTheAotCache() {
        List<String> pairs = SampleBuild.comparisons().stream()
                .map(spec -> spec.candidate() + " - " + spec.baseline())
                .toList();
        assertTrue(pairs.containsAll(List.of(
                "runner-stored - runner-stored-dynamic-services",
                "runner-stored-aot - runner-stored-dynamic-services-aot")), pairs.toString());
    }

    @Test
    void everyRunnerRowSaysWhetherItsJarCarriesAStaticServiceTable(@TempDir Path output) throws Exception {
        Path classes = compile(output.resolve("services"), "fixture.ServicesMain", """
                package fixture;
                public final class ServicesMain {
                    public static void main(String[] args) { }
                }
                """);
        Path core = micronautCoreLookalike(output.resolve("core"), "5.1.15");

        Variant table = runnerJar(output, "runner-stored", "fixture.ServicesMain",
                List.of(classes), List.of(core), Compression.STORED, EntryMode.STUB);
        Variant dynamic = runnerJar(output, "runner-stored-dynamic-services", "fixture.ServicesMain",
                List.of(classes), List.of(core), Compression.STORED, EntryMode.STUB,
                SampleBuild.RunnerJarOptions.DEFAULTS.withStaticServices(false));
        Variant noCore = runnerJar(output, "runner-preserve", "fixture.ServicesMain",
                List.of(classes), List.of(), Compression.PRESERVE, EntryMode.STUB);

        // The one slot is the table's own registration, which its closed world includes.
        assertEquals("static services: 1 slots (core 5.1.15)", table.buildNote());
        assertTrue(table.description().endsWith("; static services: 1 slots (core 5.1.15)"), table.description());
        assertEquals("dynamic service scan", dynamic.buildNote());
        assertEquals("dynamic service scan", noCore.buildNote(), "a table that stood down shows in the report");
        String tableEntry = IndexFormat.CLASSES_PREFIX + "io/micronaut/runner/generated/services/RunnerServiceTable.class";
        try (JarFile jar = new JarFile(table.artifact().toFile())) {
            assertTrue(jar.getEntry(tableEntry) != null);
        }
        try (JarFile jar = new JarFile(dynamic.artifact().toFile())) {
            assertNull(jar.getEntry(tableEntry));
        }

        Variant extracted = SampleBuild.extractedRunner(output, table, SampleBuild.spec("runner-extracted"));
        assertEquals(table.buildNote(), extracted.buildNote(),
                "the extracted layout carries the table of the jar it was extracted from");
    }

    /**
     * A jar that looks like micronaut-core to the static service table generator: the hook classes with the
     * members the table is compiled against, and a manifest that states a version.
     */
    private static Path micronautCoreLookalike(Path fixture, String version) throws IOException {
        Path classes = compile(fixture, "io.micronaut.core.io.service.SoftServiceLoader", """
                package io.micronaut.core.io.service;

                import java.util.List;
                import java.util.Map;
                import java.util.function.Predicate;
                import java.util.function.Supplier;
                import java.util.stream.Stream;

                public final class SoftServiceLoader {
                    public interface StaticServiceLoader<S> {
                        Stream<StaticDefinition<S>> findAll(Predicate<String> predicate);
                        List<S> load(Predicate<S> predicate);
                        List<S> load(Predicate<String> condition, Predicate<S> predicate);
                    }

                    public static final class StaticDefinition<S> {
                        public static <S> StaticDefinition<S> of(String name, Class<S> value) {
                            return null;
                        }

                        public static <S> StaticDefinition<S> of(String name, Supplier<S> value) {
                            return null;
                        }

                        public S load() {
                            return null;
                        }
                    }

                    public static final class Optimizations {
                        public Optimizations(Map<String, StaticServiceLoader<?>> serviceLoaders) {
                        }
                    }
                }
                """);
        compile(fixture, "io.micronaut.core.optim.StaticOptimizations", """
                package io.micronaut.core.optim;

                public abstract class StaticOptimizations {
                    public interface Loader<T> {
                        T load();
                    }
                }
                """);
        Path jar = fixture.resolve("micronaut-core-" + version + ".jar");
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Implementation-Version", version);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
             var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new java.util.zip.ZipEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void aPositionalRowPackagesAnIndexThatAsksForPositionalReads(@TempDir Path output) throws Exception {
        Path classes = compile(output.resolve("positional"), "fixture.PositionalMain", """
                package fixture;
                public final class PositionalMain {
                    public static void main(String[] args) { }
                }
                """);

        Variant positional = runnerJar(output, "runner-stored-positional", "fixture.PositionalMain",
                List.of(classes), List.of(), Compression.STORED, EntryMode.STUB,
                SampleBuild.RunnerJarOptions.DEFAULTS.withArchiveReads(ArchiveReads.POSITIONAL));
        Variant mapped = runnerJar(output, "runner-stored", "fixture.PositionalMain",
                List.of(classes), List.of(), Compression.STORED, EntryMode.STUB);

        try (RunnerJarReader reader = RunnerJarReader.open(positional.artifact())) {
            assertTrue(reader.index().positionalReads());
            assertTrue(reader.index().largestStoredClass() > 0);
        }
        try (RunnerJarReader reader = RunnerJarReader.open(mapped.artifact())) {
            assertFalse(reader.index().positionalReads(), "a row that sets nothing follows the builder default");
        }
    }

    @Test
    void theJoranRowsAreComparedAsControlsOfThePrecompiledRows() {
        List<String> pairs = SampleBuild.comparisons().stream()
                .map(spec -> spec.candidate() + " - " + spec.baseline())
                .toList();
        assertTrue(pairs.containsAll(List.of(
                "runner-stored - runner-stored-joran",
                "runner-stored-aot - runner-stored-joran-aot")), pairs.toString());
    }

    @Test
    void aJoranControlRowSetsItsOptionOnTheSharedRecordAndIsPinnedLikeEveryLaunchInput(@TempDir Path output)
            throws Exception {
        Path classes = compile(output.resolve("joran"), "fixture.JoranMain", """
                package fixture;
                public final class JoranMain {
                    public static void main(String[] args) { }
                }
                """);
        SampleBuild.RunnerJarOptions control = SampleBuild.RunnerJarOptions.DEFAULTS.withPrecompileLogback(false);
        assertNull(control.archiveReads(), "the control leaves the archive read mode at the builder default");
        assertNull(SampleBuild.RunnerJarOptions.DEFAULTS.precompileLogback());
        assertEquals(Boolean.FALSE, control.withArchiveReads(ArchiveReads.POSITIONAL).precompileLogback(),
                "one option does not reset the other");
        assertEquals(ArchiveReads.POSITIONAL, SampleBuild.RunnerJarOptions.DEFAULTS
                .withArchiveReads(ArchiveReads.POSITIONAL).withPrecompileLogback(false).archiveReads());

        // A fixture the precompiler really compiles: a logback.xml and the real Logback and SLF4J jars. Without
        // them no archive carries a configurator whatever the option says, and the control proves nothing.
        Files.writeString(classes.resolve("logback.xml"), """
                <configuration>
                    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                        <encoder>
                            <pattern>%msg%n</pattern>
                        </encoder>
                    </appender>
                    <root level="WARN">
                        <appender-ref ref="STDOUT"/>
                    </root>
                </configuration>
                """, StandardCharsets.UTF_8);
        List<Path> logback = logbackFixture();

        Variant precompiled = runnerJar(output, "runner-stored", "fixture.JoranMain",
                List.of(classes), logback, Compression.STORED, EntryMode.STUB);
        Variant joran = runnerJar(output, "runner-stored-joran", "fixture.JoranMain",
                List.of(classes), logback, Compression.STORED, EntryMode.STUB, control);

        assertTrue(SampleBuild.logbackPrecompiled(precompiled.artifact()),
                "the builder default precompiles this fixture, so the control has something to differ from");
        assertFalse(SampleBuild.logbackPrecompiled(joran.artifact()),
                "the control's option reached the packaging library");
        assertEquals(LaunchInputs.PINNED_MODIFICATION_TIME, Files.getLastModifiedTime(joran.artifact()),
                "a trained cache of the -aot control stays valid across runs only if the jar's time is pinned");
        try (RunnerJarReader reader = RunnerJarReader.open(joran.artifact())) {
            assertFalse(reader.index().positionalReads());
        }
    }

    @Test
    void reportsRequestedAndEffectiveEntryModes(@TempDir Path output) throws Exception {
        List<String> names = List.of("runner-stored", "runner-stored-aot", "runner-stored-reflection",
                "runner-extracted");
        Reports.write(output, BenchmarkFixtures.context(output, 1, names, CompletenessPolicy.REQUIRED,
                BenchmarkProvenance.unavailable()), SampleBuild.unavailableVariants("not built", names).stream()
                .map(BenchmarkFixtures::result).toList(), List.of());

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"requestedEntryMode\": \"stub\""));
        assertTrue(json.contains("\"applicationCacheMode\": \"aot\""));
        assertTrue(json.contains("\"effectiveEntryMode\": null"));
        assertTrue(json.contains("\"requestedEntryMode\": \"reflection\""));
        assertTrue(json.contains("\"requestedEntryMode\": \"standard-loader\""));

        String markdown = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("| Variant | Requested entry | Effective entry | Packaging |"));
        for (String row : List.of("`runner-stored` | stub", "`runner-stored-aot` | stub",
                "`runner-stored-reflection` | reflection", "`runner-extracted` | standard-loader")) {
            String name = row.substring(1, row.indexOf('`', 1));
            assertTrue(markdown.contains("| " + row + " | unavailable | " + SampleBuild.spec(name).description()
                    + " |"), row);
        }
    }

    @Test
    void preparesVerifiedStubAndReflectionPairsForBothCompressionModes(@TempDir Path output) throws Exception {
        Path classes = compile(output.resolve("eligible"), "fixture.EligibleMain", """
                package fixture;
                public final class EligibleMain {
                    public static void main(String[] args) { }
                }
                """);

        for (Compression compression : Compression.values()) {
            String suffix = compression.name().toLowerCase(java.util.Locale.ROOT);
            Variant stub = runnerJar(output, "runner-" + suffix,
                    "fixture.EligibleMain", List.of(classes), List.of(), compression, EntryMode.STUB);
            Variant reflection = runnerJar(output, "runner-" + suffix + "-reflection",
                    "fixture.EligibleMain", List.of(classes), List.of(), compression, EntryMode.REFLECTION);

            assertEquals(EntryMode.STUB, stub.requestedEntryMode());
            assertEquals(EntryMode.STUB, stub.effectiveEntryMode());
            assertEquals(EntryMode.REFLECTION, reflection.requestedEntryMode());
            assertEquals(EntryMode.REFLECTION, reflection.effectiveEntryMode());
            assertRunnerIndex(stub.artifact(), true);
            assertRunnerIndex(reflection.artifact(), false);
            assertSameApplicationInputs(stub.artifact(), reflection.artifact());
        }
    }

    @Test
    void theLocalVariableTableControlKeepsTheTablesTheDefaultStrips(@TempDir Path output) throws Exception {
        Path classes = compile(output.resolve("eligible"), "fixture.EligibleMain", """
                package fixture;
                public final class EligibleMain {
                    public static void main(String[] args) {
                        int unused = args.length;
                    }
                }
                """);
        Path library = output.resolve("library.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(library))) {
            jar.putNextEntry(new ZipEntry("fixture/EligibleMain.class"));
            jar.write(Files.readAllBytes(classes.resolve("fixture/EligibleMain.class")));
            jar.closeEntry();
        }

        Variant stripped = runnerJar(output, "runner-stored", "fixture.EligibleMain",
                List.of(classes), List.of(library), Compression.STORED, EntryMode.STUB);
        Variant kept = runnerJar(output, "runner-stored-keepdebug", "fixture.EligibleMain",
                List.of(classes), List.of(library), Compression.STORED, EntryMode.STUB,
                SampleBuild.RunnerJarOptions.DEFAULTS.withStripLocalVariables(false));

        assertNull(SampleBuild.RunnerJarOptions.DEFAULTS.stripLocalVariables(),
                "a row that sets nothing follows the builder default");
        assertTrue(entryNames(stripped.artifact()).contains("MICRONAUT-INF/transforms.txt"),
                "the default strips the -g compiled dependency");
        assertFalse(entryNames(kept.artifact()).contains("MICRONAUT-INF/transforms.txt"));
        assertTrue(SampleBuild.comparisons().contains(new SampleBuild.ComparisonSpec("runner-stored",
                "runner-stored-keepdebug", "Local-variable tables stripped vs kept")));
        assertTrue(SampleBuild.comparisons().stream().anyMatch(spec -> spec.candidate().equals("runner-stored-aot")
                && spec.baseline().equals("runner-stored-keepdebug-aot")));
    }

    @Test
    void theLambdaControlKeepsTheCallSitesTheDefaultDesugars(@TempDir Path output) throws Exception {
        Path classes = compile(output.resolve("lambdas"), "fixture.LambdaMain", """
                package fixture;
                public final class LambdaMain {
                    public static void main(String[] args) {
                        Runnable greeting = () -> System.out.println("hello");
                        greeting.run();
                    }
                }
                """);
        Path library = output.resolve("library.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(library))) {
            jar.putNextEntry(new ZipEntry("fixture/lib/Unused.txt"));
            jar.closeEntry();
        }

        Variant desugared = runnerJar(output, "runner-stored", "fixture.LambdaMain",
                List.of(classes), List.of(library), Compression.STORED, EntryMode.STUB);
        Variant kept = runnerJar(output, "runner-stored-lambdas", "fixture.LambdaMain",
                List.of(classes), List.of(library), Compression.STORED, EntryMode.STUB,
                SampleBuild.RunnerJarOptions.DEFAULTS.withDesugarLambdas(false));

        assertNull(SampleBuild.RunnerJarOptions.DEFAULTS.desugarLambdas(),
                "a row that sets nothing follows the builder default");
        assertTrue(entryNames(desugared.artifact()).contains("MICRONAUT-INF/classes/fixture/LambdaMain$$Lambda$R0.class"),
                "the default desugars the application's lambda");
        assertTrue(entryNames(kept.artifact()).stream().noneMatch(name -> name.contains("$$Lambda$R")));
        assertEquals(EntryMode.STUB, kept.effectiveEntryMode());
        List<SampleBuild.ComparisonSpec> comparisons = SampleBuild.comparisons();
        assertTrue(comparisons.contains(new SampleBuild.ComparisonSpec("runner-stored", "runner-stored-lambdas",
                "Lambdas desugared vs kept")));
        assertTrue(comparisons.stream().anyMatch(spec -> spec.candidate().equals("runner-stored-aot")
                && spec.baseline().equals("runner-stored-lambdas-aot")));
        assertTrue(comparisons.stream().anyMatch(spec -> spec.candidate().equals("runner-extracted-aot")
                && spec.baseline().equals("runner-extracted-lambdas-aot")));
    }

    @Test
    void thePrefetchRowsAreComparedAsCandidatesAgainstTheDefaultRows() {
        List<String> pairs = SampleBuild.comparisons().stream()
                .map(spec -> spec.candidate() + " - " + spec.baseline())
                .toList();
        assertEquals(List.of("runner-stored-prefetch - runner-stored",
                "runner-stored-prefetch-aot - runner-stored-aot"), pairs.subList(pairs.size() - 2, pairs.size()),
                "a row added to the matrix appends its comparisons");
    }

    /**
     * The candidate packages the prefetch into an archive the default leaves it out of. The fixture is one the
     * packager really packages the prefetch for: a class path with a bean definition reference and classes shaped
     * like micronaut-inject 5's, which the packager parses and never loads. Without them no archive carries the
     * prefetch whatever the option says, and the candidate would be the default archive under another name.
     */
    @Test
    void thePrefetchCandidatePackagesThePrefetchTheDefaultLeavesOut(@TempDir Path output) throws Exception {
        Path classes = compile(output.resolve("prefetch"), "fixture.PrefetchMain", """
                package fixture;
                public final class PrefetchMain {
                    public static void main(String[] args) { }
                }
                """);
        Path reference = classes.resolve(
                "META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/fixture.$PrefetchMain$Definition");
        Files.createDirectories(reference.getParent());
        Files.createFile(reference);
        Path micronaut = compile(output.resolve("micronaut"), "io.micronaut.context.DefaultBeanDefinitionsProvider",
                """
                package io.micronaut.context;
                public final class DefaultBeanDefinitionsProvider implements BeanDefinitionsProvider {
                    public java.util.List<Object> provide(ClassLoader classLoader) {
                        return java.util.List.of();
                    }
                }
                interface BeanDefinitionsProvider {
                    java.util.List<Object> provide(ClassLoader classLoader);
                }
                interface ApplicationContextBuilder {
                    default ApplicationContextBuilder beanDefinitionsProvider(BeanDefinitionsProvider provider) {
                        return this;
                    }
                }
                interface BeanContextConfiguration {
                    default BeanDefinitionsProvider getBeanDefinitionsProvider() {
                        return null;
                    }
                }
                """);
        Path library = output.resolve("micronaut-inject-shaped.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(library));
             var files = Files.walk(micronaut)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                jar.putNextEntry(new ZipEntry(micronaut.relativize(file).toString().replace(File.separatorChar, '/')));
                jar.write(Files.readAllBytes(file));
                jar.closeEntry();
            }
        }
        SampleBuild.RunnerJarOptions candidate = SampleBuild.RunnerJarOptions.DEFAULTS.withDefinitionPrefetch(true);
        assertNull(SampleBuild.RunnerJarOptions.DEFAULTS.definitionPrefetch(),
                "a row that sets nothing follows the builder default");
        assertNull(candidate.precompileLogback());
        assertEquals(Boolean.TRUE, candidate.withArchiveReads(ArchiveReads.POSITIONAL).withPrecompileLogback(false)
                .withStripLocalVariables(false).definitionPrefetch(), "one option does not reset the other");
        assertEquals(Boolean.FALSE, SampleBuild.RunnerJarOptions.DEFAULTS.withStripLocalVariables(false)
                .withDefinitionPrefetch(true).stripLocalVariables());

        Variant left = runnerJar(output, "runner-stored", "fixture.PrefetchMain",
                List.of(classes), List.of(library), Compression.STORED, EntryMode.STUB);
        Variant packaged = runnerJar(output, "runner-stored-prefetch", "fixture.PrefetchMain",
                List.of(classes), List.of(library), Compression.STORED, EntryMode.STUB, candidate);

        assertFalse(SampleBuild.definitionPrefetch(left.artifact()),
                "the builder default leaves the prefetch out, so the candidate differs from it");
        assertTrue(SampleBuild.definitionPrefetch(packaged.artifact()),
                "the candidate's option reached the packaging library");
        assertEquals(EntryMode.STUB, packaged.effectiveEntryMode(), "the candidate keeps the entry stub");
        assertFalse(entryNames(left.artifact()).stream().anyMatch(name -> name.contains("generated/prefetch/")));
        assertTrue(entryNames(packaged.artifact()).contains(
                "MICRONAUT-INF/classes/io/micronaut/runner/generated/prefetch/DefinitionPrefetch.class"));
        assertEquals(LaunchInputs.PINNED_MODIFICATION_TIME, Files.getLastModifiedTime(packaged.artifact()),
                "a trained cache of the -aot candidate stays valid across runs only if the jar's time is pinned");
    }

    private static List<String> entryNames(Path artifact) throws IOException {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            return jar.stream().map(ZipEntry::getName).toList();
        }
    }

    @Test
    void requestedStubForIneligibleMainFailsBeforeMeasurement(@TempDir Path output) throws Exception {
        Path classes = compile(output.resolve("ineligible"), "fixture.IneligibleMain", """
                package fixture;
                public final class IneligibleMain {
                    public static int main(String[] args) { return 0; }
                }
                """);

        IOException failure = assertThrows(IOException.class, () -> runnerJar(output,
                "runner-stored", "fixture.IneligibleMain", List.of(classes), List.of(),
                Compression.STORED, EntryMode.STUB));

        assertTrue(failure.getMessage().contains("entry stub was requested"));
    }

    @Test
    void extractedVariantLaunchesTheActualApplicationJarAndManifestClasspathInOrder(@TempDir Path output)
            throws Exception {
        Path classes = compile(output.resolve("extracted-input"), "fixture.ExtractedMain", """
                package fixture;
                public final class ExtractedMain {
                    public static void main(String[] args) { }
                }
                """);
        Path dependencyOne = emptyJar(output.resolve("first dependency.jar"));
        Path dependencyTwo = emptyJar(output.resolve("second.jar"));
        Variant runner = runnerJar(output, "extract-source", "fixture.ExtractedMain",
                List.of(classes), List.of(dependencyOne, dependencyTwo), Compression.STORED, EntryMode.STUB);

        Variant extracted = SampleBuild.extractedRunner(output, runner, SampleBuild.spec("runner-extracted"));

        assertEquals(EntryMode.STANDARD_LOADER, extracted.effectiveEntryMode());
        assertEquals("-jar", extracted.command().get(1));
        assertEquals(extracted.launchInputs().get(0).toAbsolutePath().toString(), extracted.command().get(2));
        assertEquals(3, extracted.launchInputs().size());
        assertEquals("first dependency.jar", extracted.launchInputs().get(1).getFileName().toString());
        assertEquals("second.jar", extracted.launchInputs().get(2).getFileName().toString());
    }

    /** Packages a Runner jar for a fixture row of that name and entry mode, with the options or the defaults. */
    private static Variant runnerJar(Path output, String name, String mainClass, List<Path> classes,
                                     List<Path> dependencies, Compression compression, EntryMode entryMode,
                                     SampleBuild.RunnerJarOptions... options) throws IOException {
        return SampleBuild.runnerJar(output, new SampleBuild.VariantSpec(name, "fixture", entryMode, false, false,
                null), mainClass, classes, dependencies, compression,
                options.length == 0 ? SampleBuild.RunnerJarOptions.DEFAULTS : options[0]);
    }

    private static Path compile(Path fixture, String className, String source) throws IOException {

        Path sources = fixture.resolve("src");
        Path classes = fixture.resolve("classes");
        Path sourceFile = sources.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(classes);
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        // -g, so that a dependency built from a fixture carries local-variable tables.
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-g", "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, exit, "fixture compilation failed");
        return classes;
    }

    /** The logback-classic, logback-core and slf4j-api jars the build hands to this test as files. */
    private static List<Path> logbackFixture() {
        String path = System.getProperty("runner.benchmark.logbackFixture");
        assertNotNull(path, "run this test through Gradle, which sets runner.benchmark.logbackFixture to the"
                + " Logback and SLF4J jars of the fixture");
        List<Path> jars = Arrays.stream(path.split(File.pathSeparator)).map(Path::of).toList();
        assertEquals(3, jars.size(), path);
        return jars;
    }

    private static Path emptyJar(Path path) throws IOException {
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(path))) {
            return path;
        }
    }

    private static void assertRunnerIndex(Path artifact, boolean stubExpected) throws IOException {
        try (RunnerJarReader reader = RunnerJarReader.open(artifact)) {
            if (stubExpected) {
                assertEquals(GENERATED_ENTRY_STUB, reader.index().entryStubClass());
                assertTrue(reader.index().findClass(GENERATED_ENTRY_STUB) != IndexFormat.NO_INDEX);
            } else {
                assertNull(reader.index().entryStubClass());
                assertEquals(IndexFormat.NO_INDEX, reader.index().findClass(GENERATED_ENTRY_STUB));
            }
        }
    }

    private static void assertSameApplicationInputs(Path stub, Path reflection) throws IOException {
        Map<String, byte[]> stubEntries = payloadEntries(stub);
        Map<String, byte[]> reflectionEntries = payloadEntries(reflection);
        assertEquals(stubEntries.keySet(), reflectionEntries.keySet());
        for (String entry : stubEntries.keySet()) {
            assertArrayEquals(stubEntries.get(entry), reflectionEntries.get(entry), entry);
        }
    }

    private static Map<String, byte[]> payloadEntries(Path artifact) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            var enumeration = jar.entries();
            while (enumeration.hasMoreElements()) {
                var entry = enumeration.nextElement();
                if (entry.isDirectory() || entry.getName().equals(IndexFormat.INDEX_ENTRY_NAME)
                        || entry.getName().equals(IndexFormat.CLASSES_PREFIX
                        + GENERATED_ENTRY_STUB.replace('.', '/') + ".class")) {
                    continue;
                }
                try (var in = jar.getInputStream(entry)) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return entries;
    }
}
