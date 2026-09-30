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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * The packaging step that adds the static service table to an application: it shows the builder's layers to
 * {@link StaticServiceTableGenerator}, reports what came of it, and closes what it opened.
 *
 * <p>The table answers Micronaut's service lookups from names computed at packaging time, so the application
 * does not scan its class path for them when it starts. A table that differs from that scan would change which
 * beans the application has, so the generator produces one only when it can reproduce the scan, and every
 * doubt resolves to "no table", reported with its reason: the option turned off, no micronaut-core or one
 * this packager does not know, another loader that already supplies Micronaut's service loaders, or a
 * generated name that is taken.</p>
 *
 * <p>The step runs once the merged metadata is planned, which fixes the order of the
 * {@code META-INF/micronaut} names, and before the application entries are, so that what it generates is
 * packaged like any other application class, after every other step that adds to the application layer.
 * Class files and service files are parsed, never loaded. A nested jar is opened on its first read, at most
 * once, and closed before the step returns.</p>
 *
 * @since 1.0
 */
final class StaticServices {

    private static final String SCAN = "; Micronaut will scan for its services when the application starts";
    private static final String MANIFEST_NAME = "META-INF/MANIFEST.MF";

    private StaticServices() {
    }

    /**
     * Generates the table, when the application can have one, and logs one line about it: what was
     * generated and how long it took, or why nothing was.
     *
     * @param spec             what is being packaged
     * @param logger           where the line goes
     * @param warn             where a reason that deserves a warning goes instead
     * @param mergedServices   the size of every file of the merged {@code META-INF/micronaut/} copy, by entry
     *                         name, in the order the archive stores them
     * @param applicationNames the names of the application layer's entries
     * @param application      reads an entry of the application layer
     * @param nested           the nested jars, in index order
     * @return the entries to put into the application layer, which are none when there is no table
     * @throws IOException if a service file or a class file cannot be read back from a layer, or a nested jar
     *                     cannot be closed
     */
    static StaticServiceTableGenerator.Result generate(RunnerJarSpec spec, BuildLogger logger,
            Consumer<String> warn, Map<String, Long> mergedServices, List<String> applicationNames,
            EntryReader application, List<NestedLayer> nested) throws IOException {
        if (!spec.staticServices()) {
            logger.info("No static Micronaut service table was generated because it was not requested" + SCAN);
            return StaticServiceTableGenerator.Result.none("it was not requested");
        }
        long start = System.nanoTime();
        List<StaticServiceTableGenerator.Layer> layers = new ArrayList<>(nested.size() + 1);
        layers.add(new ApplicationLayer(applicationNames, application, spec.multiRelease()));
        layers.addAll(nested);
        StaticServiceTableGenerator.Result result;
        Throwable failure = null;
        try {
            result = StaticServiceTableGenerator.generate(layers, mergedServices);
        } catch (Throwable e) {
            failure = e;
            throw e;
        } finally {
            close(nested, failure);
        }
        if (!result.generated()) {
            String message = "No static Micronaut service table was generated because " + result.reason() + SCAN;
            if (result.warn()) {
                warn.accept(message);
            } else {
                logger.info(message);
            }
            return result;
        }
        logger.info("Generated a static Micronaut service table: " + result.describe() + " for micronaut-core "
                + result.coreVersion() + " in " + (System.nanoTime() - start) / 1_000_000 + " ms");
        return result;
    }

    /**
     * Closes the reader of every nested jar the generator opened.
     *
     * @param layers  the nested layers
     * @param failure what the generator is already failing with, or {@code null}
     * @throws IOException if a reader cannot be closed and the generator did not fail; when it did, the
     *                     failure to close is added to {@code failure} as suppressed instead
     */
    private static void close(List<NestedLayer> layers, Throwable failure) throws IOException {
        IOException closing = null;
        for (NestedLayer layer : layers) {
            try {
                layer.close();
            } catch (IOException e) {
                if (closing == null) {
                    closing = e;
                } else {
                    closing.addSuppressed(e);
                }
            }
        }
        if (closing == null) {
            return;
        }
        if (failure == null) {
            throw closing;
        }
        failure.addSuppressed(closing);
    }

    private static String implementationVersion(Manifest manifest) {
        return manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
    }

    /** Reads one entry of the application layer into memory. */
    @FunctionalInterface
    interface EntryReader {

        /**
         * @param name the logical name of an entry the layer has
         * @return its content
         * @throws IOException if it cannot be read
         */
        byte[] read(String name) throws IOException;
    }

    /**
     * The application layer as the generator reads it: the entries collected so far, and the ones earlier
     * steps generated.
     */
    private static final class ApplicationLayer implements StaticServiceTableGenerator.Layer {

        private final List<String> names;
        private final EntryReader reader;
        private final boolean multiRelease;

        private ApplicationLayer(List<String> names, EntryReader reader, boolean multiRelease) {
            this.names = names;
            this.reader = reader;
            this.multiRelease = multiRelease;
        }

        @Override
        public String description() {
            return "the application output";
        }

        @Override
        public int size() {
            return names.size();
        }

        @Override
        public String name(int position) {
            return names.get(position);
        }

        @Override
        public byte[] read(int position) throws IOException {
            return reader.read(names.get(position));
        }

        @Override
        public String implementationVersion() throws IOException {
            if (!names.contains(MANIFEST_NAME)) {
                return null;
            }
            return StaticServices.implementationVersion(
                    new Manifest(new ByteArrayInputStream(reader.read(MANIFEST_NAME))));
        }

        @Override
        public boolean multiRelease() {
            return multiRelease;
        }
    }

    /**
     * One nested jar as the generator reads it. The entries are the ones its stage recorded, so listing them
     * opens nothing; the jar is opened by the first read and stays open until the step closes it.
     */
    static final class NestedLayer implements StaticServiceTableGenerator.Layer {

        private final Path dependency;
        private final Path file;
        private final List<ZipEntryInfo> entries;
        private final Manifest manifest;
        private ZipReader reader;

        /**
         * @param dependency the dependency the jar was staged from, which is what a message names
         * @param file       the nested jar as it was staged: the repacked copy, or the dependency itself
         * @param entries    the entries of {@code file}
         * @param manifest   the jar's manifest, or {@code null} when it has none
         */
        NestedLayer(Path dependency, Path file, List<ZipEntryInfo> entries, Manifest manifest) {
            this.dependency = dependency;
            this.file = file;
            this.entries = entries;
            this.manifest = manifest;
        }

        @Override
        public String description() {
            return "the dependency " + dependency;
        }

        @Override
        public int size() {
            return entries.size();
        }

        @Override
        public String name(int position) {
            ZipEntryInfo entry = entries.get(position);
            String name = entry.name();
            return entry.directory() && !name.endsWith("/") ? name + "/" : name;
        }

        @Override
        public byte[] read(int position) throws IOException {
            try {
                if (reader == null) {
                    reader = ZipReader.open(file);
                }
                return reader.read(entries.get(position));
            } catch (IOException e) {
                // In STORED the reader names the repacked copy; the dependency is what has to be fixed.
                throw new IOException("The dependency " + dependency + " cannot be packaged: " + e.getMessage(), e);
            }
        }

        @Override
        public String implementationVersion() {
            return StaticServices.implementationVersion(manifest);
        }

        @Override
        public boolean multiRelease() {
            return manifest != null && "true".equalsIgnoreCase(manifest.getMainAttributes().getValue("Multi-Release"));
        }

        private void close() throws IOException {
            if (reader != null) {
                ZipReader open = reader;
                reader = null;
                open.close();
            }
        }
    }
}
