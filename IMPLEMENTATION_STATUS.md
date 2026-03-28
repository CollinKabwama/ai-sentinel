# AI-Sentinel — Implementation Status

This document summarizes what has been built and what has been done to date (codebase state, review-driven fixes, and verification).

---

## 1. Project Overview

**AI-Sentinel** is a modular Spring Boot starter for **AI-assisted zero-trust API defense**: per-request behavioral anomaly detection, risk scoring, policy-driven enforcement (allow / throttle / block / quarantine), and telemetry. It uses in-process, unsupervised ML (statistical baseline + optional Isolation Forest) with privacy-aware features and fail-open behavior.

**References (tracked in this repository):**

- **`README.md`** — Quick start, prerequisites, configuration overview, scripts, license.
- **`ARCHITECTURE.md`** — Current modules, pipeline, scoring (statistical + IF), enforcement, proxy rules, observability, extension points.
- **`scripts/README.md`** — Python traffic and training-monitor utilities.

**Note:** Paths such as `docs/` or ad-hoc audit markdown may exist **locally** but are listed in `.gitignore` and are **not** part of the published tree; do not rely on them in CI or for onboarding.

---

## 2. Development Stage (vs. Formal Roadmap)

Roadmap: **Stage 0** (core) → **1** (Spring Boot) → **2** (Isolation Forest) → **3** (security / ops hardening) → **4** (observability) → **5** (distributed) → **6** (research).

**Current position: Stages 0–4 are implemented in code; pre–Stage 5 critical fixes are merged. Stage 5 (distributed) is next.**

| Stage | Name | Status | Notes |
|-------|------|--------|--------|
| **0** | Core Engine Foundation | **Complete** | `DefaultFeatureExtractor`, `StatisticalScorer` (Welford), warmup, `ThresholdPolicyEngine`, `CompositeEnforcementHandler` + `MonitorOnlyEnforcementHandler`, `CompositeScorer`, `TelemetryEmitter`, bounded maps (TTL + maxKeys), concurrency-focused tests. |
| **1** | Spring Boot Operational Integration | **Complete** | Auto-configuration, `SentinelFilter`, `SentinelProperties` (`ai.sentinel.*`), MONITOR/ENFORCE/OFF, actuator **`/actuator/sentinel`**, fail-open on filter errors. |
| **2** | Real ML Integration | **Complete** | Bounded training buffer, **in-core** Isolation Forest (no external IF library), `IsolationForestScorer`, async retrain scheduler, anti-poisoning sample rejection, five-dimensional IF feature vector; actuator exposes model/buffer/retrain/training-count fields. |
| **3** | Security Hardening & Ops | **Largely complete** | Trusted proxies with **CIDR**, X-Forwarded-For / Forwarded / guarded X-Real-IP, `startupGracePeriod`, `enforcementScope`, training rejection threshold. Still deferred: full adversarial/stress program (e.g. high-RPS harness), some audit edge cases (see §7). |
| **4** | Observability & SRE | **Largely complete** | `MicrometerSentinelMetrics`: score distribution summaries (percentiles), pipeline/scoring/IF latency timers, per-action counters, retrain success/failure, fail-open / NaN / scoring-error counters; actuator **`scoreSummary`**, **`latencySummary`**, training accept/reject counts. OpenTelemetry and some audit-listed niceties remain out of scope. |
| **5** | Distributed & Enterprise | **Not started** | Shared store, cluster-wide quarantine, tenant isolation, admin API — none. |
| **6** | Research & Publication | **Not started** | Benchmarks, FP/FN studies, whitepaper — none. |

**Summary:** The repo is a deployable single-node anomaly filter (statistical + optional IF) with solid metrics and operator-facing actuator data. **Stage 5 (distributed)** is the next major milestone; see **§10** for how that lines up with optional ML-focused follow-ups.

---

## 3. Module Layout

| Module | Purpose |
|--------|---------|
| **ai-sentinel-core** | Feature extraction, scoring (Statistical + Isolation Forest), policy (ThresholdPolicyEngine), enforcement (Composite + MonitorOnly), telemetry events, baseline store. |
| **ai-sentinel-spring-boot-starter** | Auto-configuration, `SentinelFilter`, `SentinelProperties`, actuator endpoint `/actuator/sentinel`. |
| **ai-sentinel-demo** | Sample Spring Boot app with `/api/hello`, simulated traffic, MONITOR/ENFORCE config. |

Build: Maven multi-module (root `pom.xml`). Run demo: `mvn -pl ai-sentinel-demo spring-boot:run`.

---

## 4. What Has Been Implemented

### 4.1 Core pipeline

- **Feature extraction** — `DefaultFeatureExtractor`: `requestsPerWindow`, `endpointEntropy`, `tokenAgeSeconds`, `parameterCount`, `payloadSizeBytes`, `headerFingerprintHash`, `ipBucket`. Uses `BaselineStore` (time-bucketed counts) and per-identity endpoint history for entropy.
- **Scoring** — `StatisticalScorer` (Welford rolling mean/std, z-score → sigmoid), `CompositeScorer` (weighted combination), optional `IsolationForestScorer` (bounded buffer, minimal in-core Isolation Forest, async retrain, fallback score when no model). Score in [0, 1].
- **Policy** — `ThresholdPolicyEngine`: score bands → ALLOW, MONITOR, THROTTLE, BLOCK, QUARANTINE.
- **Enforcement** — `CompositeEnforcementHandler`: throttle (token bucket), block (status code), quarantine (time-bound). `MonitorOnlyEnforcementHandler` wraps it in MONITOR mode (no blocking, still reports quarantine state).
- **Telemetry** — `TelemetryEmitter` (e.g. `DefaultTelemetryEmitter`: JSON logs + Micrometer counters), configurable verbosity.

### 4.2 Spring Boot integration

- **Filter** — `SentinelFilter`: identity resolution (IP + optional Spring Security principal), pipeline invocation, exclude paths, MONITOR vs ENFORCE.
- **Configuration** — `SentinelProperties`: enabled, mode, excludePaths, blockStatusCode, quarantineDurationMs, throttleRequestsPerSecond, baselineTtl, baselineMaxKeys, internalMapMaxKeys, internalMapTtl, trustedProxies, threshold-moderate / threshold-elevated / threshold-high / threshold-critical, warmupMinSamples, warmupScore, isolationForest, telemetry.
- **Actuator** — `/actuator/sentinel`: `enabled`, `mode`, `isolationForestEnabled`, `startupGraceActive`, `enforcementScope`, `activeThrottleCount`, `quarantineCount` (and `activeQuarantineCount`); **`lastScoreComponents`** — map from the **most recent** `CompositeScorer.score()` (`statistical`, optional `isolationForest`, `composite`, `evaluatedAtMillis`; empty `{}` until traffic has been scored); when IF enabled: **isolationForestModelLoaded**, **isolationForestBufferedSampleCount**, **isolationForestModelVersion**, retrain timestamps, **isolationForestModelAgeMillis**, retrain failure fields, **acceptedTrainingSampleCount**, **rejectedTrainingSampleCount**; with Micrometer: **scoreSummary**, **latencySummary**, **modelRetrainSuccessCount**, **modelRetrainFailureCount** (see `SentinelActuatorEndpoint`).

#### Stage 2.2 — Operational Hardening

Stage 2 infrastructure (Isolation Forest scoring, buffer, async retrain, actuator metadata) is **complete**. Stage 2.2 adds operational maturity and model validation capabilities without changing the core architecture:

- **Retrain observability** — IsolationForestScorer logs retrain success (model version, sample count, duration) and logs retrain failures at WARN; exposes **retrainFailureCount** and **lastRetrainFailureTimeMillis** via actuator.
- **Model age visibility** — `getModelAgeMillis()` (age in ms or -1 if no model); exposed as **isolationForestModelAgeMillis** on `/actuator/sentinel`.
- **Improved cold-start** — First retrain runs after `min(retrainInterval, 30s)`; subsequent runs use the configured retrain interval.
- **Configurable IF hyperparameters** — `ai.sentinel.isolation-forest.num-trees` (default 100) and `ai.sentinel.isolation-forest.max-depth` (default 10) wired from SentinelProperties → IsolationForestConfig → IsolationForestTrainer.
- **Training-data quality gate** — When a model exists, samples with score > 0.7 are not added to the buffer, reducing poisoning from clearly anomalous requests.

### 4.3 Security and robustness (review-driven)

Addressed items from the production review and audit:

| Area | What was done |
|------|----------------|
| **Concurrency / data races** | A1/C1: `StatisticalScorer` — `score()` and `update()` use same lock on `WelfordState`; reads via `getMeansCopy()` / `getStds()` under lock. C4: `CompositeEnforcementHandler.isQuarantined()` uses `quarantinedUntil.compute()` for atomic check-and-remove (no TOCTOU). |
| **Proxy identity** | A5: `ClientIpResolver` / `SentinelFilter` — when remote is trusted, client IP from X-Forwarded-For (rightmost-untrusted), then Forwarded `for=`, then X-Real-IP only if there is no forward-chain hint (no non-blank X-Forwarded-For or Forwarded header). Config: `ai.sentinel.trusted-proxies`. |
| **Map growth / OOM** | A4: All four maps bounded: `stateByKey`, `endpointHistory`, `throttleTokens`, `quarantinedUntil` use maxKeys + TTL/expiry; eviction in StatisticalScorer, DefaultFeatureExtractor, CompositeEnforcementHandler. Config: `internalMapMaxKeys`, `internalMapTtl`. |
| **Endpoint history** | A2: Atomic counters (`AtomicIntegerArray`) and saturation at `MAX_HISTORY_COUNT` to avoid overflow/NaN. A3: `safeHistoryIndex()` — no `Math.abs(Integer.MIN_VALUE)`; index in [0, HISTORY_SIZE). |
| **NaN / bypass** | A6: NaN and negative scores clamped to high risk: `SentinelPipeline.clampScore()`, `CompositeScorer`, `StatisticalScorer` (z/sigmoid NaN handling). Policy then sees 1.0 → no ALLOW bypass. |
| **Cold start** | D3: `StatisticalScorer` warmup — when state is null or `n < warmupMinSamples`, return `warmupScore` (default 0.4) instead of 0.0. Config: `warmupMinSamples`, `warmupScore`. |
| **Path parameter explosion** | E2: `DefaultFeatureExtractor.normalizeEndpoint()` — truncate to 256 chars; `normalizePathParams()` replaces numeric and UUID path segments with `{id}` so `/api/users/123` and `/api/users/456` share one baseline. |
| **Monitor visibility** | A7: `MonitorOnlyEnforcementHandler.isQuarantined()` delegates to delegate; actuator `info()` exposes `quarantineCount` from `CompositeEnforcementHandler.getQuarantineCount()`. |

### 4.4 Pre-Stage 5 Critical Fixes

- **Configurable policy thresholds** — `ai.sentinel.threshold-moderate`, `threshold-elevated`, `threshold-high`, and `threshold-critical` (defaults 0.2 / 0.4 / 0.6 / 0.8) are bound via `SentinelProperties` and `SentinelAutoConfiguration` into `ThresholdPolicyEngine`. Invalid configuration (not strictly increasing, out of `[0.0, 1.0]`, or non-finite) fails fast at startup with a clear `IllegalArgumentException`.
- **X-Real-IP trust** — `X-Real-IP` is used only when the TCP peer is a trusted proxy and there is no forward-chain hint (no non-blank `X-Forwarded-For` or `Forwarded` header). If a hint is present but no client IP is parsed, resolution falls back to `getRemoteAddr()` instead of trusting `X-Real-IP`.
- **Isolation Forest feature vector** — `RequestFeatures.toIsolationForestArray()` feeds five behavioral features into IF scoring and training; `toArray()` remains unchanged for the statistical scorer (still includes `headerFingerprintHash` and `ipBucket`).

---

## 5. Tests Added / Updated

- **StatisticalScorerTest:** `stateByKeyEvictsWhenOverMaxKeys`, `concurrentUpdateAndScoreNoDataRace`, `scoreReturnsWarmupScoreWhenInsufficientData`, `warmupScoreConfigurable`.
- **DefaultFeatureExtractorTest:** `endpointWithHashCodeIntegerMinValueDoesNotThrow`, `endpointHistoryEvictsWhenOverMaxKeys`, `pathParamsNormalizedToPreventMapExplosion`, `uuidPathParamNormalized`, `normalizePathParamsStaticMethod`.
- **CompositeScorerTest:** `nanScoreReturnsOneNotBypass`, `negativeScoreReturnsOne`, `lastSnapshotCapturesStatisticalAndIsolationForestComponents`.
- **CompositeEnforcementHandlerTest:** `throttleMapBoundedWhenOverMaxKeys`, `quarantineBoundedMapDoesNotThrow`, `getQuarantineCountReturnsActiveQuarantines`, `isQuarantinedExpiredEntryRemovedAtomically`.
- **MonitorOnlyEnforcementHandlerTest:** `isQuarantinedDelegatesToDelegate`.
- **SentinelFilterProxyTest:** trusted proxy + X-Forwarded-For / Forwarded / X-Real-IP behavior; untrusted `X-Real-IP`; placeholder XFF ignores `X-Real-IP`; fallback to `getRemoteAddr()`.
- **ClientIpResolverTest:** forward-chain hint / `X-Real-IP` trust cases (mirrors resolver unit coverage).
- **ThresholdPolicyEngineTest:** custom thresholds, validation errors for ordering and range.
- **SentinelAutoConfigurationTest:** invalid threshold ordering fails context; custom thresholds bind to `PolicyEngine`.
- **RequestFeaturesTest:** `toIsolationForestArray` five-feature shape.
- **IsolationForestScorerTest:** fallback when no model, score in [0,1] after training, retrain failure does not break inference, atomic model swap, five-dimensional training buffer, IF ignores hash/ip bucket for inference, metadata.
- **SentinelActuatorEndpointTest:** endpoint structure, `quarantineCount`, `lastScoreComponents`, and when IF enabled the IF metadata keys.
- **BoundedTrainingBufferTest:** boundedness, snapshot, concurrent adds, null/empty ignored.
- **Demo:** `DemoIntegrationTest` (hello + actuator); test profile uses MONITOR and higher throttle for stability.

---

## 6. Configuration (High Level)

Example `application.yaml`:

```yaml
ai:
  sentinel:
    enabled: true
    mode: ENFORCE   # or MONITOR, OFF
    exclude-paths: /actuator/**,/health,/health/**
    trusted-proxies: 127.0.0.1,::1
    internal-map-max-keys: 100_000
    internal-map-ttl: 5m
    warmup-min-samples: 2
    warmup-score: 0.4
    threshold-moderate: 0.2
    threshold-elevated: 0.4
    threshold-high: 0.6
    threshold-critical: 0.8
    throttle-requests-per-second: 5.0
    isolation-forest:
      enabled: false
      training-buffer-size: 10_000
      min-training-samples: 100
      retrain-interval: 5m
      random-seed: 42
      score-weight: 0.5
      sample-rate: 0.1
      fallback-score: 0.5
      num-trees: 100
      max-depth: 10
```

---

## 7. What Is Not Done (Deferred)

The following categories still contain **gaps** relative to an idealized audit wishlist (local audit copies, if any, are not tracked in git):

- **Performance:** Hot-path micro-optimizations (e.g. repeated digests/reflection, allocation patterns, BaselineStore stampede mitigations).
- **Concurrency:** BaselineStore bucket window edge cases; `CompositeScorer` internal structure under extreme contention.
- **Design / ops:** Some D-items remain (e.g. filter ordering guarantees vs. all Spring setups, graceful degradation modes, error response Content-Type). **Policy thresholds** and **rich actuator metrics** are done.
- **Edge cases:** Token age semantics, response committed handling, advanced quarantine scoping, JSON `parameterCount`, entropy gaming—see code and tests for current behavior.

**Stage 5** will require new design for **shared state** and **multi-instance** enforcement; not covered here.

---

## 8. How to Build and Test

```bash
# From repo root
mvn clean test -q
# Expect: BUILD SUCCESS

# Run demo
mvn -pl ai-sentinel-demo spring-boot:run
# Then: curl http://localhost:8080/api/hello
#       curl http://localhost:8080/actuator/sentinel
```

---

## 9. Repo Hygiene

- **`.gitignore`** — Ignores `docs/`, `design_info.txt`, build outputs (`target/`, etc.), IDE and OS cruft.
- **`LICENSE`** — MIT (see file header; also noted in `README.md`).
- **`design_info.txt`** — Removed from version control (was redundant with tracked docs); keep private notes under ignored paths if needed.

---

## 10. Next phase: strategic options

Two complementary directions are possible after Stages 0–4:

| Option | Focus | Fit |
|--------|--------|-----|
| **A1 — Stage 5 (Distributed)** | Shared store (e.g. Redis), cluster-wide quarantine/throttle, tenant isolation, coordination APIs | **Primary** next milestone for production fleet / data-platform style maturity (e.g. multi-node consistency, Netflix-class operational concerns). |
| **A2 — Strengthen ML** | Feature quality, validation harnesses, A/B comparison of statistical vs IF, hyperparameter tuning | **Incremental** wins alongside Stage 5 prep; avoids blocking distributed work on model tweaks. |

**In-repo A2 support:** `CompositeScorer` records the latest per-scorer inputs used for the blended score, and **`/actuator/sentinel`** exposes them as **`lastScoreComponents`** for side-by-side inspection (with Micrometer `aisentinel.score.*` summaries for aggregate comparison). This is a **point-in-time, last-request** snapshot—not a full offline evaluation pipeline.

**Recommendation:** Treat **A1 (Stage 5)** as the next **major** effort; continue **A2** improvements opportunistically using metrics, actuator snapshots, and external analysis (notebooks, load tests) without derailing distributed design.

---

*Last updated: docs + roadmap + score-component actuator; implementation is the source of truth.*
