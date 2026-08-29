# Configuration reference

Properties use Spring Boot relaxed binding (`ai.sentinel.*`, `aisentinel.trainer.*`). See **`SentinelProperties`** and **`TrainerProperties`** in the codebase for validation rules.

**Operator deployment modes, MONITOR-first adoption, ENFORCE preconditions, and restart/cold-start:** [`deployment.md`](deployment.md).  
Upgrade notes: [`migration.md`](migration.md). Release notes: [`../CHANGELOG.md`](../CHANGELOG.md).

This page is organized as:

1. **Basic configuration** — what most adopters set first  
2. **Common profiles** — copy-paste starting points  
3. **Property catalog** — full `ai.sentinel.*` table (grouped)  
4. **Advanced detection / lifecycle** — warmup, gating, relearn, features  
5. **Distributed Redis** — cardinality, latency, timeouts  
6. **Local state sizing** — in-memory capacity guidance  

---

## Basic configuration

| Concern | Properties to set first |
|---------|-------------------------|
| On/off and mode | `enabled`, `mode` (prefer **`MONITOR`** for adoption) |
| Paths | `exclude-paths` |
| Policy bands | `threshold-moderate` … `threshold-critical` |
| Warmup safety | `warmup-action` (default `MONITOR`) |
| Learning | leave `statistical.baseline-update-policy` at `ALLOW_OR_MONITOR` unless you need `ALWAYS` |

Everything else is optional or advanced until you enable Isolation Forest, identity/trust, or Redis-backed distributed features.

---

## Common profiles

### Minimal MONITOR

```yaml
ai:
  sentinel:
    enabled: true
    mode: MONITOR
```

Uses in-process statistical scoring only. No Redis, Kafka, or Isolation Forest.

### Statistical only (ENFORCE trial)

```yaml
ai:
  sentinel:
    enabled: true
    mode: ENFORCE          # only after MONITOR evaluation — see deployment.md
    warmup-action: MONITOR
    statistical:
      baseline-update-policy: ALLOW_OR_MONITOR
```

### Statistical + Isolation Forest

```yaml
ai:
  sentinel:
    mode: MONITOR
    isolation-forest:
      enabled: true
      # local-retrain-enabled defaults true; registry refresh stays off until shared FS is ready
```

### Distributed Redis state

```yaml
ai:
  sentinel:
    mode: MONITOR
    distributed:
      enabled: true
      redis:
        enabled: true
        lookup-timeout: 50ms
      cluster-quarantine-read-enabled: true
      cluster-quarantine-write-enabled: true   # optional; async after local quarantine
      # cluster-throttle-enabled: true        # optional; THROTTLE path only
    identity:
      trust:
        distributed:
          enabled: false   # enable only when identity trust + Redis continuity are required
spring:
  data:
    redis:
      # host/port/...
      timeout: 50ms        # align with lookup-timeout / trust command-timeout
```

Requires `spring-boot-starter-data-redis` and a `StringRedisTemplate` bean.

---

## Property catalog (grouped)

Legend: **Required?** = needed for a working filter once `enabled=true`. **Advanced?** = leave default until you have a specific need.

### Core operation

| Property | Default | Category | Required? | Advanced? |
|----------|---------|----------|-----------|-----------|
| `ai.sentinel.enabled` | `true` | core | Yes | No |
| `ai.sentinel.mode` | `ENFORCE` | core | Yes | No — prefer `MONITOR` for adoption |
| `ai.sentinel.exclude-paths` | actuator/health/static/favicon | HTTP adapter | No | No |
| `ai.sentinel.filter-order` | late (`MAX-100`) | HTTP adapter | No | Yes |
| `ai.sentinel.trusted-proxies` | empty | HTTP adapter | No | Yes |
| `ai.sentinel.startup-grace-period` | `0` | core | No | Yes |

### Enforcement

| Property | Default | Category | Required? | Advanced? |
|----------|---------|----------|-----------|-----------|
| `ai.sentinel.block-status-code` | `429` | enforcement | No | No |
| `ai.sentinel.quarantine-duration-ms` | `300000` | enforcement | No | No |
| `ai.sentinel.throttle-requests-per-second` | `5.0` | enforcement | No | No |
| `ai.sentinel.enforcement-scope` | `IDENTITY_ENDPOINT` | enforcement | No | Yes — blast radius |
| `ai.sentinel.threshold-*` | `0.2`…`0.8` | scoring/policy | No | No |

### Baseline learning / scoring

| Property | Default | Category | Required? | Advanced? |
|----------|---------|----------|-----------|-----------|
| `ai.sentinel.baseline-ttl` / `baseline-max-keys` | `5m` / `100000` | baseline | No | Yes — sizing |
| `ai.sentinel.internal-map-max-keys` / `internal-map-ttl` | `100000` / `5m` | baseline | No | Yes — sizing; max-keys validated `[1000, 2e6]` |
| `ai.sentinel.warmup-min-samples` / `warmup-score` / `warmup-action` | `2` / `0.4` / `MONITOR` | scoring | No | No |
| `ai.sentinel.statistical.baseline-update-policy` | `ALLOW_OR_MONITOR` | baseline | No | No |
| `ai.sentinel.statistical.baseline-update-score-threshold` | `0.4` | baseline | No | Yes |
| `ai.sentinel.statistical.relearn-mode` | `DISABLED` | baseline | No | Yes |

### Isolation Forest

| Property | Default | Category | Required? | Advanced? |
|----------|---------|----------|-----------|-----------|
| `ai.sentinel.isolation-forest.enabled` | `false` | IF | No | No when enabling IF |
| `ai.sentinel.isolation-forest.*` (weight, buffer, sample-rate, …) | see table below | IF | No | Yes |
| `ai.sentinel.model-registry.*` | refresh off | IF / training | No | Yes |

### Trust / identity

| Property | Default | Category | Required? | Advanced? |
|----------|---------|----------|-----------|-----------|
| `ai.sentinel.identity.enabled` | `false` | trust | No | No when enabling identity |
| `ai.sentinel.identity.fusion.*` | off | trust | No | Yes |
| `ai.sentinel.identity.trust.*` | see trust table | trust | No | Yes |
| `ai.sentinel.identity.trust-aware-policy.*` | off | trust | No | Yes |

### Redis / distributed

| Property | Default | Category | Required? | Advanced? |
|----------|---------|----------|-----------|-----------|
| `ai.sentinel.distributed.enabled` | `false` | Redis | No | No when enabling cluster features |
| `ai.sentinel.distributed.redis.*` | off / `50ms` | Redis | No | Yes — timeouts |
| `ai.sentinel.distributed.cluster-quarantine-*` | false | Redis | No | Yes |
| `ai.sentinel.distributed.cluster-throttle-*` | false | Redis | No | Yes |
| `ai.sentinel.distributed.training-publish-*` | false | training | No | Yes |
| `ai.sentinel.distributed.cache.*` | on / `2s` / `10000` | Redis | No | Yes |

### Observability

| Property | Default | Category | Required? | Advanced? |
|----------|---------|----------|-----------|-----------|
| `ai.sentinel.telemetry.*` | `ANOMALY_ONLY` | observability | No | Yes |

### Remote evaluation (optional)

Default remains **local** evaluation with no network calls. Remote mode is additive.

| Property | Default | Notes |
|----------|---------|--------|
| `ai.sentinel.evaluation.executor-mode` | `LOCAL` | `LOCAL`, `REMOTE`, or `REMOTE_WITH_LOCAL_FALLBACK` |
| `ai.sentinel.evaluation.server.enabled` | `false` | When true, exposes authenticated `POST /ai-sentinel/v1/evaluation` |
| `ai.sentinel.evaluation.server.api-key` | _(empty)_ | Required when server enabled; inject via env/secret store |
| `ai.sentinel.evaluation.client.base-url` | _(empty)_ | Required for remote executor modes |
| `ai.sentinel.evaluation.client.api-key` | _(empty)_ | Sent as `X-AI-Sentinel-Api-Key` (not end-user Authorization) |
| `ai.sentinel.evaluation.client.connect-timeout` | `500ms` | Connect timeout |
| `ai.sentinel.evaluation.client.read-timeout` | `2s` | Read timeout |
| `ai.sentinel.evaluation.client.require-https` | `true` | Non-HTTPS rejected except loopback HTTP for local tests |

Remote transport failures are **fail-open** with status `REMOTE_EVALUATION_FAILURE` (not high risk). There is **no automatic retry** of evaluation POSTs.

**ASP.NET Core clients:** the reference adapter in [`dotnet/README.md`](../dotnet/README.md) maps `AiSentinel:*` settings to the same endpoint and `X-AI-Sentinel-Api-Key` header. Server-side properties above apply to the Java host exposing the evaluation API.

**Obsolete / rejected:** `relearn-mode=AFTER_CONSECUTIVE_SKIPS` fails binding; `relearn-after-consecutive-skips` is ignored if present as an unused key.

**Surprising default:** `mode=ENFORCE` while adoption docs recommend `MONITOR` — intentional compatibility; override for adoption.

---

## Full property notes (`ai.sentinel.*`)

| Property | Default | Notes |
|----------|---------|--------|
| `ai.sentinel.enabled` | `true` | Master switch |
| `ai.sentinel.mode` | `ENFORCE` | `OFF` (full filter bypass), `MONITOR` (score/learn/observe; **no client denial**), `ENFORCE` (client denial enabled). **Recommended adoption mode is `MONITOR`** even though the property default is `ENFORCE` — see [`deployment.md`](deployment.md). |
| `ai.sentinel.exclude-paths` | actuator, health, static, favicon | Comma-separated Ant-style patterns |
| `ai.sentinel.block-status-code` | `429` | HTTP status written on BLOCK / throttle-exhaust / quarantine responses |
| `ai.sentinel.quarantine-duration-ms` | `300000` | Local quarantine TTL in milliseconds |
| `ai.sentinel.throttle-requests-per-second` | `5.0` | Local token-bucket style throttle rate |
| `ai.sentinel.baseline-ttl` / `baseline-max-keys` | `5m` / `100000` | Shared lifetime for the rolling `BaselineStore` request window **and** `StatisticalScorer` Welford state (idle keys expire on access paths) |
| `ai.sentinel.internal-map-max-keys` / `internal-map-ttl` | `100000` / `5m` | Local endpoint-history / throttle / quarantine map bounds (not statistical baseline state). `internal-map-max-keys` is Bean-validated: **`[1000, 2_000_000]`** (rejects 0, negative, and absurdly small values). Unset keeps the default `100000`. |
| `ai.sentinel.trusted-proxies` | _(empty)_ | IPs or CIDRs; when remote matches, client IP from forwarded headers (see trusted proxy handling in [`ARCHITECTURE.md`](../ARCHITECTURE.md)) |
| `ai.sentinel.filter-order` | `2147483547` (same as `Ordered.LOWEST_PRECEDENCE - 100`, i.e. `Integer.MAX_VALUE - 100`) | Servlet filter order. **Default is intentionally late** so Spring Security (when present) typically populates `SecurityContextHolder` before Sentinel resolves identity (principal preferred over client IP). Running earlier improves early deny but usually forces IP-only identity. Absolute order vs every custom filter is not guaranteed — set this explicitly when your chain needs a different placement. Late order can also mean another filter commits the response first; see committed-response behavior below. |
| `ai.sentinel.threshold-moderate` … `threshold-critical` | `0.2` … `0.8` | Strictly increasing, in `[0,1]` |
| `ai.sentinel.warmup-min-samples` / `warmup-score` / `warmup-action` | `2` / `0.4` / `MONITOR` | Cold-start: numeric `warmup-score` is telemetry/fusion input; **`warmup-action`** is the enforcement action while `EvaluationStatus.STATISTICAL_WARMUP` is active (`ALLOW` or `MONITOR`). Warmup is **not** treated as confirmed elevated risk. |
| `ai.sentinel.statistical.baseline-update-policy` | `ALLOW_OR_MONITOR` | When the decision engine may call `AnomalyScorer.update(...)` after the risk decision: `ALWAYS`, `ALLOW_ONLY`, `ALLOW_OR_MONITOR`, `SCORE_BELOW_THRESHOLD`. Mutually exclusive modes. In default composite wiring an accepted update fans out to statistical baseline state and optional Isolation Forest training-buffer handling (IF keeps its own sample-rate / rejection gates). |
| `ai.sentinel.statistical.baseline-update-score-threshold` | `0.4` | Used only with `SCORE_BELOW_THRESHOLD`: update when fused/policy score is **strictly below** this value (`[0,1]`). Ignored by other modes. |
| `ai.sentinel.statistical.relearn-mode` | `DISABLED` | Controlled baseline reset: `DISABLED` (default) or `EXPLICIT_ONLY` (operator `BaselineLifecycle.reset`). Automatic skip-triggered relearn is **not** offered — it allowed elevated traffic to both trigger reset and train warmup. Obsolete value `AFTER_CONSECUTIVE_SKIPS` is rejected at binding. |
| `ai.sentinel.startup-grace-period` | `0` | Duration (e.g. `5m`) forcing MONITOR **presentation** after process start. Distinct from statistical warmup — see [`deployment.md`](deployment.md). |
| `ai.sentinel.enforcement-scope` | `IDENTITY_ENDPOINT` | Throttle/quarantine key scope only (`IDENTITY_ENDPOINT` or `IDENTITY_GLOBAL`). **Does not** change statistical baseline / `BaselineStore` keys (always `identity\|endpoint`) or trust baseline keys. `IDENTITY_GLOBAL` quarantines/throttles the identity across **all** endpoints — wide blast radius; use only when that is intended. |
| `ai.sentinel.isolation-forest.enabled` | `false` | In-core Isolation Forest |
| `ai.sentinel.isolation-forest.local-retrain-enabled` | `true` | Allow in-process IF retrain when IF is enabled (independent of registry refresh) |
| `ai.sentinel.isolation-forest.score-weight` | `0.5` | Weight of IF vs statistical score in the composite blend **only when IF mode is `MODEL`**. Fallback scores (`FALLBACK_NO_MODEL` / `FALLBACK_INVALID`) remain visible on snapshots/actuator but are excluded from the weighted average. |
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
| `ai.sentinel.distributed.cluster-quarantine-write-enabled` | `false` | After local `QUARANTINE`, publish `until` to Redis (requires `distributed.enabled`, `redis.enabled`, template; async, fail-open). `EnforcementHandler.releaseQuarantine` also best-effort `DEL`s the same key when write path is enabled. |
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
5. After enough updates → `STATISTICAL_LIVE` (+ `COMPLETE` when no model fallback/unavailable and no `DEGRADED`).

Operator-facing aliases (`OperatorEvaluationPhase`): `WARMUP` ← `STATISTICAL_WARMUP`; `LIVE` ← `STATISTICAL_LIVE`/`COMPLETE`; `MODEL_FALLBACK` ← `MODEL_FALLBACK_USED` (+ `MODEL_UNAVAILABLE`); `DEGRADED` ← optional-path failure with a completed decision; `FAIL_OPEN` ← `FailOpenReason` metrics/telemetry (not an `EvaluationStatus` on `RiskDecision`).

### Fail-open and degradation reporting

Fail-open **behavior** (allow on error) is availability-first. The **canonical failure-mode profile** (request outcomes, per-reason telemetry vs metrics-only paths, Redis/trainer side paths) lives in [`deployment.md`](deployment.md#failure-mode-profile-availability-first).

Visibility summary:

| Signal | Where |
|--------|--------|
| Aggregate fail-open | `aisentinel.failopen.count` |
| Reasoned fail-open metrics | `aisentinel.failopen.reason{reason=…}` for every `FailOpenReason` |
| Structured `FailOpen` telemetry | Decision-engine reasons only (`SCORER_FAILURE`, trust/fusion/policy/update failures). Pipeline/filter reasons (`IDENTITY_RESOLUTION_FAILURE`, `FEATURE_EXTRACTION_FAILURE`, `FILTER_FAILURE`) are **metrics + logs**, not `FailOpen` events |
| IF fallback vs model | `aisentinel.isolationforest.score.mode{mode=MODEL\|FALLBACK_NO_MODEL\|FALLBACK_INVALID}` + `ThreatScored.isolationForestScoreMode` |
| Status on decisions | `aisentinel.evaluation.status{status=…}` + `RiskDecision.evaluationStatuses` |
| Feature-extractor failure | Allows request; increments `FEATURE_EXTRACTION_FAILURE` — availability bias, not a scored allow |

Redis quarantine/throttle/trust fail-open keeps dedicated meters and degraded gauges; see distributed section below. Prefer **MONITOR** until fail-open rates and IF fallback modes are understood in your traffic.

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

`BaselineStore` (rolling request counts) and `StatisticalScorer` (Welford state) both use `ai.sentinel.baseline-ttl` / `baseline-max-keys`. Idle keys expire on access paths even when under `max-keys`, so an idle identity does not return to a stale Welford mean after the request window has emptied. In-memory state is process-local: a restart is a cold start (warmup). Capacity eviction is serialized; per-key bucket prune/count is synchronized with increments so rolling-window semantics stay exact under concurrent access. Local quarantine/throttle maps are also process-local and empty after restart; Redis-backed cluster quarantine/throttle (when enabled) survive the JVM if Redis still holds the keys.

`BaselineStore` uses fixed **10-second buckets**. `requestsPerWindow` is the **sum of bucket counts overlapping the TTL** (default 5 minutes) — a rolling count, not a normalized per-second rate. Crossing a 10-second bucket boundary does **not** reset the count. Counts decline only as buckets age out of the TTL window. Under steady arrival the count climbs while the window fills, then plateaus; the statistical scorer’s `requestsPerWindow` resolution floor (2.0) keeps that natural +1 staircase in ALLOW/MONITOR under default gated learning so baselines can establish, while abrupt volume shocks still saturate. Mono-endpoint flooding is detected by this volume signal under gated baseline updates; Shannon `endpointEntropy` (diversity) and `endpointConcentration` (max share) do not distinguish established mono-endpoint use from mono-endpoint floods (both yield entropy ≈ 0 and concentration ≈ 1).

### Behavioral trust (`ai.sentinel.identity.trust.*`)

| Property | Default | Notes |
|----------|---------|--------|
| `ai.sentinel.identity.trust.trust-evaluation-enabled` | `true` | When false with identity on, behavioral trust evaluator is disabled (noop) |
| `ai.sentinel.identity.trust.baseline-ttl` | `15m` | TTL for in-memory and Redis-backed baseline entries |
| `ai.sentinel.identity.trust.baseline-max-keys` | `50000` | Max tracked baseline keys in the **in-memory** store (and Redis fail-open fallback). **Does not** cap Redis key cardinality when distributed trust is enabled |
| `ai.sentinel.identity.trust.distributed.enabled` | `false` | When true and a `StringRedisTemplate` bean exists, baselines use Redis (atomic Lua + TTL); otherwise in-memory only |
| `ai.sentinel.identity.trust.distributed.key-prefix` | `aisentinel:trust:bl:` | Redis key prefix; logical keys are hashed to a fixed-width suffix |
| `ai.sentinel.identity.trust.distributed.command-timeout` | `50ms` | Max wait on the Redis **EVAL** (Lua) round-trip for behavioral baselines; binds to `SentinelProperties.TrustDistributed#commandTimeout`. Timeout or error falls back to in-memory (does not cancel in-flight I/O—align `spring.data.redis.timeout`) |

**Redis trust cardinality (ops):** Distributed trust baselines are bounded by **TTL only** — there is no application-side Redis `maxKeys` and no request-path `SCAN`/`KEYS`. Physical keys are `{key-prefix}{sha256(logicalKey)}` with logical keys `p:{principal}`, `s:{sessionIdHash}`, or `i:{identityHash}` (unauthenticated sessionless traffic uses the client IP hash). Every successful write refreshes TTL (`SET … PX`). Operators must size Redis memory for peak unique identities/sessions within `baseline-ttl`, monitor key count for the trust prefix, and configure Redis `maxmemory` / eviction policy as an **infrastructure** control (shared Redis eviction can affect other applications on the same instance — choose policy deliberately). Prefer authenticating or sessioning clients when enabling distributed trust so unauthenticated IP churn cannot inflate cardinality. Fail-open on Redis error uses the local in-memory store (which **is** `baseline-max-keys`-bounded); after Redis recovers, subsequent writes go to Redis again without an application restart.

DEBUG logs for Redis trust / quarantine / throttle failures do **not** include Redis key material or logical identity keys (exception type/message only).

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

### Request-path feature and enforcement semantics

**Token age (`tokenAgeSeconds`):** Requires both `Authorization` (non-blank) and `X-Token-Issued-At` (epoch seconds). Missing/blank/unparsable/overflow → `-1`. Valid past issued-at → non-negative age in seconds. **Future** issued-at within a tolerated clock-skew window (≤300 seconds, matching common JWT-library leeway) is clamped to `0` so ordinary issuer/application clock skew is not conflated with missing/invalid (`-1`). Future issued-at **beyond** that window is treated the same as missing/invalid (`-1`), not silently `0` — an unbounded clamp would let a client fully neutralize this feature's contribution to anomaly scoring by spoofing an arbitrarily-future timestamp (verified: against an established near-zero-token-age baseline, this dropped a would-be-QUARANTINE score to ALLOW using nothing but the header). The skew window is not configurable, to avoid reintroducing that gap. Headers are client-influenced; treat as weak signals under gated learning and z-caps.

**Parameter count:** `HttpRequestView.getParameterMap().size()` — query/form parameters only. JSON request bodies are **not** parsed on the hot path; a JSON API with an empty parameter map yields `parameterCount = 0` even when the body is large (body size still appears in `payloadSizeBytes` via `Content-Length` when present).

**Committed responses:** When another filter has already committed the servlet response before Sentinel denial writes, `EnforcementResponse.isCommitted()` is true (servlet adapter). AI-Sentinel **skips** status/body mutation, still applies local quarantine/throttle state, and still emits telemetry for the decision intent. Telemetry does **not** currently distinguish “HTTP body written” from “decision recorded but write skipped.” Prefer filter ordering so Sentinel can write denials when early blocking is required.

**Enforcement scope vs detection scope:** Scoring/baselines use `identity|endpoint`. Throttle/quarantine use `ai.sentinel.enforcement-scope`. Mismatch is intentional and configurable — document blast radius when selecting `IDENTITY_GLOBAL`.

### Redis and request-path budget

Add `spring-boot-starter-data-redis` and `spring.data.redis.*` when using cluster quarantine read/write and/or cluster throttle. Quarantine write propagation runs **asynchronously** after local quarantine is applied; Redis failures do not roll back local quarantine. Cluster throttle uses a short-budget async Redis **INCR** + **EXPIRE** script; on timeout or error the check **allows** the request (fail-open) and local per-node throttling still applies afterward. The **filter thread** waits up to the configured throttle/quarantine timeout for the Redis future, so **Redis round-trip latency is part of the request-path budget** for that check.

#### Request-path Redis matrix (verified)

| Component | Redis operation | On request path? | TTL | Failure semantics |
|-----------|-----------------|------------------|-----|-------------------|
| Cluster quarantine **read** | `GET` (async future; optional local cache) | **Yes** — when read enabled and cache miss | Value TTL set by writer (`until - now`) | Fail-open empty → not quarantined from cluster |
| Cluster quarantine **write** | `SET` + TTL (async executor) | **No** — after local quarantine; does not block caller beyond enqueue | Remaining quarantine duration | Drop / warn; local quarantine retained |
| Cluster throttle | Lua `INCR` + `EXPIRE` (async future) | **Yes** — only on `THROTTLE` action path | Window-based (~window+1s) | Fail-open allow; local throttle still runs |
| Distributed trust baseline | Lua `GET`/`SET` via `EVAL` (async future) | **Yes** — when identity trust + distributed trust enabled | `identity.trust.baseline-ttl` (default 15m) refreshed on write | Fail-open to in-memory store |
| Training publish | Kafka / log transport | **No** — async after pipeline | n/a | Fail-open drop |

**Worst-case synchronous waits per request** (when all relevant flags are on): up to one quarantine GET budget (`lookup-timeout`, default 50ms) on cache miss during quarantine checks, plus one trust EVAL budget (`command-timeout`, default 50ms) when distributed trust is on, plus one throttle EVAL budget on the THROTTLE path. These are **upper waits**, not measured network latency. Disable unused distributed flags to avoid the dependency entirely.

Align `spring.data.redis.timeout` with these budgets. Future timeouts return control to the filter thread; they do not reliably cancel in-flight Lettuce I/O.

#### Redis cardinality (quarantine / throttle / trust)

| Key family | Drivers | App hard cap? |
|------------|---------|---------------|
| `{prefix}:{tenant}:q:{enforcementKey}` | Quarantined identities × scope (endpoint vs global) | **No** — TTL only |
| `{prefix}:{tenant}:th:{bucket}:{enforcementKey}` | Throttled keys × time buckets | **No** — short window TTL |
| `{trust-prefix}{sha256(logical)}` | Unique principals / sessions / IP hashes within trust TTL | **No** — TTL only; in-memory fallback is capped |

Identity churn (especially unauthenticated IP identity) and `IDENTITY_GLOBAL` scope increase key pressure. The framework does **not** bound Redis memory. If Redis is shared with other workloads, `maxmemory` / eviction policy decisions belong to the deployment and may evict non-Sentinel keys — do not assume a universal eviction policy.

### Unauthenticated identity (IP hash) and state growth

When Spring Security is absent, the principal is anonymous, or no authenticated principal name is available, `SentinelFilter` derives the enforcement/baseline identity as **SHA-256 of the resolved client IP** (after trusted-proxy handling). That hash keys:

* statistical baseline / `BaselineStore` entries (`identity|endpoint`);
* local endpoint-history, throttle, and quarantine maps (`internal-map-max-keys`);
* trust logical key `i:{identityHash}` when identity trust is enabled (sessionless).

**Operational effects**

| Topic | Guidance |
|-------|----------|
| **State growth** | Unique client IPs within TTL/max-keys inflate in-memory maps. Redis quarantine/throttle/trust keys (when enabled) grow with unique IP hashes and are **TTL-bounded only** — see Redis cardinality above. |
| **NAT / shared egress** | Many users behind one public IP share one identity hash → shared baselines and shared throttle/quarantine blast radius. Expect noisier baselines and higher false correlation across users on that IP. |
| **Attack considerations** | Attackers who can rotate source IPs (botnets, proxy pools) create many short-lived keys (cardinality / memory pressure) and avoid accumulating a single-identity reputation. This is an inherent limit of IP-as-identity, not a detector bug. Spoofed `X-Forwarded-For` only matters when `trusted-proxies` trusts the connecting hop — keep that list tight. |
| **Recommendations** | Prefer authenticated principals (or stable session identity) for production scoring/enforcement keys. Size `baseline-max-keys` / `internal-map-max-keys` and Redis memory for peak **unique IP** cardinality if unauthenticated traffic is expected. Prefer `MONITOR` until IP churn and NAT effects are visible in metrics. Do not treat IP identity as equivalent to user identity. |

This section documents current identity fallback behavior only — it does not change resolver design.

### Local in-memory state sizing

Defaults can approach **full occupancy** under high unique-identity cardinality. Structural (not measured heap) estimate when every map is simultaneously near its configured maximum:

| Structure | Default max keys | Approx. objects per key (order-of-magnitude) |
|-----------|------------------|-----------------------------------------------|
| `BaselineStore` (`baseline-max-keys`) | 100 000 | map entry + bucket chain for rolling counts |
| `StatisticalScorer` Welford map (same `baseline-max-keys`) | 100 000 | map entry + Welford state (~feature dims) |
| Endpoint history (`internal-map-max-keys`) | 100 000 | map entry + per-identity endpoint histogram |
| Local quarantine map (`internal-map-max-keys`) | 100 000 | map entry + until timestamp |
| Local throttle map (`internal-map-max-keys`) | 100 000 | map entry + token counter |
| Trust in-memory (`identity.trust.baseline-max-keys`) | 50 000 | map entry + baseline fields (when identity trust on) |

**Conservative product:** if baseline + scorer + three internal maps are all near 100 000, that is on the order of **5 × 100 000** concurrent map entries in one JVM (trust adds another 50 000 when enabled). Exact heap depends on JVM, string sizes, and feature dimensions — treat this as a **capacity planning signal**, not a measured RSS figure.

Guidance:

* Size `*-max-keys` to expected **active** identity\|endpoint cardinality with headroom; do not assume the default 100 000 is “free.”
* Idle TTL eviction still runs under maxKeys; capacity eviction removes oldest-access keys when over cap.
* Prefer authenticating clients so identity is stable (reduces IP churn).
* Monitor actuator / metrics for map sizes where exposed; watch GC and RSS under load.
* Defaults were **not** lowered in this release — changing them is an operator decision.

### Isolation Forest (demo)

Use the bundled **`stage2`** profile for faster local training:

```bash
mvn -pl ai-sentinel-demo spring-boot:run -Dspring-boot.run.profiles=stage2
```

Config: `ai-sentinel-demo/src/main/resources/application-stage2.yaml`.

---

## Trainer (`aisentinel.trainer.*`)

See [`ai-sentinel-trainer/README.md`](../ai-sentinel-trainer/README.md) for the full table and run instructions.
