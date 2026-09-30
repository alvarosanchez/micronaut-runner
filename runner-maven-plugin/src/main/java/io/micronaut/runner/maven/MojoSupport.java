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

import io.micronaut.runner.build.RunnerJarReader;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** What the goals that run the application share: the JDK they run it with and the check for a Runner JAR. */
final class MojoSupport {

    private MojoSupport() {
    }

    /**
     * The {@code java} of the JDK the {@code maven-toolchains-plugin} selected, or else of the JDK that runs Maven.
     *
     * @param toolchainManager the toolchain manager, or {@code null} outside Maven
     * @param session          the build session
     * @param log              where the choice of a toolchain is reported
     * @param activity         what the JDK is used for, which starts the report
     * @return the executable
     */
    static Path java(ToolchainManager toolchainManager, MavenSession session, Log log, String activity) {
        Toolchain toolchain = toolchainManager == null ? null
                : toolchainManager.getToolchainFromBuildContext("jdk", session);
        if (toolchain != null) {
            String tool = toolchain.findTool("java");
            if (tool != null) {
                log.info(activity + " with the java of the toolchain " + toolchain);
                return Path.of(tool);
            }
        }
        Path home = Path.of(System.getProperty("java.home"));
        Path java = home.resolve("bin").resolve("java");
        return Files.isExecutable(java) ? java : home.resolve("bin").resolve("java.exe");
    }

    /**
     * Whether a file is a Runner JAR.
     *
     * @param archive the file
     * @return {@code true} when it is one
     */
    static boolean isRunnerJar(File archive) {
        try {
            return archive.isFile() && RunnerJarReader.isRunnerJar(archive.toPath());
        } catch (IOException e) {
            return false;
        }
    }
}
