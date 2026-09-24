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

import io.micronaut.runner.ArchiveSource;
import io.micronaut.runner.Handlers;
import io.micronaut.runner.Index;
import io.micronaut.runner.IndexFormat;
import io.micronaut.runner.NestedJarEntry;
import io.micronaut.runner.NestedJarFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * A connection to an entry of a runner archive, served from the index and the memory mapped archive.
 *
 * <h2>One identity, consistently</h2>
 * <p>{@link JarURLConnection} splits a URL at the <em>first</em> {@value Handlers#SEPARATOR}, so for the
 * nested form {@code jar:file:/app.jar!/MICRONAUT-INF/lib/dep.jar!/a/B.class} it would call the entry
 * {@code MICRONAUT-INF/lib/dep.jar!/a/B.class} — a name no jar has and no {@code JarFile} would find. A
 * library that asks the same connection for the jar, the entry name and the entry would get three answers
 * that do not describe the same thing. This class overrides every one of them so that they agree. For the
 * URL above:</p>
 * <ul>
 *   <li>{@code getJarFileURL()} is {@code jar:file:/app.jar!/MICRONAUT-INF/lib/dep.jar}</li>
 *   <li>{@code getEntryName()} is {@code a/B.class}</li>
 *   <li>{@code getJarFile()} is the {@link NestedJarFile} of that dependency</li>
 *   <li>{@code getJarEntry()} is that jar's entry {@code a/B.class}</li>
 * </ul>
 * <p>For an entry of the application layer, {@code jar:file:/app.jar!/MICRONAUT-INF/classes/a/B.class},
 * the answers are the ordinary ones over the outer archive: the jar URL is {@code file:/app.jar}, the
 * entry name is {@code MICRONAUT-INF/classes/a/B.class}, and the jar is the outer archive as an ordinary
 * {@link JarFile}. The entry itself comes from the index record the connection resolved rather than from
 * that {@link JarFile}, because the application layer's directories are recorded in the index and are not
 * entries of the outer archive: asking the archive for one would answer {@code null} for a URL whose
 * {@link #getEntryName()} is not null and whose {@link #getInputStream()} works, and the four answers
 * would stop describing the same thing.</p>
 *
 * <p>A URL that names a whole nested jar, whether it ends with {@value Handlers#SEPARATOR} or stops at the
 * jar, streams the bytes of that jar, so that code which wants to treat a dependency as an archive can.</p>
 *
 * <h2>Where the bytes come from</h2>
 * <p>An entry the index knows is streamed straight out of the archive, with no {@code java.util.zip}
 * machinery and no temporary file, and so is a whole nested jar. Only the outer archive's own entries, the
 * launcher classes, the manifest and the index itself, are read through a real {@link JarFile} of the outer
 * archive: one close-protected, process-lifetime handle that every connection shares, opened on demand.
 * It is also the jar {@link #getJarFile()} reports for every URL outside a nested jar, and closing it is a
 * no-op under either cache setting, exactly as it is for a {@link NestedJarFile}. Every call to
 * {@link #getInputStream()} returns a fresh stream, because micronaut-core reads service files by
 * disabling caches and then asking for the stream.</p>
 *
 * @since 1.0
 */
public final class RunnerJarURLConnection extends JarURLConnection {

    /** What the JDK reports for a URL that names a jar rather than an entry. */
    private static final String JAR_CONTENT_TYPE = "x-java/jar";

    /** What the JDK reports when it cannot guess a content type. */
    private static final String UNKNOWN_CONTENT_TYPE = "content/unknown";

    private final Index index;
    private final ArchiveSource source;
    private final boolean nested;
    private final int jarId;
    private final String name;
    private final int version;
    private URL jarFileUrl;
    private JarFile jarFile;
    private JarEntry jarEntry;
    private String contentType;
    private int record = IndexFormat.NO_INDEX;

    /**
     * Parses a URL of the registered archive. The handler only builds one of these for a URL whose jar
     * part is the registered archive, so the parsing here is about which jar and which entry.
     *
     * @param url the URL to connect to
     * @throws MalformedURLException if the URL is not a jar URL or no archive is registered
     */
    RunnerJarURLConnection(URL url) throws MalformedURLException {
        super(url);
        this.index = Handlers.index();
        this.source = Handlers.source();
        if (index == null || source == null) {
            throw new MalformedURLException("No runner archive is registered, cannot open " + url);
        }
        String file = url.getFile();
        int first = file.indexOf(Handlers.SEPARATOR);
        int last = Handler.indexOfBangSlash(file) - 1;
        String entry;
        if (last > first) {
            this.nested = true;
            this.jarId = Handlers.jarIdForName(Handlers.decode(file.substring(first + 2, last)));
            entry = file.substring(last + 2);
        } else {
            this.nested = false;
            this.jarId = IndexFormat.APPLICATION_JAR_ID;
            entry = file.substring(first + 2);
        }
        this.name = entry.isEmpty() ? null : Handlers.decode(entry);
        boolean multiRelease = jarId != IndexFormat.NO_INDEX
                && (nested ? index.jarMultiRelease(jarId) : index.applicationMultiRelease());
        this.version = multiRelease ? Index.effectiveMultiReleaseVersion() : Index.BASE_VERSION;
    }

    /**
     * The URL of the jar this connection reads from: the nested jar for a nested entry, and the outer
     * archive's file URL for an entry of the application layer.
     *
     * @return the jar URL
     */
    @Override
    public URL getJarFileURL() {
        if (!nested) {
            return super.getJarFileURL();
        }
        URL url = jarFileUrl;
        if (url == null) {
            url = Handlers.jarFileUrlFor(jarId);
            if (url == null) {
                url = super.getJarFileURL();
            }
            jarFileUrl = url;
        }
        return url;
    }

    /**
     * The name of the entry inside the jar {@link #getJarFileURL()} names, which for a nested entry is the
     * name relative to the nested jar rather than the one the super class would have parsed.
     *
     * @return the entry name, or {@code null} when the URL names a whole jar
     */
    @Override
    public String getEntryName() {
        return name;
    }

    /**
     * Resolves the entry and fails if it does not exist, the way the JDK's connection does.
     *
     * @throws IOException if the URL names no entry of this archive
     */
    @Override
    public void connect() throws IOException {
        if (connected) {
            return;
        }
        if (nested) {
            if (jarId == IndexFormat.NO_INDEX) {
                throw new FileNotFoundException("No nested jar for " + url);
            }
            index.validateJar(jarId);
            if (name != null) {
                record = index.resolveInJar(index.find(name), version, jarId);
                if (record == IndexFormat.NO_INDEX) {
                    throw notFound();
                }
            }
        } else if (name != null) {
            record = applicationRecord();
            if (record == IndexFormat.NO_INDEX && Handlers.jarIdForName(name) == IndexFormat.NO_INDEX) {
                JarFile outer = Handlers.outerJarFile();
                JarEntry entry = outer.getJarEntry(name);
                if (entry != null) {
                    jarFile = outer;
                    jarEntry = entry;
                } else {
                    // Last resort: an outer root name the index carries literally, which is how the
                    // merged META-INF/micronaut/ service directory is recorded. The physical archive is
                    // asked first, so that META-INF/MANIFEST.MF is the outer manifest and not the
                    // application layer's one of the same logical name.
                    record = index.resolveInJar(index.find(name), version,
                            IndexFormat.APPLICATION_JAR_ID);
                    if (record == IndexFormat.NO_INDEX) {
                        throw notFound();
                    }
                }
            }
        }
        connected = true;
    }

    /**
     * The jar this connection reads from, whatever the cache setting: the shared {@link NestedJarFile} view
     * of a nested jar, or the one shared handle on the outer archive. Closing either is a no-op.
     *
     * @return the {@link NestedJarFile} of a nested entry, or the outer archive
     * @throws IOException if the jar cannot be opened
     */
    @Override
    public JarFile getJarFile() throws IOException {
        connect();
        JarFile jar = jarFile;
        if (jar == null) {
            jar = nested ? Handlers.nestedJarFile(jarId) : Handlers.outerJarFile();
            jarFile = jar;
        }
        return jar;
    }

    /**
     * The entry this connection reads.
     *
     * <p>For a versioned entry of the application layer the outer archive holds the bytes under the
     * {@code META-INF/versions/<n>/} name, so that is the entry reported, in the same spirit as
     * {@link JarEntry#getRealName()}.</p>
     *
     * <p>An application layer entry is described from the index record, which is where its size, checksum,
     * compression method and timestamp are recorded and where {@link #getInputStream()} already reads
     * from. That is also the only way a directory of the application layer can be described at all: the
     * layer is stored exploded and the outer archive carries no entry for every directory in it, so
     * delegating to the outer {@link JarFile} would answer {@code null} for a URL that resolves
     * perfectly well, and {@code conn.getJarEntry().isDirectory()} - the usual way to classify a class
     * path URL - would fail with a {@link NullPointerException}.</p>
     *
     * @return the entry, or {@code null} when the URL names a whole jar
     * @throws IOException if the jar cannot be opened
     */
    @Override
    public JarEntry getJarEntry() throws IOException {
        connect();
        if (name == null) {
            return null;
        }
        JarEntry entry = jarEntry;
        if (entry == null) {
            if (nested) {
                entry = getJarFile().getJarEntry(name);
            } else if (record != IndexFormat.NO_INDEX) {
                entry = entryFromRecord();
            } else {
                entry = getJarFile().getJarEntry(physicalName());
            }
            jarEntry = entry;
        }
        return entry;
    }

    /**
     * Describes the resolved index record as a {@link JarEntry}, the way {@link NestedJarEntry} describes
     * a record of a nested jar.
     *
     * @return the entry, named after the outer entry that physically holds the bytes
     */
    private JarEntry entryFromRecord() {
        JarEntry entry = new JarEntry(physicalName());
        int method = index.entryMethod(record);
        if (method == IndexFormat.METHOD_STORED || method == IndexFormat.METHOD_DEFLATED) {
            entry.setMethod(method);
        }
        entry.setSize(index.entryUncompressedSize(record));
        entry.setCompressedSize(index.entryCompressedSize(record));
        entry.setCrc(index.entryCrc32(record));
        long time = NestedJarEntry.dosTimeToMillis(index.entryDosTime(record));
        if (time >= 0) {
            entry.setTime(time);
        }
        return entry;
    }

    /**
     * The manifest of the jar this connection reads from: the nested jar's own manifest for a nested
     * entry, and the outer archive's manifest otherwise.
     *
     * @return the manifest, or {@code null} when the jar has none
     * @throws IOException if the manifest cannot be read
     */
    @Override
    public Manifest getManifest() throws IOException {
        return getJarFile().getManifest();
    }

    /**
     * The main attributes of {@link #getManifest()}.
     *
     * @return the attributes, or {@code null} when the jar has no manifest
     * @throws IOException if the manifest cannot be read
     */
    @Override
    public Attributes getMainAttributes() throws IOException {
        Manifest manifest = getManifest();
        return manifest == null ? null : manifest.getMainAttributes();
    }

    /**
     * Always {@code null}: the packager strips signature files, so nothing in a runner jar is signed and
     * no entry is ever verified.
     *
     * @return {@code null}
     */
    @Override
    public Certificate[] getCertificates() {
        return null;
    }

    /**
     * Opens the content of the entry, or of the whole nested jar when the URL names one.
     *
     * @return a fresh stream over the content
     * @throws IOException if the URL names no entry, or the content cannot be read
     */
    @Override
    public InputStream getInputStream() throws IOException {
        connect();
        if (name == null) {
            if (!nested) {
                throw new IOException("no entry name specified: " + url);
            }
            return openJar(jarId);
        }
        if (record != IndexFormat.NO_INDEX) {
            return openRecord(record);
        }
        int whole = nested ? IndexFormat.NO_INDEX : Handlers.jarIdForName(name);
        if (whole != IndexFormat.NO_INDEX) {
            return openJar(whole);
        }
        InputStream in = getJarFile().getInputStream(getJarEntry());
        if (in == null) {
            throw notFound();
        }
        return in;
    }

    /**
     * The number of bytes {@link #getInputStream()} delivers.
     *
     * @return the uncompressed size of the entry, the length of the jar for a URL that names one, or
     *         {@code -1} when it cannot be determined
     */
    @Override
    public long getContentLengthLong() {
        try {
            connect();
            if (name == null) {
                return nested ? index.jarDataLength(jarId) : -1;
            }
            if (record != IndexFormat.NO_INDEX) {
                return index.entryUncompressedSize(record);
            }
            int whole = nested ? IndexFormat.NO_INDEX : Handlers.jarIdForName(name);
            if (whole != IndexFormat.NO_INDEX) {
                return index.jarDataLength(whole);
            }
            JarEntry entry = getJarEntry();
            return entry == null ? -1 : entry.getSize();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * The content type, guessed from the entry name the way the JDK guesses it.
     *
     * @return the content type, never {@code null}
     */
    @Override
    public String getContentType() {
        String type = contentType;
        if (type == null) {
            if (name == null) {
                type = JAR_CONTENT_TYPE;
            } else {
                type = guessContentTypeFromName(name);
                if (type == null) {
                    type = UNKNOWN_CONTENT_TYPE;
                }
            }
            contentType = type;
        }
        return type;
    }

    /**
     * When the entry was last modified, from the MS-DOS timestamp the archive records, converted in UTC
     * so that the instant does not depend on the machine's time zone.
     *
     * @return the instant in epoch milliseconds, or {@code 0} when it is unknown
     */
    @Override
    public long getLastModified() {
        try {
            connect();
        } catch (IOException e) {
            return 0;
        }
        if (record != IndexFormat.NO_INDEX) {
            long time = NestedJarEntry.dosTimeToMillis(index.entryDosTime(record));
            if (time >= 0) {
                return time;
            }
        }
        File archive = Handlers.archive();
        return archive == null ? 0 : archive.lastModified();
    }

    /**
     * The few header fields that mean something for an archive entry.
     *
     * @param header the header name, compared without regard to case
     * @return the value, or {@code null} when this connection has no such header
     */
    @Override
    public String getHeaderField(String header) {
        if ("content-type".equalsIgnoreCase(header)) {
            return getContentType();
        }
        if ("content-length".equalsIgnoreCase(header)) {
            long length = getContentLengthLong();
            return length < 0 ? null : Long.toString(length);
        }
        return null;
    }

    /**
     * Records the caching preference without the {@link IllegalStateException} the super class throws
     * after a connection is connected.
     *
     * <p>Nothing here caches anything a caller could observe, so the flag cannot make a read fail, and a
     * library that flips it late, as several do right before reading a service file, is simply obliged.
     * Every {@link #getInputStream()} returns a fresh stream. Outer-only entries (the launcher classes, the
     * manifest and the index) are read through the one close-protected, process-lifetime {@link JarFile} of
     * the outer archive under either setting, and closing the jar that {@link #getJarFile()} returns is a
     * no-op under either setting, exactly as for a {@link NestedJarFile}.</p>
     *
     * @param useCaches whether caches may be used
     */
    @Override
    public void setUseCaches(boolean useCaches) {
        this.useCaches = useCaches;
    }

    /**
     * The record of the application layer that backs an outer entry name, or {@link IndexFormat#NO_INDEX}
     * when the URL names an entry the index does not describe, such as a launcher class or the manifest
     * of the outer archive.
     *
     * <p>Two shapes map to jar {@code 0}. An entry under {@value IndexFormat#CLASSES_PREFIX} is the
     * application layer proper, whose logical name is the rest of the name. An entry under
     * {@value IndexFormat#MICRONAUT_SERVICES_PREFIX} is the merged service directory, which the packager
     * writes at the root of the outer archive and indexes under its literal root name; serving it from
     * the index here keeps service discovery from having to parse the outer central directory.</p>
     *
     * @return the entry record
     */
    private int applicationRecord() {
        String logical;
        if (name.startsWith(IndexFormat.CLASSES_PREFIX)) {
            logical = name.substring(IndexFormat.CLASSES_PREFIX.length());
        } else if (name.startsWith(IndexFormat.MICRONAUT_SERVICES_PREFIX)) {
            logical = name;
        } else {
            return IndexFormat.NO_INDEX;
        }
        return index.resolveInJar(index.find(logical), version, IndexFormat.APPLICATION_JAR_ID);
    }

    /**
     * The name of the outer entry that physically holds the bytes of an application layer entry, which
     * differs from the requested name only for a versioned entry.
     *
     * @return the outer entry name
     */
    private String physicalName() {
        if (record == IndexFormat.NO_INDEX) {
            return name;
        }
        int physical = index.entryPhysicalIndex(record);
        if (physical == IndexFormat.NO_INDEX || physical == record) {
            return name;
        }
        StringBuilder outer = new StringBuilder(64);
        outer.append(IndexFormat.CLASSES_PREFIX).append(index.entryName(physical));
        return outer.toString();
    }

    /**
     * Opens the content of an index record.
     *
     * @param entryRecord the record
     * @return the content
     * @throws IOException if the entry cannot be read
     */
    private InputStream openRecord(int entryRecord) throws IOException {
        return index.openEntryStream(entryRecord);
    }

    /**
     * Opens the stored bytes of a whole nested jar, which are a complete jar file in their own right.
     *
     * @param jar the nested jar
     * @return the content
     * @throws IOException if the archive no longer agrees with the index
     */
    private InputStream openJar(int jar) throws IOException {
        index.validateJar(jar);
        long length = index.jarDataLength(jar);
        return source.stream(index.jarDataOffset(jar), length, length, IndexFormat.METHOD_STORED);
    }

    /**
     * The exception the JDK throws for an entry that is not in the jar, with the same shape of message.
     *
     * @return the exception to throw
     */
    private FileNotFoundException notFound() {
        StringBuilder message = new StringBuilder(128);
        message.append("JAR entry ").append(name).append(" not found in ");
        if (nested) {
            message.append(index.jarName(jarId));
        } else {
            message.append(Handlers.archive());
        }
        return new FileNotFoundException(message.toString());
    }
}
