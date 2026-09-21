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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the parts of the launcher that do not need a packaged application: how it picks the main method
 * of a class, how it enters it, and how it identifies the archive it runs from.
 *
 * <p>Starting a real archive end to end belongs to the integration stage, which runs {@code java -jar}
 * over an archive the packager produced.</p>
 */
class LauncherTest {

    /** What the sample main methods below recorded, in call order. */
    static final List<String> CALLS = new ArrayList<>();

    /** The exception {@link Throwing} throws, so that a test can assert on its identity. */
    static final RuntimeException FAILURE = new IllegalStateException("from the application");

    @TempDir
    Path temporary;

    /**
     * Records a call from one of the sample main methods.
     *
     * @param value what was called
     */
    static void called(String value) {
        CALLS.add(value);
    }

    @BeforeEach
    void clearCalls() {
        CALLS.clear();
    }

    @Test
    void invokesAStaticMainWithTheArguments() throws Throwable {
        Launcher.invokeMain(StaticArgs.class, new String[] {"one", "two"});
        assertEquals(List.of("static-args:one,two"), CALLS);
    }

    @Test
    void prefersAnInstanceMainWithArgumentsOverAStaticMainWithout() throws Throwable {
        Method chosen = Launcher.findMainMethod(InstanceArgsBeatsStaticNoArgs.class);
        assertNotNull(chosen);
        assertEquals(1, chosen.getParameterCount(),
                "the String[] form wins even when only the no-argument form is static");

        Launcher.invokeMain(InstanceArgsBeatsStaticNoArgs.class, new String[] {"x"});
        assertEquals(List.of("instance-args:x"), CALLS);
    }

    @Test
    void prefersAnInheritedMainWithArgumentsOverADeclaredMainWithout() throws Throwable {
        Launcher.invokeMain(Derived.class, new String[0]);
        assertEquals(List.of("base-args"), CALLS,
                "the whole hierarchy is searched for main(String[]) before main() is considered");
    }

    @Test
    void invokesAnInstanceMainWithoutArgumentsOnANewInstance() throws Throwable {
        Launcher.invokeMain(NoArgsInstance.class, new String[0]);
        assertEquals(List.of("constructed", "instance-no-args"), CALLS);
    }

    @Test
    void ignoresAPrivateMain() throws Throwable {
        Launcher.invokeMain(PrivateMain.class, new String[0]);
        assertEquals(List.of("public-no-args"), CALLS);
    }

    @Test
    void rejectsAClassWithoutAMainMethod() {
        assertNull(Launcher.findMainMethod(NoMain.class));
        assertThrows(NoSuchMethodException.class, () -> Launcher.invokeMain(NoMain.class, new String[0]));
    }

    @Test
    void rejectsAnInstanceMainWithoutAnUsableConstructor() {
        assertThrows(NoSuchMethodException.class,
                () -> Launcher.invokeMain(NoConstructor.class, new String[0]));
        assertThrows(IllegalAccessException.class,
                () -> Launcher.invokeMain(PrivateConstructor.class, new String[0]));
    }

    @Test
    void letsTheApplicationThrowableThrough() {
        RuntimeException thrown = assertThrows(IllegalStateException.class,
                () -> Launcher.invokeMain(Throwing.class, new String[0]));
        assertSame(FAILURE, thrown, "the launcher must not wrap what the application throws");
    }

    @Test
    void entersThroughTheRegisteredEntryPointOnlyOnce() {
        Launcher.clearRegistration();
        try {
            Entry entry = new Entry() {
                @Override
                public void run(String[] args) {
                    called("entry:" + args.length);
                }
            };
            Launcher.register(entry);
            assertSame(entry, Launcher.registration());
            assertThrows(IllegalStateException.class, () -> Launcher.register(entry));
            assertThrows(IllegalArgumentException.class, () -> Launcher.register(null));
        } finally {
            Launcher.clearRegistration();
        }
        assertNull(Launcher.registration());
    }

    @Test
    void mapsEveryModeToItsTool() {
        assertEquals("io.micronaut.runner.tools.Extract", Launcher.toolClassName(Launcher.MODE_EXTRACT));
        assertEquals("io.micronaut.runner.tools.Inspect", Launcher.toolClassName(Launcher.MODE_INSPECT));
        assertEquals("io.micronaut.runner.tools.ListEntries", Launcher.toolClassName(Launcher.MODE_LIST));
        assertNull(Launcher.toolClassName(Launcher.MODE_RUN));
        assertNull(Launcher.toolClassName("nonsense"));
    }

    @Test
    void fallsBackToAClassPathThatNamesASingleFile() throws IOException {
        File jar = temporary.resolve("application.jar").toFile();
        Files.write(jar.toPath(), "not really a jar".getBytes(StandardCharsets.UTF_8));

        assertEquals(jar, Launcher.singleClassPathFile(jar.getPath()));
        assertNull(Launcher.singleClassPathFile(null));
        assertNull(Launcher.singleClassPathFile(""));
        assertNull(Launcher.singleClassPathFile(temporary.toString()), "a directory is not an archive");
        assertNull(Launcher.singleClassPathFile(jar.getPath() + File.pathSeparator + jar.getPath()),
                "a class path with several entries cannot identify the archive");
        assertNull(Launcher.singleClassPathFile(temporary.resolve("absent.jar").toString()));
    }

    @Test
    void locatesTheArchiveFromTheCodeSourceOrTheClassPath() {
        File located = Launcher.codeSourceFile();
        if (located != null) {
            assertTrue(located.isFile(), "the code source must be an archive when it is used at all");
        }
    }

    /** A plain static main taking arguments. */
    static class StaticArgs {

        /**
         * Records the arguments it was given.
         *
         * @param args the arguments
         */
        public static void main(String[] args) {
            called("static-args:" + String.join(",", args));
        }
    }

    /** Declares both forms, so that the preference for the {@code String[]} one is observable. */
    static class InstanceArgsBeatsStaticNoArgs {

        /** Never chosen, because the form taking arguments wins even though this one is static. */
        public static void main() {
            called("static-no-args");
        }

        /**
         * The form the launcher must choose.
         *
         * @param args the arguments
         */
        public void main(String[] args) {
            called("instance-args:" + String.join(",", args));
        }
    }

    /** Declares the form taking arguments; a subclass hides it behind a no-argument form. */
    static class Base {

        /**
         * The form the launcher must choose, even from a subclass.
         *
         * @param args the arguments
         */
        public static void main(String[] args) {
            called("base-args");
        }
    }

    /** Declares only the no-argument form, which the inherited one must beat. */
    static class Derived extends Base {

        /** Never chosen: the hierarchy holds a form taking arguments. */
        public static void main() {
            called("derived-no-args");
        }
    }

    /** An instance main with no arguments, reached through a package-private constructor. */
    static class NoArgsInstance {

        NoArgsInstance() {
            called("constructed");
        }

        /** The form the launcher must choose. */
        void main() {
            called("instance-no-args");
        }
    }

    /** A private main is not a candidate at all. */
    static class PrivateMain {

        /** Never chosen, because it is private. */
        public static void main() {
            called("public-no-args");
        }

        private static void main(String[] args) {
            called("private-args");
        }
    }

    /** Throws the shared failure, so that a test can assert the launcher rethrows that instance. */
    static class Throwing {

        /**
         * Always throws.
         *
         * @param args the arguments
         */
        public static void main(String[] args) {
            throw FAILURE;
        }
    }

    /** No main method at all. */
    static class NoMain {
    }

    /** An instance main whose class has no no-argument constructor. */
    static class NoConstructor {

        NoConstructor(String unused) {
        }

        /** Never reached. */
        void main() {
            called("never");
        }
    }

    /** An instance main whose only constructor is private. */
    static final class PrivateConstructor {

        private PrivateConstructor() {
        }

        /** Never reached. */
        void main() {
            called("never");
        }
    }
}
