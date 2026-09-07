# Detection Evaluation Contracts

This document defines the evaluation-alignment boundary for AI-Sentinel.

It introduces a deterministic, framework-independent join between:

- independent reference annotations
- deterministic replay output

The result is an ordered set of evaluation observations for later metric work.

This increment does not compute metrics and does not establish an official detection baseline.

## Purpose

This contract creates the smallest durable boundary needed for later detection metrics without weakening the replay boundary or reinterpreting labels as model inputs.

The evaluation layer exists to answer:

- what is the expected truth for an observation?
- what is the replay-produced prediction for that observation?
- how do we align them deterministically and safely?

It does not answer:

- precision/recall/F1
- TP/TN/FP/FN
- ROC/PR analysis
- delay/recovery metrics
- official baseline acceptance rules

## Inputs

The alignment layer consumes three existing contract families:

- source dataset structure from `EvaluationDatasetManifest` and replay-loaded source events
- independent reference truth from `ReferenceDatasetAnnotations`
- replay prediction output from `ReplayResult`

The alignment implementation lives in:

- `ai-sentinel-core/src/main/java/dev/aisentinel/core/evaluation/ReferenceEvaluationAligner.java`

## Ground-Truth Source

Ground truth comes only from reference annotations.

Specifically:

- `ReferenceDatasetScenarioAnnotation.expectedClass`
- `ReferenceDatasetScenarioAnnotation.anomalyExpected`
- `ReferenceDatasetScenarioAnnotation.maliciousnessAsserted`

These values are independent labels. They are not replay input, not scorer input, and not policy input.

## Detector Evidence Source

Detector evidence comes only from replay output.

This alignment layer does not convert policy action into a binary anomaly prediction. `MONITOR`,
`THROTTLE`, `BLOCK`, and `QUARANTINE` are policy/enforcement outputs, not ground truth and not
the detector classification boundary for metrics.

The aligned observation preserves replay detector evidence:

- replay anomaly score when valid
- prediction source: replay score evidence

The observation also preserves diagnostic replay evidence:

- replay policy score when valid
- replay action
- replay evaluation statuses

Later metric code must apply an explicit evaluation classification rule to the replay score evidence.
That rule is intentionally not introduced here.

No maliciousness prediction is inferred from:

- `BLOCK`
- `QUARANTINE`
- high score
- warmup behavior
- degradation status

## Alignment Rules

Alignment is deterministic and identifier-based only.

The join key is the stable `eventId` already present in:

- source dataset events
- replay results
- reference annotations

The aligner rejects:

- missing replay event for an evaluable annotation
- replay result referencing an unknown source event
- duplicate replay event ids
- annotation event ids not present in the source corpus
- conflicting scenario membership for the same evaluable event
- missing expected class
- unsupported expected class during annotation loading
- mixed replay run ids in one alignment input

No fuzzy matching is used.

## Ordering

Evaluation observations preserve dataset append order.

The source dataset order remains authoritative. Alignment iterates the replay-loaded dataset event order and emits observations only for evaluable event ids in that same order.

The evaluation layer does not sort by timestamp and does not rely on hash iteration order.

## Scenario Attribution

Each aligned observation carries:

- `scenarioId`
- `scenarioCategory`

Scenario metadata is evaluation metadata only. It is not a feature and is never provided to replay scoring.

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

## Label-Isolation Rule

The direction is intentionally one-way:

```text
replay(dataset events) -> replay results -> join with annotations -> evaluation observations
```

Not:

```text
annotations + dataset events -> replay/scoring
```

This preserves:

- `LABEL != FEATURE`
- `SCORER OUTPUT != GROUND TRUTH`

The evaluation contracts do not expose `FeatureSnapshot`, request bodies, headers, or arbitrary metadata maps.

## Historical Output Semantics

Historical fields embedded in `EvaluationEvent` are prior observations, not truth:

- historical score
- historical action
- historical statuses
- historical risk factors

The alignment layer uses current replay output for prediction evidence and independent annotations for truth.

Therefore:

- historical event output != replay prediction
- replay prediction != ground truth

## Invalid and Failure Semantics

Existing safety rules remain intact:

- `INVALID SCORE != MAXIMUM RISK`
- infrastructure failure != attack
- anomalous != malicious

If replay output carries statuses such as:

- `INVALID_SCORE`
- `REMOTE_EVALUATION_FAILURE`

those statuses are preserved as diagnostics and mark the detector score as unavailable for later
classification. They do not automatically become positive anomaly truth and do not create
maliciousness claims.

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

This contract does not yet provide:

- metric computation
- score-distribution reports
- scenario summaries
- temporal delay/recovery analysis
- official baseline evidence

It is the aligned-input layer for those later increments.

## Explicit Boundaries

The following remain true:

- `REFERENCE DATASET != DETECTION BASELINE`
- `REFERENCE DATASET != TRAINING DATASET`
- `SYNTHETIC DATA != PRODUCTION TRAFFIC`
- `LABEL != FEATURE`
- `SCORER OUTPUT != GROUND TRUTH`
- `ANOMALOUS != MALICIOUS`
- `PERFORMANCE != DETECTION EFFECTIVENESS`

## What Remains For Later Evaluation Work

Later work can build on these aligned observations to add:

- confusion-matrix accounting
- precision/recall/F1
- false-positive and false-negative rates
- ROC/PR analysis
- delay/recovery and warmup analysis
- scenario and dataset reports

Those later increments remain separate from the official detection baseline tracked in Stage 10.
