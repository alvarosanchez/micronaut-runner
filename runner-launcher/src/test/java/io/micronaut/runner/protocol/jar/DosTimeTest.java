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
package io.micronaut.runner.protocol.jar;

import io.micronaut.runner.LauncherTestAccess;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests {@link DosTime}, the connection's copy of {@code NestedJarEntry}'s MS-DOS time conversion. An application
 * entry and a nested entry must report the same instant for the same word, so the copy is held to the original
 * on every date word, in range or not, at three times of day, and on every time word, in range or not, on one
 * date.
 */
class DosTimeTest {

    @Test
    void convertsEveryWordExactlyAsNestedJarEntriesDo() {
        long[] times = {0x0000L, 0xBF7DL, 0xFFFFL};
        for (long date = 0; date <= 0xFFFFL; date++) {
            for (long time : times) {
                assertSameConversion(date << 16 | time);
            }
        }
        for (long time = 0; time <= 0xFFFFL; time++) {
            assertSameConversion(0x5A2F0000L | time);
        }
    }

    @Test
    void convertsInUtc() {
        assertEquals(-1, DosTime.toMillis(0));
        assertEquals(utc(1980, 1, 1, 0, 0, 0), DosTime.toMillis(0x00210000L));
        // 2025-01-15 23:59:58, the last time of day the two-second resolution can express.
        assertEquals(utc(2025, 1, 15, 23, 59, 58), DosTime.toMillis(0x5A2FBF7DL));
        // 2107-12-31, the last day of the format's range.
        assertEquals(utc(2107, 12, 31, 0, 0, 0), DosTime.toMillis(0xFF9F0000L));
        // Month 0 records no valid date, so it reports the start of the range.
        assertEquals(utc(1980, 1, 1, 0, 0, 0), DosTime.toMillis(0x00010000L));
    }

    private static void assertSameConversion(long dosTime) {
        long expected = LauncherTestAccess.dosTimeToMillis(dosTime);
        long actual = DosTime.toMillis(dosTime);
        if (expected != actual) {
            assertEquals(expected, actual, "0x" + Long.toHexString(dosTime));
        }
    }

    private static long utc(int year, int month, int day, int hour, int minute, int second) {
        return LocalDateTime.of(year, month, day, hour, minute, second).toEpochSecond(ZoneOffset.UTC) * 1000L;
    }
}
