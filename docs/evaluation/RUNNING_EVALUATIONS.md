# Running AI-Sentinel evaluations

This guide answers: **which existing evaluation should I run, how do I start it safely, and how do I read the result?**

It is the practical entry point for evaluators. Each workflow below gives the question it answers, one command, where results appear, how to read them, and the claim boundary. Full parameters, artifact schemas, and design details stay in the linked canonical documents.

To design a new controlled experiment instead of rerunning existing evidence, see [`CREATING_EXPERIMENTS.md`](CREATING_EXPERIMENTS.md).

All workflows here are offline, repository-controlled evidence. They are not production validation, not `ENFORCE` readiness, and not an external pilot.

---

## Before you start

### Prerequisites

| Tool | Why | Check |
|------|-----|-------|
| JDK 21 | `pom.xml` targets Java 21; the Level-1 and Level-3 scripts refuse any other `java` on `PATH` | `java -version` |
| Maven 3.x | Evaluation scripts build the `ai-sentinel-core` classpath | `mvn -version` |
| Git | Record the commit you evaluated | `git --version` |
| Python 3 | Level-1 verification, performance evidence verification, pilot verification, Kit contract validation | `python3 --version` |
| Bash | All scripts under `scripts/` | `bash --version` |

Optional: Docker for the Redis consistency tests and the [Evaluation Kit container](../contracts/EVALUATION_KIT.md#13-containerized-evaluator-local-packaging).

`java -version` and the Java that Maven uses are not always the same runtime. **Both `java -version` and `mvn -version` must report JDK 21** before you run Level-1 or Level-3 workflows.

### One-time setup

```bash
git clone https://github.com/CollinKabwama/ai-sentinel.git
cd ai-sentinel
git rev-parse HEAD            # record this; evidence is commit-bound

# macOS example for selecting JDK 21:
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"

java -version                 # must report 21
mvn -version                  # must also report Java 21
mvn -pl ai-sentinel-core -DskipTests compile
```

Accepted digests and generator behavior depend on the commit, so a result without its commit SHA is incomplete.

### Where to write results

Run every command from the repository root and give each run a **new** output directory outside protected evidence paths:

```bash
OUT="${TMPDIR:-/tmp}/ai-sentinel-evaluations"
mkdir -p "$OUT"
```

Output directories must not already exist; the tools refuse to overwrite. See [Protected evidence](#protected-evidence) for paths you must never write into.

---

## Which evaluation should I run?

| I want to… | Workflow |
|------------|----------|
| Evaluate one generated corpus and read its report | [Evaluate a generated corpus](#evaluate-a-generated-corpus) |
| Reproduce the declared Evaluation Kit evidence on my checkout | [Level-1 reproduction](#level-1-reproduction) |
| Check that current code still matches the Official Detection Reference Baseline | [Verify the detection reference baseline](#verify-the-detection-reference-baseline) |
| Check that the checked-in kit reference corpora and Kit contracts are consistent | [Kit reference inventory and contracts](#kit-reference-inventory-and-contracts) |
| Replay the historical seed reference dataset | [Historical reference dataset replay](#historical-reference-dataset-replay) |
| See Statistical and an offline Isolation Forest side by side on the same inputs | [Level-3 same-framework comparison](#level-3-same-framework-comparison) |
| Compare two runs I already have (for example two thresholds) | [Compare two existing runs](#compare-two-existing-runs) |
| Evaluate a richer fictional organization | [Northgate organization-profile corpora](#northgate-organization-profile-corpora) |
| Evaluate my own event data | [Evaluator-provided datasets](#evaluator-provided-datasets) |
| Evaluate a candidate scorer artifact | [Candidate detector evaluation](#candidate-detector-evaluation-advanced) |
| Verify an existing MONITOR pilot session | [MONITOR pilot evidence verification](#monitor-pilot-evidence-verification) |
| Verify performance or Redis consistency evidence | [Related evidence that is not detection evaluation](#related-evidence-that-is-not-detection-evaluation) |
| Design a new experiment | [`CREATING_EXPERIMENTS.md`](CREATING_EXPERIMENTS.md) |

Every script is also listed in [`scripts/README.md`](../../scripts/README.md).

---

## Workflows

### Evaluate a generated corpus

**Question:** under statistical replay at a chosen threshold, what detection metrics and event outcomes does this corpus produce?

```bash
./scripts/evaluate-generated-corpus.sh \
  --corpus evaluation/kit-reference/corpora/kit.abrupt-burst \
  --output "$OUT/kit-abrupt-burst" \
  --threshold 0.5
```

- **Results:** the console summary, plus the report files in `--output`. Always pass `--output`; without it the evidence goes to a temporary directory that is deleted after the run.
- **Read it:** open `evaluation-report.html`, then `event-inspection.json`. See [How to read an evaluation](#how-to-read-an-evaluation).
- **Boundary:** synthetic, statistical scorer only, small corpora. Not production efficacy.
- **Details:** options, exit codes, and artifacts in [`EVALUATION_KIT.md`](../contracts/EVALUATION_KIT.md#one-command-evaluation-cli).

### Level-1 reproduction

**Question:** do the declared corpora and threshold reproduce the declared digests on this checkout?

```bash
./scripts/reproduce-evaluation-evidence.sh --output "$OUT/level1"
```

- **Prerequisite:** JDK 21 on `PATH`; the script exits with an error otherwise.
- **Results:** `index.html`, `reproduction-result.json`, and one Kit report directory per target.
- **Read it:** `reproduction-result.json` with `"status": "PASS"` is the authoritative result. Expected per-target counts are listed in the canonical document.
- **Verify existing results only:** `./scripts/verify-reproduced-evidence.sh --manifest evaluation/reproduction/reproduction-manifest.json --results "$OUT/level1"`
- **Boundary:** `LEVEL-1 REPRODUCTION != DETECTION EFFICACY VALIDATION`. It shows the repository reproduces its own evidence; it is not third-party validation.
- **Details:** [`INDEPENDENT_REPRODUCTION.md`](INDEPENDENT_REPRODUCTION.md).

### Verify the detection reference baseline

**Question:** does current code and configuration still match the Official Detection Reference Baseline?

```bash
./scripts/verify-detection-reference-baseline.sh
```

- **Arguments:** optional positional baseline directory and diagnostic output directory. This script has no `--help`; a `--help` argument is treated as a path.
- **Results:** console status. Exit `0` = MATCH, `1` = DRIFT_DETECTED, `2` = baseline integrity failure, `3` = current evaluation failure, `4` = invalid usage.
- **Read it:** MATCH with zero drift entries means the accepted baseline is reproduced. DRIFT means a difference to investigate, not permission to update the baseline.
- **Boundary:** a baseline is not a quality gate or detector-quality acceptance. The lifecycle script that changes the baseline is a governed process; do not use it to "fix" drift.
- **Details:** [`DETECTION_REFERENCE_BASELINE.md`](../../evaluation/DETECTION_REFERENCE_BASELINE.md).

### Kit reference inventory and contracts

**Question:** do the checked-in kit reference corpora regenerate deterministically, and are the Kit schemas and fixtures consistent?

```bash
./scripts/verify-kit-reference-corpus.sh
./scripts/validate-evaluation-kit-contracts.sh
```

- **Read it:** the corpus check reports that the inventory matches deterministic regeneration; the contract check reports `PASSED`.
- **Boundary:** `verify-kit-reference-corpus.sh --write` rewrites `evaluation/kit-reference/` and is for maintaining the reference inventory only. Schema changes follow the contract process.
- **Details:** [`evaluation/kit-reference/README.md`](../../evaluation/kit-reference/README.md) and [`EVALUATION_KIT.md`](../contracts/EVALUATION_KIT.md#12-machine-schemas-and-fixtures).

### Historical reference dataset replay

**Question:** does the tracked `evaluation/reference/` dataset replay deterministically through the statistical scorer?

```bash
./scripts/replay-reference-dataset.sh "$OUT/reference-replay"
```

- **Results:** `manifest.json` and `results.jsonl` (replay output, not the Kit report set). The console prints the dataset and results digests.
- **Details:** [`DETERMINISTIC_REPLAY.md`](../../evaluation/DETERMINISTIC_REPLAY.md) and [`REFERENCE_DATASET.md`](../../evaluation/REFERENCE_DATASET.md).

### Level-3 same-framework comparison

**Question:** on identical controlled inputs, how do the Statistical scorer and a deterministic offline reference Isolation Forest differ?

```bash
./scripts/compare-reference-detectors.sh --output "$OUT/level3"
```

- **Prerequisite:** JDK 21 on `PATH`.
- **Inputs:** fixed to three kit reference corpora (`kit.established-normal`, `kit.abrupt-burst`, `kit.recovery`). Other corpora cannot be passed in.
- **Results:** per target, `statistical/` and `isolation-forest-reference/` Kit report directories plus `comparison/comparison.json` and `comparison.html`.
- **Read it:** `comparison.html` summarizes factual deltas. For scorer identity, configuration, and limitations, open each side's `evaluation-report.html` and the canonical document.
- **Boundary:** `LEVEL-3 COMPARISON != SCORER RANKING`. The offline Isolation Forest uses the 6-feature statistical representation, not the 5-feature runtime representation. With small, uniform warmup data its post-warmup scores can collapse to a single value, so the run demonstrates mechanics, not discrimination.
- **Details:** [`SAME_FRAMEWORK_DETECTOR_COMPARISON.md`](SAME_FRAMEWORK_DETECTOR_COMPARISON.md).

### Compare two existing runs

**Question:** what changed between two completed runs of the same corpus, without rerunning anything?

A threshold pair on one corpus:

```bash
./scripts/evaluate-generated-corpus.sh \
  --corpus evaluation/kit-reference/corpora/kit.abrupt-burst \
  --output "$OUT/thresh-0.4" --threshold 0.4

./scripts/evaluate-generated-corpus.sh \
  --corpus evaluation/kit-reference/corpora/kit.abrupt-burst \
  --output "$OUT/thresh-0.8" --threshold 0.8

./scripts/compare-evaluations.sh \
  --baseline "$OUT/thresh-0.4" \
  --candidate "$OUT/thresh-0.8" \
  --output "$OUT/compare-threshold"
```

- **Results:** `comparison.json` and `comparison.html`.
- **Boundary:** both runs must share the same corpus identity; different corpora fail with `corpusId differs`. Output is factual deltas only, with no winner. To compare independently generated scenario variants, see [`CREATING_EXPERIMENTS.md`](CREATING_EXPERIMENTS.md#compare-scenario-variants).
- **Details:** [`EVALUATION_KIT.md`](../contracts/EVALUATION_KIT.md#14-beforeafter-evaluation-comparison).

### Northgate organization-profile corpora

**Question:** what does statistical replay produce on richer fictional SaaS/API corpora with multiple identities and longer warmup?

```bash
./scripts/evaluate-generated-corpus.sh \
  --corpus evaluation/organization-profile/northgate/corpora/northgate.established-normal \
  --output "$OUT/northgate-established-normal" \
  --threshold 0.5
```

Repeat with the other `northgate.*` directories under `evaluation/organization-profile/northgate/corpora/`, each with a fresh output directory.

- **Boundary:** `SYNTHETIC != PRODUCTION VALIDATION`. Northgate is fictional; results are observations under frozen scenarios and configuration, not a reason to retune the detector and not organizational validation.
- **Details:** [`ORGANIZATION_PROFILE_SYNTHETIC_EVALUATION.md`](ORGANIZATION_PROFILE_SYNTHETIC_EVALUATION.md).

### Evaluator-provided datasets

**Question:** what does the same evaluation pipeline produce on event data I supply?

```bash
./scripts/evaluate-generated-corpus.sh \
  --dataset path/to/my-dataset \
  --output "$OUT/my-dataset"
```

- **Boundary:** controlled evidence only; not production or third-party validation.
- **Details:** directory layout, manifest, and optional annotations in [`EVALUATOR_PROVIDED_DATASET.md`](../contracts/EVALUATOR_PROVIDED_DATASET.md).

### Candidate detector evaluation (advanced)

**Question:** how does a READY candidate scorer artifact behave on the tracked historical reference dataset?

This is an **API-level** capability with no shell script. It is invoked from Java through `CandidateDetectionEvaluationRunner` with a scorer artifact descriptor, the artifact bytes, the tracked `evaluation/reference/` dataset and annotations, a classification threshold, and a new output directory. `CandidateDetectionEvaluationRunnerTest` demonstrates the mechanism; you supply your own descriptor and artifact.

- **Results:** `candidate-evaluation.json` and `candidate-evaluation.md`.
- **Boundary:** evaluated is not accepted, and accepted is not approved, shadowed, or deployed. The runner refuses protected evidence destinations and does not change accepted evidence.
- **Details:** [`SCORER_CANDIDATE_EVALUATION.md`](../contracts/SCORER_CANDIDATE_EVALUATION.md).

### MONITOR pilot evidence verification

**Question:** is a finalized MONITOR pilot session directory complete and safe?

This is a **verifier only**. It needs an existing session produced by an authorized MONITOR pilot run of a Spring Boot / Servlet application with pilot evidence collection enabled. No offline command in this guide creates that session.

```bash
./scripts/verify-monitor-pilot-evidence.sh /path/to/pilot-session
```

- **Results:** exit `0` PASS, `1` FAIL, `2` usage error.
- **Boundary:** `MONITOR != ENFORCEMENT` and `PILOT READINESS != EXTERNAL PILOT EVIDENCE`. Pilot evidence is observational and is not detection-efficacy evidence.
- **Details:** configuration, pseudonymization, and artifacts in [`MONITOR_MODE_PILOT.md`](MONITOR_MODE_PILOT.md); independent evaluator handoff in [`EXTERNAL_MONITOR_EVALUATION.md`](EXTERNAL_MONITOR_EVALUATION.md).

### Related evidence that is not detection evaluation

| Evidence | Entry point | Boundary | Details |
|----------|-------------|----------|---------|
| Historical reference performance | `./scripts/verify-reference-performance-baseline-evidence.sh` | `PERFORMANCE EVIDENCE != DETECTION EVIDENCE`; historical 0.3.0-era evidence is not current production performance | [`REFERENCE_BASELINE.md`](../performance/REFERENCE_BASELINE.md) · [`BENCHMARKING.md`](../performance/BENCHMARKING.md) |
| Same-version multi-client Redis consistency | Starter test `DistributedMultiClientStateConsistencyValidationTest` (requires Docker) | `SAME-VERSION REDIS CONSISTENCY != CLUSTER / HA VALIDATION` | [`testing.md`](../testing.md) · [`deployment.md`](../deployment.md#distributed-deployment-notes) |

---

## How to read an evaluation

Normative classification rules live in [`DETECTION_EVALUATION.md`](../../evaluation/DETECTION_EVALUATION.md#classification-rule) and [`EVALUATION_KIT.md`](../contracts/EVALUATION_KIT.md). This section explains them operationally for every Kit run (generated corpus, evaluator-provided dataset, Level-1 targets, Northgate, and each side of a Level-3 comparison).

### Claim boundaries

| Boundary | Meaning |
|----------|---------|
| `ANOMALOUS != MALICIOUS` | A label or prediction is authored or score-based, not proven intent |
| `NORMAL != SAFE` | The absence of an alert is not a safety certification |
| `ANOMALY SCORE != PROBABILITY OF ATTACK` | Scores are in `[0, 1]` under normal conditions but are not calibrated probabilities |
| `GROUND TRUTH != DETECTOR INPUT` | Labels live in `annotations.json` and are joined only during evaluation |
| `SYNTHETIC != PRODUCTION VALIDATION` | Controlled corpora do not prove field efficacy |
| `EVALUATION != DEPLOYMENT` | Offline evidence is not `MONITOR` or `ENFORCE` readiness |

### Where to look first

| Output | Use it for |
|--------|------------|
| Console summary | Status, phase counts, TP/FP/TN/FN, limitations |
| `evaluation-report.html` | Human review; start here |
| `event-inspection.json` | Why each event was counted the way it was |
| `kit-evaluation-result.json` | Machine-readable metrics and provenance |
| `evaluation.json` / `evaluation.md` | Detailed detection evidence |

Artifact schemas are defined in [`EVALUATION_KIT.md`](../contracts/EVALUATION_KIT.md#durable-evaluation-reports-with---output).

Open the report with `open` (macOS) or `xdg-open` (Linux), or copy it to a machine with a browser. In a headless environment, read the JSON and Markdown files directly.

### HTML report sections

| Section | What it shows | Check | Do not conclude |
|---------|---------------|-------|-----------------|
| Header | Corpus or dataset identity and a synthetic-data notice | You evaluated the intended input | Production validation |
| `#summary` | Status, counts, headline metrics, main limitation | `status`, event and warmup counts | That a model is production-ready |
| `#configuration` | Threshold and scorer identity | Threshold and `scorerId` (Kit default: `statistical`) | That Isolation Forest or Composite was used |
| `#phases` | Warmup, labeled, and unknown counts | Warmup is state-building only | That warmup rows count toward metrics |
| `#detection` | TP/TN/FP/FN, precision, recall, F1, false-positive and false-negative rates | Computed only over binary-labeled evaluation events with evaluable scores | That `unavailable` means zero |
| `#temporal` | Scenario timeline | Qualitative shape of the run | A production attack timeline |
| `#events` | Per-event table | Individual outcomes with participation | — |
| `#provenance` | Seed, generator digests, schema versions | Reproducibility binding | Interoperability certification |
| `#artifacts` | Sibling file names | Where the JSON companions are | — |
| `#limitations` | Explicit non-claims | Read it every time | — |

### Event inspection

`event-inspection.json` joins each detector-facing event with its ground-truth annotation and replay outcome. Key fields: `eventId`, `category`, `expectedClass`, `participation`, `binaryMetricParticipant`, `anomalyScore`, `predictedAnomalous`, `outcome`.

| `participation` | Meaning |
|-----------------|---------|
| `warmup` | Replayed to build scorer state; excluded from binary metrics |
| `binary-labeled` | Labeled `benign` or `anomalous` in the evaluation window; counted |
| `unknown-unlabeled` | Replayed; excluded from binary metrics |

| `outcome` | Meaning |
|-----------|---------|
| `true-positive` / `true-negative` / `false-positive` / `false-negative` | Counted in the confusion matrix |
| `excluded-warmup` | Warmup event; not a TN or FN |
| `excluded-unknown-unlabeled` | `expectedClass` is `unknown` or `unlabeled` |
| `excluded-invalid-or-missing-score` | No evaluable score; not silently counted |

How one event becomes an outcome:

```text
features in events.jsonl
        ↓
replay through the scorer (score, then update state)
        ↓
anomalyScore
        ↓
predictedAnomalous = (anomalyScore >= threshold)
        ↓
join annotations.json by eventId
        ↓
outcome: TP, FP, TN, FN, or excluded-*
```

Example from the tracked `kit.abrupt-burst` corpus at threshold `0.5`: burst event `evt-b330aec7-0007` is annotated `anomalous`, is predicted anomalous, and is a `true-positive`.

### TP, FP, TN, FN

| Ground truth | Predicted | Outcome | What to investigate |
|--------------|-----------|---------|---------------------|
| anomalous | anomalous | TP | — |
| benign | benign | TN | — |
| benign | anomalous | FP | Features, threshold, and whether the benign behavior is truly normal for that identity |
| anomalous | benign | FN | Whether the authored anomaly is visible in the features and whether scorer state can see it |

Predicted anomalous means `anomalyScore >= threshold` (Kit default `0.5`).

Warmup, unknown, unlabeled, and invalid-score events are **excluded**, not counted as TN or FN. Warmup events can be annotated `benign` and still be excluded, because warmup only builds baseline state. Ratios with no defined denominator (for example precision with no predicted positives) are reported as `unavailable`, not zero.

### Anomaly scores

- Higher means more anomalous; the binary prediction uses `score >= threshold` only for metrics.
- A statistical cold-start score (default `0.4`) is a warmup value, not a 40% chance of attack.
- The Level-3 offline Isolation Forest emits a fallback score (`0.3`) before its model exists.

### Reproduction or new experiment?

| What changed | Classification |
|--------------|----------------|
| Nothing except the output directory, and digests match | Exact reproduction |
| Threshold | New experiment |
| Scorer or candidate | New experiment |
| Corpus, dataset, or annotations | New experiment |

---

## What can I run with which scorer today?

| Goal | Supported path |
|------|----------------|
| Statistical scorer on any generated corpus or evaluator-provided dataset | `evaluate-generated-corpus.sh` (always statistical) |
| Same scorer, different threshold | `--threshold`, then `compare-evaluations.sh` |
| Statistical vs offline Isolation Forest | `compare-reference-detectors.sh`, on the three fixed kit corpora only |
| Candidate scorer artifact | `CandidateDetectionEvaluationRunner` (Java API) on `evaluation/reference/` |
| Isolation Forest or Composite on an arbitrary corpus via the CLI | Not supported; the Kit CLI has no `--scorer` option |

Scorers are not drop-in equivalents: they use different feature representations, state, and fallback behavior. See [`ARCHITECTURE.md`](../../ARCHITECTURE.md) and [`SAME_FRAMEWORK_DETECTOR_COMPARISON.md`](SAME_FRAMEWORK_DETECTOR_COMPARISON.md).

---

## Protected evidence

Never write outputs into, edit, or "refresh" these paths while evaluating:

| Path | Contents |
|------|----------|
| `evaluation/detection-reference-baseline/` | Official Detection Reference Baseline |
| `evaluation/reference/` | Historical reference dataset and annotations |
| `docs/performance/` | Historical performance baseline and selection analysis |
| `evaluation/kit-reference/` | Versioned kit reference inventory (maintained only through `verify-kit-reference-corpus.sh --write`) |
| `evaluation/reproduction/` | Level-1 reproduction package and expected digests |
| `evaluation/organization-profile/northgate/` | Checked-in Northgate scenarios and corpora |

The evaluation CLI and candidate runner reject output directories under the first three paths. Changes to any of them follow the documented regeneration or lifecycle process, not an evaluation run.

---

## Troubleshooting

| Symptom | Likely cause | What to do |
|---------|--------------|------------|
| `ERROR: JDK 21 is required` | `java` on `PATH` is not 21 | Put JDK 21 first on `PATH`; check `mvn -version` too |
| Maven or JaCoCo errors under a newer JDK | Maven is using a different Java | Set `JAVA_HOME` to JDK 21 |
| `corpus directory does not exist` | Wrong working directory | Relative paths resolve against your current directory |
| Output directory already exists | Tools refuse to overwrite | Choose a new `--output` |
| Protected evidence destination rejected | Output under a protected path | Write under `$OUT` |
| `corpusId differs` | Comparing runs of different corpora | Compare runs of the same corpus only |
| HTML report missing | `--output` omitted or run failed | Always pass `--output` |
| Baseline `DRIFT_DETECTED` | Code or configuration changed | Investigate; do not promote a new baseline casually |
| Baseline integrity failure | Baseline files missing or edited | Restore them from Git |
| Level-1 digest mismatch | Different commit, corpus, or threshold | Align your checkout with the declared configuration |
| Kit contract validation fails | Schema or fixture drift | Investigate; do not edit accepted fixtures to make it pass |
| Starter tests cannot find new core classes | Stale local `ai-sentinel-core` install | `mvn -pl ai-sentinel-core -DskipTests install`, then retest |
| Redis tests skipped or failing | Docker unavailable | Environment limitation, not a cluster result |
| A `--help` argument is treated as a path | Script has no help option | Read the script header or the canonical document |

---

## Known limitations

- The Level-1 and Level-3 scripts require JDK 21 on `PATH`.
- The Kit evaluation CLI always uses the statistical scorer.
- Level-3 comparison runs only on its three fixed kit corpora.
- Candidate evaluation is available through Java only.
- MONITOR pilot verification requires a real finalized session directory.
- Some scripts have no `--help` option.
