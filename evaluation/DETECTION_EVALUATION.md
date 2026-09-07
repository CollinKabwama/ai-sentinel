# Detection Evaluation

This document defines the framework-independent evaluation boundary for AI-Sentinel detection quality.

It covers:

- deterministic alignment between independent reference annotations and replay output
- explicit anomaly-score classification for offline metrics
- deterministic dataset-level and scenario-level confusion-matrix accounting

It does not establish an official detection baseline.

## Evaluation Architecture

The evaluation flow is intentionally one-way:

```text
reference annotations -> independent truth
reference dataset -> deterministic replay -> current replay detector evidence
truth + replay evidence -> aligned observations -> detection metrics
```

Not:

```text
annotations + dataset -> replay or scoring
```

This preserves:

- `LABEL != FEATURE`
- `SCORER OUTPUT != GROUND TRUTH`
- `POLICY ACTION != DETECTOR PREDICTION`

Metrics operate only after alignment. They do not influence replay, scoring, baseline learning, policy, or enforcement.

## Inputs

The evaluation layer consumes existing contract families:

- source dataset structure from `EvaluationDatasetManifest` and replay-loaded source events
- independent reference truth from `ReferenceDatasetAnnotations`
- replay prediction output from `ReplayResult`

The main implementations live in:

- `ai-sentinel-core/src/main/java/dev/aisentinel/core/evaluation/ReferenceEvaluationAligner.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/evaluation/DetectionMetricsCalculator.java`

## Ground-Truth Source

Ground truth comes only from reference annotations.

Specifically:

- `ReferenceDatasetScenarioAnnotation.expectedClass`
- `ReferenceDatasetScenarioAnnotation.anomalyExpected`
- `ReferenceDatasetScenarioAnnotation.maliciousnessAsserted`

For anomaly-quality metrics, the binary target is the accepted `anomalyExpected` field:

- `NORMAL` -> expected anomalous = `false`
- `SYNTHETIC_ANOMALOUS` -> expected anomalous = `true`
- `LEGITIMATE_ANOMALOUS` -> expected anomalous = `true`

Maliciousness is separate diagnostic truth. It does not change anomaly confusion accounting.

## Replay Detector Evidence

Detector evidence comes from the replay anomaly score preserved on `EvaluationPrediction`.

The selected score for offline anomaly evaluation is:

- `EvaluationPrediction.anomalyScore`

This is the accepted replay detector score boundary because it is the scorer-produced anomaly signal already separated from:

- policy score
- policy action
- historical event output
- ground truth annotations

The metric layer does not reconstruct scorer internals or duplicate policy logic. It consumes the replay detector evidence already materialized by the accepted replay and alignment contracts.

## Policy-Action Separation

`ALLOW`, `MONITOR`, `THROTTLE`, `BLOCK`, and `QUARANTINE` remain diagnostic replay outputs only.

They do not determine binary anomaly prediction for metrics.

In particular:

- `MONITOR` does not automatically mean predicted normal
- `THROTTLE`, `BLOCK`, and `QUARANTINE` do not automatically mean predicted anomalous
- warmup `MONITOR` does not automatically become a positive detection

## Classification Rule

Offline anomaly classification is explicit and caller-supplied.

`DetectionClassificationConfiguration` requires:

- one explicit `anomalyThreshold`
- finite value in `[0,1]`
- no hidden default threshold

The threshold boundary is inclusive:

```text
predicted anomalous := anomalyScore >= anomalyThreshold
```

This behavior is deterministic for:

- scores below the threshold
- scores exactly equal to the threshold
- scores above the threshold
- score `0`
- score `1`

The threshold is not derived from policy action, policy thresholds, or reference-corpus outcomes.

## Alignment Rules

Alignment is deterministic and identifier-based only.

The join key is the stable `eventId` already present in:

- source dataset events
- replay results
- reference annotations

The aligner rejects:

- missing replay event for an evaluable annotation
- replay result referencing an unknown source event
- replay result fields that do not match the source event
- duplicate replay event ids
- annotation event ids not present in the source corpus
- conflicting scenario membership for the same evaluable event
- missing expected class
- unsupported expected class during annotation loading
- mixed replay run ids in one alignment input

No fuzzy matching is used.

## Ordering

Evaluation observations preserve dataset append order.

The source dataset order remains authoritative. Alignment iterates replay-loaded source events and emits evaluable observations in that same order.

Metric aggregation is deterministic over those aligned observations. Dataset-level counts do not depend on ordering. Scenario summaries preserve first-appearance order from the aligned observations rather than relying on unordered map iteration.

## Scenario Attribution

Each aligned observation carries:

- `scenarioId`
- `scenarioCategory`

Scenario metadata is evaluation metadata only. It is not a feature and is never provided to replay scoring.

Scenario-level metric aggregation is supported without introducing temporal semantics. It groups observations by scenario identity while preserving stable first-appearance order.

## Evaluable Event Rule

Not every scenario uses the same baseline/evaluation shape.

The alignment contract uses this deterministic rule:

- if a scenario declares non-empty `evaluationEventIds`, only those events are evaluable
- if a scenario declares no `evaluationEventIds` and no `baselineEventIds`, the full scenario `eventIds` set is evaluable

This preserves the distinction between:

- scenario context
- baseline-only events
- evaluable events

For the current tracked reference corpus:

- `REFERENCE_EVENT_COUNT = 136`
- `REFERENCE_SCENARIO_COUNT = 11`
- `ALIGNED_OBSERVATION_COUNT = 84`

The 84 aligned observations are the events currently declared evaluable by the annotation contract under the rule above.

## Historical Output Semantics

Historical fields embedded in `EvaluationEvent` are prior observations, not truth:

- historical score
- historical action
- historical statuses
- historical risk factors

Current replay output is the prediction evidence source. Independent annotations are the truth source.

Therefore:

- historical event output != replay prediction
- replay prediction != ground truth

## Invalid, Degraded, and Excluded Predictions

The metrics layer distinguishes valid classified predictions from excluded predictions whose detector evidence is unavailable.

An observation is excluded from confusion-matrix accounting when the accepted evaluation contract says the detector score is unavailable, including:

- missing anomaly score
- `INVALID_SCORE`
- `REMOTE_EVALUATION_FAILURE`

Excluded observations:

- do not become true negatives
- do not become false negatives
- do not become positive detections
- are counted explicitly as excluded/non-evaluable predictions

This preserves:

- `INVALID SCORE != MAXIMUM RISK`
- `INFRASTRUCTURE FAILURE != ATTACK`

## Fallback Semantics

Fallback and model-availability statuses remain diagnostic unless they invalidate the accepted replay detector evidence.

The scorer layer owns whether a fallback contributes to the replay anomaly score. The metrics layer does not reverse-engineer scorer internals.

With current contracts:

- a valid replay anomaly score remains classifiable when diagnostic fallback statuses are accompanied by statistical detector evidence such as `STATISTICAL_LIVE` or `STATISTICAL_WARMUP`
- a model-unavailable fallback-only score is excluded because it is an operational placeholder, not model inference evidence
- invalid or unavailable replay detector evidence remains excluded
- composite scoring already excludes fallback-only Isolation Forest values from the blended detector score unless genuine model output is available

This keeps degradation diagnostics visible without silently converting them into positive or negative anomaly labels.

## Confusion Matrix

For each evaluable prediction:

- expected anomalous + predicted anomalous -> true positive
- expected normal + predicted normal -> true negative
- expected normal + predicted anomalous -> false positive
- expected anomalous + predicted normal -> false negative

Confusion-matrix accounting is immutable and deterministic.

## Metrics

The metrics layer computes:

- precision = `TP / (TP + FP)`
- recall = `TP / (TP + FN)`
- F1 using the equivalent count form `2TP / (2TP + FP + FN)`
- false positive rate = `FP / (FP + TN)`
- false negative rate = `FN / (FN + TP)`

All defined ratio values are finite and constrained to `[0,1]`.

## Zero-Denominator Behavior

The framework distinguishes mathematically zero from undefined.

`DetectionMetricValue` represents:

- `defined = true` with a finite value in `[0,1]`
- `defined = false` with no numeric value

Undefined cases include:

- precision when `TP + FP = 0`
- recall and false negative rate when `TP + FN = 0`
- false positive rate when `FP + TN = 0`
- F1 when `2TP + FP + FN = 0`

No metric emits `NaN`, `+Infinity`, or `-Infinity`.

## Legitimate Anomalous Behavior

`LEGITIMATE_ANOMALOUS` contributes to anomaly truth as anomalous.

Therefore it may correctly produce:

- true positive if detected as anomalous
- false negative if not detected as anomalous

It is not automatically malicious and must not be reclassified as a false positive merely because the behavior is legitimate.

## Privacy

The evaluation framework operates only on accepted privacy-safe contracts.

It does not introduce:

- raw headers
- tokens
- cookies
- request bodies
- response bodies
- arbitrary query values
- generic metadata bags

## Current Limitations

This layer intentionally does not yet provide:

- delay or recovery metrics
- stabilization or warmup-duration metrics
- ROC or PR curves
- AUC
- threshold optimization
- threshold auto-selection
- report files or evidence bundles
- official baseline acceptance rules

It provides reusable measurement primitives only.

## Explicit Boundaries

The following remain true:

- `REFERENCE DATASET != DETECTION BASELINE`
- `REFERENCE DATASET != TRAINING DATASET`
- `SYNTHETIC DATA != PRODUCTION TRAFFIC`
- `LABEL != FEATURE`
- `SCORER OUTPUT != GROUND TRUTH`
- `POLICY ACTION != DETECTOR PREDICTION`
- `ANOMALOUS != MALICIOUS`
- `INVALID SCORE != MAXIMUM RISK`
- `INFRASTRUCTURE FAILURE != ATTACK`
- `PERFORMANCE != DETECTION EFFECTIVENESS`

## What Remains For Later Evaluation Work

Later work can build on these metrics to add:

- delay and recovery analysis
- warmup and stabilization analysis
- ROC or PR analysis
- evidence/report generation
- official detection baseline work

Those later capabilities remain separate from baseline establishment and quality acceptance.
