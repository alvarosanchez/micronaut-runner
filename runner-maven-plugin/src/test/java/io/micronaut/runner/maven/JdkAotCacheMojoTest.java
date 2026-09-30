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

import io.micronaut.runner.build.AotTarget;
import io.micronaut.runner.build.aotcache.AotCacheSettings;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attribute;
import java.lang.classfile.ClassFile;
import java.lang.classfile.FieldModel;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdkAotCacheMojo} and {@link LayoutMojo} as plain objects, with their parameters set by reflection, as
 * {@link PackageMojoTest} drives the packaging goal. Nothing is launched: the parameters' mapping onto the
 * packaging library's settings, the switch, the Runner JAR's path and the JDK are checked.
 */
class JdkAotCacheMojoTest {

    private static final ClassDesc PARAMETER = ClassDesc.of("org.apache.maven.plugins.annotations.Parameter");

    @TempDir
    Path temp;

    private Path buildDirectory;
    private JdkAotCacheMojo mojo;
    private LayoutMojo layout;
    private RecordingLog log;

    @BeforeEach
    void setUp() {
        buildDirectory = temp.resolve("target");
        MavenProject project = new MavenProject();
        project.setGroupId("com.example");
        project.setArtifactId("demo");
        project.setVersion("1.0");
        project.setFile(temp.resolve("pom.xml").toFile());
        project.getBuild().setDirectory(buildDirectory.toString());
        MavenSession session = new MavenSession(null, null, new DefaultMavenExecutionRequest(),
                new DefaultMavenExecutionResult());

        log = new RecordingLog();
        mojo = new JdkAotCacheMojo();
        layout = new LayoutMojo();
        for (AbstractMojo goal : List.of(mojo, layout)) {
            goal.setLog(log);
            set(goal, "project", project);
            set(goal, "session", session);
            set(goal, "toolchainManager", new NoToolchains());
            set(goal, "outputDirectory", buildDirectory.toFile());
            set(goal, "finalName", "demo-1.0");
        }
    }

    @Test
    void unsetParametersLeaveTheLibrarysDefaults() throws MojoFailureException {
        assertEquals(AotCacheSettings.defaults(), mojo.settings());
        assertEquals(AotTarget.DEFAULT, mojo.target());
    }

    @Test
    void theParametersReachTheSettingsAndTheTarget() throws MojoFailureException {
        set(mojo, "jdkAotCacheTarget", "singleJar");
        set(mojo, "jdkAotCacheStrict", true);
        set(mojo, "jdkAotCacheJvmArgs", List.of("-XX:+UseSerialGC", "-Xmx512m"));
        set(mojo, "jdkAotCacheVerifyProbes", 20);
        set(mojo, "jdkAotCacheMinCoverage", 0.9);
        set(mojo, "trainingJvmArgs", List.of("-Dprofile.only=true"));

        AotCacheSettings settings = mojo.settings();

        assertEquals(AotTarget.SINGLE_JAR, mojo.target());
        assertTrue(settings.strict());
        assertEquals(List.of("-XX:+UseSerialGC", "-Xmx512m"), settings.jvmArgs());
        assertEquals(20, settings.verifyProbes());
        assertEquals(0.9, settings.minCoverage());
        assertEquals(List.of("-Dprofile.only=true"), mojo.trainingSettings().jvmArgs(),
                "the training jvmArgs stay the startup profile's; the cache's launches take jdkAotCacheJvmArgs");

        set(mojo, "jdkAotCacheStrict", null);
        set(mojo, "jdkAotCacheVerifyProbes", null);
        AotCacheSettings partial = mojo.settings();
        assertFalse(partial.strict(), "only the parameters that are set replace a default");
        assertEquals(AotCacheSettings.DEFAULT_VERIFY_PROBES, partial.verifyProbes());
    }

    @Test
    void anUnusableValueFailsNamingIt() {
        set(mojo, "jdkAotCacheTarget", "nested");
        MojoFailureException target = assertThrows(MojoFailureException.class, mojo::target);
        assertTrue(target.getMessage().contains("'layout'") && target.getMessage().contains("'singleJar'"),
                target.getMessage());

        set(mojo, "jdkAotCacheVerifyProbes", 0);
        MojoFailureException probes = assertThrows(MojoFailureException.class, mojo::settings);
        assertTrue(probes.getMessage().contains("verifyProbes"), probes.getMessage());
    }

    @Test
    void everyParameterIsAJdkAotCacheProperty() throws IOException {
        Map<String, Map<String, String>> parameters = parameterFields(JdkAotCacheMojo.class);
        for (String name : List.of("enabled", "target", "strict", "jvmArgs", "verifyProbes", "minCoverage")) {
            String field = "jdkAotCache" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            Map<String, String> elements = parameters.get(field);
            assertNotNull(elements, () -> "no @Parameter field " + field);
            assertEquals("micronaut.runner.jdkAotCache." + name, elements.get("property"), field);
            assertEquals(name.equals("enabled") ? "false" : null, elements.get("defaultValue"),
                    () -> field + ": only the switch has a default; the others leave runner-build's in place");
        }
    }

    @Test
    void disabledTheGoalLogsOneLineAndDoesNothing() throws Exception {
        mojo.execute();

        assertEquals(List.of("Skipping the JDK AOT cache: set micronaut.runner.jdkAotCache.enabled=true to train"
                + " one"), log.infos);
        assertFalse(buildDirectory.toFile().exists(), "nothing is written");
    }

    @Test
    void enabledTheGoalLooksForTheRunnerJar() {
        // What -Dmicronaut.runner.jdkAotCache.enabled=true sets.
        set(mojo, "jdkAotCacheEnabled", true);

        MojoFailureException missing = assertThrows(MojoFailureException.class, mojo::execute);

        assertEquals("There is no Runner JAR at " + buildDirectory.resolve("demo-1.0.jar") + " to train a JDK AOT"
                + " cache for: list mn-runner:jdk-aot-cache after mn-runner:package", missing.getMessage());
        MojoFailureException layoutMissing = assertThrows(MojoFailureException.class, layout::execute);
        assertTrue(layoutMissing.getMessage().contains(buildDirectory.resolve("demo-1.0.jar").toString()),
                layoutMissing.getMessage());
    }

    @Test
    void bothGoalsTakeTheRunnerJarFromThePackagingGoal() {
        assertEquals(PackageMojo.archiveFile(buildDirectory.toFile(), "demo-1.0", null), mojo.runnerJar());
        assertEquals(PackageMojo.archiveFile(buildDirectory.toFile(), "demo-1.0", null), layout.runnerJar());
        set(mojo, "classifier", "runner");
        set(layout, "classifier", "runner");
        assertEquals(buildDirectory.resolve("demo-1.0-runner.jar").toFile(), mojo.runnerJar());
        assertEquals(buildDirectory.resolve("demo-1.0-runner.jar").toFile(), layout.runnerJar());
        assertEquals(buildDirectory.resolve("micronaut-runner/jdk-aot-cache"), mojo.outputDirectoryOfTheCache());
        assertEquals(buildDirectory.resolve("micronaut-runner/layout"), layout.destination());
    }

    @Test
    void aBuildContextToolchainWinsOverJavaHome() {
        assertEquals(Path.of(System.getProperty("java.home")), mojo.java().getParent().getParent(),
                "without a toolchain, the JDK that runs Maven");

        Path toolchainJava = temp.resolve("toolchain-jdk/bin/java");
        set(mojo, "toolchainManager", new OneToolchain(toolchainJava.toString()));
        set(layout, "toolchainManager", new OneToolchain(toolchainJava.toString()));
        assertEquals(toolchainJava, mojo.java());
        assertEquals(toolchainJava, layout.java());
    }

    private static void set(Object goal, String name, Object value) {
        for (Class<?> type = goal.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(goal, value);
                return;
            } catch (NoSuchFieldException e) {
                // declared by a superclass
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not set " + name, e);
            }
        }
        throw new IllegalStateException("No field " + name);
    }

    /**
     * Reads the {@code @Parameter} fields of a goal. The annotation has {@code CLASS} retention, so it is read from
     * the class file rather than by reflection.
     */
    private static Map<String, Map<String, String>> parameterFields(Class<?> goal) throws IOException {
        byte[] bytes;
        try (InputStream in = goal.getResourceAsStream(goal.getSimpleName() + ".class")) {
            assertNotNull(in, () -> goal + " is not on the test class path");
            bytes = in.readAllBytes();
        }
        Map<String, Map<String, String>> fields = new TreeMap<>();
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
                    fields.put(field.fieldName().stringValue(), elements);
                }
            }
        }
        return fields;
    }

    /** A Maven log that keeps the informational lines. */
    private static final class RecordingLog extends SystemStreamLog {

        private final List<String> infos = new ArrayList<>();

        @Override
        public void info(CharSequence content) {
            infos.add(String.valueOf(content));
        }
    }

    /** A build in which no toolchain was selected. */
    private static class NoToolchains implements ToolchainManager {

        @Override
        public Toolchain getToolchainFromBuildContext(String type, MavenSession context) {
            return null;
        }

        @Override
        public List<Toolchain> getToolchains(MavenSession session, String type, Map<String, String> requirements) {
            return List.of();
        }
    }

    /** A build in which the toolchains plugin selected a JDK. */
    private static final class OneToolchain extends NoToolchains {

        private final String java;

        private OneToolchain(String java) {
            this.java = java;
        }

        @Override
        public Toolchain getToolchainFromBuildContext(String type, MavenSession context) {
            return new Toolchain() {
                @Override
                public String getType() {
                    return "jdk";
                }

                @Override
                public String findTool(String toolName) {
                    return "java".equals(toolName) ? java : null;
                }
            };
        }
    }
}
