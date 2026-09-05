# Docs layout

| Folder / file | Purpose |
|---------------|---------|
| `configuration.md` | Tracked property reference |
| `deployment.md` | Tracked deployment modes, adoption, and failure-mode profile |
| `migration.md` | Tracked upgrade guide (0.2.x → 0.3.0) |
| `testing.md` | Tracked characterization and release-gate testing |
| [`../dotnet/README.md`](../dotnet/README.md) | ASP.NET Core reference adapter (remote client; not gitignored) |
| `planning/` | Local engineering notes (gitignored) |
| `detection/` | Local characterization evidence (gitignored) |
| `archive/` | Local historical notes (gitignored) |

Most of this tree is gitignored (`docs/*`). Only allowlisted root files are published.

**Also at the repository root:** [`CHANGELOG.md`](../CHANGELOG.md) · [`ARCHITECTURE.md`](../ARCHITECTURE.md) · [`SECURITY.md`](../SECURITY.md) · [`RELEASING.md`](../RELEASING.md) · [`CONTRIBUTING.md`](../CONTRIBUTING.md)

**Suggested reading order for operators:** [`deployment.md`](deployment.md) → [`configuration.md`](configuration.md) → [`migration.md`](migration.md) when upgrading → [`testing.md`](testing.md) when validating a release build.

Current published library line: **0.3.0** ([release notes](https://github.com/CollinKabwama/ai-sentinel/releases/tag/v0.3.0)).

For how the **Java decision core** relates to the **Spring Boot / Servlet** adapter, see [`../ARCHITECTURE.md`](../ARCHITECTURE.md) (security model vs core vs current adapter).
