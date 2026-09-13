# Developer scripts

Helpers for local development. Python scripts assume the demo app is running and **`/actuator/sentinel`** is exposed. Shell scripts under this directory also cover offline evaluation corpus work and opt-in JMH benchmarks.

---

## Isolation Forest training monitor (`train_monitor.py`)

Sends HTTP traffic and polls **`/actuator/sentinel`** until `isolationForestModelLoaded` is true and `isolationForestModelVersion >= 1`.

### 1. Start the demo with Isolation Forest enabled

From the repo root, use the **`stage2`** profile (recommended — tuned for faster local training):

```bash
mvn -pl ai-sentinel-demo spring-boot:run -Dspring-boot.run.profiles=stage2
```

Profile file: `ai-sentinel-demo/src/main/resources/application-stage2.yaml` (`isolation-forest.enabled: true`, shorter retrain interval, lower `min-training-samples`).

Alternatively, set `ai.sentinel.isolation-forest.enabled: true` (and related keys) in `application.yaml` and restart.

### 2. Run the monitor

```bash
python scripts/train_monitor.py
```

### Options

| Flag | Default | Description |
|------|---------|-------------|
| `--base-url` | `http://localhost:8080` | Base URL of the app |
| `--traffic-endpoint` | `/api/hello` | Path to call for traffic |
| `--total-requests` | `500` | Total requests to send |
| `--concurrency` | `4` | Concurrent workers |
| `--poll-interval` | `3.0` | Seconds between actuator polls |
| `--request-delay-ms` | `10` | Delay between requests per worker (ms); `0` = no delay |

### Exit status

- **0** — Model loaded and version ≥ 1  
- **1** — Actuator unreachable at startup, or training did not complete  

If `isolationForestEnabled` is false, the script prints a warning and continues (see stderr).

---

## Traffic simulator (`traffic_simulator.py`)

Generates **normal**, **burst**, or **attack**-style traffic (mixed methods, headers, query strings, and payload sizes).

### Modes

| Mode | Behavior |
|------|-----------|
| `normal` | Steady RPS; mostly GET; optional small POST bodies |
| `burst` | ~2× RPS bursts with short pauses |
| `attack` | Diverse endpoints (default list), wider payload sizes |

### Usage

```bash
python scripts/traffic_simulator.py --mode normal
python scripts/traffic_simulator.py --mode burst --duration 30 --requests-per-second 15
python scripts/traffic_simulator.py --mode attack --duration 45 --concurrency 8
```

### Options

| Flag | Default | Description |
|------|---------|-------------|
| `--mode` | `normal` | `normal`, `burst`, or `attack` |
| `--base-url` | `http://localhost:8080` | Target base URL |
| `--duration` | `60.0` | Run duration (seconds) |
| `--requests-per-second` | `10.0` | Target RPS (burst spikes higher) |
| `--concurrency` | `4` | Worker threads |
| `--endpoints` | `/api/hello,/api/items,...` | Comma-separated paths |
| `--timeout` | `10.0` | Per-request timeout (seconds) |

### Exit status

- **0** — No failed requests  
- **1** — One or more errors  

Output includes counts, elapsed time, actual RPS, and latency min/avg/max/p50/p99 (ms).

---

## Actuator fields (reference)

`lastScoreComponents` reflects the **latest** blended score breakdown (`statistical`, optional `isolationForest`, `composite`, `isolationForestIncludedInBlend`, optional `isolationForestScoreMode`, `evaluatedAtMillis`); poll after sending traffic if you want fresh values. Fallback IF scores may appear in `isolationForest` while `isolationForestIncludedInBlend` is `false`.

`lastDecision` is the **most recent completed decision on this JVM** (action/policy band, anomaly vs policy score, evaluation statuses / operator phases, IF mode, statistical dominant signal). It intentionally omits identity, endpoint, headers, IP, and tokens — not a request history.

When IF is enabled, `/actuator/sentinel` also includes fields such as `isolationForestModelLoaded`, `isolationForestBufferedSampleCount`, `isolationForestModelVersion`, retrain timestamps, `acceptedTrainingSampleCount`, and `rejectedTrainingSampleCount`. With Micrometer wired, you may also see `scoreSummary`, `latencySummary`, and retrain counters — see `SentinelActuatorEndpoint` in the starter module for the authoritative list.

---

## Opt-in JMH benchmarks (`run-benchmarks.sh`)

Builds `ai-sentinel-benchmark` and runs the shaded JMH jar. **Not** part of normal `mvn test` / CI performance gating.

```bash
./scripts/run-benchmarks.sh smoke       # developer quick check — not official baseline
./scripts/run-benchmarks.sh full        # intermediate suite
./scripts/run-benchmarks.sh reference   # official controlled JMH profile
./scripts/capture-reference-baseline.sh # three reference runs → results/reference-capture/
```

Official measured baseline: [`docs/performance/REFERENCE_BASELINE.md`](../docs/performance/REFERENCE_BASELINE.md).

Details: [`docs/performance/BENCHMARKING.md`](../docs/performance/BENCHMARKING.md).

---

## Offline evaluation corpus helpers

These scripts operate on the tracked synthetic corpus under [`evaluation/reference/`](../evaluation/reference/). They do not establish an official detection baseline.

### Regenerate / compare reference dataset (`generate-reference-dataset.sh`)

```bash
./scripts/generate-reference-dataset.sh          # generate to a temp dir and compare to tracked artifacts
./scripts/generate-reference-dataset.sh --write  # refresh tracked artifacts intentionally
```

Details: [`evaluation/REFERENCE_DATASET.md`](../evaluation/REFERENCE_DATASET.md).

### Replay the reference dataset (`replay-reference-dataset.sh`)

```bash
./scripts/replay-reference-dataset.sh
./scripts/replay-reference-dataset.sh build/reference-replay
```

Compiles `ai-sentinel-core`, runs `ReferenceDatasetReplayMain`, and prints the output path plus checksums.

Details: [`evaluation/DETERMINISTIC_REPLAY.md`](../evaluation/DETERMINISTIC_REPLAY.md).

### Complete detection-evaluation evidence

There is no dedicated shell wrapper yet. After compiling `ai-sentinel-core`, run the thin CLI adapter with an **explicit** `--threshold`:

```bash
mvn -q -pl ai-sentinel-core -DskipTests compile dependency:build-classpath \
  -Dmdep.outputFile=/tmp/ai-sentinel-cp.txt
java -cp "ai-sentinel-core/target/classes:$(cat /tmp/ai-sentinel-cp.txt)" \
  dev.aisentinel.core.evaluation.ReferenceDetectionEvaluationEvidenceMain \
  --threshold 0.5 \
  --output build/reference-detection-evaluation-evidence
```

Any numeric threshold here is caller-supplied for that run only. It is not recommended, approved, or official.

`DIAGNOSTIC RESULT != ACCEPTANCE CRITERION`

Details: [`evaluation/DETECTION_EVALUATION.md`](../evaluation/DETECTION_EVALUATION.md).

### Official Detection Reference Baseline capture

```bash
./scripts/capture-detection-reference-baseline.sh
./scripts/capture-detection-reference-baseline.sh /tmp/detection-reference-baseline-preview
```

Uses `DetectionReferenceBaselineConfiguration.officialReference()` (reference classification threshold `0.5`). This is **not** a general evaluator default and is **not** the performance reference baseline. Refuses an existing destination (no overwrite flags).

Details: [`evaluation/DETECTION_REFERENCE_BASELINE.md`](../evaluation/DETECTION_REFERENCE_BASELINE.md).
