# Configuration reference

Properties use Spring Boot relaxed binding (`ai.sentinel.*`, `aisentinel.trainer.*`). See **`SentinelProperties`** and **`TrainerProperties`** in the codebase for validation rules.

---

## Core (`ai.sentinel.*`)

| Property | Default | Notes |
|----------|---------|--------|
| `ai.sentinel.enabled` | `true` | Master switch |
| `ai.sentinel.mode` | `ENFORCE` | `OFF`, `MONITOR`, `ENFORCE` |
| `ai.sentinel.exclude-paths` | actuator, health, static, favicon | Comma-separated Ant-style patterns |
| `ai.sentinel.trusted-proxies` | _(empty)_ | IPs or CIDRs; when remote matches, client IP from forwarded headers (see [`ARCHITECTURE.md`](../ARCHITECTURE.md) §8) |
| `ai.sentinel.threshold-moderate` … `threshold-critical` | `0.2` … `0.8` | Strictly increasing, in `[0,1]` |
| `ai.sentinel.warmup-min-samples` / `warmup-score` | `2` / `0.4` | Cold-start statistical behavior |
| `ai.sentinel.startup-grace-period` | `0` | Duration (e.g. `5m`) enforcing monitor-only after startup |
| `ai.sentinel.enforcement-scope` | `IDENTITY_ENDPOINT` | Throttle/quarantine key scope |
| `ai.sentinel.isolation-forest.enabled` | `false` | In-core Isolation Forest |
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
| `ai.sentinel.distributed.enabled` | `false` | Master switch for Redis-backed distributed features |
| `ai.sentinel.distributed.redis.enabled` | `false` | Enables Redis-backed beans when `spring-boot-starter-data-redis` and a `StringRedisTemplate` are present |
| `ai.sentinel.distributed.redis.key-prefix` | `aisentinel` | Key prefix for quarantine keys `{prefix}:{tenant}:q:{enforcementKey}` and throttle keys `{prefix}:{tenant}:th:{bucket}:{enforcementKey}` |
| `ai.sentinel.distributed.redis.lookup-timeout` | `50ms` | Max wait on async Redis futures for **cluster quarantine GET** and (when `cluster-throttle-timeout` is unset) **cluster throttle** Lua eval; prefer **Lettuce** and align `spring.data.redis.timeout` with these budgets |
| `ai.sentinel.distributed.redis.max-in-flight-quarantine-writes` | `256` | Semaphore cap for concurrent async cluster quarantine SETs; extra publishes are dropped (metric) without blocking the caller |
| `ai.sentinel.distributed.cache.enabled` | `true` | When `false`, skip the local cache (every lookup hits Redis within `lookup-timeout`) |
| `ai.sentinel.distributed.cache.ttl` / `cache.max-entries` | `2s` / `10000` | Local bounded cache for Redis quarantine lookups |
| `ai.sentinel.distributed.cache.negative-ttl` | _(unset)_ | TTL for negative (miss) cache lines; if unset, derived as `max(100ms, min(positiveTtl/2, 2s))` |
| `ai.sentinel.model-registry.refresh-enabled` | `false` | Phase 5.6: background poll filesystem registry for newer IF artifacts (requires IF enabled + non-blank `filesystem-root`) |
| `ai.sentinel.model-registry.filesystem-root` | _(empty)_ | Registry root shared with `ai-sentinel-trainer` output (`{root}/{tenant}/active.json` + `artifacts/`) |
| `ai.sentinel.model-registry.poll-interval` | `5m` | Poll interval (validated 10s–24h) |

### Redis and request-path budget

Add `spring-boot-starter-data-redis` and `spring.data.redis.*` when using cluster quarantine read/write and/or cluster throttle. Quarantine write propagation runs **asynchronously** after local quarantine is applied; Redis failures do not roll back local quarantine. Cluster throttle uses a short-budget async Redis **INCR** + **EXPIRE** script; on timeout or error the check **allows** the request (fail-open) and local per-node throttling still applies afterward. The **filter thread** waits up to the configured throttle/quarantine timeout for the Redis future, so **Redis round-trip latency is part of the request-path budget** for that check.

### Isolation Forest (demo)

Use the bundled **`stage2`** profile for faster local training:

```bash
mvn -pl ai-sentinel-demo spring-boot:run -Dspring-boot.run.profiles=stage2
```

Config: `ai-sentinel-demo/src/main/resources/application-stage2.yaml`.

---

## Trainer (`aisentinel.trainer.*`)

See [`ai-sentinel-trainer/README.md`](../ai-sentinel-trainer/README.md) for the full table and run instructions.
