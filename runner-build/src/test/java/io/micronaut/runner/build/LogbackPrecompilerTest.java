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

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.core.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.ILoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.net.URISyntaxException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The differential gate of {@link LogbackPrecompiler}: every accepted configuration builds exactly the context
 * Joran builds and writes exactly what Joran's writes, every configuration outside the subset generates nothing and
 * says why, and the generated configurator follows the runtime rules.
 *
 * <p>The corpus under {@code logback-corpus/} and {@link LogbackDifferential} use no Micronaut Runner type, so both
 * can test a shared engine's output as well.</p>
 */
class LogbackPrecompilerTest {

    private static final String CORPUS = "/logback-corpus/";

    private static final String LOG_FILE_TOKEN = "@LOG_FILE@";

    private static final List<String> RUNTIME_PROPERTIES = List.of("logger.config", "logback.configurationFile",
            "micronaut.runner.logback.precompiled");

    @TempDir
    Path temporary;

    @AfterEach
    void clearTheRuntimeProperties() {
        RUNTIME_PROPERTIES.forEach(System::clearProperty);
    }

    // ------------------------------------------------------------------ accept corpus

    @ParameterizedTest
    @ValueSource(strings = {"benchmark-large.xml", "launch-default.xml", "rich.xml", "file-appender.xml"})
    void anAcceptedConfigurationBehavesExactlyLikeJoran(String file) throws Exception {
        Path logFile = temporary.resolve("appender.log");
        Path xml = corpus("accept/" + file, logFile);
        Compilation compilation = compile(Files.readAllBytes(xml));

        assertEquals(List.of(), compilation.warnings());
        assertEquals(1, compilation.info().size(), compilation.info()::toString);
        assertTrue(compilation.info().get(0).startsWith("Precompiled logback.xml (application layer) into "
                + LogbackPrecompiler.CONFIGURATOR_CLASS + ": "), compilation.info()::toString);
        Path classes = compilation.write(temporary.resolve("generated"));

        // The tree, the appenders, their encoders and every warning, with both contexts alive.
        LoggerContext joran = LogbackDifferential.joran(xml);
        LoggerContext generated = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            assertEquals(Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY,
                    LogbackDifferential.configure(loader, generated));
        }
        String expected = LogbackDifferential.describe(joran, generated);
        assertEquals(expected, LogbackDifferential.describe(generated, joran));
        assertTrue(expected.contains("PatternLayoutEncoder:pattern="), expected);
        joran.stop();
        generated.stop();
        Files.deleteIfExists(logFile);

        // What every appender writes, one context at a time: a file appender of each writes the same file.
        String joranOutput = LogbackDifferential.emit(LogbackDifferential.joran(xml))
                + LogbackDifferential.read(logFile);
        Files.deleteIfExists(logFile);
        LoggerContext again = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, again);
        }
        String generatedOutput = LogbackDifferential.emit(again) + LogbackDifferential.read(logFile);
        assertEquals(joranOutput, generatedOutput);
        assertTrue(joranOutput.length() > "--- stderr ---\n(no file)".length(), joranOutput);
    }

    @Test
    void theHarnessCatchesAConfiguratorThatDiffersFromJoran() throws Exception {
        Path xml = corpus("accept/rich.xml", null);
        // A negative control: the same description with the root logger's level changed.
        LogbackPrecompiler.descriptionHook = description -> {
            Map<String, Object> changed = new LinkedHashMap<>(description);
            List<Object> operations = new ArrayList<>();
            for (Object operation : (List<?>) description.get("operations")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) operation);
                if ("ROOT".equals(copy.get("name"))) {
                    copy.put("level", "ERROR");
                }
                operations.add(copy);
            }
            changed.put("operations", operations);
            return changed;
        };
        Path classes;
        try {
            classes = compile(Files.readAllBytes(xml)).write(temporary.resolve("generated"));
        } finally {
            LogbackPrecompiler.descriptionHook = java.util.function.UnaryOperator.identity();
        }
        LoggerContext joran = LogbackDifferential.joran(xml);
        LoggerContext generated = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, generated);
        }

        assertNotEquals(LogbackDifferential.describe(joran, generated), LogbackDifferential.describe(generated, joran));
        assertNotEquals(LogbackDifferential.emit(joran), LogbackDifferential.emit(generated));
    }

    @Test
    void theFileAppenderCorpusReallyWritesItsFile() throws Exception {
        Path logFile = temporary.resolve("written.log");
        Path xml = corpus("accept/file-appender.xml", logFile);
        Path classes = compile(Files.readAllBytes(xml)).write(temporary.resolve("generated"));
        LoggerContext context = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, context);
        }

        LogbackDifferential.emit(context);

        assertTrue(LogbackDifferential.read(logFile).contains("héllo wörld"), LogbackDifferential.read(logFile));
    }

    // ------------------------------------------------------------------ reject corpus

    @ParameterizedTest
    @CsvSource({
        "property.xml, <property>",
        "scan.xml, scan=\"true\"",
        "include.xml, <include>",
        "custom-appender.xml, com.example.logging.CustomAppender",
        "conversion-rule.xml, <conversionRule>",
        "unknown-level.xml, VERBOSE",
        "debug.xml, debug=\"true\"",
        "rolling-policy.xml, <rollingPolicy>"
    })
    void aConfigurationOutsideTheSubsetGeneratesNothingAndNamesTheElement(String file, String element)
            throws Exception {
        Compilation compilation = compile(Files.readAllBytes(corpus("reject/" + file, null)));

        assertEquals(Map.of(), compilation.entries());
        assertEquals(List.of(), compilation.warnings());
        assertEquals(1, compilation.info().size(), compilation.info()::toString);
        String line = compilation.info().get(0);
        assertTrue(line.startsWith("No Logback configuration was precompiled because logback.xml (application"
                + " layer) is outside what the precompiler can reproduce exactly: "), line);
        assertTrue(line.contains(element), line);
        assertTrue(line.endsWith("; Logback will configure itself with Joran at startup"), line);
    }

    // ------------------------------------------------------------------ the classes

    @Test
    void generatingTwiceGivesTheSameBytes() throws Exception {
        byte[] xml = Files.readAllBytes(corpus("accept/rich.xml", null));

        Map<String, byte[]> first = compile(xml).entries();
        Map<String, byte[]> second = compile(xml).entries();

        assertEquals(List.copyOf(first.keySet()), List.copyOf(second.keySet()));
        for (String name : first.keySet()) {
            assertArrayEquals(first.get(name), second.get(name), name);
        }
    }

    @Test
    void everyClassIsJava25VerifiesAndLinksNothingDynamically() throws Exception {
        Map<String, byte[]> entries = compile(Files.readAllBytes(corpus("accept/rich.xml", null))).entries();

        assertEquals(List.of(LogbackPrecompiler.CONFIGURATOR_ENTRY, LogbackPrecompiler.FALLBACK_ENTRY,
                LogbackPrecompiler.SERVICE_ENTRY), List.copyOf(entries.keySet()));
        assertEquals(LogbackPrecompiler.CONFIGURATOR_CLASS + "\n",
                new String(entries.get(LogbackPrecompiler.SERVICE_ENTRY), StandardCharsets.UTF_8));
        for (String name : List.of(LogbackPrecompiler.CONFIGURATOR_ENTRY, LogbackPrecompiler.FALLBACK_ENTRY)) {
            byte[] bytes = entries.get(name);
            ClassModel model = ClassFile.of().parse(bytes);
            assertEquals(69, model.majorVersion(), name);
            assertEquals(0, model.minorVersion(), name);
            assertEquals(List.of(), ClassFile.of().verify(bytes), name);
            assertTrue(model.findAttribute(Attributes.bootstrapMethods()).isEmpty(), name + " uses invokedynamic");
        }
    }

    @Test
    void theFallbackIsCopiedVerbatimFromTheFrontEnd() throws Exception {
        Map<String, byte[]> entries = compile(Files.readAllBytes(corpus("accept/benchmark-large.xml", null)))
                .entries();
        byte[] carried;
        try (InputStream in = LogbackPrecompiler.class.getResourceAsStream(
                "/META-INF/micronaut-runner/logback-frontend.jar")) {
            Path jar = temporary.resolve("frontend.jar");
            Files.copy(in, jar);
            try (ZipReader reader = ZipReader.open(jar)) {
                carried = reader.read(reader.entry(LogbackPrecompiler.FALLBACK_ENTRY).orElseThrow());
            }
        }

        assertArrayEquals(carried, entries.get(LogbackPrecompiler.FALLBACK_ENTRY));
    }

    // ------------------------------------------------------------------ runtime rules

    @Test
    void aSecondCallAfterAResetWithLoggerConfigAppliesThatFile() throws Exception {
        Path classes = compile(Files.readAllBytes(corpus("accept/benchmark-large.xml", null)))
                .write(temporary.resolve("generated"));
        Path location = corpus("accept/rich.xml", null);
        LoggerContext context = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, context);
            context.reset();
            System.setProperty("logger.config", location.toString());
            assertEquals(Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY,
                    LogbackDifferential.configure(loader, context));
        }

        assertSameTree(LogbackDifferential.joran(location), context);
    }

    @Test
    void loggerConfigIsIgnoredOnTheFirstCall() throws Exception {
        Path xml = corpus("accept/benchmark-large.xml", null);
        Path classes = compile(Files.readAllBytes(xml)).write(temporary.resolve("generated"));
        System.setProperty("logger.config", corpus("accept/rich.xml", null).toString());
        LoggerContext context = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, context);
        }

        assertSameTree(LogbackDifferential.joran(xml), context);
    }

    @Test
    void logbackConfigurationFileHandsTheFirstCallToJoran() throws Exception {
        Path classes = compile(Files.readAllBytes(corpus("accept/benchmark-large.xml", null)))
                .write(temporary.resolve("generated"));
        Path location = corpus("accept/launch-default.xml", null);
        System.setProperty("logback.configurationFile", location.toString());
        LoggerContext context = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            assertEquals(Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY,
                    LogbackDifferential.configure(loader, context));
        }

        assertSameTree(LogbackDifferential.joran(location), context);
    }

    @Test
    void theRuntimeOptOutHandsOverToLogbacksDefaultLookup() throws Exception {
        Path classes = compile(Files.readAllBytes(corpus("accept/benchmark-large.xml", null)))
                .write(temporary.resolve("generated"));
        System.setProperty("micronaut.runner.logback.precompiled", "false");
        LoggerContext context = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, context);
        }

        // Logback's default lookup finds this test class path's logback-test.xml.
        assertSameTree(LogbackDifferential.joran(testResource("/logback-test.xml")), context);
        assertTrue(LogbackDifferential.describe(context).contains("RUNNER-BUILD-TESTS"));
    }

    @Test
    void theOptOutWithLoggerConfigEndsInThatFileAfterARefresh() throws Exception {
        Path classes = compile(Files.readAllBytes(corpus("accept/benchmark-large.xml", null)))
                .write(temporary.resolve("generated"));
        Path location = corpus("accept/rich.xml", null);
        System.setProperty("micronaut.runner.logback.precompiled", "false");
        System.setProperty("logger.config", location.toString());
        LoggerContext context = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, context);
            context.reset();
            LogbackDifferential.configure(loader, context);
        }

        assertSameTree(LogbackDifferential.joran(location), context);
    }

    @Test
    void aSecondCallWithNothingSetBuildsTheSameTreeAndOutput() throws Exception {
        Path xml = corpus("accept/rich.xml", null);
        Path classes = compile(Files.readAllBytes(xml)).write(temporary.resolve("generated"));
        LoggerContext context = new LoggerContext();
        String first;
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, context);
            first = LogbackDifferential.describe(context);
            context.reset();
            assertEquals(Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY,
                    LogbackDifferential.configure(loader, context));
        }

        String second = LogbackDifferential.describe(context);
        // reset() drops the statuses the first call recorded, so only the tree itself is compared.
        assertEquals(tree(first), tree(second));
        assertEquals(LogbackDifferential.emit(LogbackDifferential.joran(xml)), LogbackDifferential.emit(context));
    }

    @Test
    void theTestedRangeIsNumericAndHalfOpen() {
        assertTrue(LogbackPrecompiler.supported("1.5.37"));
        assertTrue(LogbackPrecompiler.supported("1.5.40"));
        assertFalse(LogbackPrecompiler.supported("1.5.36"));
        assertFalse(LogbackPrecompiler.supported("1.6.0"));
        assertFalse(LogbackPrecompiler.supported("1.6"));
        assertFalse(LogbackPrecompiler.supported("1.4.14"));
        assertFalse(LogbackPrecompiler.supported("1.5.38-SNAPSHOT"));
    }

    // ------------------------------------------------------------------ plumbing

    private static void assertSameTree(LoggerContext expected, LoggerContext actual) {
        assertEquals(tree(LogbackDifferential.describe(expected, actual)),
                tree(LogbackDifferential.describe(actual, expected)));
    }

    /** A description without its status lines, which differ between Joran and a fallback that also ran. */
    private static String tree(String description) {
        StringBuilder tree = new StringBuilder();
        for (String line : description.split("\n")) {
            if (!line.startsWith("status ")) {
                tree.append(line).append('\n');
            }
        }
        return tree.toString();
    }

    /**
     * Copies a corpus file into the temporary directory, replacing the log file token.
     *
     * @param name    the file, relative to the corpus
     * @param logFile what the token becomes, or {@code null} when the file has none
     * @return the copy
     */
    private Path corpus(String name, Path logFile) throws IOException {
        String xml;
        try (InputStream in = LogbackPrecompilerTest.class.getResourceAsStream(CORPUS + name)) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (logFile != null) {
            xml = xml.replace(LOG_FILE_TOKEN, logFile.toString());
        }
        Path copy = temporary.resolve("corpus").resolve(name);
        Files.createDirectories(copy.getParent());
        Files.writeString(copy, xml, StandardCharsets.UTF_8);
        return copy;
    }

    private static Path testResource(String name) throws URISyntaxException {
        return Path.of(LogbackPrecompilerTest.class.getResource(name).toURI());
    }

    private Compilation compile(byte[] logbackXml) throws IOException {
        Map<String, byte[]> application = new LinkedHashMap<>();
        application.put("com/example/Application.class", new byte[] {1});
        application.put("logback.xml", logbackXml);
        List<LogbackPrecompiler.Layer> layers = new ArrayList<>();
        layers.add(LogbackPrecompiler.Layer.application(application.keySet(), application::get));
        for (Class<?> type : List.of(LoggerContext.class, Context.class, ILoggerFactory.class)) {
            layers.add(layer(jarOf(type)));
        }
        List<String> info = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        BuildLogger logger = new BuildLogger() {
            @Override
            public void info(String message) {
                info.add(message);
            }

            @Override
            public void warn(String message) {
                warnings.add(message);
            }
        };
        Path work = Files.createTempDirectory(temporary, "work");
        Map<String, byte[]> entries = LogbackPrecompiler.precompile(true, layers, application.keySet(), work,
                logger, warnings::add);
        return new Compilation(entries, info, warnings);
    }

    static LogbackPrecompiler.Layer layer(Path jar) throws IOException {
        try (ZipReader reader = ZipReader.open(jar)) {
            Manifest manifest = reader.manifest().orElse(null);
            return LogbackPrecompiler.Layer.of(Dependency.of(jar), jar, manifest, reader.entries());
        }
    }

    static Path jarOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What one precompilation produced and reported.
     *
     * @param entries  the generated entries
     * @param info     the informational lines
     * @param warnings the warnings
     */
    private record Compilation(Map<String, byte[]> entries, List<String> info, List<String> warnings) {

        Path write(Path directory) throws IOException {
            assertFalse(entries.isEmpty(), () -> "nothing was generated: " + info + warnings);
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                Path file = directory.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue());
            }
            return directory;
        }
    }
}
