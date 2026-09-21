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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Builds a byte-exact {@code MICRONAUT-INF/index.bin} from a description of jars and entries.
 *
 * <p>This is a second, independent implementation of the index writer: the one the packager uses lives in
 * {@code runner-build} and is written from the same specification by other hands. Having two lets the
 * launcher tests exercise the reader without depending on the packager, and lets the integration stage
 * check that the two writers agree byte for byte.</p>
 *
 * <p>The builder derives everything the format requires from the entry names: multi-release aliases for
 * {@code META-INF/versions/N/<path>} entries of a jar flagged multi-release, synthetic directory records
 * for implied parent directories, the chain of records that share a logical name ordered by classpath
 * position with a jar's aliases in descending version order before its base record, and an open addressed
 * hash table sized at twice the number of distinct names.</p>
 *
 * <p>This is test code and is deliberately written in ordinary Java: the hot path rules that govern
 * {@code io.micronaut.runner} do not apply here.</p>
 */
public final class TestIndexBuilder {

    private final List<Jar> jars = new ArrayList<>();
    private String startClass;
    private String entryStubClass;
    private String launcherVersion;
    private long outerFileLength;
    private int headerFlags;
    private int forcedHashSlots;
    private int forcedMaxProbe = -1;
    private boolean synthesizeDirectories = true;

    /**
     * Sets the application main class recorded in the header.
     *
     * @param value the binary class name, or {@code null}
     * @return this builder
     */
    public TestIndexBuilder startClass(String value) {
        this.startClass = value;
        return this;
    }

    /**
     * Sets the generated entry stub class recorded in the header.
     *
     * @param value the binary class name, or {@code null} when no stub was generated
     * @return this builder
     */
    public TestIndexBuilder entryStubClass(String value) {
        this.entryStubClass = value;
        return this;
    }

    /**
     * Sets the packaging library version recorded in the header.
     *
     * @param value the version, or {@code null}
     * @return this builder
     */
    public TestIndexBuilder launcherVersion(String value) {
        this.launcherVersion = value;
        return this;
    }

    /**
     * Sets the outer file length recorded in the header. {@link TestArchiveBuilder} overwrites it with the
     * real length when the index is embedded in an archive.
     *
     * @param value the length in bytes
     * @return this builder
     */
    public TestIndexBuilder outerFileLength(long value) {
        this.outerFileLength = value;
        return this;
    }

    /**
     * Sets the header flags.
     *
     * @param value a mask of {@code IndexFormat.HEADER_FLAG_*}
     * @return this builder
     */
    public TestIndexBuilder headerFlags(int value) {
        this.headerFlags = value;
        return this;
    }

    /**
     * Forces a hash table size instead of the computed one, so that a test can crowd the table and produce
     * real collisions and long probe sequences.
     *
     * @param value a power of two, or {@code 0} to compute the size
     * @return this builder
     */
    public TestIndexBuilder hashSlots(int value) {
        this.forcedHashSlots = value;
        return this;
    }

    /**
     * Forces the {@code maxProbe} value written to the header, so that a test can produce an index the
     * reader must reject or one whose probe limit cuts a lookup short.
     *
     * @param value the value to write, or {@code -1} to record the real longest probe
     * @return this builder
     */
    public TestIndexBuilder maxProbe(int value) {
        this.forcedMaxProbe = value;
        return this;
    }

    /**
     * Controls whether implied parent directories get synthetic records.
     *
     * @param value whether to synthesise directories
     * @return this builder
     */
    public TestIndexBuilder synthesizeDirectories(boolean value) {
        this.synthesizeDirectories = value;
        return this;
    }

    /**
     * Adds a jar. The first jar added is the application layer, jar {@code 0}.
     *
     * @param name the jar name, {@code MICRONAUT-INF/lib/<file>.jar} or {@code MICRONAUT-INF/classes/}
     * @return the jar, for describing its entries
     */
    public Jar addJar(String name) {
        Jar jar = new Jar(this, name, jars.size());
        jars.add(jar);
        return jar;
    }

    /**
     * Writes the index.
     *
     * @return the complete index bytes, ready to be stored as {@code MICRONAUT-INF/index.bin}
     */
    public byte[] build() {
        List<Record> records = records();
        Map<String, List<Record>> chains = chains(records);
        link(chains);
        int slots = slots(chains.size());
        int[] table = new int[slots];
        int probe = fill(table, chains, slots);
        Strings strings = new Strings();

        int packageCount = 0;
        for (Jar jar : jars) {
            packageCount += jar.packages.size();
        }
        int jarTable = IndexFormat.HEADER_SIZE;
        int packageTable = align(jarTable + jars.size() * IndexFormat.JAR_RECORD_SIZE);
        int entryTable = align(packageTable + packageCount * IndexFormat.PACKAGE_RECORD_SIZE);
        int hashTable = align(entryTable + records.size() * IndexFormat.ENTRY_RECORD_SIZE);
        int stringTable = align(hashTable + slots * 4);
        byte[] fixed = new byte[stringTable];
        ByteBuffer out = ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN);

        out.putInt(IndexFormat.H_MAGIC, IndexFormat.MAGIC);
        out.putShort(IndexFormat.H_FORMAT_VERSION, (short) IndexFormat.FORMAT_VERSION);
        out.putShort(IndexFormat.H_FLAGS, (short) headerFlags);
        out.putInt(IndexFormat.H_JAR_COUNT, jars.size());
        out.putInt(IndexFormat.H_ENTRY_COUNT, records.size());
        out.putInt(IndexFormat.H_HASH_SLOTS, slots);
        out.putInt(IndexFormat.H_MAX_PROBE, forcedMaxProbe >= 0 ? forcedMaxProbe : probe);
        out.putLong(IndexFormat.H_OUTER_FILE_LENGTH, outerFileLength);
        out.putLong(IndexFormat.H_JAR_TABLE_OFFSET, jarTable);
        out.putLong(IndexFormat.H_ENTRY_TABLE_OFFSET, entryTable);
        out.putLong(IndexFormat.H_HASH_TABLE_OFFSET, hashTable);
        out.putLong(IndexFormat.H_PACKAGE_TABLE_OFFSET, packageTable);
        out.putLong(IndexFormat.H_STRING_TABLE_OFFSET, stringTable);
        out.putInt(IndexFormat.H_START_CLASS, strings.intern(startClass));
        out.putInt(IndexFormat.H_ENTRY_STUB_CLASS, strings.intern(entryStubClass));
        out.putInt(IndexFormat.H_LAUNCHER_VERSION, strings.intern(launcherVersion));
        out.putInt(IndexFormat.H_PACKAGE_COUNT, packageCount);

        int packageIndex = 0;
        for (Jar jar : jars) {
            int at = jarTable + jar.id * IndexFormat.JAR_RECORD_SIZE;
            out.putLong(at + IndexFormat.J_DATA_OFFSET, jar.dataOffset);
            out.putLong(at + IndexFormat.J_DATA_LENGTH, jar.dataLength);
            out.putLong(at + IndexFormat.J_LOCAL_HEADER_OFFSET, jar.localHeaderOffset);
            out.putInt(at + IndexFormat.J_NAME, strings.intern(jar.name));
            out.putInt(at + IndexFormat.J_COORDINATES, strings.intern(jar.coordinates));
            out.putInt(at + IndexFormat.J_FIRST_ENTRY, jar.firstEntry);
            out.putInt(at + IndexFormat.J_ENTRY_COUNT, jar.entryCount);
            out.putInt(at + IndexFormat.J_FIRST_PACKAGE, packageIndex);
            out.putInt(at + IndexFormat.J_PACKAGE_COUNT, jar.packages.size());
            out.putShort(at + IndexFormat.J_FLAGS, (short) jar.flags);
            out.putInt(at + IndexFormat.J_SPEC_TITLE, strings.intern(jar.specTitle));
            out.putInt(at + IndexFormat.J_SPEC_VERSION, strings.intern(jar.specVersion));
            out.putInt(at + IndexFormat.J_SPEC_VENDOR, strings.intern(jar.specVendor));
            out.putInt(at + IndexFormat.J_IMPL_TITLE, strings.intern(jar.implTitle));
            out.putInt(at + IndexFormat.J_IMPL_VERSION, strings.intern(jar.implVersion));
            out.putInt(at + IndexFormat.J_IMPL_VENDOR, strings.intern(jar.implVendor));
            for (PackageSection section : jar.packages) {
                int p = packageTable + packageIndex * IndexFormat.PACKAGE_RECORD_SIZE;
                out.putInt(p + IndexFormat.P_NAME, strings.intern(section.name));
                out.putInt(p + IndexFormat.P_SPEC_TITLE, strings.intern(section.specTitle));
                out.putInt(p + IndexFormat.P_SPEC_VERSION, strings.intern(section.specVersion));
                out.putInt(p + IndexFormat.P_SPEC_VENDOR, strings.intern(section.specVendor));
                out.putInt(p + IndexFormat.P_IMPL_TITLE, strings.intern(section.implTitle));
                out.putInt(p + IndexFormat.P_IMPL_VERSION, strings.intern(section.implVersion));
                out.putInt(p + IndexFormat.P_IMPL_VENDOR, strings.intern(section.implVendor));
                out.putShort(p + IndexFormat.P_FLAGS, (short) section.flags);
                packageIndex++;
            }
        }

        for (Record record : records) {
            int at = entryTable + record.index * IndexFormat.ENTRY_RECORD_SIZE;
            byte[] utf8 = record.name.getBytes(StandardCharsets.UTF_8);
            out.putInt(at + IndexFormat.E_NAME_HASH, IndexFormat.hash(record.name));
            out.putInt(at + IndexFormat.E_NAME, strings.intern(record.name));
            out.putLong(at + IndexFormat.E_DATA_OFFSET, record.dataOffset);
            out.putInt(at + IndexFormat.E_COMPRESSED_SIZE, (int) record.compressedSize);
            out.putInt(at + IndexFormat.E_UNCOMPRESSED_SIZE, (int) record.uncompressedSize);
            out.putInt(at + IndexFormat.E_CRC32, (int) record.crc32);
            out.putInt(at + IndexFormat.E_DOS_TIME, (int) record.dosTime);
            out.putInt(at + IndexFormat.E_NEXT_SAME_NAME, record.next);
            out.putShort(at + IndexFormat.E_JAR_ID, (short) record.jarId);
            out.putShort(at + IndexFormat.E_NAME_LENGTH, (short) utf8.length);
            out.put(at + IndexFormat.E_METHOD, (byte) record.method);
            out.put(at + IndexFormat.E_MR_VERSION, (byte) record.mrVersion);
            out.put(at + IndexFormat.E_FLAGS, (byte) record.flags);
            out.putInt(at + IndexFormat.E_PHYSICAL_INDEX, record.physicalIndex);
        }

        for (int i = 0; i < slots; i++) {
            out.putInt(hashTable + i * 4, table[i]);
        }

        byte[] tail = strings.bytes();
        byte[] result = new byte[stringTable + tail.length];
        System.arraycopy(fixed, 0, result, 0, fixed.length);
        System.arraycopy(tail, 0, result, stringTable, tail.length);
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(IndexFormat.H_STRING_TABLE_LENGTH, tail.length);
        return result;
    }

    private static int align(int value) {
        return (value + 7) & ~7;
    }

    /**
     * The feature version of a {@code META-INF/versions/N/...} name, or {@code 0} when the name is not a
     * versioned entry that can be aliased.
     *
     * <p>{@code N} below 9 is not a multi-release version at all (JEP 238 starts at 9, and the launcher's
     * base version is 8, so an alias of version 5 would shadow the base entry on every runtime), and
     * {@code N} above 255 does not fit {@link IndexFormat#E_MR_VERSION}, which is a {@code u8}; truncating
     * it would silently alias the wrong version. Both are therefore left as ordinary physical entries, the
     * way {@code java.util.jar.JarFile} leaves them.</p>
     */
    private static int versionOf(String name) {
        String prefix = "META-INF/versions/";
        if (!name.startsWith(prefix)) {
            return 0;
        }
        int slash = name.indexOf('/', prefix.length());
        if (slash < 0 || slash == prefix.length()) {
            return 0;
        }
        int version = 0;
        for (int i = prefix.length(); i < slash; i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
            version = version * 10 + (c - '0');
            if (version > 0xFF) {
                return 0;
            }
        }
        return version < 9 ? 0 : version;
    }

    private static String pathOf(String name) {
        return name.substring(name.indexOf('/', "META-INF/versions/".length()) + 1);
    }

    private List<Record> records() {
        List<Record> records = new ArrayList<>();
        for (Jar jar : jars) {
            jar.firstEntry = records.size();
            List<Record> physical = new ArrayList<>();
            for (EntrySpec spec : jar.entries) {
                Record record = new Record(records.size(), jar.id, spec.name);
                record.dataOffset = spec.dataOffset;
                record.compressedSize = spec.compressedSize;
                record.uncompressedSize = spec.uncompressedSize;
                record.crc32 = spec.crc32;
                record.dosTime = spec.dosTime;
                record.method = spec.method;
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
                    String path = version == 0 ? null : pathOf(record.name);
                    if (version == 0 || path.isEmpty() || path.startsWith("META-INF/")) {
                        continue;
                    }
                    Record alias = new Record(0, jar.id, path);
                    alias.dataOffset = record.dataOffset;
                    alias.compressedSize = record.compressedSize;
                    alias.uncompressedSize = record.uncompressedSize;
                    alias.crc32 = record.crc32;
                    alias.dosTime = record.dosTime;
                    alias.method = record.method;
                    alias.mrVersion = version;
                    alias.flags = IndexFormat.ENTRY_FLAG_VERSIONED_ALIAS
                            | (path.endsWith("/") ? IndexFormat.ENTRY_FLAG_DIRECTORY : 0);
                    alias.physicalIndex = record.index;
                    aliases.add(alias);
                }
                aliases.sort(Comparator.comparing((Record r) -> r.name)
                        .thenComparingInt(r -> -r.mrVersion));
                for (Record alias : aliases) {
                    alias.index = records.size();
                    records.add(alias);
                }
            }
            if (synthesizeDirectories) {
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
                    Record record = new Record(records.size(), jar.id, name);
                    record.flags = IndexFormat.ENTRY_FLAG_DIRECTORY | IndexFormat.ENTRY_FLAG_SYNTHETIC_DIR;
                    record.physicalIndex = IndexFormat.NO_INDEX;
                    records.add(record);
                }
            }
            jar.entryCount = records.size() - jar.firstEntry;
        }
        return records;
    }

    private Map<String, List<Record>> chains(List<Record> records) {
        Map<String, List<Record>> chains = new LinkedHashMap<>();
        for (Record record : records) {
            chains.computeIfAbsent(record.name, key -> new ArrayList<>()).add(record);
        }
        Comparator<Record> order = Comparator.<Record>comparingInt(r -> r.jarId)
                .thenComparingInt(r -> r.mrVersion == 0 ? Integer.MAX_VALUE : -r.mrVersion)
                .thenComparingInt(r -> r.index);
        for (List<Record> chain : chains.values()) {
            chain.sort(order);
        }
        return chains;
    }

    private void link(Map<String, List<Record>> chains) {
        for (List<Record> chain : chains.values()) {
            for (int i = 0; i < chain.size(); i++) {
                chain.get(i).next = i + 1 < chain.size()
                        ? chain.get(i + 1).index : IndexFormat.NO_INDEX;
            }
        }
    }

    private int slots(int distinctNames) {
        if (forcedHashSlots > 0) {
            return forcedHashSlots;
        }
        int slots = 2;
        while (slots < distinctNames * 2) {
            slots *= 2;
        }
        return slots;
    }

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
     * One jar of the index under construction.
     */
    public static final class Jar {

        private final TestIndexBuilder owner;
        private final String name;
        private final int id;
        private final List<EntrySpec> entries = new ArrayList<>();
        private final List<PackageSection> packages = new ArrayList<>();
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

        private Jar(TestIndexBuilder owner, String name, int id) {
            this.owner = owner;
            this.name = name;
            this.id = id;
            if (id == IndexFormat.APPLICATION_JAR_ID) {
                this.flags = IndexFormat.JAR_FLAG_IS_OUTER;
            }
        }

        /**
         * Sets the Maven coordinates of the jar.
         *
         * @param value the coordinates
         * @return this jar
         */
        public Jar coordinates(String value) {
            this.coordinates = value;
            return this;
        }

        /**
         * Sets where the nested jar sits in the outer archive.
         *
         * @param offset            the absolute offset of the nested jar's first byte
         * @param dataLength        the length of the nested jar
         * @param localHeaderOffset the offset of the outer local file header
         * @return this jar
         */
        public Jar location(long offset, long dataLength, long localHeaderOffset) {
            this.dataOffset = offset;
            this.dataLength = dataLength;
            this.localHeaderOffset = localHeaderOffset;
            return this;
        }

        /**
         * Sets the jar flags, replacing the default.
         *
         * @param value a mask of {@code IndexFormat.JAR_FLAG_*}
         * @return this jar
         */
        public Jar flags(int value) {
            this.flags = value;
            return this;
        }

        /**
         * Marks the jar multi-release, which makes the builder emit versioned aliases for its
         * {@code META-INF/versions/N} entries.
         *
         * @return this jar
         */
        public Jar multiRelease() {
            this.flags |= IndexFormat.JAR_FLAG_MULTI_RELEASE;
            return this;
        }

        /**
         * Sets the six manifest main attributes.
         *
         * @param specificationTitle   the {@code Specification-Title}
         * @param specificationVersion the {@code Specification-Version}
         * @param specificationVendor  the {@code Specification-Vendor}
         * @param implementationTitle   the {@code Implementation-Title}
         * @param implementationVersion the {@code Implementation-Version}
         * @param implementationVendor  the {@code Implementation-Vendor}
         * @return this jar
         */
        public Jar manifest(String specificationTitle, String specificationVersion,
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
         * Adds an entry that physically exists in this jar.
         *
         * @param entryName the entry name as stored in the jar, which is also its logical name
         * @return the entry, for setting its offsets and sizes
         */
        public EntrySpec addEntry(String entryName) {
            EntrySpec spec = new EntrySpec(this, entryName);
            entries.add(spec);
            return spec;
        }

        /**
         * Adds a package override section.
         *
         * @param packageName the package name in dotted form
         * @return the section
         */
        public PackageSection addPackage(String packageName) {
            PackageSection section = new PackageSection(this, packageName);
            packages.add(section);
            return section;
        }

        /**
         * Returns to the index builder.
         *
         * @return the builder this jar belongs to
         */
        public TestIndexBuilder end() {
            return owner;
        }
    }

    /**
     * One physical entry of a jar.
     */
    public static final class EntrySpec {

        private final Jar jar;
        private final String name;
        private long dataOffset;
        private long compressedSize;
        private long uncompressedSize;
        private long crc32;
        private long dosTime;
        private int method = IndexFormat.METHOD_STORED;
        private int extraFlags;

        private EntrySpec(Jar jar, String name) {
            this.jar = jar;
            this.name = name;
        }

        /**
         * Sets where the entry data sits in the outer archive and how large it is.
         *
         * @param offset       the absolute offset of the entry data
         * @param compressed   the number of stored bytes
         * @param uncompressed the number of bytes the entry expands to
         * @return this entry
         */
        public EntrySpec data(long offset, long compressed, long uncompressed) {
            this.dataOffset = offset;
            this.compressedSize = compressed;
            this.uncompressedSize = uncompressed;
            return this;
        }

        /**
         * Sets the compression method.
         *
         * @param value {@link IndexFormat#METHOD_STORED} or {@link IndexFormat#METHOD_DEFLATED}
         * @return this entry
         */
        public EntrySpec method(int value) {
            this.method = value;
            return this;
        }

        /**
         * Sets the CRC-32 of the uncompressed content.
         *
         * @param value the checksum
         * @return this entry
         */
        public EntrySpec crc32(long value) {
            this.crc32 = value;
            return this;
        }

        /**
         * Sets the MS-DOS date and time word.
         *
         * @param value the packed date and time
         * @return this entry
         */
        public EntrySpec dosTime(long value) {
            this.dosTime = value;
            return this;
        }

        /**
         * Adds flags to the record, on top of the ones the builder derives from the name.
         *
         * @param value a mask of {@code IndexFormat.ENTRY_FLAG_*}
         * @return this entry
         */
        public EntrySpec flags(int value) {
            this.extraFlags |= value;
            return this;
        }

        /**
         * Returns to the jar.
         *
         * @return the jar this entry belongs to
         */
        public Jar end() {
            return jar;
        }
    }

    /**
     * One package override section of a jar's manifest.
     */
    public static final class PackageSection {

        private final Jar jar;
        private final String name;
        private String specTitle;
        private String specVersion;
        private String specVendor;
        private String implTitle;
        private String implVersion;
        private String implVendor;
        private int flags;

        private PackageSection(Jar jar, String name) {
            this.jar = jar;
            this.name = name;
        }

        /**
         * Sets the six attributes of the section; {@code null} means "inherit from the jar".
         *
         * @param specificationTitle   the {@code Specification-Title}
         * @param specificationVersion the {@code Specification-Version}
         * @param specificationVendor  the {@code Specification-Vendor}
         * @param implementationTitle   the {@code Implementation-Title}
         * @param implementationVersion the {@code Implementation-Version}
         * @param implementationVendor  the {@code Implementation-Vendor}
         * @return this section
         */
        public PackageSection attributes(String specificationTitle, String specificationVersion,
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
         * Records a {@code Sealed} attribute on the section.
         *
         * @param sealed the value of the attribute
         * @return this section
         */
        public PackageSection sealed(boolean sealed) {
            this.flags |= IndexFormat.PACKAGE_FLAG_SEALED_SPECIFIED;
            if (sealed) {
                this.flags |= IndexFormat.PACKAGE_FLAG_SEALED_VALUE;
            }
            return this;
        }

        /**
         * Returns to the jar.
         *
         * @return the jar this section belongs to
         */
        public Jar end() {
            return jar;
        }
    }

    /**
     * One entry record of the index under construction, physical, alias or synthetic.
     */
    private static final class Record {

        private final int jarId;
        private final String name;
        private int index;
        private long dataOffset;
        private long compressedSize;
        private long uncompressedSize;
        private long crc32;
        private long dosTime;
        private int method = IndexFormat.METHOD_STORED;
        private int mrVersion;
        private int flags;
        private int physicalIndex = IndexFormat.NO_INDEX;
        private int next = IndexFormat.NO_INDEX;

        private Record(int index, int jarId, String name) {
            this.index = index;
            this.jarId = jarId;
            this.name = name;
        }
    }

    /**
     * The string table under construction, which deduplicates and hands out byte offsets.
     */
    private static final class Strings {

        private final Map<String, Integer> refs = new LinkedHashMap<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        private Strings() {
            bytes.write(0);
            bytes.write(0);
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
            if (utf8.length > 0xFFFF) {
                throw new IllegalArgumentException("String too long: " + value);
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
