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
/**
 * The settings of a training run: one launch of an application from the archive a build produced, to learn
 * something from it, such as the classes it loads at startup or a JDK cache.
 *
 * <p><b>Stable API.</b> {@link io.micronaut.runner.build.training.TrainingSettings} and its
 * {@link io.micronaut.runner.build.training.TrainingSettings.Builder Builder} are stable API for all of 1.x,
 * like {@link io.micronaut.runner.build.StartupProfileRecorder}, which takes them: a build plugin built against
 * one 1.x release runs against any later one. From 1.0.0 the build's binary-compatibility check compares them
 * with the last release and fails on an incompatible change.</p>
 *
 * <p>No annotation marks them as stable: Micronaut's {@code Experimental} annotation is not kept in class files
 * or shown in the Javadoc, so this text says it.</p>
 */
package io.micronaut.runner.build.training;
