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
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * The class loader that serves an application straight out of its runner jar.
 *
 * <p>Every class and resource comes from the index: one hash probe locates the record, the record carries
 * the absolute offset of the bytes in the outer archive, and the bytes are read from the memory mapping.
 * Nothing is unpacked, no nested {@code ZipFile} is opened, and no central directory is parsed at
 * runtime.</p>
 *
 * <h2>Delegation order</h2>
 * <p>The parent is the platform class loader, so that the real {@code java.class.path} - which holds the
 * runner jar itself, and therefore the launcher's own classes - cannot leak into the application. Three
 * rules decide where a name is looked up:</p>
 * <ul>
 *   <li>{@code io.micronaut.runner.*}, except {@code io.micronaut.runner.generated.*}, is delegated to the
 *       class loader that loaded the launcher, so that {@link Entry}, {@link Index} and friends are the
 *       same types on both sides of the boundary.</li>
 *   <li>A class whose package belongs to a boot layer module of the boot or platform loader is
 *       <em>parent-visible</em>: the parent is asked first and the index is only a fallback. The set is
 *       built once from {@link ModuleLayer#boot()}, which is exact for the running JDK, unlike a prefix
 *       test on {@code java.}.</li>
 *   <li>Everything else - that is, every application and dependency class - is looked up in the index
 *       <em>first</em>. This is the point of the whole design: a parent-first loader pays a failed parent
 *       lookup and a thrown {@link ClassNotFoundException} for every single application class, which is
 *       the dominant cost in naive nested jar loaders.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>The loader is parallel capable, so the superclass hands out one lock per class name and several
 * threads define classes at once. The protection domain cache is guarded by its own monitor, which is
 * never held while a class is defined, and package definition relies on the superclass's concurrent
 * package map, catching the {@link IllegalArgumentException} that a lost race produces.</p>
 *
 * @since 1.0
 */
public final class RunnerClassLoader extends ClassLoader {

    /**
     * System property selecting the parent class loader. The only recognised value is
     * {@value #PARENT_SYSTEM}, which is an escape hatch for applications that need to see the real
     * class path; anything else, including the property being absent, means the platform class loader.
     */
    public static final String PARENT_PROPERTY = "micronaut.runner.parent";

    /** Value of {@value #PARENT_PROPERTY} that selects {@link ClassLoader#getSystemClassLoader()}. */
    public static final String PARENT_SYSTEM = "system";

    /**
     * System property that turns on CRC-32 verification of every class and resource read from the
     * archive. Verification is off by default: the archive is checked for staleness when it is opened and
     * once per jar, and checksumming every class would add a full pass over each class file to startup.
     */
    public static final String VERIFY_PROPERTY = "micronaut.runner.verify";

    /** Name reported by {@link ClassLoader#getName()}, which shows up in stack traces. */
    private static final String LOADER_NAME = "micronaut-runner";

    /** Prefix of the launcher's own classes, which the launcher's loader must define. */
    private static final String LAUNCHER_PREFIX = "io.micronaut.runner.";

    /** Prefix of the packager's generated classes, which are part of the application layer. */
    private static final String GENERATED_PREFIX = IndexFormat.GENERATED_PACKAGE + ".";

    static {
        registerAsParallelCapable();
    }

    private final Index index;
    private final ArchiveSource source;
    private final ClassLoader parent;
    private final ClassLoader launcherLoader;
    private final HashSet<String> parentPackages;
    private final ProtectionDomain[] domains;
    private final int multiReleaseVersion;
    private final boolean verify;

    /**
     * Creates a loader over an open archive.
     *
     * <p>Everything that does not depend on the classes being loaded is computed here, once: the
     * multi-release feature version, the set of parent-visible packages and the verification flag. The
     * archive and the index are <em>not</em> owned by the loader and are never closed by it.</p>
     *
     * @param index  the index of the archive, already validated
     * @param source the open archive the index describes
     * @param parent the parent loader, normally {@link ClassLoader#getPlatformClassLoader()}; see
     *               {@link #defaultParent()}
     */
    public RunnerClassLoader(Index index, ArchiveSource source, ClassLoader parent) {
        super(LOADER_NAME, parent);
        this.index = index;
        this.source = source;
        this.parent = parent;
        this.launcherLoader = RunnerClassLoader.class.getClassLoader();
        this.parentPackages = parentVisiblePackages();
        this.domains = new ProtectionDomain[index.jarCount()];
        this.multiReleaseVersion = Index.effectiveMultiReleaseVersion();
        this.verify = "true".equals(System.getProperty(VERIFY_PROPERTY));
    }

    /**
     * The parent the launcher gives a loader for an application.
     *
     * @return {@link ClassLoader#getSystemClassLoader()} when {@value #PARENT_PROPERTY} is
     *         {@value #PARENT_SYSTEM}, otherwise {@link ClassLoader#getPlatformClassLoader()}
     */
    public static ClassLoader defaultParent() {
        if (PARENT_SYSTEM.equals(System.getProperty(PARENT_PROPERTY))) {
            return ClassLoader.getSystemClassLoader();
        }
        return ClassLoader.getPlatformClassLoader();
    }

    /**
     * Loads a class, deciding between the parent and the index by package.
     *
     * @param name    the binary name of the class
     * @param resolve whether to link the class once it is loaded
     * @return the class
     * @throws ClassNotFoundException if neither the parent nor the archive has the class
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = load(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    /**
     * Finds a class in the archive, without consulting the parent.
     *
     * <p>{@code findClass(String, String)} is deliberately not overridden: the superclass already returns
     * {@code null} for a name it cannot find in the unnamed module, which is exactly right here.</p>
     *
     * @param name the binary name of the class
     * @return the class
     * @throws ClassNotFoundException if the archive has no such class, or its bytes cannot be read
     */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> found = findInArchive(name);
        if (found == null) {
            throw new ClassNotFoundException(name);
        }
        return found;
    }

    /**
     * The first URL for a resource, in classpath order.
     *
     * @param name the resource name, with or without a leading slash
     * @return the URL, or {@code null} when the archive has no such resource or no URL can be formed
     */
    @Override
    public URL findResource(String name) {
        String logical = normalizeResourceName(name);
        if (logical == null || logical.isEmpty()) {
            return null;
        }
        int record = resolveResource(logical);
        if (record == IndexFormat.NO_INDEX) {
            return null;
        }
        return urlFor(record);
    }

    /**
     * Every URL for a resource: one per jar that contributes it, in classpath order.
     *
     * <p>A jar contributes at most one URL even when it holds several records for the name, which is the
     * case for a multi-release jar carrying both a base entry and versioned copies; the record that the
     * multi-release rules select for this runtime is the one that is reported. The first element is
     * always what {@link #findResource(String)} returns.</p>
     *
     * @param name the resource name, with or without a leading slash
     * @return the URLs, possibly empty, never {@code null}
     * @throws IOException never, but declared by the superclass
     */
    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        String logical = normalizeResourceName(name);
        if (logical == null || logical.isEmpty()) {
            return Collections.emptyEnumeration();
        }
        ArrayList<URL> urls = new ArrayList<URL>(2);
        if (logical.startsWith(IndexFormat.MICRONAUT_SERVICES_PREFIX)) {
            int merged = resolveResource(logical);
            if (merged != IndexFormat.NO_INDEX) {
                addUrl(urls, merged);
            }
            return Collections.enumeration(urls);
        }
        int head = index.find(logical);
        if (head == IndexFormat.NO_INDEX && !namesDirectory(logical)) {
            head = index.find(withTrailingSlash(logical));
        }
        if (head == IndexFormat.NO_INDEX) {
            return Collections.emptyEnumeration();
        }
        long[] seen = new long[(index.jarCount() + 63) >>> 6];
        int record = head;
        int guard = index.entryCount();
        while (record != IndexFormat.NO_INDEX && guard >= 0) {
            int jarId = index.entryJarId(record);
            int word = jarId >>> 6;
            long bit = 1L << (jarId & 63);
            if (word < seen.length && (seen[word] & bit) == 0L) {
                seen[word] |= bit;
                int chosen = index.resolveInJar(head, multiReleaseVersion, jarId);
                if (chosen != IndexFormat.NO_INDEX) {
                    addUrl(urls, chosen);
                }
            }
            record = index.next(record);
            guard--;
        }
        return Collections.enumeration(urls);
    }

    /**
     * A resource from the parent if it has one, otherwise from the archive.
     *
     * @param name the resource name
     * @return the URL, or {@code null} when neither has the resource
     */
    @Override
    public URL getResource(String name) {
        ClassLoader delegate = parent;
        if (delegate != null) {
            URL fromParent = delegate.getResource(name);
            if (fromParent != null) {
                return fromParent;
            }
        }
        return findResource(name);
    }

    /**
     * A stream over a resource, served straight from the archive.
     *
     * <p>The parent is consulted first, exactly as {@link ClassLoader#getResourceAsStream(String)} does,
     * and the archive's own resources are then opened without ever building a {@link URL}: the record
     * gives an offset and a length, and {@link ArchiveSource} turns those into a stream.</p>
     *
     * @param name the resource name
     * @return the stream, which the caller closes, or {@code null} when the resource does not exist
     */
    @Override
    public InputStream getResourceAsStream(String name) {
        if (name == null) {
            return null;
        }
        ClassLoader delegate = parent;
        if (delegate != null) {
            InputStream fromParent = delegate.getResourceAsStream(name);
            if (fromParent != null) {
                return fromParent;
            }
        }
        String logical = normalizeResourceName(name);
        if (logical == null || logical.isEmpty()) {
            return null;
        }
        int record = resolveResource(logical);
        if (record == IndexFormat.NO_INDEX) {
            return null;
        }
        try {
            return index.openEntryStream(record, verify);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Normalises a resource name against the root of the archive.
     *
     * <p>One leading slash is dropped, {@code .} segments are removed, {@code ..} segments are resolved
     * and empty segments are collapsed; a name that climbs above the root is not a name in this archive
     * and yields {@code null}. A trailing slash survives normalisation, because it is what distinguishes a
     * request for a directory from a request for a file.</p>
     *
     * @param name the name as the caller wrote it
     * @return the name relative to the root of a jar, or {@code null} when it escapes the archive
     */
    static String normalizeResourceName(String name) {
        if (name == null) {
            return null;
        }
        String value = name;
        if (!value.isEmpty() && value.charAt(0) == '/') {
            value = value.substring(1);
        }
        if (!needsNormalising(value)) {
            return value;
        }
        return resolveSegments(value);
    }

    /**
     * Whether a class name is looked up in the parent before the index.
     *
     * @param className the binary name of the class
     * @return {@code true} when the class's package belongs to the boot or platform loader
     */
    boolean isParentVisible(String className) {
        int dot = className.lastIndexOf('.');
        if (dot <= 0) {
            return false;
        }
        return parentPackages.contains(className.substring(0, dot));
    }

    /**
     * The packages of every boot layer module loaded by the boot or the platform class loader.
     *
     * <p>Modules of the system class loader are left out on purpose: in a runner jar those are the
     * launcher's own classes, which the application must not see.</p>
     *
     * @return the package names
     */
    private static HashSet<String> parentVisiblePackages() {
        HashSet<String> packages = new HashSet<String>(1024);
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        Set<Module> modules = ModuleLayer.boot().modules();
        for (Module module : modules) {
            ClassLoader loader = module.getClassLoader();
            if (loader == null || loader == platform) {
                packages.addAll(module.getPackages());
            }
        }
        return packages;
    }

    private static boolean needsNormalising(String value) {
        int length = value.length();
        int start = 0;
        for (int i = 0; i <= length; i++) {
            if (i == length || value.charAt(i) == '/') {
                int size = i - start;
                if (size == 1 && value.charAt(start) == '.') {
                    return true;
                }
                if (size == 2 && value.charAt(start) == '.' && value.charAt(start + 1) == '.') {
                    return true;
                }
                if (size == 0 && i < length) {
                    // A doubled or leading slash. The final empty segment of a name that ends with one
                    // slash is not an empty segment in this sense: it is how a directory is asked for, it
                    // survives normalisation unchanged, and leaving it on the fast path keeps every
                    // directory lookup allocation free.
                    return true;
                }
                start = i + 1;
            }
        }
        return false;
    }

    private static String resolveSegments(String value) {
        int length = value.length();
        int segments = 1;
        for (int i = 0; i < length; i++) {
            if (value.charAt(i) == '/') {
                segments++;
            }
        }
        String[] stack = new String[segments];
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= length; i++) {
            if (i == length || value.charAt(i) == '/') {
                int size = i - start;
                boolean up = size == 2 && value.charAt(start) == '.' && value.charAt(start + 1) == '.';
                boolean self = size == 1 && value.charAt(start) == '.';
                if (up) {
                    if (depth == 0) {
                        return null;
                    }
                    depth--;
                } else if (size > 0 && !self) {
                    stack[depth] = value.substring(start, i);
                    depth++;
                }
                start = i + 1;
            }
        }
        if (depth == 0) {
            return "";
        }
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < depth; i++) {
            if (i > 0) {
                result.append('/');
            }
            result.append(stack[i]);
        }
        if (value.charAt(length - 1) == '/') {
            result.append('/');
        }
        return result.toString();
    }

    /**
     * Enforces sealing for a package that is already defined by this loader.
     *
     * <p>The rules, and the messages, are the ones {@code java.net.URLClassLoader} uses: a class may only
     * join a sealed package when it comes from the code source the package was sealed with, and a package
     * that is already defined without a seal can no longer be sealed.</p>
     *
     * @param defined     the package as this loader defined it
     * @param packageName the package name, for the message
     * @param base        the code source location of the arriving class, or {@code null} when none
     * @param sealed      whether the arriving class's jar seals the package
     */
    private static void checkSealing(Package defined, String packageName, URL base, boolean sealed) {
        if (defined.isSealed()) {
            if (base == null || !defined.isSealed(base)) {
                throw new SecurityException("sealing violation: package " + packageName + " is sealed");
            }
        } else if (sealed) {
            throw new SecurityException("sealing violation: can't seal package " + packageName
                    + ": already loaded");
        }
    }

    private Class<?> load(String name) throws ClassNotFoundException {
        if (name.startsWith(LAUNCHER_PREFIX) && !name.startsWith(GENERATED_PREFIX)) {
            // The launcher's own loader is preferred, not imposed: Entry, Index and the rest must be the
            // same types on both sides of the boundary, and only the classes that loader actually has can
            // be. An application or a dependency is free to use a package under this prefix, and such a
            // class lives in the archive like any other, so a miss here falls through to it rather than
            // being reported as a class the runner jar has lost.
            ClassLoader owner = launcherLoader;
            try {
                if (owner == null) {
                    return Class.forName(name, false, null);
                }
                return owner.loadClass(name);
            } catch (ClassNotFoundException e) {
                Class<?> found = findInArchive(name);
                if (found != null) {
                    return found;
                }
                throw e;
            }
        }
        if (isParentVisible(name)) {
            Class<?> fromParent = loadFromParent(name);
            if (fromParent != null) {
                return fromParent;
            }
            Class<?> found = findInArchive(name);
            if (found != null) {
                return found;
            }
            throw new ClassNotFoundException(name);
        }
        Class<?> found = findInArchive(name);
        if (found != null) {
            return found;
        }
        Class<?> fromParent = loadFromParent(name);
        if (fromParent != null) {
            return fromParent;
        }
        throw new ClassNotFoundException(name);
    }

    private Class<?> loadFromParent(String name) {
        try {
            ClassLoader delegate = parent;
            if (delegate == null) {
                return super.loadClass(name, false);
            }
            return delegate.loadClass(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private Class<?> findInArchive(String name) throws ClassNotFoundException {
        int head = index.findClass(name);
        if (head == IndexFormat.NO_INDEX) {
            return null;
        }
        int record = index.resolve(head, multiReleaseVersion);
        if (record == IndexFormat.NO_INDEX) {
            return null;
        }
        try {
            return define(name, record);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    /**
     * Defines a class from one entry record.
     *
     * <p>A STORED entry is defined from a buffer over the mapping, which saves materialising the class
     * file as a {@code byte[]} and copying it there first. It is not a zero copy define: a non-builtin
     * loader still has the VM copy a direct buffer into its own memory before parsing it. A DEFLATE entry
     * is inflated into an exactly sized array, since it has to be materialised anyway.</p>
     *
     * @param name   the binary name of the class
     * @param record the entry record holding its bytes
     * @return the defined class
     * @throws IOException if the entry cannot be read, or fails verification when it is enabled
     */
    private Class<?> define(String name, int record) throws IOException {
        int jarId = index.entryJarId(record);
        index.validateJar(jarId);
        long size = index.entryUncompressedSize(record);
        if (size > ArchiveSource.MAX_SLICE_LENGTH) {
            throw new IOException("The class " + name + " is " + size
                    + " bytes, which is larger than a class file can be");
        }
        ProtectionDomain domain = protectionDomain(jarId);
        definePackageOf(name, jarId, domain);
        int length = (int) size;
        if (index.entryMethod(record) == IndexFormat.METHOD_STORED) {
            ByteBuffer content = source.slice(index.entryDataOffset(record), length);
            if (verify) {
                CRC32 checksum = new CRC32();
                checksum.update(content.duplicate());
                checkCrc(record, checksum.getValue());
            }
            return defineClass(name, content, domain);
        }
        long compressed = index.entryCompressedSize(record);
        if (compressed > ArchiveSource.MAX_SLICE_LENGTH) {
            throw new IOException("The class " + name + " is stored in " + compressed
                    + " compressed bytes, which is more than can be inflated at once");
        }
        byte[] content = source.inflate(index.entryDataOffset(record), (int) compressed, length);
        if (verify) {
            CRC32 checksum = new CRC32();
            checksum.update(content, 0, length);
            checkCrc(record, checksum.getValue());
        }
        return defineClass(name, content, 0, length, domain);
    }

    private void checkCrc(int record, long actual) throws IOException {
        long expected = index.entryCrc32(record);
        if (expected != actual) {
            throw new IOException("The entry " + index.entryName(record) + " of "
                    + index.jarName(index.entryJarId(record)) + " has checksum " + actual
                    + " but the index records " + expected + "; " + Index.REBUILD_MESSAGE);
        }
    }

    /**
     * Defines the package of a class, the first time that package is seen, and enforces sealing.
     *
     * <p>The attributes come from the jar's manifest main attributes, with the per-package section of
     * that manifest overriding each of the six values independently. Sealing is enforced the way
     * {@code java.net.URLClassLoader} enforces it: a class from a different code source may not join a
     * sealed package, and a package that is already defined unsealed may not be sealed afterwards.</p>
     *
     * <p>The seal base is the jar's code source location, the one URL that was created for the jar's
     * protection domain, so sealing costs no extra URL and a sealed package's base is exactly the
     * location every class of that jar reports.</p>
     *
     * @param className the binary name of the class being defined
     * @param jarId     the jar the class comes from
     * @param domain    the protection domain of that jar
     */
    private void definePackageOf(String className, int jarId, ProtectionDomain domain) {
        int dot = className.lastIndexOf('.');
        if (dot <= 0) {
            return;
        }
        String packageName = className.substring(0, dot);
        int section = index.findPackage(jarId, packageName);
        boolean sealed = index.jarSealedByDefault(jarId);
        if (section != IndexFormat.NO_INDEX && index.packageSealedSpecified(section)) {
            sealed = index.packageSealedValue(section);
        }
        URL base = domain.getCodeSource() == null ? null : domain.getCodeSource().getLocation();
        Package defined = getDefinedPackage(packageName);
        if (defined != null) {
            checkSealing(defined, packageName, base, sealed);
            return;
        }
        String specTitle = index.jarSpecTitle(jarId);
        String specVersion = index.jarSpecVersion(jarId);
        String specVendor = index.jarSpecVendor(jarId);
        String implTitle = index.jarImplTitle(jarId);
        String implVersion = index.jarImplVersion(jarId);
        String implVendor = index.jarImplVendor(jarId);
        if (section != IndexFormat.NO_INDEX) {
            String value = index.packageSpecTitle(section);
            if (value != null) {
                specTitle = value;
            }
            value = index.packageSpecVersion(section);
            if (value != null) {
                specVersion = value;
            }
            value = index.packageSpecVendor(section);
            if (value != null) {
                specVendor = value;
            }
            value = index.packageImplTitle(section);
            if (value != null) {
                implTitle = value;
            }
            value = index.packageImplVersion(section);
            if (value != null) {
                implVersion = value;
            }
            value = index.packageImplVendor(section);
            if (value != null) {
                implVendor = value;
            }
        }
        URL sealBase = sealed ? base : null;
        try {
            definePackage(packageName, specTitle, specVersion, specVendor, implTitle, implVersion,
                    implVendor, sealBase);
        } catch (IllegalArgumentException e) {
            Package raced = getDefinedPackage(packageName);
            if (raced == null) {
                throw e;
            }
            checkSealing(raced, packageName, base, sealed);
        }
    }

    /**
     * The protection domain of a jar, created the first time a class of that jar is defined.
     *
     * <p>The cache is guarded by the monitor of the array it lives in rather than published through a
     * plain array read: a {@link ProtectionDomain} does not have final fields, so handing one to another
     * thread through a data race would not guarantee that thread sees its contents. The monitor is held
     * only while the domain is created, never while a class is defined.</p>
     *
     * @param jarId the jar index
     * @return the domain, whose code source location identifies the jar
     */
    private ProtectionDomain protectionDomain(int jarId) {
        synchronized (domains) {
            ProtectionDomain domain = domains[jarId];
            if (domain == null) {
                CodeSource code = new CodeSource(Handlers.codeSourceUrlFor(jarId), (CodeSigner[]) null);
                domain = new ProtectionDomain(code, null, this, null);
                domains[jarId] = domain;
            }
            return domain;
        }
    }

    /**
     * Resolves a resource name to the record that serves it.
     *
     * <p>Names at or under {@value IndexFormat#MICRONAUT_SERVICES_PREFIX} are special: the packager
     * merges the Micronaut service metadata of every dependency into that directory at the root of the
     * outer archive, so that the framework's scanner can list it in one pass, and those merged records
     * belong to the application layer. Resolving such a name against a dependency would hand the scanner
     * a partial view, so only the application layer is considered.</p>
     *
     * @param logical the normalised name
     * @return the record, or {@link IndexFormat#NO_INDEX} when nothing matches
     */
    private int resolveResource(String logical) {
        int record = resolveExact(logical);
        if (record == IndexFormat.NO_INDEX && !namesDirectory(logical)) {
            // ZipFile.getEntry and NestedJarFile.getEntry both retry a name that missed with a trailing
            // slash, so that a lookup of "some/package" finds the directory entry. A URLClassLoader over
            // a jar, or over a directory, answers getResource("some/package") the same way. Without this
            // the loader would disagree with its own JarFile view of the very same archive.
            record = resolveExact(withTrailingSlash(logical));
        }
        return record;
    }

    /**
     * Resolves a name exactly as it was written, with no retry.
     *
     * @param logical the normalised name
     * @return the record, or {@link IndexFormat#NO_INDEX} when nothing matches
     */
    private int resolveExact(String logical) {
        int head = index.find(logical);
        if (logical.startsWith(IndexFormat.MICRONAUT_SERVICES_PREFIX)) {
            return index.resolveInJar(head, multiReleaseVersion, IndexFormat.APPLICATION_JAR_ID);
        }
        return index.resolve(head, multiReleaseVersion);
    }

    /**
     * Whether a name already asks for a directory.
     *
     * @param logical the normalised name
     * @return {@code true} when it ends with a slash
     */
    private static boolean namesDirectory(String logical) {
        int length = logical.length();
        return length > 0 && logical.charAt(length - 1) == '/';
    }

    /**
     * The same name with a trailing slash appended.
     *
     * @param logical the normalised name
     * @return the directory form of the name
     */
    private static String withTrailingSlash(String logical) {
        StringBuilder directory = new StringBuilder(logical.length() + 1);
        directory.append(logical).append('/');
        return directory.toString();
    }

    private void addUrl(ArrayList<URL> urls, int record) {
        URL url = urlFor(record);
        if (url != null) {
            urls.add(url);
        }
    }

    /**
     * The URL of an entry record.
     *
     * <p>A versioned alias is reported under the name of the entry it aliases, that is, the
     * {@code META-INF/versions/N/...} path that physically holds the bytes, which is what
     * {@code URLClassLoader} does for a multi-release jar and what makes the URL openable by any reader
     * of the archive rather than only by this loader.</p>
     *
     * @param record the entry record
     * @return the URL, or {@code null} when none can be formed
     */
    private URL urlFor(int record) {
        int physical = index.entryPhysicalIndex(record);
        String name = physical == IndexFormat.NO_INDEX
                ? index.entryName(record) : index.entryName(physical);
        return Handlers.urlFor(index.entryJarId(record), name);
    }
}
