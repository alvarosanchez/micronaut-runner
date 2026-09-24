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

import io.micronaut.runner.protocol.jar.Handler;
import io.micronaut.runner.protocol.jar.RunnerJarURLConnection;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;

/**
 * The {@code jar:} URL machinery of a runner jar: it owns the registration of
 * {@link io.micronaut.runner.protocol.jar.Handler}, the state that handler serves, and the construction of
 * every URL the class loader hands out.
 *
 * <h2>Why a {@code jar:} URL at all</h2>
 * <p>Almost nothing in a Micronaut application asks the class loader for bytes directly. Micronaut's own
 * resource loader, Flyway, Liquibase, {@code ServiceLoader} and Logback all discover things through
 * {@link URL} and {@link JarFile}, and many of them parse the URL string, pass it around, or reopen it
 * later. So the archive has to be reachable through ordinary URLs, and those URLs have to keep the shape
 * those libraries already understand:</p>
 * <pre>
 * jar:file:/abs/app.jar!/MICRONAUT-INF/classes/&lt;name&gt;             the application layer, jar 0
 * jar:file:/abs/app.jar!/MICRONAUT-INF/lib/&lt;dep&gt;.jar!/&lt;name&gt;       an entry of a nested jar
 * jar:file:/abs/app.jar!/META-INF/micronaut/&lt;service&gt;/&lt;name&gt;       the merged service directory
 * </pre>
 * <p>The last shape is the exception to "jar 0 lives under {@value IndexFormat#CLASSES_PREFIX}": the
 * packager merges the Micronaut service metadata of every jar into the root of the outer archive, and
 * those records are addressed where they really are. See {@link #urlFor}.</p>
 * <p>The nested form is the classic double separator Spring Boot's loader produced for a decade, which
 * micronaut-core's {@code IOUtils} already tolerates; inventing a protocol of our own would break service
 * scanning outright. Entry names are percent-encoded per UTF-8 byte, so a name containing a space,
 * {@code '%'}, {@code '#'}, {@code '?'}, {@code '['} or {@code ']'} cannot corrupt the URL, and the same
 * URL survives a round trip through {@link URL#toString()} and {@link URI}.</p>
 *
 * <h2>How the handler gets installed</h2>
 * <p>{@code jar} is the one protocol whose handler cannot be contributed with a
 * {@code URLStreamHandlerProvider}: {@code URL.getURLStreamHandler} skips the provider lookup for it, and a
 * {@code URLStreamHandlerFactory} can only be installed once per JVM, so claiming it would take a
 * capability the application may want. That leaves the {@value #HANDLER_PACKAGES_PROPERTY} property, which
 * makes the JDK load {@code <package>.jar.Handler} by name; {@link #register} appends
 * {@value #PROTOCOL_PACKAGE} to it and then flushes the JDK's handler cache by installing a {@code null}
 * factory, because by the time the launcher runs the JDK has usually resolved and cached its own jar
 * handler already.</p>
 *
 * <p>None of that is load bearing. {@link #urlFor} and {@link #codeSourceUrlFor} build their URLs from the
 * encoders' output with an explicit handler instance, so the URLs the class loader returns work whether or
 * not the property took effect. The property only matters for a URL that is re-parsed from its string form
 * by somebody else, which is why {@link #register} verifies it and prints one warning when it did not take,
 * rather than failing the launch.</p>
 *
 * <p>Each of those URLs is built with a single parse, the handler's own, and no {@link URI} in front of it:
 * that parse is where {@code getResources} spends its time, once per contributing jar. Nothing is lost by
 * skipping the URI, because every spec is a valid URI by construction: after {@code jar:}, the encoders
 * emit only unreserved ASCII, {@code '/'}, {@code ':'} (in the archive path only) and a {@code %XX} escape
 * per UTF-8 byte of anything else, and the jar and entry parts are joined by {@value #SEPARATOR}.
 * {@code HandlerTest} checks that guarantee once, together with the fields of every URL shape against its
 * re-parsed string form, instead of every call paying for it.</p>
 *
 * @since 1.0
 */
public final class Handlers {

    /**
     * The package added to {@value #HANDLER_PACKAGES_PROPERTY}. The JDK appends {@code .jar.Handler} to it,
     * so the handler class has to be {@code io.micronaut.runner.protocol.jar.Handler} and has to be public
     * with a public no-argument constructor: the JDK instantiates its own copy reflectively.
     */
    public static final String PROTOCOL_PACKAGE = "io.micronaut.runner.protocol";

    /** The JDK property listing packages that contribute {@link java.net.URLStreamHandler}s. */
    public static final String HANDLER_PACKAGES_PROPERTY = "java.protocol.handler.pkgs";

    /** The separator between a jar and the entry inside it, in a {@code jar:} URL. */
    public static final String SEPARATOR = "!/";

    /** Guards registration and the lazily opened jar files. */
    private static final Object LOCK = new Object();

    /** Upper case hexadecimal digits, for percent encoding. */
    private static final char[] HEX = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };

    /** A jar URL of a file that need not exist, used only to capture the JDK's own handler. */
    private static final String DEFAULT_CONTEXT_SPEC = "jar:file:/micronaut-runner-default-context.jar!/";

    private static File archiveFile;
    private static Index archiveIndex;
    private static ArchiveSource archiveSource;
    private static Handler archiveHandler;
    private static String archiveJarPrefix;
    private static String[] jarPrefixes;
    private static String[] jarNames;
    private static volatile URL defaultContext;
    private static String canonicalPath;
    private static SharedJarFile outerJarFile;
    private static NestedJarFile[] nestedJarFiles;

    /**
     * The URL form of the archive, {@code file:/abs/app.jar}. Written last and read first, so that a thread
     * that sees it non-null sees every other field of a completed registration.
     */
    private static volatile String archiveUrl;

    private Handlers() {
    }

    /**
     * Installs the {@code jar:} protocol handler for one archive and records everything the handler needs
     * to serve it.
     *
     * <p>This never throws. Every step that can fail (the property, the handler cache, the verification)
     * only affects URLs that somebody re-parses from a string; the URLs this class hands out carry the
     * handler instance with them and keep working regardless. Registration happens once per JVM: a second
     * call for the same archive does nothing, and a call for a different archive is reported on stderr and
     * ignored, because one process can only have one {@code jar:} handler and the archive is part of its
     * identity.</p>
     *
     * @param archive the outer archive, as the launcher located it
     * @param index   the index of that archive
     * @param source  the open reader of that archive
     */
    public static void register(File archive, Index index, ArchiveSource source) {
        if (archive == null || index == null || source == null) {
            return;
        }
        File absolute = archive.getAbsoluteFile();
        synchronized (LOCK) {
            if (archiveUrl != null) {
                if (!absolute.equals(archiveFile)) {
                    StringBuilder message = new StringBuilder(256);
                    message.append("micronaut-runner: ").append(absolute)
                            .append(" cannot be registered because ").append(archiveFile)
                            .append(" already owns the jar: protocol in this JVM; its URLs will not open");
                    System.err.println(message.toString());
                }
                return;
            }
            defaultContext = captureDefaultContext();
            archiveFile = absolute;
            archiveIndex = index;
            archiveSource = source;
            archiveHandler = new Handler();
            jarPrefixes = new String[index.jarCount()];
            jarNames = new String[index.jarCount()];
            nestedJarFiles = new NestedJarFile[index.jarCount()];
            StringBuilder url = new StringBuilder(fileUrl(absolute.getPath(), File.separatorChar));
            StringBuilder prefix = new StringBuilder(url.length() + 6);
            prefix.append("jar:").append(url).append(SEPARATOR);
            archiveJarPrefix = prefix.toString();
            archiveUrl = url.toString();
            install();
        }
    }

    /**
     * A URL for one logical entry of one jar of the registered archive.
     *
     * <p>The URL carries the handler instance, so it opens even when the handler could not be installed
     * process-wide. The name is percent-encoded per UTF-8 byte.</p>
     *
     * <p>One family of jar {@code 0} names is not under {@value IndexFormat#CLASSES_PREFIX}: the merged
     * Micronaut service directory. The packager writes those entries at the <em>root</em> of the outer
     * archive and records them as jar {@code 0} records whose logical name is that literal root name, so
     * their URL has to name the root too. That is not cosmetic. Whoever receives the URL is entitled to
     * treat the part after {@value #SEPARATOR} as a name the archive really carries - by asking this
     * connection for {@link java.net.JarURLConnection#getEntryName()}, by looking it up in a
     * {@link JarFile}, or by resolving it as a path inside a zip file system, which is what
     * micronaut-core does when it cannot list the outer archive directly - and
     * {@value IndexFormat#CLASSES_PREFIX} + the name is not in the outer central directory.</p>
     *
     * @param jarId       the jar, {@code 0} being the application layer
     * @param logicalName the entry name relative to that jar
     * @return the URL, or {@code null} if no archive is registered, the jar does not exist, or the name
     *         cannot be expressed as a URL
     */
    public static URL urlFor(int jarId, String logicalName) {
        if (logicalName == null) {
            return null;
        }
        if (jarId == IndexFormat.APPLICATION_JAR_ID
                && logicalName.startsWith(IndexFormat.MICRONAUT_SERVICES_PREFIX)) {
            return outerUrlFor(logicalName);
        }
        String prefix = prefixFor(jarId);
        if (prefix == null) {
            return null;
        }
        return entryUrl(prefix, logicalName);
    }

    /**
     * A URL for an entry of the outer archive itself, addressed by its real name rather than through a
     * jar of the index.
     *
     * <p>This is the shape the merged {@code META-INF/micronaut/} service directory needs, because those
     * entries live at the root of the outer archive rather than under the application layer's prefix, and
     * it is also how a caller reaches the launcher's own classes, the manifest or a nested jar as stored
     * bytes.</p>
     *
     * @param outerEntryName the entry name as the outer archive stores it
     * @return {@code jar:file:/abs/app.jar!/<name>}, or {@code null} when no archive is registered or the
     *         name cannot be expressed as a URL
     */
    public static URL outerUrlFor(String outerEntryName) {
        if (archiveUrl == null || outerEntryName == null) {
            return null;
        }
        return entryUrl(archiveJarPrefix, outerEntryName);
    }

    /**
     * The URL that identifies a whole jar of the registered archive: the {@code CodeSource} location of
     * every class defined from it, and the seal base of every package it seals.
     *
     * <p>It ends with a slash, the way {@code URLClassLoader} writes a directory code source, so that a
     * sealed package's base URL is a prefix of every entry URL of that jar.</p>
     *
     * @param jarId the jar, {@code 0} being the application layer
     * @return {@code jar:file:/abs/app.jar!/MICRONAUT-INF/classes/} for jar {@code 0},
     *         {@code jar:file:/abs/app.jar!/MICRONAUT-INF/lib/<dep>.jar!/} for a nested jar, or
     *         {@code null} when no archive is registered or the jar does not exist
     */
    public static URL codeSourceUrlFor(int jarId) {
        String prefix = prefixFor(jarId);
        if (prefix == null) {
            return null;
        }
        return url(prefix);
    }

    /**
     * The URL of the jar itself, as {@link java.net.JarURLConnection#getJarFileURL()} reports it: the file
     * URL of the outer archive for the application layer, and the {@code jar:} URL of the nested jar
     * otherwise. Unlike {@link #codeSourceUrlFor} it does not end with a separator, because it names a jar
     * rather than the root inside it.
     *
     * @param jarId the jar, {@code 0} being the application layer
     * @return the URL, or {@code null} when no archive is registered or the jar does not exist
     */
    public static URL jarFileUrlFor(int jarId) {
        String base = archiveUrl;
        Index index = archiveIndex;
        if (base == null || jarId < 0 || jarId >= index.jarCount()) {
            return null;
        }
        if (jarId == IndexFormat.APPLICATION_JAR_ID) {
            try {
                return URI.create(base).toURL();
            } catch (MalformedURLException | IllegalArgumentException e) {
                return null;
            }
        }
        StringBuilder spec = new StringBuilder(archiveJarPrefix.length() + 32);
        spec.append(archiveJarPrefix);
        encodeName(spec, jarName(jarId), 0);
        return url(spec.toString());
    }

    /**
     * Whether an archive is registered. Everything else in this class returns {@code null} or does nothing
     * until it is.
     *
     * @return {@code true} once {@link #register} has completed
     */
    public static boolean registered() {
        return archiveUrl != null;
    }

    /**
     * The registered archive.
     *
     * @return the outer archive file, or {@code null}
     */
    public static File archive() {
        return archiveUrl == null ? null : archiveFile;
    }

    /**
     * The index of the registered archive.
     *
     * @return the index, or {@code null}
     */
    public static Index index() {
        return archiveUrl == null ? null : archiveIndex;
    }

    /**
     * The reader of the registered archive.
     *
     * @return the source, or {@code null}
     */
    public static ArchiveSource source() {
        return archiveUrl == null ? null : archiveSource;
    }

    /**
     * Whether the jar part of a {@code jar:} URL names the registered archive, which is how the handler
     * decides between serving a URL itself and handing it to the JDK.
     *
     * <p>The common case is a string comparison against the form this class produces. A URL written by
     * somebody else may spell the same file differently, so the fallback decodes the path and compares it
     * as a file, first by absolute and then by canonical path. Nothing here resolves a host name: a
     * {@code jar:http:} URL simply is not ours.</p>
     *
     * @param jarFileSpec the part of a {@code jar:} URL before the first {@value #SEPARATOR}, for example
     *                    {@code file:/abs/app.jar}
     * @return {@code true} when that names the registered archive
     */
    public static boolean owns(String jarFileSpec) {
        String url = archiveUrl;
        if (url == null || jarFileSpec == null) {
            return false;
        }
        if (url.equals(jarFileSpec)) {
            return true;
        }
        return ownsFile(jarFileSpec);
    }

    /**
     * The jar of the registered archive whose name is {@code name}, for example
     * {@code MICRONAUT-INF/lib/dep.jar}.
     *
     * @param name the jar name as the index records it
     * @return the jar index, or {@link IndexFormat#NO_INDEX} when the archive has no such jar
     */
    public static int jarIdForName(String name) {
        if (archiveUrl == null || name == null) {
            return IndexFormat.NO_INDEX;
        }
        int count = jarNames.length;
        for (int jarId = 1; jarId < count; jarId++) {
            if (name.equals(jarName(jarId))) {
                return jarId;
            }
        }
        return IndexFormat.NO_INDEX;
    }

    /**
     * The shared {@link NestedJarFile} view of one nested jar. Views are created once and cached, because
     * they are immutable, because each one holds a handle on the outer file, and because a library that
     * scans the classpath asks for the same jar over and over.
     *
     * @param jarId the nested jar
     * @return the view
     * @throws IOException if the outer archive cannot be opened
     */
    public static NestedJarFile nestedJarFile(int jarId) throws IOException {
        if (archiveUrl == null || jarId <= IndexFormat.APPLICATION_JAR_ID
                || jarId >= archiveIndex.jarCount()) {
            throw new IOException("No nested jar " + jarId + " in the registered archive");
        }
        synchronized (LOCK) {
            NestedJarFile[] cache = nestedJarFiles;
            NestedJarFile jar = cache[jarId];
            if (jar == null) {
                jar = new NestedJarFile(archiveFile, archiveIndex, archiveSource, jarId);
                cache[jarId] = jar;
            }
            return jar;
        }
    }

    /**
     * The outer archive as an ordinary {@link JarFile}, for the entries that are not in the index: the
     * launcher's own classes, the manifest, the index itself and the nested jars as stored bytes. It is
     * opened on demand, because a normal application start never needs it.
     *
     * @return the shared jar file
     * @throws IOException if the archive cannot be opened
     */
    public static JarFile outerJarFile() throws IOException {
        return outerJarFile(true);
    }

    /**
     * The outer archive as either the shared process-lifetime handle or a handle owned by one connection.
     *
     * @param useCaches whether the shared handle may be used
     * @return the shared jar file, or a new independently closeable jar file when caching is disabled
     * @throws IOException if the archive cannot be opened
     */
    public static JarFile outerJarFile(boolean useCaches) throws IOException {
        if (archiveUrl == null) {
            throw new IOException("No runner archive is registered");
        }
        if (!useCaches) {
            return new JarFile(archiveFile, false, JarFile.OPEN_READ, JarFile.runtimeVersion());
        }
        synchronized (LOCK) {
            if (outerJarFile == null) {
                outerJarFile = new SharedJarFile(archiveFile);
            }
            return outerJarFile;
        }
    }

    /**
     * Opens a {@code jar:} URL that is not ours with the JDK's own handler.
     *
     * <p>Installing a handler for {@code jar:} makes this process-wide, so every {@code jar:file:} URL of
     * another file and every {@code jar:http:} URL in the JVM lands on our handler and has to keep working
     * exactly as before. The URL is rebuilt against a context captured before the installation, which
     * carries the JDK's handler with it; that is the only way to reach a handler the JDK never exposes.</p>
     *
     * @param url the URL to open
     * @return the connection the JDK would have returned
     * @throws IOException if the JDK's handler was never captured or the URL cannot be opened
     */
    @SuppressWarnings("deprecation")
    public static URLConnection openDelegate(URL url) throws IOException {
        URL context = defaultContext;
        if (context == null) {
            throw new IOException("The JDK's jar: handler was not captured, cannot open " + url);
        }
        return new URL(context, url.toExternalForm()).openConnection();
    }

    /**
     * Forgets the registered archive and closes the jar files opened for it.
     *
     * <p>The launcher never calls this: an application owns its archive until the JVM exits. Tests and
     * tools that work through more than one archive in one process do, because registration is otherwise
     * once per JVM. It does not uninstall the protocol handler, which cannot be uninstalled; the handler
     * simply delegates everything to the JDK while nothing is registered.</p>
     */
    public static void unregister() {
        synchronized (LOCK) {
            archiveUrl = null;
            archiveFile = null;
            archiveIndex = null;
            archiveSource = null;
            archiveHandler = null;
            archiveJarPrefix = null;
            jarPrefixes = null;
            jarNames = null;
            canonicalPath = null;
            NestedJarFile[] nested = nestedJarFiles;
            nestedJarFiles = null;
            if (nested != null) {
                for (int i = 0; i < nested.length; i++) {
                    if (nested[i] != null) {
                        nested[i].closeNested();
                    }
                }
            }
            SharedJarFile outer = outerJarFile;
            outerJarFile = null;
            if (outer != null) {
                try {
                    outer.closeShared();
                } catch (IOException ignored) {
                    // Nothing can be done about it and nothing depends on it.
                }
            }
        }
    }

    /**
     * Builds the URL of an entry under an encoded prefix, the one spec builder behind {@link #urlFor} and
     * {@link #outerUrlFor}.
     *
     * <p>Leading slashes are dropped. Most names need no escaping at all, so the name is scanned once for
     * its plain run of unreserved ASCII and {@code '/'}: when that run is the whole name, the spec is one
     * {@link String#concat} of the prefix and the name, a single copy. Otherwise the plain run is appended
     * and {@link #encodeName} resumes at the first character that needs escaping, which is exact because
     * the encoder treats every character before the first non-ASCII one on its own, and the plain run
     * contains none.</p>
     *
     * @param prefix the encoded prefix, ending with {@value #SEPARATOR} or a directory slash
     * @param name   the entry name, not yet encoded
     * @return the URL, or {@code null} when the spec is rejected
     */
    private static URL entryUrl(String prefix, String name) {
        int length = name.length();
        int at = 0;
        while (at < length && name.charAt(at) == '/') {
            at++;
        }
        int plain = at;
        while (plain < length && (unreserved(name.charAt(plain)) || name.charAt(plain) == '/')) {
            plain++;
        }
        if (plain == length) {
            // The launcher compiles with -XDstringConcat=inline, so '+' would go through a StringBuilder
            // and copy twice.
            return url(prefix.concat(at == 0 ? name : name.substring(at)));
        }
        StringBuilder spec = new StringBuilder(prefix.length() + (length - at) + 16);
        spec.append(prefix).append(name, at, plain);
        encodeName(spec, name, plain);
        return url(spec.toString());
    }

    /**
     * Percent-encodes a name into a URL, one escape per UTF-8 byte, leaving the unreserved set and the
     * path separator alone.
     *
     * <p>Encoding by byte rather than by character is what makes a name with a space, a {@code '%'}, a
     * {@code '#'}, a {@code '?'}, a bracket or a non-ASCII character survive the round trip through a URL
     * string and back. It also encodes {@code '!'}, so that a name can never fake the
     * {@value #SEPARATOR} that separates a jar from its entries.</p>
     *
     * @param out   the builder to append to
     * @param name  the name to encode
     * @param from  the first character to encode, so that a caller can skip a leading slash
     */
    private static void encodeName(StringBuilder out, String name, int from) {
        int length = name.length();
        for (int i = from; i < length; i++) {
            char c = name.charAt(i);
            if (c < 0x80) {
                if (unreserved(c) || c == '/') {
                    out.append(c);
                } else {
                    escape(out, (byte) c);
                }
                continue;
            }
            byte[] utf8 = name.substring(i).getBytes(StandardCharsets.UTF_8);
            for (int b = 0; b < utf8.length; b++) {
                byte value = utf8[b];
                if (value >= 0 && (unreserved((char) value) || value == '/')) {
                    out.append((char) value);
                } else {
                    escape(out, value);
                }
            }
            return;
        }
    }

    /**
     * Builds the {@code file:} URL of the archive from a file system path.
     *
     * <p>The scheme specific part has to begin with {@code '/'} for the URI to be <em>hierarchical</em>.
     * A POSIX path already begins with one. A Windows path begins with a drive letter, so without an
     * added separator the URL reads {@code file:C:/dir/app.jar}, which {@link java.net.URI} classifies as
     * opaque and {@code new File(URI)} rejects with "URI is not hierarchical".</p>
     *
     * <p>That is not a cosmetic difference. micronaut-core enumerates the merged service directory by
     * taking the part of this URL before the archive separator and handing it to
     * {@code new File(URI.create(...))}, so an opaque URL breaks bean discovery, on Windows only.</p>
     *
     * <p>The canonical form uses {@code '/'} as the URL separator over the complete path, including every
     * component after non-ASCII text. Other characters are percent-encoded as UTF-8 bytes. A backslash is
     * normalized only when it is the supplied platform separator; in a POSIX path it remains data and is
     * encoded as {@code %5C}.</p>
     *
     * <p>The separator is a parameter rather than {@link File#separatorChar} so that both platforms'
     * shapes can be tested from either platform.</p>
     *
     * @param path      the absolute path of the archive
     * @param separator the platform's file separator
     * @return the {@code file:} URL
     */
    static String fileUrl(String path, char separator) {
        StringBuilder out = new StringBuilder(path.length() + 8);
        out.append("file:");
        if (path.isEmpty() || (path.charAt(0) != '/' && path.charAt(0) != separator)) {
            out.append('/');
        }
        encodePath(out, path, separator);
        return out.toString();
    }

    private static void encodePath(StringBuilder out, String path) {
        encodePath(out, path, File.separatorChar);
    }

    private static void encodePath(StringBuilder out, String path, char separator) {
        int length = path.length();
        for (int i = 0; i < length; i++) {
            char c = path.charAt(i);
            if (c == separator) {
                c = '/';
            }
            if (c < 0x80) {
                if (unreserved(c) || c == '/' || c == ':') {
                    out.append(c);
                } else {
                    escape(out, (byte) c);
                }
                continue;
            }
            String remainder = path.substring(i);
            if (separator != '/') {
                remainder = remainder.replace(separator, '/');
            }
            byte[] utf8 = remainder.getBytes(StandardCharsets.UTF_8);
            for (int b = 0; b < utf8.length; b++) {
                byte value = utf8[b];
                if (value >= 0 && (unreserved((char) value) || value == '/' || value == ':')) {
                    out.append((char) value);
                } else {
                    escape(out, value);
                }
            }
            return;
        }
    }

    private static boolean unreserved(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~';
    }

    private static void escape(StringBuilder out, byte value) {
        out.append('%');
        out.append(HEX[(value >> 4) & 0xF]);
        out.append(HEX[value & 0xF]);
    }

    private static String jarName(int jarId) {
        String[] names = jarNames;
        String name = names[jarId];
        if (name == null) {
            name = archiveIndex.jarName(jarId);
            names[jarId] = name;
        }
        return name;
    }

    private static String prefixFor(int jarId) {
        if (archiveUrl == null) {
            return null;
        }
        String base = archiveJarPrefix;
        String[] cache = jarPrefixes;
        if (jarId < 0 || jarId >= cache.length) {
            return null;
        }
        String prefix = cache[jarId];
        if (prefix != null) {
            return prefix;
        }
        StringBuilder spec = new StringBuilder(base.length() + 48);
        spec.append(base);
        if (jarId == IndexFormat.APPLICATION_JAR_ID) {
            encodeName(spec, IndexFormat.CLASSES_PREFIX, 0);
        } else {
            encodeName(spec, jarName(jarId), 0);
            spec.append(SEPARATOR);
        }
        prefix = spec.toString();
        cache[jarId] = prefix;
        return prefix;
    }

    /**
     * Builds a URL of the registered archive from a spec this class encoded, with one parse.
     *
     * <p>This is the constructor {@link URL#of(URI, java.net.URLStreamHandler)} ends in, called with the
     * same string, so every field, {@code equals}, {@code hashCode}, {@code toExternalForm} and
     * {@code toURI} come out the same. What it skips is the {@link URI} parse in front of it, which only
     * validated a spec the encoders already guarantee to be a valid URI (see the class documentation) and
     * cost more than the URL parse itself. The constructor wraps anything {@link Handler#parseURL} throws
     * in a {@link MalformedURLException}.</p>
     *
     * @param spec a complete {@code jar:} spec, the archive's prefix followed by encoder output
     * @return the URL, or {@code null} when the spec is rejected
     */
    @SuppressWarnings("deprecation")
    private static URL url(String spec) {
        try {
            return new URL((URL) null, spec, archiveHandler);
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static URL captureDefaultContext() {
        if (defaultContext != null) {
            return defaultContext;
        }
        try {
            return URI.create(DEFAULT_CONTEXT_SPEC).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Adds our package to the handler property, drops the JDK's cached jar handler, and checks the result.
     * Called with {@link #LOCK} held, once per JVM.
     */
    private static void install() {
        if (defaultContext == null) {
            warn("the JDK's own jar: handler could not be captured");
            return;
        }
        String existing = System.getProperty(HANDLER_PACKAGES_PROPERTY);
        if (existing == null || existing.isEmpty()) {
            System.setProperty(HANDLER_PACKAGES_PROPERTY, PROTOCOL_PACKAGE);
        } else if (!lists(existing, PROTOCOL_PACKAGE)) {
            StringBuilder value = new StringBuilder(existing.length() + PROTOCOL_PACKAGE.length() + 1);
            value.append(existing).append('|').append(PROTOCOL_PACKAGE);
            System.setProperty(HANDLER_PACKAGES_PROPERTY, value.toString());
        }
        try {
            // Clears the handler cache as a side effect. It throws when a factory is already installed,
            // which is exactly the case where the handler cannot be replaced and we must give up quietly.
            URL.setURLStreamHandlerFactory(null);
        } catch (Error ignored) {
            // A URLStreamHandlerFactory owns the protocol; the verification below reports the consequence.
        }
        if (!verify()) {
            warn("the jar: protocol handler could not be installed");
        }
    }

    /**
     * Builds a URL of this archive from its string form, the way a library would, and checks that opening
     * it lands on our connection rather than the JDK's.
     *
     * @return whether the handler is in place for URLs parsed from strings
     */
    private static boolean verify() {
        String spec = prefixFor(archiveIndex.jarCount() > 1 ? 1 : IndexFormat.APPLICATION_JAR_ID);
        if (spec == null) {
            return false;
        }
        try {
            URLConnection connection = URI.create(spec).toURL().openConnection();
            return connection instanceof RunnerJarURLConnection;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void warn(String reason) {
        StringBuilder message = new StringBuilder(320);
        message.append("micronaut-runner: ").append(reason)
                .append("; nested jar URLs re-parsed from their string form will not open. ")
                .append("URLs handed out by the class loader are unaffected and keep working.");
        System.err.println(message.toString());
    }

    private static boolean lists(String property, String value) {
        int at = property.indexOf(value);
        while (at >= 0) {
            boolean startsSegment = at == 0 || property.charAt(at - 1) == '|';
            int end = at + value.length();
            boolean endsSegment = end == property.length() || property.charAt(end) == '|';
            if (startsSegment && endsSegment) {
                return true;
            }
            at = property.indexOf(value, at + 1);
        }
        return false;
    }

    /**
     * The fallback of {@link #owns}: the spec spells a file differently from the way this class does.
     *
     * @param jarFileSpec the jar part of a {@code jar:} URL
     * @return whether it denotes the registered archive
     */
    private static boolean ownsFile(String jarFileSpec) {
        if (!jarFileSpec.regionMatches(true, 0, "file:", 0, 5)) {
            return false;
        }
        String path = jarFileSpec.substring(5);
        if (path.startsWith("//")) {
            int slash = path.indexOf('/', 2);
            if (slash < 0) {
                return false;
            }
            String authority = path.substring(2, slash);
            if (!authority.isEmpty() && !"localhost".equalsIgnoreCase(authority)) {
                return false;
            }
            path = path.substring(slash);
        }
        String decoded;
        try {
            decoded = decode(path);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (File.separatorChar == '\\' && decoded.length() > 2 && decoded.charAt(0) == '/'
                && decoded.charAt(2) == ':') {
            decoded = decoded.substring(1);
        }
        File candidate = new File(decoded);
        File archive = archiveFile;
        if (archive == null) {
            return false;
        }
        if (candidate.getAbsolutePath().equals(archive.getAbsolutePath())) {
            return true;
        }
        try {
            String canonical = canonicalPath;
            if (canonical == null) {
                canonical = archive.getCanonicalPath();
                canonicalPath = canonical;
            }
            return candidate.getCanonicalPath().equals(canonical);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Percent-decodes a URL component, one UTF-8 byte per escape.
     *
     * <p>This is {@code sun.net.www.ParseUtil.decode} without the dependency: a string with no
     * {@code '%'} is returned unchanged, a run of escapes is decoded as UTF-8, and a truncated or
     * non-hexadecimal escape is rejected the way the JDK rejects it.</p>
     *
     * @param value the encoded value
     * @return the decoded value
     * @throws IllegalArgumentException if an escape is malformed
     */
    public static String decode(String value) {
        int at = value.indexOf('%');
        if (at < 0) {
            return value;
        }
        int length = value.length();
        StringBuilder out = new StringBuilder(length);
        out.append(value, 0, at);
        byte[] bytes = new byte[length];
        while (at < length) {
            char c = value.charAt(at);
            if (c != '%') {
                out.append(c);
                at++;
                continue;
            }
            int count = 0;
            while (at < length && value.charAt(at) == '%') {
                if (at + 2 >= length) {
                    throw new IllegalArgumentException("Malformed escape pair: " + value);
                }
                int high = digit(value.charAt(at + 1));
                int low = digit(value.charAt(at + 2));
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Malformed escape pair: " + value);
                }
                bytes[count] = (byte) ((high << 4) | low);
                count++;
                at += 3;
            }
            out.append(new String(bytes, 0, count, StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /**
     * The process-lifetime outer archive handle. A connection may expose it as a {@link JarFile}, so its
     * public close operation must not invalidate other connections that share it.
     */
    private static final class SharedJarFile extends JarFile {

        private SharedJarFile(File file) throws IOException {
            super(file, false, OPEN_READ, JarFile.runtimeVersion());
        }

        @Override
        public void close() {
        }

        private void closeShared() throws IOException {
            super.close();
        }
    }
}
