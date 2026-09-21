# Evaluation Kit Contracts

Normative contract foundations for the AI-Sentinel Evaluation Kit.

JSON is the **normative machine representation** for schemas and fixtures in this package.
JSON does **not** permanently constrain human authoring UX: later CLI/tooling may accept YAML or other conveniences that normalize to the same logical contract.

This document defines Evaluation Kit contracts. It does not claim production efficacy and does not change production runtime decision behavior. A deterministic corpus generator for feature-level scenario families lives in core; a versioned repository reference inventory is checked in under `evaluation/kit-reference/`. A repository one-command evaluation CLI is available via `scripts/evaluate-generated-corpus.sh` and accepts either a generated corpus (`--corpus`) or an evaluator-provided (BYO) dataset (`--dataset`). When `--output` is supplied, the CLI writes machine-readable Kit evaluation-result JSON, event-inspection JSON, a self-contained HTML evaluation report, and specialized detection evidence beside each other. An optional local container image (`Dockerfile.evaluation-kit`) packages the same evaluator without requiring a host JDK/Maven install at runtime. Before/after comparison of two existing evaluation runs is available via `scripts/compare-evaluations.sh` (factual deltas only; not a ranking or promotion decision). Large-artifact storage strategy (Git vs archive residency, content identity, local verification) is documented in [`evaluation/EVIDENCE_ARTIFACT_STORAGE.md`](../../evaluation/EVIDENCE_ARTIFACT_STORAGE.md).

Current schema versions for Kit machine contracts in this package: `"1"`.

---

## 1. Three concepts (must remain distinct)

| Concept | Role |
|---------|------|
| **Scenario / Test Plan** | Authoring definition of an experiment (population, behavior, timing, transitions, evaluation expectations). |
| **Corpus Generator** | Deterministic compile function: Scenario + seed + generator contract/build identity → Generated Corpus. |
| **Generated Corpus** | Concrete artifact set (events + manifest + optional ground-truth sidecar). |

**Scenario ≠ Generator ≠ Corpus.**

Existing `evaluation/reference/` is a **historical seed instance**, not the Kit generative architecture. Deterministic corpus generation for Kit scenarios is implemented under `dev.aisentinel.core.dataset.corpus` (feature-level). The repository-owned **versioned reference evaluation corpus** lives at [`evaluation/kit-reference/`](../../evaluation/kit-reference/) and is regenerated/verified via `scripts/verify-kit-reference-corpus.sh`.

---

## 2. Related existing contracts (reuse map)

| Existing concept | Kit relationship |
|------------------|------------------|
| [`FEATURE_SCHEMA.md`](FEATURE_SCHEMA.md) / `FeatureSchema` | **REUSE** — declare `featureSchemaVersion` compatibility. |
| [`EVALUATION_EVENT.md`](EVALUATION_EVENT.md) / `EvaluationEvent` | **REUSE** — detector-facing evidence atom; **no labels**; remaining convergence gaps are documented in §10. |
| `EvaluationDatasetManifest` | **ADAPT** — pattern for Generated Corpus Manifest fields. |
| `ReferenceDatasetAnnotations` | **ADAPT** (pattern) / **KEEP SEPARATE** (instance) — ground-truth sidecar pattern. |
| `ReplayRunManifest` / `ReplayProvenance` | **REUSE / COMPOSE** — remain distinct replay artifacts; Kit provenance binds them. |
| `CandidateEvaluationProvenance` | **KEEP SEPARATE / COMPOSE** — optional when evaluating a candidate artifact. |
| `DetectionEvaluationEvidence` | **KEEP SEPARATE** — specialized labeled detection-evaluation evidence; **not** the generic Kit Result. |
| `EvaluationRequest` / `EvaluationResponse` / `EvaluationExecutor` | **KEEP SEPARATE** — runtime evaluation API; not Kit experiment contracts. |
| `DetectionReferenceBaselineManifest` | **KEEP SEPARATE** — official baseline binding ≠ Kit scenario/corpus. |

`ai-sentinel-benchmark` remains JMH performance tooling. It does **not** become the Evaluation Kit.

### Module, artifact, and distribution boundary (current stage)

| Axis | Current decision |
|------|------------------|
| **Module boundary** | Evaluation Kit execution, report projection, corpus generation helpers, and the repository CLI entry live in **`ai-sentinel-core`** (`dev.aisentinel.core.evaluation`, `dev.aisentinel.core.dataset.corpus`, `dev.aisentinel.core.replay`). |
| **Artifact boundary** | No separate Evaluation Kit Maven module or artifact. Kit capabilities ship inside `dev.aisentinel:ai-sentinel-core` when that artifact is released. |
| **Distribution boundary** | Primary evaluator front door remains **repository checkout** + **`scripts/evaluate-generated-corpus.sh`** (host JDK 21 + Maven). An optional **local** container image (`Dockerfile.evaluation-kit`) packages the same `GeneratedCorpusEvaluationMain` entry so runtime evaluation does not require a host JDK/Maven install. There is no published registry image and no separate executable installer. |
| **Publication boundary** | No Evaluation Kit–specific Maven Central artifact. Published library coordinates remain the existing release set (`ai-sentinel`, `ai-sentinel-core`, `ai-sentinel-spring-boot-starter`). Benchmark, trainer, and demo remain non-Central libraries. |
| **Schema ownership** | Authoritative machine schemas remain under [`docs/contracts/schemas/evaluation-kit/`](schemas/evaluation-kit/). Do not duplicate them into classpath copies unless a future packaging change introduces a single build-owned source. |

**Rationale (durable):** generated-corpus evaluation reuses the same offline replay and detection-evaluation pipeline that already lives in core. Splitting a module now would enlarge the reactor without changing evaluator semantics or removing the repository-based reference path. A local container packages that same core evaluator for language-neutral runtime use; it is not a second evaluator and does not introduce a Kit Maven coordinate.

Dependency direction for Evaluation Kit tooling remains:

```text
Evaluation Kit packages (in ai-sentinel-core)
  → core replay / scoring / decision primitives
  ↛ Spring Boot starter
  ↛ ai-sentinel-benchmark
  ↛ ai-sentinel-trainer
  ↛ ai-sentinel-demo
```

Framework independence of `ai-sentinel-core` (no Spring / Servlet / Reactor) continues to cover Evaluation Kit packages.

---

## 3. Versioning model (independent axes)

None of these axes are required to equal the Maven release version.

| Axis | Meaning |
|------|---------|
| `scenarioSchemaVersion` | Scenario/Test Plan document shape. |
| `scenarioVersion` | Specific scenario experiment revision. |
| `generatorContractVersion` | Generator **I/O contract** shape/semantics. |
| `generatorBuildId` | Generator **implementation/build** identity used for determinism. |
| `corpusId` | Identity of a concrete generated corpus instance. |
| `corpusSchemaVersion` | Generated Corpus Manifest document shape. |
| `annotationSchemaVersion` | Ground-truth / annotation sidecar document shape. |
| `resultId` | Input-bound **result-family** identity (`result.` + corpusId or datasetId). Distinct configured evaluations of the same input share one `resultId`. |
| `evaluationRunId` | Deterministic **concrete evaluation-run** identity for one configured Kit evaluation (threshold + replay/reference configuration material). Additive; optional on legacy evidence. |
| `resultSchemaVersion` | Generic Kit Evaluation Result document shape. |
| `reproducibilitySchemaVersion` | Kit Reproducibility Manifest document shape. |
| `artifactSchemaVersion` | Evidence-artifact reference document shape (archive identity/location). |
| `featureSchemaVersion` | Existing FeatureSchema (currently `"1"`). |
| `evaluationEventSchemaVersion` | Existing EvaluationEvent (currently `"1"`). |
| `aiSentinelVersion` | Reference evaluation / methodology configuration version used by Kit replay defaults (historical reference packaging; not Maven project version). |
| `softwareVersion` | Packaging software version that produced Kit evidence (for example Maven `0.4.0`). Additive; omit when unknown. |
| `aiSentinelBuildId` | Exact build/commit/package-build identity when truthfully available. Omit rather than fabricate or reuse software version. |

Software SemVer, corpus/dataset/result identities, evidence checkpoint tags, and
`artifactSchemaVersion` remain independent axes. Content identity for archived
bytes is lowercase hex SHA-256 (`sha256`) plus `sizeBytes` — not the storage URL.

### Generator determinism rule

```text
same Scenario definition (scenarioId + scenarioVersion + bytes/checksum)
+ same seed
+ same generatorContractVersion
+ same generatorBuildId
⇒ same ordered corpus artifacts + same integrity metadata
```

Do **not** collapse `generatorContractVersion` and `generatorBuildId` into one ambiguous `generatorVersion`.

---

## 4. Scenario / Test Plan contract

**Machine schema:** [`schemas/evaluation-kit/scenario.schema.json`](schemas/evaluation-kit/scenario.schema.json)

### Required foundation

- `scenarioSchemaVersion`, `scenarioId`, `scenarioVersion`
- Declared `featureSchemaVersion` compatibility
- Population / identities summary
- Normal behavior summary
- Timing (warmup vs evaluation periods)
- Transitions (legitimate and/or anomalous intent descriptions)
- Optional evaluation expectations / assertions (evaluation-side only)
- Metadata sufficient for contribution and reproducibility binding

### Assertion isolation (mandatory)

Scenario evaluation expectations and assertions are **evaluation-side information**.

They **MUST NOT** be compiled into or used to configure the **same run’s**:

- scorer
- anomaly threshold
- policy threshold
- policy configuration
- enforcement behavior

unless that configuration is **explicitly declared** as part of the independent evaluation subject/configuration under test — **not** derived from the expected outcome.

**Expected outcomes must not tune the system being evaluated.**

This rule is **additional to** ground-truth ≠ detector input.

### Forbidden in Scenario as detector/scorer configuration

- Using desired detector score / desired enforcement action to set thresholds for the subject under test
- Embedding labels into detector-facing event generation inputs

---

## 5. Corpus Generator contract

**Normative I/O** (MVP implementation: `dev.aisentinel.core.dataset.corpus.CorpusGenerator`):

**Inputs**

- Scenario / Test Plan (identity + content checksum)
- Deterministic `seed` (string or integer encoded as string)
- `generatorContractVersion`
- `generatorBuildId`

**Outputs**

- Generated Corpus artifacts (detector-facing events and related files)
- Generated Corpus Manifest
- Optional Ground-Truth / Annotation sidecar (when labels exist)

**Rules**

- Determinism as in §3
- Declare representation mode: `request-like` | `feature-level` | `both`
- Never write ground truth into detector-consumable events
- Never use scenario assertions to configure scorer/policy for the subject under test

Machine recording of generator identity appears on the **Corpus Manifest** and **Reproducibility Manifest**.

---

## 5b. Versioned reference corpus inventory

Repository path: [`evaluation/kit-reference/`](../../evaluation/kit-reference/)

| Artifact | Role |
|----------|------|
| `generation-spec.json` | Declared generation inputs (scenario paths, seeds, generator build identity, inventory version). |
| `scenarios/*.json` | Version-controlled Scenario / Test Plan definitions. |
| `corpora/*/` | Generated events, Kit corpus-manifest, annotations sidecar, replay-compatible manifest. |
| `inventory.json` | Derived inventory of **actual** generated corpora (checksums, counts, identities). |

`inventoryVersion` versions the **membership and structure of this inventory** — which scenario
families/corpus entries it contains and the shape of an inventory entry — independent of
`generatorContractVersion` (I/O contract shape a single corpus generation honors) and
`generatorBuildId` (identity of the concrete generator implementation that produced the corpora).
Bump `inventoryVersion` when entries are added, removed, or renamed, or when an entry's recorded
fields change shape; regenerating existing corpora with the same scenarios/seeds/generator
identity does not require a bump.

**Machine schema (inventory):** [`schemas/evaluation-kit/corpus-inventory.schema.json`](schemas/evaluation-kit/corpus-inventory.schema.json)

Reproduce/verify:

```bash
./scripts/verify-kit-reference-corpus.sh
```

This inventory is synthetic evaluation evidence only. It does not prove production efficacy.

### Evaluating generated corpora

Generated corpora under `evaluation/kit-reference/corpora/*/` are first-class inputs to offline
detection evaluation via `GeneratedCorpusDetectionEvaluator`:

1. Validate Kit `corpus-manifest.json` provenance and artifact checksums.
2. Load detector-facing events through the existing replay-compatible `manifest.json`.
3. Join event-level `annotations.json` ground truth by `eventId` (sidecar only).
4. Exclude `category=warmup` and `expectedClass=unknown|unlabeled` from binary detection metrics.
5. Replay and compare observed scores/statuses/actions against authored truth.

Dual manifests remain intentional: Kit corpus-manifest carries generation provenance; replay
`manifest.json` remains the typed entry for `ReplayDatasetLoader`. Historical `evaluation/reference/`
is unchanged and continues to use its own annotation schema.

Runtime `EvaluationStatus` values (including invalid-score outcomes) are produced only by actual
replay/scoring. Generated corpora must not pre-assert them.

### One-command evaluation (CLI)

Evaluate one Evaluation Kit input directory without writing Java or assembling Maven modules by hand
(JDK 21 required; the script builds/runs the module itself). It can be invoked from the repository
root or any other directory. Exactly one of `--corpus` or `--dataset` is required.

Generated corpus:

```bash
./scripts/evaluate-generated-corpus.sh \
  --corpus evaluation/kit-reference/corpora/kit.abrupt-burst
```

Evaluator-provided (BYO) dataset:

```bash
./scripts/evaluate-generated-corpus.sh \
  --dataset path/to/my-byo-dataset
```

Options:

| Option | Required | Meaning |
|--------|----------|---------|
| `--corpus <directory>` | Exactly one of `--corpus` / `--dataset` | Generated corpus directory containing Kit + replay artifacts. Relative paths resolve against your current directory, not the repository root. |
| `--dataset <directory>` | Exactly one of `--corpus` / `--dataset` | Evaluator-provided dataset directory (`dataset-manifest.json` + `events.jsonl` + optional `annotations.json`). No replay `manifest.json` or generator provenance required. |
| `--output <directory>` | No | Evidence + report output directory (temporary if omitted; must not already exist). Writes Kit result JSON, event inspection, HTML report, and specialized detection evidence. |
| `--threshold <0..1>` | No | Anomaly classification threshold (default `0.5`) |
| `-h` / `--help` | No | Usage text |

Exit codes: `0` success; `1` load / integrity / ground-truth / evaluation failure; `2` invalid usage.

The CLI is a thin adapter over `GeneratedCorpusDetectionEvaluator` (`--corpus`) or
`EvaluatorProvidedDatasetEvaluator` (`--dataset`). It prints a terminal summary of provenance,
this run's evaluation configuration, phase counts, binary detection metrics (with undefined ratios
shown as `unavailable`), limitations, and (when `--output` is supplied) paths to durable report
artifacts. Comparison of two existing runs uses a separate command (`scripts/compare-evaluations.sh`; see §14).

### Durable evaluation reports (with `--output`)

When an evidence directory is requested, evaluation writes these sibling artifacts:

| Artifact | Role |
|----------|------|
| `kit-evaluation-result.json` | Evaluation Kit result (schema: evaluation-result). Provenance uses optional `datasetSource`: absent or `generated-corpus` retains generator/corpus fields; `evaluator-provided` uses dataset identity and forbids `seed` / `generatorBuildId` / `generatorContractVersion` / `corpusId`. |
| `event-inspection.json` | Event-level join of authored ground truth and runtime replay outcomes |
| `evaluation-report.html` | Self-contained human-readable report (provenance, metrics, timeline, event table) |
| `evaluation.json` / `evaluation.md` | Specialized `DetectionEvaluationEvidence` (unchanged specialized format) |

Report projection explains what happened. It does not change how detection evaluation runs.
Event inspection keeps ground-truth labels separate from detector-facing inputs.
Undefined metric ratios remain unavailable (not fabricated zeros).

### Evaluator-provided (BYO) datasets

An evaluator may supply a local dataset directory that is **not** a generated corpus:

```text
dataset-manifest.json   # evaluator-dataset-manifest schema
events.jsonl            # EvaluationEvent JSONL (detector-facing, no labels)
annotations.json        # OPTIONAL Kit-style ground truth; when present uses datasetId (not corpusId)
```

Rules:

- No `corpus-manifest.json`, no generator seed/build identity, and no fabricated generator metadata.
- No separate replay `manifest.json` is required; the evaluator builds a `ReplayDataset` in memory.
- Ground-truth sidecar (when present) uses `datasetId` instead of `corpusId`.
- BYO evaluation is controlled evidence only. It is **not** production validation and is **not**
  independent third-party validation of production efficacy.
- Comparison against other datasets or product ranking UX is out of scope for this path.

**Authoring guide:** [`EVALUATOR_PROVIDED_DATASET.md`](EVALUATOR_PROVIDED_DATASET.md)

**Machine schema:** [`schemas/evaluation-kit/evaluator-dataset-manifest.schema.json`](schemas/evaluation-kit/evaluator-dataset-manifest.schema.json)

---

## 6. Generated Corpus Manifest contract

**Machine schema:** [`schemas/evaluation-kit/corpus-manifest.schema.json`](schemas/evaluation-kit/corpus-manifest.schema.json)

### Required foundation

- `corpusSchemaVersion`, `corpusId`
- Scenario binding (`scenarioId`, `scenarioVersion`, optional scenario checksum)
- `seed`, `generatorContractVersion`, `generatorBuildId`
- `featureSchemaVersion`, `evaluationEventSchemaVersion`
- Artifact roles + checksums (SHA-256 lowercase hex)
- Representation mode
- Warmup / evaluation period metadata pointers
- Optional pointer to ground-truth sidecar (`annotationsArtifact` + checksum)

### Artifact roles (names)

| Role | Detector-facing? | Notes |
|------|------------------|-------|
| `events` | Yes | EvaluationEvent-compatible stream (or equivalent). **No labels.** |
| `annotations` / ground truth | No | Sidecar only; join at evaluation time. |
| `checksums` / manifest digests | N/A | Integrity metadata. |

The generic `artifacts` array accepts detector-facing event artifacts only. Ground truth uses `annotationsArtifact`, never a generic artifact role.

---

## 7. Ground-truth / annotation contract

**Machine schema:** [`schemas/evaluation-kit/ground-truth.schema.json`](schemas/evaluation-kit/ground-truth.schema.json)

- Sidecar / evaluation-only
- Join key typically `eventId` (and optional `scenarioId`)
- **MUST NOT** appear inside detector-facing `EvaluationEvent` representation
- May be absent entirely for unlabeled / BYO evaluation
- Identity: generated corpora use `corpusId`; evaluator-provided datasets use `datasetId` (mutually exclusive)

Isolation invariant:

```text
Corpus events (detector path)  →  NO expectedClass / anomaly labels / assertion payloads
Ground-truth sidecar           →  join by eventId at evaluation time only
```

---

## 8. Generic Evaluation Kit Result contract

**Machine schema:** [`schemas/evaluation-kit/evaluation-result.schema.json`](schemas/evaluation-kit/evaluation-result.schema.json)

### Required foundation

- `resultId` (input-bound result-family identity), optional additive `evaluationRunId` (concrete configured run), `resultSchemaVersion` (`"1"`; compatible evolution via `datasetSource` discriminator)
- Provenance bindings:
  - **Generated corpus** (absent `datasetSource` or `datasetSource: "generated-corpus"`): scenario / corpus / generator / seed / schemas / engine identity
  - **Evaluator-provided** (`datasetSource: "evaluator-provided"`): `datasetId`, `datasetSchemaVersion`, `eventsSha256`, schema versions, `representationMode`, engine identity — and must not include `seed`, `generatorBuildId`, `generatorContractVersion`, or `corpusId`
- Optional `provenance.softwareVersion` for packaging software version; optional `provenance.aiSentinelBuildId` only when an exact build/commit identity is known
- Artifact bindings and checksums where applicable
- `status` and `limitations` where appropriate

### Result family vs concrete evaluation run

```text
resultId          = result.<corpusId|datasetId>     # durable family for that input
evaluationRunId   = evalrun.<12-byte SHA-256 hex>    # one configured evaluation
```

Canonical UTF-8 material for `evaluationRunId` (newline-joined, ordered):

```text
datasetSource=<generated-corpus|evaluator-provided>
inputId=<corpusId|datasetId>
eventsSha256=<corpusEventsSha256|eventsSha256>
annotationsSha256=<sha or empty if unlabeled>
featureSchemaVersion=<…>
evaluationEventSchemaVersion=<…>
replayConfigurationFingerprint=<…>
scorerId=<…>
scorerVersion=<…>
policyId=<…>
policyVersion=<…>
anomalyThreshold=<Double.toString>
resultSchemaVersion=1
```

Excluded from the material: output directory, absolute paths, timestamps, UUIDs, hostnames.

### Specialized evidence `datasetId` naming

Specialized Detection Evaluation Evidence (`evaluation.json` / `evaluation.md`) uses generic
`reference.datasetId` / `replay.datasetId` fields. For generated Kit corpora those fields carry
the **corpus identity string**. They are not a second, independent evaluator-provided dataset id.
Kit result provenance continues to use `corpusId` for generated runs and `datasetId` for BYO runs.

### Metric families

Metric families **MAY be independently absent or marked unavailable** when the evaluation does not support them.

When `availability` is `"available"`, `values` is required.
When `availability` is `"unavailable"` or `"not_applicable"`, `values` must not be present.

**Do not fabricate metrics.**

Unlabeled evaluation **MUST** be representable without inventing labeled detection metrics.

### Relationship to `DetectionEvaluationEvidence`

`DetectionEvaluationEvidence` remains a **specialized** labeled detection-evaluation artifact.

A generic Kit Result **MAY** optionally reference or embed specialized detection evidence when a labeled detection methodology applies (`detectionEvidenceRef` / optional embedded object).

A generic Kit Result **MAY** also optionally reference:

- `eventInspectionRef` — event-level inspection joining authored ground truth with runtime replay outcomes
- `htmlReportRef` — self-contained human-readable HTML evaluation report

`DetectionEvaluationEvidence` is **not** the universal Kit Result type and is **not** redesigned here.

---

## 9. Reproducibility / Provenance contract

**Machine schema:** [`schemas/evaluation-kit/reproducibility-manifest.schema.json`](schemas/evaluation-kit/reproducibility-manifest.schema.json)

Compose existing artifacts; do **not** flatten into one replacement type.

May bind / reference:

- Scenario identity (+ checksum)
- `generatorContractVersion`, `generatorBuildId`, `seed`
- Corpus identity / schema / checksums
- Annotation identity / schema / checksum (optional)
- `featureSchemaVersion`, `evaluationEventSchemaVersion`
- Replay identity / provenance (**reuse** `ReplayRunManifest` shape by reference)
- Scorer / model identity
- Policy / config identity
- Optional `CandidateEvaluationProvenance` (candidate path only)
- `aiSentinelVersion` / build / commit
- Tooling / runtime identity relevant to the run

`ReplayRunManifest` remains a distinct replay artifact.
`CandidateEvaluationProvenance` remains optional and candidate-specific.

An Evaluation Result and the Reproducibility Manifest it binds to must agree on shared identity fields (`resultId`, scenario, corpus, seed, generator contract/build, engine version). Schema validation alone is per-document; cross-document reconciliation is part of contract validation.

---

## 10. EvaluationEvent convergence

`EvaluationEvent` remains the detector-facing evidence atom. Convergence wiring in the Java core:

1. Map corpus / reference events → `EvaluationEvent` **without labels** (canonical JSON via `EvaluationEventJson`).
2. Keep ground-truth join **outside** the event schema (sidecar).
3. Kit run metadata (`scenarioId`, `corpusId`, `resultId`, generator/seed bindings) attaches via **sidecar / run-manifest**, not detector-facing events.
4. Dataset export and replay load share the same event encode/decode path; replay projections derive through `EvaluationEventReplayBridge`.
5. Preserve privacy allow-list of `EvaluationEvent`.
6. Do not require Spring or remote `EvaluationExecutor` for the basic offline Kit path.
7. Historical reference baselines remain unmodified; compatibility is at the code/contract boundary.

`DetectionEvaluationEvidence` and the generic Evaluation Kit Result remain separate abstractions.

---

## 11. Deferred architecture questions

| Topic | Status |
|-------|--------|
| Module / distribution boundary vs `ai-sentinel-benchmark` | **Decided (current stage):** Kit remains in `ai-sentinel-core`; benchmark stays separate JMH tooling; no Kit-specific published artifact; distribution = repository script (see §2) |
| Large artifact storage | **Decided (strategy):** Git retains definitions/manifests/checksums/compact fixtures; public large evidence uses GitHub Release assets as locators with Git-tracked `sha256`+`sizeBytes` identity; private/licensed artifacts may omit public locators (`external-reference-only`). Compact kit-reference and historical baselines stay in Git. No live remote archive deployment required while artifacts remain compact. See [`evaluation/EVIDENCE_ARTIFACT_STORAGE.md`](../../evaluation/EVIDENCE_ARTIFACT_STORAGE.md). |
| Deterministic corpus generator implementation | Implemented (`feature-level`, multi-family) |
| Versioned reference corpus inventory | Checked in under `evaluation/kit-reference/` |
| One-command generated-corpus evaluation CLI | Available via `scripts/evaluate-generated-corpus.sh` (`--corpus`) |
| One-command evaluator-provided (BYO) dataset CLI | Available via the same script (`--dataset`) |
| JSON + HTML evaluation reports / event inspection | Written under `--output` (`kit-evaluation-result.json`, `event-inspection.json`, `evaluation-report.html`) |
| Container packaging | Local image build via `Dockerfile.evaluation-kit` (not published to a registry) |
| Before/after evaluation comparison | Available via `scripts/compare-evaluations.sh` (existing-run evidence only; factual deltas) |
| Separate Evaluation Kit Maven module / Central artifact | Not created at this stage |

---

## 12. Machine schemas and fixtures

| Artifact | Path |
|----------|------|
| Shared definitions | [`schemas/evaluation-kit/common.schema.json`](schemas/evaluation-kit/common.schema.json) |
| Scenario | [`schemas/evaluation-kit/scenario.schema.json`](schemas/evaluation-kit/scenario.schema.json) |
| Corpus manifest | [`schemas/evaluation-kit/corpus-manifest.schema.json`](schemas/evaluation-kit/corpus-manifest.schema.json) |
| Corpus inventory | [`schemas/evaluation-kit/corpus-inventory.schema.json`](schemas/evaluation-kit/corpus-inventory.schema.json) |
| Evaluator-provided dataset manifest | [`schemas/evaluation-kit/evaluator-dataset-manifest.schema.json`](schemas/evaluation-kit/evaluator-dataset-manifest.schema.json) |
| Ground truth | [`schemas/evaluation-kit/ground-truth.schema.json`](schemas/evaluation-kit/ground-truth.schema.json) |
| Evaluation result | [`schemas/evaluation-kit/evaluation-result.schema.json`](schemas/evaluation-kit/evaluation-result.schema.json) |
| Event inspection | [`schemas/evaluation-kit/event-inspection.schema.json`](schemas/evaluation-kit/event-inspection.schema.json) |
| Evaluation comparison | [`schemas/evaluation-kit/comparison-result.schema.json`](schemas/evaluation-kit/comparison-result.schema.json) |
| Reproducibility | [`schemas/evaluation-kit/reproducibility-manifest.schema.json`](schemas/evaluation-kit/reproducibility-manifest.schema.json) |
| Evidence artifact reference | [`schemas/evaluation-kit/evidence-artifact.schema.json`](schemas/evaluation-kit/evidence-artifact.schema.json) |
| Valid fixtures | [`fixtures/evaluation-kit/valid/`](fixtures/evaluation-kit/valid/) |
| Invalid fixtures | [`fixtures/evaluation-kit/invalid/`](fixtures/evaluation-kit/invalid/) |

Validate with:

```bash
scripts/validate-evaluation-kit-contracts.sh
```

Local archive verification (no remote fetch/upload):

```bash
scripts/verify-evidence-artifact.sh \
  --manifest docs/contracts/fixtures/evaluation-kit/valid/evidence-artifact.git-path.example.json \
  --artifact docs/contracts/fixtures/evaluation-kit/artifacts/sample-evidence.txt
```

---

## 13. Containerized evaluator (local packaging)

The container is a packaging/distribution surface around the existing Evaluation Kit evaluator
(`GeneratedCorpusEvaluationMain`) and existing-run comparator (`EvaluationComparisonMain`). It
does **not** redefine evaluation, report schemas, or detection semantics. The entrypoint dispatches
to comparison when `--baseline` is present; otherwise it accepts evaluation `--corpus` or `--dataset`.

### Build (local only)

From the repository root (Docker required):

```bash
docker build -f Dockerfile.evaluation-kit -t ai-sentinel-evaluation-kit:local .
```

The resulting tag is a **local** image name. This repository does not publish the Evaluation Kit
image to Docker Hub, GHCR, or another registry as part of this packaging path.

### Run

Mount a corpus or BYO dataset directory read-only. Bind-mount a writable host directory for
persistent evidence, and pass an `--output` path that does **not** already exist inside the
container (the CLI refuses pre-existing output directories). A practical pattern is to mount a
host parent at `/output` and write to a child such as `/output/run`.

Generated corpus:

```bash
mkdir -p "$PWD/out"
docker run --rm \
  --network=none \
  --read-only \
  --tmpfs /tmp \
  -v "$PWD/evaluation/kit-reference/corpora/kit.abrupt-burst:/input:ro" \
  -v "$PWD/out:/output" \
  ai-sentinel-evaluation-kit:local \
  --corpus /input \
  --output /output/run
```

Evaluator-provided dataset:

```bash
mkdir -p "$PWD/out"
docker run --rm \
  --network=none \
  --read-only \
  --tmpfs /tmp \
  -v "$PWD/path/to/my-byo-dataset:/input:ro" \
  -v "$PWD/out:/output" \
  ai-sentinel-evaluation-kit:local \
  --dataset /input \
  --output /output/run
```

Path semantics inside the container are ordinary absolute paths. `/input` and `/output` are
recommended mount points, not hard-coded evaluator requirements.

Expected persistent artifacts (unchanged from the host CLI):

1. `evaluation.json`
2. `evaluation.md`
3. `kit-evaluation-result.json`
4. `event-inspection.json`
5. `evaluation-report.html`

### Behavior notes

| Topic | Behavior |
|-------|----------|
| Entrypoint | `GeneratedCorpusEvaluationMain` (same CLI/exit codes as the host script; supports `--corpus` and `--dataset`) |
| No `--output` | Terminal summary only; temporary evidence is created under the container `/tmp` and deleted before exit |
| No args | Exit `2` with usage on stderr |
| `--help` | Exit `0` |
| Input mount | Prefer `:ro`; the evaluator does not mutate the input tree |
| Runtime network | Not required after the image is built (verify with `--network=none`) |
| Read-only root FS | Supported when `/tmp` is writable (for example `--read-only --tmpfs /tmp`) and output is a writable mount |
| Image user | Non-root UID `10001` |
| Output file ownership | On Docker Desktop, bind-mounted output is typically surfaced as the host user. On native Linux, files created under a bind-mounted `--output` directory are owned by the container UID (`10001`) unless the container is run with an explicit host UID/GID mapping. |
| Reference corpora | Not bundled in the image; mount `evaluation/kit-reference/corpora/...` (or another corpus directory) |
| Schemas | Authoritative schemas remain under `docs/contracts/schemas/evaluation-kit/` on the host; the image does not introduce a second schema source |

Containerized evaluator ≠ production deployment image. Synthetic evaluation ≠ production validation.

---

## 14. Before/after evaluation comparison

Comparison consumes two already-written evaluation run directories and does not rerun detectors.
Each directory must contain `kit-evaluation-result.json` and `event-inspection.json`; HTML and
Markdown reports are never treated as source evidence. Artifact references in result evidence must
remain confined to the run directory (no absolute paths, URI references, or `..` traversal).

Runs are comparable only when their dataset source, result/feature/event schema versions,
representation mode (when present), dataset identity and event ID set agree. Generated runs bind
the same corpus and event/annotation hashes. Evaluator-provided runs bind the same dataset and
events hash, and annotation hashes must either both be absent or equal. Labeled and unlabeled runs
cannot be mixed, and per-event expected classes must agree. Anomaly thresholds may differ; this is
reported by `thresholdEqual` rather than treated as incompatibility.

Host CLI:

```bash
scripts/compare-evaluations.sh \
  --baseline path/to/baseline-run \
  --candidate path/to/candidate-run \
  --output path/to/new-comparison-directory
```

The output directory must not already exist. The command writes deterministic `comparison.json`
and self-contained `comparison.html`. Omit `--output` for a terminal summary backed by temporary
artifacts that are removed before exit. Exit codes are `0` for success/help, `2` for invalid usage,
and `1` for incompatibility, integrity, I/O, or comparison failure.

For the container, mount both run directories read-only and a writable output parent, then pass
`--baseline`, `--candidate`, and `--output`; the entrypoint selects the comparison CLI.

The report preserves unavailable metrics as unavailable rather than zero, and records factual
metric, count, event, and binary-correctness transitions. It does not identify a preferred run,
recommend promotion, gate CI, or make a deployment decision. A comparison is offline engineering
evidence, not production validation.

### Comparison identity

`comparisonId` is derived from the concrete runs being compared:

- Prefer each side's `evaluationRunId` when present.
- For legacy evidence lacking `evaluationRunId`, use a deterministic **legacy compatibility
  surrogate** from persisted family identity plus available result-affecting fields
  (`resultId`, event digests, annotations digest when present, anomaly threshold, schema
  versions). That surrogate is **not** equivalent to a full concrete run identity.

Direction matters: baseline vs candidate is not the same as the swapped pair.

---

## 15. Non-goals of this contract package

- No new Maven module (Evaluation Kit remains in `ai-sentinel-core` at this stage)
- No Evaluation Kit–specific Maven Central publication
- No published Evaluation Kit container registry artifact in this packaging path
- No production runtime decision-behavior change
- No modification of historical evidence or the `v0.4.0` release tag
- No production-efficacy claims
