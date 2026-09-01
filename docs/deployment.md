# Deployment modes and operator safety

This guide states **actual** `OFF` / `MONITOR` / `ENFORCE` behavior from the **current** Spring Boot / Servlet starter filter and core pipeline. It is the primary reference for safe adoption of the Java 21 deployable integration.

**Recommended initial deployment mode: `MONITOR`.**  
Do not enable `ENFORCE` based on synthetic regression suites alone. Application-specific monitoring, tuning, and operational validation are required first.

Property defaults (verified): `ai.sentinel.enabled=true`, `ai.sentinel.mode=MONITOR`. **Client denial requires explicit `ai.sentinel.mode=ENFORCE`** after MONITOR validation.

Full property tables: [`configuration.md`](configuration.md). Upgrade from the previous published
library line (`0.2.0`): [`migration.md`](migration.md). Validation gates: [`testing.md`](testing.md).
Architecture (security model vs Java core vs current adapter) and observability: [`../ARCHITECTURE.md`](../ARCHITECTURE.md).

---

## Deployment modes

### OFF

When `ai.sentinel.enabled=false` **or** `ai.sentinel.mode=OFF`, `SentinelFilter` returns immediately to the servlet chain.

| Behavior | Result |
|----------|--------|
| Feature extraction / scoring / policy | Not run |
| Baseline updates | Not run |
| Metrics / telemetry / training publish | Not emitted by Sentinel |
| Quarantine / throttle | Not consulted or written |
| Client response | Untouched |
| Fail-open paths | Not applicable (Sentinel never entered) |

`OFF` is a full bypass for this filter — not a soft mode that still scores.

Excluded paths (`ai.sentinel.exclude-paths`) also bypass the pipeline while leaving mode unchanged for other traffic.

---

### MONITOR

`MONITOR` **still evaluates risk**. It does **not** mean “detector off.”

The pipeline runs identity resolution, feature extraction, scoring, policy, baseline learning (subject to gated update policy), telemetry, metrics, and optional training publish. Enforcement uses:

* `DiscardingEnforcementResponse` (no client status/body writes from Sentinel);
* `MonitorOnlyEnforcementHandler` (always proceeds; emits `MONITOR_WOULD_*` intent telemetry for throttle/block/quarantine).

The filter **always continues** the chain after the pipeline. Clients are never denied by Sentinel in this mode.

| Capability | MONITOR |
|------------|---------|
| Feature extraction | Yes |
| Statistical / IF scoring | Yes (IF when enabled) |
| Baseline updates | Yes (gated; warmup always learns) |
| Risk decision | Yes |
| Metrics / telemetry | Yes |
| Training publication | Yes if configured |
| Client denial | **No** |
| Quarantine / throttle **read** | Yes (presentation may show quarantine action; client still proceeds) |
| Quarantine / throttle **write** | No (handler never applies hard enforcement) |
| Fail-open | Yes (same request-path fail-open as ENFORCE) |

---

### ENFORCE

`ENFORCE` uses a live `ServletEnforcementResponse` and the composite (optionally cluster-aware) enforcement handler. `THROTTLE`, `BLOCK`, and `QUARANTINE` can write HTTP status/body and update local (and Redis, when enabled) enforcement state.

ENFORCE does **not** guarantee perfect prevention:

* Fail-open allows the request on many evaluation failures (`FailOpenReason`).
* Startup grace (when configured) forces MONITOR **presentation** only.
* Statistical warmup applies `warmup-action` (default `MONITOR`) until live.
* An already-committed servlet response skips denial writes while state/telemetry may still record intent.
* Distributed Redis paths are fail-open and locally authoritative when Redis is unavailable.

---

## Mode capability matrix

Verified against `SentinelFilter`, `SentinelPipeline`, `SentinelDecisionEngine`, and enforcement wiring.

| Capability | OFF | MONITOR | ENFORCE |
|------------|:---:|:-------:|:-------:|
| Feature extraction | No | Yes | Yes |
| Statistical scoring | No | Yes | Yes |
| IF scoring (when enabled) | No | Yes | Yes |
| Baseline updates | No | Yes | Yes |
| Risk decision | No | Yes | Yes |
| Metrics | No | Yes | Yes |
| Telemetry | No | Yes | Yes |
| Training publication (when enabled) | No | Yes | Yes |
| Client denial | No | No | Yes |
| Quarantine/throttle state write | No | No | Yes |
| Quarantine/throttle state read | No | Yes | Yes |
| Fail-open on pipeline errors | n/a | Yes | Yes |

---

## Recommended adoption sequence

```text
integrate with enabled=true, mode=MONITOR
  → observe EvaluationStatus / OperatorEvaluationPhase, decisions, and fail-open
  → tune thresholds, scope, identity, and distributed flags
  → decide explicitly whether ENFORCE is appropriate
```

`ENFORCE` does **not** automatically follow `MONITOR`. Some deployments should remain in MONITOR indefinitely.

### Legitimate workload transitions and gated learning

Default statistical learning uses **`ALLOW_OR_MONITOR`**: after a risk decision, the baseline updates only for `ALLOW` / `MONITOR` (and always during warmup). Elevated actions (`THROTTLE` / `BLOCK` / `QUARANTINE`) do **not** train the baseline. That protects against **baseline poisoning** from anomalous traffic.

**Operational consequence:** a **legitimate permanent workload change** (deploy, batch job, new steady traffic level) can remain elevated relative to the prior baseline for as long as gated learning refuses to absorb the new pattern. Elevated risk indicates **behavioral deviation**, not proof of malicious activity.

**What does *not* automatically clear sticky elevation while traffic continues:**

* Idle **TTL** on `BaselineStore` / statistical keys — continuous requests keep keys active, so idle expiry does not act as relearning.
* Publishing a new Isolation Forest model — independent of statistical Welford state.
* Quarantine **release** alone — clears enforcement state only (see table below).

**Supported recovery today:**

1. Confirm the new workload is legitimate (MONITOR telemetry / metrics / business context).
2. **Primary per-key operator path:** ensure `ai.sentinel.statistical.relearn-mode=EXPLICIT_ONLY`, then call `BaselineLifecycle.reset(identityHash, endpoint)` so the key re-enters warmup and subsequent traffic may train a new baseline. With default `relearn-mode=DISABLED`, reset is a no-op.
3. **Process restart** clears local in-memory Welford / baseline maps (re-warmup). Redis-backed quarantine/trust may outlive local statistical state.
4. **Idle TTL** after traffic stops may expire unused keys; continuous elevated traffic refreshes access and does not clear sticky elevation.
5. **Deliberate policy change** (for example `ALWAYS`) allows continuous learning to absorb a new plateau — an operational choice, not automatic relearning.

There is **no** automatic skip-triggered relearn and **no** shadow/candidate baseline in the current line. Automatic continuous adaptation after elevated risk remains an **architecture/product decision** and must not be assumed for production **ENFORCE** readiness. Prefer MONITOR until operators understand transition handling for their traffic.

### Model registry disk retention

Trainer publish writes new versioned files under `{filesystem-root}/{tenant}/artifacts/` and updates `{tenant}/active.json` to point at the active version. **Previous `{version}.meta.json` / `{version}.payload.bin` files remain on disk** until an operator deletes them. The library does **not** implement automatic disk cleanup.

**Operator practice:**

* Treat accumulation as expected for explicit train/publish cycles (not an unbounded request-path leak).
* Keep recent versions if you need rollback; remove only artifacts no longer referenced by `active.json` and outside your retention window.
* Size the shared filesystem for expected publish frequency × artifact size.
* Serving nodes only **read** the registry (`FilesystemModelRegistry` + optional refresh); they do not prune.

### False-positive recovery (quarantine lift)

Quarantine release and baseline reset are **independent** operator actions:

1. Detect a false-positive quarantine (actuator `lastDecision`, metrics, telemetry).
2. Inspect the decision (`evaluationStatuses`, scores — invalid scores present as JSON `null` with `INVALID_SCORE`).
3. Call `EnforcementHandler.releaseQuarantine(identityHash, endpoint)` on the Spring `enforcementHandler` /
   `enforcementHandlerImpl` bean (scope-aware; idempotent; also best-effort clears Redis when cluster write is enabled).
4. Optionally call `BaselineLifecycle.reset(identityHash, endpoint)` when subsequent traffic should relearn —
   **only if** `relearn-mode` allows it. This does **not** lift quarantine by itself.
5. Monitor recovery (quarantine count gauge, `aisentinel.quarantine.released`, clear success/failure meters).

| Action | Clears quarantine? | Resets statistical baseline? |
|--------|--------------------|------------------------------|
| `releaseQuarantine` | Yes (local + best-effort cluster) | No |
| `BaselineLifecycle.reset` | No | Yes (when relearn enabled) |

There is **no** unauthenticated HTTP admin release endpoint. Inject the existing Spring beans from a secured operator tool or support path.

### What to evaluate in MONITOR

* False-positive frequency of would-be `THROTTLE` / `BLOCK` / `QUARANTINE` (telemetry / metrics).
* `WARMUP` vs `LIVE` rates and cold-start behavior after restarts.
* `DEGRADED` and `MODEL_FALLBACK` / `isolationForestScoreMode` frequency.
* `FailOpenReason` distribution (`aisentinel.failopen.reason`).
* Identity and endpoint patterns; `EnforcementScope` blast radius.
* Whether explicit baseline reset (`BaselineLifecycle` / `relearn-mode`) is understood — including legitimate permanent workload transitions under gated learning (see above).
* Registry disk retention procedure when using the filesystem model registry.
* Redis degradation gauges and timeouts when distributed features are enabled.
* Compatibility of denial status/bodies with your API clients (validated in a non-production ENFORCE trial if you proceed).

### Exit questions before ENFORCE

Answer these for **your** traffic — there is no fixed calendar duration that substitutes for evidence:

1. Are benign requests predominantly `ALLOW` / `MONITOR` as expected once live?
2. Are `WARMUP` decisions understood and not treated as abuse?
3. Are model fallbacks rare or expected for your IF configuration?
4. Are fail-open events understood and acceptable?
5. Are false-positive BLOCK/QUARANTINE rates acceptable for your users?
6. Is `EnforcementScope` configured for the intended blast radius?
7. Is baseline reset/relearn procedure understood?
8. Are Redis/distributed dependencies sized and monitored (if enabled)?
9. Are client-visible enforcement responses compatible with the application?
10. Has MONITOR run long enough to see representative traffic (peaks, deploys, identity mix)?

---

## ENFORCE safety guidance

Synthetic regression suites establish:

* deterministic detector regressions;
* synthetic attack / flood coverage;
* false-positive mitigations (warmup, gating, variance floors);
* concurrency and state-safety hardening;
* fail-open and IF-fallback **observability**;
* baseline lifecycle behavior.

They do **not** establish:

* your production false-positive or detection rates;
* coverage of every real attack pattern;
* full multi-process distributed correctness beyond Testcontainers/single-JVM evidence;
* every application-specific response interaction.

### Preconditions before enabling ENFORCE

* Successful MONITOR evaluation using the exit questions above.
* Operators understand `EvaluationStatus` / `OperatorEvaluationPhase`, `FailOpenReason`, and IF score modes.
* Policy thresholds and `EnforcementScope` reviewed for this application.
* Fail-open availability bias accepted for your threat model.
* Redis operational readiness if cluster quarantine/throttle/trust is enabled.
* Explicit baseline reset procedure known (`relearn-mode`, who may call reset).
* Alerting on DEGRADED / MODEL_FALLBACK / fail-open rates.
* Application validation of BLOCK/QUARANTINE/THROTTLE HTTP responses (including committed-response limits).

---

## Restart and cold start

### State persistence

| State | Local JVM restart | Redis / filesystem when configured | Recovery |
|-------|-------------------|--------------------------------------|----------|
| Statistical Welford baseline | Lost | Not Redis-backed | Rebuild via WARMUP → LIVE |
| Request-count `BaselineStore` | Lost | Not Redis-backed | Rebuild with traffic |
| Endpoint history (entropy/concentration) | Lost | Process-local | Rebuild with traffic |
| Baseline lifecycle / relearn counters | Lost | Process-local | Defaults after restart |
| IF training buffer | Lost | Process-local | Refill from traffic |
| In-memory IF model | Lost until reload | Filesystem registry survives | `ModelRefreshScheduler` reinstalls when refresh enabled |
| Local quarantine / throttle maps | Lost | Redis keys survive TTL when writers enabled | Empty local maps; Redis may still quarantine on read |
| Trust baseline (in-memory) | Lost | Redis trust keys survive TTL when distributed trust enabled | Fail-open in-memory until Redis healthy |

After restart, expect **statistical cold start**: new identity|endpoint keys enter `STATISTICAL_WARMUP` until enough samples. Default `warmup-action` is `MONITOR`, so warmup does not client-deny even in ENFORCE. Local quarantine/throttle maps start empty.

### Startup grace vs statistical warmup

These are different controls:

| | **Startup grace** (`startup-grace-period`) | **Statistical warmup** |
|--|--------------------------------------------|-------------------------|
| Trigger | Wall-clock since grace bean start | Sparse baseline samples for a key |
| Default | Off (`0`) | On until `warmup-min-samples` / live `n` |
| Effect | Forces enforcement **presentation** to `MONITOR` | Forces action to `warmup-action` (ALLOW/MONITOR) |
| Learning | Does **not** change baseline-update gating (uses pre-override risk action) | Warmup **always** updates so keys can leave cold start |
| Status | `RiskDecision.startupGraceActive` | `EvaluationStatus.STATISTICAL_WARMUP` / phase `WARMUP` |
| Interaction | Applied **after** warmup override; while active, also suppresses quarantine presentation override | Independent lifecycle per identity\|endpoint |

---

## Failure mode profile (availability-first)

**Canonical reference** for how AI-Sentinel behaves when optional or request-path components fail.
Behavior is **availability-first (fail-open)**: optional and many request-path errors **allow** the client request rather than deny it.
This document describes **current** behavior only — it does **not** invent fail-closed modes.

### How to read outcomes

| Outcome class | Meaning |
|---------------|---------|
| **Scored ALLOW / policy action** | Complete `RiskDecision`; client outcome follows mode (`MONITOR` never denies; `ENFORCE` may deny). |
| **Degraded decision** | Complete `RiskDecision` with `EvaluationStatus.DEGRADED`; request continues with partial context. |
| **Whole-request fail-open** | No complete decision (or filter catch-all); request **allowed**; `FailOpenReason` metrics incremented. |
| **Side-path fail-open** | Request outcome unchanged; a side effect (training publish, Redis write, lifecycle hook) was dropped. |

An allowed request is **not** proof of low risk.

### Visibility: metrics vs structured `FailOpen` telemetry

All request-path `FailOpenReason` values increment:

* `aisentinel.failopen.count`
* `aisentinel.failopen.reason{reason=...}`

Structured `FailOpen` telemetry (`TelemetryEvent.failOpen`) is emitted for **decision-engine** reasons only
(`SCORER_FAILURE`, `BASELINE_UPDATE_FAILURE`, `TRUST_*`, `RISK_FUSION_FAILURE`, `BASELINE_UPDATE_POLICY_FAILURE`).

Pipeline / filter reasons record **metrics + logs** but **do not** emit structured `FailOpen` telemetry today:

| Reason | Where recorded | Structured `FailOpen` telemetry? |
|--------|----------------|----------------------------------|
| `SCORER_FAILURE` | Decision engine | Yes |
| `BASELINE_UPDATE_FAILURE` | Decision engine | Yes |
| `TRUST_EVALUATION_FAILURE` | Decision engine | Yes |
| `RISK_FUSION_FAILURE` | Decision engine | Yes |
| `TRUST_POLICY_FAILURE` | Decision engine | Yes |
| `BASELINE_UPDATE_POLICY_FAILURE` | Decision engine | Yes |
| `IDENTITY_RESOLUTION_FAILURE` | Pipeline | No (metrics + debug log) |
| `FEATURE_EXTRACTION_FAILURE` | Pipeline | No (metrics + debug log) |
| `FILTER_FAILURE` | Servlet filter catch-all | No (metrics + warn log) |
| `BASELINE_LIFECYCLE_FAILURE` | Baseline lifecycle (off request path) | No (metrics + debug log) |

Redis quarantine / throttle / trust fail-open uses **dedicated** meters
(`aisentinel.distributed.*`, `aisentinel.identity.trust.baseline.redis.*`) and is **not** double-counted as `FailOpenReason`.

### Failure matrix (request outcome)

| Failure | Request outcome | Operator signal | Notes |
|---------|-----------------|-----------------|-------|
| **Detector / scorer throw** (`AnomalyScorer#score`) | **Allowed** (no `RiskDecision`) | `SCORER_FAILURE` metrics + `FailOpen` telemetry | Pipeline returns proceed |
| **Baseline / scorer `update` throw** | **Allowed** (no `RiskDecision`) | `BASELINE_UPDATE_FAILURE` metrics + `FailOpen` telemetry | Score succeeded; learning aborted for this observation |
| **Feature extraction throw** | **Allowed** (no scoring) | `FEATURE_EXTRACTION_FAILURE` metrics | Early return from pipeline |
| **Identity context resolver throw** | Scoring **continues** without identity context | `IDENTITY_RESOLUTION_FAILURE` metrics | Trust/fusion may be absent for that request |
| **Trust lookup / evaluation throw** | Decision continues **without** trust enrichment (`DEGRADED`) | `TRUST_EVALUATION_FAILURE` metrics + `FailOpen` telemetry | Availability over trust completeness |
| **Risk fusion throw** | Decision continues with **anomaly score only** (`DEGRADED`) | `RISK_FUSION_FAILURE` metrics + `FailOpen` telemetry | |
| **Trust-policy adjuster throw** | Decision continues with **pre-adjust** action (`DEGRADED`) | `TRUST_POLICY_FAILURE` metrics + `FailOpen` telemetry | |
| **Baseline-update policy throw** | Decision completes; **update skipped** (`DEGRADED`) | `BASELINE_UPDATE_POLICY_FAILURE` metrics + `FailOpen` telemetry | |
| **Filter catch-all** (any uncaught exception around pipeline) | **Allowed** via `filterChain.doFilter` | `FILTER_FAILURE` metrics + warn log | Includes unexpected enforcement-path throws that escape the pipeline |
| **Enforcement HTTP write failure / committed response** | Denial **write skipped**; local quarantine/throttle state may still apply | Debug log; decision telemetry may still record intent | Not a `FailOpenReason`; client may still receive an upstream response |
| **Cluster quarantine Redis read failure** | Treated as **not** cluster-quarantined (local quarantine still authoritative) | `aisentinel.distributed.*` meters / degraded gauges | Fail-open |
| **Cluster quarantine Redis write failure** | Local quarantine **retained**; cluster publish dropped | Distributed meters / debug | Does not roll back local state |
| **Cluster throttle Redis failure** | Throttle check **allows**; local per-node throttle still runs | Distributed throttle meters | Fail-open |
| **Distributed trust Redis failure** | Falls back to **in-memory** trust store | Trust Redis meters | In-memory path is `baseline-max-keys`-bounded |
| **Training publish / trainer-side failure** | Request outcome **unchanged** | Training publish failure meters / trainer logs | Async, bounded, fail-open drop |
| **Identity response hook throw** | Request outcome **unchanged** | Debug log only | After pipeline |
| **IF local retrain / model registry refresh failure** | Serving continues on last good model or IF fallback mode | Retrain/registry logs and IF score-mode meters | Off request path for registry; does not deny clients |

### Operator expectations

* Prefer **`MONITOR`** until fail-open rates, `DEGRADED`, and Redis degradation gauges are understood for your traffic.
* Alert on sustained `aisentinel.failopen.reason` and distributed degraded gauges — not only on denial counts.
* Do **not** treat “request allowed” as “scored low risk.”
* There is **no** library fail-closed profile today; changing that would be a future product decision, not current behavior.

Related property detail: [`configuration.md`](configuration.md) (fail-open reporting, Redis matrix). Architecture overview: [`../ARCHITECTURE.md`](../ARCHITECTURE.md).

---

## Distributed deployment notes

* Local single-instance behavior is the default and fully exercised in unit/integration tests.
* Redis quarantine/throttle/trust and Kafka training publish are **optional**, fail-open, and validated with Testcontainers where Docker is available.
* Testcontainers / single-JVM suites are **not** a substitute for production multi-process proof.

### Distributed test coverage (what is / is not proven)

| Area | Automated coverage today | Still untested here |
|------|--------------------------|---------------------|
| Cluster quarantine write → Redis → read | Testcontainers single-JVM; second Lettuce client stands in for a peer | Separate OS processes / hosts; rolling restart races across nodes |
| Cluster throttle | Unit tests with mocked Redis; fail-open paths | Live Redis Testcontainers throttle under concurrent JVMs |
| Distributed trust baselines | Unit tests with mocked Redis + fail-open fallback | Multi-JVM trust continuity under partition |
| Training publish / Kafka | Publisher unit / bounded fail-open tests | Broker E2E across publishers and trainer instances |
| Multi-host networking / K8s | — | Not in scope of library CI |

Operators should validate Redis timeouts, degraded gauges, and peer visibility in **their** topology before relying on ENFORCE with distributed flags.

* Prefer MONITOR until distributed degraded gauges, timeouts, and cardinality are understood in your environment.
* Redis round-trips on quarantine read, throttle (THROTTLE path), and distributed trust are part of the request-path latency budget — see the matrix in [`configuration.md`](configuration.md).
* Redis key cardinality for quarantine/throttle/trust is **TTL-only**; the library does not hard-cap Redis memory. Shared Redis `maxmemory` / eviction policy is a deployment decision.
* Local map defaults (`baseline-max-keys` / `internal-map-max-keys` = 100 000) can be memory-heavy at full occupancy — size to active cardinality with headroom.

---

## Observability cheat sheet

| Signal | Operator meaning |
|--------|------------------|
| `WARMUP` | Cold-start; not confirmed abuse |
| `LIVE` | Statistical path live |
| `MODEL_FALLBACK` | IF used fallback score — not silent “statistical only”; composite uses statistical score only until a model is loaded |
| `DEGRADED` | Optional subsystem failed; decision still produced |
| `FailOpenReason` | Why a request was allowed after an error |
| `isolationForestScoreMode` | `MODEL` vs `FALLBACK_NO_MODEL` vs `FALLBACK_INVALID` |
| `/actuator/sentinel` `lastDecision` | Last completed decision on **this JVM** (action, scores, phases, IF mode, statistical dominant signal, structured `riskFactors` / optional `securityAdvice`) — not cluster history; no identity/endpoint. Advice is operator guidance only and does not change enforcement. |

See the full observability meter list in [`../ARCHITECTURE.md`](../ARCHITECTURE.md) § Observability.
