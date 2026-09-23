# Startup benchmark

**COMPLETE required comparison** — every required variant completed every requested measured run.

Time from process spawn to the first successful HTTP response, for the same Micronaut application across the required packaging and entry-path matrix.

- **Sample**: `sample` (relocatable identifier; source revision is in `results.json`)
- **Machine**: Mac OS X 26.6.2 · aarch64 · 12 CPUs
- **JDK**: 25.0.3 (Oracle Corporation)
- **Run**: 1 measured iterations per variant after 1 discarded warm-up iterations; the variants are **interleaved in a random order within each iteration** (seed 20260921)
- **Completeness policy**: `partial`
- **JVM process**: fresh for every sample
- **OS page cache**: uncontrolled; discarded warm-ups do not establish a controlled warm-cache or cold-filesystem-cache state
- **Application cache**: per variant. `runner-stored-cds` uses verified custom-loader CDS; `shadow-aot` and `runner-extracted-aot` use verified built-in-loader JDK AOT caches. Their paired rows select no application archive. Default JDK class sharing may still be active
- **Readiness**: first HTTP 200 from `/hello`, polled every 2 ms with one persistent client, timed on a single monotonic clock that starts immediately before the process is spawned
- **Timing runs carry no `-Xlog` flags.** Class-load counts, when collected, come from separate runs and are labelled as such below
- **Generated**: 2026-09-23T04:44:47.551362Z

## Matrix status

Skipped means a scheduled cell was not attempted because the variant was unavailable; it is distinct from a failed process attempt. Warm-up failures are reported but do not make an otherwise complete measured matrix fail.

| Variant | Phase | Requested | Attempted | Successful | Failed | Skipped |
|---|---|---:|---:|---:|---:|---:|
| `exploded-cp` | warm-up | 1 | 1 | 1 | 0 | 0 |
| `exploded-cp` | measured | 1 | 1 | 1 | 0 | 0 |
| `thin-jar` | warm-up | 1 | 1 | 1 | 0 | 0 |
| `thin-jar` | measured | 1 | 1 | 1 | 0 | 0 |
| `shadow` | warm-up | 1 | 1 | 1 | 0 | 0 |
| `shadow` | measured | 1 | 1 | 1 | 0 | 0 |
| `shadow-aot` | warm-up | 1 | 1 | 1 | 0 | 0 |
| `shadow-aot` | measured | 1 | 1 | 1 | 0 | 0 |
| `runner-stored` | warm-up | 1 | 1 | 1 | 0 | 0 |
| `runner-stored` | measured | 1 | 1 | 1 | 0 | 0 |
| `runner-stored-cds` | warm-up | 1 | 1 | 1 | 0 | 0 |
| `runner-stored-cds` | measured | 1 | 1 | 1 | 0 | 0 |
| `runner-stored-reflection` | warm-up | 1 | 1 | 1 | 0 | 0 |
| `runner-stored-reflection` | measured | 1 | 1 | 1 | 0 | 0 |
| `runner-preserve` | warm-up | 1 | 1 | 1 | 0 | 0 |
| `runner-preserve` | measured | 1 | 1 | 1 | 0 | 0 |
| `runner-preserve-reflection` | warm-up | 1 | 1 | 1 | 0 | 0 |
| `runner-preserve-reflection` | measured | 1 | 1 | 1 | 0 | 0 |
| `runner-extracted` | warm-up | 1 | 1 | 1 | 0 | 0 |
| `runner-extracted` | measured | 1 | 1 | 1 | 0 | 0 |
| `runner-extracted-aot` | warm-up | 1 | 1 | 1 | 0 | 0 |
| `runner-extracted-aot` | measured | 1 | 1 | 1 | 0 | 0 |

## Successful measured runs only

Timing distributions condition on successful measured attempts; failed and skipped attempts are never replaced with zeroes or invented durations.

| Variant | Runs | Readiness, median | p90 | 95% CI of median | Min | Max | To startup line, median | Framework's own figure | Complete deployment |
|---|---:|---:|---:|---|---:|---:|---:|---:|---:|
| `exploded-cp` | 1 | **574.7 ms** | 574.7 ms | descriptive only (1/10 samples; at least 10 successful measured samples required) | 574.7 ms | 574.7 ms | not seen | not seen | 15.2 MiB |
| `thin-jar` | 1 | **545.9 ms** | 545.9 ms | descriptive only (1/10 samples; at least 10 successful measured samples required) | 545.9 ms | 545.9 ms | not seen | not seen | 14.1 MiB |
| `shadow` | 1 | **525.3 ms** | 525.3 ms | descriptive only (1/10 samples; at least 10 successful measured samples required) | 525.3 ms | 525.3 ms | not seen | not seen | 14.1 MiB |
| `shadow-aot` | 1 | **329.0 ms** | 329.0 ms | descriptive only (1/10 samples; at least 10 successful measured samples required) | 329.0 ms | 329.0 ms | not seen | not seen | 14.1 MiB |
| `runner-stored` | 1 | **459.0 ms** | 459.0 ms | descriptive only (1/10 samples; at least 10 successful measured samples required) | 459.0 ms | 459.0 ms | not seen | not seen | 35.9 MiB |
| `runner-stored-cds` | 1 | **311.6 ms** | 311.6 ms | descriptive only (1/10 samples; at least 10 successful measured samples required) | 311.6 ms | 311.6 ms | not seen | not seen | 35.9 MiB |
| `runner-stored-reflection` | 1 | **490.0 ms** | 490.0 ms | descriptive only (1/10 samples; at least 10 successful measured samples required) | 490.0 ms | 490.0 ms | not seen | not seen | 35.9 MiB |
| `runner-preserve` | 1 | **483.4 ms** | 483.4 ms | descriptive only (1/10 samples; at least 10 successful measured samples required) | 483.4 ms | 483.4 ms | not seen | not seen | 16.8 MiB |
| `runner-preserve-reflection` | 1 | **492.3 ms** | 492.3 ms | descriptive only (1/10 samples; at least 10 successful measured samples required) | 492.3 ms | 492.3 ms | not seen | not seen | 16.8 MiB |
| `runner-extracted` | 1 | **496.1 ms** | 496.1 ms | descriptive only (1/10 samples; at least 10 successful measured samples required) | 496.1 ms | 496.1 ms | not seen | not seen | 33.1 MiB |
| `runner-extracted-aot` | 1 | **330.8 ms** | 330.8 ms | descriptive only (1/10 samples; at least 10 successful measured samples required) | 330.8 ms | 330.8 ms | not seen | not seen | 33.1 MiB |

## Deployment sizes

Complete deployment is the sum of required regular-file lengths in logical bytes; allocated filesystem blocks and compressed transfer sizes are not reported. Repeated normalized paths are counted once and attributed to their first component. Symbolic links are not followed or counted; distinct hard-linked paths count separately.

| Variant | Component | Component bytes | Complete deployment |
|---|---|---:|---:|
| `exploded-cp` | application | 1322511 B | 15968365 B |
| `exploded-cp` | dependencies | 14645854 B | 15968365 B |
| `thin-jar` | application | 145794 B | 14791648 B |
| `thin-jar` | dependencies | 14645854 B | 14791648 B |
| `shadow` | archive | 14774163 B | 14774163 B |
| `shadow-aot` | archive | 14774163 B | 14774163 B |
| `runner-stored` | archive | 37599363 B | 37599363 B |
| `runner-stored-cds` | archive | 37599363 B | 37599363 B |
| `runner-stored-reflection` | archive | 37598292 B | 37598292 B |
| `runner-preserve` | archive | 17632954 B | 17632954 B |
| `runner-preserve-reflection` | archive | 17631883 B | 17631883 B |
| `runner-extracted` | extracted-layout | 34758846 B | 34758846 B |
| `runner-extracted-aot` | extracted-layout | 34758846 B | 34758846 B |

## Application-cache preparation

Cache bytes and build-time preparation are reported separately from complete deployment bytes and runtime readiness. A reused cache has no training cost in this invocation; preparation still includes its verification launch.

| Variant | Mode | Cache bytes | Training cost | Preparation cost | Reused |
|---|---|---:|---:|---:|---|
| `exploded-cp` | none | — | — | — | — |
| `thin-jar` | none | — | — | — | — |
| `shadow` | none | — | — | — | — |
| `shadow-aot` | aot | 47054848 B | 4939 ms | 8508 ms | false |
| `runner-stored` | none | — | — | — | — |
| `runner-stored-cds` | cds-strict | 31506432 B | 3976 ms | 7506 ms | false |
| `runner-stored-reflection` | none | — | — | — | — |
| `runner-preserve` | none | — | — | — | — |
| `runner-preserve-reflection` | none | — | — | — | — |
| `runner-extracted` | none | — | — | — | — |
| `runner-extracted-aot` | aot | 47185920 B | 5047 ms | 8601 ms | false |

## Paired readiness comparisons

The predeclared estimator is the median iteration-level readiness difference (**left − right**). Only attempts from the same measured iteration form a pair; incomplete iterations are excluded and listed rather than silently re-paired.

| Comparison | Pairs | Median difference | 95% paired interval | Excluded |
|---|---:|---:|---|---:|
| `exploded-cp` − `thin-jar` | 1 complete / 1 requested | 28.8 ms | descriptive only (1/10 pairs) | 0 |
| `exploded-cp` − `shadow` | 1 complete / 1 requested | 49.4 ms | descriptive only (1/10 pairs) | 0 |
| `exploded-cp` − `shadow-aot` | 1 complete / 1 requested | 245.7 ms | descriptive only (1/10 pairs) | 0 |
| `exploded-cp` − `runner-stored` | 1 complete / 1 requested | 115.7 ms | descriptive only (1/10 pairs) | 0 |
| `exploded-cp` − `runner-stored-cds` | 1 complete / 1 requested | 263.1 ms | descriptive only (1/10 pairs) | 0 |
| `exploded-cp` − `runner-stored-reflection` | 1 complete / 1 requested | 84.7 ms | descriptive only (1/10 pairs) | 0 |
| `exploded-cp` − `runner-preserve` | 1 complete / 1 requested | 91.3 ms | descriptive only (1/10 pairs) | 0 |
| `exploded-cp` − `runner-preserve-reflection` | 1 complete / 1 requested | 82.4 ms | descriptive only (1/10 pairs) | 0 |
| `exploded-cp` − `runner-extracted` | 1 complete / 1 requested | 78.6 ms | descriptive only (1/10 pairs) | 0 |
| `exploded-cp` − `runner-extracted-aot` | 1 complete / 1 requested | 244.0 ms | descriptive only (1/10 pairs) | 0 |
| `thin-jar` − `shadow` | 1 complete / 1 requested | 20.6 ms | descriptive only (1/10 pairs) | 0 |
| `thin-jar` − `shadow-aot` | 1 complete / 1 requested | 216.9 ms | descriptive only (1/10 pairs) | 0 |
| `thin-jar` − `runner-stored` | 1 complete / 1 requested | 86.9 ms | descriptive only (1/10 pairs) | 0 |
| `thin-jar` − `runner-stored-cds` | 1 complete / 1 requested | 234.3 ms | descriptive only (1/10 pairs) | 0 |
| `thin-jar` − `runner-stored-reflection` | 1 complete / 1 requested | 55.9 ms | descriptive only (1/10 pairs) | 0 |
| `thin-jar` − `runner-preserve` | 1 complete / 1 requested | 62.5 ms | descriptive only (1/10 pairs) | 0 |
| `thin-jar` − `runner-preserve-reflection` | 1 complete / 1 requested | 53.6 ms | descriptive only (1/10 pairs) | 0 |
| `thin-jar` − `runner-extracted` | 1 complete / 1 requested | 49.8 ms | descriptive only (1/10 pairs) | 0 |
| `thin-jar` − `runner-extracted-aot` | 1 complete / 1 requested | 215.2 ms | descriptive only (1/10 pairs) | 0 |
| `shadow` − `shadow-aot` | 1 complete / 1 requested | 196.3 ms | descriptive only (1/10 pairs) | 0 |
| `shadow` − `runner-stored` | 1 complete / 1 requested | 66.3 ms | descriptive only (1/10 pairs) | 0 |
| `shadow` − `runner-stored-cds` | 1 complete / 1 requested | 213.7 ms | descriptive only (1/10 pairs) | 0 |
| `shadow` − `runner-stored-reflection` | 1 complete / 1 requested | 35.3 ms | descriptive only (1/10 pairs) | 0 |
| `shadow` − `runner-preserve` | 1 complete / 1 requested | 41.9 ms | descriptive only (1/10 pairs) | 0 |
| `shadow` − `runner-preserve-reflection` | 1 complete / 1 requested | 33.0 ms | descriptive only (1/10 pairs) | 0 |
| `shadow` − `runner-extracted` | 1 complete / 1 requested | 29.2 ms | descriptive only (1/10 pairs) | 0 |
| `shadow` − `runner-extracted-aot` | 1 complete / 1 requested | 194.5 ms | descriptive only (1/10 pairs) | 0 |
| `shadow-aot` − `runner-stored` | 1 complete / 1 requested | -130.0 ms | descriptive only (1/10 pairs) | 0 |
| `shadow-aot` − `runner-stored-cds` | 1 complete / 1 requested | 17.4 ms | descriptive only (1/10 pairs) | 0 |
| `shadow-aot` − `runner-stored-reflection` | 1 complete / 1 requested | -161.0 ms | descriptive only (1/10 pairs) | 0 |
| `shadow-aot` − `runner-preserve` | 1 complete / 1 requested | -154.4 ms | descriptive only (1/10 pairs) | 0 |
| `shadow-aot` − `runner-preserve-reflection` | 1 complete / 1 requested | -163.3 ms | descriptive only (1/10 pairs) | 0 |
| `shadow-aot` − `runner-extracted` | 1 complete / 1 requested | -167.1 ms | descriptive only (1/10 pairs) | 0 |
| `shadow-aot` − `runner-extracted-aot` | 1 complete / 1 requested | -1.7 ms | descriptive only (1/10 pairs) | 0 |
| `runner-stored` − `runner-stored-cds` | 1 complete / 1 requested | 147.4 ms | descriptive only (1/10 pairs) | 0 |
| `runner-stored` − `runner-stored-reflection` | 1 complete / 1 requested | -31.0 ms | descriptive only (1/10 pairs) | 0 |
| `runner-stored` − `runner-preserve` | 1 complete / 1 requested | -24.4 ms | descriptive only (1/10 pairs) | 0 |
| `runner-stored` − `runner-preserve-reflection` | 1 complete / 1 requested | -33.3 ms | descriptive only (1/10 pairs) | 0 |
| `runner-stored` − `runner-extracted` | 1 complete / 1 requested | -37.1 ms | descriptive only (1/10 pairs) | 0 |
| `runner-stored` − `runner-extracted-aot` | 1 complete / 1 requested | 128.3 ms | descriptive only (1/10 pairs) | 0 |
| `runner-stored-cds` − `runner-stored-reflection` | 1 complete / 1 requested | -178.4 ms | descriptive only (1/10 pairs) | 0 |
| `runner-stored-cds` − `runner-preserve` | 1 complete / 1 requested | -171.8 ms | descriptive only (1/10 pairs) | 0 |
| `runner-stored-cds` − `runner-preserve-reflection` | 1 complete / 1 requested | -180.7 ms | descriptive only (1/10 pairs) | 0 |
| `runner-stored-cds` − `runner-extracted` | 1 complete / 1 requested | -184.5 ms | descriptive only (1/10 pairs) | 0 |
| `runner-stored-cds` − `runner-extracted-aot` | 1 complete / 1 requested | -19.1 ms | descriptive only (1/10 pairs) | 0 |
| `runner-stored-reflection` − `runner-preserve` | 1 complete / 1 requested | 6.6 ms | descriptive only (1/10 pairs) | 0 |
| `runner-stored-reflection` − `runner-preserve-reflection` | 1 complete / 1 requested | -2.3 ms | descriptive only (1/10 pairs) | 0 |
| `runner-stored-reflection` − `runner-extracted` | 1 complete / 1 requested | -6.1 ms | descriptive only (1/10 pairs) | 0 |
| `runner-stored-reflection` − `runner-extracted-aot` | 1 complete / 1 requested | 159.3 ms | descriptive only (1/10 pairs) | 0 |
| `runner-preserve` − `runner-preserve-reflection` | 1 complete / 1 requested | -8.9 ms | descriptive only (1/10 pairs) | 0 |
| `runner-preserve` − `runner-extracted` | 1 complete / 1 requested | -12.7 ms | descriptive only (1/10 pairs) | 0 |
| `runner-preserve` − `runner-extracted-aot` | 1 complete / 1 requested | 152.7 ms | descriptive only (1/10 pairs) | 0 |
| `runner-preserve-reflection` − `runner-extracted` | 1 complete / 1 requested | -3.8 ms | descriptive only (1/10 pairs) | 0 |
| `runner-preserve-reflection` − `runner-extracted-aot` | 1 complete / 1 requested | 161.5 ms | descriptive only (1/10 pairs) | 0 |
| `runner-extracted` − `runner-extracted-aot` | 1 complete / 1 requested | 165.3 ms | descriptive only (1/10 pairs) | 0 |


Intervals use 2000 bootstrap resamples of complete iteration pairs and are emitted only with at least 10 complete pairs. The threshold is a reporting policy, not a universal guarantee; an interval does not by itself establish a startup speedup.

All 11 required variants completed all 1 requested measured runs.

## What each variant is

| Variant | Requested entry | Effective entry | Packaging |
|---|---|---|---|
| `exploded-cp` | standard-loader | standard-loader | Class files and dependency jars on an explicit, ordered -cp |
| `thin-jar` | standard-loader | standard-loader | Application jar with a Class-Path manifest pointing at lib/ |
| `shadow` | standard-loader | standard-loader | Everything flattened into one jar by the Shadow plugin |
| `shadow-aot` | standard-loader | standard-loader | Everything flattened into one jar by the Shadow plugin; verified built-in-loader JDK AOT cache |
| `runner-stored` | stub | stub | Runner jar, nested dependencies re-packed uncompressed; plugin-default entry stub |
| `runner-stored-cds` | stub | stub | Runner jar, nested dependencies re-packed uncompressed; plugin-default entry stub; verified application-class CDS; strict archive loading |
| `runner-stored-reflection` | reflection | reflection | Runner jar, nested dependencies re-packed uncompressed; reflection ablation |
| `runner-preserve` | stub | stub | Runner jar, nested dependencies copied byte for byte; plugin-default entry stub |
| `runner-preserve-reflection` | reflection | reflection | Runner jar, nested dependencies copied byte for byte; reflection ablation |
| `runner-extracted` | standard-loader | standard-loader | Runner jar unpacked with -Dmicronaut.runner.mode=extract, run by the JDK's own loader |
| `runner-extracted-aot` | standard-loader | standard-loader | Runner jar unpacked with -Dmicronaut.runner.mode=extract, run by the JDK's own loader; verified built-in-loader JDK AOT cache |

## How to read this

- All variants are built from **one** compilation of the sample and **one** dependency resolution, so any difference is a difference in packaging.
- The median and the 90th percentile are reported instead of a mean and a standard deviation because process start times have a hard floor and a long right tail.
- Runs with fewer than 10 successful measured samples are **descriptive only**: medians and percentiles remain visible, but no confidence interval is emitted. This reporting threshold is not a universal guarantee of precision.
- When the threshold is met, the interval is a percentile bootstrap of the median (2000 resamples). Per-variant intervals are not a test of a difference and do not by themselves establish a startup speedup.
- **Readiness**, **to startup line** and **the framework's own figure** are three different quantities and the gaps between them are informative. Readiness includes serving the first request, which on a cold JVM is not free. The startup line is this process observing the child's console on the same clock, which is what a careful measurement by hand produces. The framework's figure is the application counting itself, starting well after the JVM did: the smallest of the three and the only one that is not an external observation.
- A variant marked **not measured** either could not be built or never answered. Matrix status and attempt records distinguish skipped cells from failed processes.
- Every raw sample, warm-up runs included, is in `results.json`.
