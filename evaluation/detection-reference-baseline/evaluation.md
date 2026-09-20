# Detection Evaluation Evidence

This report records deterministic diagnostic evaluation evidence. It does not establish an official detection baseline.

## Configuration

- Threshold: `0.5`
- Boundary: `valid anomalyScore >= anomalyThreshold`
- Policy action determines detection: no
- Detection delay equals request latency: no

## Reference Provenance

- Dataset ID: `reference-synthetic-evaluation-v1`
- Dataset schema version: `1`
- Evaluation event schema version: `1`
- Feature schema version: `1`
- Annotation schema version: `1`
- Source classification: `synthetic-reference-evaluation`
- Transformation version: `reference-dataset-v1`
- Ordering: `append-order`
- Events SHA-256: `1c4178816d33dd460dc233167d5b1e770ba25a6694a772407f2d01dc21e4eded`

## Replay Provenance

- Replay schema version: `1`
- Replay run ID: `replay-60737dbf1c865b96`
- Replay mode: `fresh-run`
- Scorer ID: `statistical`
- Scorer version: `0.3.0`
- Policy ID: `threshold-policy-default`
- Policy version: `0.3.0`
- Configuration fingerprint: `5c60d81361834b253970dc267df7ebe4ed4656417dfaef33d90c15e62469e767`
- AI-Sentinel version: `0.3.0`
- Replay results SHA-256: `dc2c7c24adf344e272589f7173c0b22c1ca303ef90f2e875a1cbbeec6d6a7347`

## Structural Counts

- Reference events: `136`
- Scenarios: `11`
- Aligned evaluation observations: `84`
- Expected normal observations: `26`
- Expected anomalous observations: `58`
- Evaluable predictions: `84`
- Excluded predictions: `0`
- Anomalous segments: `8`
- Detected segments: `7`
- Undetected segments: `1`
- Observed recovery windows: `0`
- Stabilized recovery windows: `0`
- Unstabilized recovery windows: `0`

## Confusion Matrix

- True positives: `35`
- True negatives: `26`
- False positives: `0`
- False negatives: `23`

## Metrics

- Precision: `1.0`
- Recall: `0.603448275862069`
- F1: `0.7526881720430108`
- False-positive rate: `0.0`
- False-negative rate: `0.39655172413793105`

## Scenario Metrics

### `normal-established-baseline`

- Category: `ESTABLISHED_NORMAL_BASELINE`
- Total observations: `12`
- Evaluable predictions: `12`
- Excluded predictions: `0`
- True positives: `0`
- True negatives: `12`
- False positives: `0`
- False negatives: `0`
- Precision: `undefined`
- Recall: `undefined`
- F1: `undefined`
- False-positive rate: `0.0`
- False-negative rate: `undefined`

### `warmup-new-identity`

- Category: `WARMUP_NEW_IDENTITY`
- Total observations: `2`
- Evaluable predictions: `2`
- Excluded predictions: `0`
- True positives: `0`
- True negatives: `2`
- False positives: `0`
- False negatives: `0`
- Precision: `undefined`
- Recall: `undefined`
- F1: `undefined`
- False-positive rate: `0.0`
- False-negative rate: `undefined`

### `rapid-request-burst`

- Category: `RAPID_REQUEST_BURST`
- Total observations: `10`
- Evaluable predictions: `10`
- Excluded predictions: `0`
- True positives: `4`
- True negatives: `0`
- False positives: `0`
- False negatives: `6`
- Precision: `1.0`
- Recall: `0.4`
- F1: `0.5714285714285714`
- False-positive rate: `undefined`
- False-negative rate: `0.6`

### `endpoint-behavior-change`

- Category: `ENDPOINT_BEHAVIOR_CHANGE`
- Total observations: `8`
- Evaluable predictions: `8`
- Excluded predictions: `0`
- True positives: `2`
- True negatives: `0`
- False positives: `0`
- False negatives: `6`
- Precision: `1.0`
- Recall: `0.25`
- F1: `0.4`
- False-positive rate: `undefined`
- False-negative rate: `0.75`

### `payload-size-deviation`

- Category: `PAYLOAD_SIZE_DEVIATION`
- Total observations: `6`
- Evaluable predictions: `6`
- Excluded predictions: `0`
- True positives: `6`
- True negatives: `0`
- False positives: `0`
- False negatives: `0`
- Precision: `1.0`
- Recall: `1.0`
- F1: `1.0`
- False-positive rate: `undefined`
- False-negative rate: `0.0`

### `parameter-count-deviation`

- Category: `PARAMETER_COUNT_DEVIATION`
- Total observations: `6`
- Evaluable predictions: `6`
- Excluded predictions: `0`
- True positives: `6`
- True negatives: `0`
- False positives: `0`
- False negatives: `0`
- Precision: `1.0`
- Recall: `1.0`
- F1: `1.0`
- False-positive rate: `undefined`
- False-negative rate: `0.0`

### `token-age-change`

- Category: `TOKEN_AGE_CHANGE`
- Total observations: `6`
- Evaluable predictions: `6`
- Excluded predictions: `0`
- True positives: `6`
- True negatives: `0`
- False positives: `0`
- False negatives: `0`
- Precision: `1.0`
- Recall: `1.0`
- F1: `1.0`
- False-positive rate: `undefined`
- False-negative rate: `0.0`

### `low-variance-baseline-deviation`

- Category: `LOW_VARIANCE_BASELINE_DEVIATION`
- Total observations: `4`
- Evaluable predictions: `4`
- Excluded predictions: `0`
- True positives: `3`
- True negatives: `0`
- False positives: `0`
- False negatives: `1`
- Precision: `1.0`
- Recall: `0.75`
- F1: `0.8571428571428571`
- False-positive rate: `undefined`
- False-negative rate: `0.25`

### `gradual-behavior-change`

- Category: `GRADUAL_BEHAVIOR_CHANGE`
- Total observations: `8`
- Evaluable predictions: `8`
- Excluded predictions: `0`
- True positives: `8`
- True negatives: `0`
- False positives: `0`
- False negatives: `0`
- Precision: `1.0`
- Recall: `1.0`
- F1: `1.0`
- False-positive rate: `undefined`
- False-negative rate: `0.0`

### `legitimate-bulk-operation`

- Category: `LEGITIMATE_BULK_OPERATION`
- Total observations: `10`
- Evaluable predictions: `10`
- Excluded predictions: `0`
- True positives: `0`
- True negatives: `0`
- False positives: `0`
- False negatives: `10`
- Precision: `undefined`
- Recall: `0.0`
- F1: `0.0`
- False-positive rate: `undefined`
- False-negative rate: `1.0`

### `interleaved-normal-identities`

- Category: `INTERLEAVED_NORMAL_IDENTITIES`
- Total observations: `12`
- Evaluable predictions: `12`
- Excluded predictions: `0`
- True positives: `0`
- True negatives: `12`
- False positives: `0`
- False negatives: `0`
- Precision: `undefined`
- Recall: `undefined`
- F1: `undefined`
- False-positive rate: `0.0`
- False-negative rate: `undefined`

## Temporal Evidence

### `normal-established-baseline`

- Category: `ESTABLISHED_NORMAL_BASELINE`
- Scenario observation count: `12`
- Anomalous segments: `0`
- No anomalous truth segments are present.

### `warmup-new-identity`

- Category: `WARMUP_NEW_IDENTITY`
- Scenario observation count: `2`
- Anomalous segments: `0`
- No anomalous truth segments are present.

### `rapid-request-burst`

- Category: `RAPID_REQUEST_BURST`
- Scenario observation count: `10`
- Anomalous segments: `1`
#### Segment `0`

- Anomaly onset event: `evt-ref-0023`
- Anomaly onset sequence: `23`
- Anomaly onset observed at: `2026-01-01T00:10:00Z`
- Anomaly window end event: `evt-ref-0032`
- Anomaly observation count: `10`
- Detected within observed anomaly window: `yes`
- Unavailable detector evidence count: `0`
- First detection event: `evt-ref-0029`
- Detection observation delay: `6`
- Evaluable-opportunity delay: `6`
- Event-time detection delay: `PT6S`
- Observed recovery window: none

### `endpoint-behavior-change`

- Category: `ENDPOINT_BEHAVIOR_CHANGE`
- Scenario observation count: `8`
- Anomalous segments: `1`
#### Segment `0`

- Anomaly onset event: `evt-ref-0039`
- Anomaly onset sequence: `39`
- Anomaly onset observed at: `2026-01-01T00:12:40Z`
- Anomaly window end event: `evt-ref-0046`
- Anomaly observation count: `8`
- Detected within observed anomaly window: `yes`
- Unavailable detector evidence count: `0`
- First detection event: `evt-ref-0044`
- Detection observation delay: `5`
- Evaluable-opportunity delay: `5`
- Event-time detection delay: `PT50S`
- Observed recovery window: none

### `payload-size-deviation`

- Category: `PAYLOAD_SIZE_DEVIATION`
- Scenario observation count: `6`
- Anomalous segments: `1`
#### Segment `0`

- Anomaly onset event: `evt-ref-0053`
- Anomaly onset sequence: `53`
- Anomaly onset observed at: `2026-01-01T00:16:00Z`
- Anomaly window end event: `evt-ref-0058`
- Anomaly observation count: `6`
- Detected within observed anomaly window: `yes`
- Unavailable detector evidence count: `0`
- First detection event: `evt-ref-0053`
- Detection observation delay: `0`
- Evaluable-opportunity delay: `0`
- Event-time detection delay: `PT0S`
- Observed recovery window: none

### `parameter-count-deviation`

- Category: `PARAMETER_COUNT_DEVIATION`
- Scenario observation count: `6`
- Anomalous segments: `1`
#### Segment `0`

- Anomaly onset event: `evt-ref-0065`
- Anomaly onset sequence: `65`
- Anomaly onset observed at: `2026-01-01T00:18:48Z`
- Anomaly window end event: `evt-ref-0070`
- Anomaly observation count: `6`
- Detected within observed anomaly window: `yes`
- Unavailable detector evidence count: `0`
- First detection event: `evt-ref-0065`
- Detection observation delay: `0`
- Evaluable-opportunity delay: `0`
- Event-time detection delay: `PT0S`
- Observed recovery window: none

### `token-age-change`

- Category: `TOKEN_AGE_CHANGE`
- Scenario observation count: `6`
- Anomalous segments: `1`
#### Segment `0`

- Anomaly onset event: `evt-ref-0077`
- Anomaly onset sequence: `77`
- Anomaly onset observed at: `2026-01-01T00:21:36Z`
- Anomaly window end event: `evt-ref-0082`
- Anomaly observation count: `6`
- Detected within observed anomaly window: `yes`
- Unavailable detector evidence count: `0`
- First detection event: `evt-ref-0077`
- Detection observation delay: `0`
- Evaluable-opportunity delay: `0`
- Event-time detection delay: `PT0S`
- Observed recovery window: none

### `low-variance-baseline-deviation`

- Category: `LOW_VARIANCE_BASELINE_DEVIATION`
- Scenario observation count: `4`
- Anomalous segments: `1`
#### Segment `0`

- Anomaly onset event: `evt-ref-0093`
- Anomaly onset sequence: `93`
- Anomaly onset observed at: `2026-01-01T00:25:18Z`
- Anomaly window end event: `evt-ref-0096`
- Anomaly observation count: `4`
- Detected within observed anomaly window: `yes`
- Unavailable detector evidence count: `0`
- First detection event: `evt-ref-0094`
- Detection observation delay: `1`
- Evaluable-opportunity delay: `1`
- Event-time detection delay: `PT10S`
- Observed recovery window: none

### `gradual-behavior-change`

- Category: `GRADUAL_BEHAVIOR_CHANGE`
- Scenario observation count: `8`
- Anomalous segments: `1`
#### Segment `0`

- Anomaly onset event: `evt-ref-0101`
- Anomaly onset sequence: `101`
- Anomaly onset observed at: `2026-01-01T00:27:18Z`
- Anomaly window end event: `evt-ref-0108`
- Anomaly observation count: `8`
- Detected within observed anomaly window: `yes`
- Unavailable detector evidence count: `0`
- First detection event: `evt-ref-0101`
- Detection observation delay: `0`
- Evaluable-opportunity delay: `0`
- Event-time detection delay: `PT0S`
- Observed recovery window: none

### `legitimate-bulk-operation`

- Category: `LEGITIMATE_BULK_OPERATION`
- Scenario observation count: `10`
- Anomalous segments: `1`
#### Segment `0`

- Anomaly onset event: `evt-ref-0115`
- Anomaly onset sequence: `115`
- Anomaly onset observed at: `2026-01-01T00:30:54Z`
- Anomaly window end event: `evt-ref-0124`
- Anomaly observation count: `10`
- Detected within observed anomaly window: `no`
- Unavailable detector evidence count: `0`
- First detection event: not detected within the observed anomaly window
- Observed recovery window: none

### `interleaved-normal-identities`

- Category: `INTERLEAVED_NORMAL_IDENTITIES`
- Scenario observation count: `12`
- Anomalous segments: `0`
- No anomalous truth segments are present.

## Limitations

- REPORT != BASELINE. This artifact records deterministic diagnostic evaluation evidence only.
- POLICY ACTION != DETECTOR PREDICTION. Detector classification remains thresholded anomaly-score evaluation.
- DETECTION DELAY != REQUEST LATENCY. Temporal delay describes ordered evaluation observations, not application latency.
- No observed recovery windows are present in this evaluation corpus.

