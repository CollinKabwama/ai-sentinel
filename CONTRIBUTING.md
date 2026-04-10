# Contributing to AI-Sentinel

Thanks for helping improve AI-Sentinel. Contributions should match the project’s engineering style: small, reviewable changes; tests when behavior changes; documentation updates when user-visible behavior or configuration changes.

---

## Project structure

| Module | Purpose |
|--------|---------|
| **ai-sentinel-core** | Framework-agnostic engine: features, scorers, policy, enforcement, pipeline contracts, Isolation Forest training/scoring, model artifact types (`io.aisentinel.model.*`). |
| **ai-sentinel-spring-boot-starter** | Spring Boot auto-configuration, servlet filter, `SentinelProperties`, actuator, Micrometer adapter, optional distributed and model-registry beans. |
| **ai-sentinel-trainer** | Optional standalone Spring Boot app: consumes training candidates (Kafka when enabled), trains IF, publishes to a filesystem model registry. See [`ai-sentinel-trainer/README.md`](ai-sentinel-trainer/README.md). |
| **ai-sentinel-demo** | Reference app for local runs and smoke tests. |

---

## Where to start

When reading the codebase:

1. **`SentinelPipeline`** ([`ai-sentinel-core`](ai-sentinel-core/src/main/java/io/aisentinel/core/SentinelPipeline.java)) — orchestrates extract → score → policy → enforce → telemetry and optional training publish.
2. **`SentinelFilter`** ([`ai-sentinel-spring-boot-starter`](ai-sentinel-spring-boot-starter/src/main/java/io/aisentinel/autoconfigure/web/SentinelFilter.java)) — servlet entry point that invokes the pipeline once per request.
3. **`SentinelAutoConfiguration`** ([`ai-sentinel-spring-boot-starter`](ai-sentinel-spring-boot-starter/src/main/java/io/aisentinel/autoconfigure/config/SentinelAutoConfiguration.java)) — registers beans and `@ConditionalOnMissingBean` extension points.

Then see [`ARCHITECTURE.md`](ARCHITECTURE.md) for the full picture.

---

## Branching strategy

| Branch | Purpose |
|--------|---------|
| **`main`** | Stable, release-quality code. Tagged for releases. Not a direct PR target for normal work. |
| **`dev`** | Active integration branch. Default target for all pull requests. |

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

If a maintainer designates an issue as:

- hotfix
- security
- release-blocker

Then:

- branch from `main`
- PR into `main`
- maintainer merges `main` back into `dev`

### Releases

Maintainers:

- merge `dev` → `main`
- tag release (e.g. `v0.2.0`)

Contributors do NOT manage releases.

---

## Prerequisites

- **Java 21** (see root `pom.xml`)
- **Maven 3.8+**
- **Docker** — optional; required only to run Testcontainers-based tests in `ai-sentinel-spring-boot-starter` (skipped when Docker is unavailable)

---

## Build

From the repository root:

```bash
mvn clean install
```

---

## Tests

```bash
mvn test
```

Run tests before opening a PR. Distributed Redis tests need Docker when enabled.

---

## Run the demo

```bash
mvn -pl ai-sentinel-demo spring-boot:run
```

Optional IF-focused profile:

```bash
mvn -pl ai-sentinel-demo spring-boot:run -Dspring-boot.run.profiles=stage2
```

---

## Code style

- Follow existing naming and package layout (`io.aisentinel.*`).
- Prefer focused changes; avoid unrelated refactors in the same PR.
- Match formatting and patterns used in nearby code.
- Keep public API changes minimal and backward compatible unless explicitly agreed.

---

## Commits

- Use clear, imperative subject lines (e.g. `fix: clarify cluster throttle timeout in README`).
- Avoid noisy or unrelated commits; squash locally if needed before push.

---

## Pull requests

- Target the **`dev`** branch unless your change is a maintainer-approved hotfix (see **Branching strategy** above).
- Describe **what** changed and **why** (short summary is enough).
- Link related issues if any.
- Ensure `mvn test` passes.
- Update **README**, **ARCHITECTURE**, **`docs/configuration.md`**, and module READMEs when behavior or configuration changes.

---

## Where to plug in new behavior

| Area | Extension | Notes |
|------|-----------|--------|
| **Scoring** | `FeatureExtractor`, `AnomalyScorer` / `CompositeScorer`, `IsolationForestScorer` | Hot path must stay bounded and non-blocking for scoring. |
| **Distributed** | `ClusterQuarantineReader` / `Writer`, `ClusterThrottleStore`, `TrainingCandidatePublisher` | Optional; fail-open; see [`ARCHITECTURE.md`](ARCHITECTURE.md) §10. |
| **Trainer** | Separate module; consumes Kafka when `aisentinel.trainer.kafka.enabled=true` | Publishes to filesystem layout consumed by `ModelRegistryReader` on nodes. |
| **Registry on nodes** | `ModelRegistryReader` (default: `FilesystemModelRegistry` when auto-config applies) | Refresh is off-request; no registry I/O on the servlet thread. |

Use `@ConditionalOnMissingBean` where the starter already defines a bean so applications can override.

---

## Invariants (do not break)

1. **Request path** — Scoring and enforcement must not perform unbounded blocking or network I/O that can stall the filter thread beyond documented timeouts (e.g. Redis lookups for cluster features).
2. **Fail-open** — When optional distributed or training features fail, the request should still be handled per policy where the code documents fail-open behavior.
3. **Bounded memory** — Training buffers, caches, and async queues must remain capped; no unbounded growth on the hot path.
4. **Local enforcement authority** — Cluster quarantine/throttle views are additive; local maps and decisions remain the baseline unless documented otherwise.

Details: [`ARCHITECTURE.md`](ARCHITECTURE.md).
