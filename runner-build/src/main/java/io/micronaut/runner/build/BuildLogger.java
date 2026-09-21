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
 * Where the packaging library reports what it did and what looked wrong.
 *
 * <p>It is deliberately this small. Gradle and Maven each have their own logging abstraction and neither
 * wants a third one on the class path, so the packaging library declares the two levels it actually uses
 * and each plugin passes a three line adapter. Everything the library says on {@link #warn(String)} also
 * ends up in {@link RunnerJarResult#warnings()}, so a caller that prefers to inspect the result rather than
 * watch the log can use {@link #noOp()}.</p>
 *
 * @since 1.0
 */
public interface BuildLogger {

    /**
     * Reports something that went as expected and is worth seeing at an informational log level.
     *
     * @param message the message, already formatted
     */
    void info(String message);

    /**
     * Reports something the user should probably act on: a duplicate entry that was dropped, a signed
     * dependency whose signatures were removed, a {@code Class-Path} attribute that no longer applies.
     *
     * @param message the message, already formatted
     */
    void warn(String message);

    /**
     * A logger that discards everything, for callers that read {@link RunnerJarResult#warnings()} instead.
     *
     * @return the shared no-op logger
     */
    static BuildLogger noOp() {
        return NoOp.INSTANCE;
    }

    /**
     * A logger that prints to {@link System#err}, for tests and for command line use.
     *
     * @return the shared standard error logger
     */
    static BuildLogger systemErr() {
        return SystemErr.INSTANCE;
    }

    /**
     * The logger {@link #noOp()} hands out.
     */
    final class NoOp implements BuildLogger {

        private static final NoOp INSTANCE = new NoOp();

        private NoOp() {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }
    }

    /**
     * The logger {@link #systemErr()} hands out, which prefixes every line so that the origin of a message
     * is obvious in a build log it shares with everything else.
     */
    final class SystemErr implements BuildLogger {

        private static final SystemErr INSTANCE = new SystemErr();

        private SystemErr() {
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
}
