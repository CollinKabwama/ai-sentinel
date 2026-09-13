# Docs layout

| Folder / file | Purpose |
|---------------|---------|
| `configuration.md` | Tracked property reference |
| `deployment.md` | Tracked deployment modes, adoption, and failure-mode profile |
| `migration.md` | Tracked upgrade guide (0.2.x → 0.3.0) |
| `testing.md` | Tracked characterization and release-gate testing |
| [`contracts/`](contracts/FEATURE_SCHEMA.md) | Versioned feature-schema, evaluation-event, and dataset-export contract documentation |
| [`performance/`](performance/BENCHMARKING.md) | Tracked JMH benchmark foundation + [0.3.0 reference baseline](performance/REFERENCE_BASELINE.md) (not an SLA) |
| [`../evaluation/`](../evaluation/DETECTION_EVALUATION.md) | Offline Detection Evaluation Framework plus [Official Detection Reference Baseline](../evaluation/DETECTION_REFERENCE_BASELINE.md) initial capture (not production efficacy / not a quality gate). |
| [`../dotnet/README.md`](../dotnet/README.md) | ASP.NET Core reference adapter (remote client; not gitignored) |
| `planning/` | Local planning notes remain gitignored unless separately allowlisted |
| `detection/` | Local characterization evidence (gitignored) |
| `archive/` | Local historical notes (gitignored) |

Most of this tree is gitignored (`docs/*`). Allowlisted root files plus **`docs/performance/`** are published.

**Also at the repository root:** [`CHANGELOG.md`](../CHANGELOG.md) · [`ARCHITECTURE.md`](../ARCHITECTURE.md) · [`SECURITY.md`](../SECURITY.md) · [`RELEASING.md`](../RELEASING.md) · [`CONTRIBUTING.md`](../CONTRIBUTING.md)

**Offline evaluation docs:** [`../evaluation/REFERENCE_DATASET.md`](../evaluation/REFERENCE_DATASET.md) · [`../evaluation/DETERMINISTIC_REPLAY.md`](../evaluation/DETERMINISTIC_REPLAY.md) · [`../evaluation/DETECTION_EVALUATION.md`](../evaluation/DETECTION_EVALUATION.md)

**Suggested reading order for operators:** [`deployment.md`](deployment.md) → [`configuration.md`](configuration.md) → [`migration.md`](migration.md) when upgrading → [`testing.md`](testing.md) when validating a release build.

Current published library line: **0.3.0** ([release notes](https://github.com/CollinKabwama/ai-sentinel/releases/tag/v0.3.0)).

For how the **Java decision core** relates to the **Spring Boot / Servlet** adapter, see [`../ARCHITECTURE.md`](../ARCHITECTURE.md) (security model vs core vs current adapter).
