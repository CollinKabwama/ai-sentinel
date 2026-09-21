# Independent reproduction of Evaluation Kit evidence

Level-1 reproducibility package for AI-Sentinel Evaluation Kit reference evidence.

This package lets a technically competent outsider:

1. identify a small declared reproduction set
2. regenerate Evaluation Kit evidence with one command
3. verify artifact bytes and identities against repository-declared expectations
4. inspect human-readable HTML

## What this is

**Reproduction of repository-controlled Evaluation Kit evidence.**

PASS means: the declared controlled corpora were regenerated with the declared
configuration and matched the declared digests and run identities.

## What this is not

- external / third-party dataset validation
- production efficacy proof
- MONITOR or ENFORCE readiness
- model superiority
- cryptographic attestation or signed trust
- proof that an outsider has already run the package

Checksum verification ≠ signed attestation ≠ independent trust.

## Selected targets

| Target | Why included |
|--------|----------------|
| `established-normal` | Benign-only evaluation; unavailable positive-class metrics |
| `abrupt-burst` | Warmup + anomalous burst; controlled labeled metrics |
| `recovery` | Normal → anomaly → recovery temporal shape |

Default anomaly threshold: **0.5**.

These are tiny controlled synthetic corpora. Perfect ratios, when present, are
not production efficacy estimates.

### Expected structural summary (machine-verified)

| Target | Events / warmup / labeled | Benign / anomalous | Detection counts | Notes |
|--------|---------------------------|--------------------|------------------|-------|
| established-normal | 10 / 4 / 6 | 6 / 0 | TP0 TN6 FP0 FN0 | Positive-class metrics unavailable (not zeros) |
| abrupt-burst | 10 / 4 / 6 | 2 / 4 | TP4 TN2 FP0 FN0 | Controlled perfect ratios; tiny-n limitation |
| recovery | 10 / 4 / 6 | 3 / 3 | TP3 TN3 FP0 FN0 | Temporal recovery shape; tiny-n limitation |

Exact values are declared in `evaluation/reproduction/reproduction-manifest.json`.

## Prerequisites (canonical host path)

- macOS or Linux shell
- JDK **21**
- Maven (`mvn`)
- Python 3

Initial Maven dependency resolution may require network. After dependencies are
cached locally, evaluation itself does not require network. This package does
**not** claim a fully offline first-time host bootstrap.

Optional container alternate (not the normative path): Docker plus the existing
local `Dockerfile.evaluation-kit` image. Container **build** may require network;
hardened **run** can use `--network=none` after the image exists.

Windows is not claimed unless separately tested.

## Canonical command

From the repository root:

```bash
./scripts/reproduce-evaluation-evidence.sh --output /path/to/new-results-directory
```

The output directory must not already exist (strict no-overwrite).

This command:

1. checks JDK 21 / Maven / Python prerequisites
2. evaluates each declared corpus via `scripts/evaluate-generated-corpus.sh`
3. writes an output-local `index.html`
4. verifies digests and identities
5. writes deterministic `reproduction-result.json`

## Verify-only command

If evidence was already produced into a results directory:

```bash
./scripts/verify-reproduced-evidence.sh \
  --manifest evaluation/reproduction/reproduction-manifest.json \
  --results /path/to/results-directory \
  --repo-root .
```

## Expected output layout

```text
<output>/
  index.html
  reproduction-result.json
  established-normal/
    kit-evaluation-result.json
    event-inspection.json
    evaluation.json
    evaluation.md
    evaluation-report.html
  abrupt-burst/
    ...
  recovery/
    ...
```

Open `<output>/index.html` for human navigation. Machine success is
`reproduction-result.json` with `"status": "PASS"`.

## Identity model

| Concept | Field | Meaning |
|---------|-------|---------|
| Input locator | `corpusPath` | Repository-relative path only |
| Input content identity | `eventsSha256` / `annotationsSha256` | Canonical corpus bytes |
| Result family | `resultId` | Input-bound family id |
| Concrete run | `evaluationRunId` | Configured evaluation identity |
| Packaging software | `softwareVersion` | e.g. `0.4.0` |
| Reference configuration | `aiSentinelVersion` / `scorerVersion` / `policyVersion` | Historical reference package (`0.3.0`) |
| Artifact bytes | per-file `sha256` + `sizeBytes` | Exact evidence file identity |

`evaluationRunId` is not a checksum and not an attestation.

## Verification layers

The verifier reports:

- INPUT
- RUN IDENTITY
- ARTIFACT BYTES
- STRUCTURAL RESULT
- OVERALL REPRODUCTION

Any layer failure fails the target and the package.

## Package metadata location

```text
evaluation/reproduction/
  reproduction-manifest.json
  expected/*.digests.json
  README.md
```

Git stores declarations and expected digests only — not full regenerated HTML/JSON dumps.

## Optional BYO contract demonstration

A labeled evaluator-provided fixture exists for contract familiarity:

`ai-sentinel-core/src/test/resources/evaluation-kit-byo/minimal-labeled`

See [`docs/contracts/EVALUATOR_PROVIDED_DATASET.md`](../contracts/EVALUATOR_PROVIDED_DATASET.md).

That fixture is **not** part of the Level-1 reproduction acceptance gate and is
**not** independent external evidence.

## Optional container alternate

Build (may require network):

```bash
docker build -f Dockerfile.evaluation-kit -t ai-sentinel-evaluation-kit:local .
```

Then evaluate a single corpus with hardened runtime flags as documented in
[`docs/contracts/EVALUATION_KIT.md`](../contracts/EVALUATION_KIT.md) §13.
Host reproduction remains the normative package path.

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| output directory already exists | Choose a new `--output` path |
| JDK 21 required | Point `JAVA_HOME` / PATH at JDK 21 (not 25) |
| mvn not found | Install Maven or add it to PATH |
| eventsSha256 mismatch | Local corpus bytes differ from declared identity |
| evaluationRunId mismatch | Threshold/config/software identity differs from package |
| artifact sha256 mismatch | Evidence writers or inputs changed; package expectations need refresh |
| missing artifact | Evaluation did not complete for that target |

## Reporting a mismatch

If verification fails on a clean checkout with documented prerequisites, record:

- repository commit SHA
- JDK / Maven / OS versions
- exact command
- `reproduction-result.json`
- which target/layer failed

Do not include secrets. This package does not phone home.

Optional operator notes (date, environment, evaluator remarks) belong in a
**separate external record**, not in deterministic `reproduction-result.json`.

## Claim boundaries

- Evaluation ≠ deployment
- Synthetic ≠ production validation
- Reproduction PASS ≠ independent validation already performed by outsiders
- BYO demo ≠ external evidence
- Anomalous ≠ malicious
- Checksum match ≠ signed attestation
