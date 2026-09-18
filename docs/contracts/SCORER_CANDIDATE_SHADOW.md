# Candidate Shadow Scoring and Comparison Telemetry

This contract describes observational **shadow scoring**: an explicitly enabled,
identity-bound candidate may score the same request features beside the
authoritative scorer, emitting comparison telemetry only.

Implementation lives in `dev.aisentinel.core.scoring.shadow`
(`ShadowScoringExecutor` and related types).

It builds on:

- [scorer/model artifact contract](SCORER_ARTIFACT.md)
- [candidate loading/health](SCORER_CANDIDATE_LOADING.md)
- [candidate replay/evaluation acceptance](SCORER_CANDIDATE_EVALUATION.md)

## Purpose

Answer observational questions such as:

- When shadow is explicitly enabled, what score did the bound candidate produce?
- How does that score compare numerically to the authoritative score?
- Under an optional diagnostic classification threshold, do the scorers agree?

This does **not** answer production authority, promotion readiness, or ground
truth.

## Authority boundary

```text
REQUEST / FEATURE CONTEXT
          |
 +--------+--------+
 |                 |
 v                 v
AUTHORITATIVE      SHADOW CANDIDATE
SCORER             (observational)
 |                 |
 v                 v
authoritative      candidate score
score              |
 |                 |
 |          observation only
 |                 |
 +--------+--------+
          |
          v
 comparison telemetry

authoritative score
  → existing decision engine
  → existing policy
  → existing enforcement
```

Invariant:

`SHADOW RESULT != PRODUCTION DECISION`

Candidate scores must never influence ALLOW / MONITOR / THROTTLE / BLOCK /
QUARANTINE, risk thresholds, baseline update eligibility, authoritative
evaluation status, or enforcement response.

The authoritative result must be identical whether shadow is disabled or
enabled, except for explicitly observational telemetry / context side effects.

## Default and activation

Default: **shadow disabled**.

Activation requires **all** of:

1. `ShadowScoringConfiguration.enabled == true` (explicit opt-in)
2. an `AcceptedCandidateIdentity` binding (operator-supplied attestation that
   offline acceptance was `ACCEPTED` for that exact identity)
3. a bound callable candidate whose provenance **matches** that identity
4. successful candidate scoring for the observation

`ACCEPTANCE != AUTOMATIC SHADOW ENABLEMENT`

`READY != SHADOW ENABLED`

`ACCEPTED != SHADOW ENABLED`

`MODEL AVAILABLE != SHADOW ENABLED`

No candidate executes merely because one is loaded, READY, evaluated, or
ACCEPTED.

## Eligibility identity binding

`AcceptedCandidateIdentity` binds:

- scorer id
- scorer version
- artifact id
- verified artifact digest
- configuration fingerprint

Mismatch (including digest or fingerprint substitution) yields
`NOT_ELIGIBLE` / `IDENTITY_MISMATCH` and does **not** invoke the candidate.

Accepted candidate A must not activate shadow execution for candidate B.

## Execution model

Shadow scoring is **synchronous in-process** observational work on the
evaluation path when enabled. It is:

- not process / container isolation
- not an asynchronous production shadow platform
- not zero-latency

When enabled, candidate scoring adds work to the request/evaluation path.
Do not claim production SLA or negligible latency without measured evidence.

`PERFORMANCE != DETECTION EFFECTIVENESS`

## Feature semantics

The candidate receives the same `RequestFeatures` instance used for
authoritative scoring. Each scorer applies its own validated projection
(`SAME DIMENSION != SAME FEATURE SEMANTICS`).

Live shadow scoring has **no ground truth** and must not consume evaluation
labels.

## State isolation

- Shadow execution never calls `update` on the candidate
  (`SHADOW OBSERVATION != TRAINING`).
- Shadow execution must not mutate authoritative baseline / model state.
- Current IF/AIF1 candidate runtime update remains a no-op.
- Candidate failure must not substitute for authoritative scorer failure
  (`CANDIDATE FAILURE != AUTHORITATIVE FAILURE`).
- The candidate is **not** a fallback / secondary / failover production scorer.

## Observation statuses

| Status | Meaning |
|--------|---------|
| `DISABLED` | Configuration off; candidate not invoked |
| `NOT_ELIGIBLE` | Enabled but identity/acceptance binding failed |
| `CANDIDATE_UNAVAILABLE` | Enabled and identity-ready, but no candidate bound |
| `SCORED` | Candidate returned a contract-valid score |
| `CANDIDATE_INVALID_SCORE` | Candidate returned NaN / ±Infinity / negative |
| `CANDIDATE_EXECUTION_FAILED` | Candidate `score` threw an ordinary runtime exception |

These describe shadow **execution**, not candidate load status
(`NOT_CONFIGURED` / `INVALID` / `UNAVAILABLE` / `READY`).

Invalid candidate scores are not fabricated, not clamped into “maximum risk,”
and never become authoritative (`INVALID SCORE != MAXIMUM RISK`,
`UNAVAILABLE SCORE != SYNTHETIC SCORE`).

## Comparison semantics

When both authoritative and candidate scores are valid:

- `delta = candidateScore - authoritativeScore`
- `absoluteDelta = abs(delta)`

A positive delta means only that the candidate produced a numerically higher
risk score. It does **not** mean “candidate worse,” “attack detected,” or
ground truth.

Optional diagnostic classification (explicit threshold on shadow configuration,
**not** production policy thresholds) may yield:

- `AGREE_NORMAL`
- `AGREE_ANOMALOUS`
- `AUTHORITATIVE_ONLY_ANOMALOUS`
- `CANDIDATE_ONLY_ANOMALOUS`

`ANOMALOUS != MALICIOUS`

`DISAGREEMENT != DEFECT`

`DISAGREEMENT != GROUND TRUTH`

`DISAGREEMENT != CANDIDATE FAILURE`

Live shadow telemetry must **not** label TP / FP / FN / TN without independently
supplied truth.

## Telemetry

`ShadowObservationSink` is a framework-independent boundary with a `NOOP`
default. Core does not couple to Kafka, Redis, HTTP exporters, Micrometer
registries, filesystems, or databases.

`TELEMETRY FAILURE != REQUEST FAILURE`

Sink failures are contained and must not change the production decision.

Correlation uses a privacy-safe identifier (for example the pseudonymized
identity hash already used on the decision path). Do not emit raw tokens,
cookies, JWTs, API keys, authorization headers, or raw PII.

`PSEUDONYMIZED != ANONYMOUS`

## Provenance

Observations retain candidate provenance (scorer id/version, artifact id,
verified digest, configuration fingerprint, feature schema / projection,
runtime implementation id) via existing `CandidateScorerProvenance`.

`CANDIDATE CONFIGURATION != SHADOW EXECUTION CONFIGURATION`

## Wiring

`SentinelDecisionEngine` (and optionally `SentinelPipeline`) accept an optional
`ShadowScoringExecutor`. Existing constructors default to
`ShadowScoringExecutor.disabled()`.

Spring / production enablement properties are out of scope for this capability;
default application behavior remains unchanged (shadow off).

## Current runtime limitation

Candidate executable runtime remains Isolation Forest / AIF1 unless later work
extends supported construction.

## Explicitly out of scope

- champion / challenger
- promotion / rollback
- percentage traffic sampling / rollout
- automatic model selection
- threshold optimization or production threshold recommendation
- candidate-driven enforcement or “safety escalation”
- training / relearning from shadow observations
- Kafka / Redis / dashboard telemetry platforms
- process-isolation sandbox architecture

## Boundaries

```text
SHADOW RESULT != PRODUCTION DECISION
SHADOW SCORE != AUTHORITATIVE SCORE
SHADOW DISAGREEMENT != DEFECT
SHADOW DISAGREEMENT != GROUND TRUTH
SHADOW SCORE != MALICIOUSNESS
SHADOW EXECUTION != PRODUCTION APPROVAL
SHADOW ENABLED != CANDIDATE PROMOTED
ACCEPTANCE != AUTOMATIC SHADOW ENABLEMENT
SHADOW OBSERVATION != TRAINING
CANDIDATE FAILURE != AUTHORITATIVE FAILURE
TELEMETRY FAILURE != REQUEST FAILURE
ACCEPTED CANDIDATE != CHAMPION
MODEL AVAILABLE != SHADOW ENABLED
```

## Related docs

- [`SCORER_ARTIFACT.md`](SCORER_ARTIFACT.md)
- [`SCORER_CANDIDATE_LOADING.md`](SCORER_CANDIDATE_LOADING.md)
- [`SCORER_CANDIDATE_EVALUATION.md`](SCORER_CANDIDATE_EVALUATION.md)
