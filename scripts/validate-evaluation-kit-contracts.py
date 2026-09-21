#!/usr/bin/env python3
"""Validate Evaluation Kit JSON Schema contracts and fixtures.

Prefer jsonschema when available. Always run isolation invariant checks.
Does not introduce a production Java API or runtime dependency.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_DIR = ROOT / "docs" / "contracts" / "schemas" / "evaluation-kit"
VALID_DIR = ROOT / "docs" / "contracts" / "fixtures" / "evaluation-kit" / "valid"
INVALID_DIR = ROOT / "docs" / "contracts" / "fixtures" / "evaluation-kit" / "invalid"

SCHEMA_BY_PREFIX = {
    "scenario.": "scenario.schema.json",
    "corpus-manifest.": "corpus-manifest.schema.json",
    "corpus-inventory.": "corpus-inventory.schema.json",
    "evaluator-dataset-manifest.": "evaluator-dataset-manifest.schema.json",
    "ground-truth.": "ground-truth.schema.json",
    "evaluation-result.": "evaluation-result.schema.json",
    "event-inspection.": "event-inspection.schema.json",
    "comparison.": "comparison-result.schema.json",
    "reproducibility-manifest.": "reproducibility-manifest.schema.json",
}

# Fixture name prefixes that intentionally have no JSON Schema (validated only by the
# invariant checks below, e.g. because they illustrate an existing, separately-governed
# contract such as EvaluationEvent). Any OTHER unmapped prefix is treated as a naming
# mistake and fails validation instead of silently skipping, so a new/mistyped fixture
# can never escape validation coverage unnoticed.
INVARIANT_ONLY_PREFIXES = ("detector-facing-event.", "scorer-input.")

FORBIDDEN_EVENT_FIELDS = {
    "expectedClass",
    "groundTruth",
    "ground_truth",
    "annotation",
    "annotations",
    "label",
    "labels",
    "anomalyExpected",
    "maliciousnessAsserted",
    "evaluationExpectations",
    "scenarioId",
    "corpusId",
    "resultId",
    "seed",
    "generatorContractVersion",
    "generatorBuildId",
    "desiredAnomalyScore",
    "expectedAction",
}

FORBIDDEN_SCORER_INPUT_FIELDS = {
    "evaluationExpectations",
    "desiredAnomalyScore",
    "expectedAction",
    "expectedClass",
    "groundTruth",
    "labels",
}


def load_json(path: Path):
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def resolve_schema_name(fixture_name: str) -> str | None:
    for prefix, schema_name in SCHEMA_BY_PREFIX.items():
        if fixture_name.startswith(prefix):
            return schema_name
    return None


def build_validator():
    try:
        from jsonschema import Draft202012Validator
        from referencing import Registry, Resource
        from referencing.jsonschema import DRAFT202012
    except ImportError as exc:  # pragma: no cover - exercised when deps missing
        raise SystemExit(
            "jsonschema is required. Run scripts/validate-evaluation-kit-contracts.sh "
            f"(it bootstraps a local venv). Import error: {exc}"
        ) from exc

    registry = Registry()
    for schema_path in SCHEMA_DIR.glob("*.schema.json"):
        schema = load_json(schema_path)
        resource = Resource.from_contents(schema, default_specification=DRAFT202012)
        registry = registry.with_resource(schema["$id"], resource)
        # Also register by relative filename for $ref resolution used in schemas.
        registry = registry.with_resource(schema_path.name, resource)

    validators = {}
    for schema_path in SCHEMA_DIR.glob("*.schema.json"):
        if schema_path.name == "common.schema.json":
            continue
        schema = load_json(schema_path)
        validators[schema_path.name] = Draft202012Validator(schema, registry=registry)
    return validators


def schema_errors(validator, instance) -> list[str]:
    return [e.message for e in sorted(validator.iter_errors(instance), key=lambda e: e.path)]


def check_forbidden_fields(obj, forbidden: set[str], path: str = "$") -> list[str]:
    errors = []
    if isinstance(obj, dict):
        for key, value in obj.items():
            here = f"{path}.{key}"
            if key in forbidden:
                errors.append(f"forbidden field present: {here}")
            errors.extend(check_forbidden_fields(value, forbidden, here))
    elif isinstance(obj, list):
        for i, value in enumerate(obj):
            errors.extend(check_forbidden_fields(value, forbidden, f"{path}[{i}]"))
    return errors


def run_invariant_checks() -> list[str]:
    failures = []

    event = load_json(VALID_DIR / "detector-facing-event.example.json")
    event_hits = check_forbidden_fields(event, FORBIDDEN_EVENT_FIELDS)
    if event_hits:
        failures.append(
            "valid detector-facing event unexpectedly contains ground-truth-like fields: "
            + "; ".join(event_hits)
        )

    bad_event = load_json(INVALID_DIR / "detector-facing-event.with-ground-truth.json")
    if not check_forbidden_fields(bad_event, FORBIDDEN_EVENT_FIELDS):
        failures.append(
            "invalid detector-facing event fixture does not demonstrate ground-truth leakage"
        )

    scorer = load_json(VALID_DIR / "scorer-input.example.json")
    scorer_hits = check_forbidden_fields(scorer, FORBIDDEN_SCORER_INPUT_FIELDS)
    if scorer_hits:
        failures.append(
            "valid scorer-input unexpectedly contains expectation fields: "
            + "; ".join(scorer_hits)
        )

    bad_scorer = load_json(INVALID_DIR / "scorer-input.with-expectations.json")
    if not check_forbidden_fields(bad_scorer, FORBIDDEN_SCORER_INPUT_FIELDS):
        failures.append(
            "invalid scorer-input fixture does not demonstrate expectation leakage"
        )

    unlabeled = load_json(VALID_DIR / "evaluation-result.unlabeled.example.json")
    if "detectionEvidenceRef" in unlabeled:
        failures.append("unlabeled result must not require detectionEvidenceRef")
    dl = unlabeled.get("metricFamilies", {}).get("detectionLabeled", {})
    if dl.get("availability") not in {"unavailable", "not_applicable"}:
        failures.append("unlabeled result must mark detectionLabeled unavailable/not_applicable")
    if "values" in dl:
        failures.append("unlabeled result must not fabricate detectionLabeled.values")

    labeled = load_json(VALID_DIR / "evaluation-result.labeled.example.json")
    if "detectionEvidenceRef" not in labeled:
        failures.append("labeled example should show optional detectionEvidenceRef composition")

    failures.extend(check_result_reproducibility_reconciliation(unlabeled))

    return failures


def check_result_reproducibility_reconciliation(result: dict) -> list[str]:
    """Cross-document check: an Evaluation Result and the Reproducibility Manifest it
    binds to must agree on shared identity fields. JSON Schema validates each document
    independently, so this reconciliation can only be enforced here — without it, the
    same run's identity could silently diverge between the two documents.
    """
    failures = []
    manifest_path = VALID_DIR / "reproducibility-manifest.example.json"
    manifest = load_json(manifest_path)
    provenance = result.get("provenance", {})

    pairs = [
        ("resultId", result.get("resultId"), manifest.get("resultId")),
        ("scenarioId", provenance.get("scenarioId"), manifest.get("scenario", {}).get("scenarioId")),
        ("scenarioVersion", provenance.get("scenarioVersion"), manifest.get("scenario", {}).get("scenarioVersion")),
        ("corpusId", provenance.get("corpusId"), manifest.get("corpus", {}).get("corpusId")),
        ("seed", provenance.get("seed"), manifest.get("generator", {}).get("seed")),
        (
            "generatorContractVersion",
            provenance.get("generatorContractVersion"),
            manifest.get("generator", {}).get("generatorContractVersion"),
        ),
        (
            "generatorBuildId",
            provenance.get("generatorBuildId"),
            manifest.get("generator", {}).get("generatorBuildId"),
        ),
        ("aiSentinelVersion", provenance.get("aiSentinelVersion"), manifest.get("engine", {}).get("aiSentinelVersion")),
    ]
    for field, result_value, manifest_value in pairs:
        if result_value != manifest_value:
            failures.append(
                f"evaluation-result/{result.get('resultId')} and reproducibility-manifest/"
                f"{manifest_path.name} disagree on {field}: {result_value!r} != {manifest_value!r}"
            )
    return failures


def main() -> int:
    validators = build_validator()
    failures: list[str] = []
    passed = 0

    print("== schema validation: valid fixtures ==")
    for path in sorted(VALID_DIR.glob("*.json")):
        schema_name = resolve_schema_name(path.name)
        if schema_name is None:
            if path.name.startswith(INVARIANT_ONLY_PREFIXES):
                # Non-schema illustrative fixtures validated by invariants only.
                print(f"SKIP schema ({path.name}) — invariant-only fixture")
                continue
            failures.append(
                f"UNMAPPED fixture: {path.name} matches no schema prefix and no "
                "invariant-only allowlist entry — add a schema mapping or allowlist it explicitly"
            )
            print(f"FAIL {path.name}: unmapped fixture name (would silently escape validation)")
            continue
        instance = load_json(path)
        errors = schema_errors(validators[schema_name], instance)
        if errors:
            failures.append(f"VALID expected pass: {path.name}: {errors[0]}")
            print(f"FAIL {path.name}: {errors[0]}")
        else:
            passed += 1
            print(f"PASS {path.name} -> {schema_name}")

    print("\n== schema validation: invalid fixtures ==")
    for path in sorted(INVALID_DIR.glob("*.json")):
        schema_name = resolve_schema_name(path.name)
        if schema_name is None:
            if path.name.startswith(INVARIANT_ONLY_PREFIXES):
                # Invariant-only negative fixtures.
                print(f"SKIP schema ({path.name}) — invariant-only negative fixture")
                continue
            failures.append(
                f"UNMAPPED fixture: {path.name} matches no schema prefix and no "
                "invariant-only allowlist entry — add a schema mapping or allowlist it explicitly"
            )
            print(f"FAIL {path.name}: unmapped fixture name (would silently escape validation)")
            continue
        instance = load_json(path)
        errors = schema_errors(validators[schema_name], instance)
        if not errors:
            failures.append(f"INVALID expected fail: {path.name} unexpectedly valid")
            print(f"FAIL {path.name}: unexpectedly valid")
        else:
            passed += 1
            print(f"PASS {path.name} rejected ({errors[0]})")

    print("\n== isolation / unlabeled invariants ==")
    invariant_failures = run_invariant_checks()
    if invariant_failures:
        for item in invariant_failures:
            failures.append(item)
            print(f"FAIL {item}")
    else:
        passed += 1
        print("PASS ground-truth isolation, assertion isolation, unlabeled result invariants")

    print("\n== kit-reference repository artifacts ==")
    kit_root = ROOT / "evaluation" / "kit-reference"
    inventory_path = kit_root / "inventory.json"
    if not inventory_path.is_file():
        failures.append("Missing evaluation/kit-reference/inventory.json")
        print("FAIL missing inventory.json")
    else:
        inventory = load_json(inventory_path)
        errors = schema_errors(validators["corpus-inventory.schema.json"], inventory)
        if errors:
            failures.append(f"kit-reference inventory.json: {errors[0]}")
            print(f"FAIL inventory.json: {errors[0]}")
        else:
            passed += 1
            print("PASS inventory.json -> corpus-inventory.schema.json")

        scenario_dir = kit_root / "scenarios"
        for path in sorted(scenario_dir.glob("*.json")):
            instance = load_json(path)
            errors = schema_errors(validators["scenario.schema.json"], instance)
            if errors:
                failures.append(f"kit-reference scenario {path.name}: {errors[0]}")
                print(f"FAIL {path.name}: {errors[0]}")
            else:
                passed += 1
                print(f"PASS {path.name} -> scenario.schema.json")

        for entry in inventory.get("entries", []):
            corpus_path = kit_root / entry["corpusPath"]
            manifest_path = corpus_path / "corpus-manifest.json"
            annotations_path = corpus_path / "annotations.json"
            events_path = corpus_path / "events.jsonl"
            for required in (manifest_path, annotations_path, events_path):
                if not required.is_file():
                    failures.append(f"Missing artifact: {required.relative_to(ROOT)}")
                    print(f"FAIL missing {required.relative_to(ROOT)}")
            if manifest_path.is_file():
                errors = schema_errors(
                    validators["corpus-manifest.schema.json"], load_json(manifest_path)
                )
                if errors:
                    failures.append(f"{manifest_path.relative_to(ROOT)}: {errors[0]}")
                    print(f"FAIL {manifest_path.relative_to(ROOT)}: {errors[0]}")
                else:
                    passed += 1
                    print(f"PASS {manifest_path.relative_to(ROOT)}")
            if annotations_path.is_file():
                errors = schema_errors(
                    validators["ground-truth.schema.json"], load_json(annotations_path)
                )
                if errors:
                    failures.append(f"{annotations_path.relative_to(ROOT)}: {errors[0]}")
                    print(f"FAIL {annotations_path.relative_to(ROOT)}: {errors[0]}")
                else:
                    passed += 1
                    print(f"PASS {annotations_path.relative_to(ROOT)}")
            if events_path.is_file():
                with events_path.open(encoding="utf-8") as handle:
                    for line_no, line in enumerate(handle, start=1):
                        line = line.strip()
                        if not line:
                            continue
                        event = json.loads(line)
                        for field in FORBIDDEN_EVENT_FIELDS:
                            if field in event:
                                failures.append(
                                    f"{events_path.relative_to(ROOT)}:{line_no} contains forbidden field {field}"
                                )
                                print(
                                    f"FAIL {events_path.relative_to(ROOT)}:{line_no} forbidden field {field}"
                                )

    print()
    if failures:
        print(
            f"Evaluation Kit contract validation FAILED "
            f"({len(failures)} failure(s); {passed} checks passed)"
        )
        for item in failures:
            print(f"  - {item}")
        return 1

    print(f"Evaluation Kit contract validation PASSED ({passed} checks)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
