# Kit Reference Evaluation Corpus

Repository-owned **versioned reference evaluation corpus** generated from Evaluation Kit scenario definitions via the deterministic corpus generator.

This is controlled **synthetic evaluation evidence**. It is **not** proof of production efficacy, production detection rates, external validation, or deployment readiness.

Historical seed artifacts remain at [`../reference/`](../reference/) and are intentionally separate.

## Layout

| Path | Purpose |
|------|---------|
| `generation-spec.json` | Declared generation inputs (scenario files, seeds, generator build identity, inventory version). |
| `scenarios/` | Version-controlled Scenario / Test Plan definitions. |
| `corpora/<name>/` | Generated artifacts per scenario: `events.jsonl`, `corpus-manifest.json`, `annotations.json`, replay-compatible `manifest.json`. |
| `inventory.json` | Derived inventory of actual generated corpora (checksums, counts, identities). |

## Concepts

```text
Scenario / Test Plan
    ↓
Deterministic Corpus Generator
    ↓
Generated Corpus (+ ground-truth sidecar)
    ↓
Inventory / checksums
```

- **Scenario ≠ Generator ≠ Corpus**
- **Ground truth ≠ detector input** (labels live only in `annotations.json`)
- **Baseline/warmup vs evaluation** phases are recorded in the annotation sidecar `category` field (for example `warmup` vs evaluation categories), not as scorer features
- Representation mode for this inventory: **feature-level**

## Reproduce / verify

From the repository root (JDK 21):

```bash
./scripts/verify-kit-reference-corpus.sh
```

Regenerate checked-in artifacts (maintainers only):

```bash
./scripts/verify-kit-reference-corpus.sh --write
```

A clean checkout should verify with **no diff**.

## What this proves / does not prove

**Proves:** deterministic generation, reproducibility under declared seeds/generator identity, ground-truth sidecars for authored conditions, replay load compatibility for these synthetic events.

**Does not prove:** production efficacy, real-world false-positive rates, external validation, MONITOR/ENFORCE readiness, or that anomalous conditions are malicious.
