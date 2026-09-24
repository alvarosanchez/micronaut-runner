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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.attributes.Usage;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the plugin sets up while it is applied: the extension and its {@code micronaut.runner} alias, the
 * archive's naming conventions and the configuration that carries the archive. The builds that exercise them
 * are in the functional tests.
 */
class MicronautRunnerPluginTest {

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

    /** An extension that other plugins extend, as the Micronaut Gradle plugin's own is. */
    public abstract static class MicronautStub implements ExtensionAware {
    }
}
