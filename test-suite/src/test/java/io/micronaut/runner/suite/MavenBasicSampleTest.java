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
package io.micronaut.runner.suite;

import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the {@code maven-basic} sample with the real Maven plugin, resolved from the same aggregated
 * repository the Gradle sample resolves its plugin from.
 *
 * <p>The build provisions the Apache Maven version and SHA-512 checksum pinned in
 * {@code gradle/maven-distribution.properties}. The distribution is a declared input of this test task, so
 * neither a developer's {@code PATH} nor a cached result from a different Maven can change what is tested.</p>
 *
 * <p>Maven is invoked with a local repository of this suite's own, under the test-suite build directory,
 * rather than the developer's {@code ~/.m2}. Two reasons: a test must not rewrite the developer's
 * repository, and the version under test is fixed ({@code -DUMMY}), so a cache keyed on the version alone
 * would serve yesterday's plugin forever. The plugin's own coordinates are purged from that repository
 * before every invocation; everything else stays cached.</p>
 */
@Timeout(value = 30, unit = TimeUnit.MINUTES)
class MavenBasicSampleTest {

    /** The line the sample prints when it started, resolved its bean and read its resource. */
    private static final String EXPECTED_OUTPUT = "RUNNER OK: hello from RunnerClassLoader";

    /** The coordinates of the modules under test, purged from the local repository before each run. */
    private static final String RUNNER_GROUP_PATH = "io/micronaut/runner";

    /** How long the sample is given to start, print and exit. */
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(2);

    /** Checksum sidecars Gradle publishes for every Maven artifact. */
    private static final Map<String, String> CHECKSUMS = Map.of(
            "md5", "MD5",
            "sha1", "SHA-1",
            "sha256", "SHA-256",
            "sha512", "SHA-512");

    @TempDir
    Path temporary;

    @BeforeAll
    static void assumeTheSuiteCanRun() {
        // Not the network probe: the descriptor check below reads a local file and is worth running
        // even on a machine that cannot reach Maven Central.
        Samples.assumeTheBuildProvidedItsProperties();
    }

    /**
     * The plugin descriptors inside the jar carry their own version, and Maven refuses to load a plugin
     * whose descriptors disagree with the artifact it was resolved as. Rewriting those descriptors also
     * changes the bytes after Gradle has published their checksum sidecars. Check both the module repository
     * and its aggregated copy, while proving that the source jar for normal publication stayed untouched.
     */
    @Test
    void theTestPublicationCarriesDescriptorsAndChecksumsMavenWillAccept() throws Exception {
        for (Path jar : List.of(modulePluginJar(), pluginJar())) {
            assertTrue(Files.isRegularFile(jar), () -> "the Maven plugin was not published to " + jar);
            assertDescriptorVersion(jar, "META-INF/maven/plugin.xml", Samples.VERSION);
            assertDescriptorVersion(jar,
                    "META-INF/maven/io.micronaut.runner/micronaut-runner-maven-plugin/plugin-help.xml",
                    Samples.VERSION);
            assertChecksumsMatch(jar);
        }

        Path sourceJar = sourcePluginJar();
        String sourceVersion = Samples.VERSION.replace("-DUMMY", "-SNAPSHOT");
        assertDescriptorVersion(sourceJar, "META-INF/maven/plugin.xml", sourceVersion);
        assertDescriptorVersion(sourceJar,
                "META-INF/maven/io.micronaut.runner/micronaut-runner-maven-plugin/plugin-help.xml",
                sourceVersion);
    }

    @Test
    void copyingTheSampleSkipsExistingBuildOutput() throws IOException {
        Path source = temporary.resolve("source");
        Files.createDirectories(source.resolve("target"));
        Files.writeString(source.resolve("pom.xml"), "fixture", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("target/stale.jar"), "stale", StandardCharsets.UTF_8);

        Path copy = Samples.copySample(source, temporary.resolve("copy"));

        assertEquals("fixture", Files.readString(copy.resolve("pom.xml"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(copy.resolve("target")), "build output must not be copied into the test fixture");
    }

    /**
     * Packages a copy of the sample twice. {@code clean package} produces a runner jar, keeps the jar
     * plugin's output as the original, and the one launch proves that the runtime exposes the main and the
     * per-package manifest values. {@code package} without {@code clean} (#25) must then take its manifest
     * from the jar plugin's new output rather than from the original a previous cycle left behind.
     */
    @Test
    void packagesTheSampleAndRunsTheRunnerJar() throws Exception {
        Samples.requireIntegrationScenario();
        Samples.requirePublishedArtifact("io/micronaut/runner/micronaut-runner-maven-plugin/"
                + Samples.VERSION + "/micronaut-runner-maven-plugin-" + Samples.VERSION + ".jar");
        requireMavenCanLoadThePlugin();
        Path sample = Samples.copySample(Samples.sample("maven-basic"), temporary.resolve("maven-basic"));
        Path archive = sample.resolve("target/maven-basic-0.1.jar");
        Path original = sample.resolve("target/original-maven-basic-0.1.jar");

        StringBuilder log = new StringBuilder();
        int status = maven(sample, log, "clean", "package");
        assertEquals(0, status, () -> "the Maven build failed:\n" + log);

        assertTrue(Files.isRegularFile(archive), () -> "no archive at " + archive + ":\n" + log);
        assertTrue(Files.isRegularFile(original),
                () -> "the jar plugin's output must be kept as " + original.getFileName() + ":\n" + log);
        assertTrue(isRunnerJar(archive), () -> archive.getFileName() + " is not a runner jar:\n" + log);
        assertFalse(isRunnerJar(original), () -> original.getFileName() + " must be the jar plugin's output:\n" + log);
        try (JarFile jar = new JarFile(archive.toFile())) {
            assertNotNull(jar.getEntry("MICRONAUT-INF/classes/io/micronaut/runner/generated/AppEntry.class"),
                    () -> "the default configuration did not generate the entry stub:\n" + log);
            assertNotNull(jar.getEntry(
                    "MICRONAUT-INF/classes/io/micronaut/runner/generated/logback/LogbackConfigurator.class"),
                    () -> "the default configuration did not precompile logback.xml:\n" + log);
        }
        assertManifestVersion(original, null, "v1");
        assertManifestVersion(original, "com/example/", "package-v1");

        ForkedApplication application = ForkedApplication.start(archive, sample, Map.of());
        try {
            int exit = application.awaitExit(RUN_TIMEOUT);
            assertEquals(0, exit, () -> "the packaged application exited with " + exit + application.describe());
            assertTrue(application.output().contains(EXPECTED_OUTPUT),
                    () -> "the application did not print \"" + EXPECTED_OUTPUT + "\"" + application.describe());
            String manifestLine = "RUNNER MANIFEST: main=v1, package=package-v1";
            assertTrue(application.output().contains(manifestLine),
                    () -> "the application did not print \"" + manifestLine + "\"" + application.describe());
        } finally {
            application.close();
        }

        Path pom = sample.resolve("pom.xml");
        String secondPom = Files.readString(pom, StandardCharsets.UTF_8)
                .replace("<fixture.main.version>v1</fixture.main.version>",
                        "<fixture.main.version>v2</fixture.main.version>")
                .replace("<fixture.package.version>package-v1</fixture.package.version>",
                        "<fixture.package.version>package-v2</fixture.package.version>");
        Files.writeString(pom, secondPom, StandardCharsets.UTF_8);
        Files.writeString(sample.resolve("src/main/resources/package-cycle.txt"), "v2", StandardCharsets.UTF_8);

        StringBuilder secondLog = new StringBuilder();
        assertEquals(0, maven(sample, secondLog, "package"),
                () -> "the non-clean Maven build failed:\n" + secondLog);
        assertTrue(isRunnerJar(archive), () -> archive.getFileName() + " is not a runner jar:\n" + secondLog);
        assertFalse(isRunnerJar(original), "the saved original must never be a previous runner");
        assertManifestVersion(original, null, "v2");
        assertManifestVersion(original, "com/example/", "package-v2");
        // The runner manifest copies the main Implementation-Version. The package section lives only in the
        // index, which is built from the same manifest, and the launch above already read both at runtime.
        assertManifestVersion(archive, null, "v2");
    }

    // ------------------------------------------------------------------ plumbing

    /** Fails closed when the published descriptor cannot be loaded in required mode. */
    private static void requireMavenCanLoadThePlugin() throws IOException {
        Path jar = pluginJar();
        if (!Files.isRegularFile(jar)
                || !Samples.VERSION.equals(pluginDescriptorVersion(jar, "META-INF/maven/plugin.xml"))) {
            throw new AssertionError("the published Maven plugin descriptor does not match " + Samples.VERSION
                    + "; see theTestPublicationCarriesDescriptorsAndChecksumsMavenWillAccept");
        }
    }

    /** Runs Maven against the sample, capturing everything it prints. */
    private static int maven(Path projectDirectory, StringBuilder log, String... goals) throws Exception {
        Path localRepository = Samples.mavenLocalRepository();
        Files.createDirectories(localRepository);
        Samples.deleteRecursively(localRepository.resolve(RUNNER_GROUP_PATH));

        Properties properties = new Properties();
        properties.setProperty("runner.repo", Samples.REPO);
        properties.setProperty("runner.version", Samples.VERSION);
        if (Samples.MICRONAUT_PLATFORM_VERSION != null) {
            properties.setProperty("micronaut.platform.version", Samples.MICRONAUT_PLATFORM_VERSION);
        }
        if (Samples.MICRONAUT_VERSION != null) {
            properties.setProperty("micronaut.core.version", Samples.MICRONAUT_VERSION);
        }

        InvocationRequest request = new DefaultInvocationRequest()
                .setPomFile(projectDirectory.resolve("pom.xml").toFile())
                .setBaseDirectory(projectDirectory.toFile())
                .addArgs(List.of(goals))
                .setProperties(properties)
                .setLocalRepositoryDirectory(localRepository.toFile())
                .setJavaHome(Samples.javaHome().toFile())
                .setBatchMode(true)
                .setGlobalChecksumPolicy(InvocationRequest.CheckSumPolicy.Fail)
                .setNoTransferProgress(true)
                .setShowErrors(true)
                .setInputStream(InputStream.nullInputStream());
        request.setOutputHandler(line -> log.append(line).append('\n'));
        request.setErrorHandler(line -> log.append(line).append('\n'));

        Invoker invoker = new DefaultInvoker().setMavenHome(mavenHome().toFile());
        InvocationResult result = invoker.execute(request);
        if (result.getExecutionException() != null) {
            throw new AssertionError("Maven could not be run:\n" + log, result.getExecutionException());
        }
        return result.getExitCode();
    }

    /** Returns the checksum-verified Maven home provisioned by the Gradle build. */
    static Path mavenHome() {
        return mavenHome(System.getProperty("runner.test.mavenHome"), System.getProperty("os.name", ""));
    }

    static Path mavenHome(String configured, String osName) {
        if (configured == null || configured.isBlank()) {
            throw new AssertionError("runner.test.mavenHome is not set; run this suite through Gradle");
        }
        Path home = Path.of(configured);
        boolean windows = osName.toLowerCase(Locale.ROOT).contains("win");
        Path executable = home.resolve("bin").resolve(windows ? "mvn.cmd" : "mvn");
        if (!Files.isRegularFile(executable) || (!windows && !Files.isExecutable(executable))) {
            throw new AssertionError("the pinned Maven distribution has no executable at " + executable);
        }
        return home;
    }

    private static Path pluginJar() {
        return pluginJar(Path.of(URI.create(Samples.REPO)));
    }

    private static Path modulePluginJar() {
        Path samples = Path.of(System.getProperty("runner.test.samplesDir"));
        return pluginJar(samples.getParent().getParent().resolve("runner-maven-plugin/build/test-repo"));
    }

    private static Path sourcePluginJar() {
        Path samples = Path.of(System.getProperty("runner.test.samplesDir"));
        String sourceVersion = Samples.VERSION.replace("-DUMMY", "-SNAPSHOT");
        return samples.getParent().getParent().resolve("runner-maven-plugin/build/libs")
                .resolve("micronaut-runner-maven-plugin-" + sourceVersion + ".jar");
    }

    private static Path pluginJar(Path repository) {
        return repository
                .resolve(RUNNER_GROUP_PATH)
                .resolve("micronaut-runner-maven-plugin")
                .resolve(Samples.VERSION)
                .resolve("micronaut-runner-maven-plugin-" + Samples.VERSION + ".jar");
    }

    private static void assertDescriptorVersion(Path jar, String name, String expectedVersion) throws IOException {
        assertTrue(Files.isRegularFile(jar), () -> "the Maven plugin was not built at " + jar);
        String descriptorVersion = pluginDescriptorVersion(jar, name);
        assertNotNull(descriptorVersion, () -> jar + " has no " + name);
        assertEquals(expectedVersion, descriptorVersion,
                () -> name + " inside " + jar.getFileName() + " says " + descriptorVersion
                        + " but the artifact was published as " + expectedVersion + ". Maven refuses such a "
                        + "plugin with \"Invalid plugin descriptor\", so no Maven sample can run. The "
                        + "descriptor has to be regenerated for the -DUMMY twin publication (see "
                        + "buildSrc/src/main/groovy/io.micronaut.build.internal.runner-maven-plugin.gradle "
                        + "and io.micronaut.build.internal.runner-test-repo.gradle).");
    }

    private static void assertChecksumsMatch(Path jar) throws Exception {
        byte[] bytes = Files.readAllBytes(jar);
        for (Map.Entry<String, String> checksum : CHECKSUMS.entrySet()) {
            Path sidecar = jar.resolveSibling(jar.getFileName() + "." + checksum.getKey());
            assertTrue(Files.isRegularFile(sidecar), () -> "the Maven publication has no " + sidecar);
            String expected = Files.readString(sidecar, StandardCharsets.US_ASCII).trim();
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance(checksum.getValue()).digest(bytes));
            assertEquals(expected, actual,
                    () -> sidecar + " does not describe the staged bytes of " + jar.getFileName());
        }
    }

    /** Reads {@code <version>} out of a plugin descriptor, without pulling in an XML parser. */
    private static String pluginDescriptorVersion(Path jar, String name) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            var entry = file.getEntry(name);
            if (entry == null) {
                return null;
            }
            try (InputStream in = file.getInputStream(entry)) {
                String descriptor = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                int open = descriptor.indexOf("<version>");
                int close = descriptor.indexOf("</version>", open);
                return open < 0 || close < 0 ? null : descriptor.substring(open + "<version>".length(), close).trim();
            }
        }
    }

    private static void assertManifestVersion(Path jar, String section, String expected) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            assertNotNull(manifest, () -> jar + " has no manifest");
            Attributes attributes = section == null ? manifest.getMainAttributes() : manifest.getAttributes(section);
            assertNotNull(attributes, () -> jar + " has no manifest section " + section);
            assertEquals(expected, attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION),
                    () -> jar + " carries stale manifest metadata in "
                            + (section == null ? "the main section" : section));
        }
    }

    private static boolean isRunnerJar(Path archive) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            return jar.getEntry("MICRONAUT-INF/index.bin") != null;
        }
    }
}
