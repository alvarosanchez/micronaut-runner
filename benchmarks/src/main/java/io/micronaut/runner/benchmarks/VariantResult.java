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

import java.util.List;

/**
 * Everything the report knows about one variant: what it was, whether it could be built, every raw sample
 * it produced, and the summary of the measured ones.
 *
 * @param variant     the variant, including its command line and why it may be unavailable
 * @param sizeBytes   the size of its artifact, or {@code -1} when there is none
 * @param samples     every run, warm-up runs included and flagged as such
 * @param readiness   the summary of the measured readiness times, or {@code null} when there are none
 * @param framework   the summary of the framework's own reported startup times, or {@code null}
 * @param failures    runs that never answered, with the reason, in the order they happened
 */
record VariantResult(Variant variant,
                     long sizeBytes,
                     List<StartupSample> samples,
                     Statistics readiness,
                     Statistics framework,
                     List<String> failures) {
}
