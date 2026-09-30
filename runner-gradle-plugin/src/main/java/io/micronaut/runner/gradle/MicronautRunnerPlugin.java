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
import io.micronaut.runner.build.StartupProfileRecorder;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.AppliedPlugin;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * runtime classpath in resolution order.</p>
 *
 * <p>A project that applies {@code io.micronaut.aot} together with a Micronaut application or library plugin
 * also gets {@value #OPTIMIZED_TASK_NAME}, in {@code assemble} too. It packages the archive of Micronaut AOT's
 * {@code optimizedJitJar} task as the application layer, with the same dependencies and options, into
 * {@code -all-optimized.jar}, as Shadow users get {@code optimizedJitJarAll} beside {@code shadowJar}.
 * {@value #TASK_NAME} never packages Micronaut AOT output, and this plugin never applies or runs Micronaut AOT
 * on its own.</p>
 *
 * <p>Only a project that applies a Shadow plugin also gets {@value #SHADOW_COLLISION_TASK_NAME}, which each
 * Runner task and the Shadow task that writes the same archive name by default depend on.</p>
 *
 * <p>It also registers {@value #RECORD_TASK_NAME}, which launches the archive and records the classes it loads
 * at startup to {@value io.micronaut.runner.build.StartupProfileRecorder#PROFILE_LOCATION}. The task runs only
 * when it is asked for. Once the file exists, the archive embeds it, and the launcher preloads its classes.</p>
 *
 * <p>Experimentally, and only in this interim plugin, it registers {@value #JDK_AOT_CACHE_TASK_NAME}, which trains
 * and verifies a JDK AOT cache for the archive's extracted layout (or the archive itself) with the project's
 * toolchain, and {@value #LAYOUT_TASK_NAME}, which writes the layout alone. {@code assemble} builds the cache only
 * with {@code jdkAotCache.enabled = true}.</p>
 *
 * @since 1.0
 */
public class MicronautRunnerPlugin implements Plugin<Project> {

    /** The name of the task this plugin registers in every project. */
    public static final String TASK_NAME = "micronautRunnerJar";

    /**
     * The task that packages the Micronaut AOT-optimized application. It exists only in a project that applies
     * {@code io.micronaut.aot} and a Micronaut application or library plugin, which is when Micronaut AOT
     * registers the {@code optimizedJitJar} task whose archive this one packages.
     */
    public static final String OPTIMIZED_TASK_NAME = "optimizedMicronautRunnerJar";

    /**
     * The task that records the startup profile. It launches the application, so it is never part of
     * {@code assemble}, {@code build} or {@code check}.
     */
    public static final String RECORD_TASK_NAME = "recordStartupProfile";

    /**
     * The task that writes the extracted layout, for a build that trains its JDK AOT cache elsewhere. Experimental,
     * and part of this interim plugin only.
     */
    public static final String LAYOUT_TASK_NAME = "micronautRunnerLayout";

    /**
     * The task that trains and verifies a JDK AOT cache. It is part of {@code assemble} only with
     * {@code jdkAotCache.enabled = true}. Experimental, and part of this interim plugin only.
     */
    public static final String JDK_AOT_CACHE_TASK_NAME = "micronautRunnerJdkAotCache";

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

    /**
     * The default archive classifier of {@value #OPTIMIZED_TASK_NAME}. It matches the one Micronaut AOT gives
     * {@code optimizedJitJarAll}, its Shadow JAR of the optimized application.
     */
    public static final String OPTIMIZED_CLASSIFIER = "all-optimized";

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

    /** The Shadow plugins' own task. */
    private static final String SHADOW_JAR = "shadowJar";

    /** micronaut-gradle-plugin's Micronaut AOT plugin. No type of it is used: every lookup is by name. */
    private static final String AOT_PLUGIN = "io.micronaut.aot";

    /**
     * The plugin that micronaut-gradle-plugin's application and library plugins apply. Micronaut AOT registers
     * its tasks only once it is applied.
     */
    private static final String COMPONENT_PLUGIN = "io.micronaut.component";

    /** Micronaut AOT's jar of the optimized application, which {@value #OPTIMIZED_TASK_NAME} packages. */
    private static final String OPTIMIZED_JIT_JAR = "optimizedJitJar";

    /** Micronaut AOT's Shadow JAR of the optimized application, registered when a Shadow plugin is applied. */
    private static final String OPTIMIZED_SHADOW_JAR = "optimizedJitJarAll";

    @Override
    public void apply(Project project) {
        project.getPluginManager().withPlugin(UPSTREAM_PLUGIN_ID, _ -> {
            throw new GradleException(MIGRATION_MESSAGE);
        });

        MicronautRunnerExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, MicronautRunnerExtension.class);
        extension.getEnabled().convention(true);
        // The packaging library owns the defaults; the conventions only show them. addOpens, addExports and
        // manifestAttributes default to empty, which is a collection property's own initial value, and
        // startupClasses has no default of the library's.
        extension.getCompression().convention(defaultOf(RunnerJarOption.COMPRESSION));
        extension.getEntryStub().convention(Boolean.valueOf(defaultOf(RunnerJarOption.ENTRY_STUB)));
        extension.getMultiRelease().convention(Boolean.valueOf(defaultOf(RunnerJarOption.MULTI_RELEASE)));
        extension.getEnableNativeAccess().convention(
                Boolean.valueOf(defaultOf(RunnerJarOption.ENABLE_NATIVE_ACCESS)));
        // A committed startup profile is embedded by convention. The provider reads only whether the file
        // exists, which the configuration cache records as an input; it must not read recordStartupProfile's
        // output property, which would make the archive depend on the task that runs it.
        RegularFile profile = project.getLayout().getProjectDirectory()
                .file(StartupProfileRecorder.PROFILE_LOCATION);
        extension.getStartupClasses().convention(project.getProviders().provider(
                () -> profile.getAsFile().isFile() ? profile : null));
        TrainingSpec.defaults(extension.getTraining());
        JdkAotCacheSpec.defaults(extension.getJdkAotCache());

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

        TaskProvider<MicronautRunnerJar> runnerJar = register(project, extension, TASK_NAME,
                "Packages the application and its dependencies as a runner jar", DEFAULT_CLASSIFIER,
                main.getOutput());

        // The optimized application gets an archive of its own, so -all.jar is the plain application whether or
        // not Micronaut AOT is applied. Micronaut AOT registers optimizedJitJar only once a Micronaut component
        // plugin is applied, so this task exists exactly when its input can.
        project.getPluginManager().withPlugin(AOT_PLUGIN, _ ->
                project.getPluginManager().withPlugin(COMPONENT_PLUGIN, _ ->
                        register(project, extension, OPTIMIZED_TASK_NAME,
                                "Packages the Micronaut AOT-optimized application and its dependencies as a runner jar",
                                OPTIMIZED_CLASSIFIER, optimizedJitJarArchive(project))));

        // Only on request: it launches the application.
        JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        project.getTasks().register(RECORD_TASK_NAME, RecordStartupProfile.class, task -> {
            task.setGroup(LifecycleBasePlugin.BUILD_GROUP);
            task.setDescription("Launches the runner jar and records the classes it loads at startup to "
                    + StartupProfileRecorder.PROFILE_LOCATION + ", which the next " + TASK_NAME + " embeds");
            task.getArchiveFile().convention(runnerJar.flatMap(MicronautRunnerJar::getArchiveFile));
            task.getJavaLauncher().convention(toolchains.launcherFor(java.getToolchain()));
            TrainingSpec.conventions(task.getTraining(), extension.getTraining());
            task.getProfileFile().convention(project.getLayout().getProjectDirectory()
                    .file(StartupProfileRecorder.PROFILE_LOCATION));
            task.getWorkDirectory().convention(project.getLayout().getBuildDirectory()
                    .dir("micronaut-runner/record-startup-profile"));
            onlyIfEnabled(task, extension);
        });

        // The JDK AOT cache and its layout: experimental, and only in this interim plugin.
        project.getTasks().register(LAYOUT_TASK_NAME, MicronautRunnerLayout.class, task -> {
            task.setGroup(LifecycleBasePlugin.BUILD_GROUP);
            task.setDescription("Writes the extracted layout of the runner jar, for a JDK AOT cache trained"
                    + " elsewhere (experimental)");
            task.getArchiveFile().convention(runnerJar.flatMap(MicronautRunnerJar::getArchiveFile));
            task.getJavaLauncher().convention(toolchains.launcherFor(java.getToolchain()));
            task.getDestinationDirectory().convention(project.getLayout().getBuildDirectory()
                    .dir("micronaut-runner/layout"));
            onlyIfEnabled(task, extension);
        });
        TaskProvider<MicronautRunnerJdkAotCache> jdkAotCache = project.getTasks().register(JDK_AOT_CACHE_TASK_NAME,
                MicronautRunnerJdkAotCache.class, task -> {
                    task.setGroup(LifecycleBasePlugin.BUILD_GROUP);
                    task.setDescription("Trains and verifies a JDK AOT cache for the runner jar, in its own"
                            + " directory with a launch argfile (experimental)");
                    task.getArchiveFile().convention(runnerJar.flatMap(MicronautRunnerJar::getArchiveFile));
                    task.getJavaLauncher().convention(toolchains.launcherFor(java.getToolchain()));
                    task.getJdkBuild().convention(task.getJavaLauncher().map(launcher ->
                            launcher.getMetadata().getJavaRuntimeVersion() + " / "
                                    + launcher.getMetadata().getJvmVersion()));
                    JdkAotCacheSpec.conventions(task.getJdkAotCache(), extension.getJdkAotCache());
                    TrainingSpec.conventions(task.getTraining(), extension.getTraining());
                    task.getOutputDirectory().convention(project.getLayout().getBuildDirectory()
                            .dir("micronaut-runner/jdk-aot-cache"));
                    onlyIfEnabled(task, extension);
                });
        // Wired lazily, so that the configuration cache holds the decision with the value it read.
        Provider<List<Object>> cacheOnAssemble = extension.getJdkAotCache().getEnabled()
                .map(enabled -> enabled ? List.<Object>of(jdkAotCache) : List.of());
        project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME, task -> task.dependsOn(cacheOnAssemble));

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
            TaskProvider<ValidateShadowArchiveCollision> collisionCheck = project.getTasks().register(
                    SHADOW_COLLISION_TASK_NAME, ValidateShadowArchiveCollision.class, task -> {
                        task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
                        task.setDescription("Validates that Runner and Shadow archives have distinct outputs");
                        // Read when the task executes or the configuration cache stores it: every task is
                        // registered by then, and the pairs carry where each one writes at that moment.
                        task.getArchivePairs().set(project.provider(() -> archivePairs(project)));
                        onlyIfEnabled(task, extension);
                    });
            // Micronaut AOT registers optimizedJitJarAll in afterEvaluate, and the optimized runner task may be
            // registered after this callback. A name filter reaches a task whenever it is registered, and
            // realizes no other task.
            Set<String> guarded = Set.of(TASK_NAME, SHADOW_JAR, OPTIMIZED_TASK_NAME, OPTIMIZED_SHADOW_JAR);
            project.getTasks().named(guarded::contains).configureEach(task -> task.dependsOn(collisionCheck));
        };
        project.getPluginManager().withPlugin(SHADOW_PLUGIN, forbidCollisionWithShadow);
        project.getPluginManager().withPlugin(LEGACY_SHADOW_PLUGIN, forbidCollisionWithShadow);
    }

    /**
     * Registers a packaging task and wires it into {@code assemble}. Everything the plugin's two packaging tasks
     * share is set here, so that they differ only in their application layer and their classifier.
     *
     * @param project           the project
     * @param extension         the extension
     * @param name              the task name
     * @param description       the task description
     * @param classifier        the archive classifier convention
     * @param applicationOutput the application layer, as {@code ConfigurableFileCollection.from} takes it
     * @return the task
     */
    private TaskProvider<MicronautRunnerJar> register(Project project, MicronautRunnerExtension extension,
                                                      String name, String description, String classifier,
                                                      Object applicationOutput) {
        Configuration runtimeClasspath = project.getConfigurations()
                .getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
        BasePluginExtension base = project.getExtensions().getByType(BasePluginExtension.class);

        TaskProvider<MicronautRunnerJar> runnerJar = project.getTasks()
                .register(name, MicronautRunnerJar.class, task -> {
                    task.setGroup(LifecycleBasePlugin.BUILD_GROUP);
                    task.setDescription(description);

                    task.getApplicationOutput().from(applicationOutput);
                    // Dependencies only: the application's own output is a separate layer of the archive.
                    task.getClasspath().from(runtimeClasspath);
                    task.getCoordinates().set(coordinatesOf(runtimeClasspath));
                    task.getProjectModules().set(projectModulesOf(runtimeClasspath));

                    configureOptions(task, extension);

                    // An archive task's naming conventions, in the directory a jar is written to.
                    task.getArchiveBaseName().convention(base.getArchivesName());
                    task.getArchiveVersion().convention(project.provider(() -> versionOf(project)));
                    task.getArchiveClassifier().convention(classifier);
                    task.getDestinationDirectory().convention(base.getLibsDirectory());

                    // The application's own manifest attributes, such as Implementation-Version, which a
                    // directory input cannot carry and Micronaut AOT's optimizedJitJar does not inherit, come
                    // from the jar task's manifest configuration, as Shadow takes them. The task holds the
                    // manifest object and reads it when it executes: wiring it to the jar task's output would
                    // run :jar, and a snapshot taken at configuration time would go stale under the
                    // configuration cache. get() realizes the jar task only when this task is realized.
                    task.setInheritedManifest(
                            project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class).get().getManifest());

                    JavaApplication application = project.getExtensions().findByType(JavaApplication.class);
                    if (application != null) {
                        task.getMainClass().convention(application.getMainClass());
                    }
                });

        project.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME, task -> task.dependsOn(runnerJar));
        return runnerJar;
    }

    /**
     * The archive of Micronaut AOT's {@code optimizedJitJar} task, looked up by name when the provider is read,
     * which is while the task graph is built. Neither the order the plugins are applied in nor the order of
     * their callbacks matters then, and Micronaut AOT may register the task after this plugin's callback ran.
     *
     * <p>It never falls back to the main source set's output, which would put the plain application into
     * {@code -all-optimized.jar}.</p>
     *
     * @param project the project
     * @return the archive, with its task as the producer
     */
    private static Provider<RegularFile> optimizedJitJarArchive(Project project) {
        return project.provider(() -> {
            if (!project.getTasks().getNames().contains(OPTIMIZED_JIT_JAR)) {
                throw new GradleException(OPTIMIZED_TASK_NAME + " packages the archive of the " + OPTIMIZED_JIT_JAR
                        + " task, which this project does not have although it applies " + AOT_PLUGIN + ". "
                        + TASK_NAME + " packages the application without Micronaut AOT's optimizations.");
            }
            return project.getTasks().named(OPTIMIZED_JIT_JAR, Jar.class);
        }).flatMap(jar -> jar.flatMap(Jar::getArchiveFile));
    }

    /**
     * Each Runner task and the Shadow task that writes the same archive name by default, with where both write.
     *
     * @param project the project
     * @return the plain pair, and the optimized pair when both of its tasks exist
     */
    private static List<ValidateShadowArchiveCollision.ArchivePair> archivePairs(Project project) {
        List<ValidateShadowArchiveCollision.ArchivePair> pairs = new ArrayList<>();
        pairs.add(archivePair(project, TASK_NAME, SHADOW_JAR));
        Set<String> names = project.getTasks().getNames();
        if (names.contains(OPTIMIZED_TASK_NAME) && names.contains(OPTIMIZED_SHADOW_JAR)) {
            pairs.add(archivePair(project, OPTIMIZED_TASK_NAME, OPTIMIZED_SHADOW_JAR));
        }
        return pairs;
    }

    private static ValidateShadowArchiveCollision.ArchivePair archivePair(Project project, String runnerTask,
                                                                         String shadowTask) {
        File runnerArchive = project.getTasks().named(runnerTask, MicronautRunnerJar.class).get()
                .getArchiveFile().get().getAsFile();
        File shadowArchive = project.getTasks().named(shadowTask, Jar.class).get()
                .getArchiveFile().get().getAsFile();
        return new ValidateShadowArchiveCollision.ArchivePair(runnerTask, runnerArchive, shadowTask, shadowArchive);
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
        task.getStartupClasses().convention(extension.getStartupClasses());
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
     * Captures the absolute path of every resolved dependency that another project of this build produced,
     * which the packaging library never rewrites. A project is told apart by its component identifier, never
     * by a display name, which an included build or a custom component can spell differently.
     */
    private Provider<Set<String>> projectModulesOf(Configuration runtimeClasspath) {
        return runtimeClasspath.getIncoming().getArtifacts().getResolvedArtifacts().map(artifacts -> {
            Set<String> modules = new LinkedHashSet<>();
            for (ResolvedArtifactResult artifact : artifacts) {
                if (artifact.getId().getComponentIdentifier() instanceof ProjectComponentIdentifier) {
                    modules.add(artifact.getFile().getAbsolutePath());
                }
            }
            return modules;
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
