# ai-sentinel-core

Framework-independent identity-risk engine (no Spring, no Servlet API on the classpath).

**Primary types:** `SentinelPipeline` (orchestration), `SentinelDecisionEngine` / `RiskDecision` (pure evaluation),
`HttpRequestView` / `EnforcementResponse` (transport boundary), feature extraction, `AnomalyScorer` implementations,
policy, enforcement handlers, identity/trust/fusion SPIs, and `dev.aisentinel.model` registry artifact types.

Consumed by **`ai-sentinel-spring-boot-starter`** and **`ai-sentinel-trainer`**.

**Next:** [Root README](../README.md) · [Architecture](../ARCHITECTURE.md)
