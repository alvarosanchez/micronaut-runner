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

import io.micronaut.runner.Index;
import io.micronaut.runner.IndexFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Packages the bean definition prefetch for {@link RunnerJarBuilder}: decides whether the application can use
 * it, and hands back the entries that put it into the application layer.
 *
 * <h2>What is packaged</h2>
 * <p>Micronaut loads and initialises almost every bean definition reference before the application is ready, in
 * parallel on the common pool, but only once the main thread has configured logging and created the context
 * builder. The entry stub runs before the application's {@code main}, so it can start that work earlier than any
 * point Micronaut owns. With the prefetch, the stub calls {@value #CONFIGURER_CLASS}{@code .start()}, which runs
 * Micronaut's own {@code DefaultBeanDefinitionsProvider.provide(ClassLoader)} on the common pool, and the same
 * class, registered as an {@code ApplicationContextConfigurer} service, hands the result to the first
 * application context through the builder's {@code beanDefinitionsProvider}.</p>
 *
 * <p>Three things change in the application layer, and nothing else in the archive: the entry stub is replaced,
 * in place, by one that starts the prefetch; the two classes are added; and the configurer is registered in the
 * layer's {@value #SERVICE_ENTRY}, after the lines the application already has there, or in a new file. The
 * service files of nested jars are untouched.</p>
 *
 * <p>Both classes are hand-written, compiled once when this library is built, against Micronaut but never on this
 * library's class path, and copied byte for byte. They travel as a jar resource, like the launcher: a build tool
 * that rewrites the class files of a plugin class path, as Gradle's instrumentation does to calls of
 * {@code System.getProperty}, does not look inside a nested jar.</p>
 *
 * <h2>When it is left out</h2>
 * <p>Like the entry stub, the prefetch is worth having and harmless to leave out, so every doubt resolves to "no
 * prefetch", reported at an informational level with the first reason that applies: the option turned off, no
 * entry stub to start it from, a Micronaut without the provider the task calls (4.x, or a later one that reshaped
 * it), no bean definition reference to load, or a reference entry with content, which a later Micronaut may give
 * a meaning this version does not know. An application that already carries an entry in the prefetch's own
 * package is a warning instead, as it is for the entry stub.</p>
 *
 * <h2>Nothing is run</h2>
 * <p>The packager parses the application's {@code DefaultBeanDefinitionsProvider} and the two Micronaut types
 * whose methods the configurer calls, each the first copy on the class path, and loads none of them.</p>
 *
 * @since 1.0
 */
final class DefinitionPrefetchPackager {

    /** The package of the two classes, below {@link IndexFormat#GENERATED_PACKAGE}. */
    static final String PACKAGE = IndexFormat.GENERATED_PACKAGE + ".prefetch";

    /** The directory of the two classes in the application layer. An application entry below it stands down. */
    static final String PACKAGE_PATH = PACKAGE.replace('.', '/') + "/";

    /** The task that runs Micronaut's provider. */
    static final String TASK_CLASS = PACKAGE + ".DefinitionPrefetch";

    /** The class the entry stub calls and Micronaut loads as a service. */
    static final String CONFIGURER_CLASS = PACKAGE + ".DefinitionPrefetchConfigurer";

    /** The logical name of {@value #TASK_CLASS} in the application layer. */
    static final String TASK_ENTRY = PACKAGE_PATH + "DefinitionPrefetch.class";

    /** The logical name of {@value #CONFIGURER_CLASS} in the application layer. */
    static final String CONFIGURER_ENTRY = PACKAGE_PATH + "DefinitionPrefetchConfigurer.class";

    /** The method of {@value #CONFIGURER_CLASS} the entry stub calls: {@code public static void start()}. */
    static final String START_METHOD = "start";

    /** The service file of the application layer that names the configurer. */
    static final String SERVICE_ENTRY = "META-INF/services/io.micronaut.context.ApplicationContextConfigurer";

    /** Where the merged bean definition reference entries are: one empty file per reference class. */
    static final String REFERENCES_PREFIX =
            IndexFormat.MICRONAUT_SERVICES_PREFIX + "io.micronaut.inject.BeanDefinitionReference/";

    /** Micronaut's provider, which micronaut-inject has had since 5.0 and the task calls. */
    static final String PROVIDER_ENTRY = "io/micronaut/context/DefaultBeanDefinitionsProvider.class";

    /** The builder interface whose {@code beanDefinitionsProvider} the configurer calls. */
    static final String BUILDER_ENTRY = "io/micronaut/context/ApplicationContextBuilder.class";

    /** The configuration interface whose {@code getBeanDefinitionsProvider} the configurer calls. */
    static final String CONFIGURATION_ENTRY = "io/micronaut/context/BeanContextConfiguration.class";

    /** The two classes, put on this library's own class path by its build. */
    private static final String RESOURCE = "/META-INF/micronaut-runner/prefetch.jar";

    private static final String PROVIDER_INTERFACE = "io/micronaut/context/BeanDefinitionsProvider";
    private static final String PROVIDER_TYPE = "io/micronaut/context/DefaultBeanDefinitionsProvider";
    private static final String PROVIDE_DESCRIPTOR = "(Ljava/lang/ClassLoader;)Ljava/util/List;";
    private static final String SET_PROVIDER_DESCRIPTOR =
            "(L" + PROVIDER_INTERFACE + ";)Lio/micronaut/context/ApplicationContextBuilder;";
    private static final String GET_PROVIDER_DESCRIPTOR = "()L" + PROVIDER_INTERFACE + ";";
    private static final String NOT_PACKAGED =
            "; Micronaut loads the bean definitions when the application context starts";

    private DefinitionPrefetchPackager() {
    }

    /**
     * Decides whether the prefetch is packaged, and with which entries.
     *
     * @param requested  whether {@link RunnerJarSpec#definitionPrefetch()} asks for it
     * @param mainClass  the main class the generated entry stub enters, or {@code null} when no stub was
     *                   generated
     * @param classPath  the application layer, then every dependency in class-path order
     * @param references the merged bean definition reference entries
     * @return the entries to put into the application layer and the line to log, or the warning to report
     * @throws IOException if a class of the class path or the application's service file cannot be read, or this
     *                     library carries no prefetch classes
     */
    static Outcome plan(boolean requested, String mainClass, List<LogbackPrecompiler.Layer> classPath,
                        References references) throws IOException {
        if (!requested) {
            return Outcome.standDown("the definitionPrefetch option is false");
        }
        if (mainClass == null) {
            return Outcome.standDown("no entry stub was generated to start it from");
        }
        String reason = micronautReason(first(classPath, PROVIDER_ENTRY), first(classPath, BUILDER_ENTRY),
                first(classPath, CONFIGURATION_ENTRY));
        if (reason != null) {
            return Outcome.standDown(reason);
        }
        if (references.count() == 0) {
            return Outcome.standDown("the class path registers no bean definition reference under "
                    + REFERENCES_PREFIX);
        }
        if (references.withContent() != null) {
            return Outcome.standDown("the bean definition reference entry '" + references.withContent()
                    + "' has content, which this version of Micronaut Runner does not interpret");
        }
        LogbackPrecompiler.Layer application = classPath.get(0);
        for (String name : application.names()) {
            if (name.startsWith(PACKAGE_PATH)) {
                return new Outcome(Map.of(), null, "The application output already carries '" + name
                        + "'; no bean definition prefetch was packaged" + NOT_PACKAGED);
            }
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        // A key the layer already has keeps its place in the archive: the stub, and an existing service file.
        entries.put(EntryStubGenerator.STUB_RESOURCE_NAME, EntryStubGenerator.generate(mainClass, true));
        entries.putAll(classes());
        entries.put(SERVICE_ENTRY, serviceFile(
                application.names().contains(SERVICE_ENTRY) ? application.read(SERVICE_ENTRY) : null));
        return new Outcome(entries, "Packaged the bean definition prefetch for " + references.count()
                + " bean definition references", null);
    }

    /**
     * Checks that the index of a finished archive knows the two classes the entry stub and the service file name.
     *
     * @param index  the index of the archive
     * @param output the archive the build was asked for, for the message
     * @throws IOException if a class is missing
     */
    static void verify(Index index, Path output) throws IOException {
        for (String name : List.of(TASK_CLASS, CONFIGURER_CLASS)) {
            if (index.findClass(name) == IndexFormat.NO_INDEX) {
                throw new IOException("The entry stub of " + output + " starts the bean definition prefetch but"
                        + " the index does not know " + name + "; the application would not start");
            }
        }
    }

    /**
     * Reads the two classes out of the jar this library carries.
     *
     * @return the task and the configurer, keyed by their logical names in the application layer, in that order
     * @throws IOException if the resource is missing or does not hold exactly those two classes
     */
    static Map<String, byte[]> classes() throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(TASK_ENTRY, null);
        classes.put(CONFIGURER_ENTRY, null);
        try (InputStream in = DefinitionPrefetchPackager.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("The packaging library carries no bean definition prefetch at " + RESOURCE
                        + "; it was built incorrectly");
            }
            ZipInputStream zip = new ZipInputStream(in);
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                if (!classes.containsKey(name) || classes.get(name) != null) {
                    throw new IOException("The bean definition prefetch at " + RESOURCE + " carries the unexpected"
                            + " class '" + name + "'; the packaging library was built incorrectly");
                }
                classes.put(name, zip.readAllBytes());
            }
        }
        for (Map.Entry<String, byte[]> item : classes.entrySet()) {
            if (item.getValue() == null) {
                throw new IOException("The bean definition prefetch at " + RESOURCE + " carries no '"
                        + item.getKey() + "'; the packaging library was built incorrectly");
            }
        }
        return classes;
    }

    /**
     * Decides whether the application's Micronaut has what the two classes link against, by parsing three of its
     * class files.
     *
     * @param provider      the class file of {@code DefaultBeanDefinitionsProvider}, or {@code null} when the class
     *                      path has none
     * @param builder       the class file of {@code ApplicationContextBuilder}, or {@code null}
     * @param configuration the class file of {@code BeanContextConfiguration}, or {@code null}
     * @return {@code null} when the prefetch can be packaged, otherwise why not, phrased to be read after "no
     *         bean definition prefetch was packaged because ..."
     */
    static String micronautReason(byte[] provider, byte[] builder, byte[] configuration) {
        String found = "the class path has no micronaut-inject 5.0 or later: ";
        if (provider == null) {
            return found + "no " + PROVIDER_ENTRY;
        }
        try {
            ClassModel model = ClassFile.of().parse(provider);
            if (!PROVIDER_TYPE.equals(model.thisClass().asInternalName())) {
                return found + PROVIDER_ENTRY + " declares itself to be " + model.thisClass().asInternalName();
            }
            if (!model.flags().has(AccessFlag.PUBLIC) || model.flags().has(AccessFlag.INTERFACE)
                    || model.flags().has(AccessFlag.ABSTRACT)) {
                return found + PROVIDER_TYPE + " is not a public class";
            }
            boolean implemented = false;
            for (ClassEntry implementedInterface : model.interfaces()) {
                implemented |= PROVIDER_INTERFACE.equals(implementedInterface.asInternalName());
            }
            if (!implemented) {
                return found + PROVIDER_TYPE + " does not implement " + PROVIDER_INTERFACE;
            }
            if (!declares(model, "<init>", "()V", false)) {
                return found + PROVIDER_TYPE + " has no public no-argument constructor";
            }
            if (!declares(model, "provide", PROVIDE_DESCRIPTOR, false)) {
                return found + PROVIDER_TYPE + " has no public provide(ClassLoader)";
            }
            if (builder == null || !declares(ClassFile.of().parse(builder), "beanDefinitionsProvider",
                    SET_PROVIDER_DESCRIPTOR, true)) {
                return found + "no ApplicationContextBuilder.beanDefinitionsProvider(BeanDefinitionsProvider)";
            }
            if (configuration == null || !declares(ClassFile.of().parse(configuration),
                    "getBeanDefinitionsProvider", GET_PROVIDER_DESCRIPTOR, true)) {
                return found + "no BeanContextConfiguration.getBeanDefinitionsProvider()";
            }
        } catch (IllegalArgumentException e) {
            return found + "a Micronaut class file cannot be parsed: " + e.getMessage();
        }
        return null;
    }

    /**
     * The service file with the configurer's line added after whatever the application already registers, such as
     * the configurer Micronaut AOT generates.
     *
     * @param existing the application's own service file, or {@code null} when it has none
     * @return the content to package
     */
    static byte[] serviceFile(byte[] existing) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        if (existing != null && existing.length > 0) {
            content.writeBytes(existing);
            if (existing[existing.length - 1] != '\n') {
                content.write('\n');
            }
        }
        content.writeBytes((CONFIGURER_CLASS + "\n").getBytes(StandardCharsets.UTF_8));
        return content.toByteArray();
    }

    /** Reads the first copy of an entry on the class path, or {@code null} when no layer has it. */
    private static byte[] first(List<LogbackPrecompiler.Layer> classPath, String name) throws IOException {
        for (LogbackPrecompiler.Layer layer : classPath) {
            if (layer.names().contains(name)) {
                return layer.read(name);
            }
        }
        return null;
    }

    /** Whether a class declares a public, non-static method, which an interface may leave abstract. */
    private static boolean declares(ClassModel model, String name, String descriptor, boolean mayBeAbstract) {
        for (MethodModel method : model.methods()) {
            if (name.equals(method.methodName().stringValue())
                    && descriptor.equals(method.methodType().stringValue())) {
                return method.flags().has(AccessFlag.PUBLIC) && !method.flags().has(AccessFlag.STATIC)
                        && (mayBeAbstract || !method.flags().has(AccessFlag.ABSTRACT));
            }
        }
        return false;
    }

    /**
     * The merged {@value #REFERENCES_PREFIX} entries of a class path.
     *
     * @param count       how many there are
     * @param withContent the first one that is not empty, or {@code null} when every one is empty
     */
    record References(int count, String withContent) {

        /** A class path without a bean definition reference. */
        static final References NONE = new References(0, null);

        /**
         * Counts the references among the merged Micronaut service entries.
         *
         * @param mergedNames the names of the merged entries, in a stable order
         * @param size        the size of the content the merge kept for a name
         * @return the references
         */
        static References of(Collection<String> mergedNames, ToLongFunction<String> size) {
            int count = 0;
            String withContent = null;
            for (String name : mergedNames) {
                if (name.startsWith(REFERENCES_PREFIX)) {
                    count++;
                    if (withContent == null && size.applyAsLong(name) != 0) {
                        withContent = name;
                    }
                }
            }
            return new References(count, withContent);
        }
    }

    /**
     * What the packager decided.
     *
     * @param entries the entries to put into the application layer, in order; empty when nothing is packaged
     * @param message the informational line to log, or {@code null} when {@code warning} is set
     * @param warning the warning to report, or {@code null}
     */
    record Outcome(Map<String, byte[]> entries, String message, String warning) {

        static Outcome standDown(String reason) {
            return new Outcome(Map.of(), "No bean definition prefetch was packaged because " + reason
                    + NOT_PACKAGED, null);
        }
    }
}
