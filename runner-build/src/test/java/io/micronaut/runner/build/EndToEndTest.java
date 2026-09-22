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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The end-to-end test: it compiles a small application and two dependency jars, packages them with
 * {@link RunnerJarBuilder}, and starts the result with {@code java -jar} in a forked JVM.
 *
 * <p>Everything the launcher promises is asserted from <em>inside</em> the running application, because
 * that is the only place where it can be observed the way a real application observes it: the class loader
 * that is actually installed, the URLs it actually hands out, the packages it actually defines. The
 * application prints one {@code PASS}/{@code FAIL} line per assertion and a final {@code RESULT} line; this
 * test fails if any line says {@code FAIL}, if the {@code RESULT} line is missing, or if the process exits
 * non-zero. A failure therefore names the promise that was broken rather than just a bad exit code.</p>
 *
 * <p>What the fixture is built to exercise, in one archive: two application output directories (classes and
 * resources), a {@code META-INF/services} provider in the application and another in a dependency, Micronaut
 * service metadata in the application and in both dependencies, a resource name carried by all three jars,
 * an ordinary dependency with a per-package manifest section that seals its package, and a multi-release
 * dependency with two versioned copies of the same class.</p>
 *
 * <p>The forked JVM is the one at the system property {@code runner.test.javaHome}, which the build sets to
 * the JDK running the build. The fixture downloads nothing. Its detached-signature test uses a locally
 * installed GnuPG executable and ephemeral keyrings; the other tests read only their temporary directory and
 * the bundled launcher jar. Together they start five short-lived JVMs.</p>
 */
class EndToEndTest {

    @TempDir
    static Path workspace;

    /** A fixed MS-DOS timestamp for the fixture jars, so rebuilding a fixture changes nothing. */
    private static final long FIXTURE_TIME = 1_000_000_000_000L;

    /** The line the application prints when every assertion inside it passed. */
    private static final String RESULT_OK = "RESULT OK";

    /** A directory name with a space and a non-ASCII character, where URL encoding bugs surface. */
    private static final String AWKWARD_DIRECTORY = "a we\u00efrd dir";

    private static final String GREETER_SOURCE = """
            package com.example.spi;

            public interface Greeter {
                String greet();
            }
            """;

    private static final String HELPER_SOURCE = """
            package com.example;

            public final class Helper {
                public static String describe() {
                    return "helper";
                }
            }
            """;

    private static final String APP_GREETER_SOURCE = """
            package com.example;

            import com.example.spi.Greeter;

            public final class AppGreeter implements Greeter {
                @Override
                public String greet() {
                    return "hello-from-application";
                }
            }
            """;

    private static final String DEP_ONE_SOURCE = """
            package org.depone;

            public class DepOne {
                public String hello() {
                    return "dep-one";
                }
            }
            """;

    private static final String DEP_GREETER_SOURCE = """
            package org.depone;

            import com.example.spi.Greeter;

            public final class DepGreeter implements Greeter {
                @Override
                public String greet() {
                    return "hello-from-dep-one";
                }
            }
            """;

    private static final String PLAIN_SOURCE = """
            package org.deptwo;

            public final class Plain {
                public static String hello() {
                    return "dep-two";
                }
            }
            """;

    /**
     * The application. It asserts, from inside the packaged archive, every promise section 4.2 of the
     * specification makes about the class loader, the URLs and the package metadata, and prints one line
     * per assertion so that a failure names the promise rather than an exit code.
     */
    private static final String APPLICATION_SOURCE = """
            package com.example;

            import com.example.spi.Greeter;

            import java.io.ByteArrayOutputStream;
            import java.io.File;
            import java.io.InputStream;
            import java.net.JarURLConnection;
            import java.net.URI;
            import java.net.URL;
            import java.nio.charset.StandardCharsets;
            import java.security.ProtectionDomain;
            import java.util.Collections;
            import java.util.Enumeration;
            import java.util.List;
            import java.util.ServiceLoader;
            import java.util.TreeSet;
            import java.util.jar.JarEntry;
            import java.util.jar.JarFile;
            import java.util.zip.ZipEntry;
            import java.util.zip.ZipFile;

            public final class Application {

                private static int failures;

                public static void main(String[] args) throws Exception {
                    System.out.println("ARGS " + String.join("|", args));

                    ClassLoader loader = Thread.currentThread().getContextClassLoader();
                    check("the context class loader is the runner class loader",
                            "io.micronaut.runner.RunnerClassLoader".equals(loader.getClass().getName()),
                            loader.getClass().getName());
                    check("the application was loaded by it",
                            Application.class.getClassLoader() == loader,
                            String.valueOf(Application.class.getClassLoader()));
                    check("a second application class loads",
                            "helper".equals(Helper.describe()), Helper.describe());

                    Class<?> depOne = Class.forName("org.depone.DepOne", true, loader);
                    String oneSays = (String) depOne.getMethod("hello")
                            .invoke(depOne.getDeclaredConstructor().newInstance());
                    check("a class loads from the first dependency", "dep-one".equals(oneSays), oneSays);

                    Class<?> versioned = Class.forName("org.deptwo.Versioned", true, loader);
                    String which = (String) versioned.getMethod("which").invoke(null);
                    check("the multi-release class resolves to the versioned variant",
                            "v21".equals(which), which);
                    Class<?> plain = Class.forName("org.deptwo.Plain", true, loader);
                    check("a base class of the multi-release dependency loads",
                            "dep-two".equals(plain.getMethod("hello").invoke(null)), "?");

                    TreeSet<String> providers = new TreeSet<>();
                    TreeSet<String> greetings = new TreeSet<>();
                    for (Greeter greeter : ServiceLoader.load(Greeter.class, loader)) {
                        providers.add(greeter.getClass().getName());
                        greetings.add(greeter.greet());
                    }
                    check("ServiceLoader finds the provider from the dependency",
                            providers.contains("org.depone.DepGreeter"), providers.toString());
                    check("ServiceLoader finds the application's own provider",
                            providers.contains("com.example.AppGreeter"), providers.toString());
                    check("both providers run",
                            greetings.contains("hello-from-application")
                                    && greetings.contains("hello-from-dep-one"), greetings.toString());
                    check("both service files are visible", Collections.list(loader.getResources(
                            "META-INF/services/com.example.spi.Greeter")).size() == 2, "?");

                    check("getResource finds an application resource",
                            loader.getResource("com/example/app-resource.txt") != null, "null");
                    check("getResourceAsStream reads an application resource",
                            "application-resource".equals(read(loader, "com/example/app-resource.txt")),
                            read(loader, "com/example/app-resource.txt"));
                    check("getResource finds a dependency resource",
                            loader.getResource("org/depone/dep-resource.txt") != null, "null");
                    check("getResourceAsStream reads a dependency resource",
                            "dep-one-resource".equals(read(loader, "org/depone/dep-resource.txt")),
                            read(loader, "org/depone/dep-resource.txt"));
                    check("a class reads a resource next to itself",
                            "dep-one-resource".equals(readFrom(depOne, "/org/depone/dep-resource.txt")),
                            readFrom(depOne, "/org/depone/dep-resource.txt"));

                    List<URL> serviceDirectories =
                            Collections.list(loader.getResources("META-INF/micronaut/"));
                    check("getResources(\\"META-INF/micronaut/\\") returns exactly one URL",
                            serviceDirectories.size() == 1, serviceDirectories.toString());
                    if (serviceDirectories.size() == 1) {
                        checkServiceDirectory(serviceDirectories.get(0));
                    }

                    Package dependencyPackage = depOne.getPackage();
                    check("Package.getImplementationVersion comes from the per-package section",
                            "1.2.3".equals(dependencyPackage.getImplementationVersion()),
                            String.valueOf(dependencyPackage.getImplementationVersion()));
                    check("Package.getImplementationTitle comes from the per-package section",
                            "Dependency One".equals(dependencyPackage.getImplementationTitle()),
                            String.valueOf(dependencyPackage.getImplementationTitle()));
                    check("the sealed package reports itself sealed",
                            dependencyPackage.isSealed(), "not sealed");
                    check("the application package inherits the manifest main attributes",
                            "9.9.9".equals(Application.class.getPackage().getImplementationVersion()),
                            String.valueOf(Application.class.getPackage().getImplementationVersion()));

                    URL applicationCode = location(Application.class);
                    URL firstCode = location(depOne);
                    URL secondCode = location(versioned);
                    check("every jar has a code source",
                            applicationCode != null && firstCode != null && secondCode != null,
                            applicationCode + " / " + firstCode + " / " + secondCode);
                    check("the code source locations differ per jar",
                            applicationCode != null && firstCode != null && secondCode != null
                                    && !applicationCode.toString().equals(firstCode.toString())
                                    && !firstCode.toString().equals(secondCode.toString())
                                    && !applicationCode.toString().equals(secondCode.toString()),
                            applicationCode + " / " + firstCode + " / " + secondCode);
                    check("a dependency's code source names its nested jar",
                            firstCode != null
                                    && firstCode.toString().endsWith("!/MICRONAUT-INF/lib/dep-one.jar!/"),
                            String.valueOf(firstCode));
                    check("the application's code source names the application layer",
                            applicationCode != null
                                    && applicationCode.toString().endsWith("!/MICRONAUT-INF/classes/"),
                            String.valueOf(applicationCode));
                    checkApplicationCodeSource(applicationCode);

                    check("a merged service file keeps the application's content",
                            "APP-MERGED-CONTENT".equals(read(loader, "META-INF/micronaut/notes.txt")),
                            read(loader, "META-INF/micronaut/notes.txt"));
                    check("a merged service file keeps a dependency's content",
                            "DEP-MERGED-CONTENT".equals(read(loader, "META-INF/micronaut/dep-notes.txt")),
                            read(loader, "META-INF/micronaut/dep-notes.txt"));

                    URL versionedResource = loader.getResource("org/deptwo/Versioned.class");
                    check("a versioned class is reported under the name that holds its bytes",
                            versionedResource != null && versionedResource.toString().endsWith(
                                "!/MICRONAUT-INF/lib/dep-two.jar!"
                                + "/META-INF/versions/21/org/deptwo/Versioned.class"),
                            String.valueOf(versionedResource));

                    // Neither jar carries an explicit directory entry for these, so both answers come
                    // from the directory records the packager synthesised.
                    check("a directory implied by a dependency resolves",
                            loader.getResource("org/depone/") != null, "null");
                    check("a directory implied by the application layer resolves",
                            loader.getResource("com/example/") != null, "null");
                    check("a directory that does not exist stays unresolved",
                            loader.getResource("org/nowhere/") == null,
                            String.valueOf(loader.getResource("org/nowhere/")));
                    check("a directory resolves without a trailing slash too",
                            loader.getResource("com/example") != null
                                    && loader.getResource("org/depone") != null,
                            loader.getResource("com/example") + " / "
                                    + loader.getResource("org/depone"));
                    check("a doubled slash is collapsed even with no dot segment in the name",
                            "application-resource".equals(read(loader, "com//example/app-resource.txt")),
                            read(loader, "com//example/app-resource.txt"));
                    checkDirectoryConnection(loader.getResource("com/example/"));
                    check("a resource name escaping the archive root stays unresolved",
                            loader.getResource("../outside.txt") == null,
                            String.valueOf(loader.getResource("../outside.txt")));

                    checkNestedUrl(loader.getResource("org/depone/dep-resource.txt"));

                    List<URL> shared = Collections.list(loader.getResources("shared.txt"));
                    check("a name present in three jars yields three URLs",
                            shared.size() == 3, shared.toString());
                    check("classpath order puts the application first",
                            "shared-application".equals(read(loader, "shared.txt")),
                            read(loader, "shared.txt"));
                    check("getResource returns the first of them",
                            !shared.isEmpty() && shared.get(0).toString().equals(
                                    String.valueOf(loader.getResource("shared.txt"))),
                            shared.toString());

                    System.out.println(failures == 0 ? "RESULT OK" : "RESULT FAILED " + failures);
                    if (failures != 0) {
                        System.exit(1);
                    }
                }

                private static void checkServiceDirectory(URL directory) throws Exception {
                    String text = directory.toString();
                    check("the merged service directory is addressed at the outer archive root",
                            text.startsWith("jar:file:") && text.endsWith("!/META-INF/micronaut/"), text);

                    JarURLConnection connection = (JarURLConnection) directory.openConnection();
                    connection.setUseCaches(false);
                    check("its connection reports the outer-root entry name",
                            "META-INF/micronaut/".equals(connection.getEntryName()),
                            connection.getEntryName());
                    TreeSet<String> throughJarFile = new TreeSet<>();
                    JarFile jar = connection.getJarFile();
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        collect(entries.nextElement().getName(), throughJarFile);
                    }
                    checkMerged("JarURLConnection and JarFile", throughJarFile);

                    // Exactly what micronaut-core's collectJarServices does with this URL: split the one
                    // archive separator off the scheme specific part and open the outer archive itself.
                    String part = directory.toURI().getRawSchemeSpecificPart();
                    int bang = part.indexOf("!/");
                    check("the URL carries exactly one archive separator and a file: inner URL",
                            bang >= 0 && part.indexOf("!/", bang + 2) < 0 && part.startsWith("file:"),
                            part);
                    if (bang < 0) {
                        return;
                    }
                    TreeSet<String> throughZipFile = new TreeSet<>();
                    try (ZipFile zip = new ZipFile(new File(URI.create(part.substring(0, bang))))) {
                        Enumeration<? extends ZipEntry> zipEntries = zip.entries();
                        while (zipEntries.hasMoreElements()) {
                            collect(zipEntries.nextElement().getName(), throughZipFile);
                        }
                    }
                    checkMerged("ZipFile over the outer archive", throughZipFile);
                }

                private static void checkNestedUrl(URL resource) throws Exception {
                    check("a dependency resource URL names the nested jar",
                            resource != null
                                && resource.toString().contains("!/MICRONAUT-INF/lib/dep-one.jar!/"),
                            String.valueOf(resource));
                    if (resource == null) {
                        return;
                    }
                    // Re-parsed from its string form, so it goes through the handler the launcher
                    // registered rather than the instance the class loader attached to the URL.
                    URL reparsed = new URI(resource.toString()).toURL();
                    String content;
                    try (InputStream in = reparsed.openStream()) {
                        content = drain(in);
                    }
                    check("a URL re-parsed from its string form still opens",
                            "dep-one-resource".equals(content), content);
                    JarURLConnection nested = (JarURLConnection) reparsed.openConnection();
                    check("its connection reports the nested entry name",
                            "org/depone/dep-resource.txt".equals(nested.getEntryName()),
                            nested.getEntryName());
                    check("its jar URL names the nested jar",
                            nested.getJarFileURL().toString()
                                    .endsWith("!/MICRONAUT-INF/lib/dep-one.jar"),
                            nested.getJarFileURL().toString());
                    check("the nested jar's own manifest is readable",
                            nested.getManifest() != null
                                    && "Dependency One Jar".equals(nested.getManifest()
                                            .getMainAttributes().getValue("Implementation-Title")),
                            String.valueOf(nested.getManifest()));
                }

                private static void collect(String name, TreeSet<String> into) {
                    if (name.startsWith("META-INF/micronaut/") && !name.endsWith("/")) {
                        into.add(name);
                    }
                }

                private static void checkApplicationCodeSource(URL location) throws Exception {
                    if (location == null) {
                        return;
                    }
                    JarURLConnection connection = (JarURLConnection) location.openConnection();
                    connection.setUseCaches(false);
                    JarEntry entry = connection.getJarEntry();
                    check("the application layer's code source names an entry of the archive",
                            entry != null && entry.isDirectory(), String.valueOf(entry));
                    try (InputStream in = connection.getInputStream()) {
                        check("and the code source location can be opened",
                                in.readAllBytes().length == 0, "?");
                    }
                }

                private static void checkDirectoryConnection(URL directory) throws Exception {
                    check("an application layer directory has a URL", directory != null, "null");
                    if (directory == null) {
                        return;
                    }
                    JarURLConnection connection = (JarURLConnection) directory.openConnection();
                    connection.setUseCaches(false);
                    JarEntry entry = connection.getJarEntry();
                    check("its connection describes the same entry it streams",
                            entry != null && entry.isDirectory()
                                    && entry.getName().equals(connection.getEntryName()),
                            entry == null
                                    ? "null" : entry.getName() + " vs " + connection.getEntryName());
                }

                private static void checkMerged(String how, TreeSet<String> names) {
                    check("the merged directory read through " + how + " has the application's service",
                            names.contains(
                                "META-INF/micronaut/com.example.spi.Greeter/com.example.AppGreeter"),
                            names.toString());
                    check("the merged directory read through " + how
                                + " has the first dependency's service",
                            names.contains(
                                "META-INF/micronaut/com.example.spi.Greeter/org.depone.DepGreeter"),
                            names.toString());
                    check("the merged directory read through " + how
                                + " has the second dependency's service",
                            names.contains("META-INF/micronaut/io.example.Other/org.deptwo.Thing"),
                            names.toString());
                }

                private static URL location(Class<?> type) {
                    ProtectionDomain domain = type.getProtectionDomain();
                    if (domain == null || domain.getCodeSource() == null) {
                        return null;
                    }
                    return domain.getCodeSource().getLocation();
                }

                private static String read(ClassLoader loader, String name) {
                    try (InputStream in = loader.getResourceAsStream(name)) {
                        return in == null ? null : drain(in);
                    } catch (Exception e) {
                        return "error: " + e;
                    }
                }

                private static String readFrom(Class<?> type, String name) {
                    try (InputStream in = type.getResourceAsStream(name)) {
                        return in == null ? null : drain(in);
                    } catch (Exception e) {
                        return "error: " + e;
                    }
                }

                private static String drain(InputStream in) throws Exception {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[512];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                    return out.toString(StandardCharsets.UTF_8).trim();
                }

                private static void check(String what, boolean ok, String actual) {
                    if (ok) {
                        System.out.println("PASS " + what);
                    } else {
                        failures++;
                        System.out.println("FAIL " + what + " -- got: " + actual);
                    }
                }
            }
            """;

    private static Path storedArchive;
    private static Path preserveArchive;
    private static Path awkwardArchive;

    @BeforeAll
    static void packageTheApplication() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "this JDK has no java compiler");
        Assumptions.assumeTrue(
                RunnerJarBuilder.class.getResource("/META-INF/micronaut-runner/launcher.jar") != null,
                "the bundled launcher jar is not on the test class path");
        Assumptions.assumeTrue(javaExecutable() != null, "no JDK to fork; set runner.test.javaHome");

        Path sources = workspace.resolve("sources");
        Path applicationClasses = workspace.resolve("app/classes");
        Path applicationResources = workspace.resolve("app/resources");
        Path firstClasses = workspace.resolve("dep-one/classes");
        Path secondClasses = workspace.resolve("dep-two/classes");
        Path secondClasses17 = workspace.resolve("dep-two/classes-17");
        Path secondClasses21 = workspace.resolve("dep-two/classes-21");

        compile(compiler, sources.resolve("app"), applicationClasses, null, Map.of(
                "com/example/spi/Greeter.java", GREETER_SOURCE,
                "com/example/Helper.java", HELPER_SOURCE,
                "com/example/AppGreeter.java", APP_GREETER_SOURCE,
                "com/example/Application.java", APPLICATION_SOURCE));
        compile(compiler, sources.resolve("dep-one"), firstClasses, applicationClasses, Map.of(
                "org/depone/DepOne.java", DEP_ONE_SOURCE,
                "org/depone/DepGreeter.java", DEP_GREETER_SOURCE));
        compile(compiler, sources.resolve("dep-two"), secondClasses, null, Map.of(
                "org/deptwo/Plain.java", PLAIN_SOURCE,
                "org/deptwo/Versioned.java", versionedSource("base")));
        compile(compiler, sources.resolve("dep-two-17"), secondClasses17, null, Map.of(
                "org/deptwo/Versioned.java", versionedSource("v17")));
        compile(compiler, sources.resolve("dep-two-21"), secondClasses21, null, Map.of(
                "org/deptwo/Versioned.java", versionedSource("v21")));

        write(applicationResources.resolve("com/example/app-resource.txt"), "application-resource");
        write(applicationResources.resolve("shared.txt"), "shared-application");
        write(applicationResources.resolve("META-INF/services/com.example.spi.Greeter"),
                "com.example.AppGreeter\n");
        write(applicationResources.resolve(
                "META-INF/micronaut/com.example.spi.Greeter/com.example.AppGreeter"), "");
        // Not every file under META-INF/micronaut/ is an empty marker; one that has content must keep it,
        // because the class loader answers every lookup under that prefix from the merged copy.
        write(applicationResources.resolve("META-INF/micronaut/notes.txt"), "APP-MERGED-CONTENT");

        Manifest applicationManifest = manifest(attributes -> {
            attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, "Runner End To End Application");
            attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, "9.9.9");
        });

        // The ordinary dependency: its own service provider, its own Micronaut metadata, and a manifest
        // section that gives org/depone/ its own implementation version and seals it.
        Manifest firstManifest = manifest(attributes -> {
            attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, "Dependency One Jar");
            attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, "1.0.0");
        });
        Attributes section = new Attributes();
        section.put(Attributes.Name.IMPLEMENTATION_TITLE, "Dependency One");
        section.put(Attributes.Name.IMPLEMENTATION_VERSION, "1.2.3");
        section.putValue("Sealed", "true");
        firstManifest.getEntries().put("org/depone/", section);

        Map<String, byte[]> firstEntries = new LinkedHashMap<>();
        firstEntries.put("org/depone/DepOne.class",
                Files.readAllBytes(firstClasses.resolve("org/depone/DepOne.class")));
        firstEntries.put("org/depone/DepGreeter.class",
                Files.readAllBytes(firstClasses.resolve("org/depone/DepGreeter.class")));
        firstEntries.put("org/depone/dep-resource.txt", bytes("dep-one-resource"));
        firstEntries.put("shared.txt", bytes("shared-dep-one"));
        firstEntries.put("META-INF/services/com.example.spi.Greeter", bytes("org.depone.DepGreeter\n"));
        firstEntries.put("META-INF/micronaut/com.example.spi.Greeter/org.depone.DepGreeter", new byte[0]);
        firstEntries.put("META-INF/micronaut/dep-notes.txt", bytes("DEP-MERGED-CONTENT"));
        Path first = workspace.resolve("libs/dep-one.jar");
        writeJar(first, firstManifest, firstEntries);

        // The multi-release dependency: two versioned copies of one class, and Micronaut metadata for a
        // service the application knows nothing about, which the merge has to pick up all the same.
        Manifest secondManifest = manifest(attributes -> {
            attributes.putValue("Multi-Release", "true");
            attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, "Dependency Two");
            attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, "2.0.0");
        });
        Map<String, byte[]> secondEntries = new LinkedHashMap<>();
        secondEntries.put("org/deptwo/Plain.class",
                Files.readAllBytes(secondClasses.resolve("org/deptwo/Plain.class")));
        secondEntries.put("org/deptwo/Versioned.class",
                Files.readAllBytes(secondClasses.resolve("org/deptwo/Versioned.class")));
        secondEntries.put("META-INF/versions/17/org/deptwo/Versioned.class",
                Files.readAllBytes(secondClasses17.resolve("org/deptwo/Versioned.class")));
        secondEntries.put("META-INF/versions/21/org/deptwo/Versioned.class",
                Files.readAllBytes(secondClasses21.resolve("org/deptwo/Versioned.class")));
        secondEntries.put("org/deptwo/dep-resource.txt", bytes("dep-two-resource"));
        secondEntries.put("shared.txt", bytes("shared-dep-two"));
        secondEntries.put("META-INF/micronaut/io.example.Other/org.deptwo.Thing", new byte[0]);
        Path second = workspace.resolve("libs/dep-two.jar");
        writeJar(second, secondManifest, secondEntries);

        List<Dependency> dependencies = List.of(
                new Dependency(first, "org.example:dep-one:1.2.3"),
                new Dependency(second, "org.example:dep-two:2.0.0"));
        RunnerJarSpec.Builder common = RunnerJarSpec.builder()
                .mainClass("com.example.Application")
                .applicationOutput(List.of(applicationClasses, applicationResources))
                .applicationManifest(applicationManifest)
                .dependencies(dependencies);

        storedArchive = workspace.resolve("out/app.jar");
        RunnerJarBuilder.build(common.output(storedArchive).compression(Compression.STORED).build(),
                BuildLogger.noOp());

        preserveArchive = workspace.resolve("out/app-preserve.jar");
        RunnerJarBuilder.build(common.output(preserveArchive).compression(Compression.PRESERVE).build(),
                BuildLogger.noOp());

        Path awkward = workspace.resolve(AWKWARD_DIRECTORY);
        Files.createDirectories(awkward);
        awkwardArchive = awkward.resolve("app.jar");
        Files.copy(storedArchive, awkwardArchive, StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    void startsTheApplicationAndEveryPromiseHoldsInside() throws Exception {
        Forked run = fork(storedArchive, workspace, List.of(), List.of("alpha", "beta"));
        assertPassed(run);
        assertTrue(run.output().contains("ARGS alpha|beta"),
                () -> "the application did not receive its arguments\n" + run.output());
        // A check that never runs is a check that passes, so a few of them are named here to make sure
        // the application really did reach them.
        for (String reached : new String[] {
            "PASS and the code source location can be opened",
            "PASS its connection describes the same entry it streams",
            "PASS a directory resolves without a trailing slash too",
            "PASS a merged service file keeps a dependency's content"}) {
            assertTrue(run.output().contains(reached),
                    () -> "the application never reported: " + reached + "\n" + run.output());
        }
    }

    @Test
    void reportsItsTimingsWhenAsked() throws Exception {
        Forked run = fork(storedArchive, workspace,
                List.of("-Dmicronaut.runner.timing=true"), List.of());
        assertPassed(run);
        assertTrue(run.output().contains("archive opened") && run.output().contains("index read")
                        && run.output().contains("class loader ready")
                        && run.output().contains("application entered"),
                () -> "the timing checkpoints were not printed\n" + run.output());
    }

    @Test
    void worksWithoutMemoryMappingAndWithEveryEntryVerified() throws Exception {
        assertPassed(fork(storedArchive, workspace,
                List.of("-Dmicronaut.runner.mmap=false", "-Dmicronaut.runner.verify=true"), List.of()));
    }

    @Test
    void worksFromAnotherDirectoryAndFromAPathWithASpaceAndANonAsciiCharacter() throws Exception {
        // Run from a working directory that is neither the archive's nor an ancestor of it, so nothing can
        // accidentally resolve relative to the current directory, and from a path that only survives if
        // every URL on the way is percent-encoded and decoded again.
        Path elsewhere = Files.createDirectories(workspace.resolve("elsewhere"));
        assertPassed(fork(awkwardArchive, elsewhere, List.of(), List.of()));
    }

    @Test
    void worksWhenTheDependenciesKeepTheirOwnCompression() throws Exception {
        assertPassed(fork(preserveArchive, workspace, List.of(), List.of()));
    }

    @Test
    void compatibilityGuideRequiresDetachedVerificationBeforeLaunch() throws Exception {
        String guide = Files.readString(Path.of(System.getProperty("runner.test.compatibilityGuide")),
                StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');

        assertFalse(guide.contains("Sign the outer archive if you need a signature"));
        assertFalse(guide.contains("sign the finished archive with a tool"));
        assertTrue(guide.contains("--armor --detach-sign --output \"$artifact.asc\" \"$artifact\""));
        assertTrue(guide.contains("--verify \"$signature\" \"$artifact\" &&\nexec java -jar \"$artifact\""));
        assertTrue(guide.contains("complete fingerprints were\nchecked against the release policy"));
        assertTrue(guide.contains("same bytes that are executed"));
        assertTrue(guide.contains("another process can replace the\npath between verification and launch"));
        assertTrue(guide.contains("CRC-32 checks") && guide.contains("they do not\nauthenticate a publisher"));
        assertTrue(guide.contains("null `CodeSigner[]`"));
        assertTrue(guide.contains("does not make the runner verify nested JARs"));
    }

    @Test
    void detachedVerificationAuthenticatesFinalBytesWithoutMutatingThem() throws Exception {
        String gpg = gpgExecutable();
        if (gpg == null) {
            throw new AssertionError("GnuPG is required for detached-signature verification tests");
        }

        Path scenario = Files.createDirectories(workspace.resolve("detached-signature"));
        Path artifact = scenario.resolve("app.jar");
        Files.copy(storedArchive, artifact, StandardCopyOption.REPLACE_EXISTING);
        byte[] packagedHash = sha256(artifact);

        // GnuPG on macOS places agent sockets below the home and is limited by the short sockaddr_un path.
        // Keep these temporary homes directly below the user's home rather than JUnit's long temporary path.
        Path gpgRoot = Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".mr-gpg-");
        try {
            Path signerHome = gpgHome(gpgRoot.resolve("s"));
            String signer = generateSigningKey(gpg, signerHome,
                    "Runner Release Test <release@example.invalid>");
            Path trustedHome = gpgHome(gpgRoot.resolve("t"));
            importPublicKey(gpg, signerHome, signer, trustedHome, scenario.resolve("release-key.asc"));

            Path signature = scenario.resolve("app.jar.asc");
            requireSuccess(command(scenario, gpg, "--batch", "--homedir", signerHome.toString(),
                    "--pinentry-mode", "loopback", "--passphrase", "", "--local-user", signer,
                    "--armor", "--detach-sign", "--output", signature.toString(), artifact.toString()));
            assertArrayEquals(packagedHash, sha256(artifact), "detached signing changed the runner JAR");

            VerificationRun verified = verifyThenRun(gpg, trustedHome, signature, artifact);
            assertTrue(verified.launched(),
                    () -> "valid signature did not permit launch\n" + verified.verification().output());
            assertPassed(verified.run());
            assertArrayEquals(packagedHash, sha256(artifact), "verification or launch changed the runner JAR");

            Path tampered = scenario.resolve("app-tampered.jar");
            Files.copy(artifact, tampered, StandardCopyOption.REPLACE_EXISTING);
            Files.write(tampered, new byte[] {0}, StandardOpenOption.APPEND);
            VerificationRun modified = verifyThenRun(gpg, trustedHome, signature, tampered);
            assertFalse(modified.launched(), "modified bytes were launched after signature verification");
            assertFalse(modified.verification().status() == 0, "modified bytes passed detached verification");

            Path otherSignerHome = gpgHome(gpgRoot.resolve("o"));
            String otherSigner = generateSigningKey(gpg, otherSignerHome,
                    "Untrusted Runner Test <untrusted@example.invalid>");
            Path wrongTrustedHome = gpgHome(gpgRoot.resolve("w"));
            importPublicKey(gpg, otherSignerHome, otherSigner, wrongTrustedHome,
                    scenario.resolve("untrusted-key.asc"));
            VerificationRun wrongIdentity = verifyThenRun(gpg, wrongTrustedHome, signature, artifact);
            assertFalse(wrongIdentity.launched(), "an artifact was launched with the wrong trusted identity");
            assertFalse(wrongIdentity.verification().status() == 0,
                    "a signature from outside the pinned keyring was accepted");
        } finally {
            deleteRecursively(gpgRoot);
        }
    }

    private static VerificationRun verifyThenRun(String gpg, Path verifierHome, Path signature, Path artifact)
            throws IOException, InterruptedException {
        CommandResult verification = command(workspace, gpg, "--batch", "--homedir", verifierHome.toString(),
                "--verify", signature.toString(), artifact.toString());
        if (verification.status() != 0) {
            return new VerificationRun(false, null, verification);
        }
        return new VerificationRun(true, fork(artifact, workspace, List.of(), List.of()), verification);
    }

    private static String generateSigningKey(String gpg, Path home, String identity)
            throws IOException, InterruptedException {
        requireSuccess(command(workspace, gpg, "--batch", "--homedir", home.toString(),
                "--pinentry-mode", "loopback", "--passphrase", "", "--quick-generate-key", identity,
                "ed25519", "sign", "0"));
        CommandResult listing = command(workspace, gpg, "--batch", "--homedir", home.toString(),
                "--with-colons", "--list-secret-keys", identity);
        requireSuccess(listing);
        for (String line : listing.output().split("\\R")) {
            String[] fields = line.split(":", -1);
            if (fields.length > 9 && "fpr".equals(fields[0])) {
                return fields[9];
            }
        }
        throw new AssertionError("GnuPG did not report a fingerprint for " + identity + "\n" + listing.output());
    }

    private static void importPublicKey(String gpg, Path signerHome, String fingerprint, Path verifierHome,
            Path exportedKey) throws IOException, InterruptedException {
        requireSuccess(command(workspace, gpg, "--batch", "--homedir", signerHome.toString(), "--armor",
                "--output", exportedKey.toString(), "--export", fingerprint));
        requireSuccess(command(workspace, gpg, "--batch", "--homedir", verifierHome.toString(),
                "--import", exportedKey.toString()));
    }

    private static Path gpgHome(Path directory) throws IOException {
        Files.createDirectories(directory);
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows has no POSIX mode bits; GnuPG uses the platform ACLs instead.
        }
        return directory;
    }

    private static String gpgExecutable() throws IOException, InterruptedException {
        for (String candidate : List.of("gpg", "gpg.exe")) {
            try {
                CommandResult result = command(workspace, candidate, "--version");
                if (result.status() == 0) {
                    return candidate;
                }
            } catch (IOException ignored) {
                // Try the platform-specific spelling next.
            }
        }
        return null;
    }

    private static byte[] sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return digest.digest();
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var entries = Files.walk(directory)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static CommandResult command(Path workingDirectory, String... command)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new CommandResult(process.waitFor(), output, List.of(command));
    }

    private static void requireSuccess(CommandResult result) {
        assertEquals(0, result.status(), () -> String.join(" ", result.command()) + " failed\n" + result.output());
    }

    /**
     * Fails with the application's own {@code FAIL} lines when any assertion inside it did not hold.
     */
    private static void assertPassed(Forked run) {
        List<String> failures = new ArrayList<>();
        for (String line : run.output().split("\n", -1)) {
            if (line.startsWith("FAIL ")) {
                failures.add(line);
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
        assertTrue(run.output().contains(RESULT_OK),
                () -> "the application did not report success\n" + run.output());
        assertEquals(0, run.status(), () -> "the JVM exited with " + run.status() + "\n" + run.output());
    }

    /**
     * Starts the archive with {@code java -jar} in the JDK the build points at, and waits for it.
     */
    private static Forked fork(Path archive, Path workingDirectory, List<String> jvmArguments,
            List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.addAll(jvmArguments);
        command.add("-jar");
        command.add(archive.toAbsolutePath().toString());
        command.addAll(arguments);
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new Forked(process.waitFor(), output);
    }

    /**
     * The {@code java} of the JDK the build runs on, which is the JDK the forked application must use.
     */
    private static Path javaExecutable() {
        String home = System.getProperty("runner.test.javaHome", System.getProperty("java.home"));
        if (home == null || home.isEmpty()) {
            return null;
        }
        Path candidate = Path.of(home, "bin", "java");
        if (!Files.isExecutable(candidate)) {
            candidate = Path.of(home, "bin", "java.exe");
        }
        return Files.isExecutable(candidate) ? candidate : null;
    }

    private static void compile(JavaCompiler compiler, Path sources, Path classes, Path classpath,
            Map<String, String> files) throws IOException {
        List<String> arguments = new ArrayList<>(List.of("--release", "25", "-d", classes.toString()));
        if (classpath != null) {
            arguments.add("-cp");
            arguments.add(classpath.toString());
        }
        for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
            Path source = sources.resolve(file.getKey());
            Files.createDirectories(source.getParent());
            Files.writeString(source, file.getValue());
            arguments.add(source.toString());
        }
        if (compiler.run(null, null, null, arguments.toArray(new String[0])) != 0) {
            throw new IOException("Could not compile the fixture sources under " + sources);
        }
    }

    private static Manifest manifest(Consumer<Attributes> configure) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        configure.accept(manifest.getMainAttributes());
        return manifest;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static void writeJar(Path file, Manifest manifest, Map<String, byte[]> entries)
            throws IOException {
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry record = new ZipEntry(entry.getKey());
                record.setTime(FIXTURE_TIME);
                out.putNextEntry(record);
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static String versionedSource(String answer) {
        return """
                package org.deptwo;

                public final class Versioned {
                    public static String which() {
                        return "%s";
                    }
                }
                """.formatted(answer);
    }

    /**
     * The outcome of one forked JVM.
     *
     * @param status the exit status
     * @param output standard output and standard error, interleaved
     */
    private record Forked(int status, String output) {
    }

    private record CommandResult(int status, String output, List<String> command) {
    }

    private record VerificationRun(boolean launched, Forked run, CommandResult verification) {
    }
}
