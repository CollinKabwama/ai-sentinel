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

Stages **0–4** are implemented in this repository (core engine, Spring Boot integration, Isolation Forest, security/ops hardening, Micrometer/actuator depth). **Pre–Stage 5** fixes (configurable thresholds, safer X-Real-IP, IF-only feature vector) are included. **Stage 5** is **partially** started: **shared quarantine** can use **Redis** for a **read path** (merge cluster view into `isQuarantined`, fail-open) and an optional **write path** (propagate local `QUARANTINE` to Redis for peer nodes, fail-open, non-blocking). Kafka, trainer, model registry, **distributed throttling**, and other Phase 5 items are **not** implemented yet. Extended Phase 5 scope and failure-mode notes may be kept locally at **`docs/PHASE5_DISTRIBUTED_DESIGN.md`** (the `docs/` tree is gitignored and is not part of the published repository).

Architecture and data flow: [`ARCHITECTURE.md`](ARCHITECTURE.md).

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
| `ai.sentinel.distributed.cluster-quarantine-read-enabled` | `false` | Merge cluster quarantine into `isQuarantined` (local OR Redis view) |
| `ai.sentinel.distributed.cluster-quarantine-write-enabled` | `false` | After local `QUARANTINE`, publish `until` to Redis (requires `distributed.enabled`, `redis.enabled`, template; async, fail-open) |
| `ai.sentinel.distributed.enabled` | `false` | Phase 5 master switch for Redis reader/writer wiring |
| `ai.sentinel.distributed.redis.enabled` | `false` | Use `RedisClusterQuarantineReader` / `RedisClusterQuarantineWriter` when `spring-boot-starter-data-redis` is on the classpath |
| `ai.sentinel.distributed.redis.key-prefix` | `aisentinel` | Redis key prefix: `{prefix}:{tenant}:q:{enforcementKey}` |
| `ai.sentinel.distributed.redis.lookup-timeout` | `50ms` | Max wait on the async future for each Redis GET; set `spring.data.redis.timeout` (Lettuce) to a similar or lower value so client timeouts align with this budget |
| `ai.sentinel.distributed.redis.max-in-flight-quarantine-writes` | `256` | Semaphore cap for concurrent async cluster quarantine SETs; extra publishes are dropped (metric) without blocking the caller |
| `ai.sentinel.distributed.cache.enabled` | `true` | When `false`, skip the local cache (every lookup hits Redis within `lookup-timeout`) |
| `ai.sentinel.distributed.cache.ttl` / `cache.max-entries` | `2s` / `10000` | Local bounded cache for Redis quarantine lookups |
| `ai.sentinel.distributed.cache.negative-ttl` | _(unset)_ | TTL for negative (miss) cache lines; if unset, derived as `max(100ms, min(positiveTtl/2, 2s))` |

Add `spring-boot-starter-data-redis` and Redis connection settings (`spring.data.redis.*`) when using cluster quarantine read and/or write. Write propagation runs **asynchronously** after local quarantine is applied; Redis failures do not roll back local quarantine.

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
- `lastScoreComponents` — snapshot from the **last** scored request: `statistical`, optional `isolationForest`, `composite`, `evaluatedAtMillis` (empty `{}` until traffic hits the filter)
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
| 5 | Distributed store, shared quarantine, cluster coordination | **In progress** — Redis read + optional write propagation; not Phase-5-complete (no Kafka/trainer/registry/throttle) |
| 6 | Research, benchmarks, publications | Not started |

Deferred items and Phase 5 boundaries are summarized in this README; longer design notes may exist only in a local **`docs/`** copy (not versioned here).

### Phase 5.3 — Distributed validation

This milestone is **verification**, not new product features. Automated coverage in `ai-sentinel-spring-boot-starter` includes:

- **Scope (important):** All Phase 5.3 Testcontainers tests run in a **single JVM** and **one Spring `ApplicationContext`**. “Node A” is the wired `CompositeEnforcementHandler` plus the primary `StringRedisTemplate`. “Node B” is modeled with a **second `LettuceConnectionFactory` / `StringRedisTemplate`** to the **same** Redis server, a **new** `RedisClusterQuarantineReader` (separate local cache and `DistributedQuarantineStatus`), and—where enforcement is asserted—a separately built `ClusterAwareEnforcementHandler` + `SentinelPipeline` + `SentinelFilter` over `MockMvc`. There is **no automated two-process / two-JVM** test in CI yet.
- **Distributed enforcement E2E (Docker):** `DistributedQuarantineValidationTest#nodeAWritesQuarantine_nodeBClusterAwareFilterBlocksHttp` — Node A publishes quarantine to Redis; Node B’s pipeline uses `ClusterAwareEnforcementHandler` with **empty local** quarantine maps; an HTTP GET through `SentinelFilter` receives the configured block status (default **429**) and body **Quarantined**, proving cluster state affects **enforcement**, not only `quarantineUntil`.
- **Read-path + metrics (Docker):** `DistributedQuarantineValidationTest#nodeAQuarantineWritesRedis_nodeBReaderSeesClusterQuarantine_separateRedisClient_metricDeltas` — second Redis client + reader; **Micrometer counter deltas** (write attempts/successes, lookups) and `redisWriterDegraded == false` on the shared status bean after a successful publish/read (baselines captured per test).
- **CI / Docker:** Testcontainers Redis tests are **skipped** when Docker is unavailable (`@Testcontainers(disabledWithoutDocker = true)`). To exercise them in CI, run with a Docker-capable agent (or accept skips).
- **Redis unavailable:** `DistributedQuarantineRedisFailureTest` — reader fail-open; writer async failure; local quarantine preserved; unreachable Redis port chosen dynamically via `ServerSocket(0)` (no hardcoded port).
- **Slow Redis vs lookup budget:** **Unit-only:** `RedisClusterQuarantineReaderTest#failOpenOnTimeout` (mocked slow `GET`). There is **no** integration test that delays real Lettuce I/O against Testcontainers Redis; align `spring.data.redis.timeout` with `lookup-timeout` in production (see table above).
- **Dropped writes:** `RedisClusterQuarantineWriterTest#secondPublishDroppedWhileFirstWriteBlocksRedis` plus `DistributedQuarantineDroppedWriteCompositeTest`.
- **Cache staleness:** `DistributedQuarantineValidationTest#cacheServesStalePositiveUntilRedisKeyDeletedThenExpiresAndFailsOpen`.
- **Actuator shape:** `DistributedQuarantineValidationTest#actuatorExposesDistributedFlagsAndMetricSummary`.

**Guarantees reinforced by 5.3:** local quarantine remains authoritative; cluster view is additive; Redis is optional; read and write paths stay fail-open; publish path does not block on Redis I/O; bounded in-flight writes can drop excess work with observability.

**Still not implemented:** distributed throttling, Kafka training pipeline, trainer service, model registry, automated multi-JVM validation, and other Phase 5 items called out above.

**Optional manual two-instance check:** start Redis (`docker compose up -d` using repo-root `docker-compose.yml` if you use it), run two JVMs (e.g. two terminals with `mvn -pl ai-sentinel-demo spring-boot:run` on different `server.port` values) with the same `spring.data.redis.*` and `ai.sentinel.distributed.*` settings, trigger `QUARANTINE` on instance A, then call an endpoint on instance B with the same identity and confirm cluster quarantine merges into enforcement when read path is enabled.

---

## Contributing

- Match existing code style and Maven module boundaries.
- Run **`mvn test`** before submitting changes.
- Prefer factual, concise documentation updates alongside behavior changes.

---

## License

This project is licensed under the **MIT License** — see [`LICENSE`](LICENSE).
