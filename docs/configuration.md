# Configuration reference

Properties use Spring Boot relaxed binding (`ai.sentinel.*`, `aisentinel.trainer.*`). See **`SentinelProperties`** and **`TrainerProperties`** in the codebase for validation rules.

---

## Core (`ai.sentinel.*`)

| Property | Default | Notes |
|----------|---------|--------|
| `ai.sentinel.enabled` | `true` | Master switch |
| `ai.sentinel.mode` | `ENFORCE` | `OFF`, `MONITOR`, `ENFORCE` |
| `ai.sentinel.exclude-paths` | actuator, health, static, favicon | Comma-separated Ant-style patterns |
| `ai.sentinel.block-status-code` | `429` | HTTP status written on BLOCK / throttle-exhaust / quarantine responses |
| `ai.sentinel.quarantine-duration-ms` | `300000` | Local quarantine TTL in milliseconds |
| `ai.sentinel.throttle-requests-per-second` | `5.0` | Local token-bucket style throttle rate |
| `ai.sentinel.baseline-ttl` / `baseline-max-keys` | `5m` / `100000` | Shared lifetime for the rolling `BaselineStore` request window **and** `StatisticalScorer` Welford state (idle keys expire on access paths) |
| `ai.sentinel.internal-map-max-keys` / `internal-map-ttl` | `100000` / `5m` | Local endpoint-history / throttle / quarantine map bounds (not statistical baseline state) |
| `ai.sentinel.trusted-proxies` | _(empty)_ | IPs or CIDRs; when remote matches, client IP from forwarded headers (see trusted proxy handling in [`ARCHITECTURE.md`](../ARCHITECTURE.md)) |
| `ai.sentinel.filter-order` | `2147483547` (same as `Ordered.LOWEST_PRECEDENCE - 100`, i.e. `Integer.MAX_VALUE - 100`) | Servlet filter order for Sentinel; adjust when you need Sentinel before/after other app filters or Spring Security chain behavior |
| `ai.sentinel.threshold-moderate` … `threshold-critical` | `0.2` … `0.8` | Strictly increasing, in `[0,1]` |
| `ai.sentinel.warmup-min-samples` / `warmup-score` / `warmup-action` | `2` / `0.4` / `MONITOR` | Cold-start: numeric `warmup-score` is telemetry/fusion input; **`warmup-action`** is the enforcement action while `EvaluationStatus.STATISTICAL_WARMUP` is active (`ALLOW` or `MONITOR`). Warmup is **not** treated as confirmed elevated risk. |
| `ai.sentinel.statistical.baseline-update-policy` | `ALLOW_OR_MONITOR` | When the decision engine may call `AnomalyScorer.update(...)` after the risk decision: `ALWAYS`, `ALLOW_ONLY`, `ALLOW_OR_MONITOR`, `SCORE_BELOW_THRESHOLD`. Mutually exclusive modes. In default composite wiring an accepted update fans out to statistical baseline state and optional Isolation Forest training-buffer handling (IF keeps its own sample-rate / rejection gates). |
| `ai.sentinel.statistical.baseline-update-score-threshold` | `0.4` | Used only with `SCORE_BELOW_THRESHOLD`: update when fused/policy score is **strictly below** this value (`[0,1]`). Ignored by other modes. |
| `ai.sentinel.statistical.relearn-mode` | `DISABLED` | Controlled baseline reset: `DISABLED` (default) or `EXPLICIT_ONLY` (operator `BaselineLifecycle.reset`). Automatic skip-triggered relearn is **not** offered — it allowed elevated traffic to both trigger reset and train warmup. Obsolete value `AFTER_CONSECUTIVE_SKIPS` is rejected at binding. |
| `ai.sentinel.startup-grace-period` | `0` | Duration (e.g. `5m`) enforcing monitor-only after startup |
| `ai.sentinel.enforcement-scope` | `IDENTITY_ENDPOINT` | Throttle/quarantine key scope |
| `ai.sentinel.isolation-forest.enabled` | `false` | In-core Isolation Forest |
| `ai.sentinel.isolation-forest.local-retrain-enabled` | `true` | Allow in-process IF retrain when IF is enabled (independent of registry refresh) |
| `ai.sentinel.isolation-forest.score-weight` | `0.5` | Weight of IF vs statistical score in the default composite blend |
| `ai.sentinel.isolation-forest.training-buffer-size` | `10000` | Bounded buffer for local retrain samples |
| `ai.sentinel.isolation-forest.min-training-samples` | `100` | Minimum samples before local retrain |
| `ai.sentinel.isolation-forest.retrain-interval` | `5m` | Local retrain cadence |
| `ai.sentinel.isolation-forest.sample-rate` | `0.1` | Fraction of requests admitted to the training buffer |
| `ai.sentinel.isolation-forest.training-rejection-score-threshold` | `0.7` | Used with training anti-poisoning gates |
| `ai.sentinel.identity.enabled` | `false` | When true, resolve `IdentityContext` and enable trust / fusion beans (see identity tables below) |
| `ai.sentinel.identity.fusion.enabled` | `false` | Risk fusion: combine anomaly score with identity trust before policy (no effect when identity is off) |
| `ai.sentinel.identity.fusion.strength` | `0.35` | Fusion blend strength in `[0,1]` when fusion is enabled |
| `ai.sentinel.telemetry.log-verbosity` | `ANOMALY_ONLY` | `FULL`, `ANOMALY_ONLY`, `SAMPLED`, `NONE` |
| `ai.sentinel.telemetry.log-score-threshold` | `0.4` | Score floor for anomaly-oriented verbosity modes |
| `ai.sentinel.telemetry.log-sample-rate` | `100` | Sampling denominator for `SAMPLED` verbosity |
| `ai.sentinel.distributed.tenant-id` | `default` | Tenant segment in shared Redis / registry paths (align with trainer) |
| `ai.sentinel.distributed.cluster-quarantine-read-enabled` | `false` | Merge cluster quarantine into `isQuarantined` (local OR Redis view) |
| `ai.sentinel.distributed.cluster-quarantine-write-enabled` | `false` | After local `QUARANTINE`, publish `until` to Redis (requires `distributed.enabled`, `redis.enabled`, template; async, fail-open) |
| `ai.sentinel.distributed.cluster-throttle-enabled` | `false` | On the **THROTTLE** action path only, consult a Redis fixed-window counter per enforcement key (cluster-wide cap; fail-open if Redis fails; requires `distributed.enabled`, `redis.enabled`, template) |
| `ai.sentinel.distributed.cluster-throttle-window` | `1s` | Wall-clock window length for the cluster throttle counter (validated ≥ 100ms) |
| `ai.sentinel.distributed.cluster-throttle-max-requests-per-window` | `30` | Max requests cluster-wide per enforcement key per window when cluster throttle is enabled (validated ≥ 1) |
| `ai.sentinel.distributed.cluster-throttle-max-in-flight` | `1024` | Per-JVM semaphore cap for concurrent cluster-throttle Redis evals; extra evaluations fail-open (metric `aisentinel.distributed.throttle.executor.rejected`); runtime clamp `[1, 50000]` |
| `ai.sentinel.distributed.cluster-throttle-timeout` | _(unset)_ | Max wait on the throttle Redis future; when unset or non-positive, uses `distributed.redis.lookup-timeout` |
| `ai.sentinel.distributed.training-publish-enabled` | `false` | Async export of versioned training candidates after enforcement (fail-open; bounded) |
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
| `ai.sentinel.model-registry.refresh-enabled` | `false` | Background poll of filesystem registry for newer IF artifacts (requires IF enabled + non-blank `filesystem-root`) |
| `ai.sentinel.model-registry.filesystem-root` | _(empty)_ | Registry root shared with `ai-sentinel-trainer` output (`{root}/{tenant}/active.json` + `artifacts/`) |
| `ai.sentinel.model-registry.poll-interval` | `5m` | Poll interval (validated 10s–24h) |

### Statistical warmup lifecycle

1. New identity|endpoint key → `EvaluationStatus.STATISTICAL_WARMUP` until enough samples (`warmup-min-samples`, with live scoring also requiring `n ≥ 2`).
2. Scorer still returns `warmup-score` for the numeric anomaly/policy score path (fusion/telemetry).
3. Decision engine overrides the **enforcement** action to `warmup-action` (default `MONITOR`) so warmup is **not** interpreted as confirmed elevated risk.
4. Warmup observations **always** update the baseline so cold-start keys can leave warmup under gated policies.
5. After enough updates → `STATISTICAL_LIVE` (+ `COMPLETE` when no model fallback/unavailable).

### Statistical baseline update policy

Decision flow:

```text
score → fusion / policy / trust → risk action → baseline-update policy → conditional update
     → warmup / startup-grace / quarantine (enforcement presentation only)
```

| Mode | Updates when |
|------|----------------|
| `ALLOW_OR_MONITOR` (default) | Risk-derived action is `ALLOW` or `MONITOR` (and always during `STATISTICAL_WARMUP`) |
| `ALLOW_ONLY` | Risk-derived action is `ALLOW` (and always during warmup) |
| `ALWAYS` | Every observation (previous unconditional behavior) |
| `SCORE_BELOW_THRESHOLD` | Policy/fused score &lt; `baseline-update-score-threshold` (and always during warmup) |

Learning uses the **risk-derived** action after policy and trust adjustment. Startup grace forcing `MONITOR` or a quarantine presentation override does **not** decide whether the feature vector trains the scorer. Trust fusion that changes the policy score/action does affect learning.

When an update is skipped, `RiskDecision` may include `EvaluationStatus.BASELINE_UPDATE_SKIPPED`.

The policy gates the decision-engine call to `AnomalyScorer.update(...)`. With the default `CompositeScorer`, an accepted update is forwarded to every child scorer (statistical Welford state and, when enabled, Isolation Forest training-buffer admission). Isolation Forest still applies its own sample-rate and training-rejection logic inside `update`.

### Controlled relearn / reset

Gated learning can freeze a baseline during a sustained elevated-risk shift. Relearn is **off by default** (`relearn-mode=DISABLED`) so contamination protection is not weakened silently.

| Mode | Behavior |
|------|----------|
| `DISABLED` | No operator reset through `BaselineLifecycle` |
| `EXPLICIT_ONLY` | Operators may call `BaselineLifecycle.reset(identityHash, endpoint)` (Spring bean) to clear that key’s Welford state |

**Automatic skip-triggered relearn was removed.** An earlier `AFTER_CONSECUTIVE_SKIPS` mode let the same elevated traffic both force a reset and train post-reset warmup, defeating gated-learning contamination protection. Configurations still setting `AFTER_CONSECUTIVE_SKIPS` fail at property binding (unknown enum value). Property `relearn-after-consecutive-skips` is obsolete and ignored if present as an unused key.

Reset clears statistical state for the key so the next observations re-enter `STATISTICAL_WARMUP` (warmup still learns; live gating resumes afterward). Successful explicit resets increment `aisentinel.baseline.relearn{reason=EXPLICIT}`.

**Operational responsibility:** perform an explicit reset only when subsequent traffic is expected to represent legitimate behavior. No unauthenticated HTTP reset endpoint is exposed.

### Baseline lifetime alignment

`BaselineStore` (rolling request counts) and `StatisticalScorer` (Welford state) both use `ai.sentinel.baseline-ttl` / `baseline-max-keys`. Idle keys expire on access paths even when under `max-keys`, so an idle identity does not return to a stale Welford mean after the request window has emptied. In-memory state is process-local: a restart is a cold start (warmup).

`BaselineStore` uses fixed **10-second buckets**. `requestsPerWindow` is the **sum of bucket counts overlapping the TTL** (default 5 minutes) — a rolling count, not a normalized per-second rate. Crossing a 10-second bucket boundary does **not** reset the count. Counts decline only as buckets age out of the TTL window. Mono-endpoint flooding is detected by this volume signal under gated baseline updates; Shannon `endpointEntropy` (diversity) and `endpointConcentration` (max share) do not distinguish established mono-endpoint use from mono-endpoint floods (both yield entropy ≈ 0 and concentration ≈ 1).

### Behavioral trust (`ai.sentinel.identity.trust.*`)

| Property | Default | Notes |
|----------|---------|--------|
| `ai.sentinel.identity.trust.trust-evaluation-enabled` | `true` | When false with identity on, behavioral trust evaluator is disabled (noop) |
| `ai.sentinel.identity.trust.baseline-ttl` | `15m` | TTL for in-memory and Redis-backed baseline entries |
| `ai.sentinel.identity.trust.baseline-max-keys` | `50000` | Max tracked baseline keys in the in-memory store |
| `ai.sentinel.identity.trust.distributed.enabled` | `false` | When true and a `StringRedisTemplate` bean exists, baselines use Redis (atomic Lua + TTL); otherwise in-memory only |
| `ai.sentinel.identity.trust.distributed.key-prefix` | `aisentinel:trust:bl:` | Redis key prefix; logical keys are hashed to a fixed-width suffix |
| `ai.sentinel.identity.trust.distributed.command-timeout` | `50ms` | Max wait on the Redis **EVAL** (Lua) round-trip for behavioral baselines; binds to `SentinelProperties.TrustDistributed#commandTimeout`. Timeout or error falls back to in-memory (does not cancel in-flight I/O—align `spring.data.redis.timeout`) |

### Trust-aware policy (`ai.sentinel.identity.trust-aware-policy.*`)

Escalates (never relaxes) the anomaly `PolicyEngine` action using identity trust. Requires identity resolution (`ai.sentinel.identity.enabled=true`).

| Property | Default | Notes |
|----------|---------|--------|
| `ai.sentinel.identity.trust-aware-policy.enabled` | `false` | Master switch |
| `ai.sentinel.identity.trust-aware-policy.authenticated-only` | `true` | Only escalate for authenticated identities |
| `ai.sentinel.identity.trust-aware-policy.protected-endpoint-patterns` | _(empty)_ | Ant-style paths treated as higher sensitivity |
| `ai.sentinel.identity.trust-aware-policy.http-methods` | _(empty)_ | Restrict escalation to listed methods (empty = all) |
| `ai.sentinel.identity.trust-aware-policy.trust-no-effect-minimum` | `0.80` | Trust at/above this band: no escalation |
| `ai.sentinel.identity.trust-aware-policy.trust-medium-band-minimum` | `0.50` | Medium trust band floor |
| `ai.sentinel.identity.trust-aware-policy.trust-low-band-minimum` | `0.25` | Low trust band floor |
| `ai.sentinel.identity.trust-aware-policy.deny-on-critical-trust-enabled` | `false` | Allow critical-trust deny path |
| `ai.sentinel.identity.trust-aware-policy.require-min-risk-for-trust-deny` | `true` | Require anomaly score ≥ min when denying |
| `ai.sentinel.identity.trust-aware-policy.min-risk-score-for-trust-deny` | `0.40` | Minimum anomaly score gate for trust deny |

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
