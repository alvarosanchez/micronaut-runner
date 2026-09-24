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
package io.micronaut.runner.benchmarks;

/**
 * Build-time application-cache evidence kept separate from startup timing. The cache file itself is also a
 * component of the variant's complete deployment; its training and preparation cost are not.
 */
record CacheInfo(String mode,
                 String identity,
                 long bytes,
                 long preparationMillis,
                 long trainingMillis,
                 boolean reused,
                 String lifecycle,
                 String verification) {
}
