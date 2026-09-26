# AI-Sentinel

**Zero Trust–aligned behavioral-risk and adaptive application-security tooling** for HTTP/API workloads. It continuously evaluates post-authentication behavior and enables risk-informed application responses as that behavior changes. It is designed to **complement** identity, authorization, device, network, and monitoring controls — not to replace them, and not as a complete Zero Trust architecture or certification claim.

The Java decision core is framework-independent; the primary in-process integration is a **Java 21 Spring Boot/Servlet** library. The same engine can be reached over an authenticated **remote evaluation HTTP API**, with a reference **ASP.NET Core** client under [`dotnet/`](dotnet/).

---

## Overview

AI-Sentinel evaluates each request using privacy-oriented behavioral features (rates, entropy, payload shape, header fingerprints, IP buckets, and related signals). It combines statistical baselines with an optional **Isolation Forest** model, optionally blends **identity trust** with **anomaly risk**, and maps the outcome to actions: allow, monitor, throttle, block, or quarantine.

**How it is packaged today:** the primary integration is an in-process servlet **filter** in the Spring Boot starter. The same behavioral engine can also be exposed as an authenticated **remote evaluation API** (`POST /ai-sentinel/v1/evaluation`) for out-of-process clients. A reference **ASP.NET Core** adapter consumes that API — see [`dotnet/README.md`](dotnet/README.md). There is no separate hosted SaaS scoring service in this repository.

**Problem it addresses:** Static rules and coarse rate limits miss gradual or identity-specific abuse. AI-Sentinel complements authentication and infrastructure controls with **per-identity** behavioral signals and a single, configurable policy surface.

> **Deployment posture:** Prefer **`ai.sentinel.mode=MONITOR`** for initial production adoption. The library default is **`MONITOR`** (observe and learn; no client denial). Explicit **`ai.sentinel.mode=ENFORCE`** is required to enable client-facing denial, and only after application-specific monitoring, tuning, and operational validation. See **[`docs/deployment.md`](docs/deployment.md)**.

---

## Key capabilities

### Runtime

- **Identity-aware security** — Optional integration with Spring Security and HTTP sessions to resolve `IdentityContext` and attach trust metadata to the request.
- **Behavioral trust** — Per-identity baselines and trust scores derived from request history, drift signals, and burst patterns.
- **Anomaly detection** — Statistical baselines plus an optional in-core Isolation Forest model.
- **Risk fusion** — Optional combination of anomaly score and identity trust so policy evaluates a single fused risk scalar.
- **Adaptive enforcement** — Threshold-driven actions (throttle, block, quarantine) with monitor-only mode and startup grace.
- **Distributed state (optional)** — Redis-backed cluster quarantine and throttle, asynchronous **training candidate** export, a standalone **trainer** application, and filesystem **model registry** refresh on serving nodes.
- **Distributed behavioral baselines (optional)** — Redis-backed continuity for per-identity behavioral baselines across instances, with fail-open fallback to in-memory storage when Redis is slow or unavailable.

### Offline Detection Evaluation Framework

Engineering evidence machinery (not part of the request path):

- privacy-safe reference corpus and independent annotations
- deterministic replay
- truth/replay alignment
- explicit detector classification (caller-supplied threshold)
- aggregate and scenario metrics
- temporal anomaly evaluation (detection delay, recovery/stabilization semantics)
- deterministic `evaluation.json` / `evaluation.md` evidence
- reusable `DetectionEvaluationRunner` orchestration

Details: [`evaluation/DETECTION_EVALUATION.md`](evaluation/DETECTION_EVALUATION.md).

`FRAMEWORK ACCEPTANCE != DETECTION QUALITY ACCEPTANCE` · `REPORT != BASELINE` · `REFERENCE DATASET != DETECTION BASELINE`

### Candidate scorer lifecycle (engineering capability in 0.4.0)

`ai-sentinel-core` includes an offline/opt-in candidate-model path packaged in **0.4.0**. It is **not** required for ordinary starter usage and does **not** rewire the authoritative runtime scorer. Steps below are **caller-driven** (not automatic transitions):

1. Validate artifact descriptor / integrity metadata
2. Verify and load candidate bytes (bounded; supported Isolation Forest/`aif1`)
3. Replay and evaluate against the reference corpus
4. Optionally assess engineering **acceptance**
5. Optionally enable **shadow** scoring (observational; identity-bound; default OFF)
6. Optionally designate challenger / approve / promote a **lifecycle champion** designation

`VALID ARTIFACT != GOOD MODEL` · `EVALUATION COMPLETED != ACCEPTED` · `ACCEPTANCE != AUTOMATIC SHADOW ENABLEMENT` · `PROMOTED != PRODUCTION DEPLOYED`

Contracts: [`docs/contracts/SCORER_ARTIFACT.md`](docs/contracts/SCORER_ARTIFACT.md) · [`SCORER_CANDIDATE_LOADING.md`](docs/contracts/SCORER_CANDIDATE_LOADING.md) · [`SCORER_CANDIDATE_EVALUATION.md`](docs/contracts/SCORER_CANDIDATE_EVALUATION.md) · [`SCORER_CANDIDATE_SHADOW.md`](docs/contracts/SCORER_CANDIDATE_SHADOW.md) · [`SCORER_MODEL_LIFECYCLE.md`](docs/contracts/SCORER_MODEL_LIFECYCLE.md).

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
      → Authoritative anomaly scoring (AnomalyScorer)
      → Risk fusion (optional)
      → Policy evaluation (PolicyEngine)
      → Trust-aware policy adjustment (optional)
  → Enforcement response
  → Telemetry / metrics
```

**Authoritative path:** `AUTHORITATIVE RUNTIME SCORER → PRODUCTION DECISION → POLICY → ENFORCEMENT`.

**Optional observational path** (explicitly enabled only): same request features → accepted candidate shadow scorer → observational evidence (`SHADOW RESULT != PRODUCTION DECISION`; default OFF).

**Optional offline governance path** (not wired into starter auto-configuration): validated candidate artifact → load/evaluate/accept → explicit challenger designation → approval → lifecycle promotion of a champion designation only (`PROMOTED LIFECYCLE CHAMPION != RUNNING PRODUCTION SCORER`).

**Optional training path** (off the servlet hot path for model refresh): serving nodes may publish `TrainingCandidateRecord` events (log or Kafka) → **trainer** consumes → writes registry artifacts → nodes **poll** and install new Isolation Forest models when configured. Do not confuse filesystem model-registry refresh with candidate lifecycle promotion.

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

Add the starter dependency (current release **0.4.0**):

```xml
<dependency>
    <groupId>dev.aisentinel</groupId>
    <artifactId>ai-sentinel-spring-boot-starter</artifactId>
    <version>0.4.0</version>
</dependency>
```

**0.4.0** is the current published release ([GitHub Release](https://github.com/CollinKabwama/ai-sentinel/releases/tag/v0.4.0), [Maven Central](https://central.sonatype.com/artifact/dev.aisentinel/ai-sentinel-spring-boot-starter/0.4.0)). It packages the candidate-integration, observational shadow, and lifecycle-governance engineering capabilities described above. Prefer **`mode=MONITOR`** for initial adoption. Published Central coordinates: `dev.aisentinel:ai-sentinel`, `dev.aisentinel:ai-sentinel-core`, and `dev.aisentinel:ai-sentinel-spring-boot-starter` at **0.4.0**. Previous published line: **0.3.0** ([tag `v0.3.0`](https://github.com/CollinKabwama/ai-sentinel/releases/tag/v0.3.0)).

Upgrade notes: [`docs/migration.md`](docs/migration.md). Full history: [`CHANGELOG.md`](CHANGELOG.md).

---

## Deployment modes

**Operating modes (`OFF` / `MONITOR` / `ENFORCE`), MONITOR-first adoption, ENFORCE preconditions, restart/cold-start, and startup grace vs warmup:** **[`docs/deployment.md`](docs/deployment.md)**.

### Local vs distributed topology

### Local (default)

All state is **in-process**: statistical baselines, optional Isolation Forest training buffer, policy thresholds, and local throttle/quarantine maps. No Redis or Kafka is required. This is the right default for single-node applications and most development workflows.

### Distributed (optional)

Enable **`ai.sentinel.distributed.*`** and add **`spring-boot-starter-data-redis`** when you need cluster-wide quarantine visibility, cluster throttle counters, or asynchronous training export. Enable **`ai.sentinel.identity.trust.distributed.enabled`** (with a `StringRedisTemplate` bean) to share **behavioral trust baselines** across horizontal replicas; on Redis timeout or error, the implementation **fails open** to in-memory baseline semantics (per-instance; not auto-reconciled after Redis recovers — see [`docs/deployment.md`](docs/deployment.md#distributed-deployment-notes)).

- **Cluster quarantine and throttle** — Redis lookups use bounded waits; local enforcement remains authoritative when Redis is unavailable.
- **Behavioral baselines (Redis)** — Shared across replicas with a short command timeout; failures fall back to local memory. Align `spring.data.redis.timeout` with `ai.sentinel.identity.trust.distributed.command-timeout` (see [`docs/configuration.md`](docs/configuration.md)).
- **Training and model registry** — Bounded, fail-open async publish; trainer writes to a **filesystem** layout that serving nodes poll for new models.

Optional integrations do not change the core policy math unless you turn the corresponding flags on. Repository Redis tests exercise **same-version** multi-client consistency; they are not production multi-process / Cluster / rolling-deploy proof — see [`docs/deployment.md`](docs/deployment.md#distributed-deployment-notes).

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

Module roles match the architecture table above. Details and extension points: **[`ARCHITECTURE.md`](ARCHITECTURE.md)**. There is no `ai-sentinel-dashboard` module; use Prometheus, Grafana, or logs for dashboards.

---

## Scripts

Python (stdlib only): **[`scripts/README.md`](scripts/README.md)** (`train_monitor.py`, `traffic_simulator.py`). Typical: run the demo with the **`stage2`** profile, then `python scripts/train_monitor.py`.

---

## Offline detection evaluation

Offline Detection Evaluation Framework, Official Detection Reference Baseline, Level-1 Kit reproduction, Level-3 same-framework comparison, and Northgate synthetic evaluation are documented under [`evaluation/`](evaluation/) and [`docs/evaluation/`](docs/evaluation/). These are **repository-controlled evidence** workflows — not production efficacy, not ENFORCE readiness, and not a completed external pilot.

Entry points:

- [`evaluation/DETECTION_EVALUATION.md`](evaluation/DETECTION_EVALUATION.md)
- [`evaluation/DETECTION_REFERENCE_BASELINE.md`](evaluation/DETECTION_REFERENCE_BASELINE.md)
- [`docs/evaluation/INDEPENDENT_REPRODUCTION.md`](docs/evaluation/INDEPENDENT_REPRODUCTION.md)
- [`docs/evaluation/EXTERNAL_MONITOR_EVALUATION.md`](docs/evaluation/EXTERNAL_MONITOR_EVALUATION.md) (handoff docs ≠ an external run; Central `0.4.0` predates MONITOR pilot tooling on `dev`)

`FRAMEWORK ACCEPTANCE != DETECTION QUALITY ACCEPTANCE` · `BASELINE != QUALITY GATE` · `Evaluation != Deployment` · `Synthetic != Production validation`

Candidate shadow / lifecycle packaging in **0.4.0** remains designation/observational only (`SHADOW RESULT != PRODUCTION DECISION`, `PROMOTED != PRODUCTION DEPLOYED`).

---

## Current limitations

- **Production efficacy NOT ESTABLISHED** — Prefer `MONITOR`; do not claim production-ready ENFORCE from synthetic tests alone ([`docs/deployment.md`](docs/deployment.md)).
- **Candidate / lifecycle boundaries** — Validated or lifecycle-promoted candidates do not become the running production scorer (`PROMOTED LIFECYCLE CHAMPION != RUNNING PRODUCTION SCORER`). Operational pilot evidence remains unresolved (`PILOT_EVIDENCE_REQUIRED`); MONITOR readiness ≠ completed pilot.
- **Official Detection Reference Baseline** — Capture/verify/lifecycle are complete under [`evaluation/DETECTION_REFERENCE_BASELINE.md`](evaluation/DETECTION_REFERENCE_BASELINE.md). Drift means difference, not detector-quality acceptance.
- **Stable software baseline** — **0.4.0** is the current published release. Historical performance and Official Detection Reference Baseline evidence remain associated with the **0.3.0-era** artifacts unless an artifact explicitly states otherwise (`Historical performance evidence != current production performance`).
- **MONITOR default** — Observe/learn; no client denial. Full mode matrix and availability-first failure profile: [`docs/deployment.md`](docs/deployment.md). `REMOTE_EVALUATION_FAILURE` fail-open proceed ≠ trusted engine ALLOW ([`SECURITY.md`](SECURITY.md), [`dotnet/README.md`](dotnet/README.md)).
- **Distributed Redis** — Optional shared quarantine/throttle/trust baselines. Repository tests exercise **same-version** multi-client consistency against one Redis backend; that is **not** Redis Cluster, multi-host, rolling-deploy, or mixed-version proof. Local trust fallback during Redis outage is per-instance and is not auto-reconciled after recovery — [`docs/deployment.md`](docs/deployment.md#distributed-deployment-notes).
- **Gated baseline learning** — Default `ALLOW_OR_MONITOR` skips learning on elevated risk actions. Legitimate permanent workload changes may stay elevated until an explicit baseline reset — [`docs/deployment.md`](docs/deployment.md#legitimate-workload-transitions-and-gated-learning).
- **Filesystem model registry** only (no built-in S3/Redis artifact store). Prior version files are not deleted automatically ([`docs/deployment.md`](docs/deployment.md#model-registry-disk-retention)).
- **Trainer `eventId` dedup** is JVM-local.
- **Isolation Forest** returns one scalar score (no SHAP/LIME). Without a loaded model, composite uses the statistical score until mode is `MODEL`.
- **Shadow scoring** (when enabled) is synchronous in-process, not an async shadow platform.
- **Performance vs detection** — JMH/resource baselines measure cost, not detection effectiveness. See [`docs/performance/REFERENCE_BASELINE.md`](docs/performance/REFERENCE_BASELINE.md).
- **Repository security hardening ≠ production security certification** — [`SECURITY.md`](SECURITY.md).

---

## Where to read more

| Topic | Doc |
|-------|-----|
| Architecture / adapters | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Configuration | [`docs/configuration.md`](docs/configuration.md) |
| Deployment / fail-open | [`docs/deployment.md`](docs/deployment.md) |
| Security | [`SECURITY.md`](SECURITY.md) |
| Testing / release gates | [`docs/testing.md`](docs/testing.md) |
| Migration | [`docs/migration.md`](docs/migration.md) |
| Docs index | [`docs/README.md`](docs/README.md) |
| .NET remote client | [`dotnet/README.md`](dotnet/README.md) |

---

## Security

**[`SECURITY.md`](SECURITY.md)** — reporting and design assumptions.

---

## Contributing

Development uses the **`dev`** branch — see **[`CONTRIBUTING.md`](CONTRIBUTING.md)** for workflow, layout, tests, and PR expectations.
Please also follow the **[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md)**.

- Match existing style and module boundaries.
- Run **`mvn test`** (or **`mvn clean verify`** before release) before submitting.
- Update documentation when behavior or configuration changes.

---

## License

This project is licensed under the **MIT License** — see [`LICENSE`](LICENSE).
