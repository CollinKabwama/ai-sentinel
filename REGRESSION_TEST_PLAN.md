# AI-Sentinel End-to-End Regression — Test Plan

**Branch under test:** `refactor/framework-independent-decision-core`  
**Java:** 21 (project), runtime available: OpenJDK 25 via Homebrew Maven  
**Build:** Maven multi-module (`mvn clean verify`)

## Modules

| Module | Role in E2E |
|--------|-------------|
| `ai-sentinel-core` | Decision core (`SentinelDecisionEngine`, scorers, policy, enforcement state) |
| `ai-sentinel-spring-boot-starter` | Servlet adapter (`SentinelFilter`, auto-config, Redis/Kafka optional) |
| `ai-sentinel-trainer` | Offline IF training (optional path) |
| `ai-sentinel-demo` | Runnable sample + integration smoke |

## Primary runtime flow (actual classes)

```text
SentinelFilter.doFilterInternal
  → ServletHttpRequestView / ServletEnforcementResponse
  → SentinelPipeline.process
      → IdentityContextResolver.resolve
      → FeatureExtractor.extract → RequestFeatures
      → SentinelDecisionEngine.evaluate → RiskDecision
      → EnforcementHandler.apply
      → TrainingCandidatePublisher (async)
      → IdentityResponseHook.afterPipeline
```

## Enforcement actions (implemented)

`ALLOW`, `MONITOR`, `THROTTLE`, `BLOCK`, `QUARANTINE`  
(No challenge / step-up verification in codebase.)

## Model implementations

- `StatisticalScorer` (always available)
- `IsolationForestScorer` (optional, in-core IF)
- `CompositeScorer` (weighted blend)
- Replaceable via `AnomalyScorer` SPI / `@ConditionalOnMissingBean`

## Existing test categories

| Category | Location |
|----------|----------|
| Unit (core) | scorers, policy, fusion, trust, decision engine |
| Architecture | `CoreIndependenceArchTest` |
| Pipeline (no Spring) | `SentinelPipeline*Test` |
| Starter integration | filter, auto-config, Redis Testcontainers |
| Demo E2E smoke | `DemoIntegrationTest` |
| Distributed | quarantine/throttle validation (Docker) |

## Scenarios planned

| ID | Scenario | Approach |
|----|----------|----------|
| A | Normal request | Demo + starter filter tests + new E2E if gap |
| B | Elevated risk / throttle abuse | Core pipeline + CompositeEnforcementHandler |
| C | All 5 enforcement actions | Core enforcement + decision engine |
| D | Rules-only (IF off) | Config / auto-config test |
| E | Model-enabled | IsolationForestScorer + composite tests |
| F | Model replacement | `ModelReplaceabilityTest` |
| G | Cold-start / warmup | StatisticalScorer warmup + pipeline |
| H | Fail-open paths | Identity/score fail-open pipeline tests |
| I | Multi-instance | Document: issue #21 / Testcontainers single-JVM only |
| J | Restart | Demo spring context restart smoke if feasible |
| K | Config defaults | SentinelProperties validation tests |
| L | API compat | HttpRequestView / EnforcementResponse SPI change review |

## Out of scope / not tested unless already present

- Full load / SLA certification
- True multi-JVM cluster E2E (tracked as issue #21)
- Challenge / MFA enforcement (not implemented)
- Trainer Kafka live cluster against production brokers

## Commands

```bash
mvn clean verify
mvn -pl ai-sentinel-demo -am package
# optional live: mvn -pl ai-sentinel-demo spring-boot:run
```

## Results

Full execution results, regression matrix, defects, coverage gaps, and conclusion:
[`REGRESSION_VALIDATION_REPORT.md`](REGRESSION_VALIDATION_REPORT.md)  
(**Conditionally validated**, 2026-08-03).
