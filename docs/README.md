# Docs layout

| Folder / file | Purpose |
|---------------|---------|
| `configuration.md` | Tracked property reference |
| `deployment.md` | Tracked deployment modes, adoption, and failure-mode profile |
| `migration.md` | Tracked upgrade guide from the previous published line |
| `testing.md` | Tracked characterization and release-gate testing |
| `planning/` | Local engineering notes (gitignored) |
| `detection/` | Local characterization evidence (gitignored) |
| `archive/` | Local historical notes (gitignored) |

Most of this tree is gitignored (`docs/*`). Only allowlisted root files are published.

**Suggested reading order for operators:** [`deployment.md`](deployment.md) → [`configuration.md`](configuration.md) → [`migration.md`](migration.md) when upgrading → [`testing.md`](testing.md) when validating a release build.
