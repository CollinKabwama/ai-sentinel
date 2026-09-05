# AI-Sentinel Benchmark Module

Opt-in JMH foundation for measuring the **current** in-process AI-Sentinel decision path.

This module does **not** claim production SLAs, partner guarantees, or detection effectiveness.

## What it measures

- Scorer latency (statistical, Isolation Forest, composite)
- Decision-engine latency for representative workloads
- Feature-extraction latency
- Pipeline latency/throughput at 1/4/16 threads
- Statistical scorer cost across local identity cardinality

Pipeline measurements use the core in-process pipeline with no-op telemetry/enforcement sinks. They do not include
servlet adaptation, Redis/Kafka/network I/O, Micrometer export, or response body writes.

## What it does not measure (yet)

- Java remote evaluation
- .NET → Java remote path
- Redis / dependency degradation
- Multi-instance distributed behavior
- Detection quality metrics (precision/recall/…)

## Build

```bash
mvn -pl ai-sentinel-benchmark -am package
```

Support-code unit tests run with normal Surefire. JMH suites do **not** run during `mvn test` / `mvn verify`.

## Run

```bash
# Quick smoke (developer check only — not the official baseline)
./scripts/run-benchmarks.sh smoke

# Intermediate full suite
./scripts/run-benchmarks.sh full

# Official reference JMH profile
./scripts/run-benchmarks.sh reference

# Three controlled official capture runs
./scripts/capture-reference-baseline.sh

# Categories
./scripts/run-benchmarks.sh scorer
./scripts/run-benchmarks.sh engine
./scripts/run-benchmarks.sh features
./scripts/run-benchmarks.sh pipeline
./scripts/run-benchmarks.sh cardinality
```

Or directly:

```bash
java -jar ai-sentinel-benchmark/target/benchmarks.jar [jmh-args...]
```

Results land under `ai-sentinel-benchmark/results/` (gitignored):

- `manifest-*.json` — AI-Sentinel environment metadata
- `jmh-*.json` — JMH machine-readable output (includes sample percentiles)

Official measured baseline (tracked docs): [`docs/performance/REFERENCE_BASELINE.md`](../docs/performance/REFERENCE_BASELINE.md) and [`docs/performance/reference-baseline.json`](../docs/performance/reference-baseline.json).

See [`docs/performance/BENCHMARKING.md`](../docs/performance/BENCHMARKING.md).
