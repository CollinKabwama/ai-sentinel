# AI-Sentinel 0.3.0 reference performance baseline

## 1. Purpose

This document records the **official reference engineering baseline** for the approved **AI-Sentinel 0.3.0** in-process implementation under a documented controlled environment.

These measurements are a reference engineering baseline for the documented environment.

They are **NOT**:

- production SLA guarantees;
- minimum performance guarantees;
- hardware-independent results;
- security efficacy claims;
- partner workload benchmarks.

The benchmark captures relative engineering reference points that are intended to make future changes comparable against a known **0.3.0** baseline.

Machine-readable summary: [`reference-baseline.json`](reference-baseline.json). It includes selected per-run raw metrics and SHA-256 hashes for the local raw capture artifacts used to audit the accepted baseline: `capture-notes.txt`, `analysis.json`, and the stable `run-0{1,2,3}/{jmh.json,manifest.json}` copies. The large generated raw capture directory remains gitignored.

## 2. Baseline identity

| Field | Value |
|-------|-------|
| AI-Sentinel version | `0.3.0` |
| Git commit (product HEAD at capture) | `7bce0feb59784a524e932af799aecbd8eda76557` |
| Benchmark suite | `ai-sentinel-benchmark-foundation` suite format `1` |
| Feature schema version | `1` |
| Deployment mode | `in-process` |
| State backend | `local-memory` |
| Capture date (UTC) | 2026-09-04 |
| Capture window (UTC) | 2026-09-04T05:31:28Z → 2026-09-04T05:51:58Z |
| Controlled runs | 3 (`run-01` … `run-03`) |
| Capture command | `./scripts/capture-reference-baseline.sh` |

Working-tree benchmark-foundation sources were present during capture. They measure **0.3.0** runtime behavior from `ai-sentinel-core`; they do not change production semantics.

## 3. Environment

| Field | Value |
|-------|-------|
| Host class | developer laptop (not lab hardware) |
| OS | macOS (`Mac OS X` 26.6.2) |
| Architecture | `aarch64` (Apple Silicon) |
| CPU model | Apple M5 |
| Logical CPUs | 10 |
| Physical memory | ~24 GiB (`25769803776` bytes) |
| JVM | OpenJDK 64-Bit Server VM |
| Java version | 21.0.10 (LTS) |
| JVM vendor | Microsoft |
| Max heap (manifest) | ~6 GiB (`6442450944` bytes) |
| Relevant JVM args | `-Daisentinel.benchmark.resultsDir=<run-dir>` only |
| Container limits | none (bare metal host JVM) |
| Power source | **Battery Power** (not AC) |
| Performance / low-power mode | unknown (not reliably detected) |
| Thermal condition | no obvious thermal throttle noted; not instrumented |
| Background load | concurrent heavy builds/`mvn verify` avoided; ordinary desktop activity may still exist |

Honest environment note: capture was performed on battery. Future comparisons should prefer AC power when possible and treat cross-host deltas carefully.

## 4. Benchmark configuration

Official profile: **`reference`** (not `smoke`, not developer-host smoke numbers).

| JMH setting | Value |
|-------------|-------|
| Forks (`-f`) | 2 |
| Warmup iterations (`-wi`) | 5 |
| Warmup time (`-w`) | 1s |
| Measurement iterations (`-i`) | 5 |
| Measurement time (`-r`) | 1s |
| Latency mode | `SampleTime` (ns/op) |
| Throughput mode | `Throughput` (ops/s) |
| Threads | 1 (unless `@Threads` on throughput methods: 1 / 4 / 16) |

GC / allocation profilers were **not** attached to the official latency/throughput runs (to avoid distorting timings). Optional allocation/GC observation was not published in this baseline.

Raw per-run artifacts (gitignored):

```text
ai-sentinel-benchmark/results/reference-capture/
  capture-notes.txt
  run-01/{manifest.json,jmh.json,…}
  run-02/{manifest.json,jmh.json,…}
  run-03/{manifest.json,jmh.json,…}
```

## 5. Scorer results

Units: **ns/op** (`SampleTime`). Forks=2, wi=5, i=5.

| Scorer path | mean | p50 | p95 | p99 | selected run | p50 ratio (max/min) |
|-------------|-----:|----:|----:|----:|-------------:|--------------------:|
| `statistical` | 102.9 | 83 | 84 | 125 | 1 | 1.00 |
| `isolationForestModel` (loaded model) | 201.6 | 167 | 209 | 250 | 2 | 1.00 |
| `isolationForestFallback` (no loaded model) | **19.3** | 0† | 42† | 42† | 1 | mean ratio 1.12 |
| `compositeStatisticalOnly` | 87.7 | 83 | 84 | 166 | 1 | 1.00 |
| `compositeWithIf` | 285.7 | 250 | 292 | 583 | 3 | 1.00 |

† Isolation Forest **fallback** is extremely cheap; SampleTime **p50 hits the timer resolution floor (~0 ns)**. Treat **mean ≈ 19 ns/op** as the primary comparison metric for that path. Do **not** merge fallback with loaded-model inference.

## 6. Decision-engine results

Units: **ns/op**. Workloads exercise decision cost, not attack claims. **ANOMALOUS ≠ MALICIOUS.**

| Workload | Meaning | mean | p50 | p95 | p99 | run |
|----------|---------|-----:|----:|----:|----:|---:|
| `establishedBaseline` | Seeded statistical state; typical observation | 676.4 | 542 | 625 | 1290 | 3 |
| `warmupSparse` | Cold/sparse path; fixture reset **per invocation** so measurement stays on the warmup path | 496.3 | 458 | 500 | 708 | 2 |
| `abruptDeviation` | Large volume step vs seeded baseline (behavioral deviation cost) | 757.3 | 542 | 667 | 1542 | 3 |
| `invalidScore` | NaN scorer → invalid-score handling | 356.5 | 292 | 334 | 708 | 1 |

p50 was identical across all three runs for each workload (ratio 1.00). p99 tails vary more run-to-run; values above are from the selected representative run.

## 7. Feature extraction results

Units: **ns/op**. Fixtures are synthetic; they do **not** cover arbitrary production payload sizes.

| Shape | Fixture properties | mean | p50 | p95 | p99 | run |
|-------|--------------------|-----:|----:|----:|----:|---:|
| `small` | `GET /api/ping`, remote addr only | 250.7 | 209 | 250 | 334 | 2 |
| `typical` | `GET /api/benchmark`, UA/Accept, one query param | 379.5 | 292 | 417 | 709 | 1 |
| `largerValid` | `GET /api/search`, several headers (incl. auth/token-issued), 16 query params, `Content-Length: 32768` | 460.9 | 417 | 541 | 958 | 2 |

## 8. Pipeline latency results

Name: **AI-Sentinel in-process pipeline evaluation latency**

(`PipelineBenchmark.processLatencyOneThread`, 1 thread, typical request, shared established identity).

| Metric | Value |
|--------|------:|
| mean | 886.5 ns/op |
| p50 | 792 ns/op |
| p95 | 958 ns/op |
| p99 | 1334 ns/op |
| selected run | 2 |
| p50 ratio across runs | 1.05 |

Includes in-process feature extraction, scoring/decision evaluation, policy, baseline learning, and enforcement **invocation** against no-op doubles.

Explicitly **excludes**:

- servlet adaptation / filter overhead (if outside the benchmarked pipeline call);
- HTTP response writing / response-body writes;
- Redis;
- Kafka;
- network I/O;
- trainer;
- registry;
- Micrometer export.

This is **not** end-to-end application latency.

## 9. Pipeline throughput results

Shared **single identity** across threads (intentional contention / shared-state profile). Not production capacity.

| Threads | mean ops/s (selected) | selected run | mean ratio (max/min across 3 runs) |
|--------:|----------------------:|-------------:|-----------------------------------:|
| 1 | 1 161 084 | 2 | 1.11 |
| 4 | 1 697 636 | 2 | 1.43 |
| 16 | 1 530 236 | 3 | 1.02 |

Four-thread throughput showed the highest run-to-run variance among throughput profiles (still under a 1.5× mean ratio). Do not generalize to multi-identity production capacity.

## 10. Identity-cardinality results

Measures **local in-memory statistical scorer-state lookup/scoring cost** as identity cardinality increases (`1` / `100` / `1000` / `10000`). Units: **ns/op**.

| Identities | mean | p50 | p95 | p99 | run |
|-----------:|-----:|----:|----:|----:|---:|
| 1 | 74.5 | 83 | 84 | 125 | 2 |
| 100 | 111.7 | 84 | 125 | 125 | 1 |
| 1000 | 113.9 | 84 | 125 | 167 | 2 |
| 10000 | 123.0 | 84 | 125 | 167 | 2 |

This does **not** claim that AI-Sentinel “scales to 10,000 production users.”

## 11. Optional allocation / GC observations

Not published for this baseline. Profilers were kept off primary official runs to avoid timing distortion. A separate profiling pass may be added later without revising these latency numbers.

## 12. Repeatability / variance notes

- Three controlled `reference`-profile executions were completed.
- SampleTime **p50** values were highly stable (most ratios = 1.00; feature `small` p50 ratio 1.20; pipeline latency 1.05).
- Isolation Forest fallback is **not** unstable in wall cost; SampleTime percentiles sit on the timer floor — use **mean**.
- Throughput means: 1-thread and 16-thread stable; **4-thread** mean ratio **1.43** (highest variance; documented, not hidden).
- p99 is noisier than p50; official p99 is from the selected representative run, not an average of percentiles across runs.

Verdict on stability: **acceptable for a developer-host reference baseline** with the documented caveats.

## 13. Interpretation

Under this host and configuration, in-process evaluation components typically sit in the **sub-microsecond to low-microsecond** SampleTime regime for scorers/engine/features/pipeline latency, with pipeline throughput on the order of **~1.1–1.7 Mops/s** for the shared-identity microbenchmark profiles.

Use these figures only as **relative** anchors when comparing future commits on comparable hardware and the same `reference` JMH profile.

## 14. Limitations

- Single developer laptop; battery-powered during capture.
- Synthetic fixtures; not production traffic mix.
- In-process / local-memory only — not remote, Redis, Kafka, .NET, or multi-instance.
- Shared-identity throughput profile ≠ production multi-tenant capacity.
- Identity cardinality is local map cost only.
- Not detection quality (precision/recall/FPR).
- Not an SLA or partner guarantee.
- JMH SampleTime resolution limits interpretation of sub-~40 ns paths.

## 15. Reproduction commands

```bash
# Three official controlled runs (writes gitignored raw results)
./scripts/capture-reference-baseline.sh

# Or a single reference-profile suite into a chosen results directory
AISENTINEL_BENCHMARK_RESULTS_DIR=ai-sentinel-benchmark/results/manual-reference \
  ./scripts/run-benchmarks.sh reference
```

Requires JDK 21. Do **not** treat `smoke` or ad-hoc short runs as this official baseline.

Compare future work with the same `reference` JMH args (`-f 2 -wi 5 -i 5 -w 1s -r 1s`) and record environment metadata from the generated manifests.
