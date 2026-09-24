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

import io.micronaut.runner.build.RunnerJarOption;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Usage;
import org.gradle.api.plugins.AppliedPlugin;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.jspecify.annotations.Nullable;

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
 * <p>This is the interim plugin, {@code io.micronaut.runner.standalone}. The Micronaut Gradle plugin's
 * {@code io.micronaut.runner} replaces it, with the same task names and the same {@code micronaut { runner {} }}
 * block, and the two refuse to run in one build.</p>
 *
 * <p>The plugin creates the {@value #EXTENSION_NAME} extension, a {@link MicronautRunnerExtension}, which is
 * also {@code micronaut.runner} in a build that applies a Micronaut plugin. It registers the
 * {@value #TASK_NAME} task, wires it into {@code assemble} and exposes its archive as the
 * {@value #ELEMENTS_CONFIGURATION_NAME} configuration. It needs no configuration in a project that already
 * applies the {@code application} plugin: the main class comes from the {@code application} block, the
 * application classes and resources from the main source set's output, and the dependencies from the
 * runtime classpath in resolution order. Only a project that applies a Shadow plugin also gets
 * {@value #SHADOW_COLLISION_TASK_NAME}, which both archive tasks depend on.</p>
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

    /** The name of the extension this plugin creates on the project. */
    public static final String EXTENSION_NAME = "micronautRunner";

    /**
     * The consumable configuration that carries the archive, for another project of the build or for a
     * publication.
     */
    public static final String ELEMENTS_CONFIGURATION_NAME = "micronautRunnerElements";

    /**
     * The {@link Usage} of {@value #ELEMENTS_CONFIGURATION_NAME}. It is its own, so that no Java consumer
     * selects the runner jar by accident.
     */
    public static final String USAGE = "micronaut-runner";

    /**
     * The default archive classifier. It matches the one the Shadow plugin uses, so build scripts,
     * Dockerfiles and CI jobs that already refer to the shaded artifact keep working unchanged.
     */
    public static final String DEFAULT_CLASSIFIER = "all";

    /** The Micronaut Gradle plugin's Runner plugin, which replaces this one. */
    private static final String UPSTREAM_PLUGIN_ID = "io.micronaut.runner";

    /** The extension a Micronaut plugin creates on the project. */
    private static final String MICRONAUT_EXTENSION_NAME = "micronaut";

    /** The name of this plugin's extension inside the {@code micronaut} extension. */
    private static final String MICRONAUT_RUNNER_EXTENSION_NAME = "runner";

    private static final String MIGRATION_MESSAGE = "The io.micronaut.runner plugin from micronaut-gradle-plugin"
            + " builds the Runner JAR in this build. Remove io.micronaut.runner.standalone: the task names and"
            + " micronaut { runner { } } stay the same, and settings in micronautRunner { } move into"
            + " micronaut { runner { } }.";

    private static final String SHADOW_PLUGIN = "com.gradleup.shadow";
    private static final String LEGACY_SHADOW_PLUGIN = "com.github.johnrengelman.shadow";

    @Override
    public void apply(Project project) {
        project.getPluginManager().withPlugin(UPSTREAM_PLUGIN_ID, _ -> {
            throw new GradleException(MIGRATION_MESSAGE);
        });

        MicronautRunnerExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, MicronautRunnerExtension.class);
        extension.getEnabled().convention(true);
        // The packaging library owns the defaults; the conventions only show them. addOpens, addExports and
        // manifestAttributes default to empty, which is a collection property's own initial value.
        extension.getCompression().convention(defaultOf(RunnerJarOption.COMPRESSION));
        extension.getEntryStub().convention(Boolean.valueOf(defaultOf(RunnerJarOption.ENTRY_STUB)));
        extension.getMultiRelease().convention(Boolean.valueOf(defaultOf(RunnerJarOption.MULTI_RELEASE)));
        extension.getEnableNativeAccess().convention(
                Boolean.valueOf(defaultOf(RunnerJarOption.ENABLE_NATIVE_ACCESS)));

        // A Micronaut plugin may be applied before or after this one, and whichever comes last adds the
        // alias. Both happen while plugins are applied, so the Kotlin DSL generates an accessor for it.
        exposeAsMicronautRunner(project, extension);
        project.getPlugins().configureEach(_ -> exposeAsMicronautRunner(project, extension));

        project.getPluginManager().withPlugin("java", _ -> configure(project, extension));
    }

    /**
     * Adds the extension to the {@code micronaut} extension as {@code runner}, once a Micronaut plugin has
     * created it. The lookup is by name, so the plugin needs no Micronaut type on its class path.
     *
     * @param project   the project
     * @param extension the extension
     */
    private static void exposeAsMicronautRunner(Project project, MicronautRunnerExtension extension) {
        if (project.getExtensions().findByName(MICRONAUT_EXTENSION_NAME) instanceof ExtensionAware micronaut
                && micronaut.getExtensions().findByName(MICRONAUT_RUNNER_EXTENSION_NAME) == null) {
            micronaut.getExtensions().add(MicronautRunnerExtension.class, MICRONAUT_RUNNER_EXTENSION_NAME,
                    extension);
        }
    }

    private void configure(Project project, MicronautRunnerExtension extension) {
        SourceSet main = project.getExtensions().getByType(SourceSetContainer.class)
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        Configuration runtimeClasspath = project.getConfigurations()
                .getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
        BasePluginExtension base = project.getExtensions().getByType(BasePluginExtension.class);

        TaskProvider<MicronautRunnerJar> runnerJar = project.getTasks()
                .register(TASK_NAME, MicronautRunnerJar.class, task -> {
                    task.setGroup(LifecycleBasePlugin.BUILD_GROUP);
                    task.setDescription("Packages the application and its dependencies as a runner jar");

                    task.getApplicationOutput().from(main.getOutput());
                    // Dependencies only: the application's own output is a separate layer of the archive.
                    task.getClasspath().from(runtimeClasspath);
                    task.getCoordinates().set(coordinatesOf(runtimeClasspath));

                    configureOptions(task, extension);

                    // An archive task's naming conventions, in the directory a jar is written to.
                    task.getArchiveBaseName().convention(base.getArchivesName());
                    task.getArchiveVersion().convention(project.provider(() -> versionOf(project)));
                    task.getArchiveClassifier().convention(DEFAULT_CLASSIFIER);
                    task.getDestinationDirectory().convention(base.getLibsDirectory());

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
                });

        project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME, task -> task.dependsOn(runnerJar));

        // Its own Usage keeps the archive out of every Java consumer's variant selection: a consumer asks for
        // this configuration by name.
        project.getConfigurations().consumable(ELEMENTS_CONFIGURATION_NAME, elements -> {
            elements.setDescription("The runner jar built by " + TASK_NAME);
            elements.getAttributes().attribute(Usage.USAGE_ATTRIBUTE,
                    project.getObjects().named(Usage.class, USAGE));
            elements.getOutgoing().artifact(runnerJar.flatMap(MicronautRunnerJar::getArchiveFile), artifact -> {
                artifact.setType(ArtifactTypeDefinition.JAR_TYPE);
                artifact.builtBy(runnerJar);
            });
        });

        // A runner jar and a shaded jar are different archives. Writing both to one path silently produces
        // whichever task ran last. A separate task performs the check even when either producer is skipped.
        Action<AppliedPlugin> forbidCollisionWithShadow = _ -> {
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
                        onlyIfEnabled(task, extension);
                    });
            runnerJar.configure(task -> task.dependsOn(collisionCheck));
            shadowJar.configure(task -> task.dependsOn(collisionCheck));
        };
        project.getPluginManager().withPlugin(SHADOW_PLUGIN, forbidCollisionWithShadow);
        project.getPluginManager().withPlugin(LEGACY_SHADOW_PLUGIN, forbidCollisionWithShadow);
    }

    /**
     * Gives a packaging task the extension's options as the conventions of its own, so that a value set on
     * the task wins for that task, and skips the task when the extension is disabled.
     *
     * @param task      the packaging task
     * @param extension the extension
     */
    private static void configureOptions(MicronautRunnerJar task, MicronautRunnerExtension extension) {
        task.getCompression().convention(extension.getCompression());
        task.getEntryStub().convention(extension.getEntryStub());
        task.getMultiRelease().convention(extension.getMultiRelease());
        task.getEnableNativeAccess().convention(extension.getEnableNativeAccess());
        task.getAddOpens().convention(extension.getAddOpens());
        task.getAddExports().convention(extension.getAddExports());
        task.getManifestAttributes().convention(extension.getManifestAttributes());
        task.getOptions().convention(extension.getOptions());
        onlyIfEnabled(task, extension);
    }

    /**
     * Skips a task of this plugin when the extension is disabled.
     *
     * @param task      the task
     * @param extension the extension
     */
    private static void onlyIfEnabled(Task task, MicronautRunnerExtension extension) {
        task.onlyIf(EXTENSION_NAME + ".enabled is true", new Enabled(extension.getEnabled()));
    }

    /**
     * The default of an option with an unconditional default, in the grammar the packaging library reads.
     *
     * @param option the option
     * @return its default
     */
    private static String defaultOf(RunnerJarOption option) {
        return option.defaultValue().orElseThrow(
                () -> new IllegalStateException(option.optionName() + " has no unconditional default"));
    }

    /**
     * The project version as an archive name carries it: none when it is empty or {@code unspecified}.
     *
     * @param project the project
     * @return the version, or {@code null} for none
     */
    private static @Nullable String versionOf(Project project) {
        String version = String.valueOf(project.getVersion());
        return version.isEmpty() || Project.DEFAULT_VERSION.equals(version) ? null : version;
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

    /**
     * The condition under which the plugin's tasks run. It is a class rather than a lambda, so that the
     * configuration cache stores it with the value of the property it reads.
     */
    private static final class Enabled implements Spec<Task> {

        private final Provider<Boolean> enabled;

        private Enabled(Provider<Boolean> enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean isSatisfiedBy(Task task) {
            return enabled.get();
        }
    }
}
