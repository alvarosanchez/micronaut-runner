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

import io.micronaut.runner.build.BuildLogger;
import io.micronaut.runner.build.RunnerJarBuilder;
import io.micronaut.runner.build.RunnerJarSpec;
import io.micronaut.runner.build.training.TrainingSettings;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecordStartupProfileMojo} against a stub project: a runner jar built here with
 * {@link RunnerJarBuilder}, where {@code mn-runner:package} would have written it, whose application prints a
 * line and exits. The goal is driven as a plain object, with its parameters set by reflection, as
 * {@link PackageMojoTest} drives the packaging goal.
 */
class RecordStartupProfileMojoTest {

    private static final String MAIN_CLASS = "com.example.App";

    @TempDir
    Path temp;

    private Path buildDirectory;
    private RecordStartupProfileMojo mojo;
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

        log = new RecordingLog();
        mojo = new RecordStartupProfileMojo();
        mojo.setLog(log);
        set("project", project);
        set("session", new MavenSession(null, null, new DefaultMavenExecutionRequest(),
                new DefaultMavenExecutionResult()));
        set("toolchainManager", new NoToolchains());
        set("outputDirectory", buildDirectory.toFile());
        set("finalName", "demo-1.0");
    }

    @Test
    void recordsTheProfileOfThePackagedArchiveUnderTheBaseDirectory() throws Exception {
        packageTheApplication(buildDirectory.resolve("demo-1.0.jar"));
        set("trainingRunToExit", true);

        mojo.execute();

        Path profile = temp.resolve("src/main/micronaut-runner/startup-classes.txt");
        List<String> lines = Files.readAllLines(profile);
        assertTrue(lines.contains(MAIN_CLASS), lines::toString);
        assertTrue(lines.contains("# re-record: mvn package mn-runner:record-startup-profile"), lines::toString);
        assertTrue(lines.contains("# workload: none, the application ran to its exit"), lines::toString);
        assertTrue(Files.readString(buildDirectory.resolve("micronaut-runner/record-startup-profile/application.log"))
                .contains("APP RAN"), "the work directory is under the build directory");
        assertTrue(log.infos.stream().anyMatch(line -> line.startsWith("Recorded ")
                && line.contains(" startup classes to src/main/micronaut-runner/startup-classes.txt in ")
                && line.endsWith("Commit it; the next mn-runner:package embeds it.")), log.infos::toString);
    }

    @Test
    void readsTheArchiveThatWasAttachedWithAClassifier() throws Exception {
        packageTheApplication(buildDirectory.resolve("demo-1.0-runner.jar"));
        set("classifier", "runner");
        set("trainingRunToExit", true);

        mojo.execute();

        assertTrue(Files.isRegularFile(temp.resolve("src/main/micronaut-runner/startup-classes.txt")));
    }

    @Test
    void withoutARunnerJarItFailsAndSaysWhatToRun() throws Exception {
        MojoFailureException missing = assertThrows(MojoFailureException.class, mojo::execute);
        assertEquals("There is no runner jar at " + buildDirectory.resolve("demo-1.0.jar") + " to record the"
                + " startup profile from: run `mvn package mn-runner:record-startup-profile`", missing.getMessage());

        // What maven-jar-plugin wrote is not a runner jar either.
        Files.createDirectories(buildDirectory);
        Files.write(buildDirectory.resolve("demo-1.0.jar"), new byte[] {'P', 'K', 5, 6});
        MojoFailureException thin = assertThrows(MojoFailureException.class, mojo::execute);
        assertTrue(thin.getMessage().contains("mvn package mn-runner:record-startup-profile"), thin.getMessage());
        assertFalse(Files.exists(temp.resolve("src/main/micronaut-runner")));
    }

    @Test
    void aWorkDirectoryNoRecordingLeftFailsTheGoalAndIsKept() throws Exception {
        packageTheApplication(buildDirectory.resolve("demo-1.0.jar"));
        set("trainingRunToExit", true);
        Path work = buildDirectory.resolve("micronaut-runner/record-startup-profile");
        Path stray = Files.writeString(Files.createDirectories(work).resolve("notes.txt"), "not the recorder's");

        MojoFailureException refused = assertThrows(MojoFailureException.class, mojo::execute);

        assertTrue(refused.getMessage().contains(work.toString()) && refused.getMessage().contains("not empty"),
                refused.getMessage());
        assertTrue(Files.exists(stray), "nothing is deleted");
        assertFalse(Files.exists(temp.resolve("src/main/micronaut-runner")));
    }

    @Test
    void theTrainingParametersMapOntoTheSettings() throws Exception {
        assertEquals(TrainingSettings.defaults(), mojo.trainingSettings(), "unset, the library's defaults apply");

        set("trainingReadinessPath", "/health");
        set("trainingWorkloadPaths", List.of("/hello", "/orders"));
        set("trainingWorkloadRepeat", 3);
        set("trainingWorkloadCommand", List.of("warm-up", "--all"));
        set("trainingStopPath", "/stop");
        set("trainingJvmArgs", List.of("-Xmx256m"));
        set("trainingEnvironment", List.of("ENDPOINTS_STOP_ENABLED=true", "EMPTY=", "URL=http://a/?b=c"));
        set("trainingPortVariable", "SERVER_PORT");
        set("trainingReadinessTimeout", 90);
        set("trainingWorkloadTimeout", 45);
        set("trainingStopTimeout", 10);

        TrainingSettings settings = mojo.trainingSettings();

        assertEquals("/health", settings.readinessPath());
        assertEquals(List.of("/hello", "/orders"), settings.workloadPaths());
        assertEquals(3, settings.workloadRepeat());
        assertEquals(List.of("warm-up", "--all"), settings.workloadCommand());
        assertEquals("/stop", settings.stopPath());
        assertEquals(List.of("-Xmx256m"), settings.jvmArgs());
        assertEquals(Map.of("ENDPOINTS_STOP_ENABLED", "true", "EMPTY", "", "URL", "http://a/?b=c"),
                settings.environment());
        assertEquals("SERVER_PORT", settings.portVariable());
        assertEquals(Duration.ofSeconds(90), settings.readinessTimeout());
        assertEquals(Duration.ofSeconds(45), settings.workloadTimeout());
        assertEquals(Duration.ofSeconds(10), settings.stopTimeout());
    }

    @Test
    void anUnusableTrainingParameterFailsNamingIt() {
        set("trainingEnvironment", List.of("NO_EQUALS"));
        MojoFailureException environment = assertThrows(MojoFailureException.class, mojo::trainingSettings);
        assertTrue(environment.getMessage().contains("NAME=value"), environment.getMessage());

        set("trainingEnvironment", null);
        set("trainingReadinessPath", "health");
        MojoFailureException path = assertThrows(MojoFailureException.class, mojo::trainingSettings);
        assertTrue(path.getMessage().contains("readinessPath"), path.getMessage());

        set("trainingReadinessPath", null);
        set("trainingRunToExit", true);
        set("trainingWorkloadPaths", List.of("/hello"));
        MojoFailureException runToExit = assertThrows(MojoFailureException.class, mojo::trainingSettings);
        assertTrue(runToExit.getMessage().contains("runToExit"), runToExit.getMessage());
    }

    @Test
    void theJavaOfASelectedToolchainIsUsed() {
        assertEquals(Path.of(System.getProperty("java.home")), mojo.java().getParent().getParent(),
                "without a toolchain, the JDK that runs Maven");

        Path toolchainJava = temp.resolve("toolchain-jdk/bin/java");
        set("toolchainManager", new OneToolchain(toolchainJava.toString()));
        assertEquals(toolchainJava, mojo.java());
    }

    /** Builds a runner jar whose application prints a line and exits, where the packaging goal writes it. */
    private void packageTheApplication(Path archive) throws IOException {
        Assumptions.assumeTrue(
                RunnerJarBuilder.class.getResource("/META-INF/micronaut-runner/launcher.jar") != null,
                "the bundled launcher jar is not on the test class path");
        Path classes = temp.resolve("classes");
        Path file = classes.resolve(MAIN_CLASS.replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        ClassDesc system = ClassDesc.of("java.lang.System");
        ClassDesc printStream = ClassDesc.of("java.io.PrintStream");
        Files.write(file, ClassFile.of().build(ClassDesc.of(MAIN_CLASS), type -> type
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withMethodBody("main", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String.arrayType()),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code
                                .getstatic(system, "out", printStream)
                                .ldc("APP RAN")
                                .invokevirtual(printStream, "println",
                                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String))
                                .return_())));
        RunnerJarBuilder.build(RunnerJarSpec.builder()
                .mainClass(MAIN_CLASS)
                .applicationOutput(List.of(classes))
                .dependencies(List.of())
                .output(archive)
                .build(), BuildLogger.noOp());
    }

    private void set(String name, Object value) {
        for (Class<?> type = RecordStartupProfileMojo.class; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(mojo, value);
                return;
            } catch (NoSuchFieldException e) {
                // declared by a superclass
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not set " + name, e);
            }
        }
        throw new IllegalStateException("No field " + name);
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
