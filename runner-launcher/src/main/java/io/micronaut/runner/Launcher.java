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
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * The {@code Main-Class} of every runner jar.
 *
 * <p>Starting an application is four steps: find the archive this class was loaded from, map it, read its
 * index, and hand both to a {@link RunnerClassLoader}. The application is then entered either through the
 * {@link Entry} the packager generated - an {@code invokeinterface}, with no reflection and no method
 * handle anywhere on the path - or, when no stub could be generated, through the reflective fallback that
 * implements the same main method selection rules as the {@code java} launcher.</p>
 *
 * <h2>What this class deliberately does not do</h2>
 * <ul>
 *   <li>It does not close the {@link ArchiveSource} or the {@link Index}. Application threads keep loading
 *       classes long after {@code main} returns, so the mapping has to outlive it; the process exit
 *       releases it.</li>
 *   <li>It does not catch the application's exceptions or install a shutdown hook. A {@link Throwable} out
 *       of the application propagates out of {@link #main(String[])} unchanged, so the JVM prints it and
 *       sets the exit status exactly as it would for an ordinary main class.</li>
 * </ul>
 *
 * <h2>Exit codes</h2>
 * <p>{@code 0} when the application returns normally, {@code 1} when it throws - that is the JVM's own
 * behaviour for an uncaught exception - and {@value #EXIT_LAUNCHER_ERROR} for a launcher level failure
 * such as a missing, unreadable or stale archive, which is reported on standard error and never as a
 * stack trace.</p>
 *
 * @since 1.0
 */
public final class Launcher {

    /**
     * System property selecting what the launcher does with the archive: {@value #MODE_RUN} (the default)
     * starts the application, the other modes hand the archive to a tool in
     * {@code io.micronaut.runner.tools} and never load application code.
     */
    public static final String MODE_PROPERTY = "micronaut.runner.mode";

    /** Default mode: start the application. */
    public static final String MODE_RUN = "run";

    /** Mode that unpacks the archive into a directory layout the JDK launcher can run. */
    public static final String MODE_EXTRACT = "extract";

    /** Mode that prints the index: header, jars and packages. */
    public static final String MODE_INSPECT = "inspect";

    /** Mode that lists the logical names the archive holds. */
    public static final String MODE_LIST = "list";

    /**
     * System property that prints elapsed time checkpoints to standard error when set to exactly
     * {@code "true"}. The measurement is {@link System#nanoTime()} from the first statement of
     * {@link #main(String[])}, so it covers the launcher and nothing before it.
     */
    public static final String TIMING_PROPERTY = "micronaut.runner.timing";

    /** Exit status for a launcher level failure, as opposed to an exception out of the application. */
    public static final int EXIT_LAUNCHER_ERROR = 2;

    /** Package the non-run modes are implemented in; those classes are loaded only in those modes. */
    private static final String TOOLS_PACKAGE = "io.micronaut.runner.tools.";

    /** Name of the method the reflective fallback looks for. */
    private static final String MAIN_METHOD = "main";

    /** Guards {@link #entry} so that a second registration is rejected rather than silently winning. */
    private static final Object REGISTRATION_LOCK = new Object();

    /** The entry point the generated stub registered, or {@code null} when there is no stub. */
    private static volatile Entry entry;

    private Launcher() {
    }

    /**
     * Starts the application packaged in the runner jar this class was loaded from.
     *
     * @param args the command line arguments, passed to the application unchanged
     * @throws Throwable whatever the application throws, rethrown unchanged
     */
    public static void main(String[] args) throws Throwable {
        long started = System.nanoTime();
        boolean timing = "true".equals(System.getProperty(TIMING_PROPERTY));
        File archive = locateArchive();
        if (archive == null) {
            fail("Cannot locate the runner jar. Start it with 'java -jar <application>.jar', or put"
                    + " exactly one jar on the class path.");
            return;
        }
        String mode = System.getProperty(MODE_PROPERTY);
        String tool = null;
        if (mode != null && !mode.isEmpty() && !MODE_RUN.equals(mode)) {
            tool = toolClassName(mode);
            if (tool == null) {
                fail("Unknown " + MODE_PROPERTY + " value '" + mode + "'. Valid modes are " + MODE_RUN
                        + ", " + MODE_EXTRACT + ", " + MODE_INSPECT + " and " + MODE_LIST + ".");
                return;
            }
        }
        ArchiveSource source;
        try {
            source = ArchiveSource.open(archive);
        } catch (IOException e) {
            fail("Cannot read the runner jar " + archive + ": " + e);
            return;
        }
        if (timing) {
            checkpoint(started, "archive opened");
        }
        Index index;
        try {
            index = Index.open(source);
        } catch (IOException | IllegalStateException e) {
            source.close();
            fail("Cannot read the index of " + archive + ": " + e.getMessage());
            return;
        }
        if (timing) {
            checkpoint(started, "index read");
        }
        if (tool != null) {
            try {
                runTool(tool, mode, args, archive, index, source);
            } finally {
                source.close();
            }
            return;
        }
        // From here on the archive stays open for the life of the process: application threads keep
        // loading classes long after main returns, and closing the mapping would pull the bytes out from
        // under them. Nothing else holds a reference, so the process exit is what releases it.
        Handlers.register(archive, index, source);
        RunnerClassLoader loader = new RunnerClassLoader(index, source, RunnerClassLoader.defaultParent());
        Thread.currentThread().setContextClassLoader(loader);
        if (timing) {
            checkpoint(started, "class loader ready");
        }
        String stub = index.entryStubClass();
        String start = index.startClass();
        String enter = stub != null ? stub : start;
        if (enter == null) {
            fail("The runner jar " + archive + " names no application class. " + Index.REBUILD_MESSAGE);
            return;
        }
        Class<?> application;
        try {
            application = Class.forName(enter, true, loader);
        } catch (ClassNotFoundException e) {
            fail("The application class " + enter + " is missing from " + archive + ". "
                    + Index.REBUILD_MESSAGE);
            return;
        }
        if (stub != null) {
            Entry registered = entry;
            if (registered == null) {
                fail("The generated entry point " + stub + " did not register itself. "
                        + Index.REBUILD_MESSAGE);
                return;
            }
            if (timing) {
                checkpoint(started, "application entered");
            }
            registered.run(args);
            // main returned normally: the archive can trim what only startup needed.
            source.startupFinished();
            return;
        }
        if (timing) {
            checkpoint(started, "application entered");
        }
        invokeMain(application, args);
        source.startupFinished();
    }

    /**
     * Registers the application entry point. The class the packager generates calls this from its static
     * initialiser, so that the launcher can enter the application through an interface call.
     *
     * @param registration the entry point
     * @throws IllegalArgumentException if the entry point is {@code null}
     * @throws IllegalStateException    if an entry point is already registered; a runner jar starts once
     *                                  per JVM
     */
    public static void register(Entry registration) {
        if (registration == null) {
            throw new IllegalArgumentException("Cannot register a null application entry point");
        }
        synchronized (REGISTRATION_LOCK) {
            if (entry != null) {
                throw new IllegalStateException("An application entry point is already registered;"
                        + " a runner jar starts the application once per JVM");
            }
            entry = registration;
        }
    }

    /**
     * The archive the launcher runs from: the jar this class was loaded from, or the single jar on the
     * class path.
     *
     * @return the archive, or {@code null} when it cannot be identified
     */
    static File locateArchive() {
        File located = codeSourceFile();
        if (located != null) {
            return located;
        }
        return singleClassPathFile(System.getProperty("java.class.path"));
    }

    /**
     * The file this class was loaded from, when it is a file at all.
     *
     * @return the archive, or {@code null} when the code source is missing or is not a local file
     */
    static File codeSourceFile() {
        try {
            ProtectionDomain domain = Launcher.class.getProtectionDomain();
            if (domain == null) {
                return null;
            }
            CodeSource code = domain.getCodeSource();
            if (code == null) {
                return null;
            }
            URL location = code.getLocation();
            if (location == null || !"file".equals(location.getProtocol())) {
                return null;
            }
            File candidate = new File(location.toURI());
            return candidate.isFile() ? candidate : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The class path as an archive, when it names exactly one existing file.
     *
     * @param classPath the value of {@code java.class.path}
     * @return the archive, or {@code null} when the class path has several entries or is not a file
     */
    static File singleClassPathFile(String classPath) {
        if (classPath == null || classPath.isEmpty()
                || classPath.indexOf(File.pathSeparatorChar) >= 0) {
            return null;
        }
        File candidate = new File(classPath);
        return candidate.isFile() ? candidate : null;
    }

    /**
     * The class implementing a non-run mode.
     *
     * @param mode the value of {@value #MODE_PROPERTY}
     * @return the binary class name, or {@code null} when the mode is not one this launcher knows
     */
    static String toolClassName(String mode) {
        if (MODE_EXTRACT.equals(mode)) {
            return TOOLS_PACKAGE + "Extract";
        }
        if (MODE_INSPECT.equals(mode)) {
            return TOOLS_PACKAGE + "Inspect";
        }
        if (MODE_LIST.equals(mode)) {
            return TOOLS_PACKAGE + "ListEntries";
        }
        return null;
    }

    /**
     * Invokes a class's main method the way the {@code java} launcher does.
     *
     * <p>A candidate is named {@code main}, returns {@code void} and is not private. The form taking a
     * {@code String[]} is preferred over the form taking nothing <em>regardless of whether either is
     * static</em>, and the whole hierarchy - superclasses and the interfaces they implement - is searched
     * for the first form before the second is considered at all: an inherited {@code main(String[])} beats
     * a declared {@code main()}, an instance {@code main(String[])} beats a static {@code main()}, and a
     * {@code main} inherited as an interface default method is as good as one inherited from a superclass.
     * A non-static candidate is invoked on a new instance built from a non-private no-argument
     * constructor.</p>
     *
     * @param type the application class
     * @param args the command line arguments
     * @throws Throwable whatever the application throws, unwrapped from the reflective call
     */
    static void invokeMain(Class<?> type, String[] args) throws Throwable {
        Method method = findMainMethod(type);
        if (method == null) {
            throw new NoSuchMethodException(type.getName() + " does not declare or inherit a non-private"
                    + " void main(String[]) or void main()");
        }
        Object instance = null;
        if (!Modifier.isStatic(method.getModifiers())) {
            instance = newInstance(type);
        }
        if (!isPubliclyAccessible(method.getModifiers(), method.getDeclaringClass())) {
            method.setAccessible(true);
        }
        try {
            if (method.getParameterCount() == 1) {
                method.invoke(instance, (Object) args);
            } else {
                method.invoke(instance);
            }
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    /**
     * Finds the main method of a class, applying the preference rules described on
     * {@link #invokeMain(Class, String[])}.
     *
     * @param type the application class
     * @return the method, or {@code null} when the class has no candidate
     */
    static Method findMainMethod(Class<?> type) {
        Method method = findCandidate(type, true, true);
        if (method == null) {
            method = findCandidate(type, false, true);
        }
        if (method == null || !isUsableMain(method)) {
            method = findCandidate(type, false, false);
        }
        if (method == null || !isUsableMain(method)) {
            return null;
        }
        return method;
    }

    /**
     * Whether a candidate is a main method a program can be started through.
     *
     * @param candidate the method the search settled on
     * @return {@code true} when it returns {@code void} and is not private
     */
    private static boolean isUsableMain(Method candidate) {
        return candidate.getReturnType() == void.class && !Modifier.isPrivate(candidate.getModifiers());
    }

    /**
     * The {@code main} of one arity in a type's hierarchy, chosen the way {@code java} chooses it.
     *
     * <p>This mirrors {@code java.lang.Class.getMethodsRecursive}, which is what
     * {@code jdk.internal.misc.MethodFinder} - the code the {@code java} launcher itself runs - searches
     * with. A type's own declared methods win outright; failing that the superclass is searched and its
     * answer merged with the answer from every directly implemented interface. Static methods count in the
     * class hierarchy but not in the interface hierarchy, because a static interface method is not
     * inherited.</p>
     *
     * @param type          the type to search
     * @param publicOnly    whether only public declared methods are considered, which is the first pass
     * @param withArguments whether to look for {@code main(String[])} rather than {@code main()}
     * @return the method, or {@code null} when the hierarchy declares none
     */
    private static Method findCandidate(Class<?> type, boolean publicOnly, boolean withArguments) {
        return findCandidate(type, publicOnly, withArguments, true);
    }

    private static Method findCandidate(Class<?> type, boolean publicOnly, boolean withArguments,
            boolean includeStatic) {
        Method declared = declaredCandidate(type, publicOnly, withArguments, includeStatic);
        if (declared != null) {
            // A match among the declared methods overrides anything a supertype declares with the same
            // signature, so the search stops here.
            return declared;
        }
        Method best = null;
        Class<?> superclass = type.getSuperclass();
        if (superclass != null) {
            best = findCandidate(superclass, publicOnly, withArguments, includeStatic);
        }
        Class<?>[] interfaces = type.getInterfaces();
        for (int i = 0; i < interfaces.length; i++) {
            best = moreSpecific(best, findCandidate(interfaces[i], publicOnly, withArguments, false));
        }
        return best;
    }

    private static Method declaredCandidate(Class<?> type, boolean publicOnly, boolean withArguments,
            boolean includeStatic) {
        Method[] methods = type.getDeclaredMethods();
        Method found = null;
        for (int i = 0; i < methods.length; i++) {
            Method candidate = methods[i];
            if (!MAIN_METHOD.equals(candidate.getName())) {
                continue;
            }
            int modifiers = candidate.getModifiers();
            if (publicOnly && !Modifier.isPublic(modifiers)) {
                continue;
            }
            if (!includeStatic && Modifier.isStatic(modifiers)) {
                continue;
            }
            Class<?>[] parameters = candidate.getParameterTypes();
            boolean matches = withArguments
                    ? parameters.length == 1 && parameters[0] == String[].class
                    : parameters.length == 0;
            if (!matches) {
                continue;
            }
            if (found == null
                    || (found.getReturnType() != void.class && candidate.getReturnType() == void.class)) {
                found = candidate;
            }
        }
        return found;
    }

    private static Method moreSpecific(Method existing, Method candidate) {
        if (candidate == null) {
            return existing;
        }
        if (existing == null) {
            return candidate;
        }
        Class<?> arriving = candidate.getDeclaringClass();
        Class<?> present = existing.getDeclaringClass();
        if (present.isInterface() != arriving.isInterface()) {
            // A method declared by a class always wins over one declared by an interface.
            return present.isInterface() ? candidate : existing;
        }
        return present.isAssignableFrom(arriving) ? candidate : existing;
    }

    private static Object newInstance(Class<?> type) throws Throwable {
        Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodException(type.getName() + " declares an instance main method but no"
                    + " no-argument constructor to call it on");
        }
        if (Modifier.isPrivate(constructor.getModifiers())) {
            throw new IllegalAccessException(type.getName() + " declares an instance main method but its"
                    + " no-argument constructor is private");
        }
        if (!isPubliclyAccessible(constructor.getModifiers(), type)) {
            constructor.setAccessible(true);
        }
        try {
            return constructor.newInstance();
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        }
    }

    private static boolean isPubliclyAccessible(int modifiers, Class<?> declaring) {
        return Modifier.isPublic(modifiers) && Modifier.isPublic(declaring.getModifiers());
    }

    private static Throwable unwrap(InvocationTargetException e) {
        Throwable cause = e.getCause();
        return cause != null ? cause : e;
    }

    private static void runTool(String tool, String mode, String[] args, File archive, Index index,
                                ArchiveSource source) throws Throwable {
        Class<?> type;
        try {
            type = Class.forName(tool, true, Launcher.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            fail("The " + mode + " mode is not available: " + tool + " is not packaged in this runner"
                    + " jar.");
            return;
        }
        Method run;
        try {
            run = type.getMethod("run", String[].class, File.class, Index.class, ArchiveSource.class);
        } catch (NoSuchMethodException e) {
            fail("The " + mode + " mode is not available: " + tool + " has no run method.");
            return;
        }
        try {
            run.invoke(null, new Object[] {args, archive, index, source});
        } catch (InvocationTargetException e) {
            throw unwrap(e);
        } catch (IllegalAccessException e) {
            fail("The " + mode + " mode is not available: " + tool + " is not accessible.");
        }
    }

    private static void checkpoint(long started, String phase) {
        long micros = (System.nanoTime() - started) / 1000L;
        StringBuilder message = new StringBuilder(48);
        message.append("[micronaut-runner] ").append(phase).append(' ').append(micros / 1000L).append('.');
        long fraction = micros % 1000L;
        if (fraction < 100L) {
            message.append('0');
        }
        if (fraction < 10L) {
            message.append('0');
        }
        message.append(fraction).append(" ms");
        System.err.println(message);
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(EXIT_LAUNCHER_ERROR);
    }

    /**
     * Forgets the registered entry point. Tests use it to exercise {@link #register(Entry)} more than
     * once in one JVM; nothing in a packaged application can reach it, because the generated stub lives
     * in {@link IndexFormat#GENERATED_PACKAGE}.
     */
    static void clearRegistration() {
        synchronized (REGISTRATION_LOCK) {
            entry = null;
        }
    }

    /**
     * The registered entry point, for tests.
     *
     * @return the entry point, or {@code null} when none was registered
     */
    static Entry registration() {
        return entry;
    }
}
