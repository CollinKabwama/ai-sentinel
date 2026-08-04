# AI-Sentinel End-to-End Regression Validation Report

**Branch:** `refactor/framework-independent-decision-core`  
**Date:** 2026-08-03  
**Environment:** macOS aarch64; Apache Maven 3.9.12; OpenJDK 25.0.1 (Homebrew) running a Java 21 project; Docker **unavailable**  
**Conclusion:** **Conditionally validated**

---

## 1. End-to-end test plan

See also [`REGRESSION_TEST_PLAN.md`](REGRESSION_TEST_PLAN.md). Summary:

### Major runtime flows

```text
SentinelFilter → ServletHttpRequestView / ServletEnforcementResponse
  → SentinelPipeline.process
      → IdentityContextResolver → FeatureExtractor
      → SentinelDecisionEngine.evaluate → RiskDecision
      → EnforcementHandler.apply
      → TrainingCandidatePublisher / IdentityResponseHook
```

### Scenarios tested

| ID | Scenario | How |
|----|----------|-----|
| A | Normal request | `ServletAdapterEndToEndRegressionTest`, demo live HTTP `/api/hello` |
| B | Elevated risk | Fixed high scores → BLOCK / QUARANTINE / THROTTLE exhaust |
| C | All enforcement actions | `EndToEndPipelineRegressionTest` (ALLOW/MONITOR/THROTTLE/BLOCK/QUARANTINE) |
| D | Rules-only (IF off) | Defaults + `SentinelPropertiesTest` / auto-config (IF disabled by default) |
| E | Model-enabled | Existing IF / `CompositeScorer` unit tests |
| F | Model replacement | `ModelReplaceabilityTest` + fixed scorers in E2E |
| G | Cold-start | `coldStart_featureExtractionProducesNumericFeaturesWithoutBaselineHistory` |
| H | Failure / fail-open | Pipeline identity/score fail-open tests + scorer exception E2E |
| I | Multi-instance | **Not executed** (Docker down; Testcontainers skipped) |
| J | Restart / recovery | Demo cold start only (not full restart persistence) |
| K | Configuration | `SentinelPropertiesTest`, `SentinelAutoConfigurationTest` |
| L | Public API | Manual review of servlet → `HttpRequestView` / `EnforcementResponse` boundary |

### Scenarios not tested

- True multi-JVM Redis quarantine/throttle cluster E2E (issue #21; Testcontainers skipped)
- Live Kafka trainer against a broker
- Challenge / step-up MFA (not implemented)
- Full SLA / load certification
- Binary API compatibility tooling (Japicmp etc. not configured)
- Before/after refactor latency baseline comparison (no stored pre-refactor numbers)

### Assumptions

- Default thresholds: MONITOR ≥0.2, THROTTLE ≥0.4, BLOCK ≥0.6, QUARANTINE ≥0.8
- Scoring/feature failures fail open (request proceeds) unless enforcement already decided otherwise
- Project targets Java 21; validation ran on JDK 25 via Maven

### Required infrastructure

- Maven + JDK 21+ for full suite
- Docker for 5 distributed quarantine Testcontainers tests (unavailable here → skipped)

---

## 2. Execution report

| Command | Result | Tests | Skipped | Failures | Duration | Notes |
|---------|--------|-------|---------|----------|----------|-------|
| `mvn clean verify` (all tests) | **SUCCESS** | **423** | **5** | **0** | ~20–40 s | Includes new regression tests |
| `mvn clean verify -Dtest='!RedisClusterQuarantineWriterTest#expiredSkip…'` | SUCCESS | 422 | 5 | 0 | ~20 s | Earlier mitigation for suspected flaky method |
| Solo re-run of that Redis method | SUCCESS | 1 | 0 | 0 | ~0.65 s | Classified as **flaky / environmental**, not a confirmed refactor regression |
| `mvn -pl ai-sentinel-core dependency:tree` | OK | — | — | — | — | **No** `jakarta.servlet` / `org.springframework` |
| JaCoCo (core, during verify) | Generated | — | — | — | — | Instruction **84.2%**, branch **63.8%** |
| Demo `spring-boot:run` (port 18080) | Started | — | — | — | **0.625 s** to `Started DemoApplication` | `/actuator/health`→200, `/api/hello`→200; telemetry `ThreatScored` / `THROTTLE_ALLOW` observed |
| Static analysis (Checkstyle/SpotBugs/PMD) | N/A | — | — | — | — | Not configured beyond JaCoCo |
| Formatting / lint gate | N/A | — | — | — | — | No Spotless/fmt plugin in reactor |
| Container image build | N/A | — | — | — | — | No Dockerfile exercised in this pass |

### Skipped tests (environmental)

All five skips are Docker/Testcontainers:

- `DistributedQuarantineValidationTest#writerBeanIsRedisImplementationInSharedRedisContext`
- `…#actuatorExposesDistributedFlagsAndMetricSummary`
- `…#cacheServesStalePositiveUntilRedisKeyDeletedThenExpiresAndFailsOpen`
- `…#nodeAQuarantineWritesRedis_nodeBReaderSeesClusterQuarantine_…`
- `…#nodeAWritesQuarantine_nodeBClusterAwareFilterBlocksHttp`

### New tests added this validation

| Class | Count | Scope |
|-------|------:|-------|
| `EndToEndPipelineRegressionTest` | 8 | Core pipeline, no Spring/servlet |
| `ServletAdapterEndToEndRegressionTest` | 8 | Real servlet adapters + filter |
| `DecisionEnginePerformanceSmokeTest` | 1 | Decision-path latency smoke |

### Module breakdown (`mvn clean verify`)

| Module | Tests | Skipped | Failures |
|--------|------:|--------:|---------:|
| `ai-sentinel-core` | 223+ (incl. new) | 0 | 0 |
| `ai-sentinel-spring-boot-starter` | ~183 | 5 | 0 |
| `ai-sentinel-trainer` | 12 | 0 | 0 |
| `ai-sentinel-demo` | 4 | 0 | 0 |
| **Total** | **423** | **5** | **0** |

---

## 3. Regression matrix

| Capability | Test performed | Result | Evidence | Remaining risk |
|------------|----------------|--------|----------|----------------|
| Normal request | Filter + demo HTTP | Pass | `ServletAdapterEndToEndRegressionTest.normalRequest_*`; demo `/api/hello` 200 + telemetry | None material |
| Feature extraction | Cold-start pipeline | Pass | `EndToEndPipelineRegressionTest.coldStart_*` | Deep feature-value golden vectors not asserted |
| Risk evaluation (core) | Direct engine + pipeline | Pass | `SentinelDecisionEngineDirectTest`, E2E fixed scores | — |
| Model replacement | Deterministic `AnomalyScorer` | Pass | `ModelReplaceabilityTest` (11); E2E `FixedScorer` | Actuator snapshot still `instanceof`-bound |
| Rules-only / IF off | Defaults + properties | Pass | `SentinelPropertiesTest` IF default false; auto-config | No dedicated “IF disabled live demo” scenario |
| AI / IF path | Unit / registry tests | Pass | `IsolationForestScorer*`, `CompositeScorerTest` | Live retrain + Kafka not exercised |
| ALLOW | Pipeline E2E | Pass | score 0.0 → proceed, no response write | — |
| MONITOR | Pipeline + filter MONITOR mode | Pass | score 0.3; `Mode.MONITOR` continues chain | Custom handler double-write (pre-existing) |
| THROTTLE | Budget allow + exhaust 429 | Pass | `throttle_*` tests | Cluster throttle Redis skipped |
| BLOCK | Score 0.7 → 429, chain stopped | Pass | core + servlet adapter tests | — |
| QUARANTINE | Critical score + sticky block | Pass | quarantine then low score still blocked | Multi-node Redis skipped |
| Challenge / step-up | — | N/A | Not implemented | — |
| Fail-open (scorer) | Exploding scorer | Pass | `scoringFailure_failOpenAllowsRequest` | Intentional fail-open is a security tradeoff |
| Fail-open (identity / extract) | Existing pipeline tests | Pass | `SentinelPipelineIdentityFailOpenTest`, metrics tests | Feature extract fail skips enforcement |
| Core without Spring | ArchUnit + direct tests | Pass | `CoreIndependenceArchTest`; dependency tree clean | — |
| Servlet adapter boundary | View/response + filter | Pass | `ServletAdapterEndToEndRegressionTest` | Session `isNew` approximation (documented) |
| Config compatibility | Properties / auto-config | Pass | Existing starter tests | No Japicmp |
| Public API surface | Manual review | Pass w/ caveats | Constructors preserved; servlet types removed from core SPIs | Downstream code using servlet types on core APIs breaks (intentional) |
| Performance smoke | 10k evaluations | Pass | avg &lt; 1 ms bound (`DecisionEnginePerformanceSmokeTest`, 0.013 s wall) | No p95/p99 report; no pre-refactor baseline |
| Distributed multi-instance | Testcontainers | **Skipped** | Docker unavailable | **High** until Docker suite green |
| Restart / persistence | Demo cold start | Partial | Startup 0.625 s | Baseline/model recovery across restart not proven |
| Security bypass review | Code + negative tests | Pass w/ known issues | Exclude/OFF/MONITOR tested; NaN clamp tests exist | See defects / gaps |

---

## 4. Defect report

### D1 — Intermittent Redis quarantine writer assertion

| Field | Detail |
|-------|--------|
| **Title** | `RedisClusterQuarantineWriterTest.expiredSkipAfterRedisFailureDoesNotClearDegraded` intermittent failure |
| **Severity** | Low (async timing; not on request hot path of servlet refactor) |
| **Pre-existing or regression** | **Pre-existing / flaky** — unrelated to `HttpRequestView` boundary; failed once in an earlier full run, passed in isolation and in final full `verify` |
| **Reproduction** | Run full `mvn clean verify` repeatedly; race on async degraded-state clearing after mocked Redis failure |
| **Expected** | Degraded flag remains set after failed SET + expired skip |
| **Actual** | Occasionally cleared before assertion |
| **Affected classes** | `RedisClusterQuarantineWriter`, corresponding test |
| **Root-cause hypothesis** | Async callback / timing race in test, not decision-core logic |
| **Fix status** | **Not fixed** (out of scope for validation-only task) |
| **Remaining risk** | Occasional CI flake |

### D2 — NaN clamp metric dead under default `CompositeScorer` (pre-existing)

| Field | Detail |
|-------|--------|
| **Severity** | Low (observability) |
| **Source** | `ARCHITECTURE_REVIEW.md` §9.1 |
| **Fix status** | Documented only |

### D3 — MONITOR + custom `EnforcementHandler` can double-write (pre-existing)

| Field | Detail |
|-------|--------|
| **Severity** | Medium (integration footgun) |
| **Source** | `ARCHITECTURE_REVIEW.md` §9.3 |
| **Actual** | Filter always continues chain in MONITOR; custom handler that writes body can corrupt response |
| **Fix status** | Default wiring safe via `MonitorOnlyEnforcementHandler`; not changed |

### D4 — Session `isNew` approximated via timestamps (intentional refactor approximation)

| Field | Detail |
|-------|--------|
| **Severity** | Medium (identity-trust signal fidelity) |
| **Source** | `ARCHITECTURE_REVIEW.md` §9.5 |
| **Expected** | Exact `HttpSession.isNew()` semantics |
| **Actual** | `lastAccessed == creation` approximation on `HttpRequestView` |
| **Impact** | Can shift fused trust penalty (`NEW_SESSION` / 0.18) on atypical containers |
| **Fix status** | Documented; backlog `isNewSession()` on view |

### D5 — Deprecated single-arg `isQuarantined(String)` still overridable (pre-existing)

| Field | Detail |
|-------|--------|
| **Severity** | Medium for custom handlers |
| **Fix status** | Documented; pipeline calls two-arg form |

**No material decision-path or enforcement regression attributable to the framework-independence refactor was confirmed in this pass.**

---

## 5. Coverage-gap report

| Priority | Gap |
|----------|-----|
| **Critical** | Multi-node Redis quarantine/throttle E2E (Docker skipped) — cluster enforcement unverified on this machine |
| **High** | Feature-extractor failure path leaves request unenforced (fail-open) — behavior intentional but needs explicit product/security sign-off tests in ops docs |
| **High** | Live Isolation Forest load + inference + retrain under demo traffic (unit coverage exists; live path thin) |
| **High** | Binary / source API compatibility gate for downstream apps after servlet removal from core SPIs |
| **Medium** | Restart: model registry / baseline / Redis reconnect recovery |
| **Medium** | Full-request-path latency p95/p99 vs security-disabled / rules-only / AI modes |
| **Medium** | Concurrent same-identity enforcement races under load |
| **Low** | Trainer Kafka end-to-end against a real broker |
| **Low** | Second transport adapter (WebFlux) proof |
| **Low** | Challenge/MFA (N/A — not implemented) |

---

## 6. Performance smoke

- **Core decision engine:** 2 000 warm-up + 10 000 timed `evaluate` calls; assertion **average &lt; 1 000 µs** passed (test wall ~13 ms for the timed loop ⇒ order of **~1 µs/eval** on this machine — smoke only, not an SLA).
- **Demo startup:** **0.625 s** to `Started DemoApplication` (JDK 25).
- **Full request path / CPU / memory / throughput:** not instrumented in this pass.
- **Comparison to pre-refactor:** **not available** — do not claim improvement or regression.

---

## 7. Security regression review (summary)

| Boundary | Finding |
|----------|---------|
| Exclude paths / mode OFF | Bypass intentional and tested |
| MONITOR mode | Continues chain; default handler does not write blocking body |
| NaN / negative scores | Clamped to high risk (no bypass); covered by pipeline tests |
| Scorer / identity / extract failures | Fail-open by design; metrics `recordFailOpen` |
| Filter exception wrapper | Fail-open continue chain |
| Enforcement applied after decision | Verified for BLOCK/QUARANTINE/THROTTLE exhaust via adapters |
| Sensitive telemetry | Demo log shows truncated identity hash (`12ca***71a0`) |
| Core trust of caller attributes | Identity hash supplied by filter; core trusts resolver/scorer inputs as designed |
| Shared mutable state | Quarantine/throttle maps in-process; cluster path untested here |

Negative tests covering important boundaries: exclude path, mode OFF, MONITOR override, scorer fail-open, quarantine stickiness after score drops, throttle budget exhaustion.

---

## 8. Public API compatibility notes

**Intentional breaking change for anyone calling former servlet-typed core APIs:**

- Core SPIs now take `HttpRequestView` / `EnforcementResponse` instead of `HttpServletRequest` / `HttpServletResponse`.
- Spring apps using only the starter filter/auto-config should be unaffected.
- `SentinelPipeline` public constructor set preserved for Spring wiring; engine is constructed internally.
- Configuration property namespace `ai.sentinel.*` retained (validated by existing property/auto-config tests).

---

## 9. Final conclusion

### **Conditionally validated**

**What is validated**

- Clean reactor build and packaging (`BUILD SUCCESS`).
- Existing automated suite green: **423 tests, 0 failures**, 5 Docker-dependent skips.
- Framework-independent core: ArchUnit + dependency tree; decision path testable without Spring/servlet.
- Spring servlet adapter path: normal allow, block, quarantine, MONITOR, exclude, OFF.
- All five implemented enforcement actions exercised end to end on the real pipeline.
- Model replaceability and fail-open scoring behavior confirmed.
- Performance smoke shows no multi-millisecond accidental I/O on the hot decision path.
- Demo application starts and serves protected traffic with telemetry.

**What is not validated**

- Distributed multi-instance enforcement (Docker/Testcontainers skipped).
- Full performance certification and pre/post refactor comparison.
- Binary API tooling for third-party consumers.
- Live IF retrain/Kafka and full restart recovery of remote state.

**Do not treat this as production certification.** Remaining risks are primarily environmental (no Docker) and known pre-existing footguns documented in `ARCHITECTURE_REVIEW.md`, not newly introduced decision-logic regressions from the refactor.
