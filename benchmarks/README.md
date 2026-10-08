# Micronaut Runner benchmarks

This module holds Micronaut Runner's benchmark suites: JMH micro-benchmarks of the class loader, a profile of
Runner's own packaging cost, and an end-to-end startup harness. This file says how they produce their results, and
every property they take.

The user guide publishes no results. Its
[Performance](https://micronaut-projects.github.io/micronaut-runner/latest/guide/#performance) section states only
rounded, relative claims, refreshed from a quiet run on a maintainer's machine, never from CI figures; see
[Claims in the user guide](#claims-in-the-user-guide) for where each one comes from.

Nothing a run produces is committed. Reports land in `benchmarks/build/reports/{startup,packaging,jmh}`,
and CI uploads them as workflow artifacts, which expire after 30 days.

## Running

```bash
./gradlew :benchmarks:jmh
./gradlew :benchmarks:packagingProfile
./gradlew :benchmarks:startupBenchmark -Pbenchmarks.iterations=20
./gradlew :benchmarks:startupBenchmark -Pbenchmarks.iterations=20 -Pbenchmarks.optionalRows=true
./gradlew :benchmarks:startupBenchmark -Pbenchmarks.iterations=1 -Pbenchmarks.diagnostics=true
./gradlew :benchmarks:startupBenchmark -Pbenchmarks.iterations=20 -Pbenchmarks.cpus=1 -Pbenchmarks.variants=shadow,runner-stored
```

`benchmarks.yml` runs the benchmark-tool tests, JMH, `packagingProfile` and `startupBenchmark` weekly, on demand,
and on pull requests labelled `relates-to: benchmarks`, never as a pull request gate: shared runners are too noisy to fail a build on. A dispatch can set `iterations`, `cpus`,
`pageCache` and `optionalRows`, which adds the opt-in rows to the main job only. Weekly and dispatched runs also
start four `startup-cpu-matrix` legs, which measure the Runner and Shadow comparison rows at one CPU, at two CPUs,
unlimited, and unlimited with `evict-artifacts`. Each leg runs on its own machine, so compare the legs' paired
Runner − Shadow differences, never their absolute times.

Short smoke runs (a handful of iterations, a subset of rows) stay labelled descriptive. They are for checking that
the harness works, never for a claim.

## Properties

| Property | Task | Default | What it does |
|---|---|---|---|
| `-Pbenchmarks.iterations=N` | `startupBenchmark` | `10` | Measured iterations per row; 3 discarded warm-up iterations come first (fewer when `N` < 3). |
| `-Pbenchmarks.optionalRows=true` | `startupBenchmark` | off | Also builds and measures the opt-in rows. Cannot be combined with `benchmarks.variants`. |
| `-Pbenchmarks.variants=a,b` | `startupBenchmark` | every core row | Builds and measures only the named rows, in report order (see below). |
| `-Pbenchmarks.allowPartial=true` | `startupBenchmark` | off | The partial policy, for investigation (see below). |
| `-Pbenchmarks.diagnostics=true` | `startupBenchmark` | off | Adds one `-Xlog:class+load` diagnostic launch per row, outside every timing distribution. |
| `-Pbenchmarks.cpus=N` | `startupBenchmark` | none | Linux, needs `taskset`: every child JVM on `N` CPUs (see below). |
| `-Pbenchmarks.pageCache=mode` | `startupBenchmark` | `uncontrolled` | `uncontrolled`, `evict-artifacts` or `drop-all` (see below). |
| `-Pbenchmarks.micronautCore=V` | `startupBenchmark` | the sample's platform | Builds the sample with every `io.micronaut` module aligned to micronaut-core `V`. |
| `-Pbenchmarks.sample=dir` | `startupBenchmark` | `benchmark-large` | Another sample under `test-suite/samples`. |
| `-Pbenchmarks.workloads=a,b` | `packagingProfile` | `small,representative,wide` | Synthetic packaging shapes; `no-manifest` is the fourth. |
| `-Pbenchmarks.packagingIterations=N` | `packagingProfile` | `3` | Independent packaging workers per scenario. |
| `-Pjmh.includes=regex` | `jmh` | everything | Only the matching JMH benchmarks. |

The harness's own options (`--warmup`, `--seed`, `--readiness`, `--timeout`) keep their defaults from Gradle: 3
warm-ups, seed `20260921`, readiness path `/hello` and a 120 s start timeout.

Where things land:

- `benchmarks/build/reports/startup/` – `results.json`, `summary.md` and, with diagnostics, `diagnostics/`.
- `benchmarks/build/reports/packaging/` – `packaging-results.json` and `packaging-summary.md`.
- `benchmarks/build/reports/jmh/results.json`.
- `benchmarks/build/tmp/startupBenchmark` – the startup task's built rows and trained caches.

## Which JDK a run uses

Every startup row, every cache training and verification launch, and the nested build of the sample run on the
JDK that runs the harness: `SampleBuild` launches `java.home`'s `java`, and gives the sample build the same JDK as
`JAVA_HOME`. `startupBenchmark` is a plain `JavaExec`, so that is Gradle's JDK unless something sets the task's
`javaLauncher`.

To measure another JDK while Gradle stays on 25, point `startupBenchmark` at a toolchain with an init script:

```groovy
allprojects {
    tasks.matching { it.name == 'startupBenchmark' }.configureEach {
        javaLauncher = project.extensions.getByType(JavaToolchainService).launcherFor {
            languageVersion = JavaLanguageVersion.of(27)
        }
    }
}
```

```bash
./gradlew :benchmarks:startupBenchmark --init-script jdk27.gradle \
  -Porg.gradle.java.installations.paths=/path/to/jdk-27 -Porg.gradle.java.installations.auto-download=false
```

Gradle 9.4.1 cannot compile build scripts on JDK 27 (class file major version 71), so on a machine whose Gradle
caches have not compiled the sample's scripts yet, keep the nested build's daemon on 25 with
`org.gradle.java.home=<jdk 25>` in `~/.gradle/gradle.properties`; the rows still launch on the harness's JDK. A
nested daemon started on another JDK keeps running after the task ends; stop it by its PID.

## Startup harness

### Rows

`SampleBuild`'s `VariantSpec` table is the only list of rows: name, description, entry mode, cache flag, core or
opt-in, and the row it is built from. Every row is built from one compilation of the sample and one dependency
resolution, so a difference between two rows is a difference in packaging.

- **Core rows** are always built and scheduled. They are the run's required variants and gate its exit code.
- **Opt-in rows** (ablations, controls and experiments) are built only with `-Pbenchmarks.optionalRows=true` or
  when named in `-Pbenchmarks.variants`. Their failures are reported but never change the exit code.

The Runner rows call `RunnerJarBuilder` directly with the packaging library's defaults of the day, build-time
transforms included; a control row changes one option. The harness passes the builder a logger, so the run's log
has every info and warning line the builder printed, prefixed `[startup-benchmark] <row>:`. That is where a run
says which build steps ran, stood down (with the reason) or were off for each row: the `Runner options:` line,
the strip and desugar counts, the precompiled Logback configurator, the static service table. The
description of every Runner row also ends with `static services: N slots (core V)` when its JAR carries a static
service table, and `dynamic service scan` when it does not.

`runner-stored-preload` records a startup profile from `runner-stored` in every run, with the recorder that
`recordStartupProfile` uses, and packages it. No other row preloads: preloading by convention needs a committed
`src/main/micronaut-runner/startup-classes.txt`, which the samples do not commit, and the harness calls the
builder, not the plugin convention. The recording launch runs on every CPU, even under `-Pbenchmarks.cpus`; the
class list barely depends on the CPU count.

The recording is made at most once per run and shared: `runner-stored-ordered` packages the same inputs with it
exactly as `runner-stored-preload` does (each nested JAR's startup classes first) and launches with
`-Dmicronaut.runner.preload=false`, so it differs from `runner-stored` only in entry order;
`runner-stored-hybrid` does the same with `HYBRID` compression, and is unavailable when its JAR holds no deflated
nested entry (the build fell back to `STORED`). Naming either row builds `runner-stored` and records from it.

In a row name, `maot` is Micronaut AOT and the `-aot` suffix is the JDK AOT cache. The declared pairs group like this:

- **Defaults:** `runner-stored` − `shadow`, the headline; it also includes the cost of inflating Shadow's DEFLATED
  entries.
- **Compression-matched:** `runner-stored` − `shadow-stored` (neither side inflates class bytes) and
  `runner-preserve` − `shadow` (both do) measure what Runner's layout itself buys; `shadow-stored` − `shadow` is the
  compression share alone.
- **Caches:** `runner-stored-aot`, `runner-extracted-aot` and `shadow-aot` against each other. Each cached row shares
  its uncached twin's exact artifact bytes.
- **Controls:** `runner-stored` − control and `runner-stored-aot` − its `-aot` twin, for each default the control turns
  off (`-joran`, `-dynamic-services`, `-lambdas`). `runner-extracted-aot` − `runner-extracted-lambdas-aot` runs on the
  JDK's own loader, which archives lambdas itself, so it checks that desugaring costs the extracted layout nothing.
- **Opt-in candidates:** `-preload`, `-positional` and `-stripdebug` (`stripLocalVariables=true`) against
  `runner-stored` (and their `-aot` twins against `runner-stored-aot`); `runner-stored` − `runner-stored-reflection`
  for the entry stub, whose interval still spans zero at 20 iterations, so run it with 40.
- **Micronaut AOT:** `runner-maot` − `shadow-maot` is like for like, both archives holding the same AOT-optimized
  application; `runner-stored` − `shadow-maot` is Runner's `-all.jar` against Shadow's `-all-optimized.jar`;
  `runner-maot` − `runner-stored` and `shadow-maot` − `shadow` are what Micronaut AOT adds to each packaging, and
  `runner-maot-aot` − `shadow-maot-aot` compares the two with a cache on each side. `runner-maot` takes the same
  packaging defaults as `runner-stored`, so Micronaut AOT's own Logback configurator takes the place of the precompiled
  one there, which is why Micronaut AOT adds less to Runner than to Shadow.

`-Pbenchmarks.variants=a,b` builds and measures only the named rows. Naming an opt-in row builds it. A named row
that derives from another (a cached row from its uncached twin, `runner-extracted` from `runner-stored`) builds
that row too, but it is neither measured nor reported. With `variants`, the required rows are the named core rows;
a selection of opt-in rows only has no required row, both reports say so instead of calling the run complete, and
the task exits zero only when at least one measured attempt succeeded.

### Measurement

- Each sample starts a fresh JVM: a cold JVM process, not a cold disk or page cache unless the page-cache mode
  says so.
- Readiness is measured from outside, from process spawn to the first HTTP 200 on `/hello`, polled every 2 ms with
  one persistent client on one monotonic clock that starts immediately before the spawn. It includes serving the
  first request. The framework's "startup completed" line and its own figure are recorded next to it and are never
  substituted for it.
- The variants run interleaved, in a fresh random order within each iteration from the recorded seed. Warm-up
  iterations are discarded; every raw sample is kept.
- `StartupHarness` is the only code that spawns and polls an application: timed runs, cache training and
  verification all go through it. It removes `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` and
  `JDK_AOT_VM_OPTIONS` from every child launch, so ambient flags cannot change a row; nothing about them is
  recorded.
- Timing runs carry no diagnostic flags: `-Xlog` changes what is measured. `-Pbenchmarks.diagnostics=true` adds
  separate `-Xlog:class+load` launches, whose readiness shows only the logging overhead and never enters a timing
  distribution.

### Pairing and intervals

Comparisons are a declared list of candidate − baseline pairs (`SampleBuild.comparisons()`), led by
`runner-stored` − `shadow`, so a negative difference means the candidate is faster. A pair is reported only when
both rows ran.

- Readiness is paired by iteration. Each comparison reports the median of the per-iteration differences, and a
  relative change that is the median of the per-iteration candidate/baseline ratios minus one, not a ratio of
  medians.
- Only complete measured iterations count. An iteration that lacks either row is excluded and listed, never
  re-paired with another.
- A 95% percentile-bootstrap interval (2,000 resamples) appears only with at least ten complete pairs, and a
  median's interval only with ten successful samples. Below that the summary is descriptive only. Ten is a
  reporting threshold, not a guarantee of precision: a zero-width interval over repeated observations cannot
  recover variability that was never observed.
- Memory and class-count differences are differences of the two per-row medians, with no interval.

### Required, fail-closed matrix

By default every required row must be built and complete every requested measured iteration; otherwise the task
exits nonzero after writing both `results.json` and `summary.md`. Both reports give requested, attempted,
successful, failed and skipped counts for the warm-up and measured phases. A row that could not be built is
skipped, not reported as a failed process. A warm-up failure is visible but does not by itself fail a complete
measured matrix.

`-Pbenchmarks.allowPartial=true` selects the partial policy, for investigation. An incomplete partial run is
labelled as such in both reports and exits zero only when at least one measured attempt succeeded. Its
distributions remain conditional on the successful attempts: the harness never turns a timeout into a zero
duration, and never retries until a favourable sample appears.

### `results.json` and provenance

`results.json` has a versioned schema (`schemaVersion`). It records the runner and sample revisions with their
clean or dirty state; the JDK version, runtime version, vendor and VM name; the OS name and version and the
architecture; the logical CPU count (captured before `-Pbenchmarks.cpus` pins the harness) and the physical
memory; the seed and the actual attempt order; each row's effective command; and the SHA-256 of every ordered
launch input, so a reader can identify the exact launched bytes. Each attempt is recorded once, under `attempts`,
with its row, iteration, schedule order, phase, outcome, failure and exit information, its timing when it
produced a valid measurement, and its `atReadiness` snapshot. The constants every run shares are written once,
under `policy`.

Paths appear as tokens, the same in commands and in failure messages: `${java}`, `${input:N}` for a launch
input, `${workdir}`, `${sample}`, `${work}`, `${output}` and `${user-home}`.

### Memory and classes at readiness

Every successful timing run records memory and loaded classes once, after readiness and outside the timed
interval: `atReadiness` per attempt in `results.json`, per-row medians in `summary.md`.

- Linux: `VmRSS`, `VmHWM`, `RssAnon` and `RssFile` from `/proc/<pid>/status`; `majorFaults` (`majflt` of
  `/proc/<pid>/stat`) and `readBytes` (`read_bytes` of `/proc/<pid>/io`).
- macOS: resident size, `phys_footprint` and its lifetime peak from `proc_pid_rusage`.
- Class counts: one `jstat -snap` of the child's JDK, which reads its performance counters without attaching and
  adds no flag to the child.
- A value that cannot be read is `null`: every value on Windows, the class counts of a JVM started with
  `-XX:-UsePerfData`, and the fault and read counters off Linux.

RSS includes clean, file-backed pages of memory-mapped files (the Runner archive, the CDS and AOT archives, the
JDK's libraries) that the kernel can drop and re-read. Private memory (`RssAnon` on Linux, `phys_footprint` on
macOS) is the like-for-like comparison between a mapped archive and a flattened JAR.

### Deployment size

The size columns are the complete deployment, not only the launched file: the sum of the logical lengths of every
required regular file. That is the application and dependency components for the thin and exploded layouts, the
whole extracted layout, or the single archive for the Shadow and Runner rows, plus the trained cache file for
every cached row, because the launch cannot start without it.

- Each component and the total have two figures: raw logical bytes, and gzip -6 bytes, the sum of per-file gzip -6
  lengths. The second approximates an image layer's transfer size; tar headers and cross-file dictionary effects
  are left out. Both are computed while rows are prepared, never during timed launches.
- A path named twice is counted once, for the first component that names it. Symbolic links are neither followed
  nor counted, except that a required cache file that is a link is an error. Distinct hard-linked paths count
  separately.
- Build workspaces, logs, reports and neighbouring rows are never included.

STORED stays the default and no resources-only deflate mode is planned: classes are about 94% of the benchmark
sample's uncompressed content, so compressing only resources could not close the raw-size gap.

### Trained caches

The cached rows (`shadow-aot`, `runner-stored-aot`, `runner-extracted-aot`, the opt-in `-aot` twins and the
Micronaut AOT pair's `shadow-maot-aot` and `runner-maot-aot`) use the JDK AOT cache, so a trained Runner layout is
never compared only with an untrained Shadow one. Each cached row and its uncached twin share the exact artifact
bytes, ordered class path, working directory, workload and JVM options except the cache selection. Default JDK
class sharing stays on wherever the JDK enables it; reuse of platform classes from the JDK's default archive is
not evidence of an application cache, so it is a separate baseline condition. Classes that Runner's loader
defines are cached but not AOT-linked. The JDK AOT cache is not GraalVM Native Image.

Lifecycle, all outside the timing samples and through `StartupHarness`:

1. **Train.** Launch with `-XX:AOTCacheOutput=<cache>` (plus the JDK's creation flags, such as JDK 27's
   `-XX:+UnlockDiagnosticVMOptions -XX:+AOTCompatibleOopCompression`) and, for a Runner single JAR only, the
   training-only `-Dmicronaut.runner.aot.training=true`. Wait for readiness, exercise `/hello`, send SIGTERM and
   accept exit status 0 or 143. The cache is written to a temporary file and moved into place only when nonempty.
2. **Verify.** A strict launch with `-XX:AOTMode=on -XX:AOTCache=<cache> -Xlog:class+load` must load
   `com.example.Application`, an application class, from the cache's shared objects file. Aggregate shared-class
   counts are not enough.
3. **Measure.** Timed launches add `-XX:AOTMode=on -XX:AOTCache=<cache>` right after `java`, so a missing or
   unusable cache fails the launch instead of running uncached. They launch the actual Shadow JAR, Runner JAR or
   extracted application JAR, with nothing added to either class path.

Any failed phase, or a JDK older than 25, makes the row unavailable. The harness never substitutes an uncached
process for a cached row.

The cache identity is a SHA-256 over a schema salt, the exact JDK runtime and VM build, the VM name, the
architecture, the JDK's AOT creation flags, the CPU count under `-Pbenchmarks.cpus`, the launch command after
`java`, the readiness and workload paths, the training-only property of a Runner single JAR, and each ordered
launch input's position, file name and content digest. For a Runner row that input is the final JAR, so its
embedded application and dependency bytes are part of the identity. Caches are reused across runs while those
bytes are unchanged, because the harness pins every launch input's modification time, which the JDK also checks.
A reused cache is verified again before it is used. `rm -rf benchmarks/build/tmp/startupBenchmark/managed-aot`
forces a retrain. `results.json` records each cache's `cacheSha256` and whether this run reused it
(`cacheReused`); `summary.md` names the reused and trained caches. Training and preparation time are reported
separately from deployment bytes and readiness.

The production recipe, extraction and training included, is the guide's
[JDK AOT Cache](https://micronaut-projects.github.io/micronaut-runner/latest/guide/#jdkAotCache) section; the
harness does not use the plugins' training tasks.

### Page cache

`-Pbenchmarks.pageCache` chooses what happens to the OS page cache before every launch (warm-up, measured and
diagnostic), always outside the timed interval:

- `uncontrolled` (default): no eviction, no `sync`, no `sudo`. The cache holds whatever the build and earlier
  starts left in it, so the run is neither a warm-cache nor a cold-cache measurement.
- `evict-artifacts`: Linux only, no root. After preparation the harness runs `sync` once; before every launch it
  evicts every regular file of the row's artifact and launch inputs with `posix_fadvise(POSIX_FADV_DONTNEED)`: the
  JAR, the AOT cache file, the thin and exploded `lib/` JARs and class trees, and the whole extracted layout. The
  JDK and the shared work directory stay cached, so only the packaging is cold.
- `drop-all`: needs passwordless `sudo`. Before every launch, `sync` and then `echo 3 > /proc/sys/vm/drop_caches`
  on Linux or `purge` on macOS. Pages a live process maps stay resident, including the parts of the JDK the
  harness JVM maps, so it is colder than `evict-artifacts` but not a fresh node.

An unsupported combination (`evict-artifacts` off Linux, `drop-all` without passwordless `sudo`) fails at once
with a usage error; there is no fallback. `majorFaults` and `readBytes` confirm eviction: a cold launch reads its
files from storage, and a memory-mapped Runner archive also takes major faults, while Shadow reads its JAR with
`read` calls, which are not faults. A cold-cache result names its page-cache mode and the storage the report
recorded under the work directory: local NVMe can hide a difference that network block storage would not.

### CPU limit

`-Pbenchmarks.cpus=N` (Linux, needs `taskset`) runs every child JVM, the cache training and verification launches
included, on the first `N` CPUs the harness may use, and pins the harness's own threads to the rest once the rows
are prepared. `N` must leave the harness at least one CPU.

- Before the sample build, a probe JVM started under the limit must see exactly `N` CPUs, and its ergonomic flags
  are recorded, the collector among them: below two CPUs JDK 25 selects SerialGC and fewer compiler threads, and
  Netty and the fork-join pool size themselves from the CPU count.
- A limited cache has its own identity, so a cache trained without a limit is never reused under one.
- `taskset` models CPU affinity, not CFS quota throttling. `docker --cpus` and Kubernetes limits set `cpu.max`,
  where the JVM sees `ceil(quota)` CPUs but its threads can burst across every core and are then throttled once
  per period. If one-CPU margins look odd, cross-check once with `docker run --cpus=1`.
- Both reports record the limit, the machine, the child and harness CPU lists, the child CPUs' SMT siblings, the
  CPU model, the probe's count and flags, the page-cache mode, the kernel and the storage under the work
  directory.

A pull request that adds threads to the application startup path links a paired `-Pbenchmarks.cpus=1` result.
Packaging-time parallelism does not run in the application and no harness mode covers it: check it at one CPU by
hand with `taskset -c 0`.

## JMH

The JMH benchmarks cover the class loader's hot path: index lookup hit and miss, package-table lookup, class
loading, defining a class from a mapped buffer versus a byte array, resource lookup and streaming, duplicate
resource enumeration and service discovery. Named synthetic shapes vary JAR and entry count, class size and
locality, resource size, manifest packages, duplicate names and multi-release entries; the resource benchmarks
take them through the `workload` parameter (`representative` is the default shape to quote). They isolate
mechanisms and are not startup results. In particular, allocation avoided by defining from a mapped buffer is not
elapsed time saved. Per-JAR operations, such as enumerating every copy of a resource or listing service
descriptors, behave like a multi-JAR class path rather than like a flat JAR, which returns one merged copy.

Class loading and resource lookup are compared with two JDK baselines built from the same synthetic inputs as
the Runner archives:

- `Shaded*` runs a `URLClassLoader` over one flattened JAR: the application first, then every dependency in
  class-path order, with `META-INF/services` descriptors merged, first-wins duplicates and every entry DEFLATED.
  That is the layout of the `shadow` startup row. `java -jar` runs a Shadow JAR on the JDK's built-in application
  class loader rather than a `URLClassLoader`, so this baseline is a proxy for it.
- `URL*` runs a `URLClassLoader` over the application classes plus every dependency JAR: the layout of the
  `thin-jar` and `exploded-cp` rows.

## Packaging

Packaging time is not a goal of Micronaut Runner: the build does extra work on purpose, so that every start does
less. `packagingProfile` exists to catch regressions in Runner's own packaging cost, comparing Runner with its previous
self, and its results are never quoted as a feature. The guide's one statement about packaging time is a single
sentence without figures, in the Introduction, that a Runner JAR takes less time to build than a Shadow JAR of the
same application with the default settings (see [Claims in the user guide](#claims-in-the-user-guide)). Add no
figures to it, and no other packaging claim.

`packagingProfile` builds STORED and PRESERVE Runner archives of synthetic builder profiles in fresh JVMs, for
deterministic first-build, unchanged-rebuild, application-edit and dependency-edit scenarios. Elapsed time and
allocation, and process RSS and peak heap, come from separate invocations. Logical input and output bytes are
reported apart from time and memory and are not kernel I/O counters.

## Attributing a difference

A difference between two rows can be attributed to one mechanism (stored dependencies, merged discovery,
mapped-buffer definition, a build-time transform) only through a matched ablation that changes only that
mechanism, such as the control rows. Hash-collision, resource-resolution and merged-metadata fixtures validate
behaviour; they cannot prove a performance cause. The control rows' effects overlap, so they are never added up.
A number quoted in a pull request or in this file names its run, its JDK build, its machine, the sample's Micronaut
and Netty versions, its page-cache and CPU conditions, and which of readiness, the startup line or the framework's own
figure it is.

## Claims in the user guide

The guide's Performance section makes four claims, rounded to a plain fraction and phrased as less or more time. Other
pages compare options in words only, such as "slightly slower" or "about the same", and the Introduction says once,
without figures, that with the default settings a Runner JAR takes less time to build than a Shadow JAR of the same
application. A claim stays in the guide only while every session that measured it agrees with its wording. Each claim
comes from these comparisons (the startup harness's paired rows, or a manual comparison run with the same paired,
interleaved method):

| Claim | Where | Comparison | Last measured |
|---|---|---|---|
| Without a cache, about a quarter less time than Shadow, and clearly less memory | Performance, Introduction, Choosing a Deployment, README | `runner-stored` − `shadow`: time and memory | JDK 25: −26…−28% time, RSS −19…−20%, private −40…−41%. JDK 27 (stripping on): −25…−27% time, RSS −32%, private −48…−49% |
| Nearly three times as large on disk, about a fifth smaller compressed | Performance, Introduction | `runner-stored` / `shadow` deployment size | 2.80× raw, 0.78× gzip |
| With caches on both sides, about the same time as Shadow or slightly less, somewhat less private memory, higher RSS | Performance, Choosing a Deployment | `runner-stored-aot` − `shadow-aot` | JDK 25: −4…−8% time (two of four n.s.), private −7…−8%, RSS +12…+13%. JDK 27 (stripping on): −1…−3% (n.s.), private −13…−14%, RSS +6…+7% |
| A JDK AOT cache makes the single JAR start much faster | Choosing a Deployment | `runner-stored-aot` − `runner-stored` | JDK 25: −39…−45%. JDK 27 (stripping on): −42…−43% |
| A startup profile takes nearly a further quarter off a start without a cache | Performance, Choosing a Deployment | `runner-stored-preload` − `runner-stored` | −23% (JDK 25) |
| With a cache, a startup profile adds little; it uses slightly more memory during startup | Startup Profile, Choosing a Deployment | `runner-stored-preload-aot` − `runner-stored-aot`; `runner-stored-preload` − `runner-stored`: private memory | −5…−8% (one of three n.s.); private +3…+4% (JDK 25) |
| Extracted layout: about the same with its cache, lower RSS; slower without one | Choosing a Deployment, Extracting to a Directory | `runner-extracted-aot` − `runner-stored-aot`, `runner-extracted` − `runner-stored` | −5…+6% time, RSS −13…−17%; uncached +9…+26% |
| Extracted `STORED` and `PRESERVE` layouts start the same with their cache; `STORED` starts faster without it | What a Cache Depends On | manual: `PRESERVE` layout − `STORED` layout, with and without their caches | +1% (n.s.); uncached +16% (one JDK 25 session, the `STORED` side with stripping) |
| Micronaut AOT: `-all-optimized.jar` fastest uncached, `-all.jar` ahead of the optimized Shadow JAR, about the same cached | Micronaut AOT, Choosing a Deployment | `runner-maot` − `runner-stored`, `runner-stored` − `shadow-maot`, `runner-maot-aot` − `runner-stored-aot` | −5…−7%; −18…−21% (JDK 25; −18…−19% on JDK 27 with stripping); −1…+1% (n.s. in all six sessions; paired from `results.json`, because the harness declares no such comparison) |
| `PRESERVE` slower than `STORED` but faster than Shadow; slightly larger than Shadow on disk, about the same gzipped | Compression Modes | `runner-preserve` − `runner-stored`, `runner-preserve` − `shadow` | +13…+17% (JDK 25; +15…+16% on JDK 27 with stripping); −14…−17%; 1.20× raw, 1.04× gzip |
| `HYBRID` starts like `STORED`, smaller on disk, larger compressed | Compression Modes | `runner-stored-hybrid` − `runner-stored` | −1…+3% (n.s.); 0.74× raw, 1.35× gzip (stripping on) |
| `POSITIONAL`: lower RSS, slightly slower, same private memory | Mapped or Positional Reads | `runner-stored-positional` − `runner-stored` | +1…+3% time; RSS −13…−16% |
| Opt-in local-variable stripping: somewhat smaller, slightly faster without a cache, no difference with one | Opt-in: Dependency Local-Variable Names | `runner-stored-stripdebug` − `runner-stored`, and their `-aot` twins | 0.88× raw; −2…−3% uncached; −1…0% cached (n.s.) |
| Dynamic CDS starts more slowly than the AOT cache | The JDK AOT Cache | manual: `-XX:ArchiveClassesAtExit` and `-XX:+AutoCreateSharedArchive` against the AOT cache | AOT −11…−12% |
| With the default settings, building a Runner JAR takes less time than building a Shadow JAR | Introduction | manual: `packagingComparison`, `runner-stored` − `shadow`, `--rerun` and edit-then-build; plus an up-to-date check | packaging task −59…−61%, whole build −30…−35%; no difference when up to date |

They were last measured on 2026-10-02 at commit `71e87dd`, on an Apple M4 Pro with 12 CPUs (macOS 26.6.2), with
Homebrew OpenJDK 25.0.4.1 (four startup batches) and 27 (two), the sample on micronaut-core 5.1.15. That commit
still stripped dependency local-variable tables by default. The JDK 25 figures for the default against Shadow come
from its `runner-stored-keepdebug` rows, which are byte for byte today's default; the JDK 27 figures marked
"stripping on" do not. The opt-in stripping row was measured again on 2026-10-08, after stripping moved to Micronaut
AOT's engine (#251): three sessions of 40 interleaved, paired rounds of the sample's own Runner JARs, with and without
`stripLocalVariables`, JDK 25.0.4.1. Comparisons within Runner (the startup profile, the extracted layout,
`-all-optimized.jar` against `-all.jar`, `HYBRID` and `POSITIONAL`) were measured with stripping on for both rows, so
they are not yet measured on today's default. The packaging claim comes from three manual sessions of the packaging
comparison, on JDK 25.0.4.1 with a warm Gradle daemon, with stripping on: today's default skips that step, so it does
less work than was measured. The claim names only Shadow, because Maven Shade was not measured. The advantage comes
from not compressing entries: against a Shadow JAR whose entries are stored uncompressed, the two take about the same
time.

The `packagingComparison` task has since been removed from this module. To refresh the packaging claim, run
`./gradlew :benchmarks:packagingComparison` from `2035468`, the base of #245, which still has the task and today's
stripping default; to measure a later Runner, restore the task from there. To refresh the startup claims, run on a
quiet machine:

```bash
./gradlew :benchmarks:startupBenchmark -Pbenchmarks.iterations=40 -Pbenchmarks.optionalRows=true
./gradlew :benchmarks:startupBenchmark -Pbenchmarks.iterations=40 -Pbenchmarks.optionalRows=true --init-script jdk27.gradle
./gradlew :benchmarks:startupBenchmark -Pbenchmarks.iterations=30 -Pbenchmarks.micronautCore=5.2.12
```

The second run measures the newest JDK through the init script of [Which JDK a run uses](#which-jdk-a-run-uses). The
third is a cross-check on a newer micronaut-core, where the precomputed service lookups do nothing; no claim rests on
it alone. The manual comparisons follow the same paired, interleaved method on the artifacts the harness builds. Update the guide's
wording only when a rounded claim changes, and update this table in the same pull request.
