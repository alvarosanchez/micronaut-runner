# Issue 47 package lookup evidence

This directory retains the local before/after measurements for the bounded, lazy per-JAR package-name lookup. They are evidence for this change, not cross-machine or whole-application performance claims.

## Provenance and method

- Source baseline: `c231ce0` (`origin/master` when the branch was created). The baseline JMH jar used the new benchmark fixtures with only `Index.java` restored to that revision; the candidate jar used the complete change.
- Machine/JVM: macOS 26.6.2, aarch64, 12 logical processors, Oracle JDK 25.0.3 HotSpot.
- OS page-cache state was uncontrolled.
- JMH used two forks, one 300 ms warm-up and three 300 ms measurement iterations with `-prof gc`, except the class-definition rows, which used one 500 ms warm-up and three 500 ms measurement iterations. These short runs separate lookup and allocation effects; confidence intervals remain in the JSON.
- `baseline.json` and `candidate.json` retain the raw JMH samples. Absolute JVM paths are normalized to `${java}`.
- Retained cache size was measured separately with `Instrumentation.getObjectSize`, traversing only the object graph rooted at `Index.packageLookups` with identity de-duplication. The measurement JVM used compressed references. This is retained Java-heap size, not total process RSS.
- The startup matrix is one measured sample after one discarded warm-up per variant. `startup-results.json` and `startup-summary.md` retain all attempts; the values are descriptive only.

Commands:

```bash
./gradlew :benchmarks:jmhJar
java -jar benchmarks/build/libs/benchmarks-1.0.0-SNAPSHOT-jmh.jar \
  'PackageLookupScalingBenchmark.(findMissing|findPresent|findSamePackage)' \
  -p packageSections=0,2,30,300,1000 \
  -f 2 -wi 1 -w 300ms -i 3 -r 300ms -prof gc -rf json
java -jar benchmarks/build/libs/benchmarks-1.0.0-SNAPSHOT-jmh.jar \
  'PackageLookupScalingBenchmark.initializeAndFind' \
  -p packageSections=0,2,30,300,1000 \
  -f 2 -wi 1 -w 300ms -i 3 -r 300ms -prof gc -rf json
java -jar benchmarks/build/libs/benchmarks-1.0.0-SNAPSHOT-jmh.jar \
  'PackageLookupBenchmark.*' -p workload=small,representative,wide \
  -f 2 -wi 1 -w 300ms -i 3 -r 300ms -prof gc -rf json
java -jar benchmarks/build/libs/benchmarks-1.0.0-SNAPSHOT-jmh.jar \
  'StoredRunnerClassLoaderBenchmark.(runnerClassLoader|runnerClassesInSamePackage)' \
  -f 2 -wi 1 -w 500ms -i 3 -r 500ms -prof gc -rf json
./gradlew :benchmarks:startupBenchmark \
  -Pbenchmarks.iterations=1 -Pbenchmarks.allowPartial=true
```

## Zero, small and large package tables

The steady-state rows cycle through every present name or a corresponding absent name after one lookup has initialized the candidate's fixed per-JAR table.

| Sections | Hit baseline | Hit candidate | Hit allocation baseline/candidate | Miss baseline | Miss candidate | Miss allocation baseline/candidate |
|---:|---:|---:|---:|---:|---:|---:|
| 0 | 3.056 ns | 3.038 ns | ~0 / ~0 B | 3.060 ns | 2.982 ns | ~0 / ~0 B |
| 2 | 18.806 ns | 5.433 ns | 176 / ~0 B | 22.268 ns | 4.239 ns | 240 / ~0 B |
| 30 | 193.415 ns | 5.752 ns | 1,915.7 / ~0 B | 335.622 ns | 4.158 ns | 3,712.0 / ~0 B |
| 300 | 1,791.393 ns | 6.166 ns | 19,127.3 / ~0 B | 3,433.719 ns | 3.902 ns | 38,000.1 / ~0 B |
| 1,000 | 6,215.175 ns | 7.655 ns | 63,859.4 / ~0 B | 12,603.231 ns | 5.649 ns | 127,600.3 / ~0 B |

The zero-record path remains allocation-free and never creates the package cache. Repeated lookup of the first package was 11.5–11.9 ns and 112 B/op on the baseline versus about 4.6 ns and allocation-free for the stable candidate rows. One short 30-section candidate fork was an outlier, so no tighter latency claim is made from that row.

## Initialization cost and retained memory

Initialization occurs only for a JAR whose non-empty package table is queried. The first-lookup benchmark opens an `Index` outside timing and measures the lookup that builds the table. Because its present target is the first package, the baseline stops after one decoded record; the candidate pays the full bounded-table construction cost once.

| Sections | First lookup baseline | First lookup candidate | Candidate allocation | Retained candidate cache |
|---:|---:|---:|---:|---:|
| 0 | 8.393 ns | 6.640 ns | 330 B/op | 0 B |
| 2 | 18.544 ns | 68.793 ns | 748 B/op | 296 B |
| 30 | 18.368 ns | 673.550 ns | 5,373 B/op | 3,184 B |
| 300 | 19.240 ns | 7.190 µs | 53,839 B/op | 34,488 B |
| 1,000 | 18.590 ns | 25.093 µs | 168,441 B/op | 104,280 B |

This makes the trade-off explicit: startup work and retained memory are linear in the package records of each JAR that is actually queried, bounded by immutable archive metadata, and zero for untouched or empty JAR tables. Arbitrary misses are not retained.

## Issue 51 representative package workload

The `representative` synthetic workload has 48 JARs, 160 classes/JAR and 12 manifest package sections/JAR. This reruns the manifest-heavy package-lookup workload from issue 51, rather than inferring a whole-application gain.

| Lookup | Baseline latency | Candidate latency | Baseline allocation | Candidate allocation |
|---|---:|---:|---:|---:|
| Present | 92.523 ns/op | 8.500 ns/op | 800.0 B/op | ~0 B/op |
| Missing | 148.546 ns/op | 20.948 ns/op | 1,558.9 B/op | 70.4 B/op |
| Zero-section control | 1.409 ns/op | 1.621 ns/op | ~0 B/op | ~0 B/op |

The candidate removed repeated package-name decoding from hits and from the table scan on misses. The remaining miss allocation comes from the benchmark's varying absent query names, not an arbitrary-name cache.

## Class definition and application startup

The fresh-loader class-definition benchmark includes archive open, index validation, loader creation and class definition. Its confidence intervals overlap heavily:

| Work | Baseline latency/allocation | Candidate latency/allocation |
|---|---:|---:|
| 200 classes spread through the archive | 2.896 ms / 600.4 KiB | 2.987 ms / 513.5 KiB |
| 12 classes in one package | 0.346 ms / 59.1 KiB | 0.410 ms / 60.3 KiB |

These exploratory end-to-end rows do not establish a latency improvement. They show that the lookup improvement is small relative to class definition and that initializing a table can outweigh scan savings when only a few classes are defined.

The separate current whole-application run measured `runner-stored` readiness at 463.3 ms (one sample), versus 505.6 ms for `runner-preserve`, 546.8 ms for Shadow and 588.8 ms for exploded classpath in that randomized run. The application currently has 49 dependency JARs and, as issue 47 records, no package-override records; therefore this is a compatibility/startup-overhead check, not evidence that package caching improved application startup. No allocation result is inferred from readiness; allocation is reported only by the JMH rows above.
