# Feature Schema

`FeatureSchema` defines the versioned feature contract used to interpret AI-Sentinel request features.

## Version

Current feature schema version: `"1"`

The feature schema version is a data-contract version. It is independent from release numbers and independent from the evaluation-event schema version.

## Canonical Feature Definitions

Canonical feature order for schema `"1"`:

1. `requestsPerWindow`
   Rolling request count within the baseline TTL window. Type: decimal. Unit: count.
2. `endpointEntropy`
   Natural-log entropy over recent endpoints for the evaluated identity. Type: decimal. Unit: nats.
3. `endpointConcentration`
   Maximum endpoint share in the same recent endpoint histogram. Type: decimal. Unit: ratio.
4. `tokenAgeSeconds`
   Seconds since `X-Token-Issued-At` when available. `-1` means missing, invalid, overflow, or materially future. Type: decimal. Unit: seconds.
5. `parameterCount`
   Query/form parameter map size. Type: integer. Unit: count.
6. `payloadSizeBytes`
   Request payload size. Type: long. Unit: bytes.
7. `headerFingerprintHash`
   Java `Map.hashCode()` of lowercase non-`Authorization` header names and header-value lengths. Type: hashed long. No physical unit.
8. `ipBucket`
   IPv4 `/24` numeric bucket, or a non-IPv4 remote-address hash bucket. Type: bucketed integer. Unit: bucket.

## Ordered Projections

`STATISTICAL`

1. `requestsPerWindow`
2. `endpointEntropy`
3. `endpointConcentration`
4. `tokenAgeSeconds`
5. `parameterCount`
6. `payloadSizeBytes`

`ISOLATION_FOREST`

1. `requestsPerWindow`
2. `endpointEntropy`
3. `tokenAgeSeconds`
4. `parameterCount`
5. `payloadSizeBytes`

`EXPORT`

1. `requestsPerWindow`
2. `endpointEntropy`
3. `tokenAgeSeconds`
4. `parameterCount`
5. `payloadSizeBytes`
6. `headerFingerprintHash`
7. `ipBucket`

`endpointConcentration` is part of the canonical schema and the statistical scorer projection, but not the current Isolation Forest or export projection. `headerFingerprintHash` and `ipBucket` are canonical features and remain available for export and offline analysis, but they are not consumed by the current online scorers.

## Compatibility Rules

The feature schema is strict. A consumer trained or written for one feature schema version must not silently interpret a different feature schema version.

The following changes require a new feature schema version:

- adding a canonical feature;
- removing a canonical feature;
- renaming a feature;
- reordering canonical or projection positions;
- changing feature type;
- changing unit or semantic meaning in a way that changes interpretation;
- changing normalization or encoding in a way that would cause an existing scorer or dataset reader to misread values.

Documentation clarifications that do not change meaning do not require a new version.

## Missing And Invalid Values

Missing feature is not equivalent to zero. Consumers must reject unsupported or incomplete feature schemas rather than silently substituting values.

All canonical features in schema `"1"` are required. Numeric fields that carry invalid values must be handled explicitly by the producing layer rather than being coerced into another meaning.
