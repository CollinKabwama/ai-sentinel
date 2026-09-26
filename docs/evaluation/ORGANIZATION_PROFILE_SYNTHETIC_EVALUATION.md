# Fictional organization-profile synthetic evaluation (Northgate)

AI-Sentinel's Evaluation Kit can reproducibly evaluate a documented **fictional
organization-profile synthetic** application/API workload.

This capability is **synthetic**. Northgate is a fictional, non-identifying
SaaS/API profile. It is **not** based on proprietary employer or customer data
and does **not** claim to represent a real deployment, customer, partner, or
production traffic.

`Northgate synthetic ≠ company-private validation` · `Synthetic ≠ Production validation`

## Allowed claim

> AI-Sentinel's Evaluation Kit can reproducibly evaluate a documented fictional
> organization-profile synthetic application/API workload.

Also allowed (after a run):

> Under the frozen synthetic scenario definitions and reference scorer
> configuration, the evaluation produced \[factual result\].

## Disallowed claims

Do **not** describe this work as:

- validated at an organization
- customer validated / externally validated / production validated
- representative of all SaaS systems
- realistic production accuracy
- deployment ready
- field validation

## Northgate profile

| Attribute | Value |
|-----------|--------|
| Profile id | `northgate` |
| Classification | fictional-organization-profile-synthetic |
| Narrative shape | authenticated multi-tenant SaaS/API platform |
| Identities per scenario | 4 (1 stable integration + 3 interactive) |
| Endpoint families | `/api/v1/records`, `/api/v1/records/{id}`, `/api/v1/search`, `/api/v1/export`, `/api/v1/account`, `/api/v1/auth/refresh` |

Artifacts live under
[`evaluation/organization-profile/northgate/`](../../evaluation/organization-profile/northgate/).

## Inventory

| Scenario id | Compiler family | Corpus directory |
|-------------|-----------------|------------------|
| `northgate.established-normal.v1` | `established-normal` | `corpora/northgate.established-normal` |
| `northgate.legitimate-burst.v1` | `legitimate-burst` | `corpora/northgate.legitimate-burst` |
| `northgate.abrupt-burst.v1` | `abrupt-burst` | `corpora/northgate.abrupt-burst` |
| `northgate.endpoint-shift.v1` | `endpoint-distribution-change` | `corpora/northgate.endpoint-shift` |
| `northgate.session-recovery.v1` | `recovery` | `corpora/northgate.session-recovery` |

Generation inputs are declared in
[`generation-spec.json`](../../evaluation/organization-profile/northgate/generation-spec.json)
(`generatorBuildId`: `aisentinel-northgate-corpus@1`).

Each scenario uses **48** warmup ticks + **32** evaluation ticks (**80** events).
Across five scenarios the total corpus is **400** events.

## Identity model

Within each scenario prefix (`northgate-en-`, `northgate-lb-`, `northgate-ab-`,
`northgate-ed-`, `northgate-rc-`):

| Suffix | Role |
|--------|------|
| `001` | Stable integration identity (primary subject for abrupt / endpoint-shift / recovery evaluation phases, matching existing compiler semantics) |
| `002`–`004` | Interactive identities |

## Per-identity baseline depth

Existing compilers emit **one event per timing tick** and rotate identities during
warmup. With `identityCount=4` and `warmup.durationSeconds=48`, each identity
receives **12** warmup/state-building observations before evaluation at the
scenario level.

Northgate provides 12 warmup observations per identity at the scenario level.
Because the reference StatisticalScorer maintains state per identity-endpoint
key, individual statistical state buckets receive a smaller subset of those
observations (approximately 4 warmup observations under the current deterministic
endpoint distribution across six endpoint keys).

That satisfies the authored per-identity baseline depth target (approximately
10–15 scenario-level observations) without changing `StatisticalScorer` or
compiler implementations.

### Honest compiler participation notes

- **established-normal** / **legitimate-burst**: all four identities continue into evaluation.
- **abrupt-burst**: all four identities appear in early evaluation-normal; the anomalous burst concentrates on identity `001` (existing `AbruptBurstCompiler` semantics).
- **endpoint-distribution-change** / **recovery**: evaluation-phase events are emitted for identity `001` only (existing compiler semantics). Identities `002`–`004` still receive the full 12-observation warmup baseline.

## Scenarios

### 1. Established normal

Stable benign behavior after baseline. Ground truth: all evaluation events benign.

### 2. Legitimate burst

Scheduled integration/export high activity authored as **benign**. Ground truth:
burst remains benign. High request rate alone does not define malice.

### 3. Abrupt burst

Authored anomalous sharp increase against a narrow endpoint (export-first list so
the compiler's collapse target matches the narrative). Ground truth: burst-window
events anomalous; surrounding events benign.

### 4. Endpoint shift

Endpoint-distribution change toward export-heavy behavior at approximately stable
volume (existing `endpointShiftFeatures` template). Ground truth: post-shift window
anomalous.

### 5. Session recovery

Uses the existing **`recovery`** family (not a session-anomaly extension). Normal →
anomalous burst → recovery. Recovery starts in the final third of the evaluation
window per `RecoveryCompiler` (authored boundary, not scorer-derived). Ground truth:
burst anomalous; recovery benign.

## Generator rules vs assumptions vs ground-truth assertions

### GENERATOR RULE

- One event per second of declared warmup/evaluation duration (`TimedPhaseCompiler`).
- Warmup identity rotation and feature templates come from existing family compilers
  and `FeatureCorpusSupport` (unchanged).
- Declared `endpointKeys` are written into generated events as synthetic
  `endpointKey` labels for distribution/entropy features — not evidence of full
  reconstructed HTTP request paths or live network capture.
- `normalFeatures`: warmup `requestsPerWindow=3.0`, evaluation-normal `4.0`;
  entropy/concentration/token-age/parameter/payload templates are fixed constants
  for the normal template.
- `headerFingerprintHash` is deterministic synthetic feature data derived from
  `identityKey|endpointKey|seedMix`, not evidence of full reconstructed HTTP headers.
- `ipBucket` is synthetic canonical feature data derived from the same fingerprint
  material.
- `tokenAgeSeconds` uses the existing fixed-template synthetic values (for example
  `120.0` in normal templates); it is not an evolving session clock.
- `legitimateBurstFeatures`, `burstFeatures`, and `endpointShiftFeatures` use the
  existing fixed templates for those families.
- Ground truth (`expectedClass`, category) is written only to `annotations.json`.

### ASSUMPTION

- Fixed feature templates are a plausible coarse approximation of a modest fictional
  SaaS/API workload for Evaluation Kit mechanics — not measured production statistics.
- Endpoint key lists express profile narrative and endpoint diversity; existing
  compilers do **not** apply endpoint-specific parameter-count or payload-size ranges.
- Token age is a stable template value (not a progressive clock) under current
  generator semantics.
- “Session recovery” here means recovery-family return-to-baseline, not a new
  identity-session-transition model.

### GROUND-TRUTH ASSERTION

- Established-normal and legitimate-burst evaluation events are benign.
- Abrupt-burst and endpoint-shift post-transition windows are anomalous.
- Recovery: anomalous transition window anomalous; recovery window benign.
- Assertions live in scenario `evaluationExpectations` and annotations sidecars only.

## Canonical features

Every generated event populates all eight existing canonical fields:

`requestsPerWindow`, `endpointEntropy`, `endpointConcentration`, `tokenAgeSeconds`,
`parameterCount`, `payloadSizeBytes`, `headerFingerprintHash`, `ipBucket`.

No feature schema changes. No new sentinel conventions.

## Determinism

Same scenario document bytes + same seed + same `generatorBuildId` → byte-identical
`corpus-manifest.json`, `events.jsonl`, and `annotations.json`.

Corpus identity uses the existing Kit corpus identity/hash machinery.

## Evaluation

Evaluate with the existing reference StatisticalScorer path only:

```bash
./scripts/evaluate-generated-corpus.sh \
  --corpus evaluation/organization-profile/northgate/corpora/northgate.established-normal \
  --output /path/to/northgate-established-normal-out
```

Repeat for each corpus directory under `corpora/`.

Outputs are the existing five Evaluation Kit artifacts:

- `evaluation.json`
- `evaluation.md`
- `kit-evaluation-result.json`
- `event-inspection.json`
- `evaluation-report.html`

Do **not** treat metrics as acceptance targets. Poor detector results, if any, are
observations under frozen scenarios/config — not a reason to retune the detector or
rewrite scenarios for score chasing.

## Ground-truth isolation

Detector-facing `events.jsonl` must not contain `expectedClass` /
`evaluationExpectations`. Annotations remain a sidecar. Scorer configuration is not
derived from annotations or scenario assertions.

## Limitations

- Fictional / synthetic only; not organization, customer, or production validation.
- Reuses existing feature-level compiler templates (limited natural variation;
  not endpoint-parameterized ranges).
- Some families concentrate evaluation-phase traffic on identity `001`.
- Does not include Isolation Forest comparison, external datasets, shadow scoring,
  MONITOR pilots, or additional organization profiles.
- Sixth session-anomaly scenario deferred.

## Hardening deferred

- session-anomaly / identity-session-transition scenario
- compiler extensions / new families
- additional profiles (financial, retail, customization frameworks)
- real traffic adapters
- shadow scoring / MONITOR pilots
- external dataset campaigns
- significance testing / detector comparisons
