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

Stages **0–4** are implemented in this repository (core engine, Spring Boot integration, Isolation Forest, security/ops hardening, Micrometer/actuator depth). **Pre–Stage 5** fixes (configurable thresholds, safer X-Real-IP, IF-only feature vector) are included. **Stage 5** is **partially** started: **shared quarantine** can use **Redis** for a **read path** (merge cluster view into `isQuarantined`, fail-open) and an optional **write path** (propagate local `QUARANTINE` to Redis for peer nodes, fail-open, non-blocking). **Phase 5.4** adds optional **cluster throttle** for the THROTTLE band only (fixed-window Redis counter per enforcement key, fail-open). Kafka, trainer, model registry, training pipeline, and broader distributed ML lifecycle items are **not** implemented yet. Extended Phase 5 scope and failure-mode notes may be kept locally at **`docs/PHASE5_DISTRIBUTED_DESIGN.md`** (the `docs/` tree is gitignored and is not part of the published repository).

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
| `ai.sentinel.distributed.cluster-throttle-enabled` | `false` | On the **THROTTLE** action path only, consult a Redis fixed-window counter per enforcement key (cluster-wide cap; fail-open if Redis fails; requires `distributed.enabled`, `redis.enabled`, template) |
| `ai.sentinel.distributed.cluster-throttle-window` | `1s` | Wall-clock window length for the cluster throttle counter (validated ≥ 100ms) |
| `ai.sentinel.distributed.cluster-throttle-max-requests-per-window` | `30` | Max requests cluster-wide per enforcement key per window when cluster throttle is enabled (validated ≥ 1) |
| `ai.sentinel.distributed.cluster-throttle-max-in-flight` | `1024` | Per-JVM semaphore cap for concurrent cluster-throttle Redis evals; extra evaluations fail-open (metric `aisentinel.distributed.throttle.executor.rejected`); runtime clamp `[1, 50000]` |
| `ai.sentinel.distributed.cluster-throttle-timeout` | _(unset)_ | Max wait on the throttle Redis future; when unset or non-positive, uses `distributed.redis.lookup-timeout` |
| `ai.sentinel.distributed.training-publish-enabled` | `false` | Phase 5.5: async export of versioned training candidates after enforcement (fail-open; bounded) |
| `ai.sentinel.distributed.training-publish-sample-rate` | `0.1` | Uniform fraction for the probabilistic sample gate (0–1); high composite scores can bypass when stratified sampling is on |
| `ai.sentinel.distributed.training-publish-stratified-sampling` | `true` | When true, composite ≥ `training-publish-high-composite-bypass-sample-min-score` skips the uniform sample draw |
| `ai.sentinel.distributed.training-publish-high-composite-bypass-sample-min-score` | `0.4` | Inclusive floor for bypassing uniform sampling when stratified sampling is enabled (0–1) |
| `ai.sentinel.distributed.training-publish-max-in-flight` | `256` | Semaphore cap for concurrent publish tasks; excess dropped with metric |
| `ai.sentinel.distributed.training-publish-timeout` | `2s` | Max wait on Kafka send completion (`future.get`); validated ≤ 30s; transport clamps to 10s |
| `ai.sentinel.distributed.training-publish-min-composite-score` | `0` | Minimum composite score to export (inclusive) |
| `ai.sentinel.distributed.training-publish-apply-if-anti-poisoning` | `true` | Skip export when IF score &gt; `isolation-forest.training-rejection-score-threshold` (when IF score present) |
| `ai.sentinel.distributed.training-publisher-node-id` | _(empty)_ | Optional instance id in exported events (max length 128) |
| `ai.sentinel.distributed.training-kafka-enabled` | `false` | Use `KafkaTemplate` when present (requires `spring-kafka` + broker config); else JSON log line transport |
| `ai.sentinel.distributed.training-candidates-topic` | `aisentinel.training.candidates` | Kafka topic when Kafka transport is active |
| `ai.sentinel.distributed.enabled` | `false` | Phase 5 master switch for Redis-backed distributed features |
| `ai.sentinel.distributed.redis.enabled` | `false` | Enables Redis-backed beans when `spring-boot-starter-data-redis` and a `StringRedisTemplate` are present |
| `ai.sentinel.distributed.redis.key-prefix` | `aisentinel` | Key prefix for quarantine keys `{prefix}:{tenant}:q:{enforcementKey}` and throttle keys `{prefix}:{tenant}:th:{bucket}:{enforcementKey}` |
| `ai.sentinel.distributed.redis.lookup-timeout` | `50ms` | Max wait on async Redis futures for **cluster quarantine GET** and (when `cluster-throttle-timeout` is unset) **cluster throttle** Lua eval; prefer **Lettuce** as the Redis client and align `spring.data.redis.timeout` with these budgets |
| `ai.sentinel.distributed.redis.max-in-flight-quarantine-writes` | `256` | Semaphore cap for concurrent async cluster quarantine SETs; extra publishes are dropped (metric) without blocking the caller |
| `ai.sentinel.distributed.cache.enabled` | `true` | When `false`, skip the local cache (every lookup hits Redis within `lookup-timeout`) |
| `ai.sentinel.distributed.cache.ttl` / `cache.max-entries` | `2s` / `10000` | Local bounded cache for Redis quarantine lookups |
| `ai.sentinel.distributed.cache.negative-ttl` | _(unset)_ | TTL for negative (miss) cache lines; if unset, derived as `max(100ms, min(positiveTtl/2, 2s))` |

Add `spring-boot-starter-data-redis` and Redis connection settings (`spring.data.redis.*`) when using cluster quarantine read/write and/or cluster throttle. Quarantine write propagation runs **asynchronously** after local quarantine is applied; Redis failures do not roll back local quarantine. Cluster throttle uses a short-budget async Redis **INCR** + **EXPIRE** script; on timeout or error the check **allows** the request (fail-open) and local per-node throttling still applies afterward. The **filter thread** waits up to the configured throttle/quarantine timeout for the Redis future, so **Redis round-trip latency is part of the request-path budget** for that check (async handoff only; no unbounded blocking beyond the timeout).

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
- When Micrometer is present: `scoreSummary`, `latencySummary`, `modelRetrainSuccessCount`, `modelRetrainFailureCount`, `distributedMetrics`, `distributedThrottleMetrics`
- Distributed: `distributedClusterThrottleEnabled`, `clusterThrottleStoreType`, `distributedThrottle` (degraded / last Redis error), throttle window and max-per-window echo fields

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
| 5 | Distributed store, shared quarantine, cluster coordination | **In progress** — Redis quarantine read/write + validation + cluster throttle + **optional training candidate export** (5.5); not Phase-5-complete (no trainer/registry/full Kafka lifecycle) |
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

**Still not implemented:** Kafka training pipeline, trainer service, model registry, automated multi-JVM throttle validation, and other Phase 5 items called out above.

**Optional manual two-instance check:** start Redis (`docker compose up -d` using repo-root `docker-compose.yml` if you use it), run two JVMs (e.g. two terminals with `mvn -pl ai-sentinel-demo spring-boot:run` on different `server.port` values) with the same `spring.data.redis.*` and `ai.sentinel.distributed.*` settings, trigger `QUARANTINE` on instance A, then call an endpoint on instance B with the same identity and confirm cluster quarantine merges into enforcement when read path is enabled.

### Phase 5.4 — Distributed throttling (high-risk / THROTTLE path)

**What it does:** When policy maps a request to **THROTTLE**, `CompositeEnforcementHandler` first consults an optional **Redis fixed-window counter** per tenant + enforcement key (`identity|endpoint` or identity-only per `enforcement-scope`), then applies the existing **local** token bucket. That closes the “rotate across nodes to reset throttle” gap for suspicious traffic **without** turning Redis into a global limiter for all requests.

**What it does not do:** It is **not** cluster rate limiting for ALLOW/MONITOR traffic, **not** applied on BLOCK/QUARANTINE paths, and **not** a precise distributed token-bucket—just a bounded **INCR** per window with TTL.

**Burst / window boundary:** Redis **INCR** is atomic. Under concurrent load, at most **`cluster-throttle-max-requests-per-window`** evaluations can observe a counter value ≤ that cap (cluster **allow**); additional evaluations in the same window observe a higher count and are **cluster-rejected** (`tryAcquire` returns false) unless a fail-open path applies (timeout, Redis error, in-flight cap). Near a window rollover, a short period can admit up to **2× max** cluster-side (last slots of bucket *N* and first slots of bucket *N+1*)—local throttle still applies afterward.

**Semantics:** Fail-open on Redis down/slow/errors, executor/semaphore saturation, or timeout (request continues; local throttle may still apply). The JVM holds a bounded number of in-flight throttle Redis tasks (`cluster-throttle-max-in-flight`); excess attempts fail-open with reason **`inflight_exhausted`**.

**Metrics (Micrometer / Prometheus):**

| Meter | Meaning |
|-------|---------|
| `aisentinel.distributed.throttle.evaluation` | Throttle path invoked cluster check |
| `aisentinel.distributed.throttle.allow` | Counter ≤ cap (cluster allows) |
| `aisentinel.distributed.throttle.reject` | Counter &gt; cap (cluster rejects) |
| `aisentinel.distributed.throttle.redis.timeout` | Future timed out waiting for Redis |
| `aisentinel.distributed.throttle.redis.failure` | Redis/command failure on completed wait |
| `aisentinel.distributed.throttle.executor.rejected` | In-flight semaphore full or executor rejected async work |
| `aisentinel.distributed.throttle.redis.eval` | Timer for eval wall time |

Actuator **`distributedThrottle`** exposes `redisThrottleDegraded` and `lastRedisErrorSummary` with reason prefixes: `redis_timeout`, `redis_failure`, `inflight_exhausted`, `executor_rejected`.

**Still deferred:** Trainer service, model registry, automated multi-JVM throttle validation (starter unit tests cover burst, timeout, recovery, and in-flight saturation).

### Phase 5.5 — Training candidate publishing

**What it does:** After policy and enforcement complete, the pipeline can **offer** a bounded, versioned **`TrainingCandidateRecord`** (schema v2: **`eventId`**, SHA-256 **fingerprints** for endpoint and enforcement-key material, numeric feature snapshots, scores, policy outcome) to an async publisher. **Raw request paths and composite enforcement keys are not exported** (only stable hashes of the same strings used locally). Default transport is a single **JSON log line** at INFO; optional **Kafka** when `spring-kafka` is on the classpath, `KafkaTemplate` exists, and `training-kafka-enabled=true`.

**What it does not do:** No on-request training, no trainer consumer, no model registry, no change to policy or enforcement outcomes.

**Hook:** `SentinelPipeline` calls `TrainingCandidatePublisher.publish` after `enforcementHandler.apply` returns; the request thread runs cheap gates, **copies feature arrays**, computes hashes, builds the record, `Semaphore.tryAcquire`, and schedules work; transport I/O runs on virtual-thread workers.

**Bounded / fail-open:** Score floor and optional IF anti-poisoning run before sampling; **stratified sampling** (default on) lets high composite scores bypass the uniform sample so rare high-risk rows are not starved; in-flight cap with **drop + metric**, executor reject handling, transport errors recorded without affecting the HTTP path.

**Metrics:** `aisentinel.distributed.training.publish.*` (attempt, success, failure, **failure.timeout**, **failure.serialization**, dropped, skipped_sample, skipped_gate, executor_rejected, unexpected_failure), **`aisentinel.distributed.training.publish.transport`** timer; gauge `aisentinel.distributed.training.publish.degraded` when status bean is present. Actuator: `distributedTrainingPublishMetrics`, `trainingPublish`, flags for publish/Kafka/topic.

**Phase 5.6+ (not here):** Hardened Kafka reliability, trainer-side consumption contract, schema registry, replay tooling.

---

## Contributing

- Match existing code style and Maven module boundaries.
- Run **`mvn test`** before submitting changes.
- Prefer factual, concise documentation updates alongside behavior changes.

---

## License

This project is licensed under the **MIT License** — see [`LICENSE`](LICENSE).
