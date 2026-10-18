# Northgate fictional organization-profile synthetic corpora

Versioned scenario definitions and deterministic generated corpora for the
**fictional** Northgate SaaS/API organization profile.

This inventory is synthetic evaluation material only. It does **not** represent a
real organization, customer, partner, or production deployment.

Guide: [`docs/evaluation/ORGANIZATION_PROFILE_SYNTHETIC_EVALUATION.md`](../../../docs/evaluation/ORGANIZATION_PROFILE_SYNTHETIC_EVALUATION.md).

## Layout

| Path | Role |
|------|------|
| `generation-spec.json` | Fixed seeds + `generatorBuildId` |
| `scenarios/*.json` | Scenario / Test Plan documents |
| `corpora/*/` | Generated Kit corpora (`events.jsonl`, manifests, annotations) |

## Evaluate

```bash
./scripts/evaluate-generated-corpus.sh \
  --corpus evaluation/organization-profile/northgate/corpora/northgate.established-normal \
  --output /tmp/northgate-established-normal-out
```
