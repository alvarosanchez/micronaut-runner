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

/**
 * The MS-DOS timestamp conversion of {@link RunnerJarURLConnection}: a copy of
 * {@code io.micronaut.runner.NestedJarEntry.dosTimeToMillis}, so that an entry of the application layer and an
 * entry of a nested jar report the same instant for the same word.
 *
 * <p>It is a copy, rather than a call, so that {@code NestedJarEntry} can stay package-private. {@code DosTimeTest}
 * checks that the two copies agree on every date the format can express. It is a class of its own, rather than
 * a method of {@link RunnerJarURLConnection}, because that class loads before {@code main} and this one only loads
 * when a connection is first asked for a timestamp.</p>
 *
 * @since 1.0
 */
final class DosTime {

    /** Epoch milliseconds of 1980-01-01T00:00:00Z, the start of the MS-DOS timestamp range. */
    private static final long DOS_EPOCH = 315532800000L;

    private DosTime() {
    }

    /**
     * Converts a packed MS-DOS date and time word, as a ZIP header stores it, into epoch milliseconds
     * <strong>in UTC</strong>.
     *
     * <p>{@code ZipEntry} converts the same word in the default time zone, which makes the instant it reports
     * depend on where the machine is. The packager computes its timestamps in UTC, so converting back in UTC is
     * what round trips them. The arithmetic is the civil-from-days algorithm, so nothing on this path loads
     * {@code java.time} or a calendar.</p>
     *
     * @param dosTime the packed date and time, as {@code Index.entryDosTime(int)} returns it
     * @return the instant in epoch milliseconds, or {@code -1} when the word records no time
     */
    static long toMillis(long dosTime) {
        if (dosTime == 0) {
            return -1;
        }
        int second = (int) ((dosTime << 1) & 0x3E);
        int minute = (int) ((dosTime >> 5) & 0x3F);
        int hour = (int) ((dosTime >> 11) & 0x1F);
        int day = (int) ((dosTime >> 16) & 0x1F);
        int month = (int) ((dosTime >> 21) & 0x0F);
        int year = (int) ((dosTime >> 25) & 0x7F) + 1980;
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return DOS_EPOCH;
        }
        return (daysFromCivil(year, month, day) * 86400L + hour * 3600L + minute * 60L + second) * 1000L;
    }

    /**
     * The days between 1970-01-01 and a civil date, by Howard Hinnant's algorithm, which is plain integer
     * arithmetic valid for every year the MS-DOS format can express.
     *
     * @param year  the year
     * @param month the month, 1 to 12
     * @param day   the day of the month, 1 to 31
     * @return the number of days since the epoch, which may be negative
     */
    private static long daysFromCivil(int year, int month, int day) {
        int y = month <= 2 ? year - 1 : year;
        int era = (y >= 0 ? y : y - 399) / 400;
        int yearOfEra = y - era * 400;
        int dayOfYear = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        int dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
        return era * 146097L + dayOfEra - 719468L;
    }
}
