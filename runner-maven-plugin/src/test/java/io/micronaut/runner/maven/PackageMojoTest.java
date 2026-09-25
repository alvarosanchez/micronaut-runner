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
package io.micronaut.runner.maven;

import io.micronaut.runner.IndexFormat;
import io.micronaut.runner.build.Dependency;
import io.micronaut.runner.build.RunnerJarBuilder;
import io.micronaut.runner.build.RunnerJarOption;
import io.micronaut.runner.build.RunnerJarSpec;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attribute;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.FieldModel;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PackageMojo}: everything the goal decides before it needs a Maven runtime.
 *
 * <p>The Maven plugin testing harness is deliberately not on this build's classpath, so the mojo is driven
 * the way a plain object is: its {@code @Parameter} fields are set by reflection and its behaviour is
 * asserted on the file system it writes to and on the project it mutates. The parts that need a real
 * reactor - dependency resolution, the lifecycle ordering that puts {@code maven-jar-plugin} first - are
 * covered by the sample builds under {@code test-suite}, not here.</p>
 *
 * <p>Four things are pinned down. The reproducible timestamp, which is read exactly as maven-jar-plugin reads
 * it. The goal's parameters, which are wiring for the packaging library's option table: a typed option is a
 * boxed parameter without a default, and every option can be set by name. The failures, which are
 * {@code MojoFailureException}s with the packaging library's message. And the artifact decision, which is
 * the one piece of behaviour a user notices immediately: whether the runner jar becomes the main artifact
 * and where the jar plugin's own output ends up.</p>
 */
class PackageMojoTest {

    /** The default the format itself uses when no reproducible timestamp is configured. */
    private static final Instant DEFAULT_TIMESTAMP = Instant.parse("1980-02-01T00:00:00Z");

    /** The descriptor of Maven's {@code @Parameter}. */
    private static final ClassDesc PARAMETER = ClassDesc.of("org.apache.maven.plugins.annotations.Parameter");

    /** Typed options whose parameter keeps the name maven-archiver gave it. */
    private static final Map<String, String> FIELD_ALIASES = Map.of("manifestAttributes", "manifestEntries");

    /** The parameters that carry build-tool facts rather than packaging options. */
    private static final Set<String> BUILD_TOOL_PARAMETERS = Set.of(
            "project", "session", "reactorProjects", "mainClass", "outputDirectory", "finalName", "classifier",
            "outputTimestamp", "skip", "runnerOptions");

    /** The entry that marks the jar produced by {@code maven-jar-plugin} in these fixtures. */
    private static final String JAR_PLUGIN_MARKER = "com/example/marker.txt";

    /** The main class of the fixture application. */
    private static final String MAIN_CLASS = "com.example.App";

    @TempDir
    Path temp;

    private Path buildDirectory;
    private Path classes;
    private PackageMojo mojo;
    private MavenProject project;
    private RecordingLog log;
    private RecordingProjectHelper projectHelper;

    @BeforeEach
    void setUp() throws IOException {
        buildDirectory = temp.resolve("target");
        classes = buildDirectory.resolve("classes");
        Files.createDirectories(classes);

        project = new MavenProject();
        project.setGroupId("com.example");
        project.setArtifactId("demo");
        project.setVersion("1.0");
        project.getBuild().setOutputDirectory(classes.toString());
        project.setArtifact(new DefaultArtifact("com.example", "demo", "1.0", "compile", "jar", null,
                new DefaultArtifactHandler("jar")));

        log = new RecordingLog();
        projectHelper = new RecordingProjectHelper();

        mojo = new PackageMojo();
        mojo.setLog(log);
        set("project", project);
        set("session", new MavenSession(null, null, new DefaultMavenExecutionRequest(),
                new DefaultMavenExecutionResult()));
        set("projectHelper", projectHelper);
        set("mainClass", MAIN_CLASS);
        set("outputDirectory", buildDirectory.toFile());
        set("finalName", "demo-1.0");
        // The option parameters stay null, which is what Maven leaves in them when nothing sets them.
    }

    // ---------------------------------------------------------------- timestamp

    @Test
    void readsAnIso8601TimestampWithAnOffset() throws MojoFailureException {
        set("outputTimestamp", "2024-01-01T00:00:00+01:00");
        assertEquals(Instant.parse("2023-12-31T23:00:00Z"), spec().timestamp());
    }

    @Test
    void readsATimestampGivenAsSecondsSinceTheEpoch() throws MojoFailureException {
        set("outputTimestamp", "1704067200");
        assertEquals(Instant.ofEpochSecond(1704067200L), spec().timestamp());
    }

    @Test
    void aDisabledTimestampPassesNothingAndDoesNotWarn() throws MojoFailureException {
        assumeNoSourceDateEpoch();
        // One non-digit character is how maven-jar-plugin's documentation disables the timestamp.
        set("outputTimestamp", "x");
        assertEquals(DEFAULT_TIMESTAMP, spec().timestamp());
        assertTrue(log.warnings.isEmpty(), () -> "a disabled timestamp must not warn: " + log.warnings);
    }

    @Test
    void anUnsetTimestampPassesNothing() throws MojoFailureException {
        assumeNoSourceDateEpoch();
        // Maven hands a parameter whose default names an unset property null, not the placeholder.
        set("outputTimestamp", null);
        assertEquals(DEFAULT_TIMESTAMP, spec().timestamp());
        assertTrue(log.warnings.isEmpty(), () -> "an unset timestamp is normal, it must not warn: " + log.warnings);
    }

    @Test
    void aSingleDigitIsAnEpochSecondThatMsDosTimeCannotRepresent() {
        // maven-archiver reads "7" as 1970-01-01T00:00:07Z, as maven-jar-plugin does in the same build.
        set("outputTimestamp", "7");
        MojoFailureException failure = assertThrows(MojoFailureException.class, this::spec);
        assertTrue(failure.getMessage().contains("MS-DOS time cannot represent 1970-01-01T00:00:07Z"),
                failure::getMessage);
    }

    @Test
    void anUnparsableTimestampFailsQuotingTheValue() {
        set("outputTimestamp", "last tuesday");
        MojoFailureException failure = assertThrows(MojoFailureException.class, this::spec);
        assertTrue(failure.getMessage().contains("last tuesday"),
                () -> "the failure must quote the value that could not be parsed: " + failure.getMessage());
    }

    // ---------------------------------------------------------- typed options

    @Test
    void everyTypedOptionHasAConformingParameter() throws IOException {
        Map<String, ParameterField> parameters = parameterFields();
        Set<String> optionFields = new HashSet<>();
        for (RunnerJarOption option : RunnerJarOption.values()) {
            if (option.exposure() != RunnerJarOption.Exposure.TYPED) {
                continue;
            }
            String name = FIELD_ALIASES.getOrDefault(option.optionName(), option.optionName());
            optionFields.add(name);
            ParameterField field = parameters.get(name);
            assertNotNull(field, () -> "no @Parameter field for the typed option " + option.optionName());
            if (option.valueType() == List.class || option.valueType() == Map.class) {
                continue;
            }
            assertTrue(field.descriptor().startsWith("L"),
                    () -> name + " must be boxed, so that unset is null: " + field.descriptor());
            assertEquals("micronaut.runner." + option.optionName(), field.elements().get("property"), name);
            assertFalse(field.elements().containsKey("defaultValue"),
                    () -> name + " must leave its default to the packaging library: " + field.elements());
        }
        for (String name : parameters.keySet()) {
            if (!optionFields.contains(name)) {
                assertTrue(BUILD_TOOL_PARAMETERS.contains(name),
                        () -> name + " is neither a typed option nor a listed build-tool parameter. A packaging"
                                + " option belongs in RunnerJarOption and is set through runnerOptions until it"
                                + " is typed.");
            }
        }
        for (String name : List.of("mainClass", "classifier", "skip")) {
            assertTrue(RunnerJarOption.named(name).isEmpty(),
                    () -> name + " is a parameter of the goal and cannot be an option name");
        }
    }

    @Test
    void anUnknownCompressionFailsWithThePackagingLibrarysMessage() {
        set("compression", "DEFLATED");
        MojoFailureException failure = assertThrows(MojoFailureException.class, this::spec);
        assertEquals("Unknown compression 'DEFLATED'. Supported values are STORED, PRESERVE.", failure.getMessage());
    }

    @Test
    void aBuildWithEveryOptionUnsetPackagesTheDefaults() throws Exception {
        assumePackagingIsPossible();
        writeApplicationClass();
        Path library = writeDependency("lib-1.0.jar");
        project.setArtifacts(Set.of(artifact("lib", library)));

        Map<String, String> effective = spec().effectiveOptions();
        for (RunnerJarOption option : RunnerJarOption.values()) {
            option.defaultValue().ifPresent(value -> assertEquals(value, effective.get(option.optionName()),
                    () -> option.optionName() + " is not the packaging library's default"));
        }

        mojo.execute();

        Path archive = buildDirectory.resolve("demo-1.0.jar");
        assertTrue(contains(archive, "MICRONAUT-INF/classes/io/micronaut/runner/generated/AppEntry.class"),
                "the default configuration must generate the entry stub");
        assertEquals(Set.of(ZipEntry.STORED), nestedEntryMethods(archive, "MICRONAUT-INF/lib/lib-1.0.jar"),
                "STORED is the default compression");
        assertNull(manifest(archive).getMainAttributes().getValue("Enable-Native-Access"));
    }

    // ------------------------------------------------------------ project modules

    @Test
    void anArtifactOfAReactorProjectIsAProjectModuleAndAnyOtherIsNot() throws Exception {
        Path library = writeDependency("lib-1.0.jar");
        Path module = writeDependency("module-1.0.jar");
        project.setArtifacts(new java.util.LinkedHashSet<>(List.of(artifact("lib", library),
                artifact("module", module))));
        MavenProject reactorModule = new MavenProject();
        reactorModule.setGroupId("com.example");
        reactorModule.setArtifactId("module");
        reactorModule.setVersion("1.0");
        set("reactorProjects", List.of(project, reactorModule));

        Map<String, Boolean> flags = new HashMap<>();
        for (Dependency dependency : spec().dependencies()) {
            flags.put(dependency.coordinates().orElseThrow(), dependency.projectModule());
        }

        assertEquals(Map.of("com.example:lib:1.0", false, "com.example:module:1.0", true), flags);
    }

    @Test
    void withoutReactorProjectsNoArtifactIsAProjectModule() throws Exception {
        project.setArtifacts(Set.of(artifact("module", writeDependency("module-1.0.jar"))));
        set("reactorProjects", null);

        assertFalse(spec().dependencies().get(0).projectModule());
    }

    // --------------------------------------------------------- passthrough options

    @Test
    void aRunnerOptionsEntryWinsOverTheTypedParameter() throws Exception {
        assumePackagingIsPossible();
        writeApplicationClass();
        Path library = writeDependency("lib-1.0.jar");
        project.setArtifacts(Set.of(artifact("lib", library)));
        set("compression", "STORED");
        set("runnerOptions", Map.of("compression", "PRESERVE"));

        mojo.execute();

        assertEquals(Set.of(ZipEntry.DEFLATED),
                nestedEntryMethods(buildDirectory.resolve("demo-1.0.jar"), "MICRONAUT-INF/lib/lib-1.0.jar"),
                "<runnerOptions> must win over -Dmicronaut.runner.compression");
    }

    @Test
    void collectsPassthroughOptionsFromUserThenProjectPropertiesThenRunnerOptions() {
        Properties user = new Properties();
        Properties projectProperties = new Properties();
        projectProperties.setProperty("micronaut.runner.x", "project");
        projectProperties.setProperty("micronaut.runner.y", "project");
        assertEquals(Map.of("x", "project"),
                PackageMojo.options(user, projectProperties, null, List.of("x")),
                "a project property sets a passthrough option; a name outside the table is not read");

        user.setProperty("micronaut.runner.x", "user");
        assertEquals(Map.of("x", "user"), PackageMojo.options(user, projectProperties, null, List.of("x")),
                "a user property wins over the project property");

        Map<String, String> configured = new HashMap<>();
        configured.put("x", "configured");
        configured.put("addOpens", null);
        assertEquals(Map.of("x", "configured", "addOpens", ""),
                PackageMojo.options(user, projectProperties, configured, List.of("x")),
                "a <runnerOptions> entry wins over both, and an empty element is the empty value");
    }

    @Test
    void anUnknownRunnerOptionFailsTheBuild() {
        set("runnerOptions", Map.of("desugarLambda", "true"));
        MojoFailureException failure = assertThrows(MojoFailureException.class, this::spec);
        assertTrue(failure.getMessage().startsWith("Unknown Micronaut Runner option 'desugarLambda'"),
                failure::getMessage);
    }

    // ------------------------------------------------------------- dependency files

    @Test
    void anArtifactWithoutAFileFailsNamingItsCoordinates() {
        DefaultArtifact unresolved = new DefaultArtifact("com.example", "unresolved", "1.0", "runtime", "jar", null,
                new DefaultArtifactHandler("jar"));
        project.setArtifacts(Set.of(unresolved));

        MojoFailureException failure = assertThrows(MojoFailureException.class, this::spec);
        assertTrue(failure.getMessage().contains("com.example:unresolved:1.0"), failure::getMessage);
    }

    @Test
    void passesEveryResolvedFileToThePackagingLibrary() throws MojoFailureException {
        // The packaging library applies one file rule on every build tool; the goal no longer filters.
        Path pom = temp.resolve("repository/parent-1.0.pom");
        project.setArtifacts(Set.of(artifact("parent", pom)));

        assertEquals(List.of(Dependency.of(pom, "com.example:parent:1.0")), spec().dependencies());
    }

    // -------------------------------------------------- manifest module access

    @Test
    void writesMultipleExportAndOpenPairsUsingJarManifestSyntax() throws Exception {
        assumePackagingIsPossible();
        writeApplicationClass();
        set("addExports", List.of("java.base/sun.nio.ch", "java.base/jdk.internal.misc"));
        set("addOpens", List.of("java.base/java.lang", "java.base/java.util"));

        mojo.execute();

        Attributes attributes = manifest(buildDirectory.resolve("demo-1.0.jar")).getMainAttributes();
        assertEquals("java.base/sun.nio.ch java.base/jdk.internal.misc",
                attributes.getValue("Add-Exports"));
        assertEquals("java.base/java.lang java.base/java.util", attributes.getValue("Add-Opens"));
    }

    @Test
    void rejectsCommandLineSyntaxForExportsAndOpensWithCorrections() {
        set("addExports", List.of("java.base/sun.nio.ch=ALL-UNNAMED"));
        MojoFailureException badExport = assertThrows(MojoFailureException.class, mojo::execute);
        assertTrue(badExport.getMessage().contains("Use 'java.base/sun.nio.ch' in the JAR manifest"),
                badExport::getMessage);
        assertTrue(badExport.getMessage().contains("--add-exports java.base/sun.nio.ch=ALL-UNNAMED"),
                badExport::getMessage);

        set("addExports", List.of("java.base/sun.nio.ch"));
        set("addOpens", List.of("java.base/java.lang=ALL-UNNAMED"));
        MojoFailureException badOpen = assertThrows(MojoFailureException.class, mojo::execute);
        assertTrue(badOpen.getMessage().contains("Use 'java.base/java.lang' in the JAR manifest"),
                badOpen::getMessage);
        assertTrue(badOpen.getMessage().contains("--add-opens java.base/java.lang=ALL-UNNAMED"),
                badOpen::getMessage);
    }

    // ---------------------------------------------------- the artifact decision

    @Test
    void replacesTheMainArtifactAndKeepsTheJarPluginsOutputAsTheOriginal() throws Exception {
        assumePackagingIsPossible();
        writeApplicationClass();
        writeJarPluginOutput(buildDirectory.resolve("demo-1.0.jar"));

        mojo.execute();

        Path runnerJar = buildDirectory.resolve("demo-1.0.jar");
        Path original = buildDirectory.resolve("original-demo-1.0.jar");
        assertTrue(Files.isRegularFile(runnerJar), "the runner jar must take the main artifact's name");
        assertTrue(Files.isRegularFile(original), "the jar plugin's output must be kept as original-<finalName>.jar");
        assertTrue(isRunnerJar(runnerJar), "the file under the main artifact's name must be the runner jar");
        assertTrue(contains(original, JAR_PLUGIN_MARKER), "the original must be the jar plugin's own output");

        assertEquals(runnerJar.toFile(), project.getArtifact().getFile(),
                "the project's main artifact must point at the runner jar");
        assertTrue(projectHelper.attached.isEmpty(),
                () -> "nothing may be attached when the main artifact is replaced: " + projectHelper.attached);
    }

    @Test
    void attachesAnAdditionalArtifactWhenAClassifierIsSet() throws Exception {
        assumePackagingIsPossible();
        writeApplicationClass();
        writeJarPluginOutput(buildDirectory.resolve("demo-1.0.jar"));
        set("classifier", "runner");

        mojo.execute();

        Path attached = buildDirectory.resolve("demo-1.0-runner.jar");
        assertTrue(Files.isRegularFile(attached), "the archive must carry the classifier in its name");
        assertTrue(isRunnerJar(attached), "the classified archive must be the runner jar");
        assertTrue(contains(buildDirectory.resolve("demo-1.0.jar"), JAR_PLUGIN_MARKER),
                "the jar plugin's output must be left exactly where it was");
        assertFalse(Files.exists(buildDirectory.resolve("original-demo-1.0.jar")),
                "nothing is moved aside when the main artifact is left alone");

        assertNull(project.getArtifact().getFile(), "the main artifact must not be touched");
        assertEquals(1, projectHelper.attached.size(),
                () -> "exactly one artifact must be attached, got " + projectHelper.attached);
        Attachment attachment = projectHelper.attached.get(0);
        assertEquals("jar", attachment.type());
        assertEquals("runner", attachment.classifier());
        assertEquals(attached.toFile(), attachment.file());
    }

    @Test
    void keepsTheRealOriginalWhenTheGoalRunsTwice() throws Exception {
        assumePackagingIsPossible();
        writeApplicationClass();
        writeJarPluginOutput(buildDirectory.resolve("demo-1.0.jar"));

        mojo.execute();
        mojo.execute();

        Path original = buildDirectory.resolve("original-demo-1.0.jar");
        assertTrue(contains(original, JAR_PLUGIN_MARKER),
                "a second run must not promote the first run's runner jar to original-<finalName>.jar");
        assertFalse(isRunnerJar(original), "the original must never be a runner jar");
        assertTrue(isRunnerJar(buildDirectory.resolve("demo-1.0.jar")), "the second run must still produce the archive");
    }

    @Test
    void refreshesTheOriginalFromANewJarPluginOutput() throws Exception {
        assumePackagingIsPossible();
        writeApplicationClass();
        Path mainArtifact = buildDirectory.resolve("demo-1.0.jar");
        Path original = buildDirectory.resolve("original-demo-1.0.jar");
        writeJarPluginOutput(mainArtifact, "v1");

        mojo.execute();
        writeJarPluginOutput(mainArtifact, "v2");
        mojo.execute();

        assertEquals("v2", manifest(original).getMainAttributes().getValue("Implementation-Version"),
                "a new jar-plugin output must replace the stale original on a non-clean package cycle");
        Attributes applicationPackage = manifest(original).getAttributes("com/example/");
        assertNotNull(applicationPackage, () -> original + " has no com/example/ manifest section");
        assertEquals("package-v2", applicationPackage.getValue("Implementation-Version"),
                "named package metadata must be refreshed too");
        assertFalse(isRunnerJar(original), "the refreshed original must remain the thin jar");
        assertTrue(isRunnerJar(mainArtifact), "the main artifact must remain the runner jar");
    }

    @Test
    void preservesBothThinJarsWhenPackagingTheNewOutputFails() throws Exception {
        assumePackagingIsPossible();
        writeApplicationClass();
        Path mainArtifact = buildDirectory.resolve("demo-1.0.jar");
        Path original = buildDirectory.resolve("original-demo-1.0.jar");
        writeJarPluginOutput(mainArtifact, "v1");
        mojo.execute();

        writeJarPluginOutput(mainArtifact, "v2");
        Files.delete(classes.resolve(MAIN_CLASS.replace('.', '/') + ".class"));

        assertThrows(MojoExecutionException.class, mojo::execute);
        assertEquals("v2", manifest(mainArtifact).getMainAttributes().getValue("Implementation-Version"),
                "a failed package must leave the newly generated thin jar under the main artifact name");
        assertEquals("v1", manifest(original).getMainAttributes().getValue("Implementation-Version"),
                "a failed package must not replace the last successfully saved original");
        assertFalse(isRunnerJar(mainArtifact));
        assertFalse(isRunnerJar(original));
    }

    @Test
    void preservesTheThinJarWhenUpdatingTheOriginalFailsAfterPackaging() throws Exception {
        assumePackagingIsPossible();
        writeApplicationClass();
        Path mainArtifact = buildDirectory.resolve("demo-1.0.jar");
        Path original = buildDirectory.resolve("original-demo-1.0.jar");
        writeJarPluginOutput(mainArtifact, "v2");
        Files.createDirectories(original.resolve("replacement-blocker"));

        assertThrows(MojoExecutionException.class, mojo::execute);

        assertTrue(isRunnerJar(mainArtifact), "runner packaging succeeded before the original update failed");
        List<Path> preserved;
        try (var files = Files.list(buildDirectory)) {
            preserved = files.filter(path -> path.getFileName().toString().startsWith(".micronaut-runner-original-"))
                    .toList();
        }
        assertEquals(1, preserved.size(),
                () -> "the only thin-jar copy must survive under its temporary name: " + preserved);
        assertEquals("v2", manifest(preserved.get(0)).getMainAttributes().getValue("Implementation-Version"));
        assertEquals(1, log.warnings.size(), () -> "expected the preserved location in one warning: " + log.warnings);
        assertTrue(log.warnings.get(0).contains(preserved.get(0).toString()),
                () -> "the warning must identify the preserved thin jar: " + log.warnings);
    }

    @Test
    void packagesWithoutAJarPluginOutput() throws Exception {
        // Nothing requires maven-jar-plugin to have run: the manifest source is optional.
        assumePackagingIsPossible();
        writeApplicationClass();

        mojo.execute();

        assertTrue(isRunnerJar(buildDirectory.resolve("demo-1.0.jar")));
        assertFalse(Files.exists(buildDirectory.resolve("original-demo-1.0.jar")),
                "there was no jar plugin output to move aside");
    }

    @Test
    void skipsWithoutWritingAnything() throws Exception {
        set("skip", true);
        mojo.execute();
        assertFalse(Files.exists(buildDirectory.resolve("demo-1.0.jar")));
        assertNull(project.getArtifact().getFile());
    }

    @Test
    void failsInRunnerPackagingEvenWhenSkippedAndWritesNothing() throws Exception {
        // In runner packaging, micronaut-maven-plugin has already replaced the main artifact with a Runner JAR.
        writeApplicationClass();
        writeJarPluginOutput(buildDirectory.resolve("demo-1.0.jar"));
        project.setPackaging("runner");
        Map<Path, String> before = snapshot(buildDirectory);

        for (boolean skip : new boolean[] {false, true}) {
            set("skip", skip);
            MojoFailureException failure = assertThrows(MojoFailureException.class, mojo::execute);
            assertEquals("micronaut-maven-plugin builds the Runner JAR in runner packaging; remove this plugin's"
                    + " execution", failure.getMessage(), () -> "skip=" + skip);
            assertEquals(before, snapshot(buildDirectory), () -> "skip=" + skip + " changed the build directory");
        }
        assertNull(project.getArtifact().getFile(), "the main artifact must not be touched");
        assertTrue(projectHelper.attached.isEmpty(), () -> "nothing may be attached: " + projectHelper.attached);
    }

    @Test
    void failsWithAnActionableMessageWhenThereAreNoClasses() {
        Path missing = temp.resolve("nowhere");
        project.getBuild().setOutputDirectory(missing.toString());
        MojoFailureException failure = assertThrows(MojoFailureException.class, mojo::execute);
        assertTrue(failure.getMessage().contains(missing.toString()),
                () -> "the failure must name the directory it looked in: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("package phase"),
                () -> "the failure must say how to fix it: " + failure.getMessage());
    }

    // -------------------------------------------------------------------- plumbing

    /**
     * The launcher jar is a generated resource of the packaging library. Without it nothing can be
     * packaged, and that is a build problem rather than a failure of this mojo.
     */
    private static void assumePackagingIsPossible() {
        Assumptions.assumeTrue(
                RunnerJarBuilder.class.getResource("/META-INF/micronaut-runner/launcher.jar") != null,
                "the bundled launcher jar is not on the test class path");
    }

    /** Every regular file under a directory, with a digest of its content. */
    private static Map<Path, String> snapshot(Path directory) throws IOException {
        Map<Path, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(directory.relativize(file), HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return files;
    }

    private static void assumeNoSourceDateEpoch() {
        Assumptions.assumeTrue(System.getenv("SOURCE_DATE_EPOCH") == null,
                "SOURCE_DATE_EPOCH is set, so maven-archiver takes the timestamp from it");
    }

    /** The spec the goal hands to the packaging library, without packaging anything. */
    private RunnerJarSpec spec() throws MojoFailureException {
        return mojo.buildSpec(classes.toFile(), buildDirectory.resolve("demo-1.0.jar").toFile(), null);
    }

    /**
     * Reads every {@code @Parameter} field of the goal. The annotation has {@code CLASS} retention, so it is
     * read from the class file rather than by reflection.
     */
    private static Map<String, ParameterField> parameterFields() throws IOException {
        byte[] bytes;
        try (InputStream in = PackageMojo.class.getResourceAsStream("PackageMojo.class")) {
            assertNotNull(in, "PackageMojo.class is not on the test class path");
            bytes = in.readAllBytes();
        }
        Map<String, ParameterField> fields = new TreeMap<>();
        for (FieldModel field : ClassFile.of().parse(bytes).fields()) {
            for (Attribute<?> attribute : field.attributes()) {
                if (!(attribute instanceof RuntimeInvisibleAnnotationsAttribute annotations)) {
                    continue;
                }
                for (Annotation annotation : annotations.annotations()) {
                    if (!annotation.classSymbol().equals(PARAMETER)) {
                        continue;
                    }
                    Map<String, String> elements = new TreeMap<>();
                    for (AnnotationElement element : annotation.elements()) {
                        elements.put(element.name().stringValue(), switch (element.value()) {
                            case AnnotationValue.OfString text -> text.stringValue();
                            case AnnotationValue.OfBoolean flag -> String.valueOf(flag.booleanValue());
                            default -> element.value().toString();
                        });
                    }
                    fields.put(field.fieldName().stringValue(),
                            new ParameterField(field.fieldType().stringValue(), elements));
                }
            }
        }
        return fields;
    }

    private void set(String name, Object value) {
        try {
            Field field = PackageMojo.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(mojo, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set PackageMojo." + name, e);
        }
    }

    /**
     * Writes the class file the main class points at: a public class with an empty
     * {@code public static void main(String[])}, which is all the entry stub needs. It is never executed here.
     */
    private void writeApplicationClass() throws IOException {
        Path file = classes.resolve(MAIN_CLASS.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, ClassFile.of().build(ClassDesc.of(MAIN_CLASS), type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withMethodBody("main", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String.arrayType()),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, CodeBuilder::return_)));
    }

    /** Writes a dependency jar whose one class is deflated, as a published jar's classes are. */
    private Path writeDependency(String name) throws IOException {
        Path file = temp.resolve("repository").resolve(name);
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("com/example/lib/Library.class"));
            out.write(new byte[512]);
            out.closeEntry();
        }
        return file;
    }

    private static Artifact artifact(String artifactId, Path file) {
        DefaultArtifact artifact = new DefaultArtifact("com.example", artifactId, "1.0", "runtime", "jar", null,
                new DefaultArtifactHandler("jar"));
        artifact.setFile(file.toFile());
        return artifact;
    }

    /** The compression methods of the entries of a jar nested in a runner jar. */
    private static Set<Integer> nestedEntryMethods(Path archive, String name) throws IOException {
        byte[] nested;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            assertNotNull(entry, () -> name + " is not in " + archive);
            try (InputStream in = zip.getInputStream(entry)) {
                nested = in.readAllBytes();
            }
        }
        Set<Integer> methods = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(nested))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (!entry.isDirectory()) {
                    methods.add(entry.getMethod());
                }
            }
        }
        return methods;
    }

    /** Writes a stand-in for the jar {@code maven-jar-plugin} produces, with a manifest and one marker entry. */
    private Path writeJarPluginOutput(Path file) throws IOException {
        return writeJarPluginOutput(file, "1.0");
    }

    private Path writeJarPluginOutput(Path file, String version) throws IOException {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Implementation-Title", "demo");
        main.putValue("Implementation-Version", version);
        Attributes applicationPackage = new Attributes();
        applicationPackage.putValue("Implementation-Version", "package-" + version);
        manifest.getEntries().put("com/example/", applicationPackage);
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            out.putNextEntry(new ZipEntry(JAR_PLUGIN_MARKER));
            out.write("from maven-jar-plugin".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }

    private static Manifest manifest(Path file) throws IOException {
        try (JarFile jar = new JarFile(file.toFile())) {
            Manifest manifest = jar.getManifest();
            assertNotNull(manifest, () -> file + " has no manifest");
            return manifest;
        }
    }

    /** A runner jar is recognised the way the launcher recognises one: by its manifest. */
    private static boolean isRunnerJar(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try (JarFile jar = new JarFile(file.toFile())) {
            Manifest manifest = jar.getManifest();
            return manifest != null
                    && MAIN_CLASS.equals(manifest.getMainAttributes().getValue(IndexFormat.ATTR_START_CLASS))
                    && jar.getEntry(IndexFormat.INDEX_ENTRY_NAME) != null;
        }
    }

    private static boolean contains(Path file, String entry) throws IOException {
        try (JarFile jar = new JarFile(file.toFile())) {
            return jar.getEntry(entry) != null;
        }
    }

    /** A {@link org.apache.maven.plugin.logging.Log} that keeps the warnings, so they can be asserted on. */
    private static final class RecordingLog extends SystemStreamLog {

        private final List<String> warnings = new ArrayList<>();

        @Override
        public void warn(CharSequence content) {
            warnings.add(String.valueOf(content));
        }

        @Override
        public void warn(CharSequence content, Throwable error) {
            warnings.add(String.valueOf(content));
        }

        @Override
        public void info(CharSequence content) {
            // quiet: the goal's progress is not what these tests are about
        }
    }

    /** One recorded call to {@link MavenProjectHelper#attachArtifact}. */
    private record Attachment(String type, String classifier, File file) {
    }

    /**
     * A field annotated {@code @Parameter}.
     *
     * @param descriptor the field's type descriptor
     * @param elements   the annotation's elements, as text
     */
    private record ParameterField(String descriptor, Map<String, String> elements) {
    }

    /** Records attachments instead of touching a real artifact set. */
    private static final class RecordingProjectHelper implements MavenProjectHelper {

        private final List<Attachment> attached = new ArrayList<>();

        @Override
        public void attachArtifact(MavenProject project, File artifactFile, String artifactClassifier) {
            attached.add(new Attachment("jar", artifactClassifier, artifactFile));
        }

        @Override
        public void attachArtifact(MavenProject project, String artifactType, File artifactFile) {
            attached.add(new Attachment(artifactType, null, artifactFile));
        }

        @Override
        public void attachArtifact(MavenProject project, String artifactType, String artifactClassifier,
                File artifactFile) {
            attached.add(new Attachment(artifactType, artifactClassifier, artifactFile));
        }

        @Override
        public void addResource(MavenProject project, String resourceDirectory, List<String> includes,
                List<String> excludes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addTestResource(MavenProject project, String resourceDirectory, List<String> includes,
                List<String> excludes) {
            throw new UnsupportedOperationException();
        }
    }
}
