# AI-Sentinel documentation index

Each topic has one canonical document. Other documents should link to it rather than repeat it.

## Canonical documents

| Topic | Canonical document |
|-------|--------------------|
| Overview, positioning, quick start | [`../README.md`](../README.md) |
| Architecture, component boundaries, data flow, extension points | [`../ARCHITECTURE.md`](../ARCHITECTURE.md) |
| Security properties, integration responsibilities, limitations | [`../SECURITY.md`](../SECURITY.md) |
| Configuration properties and defaults | [`configuration.md`](configuration.md) |
| Operating modes, adoption, failure behavior, Redis operations | [`deployment.md`](deployment.md) |
| Upgrade notes | [`migration.md`](migration.md) |
| Test strategy and release gates | [`testing.md`](testing.md) |
| Evaluation workflows and evidence boundaries | [Evaluation](#evaluation) (below) |
| Performance evidence | [`performance/REFERENCE_BASELINE.md`](performance/REFERENCE_BASELINE.md) · [`performance/BENCHMARKING.md`](performance/BENCHMARKING.md) |
| .NET reference client | [`../dotnet/README.md`](../dotnet/README.md) |
| Scripts | [`../scripts/README.md`](../scripts/README.md) |
| Contributor workflow | [`../CONTRIBUTING.md`](../CONTRIBUTING.md) |
| Release history | [`../CHANGELOG.md`](../CHANGELOG.md) |
| Maven Central publishing (maintainers) | [`../RELEASING.md`](../RELEASING.md) |

## Suggested reading order

- **Operators:** [`deployment.md`](deployment.md) → [`configuration.md`](configuration.md) → [`../SECURITY.md`](../SECURITY.md) → [`migration.md`](migration.md) when upgrading.
- **Contributors:** [`../ARCHITECTURE.md`](../ARCHITECTURE.md) → [`../CONTRIBUTING.md`](../CONTRIBUTING.md) → [`testing.md`](testing.md).
- **Evaluators:** [Evaluation](#evaluation) below.

## Evaluation

Offline evaluation is repository-controlled engineering evidence. It is not production validation, not `ENFORCE` readiness, and not an external pilot. Ground truth stays in annotation sidecars and is never detector input.

| Document | Purpose |
|----------|---------|
| [`../evaluation/REFERENCE_DATASET.md`](../evaluation/REFERENCE_DATASET.md) | Historical seed reference corpus |
| [`../evaluation/DETERMINISTIC_REPLAY.md`](../evaluation/DETERMINISTIC_REPLAY.md) | Deterministic replay |
| [`../evaluation/DETECTION_EVALUATION.md`](../evaluation/DETECTION_EVALUATION.md) | Detection Evaluation Framework (metrics and evidence) |
| [`../evaluation/DETECTION_REFERENCE_BASELINE.md`](../evaluation/DETECTION_REFERENCE_BASELINE.md) | Official Detection Reference Baseline (capture, verification, lifecycle) |
| [`../evaluation/EVIDENCE_ARTIFACT_STORAGE.md`](../evaluation/EVIDENCE_ARTIFACT_STORAGE.md) | Large-artifact storage and content identity |
| [`contracts/EVALUATION_KIT.md`](contracts/EVALUATION_KIT.md) | Evaluation Kit contracts, generated corpora, evaluation CLI, container, comparison |
| [`contracts/EVALUATOR_PROVIDED_DATASET.md`](contracts/EVALUATOR_PROVIDED_DATASET.md) | Evaluator-provided datasets |
| [`evaluation/INDEPENDENT_REPRODUCTION.md`](evaluation/INDEPENDENT_REPRODUCTION.md) | Level-1 reproduction (reproduces evidence; not detection efficacy proof) |
| [`evaluation/SAME_FRAMEWORK_DETECTOR_COMPARISON.md`](evaluation/SAME_FRAMEWORK_DETECTOR_COMPARISON.md) | Level-3 same-framework detector comparison (factual deltas; not a scorer ranking) |
| [`evaluation/ORGANIZATION_PROFILE_SYNTHETIC_EVALUATION.md`](evaluation/ORGANIZATION_PROFILE_SYNTHETIC_EVALUATION.md) | Fictional organization-profile synthetic corpora |
| [`evaluation/MONITOR_MODE_PILOT.md`](evaluation/MONITOR_MODE_PILOT.md) | MONITOR-mode pilot evidence workflow (readiness; not external pilot evidence) |
| [`evaluation/EXTERNAL_MONITOR_EVALUATION.md`](evaluation/EXTERNAL_MONITOR_EVALUATION.md) | Handoff for an authorized independent evaluator · [attestation template](evaluation/external-evaluator-attestation.template.md) |

Performance evidence is separate from detection evidence: the performance reference baseline under [`performance/`](performance/REFERENCE_BASELINE.md) is not the Official Detection Reference Baseline.

## Contracts

| Contract | Purpose |
|----------|---------|
| [`contracts/FEATURE_SCHEMA.md`](contracts/FEATURE_SCHEMA.md) | Feature layout and versioning |
| [`contracts/EVALUATION_EVENT.md`](contracts/EVALUATION_EVENT.md) | Evaluation event schema |
| [`contracts/DATASET_EXPORT.md`](contracts/DATASET_EXPORT.md) | Dataset export |

## Candidate scorer lifecycle

Opt-in engineering capability packaged in **0.4.0**. None of these steps changes the running production scorer (`PROMOTED LIFECYCLE CHAMPION != RUNNING PRODUCTION SCORER`).

1. [`contracts/SCORER_ARTIFACT.md`](contracts/SCORER_ARTIFACT.md) — descriptor and integrity metadata
2. [`contracts/SCORER_CANDIDATE_LOADING.md`](contracts/SCORER_CANDIDATE_LOADING.md) — verified-bytes load and readiness
3. [`contracts/SCORER_CANDIDATE_EVALUATION.md`](contracts/SCORER_CANDIDATE_EVALUATION.md) — replay, evaluation, acceptance
4. [`contracts/SCORER_CANDIDATE_SHADOW.md`](contracts/SCORER_CANDIDATE_SHADOW.md) — opt-in observational shadow scoring
5. [`contracts/SCORER_MODEL_LIFECYCLE.md`](contracts/SCORER_MODEL_LIFECYCLE.md) — challenger, approval, designation-only promotion

## Versions

Current published release: **0.4.0** ([release notes](https://github.com/CollinKabwama/ai-sentinel/releases/tag/v0.4.0)). Previous: **0.3.0** ([release notes](https://github.com/CollinKabwama/ai-sentinel/releases/tag/v0.3.0)). Historical performance evidence belongs to the 0.3.0 line.
