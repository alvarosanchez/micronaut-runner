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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link JdkProbe#parse(String)} against the output of the probe command on Homebrew's JDK 25.0.4.1 and 27. */
class JdkProbeTest {

    /** Excerpts of {@code java -XX:+UnlockDiagnosticVMOptions -XX:+PrintFlagsFinal -XshowSettings:properties -version}. */
    private static final String JDK_25 = """
            [Global flags]
                ccstr AOTCache                                 =                                           {product} {default}
                ccstr AOTMode                                  =                                           {product} {default}
                 bool UseCompactObjectHeaders                  = false                          {product lp64_product} {default}
            Property settings:
                file.encoding = UTF-8
                java.class.path =\s
                java.home = /opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
                java.library.path = /Users/alvaro/Library/Java/Extensions
                    /Library/Java/Extensions
                    /System/Library/Java/Extensions
                java.runtime.name = OpenJDK Runtime Environment
                java.runtime.version = 25.0.4.1
                java.vm.name = OpenJDK 64-Bit Server VM
                java.vm.version = 25.0.4.1
                os.arch = aarch64
                os.name = Mac OS X
                os.version = 26.6.2

            openjdk version "25.0.4.1" 2026-08-18
            OpenJDK Runtime Environment Homebrew (build 25.0.4.1)
            OpenJDK 64-Bit Server VM Homebrew (build 25.0.4.1, mixed mode, sharing)
            """;

    private static final String JDK_27 = """
            [Global flags]
                ccstr AOTCache                                 =                                           {product} {default}
                 bool AOTCompatibleOopCompression              = false                          {diagnostic lp64_product} {ergonomic}
                 bool UseCompactObjectHeaders                  = true                           {product lp64_product} {default}
            Property settings:
                java.class.path =\s
                java.runtime.version = 27
                java.vm.version = 27
                os.arch = aarch64
                os.name = Mac OS X

            openjdk version "27" 2026-09-15
            OpenJDK Runtime Environment Homebrew (build 27)
            OpenJDK 64-Bit Server VM Homebrew (build 27, mixed mode, sharing)
            """;

    @Test
    void jdk25HasNoCompatibleOopCompression() throws IOException {
        JdkProbe probe = JdkProbe.parse(JDK_25);

        assertEquals("25.0.4.1", probe.vmVersion());
        assertEquals("25.0.4.1", probe.runtimeVersion());
        assertEquals("Mac OS X", probe.osName());
        assertEquals("aarch64", probe.osArch());
        assertFalse(probe.compatibleOopCompression());
        assertEquals(List.of(), probe.creationFlags());
    }

    @Test
    void jdk27CreatesWithCompatibleOopCompression() throws IOException {
        JdkProbe probe = JdkProbe.parse(JDK_27);

        assertEquals("27", probe.vmVersion());
        assertEquals("27", probe.runtimeVersion());
        assertEquals("Mac OS X", probe.osName());
        assertEquals("aarch64", probe.osArch());
        assertTrue(probe.compatibleOopCompression());
        assertEquals(List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:+AOTCompatibleOopCompression"),
                probe.creationFlags());
    }

    @Test
    void anOutputWithoutAPropertyFailsNamingIt() {
        IOException missing = assertThrows(IOException.class,
                () -> JdkProbe.parse(JDK_27.replace("    os.arch = aarch64\n", "")));
        assertTrue(missing.getMessage().contains("os.arch"), missing.getMessage());
    }

    @Test
    void theCommandPrintsFlagsPropertiesAndVersionInOneLaunch() {
        assertEquals(List.of(Path.of("java").toString(), "-XX:+UnlockDiagnosticVMOptions", "-XX:+PrintFlagsFinal",
                "-XshowSettings:properties", "-version"), JdkProbe.command(Path.of("java")));
    }
}
