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

/**
 * The on-disk layout of {@code MICRONAUT-INF/index.bin}, the index a runner jar carries so that the
 * launcher never has to parse a nested archive's central directory at startup.
 *
 * <p>This class is the single contract shared by the writer (in the packaging library) and the reader
 * (in the launcher). It contains constants only: every member is a compile-time constant, so javac
 * inlines them at every use site and this class is never loaded at runtime.</p>
 *
 * <h2>Conventions</h2>
 * <ul>
 *   <li>All multi-byte integers are <strong>little-endian</strong>.</li>
 *   <li>{@code u8}/{@code u16}/{@code u32}/{@code u64} denote unsigned values; readers widen them
 *       ({@code u32} into a {@code long} where the value may exceed {@link Integer#MAX_VALUE}).</li>
 *   <li>Every section begins at an 8-byte aligned offset relative to the start of the index.</li>
 *   <li>A <em>string reference</em> ({@code strref}) is a {@code u32} byte offset into the string table.
 *       A string is stored as {@code u16 length} followed by that many UTF-8 bytes. Offset {@code 0}
 *       always holds the empty string, so a {@code strref} of {@code 0} means "absent".</li>
 *   <li>A <em>string list reference</em> is a {@code u32} offset into the string table of a
 *       {@code u16 count} followed by {@code count} {@code u32} string references.</li>
 *   <li>{@link #NO_INDEX} is the sentinel for an absent record index.</li>
 * </ul>
 *
 * <h2>Logical names</h2>
 * <p>An entry's <em>logical name</em> is its name relative to the jar that contains it. For nested jars
 * that is the entry name as stored in the nested archive; for jar {@code 0} (the application layer,
 * stored exploded in the outer archive) it is the outer entry name minus the
 * {@code MICRONAUT-INF/classes/} prefix. Lookups are always by logical name.</p>
 *
 * <h2>Multi-release jars</h2>
 * <p>For a jar flagged {@link #JAR_FLAG_MULTI_RELEASE}, every {@code META-INF/versions/N/<path>} entry
 * whose {@code <path>} does <em>not</em> itself start with {@code META-INF/} is indexed a second time
 * under the logical name {@code <path>} as an <em>alias</em> record carrying
 * {@link #ENTRY_FLAG_VERSIONED_ALIAS} and {@code mrVersion = N}. Within one jar, alias records precede
 * the base record in descending version order, so a lookup walks the chain and takes the first record
 * whose {@code mrVersion} is {@code 0} or at most the effective runtime feature version.</p>
 *
 * @since 1.0
 */
public final class IndexFormat {

    // ------------------------------------------------------------------------------------------------
    // Archive-level names
    // ------------------------------------------------------------------------------------------------

    /** Outer archive entry holding the index. It is always the first entry after the manifest. */
    public static final String INDEX_ENTRY_NAME = "MICRONAUT-INF/index.bin";

    /** Outer archive prefix under which the application classes and resources are stored exploded. */
    public static final String CLASSES_PREFIX = "MICRONAUT-INF/classes/";

    /** Outer archive prefix under which the dependencies are stored as intact nested jars. */
    public static final String LIB_PREFIX = "MICRONAUT-INF/lib/";

    /**
     * Outer archive entry, written right after the launcher classes, that records what the build-time class
     * transforms did to the dependency classes: tab-separated lines, the first naming the Runner version. It is
     * present only when a transform changed a class or noted a fallback, it is not indexed, and the launcher
     * never reads it at run time; only the {@code inspect} mode prints it.
     */
    public static final String TRANSFORMS_ENTRY_NAME = "MICRONAUT-INF/transforms.txt";

    /** Manifest attribute carrying the format version, so tooling can detect a runner jar. */
    public static final String ATTR_FORMAT = "Micronaut-Runner-Format";

    /** Manifest attribute carrying the version of the packaging library that produced the archive. */
    public static final String ATTR_VERSION = "Micronaut-Runner-Version";

    /** Manifest attribute carrying the application main class (informational; the index is authoritative). */
    public static final String ATTR_START_CLASS = "Micronaut-Runner-Start-Class";

    /** The {@code Main-Class} of every runner jar. */
    public static final String LAUNCHER_CLASS = "io.micronaut.runner.Launcher";

    /** Package into which the packager generates classes; it is part of the application layer. */
    public static final String GENERATED_PACKAGE = "io.micronaut.runner.generated";

    /** Micronaut's service metadata directory, merged into the outer archive root by the packager. */
    public static final String MICRONAUT_SERVICES_PREFIX = "META-INF/micronaut/";

    // ------------------------------------------------------------------------------------------------
    // Header
    // ------------------------------------------------------------------------------------------------

    /** Magic number: the ASCII bytes {@code M N R I}, read as a little-endian {@code u32}. */
    public static final int MAGIC = 0x49524E4D;

    /** The only format version this release reads or writes. */
    public static final int FORMAT_VERSION = 1;

    /** Size of the fixed header, in bytes. */
    public static final int HEADER_SIZE = 128;

    /** Sentinel for "no such record". */
    public static final int NO_INDEX = 0xFFFFFFFF;

    /** Largest probe sequence a reader accepts before declaring the hash table corrupt. */
    public static final int MAX_PROBE_LIMIT = 64;

    public static final int H_MAGIC = 0;                 // u32
    public static final int H_FORMAT_VERSION = 4;        // u16
    public static final int H_FLAGS = 6;                 // u16
    public static final int H_JAR_COUNT = 8;             // u32
    public static final int H_ENTRY_COUNT = 12;          // u32
    public static final int H_HASH_SLOTS = 16;           // u32, power of two
    public static final int H_MAX_PROBE = 20;            // u32
    public static final int H_OUTER_FILE_LENGTH = 24;    // u64
    public static final int H_JAR_TABLE_OFFSET = 32;     // u64
    public static final int H_ENTRY_TABLE_OFFSET = 40;   // u64
    public static final int H_HASH_TABLE_OFFSET = 48;    // u64
    public static final int H_PACKAGE_TABLE_OFFSET = 56; // u64
    public static final int H_STRING_TABLE_OFFSET = 64;  // u64
    public static final int H_STRING_TABLE_LENGTH = 72;  // u64
    public static final int H_START_CLASS = 80;          // u32 strref, binary class name
    public static final int H_ENTRY_STUB_CLASS = 84;     // u32 strref, 0 when no stub was generated
    public static final int H_LAUNCHER_VERSION = 88;     // u32 strref
    public static final int H_PACKAGE_COUNT = 92;        // u32
    public static final int H_RESERVED = 96;             // u8[32], zero

    /** Header flag: the nested jars were re-packed with all entries STORED. */
    public static final int HEADER_FLAG_NESTED_STORED = 1;

    /** Header flag: the application layer is itself multi-release. */
    public static final int HEADER_FLAG_APP_MULTI_RELEASE = 1 << 1;

    // ------------------------------------------------------------------------------------------------
    // Jar table. Jar 0 is always the application layer (the outer archive itself).
    // ------------------------------------------------------------------------------------------------

    /** Size of one jar record, in bytes. */
    public static final int JAR_RECORD_SIZE = 80;

    /** Index of the application layer in the jar table. */
    public static final int APPLICATION_JAR_ID = 0;

    /** Absolute offset of the nested jar's first byte in the outer file; {@code 0} for jar 0. */
    public static final int J_DATA_OFFSET = 0;           // u64
    /** Length of the nested jar; the outer file length for jar 0. */
    public static final int J_DATA_LENGTH = 8;           // u64
    /** Offset of the outer local file header introducing this nested jar; unused for jar 0. */
    public static final int J_LOCAL_HEADER_OFFSET = 16;  // u64
    /** {@code MICRONAUT-INF/lib/<file>.jar}, or {@code MICRONAUT-INF/classes/} for jar 0. */
    public static final int J_NAME = 24;                 // u32 strref
    /** Maven coordinates such as {@code io.netty:netty-common:4.2.1}, or 0 when unknown. */
    public static final int J_COORDINATES = 28;          // u32 strref
    public static final int J_FIRST_ENTRY = 32;          // u32
    public static final int J_ENTRY_COUNT = 36;          // u32
    public static final int J_FIRST_PACKAGE = 40;        // u32
    public static final int J_PACKAGE_COUNT = 44;        // u32
    public static final int J_FLAGS = 48;                // u16
    public static final int J_RESERVED_1 = 50;           // u16
    public static final int J_SPEC_TITLE = 52;           // u32 strref
    public static final int J_SPEC_VERSION = 56;         // u32 strref
    public static final int J_SPEC_VENDOR = 60;          // u32 strref
    public static final int J_IMPL_TITLE = 64;           // u32 strref
    public static final int J_IMPL_VERSION = 68;         // u32 strref
    public static final int J_IMPL_VENDOR = 72;          // u32 strref
    public static final int J_RESERVED_2 = 76;           // u32

    /** The nested jar declares {@code Multi-Release: true}. */
    public static final int JAR_FLAG_MULTI_RELEASE = 1;
    /** The source jar carried signature files, which the packager removed. */
    public static final int JAR_FLAG_SIGNED_ORIGINAL = 1 << 1;
    /** The jar has a {@code META-INF/MANIFEST.MF} entry. */
    public static final int JAR_FLAG_HAS_MANIFEST = 1 << 2;
    /** This record is the application layer (jar 0). */
    public static final int JAR_FLAG_IS_OUTER = 1 << 3;
    /** The manifest main attributes declare {@code Sealed: true}. */
    public static final int JAR_FLAG_SEALED_BY_DEFAULT = 1 << 4;

    // ------------------------------------------------------------------------------------------------
    // Package table: per-package manifest sections, which override the jar's main attributes.
    // ------------------------------------------------------------------------------------------------

    /** Size of one package record, in bytes. */
    public static final int PACKAGE_RECORD_SIZE = 32;

    /** The package name in dotted form, for example {@code org.example.api}. */
    public static final int P_NAME = 0;                  // u32 strref
    public static final int P_SPEC_TITLE = 4;            // u32 strref, 0 = inherit from the jar
    public static final int P_SPEC_VERSION = 8;          // u32 strref, 0 = inherit
    public static final int P_SPEC_VENDOR = 12;          // u32 strref, 0 = inherit
    public static final int P_IMPL_TITLE = 16;           // u32 strref, 0 = inherit
    public static final int P_IMPL_VERSION = 20;         // u32 strref, 0 = inherit
    public static final int P_IMPL_VENDOR = 24;          // u32 strref, 0 = inherit
    public static final int P_FLAGS = 28;                // u16
    public static final int P_RESERVED = 30;             // u16

    /** The section carries a {@code Sealed} attribute; without this flag the jar default applies. */
    public static final int PACKAGE_FLAG_SEALED_SPECIFIED = 1;
    /** The value of that {@code Sealed} attribute. Only meaningful together with the flag above. */
    public static final int PACKAGE_FLAG_SEALED_VALUE = 1 << 1;

    // ------------------------------------------------------------------------------------------------
    // Entry table. A jar's records are contiguous: physical records first, in the original central
    // directory order, then versioned aliases, then synthetic directories.
    // ------------------------------------------------------------------------------------------------

    /** Size of one entry record, in bytes. */
    public static final int ENTRY_RECORD_SIZE = 48;

    /** {@link #hash(String)} of the logical name. */
    public static final int E_NAME_HASH = 0;             // u32
    /** The logical name. */
    public static final int E_NAME = 4;                  // u32 strref
    /** Absolute offset in the outer file of the entry's data, that is, past its local file header. */
    public static final int E_DATA_OFFSET = 8;           // u64
    public static final int E_COMPRESSED_SIZE = 16;      // u32
    public static final int E_UNCOMPRESSED_SIZE = 20;    // u32
    public static final int E_CRC32 = 24;                // u32
    /** MS-DOS date and time, as stored in the ZIP header. */
    public static final int E_DOS_TIME = 28;             // u32
    /** Next record with the same logical name in classpath order, or {@link #NO_INDEX}. */
    public static final int E_NEXT_SAME_NAME = 32;       // u32
    public static final int E_JAR_ID = 36;               // u16
    /** Length in UTF-8 bytes of the logical name; must agree with the string table. */
    public static final int E_NAME_LENGTH = 38;          // u16
    /** {@link #METHOD_STORED} or {@link #METHOD_DEFLATED}. */
    public static final int E_METHOD = 40;               // u8
    /** {@code 0} for a base entry, otherwise the {@code META-INF/versions/N} feature version. */
    public static final int E_MR_VERSION = 41;           // u8
    /**
     * A mask of {@link #ENTRY_FLAG_DIRECTORY}, {@link #ENTRY_FLAG_SYNTHETIC_DIR},
     * {@link #ENTRY_FLAG_VERSIONED_ALIAS}, {@link #ENTRY_FLAG_PHYSICAL} and
     * {@link #ENTRY_FLAG_DIRECTORY_TWIN}.
     */
    public static final int E_FLAGS = 42;                // u8
    public static final int E_RESERVED = 43;             // u8
    /**
     * For an alias, the physical record it aliases, whose logical name is the real
     * {@code META-INF/versions/N/...} path. For a physical record, its own index. {@link #NO_INDEX}
     * for a synthetic directory.
     */
    public static final int E_PHYSICAL_INDEX = 44;       // u32

    /** The entry is a directory; its logical name ends with {@code '/'}. */
    public static final int ENTRY_FLAG_DIRECTORY = 1;
    /** The directory has no entry of its own in the source archive and was synthesised by the packager. */
    public static final int ENTRY_FLAG_SYNTHETIC_DIR = 1 << 1;
    /** The record is a multi-release alias of another record in the same jar. */
    public static final int ENTRY_FLAG_VERSIONED_ALIAS = 1 << 2;
    /** The record corresponds to an entry that physically exists in the source archive. */
    public static final int ENTRY_FLAG_PHYSICAL = 1 << 3;
    /**
     * Set on every record (physical or versioned alias) whose logical name does not end with '/' when the
     * index also holds that name followed by '/' in any jar, as an explicit, versioned-alias or synthesised
     * directory. A loader considers the directory spelling of a slashless name only when the exact lookup
     * misses or the record it resolved carries this flag.
     */
    public static final int ENTRY_FLAG_DIRECTORY_TWIN = 1 << 4;

    /** ZIP compression method: stored, that is, uncompressed. */
    public static final int METHOD_STORED = 0;
    /** ZIP compression method: deflated. The stream is raw deflate, with no zlib wrapper. */
    public static final int METHOD_DEFLATED = 8;

    // ------------------------------------------------------------------------------------------------
    // ZIP structures the launcher parses in the outer archive
    // ------------------------------------------------------------------------------------------------

    /** Local file header signature, {@code PK\3\4}. */
    public static final int LOCAL_HEADER_SIGNATURE = 0x04034B50;
    /** Central directory file header signature, {@code PK\1\2}. */
    public static final int CENTRAL_HEADER_SIGNATURE = 0x02014B50;
    /** End of central directory signature, {@code PK\5\6}. */
    public static final int END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054B50;
    /** ZIP64 end of central directory record signature, {@code PK\6\6}. */
    public static final int ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06064B50;
    /** ZIP64 end of central directory locator signature, {@code PK\6\7}. */
    public static final int ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE = 0x07064B50;
    /** Header id of the ZIP64 extended information extra field. */
    public static final int ZIP64_EXTRA_FIELD_ID = 0x0001;

    /** Size of an end of central directory record without a comment. */
    public static final int END_OF_CENTRAL_DIRECTORY_SIZE = 22;
    /** Size of a ZIP64 end of central directory locator. */
    public static final int ZIP64_LOCATOR_SIZE = 20;
    /** Largest ZIP archive comment, which bounds how far back the launcher scans for the record. */
    public static final int MAX_COMMENT_SIZE = 0xFFFF;
    /** Value a 32-bit ZIP field carries when the real value lives in a ZIP64 extra field. */
    public static final long ZIP64_MARKER = 0xFFFFFFFFL;

    private IndexFormat() {
    }

    /**
     * The hash of a logical name, as stored in {@link #E_NAME_HASH} and used to pick a hash table slot.
     *
     * <p>It is {@link String#hashCode()}, whose value is specified by the Java language, spread so that
     * the high bits influence the low-order slot index. Writers and readers must agree exactly, so the
     * definition is fixed here and must never change within a format version.</p>
     *
     * @param name the logical name
     * @return the hash
     */
    public static int hash(String name) {
        return spread(name.hashCode());
    }

    /**
     * Mixes the high bits of a {@link String#hashCode()} into the low bits.
     *
     * @param hashCode the raw hash code
     * @return the spread hash
     */
    public static int spread(int hashCode) {
        return hashCode ^ (hashCode >>> 16);
    }
}
