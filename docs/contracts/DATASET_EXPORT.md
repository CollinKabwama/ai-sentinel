# Dataset Export Contract

## Purpose

This document defines the portable dataset/export contract for AI-Sentinel evaluation data.

It is intentionally built on a privacy-minimized behavioral/evaluation record. It is not a raw HTTP request archive.

Canonical export record: `EvaluationEvent`

Portable dataset envelope:

```text
dataset/
  manifest.json
  events.jsonl
```

This contract is intended to support later:

- reference synthetic/evaluation datasets;
- deterministic replay;
- detection evaluation;
- external candidate-model evaluation;
- analyst feedback exports.

It does not itself implement replay, detection metrics, or the official reference dataset.

## Record Model

AI-Sentinel exports `EvaluationEvent` records directly rather than introducing a duplicate dataset-only DTO.

Why:

- `EvaluationEvent` is already intentionally allowlisted;
- it already carries feature schema linkage, feature snapshot, action, statuses, and risk factors;
- it avoids generic metadata maps that could smuggle secrets;
- it is framework-independent and does not depend on Spring or Servlet types.

Additional dataset semantics live in the manifest and writer behavior rather than in a second event record.

## Dataset Manifest

Current dataset schema version: `"1"`

Manifest fields:

- `datasetSchemaVersion`
- `datasetId`
- `createdAt`
- `aiSentinelVersion`
- `featureSchemaVersion`
- `evaluationEventSchemaVersion`
- `recordCount`
- `ordering`
- `sourceClassification`
- `transformationVersion`
- `eventsFile`
- `eventsSha256`
- optional `description`
- optional `scenario`

Checksum is integrity evidence only. It is not a signature and does not prove authenticity.

## Serialization Format

- `events.jsonl`
  - one JSON `EvaluationEvent` per line
  - stable field order
  - stable feature ordering
  - stable status ordering
  - stable risk-factor ordering as provided by the contract

- `manifest.json`
  - one JSON object describing the dataset envelope and checksum

## Ordering

Canonical dataset ordering is `append-order`.

That means:

- the order records are written to `events.jsonl` is authoritative;
- replay must preserve file order;
- timestamps are informative but do not override file order;
- exporters must not reorder records nondeterministically.

## Privacy Boundary

### Allowed

- pseudonymous `identityKey`
- `identityType` when available
- normalized `endpointKey`
- feature schema version
- feature snapshot
- scorer identity/version when truthfully known
- valid scores
- action
- evaluation statuses
- risk factors
- optional policy/evaluation context already modeled by `EvaluationEvent`

### Conditionally allowed

- `correlationId` when it is operationally safe and bounded
- `policyId` / `policyVersion` when available
- `evaluationMode` when truthfully known

### Prohibited

- Authorization header values
- bearer tokens
- JWTs
- access tokens
- refresh tokens
- API keys
- passwords
- cookies
- `Set-Cookie` values
- session secrets
- CSRF tokens
- arbitrary request headers
- raw request bodies
- raw response bodies
- arbitrary query-string values
- unnecessary PII
- secrets in metadata maps

`Pseudonymized != anonymous`

A stable pseudonymous `identityKey` can still be sensitive and must be handled accordingly.

## Header Transport Boundary

Remote evaluation adapters must not copy arbitrary headers into `EvaluationRequest`.

The transport boundary is a safe-header allowlist for behavioral parity, not a request mirror.

Current Java mapping preserves only a bounded safe subset and represents `Authorization` as presence-only rather than forwarding bearer credentials. The dataset export layer does not serialize request headers at all.

Remote mappers preserve query/form parameter count as shape-only placeholders. They must not forward raw parameter names or values.

## Endpoint Handling

Exported records use `endpointKey`, not raw URLs with query strings.

`EvaluationEvent` rejects endpoint keys containing query or fragment delimiters so sensitive query parameters cannot enter exported datasets through that contract.

## Empty Datasets

Empty datasets are valid.

`events.jsonl` may be empty when `recordCount = 0`, provided the manifest is present and the checksum matches the empty event file.

## Failure Handling

A completed dataset exists only when both:

- `events.jsonl`
- `manifest.json`

are finalized successfully.

Temporary/incomplete output must not masquerade as a valid completed dataset.

## Compatibility Rules

- unknown dataset schema versions must be rejected explicitly;
- unknown evaluation-event schema versions must be rejected explicitly;
- unknown feature schema versions must be rejected explicitly;
- additive future manifest fields may be tolerated by future readers only when documented as compatible;
- replay/evaluation code must not silently reinterpret incompatible schema versions.
