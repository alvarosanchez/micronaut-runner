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
import java.util.HashMap;
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
 *       <em>parent-visible</em>: the parent is asked first and the index is only a fallback. The package
 *       map is built once from {@link ModuleLayer#boot()}, which is exact for the running JDK, unlike a
 *       prefix test on {@code java.}. When the parent is one of the JDK's own loaders - the platform
 *       loader, or the JDK's system loader - a package of a module of the boot loader skips both this
 *       loader's per-name lock and the parent: {@link Class#forName(Module, String)} asks the boot loader
 *       directly, which is where the parent would end up, and the lock and the archive are consulted only
 *       when the boot loader does not have the class. Packages of the platform loader's modules, and every
 *       package under any other parent, are asked of the parent.</li>
 *   <li>Everything else - that is, every application and dependency class - is looked up in the index
 *       <em>first</em>, and the parent is asked only after a miss. This is the point of the whole design: a
 *       parent-first loader pays a failed parent lookup and a thrown {@link ClassNotFoundException} for
 *       every single application class, which is the dominant cost in naive nested jar loaders.</li>
 * </ul>
 *
 * <p>A class neither side has costs one {@link ClassNotFoundException}, as it does with the JDK's own
 * loaders: the parent's is rethrown rather than caught and replaced, and the one exception this loader
 * builds itself is for a boot-module package whose class neither the boot loader nor the archive has.</p>
 *
 * <h2>Thread safety</h2>
 * <p>The loader is parallel capable, so the superclass hands out one lock per class name and several
 * threads define classes at once. The protection domain cache is guarded by its own monitor, which is
 * never held while a class is defined, and package definition relies on the superclass's concurrent
 * package map, catching the {@link IllegalArgumentException} that a lost race produces. The decoded
 * manifest attributes of a jar are written under that same monitor, together with its protection domain,
 * and read without it: every class definition acquires the monitor for the domain before it reads them,
 * which orders the read after the write. The boot fast path defines nothing, so it takes no lock.</p>
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
     *
     * <p>The property is read once, when the archive's {@link Index} is opened, and the class loader,
     * {@link NestedJarFile} and {@code jar:} URL streams over that index all follow what it read then.</p>
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
    private final HashMap<String, Module> parentModules;
    private final boolean bootDirect;
    private final ProtectionDomain[] domains;
    private final String[][] jarAttributes;
    private final int multiReleaseVersion;
    private final boolean verify;

    /**
     * Creates a loader over an open archive.
     *
     * <p>Everything that does not depend on the classes being loaded is computed here, once: the
     * multi-release feature version, the modules of the parent-visible packages, and whether boot-module
     * packages may bypass the parent. Whether classes and resources are verified is the index's setting,
     * read when the index was opened; see {@link #VERIFY_PROPERTY}. The archive and the index are
     * <em>not</em> owned by the loader and are never closed by it.</p>
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
        this.parentModules = parentVisibleModules();
        this.bootDirect = isBuiltinLoader(parent);
        this.domains = new ProtectionDomain[index.jarCount()];
        this.jarAttributes = new String[index.jarCount()][];
        this.multiReleaseVersion = Index.effectiveMultiReleaseVersion();
        this.verify = index.verifies();
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
     * <p>The package name is extracted once, here, and handed down to the definition of the class. A class
     * of a boot-module package is looked up in the boot loader before the per-name lock is taken, when the
     * parent is one of the JDK's own loaders: nothing is defined on that path, so neither the lock nor
     * {@link #findLoadedClass(String)} is needed, and a hit costs no lock object in the superclass's lock
     * map.</p>
     *
     * @param name    the binary name of the class
     * @param resolve whether to link the class once it is loaded
     * @return the class
     * @throws ClassNotFoundException if neither the parent nor the archive has the class
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        String packageName = packageOf(name);
        Module jdk = packageName == null ? null : parentModules.get(packageName);
        boolean boot = bootDirect && jdk != null && jdk.getClassLoader() == null;
        if (boot) {
            // No lock and no exception: null on a miss, which the archive then gets to answer.
            Class<?> found = Class.forName(jdk, name);
            if (found != null) {
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = load(name, packageName, jdk != null, boot);
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
        Class<?> found = findInArchive(name, packageOf(name));
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
        if (namesMicronautServices(logical)) {
            int merged = resolveResource(logical);
            if (merged != IndexFormat.NO_INDEX) {
                addUrl(urls, merged);
            }
            return Collections.enumeration(urls);
        }
        int exact = index.resolve(index.find(logical), multiReleaseVersion);
        int directory = exactIsFinal(logical, exact) ? IndexFormat.NO_INDEX
                : index.resolve(index.findDirectory(logical), multiReleaseVersion);
        int selected = selectResource(exact, directory);
        int remaining = index.entryCount();
        while (selected != IndexFormat.NO_INDEX && remaining > 0) {
            int jarId = index.entryJarId(selected);
            addUrl(urls, selected);
            if (exact != IndexFormat.NO_INDEX && index.entryJarId(exact) == jarId) {
                exact = index.resolve(index.nextJar(exact), multiReleaseVersion);
            }
            if (directory != IndexFormat.NO_INDEX && index.entryJarId(directory) == jarId) {
                directory = index.resolve(index.nextJar(directory), multiReleaseVersion);
            }
            selected = selectResource(exact, directory);
            remaining--;
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
            return index.openEntryStream(record);
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
        String packageName = packageOf(className);
        return packageName != null && parentModules.containsKey(packageName);
    }

    /**
     * The package of a class.
     *
     * @param className the binary name of the class
     * @return the package name, or {@code null} for a class of the unnamed package
     */
    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot > 0 ? className.substring(0, dot) : null;
    }

    /**
     * The packages of every boot layer module loaded by the boot or the platform class loader, each mapped
     * to its module.
     *
     * <p>Modules of the system class loader are left out on purpose: in a runner jar those are the
     * launcher's own classes, which the application must not see.</p>
     *
     * @return the modules by package name
     */
    private static HashMap<String, Module> parentVisibleModules() {
        HashMap<String, Module> packages = new HashMap<String, Module>(2048);
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        Set<Module> modules = ModuleLayer.boot().modules();
        for (Module module : modules) {
            ClassLoader loader = module.getClassLoader();
            if (loader == null || loader == platform) {
                for (String packageName : module.getPackages()) {
                    packages.put(packageName, module);
                }
            }
        }
        return packages;
    }

    /**
     * Whether a parent is one of the JDK's own loaders, whose lookup of a boot-module package ends in the
     * boot loader without any other step: the platform loader, or the system loader when it is the JDK's
     * rather than one installed with {@code -Djava.system.class.loader}.
     *
     * @param parent the parent loader
     * @return {@code true} when a boot-module package may be looked up in the boot loader directly
     */
    private static boolean isBuiltinLoader(ClassLoader parent) {
        if (parent == null) {
            return false;
        }
        if (parent == ClassLoader.getPlatformClassLoader()) {
            return true;
        }
        return parent == ClassLoader.getSystemClassLoader()
                && parent.getClass().getModule() == Object.class.getModule();
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

    /**
     * Loads a class that is not loaded yet, with its per-name lock held.
     *
     * <p>A miss costs one {@link ClassNotFoundException}: the parent's, which propagates or is rethrown
     * after the archive fallback misses too, or, after a boot miss, the one built here.</p>
     *
     * @param name          the binary name of the class
     * @param packageName   its package, or {@code null} for the unnamed package
     * @param parentVisible whether the package belongs to a module of the boot or the platform loader
     * @param bootMissed    whether the boot loader was already asked for the class and did not have it
     * @return the class
     * @throws ClassNotFoundException if neither the parent nor the archive has the class
     */
    private Class<?> load(String name, String packageName, boolean parentVisible, boolean bootMissed)
            throws ClassNotFoundException {
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
                Class<?> found = findInArchive(name, packageName);
                if (found != null) {
                    return found;
                }
                throw e;
            }
        }
        if (bootMissed) {
            // The parent is one of the JDK's loaders, and for a boot-module package it would only ask the
            // boot loader again. The archive is the split-package fallback, as it is below.
            Class<?> found = findInArchive(name, packageName);
            if (found != null) {
                return found;
            }
            throw new ClassNotFoundException(name);
        }
        if (parentVisible) {
            try {
                return fromParent(name);
            } catch (ClassNotFoundException e) {
                Class<?> found = findInArchive(name, packageName);
                if (found != null) {
                    return found;
                }
                throw e;
            }
        }
        Class<?> found = findInArchive(name, packageName);
        if (found != null) {
            return found;
        }
        // Still asked after an archive miss, so that classes appended to the boot class path stay
        // reachable. Its ClassNotFoundException is the one the caller gets.
        return fromParent(name);
    }

    private Class<?> fromParent(String name) throws ClassNotFoundException {
        ClassLoader delegate = parent;
        return delegate != null ? delegate.loadClass(name) : super.loadClass(name, false);
    }

    private Class<?> findInArchive(String name, String packageName) throws ClassNotFoundException {
        int head = index.findClass(name);
        if (head == IndexFormat.NO_INDEX) {
            return null;
        }
        int record = index.resolve(head, multiReleaseVersion);
        if (record == IndexFormat.NO_INDEX) {
            return null;
        }
        try {
            return define(name, packageName, record);
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
     * @param name        the binary name of the class
     * @param packageName its package, or {@code null} for the unnamed package
     * @param record      the entry record holding its bytes
     * @return the defined class
     * @throws IOException if the entry cannot be read, or fails verification when it is enabled
     */
    private Class<?> define(String name, String packageName, int record) throws IOException {
        int jarId = index.entryJarId(record);
        index.validateJar(jarId);
        long size = index.entryUncompressedSize(record);
        if (size > ArchiveSource.MAX_SLICE_LENGTH) {
            throw new IOException("The class " + name + " is " + size
                    + " bytes, which is larger than a class file can be");
        }
        ProtectionDomain domain = protectionDomain(jarId);
        definePackageOf(packageName, jarId, domain);
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
     * <p>The common case is settled first: a package that is already defined and unsealed, joined by a
     * class from a jar that has no package sections and is not sealed by default, can raise no sealing
     * violation, so nothing else is looked up for it.</p>
     *
     * @param packageName the package of the class being defined, or {@code null} for the unnamed package
     * @param jarId       the jar the class comes from
     * @param domain      the protection domain of that jar
     */
    private void definePackageOf(String packageName, int jarId, ProtectionDomain domain) {
        if (packageName == null) {
            return;
        }
        Package defined = getDefinedPackage(packageName);
        if (defined != null && !defined.isSealed()
                && index.jarPackageCount(jarId) == 0 && !index.jarSealedByDefault(jarId)) {
            // No package section and no seal, so checkSealing could not throw.
            return;
        }
        int section = index.findPackage(jarId, packageName);
        boolean sealed = index.jarSealedByDefault(jarId);
        if (section != IndexFormat.NO_INDEX && index.packageSealedSpecified(section)) {
            sealed = index.packageSealedValue(section);
        }
        URL base = domain.getCodeSource() == null ? null : domain.getCodeSource().getLocation();
        if (defined != null) {
            checkSealing(defined, packageName, base, sealed);
            return;
        }
        definePackageFromMetadata(packageName, jarId, section, base, sealed);
    }

    private void definePackageFromMetadata(String packageName, int jarId, int section, URL base, boolean sealed) {
        // Decoded once per jar by protectionDomain, which every definition calls first; see its Javadoc.
        String[] attributes = jarAttributes[jarId];
        String specTitle = attributes[0];
        String specVersion = attributes[1];
        String specVendor = attributes[2];
        String implTitle = attributes[3];
        String implVersion = attributes[4];
        String implVendor = attributes[5];
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
     * <p>The same block decodes the jar's six manifest main attributes into {@code jarAttributes}, once per
     * jar rather than once per package, and every package of the jar then starts from those strings. They
     * are read later without the monitor, and that read is safely published: a class definition always
     * calls this method before it defines a package, so the reading thread acquires the monitor after the
     * thread that wrote the attributes released it, or wrote them itself.</p>
     *
     * @param jarId the jar index
     * @return the domain, whose code source location identifies the jar
     */
    private ProtectionDomain protectionDomain(int jarId) {
        synchronized (domains) {
            ProtectionDomain domain = domains[jarId];
            if (domain == null) {
                jarAttributes[jarId] = new String[] {
                    index.jarSpecTitle(jarId), index.jarSpecVersion(jarId), index.jarSpecVendor(jarId),
                    index.jarImplTitle(jarId), index.jarImplVersion(jarId), index.jarImplVendor(jarId)
                };
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
        int exact = resolveExact(logical);
        // ZipFile.getEntry and NestedJarFile.getEntry both retry a name within that jar with a trailing
        // slash, so that a lookup of "some/package" finds its directory entry. That fallback is consulted
        // only on a miss or for a name the packager flagged as having a directory twin: an unflagged hit is
        // final after one probe, because no jar holds the directory spelling of its name.
        if (exactIsFinal(logical, exact)) {
            return exact;
        }
        return resolveWithDirectoryFallback(logical, exact);
    }

    /**
     * Resolves a slashless name that missed, or whose name has a directory twin, against both spellings.
     *
     * <p>The first exact and fallback candidates are compared by jar rather than trying the alternatives
     * globally: an earlier directory wins over a later exact-name file, while the exact name wins when one
     * jar has both. The directory spelling falls under the same Micronaut service policy as the name,
     * because a slashless name is at or under {@value IndexFormat#MICRONAUT_SERVICES_PREFIX} exactly when
     * the same name followed by a slash is.</p>
     *
     * @param logical the normalised name, which does not end with a slash
     * @param exact   the record the exact name resolved to, or {@link IndexFormat#NO_INDEX}
     * @return the record, or {@link IndexFormat#NO_INDEX} when nothing matches
     */
    private int resolveWithDirectoryFallback(String logical, int exact) {
        int directory = resolveChain(index.findDirectory(logical), logical);
        return selectResource(exact, directory);
    }

    /**
     * Whether the exact-name lookup already settles a resource, so that its directory spelling need not be
     * probed: the name asks for a directory itself, or it resolved to a record whose name has no directory
     * twin anywhere in the index.
     *
     * @param logical the normalised name
     * @param exact   the record the exact name resolved to, or {@link IndexFormat#NO_INDEX}
     * @return {@code true} when no directory fallback can change the answer
     */
    private boolean exactIsFinal(String logical, int exact) {
        return namesDirectory(logical) || (exact != IndexFormat.NO_INDEX && !index.entryDirectoryTwin(exact));
    }

    /**
     * Selects between the next exact-name and directory-fallback candidates in classpath order.
     *
     * @param exact     the next exact-name record
     * @param directory the next trailing-slash record
     * @return the earlier candidate, preferring {@code exact} when both belong to one jar
     */
    private int selectResource(int exact, int directory) {
        if (exact == IndexFormat.NO_INDEX) {
            return directory;
        }
        if (directory == IndexFormat.NO_INDEX) {
            return exact;
        }
        return index.entryJarId(exact) <= index.entryJarId(directory) ? exact : directory;
    }

    /**
     * Resolves a name exactly as it was written, with no retry.
     *
     * @param logical the normalised name
     * @return the record, or {@link IndexFormat#NO_INDEX} when nothing matches
     */
    private int resolveExact(String logical) {
        return resolveChain(index.find(logical), logical);
    }

    /**
     * Picks the record a chain resolves to, restricted to the application layer for a name in the merged
     * Micronaut service tree.
     *
     * @param head    the chain head
     * @param logical the normalised name that selects the policy
     * @return the record, or {@link IndexFormat#NO_INDEX} when nothing matches
     */
    private int resolveChain(int head, String logical) {
        if (namesMicronautServices(logical)) {
            return index.resolveInJar(head, multiReleaseVersion, IndexFormat.APPLICATION_JAR_ID);
        }
        return index.resolve(head, multiReleaseVersion);
    }

    /**
     * Whether a name belongs to the merged Micronaut service tree, including the root directory requested
     * without its trailing slash.
     *
     * @param logical the normalised resource name
     * @return {@code true} when only the merged application-layer copy may be visible
     */
    private static boolean namesMicronautServices(String logical) {
        String prefix = IndexFormat.MICRONAUT_SERVICES_PREFIX;
        return logical.startsWith(prefix)
                || (logical.length() == prefix.length() - 1 && prefix.startsWith(logical));
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
