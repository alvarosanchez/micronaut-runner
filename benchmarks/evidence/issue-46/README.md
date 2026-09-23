# Issue 46 resource-enumeration profile

This directory retains the bounded launcher microbenchmark used to validate the same-name-chain change. It measures `RunnerClassLoader.findResources` only; it is not evidence of a whole-application startup improvement.

## Provenance and conditions

- Source revision: `c8ed6ff4082b09b2f43bfbdea8018a1901e59dca`, with the issue-46 worktree changes applied.
- Baseline: the reported prefix-rescan loop was temporarily restored in `findResources` while keeping the identical benchmark and fixture code. The final candidate uses the current-record `Index.nextJar` cursor. The temporary baseline source was not committed.
- Context: prerequisite PR #96 had already introduced an equivalent linear helper while fixing slashless directory fallback; this change moves that cursor into `Index`, adds an explicit whole-enumeration bound, and supplies the missing scale/adversarial contracts and retained evidence.
- Machine: macOS 26.6.2 (25G83), arm64, 12 logical processors, 51,539,607,552 physical-memory bytes.
- JVM: Oracle GraalVM 25.0.3+9.1 LTS, Java HotSpot 64-Bit Server VM.
- OS page-cache state: uncontrolled. The fixture is prebuilt in trial setup, outside measurement.
- JMH: 2 independent forks, 3 × 1 s warm-up iterations, 5 × 1 s measurement iterations, 1 thread, `-Xms512m -Xmx512m`, GC profiler.

Commands:

```bash
java -jar baseline.jar \
  'ResourceEnumerationBenchmark.enumerateSameNameResource' \
  -f 2 -wi 3 -w 1s -i 5 -r 1s -prof gc -rf json -rff baseline.json
java -jar candidate.jar \
  'ResourceEnumerationBenchmark.enumerateSameNameResource' \
  -f 2 -wi 3 -w 1s -i 5 -r 1s -prof gc -rf json -rff candidate.json
```

## Results

Latency and allocation are reported separately. Scores are means across ten measurement samples; `±` is JMH's 99.9% confidence interval.

| Contributing JARs | Prefix-rescan baseline | Current-record candidate | Latency change |
|---:|---:|---:|---:|
| 1 | 0.948 ± 0.283 µs/op | 0.774 ± 0.014 µs/op | -18.4% |
| 30 | 23.744 ± 0.679 µs/op | 21.813 ± 2.861 µs/op | -8.1% |
| 300 | 330.933 ± 12.772 µs/op | 211.024 ± 4.721 µs/op | -36.2% |

| Contributing JARs | Prefix-rescan allocation | Current-record allocation | Allocation change |
|---:|---:|---:|---:|
| 1 | 2,120.007 B/op | 2,232.005 B/op | +5.3% |
| 30 | 44,328.797 B/op | 44,680.152 B/op | +0.8% |
| 300 | 402,866.304 B/op | 402,945.470 B/op | +0.02% |

The 300-JAR latency result demonstrates the bounded traversal benefit in this isolated operation. Allocation is effectively unchanged at scale and is dominated by materializing the result URLs/list; no allocation-reduction claim is made. Raw fork samples and profiler metrics are retained in `baseline.json` and `candidate.json`.
