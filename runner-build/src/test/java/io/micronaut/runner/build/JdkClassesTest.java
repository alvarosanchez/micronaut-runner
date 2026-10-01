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

import java.lang.classfile.ClassFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdkClasses} against the JDK that runs the tests: which packages are the JDK's at run time, and what a
 * generated class may call in them.
 */
class JdkClassesTest {

    @Test
    void thePackagesOfBootAndPlatformModulesAreTheJdks() {
        assertTrue(JdkClasses.owns("java/lang"));
        assertTrue(JdkClasses.owns("java/util/function"));
        assertTrue(JdkClasses.owns("java/sql"), "a platform loader module");
        assertTrue(JdkClasses.owns("jdk/internal/misc"), "an internal package is still asked of the JDK first");
        assertFalse(JdkClasses.owns("com/example"));
        assertFalse(JdkClasses.owns(""));
        assertFalse(JdkClasses.owns("com/sun/tools/javac/main"), "an application loader module is not");
    }

    @Test
    void aPublicClassOfAnExportedPackageAnswersWithItsDeclaredMethods() {
        JdkClasses.JdkClass string = JdkClasses.find("java/lang/String");

        assertNotNull(string);
        assertTrue(string.exported());
        assertTrue((string.flags() & ClassFile.ACC_PUBLIC) != 0);
        assertEquals("java/lang/Object", string.superName());
        assertTrue(string.interfaces().contains("java/io/Serializable"));
        Integer valueOf = string.method("valueOf", "(Ljava/lang/Object;)Ljava/lang/String;");
        assertNotNull(valueOf);
        assertTrue((valueOf & ClassFile.ACC_PUBLIC) != 0 && (valueOf & ClassFile.ACC_STATIC) != 0);
        assertEquals(0, valueOf & JdkClasses.JdkClass.CALLER_SENSITIVE);
        assertNull(string.method("valueOf", "(Ljava/lang/String;)Ljava/lang/String;"), "the exact descriptor");
        assertNull(string.method("hashCode", "()J"));
        assertNull(JdkClasses.find("java/util/ArrayList").method("stream", "()Ljava/util/stream/Stream;"),
                "an inherited method is not declared");
        assertNotNull(JdkClasses.find("java/util/ArrayList").method("<init>", "()V"));
        assertSame(string, JdkClasses.find("java/lang/String"), "a class is read once");
    }

    @Test
    void callerSensitiveMethodsInternalPackagesAndMissingClassesAreTold() {
        Integer forName = JdkClasses.find("java/lang/Class").method("forName",
                "(Ljava/lang/String;)Ljava/lang/Class;");
        assertNotNull(forName);
        assertTrue((forName & JdkClasses.JdkClass.CALLER_SENSITIVE) != 0);
        assertEquals(0, JdkClasses.find("java/lang/Class").method("getName", "()Ljava/lang/String;")
                & JdkClasses.JdkClass.CALLER_SENSITIVE);

        JdkClasses.JdkClass internal = JdkClasses.find("jdk/internal/misc/Unsafe");
        assertNotNull(internal);
        assertFalse(internal.exported(), "java.base exports jdk.internal.misc to named modules only");

        assertNull(JdkClasses.find("java/lang/NoSuchClass"));
        assertNull(JdkClasses.find("com/example/Application"));
        assertTrue((JdkClasses.find("java/util/function/Function").flags() & ClassFile.ACC_INTERFACE) != 0);
    }
}
