# Testing and release gates

This page documents how AI-Sentinel validates detector and release behavior.

Day-to-day contribution workflow: [`../CONTRIBUTING.md`](../CONTRIBUTING.md).  
Publishing to Maven Central: [`../RELEASING.md`](../RELEASING.md).

---

## Full reactor gate

From the repository root, with the supported JDK (21):

```bash
mvn clean verify
```

This is the **primary release gate**. It runs unit, integration, architecture, characterization,
and module packaging for the full reactor. The same command is what CI should run for pull
requests and release candidates.

`mvn test` alone is useful for fast local iteration, but it is **not** a substitute for the release
gate: packaging and some module checks only run under `verify`.

When Docker is unavailable, Testcontainers-backed distributed checks are **skipped** (typically five
tests in the starter module). Skips are expected in that environment; failures and errors are not.

### Public API compatibility

Published modules (`ai-sentinel-core`, `ai-sentinel-spring-boot-starter`) can be checked against the
last Central baseline with japicmp:

```bash
mvn -Papi-compatibility -pl ai-sentinel-core,ai-sentinel-spring-boot-starter -am verify -DskipTests
```

CI runs this after the reactor verify. The baseline version is
`aisentinel.api.compatibility.oldVersion` (default `0.2.0`). Narrow excludes (documented in module
POMs) cover the removed one-argument `EnforcementHandler.isQuarantined(String)` and a pre-existing
Spring `@Bean` signature change on `SentinelAutoConfiguration.enforcementHandlerImpl`; both should be
removed when the consolidation release becomes the next baseline.

Expected shape (may grow if tests are added):

| Module | Typical tests | Notes |
|--------|---------------|--------|
| `ai-sentinel-core` | ~360 | Includes characterization + architecture |
| `ai-sentinel-spring-boot-starter` | ~215 | Includes 5 Docker/Testcontainers skips when Docker is unavailable |
| `ai-sentinel-trainer` | 12 | |
| `ai-sentinel-demo` | 4 | |
| **Total** | **~591** | 0 failures / 0 errors |

Run **twice** before cutting a release tag so flakes are visible.

---

## Characterization release gate

Characterization and detector-quality scenarios live under
`ai-sentinel-core/src/test/java/dev/aisentinel/core/scenario/` and related regression packages.
They are ordinary JUnit tests on the default Surefire path — **no separate suite profile is
required**. Passing `mvn clean verify` already executes them.

### Core characterization / scenario tests

| Test | Fidelity | Release expectation |
|------|----------|---------------------|
| `NormalEstablishedBaselineScenarioTest` | Real extractor + default gated decision engine; optional clocked plateau | Compressed staircase stays MONITOR (no THROTTLE+); steady plateau / constant volume → ALLOW |
| `GradualEndpointRequestRampScenarioTest` | Controlled `requestsPerWindow` staircase through production decision flow | Late scores stay MONITOR-band under continuous learning; gated ≥ always late score |
| `SuddenStepRequestBurstScenarioTest` | Controlled features (not full extractor E2E) | Abrupt step saturates; default gating holds elevated (ALWAYS may decay) |
| `RealisticVarianceSuddenStepScenarioTest` | Controlled features with mild/wide variance | Large steps remain high by design |
| `NearZeroVarianceSensitivityTest` | Controlled features | Tiny ordinal flips stay soft; genuine rate bursts still saturate |
| `BaselineUpdateStrategyComparisonTest` | Test-controlled update strategies | Documents ALWAYS vs gated strategies (seeded baselines) |

### Closely related regressions (also on verify)

| Test | Role |
|------|------|
| `SingleEndpointFloodRegressionTest` | Abrupt flood → QUARANTINE under gating; unit ramp must not freeze-escalate |
| `DetectorQualityRegressionTest` | Feature-role / flood vs distribution-shift checks |
| `BaselineLifecycleRegressionTest` | Explicit reset / TTL alignment |
| `AutomaticRelearnPoisoningRegressionTest` | No automatic relearn poisoning path |
| `CompositeScorerIsolationForestBlendTest` | IF fallback excluded from blend unless `MODEL` |
| `CoreIndependenceArchTest` / starter ArchUnit | Framework boundary guards |

### Focused characterization command

Optional smoke before a full verify:

```bash
mvn -pl ai-sentinel-core -Dtest=NormalEstablishedBaselineScenarioTest,GradualEndpointRequestRampScenarioTest,SuddenStepRequestBurstScenarioTest,RealisticVarianceSuddenStepScenarioTest,NearZeroVarianceSensitivityTest,BaselineUpdateStrategyComparisonTest,SingleEndpointFloodRegressionTest test
```

### Fidelity notes

- Production `requestsPerWindow` is a rolling bucket **count**. Abrupt `10 → 100` steps use
  controlled `RequestFeatures` fixtures; they are not produced by firing the extractor alone.
- Compressed-time extractor runs fill the window without ageing buckets; they characterize
  staircase learning, not wall-clock plateau timing.
- Clocked / plateau scenarios advance an injectable `BaselineStore` clock for steady-rate proof.

---

## Architecture gates

Included in `ai-sentinel-core` / starter Surefire:

- Core must not depend on Spring, servlet, or Reactor API packages.
- Starter servlet types remain confined to `autoconfigure.web`.
- Scorers remain replaceable behind `AnomalyScorer`.

---

## What these gates do not prove

- Production-ready ENFORCE under real traffic
- Multi-host distributed end-to-end behavior beyond documented Testcontainers coverage
- Isolation Forest per-feature attribution
- Fail-closed availability

Preserve MONITOR-first adoption: [`deployment.md`](deployment.md).
