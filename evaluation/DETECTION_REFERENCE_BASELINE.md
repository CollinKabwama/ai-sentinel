# Official Detection Reference Baseline

## Status

The Official Detection Reference Baseline capability is **DONE** on the current
development line:

- **definition and capture** — tracked deterministic baseline artifacts
- **verification and drift detection** — read-only MATCH / DRIFT reporting
- **lifecycle and governance** — candidate create/approve/reject/promote, history
  retention, and governed rollback

Tracked artifacts:

- [`detection-reference-baseline/manifest.json`](detection-reference-baseline/manifest.json)
- [`detection-reference-baseline/evaluation.json`](detection-reference-baseline/evaluation.json)
- [`detection-reference-baseline/evaluation.md`](detection-reference-baseline/evaluation.md)

Additive lifecycle directories (created by maintainer commands; not required for MATCH):

- `candidates/<candidateId>/`
- `history/<historyId>/`
- `governance/`

Related framework documentation: [`DETECTION_EVALUATION.md`](DETECTION_EVALUATION.md).

`DETECTION BASELINE != PRODUCTION EFFICACY` · `BASELINE != QUALITY GATE` · `DRIFT != REGRESSION` · `DRIFT != APPROVAL`

Capability completion does **not** establish production efficacy, ENFORCE
readiness, or detector-quality acceptance for production traffic.

## Definition

The Official Detection Reference Baseline is a tracked, deterministic reference
record of the accepted detector's behavior against a specific version of
AI-Sentinel's tracked reference evaluation corpus under one explicitly defined
reference evaluation configuration.

It provides:

- a stable engineering reference point
- reproducible detector-behavior evidence
- a foundation for later comparison and drift detection
- provenance for future scorer/configuration evolution

It does **not** establish:

- production efficacy
- production readiness
- production SLA
- production false-positive or false-negative tolerance
- safe automatic enforcement
- model optimality
- threshold optimality
- pilot acceptance
- production quality acceptance

## Architecture

```text
TRACKED REFERENCE CORPUS
        ↓
DETERMINISTIC REPLAY
        ↓
DetectionEvaluationRunner
        ↓
ACCEPTED EVALUATION EVIDENCE
        ↓
DETECTION REFERENCE BASELINE CAPTURE
        ↓
CANONICAL TRACKED BASELINE ARTIFACTS
```

The baseline layer does **not** independently recalculate truth, predictions,
confusion matrices, quality metrics, scenario metrics, or temporal metrics.
Those belong to the Detection Evaluation Framework.

`REPORT != BASELINE` for diagnostic evidence alone. The tracked baseline
directory is the deliberate publication of that evidence under the Official
Detection Reference Baseline configuration.

## Reference classification configuration

**Reference classification threshold: `0.5`**

Single source for Official Detection Reference Baseline capture:

`DetectionReferenceBaselineConfiguration.officialReference()`

which composes:

- `DetectionClassificationConfiguration(0.5)`
- `ReplayConfiguration.referenceDefaults()`
- expected tracked reference dataset / annotation identities

### Classification rule

```text
valid anomalyScore >= 0.5  → predicted anomalous
valid anomalyScore < 0.5   → predicted normal
```

Unavailable detector evidence remains unavailable under existing framework
semantics. Missing / invalid / remote-failure scores are not reclassified as
normal or anomalous merely because the reference threshold is `0.5`.

### Threshold decision (prerequisite)

`0.5` is an intentional engineering decision for Official Detection Reference
Baseline reproducibility only.

It is selected because:

- detector anomaly scores use a normalized `[0, 1]` domain
- `0.5` is the numerical midpoint of that normalized domain
- it provides a simple deterministic reference boundary
- it enables stable comparison of future engineering changes
- it avoids coupling the reference baseline to enforcement policy bands

It is **not**:

- optimal
- statistically neutral
- a calibrated probability
- recommended for production
- an enforcement threshold
- a deployment threshold
- a quality gate

No threshold sweep, grid search, ROC/PR/AUC optimization, or corpus-label tuning
was performed to choose this value.

`0.5` is **not** a general evaluation-framework default.
`DetectionClassificationConfiguration` and
`ReferenceDetectionEvaluationEvidenceMain` still require an explicit caller
threshold for general diagnostic runs.

### Policy threshold separation

Existing policy bands such as `0.2 / 0.4 / 0.6 / 0.8` remain unrelated. They map
policy/enforcement actions and must not become detector-classification
thresholds or baseline predictions.

`POLICY ACTION != DETECTOR PREDICTION`
`REFERENCE THRESHOLD != PRODUCTION THRESHOLD`
`REFERENCE THRESHOLD != ENFORCEMENT THRESHOLD`

## Artifact layout

```text
evaluation/detection-reference-baseline/
  manifest.json      # compact provenance / binding record
  evaluation.json    # accepted framework evidence (machine-readable)
  evaluation.md      # accepted framework evidence (human-readable)
```

This namespace is distinct from:

- `evaluation/reference/` — reference dataset input
- `docs/performance/REFERENCE_BASELINE.md` — performance cost baseline

`REFERENCE DATASET != DETECTION BASELINE`
`REFERENCE PERFORMANCE BASELINE != DETECTION REFERENCE BASELINE`
`PERFORMANCE != DETECTION EFFECTIVENESS`

## Baseline identity and schema

| Field | Value |
| --- | --- |
| Baseline artifact schema | `1` |
| Baseline ID | `official-detection-reference-baseline-v1` |
| Baseline kind | `official-detection-reference-baseline` |

Baseline artifact schema is independent from product release versions.

## Manifest

`DetectionReferenceBaselineManifest` binds:

- baseline metadata (schema, id, kind, purpose)
- reference corpus provenance (dataset/schemas/ordering/`eventsSha256`)
- independent annotation content SHA-256 (`annotationsSha256`)
- replay provenance (run id, mode, scorer, policy, fingerprint, results hash)
- classification provenance (threshold `0.5`, boundary, reference role)
- structural snapshot counts from accepted evidence
- SHA-256 digests and byte counts for `evaluation.json` / `evaluation.md`
- required limitations

Canonical artifacts must remain reproducible: no capture clock, hostname,
username, absolute path, branch name, or CI build id.

## Capture command

From the repository root (JDK 21):

```bash
./scripts/capture-detection-reference-baseline.sh
```

Equivalent maintainer entry point:

```bash
mvn -q -pl ai-sentinel-core -DskipTests compile dependency:build-classpath \
  -Dmdep.outputFile=/tmp/ai-sentinel-cp.txt
java -cp "ai-sentinel-core/target/classes:$(cat /tmp/ai-sentinel-cp.txt)" \
  dev.aisentinel.core.evaluation.ReferenceDetectionBaselineMain \
  --output evaluation/detection-reference-baseline
```

Threshold `0.5` comes only from
`DetectionReferenceBaselineConfiguration.officialReference()`. The general
evidence CLI still requires `--threshold` and has no hidden default.

### No-overwrite behavior

Initial capture refuses an existing destination. There is no `--force`,
`--overwrite`, `--replace`, `--update`, `--approve`, or `--promote`.

### Publication safety

Artifacts are generated and validated in a temporary sibling directory, then
published by directory move. Where the filesystem supports it, the move is
atomic (`ATOMIC_MOVE`); otherwise a non-atomic move is used after validation.
Failed captures leave no destination directory presented as a valid baseline.

Do **not** hand-edit generated `manifest.json`, `evaluation.json`, or
`evaluation.md`. Fix generators/configuration and regenerate.

## Privacy

Baseline artifacts preserve the evaluation privacy boundary. They must not
include identity keys, raw sensitive endpoints, authorization headers, cookies,
tokens, credentials, request/query/body contents, `FeatureSnapshot` contents,
PII, local filesystem paths, usernames, or hostnames.

`PSEUDONYMIZED != ANONYMOUS`

## Verification and drift detection

Verification answers:

> Does a fresh deterministic evaluation still reproduce the currently tracked
> Official Detection Reference Baseline, and if not, what changed?

It does **not** approve drift, replace the baseline, promote candidates, or
decide product quality.

`DRIFT != REGRESSION` · `DIFFERENCE != FAILURE` · `VERIFICATION RESULT != PRODUCTION ACCEPTANCE`

### Flow

```text
TRACKED OFFICIAL BASELINE
        +
CURRENT REPOSITORY INPUTS
        ↓
FRESH DETERMINISTIC REPLAY/EVALUATION
        ↓
CURRENT CANONICAL EVIDENCE
        ↓
BASELINE VERIFIER
        ↓
VERIFICATION / DRIFT REPORT
```

Official baseline artifacts remain read-only. Fresh evidence is ephemeral
unless an explicit verification report output directory is requested (never
inside the official baseline directory).

### Overall statuses

| Status | Meaning |
| --- | --- |
| `MATCH` | Baseline valid; current evaluation succeeded; complete comparison contract matches |
| `DRIFT_DETECTED` | Baseline valid; current evaluation succeeded; one or more dimensions differ |
| `BASELINE_INTEGRITY_FAILURE` | Tracked baseline is invalid/tampered; comparison is not trustworthy |
| `CURRENT_EVALUATION_FAILURE` | Current replay/evaluation could not complete; no fabricated drift values |

`DRIFT_DETECTED` means technical difference, not release rejection.

### Drift categories

- `DATASET_PROVENANCE`
- `ANNOTATION_PROVENANCE`
- `REPLAY_PROVENANCE`
- `SCORER_PROVENANCE`
- `POLICY_PROVENANCE`
- `CLASSIFICATION_CONFIGURATION`
- `STRUCTURAL_EVIDENCE`
- `DETECTION_METRICS`
- `TEMPORAL_EVIDENCE`
- `GENERATED_EVIDENCE`

Comparison is exact/deterministic against accepted evidence values. No epsilon
tolerances, severity ratings, acceptable-drift lists, or quality gates.

### Persisted baseline integrity

Before comparison, verification loads and validates the persisted baseline:

- JSON syntax and supported schema
- required fields / baseline ID / kind / threshold
- artifact file-name path safety (no traversal / symlink artifacts)
- exact `evaluation.json` / `evaluation.md` SHA-256 and byte counts vs manifest
- manifest provenance/structure reconciliation against `evaluation.json`

Unknown additive JSON object fields are ignored after required known fields are
validated. Duplicate object keys are rejected.

### Verification command

```bash
./scripts/verify-detection-reference-baseline.sh
./scripts/verify-detection-reference-baseline.sh evaluation/detection-reference-baseline /tmp/baseline-verify-report
```

Equivalent:

```bash
java -cp "..." dev.aisentinel.core.evaluation.ReferenceDetectionBaselineVerifyMain \
  --baseline evaluation/detection-reference-baseline \
  --output /tmp/baseline-verify-report
```

Official verification always uses
`DetectionReferenceBaselineConfiguration.officialReference()` (threshold `0.5`).
There is no CLI option to change the official verification threshold.

### Exit codes

| Code | Status |
| --- | --- |
| 0 | `MATCH` |
| 1 | `DRIFT_DETECTED` |
| 2 | `BASELINE_INTEGRITY_FAILURE` |
| 3 | `CURRENT_EVALUATION_FAILURE` |
| 4 | invalid usage |

Nonzero for drift is automation-friendly and does **not** mean quality rejection.

Optional `--output` writes deterministic `verification.json` / `verification.md`
and refuses an existing destination (no `--force`).

### Report schema

Verification reports use independent schema version `1`
(`official-detection-reference-baseline-verification`), distinct from the
baseline artifact schema.

## Lifecycle and governance

Lifecycle answers:

> When current deterministic behavior intentionally changes, how can a new
> baseline candidate be created, reviewed, approved, promoted, retained,
> audited, and rolled back without silently rewriting engineering history?

```text
CURRENT REPOSITORY TRUTH
        ↓
EXISTING DETERMINISTIC CAPTURE
        ↓
CANDIDATE BASELINE ARTIFACTS
        ↓
EXISTING VERIFIER / COMPARATOR
        ↓
CANDIDATE COMPARISON
        ↓
EXPLICIT APPROVAL / REJECTION
        ↓
OPTIONAL EXPLICIT PROMOTION
        ↓
HISTORY RETENTION / OPTIONAL ROLLBACK
```

### Governance principles

- `DRIFT != APPROVAL`
- `DRIFT != REGRESSION`
- `CANDIDATE != OFFICIAL BASELINE`
- `APPROVAL != PROMOTION`
- `APPROVAL != AUTOMATIC PROMOTION`
- `PROMOTION != PRODUCTION DEPLOYMENT`
- `METRIC DELTA != GOVERNANCE DECISION`
- `BASELINE CHANGE != PRODUCTION ACCEPTANCE`

A **baseline candidate** is a proposed replacement for the official reference
baseline evidence set. It is distinct from a future **scorer/model candidate**
artifact evaluated under scorer plug-in / shadow-scoring work.

Verification remains read-only. Drift is evidence for humans, never an
auto-approve or auto-promote policy. There are no acceptable-drift lists,
severity ratings, quality gates, or threshold-optimization options.

### Layout (additive)

```text
evaluation/detection-reference-baseline/
  manifest.json
  evaluation.json
  evaluation.md
  candidates/<candidateId>/
    baseline/{manifest.json,evaluation.json,evaluation.md}
    candidate.json
    comparison.json
    comparison.md
    decision.json            # after approve/reject
    decision.sha256          # hash receipt for decision.json
    promotion.json           # after successful promotion
  history/<baselineId>-<manifestHashPrefix>/
    baseline/{...}
    retention.json
  governance/
    rollback-*.json
```

Official canonical artifacts stay in place. Candidates and history are siblings.

### Candidate create

`DetectionReferenceBaselineLifecycle.createCandidate` / CLI `create-candidate`:

1. validates the current official baseline
2. captures a fresh deterministic baseline into an isolated candidate directory
3. compares candidate vs official using `DetectionReferenceBaselineVerifier.comparePersisted`
4. writes deterministic comparison + candidate governance metadata
5. refuses overwrite
6. leaves the official baseline untouched

Candidate identity is an explicit `--candidate-id` (validated; no path traversal).
Capture reuses the existing Official Detection Reference Baseline capture
machinery and official reference threshold `0.5`.

### Approval and rejection

Approval and rejection require explicit `--rationale` and `--approver`.

`SUPPLIED APPROVER IDENTIFIER != VERIFIED HUMAN IDENTITY`

This repository tooling provides deterministic governance evidence. It does not
provide cryptographic human identity, RBAC, legal approval, or deployment IAM.

Rejected candidates are terminal for promotion. Create a new candidate to
reconsider. Identical repeated decisions are treated as stable no-ops; conflicting
decision bytes are refused.

### Stale-candidate protection

Each candidate binds source official baseline ID and artifact hashes. If the
official baseline changes before approval/promotion, lifecycle reports
`CANDIDATE_STALE` and refuses the action. Candidates are not auto-rebased.

### Promotion

Promotion requires an approved, non-stale, non-identical candidate. It:

1. retains the current official baseline under `history/`
2. stages and validates the candidate as the future official set
3. publishes only the three official artifact files (lifecycle siblings untouched)
4. writes immutable promotion/retention records
5. refuses when history destination already exists

Identical candidates (`MATCH`) are refused as no-op — no meaningless history.

Publication safety is best-effort recoverable filesystem publication: history is
written before official mutation; individual artifact publish uses temp siblings
then replace; mid-failure recovery source is the retained history snapshot with
restore attempts where practical. Do not claim crash-proof multi-file atomicity
or cross-filesystem transactional guarantees.

### Rollback

Rollback is an explicit new governance event, not history rewrite:

1. validates the historical target
2. retains the current official baseline into a new history entry
3. republishes the historical baseline as official
4. writes a rollback governance record
5. never deletes prior history

Tampered history cannot be rolled back.

### Lifecycle CLI

```bash
./scripts/lifecycle-detection-reference-baseline.sh create-candidate --candidate-id <id>
./scripts/lifecycle-detection-reference-baseline.sh approve-candidate \
  --candidate-id <id> --rationale "..." --approver "..."
./scripts/lifecycle-detection-reference-baseline.sh reject-candidate \
  --candidate-id <id> --rationale "..." --approver "..."
./scripts/lifecycle-detection-reference-baseline.sh promote-candidate --candidate-id <id>
./scripts/lifecycle-detection-reference-baseline.sh rollback \
  --history-id <id> --rationale "..." --approver "..."
./scripts/lifecycle-detection-reference-baseline.sh list-history
```

Optional `--baseline <dir>` selects a lifecycle root (tests use temp roots).
No `--force`, `--overwrite`, `--threshold`, `--skip-validation`, or
`--ignore-stale` options exist.

Exit codes: `0` success, `1` invalid usage, `2` lifecycle failure.

### Schemas

- lifecycle candidate metadata: `lifecycleSchemaVersion = "1"`
- governance decision/promotion/retention/rollback: `governanceSchemaVersion = "1"`

Independent from baseline artifact schema and verification-report schema.

## Limitations

At minimum:

- reference corpus is synthetic/reference evaluation data
- it is not production traffic
- baseline results do not establish production efficacy
- baseline results do not establish production SLA
- threshold `0.5` is a fixed reference configuration, not an optimality claim
- threshold `0.5` is not a production recommendation
- anomalous does not mean malicious
- detection delay is not request latency
- framework acceptance does not establish detection-quality acceptance
- no controlled MONITOR pilot evidence is established
- no ENFORCE production approval is established
- observed metrics are not quality gates
- verification detects difference only; it does not approve/replace baselines
- lifecycle governance is repository/filesystem evidence, not production IAM approval
- supplied approver identifiers are declarative metadata, not verified identity
- even after governed promotion, the baseline is still not a production quality gate

## Explicit boundaries

- `REFERENCE DATASET != DETECTION BASELINE`
- `DETECTION BASELINE != PRODUCTION SLA`
- `DETECTION BASELINE != PRODUCTION EFFICACY`
- `BASELINE != QUALITY GATE`
- `BASELINE != THRESHOLD OPTIMALITY`
- `REFERENCE THRESHOLD != PRODUCTION THRESHOLD`
- `REFERENCE THRESHOLD != ENFORCEMENT THRESHOLD`
- `REFERENCE THRESHOLD != RECOMMENDED DEPLOYMENT THRESHOLD`
- `SYNTHETIC DATA != PRODUCTION TRAFFIC`
- `PERFORMANCE != DETECTION EFFECTIVENESS`
- `DETECTION DELAY != REQUEST LATENCY`
- `ANOMALOUS != MALICIOUS`
- `LABEL != FEATURE`
- `SCORER OUTPUT != GROUND TRUTH`
- `POLICY ACTION != DETECTOR PREDICTION`
- `INFRASTRUCTURE FAILURE != ATTACK`
- `INVALID SCORE != MAXIMUM RISK`
- `REPORT != BASELINE` (until deliberately published as this baseline)
- `FRAMEWORK ACCEPTANCE != DETECTION QUALITY ACCEPTANCE`
- `DRIFT != REGRESSION`
- `DRIFT != APPROVAL`
- `DIFFERENCE != FAILURE`
- `VERIFICATION RESULT != PRODUCTION ACCEPTANCE`
- `CANDIDATE != OFFICIAL BASELINE`
- `APPROVAL != PROMOTION`
- `APPROVAL != AUTOMATIC PROMOTION`
- `PROMOTION != PRODUCTION DEPLOYMENT`
- `METRIC DELTA != GOVERNANCE DECISION`
- `SUPPLIED APPROVER IDENTIFIER != VERIFIED HUMAN IDENTITY`
