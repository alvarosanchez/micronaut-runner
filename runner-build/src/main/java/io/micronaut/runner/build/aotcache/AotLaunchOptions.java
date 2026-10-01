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
package io.micronaut.runner.build.aotcache;

import io.micronaut.runner.build.training.TrainingSettings;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The files that go with a trained cache: the launch argfile, which carries the options production launches
 * with, and the identity file, which records what the cache is tied to. Both live in the directory the cache
 * was trained in, next to the JAR.
 *
 * <p>From that directory the application launches as {@code java @app.jvmopts -jar <jar>}: the argfile names
 * the cache by a relative path.</p>
 *
 * <p>Not for users: the class and its two file names are public only because {@code AotCacheOutput}, in another
 * package of this library, deletes those files when a build fails after training. Everything else is
 * package-private.</p>
 *
 * @since 1.0
 */
public final class AotLaunchOptions {

    /** The cache, relative to the output directory. */
    public static final String CACHE_FILE = "app.aot";

    /** The launch argfile, relative to the output directory. */
    public static final String ARGFILE = "app.jvmopts";

    /** The identity file, relative to the output directory. */
    static final String IDENTITY_FILE = "app.aot.properties";

    /** The identity entry that holds the exact VM build. */
    static final String VM_VERSION = "java.vm.version";

    /** The identity entry that holds the runtime version. */
    static final String RUNTIME_VERSION = "java.runtime.version";

    /** The identity entry that holds the operating system. */
    static final String OS_NAME = "os.name";

    /** The identity entry that holds the CPU architecture. */
    static final String OS_ARCH = "os.arch";

    /** The identity entry that holds the creation flags, as argfile tokens separated by spaces. */
    static final String CREATION_FLAGS = "creationFlags";

    /** The identity entry that holds {@link AotCacheSettings#jvmArgs()}, as argfile tokens separated by spaces. */
    static final String JVM_ARGS = "jvmArgs";

    /** The entries every identity file has; the others are the caller's labels. */
    static final List<String> IDENTITY_ENTRIES = List.of(VM_VERSION, RUNTIME_VERSION, OS_NAME, OS_ARCH,
            CREATION_FLAGS, JVM_ARGS);

    private AotLaunchOptions() {
    }

    /**
     * The options of a production launch, one per element: {@code -XX:AOTCache=app.aot}, then
     * {@code -XX:AOTMode=on} when the settings are strict, then every {@link AotCacheSettings#jvmArgs()} entry in
     * order.
     *
     * @param settings the cache settings
     * @return the options
     */
    static List<String> options(AotCacheSettings settings) {
        List<String> options = new ArrayList<>(settings.jvmArgs().size() + 2);
        options.add("-XX:AOTCache=" + CACHE_FILE);
        if (settings.strict()) {
            options.add("-XX:AOTMode=on");
        }
        options.addAll(settings.jvmArgs());
        return options;
    }

    /**
     * The content of the argfile: {@link #options(AotCacheSettings)}, one per line, each quoted the way the
     * {@code java} launcher reads an argfile when it holds whitespace, a quote, a backslash or {@code #}.
     *
     * @param settings the cache settings
     * @return the content, with {@code \n} line endings
     */
    static String argfile(AotCacheSettings settings) {
        StringBuilder content = new StringBuilder();
        for (String option : options(settings)) {
            content.append(quote(option)).append('\n');
        }
        return content.toString();
    }

    /**
     * The training settings of every launch of a cache build: the user's, with their {@code jvmArgs} replaced
     * by {@link AotCacheSettings#jvmArgs()}, so that one list reaches the recording, the creation, the probes,
     * the smoke launch and the argfile. {@link TrainingSettings#jvmArgs()} belong to the startup-profile
     * recording only.
     *
     * @param settings the cache settings
     * @param training the user's training settings
     * @return the copy
     */
    static TrainingSettings trainingSettings(AotCacheSettings settings, TrainingSettings training) {
        return training.toBuilder().jvmArgs(settings.jvmArgs()).build();
    }

    /**
     * The identity of a cache, in the order the file lists it.
     *
     * @param jdk           the JDK that built it
     * @param creationFlags the flags it was created with
     * @param jvmArgs       the JVM arguments of its launches
     * @param labels        what the caller adds, such as the {@code target}
     * @return the entries
     */
    static Map<String, String> identity(JdkProbe jdk, List<String> creationFlags, List<String> jvmArgs,
                                        Map<String, String> labels) {
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put(VM_VERSION, jdk.vmVersion());
        identity.put(RUNTIME_VERSION, jdk.runtimeVersion());
        identity.put(OS_NAME, jdk.osName());
        identity.put(OS_ARCH, jdk.osArch());
        identity.put(CREATION_FLAGS, tokens(creationFlags));
        identity.put(JVM_ARGS, tokens(jvmArgs));
        labels.forEach(identity::putIfAbsent);
        return identity;
    }

    /**
     * Writes an identity file in the {@link Properties} format, UTF-8, without the timestamp comment
     * {@link Properties#store} adds.
     *
     * @param file     the file
     * @param identity the entries, in order
     * @throws IOException if it cannot be written
     */
    static void writeIdentity(Path file, Map<String, String> identity) throws IOException {
        StringBuilder content = new StringBuilder("# The JDK AOT cache app.aot is valid only for this JDK build,"
                + " operating system and architecture.\n");
        identity.forEach((name, value) -> content.append(escape(name, true)).append('=')
                .append(escape(value, false)).append('\n'));
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * Reads an identity file.
     *
     * @param file the file
     * @return the entries
     * @throws IOException if it cannot be read
     */
    static Map<String, String> readIdentity(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Map<String, String> identity = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            identity.put(name, properties.getProperty(name));
        }
        return identity;
    }

    /**
     * Quotes one argfile token: as is when it needs nothing, otherwise in double quotes with backslashes and
     * quotes escaped.
     *
     * @param option the option
     * @return the token
     */
    static String quote(String option) {
        boolean plain = !option.isEmpty();
        for (int i = 0; i < option.length() && plain; i++) {
            char c = option.charAt(i);
            plain = !Character.isWhitespace(c) && c != '"' && c != '\'' && c != '\\' && c != '#';
        }
        if (plain) {
            return option;
        }
        StringBuilder quoted = new StringBuilder(option.length() + 2).append('"');
        for (int i = 0; i < option.length(); i++) {
            char c = option.charAt(i);
            switch (c) {
                case '\\' -> quoted.append("\\\\");
                case '"' -> quoted.append("\\\"");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                case '\f' -> quoted.append("\\f");
                default -> quoted.append(c);
            }
        }
        return quoted.append('"').toString();
    }

    private static String tokens(List<String> options) {
        StringBuilder line = new StringBuilder();
        for (String option : options) {
            line.append(line.isEmpty() ? "" : " ").append(quote(option));
        }
        return line.toString();
    }

    private static String escape(String text, boolean key) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\f' -> escaped.append("\\f");
                case '=', ':', '#', '!' -> escaped.append('\\').append(c);
                case ' ' -> escaped.append(key || i == 0 ? "\\ " : " ");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
