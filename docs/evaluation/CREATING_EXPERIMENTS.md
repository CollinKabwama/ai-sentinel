# Creating an AI-Sentinel evaluation experiment

This guide answers: **how can a contributor create a controlled, reproducible evaluation experiment using the existing Evaluation Kit machinery?**

You will write a scenario, generate a synthetic corpus with ground truth, evaluate it, inspect individual events, compare variants fairly, and record enough to reproduce the result. The basic workflow does not change AI-Sentinel code.

This guide is not a deployment guide, not a MONITOR pilot guide, and not permission to change reference corpora or accepted baselines. For reading evaluation outputs and rerunning existing evidence, see [`RUNNING_EVALUATIONS.md`](RUNNING_EVALUATIONS.md).

`EXPERIMENT RESULT != PRODUCT CAPABILITY`. An experiment describes behavior on a controlled corpus; it does not establish a new capability or accepted evidence.

---

## Local experiment work vs. tracked contributions

> **Local experiment work** normally stays outside the tracked repository:
> scenario drafts, the generator helper program, generated corpora, evaluation outputs, and your notes or analysis.
>
> **Tracked contributions** are a different process: a new reference scenario or accepted corpus, a schema or contract change, a scorer implementation, or a change to reference evidence. Open an issue first and follow [`CONTRIBUTING.md`](../../CONTRIBUTING.md#evaluation-and-experiment-contributions) and the relevant contract document.

---

## Two ways to supply experiment data

| Path | Use when | Guide |
|------|----------|-------|
| Scenario → generated corpus | You want deterministic synthetic data from a declared scenario and seed, with ground truth generated alongside | This guide |
| Evaluator-provided dataset | You already have detector-facing event data (and optionally labels) and do not need the generator | [`EVALUATOR_PROVIDED_DATASET.md`](../contracts/EVALUATOR_PROVIDED_DATASET.md) |

Both paths are evaluated by the same `scripts/evaluate-generated-corpus.sh` (`--corpus` or `--dataset`) and produce the same report set. The methodology sections below (hypothesis, controls, comparison, reproducibility) apply to both.

---

## 1. What you will build

1. A research question with a threat hypothesis and a benign-lookalike hypothesis.
2. A scenario (test plan) JSON file.
3. A generated corpus: detector-facing events, manifests, and a ground-truth sidecar.
4. At least one evaluation run with HTML and JSON evidence.
5. Event-level inspection of TP, FP, TN, and FN outcomes.
6. A fair comparison (threshold, scenario variants, or supported detector paths).
7. A reproducibility record (commit, seed, commands, digests).

---

## 2. Prerequisites

| Tool | Why | Check |
|------|-----|-------|
| JDK 21 | `pom.xml` targets Java 21; the generator helper compiles against `ai-sentinel-core`; Level-3 comparison requires `java` 21 on `PATH` | `java -version` |
| Maven 3.x | Builds the `ai-sentinel-core` classpath | `mvn -version` |
| Git | Clone and record the commit | `git --version` |
| Bash | Scripts under `scripts/` | `bash --version` |

Optional: Python 3 for `scripts/validate-evaluation-kit-contracts.sh`; Docker for the Evaluation Kit container.

Ensure **both `java -version` and `mvn -version` report JDK 21**; they can resolve different runtimes. macOS example:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
mvn -version
```

---

## 3. Set up the repository and a scratch workspace

```bash
git clone https://github.com/CollinKabwama/ai-sentinel.git
cd ai-sentinel
git rev-parse HEAD
mvn -pl ai-sentinel-core -DskipTests compile
```

Corpus generation and replay depend on the code, so two people with the same scenario and seed but different commits can get different corpora or scores. The commit SHA is part of the experiment's identity.

Create a workspace outside the repository:

```bash
export EXP="${TMPDIR:-/tmp}/ai-sentinel-experiment/normal-vs-burst"
mkdir -p "$EXP"
```

Never write experiment files or outputs under `evaluation/detection-reference-baseline/`, `evaluation/reference/`, `docs/performance/`, or `evaluation/kit-reference/`. See [Protected evidence](RUNNING_EVALUATIONS.md#protected-evidence).

---

## 4. The experiment pipeline

```text
Research question / hypothesis
        ↓
Scenario (test plan JSON)
        ↓
Corpus generator (CorpusGenerator Java API)
        ↓
Generated corpus (events + manifests + annotations sidecar)
        ↓
Replay + scorer (offline; the Kit CLI uses the statistical scorer)
        ↓
Evaluation (classification + metrics)
        ↓
JSON / Markdown / HTML / event inspection
```

| Distinction | Meaning |
|-------------|---------|
| Scenario ≠ generator | The scenario is the authored definition; the generator is the code that compiles it |
| Generator ≠ corpus | The generator is code; the corpus is the concrete artifact directory |
| `GROUND TRUTH != DETECTOR INPUT` | `events.jsonl` holds detector-facing events with **no labels**; `annotations.json` is the ground-truth sidecar. Labels are joined by `eventId` during evaluation and are never seen by the scorer while scoring |
| `EVALUATION != DEPLOYMENT` | Offline evidence is not `MONITOR` or `ENFORCE` readiness |
| `SYNTHETIC != PRODUCTION VALIDATION` | Controlled corpora do not prove production efficacy |

The normative contracts are in [`EVALUATION_KIT.md`](../contracts/EVALUATION_KIT.md).

---

## 5. Start with a question and a hypothesis

**Do not create data first and then look for a security story.** Write the question and both hypotheses before generating anything.

**Threat hypothesis** (example):

> A rapid rise in `requestsPerWindow` and endpoint concentration for one synthetic identity may become distinguishable from that identity's established baseline after a short warmup.

**Benign-lookalike hypothesis** (example):

> A legitimate batch or integration job could produce a similar short burst after an idle period. Any detection must be interpreted as synthetic distinguishability, not proven maliciousness.

Keep these in `$EXP/notes/hypothesis.md`.

Before generating, also record:

- **Primary independent variable** — the one thing you intentionally change.
- **Controlled variables** — everything that should stay fixed.

For seeded synthetic experiments the controls normally include scenario family, seed, scorer, threshold, warmup, population, endpoint set, feature schema, ground-truth policy, and generator build identity. If one of them changes on purpose, state that it is part of the experiment.

### Controlled-seed rule

For a variant ladder, keep the generator seed **fixed** unless the seed itself is under study:

| Variant | Primary independent variable | Seed |
|---------|------------------------------|------|
| duration 4s | evaluation duration | `example-seed-001` |
| duration 6s | evaluation duration | `example-seed-001` |
| duration 8s | evaluation duration | `example-seed-001` |

For stronger robustness evidence, use matched seed families:

```text
seed A: 4s / 6s / 8s
seed B: 4s / 6s / 8s
seed C: 4s / 6s / 8s
```

Do not compare `4s + seed A`, `6s + seed B`, `8s + seed C` and describe duration as the only independent variable, unless generator behavior has separately been shown not to depend on the seed.

---

## 6. Design normal and anomalous behavior

For the worked example (family `abrupt-burst`):

| Phase | Intent | Behavior |
|-------|--------|----------|
| Warmup | Establish a baseline | Steady low-rate traffic; excluded from binary metrics |
| Early evaluation | Legitimate | Normal behavior continues |
| Burst | Anomalous (authored) | One identity bursts toward one endpoint |

A scenario must declare `metadata.family`; generation fails without it. Supported families:

| Family | Typical use |
|--------|-------------|
| `established-normal` | Benign-only baseline stability |
| `warmup-cold-start` | Cold-start behavior |
| `abrupt-burst` / `warmup-then-burst` | Sudden rate burst |
| `legitimate-burst` | Burst labeled legitimate (lookalike) |
| `endpoint-distribution-change` | Endpoint mix shift |
| `low-variance-deviation` | Subtle deviations |
| `gradual-drift` | Slow change |
| `multi-feature-anomaly` | Multi-signal anomaly |
| `recovery` | Anomaly, then return to normal |
| `invalid-score-degradation` | Invalid score path |
| `identity-session-transition` | Identity or session transition |

The generator produces feature-level events using the canonical feature schema version `"1"`. Feature meanings are defined in [`FEATURE_SCHEMA.md`](../contracts/FEATURE_SCHEMA.md) and the event format in [`EVALUATION_EVENT.md`](../contracts/EVALUATION_EVENT.md); any line of `evaluation/kit-reference/corpora/kit.abrupt-burst/events.jsonl` is a concrete example.

---

## 7. Author the scenario

Authoring a scenario requires no Java changes.

```bash
mkdir -p "$EXP/scenario"
```

Write `$EXP/scenario/experiment.normal-vs-burst.v1.json`:

```json
{
  "scenarioSchemaVersion": "1",
  "scenarioId": "experiment.normal-vs-burst.v1",
  "scenarioVersion": "1.0.0",
  "featureSchemaVersion": "1",
  "title": "Experiment: established normal vs request burst",
  "description": "Local evaluation experiment. Synthetic only. Not a reference corpus.",
  "population": {
    "identityCount": 3,
    "identityKeyPrefix": "exp-id-",
    "notes": "Pseudonymous identities only."
  },
  "normalBehavior": {
    "summary": "Steady low-rate requests across a small endpoint set during warmup and early evaluation.",
    "endpointKeys": ["/api/a", "/api/b"]
  },
  "timing": {
    "warmup": { "durationSeconds": 4, "notes": "Baseline formation window." },
    "evaluation": { "durationSeconds": 6, "notes": "Includes abrupt burst." }
  },
  "transitions": [
    {
      "transitionId": "t-normal",
      "intent": "legitimate",
      "summary": "Continue normal behavior after warmup.",
      "startsAfterWarmupSeconds": 0
    },
    {
      "transitionId": "t-burst",
      "intent": "anomalous",
      "summary": "Single identity bursts to one endpoint.",
      "startsAfterWarmupSeconds": 2
    }
  ],
  "evaluationExpectations": {
    "notes": "Evaluation-side only. Must not tune the subject under test.",
    "assertions": [
      {
        "assertionId": "a-burst-present",
        "kind": "structural",
        "statement": "Evaluation window includes anomalous burst events for the primary identity."
      }
    ]
  },
  "metadata": {
    "family": "abrupt-burst",
    "authoringNote": "Local experiment"
  }
}
```

Fields the generator reads:

| Field | Required | Meaning |
|-------|----------|---------|
| `scenarioSchemaVersion` | Yes | Must be `"1"` |
| `scenarioId` | Yes | Experiment identity |
| `scenarioVersion` | Yes | Scenario revision |
| `featureSchemaVersion` | Yes | Must be `"1"` |
| `population.identityCount` | Yes | At least 1 |
| `population.identityKeyPrefix` | No | Synthetic identity prefix |
| `normalBehavior.summary` | Yes | Human summary |
| `normalBehavior.endpointKeys` | No | Endpoint set |
| `timing.warmup.durationSeconds` | Yes | At least 0 |
| `timing.evaluation.durationSeconds` | Yes | At least 1 |
| `transitions[]` | Yes (at least one) | `transitionId`, `intent`, `summary`; optional `startsAfterWarmupSeconds` |
| `transitions[].intent` | Yes | `legitimate`, `anomalous`, `mixed`, or `unspecified` |
| `metadata.family` | Yes | One of the supported families |

The machine schema is [`scenario.schema.json`](../contracts/schemas/evaluation-kit/scenario.schema.json). `evaluationExpectations` is allowed by the schema but is not read during generation and must never configure the scorer or threshold.

A tracked template to copy from (do not edit it): `evaluation/kit-reference/scenarios/kit.abrupt-burst.v1.json`.

---

## 8. Generate the corpus

The corpus generator is currently exposed as a Java API, `dev.aisentinel.core.dataset.corpus.CorpusGenerator`. The minimal local program below invokes it for an experiment.

- Keep it in your experiment workspace (`$EXP`), not in the repository.
- It is experiment tooling, not production code; do not add it under `ai-sentinel-core`.
- It does not modify AI-Sentinel; it only calls the public generator API.

`scripts/verify-kit-reference-corpus.sh` is not a general generator: it verifies (or, for maintainers, rewrites) only the checked-in `evaluation/kit-reference/` inventory.

Save as `$EXP/GenerateExperimentCorpus.java`:

```java
import dev.aisentinel.core.dataset.corpus.CorpusGenerator;
import java.nio.file.Path;

public class GenerateExperimentCorpus {
  public static void main(String[] args) throws Exception {
    if (args.length != 4) {
      System.err.println("Usage: GenerateExperimentCorpus <scenario.json> <seed> <generatorBuildId> <outputDir>");
      System.exit(2);
    }
    var generated = CorpusGenerator.generate(
        Path.of(args[0]), args[1], args[2], Path.of(args[3]));
    CorpusGenerator.verifyChecksums(generated);
    System.out.println("corpusId=" + generated.corpusId());
    System.out.println("output=" + args[3]);
  }
}
```

Compile and run it from the repository root:

```bash
CP_FILE="$EXP/classpath.txt"
mvn -q -pl ai-sentinel-core -DskipTests compile dependency:build-classpath \
  -Dmdep.outputFile="$CP_FILE"
JAVA_CP="ai-sentinel-core/target/classes:$(cat "$CP_FILE")"

mkdir -p "$EXP/classes"
javac --release 21 -cp "$JAVA_CP" -d "$EXP/classes" "$EXP/GenerateExperimentCorpus.java"

java -cp "$EXP/classes:$JAVA_CP" GenerateExperimentCorpus \
  "$EXP/scenario/experiment.normal-vs-burst.v1.json" \
  "example-seed-001" \
  "local-experiment-generator" \
  "$EXP/corpus"
```

| Argument | Meaning | Example |
|----------|---------|---------|
| `scenario.json` | Scenario path | `$EXP/scenario/experiment.normal-vs-burst.v1.json` |
| `seed` | Determinism binder | `example-seed-001` |
| `generatorBuildId` | Identity of the generator build you used | `local-experiment-generator` |
| `outputDir` | New directory for the corpus | `$EXP/corpus` |

The program prints the generated `corpusId`. The corpus directory contains:

| File | Role |
|------|------|
| `events.jsonl` | Detector-facing evaluation events (no labels) |
| `annotations.json` | Ground-truth sidecar |
| `corpus-manifest.json` | Generation provenance and checksums |
| `manifest.json` | Replay-compatible dataset manifest |

---

## 9. Ground truth

For the supported families, **ground truth is generated with the corpus**; you normally do not hand-write `annotations.json`. Its format is defined by [`ground-truth.schema.json`](../contracts/schemas/evaluation-kit/ground-truth.schema.json) and [`EVALUATION_KIT.md`](../contracts/EVALUATION_KIT.md#7-ground-truth--annotation-contract).

Each annotation has an `eventId` (the join key into `events.jsonl`), a `scenarioId`, an `expectedClass`, and a `category`:

| Value | Role in binary metrics |
|-------|------------------------|
| `expectedClass: benign` | Negative class when included |
| `expectedClass: anomalous` | Positive class when included |
| `expectedClass: unknown` / `unlabeled` | Replayed; excluded from the confusion matrix |
| `category: warmup` | Replayed to build state; excluded from binary metrics even when `benign` |

Never add labels to events. `GROUND TRUTH != DETECTOR INPUT`.

---

## 10. Validate the corpus

A private experiment is validated in layers:

1. The scenario is parsed during `CorpusGenerator.generate(...)`; invalid scenarios fail generation.
2. `CorpusGenerator.verifyChecksums(...)` checks the written artifacts (the helper already calls it).
3. `evaluate-generated-corpus.sh` checks manifests, checksums, and corpus integrity when it loads the corpus.
4. The ground-truth join and metric construction run during evaluation.

A failure in layers 3 or 4 prints `ERROR [<FAILURE_KIND>]: …` and exits with status `1`.

`./scripts/validate-evaluation-kit-contracts.sh` validates the **repository's** schemas and fixtures. It is not a validator for arbitrary private scenarios in `$EXP`. Likewise, `./scripts/verify-kit-reference-corpus.sh` checks only the checked-in reference inventory.

---

## 11. Evaluate

First run a sanity check on a tracked corpus, then your own:

```bash
./scripts/evaluate-generated-corpus.sh \
  --corpus evaluation/kit-reference/corpora/kit.abrupt-burst \
  --output "$EXP/runs/kit-abrupt-burst-statistical" \
  --threshold 0.5

./scripts/evaluate-generated-corpus.sh \
  --corpus "$EXP/corpus" \
  --output "$EXP/runs/statistical" \
  --threshold 0.5
```

- Always pass a new `--output`; without it the evidence goes to a temporary directory that is deleted.
- The CLI always uses the statistical scorer. There is no `--scorer`, `--seed`, `--model`, or `--family` option.
- Relative paths resolve against your current directory.

All options, exit codes, and output artifacts are defined in [`EVALUATION_KIT.md`](../contracts/EVALUATION_KIT.md#one-command-evaluation-cli).

---

## 12. Inspect the results

Open `$EXP/runs/statistical/evaluation-report.html`, then `event-inspection.json`. How to read the report sections, participation values, outcomes, and scores is explained in [How to read an evaluation](RUNNING_EVALUATIONS.md#how-to-read-an-evaluation).

For an experiment, walk at least one event of each outcome that occurs:

- **False positive** (benign, predicted anomalous): inspect the features, the threshold, and whether the benign behavior is truly normal under your hypothesis.
- **False negative** (anomalous, predicted benign): inspect whether the authored anomaly is visible in the features and whether the scorer's state can see it.

Different seeds or identities within the same family can produce different outcomes; the same family does not guarantee the same detectability. Record the seed and inspect the features before revising your hypothesis.

---

## 13. Compare fairly

Change **one** axis per comparison and hold everything else constant: corpus bytes and `corpusId`, annotations, event order, warmup exclusion, seed and generator build (for regeneration), and all evaluation settings except the axis under study.

### Compare thresholds on the same corpus

```bash
./scripts/evaluate-generated-corpus.sh --corpus "$EXP/corpus" \
  --output "$EXP/runs/thresh-0.4" --threshold 0.4
./scripts/evaluate-generated-corpus.sh --corpus "$EXP/corpus" \
  --output "$EXP/runs/thresh-0.8" --threshold 0.8

./scripts/compare-evaluations.sh \
  --baseline "$EXP/runs/thresh-0.4" \
  --candidate "$EXP/runs/thresh-0.8" \
  --output "$EXP/runs/compare-threshold"
```

Open `$EXP/runs/compare-threshold/comparison.html`. `compare-evaluations.sh` requires both runs to have the same corpus identity.

### Compare scenario variants

Independently generated variants (for example duration 4s vs 6s vs 8s, burst intensity, or warmup length) normally have **different `corpusId`s**. Do not use `compare-evaluations.sh` for them; it rejects runs of different corpora. Compare them manually instead:

1. Evaluate each variant independently.
2. Read each run's `kit-evaluation-result.json`.
3. Read each run's `event-inspection.json` for scores and event-level detail.
4. Build a small comparison table.
5. Compare normalized metrics and score behavior.
6. Keep the primary-variable and controlled-variable record with the table.

Keep the seed fixed across the ladder unless you are using a declared matched-seed design.

Fields in `kit-evaluation-result.json`:

- `metricFamilies.structural.values.eventCount`
- `metricFamilies.structural.values.warmupEvents`
- `metricFamilies.structural.values.labeledEvaluationEvents`
- `metricFamilies.detectionLabeled.values.truePositives`, `falsePositives`, `trueNegatives`, `falseNegatives`
- `metricFamilies.detectionLabeled.values.precision`, `recall`, `falsePositiveRate`, `falseNegativeRate`

From `event-inspection.json`: per-event `anomalyScore`, `predictedAnomalous`, `participation`, `binaryMetricParticipant`, and `outcome`, including excluded rows.

Table template:

| Variant | Events | Warmup | Evaluation events | TP | FP | TN | FN | Recall | FPR |
|---------|--------|--------|-------------------|----|----|----|----|--------|-----|
| 4s | | | | | | | | | |
| 6s | | | | | | | | | |
| 8s | | | | | | | | | |

### Raw counts vs normalized metrics

When variants have different event counts or class counts, do not compare raw TP/FP/TN/FN alone. Keep raw counts as context and prefer normalized measures: recall, false-positive rate, precision when defined, participation and exclusion rates, anomaly-score distributions, and detection latency where appropriate.

For example, `2 anomalous events, FN = 2` and `6 anomalous events, FN = 6` both have `recall = 0.0`; the larger raw FN count does not mean the second variant performed worse.

`NEGATIVE RESULT != FAILED EXPERIMENT`. If a variable you expected to matter leaves recall or false-positive rate unchanged under a fixed scorer and threshold, that is a valid, reportable result.

### Compare detector paths

- **Level-3 same-framework comparison** (`./scripts/compare-reference-detectors.sh --output "$EXP/runs/level3"`, JDK 21 required) compares Statistical with the offline reference Isolation Forest on three fixed kit reference corpora only. Your own corpus cannot be passed in. See [`SAME_FRAMEWORK_DETECTOR_COMPARISON.md`](SAME_FRAMEWORK_DETECTOR_COMPARISON.md).
- **Candidate scorer artifacts** are evaluated through the Java API on the tracked reference dataset; see [Candidate detector evaluation](RUNNING_EVALUATIONS.md#candidate-detector-evaluation-advanced).

Report factual deltas only. `LEVEL-3 COMPARISON != SCORER RANKING`; do not call any scorer "better".

---

## 14. Interpret results

State conclusions in terms of the corpus, seed, scorer, and threshold you actually used.

**Good:**

> On corpus `<corpusId>` generated with seed `example-seed-001`, statistical replay at threshold `0.5` produced FN = `<n>` on authored burst events.

**Bad:**

> Isolation Forest is the best AI-Sentinel scorer.
>
> This proves production detection efficacy.
>
> MONITOR or ENFORCE readiness is established.

Remember `ANOMALOUS != MALICIOUS`, `NORMAL != SAFE`, and `ANOMALY SCORE != PROBABILITY OF ATTACK`. Scores are explained in [Anomaly scores](RUNNING_EVALUATIONS.md#anomaly-scores).

---

## 15. Record reproducibility

Keep `$EXP/notes/reproducibility.md` with:

| Field | How to obtain |
|-------|---------------|
| Commit SHA and branch | `git rev-parse HEAD`, `git branch --show-current` |
| Java and Maven versions | `java -version`, `mvn -version` |
| Scenario path and SHA-256 | `shasum -a 256 "$EXP/scenario/…"` |
| Seed and `generatorBuildId` | The values you passed to the generator |
| Primary independent variable | For example `evaluation.durationSeconds = 6` |
| Controlled variables | Family, seed, scorer, threshold, warmup, population, endpoints, feature schema, ground-truth policy, generator build |
| `corpusId` and artifact digests | `corpus-manifest.json` |
| Scorer and threshold | The run's `#configuration` and provenance |
| Exact commands and output paths | Copy them as run |
| Output digests (optional) | `shasum -a 256` on the JSON and HTML files |

Determinism rule from the contract:

```text
same scenario bytes
+ same seed
+ same generatorContractVersion
+ same generatorBuildId
⇒ same ordered corpus artifacts and integrity metadata
```

---

## 16. Common mistakes

1. Authoring events first, then inventing a threat story.
2. Putting labels into `events.jsonl`.
3. Treating warmup events annotated `benign` as binary-metric negatives.
4. Omitting `--output` and losing the evidence.
5. Writing into `evaluation/reference/`, `evaluation/detection-reference-baseline/`, `docs/performance/`, or `evaluation/kit-reference/`.
6. Assuming the Kit CLI has a `--scorer` option.
7. Treating Level-3 Isolation Forest results on small, uniform warmup data as proof of discrimination.
8. Using `compare-evaluations.sh` on runs with different `corpusId`s.
9. Running `compare-reference-detectors.sh` with a JDK other than 21.
10. Generalizing synthetic metrics to production efficacy.
11. Changing the seed and the primary variable at the same time without a declared matched-seed design.
12. Comparing raw TP/FN counts across variants with different class counts instead of normalized metrics.

---

## 17. Clean up

Delete only your own workspace:

```bash
rm -rf "$EXP"
```

Never clean `evaluation/kit-reference/`, `evaluation/reference/`, `evaluation/detection-reference-baseline/`, or `docs/performance/`. For the next experiment, use a new workspace, `scenarioId`, and (unless the seed is held as a control) seed.

---

## 18. Checklist

- [ ] Question, threat hypothesis, and benign-lookalike hypothesis written
- [ ] Primary independent variable and controlled variables recorded
- [ ] JDK 21, Maven, and Git verified (`java -version` and `mvn -version`)
- [ ] Commit SHA recorded
- [ ] Scenario authored with `metadata.family`
- [ ] Corpus generated and checksums verified
- [ ] Protected paths avoided
- [ ] Evaluation run with `--output` succeeded
- [ ] HTML report opened and limitations read
- [ ] At least one event of each occurring outcome inspected
- [ ] At least one fair comparison run
- [ ] Conclusions written in corpus-bounded language
- [ ] Reproducibility record saved
- [ ] Workspace cleaned up or archived deliberately

---

## 19. Command reference

```bash
# Setup
git rev-parse HEAD
mvn -pl ai-sentinel-core -DskipTests compile

# Generate (after writing GenerateExperimentCorpus.java)
mvn -q -pl ai-sentinel-core -DskipTests compile dependency:build-classpath \
  -Dmdep.outputFile="$EXP/classpath.txt"
JAVA_CP="ai-sentinel-core/target/classes:$(cat "$EXP/classpath.txt")"
javac --release 21 -cp "$JAVA_CP" -d "$EXP/classes" "$EXP/GenerateExperimentCorpus.java"
java -cp "$EXP/classes:$JAVA_CP" GenerateExperimentCorpus \
  "$EXP/scenario/experiment.normal-vs-burst.v1.json" \
  "example-seed-001" "local-experiment-generator" "$EXP/corpus"

# Evaluate
./scripts/evaluate-generated-corpus.sh --corpus "$EXP/corpus" \
  --output "$EXP/runs/statistical" --threshold 0.5

# Compare thresholds (same corpus)
./scripts/compare-evaluations.sh --baseline "$EXP/runs/thresh-0.4" \
  --candidate "$EXP/runs/thresh-0.8" --output "$EXP/runs/compare-threshold"

# Level-3 statistical vs offline Isolation Forest (three fixed kit corpora; JDK 21)
./scripts/compare-reference-detectors.sh --output "$EXP/runs/level3"
```

---

## Advanced: custom scorers

The basic workflow does not need a custom scorer. If you implement one for offline evaluation:

- Implement `dev.aisentinel.core.scoring.AnomalyScorer` (`double score(RequestFeatures features)` and `void update(RequestFeatures features)`).
- Return scores in `[0.0, 1.0]`; NaN, infinite, or negative values are treated as invalid scores.
- Request-path use requires thread safety; offline replay is sequential.

Inject it into offline evaluation through the framework:

```java
import dev.aisentinel.core.evaluation.DetectionEvaluationRunner;
import dev.aisentinel.core.replay.ReplayEngine;
import dev.aisentinel.core.scoring.AnomalyScorer;

AnomalyScorer myScorer = /* your implementation */;
DetectionEvaluationRunner runner =
    new DetectionEvaluationRunner(ReplayEngine.withEvaluationScorer(myScorer));
```

`ReplayEngine.withEvaluationScorer` requires a `CANDIDATE` replay scorer configuration. Candidate artifacts (currently Isolation Forest, `aif1`) use `CandidateDetectionEvaluationRunner`; see [`SCORER_CANDIDATE_EVALUATION.md`](../contracts/SCORER_CANDIDATE_EVALUATION.md). Contributing a scorer to the repository is a tracked code contribution; see [`CONTRIBUTING.md`](../../CONTRIBUTING.md#where-to-plug-in-new-behavior).

## Advanced: feature experiments

The canonical feature schema is version `"1"`, and the Kit CLI has no option to add or drop features. Changing feature selection or meaning requires understanding `FeatureSchema` and `RequestFeatures` in `ai-sentinel-core`, a schema versioning decision ([`FEATURE_SCHEMA.md`](../contracts/FEATURE_SCHEMA.md)), and code changes. Prefer varying scenario family, timing, and transitions first.

---

## Current limitations

- Corpus generation for arbitrary scenarios is available through the Java API only.
- The Kit evaluation CLI always uses the statistical scorer.
- Level-3 comparison runs only on its three fixed kit corpora, and the offline reference Isolation Forest is not public API.
- Candidate evaluation is Java-only.
- Feature ablation requires code and schema changes.
- `compare-reference-detectors.sh` requires JDK 21 on `PATH`.
