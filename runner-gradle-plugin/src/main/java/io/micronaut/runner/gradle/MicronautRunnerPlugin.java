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

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.AppliedPlugin;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Packages a Micronaut application as a runner jar: one executable archive in which every dependency
 * remains an intact nested jar.
 *
 * <p>The plugin registers the {@value #TASK_NAME} task and wires it into {@code assemble}. It
 * needs no configuration in a project that already applies the {@code application} plugin: the main class
 * comes from the {@code application} block, the application classes and resources from the main source
 * set's output, and the dependencies from the runtime classpath in resolution order. Only a project that
 * applies a Shadow plugin also gets {@value #SHADOW_COLLISION_TASK_NAME}, which both archive tasks depend
 * on.</p>
 *
 * @since 1.0
 */
public class MicronautRunnerPlugin implements Plugin<Project> {

    /** The name of the task this plugin registers. */
    public static final String TASK_NAME = "micronautRunnerJar";

    /**
     * The task that checks Runner and Shadow output locations before either producer executes. It exists
     * only in a project that applies a Shadow plugin.
     */
    public static final String SHADOW_COLLISION_TASK_NAME = "validateMicronautRunnerShadowOutputs";

    /**
     * The default archive classifier. It matches the one the Shadow plugin uses, so build scripts,
     * Dockerfiles and CI jobs that already refer to the shaded artifact keep working unchanged.
     */
    public static final String DEFAULT_CLASSIFIER = "all";

    private static final String SHADOW_PLUGIN = "com.gradleup.shadow";
    private static final String LEGACY_SHADOW_PLUGIN = "com.github.johnrengelman.shadow";

    @Override
    public void apply(Project project) {
        project.getPluginManager().withPlugin("java", unused -> configure(project));
    }

    private void configure(Project project) {
        SourceSet main = project.getExtensions().getByType(SourceSetContainer.class)
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        Configuration runtimeClasspath = project.getConfigurations()
                .getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);

        TaskProvider<MicronautRunnerJar> runnerJar = project.getTasks()
                .register(TASK_NAME, MicronautRunnerJar.class, task -> {
                    task.setGroup(LifecycleBasePlugin.BUILD_GROUP);
                    task.setDescription("Packages the application and its dependencies as a runner jar");

                    task.getApplicationOutput().from(main.getOutput());
                    // Dependencies only: the application's own output is a separate layer of the archive.
                    task.getClasspath().from(runtimeClasspath);
                    task.getCoordinates().set(coordinatesOf(runtimeClasspath));

                    task.getCompression().convention("STORED");
                    task.getEntryStub().convention(Boolean.TRUE);
                    task.getMultiRelease().convention(Boolean.FALSE);
                    task.getEnableNativeAccess().convention(Boolean.FALSE);
                    task.getArchiveClassifier().convention(DEFAULT_CLASSIFIER);
                    task.getArchiveFile().convention(defaultArchiveFile(project, task));

                    // The application's own manifest attributes, such as Implementation-Version, which a
                    // directory input cannot carry, come from the jar task's manifest configuration, as Shadow
                    // takes them. The task holds the manifest object and reads it when it executes: wiring it
                    // to the jar task's output would run :jar, and a snapshot taken at configuration time would
                    // go stale under the configuration cache. get() realizes the jar task only when this task
                    // is realized.
                    task.setInheritedManifest(
                            project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class).get().getManifest());

                    JavaApplication application = project.getExtensions().findByType(JavaApplication.class);
                    if (application != null) {
                        task.getMainClass().convention(application.getMainClass());
                    }

                    task.getJavaLauncherVersion().convention(project.provider(() -> {
                        JavaPluginExtension java = project.getExtensions().findByType(JavaPluginExtension.class);
                        return java == null ? null : java.getTargetCompatibility().toString();
                    }));
                });

        project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME, task -> task.dependsOn(runnerJar));

        // A runner jar and a shaded jar are different archives. Writing both to one path silently produces
        // whichever task ran last. A separate task performs the check even when either producer is skipped.
        Action<AppliedPlugin> forbidCollisionWithShadow = unused -> {
            if (project.getTasks().getNames().contains(SHADOW_COLLISION_TASK_NAME)) {
                return; // both Shadow plugin ids are applied
            }
            TaskProvider<Jar> shadowJar = project.getTasks().named("shadowJar", Jar.class);
            TaskProvider<ValidateShadowArchiveCollision> collisionCheck = project.getTasks().register(
                    SHADOW_COLLISION_TASK_NAME, ValidateShadowArchiveCollision.class, task -> {
                        task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
                        task.setDescription("Validates that Runner and Shadow archives have distinct outputs");
                        task.getRunnerArchive().set(runnerJar.flatMap(MicronautRunnerJar::getArchiveFile));
                        task.getShadowArchive().set(shadowJar.flatMap(Jar::getArchiveFile));
                    });
            runnerJar.configure(task -> task.dependsOn(collisionCheck));
            shadowJar.configure(task -> task.dependsOn(collisionCheck));
        };
        project.getPluginManager().withPlugin(SHADOW_PLUGIN, forbidCollisionWithShadow);
        project.getPluginManager().withPlugin(LEGACY_SHADOW_PLUGIN, forbidCollisionWithShadow);
    }

    private Provider<RegularFile> defaultArchiveFile(Project project, MicronautRunnerJar task) {
        BasePluginExtension base = project.getExtensions().getByType(BasePluginExtension.class);
        Provider<String> name = base.getArchivesName()
                .zip(task.getArchiveClassifier(), (archivesName, classifier) -> {
                    StringBuilder fileName = new StringBuilder(archivesName);
                    String version = String.valueOf(project.getVersion());
                    if (!version.isEmpty() && !"unspecified".equals(version)) {
                        fileName.append('-').append(version);
                    }
                    if (!classifier.isEmpty()) {
                        fileName.append('-').append(classifier);
                    }
                    return fileName.append(".jar").toString();
                });
        return project.getLayout().getBuildDirectory().dir("libs").flatMap(dir -> name.map(dir::file));
    }

    /**
     * Captures the Maven coordinates of every resolved dependency, keyed by the absolute path of its file,
     * so the task can record them in the index without holding on to resolution results.
     */
    private Provider<Map<String, String>> coordinatesOf(Configuration runtimeClasspath) {
        return runtimeClasspath.getIncoming().getArtifacts().getResolvedArtifacts().map(artifacts -> {
            Map<String, String> byPath = new LinkedHashMap<>();
            for (ResolvedArtifactResult artifact : artifacts) {
                byPath.put(artifact.getFile().getAbsolutePath(),
                        artifact.getId().getComponentIdentifier().getDisplayName());
            }
            return byPath;
        });
    }

    /**
     * Orders a set of files the way the runtime classpath resolved them.
     *
     * @param files the files
     * @return the same files as a list
     */
    static List<File> ordered(Set<File> files) {
        return new ArrayList<>(files);
    }
}
