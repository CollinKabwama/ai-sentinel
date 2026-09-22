# Same-framework alternative detector comparison (Level-3)

Offline comparison of two **same-origin** anomaly detectors on controlled
Evaluation Kit reference corpora:

1. Statistical scorer (existing Evaluation Kit reference path)
2. Offline Isolation Forest reference scorer (`isolation-forest-reference`)

## Same-origin disclosure

Both detectors are implemented in the AI-Sentinel codebase.

This comparison demonstrates Evaluation Kit mechanics and algorithmic behavior on
identical controlled inputs. It is **not** an independent third-party benchmark.

## What this is

AI-Sentinel's Evaluation Kit can deterministically compare two methodologically
distinct anomaly-detection approaches on the same controlled input.

Outputs are factual metric and prediction deltas only.

## What this is not

- a winner / ranking / superiority claim
- an independent third-party benchmark
- production efficacy proof
- MONITOR or ENFORCE readiness
- a general multi-detector benchmarking framework

## Frozen Isolation Forest configuration

Selected **before** comparative metrics were inspected. Defaults align with
existing Isolation Forest property defaults where applicable:

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| `scorerId` | `isolation-forest-reference` | Distinct offline identity |
| `scorerVersion` | `1.0.0` | Durable offline package version |
| `numTrees` | `100` | Existing Isolation Forest default |
| `maxDepth` | `10` | Existing Isolation Forest default |
| `randomSeed` | `42` | Existing Isolation Forest default |
| `minTrainingSamples` | `4` | Fixed count matching kit-reference warmup event count for the three scenarios; **not** read from ground-truth labels at runtime |
| `fallbackScore` | `0.3` | Pre-model score chosen below the replay elevated threshold (`0.4`) so state-building updates remain allowed under `ALLOW_OR_MONITOR` (MONITOR band). Not tuned against comparative metrics. |
| anomaly threshold | `0.5` | Detector-native IF decision boundary from the implemented formula `score = 2^(-avgPathLength / c(n))` (`IsolationForestModel.score`): the score equals exactly `0.5` when the observed average path length equals `c(n)`, the sample-size-adjusted expected path length for random data — the algorithm's own neutral point (Liu et al., 2008 convention), independent of `n`. It is not "average path length ≈ 0.5" (path length and score are different quantities); it is chosen because it is this formula's native neutral point, not merely because the statistical path also uses `0.5`. |

Feature projection: **statistical 6-vector** via `RequestFeatures.toStatisticalArray()`
(same ordered features as the statistical detector). Production
`toIsolationForestArray()` (5 features) is **not** used.

Training: first `minTrainingSamples` detector-visible `update()` observations only;
model then frozen. No label-driven sample selection. No post-result fitting.

### Known limitation: tiny/homogeneous training population

With `minTrainingSamples = 4` and warmup events whose six-feature vectors are
near-identical (by scenario design — warmup exists to establish a stable
baseline), most or all of the 100 trees terminate at a single root-level leaf
containing all 4 training points before any split is possible. When every tree
resolves to that same leaf, every subsequently scored event — regardless of its
own feature values — receives the identical average path length, and therefore
the identical score. In the three kit-reference scenarios this is observed
directly as a score of exactly `0.500000` for every post-warmup event in all
three scenarios, independent of whether that event's true class is benign or
anomalous. This is a real, deterministic, mathematically-explained consequence
of Isolation Forest's known sensitivity to very small or low-variance training
populations — not an implementation defect, not a coerced/fabricated value, and
not specific to this offline wrapper (the same formula and trainer are used
unmodified in production). It means the Level-3 Isolation Forest results on
these three tiny corpora demonstrate evaluation *mechanics* (deterministic
replay, feature parity, identity binding, factual comparison reporting) rather
than genuine multivariate anomaly discrimination — the model has too little
and too homogeneous training data to discriminate at all. This is disclosed
here deliberately rather than remediated by enlarging `minTrainingSamples`,
which would be tuning the configuration after observing comparative results.

## Scenarios

Exactly:

- `kit.established-normal`
- `kit.abrupt-burst`
- `kit.recovery`

## Command

From the repository root (JDK 21 + Maven):

```bash
./scripts/compare-reference-detectors.sh --output /path/to/new-results-directory
```

The output directory must not already exist.

## Output layout

```text
<output>/
  established-normal/
    statistical/                     # five Evaluation Kit artifacts
    isolation-forest-reference/      # five Evaluation Kit artifacts
    comparison/                      # comparison.json + comparison.html
  abrupt-burst/
    ...
  recovery/
    ...
```

Open each target's `comparison/comparison.html` for human inspection.
Machine comparison is `comparison/comparison.json`.

## Comparison language

Allowed: factual TP/TN/FP/FN, prediction transitions, score differences, config
differences.

Not allowed: winner, better, worse, superior, best, recommended detector,
ranking, or benchmark champion language.

## Hardening deferred

Not in this capability: additional detectors, threshold sweeps, ROC/AUC suites,
hyperparameter search, significance testing, external benchmark datasets,
cross-validation, confidence intervals, or a general plugin benchmarking UI.
