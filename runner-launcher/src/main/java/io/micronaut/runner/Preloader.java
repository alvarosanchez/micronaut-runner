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
 * Defines the recorded startup classes on one background thread while the application boots.
 *
 * <p>Without it every archive class is defined when something first asks for it, and most of those requests
 * come from the main thread during the framework's serial bootstrap. The packager can embed the list of
 * classes a recording run of the same archive loaded, in load order, as the index's preload table. This
 * class replays that list on a spare core, so that by the time the main thread asks for a class it is
 * usually already defined. After the archive's classes it loads the JDK classes of the same recording that
 * the JDK's default CDS archive does not hold, through the platform class loader.</p>
 *
 * <p>Each class is loaded with {@code Class.forName(name, false, loader)}: it is loaded and defined, never
 * linked or initialised, so no application code runs on this thread and a class the application does not
 * use costs only its definition. A class that fails to load is skipped; nothing is registered for it, so the
 * application's own later request repeats the lookup and fails exactly as it would have. The class loader
 * needs no change for this: it is parallel capable, and definitions on this thread take the same per-name
 * locks, in the same subclass-before-superclass order, as definitions anywhere else.</p>
 *
 * <p>The launcher reaches this class only through {@link #start(Index, ClassLoader, boolean, long)}, and only
 * when the index carries a list, so without one neither the verifier nor the runtime loads it.</p>
 */
final class Preloader implements Runnable {

    /**
     * System property that turns preloading off when set to exactly {@code "false"}. A recording run of a
     * JAR that already carries a list sets it, so that the new recording is not the old list replayed.
     */
    static final String PRELOAD_PROPERTY = "micronaut.runner.preload";

    /** The name of the preload thread. */
    static final String THREAD_NAME = "micronaut-runner-preload";

    /**
     * The fewest processors the preloader runs with. On a single processor, which inside a container is a
     * CPU quota of one, the thread would compete with the main thread instead of using an idle core.
     */
    static final int MIN_PROCESSORS = 2;

    /** Length of {@code ".class"}. */
    private static final int CLASS_SUFFIX_LENGTH = 6;

    private final Index index;
    private final ClassLoader loader;
    private final ClassLoader jdkLoader;
    private final boolean timing;
    private final long started;

    private Preloader(Index index, ClassLoader loader, ClassLoader jdkLoader, boolean timing, long started) {
        this.index = index;
        this.loader = loader;
        this.jdkLoader = jdkLoader;
        this.timing = timing;
        this.started = started;
    }

    /**
     * Starts the preload thread, unless preloading is switched off or there is no spare processor.
     *
     * @param index   the index, whose preload tables list the classes
     * @param loader  the loader that defines the archive's classes
     * @param timing  whether to print a checkpoint when the lists are done
     * @param started the {@link System#nanoTime()} the launcher's checkpoints count from
     */
    static void start(Index index, ClassLoader loader, boolean timing, long started) {
        if (shouldStart(index.preloadCount() + index.jdkPreloadCount(), System.getProperty(PRELOAD_PROPERTY),
                Runtime.getRuntime().availableProcessors())) {
            // The platform loader delegates to the boot loader, so it finds the JDK classes of both.
            newThread(index, loader, ClassLoader.getPlatformClassLoader(), timing, started).start();
        }
    }

    /**
     * Whether the launcher starts the preload thread.
     *
     * @param count      the number of listed classes, archive and JDK classes together
     * @param property   the value of {@value #PRELOAD_PROPERTY}, or {@code null}
     * @param processors the processors available to the JVM, which inside a container reflects its CPU quota
     * @return {@code true} when there is a list, preloading is not switched off and there are at least
     *         {@value #MIN_PROCESSORS} processors
     */
    static boolean shouldStart(int count, String property, int processors) {
        return count > 0 && !"false".equals(property) && processors >= MIN_PROCESSORS;
    }

    /**
     * Creates the preload thread without starting it: a daemon, so it never keeps the JVM alive, that does not
     * inherit the launcher thread's inheritable thread locals.
     *
     * @param index     the index, whose preload tables list the classes
     * @param loader    the loader that defines the archive's classes
     * @param jdkLoader the loader the listed JDK classes are loaded through
     * @param timing    whether to print a checkpoint when the lists are done
     * @param started   the {@link System#nanoTime()} the launcher's checkpoints count from
     * @return the unstarted thread
     */
    static Thread newThread(Index index, ClassLoader loader, ClassLoader jdkLoader, boolean timing,
            long started) {
        Thread thread = new Thread(null, new Preloader(index, loader, jdkLoader, timing, started), THREAD_NAME,
                0L, false);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Loads every listed class of the archive in list order, then every listed JDK class, and ends. It prints
     * nothing but the timing checkpoint.
     */
    @Override
    public void run() {
        int count = index.preloadCount();
        for (int position = 0; position < count; position++) {
            preload(position);
        }
        int jdkCount = index.jdkPreloadCount();
        for (int position = 0; position < jdkCount; position++) {
            preloadJdk(position);
        }
        if (timing) {
            Launcher.checkpoint(started,
                    "preload finished (" + count + " classes, " + jdkCount + " JDK classes)");
        }
    }

    /**
     * Loads one listed class, skipping it on any failure a class definition can raise: a missing class, a
     * {@link LinkageError} such as a missing superclass or a malformed class file, and a
     * {@link RuntimeException} such as a sealing violation or a stale archive.
     *
     * @param position the position in the list
     */
    private void preload(int position) {
        try {
            Class.forName(binaryName(index.entryName(index.preloadRecord(position))), false, loader);
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            // Skipped. Nothing was registered, so the application's own request fails the same way.
        }
    }

    /**
     * Loads one listed JDK class, skipping a name this runtime does not have: a jlinked image or
     * {@code --limit-modules} can leave out a class the recording JDK had.
     *
     * @param position the position in the JDK list
     */
    private void preloadJdk(int position) {
        try {
            Class.forName(index.jdkPreloadName(position), false, jdkLoader);
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            // Skipped, like an archive class.
        }
    }

    /**
     * The binary name of a class entry.
     *
     * @param entryName the logical name, such as {@code org/example/Service$Inner.class}
     * @return the binary name, such as {@code org.example.Service$Inner}
     */
    static String binaryName(String entryName) {
        int end = entryName.endsWith(".class") ? entryName.length() - CLASS_SUFFIX_LENGTH : entryName.length();
        return entryName.substring(0, end).replace('/', '.');
    }
}
