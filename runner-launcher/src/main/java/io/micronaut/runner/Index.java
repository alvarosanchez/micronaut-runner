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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.zip.CRC32;

/**
 * The reader of {@code MICRONAUT-INF/index.bin}, the table of contents a runner jar carries so that the
 * launcher never parses a nested archive at startup.
 *
 * <p>The index is used <em>in place</em>: the bytes stay in the memory mapping and every accessor is a
 * bounds-checked read at a computed offset. Records are addressed by plain {@code int} indexes, never by
 * objects, because materialising one object per entry would cost more than everything else the launcher
 * does; a Micronaut application indexes on the order of fifteen thousand entries.</p>
 *
 * <h2>Validation</h2>
 * <p>{@link #open(ArchiveSource, long, long)} checks everything that can be checked in constant time or in
 * time proportional to the (small) jar table: magic, format version, section bounds, a power of two hash
 * table, a sane probe limit, and that the recorded outer file length still matches the file. Anything that
 * would cost time proportional to the entry table, such as verifying every string reference, is checked
 * when the record is touched instead, so that opening the index faults in only the pages it needs.
 * Failures throw {@link IllegalStateException} carrying {@link #REBUILD_MESSAGE}, because in practice they
 * all mean the same thing: the jar was edited, truncated or rebuilt after it was packaged. The source is
 * assumed to remain immutable after it opens. The length comparison uses the source's cached opening length,
 * and each local-header check is cached after its first success; neither is ongoing mutation monitoring.</p>
 *
 * <h2>Thread safety</h2>
 * <p>Accessors use absolute {@link ByteBuffer} reads, which do not touch the buffer position, so an instance
 * is safe for concurrent use by any number of class-loading threads. Package-name lookups lazily publish
 * immutable, bounded per-jar hash tables through atomic references. {@link #validateJar(int)} writes a
 * {@code boolean} into an array; the write is idempotent, so a race only means the check runs twice.</p>
 *
 * @since 1.0
 */
public final class Index {

    /**
     * The message every validation failure carries. Every corruption the reader can detect has the same
     * cause and the same fix, so the message says what to do rather than what byte was wrong; the detail
     * follows in parentheses for bug reports.
     */
    public static final String REBUILD_MESSAGE =
            "This runner jar was modified after it was packaged and must be rebuilt";

    /**
     * The multi-release feature version that means "base entries only". {@code META-INF/versions/N}
     * directories start at 9, so any value below 9 selects the base entry of every chain.
     */
    public static final int BASE_VERSION = 8;

    /** UTF-8 bytes of {@code .class}, compared against stored names without building the name. */
    private static final byte[] CLASS_SUFFIX = {'.', 'c', 'l', 'a', 's', 's'};

    /** {@code ".class".hashCode()}. */
    private static final int CLASS_SUFFIX_HASH = 1411683850;

    /** {@code 31^6}: {@code hashCode(a + b) == hashCode(a) * 31^length(b) + hashCode(b)}. */
    private static final int CLASS_SUFFIX_HASH_FACTOR = 887503681;

    /** Largest value {@code jarId} can take, because the entry record stores it as a {@code u16}. */
    private static final int MAX_JAR_COUNT = 0xFFFF;

    private final ArchiveSource source;
    private final ByteBuffer buffer;
    private final int length;
    private final int flags;
    private final int jarCount;
    private final int entryCount;
    private final int packageCount;
    private final int hashSlots;
    private final int hashMask;
    private final int maxProbe;
    private final long outerFileLength;
    private final int jarTableOffset;
    private final int entryTableOffset;
    private final int hashTableOffset;
    private final int packageTableOffset;
    private final int stringTableOffset;
    private final int stringTableLength;
    private final boolean[] validatedJars;
    private volatile AtomicReferenceArray<PackageLookup> packageLookups;

    private Index(ArchiveSource source, ByteBuffer buffer) {
        this.source = source;
        this.buffer = buffer;
        this.length = buffer.limit();
        if (length < IndexFormat.HEADER_SIZE) {
            throw stale("the index is " + length + " bytes, shorter than its header");
        }
        if (buffer.getInt(IndexFormat.H_MAGIC) != IndexFormat.MAGIC) {
            throw stale("no index magic at the start of " + IndexFormat.INDEX_ENTRY_NAME);
        }
        int version = buffer.getShort(IndexFormat.H_FORMAT_VERSION) & 0xFFFF;
        if (version != IndexFormat.FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported runner jar format version " + version
                    + "; this launcher reads format version " + IndexFormat.FORMAT_VERSION
                    + ". Rebuild the application with a matching version of the packaging plugin.");
        }
        this.flags = buffer.getShort(IndexFormat.H_FLAGS) & 0xFFFF;
        long jars = u32(IndexFormat.H_JAR_COUNT);
        long entries = u32(IndexFormat.H_ENTRY_COUNT);
        long slots = u32(IndexFormat.H_HASH_SLOTS);
        long probe = u32(IndexFormat.H_MAX_PROBE);
        long packages = u32(IndexFormat.H_PACKAGE_COUNT);
        if (jars > MAX_JAR_COUNT) {
            throw stale("the index declares " + jars + " jars, more than the format allows");
        }
        if (probe > IndexFormat.MAX_PROBE_LIMIT) {
            throw stale("the hash table declares a probe length of " + probe + ", more than the limit of "
                    + IndexFormat.MAX_PROBE_LIMIT);
        }
        if (slots == 0 || (slots & (slots - 1)) != 0 || slots > Integer.MAX_VALUE) {
            throw stale("the hash table has " + slots + " slots, which is not a power of two");
        }
        this.jarCount = (int) jars;
        this.entryCount = (int) entries;
        this.packageCount = (int) packages;
        this.hashSlots = (int) slots;
        this.hashMask = this.hashSlots - 1;
        this.maxProbe = (int) probe;
        this.outerFileLength = u64(IndexFormat.H_OUTER_FILE_LENGTH);
        this.jarTableOffset = section(IndexFormat.H_JAR_TABLE_OFFSET, jars,
                IndexFormat.JAR_RECORD_SIZE, "jar table");
        this.entryTableOffset = section(IndexFormat.H_ENTRY_TABLE_OFFSET, entries,
                IndexFormat.ENTRY_RECORD_SIZE, "entry table");
        this.hashTableOffset = section(IndexFormat.H_HASH_TABLE_OFFSET, slots, 4, "hash table");
        this.packageTableOffset = section(IndexFormat.H_PACKAGE_TABLE_OFFSET, packages,
                IndexFormat.PACKAGE_RECORD_SIZE, "package table");
        long stringLength = u64(IndexFormat.H_STRING_TABLE_LENGTH);
        this.stringTableOffset = section(IndexFormat.H_STRING_TABLE_OFFSET, stringLength, 1, "string table");
        this.stringTableLength = (int) stringLength;
        if (outerFileLength != source.length()) {
            throw stale("the index records an archive of " + outerFileLength + " bytes but the file is "
                    + source.length() + " bytes");
        }
        checkStringRef(buffer.getInt(IndexFormat.H_START_CLASS), "start class");
        checkStringRef(buffer.getInt(IndexFormat.H_ENTRY_STUB_CLASS), "entry stub class");
        checkStringRef(buffer.getInt(IndexFormat.H_LAUNCHER_VERSION), "launcher version");
        this.validatedJars = new boolean[this.jarCount];
        checkJarTable();
    }

    /**
     * Opens the index of an already open archive, locating it with {@link ArchiveSource#openIndex()}.
     *
     * @param source the open archive
     * @return the index reader
     * @throws IOException           if the archive carries no readable index entry
     * @throws IllegalStateException if the index is not valid for this launcher
     */
    public static Index open(ArchiveSource source) throws IOException {
        long[] location = source.openIndex();
        return open(source, location[0], location[1]);
    }

    /**
     * Opens the index stored at a known location in an archive.
     *
     * @param source      the open archive
     * @param indexOffset absolute offset of the index bytes in the archive
     * @param indexLength length of the index in bytes
     * @return the index reader
     * @throws IOException           if the index bytes cannot be read
     * @throws IllegalStateException if the index is not valid for this launcher
     */
    public static Index open(ArchiveSource source, long indexOffset, long indexLength) throws IOException {
        if (indexLength < IndexFormat.HEADER_SIZE || indexLength > ArchiveSource.MAX_SLICE_LENGTH) {
            throw stale("the index entry is " + indexLength + " bytes long");
        }
        ByteBuffer buffer = source.slice(indexOffset, (int) indexLength);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return new Index(source, buffer);
    }

    /**
     * The multi-release feature version to select entries with, derived from this runtime the way
     * {@code JarFile.runtimeVersion()} derives it but without loading {@code java.util.jar} or the regular
     * expression engine onto the startup path: {@code jdk.util.jar.enableMultiRelease=false} disables
     * versioned entries altogether, {@code jdk.util.jar.version} overrides the runtime version and is
     * clamped to it, and anything below 9 means base entries only.
     *
     * @return a feature version, at least {@link #BASE_VERSION}
     */
    public static int effectiveMultiReleaseVersion() {
        if ("false".equals(System.getProperty("jdk.util.jar.enableMultiRelease"))) {
            return BASE_VERSION;
        }
        int runtime = featureVersion(System.getProperty("java.specification.version"), BASE_VERSION);
        int requested = featureVersion(System.getProperty("jdk.util.jar.version"), -1);
        if (requested < 0 || requested > runtime) {
            return runtime;
        }
        if (requested < BASE_VERSION) {
            return BASE_VERSION;
        }
        return requested;
    }

    /**
     * The archive the index describes.
     *
     * @return the source passed to {@link #open(ArchiveSource, long, long)}
     */
    public ArchiveSource source() {
        return source;
    }

    /**
     * The header flags.
     *
     * @return a mask of {@code IndexFormat.HEADER_FLAG_*}
     */
    public int flags() {
        return flags;
    }

    /**
     * Whether the nested jars were re-packed with every entry STORED, which lets the class loader define
     * classes straight from the mapping.
     *
     * @return {@code true} when {@code IndexFormat.HEADER_FLAG_NESTED_STORED} is set
     */
    public boolean nestedStored() {
        return (flags & IndexFormat.HEADER_FLAG_NESTED_STORED) != 0;
    }

    /**
     * Whether the application layer, jar {@code 0}, is itself multi-release.
     *
     * @return {@code true} when {@code IndexFormat.HEADER_FLAG_APP_MULTI_RELEASE} is set
     */
    public boolean applicationMultiRelease() {
        return (flags & IndexFormat.HEADER_FLAG_APP_MULTI_RELEASE) != 0;
    }

    /**
     * The number of jars, including the application layer at index {@code 0}.
     *
     * @return the jar count
     */
    public int jarCount() {
        return jarCount;
    }

    /**
     * The number of entry records: physical entries, versioned aliases and synthetic directories.
     *
     * @return the entry record count
     */
    public int entryCount() {
        return entryCount;
    }

    /**
     * The number of package override records.
     *
     * @return the package record count
     */
    public int packageCount() {
        return packageCount;
    }

    /**
     * The size of the hash table, always a power of two.
     *
     * @return the number of slots
     */
    public int hashSlots() {
        return hashSlots;
    }

    /**
     * The longest probe sequence the packager needed, which bounds every lookup.
     *
     * @return the probe limit recorded in the header
     */
    public int maxProbe() {
        return maxProbe;
    }

    /**
     * The length the outer archive had when it was packaged.
     *
     * @return the recorded file length, which equals the real one or the index would not have opened
     */
    public long outerFileLength() {
        return outerFileLength;
    }

    /**
     * The application main class, in binary form.
     *
     * @return the class name, or {@code null} when the packager recorded none
     */
    public String startClass() {
        return string(buffer.getInt(IndexFormat.H_START_CLASS));
    }

    /**
     * The generated {@link Entry} implementation that starts the application without reflection.
     *
     * @return the class name, or {@code null} when no stub was generated and the launcher must fall back
     *         to reflection
     */
    public String entryStubClass() {
        return string(buffer.getInt(IndexFormat.H_ENTRY_STUB_CLASS));
    }

    /**
     * The version of the packaging library that wrote this archive.
     *
     * @return the version string, or {@code null} when it was not recorded
     */
    public String launcherVersion() {
        return string(buffer.getInt(IndexFormat.H_LAUNCHER_VERSION));
    }

    /**
     * The name of a jar: {@code MICRONAUT-INF/lib/<file>.jar}, or {@code MICRONAUT-INF/classes/} for the
     * application layer.
     *
     * @param jarId the jar index
     * @return the name
     */
    public String jarName(int jarId) {
        return string(buffer.getInt(jarOffset(jarId) + IndexFormat.J_NAME));
    }

    /**
     * The Maven coordinates of a jar, such as {@code io.netty:netty-common:4.2.1}.
     *
     * @param jarId the jar index
     * @return the coordinates, or {@code null} when the build did not know them
     */
    public String jarCoordinates(int jarId) {
        return string(buffer.getInt(jarOffset(jarId) + IndexFormat.J_COORDINATES));
    }

    /**
     * The absolute offset of a nested jar's first byte in the outer archive; {@code 0} for the application
     * layer, which is not a nested jar.
     *
     * @param jarId the jar index
     * @return the offset
     */
    public long jarDataOffset(int jarId) {
        return u64(jarOffset(jarId) + IndexFormat.J_DATA_OFFSET);
    }

    /**
     * The length of a nested jar; the outer file length for the application layer.
     *
     * @param jarId the jar index
     * @return the length in bytes
     */
    public long jarDataLength(int jarId) {
        return u64(jarOffset(jarId) + IndexFormat.J_DATA_LENGTH);
    }

    /**
     * The offset of the outer local file header that introduces a nested jar. {@link #validateJar(int)}
     * reads its signature as a staleness check.
     *
     * @param jarId the jar index
     * @return the offset, or {@code 0} for the application layer
     */
    public long jarLocalHeaderOffset(int jarId) {
        return u64(jarOffset(jarId) + IndexFormat.J_LOCAL_HEADER_OFFSET);
    }

    /**
     * The flags of a jar.
     *
     * @param jarId the jar index
     * @return a mask of {@code IndexFormat.JAR_FLAG_*}
     */
    public int jarFlags(int jarId) {
        return buffer.getShort(jarOffset(jarId) + IndexFormat.J_FLAGS) & 0xFFFF;
    }

    /**
     * Whether a jar declares {@code Multi-Release: true} and therefore carries versioned aliases.
     *
     * @param jarId the jar index
     * @return {@code true} when the jar is multi-release
     */
    public boolean jarMultiRelease(int jarId) {
        return (jarFlags(jarId) & IndexFormat.JAR_FLAG_MULTI_RELEASE) != 0;
    }

    /**
     * Whether a jar's manifest main attributes declare {@code Sealed: true}, which makes every package
     * sealed unless a package section says otherwise.
     *
     * @param jarId the jar index
     * @return {@code true} when the jar is sealed by default
     */
    public boolean jarSealedByDefault(int jarId) {
        return (jarFlags(jarId) & IndexFormat.JAR_FLAG_SEALED_BY_DEFAULT) != 0;
    }

    /**
     * The index of a jar's first entry record. A jar's records are contiguous: physical entries in the
     * original central directory order, then versioned aliases, then synthetic directories.
     *
     * @param jarId the jar index
     * @return the first record index
     */
    public int jarFirstEntry(int jarId) {
        return buffer.getInt(jarOffset(jarId) + IndexFormat.J_FIRST_ENTRY);
    }

    /**
     * The number of entry records belonging to a jar.
     *
     * @param jarId the jar index
     * @return the record count
     */
    public int jarEntryCount(int jarId) {
        return buffer.getInt(jarOffset(jarId) + IndexFormat.J_ENTRY_COUNT);
    }

    /**
     * The index of a jar's first package override record.
     *
     * @param jarId the jar index
     * @return the first package record index, meaningful only when {@link #jarPackageCount(int)} is
     *         positive
     */
    public int jarFirstPackage(int jarId) {
        return buffer.getInt(jarOffset(jarId) + IndexFormat.J_FIRST_PACKAGE);
    }

    /**
     * The number of package override records belonging to a jar.
     *
     * @param jarId the jar index
     * @return the package record count
     */
    public int jarPackageCount(int jarId) {
        return buffer.getInt(jarOffset(jarId) + IndexFormat.J_PACKAGE_COUNT);
    }

    /**
     * The {@code Specification-Title} main attribute of a jar's manifest.
     *
     * @param jarId the jar index
     * @return the attribute, or {@code null} when the manifest does not declare it
     */
    public String jarSpecTitle(int jarId) {
        return string(buffer.getInt(jarOffset(jarId) + IndexFormat.J_SPEC_TITLE));
    }

    /**
     * The {@code Specification-Version} main attribute of a jar's manifest.
     *
     * @param jarId the jar index
     * @return the attribute, or {@code null} when the manifest does not declare it
     */
    public String jarSpecVersion(int jarId) {
        return string(buffer.getInt(jarOffset(jarId) + IndexFormat.J_SPEC_VERSION));
    }

    /**
     * The {@code Specification-Vendor} main attribute of a jar's manifest.
     *
     * @param jarId the jar index
     * @return the attribute, or {@code null} when the manifest does not declare it
     */
    public String jarSpecVendor(int jarId) {
        return string(buffer.getInt(jarOffset(jarId) + IndexFormat.J_SPEC_VENDOR));
    }

    /**
     * The {@code Implementation-Title} main attribute of a jar's manifest.
     *
     * @param jarId the jar index
     * @return the attribute, or {@code null} when the manifest does not declare it
     */
    public String jarImplTitle(int jarId) {
        return string(buffer.getInt(jarOffset(jarId) + IndexFormat.J_IMPL_TITLE));
    }

    /**
     * The {@code Implementation-Version} main attribute of a jar's manifest.
     *
     * @param jarId the jar index
     * @return the attribute, or {@code null} when the manifest does not declare it
     */
    public String jarImplVersion(int jarId) {
        return string(buffer.getInt(jarOffset(jarId) + IndexFormat.J_IMPL_VERSION));
    }

    /**
     * The {@code Implementation-Vendor} main attribute of a jar's manifest.
     *
     * @param jarId the jar index
     * @return the attribute, or {@code null} when the manifest does not declare it
     */
    public String jarImplVendor(int jarId) {
        return string(buffer.getInt(jarOffset(jarId) + IndexFormat.J_IMPL_VENDOR));
    }

    /**
     * Checks, once per jar, that the nested jar still starts where the index says it does.
     *
     * <p>This is the second half of the packaged-artifact staleness diagnostic: the recorded length catches
     * a rebuild whose size differed before the source opened, and the local file header signature can catch
     * a same-length rewrite before this jar's first access. It costs a single four byte read the first time
     * a jar is touched; a successful result is then cached. It does not monitor later changes or make reads
     * safe while another process mutates the archive. The application layer is not a nested jar and is never
     * checked.</p>
     *
     * <p>A header offset outside the archive means the index does not describe this file, so it is reported
     * as a stale jar. A read that fails for any other reason, for example because the source was closed, says
     * nothing about the jar and is reported as the I/O failure it is.</p>
     *
     * @param jarId the jar index
     * @throws IllegalStateException if the archive no longer agrees with the index
     * @throws UncheckedIOException  if the local file header cannot be read
     */
    public void validateJar(int jarId) {
        int offset = jarOffset(jarId);
        if (validatedJars[jarId]) {
            return;
        }
        if (jarId != IndexFormat.APPLICATION_JAR_ID) {
            long header = u64(offset + IndexFormat.J_LOCAL_HEADER_OFFSET);
            if (header < 0 || header > source.length() - 4) {
                throw stale("the local file header of " + jarName(jarId) + " is outside the archive");
            }
            int signature;
            try {
                signature = source.i32(header);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read the local file header of " + jarName(jarId)
                        + " from " + source.file(), e);
            }
            if (signature != IndexFormat.LOCAL_HEADER_SIGNATURE) {
                throw stale("no local file header for " + jarName(jarId) + " at offset " + header);
            }
        }
        validatedJars[jarId] = true;
    }

    /**
     * Opens one indexed entry after performing its jar's lazy staleness check.
     *
     * <p>When verification is enabled, every byte returned or skipped contributes to the CRC-32 and the
     * checksum is compared as soon as the recorded end of the entry is reached. Closing a partially read
     * stream does not drain or verify it.</p>
     *
     * @param record the entry record
     * @param verify whether to verify the uncompressed content against the indexed CRC-32
     * @return a fresh stream over the entry
     * @throws IOException if the entry cannot be opened or fails verification
     */
    InputStream openEntryStream(int record, boolean verify) throws IOException {
        int jarId = entryJarId(record);
        validateJar(jarId);
        InputStream stream = source.stream(entryDataOffset(record), entryCompressedSize(record),
                entryUncompressedSize(record), entryMethod(record));
        if (!verify) {
            return stream;
        }
        return new VerifyingEntryInputStream(stream, entryUncompressedSize(record), entryCrc32(record),
                entryName(record), jarName(jarId));
    }

    /**
     * Opens one indexed entry using the runtime verification setting.
     *
     * @param record the entry record
     * @return a fresh stream over the entry
     * @throws IOException if the entry cannot be opened or fails verification
     */
    public InputStream openEntryStream(int record) throws IOException {
        return openEntryStream(record, "true".equals(System.getProperty(RunnerClassLoader.VERIFY_PROPERTY)));
    }

    /**
     * The package name a package override record applies to, in dotted form.
     *
     * @param record the package record index
     * @return the package name
     */
    public String packageName(int record) {
        return string(buffer.getInt(packageOffset(record) + IndexFormat.P_NAME));
    }

    /**
     * The {@code Specification-Title} of a package section.
     *
     * @param record the package record index
     * @return the attribute, or {@code null} to inherit the jar's main attribute
     */
    public String packageSpecTitle(int record) {
        return string(buffer.getInt(packageOffset(record) + IndexFormat.P_SPEC_TITLE));
    }

    /**
     * The {@code Specification-Version} of a package section.
     *
     * @param record the package record index
     * @return the attribute, or {@code null} to inherit the jar's main attribute
     */
    public String packageSpecVersion(int record) {
        return string(buffer.getInt(packageOffset(record) + IndexFormat.P_SPEC_VERSION));
    }

    /**
     * The {@code Specification-Vendor} of a package section.
     *
     * @param record the package record index
     * @return the attribute, or {@code null} to inherit the jar's main attribute
     */
    public String packageSpecVendor(int record) {
        return string(buffer.getInt(packageOffset(record) + IndexFormat.P_SPEC_VENDOR));
    }

    /**
     * The {@code Implementation-Title} of a package section.
     *
     * @param record the package record index
     * @return the attribute, or {@code null} to inherit the jar's main attribute
     */
    public String packageImplTitle(int record) {
        return string(buffer.getInt(packageOffset(record) + IndexFormat.P_IMPL_TITLE));
    }

    /**
     * The {@code Implementation-Version} of a package section.
     *
     * @param record the package record index
     * @return the attribute, or {@code null} to inherit the jar's main attribute
     */
    public String packageImplVersion(int record) {
        return string(buffer.getInt(packageOffset(record) + IndexFormat.P_IMPL_VERSION));
    }

    /**
     * The {@code Implementation-Vendor} of a package section.
     *
     * @param record the package record index
     * @return the attribute, or {@code null} to inherit the jar's main attribute
     */
    public String packageImplVendor(int record) {
        return string(buffer.getInt(packageOffset(record) + IndexFormat.P_IMPL_VENDOR));
    }

    /**
     * The flags of a package override record.
     *
     * @param record the package record index
     * @return a mask of {@code IndexFormat.PACKAGE_FLAG_*}
     */
    public int packageFlags(int record) {
        return buffer.getShort(packageOffset(record) + IndexFormat.P_FLAGS) & 0xFFFF;
    }

    /**
     * Whether a package section carries a {@code Sealed} attribute at all; without one the jar's default
     * applies.
     *
     * @param record the package record index
     * @return {@code true} when the section specifies sealing
     */
    public boolean packageSealedSpecified(int record) {
        return (packageFlags(record) & IndexFormat.PACKAGE_FLAG_SEALED_SPECIFIED) != 0;
    }

    /**
     * The value of a package section's {@code Sealed} attribute, meaningful only together with
     * {@link #packageSealedSpecified(int)}.
     *
     * @param record the package record index
     * @return {@code true} when the section declares {@code Sealed: true}
     */
    public boolean packageSealedValue(int record) {
        return (packageFlags(record) & IndexFormat.PACKAGE_FLAG_SEALED_VALUE) != 0;
    }

    /**
     * Finds the package override record of a package within one jar.
     *
     * @param jarId the jar index
     * @param name  the package name in dotted form
     * @return the package record index, or {@link IndexFormat#NO_INDEX} when the jar's manifest has no
     *         section for that package
     */
    public int findPackage(int jarId, String name) {
        int first = jarFirstPackage(jarId);
        int count = jarPackageCount(jarId);
        if (count == 0) {
            return IndexFormat.NO_INDEX;
        }
        AtomicReferenceArray<PackageLookup> lookups = packageLookups;
        if (lookups == null) {
            synchronized (this) {
                lookups = packageLookups;
                if (lookups == null) {
                    lookups = new AtomicReferenceArray<>(jarCount);
                    packageLookups = lookups;
                }
            }
        }
        PackageLookup lookup = lookups.get(jarId);
        if (lookup == null) {
            PackageLookup candidate = packageLookup(first, count);
            if (lookups.compareAndSet(jarId, null, candidate)) {
                lookup = candidate;
            } else {
                lookup = lookups.get(jarId);
            }
        }
        return lookup.find(name);
    }

    private PackageLookup packageLookup(int first, int count) {
        int slots = Integer.highestOneBit((count << 1) - 1) << 1;
        String[] names = new String[slots];
        int[] records = new int[slots];
        int mask = slots - 1;
        for (int i = 0; i < count; i++) {
            int record = first + i;
            String name = packageName(record);
            int slot = IndexFormat.spread(name.hashCode()) & mask;
            while (names[slot] != null) {
                if (name.equals(names[slot])) {
                    break;
                }
                slot = (slot + 1) & mask;
            }
            if (names[slot] == null) {
                names[slot] = name;
                records[slot] = record;
            }
        }
        return new PackageLookup(names, records);
    }

    /**
     * The logical name of an entry, decoded from the string table.
     *
     * @param record the entry record index
     * @return the name, never {@code null} for a well formed index
     */
    public String entryName(int record) {
        return string(buffer.getInt(entryOffset(record) + IndexFormat.E_NAME));
    }

    /**
     * The stored hash of an entry's logical name, as {@link IndexFormat#hash(String)} computes it.
     *
     * @param record the entry record index
     * @return the hash
     */
    public int entryNameHash(int record) {
        return buffer.getInt(entryOffset(record) + IndexFormat.E_NAME_HASH);
    }

    /**
     * The length in UTF-8 bytes of an entry's logical name.
     *
     * @param record the entry record index
     * @return the length
     */
    public int entryNameLength(int record) {
        return buffer.getShort(entryOffset(record) + IndexFormat.E_NAME_LENGTH) & 0xFFFF;
    }

    /**
     * The jar an entry belongs to.
     *
     * @param record the entry record index
     * @return the jar index
     */
    public int entryJarId(int record) {
        return buffer.getShort(entryOffset(record) + IndexFormat.E_JAR_ID) & 0xFFFF;
    }

    /**
     * The absolute offset in the outer archive of an entry's data, that is, past its local file header.
     *
     * @param record the entry record index
     * @return the offset
     */
    public long entryDataOffset(int record) {
        return u64(entryOffset(record) + IndexFormat.E_DATA_OFFSET);
    }

    /**
     * The number of bytes an entry occupies in the archive.
     *
     * @param record the entry record index
     * @return the compressed size, widened because it is an unsigned 32 bit field
     */
    public long entryCompressedSize(int record) {
        return u32(entryOffset(record) + IndexFormat.E_COMPRESSED_SIZE);
    }

    /**
     * The number of bytes an entry expands to.
     *
     * @param record the entry record index
     * @return the uncompressed size, widened because it is an unsigned 32 bit field
     */
    public long entryUncompressedSize(int record) {
        return u32(entryOffset(record) + IndexFormat.E_UNCOMPRESSED_SIZE);
    }

    /**
     * The CRC-32 of an entry's uncompressed content, as the ZIP header records it.
     *
     * @param record the entry record index
     * @return the checksum, widened to match {@code java.util.zip.CRC32.getValue()}
     */
    public long entryCrc32(int record) {
        return u32(entryOffset(record) + IndexFormat.E_CRC32);
    }

    /**
     * The MS-DOS date and time word of an entry, exactly as the ZIP header stores it.
     *
     * @param record the entry record index
     * @return the packed date and time, widened because it is an unsigned 32 bit field
     */
    public long entryDosTime(int record) {
        return u32(entryOffset(record) + IndexFormat.E_DOS_TIME);
    }

    /**
     * The compression method of an entry.
     *
     * @param record the entry record index
     * @return {@link IndexFormat#METHOD_STORED} or {@link IndexFormat#METHOD_DEFLATED}
     */
    public int entryMethod(int record) {
        return buffer.get(entryOffset(record) + IndexFormat.E_METHOD) & 0xFF;
    }

    /**
     * The multi-release version of an entry.
     *
     * @param record the entry record index
     * @return {@code 0} for a base entry, otherwise the {@code META-INF/versions/N} feature version this
     *         record is an alias for
     */
    public int entryMrVersion(int record) {
        return buffer.get(entryOffset(record) + IndexFormat.E_MR_VERSION) & 0xFF;
    }

    /**
     * The flags of an entry.
     *
     * @param record the entry record index
     * @return a mask of {@code IndexFormat.ENTRY_FLAG_*}
     */
    public int entryFlags(int record) {
        return buffer.get(entryOffset(record) + IndexFormat.E_FLAGS) & 0xFF;
    }

    /**
     * The next record with the same logical name, in classpath order.
     *
     * @param record the entry record index
     * @return the next record, or {@link IndexFormat#NO_INDEX} at the end of the chain
     */
    public int entryNextSameName(int record) {
        return buffer.getInt(entryOffset(record) + IndexFormat.E_NEXT_SAME_NAME);
    }

    /**
     * The physical record behind an entry: itself for a real entry, the versioned entry it aliases for an
     * alias, and {@link IndexFormat#NO_INDEX} for a synthetic directory. Use it to get the name an entry
     * really has in the archive, for example {@code META-INF/versions/21/a/B.class}.
     *
     * @param record the entry record index
     * @return the physical record index or {@link IndexFormat#NO_INDEX}
     */
    public int entryPhysicalIndex(int record) {
        return buffer.getInt(entryOffset(record) + IndexFormat.E_PHYSICAL_INDEX);
    }

    /**
     * Whether an entry is a directory, that is, its logical name ends with a slash.
     *
     * @param record the entry record index
     * @return {@code true} for a directory
     */
    public boolean entryDirectory(int record) {
        return (entryFlags(record) & IndexFormat.ENTRY_FLAG_DIRECTORY) != 0;
    }

    /**
     * Whether a directory entry was synthesised by the packager because the source archive had no entry
     * for it. Synthetic directories answer resource lookups but are excluded from jar enumeration.
     *
     * @param record the entry record index
     * @return {@code true} for a synthesised directory
     */
    public boolean entrySyntheticDirectory(int record) {
        return (entryFlags(record) & IndexFormat.ENTRY_FLAG_SYNTHETIC_DIR) != 0;
    }

    /**
     * Whether a record is a multi-release alias of another record in the same jar. Aliases are excluded
     * from jar enumeration, which lists physical records only.
     *
     * @param record the entry record index
     * @return {@code true} for an alias
     */
    public boolean entryVersionedAlias(int record) {
        return (entryFlags(record) & IndexFormat.ENTRY_FLAG_VERSIONED_ALIAS) != 0;
    }

    /**
     * Whether a record corresponds to an entry that physically exists in the source archive.
     *
     * @param record the entry record index
     * @return {@code true} for a physical entry
     */
    public boolean entryPhysical(int record) {
        return (entryFlags(record) & IndexFormat.ENTRY_FLAG_PHYSICAL) != 0;
    }

    /**
     * Looks up a logical name and returns the head of its chain.
     *
     * <p>The chain holds every record with that name across all jars, in classpath order, with a jar's
     * versioned aliases before its base record; {@link #resolve(int, int)} picks the record that applies.
     * The candidate's name is compared against the stored UTF-8 bytes without allocating: the lengths are
     * compared first, then the bytes, and only a name that actually contains a non-ASCII character is
     * decoded.</p>
     *
     * @param logicalName the entry name relative to its jar, with no leading slash
     * @return the first record with that name, or {@link IndexFormat#NO_INDEX}
     */
    public int find(String logicalName) {
        int hash = IndexFormat.hash(logicalName);
        int slot = hash & hashMask;
        int table = hashTableOffset;
        for (int probe = 0; probe <= maxProbe; probe++) {
            int record = buffer.getInt(table + (slot << 2));
            if (record == IndexFormat.NO_INDEX) {
                return IndexFormat.NO_INDEX;
            }
            int offset = entryOffset(record);
            if (buffer.getInt(offset + IndexFormat.E_NAME_HASH) == hash
                    && nameEquals(offset, logicalName)) {
                return record;
            }
            slot = (slot + 1) & hashMask;
        }
        return IndexFormat.NO_INDEX;
    }

    /**
     * Looks up the resource that holds a class and returns the head of its chain.
     *
     * <p>This is the most executed method in the launcher: every class the application loads comes through
     * it. It never builds the resource name. The hash of {@code binaryName.replace('.', '/') + ".class"}
     * is computed straight from the binary name, using the identity
     * {@code hashCode(a + b) == hashCode(a) * 31^length(b) + hashCode(b)} to fold in the suffix, and the
     * candidate is verified by comparing the stored UTF-8 bytes while mapping dots to slashes on the fly.
     * No allocation happens on the hit path, the miss path or the collision path.</p>
     *
     * @param binaryName the class name, for example {@code org.example.Service$Inner}
     * @return the first record for that class, or {@link IndexFormat#NO_INDEX}
     */
    public int findClass(String binaryName) {
        int hash = 0;
        int nameLength = binaryName.length();
        for (int i = 0; i < nameLength; i++) {
            char c = binaryName.charAt(i);
            if (c == '.') {
                c = '/';
            }
            hash = 31 * hash + c;
        }
        hash = IndexFormat.spread(hash * CLASS_SUFFIX_HASH_FACTOR + CLASS_SUFFIX_HASH);
        int slot = hash & hashMask;
        int table = hashTableOffset;
        for (int probe = 0; probe <= maxProbe; probe++) {
            int record = buffer.getInt(table + (slot << 2));
            if (record == IndexFormat.NO_INDEX) {
                return IndexFormat.NO_INDEX;
            }
            int offset = entryOffset(record);
            if (buffer.getInt(offset + IndexFormat.E_NAME_HASH) == hash
                    && classNameEquals(offset, binaryName)) {
                return record;
            }
            slot = (slot + 1) & hashMask;
        }
        return IndexFormat.NO_INDEX;
    }

    /**
     * Follows a chain of records with the same logical name.
     *
     * @param record the current record
     * @return the next record, or {@link IndexFormat#NO_INDEX} at the end of the chain
     */
    public int next(int record) {
        return buffer.getInt(entryOffset(record) + IndexFormat.E_NEXT_SAME_NAME);
    }

    /**
     * Advances a same-name chain from the current record to the first record in the next JAR.
     *
     * <p>Records of one JAR are contiguous in a same-name chain. Starting here, rather than at the chain
     * head, lets resource enumeration consume the chain once while still selecting one effective record
     * per JAR.</p>
     *
     * @param record the record selected for the current JAR
     * @return the first record in the next JAR, or {@link IndexFormat#NO_INDEX} at the end of the chain
     */
    public int nextJar(int record) {
        int jarId = entryJarId(record);
        int current = next(record);
        int guard = entryCount;
        while (current != IndexFormat.NO_INDEX && entryJarId(current) == jarId) {
            current = next(current);
            guard--;
            if (guard < 0) {
                throw stale("the chain from record " + record + " does not leave jar " + jarId);
            }
        }
        return current;
    }

    /**
     * Picks the record a chain resolves to for a given runtime.
     *
     * <p>Within one jar the chain lists versioned aliases in descending version order before the base
     * record, so the first record whose version is {@code 0} or at most {@code effectiveVersion} is the
     * one {@code JarFile} would return. Names under {@code META-INF/} are never versioned, which the
     * packager guarantees by not emitting aliases for them, so no special case is needed here.</p>
     *
     * @param chainHead        a record, usually the result of {@link #find(String)}
     * @param effectiveVersion the runtime feature version, or at most {@link #BASE_VERSION} for base
     *                         entries only
     * @return the first applicable record, or {@link IndexFormat#NO_INDEX}
     */
    public int resolve(int chainHead, int effectiveVersion) {
        int highest = versionedCeiling(effectiveVersion);
        int record = chainHead;
        int guard = entryCount;
        while (record != IndexFormat.NO_INDEX) {
            int offset = entryOffset(record);
            int version = buffer.get(offset + IndexFormat.E_MR_VERSION) & 0xFF;
            if (version == 0 || version <= highest) {
                return record;
            }
            record = buffer.getInt(offset + IndexFormat.E_NEXT_SAME_NAME);
            guard--;
            if (guard < 0) {
                throw stale("the chain from record " + chainHead + " does not end");
            }
        }
        return IndexFormat.NO_INDEX;
    }

    /**
     * Picks the record a chain resolves to within one jar, for enumerating a resource per jar the way
     * {@code ClassLoader.getResources} does.
     *
     * @param chainHead        a record, usually the result of {@link #find(String)}
     * @param effectiveVersion the runtime feature version, or at most {@link #BASE_VERSION} for base
     *                         entries only
     * @param jarId            the only jar to consider
     * @return the first applicable record of that jar, or {@link IndexFormat#NO_INDEX}
     */
    public int resolveInJar(int chainHead, int effectiveVersion, int jarId) {
        int highest = versionedCeiling(effectiveVersion);
        int record = chainHead;
        int guard = entryCount;
        while (record != IndexFormat.NO_INDEX) {
            int offset = entryOffset(record);
            int version = buffer.get(offset + IndexFormat.E_MR_VERSION) & 0xFF;
            if ((buffer.getShort(offset + IndexFormat.E_JAR_ID) & 0xFFFF) == jarId
                    && (version == 0 || version <= highest)) {
                return record;
            }
            record = buffer.getInt(offset + IndexFormat.E_NEXT_SAME_NAME);
            guard--;
            if (guard < 0) {
                throw stale("the chain from record " + chainHead + " does not end");
            }
        }
        return IndexFormat.NO_INDEX;
    }

    /**
     * The highest {@code META-INF/versions/N} that a lookup may select for an effective runtime version.
     *
     * <p>{@code JarFile} consults versioned entries only when the version it runs for is <em>above</em>
     * {@link #BASE_VERSION}: with {@code jdk.util.jar.enableMultiRelease} set to {@code false}, or with
     * {@code jdk.util.jar.version} pinned to 8, a {@code META-INF/versions/8/} entry is not selected even
     * though its version is not above what was asked for. Reporting {@code 0} for that case keeps the
     * test inside the two resolve loops a single comparison, because no alias ever records version
     * {@code 0} and every base record does.</p>
     *
     * @param effectiveVersion the runtime feature version
     * @return the highest alias version that applies, or {@code 0} when only base entries apply
     */
    private static int versionedCeiling(int effectiveVersion) {
        return effectiveVersion > BASE_VERSION ? effectiveVersion : 0;
    }

    /**
     * Decodes a string from the string table.
     *
     * <p>Reference {@code 0} always points at the empty string the writer puts at offset zero, and means
     * "absent"; this method returns {@code null} for it, so that a caller can hand the result straight to
     * {@code Package} and manifest APIs, which take {@code null} for a missing attribute.</p>
     *
     * @param ref a string reference, that is, a byte offset into the string table
     * @return the string, or {@code null} when the reference is {@code 0}
     */
    public String string(int ref) {
        if (ref == 0) {
            return null;
        }
        int at = stringBytesOffset(ref, -1);
        int size = buffer.getShort(at - 2) & 0xFFFF;
        byte[] bytes = new byte[size];
        buffer.get(at, bytes, 0, size);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Walks every string reference in the index and checks that it lies inside the string table.
     *
     * <p>{@link #open(ArchiveSource, long, long)} deliberately does not do this: it is the one validation
     * whose cost grows with the number of entries, and it would fault in the whole entry and string tables
     * before the application starts. References are bounds-checked when they are dereferenced instead.
     * Tools that dump an index, and tests, call this to check the whole file up front.</p>
     *
     * @throws IllegalStateException if any reference points outside the string table
     */
    public void validateStringReferences() {
        for (int i = 0; i < jarCount; i++) {
            int offset = jarOffset(i);
            checkStringRef(buffer.getInt(offset + IndexFormat.J_NAME), "jar name");
            checkStringRef(buffer.getInt(offset + IndexFormat.J_COORDINATES), "jar coordinates");
            for (int attribute = IndexFormat.J_SPEC_TITLE; attribute <= IndexFormat.J_IMPL_VENDOR;
                    attribute += 4) {
                checkStringRef(buffer.getInt(offset + attribute), "jar manifest attribute");
            }
        }
        for (int i = 0; i < packageCount; i++) {
            int offset = packageOffset(i);
            for (int attribute = IndexFormat.P_NAME; attribute <= IndexFormat.P_IMPL_VENDOR;
                    attribute += 4) {
                checkStringRef(buffer.getInt(offset + attribute), "package attribute");
            }
        }
        for (int i = 0; i < entryCount; i++) {
            int offset = entryOffset(i);
            int ref = buffer.getInt(offset + IndexFormat.E_NAME);
            int size = buffer.getShort(offset + IndexFormat.E_NAME_LENGTH) & 0xFFFF;
            if (ref == 0) {
                throw stale("entry record " + i + " has no name");
            }
            stringBytesOffset(ref, size);
        }
    }

    private static IllegalStateException stale(String detail) {
        StringBuilder message = new StringBuilder(REBUILD_MESSAGE.length() + detail.length() + 3);
        message.append(REBUILD_MESSAGE).append(" (").append(detail).append(')');
        return new IllegalStateException(message.toString());
    }

    private static int featureVersion(String value, int fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        int at = 0;
        int parsed = 0;
        int digits = 0;
        while (at < value.length()) {
            char c = value.charAt(at);
            if (c < '0' || c > '9' || digits > 6) {
                break;
            }
            parsed = parsed * 10 + (c - '0');
            digits++;
            at++;
        }
        if (digits == 0) {
            return fallback;
        }
        if (parsed == 1 && at + 1 < value.length() && value.charAt(at) == '.') {
            return featureVersion(value.substring(at + 1), fallback);
        }
        return parsed;
    }

    private int u16(int offset) {
        return buffer.getShort(offset) & 0xFFFF;
    }

    private long u32(int offset) {
        return buffer.getInt(offset) & 0xFFFFFFFFL;
    }

    private long u64(int offset) {
        return buffer.getLong(offset);
    }

    private int section(int headerOffset, long count, long elementSize, String what) {
        long offset = u64(headerOffset);
        long total;
        try {
            total = Math.multiplyExact(count, elementSize);
        } catch (ArithmeticException e) {
            throw stale("the " + what + " at offset " + offset + " with " + count
                    + " elements does not fit in an index of " + length + " bytes");
        }
        if (!rangeFits(offset, total, length)) {
            throw stale("the " + what + " at offset " + offset + " with " + count
                    + " elements does not fit in an index of " + length + " bytes");
        }
        if (total > 0 && offset < IndexFormat.HEADER_SIZE) {
            throw stale("the " + what + " at offset " + offset + " overlaps the header");
        }
        return (int) offset;
    }

    private void checkJarTable() {
        for (int i = 0; i < jarCount; i++) {
            int offset = jarOffset(i);
            long firstEntry = u32(offset + IndexFormat.J_FIRST_ENTRY);
            long count = u32(offset + IndexFormat.J_ENTRY_COUNT);
            if (!rangeFits(firstEntry, count, entryCount)) {
                throw stale("jar " + i + " claims " + count + " entries starting at " + firstEntry
                        + " but the index holds " + entryCount);
            }
            long firstPackage = u32(offset + IndexFormat.J_FIRST_PACKAGE);
            long packages = u32(offset + IndexFormat.J_PACKAGE_COUNT);
            if (packages > 0 && !rangeFits(firstPackage, packages, packageCount)) {
                throw stale("jar " + i + " claims " + packages + " packages starting at "
                        + firstPackage + " but the index holds " + packageCount);
            }
            long dataOffset = u64(offset + IndexFormat.J_DATA_OFFSET);
            long dataLength = u64(offset + IndexFormat.J_DATA_LENGTH);
            if (!rangeFits(dataOffset, dataLength, outerFileLength)) {
                throw stale("jar " + i + " spans " + dataLength + " bytes at offset " + dataOffset
                        + " but the archive is " + outerFileLength + " bytes");
            }
        }
    }

    private static boolean rangeFits(long offset, long count, long limit) {
        return offset >= 0 && count >= 0 && offset <= limit && count <= limit - offset;
    }

    private void checkStringRef(int ref, String what) {
        if (ref == 0) {
            return;
        }
        try {
            stringBytesOffset(ref, -1);
        } catch (IllegalStateException e) {
            throw stale("the " + what + " points outside the string table");
        }
    }

    /**
     * The offset of a name's bytes, for the lookup path. It trusts the record's own name length, which the
     * format requires to agree with the string table, and only bounds-checks it, so that comparing a name
     * costs one read of the record and none of the string table's framing. {@link #string(int)} and
     * {@link #validateStringReferences()} take the strict route through {@link #stringBytesOffset}.
     *
     * @param ref  the string reference
     * @param size the name length the entry record carries
     * @return the offset of the first name byte in the index
     */
    private int nameBytesOffset(int ref, int size) {
        long start = ref & 0xFFFFFFFFL;
        if (start == 0 || start + 2 + size > stringTableLength) {
            throw stale("the name at string reference " + start + " runs past the string table of "
                    + stringTableLength + " bytes");
        }
        return stringTableOffset + (int) start + 2;
    }

    private int stringBytesOffset(int ref, int expectedSize) {
        long start = ref & 0xFFFFFFFFL;
        if (start + 2 > stringTableLength) {
            throw stale("string reference " + start + " is outside the string table of "
                    + stringTableLength + " bytes");
        }
        int at = stringTableOffset + (int) start;
        int size = buffer.getShort(at) & 0xFFFF;
        if (expectedSize >= 0 && size != expectedSize) {
            throw stale("string reference " + start + " holds " + size + " bytes but the record records "
                    + expectedSize);
        }
        if (start + 2 + size > stringTableLength) {
            throw stale("the string at reference " + start + " runs past the string table");
        }
        return at + 2;
    }

    private int jarOffset(int jarId) {
        if (Integer.compareUnsigned(jarId, jarCount) >= 0) {
            throw stale("jar " + Integer.toUnsignedString(jarId) + " does not exist, the index holds "
                    + jarCount);
        }
        return jarTableOffset + jarId * IndexFormat.JAR_RECORD_SIZE;
    }

    private int packageOffset(int record) {
        if (Integer.compareUnsigned(record, packageCount) >= 0) {
            throw stale("package record " + Integer.toUnsignedString(record)
                    + " does not exist, the index holds " + packageCount);
        }
        return packageTableOffset + record * IndexFormat.PACKAGE_RECORD_SIZE;
    }

    private int entryOffset(int record) {
        if (Integer.compareUnsigned(record, entryCount) >= 0) {
            throw stale("entry record " + Integer.toUnsignedString(record)
                    + " does not exist, the index holds " + entryCount);
        }
        return entryTableOffset + record * IndexFormat.ENTRY_RECORD_SIZE;
    }

    private boolean nameEquals(int offset, String name) {
        int size = buffer.getShort(offset + IndexFormat.E_NAME_LENGTH) & 0xFFFF;
        int characters = name.length();
        if (size < characters) {
            return false;
        }
        int at = nameBytesOffset(buffer.getInt(offset + IndexFormat.E_NAME), size);
        if (size != characters) {
            return decodedEquals(at, size, name);
        }
        for (int i = 0; i < characters; i++) {
            char c = name.charAt(i);
            if (c >= 0x80) {
                return decodedEquals(at, size, name);
            }
            if (buffer.get(at + i) != (byte) c) {
                return false;
            }
        }
        return true;
    }

    private boolean classNameEquals(int offset, String binaryName) {
        int size = buffer.getShort(offset + IndexFormat.E_NAME_LENGTH) & 0xFFFF;
        int characters = binaryName.length() + CLASS_SUFFIX.length;
        if (size < characters) {
            return false;
        }
        int at = nameBytesOffset(buffer.getInt(offset + IndexFormat.E_NAME), size);
        if (size != characters) {
            return decodedEquals(at, size, resourceName(binaryName));
        }
        int prefix = binaryName.length();
        for (int i = 0; i < prefix; i++) {
            char c = binaryName.charAt(i);
            if (c == '.') {
                c = '/';
            }
            if (c >= 0x80) {
                return decodedEquals(at, size, resourceName(binaryName));
            }
            if (buffer.get(at + i) != (byte) c) {
                return false;
            }
        }
        for (int i = 0; i < CLASS_SUFFIX.length; i++) {
            if (buffer.get(at + prefix + i) != CLASS_SUFFIX[i]) {
                return false;
            }
        }
        return true;
    }

    private String resourceName(String binaryName) {
        StringBuilder name = new StringBuilder(binaryName.length() + CLASS_SUFFIX.length);
        for (int i = 0; i < binaryName.length(); i++) {
            char c = binaryName.charAt(i);
            name.append(c == '.' ? '/' : c);
        }
        for (int i = 0; i < CLASS_SUFFIX.length; i++) {
            name.append((char) CLASS_SUFFIX[i]);
        }
        return name.toString();
    }

    private boolean decodedEquals(int at, int size, String name) {
        byte[] bytes = new byte[size];
        buffer.get(at, bytes, 0, size);
        return name.equals(new String(bytes, StandardCharsets.UTF_8));
    }

    /** One immutable hash table containing only the package metadata declared by a single jar. */
    private static final class PackageLookup {

        private final String[] names;
        private final int[] records;
        private final int mask;

        private PackageLookup(String[] names, int[] records) {
            this.names = names;
            this.records = records;
            this.mask = names.length - 1;
        }

        private int find(String name) {
            int slot = IndexFormat.spread(name.hashCode()) & mask;
            String candidate = names[slot];
            while (candidate != null) {
                if (name.equals(candidate)) {
                    return records[slot];
                }
                slot = (slot + 1) & mask;
                candidate = names[slot];
            }
            return IndexFormat.NO_INDEX;
        }
    }

    /** Verifies an indexed entry without buffering its content. */
    private static final class VerifyingEntryInputStream extends InputStream {

        private static final int SKIP_BUFFER_SIZE = 8192;

        private final InputStream delegate;
        private final CRC32 checksum = new CRC32();
        private final long expected;
        private final String entryName;
        private final String jarName;
        private long remaining;
        private IOException failure;
        private boolean verified;

        private VerifyingEntryInputStream(InputStream delegate, long size, long expected, String entryName,
                                          String jarName) {
            this.delegate = delegate;
            this.remaining = size;
            this.expected = expected;
            this.entryName = entryName;
            this.jarName = jarName;
        }

        @Override
        public int read() throws IOException {
            checkFailure();
            if (remaining == 0) {
                verifyEnd();
                return -1;
            }
            int value = delegate.read();
            if (value < 0) {
                return value;
            }
            checksum.update(value);
            remaining--;
            if (remaining == 0) {
                verifyEnd();
            }
            return value;
        }

        @Override
        public int read(byte[] destination, int offset, int count) throws IOException {
            checkFailure();
            Objects.checkFromIndexSize(offset, count, destination.length);
            if (count == 0) {
                return 0;
            }
            if (remaining == 0) {
                verifyEnd();
                return -1;
            }
            int read = delegate.read(destination, offset, (int) Math.min(count, remaining));
            if (read < 0) {
                return read;
            }
            checksum.update(destination, offset, read);
            remaining -= read;
            if (remaining == 0) {
                verifyEnd();
            }
            return read;
        }

        @Override
        public long skip(long count) throws IOException {
            checkFailure();
            long wanted = Math.min(Math.max(count, 0L), remaining);
            if (wanted == 0) {
                if (remaining == 0 && count > 0) {
                    verifyEnd();
                }
                return 0;
            }
            byte[] discarded = new byte[(int) Math.min(wanted, SKIP_BUFFER_SIZE)];
            long skipped = 0;
            while (skipped < wanted) {
                int read = read(discarded, 0, (int) Math.min(discarded.length, wanted - skipped));
                if (read < 0) {
                    break;
                }
                skipped += read;
            }
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private void verifyEnd() throws IOException {
            checkFailure();
            if (verified) {
                return;
            }
            if (delegate.read() >= 0) {
                fail(new IOException("The entry holds more data than the index records"));
            }
            long actual = checksum.getValue();
            if (expected != actual) {
                fail(new IOException("The entry " + entryName + " of " + jarName + " has checksum "
                        + actual + " but the index records " + expected + "; " + REBUILD_MESSAGE));
            }
            verified = true;
        }

        private void checkFailure() throws IOException {
            if (failure != null) {
                throw failure;
            }
        }

        private void fail(IOException exception) throws IOException {
            failure = exception;
            throw exception;
        }
    }
}
