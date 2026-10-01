# Micronaut Runner benchmarks

This module holds the three benchmark suites behind the guide's
[Benchmarks](https://micronaut-projects.github.io/micronaut-runner/latest/guide/#benchmarks) page: JMH
micro-benchmarks of the class loader, packaging-time measurements, and an end-to-end startup harness. The guide
publishes results and says what they mean; this file says how the harness produces them, and every property it
takes.

Nothing a run produces is committed. Reports land in `benchmarks/build/reports/{startup,packaging,packaging-comparison,jmh}`,
and CI uploads them as workflow artifacts, which expire after 30 days.

## Running

```bash
./gradlew :benchmarks:jmh
./gradlew :benchmarks:packagingProfile
./gradlew :benchmarks:packagingComparison
./gradlew :benchmarks:startupBenchmark -Pbenchmarks.iterations=20
./gradlew :benchmarks:startupBenchmark -Pbenchmarks.iterations=20 -Pbenchmarks.optionalRows=true
./gradlew :benchmarks:startupBenchmark -Pbenchmarks.iterations=1 -Pbenchmarks.diagnostics=true
./gradlew :benchmarks:startupBenchmark -Pbenchmarks.iterations=20 -Pbenchmarks.cpus=1 -Pbenchmarks.variants=shadow,runner-stored
```

`benchmarks.yml` runs the benchmark-tool tests, JMH, `packagingProfile`, `startupBenchmark` and
`packagingComparison` weekly, on demand, and on pull requests labelled `relates-to: benchmarks`, never as a pull
request gate: shared runners are too noisy to fail a build on. A dispatch can set `iterations`, `cpus`,
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
| `-Pbenchmarks.sample=dir` | `startupBenchmark` | `benchmark-large` | Another sample under `test-suite/samples`. `packagingComparison` ignores it. |
| `-Pbenchmarks.workloads=a,b` | `packagingProfile` | `small,representative,wide` | Synthetic packaging shapes; `no-manifest` is the fourth. |
| `-Pbenchmarks.packagingIterations=N` | `packagingProfile` | `3` | Independent packaging workers per scenario. |
| `-Pbenchmarks.packagingComparisonIterations=N` | `packagingComparison` | `10` | Measured rounds after 3 warm-up rounds; 10 is the fewest that give a 95% interval. |
| `-Pjmh.includes=regex` | `jmh` | everything | Only the matching JMH benchmarks. |

The harness's own options (`--warmup`, `--seed`, `--readiness`, `--timeout`) keep their defaults from Gradle: 3
warm-ups, seed `20260921`, readiness path `/hello` and a 120 s start timeout.

Where things land:

- `benchmarks/build/reports/startup/` – `results.json`, `summary.md` and, with diagnostics, `diagnostics/`.
- `benchmarks/build/reports/packaging/` – `packaging-results.json` and `packaging-summary.md`.
- `benchmarks/build/reports/packaging-comparison/` – `results.json` and `summary.md`.
- `benchmarks/build/reports/jmh/results.json`.
- `benchmarks/build/tmp/startupBenchmark` – the startup task's built rows and trained caches.
- `benchmarks/build/tmp/packagingComparison` – the packaging comparison's copy of the sample.

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
the strip and desugar counts, the precompiled Logback configurator, the static service table, the prefetch. The
description of every Runner row also ends with `static services: N slots (core V)` when its JAR carries a static
service table, and `dynamic service scan` when it does not.

`runner-stored-preload` records a startup profile from `runner-stored` in every run, with the recorder that
`recordStartupProfile` uses, and packages it. No other row preloads: preloading by convention needs a committed
`src/main/micronaut-runner/startup-classes.txt`, which the samples do not commit, and the harness calls the
builder, not the plugin convention. The recording launch runs on every CPU, even under `-Pbenchmarks.cpus`; the
class list barely depends on the CPU count.

In a row name, `maot` is Micronaut AOT and the `-aot` suffix is the JDK AOT cache. The declared pairs group like this:

- **Defaults:** `runner-stored` − `shadow`, the headline; it also includes the cost of inflating Shadow's DEFLATED
  entries.
- **Compression-matched:** `runner-stored` − `shadow-stored` (neither side inflates class bytes) and
  `runner-preserve` − `shadow` (both do) measure what Runner's layout itself buys; `shadow-stored` − `shadow` is the
  compression share alone.
- **Caches:** `runner-stored-aot`, `runner-extracted-aot` and `shadow-aot` against each other. Each cached row shares
  its uncached twin's exact artifact bytes.
- **Controls:** `runner-stored` − control and `runner-stored-aot` − its `-aot` twin, for each default the control turns
  off (`-joran`, `-keepdebug`, `-dynamic-services`, `-lambdas`). `runner-extracted-aot` − `runner-extracted-lambdas-aot`
  runs on the JDK's own loader, which archives lambdas itself, so it checks that desugaring costs the extracted layout
  nothing.
- **Opt-in candidates:** `-preload`, `-prefetch` and `-positional` against `runner-stored` (and their `-aot` twins
  against `runner-stored-aot`); `runner-stored` − `runner-stored-reflection` for the entry stub, whose interval still
  spans zero at 20 iterations, so run it with 40.
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
[Class Data Sharing and the AOT Cache](https://micronaut-projects.github.io/micronaut-runner/latest/guide/#cdsAndAot)
page; the harness does not use the plugins' training tasks.

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
take them through the `workload` parameter (`representative` is the shape the guide publishes). They isolate
mechanisms and are not startup results. In particular, allocation avoided by defining from a mapped buffer is not
elapsed time saved.

Class loading and resource lookup are compared with two JDK baselines built from the same synthetic inputs as
the Runner archives:

- `Shaded*` runs a `URLClassLoader` over one flattened JAR: the application first, then every dependency in
  class-path order, with `META-INF/services` descriptors merged, first-wins duplicates and every entry DEFLATED.
  That is the layout of the `shadow` startup row. `java -jar` runs a Shadow JAR on the JDK's built-in application
  class loader rather than a `URLClassLoader`, so this baseline is a proxy for it.
- `URL*` runs a `URLClassLoader` over the application classes plus every dependency JAR: the layout of the
  `thin-jar` and `exploded-cp` rows.

## Packaging

`packagingProfile` builds STORED and PRESERVE Runner archives of synthetic builder profiles in fresh JVMs, for
deterministic first-build, unchanged-rebuild, application-edit and dependency-edit scenarios. Elapsed time and
allocation, and process RSS and peak heap, come from separate invocations. Logical input and output bytes are
reported apart from time and memory and are not kernel I/O counters.

`packagingComparison` packages a real application, a fresh copy of `test-suite/samples/benchmark-large`, in a warm
Gradle daemon of its own, which is where developers and CI pay for packaging. It compares `micronautRunnerJar`
with STORED (`runner-stored`) and PRESERVE (`runner-preserve`) compression against `shadowJar` (`shadow`) and the
sample's STORED control `shadowJarStored` (`shadow-stored`).

- `rerun` runs `<task> --rerun`, which re-executes only the packaging task. `micronautRunnerJar` keeps no
  packaging state between executions, so this is the cold packaging path; there is no separate cold scenario.
- `edit` makes a bytecode-changing edit to `Application.java` and runs `<task>`, so compilation and the packaging
  task's other dependencies run as well.
- Each invocation records the packaging task's action time and the wall time of the whole `gradlew` process. The
  comparisons are `runner-stored`/`shadow` (the defaults) and the compression-matched `runner-stored`/
  `shadow-stored` (neither archive deflated) and `runner-preserve`/`shadow` (both deflated), each the median of
  per-round differences.
- `runner-stored`'s time includes the default build-time steps: the class transforms (lambda desugaring and
  local-variable stripping), Logback precompilation and the static service table. `runner-preserve` nests the
  dependencies byte for byte and runs no class transform.
- 3 warm-up rounds, then 10 measured rounds by default. Each round runs one untimed `classes` build, then every
  row in the `rerun` scenario and then in the `edit` scenario, each scenario in a fresh order shuffled from a seed
  the report records.
- Its nested builds run with `--no-build-cache --no-configuration-cache` in a daemon that its own JVM options keep
  apart from every other daemon and that stops itself 60 seconds after the task ends. The harness never runs
  `--stop`.

Maven Shade is not compared. `test-suite/samples/maven-basic` configures no `maven-shade-plugin`, and in that
small sample Maven's own startup would dominate the timings. The Maven plugin packages with the same
`runner-build` code path as the Gradle task, which the Runner rows here and `packagingProfile` already cover.

## Attributing a difference

A difference between two rows can be attributed to one mechanism (stored dependencies, merged discovery,
mapped-buffer definition, a build-time transform) only through a matched ablation that changes only that
mechanism, such as the control rows. Hash-collision, resource-resolution and merged-metadata fixtures validate
behaviour; they cannot prove a performance cause. The control rows' effects overlap, so they are never added up.
A published number names its run, its JDK build, its machine, the sample's Micronaut and Netty versions, its
page-cache and CPU conditions, and which of readiness, the startup line or the framework's own figure it is.
