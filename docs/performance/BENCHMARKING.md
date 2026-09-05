# AI-Sentinel benchmarking

This document describes the **reproducible benchmark foundation** for the published **0.3.0** in-process runtime.

## Purpose

Answer, for a measured host and configuration:

1. What does the current evaluation path cost?
2. Where is time spent (features vs scorers vs decision engine vs pipeline)?
3. How does cost change with concurrency and local identity cardinality?
4. Can future architectural changes be compared against a recorded baseline?

## What these numbers are not

Synthetic JMH results on a developer or CI host are **not**:

- production latency guarantees;
- partner SLAs;
- universal throughput claims;
- detection-quality evidence (precision/recall/FPR/…).

Do not publish marketing claims from these runs without a separately scoped measurement program on representative hardware.

## Module layout

| Path | Role |
|------|------|
| [`ai-sentinel-benchmark/`](../../ai-sentinel-benchmark/) | Opt-in JMH module + deployment benchmark harness + support fixtures/metadata |
| [`scripts/run-benchmarks.sh`](../../scripts/run-benchmarks.sh) | Convenience runner |
| [`scripts/run-deployment-benchmarks.sh`](../../scripts/run-deployment-benchmarks.sh) | Deployment/degradation benchmark runner |
| `ai-sentinel-benchmark/results/` | Generated manifests + JMH JSON (gitignored) |

Benchmark-only code stays outside production runtime modules.

## Measurement technology

| Class | Tool | Why |
|-------|------|-----|
| Microbenchmarks (scorer, engine, features, cardinality) | **JMH** `SampleTime` | Warmup, forks, percentile samples, Blackhole consumption; sub-microsecond percentiles are order-of-magnitude signals unless repeated under controlled conditions |
| Pipeline concurrency | JMH `Throughput` + `@Threads` | Controlled worker counts |
| Support-code tests | JUnit | Fixture/metadata correctness — **not** timing |

Naive `System.nanoTime` loops are intentionally **not** used for the foundation suite.

## Workloads

Durable engineering names (not planning jargon):

| Name | Intent |
|------|--------|
| `establishedBaseline` | Seeded statistical state; typical observation |
| `warmupSparse` | Cold/sparse observation (warmup statuses may apply) |
| `abruptDeviation` | Large volume step vs seeded baseline |
| `invalidScore` | NaN scorer → `INVALID_SCORE` path (correctness-oriented) |
| Feature shapes `typical` / `small` / `largerValid` | Extraction cost by request shape |
| Identity cardinality `1` / `100` / `1000` / `10000` | Local map size sensitivity |

Anomalous ≠ malicious. Deviation workloads exercise score/policy cost, not attack claims.

`warmupSparse` resets its tiny scorer fixture before each measured invocation so it remains a warmup-path
measurement instead of drifting into live scoring after the first sample. The pipeline benchmark intentionally
uses one established in-process identity and no-op telemetry/enforcement doubles; it includes feature extraction,
decision evaluation, policy, baseline learning, and enforcement invocation, but excludes servlet adaptation,
network I/O, Redis, Kafka, Micrometer export, and response-body writes.

## Environment metadata

Every launcher run writes `manifest-*.json` including (when available):

- AI-Sentinel version;
- Git commit SHA;
- suite format version;
- Java/OS/arch/CPU/heap;
- feature schema version;
- deployment mode (`in-process`);
- state backend (`local-memory`);
- JMH argument string.

Unavailable fields are JSON `null`.

## How to run

Requires **JDK 21**.

### Smoke (developer quick check — not the official baseline)

```bash
./scripts/run-benchmarks.sh smoke
```

Smoke timings are for local sanity only. Do **not** treat them as the 0.3.0 reference baseline.

### Full foundation suite (intermediate JMH settings)

```bash
./scripts/run-benchmarks.sh full
```

Uses `-f 1 -wi 3 -i 5 -w 1s -r 1s`. Useful for broader local exploration; still **not** the official captured baseline unless revalidated under the `reference` profile.

### Official reference profile (controlled capture)

```bash
# Single reference-profile suite
./scripts/run-benchmarks.sh reference

# Three controlled runs into results/reference-capture/run-0{1,2,3}/
./scripts/capture-reference-baseline.sh
```

Official JMH args: `-f 2 -wi 5 -i 5 -w 1s -r 1s`.

Populated baseline: [`REFERENCE_BASELINE.md`](REFERENCE_BASELINE.md) and [`reference-baseline.json`](reference-baseline.json).

### Categories

```bash
./scripts/run-benchmarks.sh scorer
./scripts/run-benchmarks.sh engine
./scripts/run-benchmarks.sh features
./scripts/run-benchmarks.sh pipeline
./scripts/run-benchmarks.sh cardinality
```

Category modes inherit default launcher JMH settings unless you pass extra JMH args. Prefer `reference` (or `capture-reference-baseline.sh`) for official comparisons.

### Build only

```bash
mvn -pl ai-sentinel-benchmark -am package
java -jar ai-sentinel-benchmark/target/benchmarks.jar [jmh-args...]
```

## Output format

1. **`manifest-*.json`** — AI-Sentinel metadata (suite version, commit, environment, disclaimer).
2. **`jmh-*.json`** — JMH native JSON (primary/secondary metrics, including sample percentiles when using `SampleTime`).

Prefer JMH’s statistics over a hand-rolled percentile engine.

## Deployment and degradation benchmarks

These measurements are a separate result family from the in-process JMH reference baseline. They exist to measure
deployment cost and fail-open/degradation behavior, not detection effectiveness.

### Tooling split

- **JMH** remains the benchmark tool for in-process scorer/engine/feature/pipeline/cardinality measurements.
- **Deployment harness** measures Java remote HTTP, Redis-backed state, and degradation/recovery scenarios where JMH
  would be a poor fit.

Do not compare JMH nanoseconds-per-operation directly with loopback deployment latency as if they were identical
measurement boundaries.

### Deployment modes

```bash
./scripts/run-deployment-benchmarks.sh smoke
./scripts/run-deployment-benchmarks.sh remote
./scripts/run-deployment-benchmarks.sh redis
./scripts/run-deployment-benchmarks.sh degradation
./scripts/run-deployment-benchmarks.sh full
```

`smoke` is a quick sanity run only. `full` is opt-in. Neither replaces the official in-process reference baseline.

### Current deployment scenarios

- `JAVA_REMOTE_NORMAL`
- `REMOTE_UNAVAILABLE`
- `REMOTE_SLOW_OR_TIMEOUT`
- `REMOTE_MALFORMED_RESPONSE`
- `REMOTE_RECOVERY`
- `TRUST_LOCAL_MEMORY_REFERENCE`
- `REDIS_NORMAL`
- `REDIS_UNAVAILABLE`
- `REDIS_INTERRUPTED`
- `REDIS_RECOVERY`

`.NET -> Java` benchmarking remains optional and environment-dependent.

### Measurement boundaries

Java remote latency includes:

- client serialization;
- loopback HTTP transport;
- request deserialization by the benchmark HTTP adapter;
- `RemoteEvaluationController` contract handling;
- local AI-Sentinel evaluation;
- response serialization;
- client deserialization.

Redis-backed latency includes the same loopback remote boundary plus the trust baseline state interaction. When Redis is
enabled locally, that includes client calls, local Docker-hosted Redis transport, Redis server-side update execution, and
any documented fail-open fallback.

These deployment benchmarks exclude:

- WAN/internet latency;
- full Spring Boot/Tomcat servlet dispatch;
- production TLS termination if not configured locally;
- load balancers/API gateways;
- application business endpoint work;
- production telemetry/export pipelines;
- real cloud Redis networking and topology.

### Result semantics

Deployment results are written as JSON under:

- `ai-sentinel-benchmark/results/deployment/<timestamp>/deployment-*.json`

Each result records scenario, deployment mode, state backend, concurrency, warmup, measured attempts, success/failure
counters, latency percentiles, throughput, timeout budget, environment metadata, and benchmark notes.

Successful latency percentiles are computed from post-warmup per-request successful observations only. Failed operations
are summarized separately and are never mixed into successful latency percentiles.

### Prerequisites and interpretation

- JDK 21 is required.
- Docker must be available for the Redis-backed deployment scenarios.
- Raw generated deployment results remain gitignored.

Benchmark result != production SLA.

Performance != detection effectiveness.

Infrastructure failure != attack.

Local loopback/container performance != production infrastructure performance.

## Resource measurements

These measurements are a separate result family from both the official in-process JMH latency baseline and the
deployment/degradation latency harness. They exist to capture local resource cost under controlled load, not to make
production sizing claims.

### Resource modes

```bash
./scripts/run-resource-benchmarks.sh smoke
./scripts/run-resource-benchmarks.sh in-process
./scripts/run-resource-benchmarks.sh remote
./scripts/run-resource-benchmarks.sh redis
./scripts/run-resource-benchmarks.sh full
./scripts/run-resource-benchmarks.sh allocation
```

`smoke` is instrumentation verification only. `full` is the longer opt-in evidence profile. `allocation` is a separate
JMH GC-profiler pass for selected in-process workloads and is not equivalent to the official latency baseline.

### Resource scenarios

- `RESOURCE_IN_PROCESS`
- `RESOURCE_REMOTE`
- `RESOURCE_REDIS_LOCAL_MEMORY_CONTROL`
- `RESOURCE_REDIS_BACKED`

The resource harness currently measures the combined benchmark-client plus evaluator JVM for remote and Redis-backed
scenarios. It does not claim standalone server-only CPU or memory usage.

### Metrics and sources

- Process CPU time delta via `OperatingSystemMXBean.getProcessCpuTime()`
- `processCpuCoresEquivalent = processCpuTimeDelta / measuredWallTime`
- `processCpuPercentOfMachine = processCpuCoresEquivalent / logicalProcessors * 100`
- Heap snapshots and sampled measured-window peak via `MemoryMXBean`
- GC collection count/time deltas via `GarbageCollectorMXBean`
- Best-effort process RSS snapshots on supported hosts via `ps -o rss`
- Local Redis container CPU/memory sampling via `docker stats --no-stream`
- Allocation and GC-allocation evidence via a separate JMH `-prof gc` run

Peak heap is based on observed samples during the measured window. Process RSS and Redis container metrics remain
best-effort platform/local-runtime observations rather than portable capacity claims.
`redisContainerCpuPercent` is the average of successful `docker stats --no-stream` samples collected during the measured
window. `redisContainerMemoryBytes` is the latest successful container memory sample, not a peak or average.
Redis-backed resource rows also include measured-window `redisTrustSuccesses`, `redisTrustFailures`, and
`redisTrustFallbacks` counters so a local fallback path cannot be mistaken for successful Redis-backed measurement.

### Measurement boundaries and interpretation

- Warmup and measured windows are distinct; interval CPU and GC deltas are taken over the measured window only.
- Resource JSON records `used`, `committed`, and `max` heap separately.
- Process RSS is not a proxy for JVM heap.
- Allocation rate is not retained heap and is not evidence of a memory leak.
- Local Docker Redis measurements are not cloud or managed Redis cost.
- Resource benchmark result != production capacity.
- Local resource result != cloud sizing guidance.

### Resource outputs

Generated resource outputs are written under:

- `ai-sentinel-benchmark/results/resources/<timestamp>/resource-*.json`
- `ai-sentinel-benchmark/results/resources/<timestamp>/allocation-jmh.json`

These raw generated outputs remain gitignored unless a separate stable summary artifact is intentionally tracked.

## Normal CI / verify behavior

`mvn test` and `mvn clean verify` compile the benchmark module and run **support-code unit tests only**.

They do **not** execute the JMH suite or the deployment benchmark runs. There are **no** host-dependent performance
gates in CI yet.

## Extension points

Still out of scope for the current benchmark suite:

- multi-instance distributed behavior under real cluster topology;
- automated regression thresholds/gates;
- broader `.NET -> Java` deployment benchmarking when the runtime is unavailable.

## Related

- [`REFERENCE_BASELINE.md`](REFERENCE_BASELINE.md) — official 0.3.0 reference baseline (measured)
- [`reference-baseline.json`](reference-baseline.json) — machine-readable baseline summary
- [`docs/testing.md`](../testing.md) — correctness gates (distinct from JMH)
- [`ARCHITECTURE.md`](../../ARCHITECTURE.md) — runtime design
