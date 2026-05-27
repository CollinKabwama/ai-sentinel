# Contributing to AI-Sentinel

Thanks for helping improve AI-Sentinel. Bug-fix pull requests are always welcome.

For **new features** or **non-trivial behavior changes**, open an issue first so maintainers and users can discuss scope, defaults, and compatibility:

[Open an issue](https://github.com/CollinKabwama/ai-sentinel/issues/new)

Contributions should match the project’s style: **small, reviewable changes**; **tests** when behavior changes; **documentation** when user-visible behavior or configuration changes.

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
| **ai-sentinel-core** | Framework-agnostic engine: features, scorers, policy, enforcement, pipeline contracts, Isolation Forest training/scoring, model artifact types (`io.aisentinel.model.*`). |
| **ai-sentinel-spring-boot-starter** | Spring Boot auto-configuration, servlet filter, `SentinelProperties`, actuator, Micrometer adapter, optional distributed and model-registry beans. |
| **ai-sentinel-trainer** | Optional standalone Spring Boot app: consumes training candidates (Kafka when enabled), trains IF, publishes to a filesystem model registry. See [`ai-sentinel-trainer/README.md`](ai-sentinel-trainer/README.md). |
| **ai-sentinel-demo** | Reference app for local runs and smoke tests. |

---

## Where to start

1. **`SentinelPipeline`** — [`ai-sentinel-core/.../SentinelPipeline.java`](ai-sentinel-core/src/main/java/io/aisentinel/core/SentinelPipeline.java) — extract → score → policy → enforce → telemetry and optional training publish.
2. **`SentinelFilter`** — [`ai-sentinel-spring-boot-starter/.../SentinelFilter.java`](ai-sentinel-spring-boot-starter/src/main/java/io/aisentinel/autoconfigure/web/SentinelFilter.java) — servlet entry point.
3. **`SentinelAutoConfiguration`** — [`.../SentinelAutoConfiguration.java`](ai-sentinel-spring-boot-starter/src/main/java/io/aisentinel/autoconfigure/config/SentinelAutoConfiguration.java) — beans and `@ConditionalOnMissingBean` extension points.

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

Maintainers merge `dev` → `main` and tag releases. Contributors do not manage releases.

---

## Prerequisites

- **Java 21** — required by the root `pom.xml` (`<java.version>21</java.version>`). Newer JDKs may work locally; align with CI when in doubt.
- **Maven 3.8+**
- **Docker** — optional; needed for Testcontainers-based tests in `ai-sentinel-spring-boot-starter` (those tests are skipped when Docker is unavailable).

---

## Building

From the repository root:

```bash
java -version   # expect 21
mvn clean install
```

To consume a **local snapshot** in another project, install to your local repository (`~/.m2/repository`) with the command above, then depend on `io.aisentinel:ai-sentinel-spring-boot-starter:1.0.0-SNAPSHOT` (or the specific module you need). There is no separate public snapshot hosting documented in this repo; releases are via tags on `main` when published.

---

## Running the tests

```bash
mvn test
```

Run the full suite before opening a PR. To narrow scope while iterating:

```bash
mvn -pl ai-sentinel-core test
mvn -pl ai-sentinel-spring-boot-starter test
```

If a failure is unclear, re-run with `-e` or `-X` for more Maven output, or run a single test class with `-Dtest=ClassName`.

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

- Follow existing naming and packages (`io.aisentinel.*`).
- Prefer focused changes; avoid unrelated refactors in the same PR.
- Match formatting and patterns used in nearby code.
- Keep public API changes minimal and backward compatible unless explicitly agreed (see **Breaking changes**).
- Commit subjects: clear, imperative (e.g. `fix: clarify cluster throttle timeout in README`). Squash noisy commits locally before push if needed.

---

## Pull requests

- Target **`dev`** unless your change is a maintainer-approved hotfix (see **Branching strategy**).
- Describe **what** changed and **why**; link issues.
- Ensure **`mvn test`** passes.
- Update **README**, **ARCHITECTURE.md**, **`docs/configuration.md`**, and module READMEs when behavior or configuration changes.
- For breaking or deprecated behavior, follow the sections above.

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
