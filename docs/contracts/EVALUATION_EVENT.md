# Evaluation Event

`EvaluationEvent` is a privacy-safe, framework-independent record of one behavioral-risk evaluation. It is intended to be the atomic evidence record for export, replay, offline evaluation, and dataset tooling.

## Version

Current evaluation-event schema version: `"1"`

The evaluation-event schema version is independent from the feature schema version. An event with `eventSchemaVersion = "1"` may carry `featureSchemaVersion = "1"` today, and event-schema revisions do not automatically imply a feature-schema change.

## Required Fields

- `eventSchemaVersion`
- `eventId`
- `observedAt`
- `identityKey`
- `endpointKey`
- `featureSchemaVersion`
- `features`
- `scorerId`
- `action`

## Optional Fields

- `correlationId`
- `identityType`
- `scorerVersion`
- `anomalyScore`
- `policyScore`
- `riskFactors`
- `policyId`
- `policyVersion`
- `evaluationMode`

`evaluationStatuses` is always present as a deterministic list, but it may be empty.

## Field Semantics

- `eventId`
  Stable caller-supplied event identifier. The contract does not generate random IDs on its own.
- `observedAt`
  Evaluation timestamp as UTC `Instant`.
- `identityKey`
  Stable pseudonymous identity key. This is not intended to carry raw usernames, emails, subjects, or account IDs.
- `endpointKey`
  Normalized route or stable endpoint identifier. It should not include query strings, tokens, or session identifiers.
- `featureSchemaVersion`
  Version of the feature contract used to interpret `features`.
- `features`
  Canonical privacy-safe feature snapshot aligned with `FeatureSchema`.
- `scorerId`
  Durable identifier for the scorer or scorer composition that produced the evaluation.
- `action`
  Final `EnforcementAction` selected by the runtime decision pipeline.
- `evaluationStatuses`
  Deterministic representation of a semantic status set. Statuses remain operational markers, not attack labels.

## Privacy Boundary

This event is intentionally allow-listed and does not include fields for:

- authorization headers;
- JWTs;
- refresh tokens;
- cookies;
- passwords;
- API keys or secrets;
- raw request bodies;
- raw request objects;
- generic metadata maps;
- direct personal identifiers such as email, phone number, full name, or raw user ID.

Pseudonymization is not anonymization. A stable `identityKey` can still be sensitive operational data and must be protected accordingly.

## Score Semantics

- Finite numeric scores in `[0,1]` may be present in `anomalyScore` and `policyScore`.
- Absent score is represented as `null`/missing at the Java layer via nullable `Double`.
- Invalid values such as `NaN`, `Infinity`, negative finite values, and values above `1.0` are rejected by the contract and must not be serialized as JSON numbers.
- `INVALID_SCORE` remains an evaluation status, not a maximum-risk alias.

## Status And Failure Semantics

Statuses such as `INVALID_SCORE`, `MODEL_UNAVAILABLE`, `DEGRADED`, and `REMOTE_EVALUATION_FAILURE` remain operational/evaluation context. They must not be reinterpreted as evidence of maliciousness.

`INFRASTRUCTURE FAILURE != ATTACK`

`ANOMALOUS != MALICIOUS`

## Compatibility Rules

For event schema `"1"`:

- additive optional fields may be introduced in an event schema revision if the consumer explicitly documents forward-compatible handling;
- unknown additive fields are conceptually distinct from an unknown schema version;
- an unknown `eventSchemaVersion` must be rejected explicitly;
- an unknown `featureSchemaVersion` must also be rejected explicitly even when the event envelope version is supported.

## Example

```json
{
  "eventSchemaVersion": "1",
  "eventId": "evt-example-001",
  "observedAt": "2026-09-06T10:00:00Z",
  "correlationId": "corr-example-001",
  "identityKey": "id:example-001",
  "identityType": "UNKNOWN",
  "endpointKey": "route:/api/orders",
  "featureSchemaVersion": "1",
  "features": {
    "requestsPerWindow": 12.0,
    "endpointEntropy": 1.32,
    "endpointConcentration": 0.75,
    "tokenAgeSeconds": 42.0,
    "parameterCount": 3,
    "payloadSizeBytes": 512,
    "headerFingerprintHash": 123456789,
    "ipBucket": 17
  },
  "scorerId": "composite",
  "anomalyScore": 0.42,
  "policyScore": 0.42,
  "action": "MONITOR",
  "evaluationStatuses": [
    "COMPLETE"
  ],
  "riskFactors": [],
  "policyId": "threshold",
  "evaluationMode": "ENFORCE"
}
```

## Current Boundary

This contract definition does not by itself implement production export, persistence, replay, telemetry pipelines, or dataset writers. Separate infrastructure can build on the same versioned event shape.
