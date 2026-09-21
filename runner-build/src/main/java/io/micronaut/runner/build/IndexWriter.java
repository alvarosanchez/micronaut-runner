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

import io.micronaut.runner.IndexFormat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Builds the bytes of {@code MICRONAUT-INF/index.bin} from a description of the jars and entries a runner
 * jar contains.
 *
 * <p>The writer derives everything the format requires from the entry names: the multi-release aliases of a
 * jar flagged {@link IndexFormat#JAR_FLAG_MULTI_RELEASE}, the synthetic records for directories that are
 * implied but never stored, the chain of records that share a logical name, and the open addressed hash
 * table that finds a chain in one probe. See {@link IndexFormat} for the layout it writes.</p>
 *
 * <h2>Why this is a two step API</h2>
 * <p>Every record of the index is fixed size and the string table holds names, not offsets, so the length
 * of the index depends only on how many jars, entries and packages there are and on what they are called.
 * It does <em>not</em> depend on the offsets the index stores. {@link #layout()} exploits that: it lays the
 * index out and reports its {@link Layout#length()} before a single byte of the archive has been written.
 * The packager can then place the index entry in the outer archive, learn where every entry after it will
 * land, feed those offsets back into the specs and call {@link #write(Layout, long)}. Without that property
 * the archive would have to be written twice.</p>
 *
 * <pre>{@code
 * IndexWriter writer = new IndexWriter().startClass("com.example.Application");
 * IndexWriter.JarSpec application = writer.addJar(IndexFormat.CLASSES_PREFIX);
 * IndexWriter.EntrySpec entry = application.addEntry("com/example/Application.class");
 * IndexWriter.Layout layout = writer.layout();
 * // ... place an index of layout.length() bytes in the archive, then:
 * entry.dataOffset(offsetOfThatEntry);
 * byte[] index = writer.write(layout, archiveLength);
 * }</pre>
 *
 * <p>This is build time code: it is written as ordinary Java, unlike the reader in the launcher.</p>
 *
 * @since 1.0
 */
public final class IndexWriter {

    /** Largest value a {@code u32} field of the format can hold. */
    private static final long MAX_U32 = 0xFFFFFFFFL;

    /** Largest value a {@code u16} field of the format can hold. */
    private static final int MAX_U16 = 0xFFFF;

    /** The prefix of a multi-release entry, whose next segment is the feature version. */
    private static final String VERSIONS_PREFIX = "META-INF/versions/";

    /** The prefix a versioned path must not have, because {@code META-INF} is never versioned. */
    private static final String META_INF_PREFIX = "META-INF/";

    /**
     * The lowest feature version a {@code META-INF/versions/N} directory may name.
     *
     * <p>Eight, not nine, because that is where {@code java.util.jar.JarFile} draws the line: it considers
     * every version from {@code Runtime.Version.parse("8").feature()} upwards, so a jar carrying
     * {@code META-INF/versions/8/} really does resolve to that entry on an ordinary class path. Such a
     * directory is selected only when the effective runtime version is above it, which is exactly what
     * {@code Index.resolve} enforces.</p>
     */
    private static final int MIN_MULTI_RELEASE_VERSION = 8;

    /** The highest feature version the {@code u8} of {@link IndexFormat#E_MR_VERSION} can hold. */
    private static final int MAX_MULTI_RELEASE_VERSION = 0xFF;

    /**
     * The largest hash table the writer grows to before it gives up and names the entries that collide.
     *
     * <p>Sixteen million slots is sixty-four megabytes of {@code int}, far past anything a real index
     * needs: the table starts at twice the number of distinct names, so reaching this means the names
     * themselves, not the table, are the problem.</p>
     */
    private static final int MAX_HASH_SLOTS = 1 << 24;

    /** How many doublings in a row may fail to shorten the longest probe run before the writer gives up. */
    private static final int MAX_HASH_STALLS = 2;

    /** How many colliding names a diagnostic lists before it stops. */
    private static final int MAX_REPORTED_COLLISIONS = 8;

    /** Orders the versioned aliases of one jar: by name, and for one name by descending version. */
    private static final Comparator<Record> ALIAS_ORDER = new AliasOrder();

    /** Orders the records that share a logical name the way a lookup walks them. */
    private static final Comparator<Record> CHAIN_ORDER = new ChainOrder();

    private final List<JarSpec> jars = new ArrayList<>();
    private String startClass;
    private String entryStubClass;
    private String launcherVersion;
    private int headerFlags;

    /**
     * Creates an empty writer.
     */
    public IndexWriter() {
    }

    /**
     * Sets the application main class recorded in the header.
     *
     * @param value the binary class name, or {@code null} when there is none
     * @return this writer
     */
    public IndexWriter startClass(String value) {
        this.startClass = value;
        return this;
    }

    /**
     * Sets the generated entry stub class recorded in the header.
     *
     * @param value the binary class name, or {@code null} when no stub was generated
     * @return this writer
     */
    public IndexWriter entryStubClass(String value) {
        this.entryStubClass = value;
        return this;
    }

    /**
     * Sets the version of the packaging library recorded in the header.
     *
     * @param value the version, or {@code null} when it is unknown
     * @return this writer
     */
    public IndexWriter launcherVersion(String value) {
        this.launcherVersion = value;
        return this;
    }

    /**
     * Sets the header flags.
     *
     * @param value a mask of {@code IndexFormat.HEADER_FLAG_*}
     * @return this writer
     */
    public IndexWriter headerFlags(int value) {
        this.headerFlags = value;
        return this;
    }

    /**
     * Adds a jar. The first jar added is the application layer, jar {@code 0}, and is flagged
     * {@link IndexFormat#JAR_FLAG_IS_OUTER}.
     *
     * @param name the jar name: {@code MICRONAUT-INF/lib/<file>.jar}, or {@link IndexFormat#CLASSES_PREFIX}
     *             for the application layer
     * @return the jar, to describe its entries and metadata with
     * @throws NullPointerException     if {@code name} is {@code null}
     * @throws IllegalStateException    if the format's jar limit would be exceeded
     */
    public JarSpec addJar(String name) {
        Objects.requireNonNull(name, "name");
        if (jars.size() > MAX_U16) {
            throw new IllegalStateException("A runner jar cannot hold more than " + (MAX_U16 + 1) + " jars");
        }
        JarSpec jar = new JarSpec(this, name, jars.size());
        jars.add(jar);
        return jar;
    }

    /**
     * The jars added so far.
     *
     * @return the jars, in index order, starting with the application layer
     */
    public List<JarSpec> jars() {
        return Collections.unmodifiableList(jars);
    }

    /**
     * Lays the index out: expands the aliases and synthetic directories, chains the records that share a
     * name, sizes and fills the hash table and builds the string table.
     *
     * <p>Nothing here reads an offset, which is exactly the point: the resulting {@link Layout#length()} is
     * the final length of the index, so the caller can reserve room for it and only then work out where
     * everything else in the archive lands.</p>
     *
     * @return the layout, to be handed back to {@link #write(Layout, long)}
     * @throws IllegalStateException if a name is too long for the format, or the hash table cannot be filled
     *                               within {@link IndexFormat#MAX_PROBE_LIMIT} probes
     */
    public Layout layout() {
        List<Record> records = records();
        Map<String, List<Record>> chains = chains(records);
        link(chains);
        int slots = slots(chains.size());
        int[] table = new int[slots];
        int probe = fill(table, chains, slots);
        int stalls = 0;
        while (probe > IndexFormat.MAX_PROBE_LIMIT) {
            // Doubling shortens the longest run only while the names crowding a slot have different hash
            // codes. Names whose 32-bit hashes are equal land on the same slot at every table size, so the
            // run they force is at least their number and no table is ever large enough. Growing has to
            // stop and the offending names have to be named, or the build doubles until the heap is gone.
            if (slots > MAX_HASH_SLOTS / 2) {
                throw crowdedHashTable(chains, slots, probe);
            }
            int previous = probe;
            slots *= 2;
            table = new int[slots];
            probe = fill(table, chains, slots);
            if (probe < previous) {
                stalls = 0;
            } else {
                stalls++;
                if (stalls > MAX_HASH_STALLS) {
                    throw crowdedHashTable(chains, slots, probe);
                }
            }
        }

        Strings strings = new Strings();
        int startClassRef = strings.intern(startClass);
        int entryStubClassRef = strings.intern(entryStubClass);
        int launcherVersionRef = strings.intern(launcherVersion);
        int packageCount = 0;
        for (JarSpec jar : jars) {
            jar.nameRef = strings.intern(jar.name);
            jar.coordinatesRef = strings.intern(jar.coordinates);
            jar.specTitleRef = strings.internAttribute(jar.specTitle);
            jar.specVersionRef = strings.internAttribute(jar.specVersion);
            jar.specVendorRef = strings.internAttribute(jar.specVendor);
            jar.implTitleRef = strings.internAttribute(jar.implTitle);
            jar.implVersionRef = strings.internAttribute(jar.implVersion);
            jar.implVendorRef = strings.internAttribute(jar.implVendor);
            jar.firstPackage = packageCount;
            for (PackageSpec section : jar.packages) {
                section.nameRef = strings.intern(section.name);
                section.specTitleRef = strings.internAttribute(section.specTitle);
                section.specVersionRef = strings.internAttribute(section.specVersion);
                section.specVendorRef = strings.internAttribute(section.specVendor);
                section.implTitleRef = strings.internAttribute(section.implTitle);
                section.implVersionRef = strings.internAttribute(section.implVersion);
                section.implVendorRef = strings.internAttribute(section.implVendor);
                packageCount++;
            }
        }
        for (Record record : records) {
            record.nameRef = strings.intern(record.name);
        }

        int jarTable = IndexFormat.HEADER_SIZE;
        int packageTable = align(jarTable + jars.size() * IndexFormat.JAR_RECORD_SIZE);
        int entryTable = align(packageTable + packageCount * IndexFormat.PACKAGE_RECORD_SIZE);
        int hashTable = align(entryTable + records.size() * IndexFormat.ENTRY_RECORD_SIZE);
        int stringTable = align(hashTable + slots * 4);
        Layout layout = new Layout(this, records, table, probe, packageCount);
        layout.strings(strings.bytes(), startClassRef, entryStubClassRef, launcherVersionRef);
        layout.sections(jarTable, packageTable, entryTable, hashTable, stringTable);
        return layout;
    }

    /**
     * Writes the index.
     *
     * <p>Offsets and sizes are read from the specs as they stand now, so this is the call that picks up
     * whatever the packager learned about the archive after {@link #layout()} ran. The layout itself, and
     * therefore the length of the result, is unchanged.</p>
     *
     * @param layout          the layout this writer produced
     * @param outerFileLength the length of the finished outer archive, which the reader checks against the
     *                        file it opens
     * @return the complete index, exactly {@link Layout#length()} bytes long
     * @throws NullPointerException     if {@code layout} is {@code null}
     * @throws IllegalArgumentException if the layout belongs to another writer, or a value does not fit the
     *                                  field the format stores it in
     */
    public byte[] write(Layout layout, long outerFileLength) {
        Objects.requireNonNull(layout, "layout");
        if (layout.owner != this) {
            throw new IllegalArgumentException("The layout was produced by a different IndexWriter");
        }
        if (outerFileLength < 0) {
            throw new IllegalArgumentException("Negative outer file length: " + outerFileLength);
        }
        byte[] result = new byte[layout.length()];
        ByteBuffer out = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);

        out.putInt(IndexFormat.H_MAGIC, IndexFormat.MAGIC);
        out.putShort(IndexFormat.H_FORMAT_VERSION, (short) IndexFormat.FORMAT_VERSION);
        out.putShort(IndexFormat.H_FLAGS, (short) headerFlags);
        out.putInt(IndexFormat.H_JAR_COUNT, jars.size());
        out.putInt(IndexFormat.H_ENTRY_COUNT, layout.records.size());
        out.putInt(IndexFormat.H_HASH_SLOTS, layout.hashTable.length);
        out.putInt(IndexFormat.H_MAX_PROBE, layout.maxProbe);
        out.putLong(IndexFormat.H_OUTER_FILE_LENGTH, outerFileLength);
        out.putLong(IndexFormat.H_JAR_TABLE_OFFSET, layout.jarTableOffset);
        out.putLong(IndexFormat.H_ENTRY_TABLE_OFFSET, layout.entryTableOffset);
        out.putLong(IndexFormat.H_HASH_TABLE_OFFSET, layout.hashTableOffset);
        out.putLong(IndexFormat.H_PACKAGE_TABLE_OFFSET, layout.packageTableOffset);
        out.putLong(IndexFormat.H_STRING_TABLE_OFFSET, layout.stringTableOffset);
        out.putLong(IndexFormat.H_STRING_TABLE_LENGTH, layout.strings.length);
        out.putInt(IndexFormat.H_START_CLASS, layout.startClassRef);
        out.putInt(IndexFormat.H_ENTRY_STUB_CLASS, layout.entryStubClassRef);
        out.putInt(IndexFormat.H_LAUNCHER_VERSION, layout.launcherVersionRef);
        out.putInt(IndexFormat.H_PACKAGE_COUNT, layout.packageCount);

        int packageIndex = 0;
        for (JarSpec jar : jars) {
            int at = layout.jarTableOffset + jar.id * IndexFormat.JAR_RECORD_SIZE;
            out.putLong(at + IndexFormat.J_DATA_OFFSET, requireNonNegative(jar.dataOffset, "data offset"));
            out.putLong(at + IndexFormat.J_DATA_LENGTH, requireNonNegative(jar.dataLength, "data length"));
            out.putLong(at + IndexFormat.J_LOCAL_HEADER_OFFSET,
                    requireNonNegative(jar.localHeaderOffset, "local header offset"));
            out.putInt(at + IndexFormat.J_NAME, jar.nameRef);
            out.putInt(at + IndexFormat.J_COORDINATES, jar.coordinatesRef);
            out.putInt(at + IndexFormat.J_FIRST_ENTRY, jar.firstEntry);
            out.putInt(at + IndexFormat.J_ENTRY_COUNT, jar.entryCount);
            out.putInt(at + IndexFormat.J_FIRST_PACKAGE, jar.firstPackage);
            out.putInt(at + IndexFormat.J_PACKAGE_COUNT, jar.packages.size());
            out.putShort(at + IndexFormat.J_FLAGS, (short) jar.flags);
            out.putInt(at + IndexFormat.J_SPEC_TITLE, jar.specTitleRef);
            out.putInt(at + IndexFormat.J_SPEC_VERSION, jar.specVersionRef);
            out.putInt(at + IndexFormat.J_SPEC_VENDOR, jar.specVendorRef);
            out.putInt(at + IndexFormat.J_IMPL_TITLE, jar.implTitleRef);
            out.putInt(at + IndexFormat.J_IMPL_VERSION, jar.implVersionRef);
            out.putInt(at + IndexFormat.J_IMPL_VENDOR, jar.implVendorRef);
            for (PackageSpec section : jar.packages) {
                int p = layout.packageTableOffset + packageIndex * IndexFormat.PACKAGE_RECORD_SIZE;
                out.putInt(p + IndexFormat.P_NAME, section.nameRef);
                out.putInt(p + IndexFormat.P_SPEC_TITLE, section.specTitleRef);
                out.putInt(p + IndexFormat.P_SPEC_VERSION, section.specVersionRef);
                out.putInt(p + IndexFormat.P_SPEC_VENDOR, section.specVendorRef);
                out.putInt(p + IndexFormat.P_IMPL_TITLE, section.implTitleRef);
                out.putInt(p + IndexFormat.P_IMPL_VERSION, section.implVersionRef);
                out.putInt(p + IndexFormat.P_IMPL_VENDOR, section.implVendorRef);
                out.putShort(p + IndexFormat.P_FLAGS, (short) section.flags);
                packageIndex++;
            }
        }

        for (Record record : layout.records) {
            int at = layout.entryTableOffset + record.index * IndexFormat.ENTRY_RECORD_SIZE;
            EntrySpec source = record.source;
            long compressed = source == null ? 0 : source.compressedSize;
            long uncompressed = source == null ? 0 : source.uncompressedSize;
            long crc = source == null ? 0 : source.crc32;
            long dos = source == null ? 0 : source.dosTime;
            long dataOffset = source == null ? 0 : source.dataOffset;
            out.putInt(at + IndexFormat.E_NAME_HASH, IndexFormat.hash(record.name));
            out.putInt(at + IndexFormat.E_NAME, record.nameRef);
            out.putLong(at + IndexFormat.E_DATA_OFFSET, requireNonNegative(dataOffset, "data offset"));
            out.putInt(at + IndexFormat.E_COMPRESSED_SIZE,
                    (int) requireU32(compressed, "compressed size", record.name));
            out.putInt(at + IndexFormat.E_UNCOMPRESSED_SIZE,
                    (int) requireU32(uncompressed, "uncompressed size", record.name));
            out.putInt(at + IndexFormat.E_CRC32, (int) requireU32(crc, "CRC-32", record.name));
            out.putInt(at + IndexFormat.E_DOS_TIME, (int) requireU32(dos, "MS-DOS time", record.name));
            out.putInt(at + IndexFormat.E_NEXT_SAME_NAME, record.next);
            out.putShort(at + IndexFormat.E_JAR_ID, (short) record.jarId);
            out.putShort(at + IndexFormat.E_NAME_LENGTH, (short) record.nameLength);
            out.put(at + IndexFormat.E_METHOD, (byte) (source == null
                    ? IndexFormat.METHOD_STORED : source.method));
            out.put(at + IndexFormat.E_MR_VERSION, (byte) record.mrVersion);
            out.put(at + IndexFormat.E_FLAGS, (byte) record.flags);
            out.putInt(at + IndexFormat.E_PHYSICAL_INDEX, record.physicalIndex);
        }

        for (int i = 0; i < layout.hashTable.length; i++) {
            out.putInt(layout.hashTableOffset + i * 4, layout.hashTable[i]);
        }
        System.arraycopy(layout.strings, 0, result, layout.stringTableOffset, layout.strings.length);
        return result;
    }

    /**
     * Rounds an offset up to the eight byte alignment every section of the index starts at.
     *
     * @param value the offset
     * @return the aligned offset
     */
    private static int align(int value) {
        return (value + 7) & ~7;
    }

    /**
     * The feature version of a {@code META-INF/versions/N/...} entry name.
     *
     * @param name the entry name
     * @return {@code N}, or {@code 0} when the name is not a versioned entry of a usable version
     */
    private static int versionOf(String name) {
        if (!name.startsWith(VERSIONS_PREFIX)) {
            return 0;
        }
        int slash = name.indexOf('/', VERSIONS_PREFIX.length());
        if (slash < 0 || slash == VERSIONS_PREFIX.length()) {
            return 0;
        }
        char first = name.charAt(VERSIONS_PREFIX.length());
        if (first < '1' || first > '9') {
            // A leading zero is not a version. ZipFile.Source.getMetaVersion rejects it, so the JDK treats
            // META-INF/versions/09/x as an ordinary entry and never selects it; aliasing it here would
            // serve different bytes inside a runner jar than on a class path.
            return 0;
        }
        int version = 0;
        for (int i = VERSIONS_PREFIX.length(); i < slash; i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
            version = version * 10 + (c - '0');
            if (version > MAX_MULTI_RELEASE_VERSION) {
                // The format stores the version in a u8, and a jar claiming a version beyond that is not
                // one any runtime reading this index will ever select.
                return 0;
            }
        }
        return version < MIN_MULTI_RELEASE_VERSION ? 0 : version;
    }

    /**
     * The path a {@code META-INF/versions/N/...} entry aliases.
     *
     * @param name the entry name
     * @return everything after the version directory
     */
    private static String pathOf(String name) {
        return name.substring(name.indexOf('/', VERSIONS_PREFIX.length()) + 1);
    }

    private static long requireNonNegative(long value, String what) {
        if (value < 0) {
            throw new IllegalArgumentException("Negative " + what + ": " + value);
        }
        return value;
    }

    private static long requireU32(long value, String what, String name) {
        if (value < 0 || value > MAX_U32) {
            throw new IllegalArgumentException("The " + what + " of '" + name + "' does not fit 32 bits: "
                    + value);
        }
        return value;
    }

    /**
     * Expands every jar into its records: the entries it physically holds in the order they were added,
     * then its versioned aliases, then the directories it only implies.
     *
     * @return every record of the index, in the order the entry table stores them
     */
    private List<Record> records() {
        List<Record> records = new ArrayList<>();
        for (JarSpec jar : jars) {
            jar.firstEntry = records.size();
            List<Record> physical = new ArrayList<>();
            for (EntrySpec spec : jar.entries) {
                Record record = new Record(records.size(), jar.id, spec.name, spec);
                record.flags = IndexFormat.ENTRY_FLAG_PHYSICAL | spec.extraFlags;
                if (spec.name.endsWith("/")) {
                    record.flags |= IndexFormat.ENTRY_FLAG_DIRECTORY;
                }
                record.physicalIndex = record.index;
                records.add(record);
                physical.add(record);
            }
            if ((jar.flags & IndexFormat.JAR_FLAG_MULTI_RELEASE) != 0) {
                List<Record> aliases = new ArrayList<>();
                for (Record record : physical) {
                    int version = versionOf(record.name);
                    if (version == 0) {
                        continue;
                    }
                    String path = pathOf(record.name);
                    if (path.isEmpty() || path.startsWith(META_INF_PREFIX)) {
                        continue;
                    }
                    Record alias = new Record(0, jar.id, path, record.source);
                    alias.mrVersion = version;
                    alias.flags = IndexFormat.ENTRY_FLAG_VERSIONED_ALIAS
                            | (path.endsWith("/") ? IndexFormat.ENTRY_FLAG_DIRECTORY : 0);
                    alias.physicalIndex = record.index;
                    aliases.add(alias);
                }
                aliases.sort(ALIAS_ORDER);
                for (Record alias : aliases) {
                    alias.index = records.size();
                    records.add(alias);
                }
            }
            TreeSet<String> wanted = new TreeSet<>();
            TreeSet<String> present = new TreeSet<>();
            for (int i = jar.firstEntry; i < records.size(); i++) {
                String name = records.get(i).name;
                present.add(name);
                for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
                    wanted.add(name.substring(0, slash + 1));
                }
            }
            wanted.removeAll(present);
            for (String name : wanted) {
                Record record = new Record(records.size(), jar.id, name, null);
                record.flags = IndexFormat.ENTRY_FLAG_DIRECTORY | IndexFormat.ENTRY_FLAG_SYNTHETIC_DIR;
                record.physicalIndex = IndexFormat.NO_INDEX;
                records.add(record);
            }
            jar.entryCount = records.size() - jar.firstEntry;
        }
        return records;
    }

    /**
     * Groups the records by logical name and orders each group the way a lookup walks it: the application
     * layer first, then the dependencies in class path order, and within one jar the versioned aliases in
     * descending version before the base record.
     *
     * @param records every record of the index
     * @return the chains, keyed by logical name, in the order the names first appear
     */
    private Map<String, List<Record>> chains(List<Record> records) {
        Map<String, List<Record>> chains = new LinkedHashMap<>();
        for (Record record : records) {
            List<Record> chain = chains.get(record.name);
            if (chain == null) {
                chain = new ArrayList<>(1);
                chains.put(record.name, chain);
            }
            chain.add(record);
        }
        for (List<Record> chain : chains.values()) {
            chain.sort(CHAIN_ORDER);
        }
        return chains;
    }

    /**
     * Writes the {@code nextSameName} link of every record of every chain.
     *
     * @param chains the chains
     */
    private void link(Map<String, List<Record>> chains) {
        for (List<Record> chain : chains.values()) {
            for (int i = 0; i < chain.size(); i++) {
                chain.get(i).next = i + 1 < chain.size() ? chain.get(i + 1).index : IndexFormat.NO_INDEX;
            }
        }
    }

    /**
     * The size of the hash table: the next power of two that leaves the table at most half full, which is
     * what keeps linear probing short.
     *
     * @param distinctNames the number of distinct logical names
     * @return the number of slots
     */
    private int slots(int distinctNames) {
        int slots = 2;
        while (slots < distinctNames * 2) {
            slots *= 2;
        }
        return slots;
    }

    /**
     * Reports a hash table that cannot be filled, naming the logical names that crowd one slot.
     *
     * <p>A reader recomputes the slot from the name's hash alone, so names that share a hash share a slot
     * in every table size. The message therefore names the largest group of names with one hash: those are
     * the entries to rename or remove, and nothing about the table can be changed to accommodate them.</p>
     *
     * @param chains the chains being placed, keyed by logical name
     * @param slots  the size of the table that was tried last
     * @param probe  the longest probe sequence that table needed
     * @return the exception to throw
     */
    private IllegalStateException crowdedHashTable(Map<String, List<Record>> chains, int slots, int probe) {
        Map<Integer, List<String>> byHash = new LinkedHashMap<>();
        List<String> worst = new ArrayList<>();
        int worstHash = 0;
        for (String name : chains.keySet()) {
            Integer hash = Integer.valueOf(IndexFormat.hash(name));
            List<String> sharing = byHash.get(hash);
            if (sharing == null) {
                sharing = new ArrayList<>();
                byHash.put(hash, sharing);
            }
            sharing.add(name);
            if (sharing.size() > worst.size()) {
                worst = sharing;
                worstHash = hash.intValue();
            }
        }
        StringBuilder message = new StringBuilder(256);
        message.append("The index hash table cannot be filled within ")
                .append(IndexFormat.MAX_PROBE_LIMIT)
                .append(" probes: ").append(slots)
                .append(" slots still need a run of ").append(probe).append('.');
        if (worst.size() > 1) {
            message.append(' ').append(worst.size())
                    .append(" entry names share the hash code ").append(worstHash).append(" (");
            int shown = Math.min(worst.size(), MAX_REPORTED_COLLISIONS);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    message.append(", ");
                }
                message.append('\'').append(worst.get(i)).append('\'');
            }
            if (worst.size() > shown) {
                message.append(", and ").append(worst.size() - shown).append(" more");
            }
            message.append("). A lookup finds a name by that hash alone, so no table size separates them;"
                    + " rename or remove the entries that collide.");
        }
        return new IllegalStateException(message.toString());
    }

    /**
     * Fills the hash table with the head of every chain.
     *
     * @param table  the table to fill, which is overwritten
     * @param chains the chains, in the order they are inserted
     * @param slots  the size of the table
     * @return the longest probe sequence any insertion needed, which the header records so the reader can
     *         stop looking after it
     */
    private int fill(int[] table, Map<String, List<Record>> chains, int slots) {
        Arrays.fill(table, IndexFormat.NO_INDEX);
        int mask = slots - 1;
        int longest = 0;
        for (Map.Entry<String, List<Record>> chain : chains.entrySet()) {
            int slot = IndexFormat.hash(chain.getKey()) & mask;
            int probe = 0;
            while (table[slot] != IndexFormat.NO_INDEX) {
                slot = (slot + 1) & mask;
                probe++;
                if (probe > slots) {
                    throw new IllegalStateException("The hash table of " + slots + " slots is full");
                }
            }
            table[slot] = chain.getValue().get(0).index;
            longest = Math.max(longest, probe);
        }
        return longest;
    }

    /**
     * A laid out index: every record in its final position, the hash table filled and the string table
     * built, waiting only for the offsets.
     *
     * <p>A layout is produced by {@link IndexWriter#layout()} and consumed by
     * {@link IndexWriter#write(Layout, long)}. It holds no offsets of its own, which is why the length it
     * reports is final.</p>
     */
    public static final class Layout {

        private final IndexWriter owner;
        private final List<Record> records;
        private final int[] hashTable;
        private final int maxProbe;
        private final int packageCount;
        private byte[] strings;
        private int startClassRef;
        private int entryStubClassRef;
        private int launcherVersionRef;
        private int jarTableOffset;
        private int packageTableOffset;
        private int entryTableOffset;
        private int hashTableOffset;
        private int stringTableOffset;

        private Layout(IndexWriter owner, List<Record> records, int[] hashTable, int maxProbe,
                       int packageCount) {
            this.owner = owner;
            this.records = records;
            this.hashTable = hashTable;
            this.maxProbe = maxProbe;
            this.packageCount = packageCount;
        }

        /**
         * Records the string table and the three header references into it.
         *
         * @param table         the string table bytes
         * @param startClass    the reference to the application main class
         * @param entryStub     the reference to the generated entry stub class
         * @param launcher      the reference to the packaging library version
         */
        private void strings(byte[] table, int startClass, int entryStub, int launcher) {
            this.strings = table;
            this.startClassRef = startClass;
            this.entryStubClassRef = entryStub;
            this.launcherVersionRef = launcher;
        }

        /**
         * Records where each section of the index begins.
         *
         * @param jars     the offset of the jar table
         * @param packages the offset of the package table
         * @param entries  the offset of the entry table
         * @param hashes   the offset of the hash table
         * @param table    the offset of the string table
         */
        private void sections(int jars, int packages, int entries, int hashes, int table) {
            this.jarTableOffset = jars;
            this.packageTableOffset = packages;
            this.entryTableOffset = entries;
            this.hashTableOffset = hashes;
            this.stringTableOffset = table;
        }

        /**
         * The exact length of the index this layout produces.
         *
         * @return the length in bytes
         */
        public int length() {
            return stringTableOffset + strings.length;
        }

        /**
         * The number of records in the entry table: physical entries, versioned aliases and synthetic
         * directories together.
         *
         * @return the record count
         */
        public int entryCount() {
            return records.size();
        }

        /**
         * The number of package override records.
         *
         * @return the package count
         */
        public int packageCount() {
            return packageCount;
        }

        /**
         * The size of the hash table.
         *
         * @return the number of slots, a power of two
         */
        public int hashSlots() {
            return hashTable.length;
        }

        /**
         * The longest probe sequence the hash table needs, which the reader refuses to exceed.
         *
         * @return the maximum probe length
         */
        public int maxProbe() {
            return maxProbe;
        }
    }

    /**
     * One jar of the index: the application layer, or a nested dependency.
     */
    public static final class JarSpec {

        private final IndexWriter owner;
        private final String name;
        private final int id;
        private final List<EntrySpec> entries = new ArrayList<>();
        private final List<PackageSpec> packages = new ArrayList<>();
        private String coordinates;
        private String specTitle;
        private String specVersion;
        private String specVendor;
        private String implTitle;
        private String implVersion;
        private String implVendor;
        private long dataOffset;
        private long dataLength;
        private long localHeaderOffset;
        private int flags;
        private int firstEntry;
        private int entryCount;
        private int firstPackage;
        private int nameRef;
        private int coordinatesRef;
        private int specTitleRef;
        private int specVersionRef;
        private int specVendorRef;
        private int implTitleRef;
        private int implVersionRef;
        private int implVendorRef;

        private JarSpec(IndexWriter owner, String name, int id) {
            this.owner = owner;
            this.name = name;
            this.id = id;
            if (id == IndexFormat.APPLICATION_JAR_ID) {
                this.flags = IndexFormat.JAR_FLAG_IS_OUTER;
            }
        }

        /**
         * The position of this jar in the jar table.
         *
         * @return the jar id, {@code 0} for the application layer
         */
        public int id() {
            return id;
        }

        /**
         * The name this jar is recorded under.
         *
         * @return the jar name
         */
        public String name() {
            return name;
        }

        /**
         * Sets the Maven coordinates of the jar.
         *
         * @param value the coordinates, or {@code null} when they are unknown
         * @return this jar
         */
        public JarSpec coordinates(String value) {
            this.coordinates = value;
            return this;
        }

        /**
         * Adds flags to the jar record.
         *
         * @param value a mask of {@code IndexFormat.JAR_FLAG_*}
         * @return this jar
         */
        public JarSpec addFlags(int value) {
            this.flags |= value;
            return this;
        }

        /**
         * Replaces the flags of the jar record.
         *
         * @param value a mask of {@code IndexFormat.JAR_FLAG_*}
         * @return this jar
         */
        public JarSpec flags(int value) {
            this.flags = value;
            return this;
        }

        /**
         * The flags of the jar record as they stand.
         *
         * @return a mask of {@code IndexFormat.JAR_FLAG_*}
         */
        public int flags() {
            return flags;
        }

        /**
         * Sets where the jar sits in the outer archive.
         *
         * <p>For the application layer, which is the outer archive itself, the offsets are {@code 0} and
         * the length is the length of the whole file.</p>
         *
         * @param offset            the absolute offset of the jar's first byte
         * @param length            the length of the jar in bytes
         * @param localHeaderOffset the offset of the outer local file header introducing it
         * @return this jar
         */
        public JarSpec location(long offset, long length, long localHeaderOffset) {
            this.dataOffset = offset;
            this.dataLength = length;
            this.localHeaderOffset = localHeaderOffset;
            return this;
        }

        /**
         * Sets the six manifest main attributes the jar record carries.
         *
         * @param specificationTitle    the {@code Specification-Title}
         * @param specificationVersion  the {@code Specification-Version}
         * @param specificationVendor   the {@code Specification-Vendor}
         * @param implementationTitle   the {@code Implementation-Title}
         * @param implementationVersion the {@code Implementation-Version}
         * @param implementationVendor  the {@code Implementation-Vendor}
         * @return this jar
         */
        public JarSpec manifest(String specificationTitle, String specificationVersion,
                                String specificationVendor, String implementationTitle,
                                String implementationVersion, String implementationVendor) {
            this.specTitle = specificationTitle;
            this.specVersion = specificationVersion;
            this.specVendor = specificationVendor;
            this.implTitle = implementationTitle;
            this.implVersion = implementationVersion;
            this.implVendor = implementationVendor;
            return this;
        }

        /**
         * Adds an entry that physically exists in this jar. Entries must be added in the order the archive
         * stores them, because that is the order the index preserves for enumeration.
         *
         * @param logicalName the name relative to this jar, which for the application layer is the outer
         *                    entry name minus {@link IndexFormat#CLASSES_PREFIX}
         * @return the entry, to set its sizes and offsets on
         * @throws NullPointerException  if {@code logicalName} is {@code null}
         * @throws IllegalStateException if the name is longer than the format allows
         */
        public EntrySpec addEntry(String logicalName) {
            Objects.requireNonNull(logicalName, "logicalName");
            EntrySpec spec = new EntrySpec(this, logicalName);
            entries.add(spec);
            return spec;
        }

        /**
         * Adds an entry described by a ZIP entry the packager already read, copying its sizes, checksum,
         * timestamp and method.
         *
         * @param logicalName the name relative to this jar
         * @param entry       the ZIP entry, whose {@link ZipEntryInfo#dataOffset()} is <em>not</em> used
         *                    because the index needs an offset in the outer archive
         * @return the entry, whose data offset still has to be set
         * @throws NullPointerException if an argument is {@code null}
         */
        public EntrySpec addEntry(String logicalName, ZipEntryInfo entry) {
            Objects.requireNonNull(entry, "entry");
            return addEntry(logicalName)
                    .method(entry.method())
                    .sizes(entry.compressedSize(), entry.uncompressedSize())
                    .crc32(entry.crc32())
                    .dosTime(entry.dosTime());
        }

        /**
         * The entries added to this jar so far.
         *
         * @return the entries, in the order they were added
         */
        public List<EntrySpec> entries() {
            return Collections.unmodifiableList(entries);
        }

        /**
         * Adds a package override section, as read from a {@code Name:} section of the jar's manifest.
         *
         * @param packageName the package name in dotted form
         * @return the section
         * @throws NullPointerException if {@code packageName} is {@code null}
         */
        public PackageSpec addPackage(String packageName) {
            Objects.requireNonNull(packageName, "packageName");
            PackageSpec section = new PackageSpec(this, packageName);
            packages.add(section);
            return section;
        }

        /**
         * Returns to the writer this jar belongs to.
         *
         * @return the writer
         */
        public IndexWriter end() {
            return owner;
        }
    }

    /**
     * One entry that physically exists in a jar.
     *
     * <p>Everything but {@link #dataOffset(long)} is known before the archive is written; the offset is the
     * one value that has to wait for the layout to be fixed.</p>
     */
    public static final class EntrySpec {

        private final JarSpec jar;
        private final String name;
        private long dataOffset;
        private long compressedSize;
        private long uncompressedSize;
        private long crc32;
        private long dosTime;
        private int method = IndexFormat.METHOD_STORED;
        private int extraFlags;

        private EntrySpec(JarSpec jar, String name) {
            this.jar = jar;
            this.name = name;
        }

        /**
         * The logical name of the entry.
         *
         * @return the name relative to the jar
         */
        public String name() {
            return name;
        }

        /**
         * Sets the absolute offset of the entry data in the outer archive, that is, past its local file
         * header. For an entry of a nested jar that is the offset of the nested jar plus the entry's offset
         * within it.
         *
         * @param value the absolute offset
         * @return this entry
         */
        public EntrySpec dataOffset(long value) {
            this.dataOffset = value;
            return this;
        }

        /**
         * The absolute offset set on this entry.
         *
         * @return the absolute offset of the entry data
         */
        public long dataOffset() {
            return dataOffset;
        }

        /**
         * Sets the stored and expanded sizes, which are equal for a stored entry.
         *
         * @param compressed   the number of stored bytes
         * @param uncompressed the number of bytes the entry expands to
         * @return this entry
         */
        public EntrySpec sizes(long compressed, long uncompressed) {
            this.compressedSize = compressed;
            this.uncompressedSize = uncompressed;
            return this;
        }

        /**
         * Sets the compression method.
         *
         * @param value {@link IndexFormat#METHOD_STORED} or {@link IndexFormat#METHOD_DEFLATED}
         * @return this entry
         * @throws IllegalArgumentException if the method is neither
         */
        public EntrySpec method(int value) {
            if (value != IndexFormat.METHOD_STORED && value != IndexFormat.METHOD_DEFLATED) {
                throw new IllegalArgumentException("Entry '" + name + "' uses unsupported compression method "
                        + value);
            }
            this.method = value;
            return this;
        }

        /**
         * Sets the CRC-32 of the entry content, as an unsigned 32 bit value.
         *
         * @param value the checksum
         * @return this entry
         */
        public EntrySpec crc32(long value) {
            this.crc32 = value;
            return this;
        }

        /**
         * Sets the MS-DOS date and time word, as the ZIP headers store it.
         *
         * <p>Only the low 32 bits are kept, so an {@code int} that went negative because its year pushed a
         * bit into the sign position stores the value it actually holds.</p>
         *
         * @param value the packed date and time
         * @return this entry
         */
        public EntrySpec dosTime(long value) {
            this.dosTime = value & MAX_U32;
            return this;
        }

        /**
         * Adds flags to the record on top of the ones the writer derives from the name.
         *
         * @param value a mask of {@code IndexFormat.ENTRY_FLAG_*}
         * @return this entry
         */
        public EntrySpec flags(int value) {
            this.extraFlags |= value;
            return this;
        }

        /**
         * Returns to the jar this entry belongs to.
         *
         * @return the jar
         */
        public JarSpec end() {
            return jar;
        }
    }

    /**
     * One package override section, taken from a {@code Name: <package>/} section of a jar's manifest.
     */
    public static final class PackageSpec {

        private final JarSpec jar;
        private final String name;
        private String specTitle;
        private String specVersion;
        private String specVendor;
        private String implTitle;
        private String implVersion;
        private String implVendor;
        private int flags;
        private int nameRef;
        private int specTitleRef;
        private int specVersionRef;
        private int specVendorRef;
        private int implTitleRef;
        private int implVersionRef;
        private int implVendorRef;

        private PackageSpec(JarSpec jar, String name) {
            this.jar = jar;
            this.name = name;
        }

        /**
         * The package this section applies to.
         *
         * @return the package name in dotted form
         */
        public String name() {
            return name;
        }

        /**
         * Sets the six attributes of the section; {@code null} means the jar's own attribute applies.
         *
         * @param specificationTitle    the {@code Specification-Title}
         * @param specificationVersion  the {@code Specification-Version}
         * @param specificationVendor   the {@code Specification-Vendor}
         * @param implementationTitle   the {@code Implementation-Title}
         * @param implementationVersion the {@code Implementation-Version}
         * @param implementationVendor  the {@code Implementation-Vendor}
         * @return this section
         */
        public PackageSpec attributes(String specificationTitle, String specificationVersion,
                                      String specificationVendor, String implementationTitle,
                                      String implementationVersion, String implementationVendor) {
            this.specTitle = specificationTitle;
            this.specVersion = specificationVersion;
            this.specVendor = specificationVendor;
            this.implTitle = implementationTitle;
            this.implVersion = implementationVersion;
            this.implVendor = implementationVendor;
            return this;
        }

        /**
         * Records a {@code Sealed} attribute on the section, which overrides the jar's default either way.
         *
         * @param sealed the value of the attribute
         * @return this section
         */
        public PackageSpec sealed(boolean sealed) {
            this.flags |= IndexFormat.PACKAGE_FLAG_SEALED_SPECIFIED;
            if (sealed) {
                this.flags |= IndexFormat.PACKAGE_FLAG_SEALED_VALUE;
            }
            return this;
        }

        /**
         * Returns to the jar this section belongs to.
         *
         * @return the jar
         */
        public JarSpec end() {
            return jar;
        }
    }

    /**
     * One record of the entry table under construction: physical, versioned alias or synthetic directory.
     */
    private static final class Record {

        private final int jarId;
        private final String name;
        private final int nameLength;
        private final EntrySpec source;
        private int index;
        private int nameRef;
        private int mrVersion;
        private int flags;
        private int physicalIndex = IndexFormat.NO_INDEX;
        private int next = IndexFormat.NO_INDEX;

        private Record(int index, int jarId, String name, EntrySpec source) {
            this.index = index;
            this.jarId = jarId;
            this.name = name;
            this.source = source;
            int length = name.getBytes(StandardCharsets.UTF_8).length;
            if (length > MAX_U16) {
                throw new IllegalStateException("Entry name is longer than " + MAX_U16 + " UTF-8 bytes: '"
                        + name + "'");
            }
            this.nameLength = length;
        }
    }

    /**
     * Orders the versioned aliases of one jar.
     */
    private static final class AliasOrder implements Comparator<Record> {

        @Override
        public int compare(Record left, Record right) {
            int byName = left.name.compareTo(right.name);
            if (byName != 0) {
                return byName;
            }
            return Integer.compare(right.mrVersion, left.mrVersion);
        }
    }

    /**
     * Orders the records that share a logical name: by jar in class path order, and within a jar the
     * versioned aliases in descending version before the base record.
     */
    private static final class ChainOrder implements Comparator<Record> {

        @Override
        public int compare(Record left, Record right) {
            int byJar = Integer.compare(left.jarId, right.jarId);
            if (byJar != 0) {
                return byJar;
            }
            int byVersion = Integer.compare(rank(left), rank(right));
            if (byVersion != 0) {
                return byVersion;
            }
            return Integer.compare(left.index, right.index);
        }

        private static int rank(Record record) {
            return record.mrVersion == 0 ? Integer.MAX_VALUE : -record.mrVersion;
        }
    }

    /**
     * The string table under construction, which deduplicates strings and hands out their byte offsets.
     */
    private static final class Strings {

        private final Map<String, Integer> refs = new LinkedHashMap<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int emptyRef;

        private Strings() {
            // Offset 0 always holds the empty string, so a reference of 0 means "absent".
            bytes.write(0);
            bytes.write(0);
        }

        /**
         * Interns a value that means something even when it is empty.
         *
         * <p>A reference of {@code 0} means "absent", because offset {@code 0} of the table holds the
         * empty string. A manifest attribute that is present but empty is not absent -
         * {@code URLClassLoader} reports such an attribute as {@code ""} - so it is stored as a second,
         * zero length string at an offset of its own, which reads back as {@code ""} and not as
         * {@code null}. The extra two bytes are written only when a build actually has one.</p>
         */
        private int internAttribute(String value) {
            if (value == null) {
                return 0;
            }
            if (value.isEmpty()) {
                if (emptyRef == 0) {
                    emptyRef = bytes.size();
                    bytes.write(0);
                    bytes.write(0);
                }
                return emptyRef;
            }
            return intern(value);
        }

        private int intern(String value) {
            if (value == null || value.isEmpty()) {
                return 0;
            }
            Integer existing = refs.get(value);
            if (existing != null) {
                return existing;
            }
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            if (utf8.length > MAX_U16) {
                throw new IllegalStateException("String is longer than " + MAX_U16 + " UTF-8 bytes: '"
                        + value + "'");
            }
            int ref = bytes.size();
            bytes.write(utf8.length & 0xFF);
            bytes.write((utf8.length >>> 8) & 0xFF);
            bytes.write(utf8, 0, utf8.length);
            refs.put(value, ref);
            return ref;
        }

        private byte[] bytes() {
            return bytes.toByteArray();
        }
    }
}
