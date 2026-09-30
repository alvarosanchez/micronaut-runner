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
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Rejects a Shadow archive that shares a runner archive's output before either producer executes.
 *
 * <p>A Runner task and a Shadow task form a pair when they write the same archive name by default:
 * {@code micronautRunnerJar} and {@code shadowJar}, and, with Micronaut AOT, {@code optimizedMicronautRunnerJar}
 * and {@code optimizedJitJarAll}. Every task of every pair depends on this one, which checks all of the pairs,
 * so a build that requests one archive also learns that another pair would collide.</p>
 *
 * <p>This task deliberately declares no outputs, so Gradle runs the validation even when the archive tasks
 * are up to date or restored from the build cache. The archive locations are internal rather than file
 * inputs: only their relationship matters, and validation must not introduce producer dependencies or
 * include generated archive contents in another task's cache key.</p>
 */
@DisableCachingByDefault(because = "The collision must be checked before every archive task execution")
public abstract class ValidateShadowArchiveCollision extends DefaultTask {

    /**
     * The Runner and Shadow tasks that must not write one file, with where each one writes.
     *
     * @return the pairs
     */
    @Internal
    public abstract ListProperty<ArchivePair> getArchivePairs();

    /** Rejects equal output locations before either archive producer of a pair executes. */
    @TaskAction
    public void validate() {
        List<String> collisions = new ArrayList<>();
        for (ArchivePair pair : getArchivePairs().get()) {
            if (pair.shadowArchive().equals(pair.runnerArchive())) {
                collisions.add("The shadow plugin is configured to write " + pair.shadowArchive()
                        + " from " + pair.shadowTask() + ", which is also the " + pair.runnerTask() + " output."
                        + " A runner jar and a shaded jar are different archives and cannot share a file name."
                        + " Give one of them another classifier, for example " + pair.runnerTask()
                        + " { archiveClassifier = '" + exampleClassifier(pair) + "' }.");
            }
        }
        if (!collisions.isEmpty()) {
            throw new GradleException(String.join(System.lineSeparator(), collisions));
        }
    }

    /**
     * A classifier to suggest for a pair's Runner task. The two Runner tasks get different suggestions, so that
     * following both leaves them with distinct archives.
     *
     * @param pair the colliding pair
     * @return the suggested classifier
     */
    private static String exampleClassifier(ArchivePair pair) {
        return MicronautRunnerPlugin.OPTIMIZED_TASK_NAME.equals(pair.runnerTask())
                ? "executable-optimized" : "executable";
    }

    /**
     * A Runner task and the Shadow task that writes the same archive name by default.
     *
     * @param runnerTask    the name of the Runner task
     * @param runnerArchive where the Runner task writes
     * @param shadowTask    the name of the Shadow task
     * @param shadowArchive where the Shadow task writes
     */
    public record ArchivePair(String runnerTask, File runnerArchive, String shadowTask, File shadowArchive)
            implements Serializable {
    }
}
