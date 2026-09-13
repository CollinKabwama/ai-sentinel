# Official Detection Reference Baseline

## Status

This document records the **reference classification threshold decision** required
before the first Official Detection Reference Baseline can be captured.

The Detection Evaluation Framework is complete. The Official Detection Reference
Baseline itself is **not yet captured**.

Related framework documentation: [`DETECTION_EVALUATION.md`](DETECTION_EVALUATION.md).

## Purpose of a detection reference baseline

An Official Detection Reference Baseline is a deliberately captured, tracked,
deterministic record of accepted detector behavior against AI-Sentinel's
reference evaluation corpus under one explicitly identified evaluation
configuration.

It provides a stable engineering comparison point for future changes.

It does **not** establish production efficacy, production SLAs, enforcement
readiness, or quality acceptance.

`DETECTION BASELINE != PRODUCTION EFFICACY`

`BASELINE != QUALITY GATE`

`REPORT != BASELINE` remains true until a tracked baseline capture exists and
is intentionally published as such.

## Reference classification threshold decision

**Reference classification threshold: `0.5`**

This is an intentional engineering decision for **Official Detection Reference
Baseline reproducibility only**.

It is not a silent promotion of an ad-hoc diagnostic fixture. The decision
authorizes `0.5` as the fixed detector-classification boundary for that one
narrow baseline context.

### Classification rule

Under the existing evaluation framework:

```text
valid anomalyScore >= 0.5  → predicted anomalous
valid anomalyScore < 0.5   → predicted normal
```

Inclusive threshold semantics are unchanged. The framework continues to require
an explicit caller-supplied threshold for general evaluation runs. This document
does **not** introduce a hidden default into `DetectionClassificationConfiguration`
or the general CLI.

### Rationale

`0.5` is selected because:

- detector anomaly scores use a normalized `[0, 1]` domain
- `0.5` is the numerical midpoint of that normalized domain
- it provides a simple deterministic reference boundary
- it is already well exercised by evaluation-framework tests
- it enables stable comparison of future engineering changes
- it avoids coupling the reference baseline to enforcement policy bands

### Non-rationale

`0.5` is **not** selected because:

- it maximizes F1, recall, or precision
- it minimizes false positives or false negatives
- it performs best on the reference corpus
- it matches policy thresholds
- it is believed to be optimal

No threshold sweep, grid search, ROC/PR/AUC optimization, or corpus-label
tuning was performed to choose this value.

## Semantic boundaries

- `REFERENCE THRESHOLD != PRODUCTION THRESHOLD`
- `REFERENCE THRESHOLD != ENFORCEMENT THRESHOLD`
- `REFERENCE THRESHOLD != OPTIMAL THRESHOLD`
- `REFERENCE THRESHOLD != RECOMMENDED DEPLOYMENT THRESHOLD`
- `REFERENCE THRESHOLD != QUALITY GATE`
- `POLICY ACTION != DETECTOR PREDICTION`
- `DIAGNOSTIC RESULT != ACCEPTANCE CRITERION`
- `FRAMEWORK ACCEPTANCE != DETECTION QUALITY ACCEPTANCE`
- `REFERENCE DATASET != DETECTION BASELINE`
- `PERFORMANCE != DETECTION EFFECTIVENESS`
- `DETECTION DELAY != REQUEST LATENCY`
- `ANOMALOUS != MALICIOUS`

## Policy threshold separation

Existing policy bands such as:

```text
0.2  (moderate / MONITOR band floor)
0.4  (elevated / THROTTLE)
0.6  (high / BLOCK)
0.8  (critical / QUARANTINE)
```

remain **unrelated** to this reference classification decision.

They map policy/enforcement actions from policy/fused risk scores. They must
not become detector-classification thresholds and must not be used to derive
baseline detector predictions.

`POLICY ACTION != DETECTOR PREDICTION`

## Relationship to diagnostic evaluation examples

Ad-hoc evaluation examples may still show `--threshold 0.5` as a caller-supplied
value. For general framework usage, any threshold remains explicit caller input.

For **Official Detection Reference Baseline** capture, `0.5` is the authorized
**reference classification threshold**. Changing that reference value later
requires a new baseline capture.

## What remains

After this decision:

1. Official Detection Reference Baseline **definition and capture** may resume
2. Baseline **verification / drift detection** remains future work
3. Baseline **lifecycle / governance / recapture** remains future work

Do not treat this decision document as the captured baseline. Capture must still
produce tracked baseline artifacts bound to accepted evaluation evidence.
