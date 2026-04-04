# Phase 5 — Distributed AI-Sentinel: Design & Implementation Plan

**Status:** Architecture + scaffolding; **Redis-backed cluster quarantine read path** is implemented in the starter (optional `spring-boot-starter-data-redis`, feature-flagged). Kafka, trainer, model registry, and distributed quarantine **writes** are **not** implemented yet.  
**Not in scope for this document:** Trainer service binary, Kafka producer wiring, model registry client.

This document is grounded in the **current** codebase: `CompositeEnforcementHandler` (local `ConcurrentHashMap` quarantine/throttle), `SentinelPipeline` (local scoring + policy + `isQuarantined` then `apply`), `IsolationForestScorer` + `BoundedTrainingBuffer` (local training), `SentinelProperties`, Micrometer `aisentinel.*`, `/actuator/sentinel`.

---

## 1. Phase 5 readiness assessment

### 1.1 Components Phase 5 should extend

| Component | Location | Role today | Extension for Phase 5 |
|-----------|----------|------------|------------------------|
| `EnforcementHandler` | core | `apply`, `isQuarantined` | **Decorate** with cluster quarantine read (and later write/async publish). |
| `CompositeEnforcementHandler` | core | Local throttle + quarantine maps | Remains **source of truth for local** apply path; cluster **OR**’d into `isQuarantined`. |
| `SentinelPipeline` | core | Policy → quarantine shortcut | Unchanged contract; optional **training publish** hook after `scorer.update` (async, fail-open). |
| `IsolationForestScorer` | core | Local buffer + retrain | Add **optional** `TrainingCandidatePublisher` for off-node aggregation (Kafka). |
| `CompositeScorer` | core | Local blend + snapshot | Unchanged; model **version** exposed via actuator (already has last components). |
| `SentinelProperties` | starter | `ai.sentinel.*` | Nested **`distributed`** section (feature flags, tenant id, timeouts). |
| `SentinelActuatorEndpoint` | starter | JSON ops view | Add **distributed health**, **model pointer version**, **degraded mode** flags. |
| `SentinelMetrics` / Micrometer | starter | Counters/timers | Add **distributed** meters (Redis timeout, Kafka drop, cluster quarantine hits). |

### 1.2 Extension points already available

- `@ConditionalOnMissingBean` on most pipeline beans — users can substitute `EnforcementHandler`, `FeatureExtractor`, `PolicyEngine`, `SentinelMetrics`.
- `EnforcementHandler.isQuarantined(identityHash, endpoint)` — single place to **merge** local + distributed quarantine without touching policy math.
- `TelemetryEmitter` — can emit **audit** events for distributed actions (with PII-safe hashes).

### 1.3 Assumptions that are single-node only

- **Quarantine / throttle keys** live only in JVM memory; another node does not see them.
- **IF training buffer** is process-local; models **diverge** per node without coordination.
- **BaselineStore** / **StatisticalScorer** state is local (by design — scoring stays local).
- **Identity** is a hash string — **no built-in tenant** in keys today; Phase 5 adds **tenant prefix** in Redis/Kafka keys.

### 1.4 Risks for distributed evolution

| Risk | Mitigation |
|------|------------|
| Sync Redis on every request | **Never** on hot path by default; **cache + optional** check for high-risk only; OR merge only on `isQuarantined` with **short timeout** + fail-open. |
| Training poisoning at scale | Central **admission** + same **rejection score** semantics; **signed** model artifacts. |
| Split-brain quarantine | **TTL everywhere**; accept **temporary** inconsistency; document **eventual** alignment. |
| Config explosion | Single **`ai.sentinel.distributed.*`** tree; **disabled by default**. |

---

## 2. Distributed architecture design

### 2.1 Principles (locked)

1. **Scoring local** — statistical + IF inference stay in-process; **no** centralized inference service.
2. **Policy local** — `ThresholdPolicyEngine` unchanged per node (per-tenant config can differ via config server later).
3. **Enforcement: local fast path + selective global** — throttle stays **local** initially; **distributed quarantine** merged via `isQuarantined`; distributed throttle **only** for flagged identities (Phase 5b).
4. **Training path off-request** — Kafka (or similar) **async** publish of candidates; **trainer** consumes, builds model, publishes **artifact** to registry/blob; nodes **pull** new version.

### 2.2 Logical components

```
┌─────────────────────────────────────────────────────────────────┐
│ App instance (×N)                                                │
│  SentinelFilter → SentinelPipeline                               │
│    FeatureExtractor, CompositeScorer (local)                     │
│    PolicyEngine (local)                                          │
│    EnforcementHandler = ClusterAware → Composite (local maps)    │
│      isQuarantined: local OR Redis view (cached, timeout-bound)   │
│    optional: TrainingCandidatePublisher → Kafka (async)         │
│    optional: ModelVersionPoller → registry → IF hot-swap         │
└───────────────┬───────────────────────────────┬──────────────────┘
                │                               │
         Redis    │  (quarantine, metadata,      │ Kafka
         cluster  │   overrides TTL)              │ (training candidates,
                │                               │  security events)
                ▼                               ▼
        ┌───────────────┐                 ┌──────────────┐
        │ Control data  │                 │ Trainer svc  │
        │ (not scores)  │                 │ + admission  │
        └───────────────┘                 └──────┬───────┘
                                               │ model artifact
                                               ▼
                                        ┌──────────────┐
                                        │ Registry /   │
                                        │ object store │
                                        └──────────────┘
```

### 2.3 Redis usage (control plane / shared state)

| Key pattern (example) | Content | TTL |
|----------------------|---------|-----|
| `aisentinel:{tenant}:q:{enforcementKey}` | quarantine **until** epoch ms | matches policy duration + skew buffer |
| `aisentinel:{tenant}:meta:model` | **pointer** to active model version id + checksum | short (refresh from registry) |
| `aisentinel:{tenant}:override:mode` | emergency MONITOR / ENFORCE | short TTL + source tag |

**Not** in Redis: raw request bodies, full feature vectors at scale (only **bounded** summaries in Kafka events).

### 2.4 Kafka usage (data plane for training)

- **Topic:** `aisentinel.training.candidates` (partition by `tenantId` + hash of identity for ordering where needed).
- **Payload:** `TrainingCandidateRecord` (versioned schema): tenant, node id, identity hash, normalized endpoint, **IF vector** (5 doubles), composite score snapshot, timestamp, optional admission flags.
- **Producer:** async, bounded queue drop-oldest or sample on overload; **never** block filter thread > sub-ms handoff.

### 2.5 Trainer service (async)

- Consumes candidates → **windowed** buffer in trainer → admission rules (same spirit as `trainingRejectionScoreThreshold`) → trains IF → uploads artifact → **registers** version in model registry + updates Redis pointer.

### 2.6 Model consistency

- **Artifact:** `{ versionId, sha256, uri, trainedAt, schemaVersion }`.
- **Nodes:** poll registry or subscribe to pointer changes; **atomic swap** in `IsolationForestScorer` (already volatile model pattern).
- **Canary (later):** percentage rollout via config flag per node group.

---

## 3. Component responsibilities

| Component | Owns |
|-----------|------|
| **App instance** | Local scoring, policy, apply throttle/block/quarantine locally, publish training candidates (async), load models, expose metrics/actuator. |
| **Redis** | Durable quarantine visibility, optional throttle for high-risk keys, config/mode overrides, **model pointer** cache. |
| **Kafka** | Durable **append-only** training stream; replay for evaluation. |
| **Trainer** | Consume, admit, train, sign/publish artifacts, bump registry version. |
| **Model registry** | Source of truth for **which artifact is current**; checksum verification. |
| **Admin / control plane** (future) | Manual quarantine release, global monitor-only, per-route overrides, audit. |

---

## 4. Data models / event schemas

### 4.1 `TrainingCandidateRecord` (v1 draft — see Java record in code)

- `schemaVersion` (int)
- `tenantId` (String)
- `nodeId` (String)
- `identityHash` (String)
- `endpoint` (String, normalized)
- `isolationForestFeatures` (double[5])
- `compositeScore` (double)
- `capturedAtEpochMillis` (long)
- Optional: `statisticalScore`, `policyBand` for audit

**Size bound:** fixed array length; reject oversize strings at publish boundary.

### 4.2 Quarantine Redis value

- **Key (implemented):** `{keyPrefix}:{tenant}:q:{enforcementKey}` with `keyPrefix` from `ai.sentinel.distributed.redis.key-prefix` (default `aisentinel`), `tenant` from `ai.sentinel.distributed.tenant-id` (or the tenant argument passed to `ClusterQuarantineReader`), and `enforcementKey` matching `ClusterAwareEnforcementHandler` / local `CompositeEnforcementHandler` (identity or `identity|endpoint`).
- **Value:** `untilEpochMillis` (long) as decimal string; writers should set a Redis **TTL** on the key so keys expire (read path tolerates missing keys).

### 4.3 Model metadata

- `ModelArtifactMetadata`: `versionId`, `sha256`, `uri`, `createdAtEpochMillis`, `schemaVersion`.

---

## 5. Failure-mode matrix

| Dependency | Symptom | Request path behavior | Training path | Alert |
|------------|---------|----------------------|---------------|-------|
| Redis down | Timeout / connection refused | **Fail-open:** `isQuarantined` ignores cluster (local only) | N/A | Yes — `aisentinel.distributed.redis.unavailable` |
| Kafka down | Publish fails | **Drop** candidate (counter++) | Retry buffer bounded or drop | Yes |
| Trainer down | No new models | Nodes keep **last** model | Stale model age in actuator | Yes |
| Registry down | Cannot fetch artifact | **Keep** current local model | — | Yes |
| Partial rollout | Nodes on different versions | Expected; document **skew** in actuator | — | Info |
| Network partition | Split views | Quarantine may **diverge** until heal; TTL limits damage | — | Optional |

**Invariant:** HTTP handling **never** throws due to Phase 5 dependencies; worst case = **more permissive** (fail-open), not crash.

---

## 6. Request-path performance strategy

- **No** Redis on every request by default.
- **Phase 5a:** `ClusterQuarantineReader` called from `isQuarantined` with **in-memory cache** (per-key TTL, max entries) + **async refresh** optional.
- **Phase 5b:** For **high composite score** (e.g. > 0.7), optionally **force** synchronous Redis read with **hard timeout** (e.g. 2–5 ms) — configurable.
- **Kafka:** enqueue in **memory bounded queue**; separate thread batch-sends.

### 6.1 Timeouts, Redis client, and clock skew

- **`ai.sentinel.distributed.cache.enabled`:** When `true` (default), `RedisClusterQuarantineReader` uses the bounded local cache (`cache.ttl`, `cache.max-entries`, optional `cache.negative-ttl`). When `false`, the cache is not used: each `isQuarantined` check performs a fresh Redis GET within `lookup-timeout` (more Redis load; same fail-open semantics).
- **`ai.sentinel.distributed.cache.negative-ttl`:** Optional. TTL for **negative** (miss / not-quarantined) cache entries. If unset, it is derived as `max(100ms, min(positiveTtl/2, 2s))` from the positive `cache.ttl`.
- **`ai.sentinel.distributed.redis.lookup-timeout`:** Maximum time the app waits on the async future wrapping `StringRedisTemplate.opsForValue().get(...)`. When this elapses, the request path **fail-opens** (treats cluster quarantine as absent for that check).
- **`spring.data.redis.timeout` (Lettuce command timeout):** Should be set **in line with** (typically ≤) `lookup-timeout` so the client does not keep work alive much longer than the future wait. The future timeout does **not** cancel in-flight Lettuce I/O; the client timeout is the practical bound on how long a stuck Redis call can run.
- **Executor lifecycle:** `RedisClusterQuarantineReader` uses a **per-bean** virtual-thread executor and shuts it down on context destroy (`DisposableBean`) so tests and restarts do not leak threads.
- **Clock skew:** Quarantine keys store **until** as epoch millis. Each node compares with **local** `System.currentTimeMillis()`. Small skew between Redis writers and readers can make a key appear to expire slightly early/late; use reasonable TTL on keys and treat borderline times as operational tolerance.

---

## 7. Step-by-step implementation plan

| Step | Objective | Modules / files | New types | Risks | Tests | Done when |
|------|-----------|-----------------|-----------|-------|-------|-----------|
| **5.0** | Scaffolding | `ai-sentinel-core/.../distributed/**`, `SentinelProperties` | `ClusterQuarantineReader`, `Noop*`, `TrainingCandidate*`, `ClusterAwareEnforcementHandler` | API churn | Unit tests for wrapper | Interfaces + noop + wrapper + properties merged |
| **5.1** | Wire optional wrapper | `SentinelAutoConfiguration` | Bean `ClusterQuarantineReader` + conditional wrap | Behavior change | Integration test with noop | **Done:** noop + `ClusterAwareEnforcementHandler` when `cluster-quarantine-read-enabled` |
| **5.2** | Redis reader | Optional `spring-boot-starter-data-redis` on starter | `RedisClusterQuarantineReader`, bounded cache, metrics | Latency | Unit + slice tests (mock Redis ops) | **Done:** read path + fail-open; cross-node needs Redis + writer elsewhere |
| **5.3** | Redis writer + publish on quarantine | `CompositeEnforcementHandler` hook or listener | `ClusterQuarantineWriter` | Double-write | Contract tests | Global quarantine on apply |
| **5.4** | Kafka publisher | `spring-kafka` optional | `KafkaTrainingCandidatePublisher` | Backpressure | Mock broker test | Events in topic |
| **5.5** | Trainer skeleton | Separate repo/module `aisentinel-trainer` | Consumer, admission, train | Ops | CI job | End-to-end one model version |
| **5.6** | Model registry + poller | starter | `ModelRegistryClient`, poller thread | Swap safety | Version actuator | Rollback documented |
| **5.7** | Actuator + metrics | `SentinelActuatorEndpoint`, metrics | Degraded flags | Cardinality | JSON snapshot | SRE dashboard ready |

---

## 8. Risks and recommended sequencing

1. **Do distributed quarantine read path before write replication** — fewer moving parts; validate Redis SLOs.
2. **Do Kafka training pipeline before** trying to share Redis buffers.
3. **Keep trainer out of app JAR** — separate deployable.
4. **Multi-tenancy:** prefix **all** keys and Kafka headers with `tenantId` from day one (default `default`).

---

## 9. Multi-tenancy (readiness)

- **Keys:** `aisentinel:{tenant}:...` everywhere in Redis/Kafka.
- **Config:** `ai.sentinel.distributed.tenant-id` (default single tenant).
- **Models:** default shared per tenant; optional **per-tenant model id** in registry path later.

---

## 10. Admin & operational controls (requirements)

- **Emergency MONITOR:** Redis key or config refresh (already have startup grace — extend **global** override).
- **Manual quarantine release:** delete Redis key + optional local eviction API (secured).
- **Visibility:** actuator lists **degraded**, **cluster quarantine cache age**, **model version**, **Kafka lag** (trainer side).

---

*Document version: 1.0 — aligned with repository scaffolding package `io.aisentinel.distributed`.*
