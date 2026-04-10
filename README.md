# AI-Sentinel

**Behavioral anomaly detection and policy enforcement for Spring Boot APIs.**

**What it is:** A library and starter that scores each request from privacy-oriented features (rates, entropy, payload size, and related signals), combines a statistical baseline with an optional **Isolation Forest** model, and maps the result to actions: allow, monitor, throttle, block, or quarantine.

**Problem it addresses:** Static rules and coarse rate limits miss gradual or identity-specific abuse. AI-Sentinel adds **per-identity** behavior signals as a complement to authentication and infrastructure controls.

**How it runs:** Everything executes **in-process** in your JVM—no hosted scoring service. A servlet **filter** drives the pipeline (extract → score → policy → enforce). Optional **Phase 5** pieces add Redis-backed cluster views, async training export, a separate **trainer** app, and filesystem **model registry** refresh on serving nodes.

---

## Quickstart

**Prerequisites:** Java 21, Maven 3.8+ (Python optional for `scripts/`).

**End-to-end training path** (optional Phase 5.5 → 5.6):

```
Client
  ↓
SentinelFilter
  ↓
Scoring → Policy → Enforcement
  ↓
  └─ optional: TrainingPublisher → Kafka → Trainer → model registry → Node refresh (IF)
```

**Steps**

1. **Build** — `git clone <repository-url> && cd ai-sentinel && mvn clean install`
2. **Demo API** — `mvn -pl ai-sentinel-demo spring-boot:run` → `http://localhost:8080/api/hello` and `http://localhost:8080/actuator/sentinel`
3. **Optional trainer** — With Kafka and candidates flowing: `mvn -pl ai-sentinel-trainer spring-boot:run`, set `aisentinel.trainer.kafka.enabled=true`, and align registry paths with `ai.sentinel.model-registry.filesystem-root`. See [`ai-sentinel-trainer/README.md`](ai-sentinel-trainer/README.md).
4. **Tests** — `mvn test` (Docker optional for some starter integration tests)

---

## Key features

- **Feature extraction** — Rolling counts, endpoint entropy, token age, parameter count, payload size, header fingerprint hash, IP bucket (no raw body or token storage in features).
- **Scoring** — Welford statistical scorer (always on) plus optional **Isolation Forest** (bounded training buffer, async retrain, fallback when no model).
- **Policy** — `ThresholdPolicyEngine` with configurable score bands (`threshold-moderate` … `threshold-critical`).
- **Enforcement** — Throttle, HTTP block, time-bound quarantine; **MONITOR** mode logs without blocking.
- **Operations** — Startup grace (monitor-only window), enforcement scope (identity vs identity+endpoint), trusted proxy parsing (X-Forwarded-For, Forwarded, guarded X-Real-IP).
- **Observability** — JSON telemetry, Micrometer (`aisentinel.*`), **`/actuator/sentinel`**.

---

## Modules

| Module | Role |
|--------|------|
| **ai-sentinel-core** | Features, statistical + IF scoring, policy, enforcement, pipeline, telemetry contracts |
| **ai-sentinel-spring-boot-starter** | Auto-configuration, servlet filter, `SentinelProperties`, actuator, Micrometer adapter |
| **ai-sentinel-trainer** | Optional app: Kafka consumer for training candidates, IF training, filesystem model registry publisher |
| **ai-sentinel-demo** | Reference app (`/api/hello`), actuator, optional traffic simulator |

There is no `ai-sentinel-dashboard` module; use Prometheus/Grafana or logs for charts.

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

## Architecture

Runtime design, extension points (`@ConditionalOnMissingBean`), and the distributed pipeline are described in **[`ARCHITECTURE.md`](ARCHITECTURE.md)** (request path §3, distributed §10, testing §14).

Phases **0–5.6** are implemented (core through optional Redis, training export, **`ai-sentinel-trainer`**, filesystem registry refresh). The main **gap** for multi-node testing is **automated multi-JVM** validation (integration tests today use one JVM per suite).

---

## Configuration

- **Prefix:** `ai.sentinel.*` (starter), `aisentinel.trainer.*` (trainer module).
- **High level:** `enabled` / `mode`, thresholds, `isolation-forest.*`, `distributed.*` (cluster quarantine, throttle, training publish), `model-registry.*`.

**Full property table, Redis notes, and IF demo profile:** **[`docs/configuration.md`](docs/configuration.md)**.

---

## Actuator and metrics

Expose the custom endpoint (as in the demo):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,sentinel
```

**`GET /actuator/sentinel`** — config flags, quarantine/throttle counts, IF state, `lastScoreComponents`, distributed/throttle summaries when enabled.

**Prometheus** — meters prefixed with `aisentinel.` (e.g. `curl -s http://localhost:8080/actuator/prometheus | grep aisentinel`).

---

## Scripts

Python (stdlib only): **[`scripts/README.md`](scripts/README.md)** (`train_monitor.py`, `traffic_simulator.py`). Typical: run the demo with the **`stage2`** profile, then `python scripts/train_monitor.py`.

---

## Roadmap (high level)

| Phase | Focus | Status in this repo |
|-------|--------|---------------------|
| 5 | Distributed coordination, training pipeline, trainer, model registry | **Largely complete** (5.3–5.6); multi-JVM automated validation still a gap |
| 6 | Hyperparameter tuning, alternative model types, operational tooling | Not started |

### Phase 5 notes (brief)

- **5.3 — Validation** — Integration tests under `io.aisentinel.validation.*` use Testcontainers Redis and **single-JVM** setups; Docker required when not skipped. No two-JVM CI suite yet. Details: [`ARCHITECTURE.md`](ARCHITECTURE.md) §14.
- **5.4 — Cluster throttle** — Redis fixed-window counter on the **THROTTLE** path only; fail-open; metrics `aisentinel.distributed.throttle.*`. See [`docs/configuration.md`](docs/configuration.md) for properties.
- **5.5 — Training publish** — Async `TrainingCandidateRecord` export (log or Kafka). See [`ARCHITECTURE.md`](ARCHITECTURE.md) §10.
- **5.6 — Trainer + registry** — [`ai-sentinel-trainer/README.md`](ai-sentinel-trainer/README.md); serving nodes refresh via `ModelRefreshScheduler`.

---

## Current limitations

- **Filesystem model registry** only (no built-in S3/Redis artifact store in this repo).
- **Trainer `eventId` dedup** is JVM-local; multiple trainer instances are not coordinated.
- **Multi-JVM / multi-process** validation for cluster features is not fully automated in CI.
- **Registry disk** — no automatic artifact cleanup; operators manage retention.

---

## Security

**[`SECURITY.md`](SECURITY.md)** — reporting and design assumptions.

---

## Contributing

Development happens on the **`dev`** branch — see [`CONTRIBUTING.md`](CONTRIBUTING.md) for the branching workflow, layout, tests, PR expectations, and project invariants.

- Match existing style and module boundaries.
- Run **`mvn test`** before submitting.
- Update docs when behavior or configuration changes.

---

## License

This project is licensed under the **MIT License** — see [`LICENSE`](LICENSE).
