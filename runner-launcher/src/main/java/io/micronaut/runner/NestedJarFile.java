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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * A {@link JarFile} view of one nested jar of a runner archive, served entirely from the index.
 *
 * <p>Libraries that scan the class path do it through {@code JarFile}: Flyway looks for migrations,
 * Liquibase for change logs, Micronaut for service metadata, and every one of them enumerates entries and
 * opens streams rather than asking a class loader. A nested jar is not a file, so it cannot be opened as
 * one; this class answers those questions from the index instead, which is both faster and free of the
 * temporary files the JDK would otherwise write.</p>
 *
 * <h2>Why it extends JarFile at all</h2>
 * <p>The type is what libraries accept, so the view has to <em>be</em> a {@code JarFile}. The constructor
 * hands the super class the <em>outer</em> archive, with verification off and at the base version, so that
 * the JDK reuses the cached {@code ZipFile.Source} of a file it has very likely opened already rather than
 * opening and parsing a second one. Every accessor is then overridden to answer for the nested jar.</p>
 *
 * <h2>The two methods that cannot be corrected</h2>
 * <p>{@link JarFile#isMultiRelease()} and {@link JarFile#getVersion()} are {@code final}. They keep
 * reporting the state of the outer archive, which is not multi-release and is opened at the base version,
 * even when this view is of a multi-release dependency. This is a genuine, documented limitation, and it
 * is only a reporting one: {@link #getEntry(String)} applies the nested jar's own multi-release policy, so
 * lookups still return the versioned entry a real {@code JarFile} would return, and
 * {@link NestedJarEntry#getRealName()} still reports the {@code META-INF/versions/<n>/...} name it came
 * from. Code that branches on {@code isMultiRelease()} rather than on the entry it gets back will take the
 * base branch and still be handed versioned entries.</p>
 *
 * <h2>Enumeration</h2>
 * <p>{@link #entries()}, {@link #stream()} and {@link #size()} report the <em>physical</em> records of the
 * jar in central directory order: the versioned aliases and the synthesised directory records the index
 * adds are left out, so a scan sees the same entries, in the same order and in the same number, as it
 * would see in the original dependency. {@link #getEntry(String)} does include a synthesised directory,
 * because a lookup of {@code some/package/} is a question about the jar's content and the answer "yes,
 * that directory exists" is the useful one.</p>
 *
 * @since 1.0
 */
public final class NestedJarFile extends JarFile {

    /** The manifest of a jar, which is not versioned even in a multi-release jar. */
    private static final String MANIFEST_NAME = "META-INF/MANIFEST.MF";

    private final Index index;
    private final ArchiveSource source;
    private final int jarId;
    private final int firstEntry;
    private final int entryCount;
    private final int version;
    private final String name;
    private final Object lock = new Object();
    private Manifest manifest;
    private boolean manifestRead;
    private int physicalCount = -1;

    /**
     * Opens a view of one nested jar.
     *
     * <p>The outer file is opened through the super class only so that this object is a usable
     * {@code JarFile}; none of its content is read through it.</p>
     *
     * @param outerFile the outer runner archive
     * @param index     the index of that archive
     * @param source    the reader of that archive
     * @param jarId     the nested jar, which must not be the application layer
     * @throws IOException              if the outer archive cannot be opened
     * @throws IllegalArgumentException if {@code jarId} is not a nested jar of the index
     */
    public NestedJarFile(File outerFile, Index index, ArchiveSource source, int jarId) throws IOException {
        // The jar is checked inside the super call, so that a bad argument never leaks an open file.
        super(checkJarId(outerFile, index, jarId), false, OPEN_READ, JarFile.baseVersion());
        this.index = index;
        this.source = source;
        this.jarId = jarId;
        this.firstEntry = index.jarFirstEntry(jarId);
        this.entryCount = index.jarEntryCount(jarId);
        this.version = index.jarMultiRelease(jarId)
                ? Index.effectiveMultiReleaseVersion() : Index.BASE_VERSION;
        StringBuilder jarName = new StringBuilder(64);
        jarName.append(outerFile.getPath()).append(Handlers.SEPARATOR).append(index.jarName(jarId));
        this.name = jarName.toString();
    }

    /**
     * Checks that a jar exists and is a nested one, before the super constructor opens anything.
     *
     * @param outerFile the outer runner archive
     * @param index     the index of that archive
     * @param jarId     the nested jar
     * @return the outer file, so that this can be called from the super constructor arguments
     */
    private static File checkJarId(File outerFile, Index index, int jarId) {
        if (jarId <= IndexFormat.APPLICATION_JAR_ID || jarId >= index.jarCount()) {
            throw new IllegalArgumentException("Jar " + jarId + " is not a nested jar of " + outerFile);
        }
        return outerFile;
    }

    /**
     * The jar this view serves.
     *
     * @return the jar index in the archive's jar table
     */
    public int jarId() {
        return jarId;
    }

    /**
     * The name of this jar, in the form {@code <outer archive path>!/MICRONAUT-INF/lib/<dep>.jar}, which
     * is what the JDK's own nested-looking jar names have always looked like and what turns up in the
     * error messages and logs of libraries that print {@code JarFile.getName()}.
     *
     * @return the name of the nested jar
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Looks an entry up by its logical name, honouring the nested jar's own multi-release policy.
     *
     * <p>As {@code ZipFile} does, a name that does not resolve and does not end with a slash is retried
     * with one, so that {@code getEntry("some/package")} finds the directory entry.</p>
     *
     * @param entryName the entry name relative to this jar
     * @return the entry, or {@code null} when this jar has no such entry
     */
    @Override
    public ZipEntry getEntry(String entryName) {
        if (entryName == null) {
            throw new NullPointerException("entryName");
        }
        int record = findRecord(entryName);
        if (record == IndexFormat.NO_INDEX && !entryName.endsWith("/")) {
            StringBuilder directory = new StringBuilder(entryName.length() + 1);
            directory.append(entryName).append('/');
            record = findRecord(directory.toString());
        }
        if (record == IndexFormat.NO_INDEX) {
            return null;
        }
        return new NestedJarEntry(this, index, record);
    }

    /**
     * The same lookup as {@link #getEntry(String)}, typed as a {@link JarEntry}.
     *
     * @param entryName the entry name relative to this jar
     * @return the entry, or {@code null} when this jar has no such entry
     */
    @Override
    public JarEntry getJarEntry(String entryName) {
        return (JarEntry) getEntry(entryName);
    }

    /**
     * The physical entries of this jar, in central directory order.
     *
     * @return an enumeration of the entries
     */
    @Override
    public Enumeration<JarEntry> entries() {
        return Collections.enumeration(physicalEntries());
    }

    /**
     * The physical entries of this jar, in central directory order.
     *
     * <p>This is one of the few places in the launcher that touches {@code java.util.stream}: the method
     * is inherited from {@link JarFile} and has to be overridden, or a caller would be handed the outer
     * archive's entries. Nothing on the startup path calls it.</p>
     *
     * @return a stream of the entries
     */
    @Override
    public Stream<JarEntry> stream() {
        return physicalEntries().stream();
    }

    /**
     * The number of physical entries, which is the number the original dependency had.
     *
     * @return the entry count
     */
    @Override
    public int size() {
        synchronized (lock) {
            if (physicalCount < 0) {
                int count = 0;
                for (int i = 0; i < entryCount; i++) {
                    if (index.entryPhysical(firstEntry + i)) {
                        count++;
                    }
                }
                physicalCount = count;
            }
            return physicalCount;
        }
    }

    /**
     * Opens the content of an entry of this jar, straight out of the archive.
     *
     * <p>An entry this view produced is served from the record it already carries; any other entry is
     * looked up by name first, so that a caller that kept a {@link ZipEntry} of its own still works.</p>
     *
     * @param entry the entry to read
     * @return the content, or {@code null} when this jar has no such entry, as {@code ZipFile} does
     * @throws IOException if the archive no longer agrees with the index or the entry cannot be read
     */
    @Override
    public InputStream getInputStream(ZipEntry entry) throws IOException {
        if (entry == null) {
            throw new NullPointerException("entry");
        }
        int record = IndexFormat.NO_INDEX;
        if (entry instanceof NestedJarEntry) {
            NestedJarEntry nested = (NestedJarEntry) entry;
            if (nested.nestedJarFile() == this) {
                record = nested.record();
            }
        }
        if (record == IndexFormat.NO_INDEX) {
            record = findRecord(entry.getName());
        }
        if (record == IndexFormat.NO_INDEX) {
            return null;
        }
        return openRecord(record);
    }

    /**
     * The manifest of the nested jar, parsed on first use and then kept.
     *
     * <p>The entry is looked up even when the index's has-manifest flag is clear: it costs one hash probe
     * once per jar, and a stale flag would otherwise strip a library of its {@code Implementation-Version}
     * and its package sealing without a trace.</p>
     *
     * @return the manifest, or {@code null} when the jar has none
     * @throws IOException if the manifest cannot be read or is malformed
     */
    @Override
    public Manifest getManifest() throws IOException {
        synchronized (lock) {
            if (!manifestRead) {
                int record = findRecord(MANIFEST_NAME);
                if (record != IndexFormat.NO_INDEX) {
                    InputStream in = openRecord(record);
                    try {
                        manifest = new Manifest(in);
                    } finally {
                        in.close();
                    }
                }
                manifestRead = true;
            }
            return manifest;
        }
    }

    /**
     * Always {@code null}: the nested jar is stored inside the outer archive, so whatever comment it
     * carried is not part of what the index records.
     *
     * @return {@code null}
     */
    @Override
    public String getComment() {
        return null;
    }

    /**
     * Does nothing.
     *
     * <p>Views are shared: one instance per nested jar serves every library in the process, so a library
     * that closes the {@code JarFile} it was handed, and several do, must not take the jar away from the
     * others. The handle on the outer file is released when the launcher releases the archive.</p>
     */
    @Override
    public void close() {
    }

    /**
     * Really closes the handle on the outer archive. Only {@link Handlers#unregister()} calls this, when
     * a process is finished with an archive altogether.
     */
    void closeNested() {
        try {
            super.close();
        } catch (IOException ignored) {
            // Closing is best effort: nothing can be retried and nothing depends on the result.
        }
    }

    /**
     * Resolves a logical name to a record of this jar, applying the multi-release policy.
     *
     * @param entryName the logical entry name
     * @return the record, or {@link IndexFormat#NO_INDEX}
     */
    private int findRecord(String entryName) {
        return index.resolveInJar(index.find(entryName), version, jarId);
    }

    /**
     * Opens the content of a record, after checking the archive still agrees with the index.
     *
     * @param record the entry record
     * @return the content
     * @throws IOException if the entry cannot be read
     */
    private InputStream openRecord(int record) throws IOException {
        index.validateJar(jarId);
        return source.stream(index.entryDataOffset(record), index.entryCompressedSize(record),
                index.entryUncompressedSize(record), index.entryMethod(record));
    }

    /**
     * Builds the physical entries of this jar, in the order the original central directory had them.
     *
     * @return a fresh list of entries
     */
    private List<JarEntry> physicalEntries() {
        List<JarEntry> entries = new ArrayList<>(size());
        for (int i = 0; i < entryCount; i++) {
            int record = firstEntry + i;
            if (index.entryPhysical(record)) {
                entries.add(new NestedJarEntry(this, index, record));
            }
        }
        return entries;
    }
}
