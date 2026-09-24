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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompressionTest {

    @Test
    void parsesIgnoringCaseAndSurroundingWhitespace() {
        assertEquals(Compression.PRESERVE, Compression.parse(" Preserve "));
        assertEquals(Compression.STORED, Compression.parse("stored"));
        for (Compression compression : Compression.values()) {
            assertEquals(compression, Compression.parse(compression.name()));
        }
    }

    @Test
    void anUnknownValueNamesItselfAndEverySupportedValue() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Compression.parse("DEFLATED"));

        String message = failure.getMessage();
        assertTrue(message.startsWith("Unknown compression 'DEFLATED'. Supported values are "), message);
        for (Compression compression : Compression.values()) {
            assertTrue(message.contains(compression.name()), () -> message + " does not name " + compression);
        }
        assertEquals("Unknown compression 'DEFLATED'. Supported values are STORED, PRESERVE.", message);
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> Compression.parse(null));
    }
}
