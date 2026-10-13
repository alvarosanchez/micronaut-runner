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
| `MICRONAUT-INF/transforms.txt` | Present only when a build-time transform changed a class or recorded a fallback. Tab-separated lines: the first names the Runner version, then one line per nested JAR and transform with its counts, then one `lambdas` line per JAR, or for the application layer, with the lambda call sites rewritten and left. It is not indexed, the launcher never reads it at run time, and `extract` ignores it; `inspect` prints it. |
| `META-INF/micronaut/**` | The Micronaut bean metadata of the application and of every dependency, merged and de-duplicated, with explicit directory entries. See [Merged Micronaut metadata](#merged-micronaut-metadata). |
| `MICRONAUT-INF/classes/` | An explicit directory entry for the application layer, so that the `CodeSource` location of every application class names something the archive really carries. |
| `MICRONAUT-INF/classes/**` | The application's own classes and resources, stored exploded. This is "jar 0"; an entry's logical name is its path with this prefix removed. Directories inside the layer are not stored; the index carries a synthetic record for each one instead. |
| `MICRONAUT-INF/lib/<name>.jar` | The dependencies, in class path order, each a complete JAR. |

## Re-packing

With `STORED` and `HYBRID` compression, each dependency is re-packed:

- Its own entries are written `STORED` (`HYBRID`: only the startup classes and resources; see below).
- Signature files (`META-INF/*.SF`, `*.DSA`, `*.RSA`, `*.EC`, `META-INF/SIG-*`) and `META-INF/INDEX.LIST` are dropped.
- Manifests are never rewritten, so package metadata and sealing survive.
- The build-time class transform runs: lambda desugaring, which adds a generated `$$Lambda$R<n>` class after each
  host. Rewritten entries get new CRC-32 values. The application layer's lambdas are desugared the same way.

`PRESERVE` copies each dependency as it is, or its stripped copy (below), and desugars nothing.

With `stripLocalVariables`, Micronaut AOT's local-variable stripping (`micronaut-aot-bytecode`) rewrites the classes of
every dependency that is not a project module before it is staged, in every mode: a dependency with a stripped class
is re-packed, or in `PRESERVE` copied, from its rewritten copy. That copy keeps every entry's order, times and
compression method. A signed JAR, and a JAR that holds an entry name more than once, are not stripped.

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

## Static service table

For an application whose micronaut-core the packager supports, the packager adds a static service table to the
application layer: a few classes under `io/micronaut/runner/generated/services/`, and the line in
`META-INF/services/io.micronaut.core.optim.StaticOptimizations$Loader` that registers them, in front of the
application's own file when it has one. The table lists, for each service type, the names in every JAR's
`META-INF/services/` file and in the merged directory, in the order Micronaut's scan finds them, so that Micronaut
reads neither at startup. `StaticServiceTableGenerator` documents the details.

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
- **`JarFile` of a nested JAR.** `isMultiRelease()` and `getVersion()` on a `JarURLConnection`'s `JarFile` report the
  outer archive; multi-release resolution itself is per nested JAR and correct.
- **Entry stub.** The packager generates a class that calls the application's `main` directly when the main class is
  a public, non-abstract class in a named package that declares its own `public static void main(String[])`.
  Otherwise the launcher enters `main` reflectively, and the build log says why. The difference is not measurable;
  the `entryStub` option turns the stub off.
- **Static service table and the context class loader.** The table answers a lookup only when the thread's context
  class loader is the application's, as the main thread's is; any other lookup falls back to Micronaut's scan.
- **Generated lambda classes.** The classes `desugarLambdas` generates are ordinary classes: `Class.isHidden()` is
  `false`, and `Class.forName` finds them.
- **Trace switch.** `-Dmicronaut.runner.static-services.trace=true` prints every lookup the static table answers.
