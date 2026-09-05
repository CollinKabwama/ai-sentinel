# Changelog

All notable changes to AI-Sentinel are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
for the published library line.

## [Unreleased]

### Added

- Opt-in JMH **benchmark foundation** module (`ai-sentinel-benchmark`) for in-process latency/throughput measurement (not an SLA gate). See [`docs/performance/BENCHMARKING.md`](docs/performance/BENCHMARKING.md).

### Changed

- Operator docs now state that **0.3.0** is published to Maven Central (tag `v0.3.0`). japicmp still compares against **0.2.0** until a separate baseline retarget.

### Fixed

## [0.3.0] — 2026-08-30

First **stable baseline** of the AI-Sentinel library line after published 0.2.0 and the unreleased 0.2.1 development series.
**Recommended initial deployment mode remains `MONITOR`.** This release does **not** claim
production-ready `ENFORCE` from synthetic tests alone.

See also: [`docs/migration.md`](docs/migration.md) · [`docs/deployment.md`](docs/deployment.md) ·
[`docs/testing.md`](docs/testing.md)

### Breaking

- Removed deprecated `EnforcementHandler.isQuarantined(String identityHash)`. Quarantine lookup requires both identity and endpoint: `isQuarantined(String identityHash, String endpoint)`. Source callers and already compiled binaries invoking the removed interface method must migrate and recompile. See [`docs/migration.md`](docs/migration.md#quarantine-lookup-requires-identity-and-endpoint).

### Changed

- **Default operating mode is `MONITOR`** — `ai.sentinel.mode` defaults to observe/learn with no client denial. Explicit `ai.sentinel.mode=ENFORCE` is required for client-facing denial. Upgrades from 0.2.0, or unreleased 0.2.1 development trees, that relied on the previous implicit `ENFORCE` default must set `ENFORCE` explicitly. See [`docs/migration.md`](docs/migration.md).
- **Remote `EvaluationResponse` forward compatibility** — Java `RemoteEvaluationClient` ignores unknown additive JSON response fields via an isolated reader copy (caller `ObjectMapper` is not mutated). Malformed known fields still fail open.
- Pipeline and actuator diagnostic snapshots depend on `CompositeScoreSnapshotSource` rather than requiring the concrete `CompositeScorer` type for field storage (public constructors that accept `CompositeScorer` remain supported).
- Redis trust baseline key encoding reuses a thread-local SHA-256 digest instead of allocating a new `MessageDigest` on every encode. Redis key format and trust semantics are unchanged.
- Request-path scoring and feature extraction reuse the timestamp captured during feature extraction instead of calling `System.currentTimeMillis()` repeatedly on the same request.
- Telemetry emission reuses Micrometer counters per event type and builds JSON log payloads without stream collectors.
- Hot-path scorer, baseline, and enforcement maps use structured identity|endpoint keys instead of per-call string concatenation; wire/storage shape is unchanged.

### Added

- **ASP.NET Core reference adapter** (`dotnet/`) — thin remote client for the evaluation contract; middleware, sample app, cross-language fixtures, and opt-in live E2E test. No C# behavioral engine.
- **Evaluation status contributor SPI** — scorers may implement `EvaluationStatusContributor` so operational markers appear without status code knowing concrete scorer types. Engine-owned statuses cannot be injected by untrusted contributors.
- **Feature schema contract** — `FeatureSchema` publishes layout version and explicit statistical / Isolation Forest / export dimensions and ordered names; training export/parser align on the same constants.
- **Public API compatibility gate** — Maven profile `api-compatibility` runs japicmp for published modules against the configured Central baseline (**0.2.0** at this release, with approved exclusions). Retargeting that baseline to **0.3.0** is a follow-up after publication.

### Documentation

- Clarified deployable surfaces: Java 21 core + Spring Boot/Servlet, remote evaluation API, and ASP.NET Core reference adapter (not a native .NET engine).
- Documented filesystem model-registry retention: new publishes do not delete prior artifact files; operators prune obsolete versions.
- Documented gated baseline learning vs legitimate permanent workload transitions; idle TTL is not automatic relearning; supported recovery includes explicit reset (when enabled), process restart, idle expiry after traffic stops, and deliberate policy changes.
- Clarified behavioral feature trust boundary (client-influenced features vs authenticated identity; Java `ipBucket` from `remoteAddr`).
- Stated Java **21** as the supported/tested build/CI baseline.
- Documented migration from identity-only quarantine lookup to the endpoint-aware form.
- Documented the configuration default change (`ENFORCE` → `MONITOR`) and remote response unknown-field tolerance.

### Compatibility / Migration

- **API break:** one-argument `isQuarantined(String)` removed — use the two-argument form.
- **Behavioral default:** `ai.sentinel.mode` default is now `MONITOR` (was `ENFORCE` on 0.2.0 / unreleased 0.2.1).
- Binary compatibility vs published **0.2.0** is validated with japicmp (approved exclusions only for the intentional quarantine API removal and a pre-existing auto-configuration `@Bean` signature difference).

### Known limitations

- Production-ready ENFORCE without real-traffic validation is **not** claimed
- Partner / production latency evidence remains open
- Automatic baseline relearning is not offered
- Fail-closed request path is not offered
- WebFlux / reactive servlet adapter is not included
- Isolation Forest per-feature attribution / SHAP is not included
- Formal JMH / SLA certification is not included
- Kafka trainer real-broker E2E is not included
- `RiskDecision` retains a reference to the per-request `RequestContext` (not a deep-copy audit snapshot)

## [0.2.0] — 2026-08-09

Operator-facing hardening of statistical learning, observability, and deployment guidance.
**Recommended first deployment mode remains `MONITOR`.** This release does **not** claim
production-ready `ENFORCE` from synthetic tests alone.

See also: [`docs/migration.md`](docs/migration.md) · [`docs/deployment.md`](docs/deployment.md) ·
[`docs/testing.md`](docs/testing.md)

### Added

- **Evaluation lifecycle on decisions** — `RiskDecision.evaluationStatuses` carries markers such as
  `STATISTICAL_WARMUP`, `STATISTICAL_LIVE`, `COMPLETE`, `BASELINE_UPDATE_SKIPPED`,
  `BASELINE_RELEARNED`, Isolation Forest model/fallback markers, and optional-path `DEGRADED`.
  Operator-facing phase aliases are documented alongside Actuator output.
- **Configurable warmup enforcement** — `ai.sentinel.warmup-action` (`ALLOW` or `MONITOR`, default
  `MONITOR`) controls the action while statistical warmup is active. Numeric `warmup-score` remains
  telemetry / fusion input only.
- **Gated statistical baseline updates** — `ai.sentinel.statistical.baseline-update-policy`
  (default `ALLOW_OR_MONITOR`) decides when online Welford updates run after the risk decision.
  Warmup always learns so cold-start can leave warmup under gated modes.
- **Explicit baseline reset** — `ai.sentinel.statistical.relearn-mode` (`DISABLED` default, or
  `EXPLICIT_ONLY`) with `BaselineLifecycle.reset(...)`. Automatic skip-triggered relearn is not offered.
- **Decision explanation evidence** — request-scoped statistical / composite explanation on the
  request context; Actuator `lastDecision` summarizes the last completed decision on this JVM
  (no identity or raw request identifiers).
- **Committed-response awareness** — `EnforcementResponse.isCommitted()` (default `false`); when
  another filter has already committed the HTTP response, Sentinel skips status/body mutation,
  still applies local quarantine/throttle intent, and still emits telemetry.
- **Fail-open observability** — dedicated fail-open reasons / meters for optional-path and
  distributed failures (availability-preserving behavior retained by design).
- **Architecture tests** — core independence (no Spring/servlet/reactor on the core classpath),
  starter servlet types confined to `autoconfigure.web`, and scorer replaceability coverage.

### Changed

- **Warmup no longer collides with THROTTLE by accident** — cold-start identities use
  `warmup-action` (default `MONITOR`) instead of mapping `warmup-score=0.4` through policy bands.
- **Default learning no longer trains on elevated risk** — under `ALLOW_OR_MONITOR`, observations
  that resolve to THROTTLE / BLOCK / QUARANTINE do not update the statistical baseline (use
  `ALWAYS` only when you intentionally want unconditional learning).
- **Isolation Forest fallback does not dilute the composite score** — IF contributes to the
  weighted blend only when inference mode is `MODEL`. Fallback scores remain visible in telemetry /
  Actuator but are excluded from the blend.
- **Near-zero variance mitigation** — role-aware measurement-resolution floors and per-feature
  `|z|` caps; identity-like hash/IP ordinals are excluded from the statistical feature vector.
- **`requestsPerWindow` resolution floor** — unit growth while a rolling window fills stays in
  ALLOW/MONITOR under default gating so baselines can establish; after volume plateaus, benign
  traffic converges to ALLOW. Abrupt volume shocks still saturate.
- **BaselineStore / StatisticalScorer lifetime alignment** — shared `baseline-ttl` /
  `baseline-max-keys`, throttled idle expiry, serialized capacity eviction, and safer concurrent
  bucket updates.
- **Token-age future skew** — ordinary future offsets within a fixed skew window clamp to fresh;
  materially future timestamps are treated as missing/invalid (not an unbounded clamp to zero).
- **Redis / distributed operational hardening** — bounded lookup waits, DEBUG key redaction
  guidance, TTL-oriented cardinality notes, and request-path latency budgets (Redis remains
  fail-open to local semantics when unavailable).
- **Operator documentation** — MONITOR-first adoption, OFF/MONITOR/ENFORCE matrix, ENFORCE
  preconditions, restart/cold-start notes, and availability-first failure-mode profile in
  [`docs/deployment.md`](docs/deployment.md) / [`docs/configuration.md`](docs/configuration.md).

### Fixed

- Shared-state concurrency issues in baseline eviction, rolling bucket prune/count, and composite
  scorer registration.
- Same-identity enforcement concurrency and restart/recovery regressions for in-memory state.
- Request-scoped explanation / telemetry races under concurrent scoring.
- Benign established traffic escalating to QUARANTINE under default gated learning during
  rolling-window fill (resolution-floor fix above).

### Security

- Client-influenced features (for example Content-Length and token issued-at) remain weak signals;
  gated learning and z-caps limit their blast radius — see known limitations in the README.
- Automatic baseline relearn after consecutive update skips was removed; it allowed elevated
  traffic to both trigger reset and train post-reset warmup.

### Documentation

- Public migration guide: [`docs/migration.md`](docs/migration.md)
- Characterization / release-gate testing: [`docs/testing.md`](docs/testing.md)
- Release publishing: [`RELEASING.md`](RELEASING.md)

### Compatibility notes

- **Known break (framework-neutral core):** custom core SPIs now take `HttpRequestView` /
  `EnforcementResponse` instead of servlet types. Starter auto-config consumers are unaffected.
  See [`docs/migration.md`](docs/migration.md).
- **`RiskDecision`:** the canonical record includes `evaluationStatuses`. Use
  `RiskDecision.of(...)` for the previous six-field shape (empty statuses).
- New baseline / lifecycle / explanation APIs are additive for applications that do not implement
  custom decision construction.

### Not claimed in this release

- Production-ready ENFORCE without real-traffic validation
- Isolation Forest per-feature attribution / SHAP
- Hard global Redis key cardinality caps
- Automatic baseline relearning
- Fail-closed request path
- Multi-host distributed end-to-end proof beyond documented Testcontainers coverage
- WebFlux / reactive servlet support

## [0.1.0] — 2026-07-28

Initial Maven Central library line: Spring Boot starter, framework-independent scoring core,
local MONITOR/ENFORCE deployment, optional Isolation Forest and distributed integrations.

[Unreleased]: https://github.com/CollinKabwama/ai-sentinel/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/CollinKabwama/ai-sentinel/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/CollinKabwama/ai-sentinel/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/CollinKabwama/ai-sentinel/releases/tag/v0.1.0
