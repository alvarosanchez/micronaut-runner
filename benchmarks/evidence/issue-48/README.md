# Issue 48 ZIP layout profile

This directory retains the bounded local before/after packaging profile for the payload-free dry ZIP layout and per-writer copy-buffer reuse. It is descriptive evidence from one sample per case, not a published speedup claim.

## Provenance and conditions

- Baseline: `c8ed6ff4082b09b2f43bfbdea8018a1901e59dca` (clean).
- After: the issue-48 worktree at the same base revision with the implementation dirty, as recorded in `after.json`.
- Machine: macOS 26.6.2, aarch64.
- JVM: Oracle JDK 25.0.3+9-LTS-jvmci-b01.
- OS page-cache state: **uncontrolled**.
- Each row uses one fresh elapsed/allocation worker and a separate fresh peak-heap/RSS diagnostic worker. Diagnostic elapsed time is excluded.
- Input/output byte counts are logical file sizes, not kernel I/O counters.

Command:

```bash
./gradlew :benchmarks:packagingProfile \
  -Pbenchmarks.workloads=no-manifest,representative,wide \
  -Pbenchmarks.packagingIterations=1
```

## Workloads

The selected issue-51 profiles vary dependency/entry count and payload bytes:

| Workload | JARs | Class entries/JAR | Class payload | Stream resource/JAR |
|---|---:|---:|---:|---:|
| `no-manifest` | 8 | 48 | 256 B | 64 KiB |
| `representative` | 48 | 160 | 2 KiB | 1 MiB |
| `wide` | 300 | 24 | 768 B | 16 KiB |

## First-build samples

Elapsed, allocation, and peak heap are reported separately. Values are decimal MB. With one sample and uncontrolled page cache, the elapsed values do not establish a speedup or regression.

| Workload | Mode | Elapsed ms before | Elapsed ms after | Allocated MB before | Allocated MB after | Peak heap MB before | Peak heap MB after |
|---|---|---:|---:|---:|---:|---:|---:|
| no-manifest | STORED | 146.821 | 162.377 | 14.681 | 8.199 | 19.106 | 12.815 |
| no-manifest | PRESERVE | 74.246 | 86.818 | 14.456 | 7.973 | 19.106 | 12.815 |
| representative | STORED | 318.499 | 331.216 | 117.981 | 93.991 | 72.214 | 59.935 |
| representative | PRESERVE | 227.706 | 222.461 | 96.307 | 69.404 | 60.438 | 60.219 |
| wide | STORED | 543.085 | 501.547 | 288.769 | 130.599 | 91.462 | 73.348 |
| wide | PRESERVE | 500.057 | 489.279 | 289.769 | 132.400 | 91.491 | 74.016 |

All 24 matched workload/mode/scenario outputs kept identical byte lengths and SHA-256 identities before and after. The raw reports also retain unchanged-rebuild, application-edit, dependency-edit, allocation, peak heap, peak RSS, input bytes, output bytes, and output hashes.

## Retained files

- `before.json`: clean-baseline raw report.
- `after.json`: issue-48 raw report.
