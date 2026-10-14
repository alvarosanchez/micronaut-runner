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
package io.micronaut.runner.protocol.jar;

import io.micronaut.runner.ArchiveSource;
import io.micronaut.runner.Handlers;
import io.micronaut.runner.Index;
import io.micronaut.runner.IndexFormat;
import io.micronaut.runner.RunnerClassLoader;
import io.micronaut.runner.TestArchiveBuilder;
import io.micronaut.runner.TestIndexBuilder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.CRC32;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the class path scans of three libraries over a runner archive and over the same jars on a
 * {@link URLClassLoader}, and expects the same answers. Each scan is the library's algorithm written out with
 * no dependency on the library: it asks the class loader for a location, opens each URL's
 * {@link JarURLConnection} and lists what the connection describes.
 *
 * <p>The archive has the application layer and one dependency, and both contribute to the same
 * {@code db/migration} location and the same package. A scanner that lists {@code getJarFile()} for the names
 * under the location, as Flyway does, found nothing in the application layer while its connection described the
 * outer archive, where those entries carry the {@value IndexFormat#CLASSES_PREFIX} prefix.</p>
 */
class ClassPathScanTest {

    private static final String DEPENDENCY = "MICRONAUT-INF/lib/migrations.jar";
    private static final byte[] DIRECTORY = new byte[0];
    private static final String MANIFEST = "META-INF/MANIFEST.MF";
    /** The application's own attributes, which its jar carries and the packager copies into the archive's. */
    private static final String APPLICATION_ATTRIBUTES = "Implementation-Title: scan\r\n"
            + "Implementation-Version: 1.2.3\r\n";

    @TempDir
    static Path temporary;

    private static File archive;
    private static File applicationJar;
    private static File dependencyJar;
    private static ArchiveSource source;
    private static Index index;

    @BeforeAll
    static void buildTheArchiveAndItsPlainJars() throws IOException {
        Map<String, byte[]> application = application();
        Map<String, byte[]> dependency = dependency();

        TestArchiveBuilder nested = new TestArchiveBuilder();
        Map<String, Long> inner = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : dependency.entrySet()) {
            inner.put(entry.getKey(), nested.stored(entry.getKey(), entry.getValue()));
        }
        byte[] dependencyBytes = nested.build();

        TestArchiveBuilder outer = new TestArchiveBuilder();
        // The packager builds the layer from class directories, which carry no manifest, and writes the
        // application's attributes into the archive's own manifest.
        outer.stored(MANIFEST, bytes("Manifest-Version: 1.0\r\nMain-Class: io.micronaut.runner.Launcher\r\n"
                + APPLICATION_ATTRIBUTES + "\r\n"));
        byte[] draft = index(application, new LinkedHashMap<>(), dependency, inner, 0, 0, 0);
        outer.reserve(IndexFormat.INDEX_ENTRY_NAME, draft.length);
        Map<String, Long> layer = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : application.entrySet()) {
            // The packager stores the application layer exploded, without its directories: the index
            // synthesises a record for each one.
            if (!entry.getKey().endsWith("/")) {
                layer.put(entry.getKey(), outer.stored(IndexFormat.CLASSES_PREFIX + entry.getKey(), entry.getValue()));
            }
        }
        long base = outer.stored(DEPENDENCY, dependencyBytes);
        long header = outer.localHeaderOffset(DEPENDENCY);
        byte[] real = index(application, layer, dependency, inner, base, dependencyBytes.length, header);
        assertEquals(draft.length, real.length, "the index size must not depend on the offsets");
        outer.replace(IndexFormat.INDEX_ENTRY_NAME, real);
        archive = outer.writeTo(temporary.resolve("app.jar").toFile());

        TestArchiveBuilder plain = new TestArchiveBuilder();
        plain.stored(MANIFEST, bytes("Manifest-Version: 1.0\r\nMain-Class: com.example.scan.First\r\n"
                + APPLICATION_ATTRIBUTES + "\r\n"));
        for (Map.Entry<String, byte[]> entry : application.entrySet()) {
            plain.stored(entry.getKey(), entry.getValue());
        }
        applicationJar = plain.writeTo(temporary.resolve("application.jar").toFile());
        dependencyJar = Files.write(temporary.resolve("migrations.jar"), dependencyBytes).toFile();

        source = ArchiveSource.open(archive);
        index = Index.open(source);
        index.validateStringReferences();
        // Registration is global to the JVM and the first archive wins, so this class takes the handler for
        // its duration and hands it back in closeTheArchive().
        Handlers.unregister();
        Handlers.register(archive, index, source);
    }

    @AfterAll
    static void closeTheArchive() {
        Handlers.unregister();
        if (source != null) {
            source.close();
        }
    }

    /**
     * Flyway lists {@code getJarFile()} for the names under {@code db/migration/}, then reads each one through
     * the URL of the name whose path contains the location URL's path.
     */
    @Test
    void flywayFindsTheMigrationsOfEveryJar() throws IOException {
        try (URLClassLoader oracle = oracle()) {
            List<String> expected = flyway(oracle, "db/migration");
            assertEquals(List.of(
                    "db/migration/V1__schema.sql=create table genre(id int primary key, name varchar(255));",
                    "db/migration/V2__genres.sql=insert into genre(id, name) values (1, 'music');",
                    "db/migration/V3__more_genres.sql=insert into genre(id, name) values (2, 'books');"),
                    expected, "the oracle itself");
            assertEquals(expected, flyway(runner(), "db/migration"));
        }
    }

    /**
     * Spring's {@code PathMatchingResourcePatternResolver} takes the root entry's name as the prefix to strip,
     * and builds each resource's URL relative to the directory URL.
     */
    @Test
    void springFindsTheSameResources() throws IOException {
        try (URLClassLoader oracle = oracle()) {
            List<String> expected = spring(oracle, "db/migration", ".sql");
            assertEquals(3, expected.size(), expected::toString);
            assertEquals(expected, spring(runner(), "db/migration", ".sql"));
        }
    }

    /**
     * Reflections cuts each URL of a package back to the root it was found in, the code source, and walks
     * the whole jar of that root's connection.
     */
    @Test
    void aReflectionsStyleScanFindsTheSameClasses() throws IOException {
        try (URLClassLoader oracle = oracle()) {
            List<String> expected = reflections(oracle, "com.example.scan");
            assertEquals(List.of("com/example/scan/First.class", "com/example/scan/Second.class",
                    "com/example/scan/Third.class"), expected, "the oracle itself");
            assertEquals(expected, reflections(runner(), "com.example.scan"));
        }
    }

    /**
     * Code that reports its own version reads the main attributes of the manifest of the jar it was loaded
     * from, through the connection of its class's URL or of its code source. The application's own jar, the
     * oracle here, answers with its manifest, as a fat jar does with its own; the application layer, which has
     * none, answers with the archive's, where the packager put the application's attributes.
     */
    @Test
    void theApplicationsManifestDescribesItsClasses() throws IOException {
        try (URLClassLoader oracle = oracle()) {
            List<String> expected = implementation(oracle, "com/example/scan/First.class");
            assertEquals(List.of("scan 1.2.3", "scan 1.2.3"), expected, "the oracle itself");
            assertEquals(expected, implementation(runner(), "com/example/scan/First.class"));
        }
    }

    /**
     * Flyway 12's {@code ClassPathScanner} with its {@code JarFileClassPathLocationScanner}, and
     * {@code ClassPathResource.read()}.
     *
     * @return {@code name=content} for every migration, in the order Flyway finds them
     */
    private static List<String> flyway(ClassLoader loader, String location) throws IOException {
        List<String> found = new ArrayList<>();
        for (URL locationUrl : Collections.list(loader.getResources(location))) {
            JarURLConnection connection = (JarURLConnection) locationUrl.openConnection();
            connection.setUseCaches(false);
            TreeSet<String> names = new TreeSet<>();
            try (JarFile jar = connection.getJarFile()) {
                String prefix = jar.getName().toLowerCase(Locale.ROOT).endsWith(".war") ? "WEB-INF/classes/" : "";
                String folder = prefix + location + "/";
                for (JarEntry entry : Collections.list(jar.entries())) {
                    if (entry.getName().startsWith(folder)) {
                        names.add(entry.getName().substring(prefix.length()));
                    }
                }
            }
            for (String name : names) {
                if (!name.endsWith("/")) {
                    found.add(name + "=" + readUnder(loader, name, locationUrl.getPath()));
                }
            }
        }
        return found;
    }

    /**
     * What {@code ClassPathResource.read()} reads: the first URL of the name whose path contains the path of
     * the location URL it was found under.
     */
    private static String readUnder(ClassLoader loader, String name, String locationPath) throws IOException {
        for (URL url : Collections.list(loader.getResources(name))) {
            if (url.getPath() != null && url.getPath().contains(locationPath)) {
                return read(url);
            }
        }
        throw new AssertionError("Unable to obtain inputstream for resource: " + name);
    }

    /**
     * The jar branch of Spring's {@code PathMatchingResourcePatternResolver} for
     * {@code classpath*:<directory>/*<suffix>}.
     *
     * @return {@code name=content} for every match, in the order Spring finds them
     */
    @SuppressWarnings("deprecation")
    private static List<String> spring(ClassLoader loader, String directory, String suffix) throws IOException {
        List<String> found = new ArrayList<>();
        for (URL rootDirUrl : Collections.list(loader.getResources(directory + "/"))) {
            JarURLConnection connection = (JarURLConnection) rootDirUrl.openConnection();
            connection.setUseCaches(false);
            JarFile jar = connection.getJarFile();
            try {
                JarEntry rootEntry = connection.getJarEntry();
                String rootEntryPath = rootEntry != null ? rootEntry.getName() : "";
                if (!rootEntryPath.isEmpty() && !rootEntryPath.endsWith("/")) {
                    rootEntryPath = rootEntryPath + "/";
                }
                for (String entryPath : jar.stream().map(JarEntry::getName).sorted().toList()) {
                    if (entryPath.startsWith(rootEntryPath)) {
                        String relativePath = entryPath.substring(rootEntryPath.length());
                        if (!relativePath.contains("/") && relativePath.endsWith(suffix)) {
                            found.add(directory + "/" + relativePath + "=" + read(new URL(rootDirUrl, relativePath)));
                        }
                    }
                }
            } finally {
                if (!connection.getUseCaches()) {
                    jar.close();
                }
            }
        }
        return found;
    }

    /**
     * Reflections' {@code ClasspathHelper.forPackage} and its {@code Vfs} type for {@code jar:} URLs.
     *
     * @return the class entries of the package, in the order Reflections finds them
     */
    @SuppressWarnings("deprecation")
    private static List<String> reflections(ClassLoader loader, String packageName) throws IOException {
        String resourceName = packageName.replace('.', '/');
        List<String> found = new ArrayList<>();
        for (URL url : Collections.list(loader.getResources(resourceName))) {
            String form = url.toExternalForm();
            int at = form.lastIndexOf(resourceName);
            URL root = at == -1 ? url : new URL(url, form.substring(0, at));
            JarURLConnection connection = (JarURLConnection) root.openConnection();
            connection.setUseCaches(false);
            try (JarFile jar = connection.getJarFile()) {
                for (JarEntry entry : Collections.list(jar.entries())) {
                    String path = entry.getName();
                    if (!entry.isDirectory() && path.startsWith(resourceName + "/") && path.endsWith(".class")) {
                        found.add(path);
                    }
                }
            }
        }
        return found;
    }

    /**
     * The {@code Implementation-Title} and {@code Implementation-Version} that the connection of a class's URL,
     * and then of the root of the jar it is in, reports.
     *
     * @return one {@code title version} per URL
     */
    @SuppressWarnings("deprecation")
    private static List<String> implementation(ClassLoader loader, String name) throws IOException {
        URL url = loader.getResource(name);
        String form = url.toExternalForm();
        URL root = new URL(url, form.substring(0, form.lastIndexOf(name)));
        List<String> found = new ArrayList<>();
        for (URL each : List.of(url, root)) {
            JarURLConnection connection = (JarURLConnection) each.openConnection();
            connection.setUseCaches(false);
            try (JarFile ignored = connection.getJarFile()) {
                Attributes main = connection.getMainAttributes();
                found.add(main == null ? "no manifest"
                        : main.getValue("Implementation-Title") + " " + main.getValue("Implementation-Version"));
            }
        }
        return found;
    }

    private static RunnerClassLoader runner() {
        return new RunnerClassLoader(index, source, ClassLoader.getPlatformClassLoader());
    }

    private static URLClassLoader oracle() throws IOException {
        return new URLClassLoader(new URL[] {applicationJar.toURI().toURL(), dependencyJar.toURI().toURL()},
                ClassLoader.getPlatformClassLoader());
    }

    /** The application's jar as a build tool writes it, directories included. */
    private static Map<String, byte[]> application() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("application.properties", bytes("micronaut.application.name=scan"));
        entries.put("com/", DIRECTORY);
        entries.put("com/example/", DIRECTORY);
        entries.put("com/example/scan/", DIRECTORY);
        entries.put("com/example/scan/First.class", bytes("first"));
        entries.put("com/example/scan/Second.class", bytes("second"));
        entries.put("db/", DIRECTORY);
        entries.put("db/migration/", DIRECTORY);
        entries.put("db/migration/V1__schema.sql", bytes("create table genre(id int primary key, name varchar(255));"));
        entries.put("db/migration/V2__genres.sql", bytes("insert into genre(id, name) values (1, 'music');"));
        return entries;
    }

    /** A dependency that ships a migration of its own and a class of the same package. */
    private static Map<String, byte[]> dependency() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("com/", DIRECTORY);
        entries.put("com/example/", DIRECTORY);
        entries.put("com/example/scan/", DIRECTORY);
        entries.put("com/example/scan/Third.class", bytes("third"));
        entries.put("db/", DIRECTORY);
        entries.put("db/migration/", DIRECTORY);
        entries.put("db/migration/V3__more_genres.sql", bytes("insert into genre(id, name) values (2, 'books');"));
        return entries;
    }

    private static byte[] index(Map<String, byte[]> application, Map<String, Long> layer,
                                Map<String, byte[]> dependency, Map<String, Long> inner, long base, long length,
                                long header) {
        TestIndexBuilder builder = new TestIndexBuilder()
                .startClass("com.example.scan.First")
                .headerFlags(IndexFormat.HEADER_FLAG_NESTED_STORED);
        TestIndexBuilder.Jar applicationLayer = builder.addJar(IndexFormat.CLASSES_PREFIX);
        for (Map.Entry<String, byte[]> entry : application.entrySet()) {
            if (!entry.getKey().endsWith("/")) {
                byte[] content = entry.getValue();
                applicationLayer.addEntry(entry.getKey())
                        .data(layer.getOrDefault(entry.getKey(), 0L), content.length, content.length)
                        .crc32(crc32(content));
            }
        }
        TestIndexBuilder.Jar nested = builder.addJar(DEPENDENCY).location(base, length, header);
        for (Map.Entry<String, byte[]> entry : dependency.entrySet()) {
            byte[] content = entry.getValue();
            nested.addEntry(entry.getKey())
                    .data(base + inner.getOrDefault(entry.getKey(), 0L), content.length, content.length)
                    .crc32(crc32(content));
        }
        return builder.build();
    }

    private static String read(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        connection.setUseCaches(false);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static long crc32(byte[] value) {
        CRC32 checksum = new CRC32();
        checksum.update(value);
        return checksum.getValue();
    }
}
