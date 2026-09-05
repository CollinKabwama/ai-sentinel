# AI-Sentinel Benchmark Module

Opt-in benchmark module for measuring the current AI-Sentinel execution paths.

This module does **not** claim production SLAs, partner guarantees, or detection effectiveness.

## What it measures

### In-process JMH reference

- Scorer latency (statistical, Isolation Forest, composite)
- Decision-engine latency for representative workloads
- Feature-extraction latency
- Pipeline latency/throughput at 1/4/16 threads
- Statistical scorer cost across local identity cardinality

Pipeline measurements use the core in-process pipeline with no-op telemetry/enforcement sinks. They do not include
servlet adaptation, Redis/Kafka/network I/O, Micrometer export, or response body writes.

### Deployment and degradation harness

- Java remote evaluation over loopback HTTP using the real `RemoteEvaluationClient`, a benchmark HTTP adapter, and `RemoteEvaluationController`
- Redis-backed behavioral trust overhead relative to local-memory trust state
- Remote degradation scenarios: unavailable, slow/timeout, malformed response, recovery
- Redis degradation scenarios: unavailable, interrupted, recovery

Deployment results are a separate benchmark family from the JMH reference baseline. They are local controlled
integration measurements, not production request latency and not full Spring Boot/Tomcat servlet-stack measurements.

Infrastructure failure is benchmarked as fail-open/degraded behavior only. It must **not** be interpreted as attack
evidence, maximum risk, or detection effectiveness.

### Resource measurements

- Sustained resource runs for in-process, loopback remote, local-memory trust control, and Redis-backed evaluation
- Process CPU over the measured interval using JVM CPU time deltas
- JVM heap snapshots plus sampled peak heap during the measured window
- Best-effort process RSS snapshots on supported hosts
- GC count/time deltas over the measured window
- Local Docker Redis average CPU and latest memory samples for Redis-backed scenarios
- Separate opt-in JMH GC-profiler pass for allocation and GC-allocation evidence

Resource results are a third benchmark family. They do **not** replace the official latency baseline, and profiler-backed
allocation runs are intentionally separate because profiler overhead changes measurement conditions.

## What it does not measure (yet)

- .NET → Java remote path
- Multi-instance distributed behavior
- Detection quality metrics (precision/recall/…)

## Build

```bash
mvn -pl ai-sentinel-benchmark -am package
```

Support-code unit tests run with normal Surefire. JMH suites and deployment benchmark runs do **not** run during
normal `mvn test` / `mvn verify`.

## Run

```bash
# Quick smoke (developer check only — not the official baseline)
./scripts/run-benchmarks.sh smoke

# Intermediate full JMH suite
./scripts/run-benchmarks.sh full

# Official reference JMH profile
./scripts/run-benchmarks.sh reference

# Three controlled official capture runs
./scripts/capture-reference-baseline.sh

# Deployment harness smoke
./scripts/run-deployment-benchmarks.sh smoke

# Focused deployment modes
./scripts/run-deployment-benchmarks.sh remote
./scripts/run-deployment-benchmarks.sh redis
./scripts/run-deployment-benchmarks.sh degradation
./scripts/run-deployment-benchmarks.sh full

# Resource measurement harness
./scripts/run-resource-benchmarks.sh smoke
./scripts/run-resource-benchmarks.sh in-process
./scripts/run-resource-benchmarks.sh remote
./scripts/run-resource-benchmarks.sh redis
./scripts/run-resource-benchmarks.sh full

# Separate JMH allocation / GC profiler pass
./scripts/run-resource-benchmarks.sh allocation

# JMH categories
./scripts/run-benchmarks.sh scorer
./scripts/run-benchmarks.sh engine
./scripts/run-benchmarks.sh features
./scripts/run-benchmarks.sh pipeline
./scripts/run-benchmarks.sh cardinality
```

Or directly:

```bash
java -jar ai-sentinel-benchmark/target/benchmarks.jar [jmh-args...]
java -cp ai-sentinel-benchmark/target/benchmarks.jar \
  dev.aisentinel.benchmark.deployment.DeploymentBenchmarkMain [smoke|remote|redis|degradation|full]
java -cp ai-sentinel-benchmark/target/benchmarks.jar \
  dev.aisentinel.benchmark.deployment.ResourceBenchmarkMain [smoke|in-process|remote|redis|full]
```

Results land under `ai-sentinel-benchmark/results/` (gitignored):

- `manifest-*.json` — AI-Sentinel environment metadata for JMH runs
- `jmh-*.json` — JMH machine-readable output
- `deployment/<timestamp>/deployment-*.json` — deployment/degradation machine-readable output
- `resources/<timestamp>/resource-*.json` — sustained resource measurement output
- `resources/<timestamp>/allocation-jmh.json` — opt-in JMH GC-profiler output for selected in-process workloads

Official measured baseline (tracked docs): [`docs/performance/REFERENCE_BASELINE.md`](../docs/performance/REFERENCE_BASELINE.md) and [`docs/performance/reference-baseline.json`](../docs/performance/reference-baseline.json).

See [`docs/performance/BENCHMARKING.md`](../docs/performance/BENCHMARKING.md).
