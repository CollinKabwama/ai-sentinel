# Deployment modes and operator safety

This guide states **actual** `OFF` / `MONITOR` / `ENFORCE` behavior from the Spring Boot starter filter and core pipeline. It is the primary reference for safe adoption.

**Recommended initial deployment mode: `MONITOR`.**  
Do not enable `ENFORCE` based on synthetic regression suites alone. Application-specific monitoring, tuning, and operational validation are required first.

Property defaults (verified): `ai.sentinel.enabled=true`, `ai.sentinel.mode=ENFORCE`. The library default is enforcement-capable; **operators should override to `MONITOR` during adoption.**

Full property tables: [`configuration.md`](configuration.md). Architecture and observability: [`../ARCHITECTURE.md`](../ARCHITECTURE.md).

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

### What to evaluate in MONITOR

* False-positive frequency of would-be `THROTTLE` / `BLOCK` / `QUARANTINE` (telemetry / metrics).
* `WARMUP` vs `LIVE` rates and cold-start behavior after restarts.
* `DEGRADED` and `MODEL_FALLBACK` / `isolationForestScoreMode` frequency.
* `FailOpenReason` distribution (`aisentinel.failopen.reason`).
* Identity and endpoint patterns; `EnforcementScope` blast radius.
* Whether explicit baseline reset (`BaselineLifecycle` / `relearn-mode`) is understood.
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

## Failure and degraded behavior

Distinguish:

1. **Normal low-risk ALLOW** — scored decision.
2. **MONITOR mode / warmup / grace presentation** — decision computed; client not denied by Sentinel.
3. **Degraded evaluation** — full `RiskDecision` with `EvaluationStatus.DEGRADED` after optional-path failure (trust/fusion/trust-policy); request continues with partial context.
4. **Whole-request fail-open** — no complete decision (e.g. scorer failure, feature extraction failure) or filter catch-all; request allowed; `FailOpenReason` + `FailOpen` telemetry / `aisentinel.failopen.reason`.

An allowed request is **not** proof of low risk.

---

## Distributed deployment notes

* Local single-instance behavior is the default and fully exercised in unit/integration tests.
* Redis quarantine/throttle/trust and Kafka training publish are **optional**, fail-open, and validated with Testcontainers where Docker is available.
* Testcontainers / single-JVM suites are **not** a substitute for production multi-process proof.
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
| `MODEL_FALLBACK` | IF used fallback score — not silent “statistical only” |
| `DEGRADED` | Optional subsystem failed; decision still produced |
| `FailOpenReason` | Why a request was allowed after an error |
| `isolationForestScoreMode` | `MODEL` vs `FALLBACK_NO_MODEL` vs `FALLBACK_INVALID` |

See the full observability meter list in [`../ARCHITECTURE.md`](../ARCHITECTURE.md) § Observability.
