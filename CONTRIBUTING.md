# Contributing to AI-Sentinel

Thanks for helping improve AI-Sentinel. Bug-fix pull requests are always welcome.

For **new features** or **non-trivial behavior changes**, open an issue first so maintainers and users can discuss scope, defaults, and compatibility:

[Open an issue](https://github.com/CollinKabwama/ai-sentinel/issues/new)

Contributions should match the project’s style: **small, reviewable changes**; **tests** when behavior changes; **documentation** when user-visible behavior or configuration changes.

By participating, you agree to follow the [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).

---

## Breaking changes

When your PR introduces a **breaking change** (API, configuration, or behavior that can break existing users):

- Mention **breaking change** clearly in the PR title or description so maintainers can label the PR.
- In the PR description, include **migration notes**:
  - What is changing and why
  - How users should update code or configuration
  - Before/after examples where helpful

**Examples of breaking changes:** removing or renaming public types, changing default property values that alter enforcement, removing support for a configuration key, or changing semantics of scores or policy bands without a compatibility period.

---

## Deprecations

When your PR **deprecates** functionality (but keeps it working for a transition period):

- Describe what is deprecated, why, and what to use instead.
- If you know a removal timeline, state it; otherwise say “future release” and link a tracking issue.

---

## Project structure

| Module | Purpose |
|--------|---------|
| **ai-sentinel-core** | Framework-independent **Java** engine (no Spring/Servlet/Reactor): features, scorers, `SentinelDecisionEngine` / `RiskDecision`, policy, enforcement, pipeline contracts, Isolation Forest training/scoring, model artifact types (`dev.aisentinel.model.*`). |
| **ai-sentinel-spring-boot-starter** | **Current** Spring Boot / Servlet adapter: auto-configuration, servlet filter, `SentinelProperties`, actuator, Micrometer, optional distributed and model-registry beans. |
| **ai-sentinel-trainer** | Optional standalone Spring Boot app: consumes training candidates (Kafka when enabled), trains IF, publishes to a filesystem model registry. See [`ai-sentinel-trainer/README.md`](ai-sentinel-trainer/README.md). |
| **ai-sentinel-demo** | Reference Spring Boot app for local runs and smoke tests. |
| **ai-sentinel-benchmark** | Opt-in JMH, deployment, and resource benchmarks. Not published; JMH suites do not run on `mvn verify`. See [`docs/performance/BENCHMARKING.md`](docs/performance/BENCHMARKING.md). |
| **dotnet/** | Reference ASP.NET Core remote adapter (`AI.Sentinel.AspNetCore`) — consumes remote evaluation HTTP API; no C# scoring engine. See [`dotnet/README.md`](dotnet/README.md). |
| **evaluation/** | Offline Detection Evaluation Framework docs, tracked synthetic corpus, and Official Detection Reference Baseline (capture/verify/lifecycle). Engineering evidence only — not production efficacy or a quality gate. Start at [`evaluation/DETECTION_EVALUATION.md`](evaluation/DETECTION_EVALUATION.md) and [`evaluation/DETECTION_REFERENCE_BASELINE.md`](evaluation/DETECTION_REFERENCE_BASELINE.md). |

---

## Where to start

1. **`SentinelPipeline`** — [`ai-sentinel-core/.../SentinelPipeline.java`](ai-sentinel-core/src/main/java/dev/aisentinel/core/SentinelPipeline.java) — identity resolve → feature extract → **`SentinelDecisionEngine`** (score / fuse / policy) → enforce → optional training publish.
2. **`SentinelDecisionEngine`** — [`.../decision/SentinelDecisionEngine.java`](ai-sentinel-core/src/main/java/dev/aisentinel/core/decision/SentinelDecisionEngine.java) — framework-free risk decision returning `RiskDecision` (never writes the HTTP response).
3. **`SentinelFilter`** — [`ai-sentinel-spring-boot-starter/.../SentinelFilter.java`](ai-sentinel-spring-boot-starter/src/main/java/dev/aisentinel/autoconfigure/web/SentinelFilter.java) — servlet entry point and adapter boundary.
4. **`SentinelAutoConfiguration`** — [`.../SentinelAutoConfiguration.java`](ai-sentinel-spring-boot-starter/src/main/java/dev/aisentinel/autoconfigure/config/SentinelAutoConfiguration.java) — beans and `@ConditionalOnMissingBean` extension points.
5. **Offline evaluation** — `dev.aisentinel.core.replay` and `dev.aisentinel.core.evaluation` (including `DetectionEvaluationRunner` and `CandidateDetectionEvaluationRunner`) for deterministic replay and complete-run evidence against [`evaluation/reference/`](evaluation/reference/). See [`evaluation/DETECTION_EVALUATION.md`](evaluation/DETECTION_EVALUATION.md) and [`docs/contracts/SCORER_CANDIDATE_EVALUATION.md`](docs/contracts/SCORER_CANDIDATE_EVALUATION.md).

See [`ARCHITECTURE.md`](ARCHITECTURE.md) and [`docs/configuration.md`](docs/configuration.md) for the full picture.

---

## Branching strategy

| Branch | Purpose |
|--------|---------|
| **`main`** | Stable, release-quality code. Tagged for releases. Not the default target for day-to-day PRs. |
| **`dev`** | Active integration branch. **Default target for pull requests.** |

### For contributors

1. Branch from **`dev`**
2. Open your PR against **`dev`**
3. Use descriptive branch names:

| Prefix | Use |
|--------|-----|
| `feature/` | New functionality |
| `bugfix/` | Bug fixes (reference issue number if applicable) |
| `docs/` | Documentation changes |
| `chore/` | Build, CI, dependency updates |
| `hotfix/` | Urgent fix targeting `main` (maintainer-approved only) |

### Hotfixes (rare)

If a maintainer designates an issue as **hotfix**, **security**, or **release-blocker**:

- Branch from `main`, PR into `main`, then maintainers merge `main` back into `dev`.

### Releases

Maintainers merge `dev` → `main` and tag releases (for example [`v0.4.0`](https://github.com/CollinKabwama/ai-sentinel/releases/tag/v0.4.0)). After promoting a release, keep `dev` and `main` tips aligned. Contributors do not manage releases.

---

## Prerequisites

- **Java 21** — required by the root `pom.xml` (`<java.version>21</java.version>`) and CI (Temurin 21). This is the **supported/tested** baseline. Newer JDKs (for example JDK 25) are not part of the CI matrix; local Mockito/JaCoCo failures outside JDK 21 are environment issues, not a claim that the production engine requires those JDKs.
- **Maven 3.8+**
- **.NET 8 SDK** — optional; required only to build/test [`dotnet/`](dotnet/).
- **Docker** — optional; needed for Testcontainers-based tests in `ai-sentinel-spring-boot-starter` (those tests are skipped when Docker is unavailable).

---

## Building

From the repository root:

```bash
java -version   # expect 21
mvn clean install
```

To consume a local build in another project, run the command above and depend on `dev.aisentinel:ai-sentinel-spring-boot-starter` at the version in the parent `pom.xml`. Maintainers publish releases from tags on `main` ([`RELEASING.md`](RELEASING.md)).

Related: [`docs/testing.md`](docs/testing.md) (release gates) · [`docs/README.md`](docs/README.md) (docs index) · [`scripts/README.md`](scripts/README.md) (script catalog).

---

## Running the tests

Prefer the **reactor root** so modules resolve each other from the reactor build (not a stale jar in `~/.m2`).
Requires the supported/tested **Java 21** baseline (see Prerequisites).

```bash
mvn test
# or
mvn clean verify
```

`mvn clean verify` is the same primary gate used for release validation ([`docs/testing.md`](docs/testing.md)). Without Docker, a small number of Testcontainers tests are skipped rather than failed.

Optional **public API compatibility** check against the japicmp baseline (`0.3.0` by default; property `aisentinel.api.compatibility.oldVersion`):

```bash
mvn -Papi-compatibility -pl ai-sentinel-core,ai-sentinel-spring-boot-starter -am verify -DskipTests
```

CI runs this profile after the main reactor verify. For the **0.4.0** line, japicmp compares published modules against **0.3.0** (`aisentinel.api.compatibility.oldVersion`). Prior intentional 0.2.0-era excludes were removed when that baseline was retargeted.

Running a single module in isolation only works when its dependencies are already installed with matching sources:

```bash
mvn -pl ai-sentinel-core test
# After core API changes, install core before an isolated starter run:
mvn install -pl ai-sentinel-core -DskipTests
mvn -pl ai-sentinel-spring-boot-starter test
```

Otherwise starter tests may fail discovery with `NoClassDefFoundError` for new core types (for example `HttpRequestView`).

If a failure is unclear, re-run with `-e` or `-X` for more Maven output, or run a single test class with `-Dtest=ClassName`.

Docker is optional; Testcontainers-based distributed quarantine tests are skipped when Docker is unavailable.

Regression scenarios for the decision path (enforcement actions, fail-open, adapter boundary) live in
`ai-sentinel-core` / starter test packages (`…regression…`, `ServletAdapterEndToEndRegressionTest`, ArchUnit).
See also [`ARCHITECTURE.md`](ARCHITECTURE.md) §14 Testing strategy.

---

## Run the demo

```bash
mvn -pl ai-sentinel-demo spring-boot:run
```

Optional Isolation Forest validation profile:

```bash
mvn -pl ai-sentinel-demo spring-boot:run -Dspring-boot.run.profiles=stage2
```

Python helpers (stdlib only): [`scripts/README.md`](scripts/README.md).

---

## Code style and commits

- Follow existing naming and packages (`dev.aisentinel.*`).
- Prefer focused changes; avoid unrelated refactors in the same PR.
- Match formatting and patterns used in nearby code.
- Keep public API changes minimal and backward compatible unless explicitly agreed (see **Breaking changes**).
- Commit subjects: clear, imperative (e.g. `fix: clarify cluster throttle timeout in README`). Squash noisy commits locally before push if needed.

---

## Pull requests

- Target **`dev`** unless your change is a maintainer-approved hotfix (see **Branching strategy**).
- Describe **what** changed and **why**; link issues.
- Ensure **`mvn clean verify`** passes from the repository root (the same gate CI runs).
- If you change `dotnet/` or the shared Java/.NET wire fixtures in `dotnet/fixtures/`, also run **`dotnet test`** ([`dotnet/README.md`](dotnet/README.md)).
- Update the canonical document for what changed (see [`docs/README.md`](docs/README.md)) — for example `docs/configuration.md` for properties or `ARCHITECTURE.md` for component boundaries — and link to it rather than copying explanations into several files.
- For breaking or deprecated behavior, follow the sections above.

---

## Public metadata and data restrictions

Everything committed to this repository is public. Do not commit or paste into issues, PRs, commit messages, or docs:

- secrets (API keys, pseudonymization secrets, tokens, credentials, private keys);
- real customer, user, or production traffic data, raw identities, IP addresses, or request bodies;
- internal tracking identifiers or private planning notes.

Evaluation and test data must be synthetic or privacy-safe. Do not modify accepted reference evidence (`evaluation/reference/`, `evaluation/detection-reference-baseline/`, `evaluation/kit-reference/`, `docs/performance/`) except through the documented regeneration or lifecycle scripts.

Public docs describe current, implemented behavior. Do not present unimplemented ideas as product capability.

---

## Evaluation and experiment contributions

- To reproduce or understand existing evaluation evidence, start with [`docs/evaluation/RUNNING_EVALUATIONS.md`](docs/evaluation/RUNNING_EVALUATIONS.md).
- To design a new local controlled experiment, start with [`docs/evaluation/CREATING_EXPERIMENTS.md`](docs/evaluation/CREATING_EXPERIMENTS.md).
- Local experiment inputs and outputs (scenario drafts, helper programs, generated corpora, reports, notes) normally stay outside the tracked tree.
- Experiment results are not product capabilities or accepted reference evidence.
- Ground truth stays in annotation sidecars and is never detector input.
- Adding tracked reference scenarios or corpora, changing accepted evidence, or changing schemas or contracts is a tracked contribution: open an issue first and follow the relevant contract document and regeneration or lifecycle process.
- Schema or contract changes must update the affected fixtures and pass `./scripts/validate-evaluation-kit-contracts.sh`.

---

## Safe Git practices

- Review `git status` and `git diff` before committing, and stage specific files rather than everything in the tree.
- Keep generated outputs (evaluation results, benchmark results, local notes) out of commits unless a document says they are tracked.
- Do not force-push or rewrite history on `main` or `dev`.
- Keep one logical change per PR so reviews stay focused.

---

## Working with a fork

If you fork this repository, **GitHub Actions** may fail on your fork (missing secrets, permissions, or org-only settings). To reduce noise:

1. **Settings → Actions → General** — choose **Disable actions** for the fork, or  
2. **Actions** tab — disable individual workflows you do not need.

You can still open pull requests against the upstream repository; CI runs there on the PR branch.

---

## Draft pull requests

Draft PRs are fine for work in progress or experiments. They may get less review until marked ready. Very old drafts may be closed by maintainers to keep the queue manageable; that is not a rejection—reopen or open a fresh PR when you are ready.

---

## Where to plug in new behavior

| Area | Extension | Notes |
|------|-----------|--------|
| **Scoring** | `FeatureExtractor`, `AnomalyScorer` / `CompositeScorer`, `IsolationForestScorer` | Hot path must stay bounded and non-blocking for scoring. |
| **Distributed** | `ClusterQuarantineReader` / `Writer`, `ClusterThrottleStore`, `TrainingCandidatePublisher` | Optional; fail-open; see [`ARCHITECTURE.md`](ARCHITECTURE.md). |
| **Trainer** | `ai-sentinel-trainer` module | Kafka when enabled; filesystem registry layout. |
| **Registry on nodes** | `ModelRegistryReader` | Refresh off-request; no registry I/O on the servlet thread. |

Use `@ConditionalOnMissingBean` where the starter already defines a bean so applications can override.

---

## Invariants (do not break)

1. **Request path** — No unbounded blocking or network I/O on the filter thread beyond documented timeouts (e.g. Redis for cluster features).
2. **Fail-open** — Optional distributed or training failures must not strand requests without documented behavior.
3. **Bounded memory** — Training buffers, caches, and queues stay capped.
4. **Local enforcement authority** — Cluster views are additive; local maps remain baseline unless documented otherwise.

Details: [`ARCHITECTURE.md`](ARCHITECTURE.md).
