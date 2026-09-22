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
import io.micronaut.runner.build.Compression;
import io.micronaut.runner.build.RunnerJarBuilder;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

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
 * <p>Three things are pinned down. The reproducible timestamp parser, which follows the Maven reproducible
 * build specification and must fall back rather than fail on an unresolved property. The compression
 * parameter, which must name the values it accepts when it is given something else. And the artifact
 * decision, which is the one piece of behaviour a user notices immediately: whether the runner jar becomes
 * the main artifact and where the jar plugin's own output ends up.</p>
 */
class PackageMojoTest {

    /** The default the format itself uses when no reproducible timestamp is configured. */
    private static final Instant DEFAULT_TIMESTAMP = Instant.parse("1980-02-01T00:00:00Z");

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
        set("projectHelper", projectHelper);
        set("mainClass", MAIN_CLASS);
        set("outputDirectory", buildDirectory.toFile());
        set("finalName", "demo-1.0");
        set("compression", "STORED");
        set("multiRelease", false);
        set("entryStub", true);
        set("enableNativeAccess", false);
        set("skip", false);
    }

    // ---------------------------------------------------------------- timestamp

    @Test
    void readsAnIso8601Timestamp() {
        set("outputTimestamp", "2024-03-01T10:20:30Z");
        assertEquals(Instant.parse("2024-03-01T10:20:30Z"), timestamp());
    }

    @Test
    void readsATimestampGivenAsSecondsSinceTheEpoch() {
        set("outputTimestamp", "1709288430");
        assertEquals(Instant.ofEpochSecond(1709288430L), timestamp());
    }

    @Test
    void readsATimestampWithSurroundingWhitespace() {
        set("outputTimestamp", "  2024-03-01T10:20:30Z  ");
        assertEquals(Instant.parse("2024-03-01T10:20:30Z"), timestamp());
    }

    @Test
    void fallsBackWhenTheTimestampIsUnsetOrBlank() {
        set("outputTimestamp", null);
        assertEquals(DEFAULT_TIMESTAMP, timestamp());
        set("outputTimestamp", "   ");
        assertEquals(DEFAULT_TIMESTAMP, timestamp());
        assertTrue(log.warnings.isEmpty(), () -> "an unset timestamp is normal, it must not warn: " + log.warnings);
    }

    @Test
    void fallsBackSilentlyWhenThePropertyWasNotResolved() {
        // A POM that never sets project.build.outputTimestamp leaves the literal placeholder behind. That is
        // the common case, not a mistake, so it must not produce a warning on every build.
        set("outputTimestamp", "${project.build.outputTimestamp}");
        assertEquals(DEFAULT_TIMESTAMP, timestamp());
        assertTrue(log.warnings.isEmpty(), () -> "an unresolved placeholder must not warn: " + log.warnings);
    }

    @Test
    void fallsBackAndWarnsWhenTheTimestampIsGarbage() {
        set("outputTimestamp", "last tuesday");
        assertEquals(DEFAULT_TIMESTAMP, timestamp());
        assertEquals(1, log.warnings.size(), () -> "expected exactly one warning, got " + log.warnings);
        String warning = log.warnings.get(0);
        assertTrue(warning.contains("last tuesday"),
                () -> "the warning must quote the value that could not be parsed: " + warning);
        assertTrue(warning.contains("1980-02-01T00:00:00Z"),
                () -> "the warning must name the value used instead: " + warning);
    }

    @Test
    void treatsASingleDigitAsAnInstantRatherThanAnEpochSecond() {
        // The specification's epoch form is a full second count; a lone digit is not one, so it takes the
        // ISO-8601 path and fails there. Asserted because the guard that draws that line is easy to lose.
        set("outputTimestamp", "7");
        assertEquals(DEFAULT_TIMESTAMP, timestamp());
        assertEquals(1, log.warnings.size(), () -> "expected exactly one warning, got " + log.warnings);
    }

    // -------------------------------------------------------------- compression

    @Test
    void parsesCompressionCaseInsensitivelyAndIgnoringWhitespace() {
        set("compression", "stored");
        assertEquals(Compression.STORED, compression());
        set("compression", "  Preserve ");
        assertEquals(Compression.PRESERVE, compression());
    }

    @Test
    void rejectsAnUnknownCompressionNamingTheSupportedValues() {
        set("compression", "DEFLATED");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, this::compression);
        assertTrue(failure.getMessage().contains("DEFLATED"),
                () -> "the failure must quote the rejected value: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("STORED") && failure.getMessage().contains("PRESERVE"),
                () -> "the failure must name what is accepted instead: " + failure.getMessage());
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
        assertEquals("package-v2", manifest(original).getAttributes("com/example/")
                .getValue("Implementation-Version"), "named package metadata must be refreshed too");
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

    private Instant timestamp() {
        return (Instant) invoke("timestamp");
    }

    private Compression compression() {
        return (Compression) invoke("compression");
    }

    /**
     * Calls a private no-argument method of the mojo, rethrowing whatever it threw so that
     * {@code assertThrows} sees the real failure rather than an {@link InvocationTargetException}.
     */
    private Object invoke(String name) {
        try {
            Method method = PackageMojo.class.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(mojo);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not call PackageMojo." + name + "()", e);
        }
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
     * Writes the class file the main class points at. Its bytes are never executed here, only located: the
     * builder refuses to package an application whose main class is not in its own output.
     */
    private void writeApplicationClass() throws IOException {
        Path file = classes.resolve(MAIN_CLASS.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
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
