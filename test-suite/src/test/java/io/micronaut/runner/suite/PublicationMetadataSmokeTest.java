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

import groovy.json.JsonSlurper;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishes the normal (non-DUMMY) publications from an isolated copy of the production build, then
 * inspects and consumes them without {@code mavenLocal}, composite substitution or TestKit's plugin
 * classpath. This deliberately complements the fast {@code -DUMMY} repository used by the rest of the
 * suite: that repository reconstructs POMs and disables module metadata, so it cannot prove that the
 * release publications themselves are consumable.
 */
@Timeout(value = 45, unit = TimeUnit.MINUTES)
class PublicationMetadataSmokeTest {

    private static final String GROUP = "io.micronaut.runner";
    private static final String GROUP_PATH = "io/micronaut/runner";
    private static final String VERSION = "1.0.0-PUBLICATION-SMOKE";
    private static final String MARKER = "io.micronaut.runner.gradle.plugin";
    private static final String GRADLE_PLUGIN = "micronaut-runner-gradle-plugin";
    private static final String MAVEN_PLUGIN = "micronaut-runner-maven-plugin";
    private static final String BUILD = "micronaut-runner-build";
    private static final String LAUNCHER = "micronaut-runner-launcher";
    private static final String BOM = "micronaut-runner-bom";
    private static final Map<String, String> CHECKSUMS = Map.of(
            "md5", "MD5",
            "sha1", "SHA-1",
            "sha256", "SHA-256",
            "sha512", "SHA-512");

    private static Path workspace;
    private static Path isolatedBuild;
    private static Path repository;

    @BeforeAll
    static void publishNormalPublications() throws Exception {
        Samples.assumeTheBuildProvidedItsProperties();
        Samples.requireIntegrationScenario();
        Path project = requiredPathProperty("runner.test.projectDir");
        workspace = requiredPathProperty("runner.test.publicationSmokeDir");
        Samples.deleteRecursively(workspace);
        Files.createDirectories(workspace);
        isolatedBuild = workspace.resolve("producer");
        repository = workspace.resolve("repository");
        copyBuild(project, isolatedBuild);

        Path initScript = workspace.resolve("publication-smoke.init.gradle");
        Files.writeString(initScript, """
                gradle.beforeProject { project ->
                    project.pluginManager.withPlugin('maven-publish') {
                        project.publishing.repositories.maven {
                            name = 'publicationSmoke'
                            url = new File(System.getProperty('runner.smoke.repo')).toURI()
                        }
                    }
                }
                """, StandardCharsets.UTF_8);

        BuildResult result = gradle(isolatedBuild, workspace.resolve("producer-gradle-home"),
                "-I", initScript.toString(),
                "-Drunner.smoke.repo=" + repository,
                "-PprojectVersion=" + VERSION,
                ":micronaut-runner-launcher:publishMavenPublicationToPublicationSmokeRepository",
                ":micronaut-runner-build:publishMavenPublicationToPublicationSmokeRepository",
                ":micronaut-runner-gradle-plugin:publishPluginMavenPublicationToPublicationSmokeRepository",
                ":micronaut-runner-gradle-plugin:publishRunnerPluginMarkerMavenPublicationToPublicationSmokeRepository",
                ":micronaut-runner-maven-plugin:publishMavenPublicationToPublicationSmokeRepository",
                ":micronaut-runner-bom:publishMavenPublicationToPublicationSmokeRepository");

        for (String task : List.of(
                ":micronaut-runner-launcher:publishMavenPublicationToPublicationSmokeRepository",
                ":micronaut-runner-build:publishMavenPublicationToPublicationSmokeRepository",
                ":micronaut-runner-gradle-plugin:publishPluginMavenPublicationToPublicationSmokeRepository",
                ":micronaut-runner-gradle-plugin:publishRunnerPluginMarkerMavenPublicationToPublicationSmokeRepository",
                ":micronaut-runner-maven-plugin:publishMavenPublicationToPublicationSmokeRepository",
                ":micronaut-runner-bom:publishMavenPublicationToPublicationSmokeRepository")) {
            assertNotNull(result.task(task), () -> "normal publication task did not run: " + task);
            assertEquals(TaskOutcome.SUCCESS, result.task(task).getOutcome(),
                    () -> "normal publication task did not succeed: " + task + "\n" + result.getOutput());
        }
    }

    @Test
    void normalMetadataArtifactsDescriptorsAndChecksumsAgree() throws Exception {
        Map<String, String> pomDependencies = Map.of(
                LAUNCHER, "",
                BUILD, coordinate(LAUNCHER),
                GRADLE_PLUGIN, coordinate(BUILD),
                MAVEN_PLUGIN, coordinate(BUILD));
        for (Map.Entry<String, String> entry : pomDependencies.entrySet()) {
            Document pom = parseXml(artifact(entry.getKey(), "pom"));
            assertCoordinates(pom, GROUP, entry.getKey(), VERSION);
            if (entry.getValue().isEmpty()) {
                assertTrue(directDependencies(pom).isEmpty(), entry.getKey() + " must have no POM dependencies");
            } else {
                assertTrue(directDependencies(pom).contains(entry.getValue()),
                        () -> entry.getKey() + " lost transitive Runner dependency " + entry.getValue());
            }
        }

        Document marker = parseXml(artifact(MARKER, "pom"));
        assertCoordinates(marker, GROUP, MARKER, VERSION);
        assertEquals("pom", childText(marker.getDocumentElement(), "packaging"));
        assertEquals(Set.of(coordinate(GRADLE_PLUGIN)), directDependencies(marker),
                "the plugin marker must point at the implementation publication");

        Document mavenPluginPom = parseXml(artifact(MAVEN_PLUGIN, "pom"));
        assertEquals("maven-plugin", childText(mavenPluginPom.getDocumentElement(), "packaging"));
        assertDependencyScope(mavenPluginPom, BUILD, "runtime");
        assertDependencyScope(parseXml(artifact(GRADLE_PLUGIN, "pom")), BUILD, "runtime");
        assertDependencyScope(parseXml(artifact(BUILD, "pom")), LAUNCHER, "compile");

        Document bom = parseXml(artifact(BOM, "pom"));
        assertCoordinates(bom, GROUP, BOM, VERSION);
        Element properties = directChild(bom.getDocumentElement(), "properties");
        assertNotNull(properties, "the BOM must publish its Runner version property");
        assertEquals(VERSION, childText(properties, "micronaut.runner.version"));
        Set<String> managed = managedDependencies(bom);
        assertEquals(Set.of(GROUP + ":" + LAUNCHER + ":${micronaut.runner.version}",
                        GROUP + ":" + BUILD + ":${micronaut.runner.version}"), managed,
                "the BOM must manage both library modules at the smoke version");

        Map<String, Set<String>> moduleDependencies = Map.of(
                LAUNCHER, Set.of(),
                BUILD, Set.of(coordinate(LAUNCHER)),
                GRADLE_PLUGIN, Set.of(coordinate(BUILD)),
                MAVEN_PLUGIN, Set.of(coordinate(BUILD)));
        for (Map.Entry<String, Set<String>> entry : moduleDependencies.entrySet()) {
            assertModuleMetadata(entry.getKey(), entry.getValue());
        }

        assertJarDescriptors();
        assertPublishedFileNamesMatchCoordinates();
        assertAllChecksumsMatch(repository);
        assertNoDummyOrSnapshotMetadata();
    }

    @Test
    void gradleMarkerAndModuleMetadataAreRequiredByFreshConsumers() throws Exception {
        Path markerConsumer = isolatedBuild.resolve("test-suite/samples/published-marker-consumer");
        BuildResult markerResult = gradle(markerConsumer, workspace.resolve("marker-gradle-home"),
                "verifyMarker", "-Prunner.repo=" + repository.toUri(), "-Prunner.version=" + VERSION);
        assertEquals(TaskOutcome.SUCCESS, markerResult.task(":verifyMarker").getOutcome(), markerResult::getOutput);

        Path metadataConsumer = isolatedBuild.resolve("test-suite/samples/published-module-metadata-consumer");
        BuildResult metadataResult = gradle(metadataConsumer, workspace.resolve("metadata-gradle-home"),
                "verifyMetadata", "-Prunner.repo=" + repository.toUri(), "-Prunner.version=" + VERSION);
        assertEquals(TaskOutcome.SUCCESS, metadataResult.task(":verifyMetadata").getOutcome(),
                metadataResult::getOutput);
    }

    @Test
    void brokenMarkerDependencyIsRejectedByFreshGradleConsumer() throws Exception {
        Path brokenRepository = workspace.resolve("broken-marker-repository");
        copyTree(repository, brokenRepository);
        Path markerPom = artifact(brokenRepository, MARKER, "pom");
        Document document = parseXml(markerPom);
        Element dependencies = directChild(document.getDocumentElement(), "dependencies");
        assertNotNull(dependencies, "the production marker fixture must start with a dependency");
        document.getDocumentElement().removeChild(dependencies);
        writeXml(document, markerPom);

        Path consumer = isolatedBuild.resolve("test-suite/samples/published-marker-consumer");
        BuildResult failure = gradleAndFail(consumer, workspace.resolve("broken-marker-gradle-home"),
                "verifyMarker", "-Prunner.repo=" + brokenRepository.toUri(), "-Prunner.version=" + VERSION);
        String output = failure.getOutput().toLowerCase(Locale.ROOT);
        assertTrue(output.contains("plugin") && (output.contains("not found") || output.contains("unknownplugin")),
                () -> "breaking the marker dependency failed for an unrelated reason:\n" + failure.getOutput());
    }

    @Test
    void brokenJvmAttributeIsRejectedByMetadataOnlyConsumer() throws Exception {
        Path brokenRepository = workspace.resolve("broken-module-repository");
        copyTree(repository, brokenRepository);
        Path module = artifact(brokenRepository, GRADLE_PLUGIN, "module");
        String original = Files.readString(module, StandardCharsets.UTF_8);
        String broken = original.replace("\"org.gradle.jvm.version\": 25", "\"org.gradle.jvm.version\": 99");
        assertFalse(original.equals(broken), "the fixture must carry the declared Java 25 variants");
        Files.writeString(module, broken, StandardCharsets.UTF_8);

        Path consumer = isolatedBuild.resolve("test-suite/samples/published-module-metadata-consumer");
        BuildResult failure = gradleAndFail(consumer, workspace.resolve("broken-module-gradle-home"),
                "verifyMetadata", "-Prunner.repo=" + brokenRepository.toUri(), "-Prunner.version=" + VERSION);
        String output = failure.getOutput().toLowerCase(Locale.ROOT);
        assertTrue(output.contains("no matching variant") || output.contains("incompatible because")
                        || output.contains("only compatible with jvm runtime version"),
                () -> "breaking the JVM attribute failed for an unrelated reason:\n" + failure.getOutput());
    }

    @Test
    void gradleConsumerPackagesAndLaunchesFromFreshCache() throws Exception {
        Samples.requireIntegrationScenario();
        Path sample = isolatedBuild.resolve("test-suite/samples/hello-netty");
        BuildResult result = gradle(sample, workspace.resolve("sample-gradle-home"),
                "clean", ":micronautRunnerJar", "-Prunner.repo=" + repository.toUri(),
                "-Prunner.version=" + VERSION);
        assertEquals(TaskOutcome.SUCCESS, result.task(":micronautRunnerJar").getOutcome(), result::getOutput);

        Path archive = sample.resolve("build/libs/hello-netty-0.1-all.jar");
        assertTrue(Files.isRegularFile(archive), () -> "the production Gradle plugin wrote no " + archive);
        int port = Samples.freePort();
        ForkedApplication application = ForkedApplication.start(archive, sample,
                Map.of("SERVER_PORT", Integer.toString(port)));
        try {
            String body = application.awaitBody(URI.create("http://localhost:" + port + "/hello"),
                    Duration.ofMinutes(2));
            assertEquals("hello from RunnerClassLoader", body, application::describe);
        } finally {
            application.close();
        }
    }

    @Test
    void mavenConsumerPackagesAndLaunchesStrictlyFromFreshCache() throws Exception {
        Samples.requireIntegrationScenario();
        Path source = isolatedBuild.resolve("test-suite/samples/maven-basic");
        Path sample = workspace.resolve("maven-sample");
        copyTree(source, sample);
        Path localRepository = workspace.resolve("maven-local-repository");
        Files.createDirectories(localRepository);

        StringBuilder log = new StringBuilder();
        Properties properties = new Properties();
        properties.setProperty("runner.repo", repository.toUri().toASCIIString());
        properties.setProperty("runner.version", VERSION);
        properties.setProperty("micronaut.platform.version", Samples.MICRONAUT_PLATFORM_VERSION);
        properties.setProperty("micronaut.core.version", Samples.MICRONAUT_VERSION);
        InvocationRequest request = new DefaultInvocationRequest()
                .setPomFile(sample.resolve("pom.xml").toFile())
                .setBaseDirectory(sample.toFile())
                .addArgs(List.of("clean", "package"))
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
        InvocationResult result = new DefaultInvoker().setMavenHome(mavenHome().toFile()).execute(request);
        if (result.getExecutionException() != null) {
            throw new AssertionError("Maven could not be run:\n" + log, result.getExecutionException());
        }
        assertEquals(0, result.getExitCode(), () -> "strict Maven could not consume the normal plugin:\n" + log);

        Path archive = sample.resolve("target/maven-basic-0.1.jar");
        assertTrue(Files.isRegularFile(archive), () -> "the production Maven plugin wrote no " + archive);
        ForkedApplication application = ForkedApplication.start(archive, sample, Map.of());
        try {
            assertEquals(0, application.awaitExit(Duration.ofMinutes(2)), application::describe);
            assertTrue(application.output().contains("RUNNER OK: hello from RunnerClassLoader"),
                    application::describe);
        } finally {
            application.close();
        }
    }

    private static void assertModuleMetadata(String artifactId, Set<String> expectedDependencies) throws Exception {
        Path module = artifact(artifactId, "module");
        assertTrue(Files.isRegularFile(module), () -> "component publication has no Gradle metadata: " + module);
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) new JsonSlurper().parse(module.toFile());
        @SuppressWarnings("unchecked")
        Map<String, Object> component = (Map<String, Object>) metadata.get("component");
        assertEquals(GROUP, component.get("group"));
        assertEquals(artifactId, component.get("module"));
        assertEquals(VERSION, component.get("version"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variants = (List<Map<String, Object>>) metadata.get("variants");
        Set<String> foundDependencies = new java.util.HashSet<>();
        int libraryVariants = 0;
        for (Map<String, Object> variant : variants) {
            @SuppressWarnings("unchecked")
            Map<String, Object> attributes = (Map<String, Object>) variant.get("attributes");
            if ("library".equals(attributes.get("org.gradle.category"))) {
                libraryVariants++;
                assertEquals(25, ((Number) attributes.get("org.gradle.jvm.version")).intValue(),
                        () -> artifactId + " publishes a library variant for the wrong JVM");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> dependencies =
                    (List<Map<String, Object>>) variant.getOrDefault("dependencies", List.of());
            for (Map<String, Object> dependency : dependencies) {
                @SuppressWarnings("unchecked")
                Map<String, Object> version = (Map<String, Object>) dependency.get("version");
                assertEquals(VERSION, version.get("requires"));
                foundDependencies.add(dependency.get("group") + ":" + dependency.get("module") + ":"
                        + version.get("requires"));
            }
        }
        assertTrue(libraryVariants >= 2, () -> artifactId + " has no API/runtime Gradle variants");
        assertEquals(expectedDependencies, foundDependencies,
                () -> artifactId + " Gradle metadata has the wrong transitive Runner modules");
    }

    private static void assertJarDescriptors() throws Exception {
        Path gradleJar = artifact(GRADLE_PLUGIN, "jar");
        try (JarFile jar = new JarFile(gradleJar.toFile())) {
            Properties descriptor = new Properties();
            try (InputStream input = jar.getInputStream(
                    jar.getEntry("META-INF/gradle-plugins/io.micronaut.runner.properties"))) {
                descriptor.load(input);
            }
            assertEquals("io.micronaut.runner.gradle.MicronautRunnerPlugin",
                    descriptor.getProperty("implementation-class"));
        }

        Path mavenJar = artifact(MAVEN_PLUGIN, "jar");
        try (JarFile jar = new JarFile(mavenJar.toFile())) {
            assertDescriptorCoordinates(jar, "META-INF/maven/plugin.xml");
            assertDescriptorCoordinates(jar,
                    "META-INF/maven/io.micronaut.runner/micronaut-runner-maven-plugin/plugin-help.xml");
        }
    }

    private static void assertDescriptorCoordinates(JarFile jar, String entryName) throws Exception {
        var entry = jar.getEntry(entryName);
        assertNotNull(entry, () -> jar.getName() + " has no " + entryName);
        Path descriptor = workspace.resolve("descriptor-" + Math.abs(entryName.hashCode()) + ".xml");
        try (InputStream input = jar.getInputStream(entry)) {
            Files.copy(input, descriptor, StandardCopyOption.REPLACE_EXISTING);
        }
        Document document = parseXml(descriptor);
        Element root = document.getDocumentElement();
        assertEquals(GROUP, childText(root, "groupId"));
        assertEquals(MAVEN_PLUGIN, childText(root, "artifactId"));
        assertEquals(VERSION, childText(root, "version"));
        if ("plugin".equals(root.getLocalName()) || "plugin".equals(root.getNodeName())) {
            assertEquals("mn-runner", childText(root, "goalPrefix"));
        }
    }

    private static void assertPublishedFileNamesMatchCoordinates() throws IOException {
        for (String artifactId : List.of(LAUNCHER, BUILD, GRADLE_PLUGIN, MAVEN_PLUGIN, BOM, MARKER)) {
            Path directory = artifactDirectory(repository, artifactId);
            assertTrue(Files.isDirectory(directory), () -> "missing coordinate directory " + directory);
            try (Stream<Path> files = Files.list(directory)) {
                List<String> wrong = files.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> !name.startsWith(artifactId + "-" + VERSION + ".")
                                && !name.startsWith(artifactId + "-" + VERSION + "-"))
                        .toList();
                assertTrue(wrong.isEmpty(), () -> artifactId + " contains artifacts with another identity: " + wrong);
            }
        }
    }

    private static void assertAllChecksumsMatch(Path root) throws Exception {
        int checked = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path sidecar : files.filter(Files::isRegularFile).toList()) {
                String name = sidecar.getFileName().toString();
                for (Map.Entry<String, String> checksum : CHECKSUMS.entrySet()) {
                    String suffix = "." + checksum.getKey();
                    if (!name.endsWith(suffix)) {
                        continue;
                    }
                    Path artifact = sidecar.resolveSibling(name.substring(0, name.length() - suffix.length()));
                    assertTrue(Files.isRegularFile(artifact), () -> sidecar + " describes a missing file");
                    String expected = Files.readString(sidecar, StandardCharsets.US_ASCII).trim();
                    String actual = HexFormat.of().formatHex(MessageDigest.getInstance(checksum.getValue())
                            .digest(Files.readAllBytes(artifact)));
                    assertEquals(expected, actual, () -> sidecar + " does not match final published bytes");
                    checked++;
                }
            }
        }
        assertTrue(checked > 40, "too few publication checksum sidecars were exercised: " + checked);
    }

    private static void assertNoDummyOrSnapshotMetadata() throws IOException {
        List<Path> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(repository)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".pom") || name.endsWith(".module") || name.equals("maven-metadata.xml")) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    if (text.contains("-DUMMY") || text.contains("-SNAPSHOT")) {
                        offenders.add(file);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> "normal publication leaked rewritten/local coordinates: " + offenders);
    }

    private static void assertCoordinates(Document document, String group, String artifact, String version) {
        Element root = document.getDocumentElement();
        assertEquals(group, childText(root, "groupId"));
        assertEquals(artifact, childText(root, "artifactId"));
        assertEquals(version, childText(root, "version"));
    }

    private static Set<String> directDependencies(Document document) {
        Element dependencies = directChild(document.getDocumentElement(), "dependencies");
        return dependencyCoordinates(dependencies);
    }

    private static Set<String> managedDependencies(Document document) {
        Element management = directChild(document.getDocumentElement(), "dependencyManagement");
        return management == null ? Set.of() : dependencyCoordinates(directChild(management, "dependencies"));
    }

    private static Set<String> dependencyCoordinates(Element dependencies) {
        if (dependencies == null) {
            return Set.of();
        }
        Set<String> found = new java.util.HashSet<>();
        NodeList children = dependencies.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && "dependency".equals(localName(element))) {
                found.add(childText(element, "groupId") + ":" + childText(element, "artifactId") + ":"
                        + childText(element, "version"));
            }
        }
        return found;
    }

    private static void assertDependencyScope(Document document, String artifactId, String expectedScope) {
        Element dependencies = directChild(document.getDocumentElement(), "dependencies");
        assertNotNull(dependencies);
        NodeList children = dependencies.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element
                    && "dependency".equals(localName(element))
                    && artifactId.equals(childText(element, "artifactId"))) {
                assertEquals(expectedScope, childText(element, "scope"));
                return;
            }
        }
        throw new AssertionError("No dependency on " + artifactId);
    }

    private static String childText(Element parent, String name) {
        Element child = directChild(parent, name);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static Element directChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && name.equals(localName(element))) {
                return element;
            }
        }
        return null;
    }

    private static String localName(Node node) {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    private static Document parseXml(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(file.toFile());
    }

    private static void writeXml(Document document, Path file) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(new DOMSource(document), new StreamResult(file.toFile()));
    }

    private static BuildResult gradle(Path project, Path gradleHome, String... arguments) throws IOException {
        Files.createDirectories(gradleHome);
        List<String> allArguments = new ArrayList<>(List.of(arguments));
        allArguments.add("--stacktrace");
        allArguments.add("--console=plain");
        allArguments.add("--no-build-cache");
        allArguments.add("--max-workers=2");
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withTestKitDir(gradleHome.toFile())
                .withArguments(allArguments)
                .build();
    }

    private static BuildResult gradleAndFail(Path project, Path gradleHome, String... arguments) throws IOException {
        Files.createDirectories(gradleHome);
        List<String> allArguments = new ArrayList<>(List.of(arguments));
        allArguments.add("--stacktrace");
        allArguments.add("--console=plain");
        allArguments.add("--no-build-cache");
        allArguments.add("--max-workers=2");
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withTestKitDir(gradleHome.toFile())
                .withArguments(allArguments)
                .buildAndFail();
    }

    private static Path artifact(String artifactId, String extension) {
        return artifact(repository, artifactId, extension);
    }

    private static Path artifact(Path root, String artifactId, String extension) {
        return artifactDirectory(root, artifactId).resolve(artifactId + "-" + VERSION + "." + extension);
    }

    private static Path artifactDirectory(Path root, String artifactId) {
        return root.resolve(GROUP_PATH).resolve(artifactId).resolve(VERSION);
    }

    private static String coordinate(String artifactId) {
        return GROUP + ":" + artifactId + ":" + VERSION;
    }

    private static Path requiredPathProperty(String name) {
        String value = System.getProperty(name);
        assertNotNull(value, () -> name + " is not set; run this test through :test-suite:test");
        return Path.of(value);
    }

    private static Path mavenHome() {
        String configured = System.getProperty("runner.test.mavenHome");
        assertNotNull(configured, "runner.test.mavenHome is not set; run this test through Gradle");
        Path home = Path.of(configured);
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "mvn.cmd" : "mvn";
        assertTrue(Files.isRegularFile(home.resolve("bin").resolve(executable)),
                () -> "the pinned Maven distribution has no " + executable);
        return home;
    }

    private static void copyBuild(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Path relative = source.relativize(directory);
                if (isExcluded(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path relative = source.relativize(file);
                if (!isExcluded(relative)) {
                    Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isExcluded(Path relative) {
        boolean insideSources = false;
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("src")) {
                insideSources = true;
            }
            if (name.equals(".git") || name.equals(".gradle") || name.equals(".idea")
                    || (!insideSources && (name.equals("build") || name.equals("target")))) {
                return true;
            }
        }
        return false;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
