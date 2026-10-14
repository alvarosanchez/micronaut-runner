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
 *   <li>{@code getJarFile()} is the {@code NestedJarFile} view of that dependency</li>
 *   <li>{@code getJarEntry()} is that jar's entry {@code a/B.class}</li>
 * </ul>
 * <h2>The application layer is a jar of its own</h2>
 * <p>An entry of the application layer, {@code jar:file:/app.jar!/MICRONAUT-INF/classes/db/migration/V1.sql},
 * is described the same way, with the layer as the jar:</p>
 * <ul>
 *   <li>{@code getJarFileURL()} is {@code jar:file:/app.jar!/MICRONAUT-INF/classes}</li>
 *   <li>{@code getEntryName()} is {@code db/migration/V1.sql}</li>
 *   <li>{@code getJarFile()} is the {@code NestedJarFile} view of the layer, whose entries are named from the
 *   class path root</li>
 *   <li>{@code getJarEntry()} is that view's entry {@code db/migration/V1.sql}</li>
 * </ul>
 * <p>This is what a plain jar, a nested jar of this archive and Spring Boot's {@code BOOT-INF/classes} all
 * answer, and scanners depend on it. Flyway asks the class loader for {@code db/migration}, takes the
 * connection's {@code getJarFile()} and keeps the entries whose names start with {@code db/migration/}: over
 * the outer archive, where those entries are named {@code MICRONAUT-INF/classes/db/migration/...}, it found
 * none. Only the description changes: the URL text, and with it every code source and seal base, stays as it
 * is. The application layer's root, {@code jar:file:/app.jar!/MICRONAUT-INF/classes/}, which is the code
 * source of every application class, names no entry, as {@code jar:file:/plain.jar!/} does not, and its jar is
 * the layer, which is what a scanner that walks a code source lists. And
 * {@code jar:file:/app.jar!/MICRONAUT-INF/classes!/<name>}, the URL that {@code getJarFileURL()} and
 * {@code getEntryName()} spell together, opens the same entry.</p>
 *
 * <p>What remains outside every jar of the index keeps the ordinary answers over the outer archive: the
 * launcher classes, the manifest, the index, a nested jar addressed as stored bytes, and the merged
 * {@code META-INF/micronaut/} directory at the root. For those the jar URL is {@code file:/app.jar}, the entry
 * name is the outer name, and the jar is the outer archive as an ordinary {@link JarFile}.</p>
 *
 * <p>A URL that names a whole nested jar, whether it ends with {@value Handlers#SEPARATOR} or stops at the
 * jar, streams the bytes of that jar, so that code which wants to treat a dependency as an archive can.</p>
 *
 * <h2>Where the bytes come from</h2>
 * <p>An entry the index knows is streamed straight out of the archive, with no {@code java.util.zip}
 * machinery and no temporary file, and so is a whole nested jar. Only the outer archive's own entries, the
 * launcher classes, the manifest and the index itself, are read through a real {@link JarFile} of the outer
 * archive: one close-protected, process-lifetime handle that every connection shares, opened on demand.
 * It is also the jar {@link #getJarFile()} reports for every URL outside the jars of the index, and closing
 * it is a no-op under either cache setting, exactly as it is for a {@code NestedJarFile} view. Every call to
 * {@link #getInputStream()} returns a fresh stream, because micronaut-core reads service files by
 * disabling caches and then asking for the stream.</p>
 *
 * <p>Only {@link Handler} creates one, and every caller sees it as a {@link JarURLConnection}, so the class is
 * package-private.</p>
 *
 * @since 1.0
 */
final class RunnerJarURLConnection extends JarURLConnection {

    /** What the JDK reports for a URL that names a jar rather than an entry. */
    private static final String JAR_CONTENT_TYPE = "x-java/jar";

    /** What the JDK reports when it cannot guess a content type. */
    private static final String UNKNOWN_CONTENT_TYPE = "content/unknown";

    /**
     * {@code MICRONAUT-INF/classes}: the jar part that names the application layer, in its jar URL and in the
     * {@code ...!/MICRONAUT-INF/classes!/<name>} form of its entries. {@code NestedJarFile} derives its name
     * the same way.
     */
    private static final String APPLICATION_JAR_NAME =
            IndexFormat.CLASSES_PREFIX.substring(0, IndexFormat.CLASSES_PREFIX.length() - 1);

    private final Index index;
    private final ArchiveSource source;
    /**
     * Whether the URL names an entry of a jar of the index, a nested jar or the application layer, which is
     * then described as a jar of its own. Otherwise it names an entry of the outer archive.
     */
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
        if (last > first) {
            String jar = Handlers.decode(file.substring(first + 2, last));
            String entry = file.substring(last + 2);
            this.nested = true;
            this.jarId = APPLICATION_JAR_NAME.equals(jar)
                    ? IndexFormat.APPLICATION_JAR_ID : Handlers.jarIdForName(jar);
            this.name = entry.isEmpty() ? null : Handlers.decode(entry);
        } else {
            String entry = file.substring(first + 2);
            String outer = entry.isEmpty() ? null : Handlers.decode(entry);
            this.jarId = IndexFormat.APPLICATION_JAR_ID;
            if (outer != null && outer.startsWith(IndexFormat.CLASSES_PREFIX)) {
                // An entry of the application layer, described as an entry of the layer rather than of
                // the outer archive: its name is the logical one, and the layer's root names no entry.
                this.nested = true;
                this.name = outer.length() == IndexFormat.CLASSES_PREFIX.length()
                        ? null : outer.substring(IndexFormat.CLASSES_PREFIX.length());
            } else {
                this.nested = false;
                this.name = outer;
            }
        }
        boolean multiRelease = jarId != IndexFormat.NO_INDEX
                && (jarId == IndexFormat.APPLICATION_JAR_ID
                        ? index.applicationMultiRelease() : index.jarMultiRelease(jarId));
        this.version = multiRelease ? Index.effectiveMultiReleaseVersion() : Index.BASE_VERSION;
    }

    /**
     * The URL of the jar this connection reads from: {@code jar:file:/app.jar!/MICRONAUT-INF/lib/dep.jar}
     * for an entry of a nested jar, {@code jar:file:/app.jar!/MICRONAUT-INF/classes} for an entry of the
     * application layer, and the outer archive's file URL for an entry of the outer archive.
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
            url = jarId == IndexFormat.APPLICATION_JAR_ID ? applicationJarFileUrl() : Handlers.jarFileUrlFor(jarId);
            if (url == null) {
                url = super.getJarFileURL();
            }
            jarFileUrl = url;
        }
        return url;
    }

    /**
     * The jar URL of the application layer, {@code jar:file:/app.jar!/MICRONAUT-INF/classes}, in the same
     * style as a nested jar's. It is built with this connection's URL as the context, which lends it this
     * package's handler, so the URL opens whether or not the handler property took effect.
     *
     * @return the URL, or {@code null} when no archive is registered
     */
    @SuppressWarnings("deprecation")
    private URL applicationJarFileUrl() {
        URL archive = Handlers.jarFileUrlFor(IndexFormat.APPLICATION_JAR_ID);
        if (archive == null) {
            return null;
        }
        String archiveSpec = archive.toExternalForm();
        StringBuilder spec = new StringBuilder(archiveSpec.length() + APPLICATION_JAR_NAME.length() + 8);
        spec.append("jar:").append(archiveSpec).append(Handlers.SEPARATOR).append(APPLICATION_JAR_NAME);
        try {
            return new URL(url, spec.toString());
        } catch (MalformedURLException e) {
            return null;
        }
    }

    /**
     * The name of the entry inside the jar {@link #getJarFileURL()} names, which for an entry of a nested jar
     * or of the application layer is the name relative to that jar rather than the one the super class would
     * have parsed.
     *
     * @return the entry name, or {@code null} when the URL names a whole jar or the application layer's root
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
     * The jar this connection reads from, whatever the cache setting: the shared {@code NestedJarFile} view
     * of a nested jar or of the application layer, or the one shared handle on the outer archive. Closing
     * any of them is a no-op.
     *
     * @return the {@code NestedJarFile} view of the jar the entry belongs to, or the outer archive
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
     * <p>An entry of a nested jar or of the application layer is the {@code NestedJarFile} view's entry, so
     * a directory is described too, from the record the index synthesised for it: the application layer is
     * stored exploded and the outer archive carries no entry for a directory in it, and
     * {@code conn.getJarEntry().isDirectory()} is the usual way to classify a class path URL.</p>
     *
     * <p>An entry of the merged service directory is described from the index record, which is where its
     * size, checksum, compression method and timestamp are recorded and where {@link #getInputStream()}
     * already reads from. For a versioned record the outer archive holds the bytes under the
     * {@code META-INF/versions/<n>/} name, so that is the entry reported, in the same spirit as
     * {@link JarEntry#getRealName()}.</p>
     *
     * @return the entry, or {@code null} when the URL names a whole jar or the application layer's root
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
     * Describes the resolved index record as a {@link JarEntry}, the way {@code NestedJarEntry} describes
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
        long time = DosTime.toMillis(index.entryDosTime(record));
        if (time >= 0) {
            entry.setTime(time);
        }
        return entry;
    }

    /**
     * The manifest of the jar this connection reads from: the nested jar's own manifest for an entry of a
     * nested jar, and the outer archive's for an entry outside the jars of the index. For an entry of the
     * application layer it is the layer's own when the layer has one, and otherwise the outer archive's, which
     * carries the application's attributes: the packager builds the layer from class directories, which carry
     * no manifest. A fat jar likewise reports its own manifest for every class in it.
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
     * <p>The application layer's root, the code source of every application class, opens as an empty
     * stream. The layer is stored exploded, so there are no bytes of a jar to stream, but code that asks
     * where it is running from opens its code source, and that succeeds for the directory code source of a
     * {@code URLClassLoader}, as it does here.</p>
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
            return jarId == IndexFormat.APPLICATION_JAR_ID ? InputStream.nullInputStream() : openJar(jarId);
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
     * @return the uncompressed size of the entry, the length of the jar for a URL that names one, {@code 0}
     *         for the application layer's root, or {@code -1} when it cannot be determined
     */
    @Override
    public long getContentLengthLong() {
        try {
            connect();
            if (name == null) {
                if (!nested) {
                    return -1;
                }
                return jarId == IndexFormat.APPLICATION_JAR_ID ? 0 : index.jarDataLength(jarId);
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
            long time = DosTime.toMillis(index.entryDosTime(record));
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
     * no-op under either setting, exactly as for a {@code NestedJarFile} view.</p>
     *
     * @param useCaches whether caches may be used
     */
    @Override
    public void setUseCaches(boolean useCaches) {
        this.useCaches = useCaches;
    }

    /**
     * The record of jar {@code 0} that backs an outer entry name, or {@link IndexFormat#NO_INDEX} when the
     * URL names an entry the index does not describe, such as a launcher class or the manifest of the outer
     * archive.
     *
     * <p>An entry under {@value IndexFormat#MICRONAUT_SERVICES_PREFIX} is the merged service directory,
     * which the packager writes at the root of the outer archive and indexes as part of jar {@code 0} under
     * its literal root name; serving it from the index here keeps service discovery from having to parse
     * the outer central directory. An entry under {@value IndexFormat#CLASSES_PREFIX} never gets here: it is
     * an entry of the application layer, which the constructor routes to the layer.</p>
     *
     * @return the entry record
     */
    private int applicationRecord() {
        if (!name.startsWith(IndexFormat.MICRONAUT_SERVICES_PREFIX)) {
            return IndexFormat.NO_INDEX;
        }
        return index.resolveInJar(index.find(name), version, IndexFormat.APPLICATION_JAR_ID);
    }

    /**
     * The name of the outer entry that physically holds the bytes of a jar {@code 0} record reached through
     * the outer archive, which differs from the requested name only for a versioned record.
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
