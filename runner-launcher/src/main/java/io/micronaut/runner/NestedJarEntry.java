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
package io.micronaut.runner;

import java.io.IOException;
import java.security.CodeSigner;
import java.security.cert.Certificate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.Manifest;

/**
 * One entry of a {@link NestedJarFile}, built from an index record instead of from a central directory
 * header.
 *
 * <p>Everything a library reads off a {@link JarEntry} comes from the record: the name, the uncompressed
 * and compressed sizes, the CRC, the compression method and the timestamp. A library that walks a jar to
 * decide what to do with each entry, and there are several in a Micronaut application, therefore sees
 * exactly what it would see if the dependency were a jar file of its own on the class path.</p>
 *
 * <h2>Versioned entries</h2>
 * <p>When the record is a multi-release alias, {@link #getName()} is the base name the caller asked for
 * and {@link #getRealName()} is the {@code META-INF/versions/<n>/...} entry that actually holds the bytes,
 * which is exactly the distinction {@code JarFile} draws for a versioned entry of an ordinary jar.</p>
 *
 * @since 1.0
 */
public final class NestedJarEntry extends JarEntry {

    /** Epoch milliseconds of 1980-01-01T00:00:00Z, the start of the MS-DOS timestamp range. */
    private static final long DOS_EPOCH = 315532800000L;

    private final NestedJarFile owner;
    private final int record;
    private final String realName;

    /**
     * Builds an entry from an index record.
     *
     * @param owner  the jar view this entry belongs to
     * @param index  the index the record lives in
     * @param record the entry record
     */
    NestedJarEntry(NestedJarFile owner, Index index, int record) {
        super(index.entryName(record));
        this.owner = owner;
        this.record = record;
        int physical = index.entryPhysicalIndex(record);
        this.realName = physical == IndexFormat.NO_INDEX || physical == record
                ? null : index.entryName(physical);
        int method = index.entryMethod(record);
        if (method == IndexFormat.METHOD_STORED || method == IndexFormat.METHOD_DEFLATED) {
            setMethod(method);
        }
        setSize(index.entryUncompressedSize(record));
        setCompressedSize(index.entryCompressedSize(record));
        setCrc(index.entryCrc32(record));
        long time = dosTimeToMillis(index.entryDosTime(record));
        if (time >= 0) {
            setTime(time);
        }
    }

    /**
     * Converts a packed MS-DOS date and time word, as a ZIP header stores it, into epoch milliseconds
     * <strong>in UTC</strong>.
     *
     * <p>{@code ZipEntry} converts the same word in the default time zone, which makes the instant it
     * reports depend on where the machine is. The packager computes its timestamps in UTC, so converting
     * back in UTC is what round trips them, and it keeps a build reproducible across time zones. The
     * arithmetic is the civil-from-days algorithm, so nothing on this path loads {@code java.time} or a
     * calendar.</p>
     *
     * @param dosTime the packed date and time, as {@link Index#entryDosTime(int)} returns it
     * @return the instant in epoch milliseconds, or {@code -1} when the word records no time
     */
    public static long dosTimeToMillis(long dosTime) {
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

    /**
     * The jar view this entry was read from.
     *
     * @return the owning view
     */
    public NestedJarFile nestedJarFile() {
        return owner;
    }

    /**
     * The index record behind this entry, so that a reader that already has the entry does not have to
     * look it up again.
     *
     * @return the entry record index
     */
    public int record() {
        return record;
    }

    /**
     * The name of the entry that physically holds the bytes: the same as {@link #getName()} for an
     * ordinary entry, and the {@code META-INF/versions/<n>/...} name for a versioned one.
     *
     * @return the physical entry name
     */
    @Override
    public String getRealName() {
        return realName == null ? getName() : realName;
    }

    /**
     * The manifest section that applies to this entry, from the nested jar's own manifest.
     *
     * @return the attributes, or {@code null} when the manifest has no section for this entry
     * @throws IOException if the manifest cannot be read
     */
    @Override
    public Attributes getAttributes() throws IOException {
        Manifest manifest = owner.getManifest();
        return manifest == null ? null : manifest.getAttributes(getRealName());
    }

    /**
     * Always {@code null}: the packager strips signature files, so nothing inside a runner jar is signed
     * and no entry can be verified.
     *
     * @return {@code null}
     */
    @Override
    public Certificate[] getCertificates() {
        return null;
    }

    /**
     * Always {@code null}, for the same reason as {@link #getCertificates()}.
     *
     * @return {@code null}
     */
    @Override
    public CodeSigner[] getCodeSigners() {
        return null;
    }
}
