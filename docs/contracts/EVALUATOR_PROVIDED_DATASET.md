# Evaluator-provided (BYO) dataset authoring

Minimal guidance for authoring a normalized evaluator-provided Evaluation Kit dataset.

BYO evaluation is controlled evidence only. It is **not** production validation and **not**
independent third-party validation.

For the full contract, see [`EVALUATION_KIT.md`](EVALUATION_KIT.md) and
[`schemas/evaluation-kit/evaluator-dataset-manifest.schema.json`](schemas/evaluation-kit/evaluator-dataset-manifest.schema.json).

## Directory layout

```text
my-dataset/
  dataset-manifest.json
  events.jsonl
  annotations.json          # optional; omit for unlabeled evaluation
```

Do **not** include `corpus-manifest.json`, generator seed/build fields, or a separate replay
`manifest.json`. The Kit builds a replay dataset in memory from the BYO directory.

## `dataset-manifest.json`

Required concepts:

| Field | Meaning |
|-------|---------|
| `datasetSchemaVersion` | Manifest shape (`"1"`). |
| `datasetId` | Stable evaluator-declared dataset identity. |
| `featureSchemaVersion` | Feature contract (`"1"`). |
| `evaluationEventSchemaVersion` | EvaluationEvent contract (`"1"`). |
| `representationMode` | Currently Kit BYO expects `feature-level`. |
| `ordering` | Event order semantics (for example `append-order`). |
| `events.path` / `events.sha256` | Relative path + SHA-256 of `events.jsonl`. |
| `eventCount` | Declared event count; must match the JSONL line count. |
| `annotations` | Optional; when present, path + SHA-256 of `annotations.json`. |

Contract example (illustrative checksums):  
[`fixtures/evaluation-kit/valid/evaluator-dataset-manifest.example.json`](fixtures/evaluation-kit/valid/evaluator-dataset-manifest.example.json)

## `events.jsonl`

One EvaluationEvent JSON object per line. Events are **detector-facing**: do not embed expected
class, anomaly labels, or assertion payloads.

Feature schema `"1"` canonical fields (all required when using feature-level representation):

1. `requestsPerWindow`
2. `endpointEntropy`
3. `endpointConcentration`
4. `tokenAgeSeconds`
5. `parameterCount`
6. `payloadSizeBytes`
7. `headerFingerprintHash`
8. `ipBucket`

See [`FEATURE_SCHEMA.md`](FEATURE_SCHEMA.md) and [`EVALUATION_EVENT.md`](EVALUATION_EVENT.md).

## `annotations.json` (optional)

When present:

- Use Kit ground-truth shape with `datasetId` (not `corpusId`).
- Join to events by `eventId`.
- Declared SHA-256 in the manifest must match file bytes.

When absent, the evaluation is unlabeled: labeled detection metrics are unavailable (not zero).

## Minimal examples in this repository

Reusable unit fixtures (real checksums):

| Example | Path |
|---------|------|
| Labeled | `ai-sentinel-core/src/test/resources/evaluation-kit-byo/minimal-labeled/` |
| Unlabeled | `ai-sentinel-core/src/test/resources/evaluation-kit-byo/minimal-unlabeled/` |

Copy either directory and point the CLI at it.

## Exact command

From the repository root (requires JDK 21):

```bash
scripts/evaluate-generated-corpus.sh \
  --dataset ai-sentinel-core/src/test/resources/evaluation-kit-byo/minimal-labeled \
  --output /tmp/byo-labeled-out
```

Unlabeled:

```bash
scripts/evaluate-generated-corpus.sh \
  --dataset ai-sentinel-core/src/test/resources/evaluation-kit-byo/minimal-unlabeled \
  --output /tmp/byo-unlabeled-out
```

The output directory must not already exist (strict no-overwrite).

Expected artifacts beside each other:

- `kit-evaluation-result.json` (includes `resultId` family id + `evaluationRunId`)
- `event-inspection.json`
- `evaluation-report.html`
- specialized `evaluation.json` / `evaluation.md`

## Expected result behavior

| Dataset | Expected |
|---------|----------|
| Labeled minimal | `datasetSource: evaluator-provided`; labeled detection metrics available when binary-labeled evaluation events exist. |
| Unlabeled minimal | Labeled detection metrics **unavailable**; report explains that no binary ground truth was supplied. Unavailable ≠ zero performance ≠ evaluator failure. |

## Common validation errors

| Symptom | Likely cause |
|---------|--------------|
| `MISSING_ARTIFACT` / dataset directory does not exist | Wrong `--dataset` path. |
| Manifest vs annotations mismatch | `annotations` declared but file missing, or file present but not declared. |
| Integrity / SHA mismatch | Manifest checksum does not match file bytes after edits. |
| Unsupported schema / integrity failure | Malformed JSONL, wrong schema versions, or generator/corpus fields present on BYO evidence. |
| Usage exit | `--corpus` and `--dataset` supplied together (mutually exclusive). |
| Output directory already exists | Strict no-overwrite; choose a new `--output` path. |

## Claim boundaries

- Evaluation ≠ deployment decision
- BYO ≠ independent production validation
- Anomalous ≠ malicious
- Unavailable metrics ≠ zero / failure
