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
package io.micronaut.runner.build;

import io.micronaut.runner.build.training.TrainingSettings;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A build JVM in miniature: it runs the {@link TrainingDriver} against a fixture that never becomes ready,
 * so that a test can end this JVM and see what becomes of the fixture.
 */
public final class TrainingDriverFork {

    private TrainingDriverFork() {
    }

    /**
     * Runs the driver until the JVM is ended.
     *
     * @param args the {@code java} executable, the fixture jar, the working directory and the report file
     * @throws Exception when the driver gives up, which the test does not wait for
     */
    public static void main(String[] args) throws Exception {
        TrainingSettings settings = TrainingSettings.builder()
                .environment(Map.of(TrainingFixture.MODE, "never-listen", TrainingFixture.REPORT, args[3]))
                .build();
        Path directory = Path.of(args[2]);
        TrainingDriver.run(Path.of(args[0]), Path.of(args[1]), List.of(), settings, directory,
                directory.resolve("application.log"), TrainingDriver.AfterWorkload.NOTHING, BuildLogger.noOp());
    }
}
