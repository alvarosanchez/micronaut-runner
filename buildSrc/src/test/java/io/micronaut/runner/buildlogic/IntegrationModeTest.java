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
package io.micronaut.runner.buildlogic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationModeTest {

    @Test
    void requiredIsTheFailClosedDefault() {
        assertEquals(IntegrationMode.REQUIRED, IntegrationMode.resolve(null, null));
        assertEquals(IntegrationMode.REQUIRED, IntegrationMode.resolve(null, "false"));
    }

    @Test
    void developerCanExplicitlyOptOut() {
        assertEquals(IntegrationMode.OFFLINE, IntegrationMode.resolve("offline", null));
        assertEquals(IntegrationMode.OFFLINE, IntegrationMode.resolve(null, "true"));
    }

    @Test
    void requiredModeCannotBeCombinedWithOfflineIntent() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> IntegrationMode.resolve("required", "true"));
        assertTrue(failure.getMessage().contains("required"));
        assertTrue(failure.getMessage().contains("RUNNER_TEST_OFFLINE=true"));
    }

    @Test
    void invalidPolicyValuesFailInsteadOfSilentlySelectingAMode() {
        assertThrows(IllegalArgumentException.class, () -> IntegrationMode.resolve("sometimes", null));
        assertThrows(IllegalArgumentException.class, () -> IntegrationMode.resolve(null, "maybe"));
    }

    @Test
    void modeIdentityIsStableForTaskInputsAndReports() {
        assertEquals("required", IntegrationMode.REQUIRED.id());
        assertEquals("offline", IntegrationMode.OFFLINE.id());
    }
}
