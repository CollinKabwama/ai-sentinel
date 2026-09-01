# Migrating to AI-Sentinel 0.3.0

This guide covers upgrading from the published **0.2.0** line, or unreleased **0.2.1** development trees, to **0.3.0**. Notes for **0.1.0 → 0.2.0** remain below for operators still on the earlier line.

**Recommended first deployment mode remains `MONITOR`.** From **0.3.0**, `MONITOR` is also the **library default**. Do not enable `ENFORCE` based on
synthetic suites alone — see [`deployment.md`](deployment.md).

For the full user-facing change list, see the root [`CHANGELOG.md`](../CHANGELOG.md).

---

## Dependency version

```xml
<dependency>
  <groupId>dev.aisentinel</groupId>
  <artifactId>ai-sentinel-spring-boot-starter</artifactId>
  <version>0.3.0</version>
</dependency>
```

---

## 0.2.0 / unreleased 0.2.1 → 0.3.0 (required reading)

### 1. Public API break — quarantine lookup

The deprecated identity-only quarantine check has been removed from `EnforcementHandler`.

| Previous | Current |
|----------|---------|
| `isQuarantined(String identityHash)` | **Removed** |
| — | `isQuarantined(String identityHash, String endpoint)` |

**Who is affected:** source callers that invoked the one-argument overload must pass the request endpoint. Already compiled binaries that invoke the removed interface method may fail at runtime after upgrade until recompiled.

**Who is not affected:** typical starter consumers, callers already using the two-argument form, and custom `EnforcementHandler` implementations that merely implement the interface without calling the removed method.

```java
// Before (removed)
handler.isQuarantined(identityHash);

// After
handler.isQuarantined(identityHash, endpoint);
```

Pass the same endpoint used for enforcement (request path / normalized endpoint). Do not substitute a blank or wildcard endpoint merely to restore the old call shape — quarantine may be endpoint-scoped.

### 2. Behavioral / configuration default — operating mode

| Property | 0.2.0 / unreleased 0.2.1 default | 0.3.0 default | Required user action |
|----------|---------------|---------------|----------------------|
| `ai.sentinel.mode` | `ENFORCE` | `MONITOR` | If you relied on **implicit** enforcement under 0.2.0 or an unreleased 0.2.1 development tree, set `ai.sentinel.mode=ENFORCE` explicitly to retain client denial. |

This is a **behavioral / configuration default** change (not a Java method signature break). Explicit `MONITOR`, `ENFORCE`, and `OFF` bindings are unchanged.

### 3. Remote response forward compatibility

Java `RemoteEvaluationClient` ignores unknown additive `EvaluationResponse` JSON fields without mutating a caller-supplied `ObjectMapper`. Malformed known fields still fail open (`ALLOW`, `proceed=true`, no fabricated scores/factors/advice).

### 4. Suggested upgrade checklist

1. Bump the starter dependency to `0.3.0`.
2. If you previously relied on the 0.2.0 / unreleased 0.2.1 implicit `ENFORCE` default, set `ai.sentinel.mode=ENFORCE` explicitly; otherwise leave the new `MONITOR` default.
3. Migrate any one-argument `isQuarantined` callers to the two-argument form.
4. Leave `baseline-update-policy=ALLOW_OR_MONITOR` and `warmup-action=MONITOR` unless you have a deliberate reason to change them.
5. Re-run integration tests and the characterization release gate ([`testing.md`](testing.md)).
6. Review Actuator `lastDecision` / evaluation phases in a staging environment before ENFORCE.

You do **not** need to adopt deferred roadmap items (additional detectors, WebFlux, fail-closed profiles, formal SLA tooling) to upgrade.

---

## Behavior and configuration matrix (0.1.0 → 0.2.0)

| Change | Previous (0.1.0) | New (0.2.0) | Required user action |
|--------|------------------|-------------|----------------------|
| Statistical warmup enforcement | Numeric `warmup-score` (default `0.4`) mapped through policy → collided with THROTTLE | `ai.sentinel.warmup-action` (default `MONITOR`) while `STATISTICAL_WARMUP`; score is telemetry/fusion only | None for defaults. To force ALLOW during warmup: `warmup-action=ALLOW`. `THROTTLE`/`BLOCK`/`QUARANTINE` are not valid warmup actions. |
| Baseline learning | Unconditional online updates (always-learn behavior) | Default `ai.sentinel.statistical.baseline-update-policy=ALLOW_OR_MONITOR` (skips THROTTLE+) | Retest score distributions. Set `ALWAYS` only if you intentionally want unconditional learning. |
| Baseline reset / relearn | No controlled public reset API; automatic skip-triggered relearn briefly existed then was removed | `relearn-mode=DISABLED` (default) or `EXPLICIT_ONLY` + `BaselineLifecycle.reset` | Remove obsolete `AFTER_CONSECUTIVE_SKIPS` / `relearn-after-consecutive-skips`. Enable `EXPLICIT_ONLY` only deliberately. |
| Baseline TTL / max keys | Store and scorer lifetimes could diverge | Shared `ai.sentinel.baseline-ttl` / `baseline-max-keys` | If you sized Welford via `internal-map-*` alone, move intent to `baseline-*`. |
| Isolation Forest composite blend | Fallback scores could dilute the composite toward a mid-band | IF weight applies only when score mode is `MODEL` | Expect different composites when no model is loaded; inspect Actuator IF mode. |
| Decision lifecycle | Limited / undocumented warmup vs live distinction | `RiskDecision.evaluationStatuses` + Actuator `lastDecision` | Update custom decision consumers for the new record component (or use `RiskDecision.of`). |
| Committed HTTP responses | Denial writes could race a committed response | `EnforcementResponse.isCommitted()`; skip body/status mutation when committed | Prefer filter order so Sentinel can write denials when early blocking is required (`ai.sentinel.filter-order`). |
| Token age future skew | Unbounded future issued-at could collapse age toward “fresh” | Skew ≤300s → clamp to `0`; beyond → missing/invalid (`-1`) | Stop relying on arbitrarily-future `X-Token-Issued-At` to neutralize the feature. |
| Benign volume establishment | Unit `requestsPerWindow` growth under gated learning could freeze early and escalate | Resolution floor keeps window-fill in ALLOW/MONITOR; plateau → ALLOW | Expect MONITOR while the rolling window fills, then ALLOW once volume is stable. |
| Deployment guidance | Sparse mode guidance | MONITOR-first docs, ENFORCE preconditions, failure-mode profile | Read [`deployment.md`](deployment.md) before changing `ai.sentinel.mode`. |

---

## Configuration defaults to review (0.3.0)

| Property | Default | Notes |
|----------|---------|--------|
| `ai.sentinel.enabled` | `true` | |
| `ai.sentinel.mode` | `MONITOR` | Was `ENFORCE` on 0.2.0 / unreleased 0.2.1; explicit `ENFORCE` required for client denial |
| `ai.sentinel.warmup-min-samples` | `2` | |
| `ai.sentinel.warmup-score` | `0.4` | Telemetry / fusion only |
| `ai.sentinel.warmup-action` | `MONITOR` | `ALLOW` or `MONITOR` only |
| `ai.sentinel.statistical.baseline-update-policy` | `ALLOW_OR_MONITOR` | Also: `ALWAYS`, `ALLOW_ONLY`, `SCORE_BELOW_THRESHOLD` |
| `ai.sentinel.statistical.baseline-update-score-threshold` | `0.4` | Only for `SCORE_BELOW_THRESHOLD` |
| `ai.sentinel.statistical.relearn-mode` | `DISABLED` | Or `EXPLICIT_ONLY` |
| `ai.sentinel.baseline-ttl` | `5m` | Shared store + Welford idle lifetime |
| `ai.sentinel.baseline-max-keys` | `100000` | Shared cardinality bound (in-memory) |
| `ai.sentinel.startup-grace-period` | `0` | Presentation MONITOR; not a learning gate |
| `ai.sentinel.isolation-forest.score-weight` | `0.5` | Applied only in `MODEL` mode |

Full reference: [`configuration.md`](configuration.md).

---

## Compatibility break: servlet types → framework-neutral views (0.2.0)

Custom integrations that implement core SPIs against **servlet** types must move to:

| Previous concept | New SPI types |
|------------------|---------------|
| Servlet request / response in core SPIs | `HttpRequestView` / `EnforcementResponse` |

**Why:** the decision core is framework-independent (no servlet/Spring on the core classpath).
The Spring Boot starter still adapts servlet requests for you.

**Who is affected:** applications that implement custom core SPIs or call core APIs with servlet
types directly.

**Who is not affected:** applications that only depend on `ai-sentinel-spring-boot-starter` and
configure properties / optional beans without custom servlet-typed SPIs.

---

## API additions (additive for typical consumers)

| API | Notes |
|-----|--------|
| `EvaluationStatus`, `RiskDecision.evaluationStatuses`, `RiskDecision.hasStatus` | Lifecycle markers |
| `RiskDecision.of(...)` | Six-field factory with empty statuses |
| `BaselineUpdatePolicy` / `BaselineUpdateMode` / `ConfigurableBaselineUpdatePolicy` | Gated learning |
| `BaselineLifecycle` / `BaselineRelearnMode` | Explicit reset only |
| `EnforcementResponse.isCommitted()` | Default method; default `false` |
| Explanation evidence on `RequestContext` / Actuator `lastDecision` | Diagnostic; JVM-local last decision |
| `EvaluationStatusContributor` | Optional scorer status contribution |
| `FeatureSchema` | Explicit feature layout version and dimensions |

Binary/source consumers that constructed the old six-component `RiskDecision` compact form must
switch to `RiskDecision.of(...)` or pass an `evaluationStatuses` set.

---

## Related docs

| Doc | Use when |
|-----|----------|
| [`CHANGELOG.md`](../CHANGELOG.md) | Reviewing user-facing changes in this release line |
| [`configuration.md`](configuration.md) | Looking up exact property names and defaults |
| [`deployment.md`](deployment.md) | Choosing OFF / MONITOR / ENFORCE and fail-open posture |
| [`testing.md`](testing.md) | Validating a build with the characterization release gate |
| [`../ARCHITECTURE.md`](../ARCHITECTURE.md) | Understanding runtime components and replaceability |
| [`../SECURITY.md`](../SECURITY.md) | Threat-model notes and reporting process |
| [`../RELEASING.md`](../RELEASING.md) | Publishing artifacts (maintainers) |
| [`README.md`](README.md) | Docs layout and reading order |
