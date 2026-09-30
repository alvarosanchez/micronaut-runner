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

import io.micronaut.runner.build.StartupProfileRecorder;
import io.micronaut.runner.build.training.TrainingSettings;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.jvm.toolchain.JavaLauncher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Records the startup profile of the application: launches the runner jar, waits until it is ready, sends
 * it the training workload and writes the classes it loaded to a file that is meant to be committed. The
 * next build of the runner jar embeds the file, and the launcher preloads those classes on a background
 * thread.
 *
 * <p>The task launches the application, so it is never up to date and never part of {@code assemble},
 * {@code build} or {@code check}: it runs only when it is asked for. Whether it succeeds, fails or is
 * cancelled, the application and every process the application started are gone when it ends.</p>
 *
 * <p>The API is experimental: it may change in any release.</p>
 *
 * @since 1.0
 */
@UntrackedTask(because = "Launches the application")
public abstract class RecordStartupProfile extends DefaultTask {

    /** The command that runs this task again, which the profile's header carries. */
    private static final String RERECORD_COMMAND = "./gradlew " + MicronautRunnerPlugin.RECORD_TASK_NAME;

    /** The project directory, against which the profile's path is shown. */
    private final File projectDirectory;

    /** Gradle creates the task, while the project is configured. */
    public RecordStartupProfile() {
        this.projectDirectory = getProject().getProjectDir();
    }

    /**
     * The runner jar to record from. The plugin sets the archive of {@code micronautRunnerJar}, so the
     * recording runs what production runs, with every default packaging transformation.
     *
     * @return the runner jar
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getArchiveFile();

    /**
     * The JVM the application is launched with. The plugin sets the launcher of the project's toolchain,
     * which is the JVM that runs Gradle when the project configures none.
     *
     * @return the launcher
     */
    @Nested
    public abstract Property<JavaLauncher> getJavaLauncher();

    /**
     * How the application is reached, exercised and stopped. Each property takes the value of the extension's
     * {@code training { }} block as its convention.
     *
     * @return the training settings
     */
    @Nested
    public abstract TrainingSpec getTraining();

    /**
     * The profile to write. The plugin sets
     * {@value io.micronaut.runner.build.StartupProfileRecorder#PROFILE_LOCATION} in the project directory,
     * which is where the packaging tasks look for it.
     *
     * @return the profile
     */
    @OutputFile
    public abstract RegularFileProperty getProfileFile();

    /**
     * The working directory of the launch, which is emptied first. The application's output and the JVM's
     * class-load log stay in it. The task takes only a new or empty directory, or one an earlier recording
     * left, and never one that holds the runner jar or the profile: it fails rather than delete anything else.
     *
     * @return the work directory
     */
    @Internal
    public abstract DirectoryProperty getWorkDirectory();

    /**
     * Records the profile.
     *
     * @throws IOException if the application does not start, fails its workload, or the recording holds no
     *                     class of the archive
     */
    @TaskAction
    public void record() throws IOException {
        Path java = getJavaLauncher().get().getExecutablePath().getAsFile().toPath();
        Path profile = getProfileFile().get().getAsFile().toPath();
        long started = System.nanoTime();
        int classes;
        try {
            TrainingSettings settings = TrainingSpec.settings(getTraining());
            classes = StartupProfileRecorder.record(java, getArchiveFile().get().getAsFile().toPath(), settings,
                    getWorkDirectory().get().getAsFile().toPath(), profile, RERECORD_COMMAND,
                    new GradleBuildLogger(getLogger()));
        } catch (IllegalArgumentException e) {
            throw new InvalidUserDataException(String.valueOf(e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("Recording the startup profile was interrupted. The application was"
                    + " stopped and the profile was not changed.", e);
        }
        Path project = projectDirectory.toPath();
        String shown = (profile.startsWith(project) ? project.relativize(profile) : profile)
                .toString().replace('\\', '/');
        getLogger().lifecycle(String.format(Locale.ROOT, "Recorded %d startup classes to %s in %.1f s. Commit"
                        + " it; the next %s embeds it.", classes, shown,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) / 1000.0,
                MicronautRunnerPlugin.TASK_NAME));
    }
}
