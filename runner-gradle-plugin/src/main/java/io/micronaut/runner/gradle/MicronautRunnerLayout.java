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

import io.micronaut.runner.build.AotLayout;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes the extracted layout of the Runner JAR, the application JAR and {@code lib/}, for a build that trains
 * its JDK AOT cache elsewhere, such as in a container image. It is what
 * {@code java -Dmicronaut.runner.mode=extract} writes, run with the project's toolchain, and every file carries
 * the modification time {@code 1980-02-01T00:00:00Z}.
 *
 * <p>The task is experimental and belongs to this interim plugin only.</p>
 *
 * @since 1.0
 */
@DisableCachingByDefault(because = "a JDK AOT cache checks file modification times, which a build-cache restore"
        + " does not keep")
public abstract class MicronautRunnerLayout extends DefaultTask {

    /** Gradle creates the task. */
    public MicronautRunnerLayout() {
    }

    /**
     * The Runner JAR to extract. The plugin sets the archive of {@code micronautRunnerJar}.
     *
     * @return the Runner JAR
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getArchiveFile();

    /**
     * The JVM that runs the extraction. The plugin sets the launcher of the project's toolchain.
     *
     * @return the launcher
     */
    @Nested
    public abstract Property<JavaLauncher> getJavaLauncher();

    /**
     * The layout, replaced as a whole. The plugin sets {@code build/micronaut-runner/layout}.
     *
     * @return the directory
     */
    @OutputDirectory
    public abstract DirectoryProperty getDestinationDirectory();

    /**
     * Writes the layout.
     *
     * @throws IOException if the extraction fails
     */
    @TaskAction
    public void write() throws IOException {
        Path java = getJavaLauncher().get().getExecutablePath().getAsFile().toPath();
        Path destination = getDestinationDirectory().get().getAsFile().toPath();
        try {
            AotLayout.Result layout = AotLayout.write(java, getArchiveFile().get().getAsFile().toPath(), destination,
                    AotLayout.DEFAULT_TIMEOUT);
            getLogger().lifecycle("Wrote the layout " + layout.applicationJar().getFileName() + " with "
                    + layout.libraries().size() + " JARs in " + AotLayout.LIBRARY_DIRECTORY + "/ to " + destination);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("Writing the layout was interrupted", e);
        }
    }
}
