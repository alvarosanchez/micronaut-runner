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

import io.micronaut.core.annotation.Experimental;
import io.micronaut.runner.Index;
import io.micronaut.runner.build.training.TrainingDriver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Writes the extracted layout of a Runner JAR, the deployment a JDK AOT cache serves in full: an application
 * JAR whose manifest {@code Class-Path} lists {@code lib/*.jar} in class-path order, and the dependencies under
 * {@code lib/}, byte for byte the JARs the Runner JAR nests.
 *
 * <p>The layout is what {@code java -Dmicronaut.runner.mode=extract} writes, forked with the {@code java} the
 * cache is trained with, so the build runs exactly the path a user runs and keeps its own JVM clean. Every file
 * carries the modification time {@code 1980-02-01T00:00:00Z}, so a layout copied with its times kept, or
 * extracted again from the same JAR, still matches a cache trained on it.</p>
 *
 * @since 1.0
 */
@Experimental
public final class AotLayout {

    /** How long an extraction may take unless the caller says otherwise. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /** The modification time of every file of the layout. */
    public static final FileTime FILE_TIME = FileTime.from(Instant.parse("1980-02-01T00:00:00Z"));

    /** The directory of the dependencies, relative to the layout. */
    public static final String LIBRARY_DIRECTORY = "lib";

    /** How much of the extraction's output a failure quotes. */
    private static final int TAIL_CHARS = 4_000;

    /** How long a killed extraction is given to disappear. */
    private static final long KILL_MILLIS = 5_000;

    private AotLayout() {
    }

    /**
     * Extracts a Runner JAR into a directory, replacing what the directory held, and verifies the result.
     *
     * @param java        the {@code java} executable that runs the extraction
     * @param runnerJar   the Runner JAR
     * @param destination the directory, which is replaced as a whole
     * @param timeout     how long the extraction may take
     * @return the application JAR and the dependencies, in class-path order
     * @throws IOException          if the extraction fails or times out, or its output does not verify
     * @throws InterruptedException if the thread is interrupted; the extraction has been killed by then
     */
    public static Result write(Path java, Path runnerJar, Path destination, Duration timeout)
            throws IOException, InterruptedException {
        Path archive = runnerJar.toAbsolutePath().normalize();
        Path target = destination.toAbsolutePath().normalize();
        List<String> command = List.of(java.toString(), "-Dmicronaut.runner.mode=extract", "-jar",
                archive.toString(), "--destination", target.toString(), "--force");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        TrainingDriver.AMBIENT_JVM_OPTIONS.forEach(environment::remove);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
            builder.directory(parent.toFile());
        }
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(output);
            } catch (IOException ignored) {
                // The exit status decides; the output only explains a failure.
            }
        }, "micronaut-runner-extract-output");
        drain.setDaemon(true);
        drain.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(KILL_MILLIS, TimeUnit.MILLISECONDS);
                throw new IOException("Extracting " + archive.getFileName() + " did not finish within " + timeout
                        + tail(output));
            }
            drain.join(KILL_MILLIS);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        if (process.exitValue() != 0) {
            throw new IOException("Extracting " + archive.getFileName() + " into " + target + " failed with status "
                    + process.exitValue() + ": " + String.join(" ", command) + tail(output));
        }
        return verify(target, archive);
    }

    /**
     * Checks a layout against the Runner JAR it was extracted from: the application JAR's {@code Class-Path}
     * names {@code lib/} plus each dependency's file name, in index order, and the application JAR and every
     * file under {@code lib/} carry {@link #FILE_TIME}. A JDK AOT cache validates both.
     *
     * @param destination the layout
     * @param runnerJar   the Runner JAR
     * @return the application JAR and the dependencies, in class-path order
     * @throws IOException if the layout does not match; the message names the first offending entry
     */
    public static Result verify(Path destination, Path runnerJar) throws IOException {
        Path layout = destination.toAbsolutePath().normalize();
        Path applicationJar = layout.resolve(applicationJarName(runnerJar));
        if (!Files.isRegularFile(applicationJar)) {
            throw new IOException("The layout in " + layout + " has no application JAR "
                    + applicationJar.getFileName());
        }
        List<String> expected = new ArrayList<>();
        try (RunnerJarReader reader = RunnerJarReader.open(runnerJar)) {
            Index index = reader.index();
            for (int jarId = 1; jarId < index.jarCount(); jarId++) {
                String name = index.jarName(jarId);
                int slash = name.lastIndexOf('/');
                expected.add(LIBRARY_DIRECTORY + "/" + (slash < 0 ? name : name.substring(slash + 1)));
            }
        }
        List<String> actual = classPath(applicationJar);
        for (int i = 0; i < Math.max(expected.size(), actual.size()); i++) {
            String want = i < expected.size() ? expected.get(i) : "nothing";
            String have = i < actual.size() ? actual.get(i) : "nothing";
            if (!want.equals(have)) {
                throw new IOException("The Class-Path of " + applicationJar.getFileName() + " does not follow the"
                        + " class-path order of " + runnerJar.getFileName() + ": entry " + (i + 1) + " is " + have
                        + ", not " + want);
            }
        }
        List<Path> libraries = new ArrayList<>(actual.size());
        for (String entry : actual) {
            Path library = layout.resolve(entry);
            if (!Files.isRegularFile(library)) {
                throw new IOException("The layout in " + layout + " has no " + entry + ", which "
                        + applicationJar.getFileName() + " names in its Class-Path");
            }
            libraries.add(library);
        }
        List<Path> files = new ArrayList<>();
        files.add(applicationJar);
        Path lib = layout.resolve(LIBRARY_DIRECTORY);
        if (Files.isDirectory(lib)) {
            try (Stream<Path> walk = Files.walk(lib)) {
                walk.filter(Files::isRegularFile).sorted().forEach(files::add);
            }
        }
        for (Path file : files) {
            FileTime time = Files.getLastModifiedTime(file);
            if (!time.equals(FILE_TIME)) {
                throw new IOException(layout.relativize(file).toString().replace('\\', '/') + " was modified at "
                        + time + ", not at " + FILE_TIME + " as extracted: a JDK AOT cache trained on the layout"
                        + " rejects a JAR whose modification time changed. Copy the layout with its times kept, as"
                        + " cp -p and Docker COPY do");
            }
        }
        return new Result(applicationJar, List.copyOf(libraries));
    }

    /**
     * The file name Extract gives the application JAR: the Runner JAR's, with any extension replaced by
     * {@code .jar}.
     *
     * @param runnerJar the Runner JAR
     * @return the file name
     */
    public static String applicationJarName(Path runnerJar) {
        Path file = runnerJar.getFileName();
        String name = file == null ? "" : file.toString();
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name + "-extracted") + ".jar";
    }

    private static List<String> classPath(Path applicationJar) throws IOException {
        Manifest manifest;
        try (JarFile jar = new JarFile(applicationJar.toFile(), false)) {
            manifest = jar.getManifest();
        }
        String value = manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        List<String> entries = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return entries;
        }
        for (String token : value.trim().split("\\s+")) {
            try {
                entries.add(new URI(token).getPath());
            } catch (java.net.URISyntaxException e) {
                throw new IOException("The Class-Path of " + applicationJar.getFileName() + " has an entry that is"
                        + " not a relative URL: " + token, e);
            }
        }
        return entries;
    }

    private static String tail(ByteArrayOutputStream output) {
        String text = output.toString(StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            return "";
        }
        return "\n" + (text.length() > TAIL_CHARS ? "..." + text.substring(text.length() - TAIL_CHARS) : text);
    }

    /**
     * An extracted layout.
     *
     * @param applicationJar the application JAR, which {@code java -jar} launches
     * @param libraries      the dependencies, in class-path order
     */
    public record Result(Path applicationJar, List<Path> libraries) {
    }
}
