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

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;

/**
 * Rejects a Shadow archive that shares the runner archive's output before either producer executes.
 *
 * <p>This task deliberately declares no outputs, so Gradle runs the validation even when the archive tasks
 * are up to date or restored from the build cache. The archive locations are internal rather than file
 * inputs: only their relationship matters, and validation must not introduce producer dependencies or
 * include generated archive contents in another task's cache key.</p>
 */
@DisableCachingByDefault(because = "The collision must be checked before every archive task execution")
public abstract class ValidateShadowArchiveCollision extends DefaultTask {

    /**
     * The runner archive location.
     *
     * @return the runner archive
     */
    @Internal
    public abstract RegularFileProperty getRunnerArchive();

    /**
     * The Shadow archive location.
     *
     * @return the Shadow archive
     */
    @Internal
    public abstract RegularFileProperty getShadowArchive();

    /** Rejects equal output locations before either archive producer executes. */
    @TaskAction
    public void validate() {
        File runner = getRunnerArchive().get().getAsFile();
        File shadow = getShadowArchive().get().getAsFile();
        if (shadow.equals(runner)) {
            throw new GradleException("The shadow plugin is configured to write " + shadow
                    + " from shadowJar, which is also the micronautRunnerJar output. A runner jar and a"
                    + " shaded jar are different archives and cannot share a file name. Give one of them"
                    + " another classifier, for example micronautRunnerJar { archiveClassifier = 'runner' }.");
        }
    }
}
