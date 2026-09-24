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

import io.micronaut.runner.Handlers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * The {@code jar:} protocol handler of a runner application.
 *
 * <p>The class name and the package are dictated by the JDK: {@code URL.getURLStreamHandler} builds the
 * name {@code <package>.<protocol>.Handler} for every package listed in
 * {@code java.protocol.handler.pkgs}, loads it with the system class loader and instantiates it
 * reflectively, so this class must stay public, must keep this name, and must keep a public no-argument
 * constructor. The JDK's instance, the instance {@link Handlers} keeps for building URLs and the instance
 * each code-source URL carries (see {@link #withCachedForm(String)}) are different objects; all of them
 * read the same static registration, so it makes no difference which one parses a URL.</p>
 *
 * <h2>What it parses</h2>
 * <p>{@link #parseURL} follows the JDK's jar handler exactly, because every library that ever built a jar
 * URL by hand relies on those rules: the <em>last</em> {@value Handlers#SEPARATOR} separates the jar from
 * the entry, so the nested form {@code jar:file:/app.jar!/lib/dep.jar!/a/B.class} names the entry
 * {@code a/B.class}; a relative spec resolves against the entry of the context URL; and {@code .} and
 * {@code ..} segments are folded inside the entry. It differs from the JDK in two deliberate ways: a
 * {@code ..} that would climb out of the archive is rejected rather than silently clamped, and the inner
 * URL is validated by its scheme instead of by constructing a {@link URL}, which keeps URL creation off
 * the class loading path.</p>
 *
 * <h2>What it opens</h2>
 * <p>Installing a handler for {@code jar:} is process wide, so this class sees every jar URL in the JVM,
 * including the ones the JDK itself opens. A URL whose jar part is the registered archive is served by
 * {@link RunnerJarURLConnection}; everything else, {@code jar:file:} of another file and {@code jar:http:}
 * alike, is handed back to the JDK's own handler through {@link Handlers#openDelegate(URL)} and behaves
 * exactly as it did before the launcher started.</p>
 *
 * @since 1.0
 */
public final class Handler extends URLStreamHandler {

    private static final String JAR_PREFIX = "jar:";
    private static final String PROTOCOL = "jar";

    /** The one URL whose string form this handler caches, or {@code null} for a shared handler. */
    private URL owner;

    /** The string form of {@link #owner}, written before it and computed by this handler's own default. */
    private String ownerForm;

    /**
     * Creates a handler. The JDK calls this reflectively when it resolves the {@code jar} protocol through
     * {@code java.protocol.handler.pkgs}; {@link Handlers#register} also creates one directly, to build
     * URLs that do not depend on that property having taken effect; and {@link #withCachedForm(String)}
     * creates one for each URL whose string form it caches.
     */
    public Handler() {
    }

    /**
     * Launcher-internal; public only because {@link Handlers} is in another package. Creates a URL with its
     * own handler instance that computes the URL's string form once. {@link Handlers} uses it for
     * code-source URLs, whose {@code toString()} the JDK calls on every {@code defineClass}.
     *
     * <p>The URL is parsed exactly as {@link Handlers} parses every other URL it builds, with the same
     * constructor and a single parse; only the handler instance differs. The cached string is this
     * handler's own default output for the URL, so it cannot differ from what the URL reported before. A
     * URL derived from this one, such as {@code new URL(codeSource, "a/B.class")}, inherits the handler
     * but not the cache.</p>
     *
     * @param spec a complete {@code jar:} spec
     * @return the URL
     * @throws MalformedURLException if the spec is rejected
     */
    @SuppressWarnings("deprecation")
    public static URL withCachedForm(String spec) throws MalformedURLException {
        Handler handler = new Handler();
        URL url = new URL((URL) null, spec, handler);
        // Nothing is cached yet, so this is the default string form.
        handler.ownerForm = handler.toExternalForm(url);
        handler.owner = url;
        return url;
    }

    /**
     * The string form of a URL, cached for the one URL {@link #withCachedForm(String)} created this
     * handler for.
     *
     * <p>Both fields are written before that URL leaves the factory, {@link #ownerForm} first. A thread
     * that reads them through a race reads {@link #ownerForm} first and falls back to the default when it
     * sees {@code null}, which computes the same string.</p>
     *
     * @param url the URL
     * @return its string form
     */
    @Override
    protected String toExternalForm(URL url) {
        String form = ownerForm;
        return form != null && url == owner ? form : super.toExternalForm(url);
    }

    /**
     * Opens a jar URL, ours or anybody else's.
     *
     * @param url the URL to open
     * @return a {@link RunnerJarURLConnection} when the jar part names the registered runner archive, and
     *         the connection the JDK's own handler would have returned otherwise
     * @throws IOException if the URL is malformed or the JDK's handler cannot open it
     */
    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        String file = url.getFile();
        int separator = file.indexOf(Handlers.SEPARATOR);
        if (separator > 0 && Handlers.owns(file.substring(0, separator))) {
            return new RunnerJarURLConnection(url);
        }
        return Handlers.openDelegate(url);
    }

    /**
     * Parses a jar URL, following the JDK's jar handler.
     *
     * <p>{@code parseURL} cannot declare a checked exception, so a malformed spec is rejected with an
     * {@link IllegalArgumentException}, which {@link URL} turns into a
     * {@link java.net.MalformedURLException} for the caller.</p>
     *
     * @param url   the URL being built, whose fields this method sets
     * @param spec  the whole specification being parsed
     * @param start the first character after {@code jar:}
     * @param limit the end of the specification, before any {@code #} reference
     */
    @Override
    protected void parseURL(URL url, String spec, int start, int limit) {
        String file = null;
        String ref = null;
        int refPosition = spec.indexOf('#', limit);
        boolean refOnly = refPosition == start;
        if (refPosition >= 0) {
            ref = spec.substring(refPosition + 1);
            if (refOnly) {
                file = url.getFile();
            }
        }
        boolean absolute = spec.regionMatches(true, 0, JAR_PREFIX, 0, JAR_PREFIX.length());
        String body = spec.substring(start, limit);
        if (body.regionMatches(true, 0, JAR_PREFIX, 0, JAR_PREFIX.length())) {
            throw new IllegalArgumentException("Nested jar: URLs are not supported: " + spec);
        }
        if (absolute) {
            file = absoluteSpec(body);
        } else if (!refOnly) {
            file = contextSpec(url, body);
            int bangSlash = indexOfBangSlash(file);
            if (bangSlash < 0) {
                throw new IllegalArgumentException("Malformed jar URL, no !/ in " + file);
            }
            file = canonicalize(file, bangSlash);
        }
        setFile(url, file, ref);
    }

    /**
     * Compares two jar URLs by their text.
     *
     * <p>The JDK compares the inner URLs as URLs, which builds two {@link URL} objects and, for a remote
     * inner URL, resolves host names. Two URLs this launcher produced for the same entry are always
     * spelled the same way, so comparing the text answers the same question without either cost. The
     * consequence, which is why this is documented rather than hidden, is that two differently spelled
     * URLs of the same entry are not {@code equals} here even though the JDK would say they are.</p>
     *
     * @param first  the first URL
     * @param second the second URL
     * @return whether both name the same jar and the same entry
     */
    @Override
    protected boolean sameFile(URL first, URL second) {
        if (!PROTOCOL.equals(first.getProtocol()) || !PROTOCOL.equals(second.getProtocol())) {
            return false;
        }
        String file = first.getFile();
        return file != null && file.equals(second.getFile());
    }

    /**
     * Hashes a jar URL consistently with {@link #sameFile} and with the JDK for local {@code jar:file:}
     * URLs.
     *
     * <p>The JDK hashes a jar URL as the jar protocol, the enclosed URL and the entry after the first
     * {@value Handlers#SEPARATOR}. Runner URLs always enclose a local file URL, whose JDK hash is entirely
     * textual. Computing that value directly avoids constructing another URL and, unlike the JDK's generic
     * URL hash implementation, can never resolve a host name. A non-local or non-file enclosed URL keeps
     * the text hash used by {@link #sameFile}; Runner never produces such a URL.</p>
     *
     * @param url the URL
     * @return the hash
     */
    @Override
    protected int hashCode(URL url) {
        int hash = 0;
        String protocol = url.getProtocol();
        if (protocol != null) {
            hash += protocol.hashCode();
        }
        String file = url.getFile();
        if (file == null) {
            return hash;
        }
        int separator = file.indexOf(Handlers.SEPARATOR);
        if (separator < 0) {
            return hash + file.hashCode();
        }
        String enclosed = file.substring(0, separator);
        hash += localFileHash(enclosed);
        hash += file.substring(separator + Handlers.SEPARATOR.length()).hashCode();
        return hash;
    }

    /**
     * Computes the JDK URL hash of a local {@code file:} URL without constructing a URL. An authority would
     * make the JDK consult DNS, so those uncommon foreign jar URLs retain their text hash instead.
     */
    private static int localFileHash(String spec) {
        int schemeLength = 5;
        if (!spec.regionMatches(true, 0, "file:", 0, schemeLength)) {
            return spec.hashCode();
        }
        int fileStart = schemeLength;
        if (spec.startsWith("//", fileStart)) {
            int authorityStart = fileStart + 2;
            int slash = spec.indexOf('/', authorityStart);
            int question = spec.indexOf('?', authorityStart);
            int authorityEnd = slash < 0 ? (question < 0 ? spec.length() : question) : slash;
            if (authorityEnd != authorityStart) {
                return spec.hashCode();
            }
            fileStart = authorityEnd;
        }
        return "file".hashCode() + spec.substring(fileStart).hashCode() - 1;
    }

    /**
     * Compares two jar URLs, reference included, the way {@link URLStreamHandler} defines it.
     *
     * @param first  the first URL
     * @param second the second URL
     * @return whether both are the same URL
     */
    @Override
    protected boolean equals(URL first, URL second) {
        String firstRef = first.getRef();
        String secondRef = second.getRef();
        if (firstRef == null ? secondRef != null : !firstRef.equals(secondRef)) {
            return false;
        }
        return sameFile(first, second);
    }

    /**
     * The index of the slash of the last {@value Handlers#SEPARATOR} in a string, which is where the JDK
     * splits a jar URL into the jar and the entry inside it.
     *
     * @param spec the file part of a jar URL
     * @return the index of the slash, or {@code -1} when there is no separator
     */
    static int indexOfBangSlash(String spec) {
        int bang = spec.length();
        while ((bang = spec.lastIndexOf('!', bang)) >= 0) {
            if (bang != spec.length() - 1 && spec.charAt(bang + 1) == '/') {
                return bang + 1;
            }
            bang--;
        }
        return -1;
    }

    /**
     * Sets the parsed fields on the URL, splitting a query off the file the way the JDK's deprecated
     * six argument {@code setURL} does, so that {@code getFile}, {@code getPath} and {@code getQuery}
     * report what they report for a JDK parsed jar URL.
     *
     * @param url  the URL to set
     * @param file the file part, jar and entry together
     * @param ref  the reference, or {@code null}
     */
    private void setFile(URL url, String file, String ref) {
        String path = file;
        String query = null;
        if (file != null) {
            int question = file.lastIndexOf('?');
            if (question >= 0) {
                query = file.substring(question + 1);
                path = file.substring(0, question);
            }
        }
        setURL(url, PROTOCOL, "", -1, null, null, path, query, ref);
    }

    /**
     * Checks an absolute spec, that is one that came with its own {@code jar:} prefix.
     *
     * @param spec the spec without the {@code jar:} prefix
     * @return the spec, unchanged
     */
    private static String absoluteSpec(String spec) {
        int bangSlash = indexOfBangSlash(spec);
        if (bangSlash < 0) {
            throw new IllegalArgumentException("Malformed jar URL, no !/ in " + spec);
        }
        if (!hasScheme(spec, bangSlash - 1)) {
            throw new IllegalArgumentException("Malformed jar URL, the jar part of " + spec
                    + " is not a URL");
        }
        return spec;
    }

    /**
     * Whether the first {@code length} characters of a spec start with something that can be a URL scheme.
     * This stands in for the JDK's {@code new URL(inner)}, which would both create a URL on the class
     * loading path and demand that a handler for the inner protocol already exists.
     *
     * @param spec   the spec
     * @param length the length of the jar part
     * @return whether the jar part begins with {@code scheme:}
     */
    private static boolean hasScheme(String spec, int length) {
        if (length <= 0) {
            return false;
        }
        char first = spec.charAt(0);
        if (!((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z'))) {
            return false;
        }
        for (int i = 1; i < length; i++) {
            char c = spec.charAt(i);
            if (c == ':') {
                return i + 1 < length;
            }
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '-' || c == '.';
            if (!valid) {
                return false;
            }
        }
        return false;
    }

    /**
     * Resolves a relative spec against the entry of a context URL, the way the JDK's jar handler does: a
     * spec that starts with a slash replaces the whole entry, and any other spec replaces the last
     * segment of it.
     *
     * @param url  the context URL
     * @param spec the relative spec
     * @return the resolved file part
     */
    private static String contextSpec(URL url, String spec) {
        String context = url.getFile();
        if (context == null) {
            throw new IllegalArgumentException("Cannot resolve " + spec + " against " + url);
        }
        if (spec.startsWith("/")) {
            int bangSlash = indexOfBangSlash(context);
            if (bangSlash < 0) {
                throw new IllegalArgumentException("Malformed context jar URL, no !/ in " + url);
            }
            context = context.substring(0, bangSlash);
        } else {
            int lastSlash = context.lastIndexOf('/');
            if (lastSlash < 0) {
                throw new IllegalArgumentException("Malformed context jar URL " + url);
            }
            if (lastSlash < context.length() - 1) {
                context = context.substring(0, lastSlash + 1);
            }
        }
        StringBuilder file = new StringBuilder(context.length() + spec.length());
        file.append(context).append(spec);
        return file.toString();
    }

    /**
     * Folds {@code .} and {@code ..} segments of the entry part of a file, leaving the jar part alone.
     *
     * @param file      the file part of the URL
     * @param bangSlash the index of the slash that starts the entry
     * @return the folded file
     */
    private static String canonicalize(String file, int bangSlash) {
        String entry = file.substring(bangSlash);
        if (entry.indexOf("./") < 0 && entry.charAt(entry.length() - 1) != '.') {
            return file;
        }
        int at;
        int previous;
        while ((at = entry.indexOf("/../")) >= 0) {
            previous = entry.lastIndexOf('/', at - 1);
            if (previous < 0) {
                throw new IllegalArgumentException("The entry of " + file + " climbs out of the archive");
            }
            entry = splice(entry, previous, at + 3);
        }
        while ((at = entry.indexOf("/./")) >= 0) {
            entry = splice(entry, at, at + 2);
        }
        while (entry.endsWith("/..")) {
            at = entry.length() - 3;
            previous = entry.lastIndexOf('/', at - 1);
            if (previous < 0) {
                throw new IllegalArgumentException("The entry of " + file + " climbs out of the archive");
            }
            entry = entry.substring(0, previous + 1);
        }
        if (entry.endsWith("/.")) {
            entry = entry.substring(0, entry.length() - 1);
        }
        StringBuilder result = new StringBuilder(bangSlash + entry.length());
        result.append(file, 0, bangSlash).append(entry);
        return result.toString();
    }

    /**
     * Removes the characters between two indexes.
     *
     * @param value the string
     * @param from  the first index to drop
     * @param to    the first index to keep again
     * @return the spliced string
     */
    private static String splice(String value, int from, int to) {
        StringBuilder result = new StringBuilder(value.length());
        result.append(value, 0, from).append(value, to, value.length());
        return result.toString();
    }
}
