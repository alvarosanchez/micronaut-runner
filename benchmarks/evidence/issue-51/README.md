# Issue 51 representative profile

This directory retains the bounded local profiling wave used to choose the next optimization work. It is evidence for prioritization, not a published cross-machine performance claim.

## Provenance and conditions

- Source revision: `d576974bedd0cbb4114a2dd17ee40af4de374822`, with the issue-51 worktree dirty as recorded in the generated reports.
- Machine: macOS 26.6.2, aarch64, 12 logical processors, 51,539,607,552 physical-memory bytes.
- JVM: Oracle JDK 25.0.3+9-LTS-jvmci-b01, Java HotSpot 64-Bit Server VM.
- OS page-cache state: **uncontrolled**. No result is labelled cold-filesystem-cache or warm-filesystem-cache.
- JMH configuration: two independent forks, one 300 ms warm-up iteration and three 300 ms measurement iterations, one thread, `-prof gc`. These deliberately short exploratory runs rank candidates; longer confirmation is required before publishing a speedup.
- Startup configuration: one warm-up and one measured sample per variant. The report labels every startup result descriptive-only and retains the randomized attempt order and all raw attempts.
- Packaging configuration: two independent timing workers and two separate memory-diagnostic workers per workload/mode/scenario. Diagnostic elapsed time is excluded. Input/output byte counts are logical file sizes, not kernel I/O counters.
- Environment values and private absolute paths are omitted or redacted. JMH `jvm` is normalized to `${java}`.

Commands:

```bash
./gradlew :benchmarks:startupBenchmark \
  -Pbenchmarks.iterations=1 -Pbenchmarks.allowPartial=true
./gradlew :benchmarks:packagingProfile \
  -Pbenchmarks.workloads=no-manifest,representative,wide \
  -Pbenchmarks.packagingIterations=2
java -jar benchmarks/build/libs/benchmarks-1.0.0-SNAPSHOT-jmh.jar \
  'PackageLookupBenchmark.*' -p workload=small,representative,wide \
  -f 2 -wi 1 -w 300ms -i 3 -r 300ms -prof gc -rf json
java -jar benchmarks/build/libs/benchmarks-1.0.0-SNAPSHOT-jmh.jar \
  'StoredRepresentativeResourceBenchmark.(duplicateResources|serviceDiscovery|resourceStream)' \
  -p workload=no-manifest,small,representative,wide \
  -f 2 -wi 1 -w 300ms -i 3 -r 300ms -prof gc -rf json
java -jar benchmarks/build/libs/benchmarks-1.0.0-SNAPSHOT-jmh.jar \
  '(Stored|Preserve|URL)RepresentativeResourceBenchmark.(duplicateResources|resourceStream|serviceDiscovery)' \
  -p workload=representative \
  -f 2 -wi 1 -w 300ms -i 3 -r 300ms -prof gc -rf json
```

## Workloads

Synthetic archives vary JAR count, entry count, class payload, class locality, stream-resource size, package-manifest sections and duplicate chains:

| Name | JARs | Class entries/JAR | Class payload | Stream resource/JAR | Manifest packages/JAR |
|---|---:|---:|---:|---:|---:|
| `no-manifest` | 8 | 48 | 256 B | 64 KiB | 0 |
| `small` | 16 | 80 | 512 B | 64 KiB | 2 |
| `representative` | 48 | 160 | 2 KiB | 1 MiB | 12 |
| `wide` | 300 | 24 | 768 B | 16 KiB | 4 |

Every shape has deterministic same-JAR and spread class locality, stream resources, service descriptors, duplicate names and multi-release entries. The real `benchmark-large` Micronaut application adds HTTP, management, validation, serialization, cache and YAML dependencies, 51 runtime dependencies, 48 explicit singleton beans, generated bean metadata and a 1 MiB resource. Its startup matrix compares exploded/thin, Shadow, STORED, PRESERVE and extracted layouts, including equivalent verified trained-cache pairs from issues 44 and 45.

Packaging scenarios are first build, unchanged rebuild, application edit and dependency edit. STORED and PRESERVE use identical ordered inputs. Timing/allocation and RSS/peak-heap are collected in separate fresh JVMs.

## Observations

### Startup and deployment

The single measured sample is descriptive only. Readiness was 525.300 ms for Shadow, 459.044 ms for STORED, 483.434 ms for PRESERVE and 496.106 ms for extracted. Verified trained modes were 329.033 ms for Shadow AOT, 311.624 ms for STORED CDS and 330.756 ms for extracted AOT. Deployment sizes were 14.774 MB, 37.599 MB, 17.633 MB and 34.759 MB respectively; trained caches added 47.055 MB, 31.506 MB and 47.186 MB. These samples confirm that startup latency, deployment bytes and cache bytes must remain separate; one sample does not establish ordering.

### Resource enumeration and streaming

For STORED, service discovery fell from 174,794 ops/s at 8 JARs to 30,325 ops/s at 48 JARs and 4,973 ops/s at 300 JARs. Allocation rose from 11.7 KiB/op to 66.6 KiB/op and 408.1 KiB/op. Duplicate-resource enumeration showed the same shape. At 48 JARs URLClassLoader service discovery was 110,216 ops/s with 22.5 KiB/op versus about 30,700 ops/s and 66 KiB/op for STORED/PRESERVE.

Stream size, not JAR count, dominates the streaming row: the 1 MiB representative resource was 66,350 ops/s STORED, 5,657 ops/s PRESERVE and 5,406 ops/s URLClassLoader. The result is a layout-specific baseline, not proof that one copy primitive is responsible.

### Package lookup

A package hit cost 21.395 ns and 176 B/op in `small`, 95.338 ns and 800 B/op in `representative`, and 34.921 ns and 304 B/op in `wide`; the result follows manifest sections in the selected JAR rather than total JAR count. Misses were 28.727/150.276/53.187 ns and 308/1,559/567 B/op. A JAR with no package sections remained about 1.4 ns and allocation-free. This is direct evidence that repeated package-table scanning and decoding is work on class definition, while also providing the required empty-table negative control.

### Packaging, allocation and process memory

Median first-build values:

| Workload | Mode | Elapsed | Allocated | Peak heap | Peak RSS | Input | Output |
|---|---|---:|---:|---:|---:|---:|---:|
| no-manifest | STORED | 81.905 ms | 14.681 MB | 18.582 MB | 174.014 MB | 0.239 MB | 0.745 MB |
| no-manifest | PRESERVE | 78.227 ms | 14.456 MB | 19.106 MB | 173.974 MB | 0.239 MB | 0.502 MB |
| representative | STORED | 337.463 ms | 118.000 MB | 72.045 MB | 244.474 MB | 4.996 MB | 25.209 MB |
| representative | PRESERVE | 250.630 ms | 95.765 MB | 60.438 MB | 222.298 MB | 4.996 MB | 6.694 MB |
| wide | STORED | 521.197 ms | 288.950 MB | 91.462 MB | 269.263 MB | 5.509 MB | 15.476 MB |
| wide | PRESERVE | 503.188 ms | 290.067 MB | 91.215 MB | 267.108 MB | 5.509 MB | 8.153 MB |

The unchanged and application-edit rows repeat essentially all work. For representative STORED, medians were 332.901 ms/117.667 MB unchanged and 330.088 ms/117.748 MB after an application edit, versus 337.463 ms/118.000 MB first build. For wide STORED they were 522.654 ms/289.026 MB unchanged and 529.935 ms/288.999 MB after an application edit, versus 521.197 ms/288.950 MB first build.

## Decisions for issues 46–50

1. **#46 — implement a bounded same-name-chain index/cursor change.** Enumeration throughput degrades about 35x from 8 to 300 JARs while allocation grows about 35x. Preserve dependency order, duplicates and `getResources` semantics; use these negative/duplicate/service controls for the before/after PR. Do not broaden that PR into resource-content caching.
2. **#47 — implement a bounded decoded package-name lookup.** Present and missing lookup costs track package-section count, and the empty-table control is allocation-free. Cache or hash decoded package names once when the index opens, preserving first matching section and manifest attributes. Confirm class-definition end-to-end impact separately.
3. **#48 — implement payload-free dry layout plus one reusable per-writer copy buffer.** Packaging allocates 96–118 MB for the 48-JAR shape and about 289 MB for the 300-JAR shape. This supports removing known avoidable dry-layout payload and buffer churn, but not an elapsed-time claim; the follow-up must isolate each change with allocation and packaging-I/O profiles.
4. **#49 — defer the broader streaming/checksum redesign until #48 is measured.** Peak heap/RSS is bounded in these fixtures (about 91/270 MB at 300 JARs) but high relative to logical input, while this wave does not isolate repeated checksum reads from index construction or compression. Reprofile after #48, then implement only the component still responsible. Do not infer checksum benefit from aggregate allocation.
5. **#50 — implement a dependency-bundle cache keyed by ordered dependency identities and mode.** Unchanged and application-only rebuilds repeat first-build elapsed time and allocation, so dependency repacking is reusable work. The follow-up must prove first build, unchanged, app edit, dependency edit, mode change, relocation and cache-restored behavior with byte-identical outputs before enabling it by default.

## Retained files

- `startup-results.json` / `startup-summary.md`: complete startup variants, raw attempts, artifact identities, cache identity/lifecycle and redacted provenance.
- `packaging-results.json` / `packaging-summary.md`: every independent timing and memory diagnostic attempt.
- `package-lookup.json`: JMH package-table results with raw fork samples and allocation metrics.
- `resource-scaling.json`: STORED scaling data for enumeration and streaming.
- `resource-formats.json`: matched STORED/PRESERVE/URL results for the representative shape.
