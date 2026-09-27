# ai-sentinel-core

Framework-independent behavioral-risk decision engine (no Spring, Servlet, or Reactor on the classpath).

**Primary types:** `SentinelPipeline` (orchestration), `SentinelDecisionEngine` / `RiskDecision` (pure evaluation),
`HttpRequestView` / `EnforcementResponse` (transport boundary), feature extraction, `AnomalyScorer` implementations,
policy, enforcement handlers, identity/trust/fusion SPIs, and `dev.aisentinel.model` registry artifact types.

Consumed by **`ai-sentinel-spring-boot-starter`** and **`ai-sentinel-trainer`**.

Candidate scorer/model artifact contract, validation, loading/health,
replay/evaluation acceptance, observational shadow scoring, and
champion/challenger lifecycle governance live under
`dev.aisentinel.core.scoring.artifact`, `dev.aisentinel.core.scoring.shadow`, and
`dev.aisentinel.core.scoring.lifecycle`. None of these paths changes the running
production scorer; shadow scoring is observational and off by default. Contracts
and boundaries: [candidate scorer lifecycle](../docs/README.md#candidate-scorer-lifecycle).

The remote evaluation wire contract (`EvaluationRequest` / `EvaluationResponse`)
lives in `dev.aisentinel.core.contract`.

Offline evaluation packages under `dev.aisentinel.core.replay` and `dev.aisentinel.core.evaluation` implement the Detection Evaluation Framework (deterministic replay through complete-run evidence via `DetectionEvaluationRunner`) and Official Detection Reference Baseline tooling (capture, verification, lifecycle). See [`../evaluation/DETECTION_EVALUATION.md`](../evaluation/DETECTION_EVALUATION.md) and [`../evaluation/DETECTION_REFERENCE_BASELINE.md`](../evaluation/DETECTION_REFERENCE_BASELINE.md). That machinery does not establish production efficacy or a production quality gate.

**Next:** [Root README](../README.md) · [Architecture](../ARCHITECTURE.md)
