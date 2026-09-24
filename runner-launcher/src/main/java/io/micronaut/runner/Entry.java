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
package io.micronaut.runner;

/**
 * The application entry point, as seen by the launcher.
 *
 * <p>When the packager can prove the application declares a {@code public static void main(String[])}
 * it generates an implementation of this interface into the application layer and records its name in
 * the index. The launcher then initialises that class and calls {@link #run(String[])} through an
 * {@code invokeinterface}, so entering the application costs no reflection and no method handle
 * invocation.</p>
 *
 * <p>The generated class registers itself from its static initialiser by calling
 * {@code Launcher.register(Entry)}, so the launcher never has to look up a constructor either.</p>
 *
 * <p>This interface is loaded by the class loader that loaded the launcher, not by the runner class
 * loader, so that both sides see the same type.</p>
 *
 * @since 1.0
 */
public interface Entry {

    /**
     * Runs the application.
     *
     * @param args the command line arguments, exactly as passed to the launcher
     * @throws Throwable anything the application throws, which the launcher rethrows unchanged so the
     *                   JVM reports it the way it would for an ordinary main class
     */
    void run(String[] args) throws Throwable;
}
