# AI-Sentinel

**Zero-trust API defense for Spring Boot** — runtime behavioral analysis, identity-aware trust, anomaly scoring, optional risk fusion, and adaptive enforcement, all **in-process** in your JVM.

---

## Overview

AI-Sentinel is a library and Spring Boot starter that evaluates each HTTP request using privacy-oriented features (rates, entropy, payload shape, header fingerprints, IP buckets, and related signals). It combines statistical baselines with an optional **Isolation Forest** model, optionally blends **identity trust** with **anomaly risk**, and maps the outcome to actions: allow, monitor, throttle, block, or quarantine. There is no hosted scoring service: a servlet **filter** runs the pipeline on every request.

**Problem it addresses:** Static rules and coarse rate limits miss gradual or identity-specific abuse. AI-Sentinel complements authentication and infrastructure controls with **per-identity** behavioral signals and a single, configurable policy surface.

---

## Key capabilities

- **Identity-aware security** — Optional integration with Spring Security and HTTP sessions to resolve `IdentityContext` and attach trust metadata to the request.
- **Behavioral trust** — Per-identity baselines and trust scores derived from request history, drift signals, and burst patterns.
- **Anomaly detection** — Welford-based statistical scoring plus an optional in-core Isolation Forest.
- **Risk fusion** — Optional combination of anomaly score and identity trust so policy evaluates a single fused risk scalar.
- **Adaptive enforcement** — Threshold-driven actions (throttle, block, quarantine) with monitor-only mode and startup grace.
- **Distributed state (optional)** — Redis-backed cluster quarantine and throttle, asynchronous **training candidate** export, a standalone **trainer** application, and filesystem **model registry** refresh on serving nodes.
- **Distributed behavioral baselines (optional)** — Redis-backed continuity for per-identity behavioral baselines across instances, with fail-open fallback to in-memory storage when Redis is slow or unavailable.

---

## Architecture (high level)

| Layer | Responsibility |
|-------|------------------|
| **ai-sentinel-core** | `SentinelPipeline`, feature extraction, scoring, policy, enforcement, telemetry contracts, identity, trust, and fusion types |
| **ai-sentinel-spring-boot-starter** | Auto-configuration, `SentinelFilter`, `SentinelProperties`, actuator, Micrometer, optional Redis and Kafka integration |
| **ai-sentinel-trainer** | Optional application: consumes training candidates, trains Isolation Forest models, publishes artifacts to a shared filesystem registry |
| **ai-sentinel-demo** | Reference application and smoke tests |

Runtime details, extension points, and distributed components are described in **[`ARCHITECTURE.md`](ARCHITECTURE.md)**.

---

## How it works (request flow)

```text
Request
  → Identity resolution (optional)
  → Feature extraction
  → Behavioral trust evaluation (optional)
  → Anomaly scoring
  → Risk fusion (optional)
  → Policy evaluation
  → Enforcement
  → Telemetry / metrics
```

**Optional training path** (off the servlet hot path for model refresh): serving nodes may publish `TrainingCandidateRecord` events (log or Kafka) → **trainer** consumes → writes registry artifacts → nodes **poll** and install new Isolation Forest models when configured.

---

## Quickstart

**Prerequisites:** Java 21, Maven 3.8+ (Python optional for `scripts/`).

1. **Build** — `git clone <repository-url> && cd ai-sentinel && mvn clean install`
2. **Demo API** — `mvn -pl ai-sentinel-demo spring-boot:run` → `http://localhost:8080/api/hello` and `http://localhost:8080/actuator/sentinel`
3. **Optional trainer** — With Kafka and candidates flowing: `mvn -pl ai-sentinel-trainer spring-boot:run`, set `aisentinel.trainer.kafka.enabled=true`, and align registry paths with `ai.sentinel.model-registry.filesystem-root`. See [`ai-sentinel-trainer/README.md`](ai-sentinel-trainer/README.md).
4. **Tests** — `mvn test` (Docker optional for some starter integration tests)

---

## Configuration

- **Prefixes:** `ai.sentinel.*` (starter), `aisentinel.trainer.*` (trainer).
- **High level:** `enabled` / `mode`, thresholds, `isolation-forest.*`, `identity.*` (resolution and trust), `identity.fusion.*` (risk fusion), `distributed.*`, `model-registry.*`.

**Full property table, Redis budgets, and demo profiles:** **[`docs/configuration.md`](docs/configuration.md)**.

Minimal application configuration:

```yaml
ai:
  sentinel:
    enabled: true
    mode: ENFORCE   # MONITOR = score and log only; OFF = disable
```

Add the starter dependency:

```xml
<dependency>
    <groupId>io.aisentinel</groupId>
    <artifactId>ai-sentinel-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

---

## Deployment modes

### Local mode (default)

All state is **in-process**: statistical baselines, optional Isolation Forest training buffer, policy thresholds, and local throttle/quarantine maps. No Redis or Kafka is required. This is the right default for single-node applications and most development workflows.

### Distributed mode (optional)

Enable **`ai.sentinel.distributed.*`** and add **`spring-boot-starter-data-redis`** when you need cluster-wide quarantine visibility, cluster throttle counters, or asynchronous training export. Enable **`ai.sentinel.identity.trust.distributed.enabled`** (with a `StringRedisTemplate` bean) to share **behavioral trust baselines** across horizontal replicas; on Redis timeout or error, the implementation **fails open** to the same in-memory baseline semantics used in local mode.

- **Cluster quarantine and throttle** — Redis lookups use bounded waits; local enforcement remains authoritative when Redis is unavailable.
- **Behavioral baselines (Redis)** — Each update runs as one Redis **EVAL** (Lua) script (read-merge-write with TTL). The client enforces **`ai.sentinel.identity.trust.distributed.command-timeout`** on that round-trip; align `spring.data.redis.timeout` with the same budget.
- **Training and model registry** — Bounded, fail-open async publish; trainer writes to a **filesystem** layout that serving nodes poll for new models.

Optional integrations do not change the core policy math unless you turn the corresponding flags on.

---

## Observability

- **JSON telemetry** — Structured events with configurable verbosity and sampling (`ai.sentinel.telemetry.*`).
- **Micrometer** — Meters prefixed with `aisentinel.*`.
- **`GET /actuator/sentinel`** — Configuration flags, quarantine and throttle summaries, Isolation Forest state, and recent score components when the Micrometer adapter is present.

Example exposure:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,sentinel
```

---

## Extensibility

Spring Boot **`@ConditionalOnMissingBean`** is applied across the pipeline. You can replace **`FeatureExtractor`**, **`PolicyEngine`**, **`EnforcementHandler`**, **`SentinelMetrics`**, **`TrainingCandidatePublisher`**, **`ClusterThrottleStore`**, **`ModelRegistryReader`**, and other registered types by declaring your own beans. See the extension table in **[`ARCHITECTURE.md`](ARCHITECTURE.md)**.

---

## Modules

| Module | Role |
|--------|------|
| **ai-sentinel-core** | Features, statistical and IF scoring, policy, enforcement, pipeline, telemetry contracts |
| **ai-sentinel-spring-boot-starter** | Auto-configuration, servlet filter, `SentinelProperties`, actuator, Micrometer adapter |
| **ai-sentinel-trainer** | Optional app: Kafka consumer for training candidates, IF training, filesystem registry publisher |
| **ai-sentinel-demo** | Reference app (`/api/hello`), actuator, optional traffic simulator |

There is no `ai-sentinel-dashboard` module; use Prometheus, Grafana, or logs for dashboards.

---

## Scripts

Python (stdlib only): **[`scripts/README.md`](scripts/README.md)** (`train_monitor.py`, `traffic_simulator.py`). Typical: run the demo with the **`stage2`** profile, then `python scripts/train_monitor.py`.

---

## Current limitations

- **Filesystem model registry** only (no built-in S3 or Redis artifact store in this repository).
- **Trainer `eventId` dedup** is JVM-local; multiple trainer instances are not coordinated without external design.
- **Multi-JVM validation** for cluster features is not fully automated in CI; many integration tests use one JVM per suite.
- **Registry disk** — no automatic artifact cleanup; operators manage retention.

---

## Security

**[`SECURITY.md`](SECURITY.md)** — reporting and design assumptions.

---

## Contributing

Development uses the **`dev`** branch — see **[`CONTRIBUTING.md`](CONTRIBUTING.md)** for workflow, layout, tests, and PR expectations.

- Match existing style and module boundaries.
- Run **`mvn test`** before submitting.
- Update documentation when behavior or configuration changes.

---

## License

This project is licensed under the **MIT License** — see [`LICENSE`](LICENSE).
