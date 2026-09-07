# Reference Synthetic / Evaluation Dataset

## Purpose

This is AI-Sentinel's first durable synthetic engineering evaluation dataset.

It exists to provide a stable, deterministic, privacy-safe behavioral corpus for future:

- deterministic replay;
- regression evaluation;
- scorer comparison;
- detection-quality measurement;
- external candidate-model evaluation;
- shadow-scoring evaluation;
- champion/challenger comparisons.

It is not:

- production traffic;
- a claim of production representativeness;
- a training dataset;
- a benchmark SLA;
- proof of real-world attack prevalence;
- the official detection baseline.

`REFERENCE DATASET != DETECTION BASELINE`

`REFERENCE DATASET != TRAINING DATASET`

`SYNTHETIC DATA != PRODUCTION TRAFFIC`

`ANOMALOUS != MALICIOUS`

## Location

Tracked dataset artifacts live in:

```text
evaluation/reference/
  manifest.json
  events.jsonl
  annotations.json
```

This dataset is repository-level rather than hidden under unit-test resources because future replay and detection-evaluation tooling will need a shared, durable location.

## Architecture

- Observed event record: `EvaluationEvent`
- Dataset envelope: `EvaluationDatasetManifest` + `EvaluationDatasetWriter`
- Ground truth: `annotations.json`
- Generator: `ReferenceDatasetGenerator`
- Validator: `ReferenceDatasetValidator`

Labels are intentionally separated from observed event data.

The event file describes what was observed.

The annotation file describes what the synthetic scenario represents.

## Dataset Semantics

The reference corpus is sequence-based, not a bag of unrelated events.

Important semantics:

- deterministic timestamps
- deterministic event IDs and correlation IDs
- append order is authoritative
- multiple pseudonymous identities
- warmup and baseline formation
- abrupt deviation
- gradual drift
- legitimate anomalous behavior
- interleaved identities for isolation checks

## Ground Truth

Ground truth lives in `annotations.json`.

Each scenario annotation records:

- scenario ID
- scenario category
- expected synthetic class
- whether anomaly is expected
- whether maliciousness is asserted
- event IDs in the scenario
- baseline event IDs
- evaluation event IDs
- identities involved
- features exercised
- notes

Current expected classes:

- `NORMAL`
- `LEGITIMATE_ANOMALOUS`
- `SYNTHETIC_ANOMALOUS`

No label is injected into `FeatureSnapshot` or any scorer input.

## Included Scenarios

The first corpus includes:

1. `normal-established-baseline`
2. `warmup-new-identity`
3. `rapid-request-burst`
4. `endpoint-behavior-change`
5. `payload-size-deviation`
6. `parameter-count-deviation`
7. `token-age-change`
8. `low-variance-baseline-deviation`
9. `gradual-behavior-change`
10. `legitimate-bulk-operation`
11. `interleaved-normal-identities`

This is intentionally bounded. It is designed for coverage and reviewability, not statistical representativeness.

## Feature Generation

The generator uses the production `DefaultFeatureExtractor` with a deterministic injected clock and in-memory request fixtures.

That means:

- feature values follow `FeatureSchema v1`
- endpoint normalization is reused
- token-age semantics are reused
- request-window and endpoint-history behavior are reused
- generation does not depend on servlet containers, network, Redis, or wall-clock time

Observed decision outputs are generated through the real statistical scorer and decision engine, but they are not ground truth. Ground truth remains in the annotation file.

`SCORER OUTPUT != GROUND TRUTH`

## Privacy

The dataset is synthetic and privacy-safe by construction:

- pseudonymous identities only
- normalized endpoint keys only
- no raw request/response bodies
- no raw cookies
- no authorization values in exported dataset artifacts
- no real names, emails, or account IDs
- no raw query strings in exported event records

Synthetic request builders may use placeholder request metadata to exercise production feature extraction, but that data is not exported into the tracked dataset beyond privacy-safe features and normalized endpoint keys.

## Regeneration

Regenerate and compare with:

```bash
./scripts/generate-reference-dataset.sh
```

That command:

1. compiles the core module;
2. generates the dataset into a temporary directory;
3. compares the generated artifacts to `evaluation/reference/`.

To intentionally refresh the tracked dataset:

```bash
./scripts/generate-reference-dataset.sh --write
```

Refresh is explicit. The script does not silently overwrite tracked artifacts.

## Validation

Validation checks include:

- deterministic regeneration
- checksum integrity
- scenario coverage
- event uniqueness
- scenario uniqueness
- supported schema versions
- annotation linkage to event IDs
- prohibited marker scanning on dataset artifacts
- tracked dataset equality with deterministic regeneration

## Limitations

- this milestone does not implement replay
- this milestone does not compute precision/recall/F1/ROC/PR
- this milestone does not establish a detection baseline
- this milestone does not claim scorer efficacy
- current observed outputs come from the present statistical decision stack and are included only as observations, not as authoritative labels
