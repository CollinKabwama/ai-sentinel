# AI-Sentinel Engineering Roadmap Tracker

## 1. Purpose

This document is the authoritative repository-level engineering tracker for AI-Sentinel.

Its job is to record:

- what the repository already implements;
- what has been completed and merged;
- what is only partially implemented;
- what is still missing;
- what depends on what;
- what order future engineering work should follow;
- what repository evidence supports each completion claim.

Completed work remains recorded here. Future planning must not rely on chat history as the primary source of truth.

This tracker is authoritative. The companion canvas in
`docs/planning/ENGINEERING_ROADMAP_CANVAS.md` is a concise visual aid only.
If the tracker and canvas ever disagree, the tracker wins.

## 2. Status Legend

- `DONE` — the intended engineering capability materially exists and repository evidence supports the claim.
- `PARTIAL` — meaningful implementation exists, but important roadmap requirements remain.
- `NOT_STARTED` — no meaningful implementation exists beyond generic supporting infrastructure.
- `BLOCKED` — work cannot proceed yet because a dependency or external constraint is unresolved.
- `SUPERSEDED` — the original roadmap item is no longer the right target and has been replaced by a clearer or safer capability.

## 3. Current Baseline

- Stable version: `0.3.0`
- Current merged baseline: `dev` at the time this tracker was created
- Framework-independent core: Java core in `ai-sentinel-core`
- Current shipped adapter: Spring Boot / Servlet starter in `ai-sentinel-spring-boot-starter`
- Remote runtime path: Java remote evaluation HTTP contract and controller/client support
- Additional runtime path: ASP.NET Core reference remote adapter in `dotnet/`
- Feature schema: `FeatureSchema` version `1`
- Remote contract version: `EvaluationContract.CONTRACT_VERSION = 1`
- Evaluation event schema: `EvaluationEvent` schema version `1`
- Default operating mode: `MONITOR`
- Performance baseline: tracked `0.3.0` in-process reference baseline exists; it is not a production SLA
- Important invariants:
  - framework-independent core stays free of Spring/Servlet/Reactor types
  - feature order is contract-critical
  - performance benchmarking is not detection evaluation
  - infrastructure failure is not attack evidence
  - shadow scoring, when added, must not affect production decisions
  - external model artifacts must not self-promote

Evidence:

- `pom.xml`
- `README.md`
- `CHANGELOG.md`
- `ARCHITECTURE.md`
- `docs/deployment.md`
- `docs/performance/REFERENCE_BASELINE.md`
- `docs/performance/reference-baseline.json`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/contract/EvaluationContract.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/model/FeatureSchema.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/contract/EvaluationEventSchemas.java`

## 4. Master Roadmap

| ID | Workstream | Status | Depends On | Evidence | Remaining Work | Next? |
|---|---|---|---|---|---|---|
| ENG-001 | Stable release baseline | DONE | - | `pom.xml`; `README.md`; `CHANGELOG.md`; `docs/deployment.md` | Preserve as baseline | No |
| ENG-002 | Release validation and artifact metadata | DONE | ENG-001 | `RELEASING.md`; `docs/testing.md`; Maven release metadata in `pom.xml` | Keep current | No |
| ENG-003 | Framework-independent decision core and Spring Boot/Servlet integration | DONE | ENG-001 | `ai-sentinel-core/`; `ai-sentinel-spring-boot-starter/`; `ARCHITECTURE.md`; `CoreIndependenceArchTest` | Preserve boundaries | No |
| ENG-004 | Stable feature schema v1 | DONE | ENG-003 | `FeatureSchema.java`; `docs/contracts/FEATURE_SCHEMA.md`; `FeatureSchemaContractTest.java` | No external registry yet | No |
| ENG-005 | Versioned evaluation event contract | PARTIAL | ENG-004 | `EvaluationEvent.java`; `EvaluationEventSchemas.java`; `docs/contracts/EVALUATION_EVENT.md`; `EvaluationDatasetWriter.java` | No production event-emission pipeline, replay consumption, or telemetry integration yet | Yes |
| ENG-006 | Remote evaluation contract/runtime | DONE | ENG-003 | `EvaluationRequest.java`; `EvaluationResponse.java`; `RemoteEvaluationClient.java`; `RemoteEvaluationController.java`; cross-language fixture tests | Hardening continues elsewhere | No |
| ENG-007 | Default MONITOR-safe runtime posture | DONE | ENG-003 | `SentinelPipeline.java`; `docs/deployment.md`; configuration docs | Preserve default | No |
| ENG-008 | Reference synthetic / evaluation dataset | NOT_STARTED | ENG-021 | No tracked dataset contract or dataset corpus found | Define tracked synthetic/reference data only after the dataset/export contract is explicit | Yes |
| ENG-009 | Benchmark foundation: JMH harness/module | DONE | ENG-001 | `ai-sentinel-benchmark/pom.xml`; `BenchmarkLauncher.java` | Preserve and maintain | No |
| ENG-010 | Benchmark foundation: scorer benchmark | DONE | ENG-009 | `ScorerLatencyBenchmark.java` | None | No |
| ENG-011 | Benchmark foundation: decision-engine benchmark | DONE | ENG-009 | `DecisionEngineBenchmark.java` | None | No |
| ENG-012 | Benchmark foundation: feature-extraction benchmark | DONE | ENG-009 | `FeatureExtractionBenchmark.java` | None | No |
| ENG-013 | Benchmark foundation: in-process pipeline benchmark | DONE | ENG-009 | `PipelineBenchmark.java` | None | No |
| ENG-014 | Benchmark foundation: identity-cardinality benchmark | DONE | ENG-009 | `IdentityCardinalityBenchmark.java` | None | No |
| ENG-015 | Benchmark manifests/environment metadata/scripts/docs | DONE | ENG-009 | `BenchmarkManifestWriter.java`; `EnvironmentMetadataCollector.java`; `scripts/run-benchmarks.sh`; `docs/performance/BENCHMARKING.md` | Maintain | No |
| ENG-016 | Reference performance baseline | DONE | ENG-009 | `docs/performance/REFERENCE_BASELINE.md`; `docs/performance/reference-baseline.json`; `scripts/capture-reference-baseline.sh` | Preserve accepted baseline; fix audit inconsistency separately if reopened | No |
| ENG-017 | Deployment/degradation benchmark extension: Java remote loopback | PARTIAL | ENG-009, ENG-006 | `DeploymentBenchmarkMain.java`; `RemoteBenchmarkServer.java`; `scripts/run-deployment-benchmarks.sh` | No `.NET -> Java` benchmark path or tracked stable deployment baseline | Yes |
| ENG-018 | Deployment/degradation benchmark extension: Redis-backed state and fail-open scenarios | PARTIAL | ENG-009, ENG-003 | `RedisContainerSupport.java`; Redis scenarios in `DeploymentBenchmarkMain.java`; docs/tests | Multi-instance / multi-host proof still missing | Yes |
| ENG-019 | Resource measurement extension | DONE | ENG-009 | `ResourceBenchmarkMain.java`; `ResourceSupport.java`; `scripts/run-resource-benchmarks.sh`; docs/tests | Preserve and use | No |
| ENG-020 | Benchmark regression/comparison tooling | DONE | ENG-009, ENG-016, ENG-017, ENG-019 | `BenchmarkComparisonMain.java`; `ComparisonAdapters.java`; `benchmark-comparison-policy.json`; scripts/tests | CI-host gating remains future work | No |
| ENG-021 | Dataset/export contract and policy | DONE | ENG-004, ENG-005 | `EvaluationDatasetWriter.java`; `EvaluationDatasetManifest.java`; `EvaluationDatasetSchemas.java`; `docs/contracts/DATASET_EXPORT.md`; `EvaluationDatasetWriterTest.java`; `EvaluationContractMapperPrivacyTest.java` | Populate the first tracked reference dataset in a separate workstream | No |
| ENG-022 | Deterministic replay platform | NOT_STARTED | ENG-021, ENG-008 | No replay CLI/platform found | Build reusable replay/evaluate tooling over the dataset/export contract and tracked reference dataset | Yes |
| ENG-023 | Detection evaluation framework | PARTIAL | ENG-022 | scenario/regression tests such as `DetectorQualityRegressionTest.java`; `DetectionScenarioRunner.java` | No confusion matrix, precision/recall, ROC/PR, labeled evaluation datasets, or reusable evaluation reports | Yes |
| ENG-024 | Detection reference baseline | NOT_STARTED | ENG-022, ENG-023, ENG-008 | No tracked detection-quality baseline found | Establish official dataset-backed evaluation baseline | Yes |
| ENG-025 | Scorer plug-in contract and integration hardening | PARTIAL | ENG-004 | `AnomalyScorer.java`; `CompositeScorer.java`; `IsolationForestScorer.java` | Missing formal scorer descriptor/health/schema-support contract and candidate isolation lifecycle | Yes |
| ENG-026 | Shadow scoring | NOT_STARTED | ENG-022, ENG-025 | No shadow execution infrastructure found | Add non-authoritative parallel scoring and disagreement capture | Yes |
| ENG-027 | Model registry/validation/lifecycle | PARTIAL | ENG-025 | `ModelRegistryReader.java`; `ModelArtifactMetadata.java`; `FilesystemModelRegistry.java`; `ModelRefreshScheduler.java` | Filesystem-only; no broader governed lifecycle, rollback workflow, or distributed registry state | Yes |
| ENG-028 | Champion/challenger | NOT_STARTED | ENG-026, ENG-027 | No dual-result comparison framework found | Add comparison evidence over the same feature snapshot | Yes |
| ENG-029 | Pilot observability | PARTIAL | ENG-003, ENG-005 | `TelemetryEvent.java`; `DefaultTelemetryEmitter.java`; `SentinelActuatorEndpoint.java`; docs | Strong generic telemetry exists, but no full pilot evidence/event pipeline, shadow disagreement metrics, or pilot dashboards/alerts package | Yes |
| ENG-030 | Human/analyst feedback | NOT_STARTED | ENG-005, ENG-029 | No feedback ingestion contract or history model found | Add labels, audit history, event reference, and export rules | Yes |
| ENG-031 | Identity intelligence enrichment | PARTIAL | ENG-003 | `BehavioralIdentityTrustEvaluator.java`; `IdentityRiskSignals.java`; `TrustPolicyAdjuster.java` | Broader typed identities and richer assurance/context models remain future work | Yes |
| ENG-032 | Risk advisory and policy profiles | PARTIAL | ENG-003, ENG-029 | `RiskExplanation.java`; `RiskFactor.java`; `SecurityAdvice.java`; `ThresholdPolicyEngine.java` | No named policy-profile product surface or host advice execution rails | Yes |
| ENG-033 | Distributed state / multi-instance proof | PARTIAL | ENG-003, ENG-018 | Redis stores/readers/writers; distributed docs/tests | Need multi-instance consistency, rolling deployment, mixed-version, and divergence evidence | Yes |
| ENG-034 | Cross-runtime reliability | PARTIAL | ENG-006 | `dotnet/README.md`; .NET contract types/validators/tests; Java cross-language fixture tests | Need broader version-skew, auth-failure, malformed-response, and benchmarked interop evidence | Yes |
| ENG-035 | Security hardening | PARTIAL | ENG-003, ENG-006, ENG-021, ENG-033, ENG-034 | `SECURITY.md`; contract validators; deployment docs; privacy-aware features and events | Missing some stronger remote auth/privacy/export and governed artifact controls | Yes |
| ENG-036 | Pilot deployment tooling | PARTIAL | ENG-029, ENG-033, ENG-035 | `docs/deployment.md`; `docs/configuration.md`; actuator and metrics surfaces | Need pilot bundles, alerts, runbooks, snapshots, and clearer deployment evidence packaging | Yes |
| ENG-037 | Controlled MONITOR pilot | NOT_STARTED | ENG-029, ENG-030, ENG-033, ENG-035, ENG-036 | No formal pilot evidence found | Run controlled pilot only after evidence-ready tooling exists | Yes |
| ENG-038 | Evidence-driven next product release | NOT_STARTED | ENG-017, ENG-019, ENG-020, ENG-024, ENG-026, ENG-028, ENG-037, ENG-035 | No future release scope assigned yet | Define release by evidence, not roadmap optimism | Yes |

## 5. Completed Capabilities

### Stable `0.3.0` baseline

Status: `DONE`

Completed:

- stable `0.3.0` release metadata exists;
- published release/migration/testing docs exist;
- `dev` has merged performance and contract work;
- default operator posture is MONITOR-safe.

Evidence:

- `pom.xml`
- `CHANGELOG.md`
- `README.md`
- `docs/migration.md`
- `docs/testing.md`
- `docs/deployment.md`

Important retained invariants:

- MONITOR is the safe default
- performance baseline is not a production SLA
- release baseline does not imply replay/dataset maturity

### Framework-independent core plus shipped Spring Boot/Servlet adapter

Status: `DONE`

Completed:

- Java decision core is isolated from Spring/Servlet/Reactor APIs;
- shipped adapter is Spring Boot / Servlet;
- remote evaluation path and ASP.NET Core adapter exist as separate integration surfaces.

Evidence:

- `ai-sentinel-core/`
- `ai-sentinel-spring-boot-starter/`
- `ARCHITECTURE.md`
- `ai-sentinel-core/src/test/java/dev/aisentinel/core/architecture/CoreIndependenceArchTest.java`
- `ai-sentinel-spring-boot-starter/src/test/java/dev/aisentinel/autoconfigure/architecture/StarterServletBoundaryArchTest.java`

Important retained invariants:

- core stays framework-independent
- host integration belongs in adapters

### Current scorer platform baseline

Status: `DONE`

Completed:

- `AnomalyScorer` abstraction exists;
- statistical scorer exists;
- Isolation Forest scorer exists;
- composite scoring exists;
- failure semantics and invalid-score behavior are explicitly modeled.

Evidence:

- `ai-sentinel-core/src/main/java/dev/aisentinel/core/scoring/AnomalyScorer.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/scoring/StatisticalScorer.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/scoring/IsolationForestScorer.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/scoring/CompositeScorer.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/decision/EvaluationStatus.java`

Important retained invariants:

- invalid score is not maximum risk
- fallback scores must not silently distort production decisions

### Versioned feature schema v1

Status: `DONE`

Completed:

- canonical ordered feature definitions exist;
- statistical, isolation-forest, and export projections are explicit;
- compatibility rules are documented;
- validation exists in code and tests.

Evidence:

- `ai-sentinel-core/src/main/java/dev/aisentinel/core/model/FeatureSchema.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/model/FeatureSnapshot.java`
- `docs/contracts/FEATURE_SCHEMA.md`
- `ai-sentinel-core/src/test/java/dev/aisentinel/core/model/FeatureSchemaContractTest.java`

Important retained invariants:

- feature ordering is contract-critical
- silent feature reinterpretation is forbidden

### Remote evaluation runtime support

Status: `DONE`

Completed:

- versioned remote request/response contract exists;
- local bridge and remote client/controller exist;
- cross-language fixtures/tests exist for Java and .NET.

Evidence:

- `ai-sentinel-core/src/main/java/dev/aisentinel/core/contract/EvaluationRequest.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/contract/EvaluationResponse.java`
- `ai-sentinel-spring-boot-starter/src/main/java/dev/aisentinel/autoconfigure/evaluation/RemoteEvaluationClient.java`
- `ai-sentinel-spring-boot-starter/src/main/java/dev/aisentinel/autoconfigure/web/RemoteEvaluationController.java`
- `ai-sentinel-spring-boot-starter/src/test/java/dev/aisentinel/autoconfigure/evaluation/CrossLanguageContractFixtureTest.java`
- `dotnet/tests/AI.Sentinel.AspNetCore.Tests/CrossLanguageContractFixtureTests.cs`

Important retained invariants:

- remote failures remain fail-open operational statuses
- transport DTOs are not the same thing as privacy-minimized evaluation evidence

### Benchmark foundation and accepted in-process performance baseline

Status: `DONE`

Completed:

- benchmark module/harness exists;
- scorer, decision-engine, feature-extraction, pipeline, and identity-cardinality benchmarks exist;
- manifest/provenance metadata exists;
- runner scripts exist;
- documentation exists;
- tracked accepted reference baseline exists.

Evidence:

- `ai-sentinel-benchmark/pom.xml`
- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/BenchmarkLauncher.java`
- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/jmh/ScorerLatencyBenchmark.java`
- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/jmh/DecisionEngineBenchmark.java`
- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/jmh/FeatureExtractionBenchmark.java`
- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/jmh/PipelineBenchmark.java`
- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/jmh/IdentityCardinalityBenchmark.java`
- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/BenchmarkManifestWriter.java`
- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/EnvironmentMetadataCollector.java`
- `scripts/run-benchmarks.sh`
- `scripts/capture-reference-baseline.sh`
- `docs/performance/BENCHMARKING.md`
- `docs/performance/REFERENCE_BASELINE.md`
- `docs/performance/reference-baseline.json`

Important retained invariants:

- benchmark numbers are not product SLAs
- accepted baseline must remain auditable and reproducible
- CI must not silently run host-sensitive benchmarks as ordinary unit tests

### Resource measurement extension

Status: `DONE`

Completed:

- resource benchmark harness exists;
- CPU, heap, RSS, GC, and Redis container metrics are captured;
- scripts/docs/tests exist.

Evidence:

- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/deployment/ResourceBenchmarkMain.java`
- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/deployment/ResourceSupport.java`
- `scripts/run-resource-benchmarks.sh`
- `docs/performance/BENCHMARKING.md`

Important retained invariants:

- resource measurements are controlled benchmark evidence, not production claims

### Benchmark comparison tooling

Status: `DONE`

Completed:

- comparison CLI exists;
- normalization across JMH/deployment/resource families exists;
- policy-driven comparison exists;
- scripts/docs/tests exist.

Evidence:

- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/compare/BenchmarkComparisonMain.java`
- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/compare/ComparisonAdapters.java`
- `ai-sentinel-benchmark/src/main/resources/benchmark-comparison-policy.json`
- `scripts/compare-benchmarks.sh`
- `scripts/run-benchmark-regression.sh`

Important retained invariants:

- comparability is separate from regression classification
- deployment/resource comparisons are not automatically authoritative release gates

## 6. Partial Capabilities

### Versioned evaluation event contract

Status: `PARTIAL`

Already exists:

- versioned `EvaluationEvent` schema `1`;
- privacy-allowlisted evidence contract;
- typed feature snapshot linkage to feature schema version;
- deterministic status normalization.

Still required:

- production export/emission integration;
- telemetry/event pipeline integration;
- replay/evaluation consumption.

Dependencies:

- `ENG-004`

Evidence:

- `ai-sentinel-core/src/main/java/dev/aisentinel/core/contract/EvaluationEvent.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/contract/EvaluationEventSchemas.java`
- `docs/contracts/EVALUATION_EVENT.md`

### Dataset/export contract and policy

Status: `DONE`

Already exists:

- versioned `TrainingCandidateRecord`;
- pseudonymous hashes for endpoint/enforcement material;
- trainer-side parsing and artifact publication;
- checksum/provenance around model artifacts.
- versioned `EvaluationEvent`-based dataset export contract;
- deterministic `events.jsonl` plus `manifest.json` writer;
- dataset checksum/integrity evidence;
- explicit append-order semantics for future replay;
- explicit prohibited-field policy for exported evaluation data;
- privacy guardrails for endpoint keys and remote header transport.

Still required:

- populate the first tracked reference synthetic/evaluation dataset;
- connect production event emission only when a later workstream requires it;
- build replay/evaluation consumers on top of the accepted dataset contract.

Dependencies:

- `ENG-004`
- `ENG-005`

Evidence:

- `ai-sentinel-core/src/main/java/dev/aisentinel/distributed/training/TrainingCandidateRecord.java`
- `ai-sentinel-spring-boot-starter/src/main/java/dev/aisentinel/autoconfigure/distributed/training/TrainingCandidateJson.java`
- `ai-sentinel-trainer/src/main/java/dev/aisentinel/trainer/TrainingCandidateMessageParser.java`
- `ai-sentinel-trainer/src/main/java/dev/aisentinel/trainer/FilesystemArtifactPublisher.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/dataset/EvaluationDatasetWriter.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/dataset/EvaluationDatasetManifest.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/dataset/EvaluationDatasetSchemas.java`
- `ai-sentinel-core/src/test/java/dev/aisentinel/core/dataset/EvaluationDatasetWriterTest.java`
- `ai-sentinel-core/src/test/java/dev/aisentinel/core/contract/EvaluationContractMapperPrivacyTest.java`
- `docs/contracts/DATASET_EXPORT.md`

### Deployment/degradation benchmark extension

Status: `PARTIAL`

Already exists:

- Java remote loopback benchmark scenarios;
- Redis-backed/local-memory control comparisons;
- failure scenarios for remote unavailable/slow/malformed and Redis unavailable/interrupted/recovery;
- environment metadata and scripts/docs.

Still required:

- `.NET -> Java` benchmark path;
- multi-instance deployment-state benchmark proof;
- tracked stable deployment baseline;
- clearer policy coverage alignment for all emitted scenarios.

Dependencies:

- `ENG-006`
- `ENG-009`

Evidence:

- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/deployment/DeploymentBenchmarkMain.java`
- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/deployment/DeploymentBenchmarkConfig.java`
- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/deployment/RemoteBenchmarkServer.java`
- `ai-sentinel-benchmark/src/main/java/dev/aisentinel/benchmark/deployment/RedisContainerSupport.java`
- `scripts/run-deployment-benchmarks.sh`

### Detection evaluation framework

Status: `PARTIAL`

Already exists:

- deterministic scenario and regression tests;
- detector-quality characterization tests;
- performance benchmarking is explicitly separated from detection claims.

Still required:

- reusable labeled evaluation framework;
- confusion matrix and precision/recall/F1/FPR/FNR reporting;
- score-distribution and delay/recovery reporting;
- per-scenario evaluation summaries.

Dependencies:

- `ENG-022`

Evidence:

- `ai-sentinel-core/src/test/java/dev/aisentinel/core/scenario/DetectionScenarioRunner.java`
- `ai-sentinel-core/src/test/java/dev/aisentinel/core/regression/DetectorQualityRegressionTest.java`
- `docs/performance/BENCHMARKING.md`

### Scorer plug-in contract and model lifecycle

Status: `PARTIAL`

Already exists:

- scorer abstraction;
- statistical / Isolation Forest / composite scoring;
- optional filesystem model registry;
- artifact metadata and refresh scheduler.

Still required:

- formal scorer descriptor and supported-schema declaration;
- clearer runtime health/lifecycle surface;
- candidate isolation rules;
- stronger activation/rollback governance beyond filesystem swap.

Dependencies:

- `ENG-004`

Evidence:

- `ai-sentinel-core/src/main/java/dev/aisentinel/core/scoring/AnomalyScorer.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/scoring/CompositeScorer.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/scoring/IsolationForestScorer.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/model/ModelRegistryReader.java`
- `ai-sentinel-spring-boot-starter/src/main/java/dev/aisentinel/autoconfigure/model/FilesystemModelRegistry.java`
- `ai-sentinel-spring-boot-starter/src/main/java/dev/aisentinel/autoconfigure/model/ModelRefreshScheduler.java`

### Pilot observability

Status: `PARTIAL`

Already exists:

- structured telemetry;
- actuator exposure;
- Micrometer metrics;
- last-decision explanations;
- operational statuses for invalid score and degradation.

Still required:

- pilot-specific evidence package;
- shadow disagreement metrics;
- dashboard/alert/runbook bundle;
- evaluation-event export integration.

Dependencies:

- `ENG-005`

Evidence:

- `ai-sentinel-core/src/main/java/dev/aisentinel/core/telemetry/TelemetryEvent.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/telemetry/DefaultTelemetryEmitter.java`
- `ai-sentinel-spring-boot-starter/src/main/java/dev/aisentinel/autoconfigure/actuator/SentinelActuatorEndpoint.java`
- `docs/deployment.md`

### Identity intelligence and advice/policy enrichment

Status: `PARTIAL`

Already exists:

- behavioral trust signals;
- trust-aware policy adjustment;
- structured risk factors and optional security advice.

Still required:

- richer typed identity models;
- broader advice catalog and host-executable integration rails;
- named policy-profile surface where justified by evidence.

Dependencies:

- `ENG-029`

Evidence:

- `ai-sentinel-core/src/main/java/dev/aisentinel/core/identity/trust/BehavioralIdentityTrustEvaluator.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/identity/model/IdentityRiskSignals.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/decision/SecurityAdvice.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/core/policy/TrustPolicyAdjuster.java`

### Distributed state / cross-runtime reliability / security hardening

Status: `PARTIAL`

Already exists:

- Redis-backed quarantine/throttle/trust stores;
- cross-language contract fixtures and .NET remote adapter;
- contract validation and privacy-aware surfaces;
- security documentation.

Still required:

- multi-instance and mixed-version proof;
- stronger remote authentication controls;
- broader interop reliability evidence and benchmarks;
- stronger artifact governance beyond checksum-based integrity evidence.

Dependencies:

- `ENG-006`
- `ENG-021`
- `ENG-033`
- `ENG-034`

Evidence:

- `ai-sentinel-core/src/main/java/dev/aisentinel/distributed/quarantine/ClusterQuarantineReader.java`
- `ai-sentinel-core/src/main/java/dev/aisentinel/distributed/throttle/ClusterThrottleStore.java`
- `ai-sentinel-spring-boot-starter/src/main/java/dev/aisentinel/autoconfigure/identity/trust/RedisFailOpenBehavioralBaselineStore.java`
- `dotnet/README.md`
- `SECURITY.md`

## 7. Planned Work

### Deterministic replay platform

Status: `NOT_STARTED`

Objective:

- replay versioned evaluation records through controlled scorer/configuration paths.

Dependencies:

- `ENG-008`
- `ENG-021`

Planned deliverables:

- replay/evaluate CLI or equivalent reusable entrypoint
- replay input validation
- provenance-aware result output

Definition of done:

- repository contains reusable replay infrastructure rather than only ad hoc tests

### Reference synthetic / evaluation dataset

Status: `NOT_STARTED`

Objective:

- define the first tracked synthetic or evaluation dataset only after the dataset/export contract is explicit.

Dependencies:

- `ENG-021`

Planned deliverables:

- tracked dataset structure built on the accepted dataset/export contract
- provenance and ordering rules
- safety constraints on included fields

Definition of done:

- repository contains a reference synthetic/evaluation dataset that conforms to the accepted export contract

### Detection reference baseline

Status: `NOT_STARTED`

Objective:

- establish an official detection-quality baseline that is distinct from performance benchmarking.

Dependencies:

- `ENG-022`
- `ENG-023`
- `ENG-008`

Planned deliverables:

- tracked labeled reference dataset or controlled synthetic evaluation corpus
- official baseline metrics and acceptance rules

Definition of done:

- repository contains a tracked, reproducible detection-quality baseline with evidence

### Shadow scoring

Status: `NOT_STARTED`

Objective:

- run candidate scorers on the same feature snapshot without affecting production behavior.

Dependencies:

- `ENG-022`
- `ENG-025`

Planned deliverables:

- shadow result contract
- disagreement capture
- failure isolation

Definition of done:

- candidate scoring cannot affect production score, action, state mutation, or response writes

### Champion/challenger

Status: `NOT_STARTED`

Objective:

- compare champion and challenger outputs over the same feature snapshot with auditable evidence.

Dependencies:

- `ENG-026`
- `ENG-027`

Planned deliverables:

- side-by-side score/action comparison
- disagreement metrics
- latency/error evidence

Definition of done:

- repository contains reusable comparison tooling rather than only conceptual model selection notes

### Controlled MONITOR pilot

Status: `NOT_STARTED`

Objective:

- run a bounded evidence-gathering pilot without asserting production enforcement readiness.

Dependencies:

- `ENG-029`
- `ENG-030`
- `ENG-033`
- `ENG-035`
- `ENG-036`

Planned deliverables:

- pilot checklist
- runbook
- evidence package expectations

Definition of done:

- repository evidence shows controlled pilot readiness, not merely generic runtime availability

### Evidence-driven next release

Status: `NOT_STARTED`

Objective:

- determine the next product release scope from evidence across performance, replay, detection, reliability, pilot, and security work.

Dependencies:

- `ENG-017`
- `ENG-019`
- `ENG-020`
- `ENG-024`
- `ENG-026`
- `ENG-028`
- `ENG-037`
- `ENG-035`

Planned deliverables:

- explicit release gate criteria
- evidence summary
- scope decision

Definition of done:

- next release is justified by repository-tracked evidence rather than roadmap optimism

## 8. Dependency Graph

Primary dependency direction reconciled from repository truth:

```text
Stable 0.3.0 baseline
  |
  +--> Benchmark foundation
  |      |
  |      +--> Reference performance baseline
  |      +--> Deployment/degradation benchmarks
  |      +--> Resource measurements
  |      +--> Benchmark comparison tooling
  |
  +--> Versioned feature schema
         |
         +--> Versioned evaluation event contract
                |
                +--> Dataset/export contract
                |      |
                |      +--> Reference synthetic / evaluation dataset
                |             |
                |             +--> Deterministic replay
                |                    |
                |                    +--> Detection evaluation framework
                |                           |
                |                           +--> Detection reference baseline
                |
                +--> Pilot observability enrichment
                +--> Human/analyst feedback
                +--> Shadow scoring
                        |
                        +--> Champion/challenger
                        |
                        +--> Model registry/lifecycle hardening
  |
  +--> Identity/advice/policy enrichment
  |
  +--> Distributed state proof
  +--> Cross-runtime reliability
  +--> Security hardening
         |
         +--> Pilot deployment tooling
                |
                +--> Controlled MONITOR pilot
                       |
                       +--> Evidence-driven next release
```

Important deviations from a naive older roadmap:

- deployment/degradation benchmarks, resource measurements, and benchmark comparison tooling are already implemented and therefore move from future dependencies to preserved completed foundations;
- versioned feature/event contracts are no longer future design ideas; they are baseline inputs into dataset/export/replay work;
- runtime remote/Redis support is treated separately from benchmark coverage and separately from multi-instance proof;
- generic observability exists now, but a complete pilot evidence system still depends on event/export/shadow work.

## 9. Immediate Queue

1. Reference synthetic / evaluation dataset
2. Deterministic replay platform
3. Detection evaluation framework
4. Official detection reference baseline
5. Scorer plug-in contract and model lifecycle hardening
6. Shadow scoring
7. Remaining deployment/degradation evidence, including `.NET -> Java` and stronger distributed coverage
8. Model lifecycle plus champion/challenger
9. Pilot observability, analyst feedback, and deployment readiness

## 10. Deferred / Explicitly Out of Scope

- algorithm discovery
- external research and candidate-model invention
- candidate training/tuning as an AI-Sentinel engineering responsibility
- production ENFORCE readiness without evidence
- IAM replacement
- MFA replacement
- WAF replacement
- large custom UI unless later evidence justifies it

## 11. Decision Log

| Date | Decision | Reason |
|---|---|---|
| 2026-09-06 | This tracker is the authoritative engineering roadmap record. | Prevent planning drift and reliance on chat history. |
| 2026-09-06 | Repository evidence wins over earlier roadmap assumptions. | Recent benchmark and contract work already changed the baseline. |
| 2026-09-06 | Research is outside the engineering roadmap. | Engineering owns integration/validation rails around external candidate models. |
| 2026-09-06 | MONITOR remains the safe default. | Current deployment guidance and operator safety posture require it. |
| 2026-09-06 | Performance benchmarking and detection effectiveness are tracked separately. | They answer different engineering questions and must not be conflated. |
| 2026-09-06 | Remote runtime support is not equivalent to remote benchmark completion. | Runtime availability and measured deployment evidence are different capabilities. |
| 2026-09-06 | Redis support is not equivalent to distributed proof. | Multi-instance correctness and reliability need their own evidence. |
| 2026-09-06 | Shadow scoring must not affect production behavior. | Candidate execution must remain observational only. |
| 2026-09-06 | External model artifacts must not self-promote. | Registry, activation, and release decisions require explicit governance. |
| 2026-09-06 | Dataset/export contract comes before the first authoritative reference dataset. | A reference dataset should conform to an explicit export contract rather than float beside it. |

## 12. Open Engineering Findings

| Finding | Status | Severity | Area | Evidence | Planned Resolution |
|---|---|---|---|---|---|
| FIND-001 Cross-runtime sensitive-header mapping mismatch | RESOLVED | HIGH | cross-runtime reliability; security hardening; dataset/export privacy | `ai-sentinel-core/src/main/java/dev/aisentinel/core/contract/EvaluationContractMapper.java`; `ai-sentinel-core/src/test/java/dev/aisentinel/core/contract/EvaluationContractMapperPrivacyTest.java`; `dotnet/src/AI.Sentinel.AspNetCore/Mapping/DefaultEvaluationRequestMapper.cs`; `docs/contracts/DATASET_EXPORT.md` | Resolved by changing the Java mapper from copy-all available headers to an explicit safe allowlist, preserving `Authorization` as presence-only and proving case-insensitive exclusion of sensitive headers in tests. .NET remained the reference for the privacy-minimized direction. |
| FIND-002 Reference baseline `analysis.json` consistency gap | OPEN | MEDIUM | benchmark auditability; documentation consistency | `docs/performance/REFERENCE_BASELINE.md`; `docs/performance/reference-baseline.json`; `scripts/capture-reference-baseline.sh` | Resolve as a narrow benchmark evidence consistency task without recapturing the accepted baseline unless later evidence proves the published numbers themselves are wrong. |

Finding notes:

- `FIND-001` was confirmed and resolved. The Java bridge now forwards only an explicit safe allowlist, represents `Authorization` as presence-only, and no longer forwards arbitrary credential/session headers. The .NET mapper was already privacy-minimized and was used as directional evidence.
- `FIND-002` was confirmed. The tracked baseline docs/json both reference `analysis.json`, and the capture script itself creates `capture-notes.txt` plus per-run `manifest.json` and `jmh.json` copies, but does not generate `analysis.json`.

Resolved findings must remain listed here with status `RESOLVED` plus resolution evidence.

## 13. Tracker Maintenance Rules

Future engineering work must update this tracker.

Before implementation:

- inspect this tracker;
- identify the affected roadmap item(s);
- verify dependencies are satisfied or explicitly blocked;
- confirm whether the target is `DONE`, `PARTIAL`, `NOT_STARTED`, `BLOCKED`, or `SUPERSEDED`.

After implementation and local validation:

- update the relevant workstream status;
- add repository evidence paths;
- describe what remains if the result is still `PARTIAL`;
- update the immediate queue when ordering changes.

After independent review:

- apply the accepted final status;
- record any review-driven scope correction in the relevant section or decision log.

After merge:

- preserve completed work in this tracker;
- add merged evidence such as commit/PR references where useful;
- do not delete completed capabilities simply to make the tracker shorter.
- update `docs/planning/ENGINEERING_ROADMAP_CANVAS.md` so the concise visual view stays synchronized with this tracker.

General rules:

- do not mark a capability `DONE` without repository evidence;
- do not treat documentation alone as sufficient proof when code/tests contradict it;
- do not treat transport support as benchmark or pilot evidence;
- do not treat performance evidence as detection-quality evidence;
- do not create a competing master roadmap elsewhere unless this tracker is explicitly superseded.
- if the tracker and canvas disagree, update the canvas to match the tracker; do not weaken the tracker to match the canvas.
