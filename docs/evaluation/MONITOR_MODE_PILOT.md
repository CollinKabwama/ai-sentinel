# MONITOR-mode pilot readiness

Bounded Spring Boot / Servlet MONITOR-mode pilot workflow for collecting
pilot-scoped, HMAC-pseudonymized behavioral-risk observations without changing
application request outcomes under the supported pilot configuration.

This document describes **repository-level pilot readiness**. It is not a
deployment guide, not customer onboarding, and not evidence that an external
pilot has occurred.

## Purpose

Allow a future authorized external evaluator to collect bounded observational
evidence from AI-Sentinel running in MONITOR mode, using a local file sink and
a verifiable artifact set.

## Supported integration scope

Reference surface only:

- Spring Boot / Servlet starter
- `SentinelFilter`
- `ai.sentinel.mode=MONITOR`

This release does **not** provide framework-independent live pilot collection.

## Non-enforcement model

When pilot evidence collection is active, AI-Sentinel must be in MONITOR mode
and must not alter the HTTP request outcome.

Three-layer protection (unchanged):

1. `MonitorOnlyEnforcementHandler` does not delegate denying actions
   (`THROTTLE` / `BLOCK` / `QUARANTINE`).
2. `DiscardingEnforcementResponse` prevents Sentinel response mutation.
3. `SentinelFilter` always continues the filter chain in MONITOR.

Pilot observations record a **proposed** (risk-derived) action. They never
claim that action was enforced.

Example observation semantics:

```text
riskDerivedAction = BLOCK
runtimeMode = MONITOR
enforcementApplied = false
requestOutcome = CONTINUED
```

Use wording such as **proposed action** or **would-block observation**.
Do not say **blocked requests** for MONITOR pilot evidence.

## Pilot configuration requirements

Enable only when all of the following hold:

| Requirement | Property / condition |
|-------------|----------------------|
| Starter enabled | `ai.sentinel.enabled=true` |
| MONITOR mode | `ai.sentinel.mode=MONITOR` |
| Pilot collection on | `ai.sentinel.pilot.enabled=true` |
| Local output directory | `ai.sentinel.pilot.output-directory` (empty or non-existent) |
| HMAC secret | `ai.sentinel.pilot.pseudonymization-secret` (≥ 16 UTF-8 bytes) |
| Training publish OFF | `ai.sentinel.distributed.training-publish-enabled=false` |
| Supported enforcement wiring | Default MONITOR `MonitorOnlyEnforcementHandler` (no custom `EnforcementHandler` bean) |

Optional:

- `ai.sentinel.pilot.session-id` — operator-provided session id; otherwise generated at startup.

Example (local synthetic / authorized evaluator lab only):

```yaml
ai:
  sentinel:
    enabled: true
    mode: MONITOR
    distributed:
      training-publish-enabled: false
    pilot:
      enabled: true
      output-directory: /tmp/ai-sentinel-pilot-session
      session-id: evaluator-lab-001
      # Inject via env; never commit real secrets:
      # AI_SENTINEL_PILOT_PSEUDONYMIZATION_SECRET
      pseudonymization-secret: ${AI_SENTINEL_PILOT_PSEUDONYMIZATION_SECRET}
```

Startup validation **fails closed for evidence collection configuration** when
requirements are unmet (wrong mode, training publish on, missing secret/directory,
or unsupported custom enforcement wiring). Application traffic is never denied
by the pilot sink itself.

### Reference pilot constraints

Prefer no Redis / Kafka / cluster-quarantine / training-publish integrations for
reference pilot v1. Distributed features remain available for other deployments;
pilot readiness does not require them and rejects training publish when pilot
collection is enabled.

## Pseudonymization

Do **not** persist the unsalted pipeline identity hash.

Persist a pilot-scoped HMAC-SHA256 pseudonym:

```text
HMAC-SHA256(pilotSecret, existingPipelineIdentityKey)
```

Properties:

- no raw email / username / IP in evidence
- resistance to simple dictionary attacks on persisted evidence
- stable within one pilot session / secret
- unlinkable across sessions when secrets differ

The secret must never appear in:

- `pilot-manifest.json`
- `observations.jsonl`
- `pilot-summary.json`
- startup logs or exception messages

## Data minimization

`PilotObservation` has **no fields** for:

- Authorization / Cookie values
- raw HTTP headers
- raw request body / query / form values
- passwords or tokens
- raw username / email
- raw client IP

Persisted derived features (canonical eight):

- `requestsPerWindow`
- `endpointEntropy`
- `endpointConcentration`
- `tokenAgeSeconds`
- `parameterCount`
- `payloadSizeBytes`
- `headerFingerprintHash`
- `ipBucket`

`headerFingerprintHash` and `ipBucket` may still act as quasi-identifiers in
sparse traffic; retain evidence only for a bounded period decided by the
operator.

### Endpoint representation

`endpointKey` is a pilot-scoped HMAC-SHA256 pseudonym of the feature
extractor's endpoint key. This preserves endpoint grouping within one pilot
session without persisting raw/high-cardinality path segments. Query strings are
not part of the feature extractor endpoint key and are not persisted.

## Evidence artifacts

Exactly three public artifacts per finalized session:

1. `observations.jsonl` — one observation per evaluated request
2. `pilot-summary.json` — operational aggregates only
3. `pilot-manifest.json` — final integrity anchor (written last)

No HTML. Temporary `.pilot-session.lock` exists only while the session is open
and is removed on finalize.

Output directory must be empty or non-existent; prior pilot directories are not
overwritten or silently appended.

### Observation contract (`schemaVersion=1`)

Includes: `observationId`, `observedAt`, `pilotSessionId`, `evidenceClass`,
`pseudonymousIdentity`, `endpointKey`, `features`, `anomalyScore`, optional
`policyScore`, `evaluationStatuses`, `riskDerivedAction`, `enforcementApplied`,
`requestOutcome`, `runtimeMode`, `baselineUpdateStatus`, scorer/software/feature
schema provenance, optional `pipelineLatencyNanos`, optional `failOpenReason`.

`evidenceClass = OPERATIONAL_OBSERVATION`.

### Manifest

Identifies the session, MONITOR runtime mode, config digest, observation count,
`observationsSha256`, `summarySha256`, and claim boundary. No secrets. No
absolute host paths required.

### Operational summary

Counts and score/latency aggregates only. **No** TP/TN/FP/FN, precision, recall,
F1, or accuracy.

### Config digest

Deterministic SHA-256 of non-secret semantic config (mode, scorer identity,
baseline policy, policy thresholds, schema versions, etc.). Same semantic config → same digest.
Excludes pilot secret, absolute paths, hostnames, timestamps, and observation IDs.

## Verifier

```bash
./scripts/verify-monitor-pilot-evidence.sh <pilot-directory>
```

Checks artifact set, MONITOR mode, session id consistency, digests, summary
reconciliation, and absence of prohibited raw sensitive field names.

## Failure-open behavior

Pilot sink / serialization / write failures:

- are logged safely (no secret echo)
- **do not** deny the application request
- **do not** alter the response

Wrong mode → no pilot record (fail-closed for collection only).

## Start / stop conditions

**Start prerequisites:** Spring/Servlet starter; enabled; MONITOR; supported
MonitorOnly enforcement wiring; HMAC secret; local output directory; training
publish OFF; privacy/retention decision acknowledged; config frozen into
manifest on finalize.

**Stop conditions:**

- Sentinel alters a request outcome
- sensitive raw data appears in evidence
- accidental ENFORCE
- evidence integrity failure
- unsupported custom enforcement configuration
- repeated pipeline failure per operator policy

No universal numeric latency threshold is defined.

## Rollback

```yaml
ai.sentinel.mode: OFF
# or
ai.sentinel.enabled: false
# and
ai.sentinel.pilot.enabled: false
```

No migration or down procedure is required for pilot evidence files.

## Retention responsibility

The operator (or authorized evaluator) owns retention, access control, and
deletion of local pilot directories. Prefer short bounded retention.

## Future annotation shape (not implemented)

Offline analysis may later combine:

```text
observations.jsonl + separate annotations sidecar → offline analysis
```

No runtime labels. No TP/TN/FP/FN in the pilot summary.

## Claim boundaries

**Allowed (repository):**

> AI-Sentinel provides a bounded Spring/Servlet MONITOR-mode pilot workflow that
> can record pilot-scoped pseudonymized behavioral-risk observations without
> changing application request outcomes under the supported pilot configuration.

**Not claimed:**

- a real external pilot occurred
- customer adoption
- production deployment
- security effectiveness / detection efficacy
- external or production validation
- deployment readiness
- zero performance impact

`PILOT_EVIDENCE_REQUIRED` remains unresolved: this implementation does not
constitute an external pilot.

## Limitations

- Spring/Servlet MONITOR reference surface only
- local file sink only (no DB / cloud / Kafka exporter)
- custom `EnforcementHandler` beans unsupported for pilot collection
- manually constructed custom `SentinelPipeline` wiring is outside the supported
  pilot guarantee (use Spring auto-configuration for the reference pilot)
- live runs are not byte-identical across independent executions
- observational evidence only — not accuracy evidence
- no annotation ingestion in v1
