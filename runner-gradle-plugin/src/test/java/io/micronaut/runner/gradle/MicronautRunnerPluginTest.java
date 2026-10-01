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
package io.micronaut.runner.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.attributes.Usage;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the plugin sets up while it is applied: the extension and its {@code micronaut.runner} alias, the
 * archives' naming conventions, the configuration that carries the archive and the input of the optimized
 * task. The builds that exercise them are in the functional tests.
 */
class MicronautRunnerPluginTest {

    /** The plugin's internal task, which packages the JAR the extracted layout is written from. */
    private static final String LAYOUT_SOURCE_TASK_NAME = "micronautRunnerLayoutSource";

    @Test
    void theExtensionIsMicronautRunnerWhenAMicronautPluginComesFirst(@TempDir Path directory) {
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
        project.getPluginManager().apply(MicronautStubPlugin.class);
        project.getPluginManager().apply(MicronautRunnerPlugin.class);

        assertSame(extension(project), micronautRunner(project));
    }

    @Test
    void theExtensionIsMicronautRunnerWhenAMicronautPluginComesLast(@TempDir Path directory) {
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
        project.getPluginManager().apply(MicronautRunnerPlugin.class);
        assertNotNull(extension(project), "the extension exists in a build without a Micronaut plugin");
        project.getPluginManager().apply(MicronautStubPlugin.class);

        assertSame(extension(project), micronautRunner(project));
    }

    @Test
    void theArchiveIsNamedAsAnArchiveTaskNamesIt(@TempDir Path directory) {
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).withName("demo").build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(MicronautRunnerPlugin.class);
        MicronautRunnerJar task = project.getTasks()
                .named(MicronautRunnerPlugin.TASK_NAME, MicronautRunnerJar.class).get();
        File libs = project.getLayout().getBuildDirectory().dir("libs").get().getAsFile();

        // An unspecified version is left out, as it is from any archive's name.
        assertEquals(new File(libs, "demo-all.jar"), task.getArchiveFile().get().getAsFile());

        project.setVersion("1.2.3");
        assertEquals(new File(libs, "demo-1.2.3-all.jar"), task.getArchiveFile().get().getAsFile());

        BasePluginExtension base = project.getExtensions().getByType(BasePluginExtension.class);
        base.getArchivesName().set("renamed");
        base.getLibsDirectory().set(project.getLayout().getBuildDirectory().dir("dist"));
        task.getArchiveClassifier().set("");
        assertEquals(new File(libs.getParentFile(), "dist/renamed-1.2.3.jar"),
                task.getArchiveFile().get().getAsFile());

        task.getArchiveBaseName().set("app");
        task.getArchiveVersion().set("9");
        task.getArchiveClassifier().set("executable");
        task.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("out"));
        assertEquals(new File(libs.getParentFile(), "out/app-9-executable.jar"),
                task.getArchiveFile().get().getAsFile());
    }

    @Test
    void theArchiveIsCarriedByItsOwnConsumableConfiguration(@TempDir Path directory) {
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).withName("demo").build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(MicronautRunnerPlugin.class);
        MicronautRunnerJar task = project.getTasks()
                .named(MicronautRunnerPlugin.TASK_NAME, MicronautRunnerJar.class).get();

        Configuration elements = project.getConfigurations()
                .getByName(MicronautRunnerPlugin.ELEMENTS_CONFIGURATION_NAME);
        assertTrue(elements.isCanBeConsumed());
        assertFalse(elements.isCanBeResolved());
        assertEquals(MicronautRunnerPlugin.USAGE,
                elements.getAttributes().getAttribute(Usage.USAGE_ATTRIBUTE).getName(),
                "a Java consumer could select the runner jar");
        List<PublishArtifact> artifacts = List.copyOf(elements.getOutgoing().getArtifacts());
        assertEquals(1, artifacts.size(), () -> "expected the archive alone: " + artifacts);
        assertEquals(task.getArchiveFile().get().getAsFile(), artifacts.get(0).getFile());
        assertTrue(artifacts.get(0).getBuildDependencies().getDependencies(null).contains(task),
                "resolving the configuration does not build the archive");
    }

    @Test
    void theOptimizedTaskPackagesTheOptimizedJitJarAndNeverThePlainApplication(@TempDir Path directory) {
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).withName("demo").build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(MicronautRunnerPlugin.class);
        project.getPluginManager().apply("io.micronaut.aot");
        assertFalse(project.getTasks().getNames().contains(MicronautRunnerPlugin.OPTIMIZED_TASK_NAME),
                "Micronaut AOT registers optimizedJitJar only once a Micronaut component plugin is applied");
        project.getPluginManager().apply("io.micronaut.component");

        MicronautRunnerJar task = project.getTasks()
                .named(MicronautRunnerPlugin.OPTIMIZED_TASK_NAME, MicronautRunnerJar.class).get();
        File libs = project.getLayout().getBuildDirectory().dir("libs").get().getAsFile();
        assertEquals(new File(libs, "demo-all-optimized.jar"), task.getArchiveFile().get().getAsFile());
        assertTrue(project.getTasks().getByName("assemble").getTaskDependencies().getDependencies(null)
                .contains(task), "assemble does not build the optimized archive");

        // Nothing registered optimizedJitJar: the task fails rather than package the main source set's output.
        GradleException failure = assertThrows(GradleException.class,
                () -> task.getApplicationOutput().getFiles());
        assertTrue(failure.getMessage().contains("optimizedJitJar")
                && failure.getMessage().contains("io.micronaut.aot"), failure.getMessage());

        // Registered after the plugin's callback ran, as Micronaut AOT may: the lookup is by name, when read.
        Jar optimizedJitJar = project.getTasks().register("optimizedJitJar", Jar.class,
                jar -> jar.getArchiveClassifier().set("jit")).get();
        assertEquals(Set.of(new File(libs, "demo-jit.jar")), task.getApplicationOutput().getFiles());
        assertTrue(task.getApplicationOutput().getBuildDependencies().getDependencies(null)
                .contains(optimizedJitJar), "the optimized task does not build optimizedJitJar");

        MicronautRunnerJar plain = project.getTasks()
                .named(MicronautRunnerPlugin.TASK_NAME, MicronautRunnerJar.class).get();
        assertFalse(plain.getApplicationOutput().getBuildDependencies().getDependencies(null)
                .contains(optimizedJitJar), "micronautRunnerJar builds optimizedJitJar");
        assertEquals(new File(libs, "demo-all.jar"), plain.getArchiveFile().get().getAsFile());
    }

    /**
     * The internal layout-source task writes under {@code micronautRunnerJar}'s file name into a directory of its
     * own, which a destination a build script sets for every task of the type does not move: it never writes over
     * the shipped archive.
     */
    @Test
    void theLayoutSourceKeepsItsDirectoryWhenEveryRunnerJarTaskIsMoved(@TempDir Path directory) {
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).withName("demo").build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(MicronautRunnerPlugin.class);
        project.setVersion("1.2.3");
        project.getTasks().withType(MicronautRunnerJar.class).configureEach(task -> {
            task.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("dist"));
            task.getArchiveClassifier().set("app");
        });
        File build = project.getLayout().getBuildDirectory().get().getAsFile();

        MicronautRunnerJar shipped = project.getTasks()
                .named(MicronautRunnerPlugin.TASK_NAME, MicronautRunnerJar.class).get();
        MicronautRunnerJar layoutSource = project.getTasks()
                .named(LAYOUT_SOURCE_TASK_NAME, MicronautRunnerJar.class).get();

        assertEquals(new File(build, "dist/demo-1.2.3-app.jar"), shipped.getArchiveFile().get().getAsFile());
        assertEquals(new File(build, "micronaut-runner/layout-source/demo-1.2.3-app.jar"),
                layoutSource.getArchiveFile().get().getAsFile(), "the naming properties apply, the directory not");
    }

    /**
     * {@code micronautRunnerJdkAotCache} extracts the layout-source JAR for the layout target of
     * {@code micronautRunnerJar}'s archive only: the single-JAR target extracts nothing it would package, and a
     * build that points {@code archiveFile} at another Runner JAR has that JAR extracted.
     */
    @Test
    void theCacheExtractsTheLayoutSourceOnlyForTheLayoutOfTheShippedArchive(@TempDir Path directory) {
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).withName("demo").build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply(MicronautRunnerPlugin.class);
        MicronautRunnerJdkAotCache cache = project.getTasks()
                .named(MicronautRunnerPlugin.JDK_AOT_CACHE_TASK_NAME, MicronautRunnerJdkAotCache.class).get();
        File shipped = project.getTasks().named(MicronautRunnerPlugin.TASK_NAME, MicronautRunnerJar.class).get()
                .getArchiveFile().get().getAsFile();
        File layoutSource = project.getTasks().named(LAYOUT_SOURCE_TASK_NAME, MicronautRunnerJar.class).get()
                .getArchiveFile().get().getAsFile();

        assertEquals(layoutSource, cache.layoutSourceFile().get().getAsFile(), "the default target is the layout");

        cache.getJdkAotCache().getTarget().set("singleJar");
        assertEquals(shipped, cache.layoutSourceFile().get().getAsFile());

        cache.getJdkAotCache().getTarget().set("layout");
        File other = project.file("other-all.jar");
        cache.getArchiveFile().set(other);
        assertEquals(other, cache.layoutSourceFile().get().getAsFile(), "a repointed archive is extracted as it is");

        cache.getArchiveFile().set(project.getLayout().file(project.provider(() -> shipped)));
        assertEquals(layoutSource, cache.layoutSourceFile().get().getAsFile(),
                "micronautRunnerJar's archive, however it is set");
    }

    private static MicronautRunnerExtension extension(Project project) {
        return project.getExtensions().getByType(MicronautRunnerExtension.class);
    }

    private static Object micronautRunner(Project project) {
        ExtensionAware micronaut = (ExtensionAware) project.getExtensions().getByName("micronaut");
        return micronaut.getExtensions().getByName("runner");
    }

    /** The one thing a Micronaut plugin does that this plugin relies on: it creates {@code micronaut}. */
    public static class MicronautStubPlugin implements Plugin<Project> {
        @Override
        public void apply(Project project) {
            project.getExtensions().create("micronaut", MicronautStub.class);
        }
    }

    /**
     * Stands in for {@code io.micronaut.aot}, whose id {@code META-INF/gradle-plugins} in this module's test
     * resources gives it. It registers nothing: a test registers {@code optimizedJitJar} itself.
     */
    public static class AotStubPlugin implements Plugin<Project> {
        @Override
        public void apply(Project project) {
        }
    }

    /** Stands in for {@code io.micronaut.component}, which the Micronaut application and library plugins apply. */
    public static class ComponentStubPlugin implements Plugin<Project> {
        @Override
        public void apply(Project project) {
        }
    }

    /** An extension that other plugins extend, as the Micronaut Gradle plugin's own is. */
    public abstract static class MicronautStub implements ExtensionAware {
    }
}
