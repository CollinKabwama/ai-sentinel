# Official Detection Reference Baseline

## Status

Initial **definition and capture** of the Official Detection Reference Baseline is
implemented on the current development line and awaits independent review.

Tracked artifacts:

- [`detection-reference-baseline/manifest.json`](detection-reference-baseline/manifest.json)
- [`detection-reference-baseline/evaluation.json`](detection-reference-baseline/evaluation.json)
- [`detection-reference-baseline/evaluation.md`](detection-reference-baseline/evaluation.md)

Related framework documentation: [`DETECTION_EVALUATION.md`](DETECTION_EVALUATION.md).

Still remaining (not part of this capture capability):

- generalized baseline verification / drift detection
- baseline lifecycle / governance / recapture / replacement approval

`DETECTION BASELINE != PRODUCTION EFFICACY` · `BASELINE != QUALITY GATE`

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

## Future work

- **Verification / drift detection** — compare a fresh current run against the
  official baseline
- **Lifecycle / governance** — replacement approval, promotion, rollback,
  recapture authorization

Do not treat capture integrity validation as generalized drift detection.

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
