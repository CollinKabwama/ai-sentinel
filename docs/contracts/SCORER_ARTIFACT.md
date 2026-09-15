# Scorer / Model Artifact Contract

This contract defines durable engineering metadata for **candidate scorer/model
artifacts** accepted by AI-Sentinel for later loading, replay/evaluation,
shadow scoring, and model lifecycle work.

It is implemented in `dev.aisentinel.core.scoring.artifact`.

## Purpose

Answer, from declared metadata alone:

- What artifact is this?
- Which scorer/model identity and version does it represent?
- Which feature schema and ordered inputs does it require?
- What output range does it claim?
- Which capabilities does it declare?
- How is artifact integrity represented?

This is **not** runtime loading, health monitoring, shadow execution, promotion,
or detection-quality acceptance.

## Terminology

`BASELINE CANDIDATE != MODEL / SCORER CANDIDATE`

Official Detection Reference Baseline candidates are a separate governance
surface. This contract is only for scorer/model artifacts.

## Descriptor fields

`ScorerArtifactDescriptor` (schema version `1`) includes:

| Field | Role |
|---|---|
| `scorerId` / `scorerVersion` | Explicit scorer/model identity |
| `artifactId` / `artifactFormat` | Explicit artifact identity/format |
| `scorerType` | Validated lowercase identifier (e.g. `statistical`, `isolation_forest_v1`, `composite`, `external`) |
| `artifactDigest` | Explicit algorithm + digest (currently `SHA-256` canonical lowercase hex length 64; leading/trailing whitespace is invalid) |
| `featureSchemaVersion` | Must match a supported `FeatureSchema` version |
| `requiredProjection` | Optional bind to an ordered `FeatureSchema` projection |
| `requiredFeatureNames` | Deterministic ordered required inputs |
| `declaredFeatureDimension` | Must equal required feature count |
| `outputRange` | Declared numeric bounds |
| `capabilities` | Declared explainability / per-feature attribution / deterministic-execution claims |

Collections are defensively copied and immutable after construction.

## Integrity boundary

This contract validates **digest metadata format** (algorithm + hex shape).

Matching digest bytes to actual artifact content is **not** performed here and
belongs to later loading infrastructure.

`DESCRIPTOR VALID != BYTE-VERIFIED ARTIFACT`

## Feature schema binding

Candidates must declare a supported feature schema version. Required feature
names must be canonical names for that schema.

When `requiredProjection` is set, required feature names must **exactly** match
the ordered projection list from `FeatureSchema`.

Known current scorer types also bind to their current online projections:
`isolation_forest_v1` must use the ordered `ISOLATION_FOREST` features, and
`statistical` must use the ordered `STATISTICAL` features. External artifact
types may declare an explicit ordered canonical subset without a named
projection.

`same dimension != same feature semantics`

Feature order is part of the model input contract and must not depend on map
iteration, reflection order, or incidental JSON property order.

## Output contract

Declared output bounds must be finite, `min <= max`, and lie within the
`AnomalyScorer` contract `[0.0, 1.0]`.

Invalid runtime scores (`NaN`, `±Infinity`, invalid negatives) remain
`INVALID_SCORE` and must never become maximum risk.

`INVALID SCORE != MAXIMUM RISK`

## Capability claims

Declared capabilities are metadata only:

- `DECLARED CAPABILITY != VERIFIED QUALITY`
- `DECLARED EXPLAINABILITY != CORRECT EXPLANATION`
- `VALID ARTIFACT != GOOD MODEL`
- `COMPATIBLE MODEL != APPROVED MODEL`
- `MODEL METADATA != GROUND TRUTH`

Isolation Forest (`isolation_forest_v1`) must **not** claim per-feature
attribution. Absence of explainability does not invalidate an artifact.

## Provenance fingerprint

`configurationFingerprintSha256Hex()` hashes a deterministic UTF-8 canonical
length-prefixed payload of declared contract fields only. It excludes
timestamps, paths, hostnames, process IDs, and random values.

## Validation

`ScorerArtifactValidator` returns a structured
`ScorerArtifactValidationResult`.

Rejection is an engineering/configuration condition:

- `INFRASTRUCTURE FAILURE != ATTACK`
- validation never maps to `BLOCK` / `QUARANTINE` / attack classification

Acceptance means declared metadata is compatible with the current schema and
scorer output contract. It does **not** mean:

- runtime available
- healthy
- high detection quality
- shadow-eligible
- promotion-eligible
- production authority

`DESCRIPTOR VALID != RUNTIME AVAILABLE`

`SCORER HEALTH != DETECTION QUALITY`

## Boundaries with later work

| Later work | Relationship |
|---|---|
| Loading / health / isolation | Uses this contract; may verify artifact bytes against digest |
| Replay / evaluation acceptance | Should carry descriptor provenance and digest into evidence |
| Shadow scoring | Observational only; `SHADOW RESULT != PRODUCTION DECISION` |
| Champion / challenger | Separate model lifecycle governance |

## Related docs

- [`FEATURE_SCHEMA.md`](FEATURE_SCHEMA.md)
- [`../evaluation/DETECTION_REFERENCE_BASELINE.md`](../../evaluation/DETECTION_REFERENCE_BASELINE.md) (baseline candidates are different)
