# ai-sentinel

AI-Assisted Zero-Trust API Defense for Spring Boot.

## Quickstart

### 1. Add dependency

```xml
<dependency>
    <groupId>io.aisentinel</groupId>
    <artifactId>ai-sentinel-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Enable in application properties

```properties
ai.sentinel.enabled=true
ai.sentinel.mode=ENFORCE   # or MONITOR (log only) or OFF
```

### 3. Run the demo

```bash
mvn clean install
cd ai-sentinel-demo && mvn spring-boot:run
```

### 4. Test with curl

```bash
# Normal request
curl http://localhost:8080/api/hello

# Multiple requests (may trigger scoring)
for i in {1..10}; do curl -s http://localhost:8080/api/hello | jq .; done

# Check metrics
curl http://localhost:8080/actuator/prometheus | grep sentinel

# Sentinel endpoint
curl http://localhost:8080/actuator/sentinel
```

## Modes

| Mode | Behavior |
|------|----------|
| OFF | Sentinel disabled |
| MONITOR | Score and log, never block |
| ENFORCE | Full adaptive enforcement |

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| ai.sentinel.enabled | true | Enable/disable |
| ai.sentinel.mode | ENFORCE | OFF, MONITOR, ENFORCE |
| ai.sentinel.exclude-paths | /actuator/**, /health, ... | Paths to skip |
| ai.sentinel.block-status-code | 429 | HTTP status for block |
| ai.sentinel.quarantine-duration-ms | 300000 | Quarantine length |
| ai.sentinel.throttle-requests-per-second | 5.0 | Throttle limit |
| ai.sentinel.isolation-forest.enabled | false | Enable IF scorer (stub in v1) |
| ai.sentinel.telemetry.log-verbosity | ANOMALY_ONLY | FULL, ANOMALY_ONLY, SAMPLED, NONE |
| ai.sentinel.telemetry.log-score-threshold | 0.4 | Min score to log (ANOMALY_ONLY) |
| ai.sentinel.telemetry.log-sample-rate | 100 | Log every Nth event (SAMPLED) |

## Modules

- **ai-sentinel-core** — Feature extraction, StatisticalScorer, policy, enforcement
- **ai-sentinel-spring-boot-starter** — Auto-configuration, filter, actuator
- **ai-sentinel-demo** — Sample API + traffic simulator

## Developer utilities

### Stage 2: Isolation Forest training monitor

With Isolation Forest enabled, generate traffic and watch the model train:

```bash
# Start demo with ai.sentinel.isolation-forest.enabled=true (see scripts/README.md)
mvn -pl ai-sentinel-demo spring-boot:run

# In another terminal: send traffic and poll until model is loaded
python scripts/train_monitor.py
```

Options: `--base-url`, `--traffic-endpoint`, `--total-requests`, `--concurrency`, `--poll-interval`, `--request-delay-ms`. See **scripts/README.md** for details.

**Traffic simulator** (`scripts/traffic_simulator.py`) — Generate normal, burst, or attack-style traffic with randomized headers, query params, and payload sizes. Options: `--mode normal|burst|attack`, `--base-url`, `--duration`, `--requests-per-second`, `--concurrency`. Prints requests sent, errors, and latency stats.
