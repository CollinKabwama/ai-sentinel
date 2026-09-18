# Scorer / Model Lifecycle Governance (Champion / Challenger)

This contract describes **lifecycle designation governance** for candidate models:

champion / challenger designation → objective comparison evidence → eligibility →
explicit approval / rejection → explicit promotion → explicit rollback.

Implementation lives in `dev.aisentinel.core.scoring.lifecycle`
(`ModelLifecycleManager` and related types).

It builds on:

- [scorer/model artifact contract](SCORER_ARTIFACT.md)
- [candidate loading/health](SCORER_CANDIDATE_LOADING.md)
- [candidate replay/evaluation acceptance](SCORER_CANDIDATE_EVALUATION.md)
- [candidate shadow scoring](SCORER_CANDIDATE_SHADOW.md)

## Purpose

Answer governance questions such as:

- Which model identity is the current **lifecycle champion** designation?
- Which identity is the explicitly designated **challenger**?
- Are offline evaluation contexts **comparable**?
- What are the objective metric **deltas** (challenger − champion)?
- Is the challenger **eligible** for approval under an explicit policy?
- Was promotion **approved** or **rejected** by an explicit decision?
- Was the challenger **promoted** to lifecycle champion by an explicit operation?
- Can the prior champion designation be **rolled back** explicitly?

This does **not** answer production scorer wiring, deployment, enforcement, or
training.

## Authority boundary

```text
OFFLINE EVALUATION / ACCEPTANCE / SHADOW SUMMARY (caller-supplied)
          |
          v
OBJECTIVE COMPARISON EVIDENCE
          |
          v
ELIGIBILITY (ELIGIBLE / NOT_ELIGIBLE)
          |
          v
EXPLICIT GOVERNANCE DECISION (APPROVED / REJECTED)
          |
          v
EXPLICIT PROMOTION (lifecycle champion designation only)
          |
          v
OPTIONAL EXPLICIT ROLLBACK (designation only)

RUNNING PRODUCTION SCORER
  → SentinelDecisionEngine / SentinelPipeline wiring
  → policy / enforcement
  remains SEPARATE and UNCHANGED by this package
```

## Champion definition

**Champion** means the currently designated model/scorer **lifecycle reference**
against which a challenger is compared and toward which promotion may move
designation state.

Champion may be:

- `ARTIFACT_BACKED` — verified candidate artifact identity
  (`AcceptedCandidateIdentity` / `CandidateScorerProvenance`)
- `DESIGNATED_REFERENCE` — a non-artifact reference label (for example an
  in-process statistical scorer) without fabricating artifact metadata

Invariant:

`CHAMPION DESIGNATION != PRODUCTION SCORER WIRING`

Designating a champion does **not** rewire `SentinelDecisionEngine`, Spring
configuration, policy, or enforcement.

## Challenger definition

A **challenger** is an identity that was **explicitly designated** via
`designateChallenger(...)`.

At minimum, promotion eligibility typically requires:

- offline evaluation acceptance `ACCEPTED` (policy-configurable)
- comparable comparison evidence (policy-configurable)
- optional caller-supplied shadow summary thresholds (policy-configurable)

Invariants:

`ACCEPTED != CHALLENGER`

`SHADOW ENABLED != CHALLENGER`

Acceptance and shadow enablement never auto-create a challenger.

## Comparison evidence

`ChampionChallengerComparison` reports objective offline metric deltas using the
existing `DetectionMetrics` formulas (precision, recall, F1, FPR, FNR).

Status:

| Status | Meaning |
|--------|---------|
| `COMPARABLE` | Dataset identity, optional evaluation content hash, classification threshold, and optional split/schema fields align |
| `INCOMPATIBLE_EVIDENCE` | Evaluation contexts differ; **no fabricated deltas** |
| `INSUFFICIENT_EVIDENCE` | Required metrics missing |

Delta semantics: `challenger − champion`. Undefined metrics stay undefined
(not coerced to 0/1/pass/fail).

Invariant:

`METRIC DELTA != GOVERNANCE DECISION`

Comparison never selects a winner, best model, or recommended champion.

## Comparability requirements

Before deltas are computed, evidence must agree on:

- dataset identity
- optional evaluation JSON/content SHA-256 when supplied
- classification threshold (bound in evidence **and** metrics)
- feature schema version presence/value when supplied
- evaluation split identity presence/value when supplied

Invariant:

`DIFFERENT EVALUATION CONTEXT != VALID HEAD-TO-HEAD COMPARISON`

`REFERENCE THRESHOLD != PRODUCTION THRESHOLD`

`REFERENCE THRESHOLD != OPTIMAL THRESHOLD`

`REFERENCE THRESHOLD != PROMOTION THRESHOLD`

No threshold search or ROC optimization is performed.

## Shadow evidence

Merged shadow scoring emits observational sink events. Core **does not** own a
durable production shadow history database.

Promotion governance therefore accepts an optional caller-supplied
`ShadowObservationSummary` with precise observational aggregates only:

- attempted shadow executions
- valid comparison count
- candidate invalid score count
- candidate execution failure count
- agreement / disagreement counts
- mean absolute score delta

Shadow summaries must **not** encode TP/TN/FP/FN, precision, recall, or F1.

Invariant:

`SHADOW DISAGREEMENT != GROUND TRUTH`

`SHADOW RESULT != PROMOTION DECISION`

## Eligibility vs approval

`PromotionEligibilityPolicy` / `PromotionEligibilityAssessment` answer only:

`ELIGIBLE` / `NOT_ELIGIBLE`

They never approve or promote.

Invariant:

`ELIGIBLE != APPROVED`

## Approval

`approvePromotion` / `rejectPromotion` record an explicit
`ModelPromotionDecision` (`APPROVED` / `REJECTED`) with:

- champion identity at decision time
- challenger identity
- comparison SHA-256 binding
- rationale (required)
- approver identifier (caller-supplied; not verified human identity)

Invariant:

`SUPPLIED APPROVER IDENTIFIER != VERIFIED HUMAN IDENTITY`

`APPROVED != PROMOTED`

Approval leaves the lifecycle champion **unchanged** until an explicit promote.

## Promotion

`promote(expectedChampion)` updates **lifecycle designation state only**.

It records deterministic `ModelPromotionRecord` history binding:

- previous champion
- new champion (exact challenger identity: scorerId, version, artifactId,
  verified digest, configuration fingerprint)
- decision hash
- comparison hash

It does **not**:

- rewire `SentinelDecisionEngine` / `SentinelPipeline`
- change Spring configuration
- change policy or enforcement
- deploy / publish / reload artifacts
- enable ENFORCE
- change production thresholds
- auto-reload candidate loaders

Invariant:

`PROMOTED != PRODUCTION DEPLOYED`

`PROMOTION != PRODUCTION DEPLOYMENT`

`ACCEPTANCE != AUTOMATIC PROMOTION`

`METRIC IMPROVEMENT != AUTOMATIC PROMOTION`

## Stale-state and substitution protection

Promotion rejects:

- expected champion ≠ current champion (`STALE_CHAMPION`)
- approval champion context ≠ current champion (`STALE_APPROVAL`)
- decision challenger ≠ designated challenger
- bound comparison hash ≠ approval comparison hash (`EVIDENCE_MISMATCH`)
- rejected / undecided challengers
- substituted comparison evidence under an existing decision

## Rollback

`rollback(promotionId, expectedCurrentChampion, rationale, approver)` restores the
previous lifecycle champion from a recorded promotion.

It preserves immutable history and rejects stale / unknown promotion targets.

Invariant:

`ROLLBACK != PRODUCTION DEPLOYMENT ROLLBACK`

Rollback does not rewire production scorers.

## History and persistence

Optional filesystem governance root (`ModelLifecycleManager(Path)`):

- current designation files may be replaced intentionally
- history files use create-new semantics
- process-local synchronized operations
- restart can reconstruct designations, decisions, and history
- pending approval after restart requires re-binding the **identical** comparison
  hash before promote

Guarantees are **best-effort / recoverable**, not claimed transactional or
crash-proof across all files, and not distributed consensus.

Absolute paths, raw requests, JWTs, cookies, payloads, and artifact bytes are
not written into evidence.

## Privacy and security

Governance evidence uses technical identity fields only.

Promotion does not enable arbitrary code loading, network artifact retrieval,
reflection plugins, or training.

## Required invariants

```text
METRIC DELTA != GOVERNANCE DECISION
ELIGIBLE != APPROVED
APPROVED != PROMOTED
PROMOTED != PRODUCTION DEPLOYED
CHAMPION DESIGNATION != PRODUCTION SCORER WIRING
ROLLBACK != PRODUCTION DEPLOYMENT ROLLBACK
SHADOW DISAGREEMENT != GROUND TRUTH
ACCEPTANCE != AUTOMATIC PROMOTION
SHADOW RESULT != PROMOTION DECISION
PROMOTION GOVERNANCE != TRAINING
SUPPLIED APPROVER IDENTIFIER != VERIFIED HUMAN IDENTITY
ACCEPTED CANDIDATE != CHAMPION
METRIC IMPROVEMENT != AUTOMATIC PROMOTION
REFERENCE THRESHOLD != PRODUCTION THRESHOLD
REFERENCE THRESHOLD != OPTIMAL THRESHOLD
REFERENCE THRESHOLD != PROMOTION THRESHOLD
CONFIGURATION FINGERPRINT != ARTIFACT DIGEST
```

## Out of scope

- production scorer rebinding / hot swap
- canary or percentage rollout
- automatic promotion or automatic rollback
- winner / Bayesian / significance frameworks
- training / retraining
- threshold optimization
- distributed governance / remote approval UI
- telemetry databases

## Related docs

- [`SCORER_ARTIFACT.md`](SCORER_ARTIFACT.md)
- [`SCORER_CANDIDATE_LOADING.md`](SCORER_CANDIDATE_LOADING.md)
- [`SCORER_CANDIDATE_EVALUATION.md`](SCORER_CANDIDATE_EVALUATION.md)
- [`SCORER_CANDIDATE_SHADOW.md`](SCORER_CANDIDATE_SHADOW.md)
