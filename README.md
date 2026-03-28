# AI-Sentinel

**AI-assisted behavioral anomaly detection and policy-driven enforcement for Spring Boot APIs.**

AI-Sentinel scores each request using privacy-oriented features (rates, entropy, payload size, and similar signals), combines statistical baselines with an optional in-process **Isolation Forest** model, and maps scores to actions: allow, monitor, throttle, block, or quarantine. It is designed as a **library and starter**, not a hosted service: everything runs in your JVM with clear extension points.

---

## Why this exists

Traditional WAFs and static rules miss gradual abuse and novel patterns. This project explores **unsupervised, per-identity behavior** as a complement to auth and rate limits—useful for portfolios, experiments, and as a foundation for stricter production hardening later.

---

## Core capabilities

- **Feature extraction** — Rolling counts, endpoint entropy, token age, parameter count, payload size, header fingerprint hash, IP bucket (no raw body or token storage in features).
- **Scoring** — Welford-based statistical scorer (always on) plus optional **Isolation Forest** (bounded training buffer, async retrain, fallback when no model).
- **Policy** — `ThresholdPolicyEngine` with **configurable** score bands (`threshold-moderate` … `threshold-critical`).
- **Enforcement** — Throttle, HTTP block, time-bound quarantine; **MONITOR** mode logs without blocking.
- **Operations** — **Startup grace** (monitor-only window), **enforcement scope** (identity vs identity+endpoint), **trusted proxy** parsing (X-Forwarded-For, Forwarded, guarded X-Real-IP).
- **Observability** — JSON telemetry, Micrometer meters (`aisentinel.*`), custom **`/actuator/sentinel`** endpoint.

---

## Current maturity

Stages **0–4** are implemented in this repository (core engine, Spring Boot integration, Isolation Forest, security/ops hardening, Micrometer/actuator depth). **Pre–Stage 5** fixes (configurable thresholds, safer X-Real-IP, IF-only feature vector) are included. **Stage 5** (distributed state, shared quarantine, multi-node) is **not** started.

Authoritative detail: [`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md). Architecture and data flow: [`ARCHITECTURE.md`](ARCHITECTURE.md).

---

## Requirements

- **Java 21** (see root `pom.xml`)
- **Maven 3.8+**
- **Python 3.7+** (optional; for `scripts/` only)

---

## Quick start

```bash
git clone <repository-url>
cd ai-sentinel
mvn clean install -q
```

### Run the demo

```bash
mvn -pl ai-sentinel-demo spring-boot:run
```

Then:

```bash
curl -s http://localhost:8080/api/hello
curl -s http://localhost:8080/actuator/sentinel | jq .
```

### Run tests

```bash
mvn test
```

---

## Modules

| Module | Role |
|--------|------|
| **ai-sentinel-core** | Features, statistical + IF scoring, policy, enforcement, pipeline, telemetry contracts |
| **ai-sentinel-spring-boot-starter** | Auto-configuration, servlet filter, `SentinelProperties`, actuator endpoint, Micrometer adapter |
| **ai-sentinel-demo** | Sample app (`/api/hello`, etc.), actuator exposure, optional traffic simulator hookup |

There is **no** `ai-sentinel-dashboard` module in this repo; use Prometheus/Grafana or logs against `aisentinel.*` metrics if you need charts.

---

## Add the starter to your app

```xml
<dependency>
    <groupId>io.aisentinel</groupId>
    <artifactId>ai-sentinel-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Minimal configuration:

```yaml
ai:
  sentinel:
    enabled: true
    mode: ENFORCE   # MONITOR = score and log only; OFF = disable
```

---

## Key configuration

| Property | Default | Notes |
|----------|---------|--------|
| `ai.sentinel.enabled` | `true` | Master switch |
| `ai.sentinel.mode` | `ENFORCE` | `OFF`, `MONITOR`, `ENFORCE` |
| `ai.sentinel.exclude-paths` | actuator, health, static, favicon | Comma-separated Ant-style patterns |
| `ai.sentinel.trusted-proxies` | _(empty)_ | IPs or CIDRs; when remote matches, client IP from forwarded headers (see architecture doc) |
| `ai.sentinel.threshold-moderate` … `threshold-critical` | `0.2` … `0.8` | Strictly increasing, in `[0,1]` |
| `ai.sentinel.warmup-min-samples` / `warmup-score` | `2` / `0.4` | Cold-start statistical behavior |
| `ai.sentinel.startup-grace-period` | `0` | Duration (e.g. `5m`) enforcing monitor-only after startup |
| `ai.sentinel.enforcement-scope` | `IDENTITY_ENDPOINT` | Throttle/quarantine key scope |
| `ai.sentinel.isolation-forest.enabled` | `false` | Real in-core IF (not a stub) |
| `ai.sentinel.telemetry.log-verbosity` | `ANOMALY_ONLY` | `FULL`, `ANOMALY_ONLY`, `SAMPLED`, `NONE` |

A fuller YAML example lives in [`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md) §6.

### Enable Isolation Forest locally (demo)

Use the bundled **stage2** profile (tuned for faster training):

```bash
mvn -pl ai-sentinel-demo spring-boot:run -Dspring-boot.run.profiles=stage2
```

Config source: `ai-sentinel-demo/src/main/resources/application-stage2.yaml`.

---

## Actuator and metrics

Expose the custom endpoint (already set in the demo):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,sentinel
```

**`GET /actuator/sentinel`** returns JSON including, among others:

- `enabled`, `mode`, `isolationForestEnabled`, `startupGraceActive`, `enforcementScope`
- `quarantineCount`, `activeThrottleCount`
- When IF is enabled: `isolationForestModelLoaded`, `isolationForestBufferedSampleCount`, `isolationForestModelVersion`, retrain timestamps, `acceptedTrainingSampleCount`, `rejectedTrainingSampleCount`
- When Micrometer is present: `scoreSummary`, `latencySummary`, `modelRetrainSuccessCount`, `modelRetrainFailureCount`

**Prometheus** (`/actuator/prometheus`) includes meters prefixed with **`aisentinel.`** (e.g. `aisentinel_score_composite`, `aisentinel_latency_pipeline_seconds`, `aisentinel_action_allow_total`). Example:

```bash
curl -s http://localhost:8080/actuator/prometheus | grep aisentinel
```

---

## Scripts

Python utilities (stdlib only); see **[`scripts/README.md`](scripts/README.md)**.

| Script | Purpose |
|--------|---------|
| `scripts/train_monitor.py` | Send traffic and poll `/actuator/sentinel` until an IF model is loaded |
| `scripts/traffic_simulator.py` | Sustained **normal**, **burst**, or **attack**-style traffic for local experiments |

Typical flow: start the demo with **`stage2`** profile, then `python scripts/train_monitor.py`.

---

## Roadmap (high level)

| Stage | Focus | Status in this repo |
|-------|--------|---------------------|
| 5 | Distributed store, shared quarantine, cluster coordination | **Next** — not implemented |
| 6 | Research, benchmarks, publications | Not started |

See [`IMPLEMENTATION_STATUS.md`](IMPLEMENTATION_STATUS.md) §2 and §7 for deferred audit items and gaps.

---

## Contributing

- Match existing code style and Maven module boundaries.
- Run **`mvn test`** before submitting changes.
- Prefer factual, concise documentation updates alongside behavior changes.

---

## License

This project is licensed under the **MIT License** — see [`LICENSE`](LICENSE).
