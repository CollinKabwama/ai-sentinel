# AI-Sentinel

**Zero-trust–oriented API defense** — continuous identity-keyed behavioral risk evaluation and adaptive application response. The Java decision core is framework-independent; the primary in-process integration is a **Java 21 Spring Boot/Servlet** library. The same engine can be reached over an authenticated **remote evaluation HTTP API**, with a reference **ASP.NET Core** client under [`dotnet/`](dotnet/).

---

## Overview

AI-Sentinel evaluates each request using privacy-oriented behavioral features (rates, entropy, payload shape, header fingerprints, IP buckets, and related signals). It combines statistical baselines with an optional **Isolation Forest** model, optionally blends **identity trust** with **anomaly risk**, and maps the outcome to actions: allow, monitor, throttle, block, or quarantine.

**How it is packaged today:** the primary integration is an in-process servlet **filter** in the Spring Boot starter. The same behavioral engine can also be exposed as an authenticated **remote evaluation API** (`POST /ai-sentinel/v1/evaluation`) for out-of-process clients. A reference **ASP.NET Core** adapter consumes that API — see [`dotnet/README.md`](dotnet/README.md). There is no separate hosted SaaS scoring service in this repository.

**Problem it addresses:** Static rules and coarse rate limits miss gradual or identity-specific abuse. AI-Sentinel complements authentication and infrastructure controls with **per-identity** behavioral signals and a single, configurable policy surface.

> **Deployment posture:** Prefer **`ai.sentinel.mode=MONITOR`** for initial production adoption. The library default is **`MONITOR`** (observe and learn; no client denial). Explicit **`ai.sentinel.mode=ENFORCE`** is required to enable client-facing denial, and only after application-specific monitoring, tuning, and operational validation. See **[`docs/deployment.md`](docs/deployment.md)**.

---

## Key capabilities

- **Identity-aware security** — Optional integration with Spring Security and HTTP sessions to resolve `IdentityContext` and attach trust metadata to the request.
- **Behavioral trust** — Per-identity baselines and trust scores derived from request history, drift signals, and burst patterns.
- **Anomaly detection** — Statistical baselines plus an optional in-core Isolation Forest model.
- **Risk fusion** — Optional combination of anomaly score and identity trust so policy evaluates a single fused risk scalar.
- **Adaptive enforcement** — Threshold-driven actions (throttle, block, quarantine) with monitor-only mode and startup grace.
- **Distributed state (optional)** — Redis-backed cluster quarantine and throttle, asynchronous **training candidate** export, a standalone **trainer** application, and filesystem **model registry** refresh on serving nodes.
- **Distributed behavioral baselines (optional)** — Redis-backed continuity for per-identity behavioral baselines across instances, with fail-open fallback to in-memory storage when Redis is slow or unavailable.

---

## Architecture (high level)

| Layer | Responsibility |
|-------|------------------|
| **ai-sentinel-core** | Framework-independent **Java** engine (pipeline, decision engine, scoring, policy, enforcement). No Spring, Servlet, or Reactor on the core classpath. |
| **ai-sentinel-spring-boot-starter** | **Current** Spring Boot / Servlet adapter: auto-configuration, `SentinelFilter`, `SentinelProperties`, actuator, Micrometer, optional Redis and Kafka integration |
| **ai-sentinel-trainer** | Optional application: consumes training candidates, trains Isolation Forest models, publishes artifacts to a shared filesystem registry |
| **ai-sentinel-demo** | Reference Spring Boot application and smoke tests |
| **dotnet/** | Reference **ASP.NET Core** adapter (`AI.Sentinel.AspNetCore`) — remote client only; no C# scoring engine. See [`dotnet/README.md`](dotnet/README.md). |

Runtime details, extension points, and distributed components are described in **[`ARCHITECTURE.md`](ARCHITECTURE.md)**.

---

## Cross-platform integration (ASP.NET Core)

Non-Java applications can call the same Step-8/9 evaluation contract over HTTP when the Java service has remote evaluation enabled (`ai.sentinel.evaluation.server.enabled=true`). The [`dotnet/`](dotnet/) tree provides middleware, configuration, tests, and a sample app. **Java remains the authoritative engine**; the .NET library is a thin adapter with fail-open remote failure semantics and MONITOR-first guidance.

---

## How it works (request flow)

```text
Request
  → SentinelFilter (servlet adapter)
  → Identity resolution (optional)
  → Feature extraction
  → SentinelDecisionEngine
      → Behavioral trust (optional)
      → Anomaly scoring (AnomalyScorer)
      → Risk fusion (optional)
      → Policy evaluation (PolicyEngine)
      → Trust-aware policy adjustment (optional)
  → Enforcement
  → Telemetry / metrics
```

**Optional training path** (off the servlet hot path for model refresh): serving nodes may publish `TrainingCandidateRecord` events (log or Kafka) → **trainer** consumes → writes registry artifacts → nodes **poll** and install new Isolation Forest models when configured.

---

## Quickstart

**Prerequisites:** **Java 21** (Maven `<java.version>` and CI Temurin 21 — the supported/tested baseline). Local use of newer JDKs (for example JDK 25) is not a supported build matrix; Mockito/JaCoCo issues have been observed outside JDK 21. Maven 3.8+. Optional: .NET 8 SDK for `dotnet/` tests; Python for `scripts/`.

1. **Build** — `git clone <repository-url> && cd ai-sentinel && mvn clean install`
2. **Demo API** — `mvn -pl ai-sentinel-demo spring-boot:run` → `http://localhost:8080/api/hello` and `http://localhost:8080/actuator/sentinel`
3. **Optional trainer** — With Kafka and candidates flowing: `mvn -pl ai-sentinel-trainer spring-boot:run`, set `aisentinel.trainer.kafka.enabled=true`, and align registry paths with `ai.sentinel.model-registry.filesystem-root`. See [`ai-sentinel-trainer/README.md`](ai-sentinel-trainer/README.md).
4. **Tests** — `mvn test` or `mvn clean verify` from the repo root (preferred so modules resolve from the reactor). Docker is optional: Testcontainers-based distributed quarantine tests are **skipped** when Docker is unavailable (see [`CONTRIBUTING.md`](CONTRIBUTING.md)).

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
    mode: MONITOR   # default; set ENFORCE only after MONITOR validation — see docs/deployment.md
```

Add the starter dependency:

```xml
<dependency>
    <groupId>dev.aisentinel</groupId>
    <artifactId>ai-sentinel-spring-boot-starter</artifactId>
    <version>0.3.0</version>
</dependency>
```

This branch prepares the **0.3.0** library line (first stable baseline). Published Maven Central releases to date include **0.2.0** (tag `v0.2.0`). After `mvn clean install`, consume the tree version `0.3.0` from your local repository.

Upgrade notes from **0.2.0 / unreleased 0.2.1**: [`docs/migration.md`](docs/migration.md). Full history: [`CHANGELOG.md`](CHANGELOG.md).

---

## Deployment modes

**Operating modes (`OFF` / `MONITOR` / `ENFORCE`), MONITOR-first adoption, ENFORCE preconditions, restart/cold-start, and startup grace vs warmup:** **[`docs/deployment.md`](docs/deployment.md)**.

### Local vs distributed topology

### Local (default)

All state is **in-process**: statistical baselines, optional Isolation Forest training buffer, policy thresholds, and local throttle/quarantine maps. No Redis or Kafka is required. This is the right default for single-node applications and most development workflows.

### Distributed (optional)

Enable **`ai.sentinel.distributed.*`** and add **`spring-boot-starter-data-redis`** when you need cluster-wide quarantine visibility, cluster throttle counters, or asynchronous training export. Enable **`ai.sentinel.identity.trust.distributed.enabled`** (with a `StringRedisTemplate` bean) to share **behavioral trust baselines** across horizontal replicas; on Redis timeout or error, the implementation **fails open** to in-memory baseline semantics.

- **Cluster quarantine and throttle** — Redis lookups use bounded waits; local enforcement remains authoritative when Redis is unavailable.
- **Behavioral baselines (Redis)** — Shared across replicas with a short command timeout; failures fall back to local memory. Align `spring.data.redis.timeout` with `ai.sentinel.identity.trust.distributed.command-timeout` (see [`docs/configuration.md`](docs/configuration.md)).
- **Training and model registry** — Bounded, fail-open async publish; trainer writes to a **filesystem** layout that serving nodes poll for new models.

Optional integrations do not change the core policy math unless you turn the corresponding flags on. Testcontainers validation is not production multi-process proof — see [`docs/deployment.md`](docs/deployment.md).

---

## Observability

- **JSON telemetry** — Structured events with configurable verbosity and sampling (`ai.sentinel.telemetry.*`).
- **Micrometer** — Meters prefixed with `aisentinel.*`.
- **`GET /actuator/sentinel`** — Configuration flags, quarantine and throttle summaries, Isolation Forest state, recent score components, and **`lastDecision`** (why the last request on this JVM was acted on: action/band, scores, evaluation phases, IF mode, statistical dominant signal). Intentionally omits identity and request identifiers.

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
| **ai-sentinel-core** | Features, statistical and IF scoring, `SentinelDecisionEngine`, policy, enforcement, pipeline, telemetry contracts |
| **ai-sentinel-spring-boot-starter** | Auto-configuration, servlet filter, `SentinelProperties`, actuator, Micrometer adapter |
| **ai-sentinel-trainer** | Optional app: Kafka consumer for training candidates, IF training, filesystem registry publisher |
| **ai-sentinel-demo** | Reference app (`/api/hello`), actuator, optional traffic simulator |
| **dotnet/** | Reference ASP.NET Core remote adapter — see [`dotnet/README.md`](dotnet/README.md) |

There is no `ai-sentinel-dashboard` module; use Prometheus, Grafana, or logs for dashboards.

---

## Scripts

Python (stdlib only): **[`scripts/README.md`](scripts/README.md)** (`train_monitor.py`, `traffic_simulator.py`). Typical: run the demo with the **`stage2`** profile, then `python scripts/train_monitor.py`.

---

## Current limitations

- **Early release lineage** — 0.2.0 established the published library line; **0.3.0** is the first stable baseline. Treat production adoption as operator-owned after threat-model review (see [`SECURITY.md`](SECURITY.md)). Prefer **`mode=MONITOR`** first; do not claim production-ready ENFORCE from synthetic tests alone.
- **MONITOR default** — Default `ai.sentinel.mode=MONITOR` (observe/learn; no client denial). Explicit `ENFORCE` enables client denial only after ENFORCE preconditions. Full mode matrix, restart behavior, and the availability-first **failure-mode profile**: [`docs/deployment.md`](docs/deployment.md). Statistical warmup is a lifecycle state (`EvaluationStatus.STATISTICAL_WARMUP`), not evidence of abuse; default warmup action is `MONITOR`. Default baseline learning skips `THROTTLE`/`BLOCK`/`QUARANTINE` risk (`ALLOW_OR_MONITOR`).
- **Filesystem model registry** only (no built-in S3 or Redis artifact store in this repository).
- **Trainer `eventId` dedup** is JVM-local; multiple trainer instances are not coordinated without external design.
- **Multi-JVM / Docker validation** — cluster quarantine Testcontainers runs when Docker is available (single-JVM + second Redis client). Multi-process / multi-host proof remains an operator responsibility; see the coverage matrix in [`docs/deployment.md`](docs/deployment.md).
- **Registry disk** — Publishing a new Isolation Forest artifact writes new `{version}.meta.json` / `{version}.payload.bin` files and updates `active.json`. **Prior version files are not deleted automatically.** Operators prune obsolete artifacts after confirming rollback needs; see [`docs/deployment.md`](docs/deployment.md#model-registry-disk-retention).
- **Gated baseline learning** — Default `ALLOW_OR_MONITOR` skips learning on elevated risk actions (protects against baseline poisoning). A **legitimate permanent workload change** can therefore remain elevated relative to the prior baseline until an explicit `BaselineLifecycle.reset` (when `relearn-mode=EXPLICIT_ONLY`) or equivalent operational action. Idle TTL does **not** clear sticky elevation while elevated traffic continues. See [`docs/deployment.md`](docs/deployment.md#legitimate-workload-transitions-and-gated-learning).
- **Isolation Forest** returns one scalar score — no per-feature attribution (SHAP/LIME are out of scope).
- **IF enabled without a loaded model** — fallback score is visible in telemetry/actuator, but the composite blend uses the statistical score only until mode is `MODEL`.
- **Characterization test fidelity** — sudden-step detector scenarios use controlled `RequestFeatures` (not full extractor E2E). Production `requestsPerWindow` is a rolling bucket count that increments per request (~`1, 2, 3, …`), so a synthetic `10 → 100` step cannot be produced through the extractor alone.
- **Custom SPI breaking change** — core SPIs take `HttpRequestView` / `EnforcementResponse` (not servlet types); starter auto-config consumers are unaffected.

---

## Security

**[`SECURITY.md`](SECURITY.md)** — reporting and design assumptions.

---

## Contributing

Development uses the **`dev`** branch — see **[`CONTRIBUTING.md`](CONTRIBUTING.md)** for workflow, layout, tests, and PR expectations.
Please also follow the **[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md)**.

- Match existing style and module boundaries.
- Run **`mvn test`** before submitting.
- Update documentation when behavior or configuration changes.

---

## License

This project is licensed under the **MIT License** — see [`LICENSE`](LICENSE).
