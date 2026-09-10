# Deterministic Replay

Deterministic replay consumes a compatible evaluation dataset and re-executes AI-Sentinel scoring and policy behavior over the recorded `FeatureSnapshot` sequence. It is the prediction-evidence stage of offline evaluation, not the complete detection-evaluation framework and not the official detection baseline.

The current implementation lives in `ai-sentinel-core/src/main/java/dev/aisentinel/core/replay/` and writes portable replay artifacts as:

- `results.jsonl`
- `manifest.json`

Complete offline evaluation orchestration (align → classify → metrics → temporal → evidence) lives in `DetectionEvaluationRunner` and is documented in [`DETECTION_EVALUATION.md`](DETECTION_EVALUATION.md).

## Related documentation

- Reference dataset: [`REFERENCE_DATASET.md`](REFERENCE_DATASET.md)
- Detection evaluation framework: [`DETECTION_EVALUATION.md`](DETECTION_EVALUATION.md)
- Dataset/export contract: [`docs/contracts/DATASET_EXPORT.md`](../docs/contracts/DATASET_EXPORT.md)

## Purpose

Deterministic replay exists to answer one narrow engineering question:

- if dataset contents, scorer selection, policy configuration, feature schema, and replay mode are unchanged, do we reproduce equivalent machine-readable behavior?

Replay alone does not compute confusion matrices, precision/recall, ROC/PR, threshold acceptance rules, or official detection-quality claims. Those measurements, when produced, come from the separate detection-evaluation layer after alignment and an explicit caller-supplied classification threshold.

## Replay Boundary

Replay operates on ordered `FeatureSnapshot` observations, not on raw HTTP request reconstruction.

For each dataset event, replay preserves and uses:

- append order
- `eventId`
- `correlationId`
- `identityKey`
- `identityType`
- `observedAt`
- `endpointKey`
- `featureSchemaVersion`
- `features`

Replay creates fresh scorer and policy state for each run in `fresh-run` mode, then processes source events sequentially using dataset timestamps rather than wall-clock time.

Scenario boundaries do not reset state automatically. Identity state stays isolated per identity and endpoint, matching existing scorer semantics.

## EvaluationEvent Classification

Every `EvaluationEvent` field falls into one of four replay categories.

### Replay Input

- `eventId`
- `observedAt`
- `correlationId`
- `identityKey`
- `identityType`
- `endpointKey`
- `featureSchemaVersion`
- `features`

### Historical Reference Output

- `anomalyScore`
- `policyScore`
- `action`
- `evaluationStatuses`
- `riskFactors`

These fields are preserved in the dataset as historical reference material, but they are not fed back into replay scoring or replay policy evaluation.

### Provenance / Context

- `eventSchemaVersion`
- `scorerId`
- `scorerVersion`
- `policyId`
- `policyVersion`
- `evaluationMode`

Replay validates and records provenance, but does not treat these historical values as authoritative inputs for the new scorer or policy configuration. The active replay configuration is emitted separately in replay results and the replay manifest.

### Unsupported / Ignored

- no additional `EvaluationEvent` fields are supported beyond the versioned contract above
- incompatible dataset, event, or feature schema versions are rejected
- missing or unsupported scorer/model requests are rejected rather than silently substituted

## Output Contract

Each replay row in `results.jsonl` is versioned and ordered. The current contract includes:

- replay schema version
- deterministic replay run id
- replay status
- sequence number
- source `eventId`
- source `correlationId`
- source identity fields
- source timestamp
- source endpoint key
- feature schema version
- replay scorer id/version
- replay anomaly score when valid
- replay policy score when valid
- replay action
- replay evaluation statuses
- replay risk factors
- replay policy id/version
- replay evaluation mode

If a scorer returns an invalid numeric score, replay preserves the existing invalid-score semantics:

- the decision is not treated as maximum risk
- policy does not evaluate an invalid scalar
- replay emits `INVALID_SCORE`
- replay action remains fail-open unless quarantine was already in effect
- non-serializable numeric values are written as absent scores rather than invalid JSON

`manifest.json` records deterministic run-level provenance:

- replay schema version
- replay run id
- source dataset id
- source dataset events checksum
- dataset, event, and feature schema versions
- optional annotation schema version
- replay scorer id/version
- replay policy id/version
- configuration fingerprint covering replay mode, scorer settings, and policy thresholds
- replay mode
- ordering
- event count
- AI-Sentinel version
- results file name
- SHA-256 of `results.jsonl`

## Validation Rules

Replay currently validates:

- dataset manifest presence and checksum
- append-order dataset ordering
- supported dataset, event, and feature schema versions
- unique dataset event ids
- optional annotation linkage back to the dataset id
- deterministic replay output checksum
- replay result ordering and count
- replay output privacy and label boundaries

Partial output must not look complete. The writer stages temporary files and only finalizes `results.jsonl` and `manifest.json` together after successful completion.

## Supported Configuration

The current replay API is intentionally narrow:

- default replay mode: `fresh-run`
- default execution model: sequential
- first-class scorer support: statistical scorer
- default policy path: threshold policy

The design is extensible, but model-backed scorer replay is not enabled until explicit artifact/config validation is added. Unsupported requests fail clearly.

## Reference Dataset Developer Flow

Use the tracked reference dataset with:

```bash
./scripts/replay-reference-dataset.sh
```

You can also pass an output directory:

```bash
./scripts/replay-reference-dataset.sh build/reference-replay
```

The script compiles `ai-sentinel-core`, runs `dev.aisentinel.core.replay.ReferenceDatasetReplayMain`, validates the emitted replay artifacts, and prints the output location plus source and replay checksums.

## Boundaries Preserved

This layer deliberately preserves separation between:

- reference dataset generation
- deterministic replay
- detection evaluation
- official detection baseline

Replay validates annotations only as linked context. It does not interpret ground-truth labels as scorer inputs and does not compute quality metrics from them.

`REPORT != BASELINE` and `FRAMEWORK ACCEPTANCE != DETECTION QUALITY ACCEPTANCE` remain true even when replay artifacts are consumed by a complete evaluation run.
