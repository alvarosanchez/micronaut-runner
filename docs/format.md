# The Runner JAR format

This document is for maintainers and for anyone who reimplements or inspects the format. Users do not need it: the
[user guide](https://micronaut-projects.github.io/micronaut-runner/latest/guide/) covers what an application sees.

## Outer archive

Every entry of the outer archive is `STORED`, which is what lets a nested JAR's bytes be read in place at a known
offset.

| Entry | Purpose |
|---|---|
| `META-INF/MANIFEST.MF` | Written first. `Main-Class` is `io.micronaut.runner.Launcher`. `Micronaut-Runner-Format` carries the format version, `Micronaut-Runner-Start-Class` the application main class, for information only. |
| `MICRONAUT-INF/index.bin` | Written second, so the launcher finds it after reading only a couple of central directory records. |
| `io/micronaut/runner/**.class` | The launcher. Only class files are copied from the launcher library; its manifest and Maven metadata are not. |
| `MICRONAUT-INF/transforms.txt` | Present only when a build-time transform changed a class or recorded a fallback. Tab-separated lines: the first names the Runner version, then one line per nested JAR and transform with its counts (for `desugarLambdas`, a nest that fell back counts as one fallback), then one `lambdas` line per JAR, or for the application layer, with the lambda call sites rewritten and left, in total and by reason. It is not indexed, the launcher never reads it at run time, and `extract` ignores it; `inspect` prints it. |
| `META-INF/micronaut/**` | The Micronaut bean metadata of the application and of every dependency, merged and de-duplicated, with explicit directory entries. See [Merged Micronaut metadata](#merged-micronaut-metadata). |
| `MICRONAUT-INF/classes/` | An explicit directory entry for the application layer, so that the `CodeSource` location of every application class names something the archive really carries when it is looked up in the outer archive itself. |
| `MICRONAUT-INF/classes/**` | The application's own classes and resources, stored exploded. This is "jar 0"; an entry's logical name is its path with this prefix removed. Directories inside the layer are not stored; the index carries a synthetic record for each one instead. |
| `MICRONAUT-INF/lib/<name>.jar` | The dependencies, in class path order, each a complete JAR. |

## Re-packing

With `STORED` and `HYBRID` compression, each dependency is re-packed:

- Its own entries are written `STORED` (`HYBRID`: only the startup classes and resources; see below).
- Signature files (`META-INF/*.SF`, `*.DSA`, `*.RSA`, `*.EC`, `META-INF/SIG-*`) and `META-INF/INDEX.LIST` are dropped.
- Manifests are never rewritten, so package metadata and sealing survive.
- The build-time class transforms run. Lambda desugaring is Micronaut AOT's class path transform, which runs once
  over the application layer and every dependency before they are re-packed: a dependency it rewrote is re-packed
  from its copy, which holds each generated `$$Lambda$R<n>` class right after its host (the sites of one host that
  share their functional interface, interface method and captured types can share one class). Rewritten entries get
  new CRC-32 values. The application layer's lambdas are desugared by the same run.

`PRESERVE` copies each dependency as it is, or its stripped copy (below), and desugars nothing.

With `stripLocalVariables`, the same run of Micronaut AOT's class path transform (`micronaut-aot-bytecode`) also strips
the classes of every dependency that is not a project module before it is staged, in every mode: a dependency with a
stripped class is re-packed, or in `PRESERVE` copied, from its rewritten copy. That copy keeps every entry's order,
times and compression method. A signed JAR, and a JAR that holds an entry name more than once, are not stripped.

With a startup class list (`startupClasses`, which the recorded startup profile provides), re-packing also writes
each nested JAR's startup classes first, in the order they were recorded, after `META-INF/` and the manifest. Nested
JARs keep their places in the outer archive and class path order, so lookups and duplicate precedence do not change.

`HYBRID` re-packs as `STORED` does, startup classes first, but keeps the other classes compressed. An unlisted class
that no transform changed keeps its original compressed bytes; one a transform rewrote or generated is deflated
afresh, so a `HYBRID` archive's bytes depend on the build JDK's zlib. Listed classes, resources, and classes that the
dependency stored or that do not get smaller stay stored. Without a startup class list, `HYBRID` writes the nested
JARs `STORED`, with a warning.

## The index

`MICRONAUT-INF/index.bin` is a little-endian binary structure read directly from the memory mapping, with bounds
checks but no parsing into objects: a fixed header, then a jar table, a package table, an entry table, a hash table
and a string table, and, for an archive packaged with a startup class list, a preload table and a JDK preload table.

The authoritative description of every byte is
[`IndexFormat`](../runner-launcher/src/main/java/io/micronaut/runner/IndexFormat.java) in `micronaut-runner-launcher`,
whose compile-time constants both the writer and the reader use. Change the format there, and bump the format
version when an older launcher could misread a newer archive.

## Detecting a stale archive

The index stores the outer file's length, and every jar record stores the offset of the local file header that
introduces it. The length is checked when the archive opens, and each header once, on the JAR's first use; a
mismatch fails with a message telling the user to rebuild. This detects an archive that was appended to, truncated or
re-zipped after packaging, which would otherwise mean reading from wrong offsets. It is a staleness check, not an
integrity check: a modification that keeps the length and the header positions passes it.
`-Dmicronaut.runner.verify=true` checks every entry's CRC-32 as it is read.

## Merged Micronaut metadata

Micronaut locates beans by calling `getResources("META-INF/micronaut/")` and then listing the directory each returned
URL denotes. A URL cannot list a directory generically, so the framework opens the archive behind it; for a JAR nested
in another JAR that means mounting a ZIP file system over a ZIP file system, which the JDK implements by reading the
whole inner archive into memory. Doing that once per dependency would dominate startup.

So every `META-INF/micronaut/<service>/<name>` entry of the application and of every dependency is written,
de-duplicated and in class path order, into `META-INF/micronaut/` at the root of the outer archive, with real
directory entries. The class loader resolves any name under that prefix against the merged copy alone, so
`getResources("META-INF/micronaut/")` returns one URL and Micronaut lists it in one pass over an archive the JVM
already has open.

Almost all of these entries are empty markers whose name carries the information. One that has content keeps the
bytes of its first contributor in class path order, and the build warns when two JARs contribute the same name with
different content: every lookup under the prefix is answered from the merged copy, so the alternative would be an
empty stream for a file that is not empty.

`META-INF/services/` files stay where they are, one per dependency: they are read as files through their URL, which
works per JAR without special handling.

## Service index

For an application on micronaut-core 5.3.0 or later, the packager adds micronaut-core's service index to the
application layer, generated by Micronaut AOT's `micronaut-aot-service-index`: the loader and its data classes under
`io/micronaut/aot/serviceindex/generated/`, and the line in
`META-INF/services/io.micronaut.core.optim.StaticOptimizations$Loader` that registers it, first in the application's
own file when it has one. The index lists, for each service type, the names Micronaut's scan finds, so that Micronaut
reads neither directory at startup.

The generator builds the index with the application's own micronaut-core, over the class path `RunnerClassLoader`
gives the application. A view JAR, written to the work directory and deleted after the call, stands for the
application layer: its service files and the merged `META-INF/micronaut/` copy, as the archive stores it, directory
entries included. The nested JARs follow it as they were staged. The index lists no class path, because the names the
JARs have at run time are not known when the archive is packaged, so micronaut-core compares none: it serves the index
to the class loader of the loader class, `RunnerClassLoader` in the single JAR and the system class loader in the
extracted layout. `ServiceIndexing` documents the details.

## Archive path URLs

The outer archive's `file:` URL has one canonical form. File-system separators are `/` over the whole URL path,
including components after Unicode text. Unicode is encoded as UTF-8 bytes, and spaces, `%`, `#` and `?` are
percent-encoded. A Windows drive path starts with `file:/C:/`; a UNC path with `file://server/share/`. On POSIX, a
backslash is data rather than a separator and is encoded as `%5C`.

## Launcher coding rules

The launcher is on every application's startup path, so it keeps off the JVM's slow paths: no lambdas, no string
concatenation through `invokedynamic`, no reflection on the entry path, and no `java.util.logging`. It uses only
`java.base`. It hands control to the application's `main` through an interface call, which the generated entry stub
implements. The tool modes (`inspect`, `list`, `extract`) are loaded only when selected, and these rules do not apply
to them.

## Details kept out of the user guide

These facts are true and tested, but an application developer cannot act on them, so the user guide leaves them out.

- **Class loader conformance.** The launcher's class loader is tested against the JDK's `URLClassLoader` over the same
  JARs, for resource order, duplicate resources, manifests, sealing and multi-release resolution.
- **The launcher's own package.** Classes under `io.micronaut.runner` always come from the launcher: a class of the
  same name in the application or a dependency cannot replace them.
- **`JarURLConnection` of the application layer.** A URL under `MICRONAUT-INF/classes/` is described as an entry of
  the layer, the way a nested JAR's URL is: `getJarFileURL()` is `jar:file:/app.jar!/MICRONAUT-INF/classes`,
  `getEntryName()` is the class path name, and `getJarFile()` lists the layer by class path names. Scanners that list
  `getJarFile()` for the entries under a location, such as Flyway, depend on it. The URL text, and with it every code
  source, is unchanged; the layer's root names no entry and opens as an empty stream. A `META-INF/micronaut/` file that
  is both in the layer and in the merged directory is listed once, as in a fat JAR. `getManifest()` is the layer's own
  manifest when the layer has one; the plugins build the layer from class directories, which have none, and it is
  then the outer archive's manifest, which carries the application's attributes.
- **`JarFile` of a nested JAR or of the application layer.** `isMultiRelease()` and `getVersion()` on a
  `JarURLConnection`'s `JarFile` report the outer archive; multi-release resolution itself is per JAR and correct.
- **Entry stub.** The packager generates a class that calls the application's `main` directly when the main class is
  a public, non-abstract class in a named package that declares its own `public static void main(String[])`.
  Otherwise the launcher enters `main` reflectively, and the build log says why. The difference is not measurable;
  the `entryStub` option turns the stub off.
- **Service index and the class loader.** micronaut-core serves the index only to a lookup that names the class
  loader it was built for, the one that defines the generated loader; a lookup that names another falls back to the
  scan. A lookup made before the `StaticOptimizations` loaders have run scans too.
- **Generated lambda classes.** The classes `desugarLambdas` generates are ordinary classes: `Class.isHidden()` is
  `false`, and `Class.forName` finds them.
- **Extracted layout and directory entries.** A dependency that stores no `META-INF/micronaut/` directory entries is
  invisible to a scan of the extracted layout, but its names are in the index, which comes from the single JAR's
  merged copy. With `-Dmicronaut.service.index.validate=true`, the extracted layout reports those names as a
  difference.
