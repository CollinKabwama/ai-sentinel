# Developer scripts

## Stage 2: Isolation Forest training monitor

`train_monitor.py` generates traffic to the demo app and polls `/actuator/sentinel` until the Isolation Forest model is trained and loaded. Use it to verify Stage 2 behavior locally.

**Requirements:** Python 3.7+ (standard library only).

### 1. Start the demo with Isolation Forest enabled

```bash
# From repo root
mvn -pl ai-sentinel-demo spring-boot:run
```

In `ai-sentinel-demo/src/main/resources/application.yaml` (or a profile), set:

```yaml
ai:
  sentinel:
    isolation-forest:
      enabled: true
      min-training-samples: 100   # lower for faster test (e.g. 50)
      retrain-interval: 1m
```

Restart the demo after changing config.

### 2. Run the script

```bash
# Defaults: http://localhost:8080, 500 requests, poll every 3s
python scripts/train_monitor.py
```

**Options:**

| Flag | Default | Description |
|------|--------|-------------|
| `--base-url` | `http://localhost:8080` | Base URL of the app |
| `--traffic-endpoint` | `/api/hello` | Path to call for traffic |
| `--total-requests` | `500` | Number of requests to send |
| `--concurrency` | `4` | Concurrent workers |
| `--poll-interval` | `3.0` | Seconds between actuator polls |
| `--request-delay-ms` | `10` | Delay between requests per worker (ms) |

**Examples:**

```bash
# More traffic, poll every 2 seconds
python scripts/train_monitor.py --total-requests 1000 --poll-interval 2

# Different port
python scripts/train_monitor.py --base-url http://localhost:9090

# Less delay for faster fill of the buffer
python scripts/train_monitor.py --request-delay-ms 0 --total-requests 300
```

The script exits with code **0** when `isolationForestModelLoaded` is true and `isolationForestModelVersion >= 1`, otherwise **1**. Connection errors are reported to stderr; the script fails fast if the actuator is unreachable at startup.

---

## Traffic simulator

`traffic_simulator.py` generates configurable traffic patterns to train and evaluate the Isolation Forest model. It randomizes headers, query parameters, and payload sizes.

**Requirements:** Python 3.7+ (standard library only).

### Modes

| Mode | Description |
|------|--------------|
| **normal** | Steady request rate; mostly GET with small/empty body; random User-Agent, Accept, optional query params. |
| **burst** | Short bursts at ~2× RPS then pause; simulates traffic spikes. |
| **attack** | Diverse endpoints, wider payload size range (including large bodies), more header variation. |

### Usage

```bash
# Steady traffic for 60s at 10 RPS (default)
python scripts/traffic_simulator.py --mode normal

# Burst pattern, 30s
python scripts/traffic_simulator.py --mode burst --duration 30 --requests-per-second 15

# Attack-style (diverse endpoints + payloads)
python scripts/traffic_simulator.py --mode attack --duration 45 --concurrency 8
```

### Options

| Flag | Default | Description |
|------|--------|-------------|
| `--mode` | `normal` | `normal`, `burst`, or `attack` |
| `--base-url` | `http://localhost:8080` | Base URL of the app |
| `--duration` | `60.0` | Run duration in seconds |
| `--requests-per-second` | `10.0` | Target RPS (burst uses higher rate during spikes) |
| `--concurrency` | `4` | Number of concurrent workers |
| `--endpoints` | `/api/hello,/api/items,...` | Comma-separated paths |
| `--timeout` | `10.0` | Request timeout in seconds |

### Output

Prints requests sent, errors, total, elapsed time, actual RPS, and latency (min, avg, max, p50, p99 in ms). Exit code **1** if any request failed.
