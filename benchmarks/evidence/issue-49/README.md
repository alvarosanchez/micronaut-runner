# Issue 49 bounded-streaming profile

This directory retains the local before/after evidence for bounded application-JAR spooling, streamed dependency repacking, and streamed full verification. The samples are descriptive, not a published speedup claim.

## Provenance and conditions

- Baseline: `c231ce0` (`origin/master`, clean).
- After: the issue-49 worktree at the same revision with the implementation dirty.
- Machine: macOS 26.6.2, aarch64; Oracle JDK 25.0.3+9-LTS-jvmci-b01.
- OS page-cache state: **uncontrolled**.
- Directory input uses the existing `representative` issue-51 workload and two iterations. Elapsed/allocation and peak-heap diagnostics are independent fresh JVM invocations.
- JAR input packages a deterministic DEFLATED JAR made from the same representative application tree, with the same 48 dependencies, in three fresh JVM invocations.
- Input/output bytes are logical file sizes, not kernel I/O counters. The after output is 728 bytes larger because this change also updates the bundled launcher classes, so cross-revision output hashes are not expected to match. Output hashes were stable within every phase/mode.

Directory command:

```bash
./gradlew :benchmarks:packagingProfile \
  -Pbenchmarks.workloads=representative \
  -Pbenchmarks.packagingIterations=2 --rerun-tasks
```

## First-build medians

Values are decimal MB. `Logical input` is the application directory or application JAR plus dependency JAR sizes; `logical output` is the runner JAR size.

| Application input | Dependency mode | Elapsed ms before | Elapsed ms after | Allocated MB before | Allocated MB after | Peak heap MB before | Peak heap MB after | Logical input bytes after | Logical output bytes after |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| directory | STORED | 358.967 | 293.025 | 89.918 | 75.012 | 59.826 | 60.219 | 4,995,658 | 25,210,328 |
| directory | PRESERVE | 336.756 | 216.231 | 65.666 | 81.782 | 60.219 | 59.280 | 4,995,658 | 6,694,483 |
| JAR | STORED | 408.326 | 338.760 | 90.711 | 75.030 | 59.826 | 60.219 | 4,996,475 | 25,210,808 |
| JAR | PRESERVE | 285.598 | 225.078 | 64.794 | 81.833 | 60.219 | 59.171 | 4,996,475 | 6,694,981 |

The representative fixture is too small for payload size to dominate its approximately 59–60 MB fresh-JVM heap floor. The acceptance bound is therefore established separately by the forked test: an 80 MiB expanded application JAR, including one 48 MiB resource and 64 additional entries, packages and completes full verification under `-Xmx32m`. The same test fails on the baseline with `OutOfMemoryError` while `ZipReader.read` materialises the large entry.

STORED allocation fell by about 17% for both application forms. Measured PRESERVE allocation rose by 16–17 MB; streamed verification uses bounded per-entry stream wrappers, while peak heap fell slightly and remains independent of payload size. The constrained-heap test, corruption tests, and profile should be read together: bounded live memory is the design goal, not an allocation-free stream abstraction.

## Retained files

- `directory-before.json` / `directory-after.json`: raw existing-harness reports.
- `jar-before.json` / `jar-after.json`: raw fresh-JVM application-JAR samples.
