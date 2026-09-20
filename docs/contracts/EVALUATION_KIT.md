# Evaluation Kit Contracts

Normative contract foundations for the AI-Sentinel Evaluation Kit.

JSON is the **normative machine representation** for schemas and fixtures in this package.
JSON does **not** permanently constrain human authoring UX: later CLI/tooling may accept YAML or other conveniences that normalize to the same logical contract.

This document is **contract-only**. It does not claim production efficacy, does not change production runtime behavior, and does not implement a generator, CLI, container, or new Java public API.

Current schema versions for Kit machine contracts in this package: `"1"`.

---

## 1. Three concepts (must remain distinct)

| Concept | Role |
|---------|------|
| **Scenario / Test Plan** | Authoring definition of an experiment (population, behavior, timing, transitions, evaluation expectations). |
| **Corpus Generator** | Deterministic compile function: Scenario + seed + generator contract/build identity → Generated Corpus. |
| **Generated Corpus** | Concrete artifact set (events + manifest + optional ground-truth sidecar). |

**Scenario ≠ Generator ≠ Corpus.**

Existing `evaluation/reference/` is a **historical seed instance**, not the Kit generative architecture. Deterministic corpus generation is a later implementation concern; this package defines the contracts only.

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

`ai-sentinel-benchmark` remains JMH performance tooling. It does **not** become the Evaluation Kit. Module and distribution boundaries remain a later packaging decision.

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
| `resultId` | Identity of a concrete evaluation run/result. |
| `resultSchemaVersion` | Generic Kit Evaluation Result document shape. |
| `reproducibilitySchemaVersion` | Kit Reproducibility Manifest document shape. |
| `featureSchemaVersion` | Existing FeatureSchema (currently `"1"`). |
| `evaluationEventSchemaVersion` | Existing EvaluationEvent (currently `"1"`). |
| `aiSentinelVersion` / `aiSentinelBuildId` | Engine under evaluation (version and optional build/commit). |

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

**Normative I/O** (implementation is deferred; contracts only here):

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

Isolation invariant:

```text
Corpus events (detector path)  →  NO expectedClass / anomaly labels / assertion payloads
Ground-truth sidecar           →  join by eventId at evaluation time only
```

---

## 8. Generic Evaluation Kit Result contract

**Machine schema:** [`schemas/evaluation-kit/evaluation-result.schema.json`](schemas/evaluation-kit/evaluation-result.schema.json)

### Required foundation

- `resultId`, `resultSchemaVersion`
- Provenance bindings (scenario / corpus / generator / seed / schemas / engine identity)
- Artifact bindings and checksums where applicable
- `status` and `limitations` where appropriate

### Metric families

Metric families **MAY be independently absent or marked unavailable** when the evaluation does not support them.

When `availability` is `"available"`, `values` is required.
When `availability` is `"unavailable"` or `"not_applicable"`, `values` must not be present.

**Do not fabricate metrics.**

Unlabeled evaluation **MUST** be representable without inventing labeled detection metrics.

### Relationship to `DetectionEvaluationEvidence`

`DetectionEvaluationEvidence` remains a **specialized** labeled detection-evaluation artifact.

A generic Kit Result **MAY** optionally reference or embed specialized detection evidence when a labeled detection methodology applies (`detectionEvidenceRef` / optional embedded object).

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

## 10. EvaluationEvent convergence requirements (document only)

This contract package does **not** wire EvaluationEvent and does **not** add ground truth or scenario expected outcomes to the event schema.

Preferred initial direction: **sidecar / run-manifest metadata** for Kit join fields. Final wiring belongs to a later evaluation-event convergence task.

### Convergence requirements

1. Map generated corpus events → `EvaluationEvent` (or equivalent) **without labels**.
2. Keep ground-truth join **outside** the event schema (sidecar).
3. Decide how Kit run metadata (`scenarioId`, `corpusId`, `resultId`, generator/seed bindings) attaches — prefer sidecar/run-manifest over expanding detector-facing events unless a strong reason exists.
4. Ensure generation → replay → evaluation → reporting → BYO → comparison share versioned evidence semantics.
5. Preserve privacy allow-list of `EvaluationEvent`.
6. Do not require Spring or remote `EvaluationExecutor` for the basic offline Kit path.
7. Close remaining EvaluationEvent wiring gaps for Kit paths without rewriting historical reference baselines.

---

## 11. Deferred architecture questions

| Topic | Status |
|-------|--------|
| Module / distribution boundary vs `ai-sentinel-benchmark` | Deferred packaging decision |
| Large artifact storage | Deferred |
| Deterministic corpus generator implementation | Deferred |
| Multi-family corpus inventory | Deferred |
| CLI / HTML report UX / container | Deferred |
| BYO datasets / comparison UX | Deferred |

---

## 12. Machine schemas and fixtures

| Artifact | Path |
|----------|------|
| Shared definitions | [`schemas/evaluation-kit/common.schema.json`](schemas/evaluation-kit/common.schema.json) |
| Scenario | [`schemas/evaluation-kit/scenario.schema.json`](schemas/evaluation-kit/scenario.schema.json) |
| Corpus manifest | [`schemas/evaluation-kit/corpus-manifest.schema.json`](schemas/evaluation-kit/corpus-manifest.schema.json) |
| Ground truth | [`schemas/evaluation-kit/ground-truth.schema.json`](schemas/evaluation-kit/ground-truth.schema.json) |
| Evaluation result | [`schemas/evaluation-kit/evaluation-result.schema.json`](schemas/evaluation-kit/evaluation-result.schema.json) |
| Reproducibility | [`schemas/evaluation-kit/reproducibility-manifest.schema.json`](schemas/evaluation-kit/reproducibility-manifest.schema.json) |
| Valid fixtures | [`fixtures/evaluation-kit/valid/`](fixtures/evaluation-kit/valid/) |
| Invalid fixtures | [`fixtures/evaluation-kit/invalid/`](fixtures/evaluation-kit/invalid/) |

Validate with:

```bash
scripts/validate-evaluation-kit-contracts.sh
```

---

## 13. Non-goals of this contract package

- No new Maven module
- No new public Java Evaluation Kit API
- No production runtime behavior change
- No generator / CLI / container / HTML UX implementation
- No modification of historical evidence or the `v0.4.0` release tag
- No production-efficacy claims
