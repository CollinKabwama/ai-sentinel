# Candidate Scorer Loading, Health, and Runtime Isolation

This contract describes how AI-Sentinel takes a **validated candidate
scorer/model descriptor** through controlled artifact acquisition, verifies
actual artifact bytes against declared integrity metadata, constructs a
**supported** candidate safely, and represents operational readiness.

Implementation lives in `dev.aisentinel.core.scoring.artifact`
(`CandidateScorerLoader` and related types).

It builds on the [scorer/model artifact contract](SCORER_ARTIFACT.md).

## Purpose

Answer operational questions such as:

- Were artifact bytes supplied?
- Do those bytes match the SHA-256 digest declared by the validated descriptor?
- Is a runtime implementation available for this scorer type?
- Did supported construction succeed?
- What is the candidate readiness status?

This does **not** answer detection quality, promotion readiness, shadow
eligibility, or production authority.

## Loading pipeline

```text
descriptor
  → descriptor validation (ScorerArtifactValidator)
  → bounded artifact bytes (caller-supplied)
  → SHA-256 over those exact bytes
  → digest comparison
  → verified immutable byte copy
  → format/type construction
  → READY / INVALID / UNAVAILABLE / NOT_CONFIGURED
```

`VERIFY BEFORE LOAD`: construction/decoding occurs only after digest match
(and after descriptor validation).

## Artifact acquisition

`CandidateScorerLoader` is **bytes-first**. Callers supply artifact bytes from a
trusted boundary (tests, registry adapters, or future controlled sources).

This boundary does **not** perform:

- HTTP / URL downloads
- arbitrary path loading
- dynamic JAR / class loading
- plugin directory scanning
- filesystem watching / hot reload

Filesystem or network acquisition, if needed later, belongs in adapters that
only supply bytes into this loader.

## Size bound

Candidate artifact payloads are rejected above
`IsolationForestModelCodec.MAX_PAYLOAD_BYTES` (16 MiB), matching the existing
Isolation Forest install limit.

Empty payloads are invalid for loadable artifact types. Missing (`null`) bytes
are represented as `UNAVAILABLE`.

## Integrity verification

Declared SHA-256 digest metadata (from the artifact contract) is compared to
SHA-256 computed over the exact defensive copy of supplied bytes.

Successful comparison means:

> Artifact bytes match the SHA-256 digest declared by the validated descriptor.

It does **not** mean:

- publisher authenticity
- trusted supply-chain provenance
- malware safety
- model quality
- production approval

`DIGEST METADATA VALID != ARTIFACT BYTES VERIFIED`

Digest mismatch prevents construction and is an engineering integrity failure:

`ARTIFACT INTEGRITY FAILURE != ATTACK`

## Verified bytes == consumed bytes

The loader copies caller-supplied bytes once before hashing and construction.
Verified content is retained in an immutable `VerifiedArtifactBytes` value that
exposes only defensive copies. Callers cannot mutate the verified content after
verification to change what was constructed.

`VERIFIED BYTES == CONSUMED BYTES`

## Supported formats

Current candidate runtime construction supports:

| Scorer type | Runtime implementation | Notes |
|---|---|---|
| `isolation_forest_v1` + `artifactFormat=aif1` | `isolation_forest_model_codec_v1` (AIF1 binary) | Decode via `IsolationForestModelCodec`; feature dimension must match descriptor |

Recognized but **not yet loadable** as candidate runtime implementations:

- `statistical`
- `composite`
- `external`

`DESCRIPTOR FORMAT RECOGNIZED != IMPLEMENTATION AVAILABLE`

Those types return `UNAVAILABLE` with
`UNSUPPORTED_RUNTIME_IMPLEMENTATION` after successful digest verification when
bytes were supplied.

An `isolation_forest_v1` descriptor with a verified but unsupported artifact
format returns `UNAVAILABLE` with `UNSUPPORTED_ARTIFACT_FORMAT`; it is not routed
to the AIF1 decoder.

No reflection, `Class.forName`, ServiceLoader plugin discovery, or user-supplied
class names are used.

## Descriptor binding

The loaded candidate remains bound to the exact `ScorerArtifactDescriptor`
instance that was validated and whose digest matched. Provenance retains:

- scorer/model identity and version
- artifact identity/format
- declared and verified digest
- feature schema / ordered features / dimension
- configuration fingerprint
- runtime implementation id

`CONFIGURATION FINGERPRINT != ARTIFACT DIGEST`

## Runtime status

`CandidateScorerRuntimeStatus`:

| Status | Meaning |
|---|---|
| `NOT_CONFIGURED` | No candidate supplied; default runtime unchanged |
| `INVALID` | Descriptor/artifact/digest/format/construction cannot be accepted |
| `UNAVAILABLE` | Candidate expected but no usable runtime implementation or bytes missing |
| `READY` | Integrity + compatibility + construction succeeded; scorer is callable |

`DEGRADED` is not used until a concrete non-quality degradation semantic exists.

### READY does not mean good

- `READY != ACCURATE`
- `READY != APPROVED`
- `READY != SHADOW ENABLED`
- `READY != CHAMPION`
- `READY != PRODUCTION READY`

### Health vs quality

Runtime health may reflect presence, digest verification, format support,
construction success, and callability.

It must **not** represent precision, recall, F1, false-positive rate, threshold
quality, or promotion recommendations.

`SCORER HEALTH != DETECTION QUALITY`

## Failure containment

Candidate load failures are returned as structured
`CandidateScorerLoadResult` / `CandidateScorerLoadIssue` values.

They must not:

- invent anomaly scores (`0.0` / `0.5` / `1.0`) as detection evidence
- produce `BLOCK` / `QUARANTINE`
- classify attack or maliciousness
- mutate behavioral baselines
- alter authoritative scorers, policy, or enforcement

`MODEL FAILURE != ATTACK`
`INFRASTRUCTURE FAILURE != ATTACK`
`UNAVAILABLE SCORE != SYNTHETIC SCORE`

Isolation is **in-process engineering containment**, not OS process or container
isolation.

## Candidate scorer behavior

A `READY` Isolation Forest candidate uses a dedicated candidate scorer bound to
the verified decoded model:

- `score` follows loaded-model semantics; invalid numeric outputs remain
  `INVALID_SCORE` (`INVALID SCORE != MAXIMUM RISK`)
- `update` is a no-op (candidate loading must not train behavioral state)

The candidate is **not** automatically attached to production
`SentinelDecisionEngine`, composite production blending, or shadow execution.
Offline candidate replay/evaluation is a separate explicit evaluation seam
documented in [`SCORER_CANDIDATE_EVALUATION.md`](SCORER_CANDIDATE_EVALUATION.md).

## Startup / default behavior

Candidate loading is opt-in. When no candidate is configured, use
`CandidateScorerLoader.notConfigured()`. Existing Isolation Forest registry
install, composite blending, invalid-score handling, policy, and enforcement
semantics remain unchanged by this boundary.

## Boundaries with later work

| Later work | Relationship |
|---|---|
| Replay / evaluation acceptance | See [`SCORER_CANDIDATE_EVALUATION.md`](SCORER_CANDIDATE_EVALUATION.md) — READY candidates may be replayed, evaluated, and optionally assessed by an explicit engineering acceptance policy |
| Shadow scoring | Observational only; not implemented here |
| Champion / challenger | Separate lifecycle governance |
| Promotion / rollback | Separate lifecycle governance |

`LOADED MODEL != ACCEPTED MODEL`
`MODEL AVAILABLE != SHADOW ENABLED`
`SHADOW RESULT != PRODUCTION DECISION`

## Related docs

- [`SCORER_ARTIFACT.md`](SCORER_ARTIFACT.md)
- [`SCORER_CANDIDATE_EVALUATION.md`](SCORER_CANDIDATE_EVALUATION.md)
- [`FEATURE_SCHEMA.md`](FEATURE_SCHEMA.md)
