# Detection Evaluation

This document defines the framework-independent evaluation boundary for AI-Sentinel detection quality.

It covers:

- deterministic alignment between independent reference annotations and replay output
- explicit anomaly-score classification for offline metrics
- deterministic dataset-level and scenario-level confusion-matrix accounting
- deterministic temporal interpretation of aligned anomaly predictions
- deterministic evidence and report generation from accepted evaluation results

It does not establish an official detection baseline.

## Related documentation

- Reference dataset: [`REFERENCE_DATASET.md`](REFERENCE_DATASET.md)
- Deterministic replay: [`DETERMINISTIC_REPLAY.md`](DETERMINISTIC_REPLAY.md)
- Dataset/export contract: [`docs/contracts/DATASET_EXPORT.md`](../docs/contracts/DATASET_EXPORT.md)
- Evaluation event contract: [`docs/contracts/EVALUATION_EVENT.md`](../docs/contracts/EVALUATION_EVENT.md)

## Evaluation Architecture

The evaluation flow is intentionally one-way:

```text
reference dataset + annotations
  -> deterministic replay
  -> truth/replay alignment
  -> explicit classification
  -> detection metrics
  -> temporal evaluation
  -> deterministic evidence (evaluation.json / evaluation.md)
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

### Complete-run orchestration

`DetectionEvaluationRunner` is the reusable, framework-independent orchestration boundary for one complete offline evaluation. It composes accepted stage components and does not reimplement scoring, classification, metrics, temporal segmentation, or evidence serialization.

`ReferenceDetectionEvaluationEvidenceMain` is a thin CLI adapter over that runner for the tracked reference corpus. It parses arguments, locates accepted inputs, requires an explicit `--threshold`, selects an output destination, and invokes the runner.

Material configuration for one run is explicit caller input:

- reference dataset identity and annotation file
- `ReplayConfiguration`
- `DetectionClassificationConfiguration` (caller-supplied anomaly threshold)

There is no hidden threshold default, policy-derived threshold, or corpus-derived threshold selection.

### Cross-stage consistency

Independently valid stage outputs may still be an invalid complete evaluation when combined. The framework rejects contradictory combinations at the narrowest appropriate boundary, including:

- dataset / annotation identity and schema mismatch
- replay output that does not bind to the evaluated dataset
- metrics / temporal / evidence threshold provenance mismatch
- scenario identity or category drift across metrics and temporal sections
- structural count contradictions in the evidence model

### State isolation

Each complete run starts from fresh replay state. Prior successful runs, prior classification configurations, and prior failed runs must not alter a later independent evaluation of the same inputs.

### Framework acceptance scope

Framework hardening establishes technical readiness of the evaluation machinery:

- determinism
- integrity
- reproducibility
- contract consistency
- provenance
- privacy
- failure behavior

It does **not** establish detector quality acceptance.

Explicitly:

- `FRAMEWORK ACCEPTANCE != DETECTION QUALITY ACCEPTANCE`
- `REPORT != BASELINE`
- `REFERENCE DATASET != DETECTION BASELINE`
- `DIAGNOSTIC RESULT != ACCEPTANCE CRITERION`
- `DETECTION DELAY != REQUEST LATENCY`

Diagnostic precision/recall/FPR/FNR/delay values may appear in evidence for inspection. They are not pass/fail quality gates and do not approve current detection efficacy.

## Inputs

The evaluation layer consumes existing contract families:

- source dataset structure from `EvaluationDatasetManifest` and replay-loaded source events
- independent reference truth from `ReferenceDatasetAnnotations`
- replay prediction output from `ReplayResult`

The main implementations live in:

- `ai-sentinel-core/src/main/java/dev/aisentinel/core/evaluation/DetectionEvaluationRunner.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/evaluation/ReferenceEvaluationAligner.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/evaluation/DetectionMetricsCalculator.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/evaluation/TemporalDetectionEvaluator.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/evaluation/DetectionEvaluationEvidenceGenerator.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/evaluation/DetectionEvaluationEvidenceWriter.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/evaluation/ReferenceDetectionEvaluationEvidenceMain.java`

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

Metric aggregation and temporal evaluation are deterministic over those aligned observations. Dataset-level counts do not depend on ordering. Scenario summaries and temporal segments preserve first-appearance order from the aligned observations rather than relying on unordered map iteration.

## Scenario Attribution

Each aligned observation carries:

- `scenarioId`
- `scenarioCategory`

Scenario metadata is evaluation metadata only. It is not a feature and is never provided to replay scoring.

Scenario-level metric aggregation is supported without introducing temporal semantics. Temporal evaluation reuses the same scenario attribution and ordered observation sequence.

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

## Temporal Evaluation

Temporal evaluation remains downstream of:

- alignment
- detector-score classification
- evaluable detector-evidence rules

It does not re-run replay, re-label truth, or reinterpret policy action as detector prediction.

`DETECTION DELAY != REQUEST LATENCY`

Temporal detection delay measures how many ordered evaluation observations elapse between anomalous truth onset and the first positive detector classification. It is not application request latency.

## Temporal Units

The framework supports two deterministic temporal units:

- observation-count delay derived from aligned source order
- event-time delay derived from `EvaluationObservation.observedAt()` and represented as `java.time.Duration`

Observation-count delay is always supported because alignment preserves deterministic source ordering.

Event-time delay is supported only because aligned observations already carry deterministic timestamps. Temporal evaluation rejects scenario-local timestamp regressions rather than silently correcting them.

## Anomaly Segments

Temporal evaluation groups each scenario's aligned observations into contiguous truth segments based on `EvaluationTruth.anomalousExpected()`.

For each anomalous segment:

- anomaly onset = first observation in the contiguous anomalous truth segment
- anomaly window end = last observation in that anomalous truth segment
- first detection = first evaluable replay prediction classified anomalous by the explicit threshold rule

Multiple anomalous segments are supported per scenario when the ordered truth sequence contains repeated anomalous periods.

## Detection Delay

Detection delay uses a zero-based elapsed-observation convention:

- first anomalous observation detected immediately -> delay `0`
- second anomalous observation is first detection -> delay `1`
- third anomalous observation is first detection -> delay `2`

The temporal result tracks both:

- `detectionObservationDelay`: source-position distance from anomaly onset
- `evaluableObservationDelay`: number of prior evaluable detector opportunities before first detection

If detection occurs, `detectionTimeDelay` is the duration between anomaly onset and first detection.

## Undetected and Censored Segments

If no positive detector classification occurs during an anomalous segment:

- `detected = false`
- first detection is absent
- delays are absent

This means not detected within the observed evaluation window. It does not claim detection is impossible beyond the observed window.

## Recovery and Stabilization

When an anomalous truth segment is immediately followed by a contiguous normal truth segment, temporal evaluation computes recovery from that normal recovery segment.

Recovery onset is:

- the first expected-normal observation following the anomalous segment

Stabilization is:

- the earliest expected-normal observation from which all remaining observations in that contiguous normal recovery segment are evaluable and predicted normal

This avoids treating a transient single normal prediction as stable recovery.

If stabilization occurs, the result exposes:

- `recoveryObservationDelay`
- `evaluableRecoveryObservationDelay`
- `recoveryTimeDelay`

If no stable normal classification occurs before the recovery window ends, recovery remains explicitly unstabilized.

The current tracked reference corpus contains no observed recovery windows: each anomalous evaluable scenario ends at the end of its anomaly evaluation window. Recovery semantics are covered by unit fixtures and remain compatible with future scenarios that include anomalous-to-normal evaluation transitions.

## Warmup and Context Separation

Temporal evaluation uses the accepted evaluation observation set only.

It does not measure onset or delay from scenario baseline/context events that were excluded by the alignment contract.

Warmup is not inferred from:

- `MONITOR`
- the replay policy action
- any special meaning attached to score `0.4`

If an explicitly evaluable observation is still in statistical warmup, it remains part of temporal evaluation according to the same detector-evidence rules used by metrics.

## Unavailable Detector Evidence in Temporal Evaluation

Temporal evaluation reuses the accepted evaluability boundary shared with classification metrics.

Unavailable detector evidence includes:

- missing anomaly score
- `INVALID_SCORE`
- `REMOTE_EVALUATION_FAILURE`
- model-unavailable fallback-only placeholder evidence

Unavailable observations:

- do not become positive or negative classifications
- still count toward source-position delay
- do not count as prior evaluable detector opportunities
- do not satisfy stable recovery

## Evidence and Report Generation

Evidence generation is downstream-only.

It consumes accepted evaluation results and provenance:

- `ReferenceEvaluationAlignment`
- `DetectionClassificationConfiguration`
- `DetectionEvaluationMetrics`
- `TemporalDetectionEvaluation`
- existing dataset and replay provenance already established by deterministic replay and dataset contracts

It does not:

- rerun replay
- reclassify scores independently
- re-derive truth
- redefine temporal segments
- choose or optimize thresholds

`REPORT != BASELINE`

The generated evidence answers:

- what deterministic inputs were evaluated
- which explicit threshold was used
- what structural counts and metrics were produced
- what temporal segment and recovery results were produced

It does not decide whether those results are formally approved or acceptable.

## Canonical Artifacts

The current evidence writer produces a caller-directed output directory containing:

- `evaluation.json`
- `evaluation.md`

`evaluation.json` is the canonical machine-readable artifact.

`evaluation.md` is a deterministic human-readable rendering generated from the same evidence model rather than a second calculation path.

The output directory itself is caller-selected. Canonical artifact contents do not include local absolute filesystem paths.

`WrittenEvidence` returns SHA-256 hashes and byte counts for the exact UTF-8 bytes read back from the published `evaluation.json` and `evaluation.md` files. Those artifact hashes are not embedded into `evaluation.json`, avoiding self-referential content.

## Evidence Schema and Provenance

The canonical evidence model records:

- evidence schema version
- report kind
- reference dataset provenance
- replay provenance
- explicit classification threshold and boundary semantics
- structural counts
- confusion matrix
- metrics
- scenario-level metrics
- temporal scenario and segment results
- deterministic limitations statements

Threshold provenance is always explicit. There is no default threshold in the evidence model, writer, or reference-corpus entrypoint.

## Deterministic Serialization

Canonical evidence serialization is deterministic by design:

- fixed property ordering
- stable list ordering
- accepted scenario first-appearance ordering
- accepted temporal segment ordering
- UTF-8 output
- final newline on canonical artifacts
- no wall-clock generation timestamp in canonical content
- no destination-path leakage into canonical content
- Markdown-sensitive evidence strings are escaped before human-readable rendering

Repeated generation over identical accepted inputs and configuration should produce byte-identical `evaluation.json` and `evaluation.md` contents.

## Numeric and Undefined Metric Handling

Machine-readable evidence preserves full accepted metric precision using the existing `double` values.

Undefined metrics remain explicitly undefined in JSON as:

- `defined = false`
- `value = null`

They are not rewritten as:

- `NaN`
- `Infinity`
- `0`

Markdown renders undefined metrics conservatively as `undefined`.

## Structural Evidence

The report includes deterministic structural counts such as:

- reference event count
- scenario count
- aligned evaluation observation count
- expected normal observation count
- expected anomalous observation count
- evaluable prediction count
- excluded prediction count
- anomalous segment counts
- recovery-window counts

These counts reconcile with the accepted metrics and temporal results rather than introducing a second accounting path.

## Scenario and Temporal Evidence

Scenario metrics remain in accepted first-appearance order.

Temporal reporting includes, per anomalous segment where available:

- segment index
- anomaly onset
- anomaly window end
- whether the segment was detected within the observed anomaly window
- first detection
- source observation delay
- evaluable-opportunity delay
- event-time delay
- unavailable detector-evidence count
- recovery-window presence
- stable recovery status
- recovery delay

The report layer does not reinterpret these semantics. It renders the accepted temporal contracts directly.

## No-Overwrite and Partial-Write Behavior

The evidence writer does not silently overwrite an existing evidence directory.

It writes canonical artifacts into a temporary directory, validates the rendered outputs, and only then moves the completed directory into place.

That prevents `evaluation.json` from appearing finalized without its matching `evaluation.md`.

The temporary directory is created as a sibling of the requested output directory. Publication first attempts an atomic directory move and falls back to a normal no-replace move when the filesystem does not support atomic directory moves.

## Privacy Boundary for Evidence

Evidence artifacts remain within the accepted privacy boundary.

They do not expose:

- raw identity keys from evaluation observations
- raw endpoints
- raw headers
- authorization values
- cookies
- query values
- request or response bodies
- `FeatureSnapshot`

Evidence operates on accepted privacy-safe identifiers, counts, scenario metadata, and deterministic provenance only.

## Reference-Corpus Evidence Generation

`DetectionEvaluationRunner` orchestrates the real pipeline. The tracked-corpus CLI
`ReferenceDetectionEvaluationEvidenceMain` is a thin adapter that:

1. requires an explicit `--threshold`
2. locates the tracked reference dataset and annotations
3. constructs `ReplayConfiguration.referenceDefaults()` and explicit classification configuration
4. invokes the runner
5. prints publication summary metadata

The runner itself:

1. loads and consistency-checks dataset/annotation identity
2. runs deterministic replay
3. validates replay output against the evaluated dataset and configuration
4. aligns independent truth with replay output
5. computes accepted detection metrics
6. computes accepted temporal evaluation
7. constructs deterministic evidence under the same explicit classification configuration
8. writes `evaluation.json` and `evaluation.md`

The entrypoint requires an explicit threshold. It does not infer one from policy thresholds or corpus outcomes.

### Running the tracked-corpus CLI

From the repository root, after compiling `ai-sentinel-core`:

```bash
mvn -q -pl ai-sentinel-core -DskipTests compile dependency:build-classpath \
  -Dmdep.outputFile=/tmp/ai-sentinel-cp.txt
java -cp "ai-sentinel-core/target/classes:$(cat /tmp/ai-sentinel-cp.txt)" \
  dev.aisentinel.core.evaluation.ReferenceDetectionEvaluationEvidenceMain \
  --threshold 0.5 \
  --output build/reference-detection-evaluation-evidence
```

`--threshold` is required and must be a finite value in `[0, 1]`. `--output` is optional and defaults to `build/reference-detection-evaluation-evidence`.

Any threshold used for a local or CI diagnostic run is caller-supplied for that run only. It is not a recommended, approved, or official anomaly threshold.

Corpus regeneration and replay-only helpers live in [`scripts/README.md`](../scripts/README.md).

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

- warmup-duration metrics
- ROC or PR curves
- AUC
- threshold optimization
- threshold auto-selection
- official baseline establishment
- official baseline acceptance rules
- detector-quality pass/fail gates over precision/recall/FPR/FNR/delay

It provides reusable measurement, orchestration, and evidence-generation primitives only.

Framework hardening may conclude that the evaluation machinery is ready to generate a *candidate* official reference baseline. That conclusion is not itself baseline establishment or quality approval.

## Explicit Boundaries

The following remain true:

- `FRAMEWORK ACCEPTANCE != DETECTION QUALITY ACCEPTANCE`
- `REPORT != BASELINE`
- `REFERENCE DATASET != DETECTION BASELINE`
- `DIAGNOSTIC RESULT != ACCEPTANCE CRITERION`
- `REFERENCE DATASET != TRAINING DATASET`
- `SYNTHETIC DATA != PRODUCTION TRAFFIC`
- `LABEL != FEATURE`
- `SCORER OUTPUT != GROUND TRUTH`
- `POLICY ACTION != DETECTOR PREDICTION`
- `ANOMALOUS != MALICIOUS`
- `INVALID SCORE != MAXIMUM RISK`
- `INFRASTRUCTURE FAILURE != ATTACK`
- `PERFORMANCE != DETECTION EFFECTIVENESS`
- `DETECTION DELAY != REQUEST LATENCY`
- `IN-PROCESS EVALUATION != FULL APPLICATION REQUEST LATENCY`

## What Remains For Later Evaluation Work

Later work can build on these metrics and temporal results to add:

- formal approval of an official detection baseline
- aggregate temporal summaries
- warmup-duration and broader stabilization analysis
- ROC or PR analysis
- richer comparison/report packaging if later evidence requires it

Those later capabilities remain separate from baseline establishment and quality acceptance.

Official Detection Reference Baseline establishment is a distinct follow-on capability. Completing framework hardening does not approve current detector quality.
