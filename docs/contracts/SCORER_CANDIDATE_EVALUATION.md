# Candidate Scorer Replay / Evaluation Acceptance

This contract describes how AI-Sentinel takes an **operationally READY
candidate scorer** through the **existing** deterministic reference replay and
Detection Evaluation Framework to produce **candidate-specific evaluation
evidence**.

Implementation lives in `dev.aisentinel.core.evaluation`
(`CandidateDetectionEvaluationRunner`, `CandidateEvaluationAcceptancePolicy`)
and reuses:

- `CandidateScorerLoader` (PR #122 loading/health/runtime isolation)
- `ReplayEngine` with an explicit evaluation scorer
- `DetectionEvaluationRunner`
- `ReferenceEvaluationAligner`
- `DetectionMetricsCalculator`
- `TemporalDetectionEvaluator`
- `DetectionClassificationConfiguration`

It does **not** introduce a second evaluation framework, a second metrics
engine, or a second reference corpus.

## Purpose

Answer:

- Can this READY candidate be replayed deterministically against the tracked
  reference dataset?
- After independent annotation alignment, what evaluation evidence does the
  existing framework produce for **this** candidate?
- If an explicit engineering acceptance policy is supplied, do those metrics
  satisfy that policy?

These are different questions.

`EVALUATION RESULT != ACCEPTANCE DECISION`

`ACCEPTANCE DECISION != PRODUCTION APPROVAL`

Replay through the existing framework is **framework completion**. An optional
`CandidateEvaluationAcceptancePolicy` then assesses already-produced metrics.
No policy means `NOT_ASSESSED`, not silent acceptance.

It is **not**:

- production approval
- shadow enablement
- champion selection
- production deployment
- Official Detection Reference Baseline promotion
- threshold recommendation
- proof of production efficacy

## Pipeline

```text
validated candidate descriptor
  → verified candidate artifact (CandidateScorerLoader)
  → operationally READY loaded candidate
  → deterministic reference replay (explicit evaluation scorer)
  → candidate predictions
  → independent annotation alignment
  → existing detection evaluation
  → candidate evaluation evidence
  → optional explicit acceptance assessment
```

Non-ready loading (`NOT_CONFIGURED`, `INVALID`, `UNAVAILABLE`) does **not**
execute replay and does **not** fabricate detector predictions.

`CANDIDATE LOAD FAILURE != DETECTOR PREDICTION`

## Prerequisites

Evaluation of a candidate may execute only after `CandidateScorerLoader`
returns `READY`. There is no evaluation shortcut around:

- descriptor validation
- bounded artifact bytes
- SHA-256 verification
- verified-bytes == consumed-bytes
- explicit runtime dispatch
- AIF1 structural bounds for Isolation Forest candidates

The current executable candidate runtime remains Isolation Forest / `aif1`.
Other validated artifact types may still be `UNAVAILABLE` at load time.

`VALID ARTIFACT != SUPPORTED RUNTIME IMPLEMENTATION`

## Reference dataset reuse

Candidate evaluation uses the tracked reference dataset and independent
annotations. It does not copy, regenerate, or mutate:

- `evaluation/reference/events.jsonl`
- `evaluation/reference/annotations.json`
- `evaluation/reference/manifest.json`

`REFERENCE ANNOTATIONS = GROUND TRUTH`

`SCORER OUTPUT != GROUND TRUTH`

## Truth separation

Candidate scorer input is replay source features only.

Candidate scoring must never receive:

- `expectedClass`
- `anomalyExpected`
- `maliciousnessAsserted`
- scenario expected outcome
- evaluation labels

Labels are joined only **after** candidate prediction generation.

`LABEL != FEATURE`

`REFERENCE ANNOTATION != MODEL INPUT`

## Replay injection seam

Default `ReplayEngine` construction is unchanged (statistical replay).

Candidate evaluation uses `ReplayEngine.withEvaluationScorer(loaded.scorer())`
so an explicitly supplied `AnomalyScorer` is used without changing production
scorer selection.

`ReplayScorerKind.CANDIDATE` cannot be materialized by default replay
construction. That kind records candidate identity in replay provenance and
requires the explicit scorer factory.

Predictions are tagged `EvaluationPredictionSource.CANDIDATE_REPLAY_SCORE`.
That source is not evaluation truth.

## Classification threshold

Candidate evaluation reuses `DetectionClassificationConfiguration`.

The Official Detection Reference Baseline and this capability use the fixed
**reference classification threshold** `0.5` when callers supply that value
(`DetectionReferenceBaselineSchemas.REFERENCE_CLASSIFICATION_THRESHOLD`).

That value is **only** the reference classification threshold.

It is **not**:

- a production threshold
- an enforcement threshold
- an optimal threshold
- a recommended deployment threshold
- a quality gate

This capability does not sweep thresholds, maximize F1, or recommend a
deployment threshold.

`REFERENCE THRESHOLD != PRODUCTION THRESHOLD`

## Detector prediction

Candidate detector prediction is derived from candidate anomaly-score evidence
and the explicit evaluation classification rule.

Policy actions (`ALLOW`, `MONITOR`, `THROTTLE`, `BLOCK`, `QUARANTINE`) remain
diagnostic replay fields only.

`POLICY ACTION != DETECTOR PREDICTION`

Warmup `MONITOR` is still not a positive detector prediction.

## Invalid and unavailable evaluation

Invalid candidate scores (`NaN`, `±Infinity`, negative invalid finite values)
are not coerced to maximum risk. They follow existing replay/evaluation
semantics: non-serializable / `INVALID_SCORE` evidence is excluded from
confusion-matrix accounting.

`INVALID SCORE != MAXIMUM RISK`

`UNAVAILABLE EVALUATION != NEGATIVE PREDICTION`

`UNAVAILABLE EVALUATION != POSITIVE PREDICTION`

## Metrics and temporal evaluation

Candidate observations flow through the existing:

- `DetectionMetricsCalculator`
- `TemporalDetectionEvaluator`

No candidate-specific precision/recall/F1 formula or temporal segment
algorithm is introduced.

Truth-defined anomaly segments remain annotation-derived. Candidate scores
are detection evidence against those independent segments.

`ANOMALOUS != MALICIOUS`

## Candidate provenance

Candidate evidence binds to `LoadedCandidateScorer.provenance()`:

- scorer id / version
- artifact id / format / type
- artifact digest (SHA-256)
- verified digest
- configuration fingerprint
- feature schema version / projection / dimension
- runtime implementation id

`CONFIGURATION FINGERPRINT != ARTIFACT DIGEST`

Replay configuration identity also includes the verified digest so two
artifacts that share a declared scorer id/version cannot collide. The
candidate evidence section still records the unsuffixed declared version.

## Evidence

Writer: `CandidateDetectionEvaluationEvidenceWriter`

Filenames (distinct from diagnostic evaluation and from the official baseline):

- `candidate-evaluation.json`
- `candidate-evaluation.md`

Existing destinations are refused (no overwrite). Candidate evaluation must
not write into `evaluation/detection-reference-baseline/`.

Status:

- `COMPLETED` — READY candidate evaluated; nested diagnostic evaluation
  evidence is present
- `CANDIDATE_NOT_READY` — load failed; `evaluation` is null; load issues are
  present; no predictions were fabricated

Acceptance is a nested evidence object:

- `status`: `NOT_ASSESSED` / `ACCEPTED` / `REJECTED`
- `policy`: configured criteria, or `null` when no policy was supplied
- `issues`: every failed criterion, empty when not rejected

Evidence is deterministic for the same corpus + candidate artifact +
classification configuration + acceptance policy. It omits hostnames, absolute
paths, wall-clock identity, and raw request/feature payloads.

## Official baseline isolation

Official Detection Reference Baseline remains the stable reference evidence
for the authoritative statistical-replay framework.

Candidate evaluation evidence is separate.

`CANDIDATE EVALUATION != BASELINE PROMOTION`

A detection-reference-baseline candidate is **not** a scorer/model candidate.

Do not call candidate scorer evaluation output a "baseline candidate."

## Acceptance policy

Acceptance is a separate engineering assessment of already-produced candidate
metrics. It does not inspect labels during scoring, does not change scores, and
does not change replay, policy, or enforcement.

Caller supplies `CandidateEvaluationAcceptancePolicy` explicitly. There is no
hidden “good model” default.

Supported optional criteria (at least one required when a policy is constructed):

- minimum evaluable observations
- maximum excluded observations
- minimum precision
- minimum recall
- minimum F1
- maximum false-positive rate
- maximum false-negative rate

Ratio criteria must be finite in `[0,1]`. Count criteria must be `>= 0`. Invalid
configuration (`NaN`, `Infinity`, negatives, empty policy) is rejected. Values
are not silently clamped.

Every configured criterion is evaluated. Failures are all reported, in that
deterministic order, as structured issue codes. Undefined metrics do not satisfy
a configured ratio criterion.

Comparison uses the exact existing metric values (`Double.compare` / integer
counts). This capability does not compare against the Official Detection
Reference Baseline as a governance gate.

`METRIC IMPROVEMENT != AUTOMATIC ACCEPTANCE`

## Acceptance status

| Evaluation status | Policy | Acceptance status |
|---|---|---|
| `CANDIDATE_NOT_READY` | absent or present | `NOT_ASSESSED` |
| `COMPLETED` | absent | `NOT_ASSESSED` |
| `COMPLETED` | present, all criteria met | `ACCEPTED` |
| `COMPLETED` | present, any criterion unmet | `REJECTED` |

`EVALUATION COMPLETED != ACCEPTED`

`EVALUATION FAILED != ACCEPTANCE REJECTED`

`ACCEPTED` is not champion selection, promotion, or production approval.

`ACCEPTANCE POLICY != PRODUCTION POLICY`

`ACCEPTANCE THRESHOLD != PRODUCTION THRESHOLD`

## Framework acceptance vs quality

A structurally valid candidate evaluation may show poor precision, recall, F1,
or temporal detection. Completing evaluation is not quality acceptance.

An `ACCEPTED` result means only that the supplied engineering criteria were
met. It is still not production efficacy.

The candidate is **not** required to reproduce Official Detection Reference
Baseline metrics, and metric improvement is **not** automatic promotion.

`FRAMEWORK ACCEPTANCE != DETECTION QUALITY ACCEPTANCE`

`METRIC IMPROVEMENT != AUTOMATIC PROMOTION`

## Explicitly out of scope

- champion / challenger (see [`SCORER_MODEL_LIFECYCLE.md`](SCORER_MODEL_LIFECYCLE.md))
- promotion / rollback (see [`SCORER_MODEL_LIFECYCLE.md`](SCORER_MODEL_LIFECYCLE.md))
- production scorer selection
- policy or enforcement authority
- candidate training, retuning, or threshold optimization
- batch tournaments / leaderboards
- actuator / dashboard / analyst UI

Shadow scoring is a **separate** capability; see
[`SCORER_CANDIDATE_SHADOW.md`](SCORER_CANDIDATE_SHADOW.md).
Lifecycle promotion governance is a **separate** capability; see
[`SCORER_MODEL_LIFECYCLE.md`](SCORER_MODEL_LIFECYCLE.md).

## Boundaries

```text
EVALUATED CANDIDATE != ACCEPTED CANDIDATE
EVALUATED CANDIDATE != APPROVED CANDIDATE
EVALUATED CANDIDATE != SHADOW CANDIDATE
EVALUATED CANDIDATE != CHAMPION
EVALUATED CANDIDATE != PRODUCTION CANDIDATE
EVALUATED CANDIDATE != PRODUCTION DEPLOYMENT
ACCEPTED CANDIDATE != CHAMPION
ACCEPTED CANDIDATE != PROMOTED MODEL
ACCEPTED CANDIDATE != PRODUCTION MODEL
ACCEPTANCE != AUTOMATIC PROMOTION
ACCEPTANCE != AUTOMATIC DEPLOYMENT
ACCEPTANCE != AUTOMATIC SHADOW ENABLEMENT
```

```text
VALIDATED CANDIDATE
  → VERIFIED ARTIFACT
  → SAFELY LOADED CANDIDATE
  → OPERATIONALLY READY CANDIDATE
  → REPLAYED CANDIDATE
  → EVALUATED CANDIDATE
  → ACCEPTANCE EVIDENCE
```

does **not** imply shadow enablement, champion, promotion, or production deployment.

## Related docs

- [`SCORER_ARTIFACT.md`](SCORER_ARTIFACT.md)
- [`SCORER_CANDIDATE_LOADING.md`](SCORER_CANDIDATE_LOADING.md)
- [`SCORER_CANDIDATE_SHADOW.md`](SCORER_CANDIDATE_SHADOW.md)
- [`SCORER_MODEL_LIFECYCLE.md`](SCORER_MODEL_LIFECYCLE.md)
- [`../../evaluation/DETECTION_EVALUATION.md`](../../evaluation/DETECTION_EVALUATION.md)
- [`../../evaluation/DETERMINISTIC_REPLAY.md`](../../evaluation/DETERMINISTIC_REPLAY.md)
- [`../../evaluation/DETECTION_REFERENCE_BASELINE.md`](../../evaluation/DETECTION_REFERENCE_BASELINE.md)
