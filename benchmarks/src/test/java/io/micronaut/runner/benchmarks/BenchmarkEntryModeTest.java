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
            "runner-stored-dynamic-services-aot");

    @Test
    void matrixNamesPluginDefaults() {
        assertEquals(CORE_ROWS, SampleBuild.variantNames());
        assertTrue(SampleBuild.variantNames().stream().noneMatch(name -> name.endsWith("-reflection")));
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
        assertEquals(EntryMode.STUB, EntryMode.requestedBy("runner-maot"));
        assertEquals(EntryMode.STUB, EntryMode.requestedBy("runner-maot-aot"));
        assertEquals(EntryMode.STANDARD_LOADER, EntryMode.requestedBy("shadow-maot"));
        assertEquals(EntryMode.STANDARD_LOADER, EntryMode.requestedBy("shadow-maot-aot"));
        assertTrue(core.stream().noneMatch(name -> name.contains("joran")), core.toString());
        assertTrue(SampleBuild.variantNames().stream().noneMatch(name -> name.contains("joran")));
        assertEquals(EntryMode.REFLECTION, EntryMode.requestedBy("runner-stored-reflection"));
        assertEquals(EntryMode.STUB, EntryMode.requestedBy("runner-stored-preload"));
        assertEquals(EntryMode.STUB, EntryMode.requestedBy("runner-stored-preload-aot"));
        assertEquals(List.of("runner-stored-preload", "runner-stored-preload-aot"),
                withOptIn.subList(withOptIn.indexOf("runner-stored-reflection") + 1,
                        withOptIn.indexOf("runner-stored-reflection") + 3),
                "both preload rows come right after the reflection row");
        assertEquals(EntryMode.STUB, EntryMode.requestedBy("runner-stored-positional"));
        assertEquals(EntryMode.STUB, EntryMode.requestedBy("runner-stored-positional-aot"));
        assertEquals(EntryMode.STUB, EntryMode.requestedBy("runner-stored-joran"));
        assertEquals(EntryMode.STUB, EntryMode.requestedBy("runner-stored-joran-aot"));
        assertEquals(EntryMode.STUB, EntryMode.requestedBy("runner-stored-keepdebug"));
        assertEquals(EntryMode.STUB, EntryMode.requestedBy("runner-stored-keepdebug-aot"));
        assertTrue(SampleBuild.variantNames().stream().noneMatch(name -> name.contains("keepdebug")));
        assertEquals(EntryMode.STUB, EntryMode.requestedBy("runner-stored-dynamic-services"));
        assertEquals(EntryMode.STUB, EntryMode.requestedBy("runner-stored-dynamic-services-aot"));
        assertEquals(EntryMode.STANDARD_LOADER, EntryMode.requestedBy("shadow-stored"));
        assertTrue(core.stream().noneMatch(OPT_IN_ROWS::contains), "opt-in rows never gate: " + core);
    }

    @Test
    void theMicronautAotRowsAreComparedLikeForLikeAndWithThePlainRows() {
        List<SampleBuild.ComparisonSpec> specs = SampleBuild.comparisons();
        assertEquals(List.of(
                new SampleBuild.ComparisonSpec("runner-maot", "shadow-maot", "Runner vs Micronaut AOT Shadow"),
                new SampleBuild.ComparisonSpec("runner-stored", "shadow-maot",
                        "Runner without Micronaut AOT vs Micronaut AOT Shadow"),
                new SampleBuild.ComparisonSpec("runner-maot", "runner-stored", "Micronaut AOT's gain on Runner"),
                new SampleBuild.ComparisonSpec("shadow-maot", "shadow", "Micronaut AOT's gain on Shadow"),
                new SampleBuild.ComparisonSpec("runner-maot-aot", "shadow-maot-aot",
                        "Runner vs Micronaut AOT Shadow, both with a JDK AOT cache")),
                specs.subList(specs.size() - 5, specs.size()));
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

        Variant maot = SampleBuild.optimizedRunnerJar(artifacts, "runner-maot", "fixture.MaotMain",
                optimizedJitJar, List.of(core));

        // Its fixed text, plus what every Runner row says about its static service table.
        assertEquals("Runner jar of the Micronaut AOT-optimized application (optimizedJitJar);"
                + " plugin-default entry stub; static services: 1 slots (core 5.1.15)", maot.description());
        assertEquals(artifacts.resolve("runner-maot.jar"), maot.artifact());
        assertEquals(EntryMode.STUB, maot.requestedEntryMode());
        assertEquals(EntryMode.STUB, maot.effectiveEntryMode());
        assertRunnerIndex(maot.artifact(), true);
        assertTrue(entryNames(maot.artifact()).contains("MICRONAUT-INF/classes/fixture/MaotMain.class"));
        assertEquals(LaunchInputs.PINNED_MODIFICATION_TIME, Files.getLastModifiedTime(maot.artifact()));
        // The application layer was read from a copy inside the artifacts directory, never from the sample.
        assertTrue(Files.isRegularFile(artifacts.resolve("runner-maot-application/sample-0.1-jit.jar")));

        IOException noTask = assertThrows(IOException.class, () -> SampleBuild.optimizedRunnerJar(artifacts,
                "runner-maot", "fixture.MaotMain", null, List.of()));
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

        Variant preload = SampleBuild.runnerJar(output, "runner-stored-preload", "fixture.PreloadMain",
                List.of(classes), List.of(), Compression.STORED, EntryMode.STUB,
                SampleBuild.RunnerJarOptions.DEFAULTS.withStartupClasses(list));
        Variant plain = SampleBuild.runnerJar(output, "runner-stored", "fixture.PreloadMain",
                List.of(classes), List.of(), Compression.STORED, EntryMode.STUB);

        assertTrue(preload.description().contains("1 recorded startup classes preloaded"), preload.description());
        try (RunnerJarReader reader = RunnerJarReader.open(preload.artifact())) {
            assertEquals(1, reader.index().preloadCount());
        }
        try (RunnerJarReader reader = RunnerJarReader.open(plain.artifact())) {
            assertEquals(0, reader.index().preloadCount(), "a row that sets no list preloads nothing");
        }
        IOException failure = assertThrows(IOException.class, () -> SampleBuild.runnerJar(output,
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

        Variant table = SampleBuild.runnerJar(output, "runner-stored", "fixture.ServicesMain",
                List.of(classes), List.of(core), Compression.STORED, EntryMode.STUB);
        Variant dynamic = SampleBuild.runnerJar(output, "runner-stored-dynamic-services", "fixture.ServicesMain",
                List.of(classes), List.of(core), Compression.STORED, EntryMode.STUB,
                SampleBuild.RunnerJarOptions.DEFAULTS.withStaticServices(false));
        Variant noCore = SampleBuild.runnerJar(output, "runner-preserve", "fixture.ServicesMain",
                List.of(classes), List.of(), Compression.PRESERVE, EntryMode.STUB);

        // The one slot is the table's own registration, which its closed world includes.
        assertTrue(table.description().endsWith("; static services: 1 slots (core 5.1.15)"), table.description());
        assertTrue(dynamic.description().endsWith("; dynamic service scan"), dynamic.description());
        assertTrue(noCore.description().endsWith("; dynamic service scan"),
                "a table that stood down shows in the report: " + noCore.description());
        String tableEntry = IndexFormat.CLASSES_PREFIX + "io/micronaut/runner/generated/services/RunnerServiceTable.class";
        try (JarFile jar = new JarFile(table.artifact().toFile())) {
            assertTrue(jar.getEntry(tableEntry) != null);
        }
        try (JarFile jar = new JarFile(dynamic.artifact().toFile())) {
            assertNull(jar.getEntry(tableEntry));
        }

        assertEquals("; static services: 1 slots (core 5.1.15)", SampleBuild.staticServicesNote(table.description()));
        assertEquals("; static services: 489 slots (core 5.1.15)", SampleBuild.staticServicesNote(
                "Runner jar; plugin-default entry stub; static services: 489 slots (core 5.1.15); verified JDK AOT cache"));
        assertEquals("; dynamic service scan", SampleBuild.staticServicesNote(dynamic.description()));
        assertEquals("", SampleBuild.staticServicesNote("Everything flattened into one jar by the Shadow plugin"));

        Variant extracted = SampleBuild.extractedRunner(output, table, "runner-extracted");
        assertTrue(extracted.description().endsWith("; static services: 1 slots (core 5.1.15)"),
                "the extracted layout carries the table of the jar it was extracted from: " + extracted.description());
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

        Variant positional = SampleBuild.runnerJar(output, "runner-stored-positional", "fixture.PositionalMain",
                List.of(classes), List.of(), Compression.STORED, EntryMode.STUB,
                SampleBuild.RunnerJarOptions.DEFAULTS.withArchiveReads(ArchiveReads.POSITIONAL));
        Variant mapped = SampleBuild.runnerJar(output, "runner-stored", "fixture.PositionalMain",
                List.of(classes), List.of(), Compression.STORED, EntryMode.STUB);

        assertTrue(positional.description().contains("archiveReads POSITIONAL"), positional.description());
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

        Variant precompiled = SampleBuild.runnerJar(output, "runner-stored", "fixture.JoranMain",
                List.of(classes), logback, Compression.STORED, EntryMode.STUB);
        Variant joran = SampleBuild.runnerJar(output, "runner-stored-joran", "fixture.JoranMain",
                List.of(classes), logback, Compression.STORED, EntryMode.STUB, control);

        assertTrue(SampleBuild.logbackPrecompiled(precompiled.artifact()),
                "the builder default precompiles this fixture, so the control has something to differ from");
        assertFalse(precompiled.description().contains("Joran"), precompiled.description());
        assertTrue(joran.description().contains("logback.xml left to Joran"), joran.description());
        assertFalse(joran.description().contains("archiveReads"), joran.description());
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
        List<Variant> variants = List.of(
                Variant.unavailable("runner-stored", "plugin-default fixture", "not built"),
                Variant.unavailable("runner-stored-aot", "AOT fixture", "not trained"),
                Variant.unavailable("runner-stored-reflection", "reflection fixture", "not built"),
                Variant.unavailable("runner-extracted", "standard loader fixture", "not built"));
        RunContext context = new RunContext(output, "file:/repo", "1.0", output,
                1, 0, 1, "/hello", false, "2026-09-22T00:00:00Z",
                variants.stream().map(Variant::name).toList(), CompletenessPolicy.REQUIRED);
        List<VariantResult> results = variants.stream()
                .map(variant -> new VariantResult(variant, -1, List.of(), null, null, null, List.of()))
                .toList();

        Reports.write(output, context, results, List.of());

        String json = Files.readString(output.resolve(Reports.RESULTS_FILE), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"requestedEntryMode\": \"stub\""));
        assertTrue(json.contains("\"applicationCacheMode\": \"aot\""));
        assertTrue(json.contains("\"effectiveEntryMode\": null"));
        assertTrue(json.contains("\"requestedEntryMode\": \"reflection\""));
        assertTrue(json.contains("\"requestedEntryMode\": \"standard-loader\""));

        String markdown = Files.readString(output.resolve(Reports.SUMMARY_FILE), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("| Variant | Requested entry | Effective entry | Packaging |"));
        assertTrue(markdown.contains("| `runner-stored` | stub | unavailable | plugin-default fixture |"));
        assertTrue(markdown.contains("| `runner-stored-aot` | stub | unavailable | AOT fixture |"));
        assertTrue(markdown.contains("| `runner-stored-reflection` | reflection | unavailable | reflection fixture |"));
        assertTrue(markdown.contains("| `runner-extracted` | standard-loader | unavailable | standard loader fixture |"));
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
            Variant stub = SampleBuild.runnerJar(output, "runner-" + suffix,
                    "fixture.EligibleMain", List.of(classes), List.of(), compression, EntryMode.STUB);
            Variant reflection = SampleBuild.runnerJar(output, "runner-" + suffix + "-reflection",
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

        Variant stripped = SampleBuild.runnerJar(output, "runner-stored", "fixture.EligibleMain",
                List.of(classes), List.of(library), Compression.STORED, EntryMode.STUB);
        Variant kept = SampleBuild.runnerJar(output, "runner-stored-keepdebug", "fixture.EligibleMain",
                List.of(classes), List.of(library), Compression.STORED, EntryMode.STUB,
                SampleBuild.RunnerJarOptions.DEFAULTS.withStripLocalVariables(false));

        assertNull(SampleBuild.RunnerJarOptions.DEFAULTS.stripLocalVariables(),
                "a row that sets nothing follows the builder default");
        assertTrue(kept.description().endsWith("; local-variable tables kept"), kept.description());
        assertFalse(stripped.description().contains("local-variable"), stripped.description());
        assertTrue(entryNames(stripped.artifact()).contains("MICRONAUT-INF/transforms.txt"),
                "the default strips the -g compiled dependency");
        assertFalse(entryNames(kept.artifact()).contains("MICRONAUT-INF/transforms.txt"));
        assertTrue(SampleBuild.comparisons().contains(new SampleBuild.ComparisonSpec("runner-stored",
                "runner-stored-keepdebug", "Local-variable tables stripped vs kept")));
        assertTrue(SampleBuild.comparisons().stream().anyMatch(spec -> spec.candidate().equals("runner-stored-aot")
                && spec.baseline().equals("runner-stored-keepdebug-aot")));
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

        IOException failure = assertThrows(IOException.class, () -> SampleBuild.runnerJar(output,
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
        Variant runner = SampleBuild.runnerJar(output, "extract-source", "fixture.ExtractedMain",
                List.of(classes), List.of(dependencyOne, dependencyTwo), Compression.STORED, EntryMode.STUB);

        Variant extracted = SampleBuild.extractedRunner(output, runner, "runner-extracted");

        assertEquals(EntryMode.STANDARD_LOADER, extracted.effectiveEntryMode());
        assertEquals("-jar", extracted.command().get(1));
        assertEquals(extracted.launchInputs().get(0).toAbsolutePath().toString(), extracted.command().get(2));
        assertEquals(3, extracted.launchInputs().size());
        assertEquals("first dependency.jar", extracted.launchInputs().get(1).getFileName().toString());
        assertEquals("second.jar", extracted.launchInputs().get(2).getFileName().toString());
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
