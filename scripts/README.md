# Developer scripts

Optional **Python 3.7+** helpers (standard library only). They assume the demo app is running and **`/actuator/sentinel`** is exposed.

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

When IF is enabled, `/actuator/sentinel` includes fields such as `isolationForestModelLoaded`, `isolationForestBufferedSampleCount`, `isolationForestModelVersion`, `isolationForestLastRetrainTimeMillis`, `isolationForestModelAgeMillis`, `isolationForestRetrainFailureCount`, `isolationForestLastRetrainFailureTimeMillis`, `acceptedTrainingSampleCount`, and `rejectedTrainingSampleCount`. With Micrometer wired, you may also see `scoreSummary`, `latencySummary`, and retrain counters — see `SentinelActuatorEndpoint` in the starter module for the authoritative list.
