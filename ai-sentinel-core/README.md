# ai-sentinel-core

Framework-independent identity-risk engine (no Spring, no Servlet API on the classpath).

**Primary types:** `SentinelPipeline` (orchestration), `SentinelDecisionEngine` / `RiskDecision` (pure evaluation),
`HttpRequestView` / `EnforcementResponse` (transport boundary), feature extraction, `AnomalyScorer` implementations,
policy, enforcement handlers, identity/trust/fusion SPIs, and `dev.aisentinel.model` registry artifact types.

Consumed by **`ai-sentinel-spring-boot-starter`** and **`ai-sentinel-trainer`**.

Candidate scorer/model artifact contract, validation, loading/health,
replay/evaluation acceptance, observational shadow scoring, and
champion/challenger lifecycle governance live under
`dev.aisentinel.core.scoring.artifact`, `dev.aisentinel.core.scoring.shadow`, and
`dev.aisentinel.core.scoring.lifecycle`. See
[`../docs/contracts/SCORER_ARTIFACT.md`](../docs/contracts/SCORER_ARTIFACT.md),
[`../docs/contracts/SCORER_CANDIDATE_LOADING.md`](../docs/contracts/SCORER_CANDIDATE_LOADING.md),
[`../docs/contracts/SCORER_CANDIDATE_EVALUATION.md`](../docs/contracts/SCORER_CANDIDATE_EVALUATION.md),
[`../docs/contracts/SCORER_CANDIDATE_SHADOW.md`](../docs/contracts/SCORER_CANDIDATE_SHADOW.md),
and [`../docs/contracts/SCORER_MODEL_LIFECYCLE.md`](../docs/contracts/SCORER_MODEL_LIFECYCLE.md).
Descriptor acceptance is not runtime availability. Runtime readiness is not model
quality, shadow eligibility, or production authority. Evaluating a READY candidate
through the existing reference replay/evaluation framework is not acceptance.
Explicit evaluation acceptance is not approval, shadow enablement, or production
deployment. Shadow scoring is observational only
(`SHADOW RESULT != PRODUCTION DECISION`) and defaults to disabled. Lifecycle
promotion updates designation state only
(`PROMOTED != PRODUCTION DEPLOYED`).

Offline evaluation packages under `dev.aisentinel.core.replay` and `dev.aisentinel.core.evaluation` implement the Detection Evaluation Framework (deterministic replay through complete-run evidence via `DetectionEvaluationRunner`) and Official Detection Reference Baseline tooling (capture, verification, lifecycle). See [`../evaluation/DETECTION_EVALUATION.md`](../evaluation/DETECTION_EVALUATION.md) and [`../evaluation/DETECTION_REFERENCE_BASELINE.md`](../evaluation/DETECTION_REFERENCE_BASELINE.md). That machinery does not establish production efficacy or a production quality gate.

**Next:** [Root README](../README.md) · [Architecture](../ARCHITECTURE.md)
