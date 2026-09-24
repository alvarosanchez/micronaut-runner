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

/**
 * The logger {@link BuildLogger#systemErr()} hands out, which prefixes every line so that the origin of a
 * message is obvious in a build log it shares with everything else.
 */
final class SystemErrBuildLogger implements BuildLogger {

    static final SystemErrBuildLogger INSTANCE = new SystemErrBuildLogger();

    private SystemErrBuildLogger() {
    }

    @Override
    public void info(String message) {
        System.err.println("[micronaut-runner] " + message);
    }

    @Override
    public void warn(String message) {
        System.err.println("[micronaut-runner] WARNING: " + message);
    }
}
