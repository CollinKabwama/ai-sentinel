# Changelog

All notable changes to AI-Sentinel are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
for the published library line.

## [Unreleased]

Development line after **0.2.0** (tree version **0.2.1**). No published Central release yet for this line.

### Added

- **ASP.NET Core reference adapter** (`dotnet/`) — thin remote client for the Step-8/9 evaluation contract; middleware, sample app, cross-language fixtures, and opt-in live E2E test. No C# behavioral engine.

### Documentation

- Clarified deployable surfaces: Java 21 core + Spring Boot/Servlet, remote evaluation API, and ASP.NET Core reference adapter (not a native .NET engine).
- Documented filesystem model-registry retention: new publishes do not delete prior artifact files; operators prune obsolete versions.
- Documented gated baseline learning vs legitimate permanent workload transitions; idle TTL is not automatic relearning; explicit `BaselineLifecycle.reset` when enabled.
- Clarified behavioral feature trust boundary (client-influenced features vs authenticated identity; Java `ipBucket` from `remoteAddr`).
- Stated Java **21** as the supported/tested build/CI baseline.

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

[Unreleased]: https://github.com/CollinKabwama/ai-sentinel/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/CollinKabwama/ai-sentinel/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/CollinKabwama/ai-sentinel/releases/tag/v0.1.0
