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

import io.micronaut.core.annotation.Experimental;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a JDK AOT cache is tied to, as one launch of a {@code java} executable reports it: the exact VM build,
 * the operating system and the CPU architecture, and whether cache creation takes
 * {@code -XX:+AOTCompatibleOopCompression}.
 *
 * <p>The probe, never the version number, decides the creation flag: JDK 27 has it, and JDK 25.0.4.1 does not.
 * Without it a JDK 27 cache fails strict launches intermittently with {@code incompatible
 * CompressedOops::base()}, depending on where ASLR places the heap.</p>
 *
 * @param vmVersion                the {@code java.vm.version} property, the exact VM build
 * @param runtimeVersion           the {@code java.runtime.version} property
 * @param osName                   the {@code os.name} property
 * @param osArch                   the {@code os.arch} property
 * @param compatibleOopCompression whether the flag list names {@code AOTCompatibleOopCompression}
 * @since 1.0
 */
@Experimental
public record JdkProbe(String vmVersion,
                       String runtimeVersion,
                       String osName,
                       String osArch,
                       boolean compatibleOopCompression) {

    /** The creation flags of a JDK that has {@code AOTCompatibleOopCompression}. */
    public static final List<String> COMPATIBLE_OOP_COMPRESSION = List.of(
            "-XX:+UnlockDiagnosticVMOptions", "-XX:+AOTCompatibleOopCompression");

    /** How long the probe launch may take. */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /**
     * Checks that every component is there.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public JdkProbe {
        Objects.requireNonNull(vmVersion, "vmVersion");
        Objects.requireNonNull(runtimeVersion, "runtimeVersion");
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(osArch, "osArch");
    }

    /**
     * Launches {@code java} once, with {@link #command(Path)}, and reads what it prints.
     *
     * @param java the {@code java} executable
     * @return what the JDK reported
     * @throws IOException          if the launch fails, does not exit within a minute, or prints no property
     *                              the probe needs
     * @throws InterruptedException if the thread is interrupted; the launch has been killed by then
     */
    public static JdkProbe probe(Path java) throws IOException, InterruptedException {
        List<String> command = command(java);
        Forks.Result result = Forks.capture(command, null, TIMEOUT);
        if (result.exitStatus() != 0) {
            throw new IOException("The JDK probe " + String.join(" ", command) + " exited with status "
                    + result.exitStatus() + Forks.tail(result.output()));
        }
        return parse(result.output());
    }

    /**
     * The probe's command: the final flag values, the system properties and the version, in one launch.
     *
     * @param java the {@code java} executable
     * @return the command
     */
    public static List<String> command(Path java) {
        return List.of(java.toString(), "-XX:+UnlockDiagnosticVMOptions", "-XX:+PrintFlagsFinal",
                "-XshowSettings:properties", "-version");
    }

    /**
     * Reads the output of {@link #command(Path)}, standard error merged into standard output.
     *
     * @param output the output
     * @return what it reports
     * @throws IOException if a property the probe needs is missing
     */
    public static JdkProbe parse(String output) throws IOException {
        Map<String, String> properties = new HashMap<>();
        boolean flag = false;
        for (String line : output.lines().toList()) {
            String trimmed = line.strip();
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length > 1 && tokens[0].equals("bool") && tokens[1].equals("AOTCompatibleOopCompression")) {
                flag = true;
                continue;
            }
            // -XshowSettings:properties prints "    name = value"; a continuation line of a list has no " = ".
            int equals = trimmed.indexOf(" = ");
            if (equals > 0 && line.startsWith("    ") && !line.startsWith("        ")) {
                properties.putIfAbsent(trimmed.substring(0, equals), trimmed.substring(equals + 3).strip());
            }
        }
        return new JdkProbe(required(properties, "java.vm.version", output),
                required(properties, "java.runtime.version", output),
                required(properties, "os.name", output),
                required(properties, "os.arch", output),
                flag);
    }

    /**
     * The flags cache creation needs on this JDK.
     *
     * @return {@link #COMPATIBLE_OOP_COMPRESSION} when the JDK has the flag, otherwise nothing
     */
    public List<String> creationFlags() {
        return compatibleOopCompression ? COMPATIBLE_OOP_COMPRESSION : List.of();
    }

    private static String required(Map<String, String> properties, String name, String output) throws IOException {
        String value = properties.get(name);
        if (value == null || value.isEmpty()) {
            throw new IOException("The JDK probe printed no " + name + Forks.tail(output));
        }
        return value;
    }
}
