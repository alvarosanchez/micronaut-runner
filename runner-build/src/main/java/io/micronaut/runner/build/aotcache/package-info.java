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
 * Trains, verifies and describes a JDK AOT cache (JEP 483, JEP 514) for an application launched with
 * {@code java -jar}: a two-step recording and creation through the
 * {@link io.micronaut.runner.build.training.TrainingDriver}, a verification gate, the launch argfile and the
 * identity file.
 *
 * <p>The package knows nothing about the Runner JAR: it takes a directory and the name of the JAR to launch in
 * it. It depends on {@code java.base}, {@link io.micronaut.runner.build.BuildLogger} and
 * {@code io.micronaut.runner.build.training} only.</p>
 *
 * <p>Runner-only; the Micronaut Docker support trains inside images with plugin-local scripts
 * (micronaut-projects/micronaut-gradle-plugin#1379, micronaut-projects/micronaut-maven-plugin#1721).</p>
 *
 * <p>Every public type is experimental and outside the stable API: it may change in any release.</p>
 */
package io.micronaut.runner.build.aotcache;
