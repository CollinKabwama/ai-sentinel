#!/usr/bin/env python3
"""Verify reproduced Evaluation Kit evidence against a reproduction package manifest.

Resolves only safe repository-relative input paths (locators). Canonical input
identity is events/annotations SHA-256. Does not fetch, upload, or execute
evidence files.

Exit codes:
  0 — all targets PASS
  1 — verification failure
  2 — usage error
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any


REQUIRED_ARTIFACTS = (
    "kit-evaluation-result.json",
    "event-inspection.json",
    "evaluation.json",
    "evaluation.md",
    "evaluation-report.html",
)


def load_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def is_safe_relative(path_text: str) -> bool:
    if not path_text or path_text.startswith("/") or "://" in path_text:
        return False
    normalized = path_text.replace("\\", "/")
    parts = normalized.split("/")
    return ".." not in parts and not normalized.startswith("../")


def resolve_under(root: Path, relative: str, label: str) -> Path:
    if not is_safe_relative(relative):
        raise ValueError(f"{label} must be a safe relative path: {relative}")
    resolved = (root / relative).resolve()
    root_resolved = root.resolve()
    if root_resolved != resolved and root_resolved not in resolved.parents:
        raise ValueError(f"{label} escapes root: {relative}")
    return resolved


def layer_status(ok: bool) -> str:
    return "PASS" if ok else "FAIL"


def verify_target(
    repo_root: Path,
    package_root: Path,
    results_root: Path,
    package: dict,
    target: dict,
) -> dict:
    failures: list[str] = []
    target_id = target["targetId"]
    target_dir = results_root / target_id

    # INPUT layer — path is locator; digests are identity.
    input_ok = True
    try:
        corpus_dir = resolve_under(repo_root, target["corpusPath"], "corpusPath")
    except ValueError as exc:
        failures.append(str(exc))
        input_ok = False
        corpus_dir = None

    if corpus_dir is not None:
        events_path = corpus_dir / "events.jsonl"
        annotations_path = corpus_dir / "annotations.json"
        if not events_path.is_file():
            failures.append(f"missing events.jsonl under {target['corpusPath']}")
            input_ok = False
        else:
            actual_events = sha256_file(events_path)
            if actual_events != target["eventsSha256"]:
                failures.append(
                    f"eventsSha256 mismatch: expected {target['eventsSha256']}, actual {actual_events}"
                )
                input_ok = False
        if not annotations_path.is_file():
            failures.append(f"missing annotations.json under {target['corpusPath']}")
            input_ok = False
        else:
            actual_ann = sha256_file(annotations_path)
            if actual_ann != target["annotationsSha256"]:
                failures.append(
                    f"annotationsSha256 mismatch: expected {target['annotationsSha256']}, actual {actual_ann}"
                )
                input_ok = False

    # ARTIFACT BYTES
    artifact_ok = True
    artifact_records: list[dict] = []
    try:
        digests_path = resolve_under(package_root, target["expectedDigestsRef"], "expectedDigestsRef")
        digests = load_json(digests_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        failures.append(f"unable to read expected digests: {exc}")
        artifact_ok = False
        digests = {"artifacts": []}

    expected_by_path = {
        item["path"]: item for item in digests.get("artifacts", []) if isinstance(item, dict)
    }
    if target_dir.is_dir():
        for name in REQUIRED_ARTIFACTS:
            path = target_dir / name
            expected = expected_by_path.get(name)
            if expected is None:
                failures.append(f"expected digests missing entry for {name}")
                artifact_ok = False
                artifact_records.append(
                    {"path": name, "sha256": "0" * 64, "sizeBytes": 0, "status": "FAIL"}
                )
                continue
            if not path.is_file():
                failures.append(f"missing required artifact: {target_id}/{name}")
                artifact_ok = False
                artifact_records.append(
                    {
                        "path": name,
                        "sha256": expected["sha256"],
                        "sizeBytes": expected["sizeBytes"],
                        "status": "FAIL",
                    }
                )
                continue
            data = path.read_bytes()
            actual_sha = sha256_bytes(data)
            actual_size = len(data)
            status = "PASS"
            if actual_sha != expected["sha256"] or actual_size != expected["sizeBytes"]:
                status = "FAIL"
                artifact_ok = False
                failures.append(
                    f"artifact mismatch {target_id}/{name}: "
                    f"expected sha256={expected['sha256']} sizeBytes={expected['sizeBytes']}, "
                    f"actual sha256={actual_sha} sizeBytes={actual_size}"
                )
            artifact_records.append(
                {
                    "path": name,
                    "sha256": actual_sha,
                    "sizeBytes": actual_size,
                    "status": status,
                }
            )
    else:
        artifact_ok = False
        failures.append(f"missing target results directory: {target_id}")
        for name in REQUIRED_ARTIFACTS:
            expected = expected_by_path.get(name, {"sha256": "0" * 64, "sizeBytes": 0})
            artifact_records.append(
                {
                    "path": name,
                    "sha256": expected["sha256"],
                    "sizeBytes": expected["sizeBytes"],
                    "status": "FAIL",
                }
            )

    # RUN IDENTITY + STRUCTURAL from kit result
    run_ok = True
    structural_ok = True
    result_id = ""
    evaluation_run_id = ""
    kit_path = target_dir / "kit-evaluation-result.json"
    if kit_path.is_file():
        try:
            kit = load_json(kit_path)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            failures.append(f"unable to parse kit-evaluation-result.json: {exc}")
            run_ok = False
            structural_ok = False
            kit = {}

        result_id = str(kit.get("resultId", ""))
        evaluation_run_id = str(kit.get("evaluationRunId", ""))
        provenance = kit.get("provenance") if isinstance(kit.get("provenance"), dict) else {}
        families = kit.get("metricFamilies") if isinstance(kit.get("metricFamilies"), dict) else {}
        structural = (
            families.get("structural", {}).get("values")
            if isinstance(families.get("structural"), dict)
            else {}
        )
        detection = families.get("detectionLabeled") if isinstance(families.get("detectionLabeled"), dict) else {}
        detection_values = detection.get("values") if isinstance(detection.get("values"), dict) else {}

        if result_id != target["expectedResultId"]:
            run_ok = False
            failures.append(
                f"resultId mismatch: expected {target['expectedResultId']}, actual {result_id}"
            )
        if evaluation_run_id != target["expectedEvaluationRunId"]:
            run_ok = False
            failures.append(
                f"evaluationRunId mismatch: expected {target['expectedEvaluationRunId']}, actual {evaluation_run_id}"
            )
        if provenance.get("corpusId") != target["corpusId"]:
            run_ok = False
            failures.append(
                f"corpusId mismatch: expected {target['corpusId']}, actual {provenance.get('corpusId')}"
            )
        if provenance.get("softwareVersion") != package["softwareVersion"]:
            run_ok = False
            failures.append(
                f"softwareVersion mismatch: expected {package['softwareVersion']}, "
                f"actual {provenance.get('softwareVersion')}"
            )
        ref = package["referenceConfiguration"]
        for field in ("aiSentinelVersion", "scorerVersion", "policyVersion"):
            if str(provenance.get(field, "")) != str(ref.get(field, "")):
                run_ok = False
                failures.append(
                    f"{field} mismatch: expected {ref.get(field)}, actual {provenance.get(field)}"
                )
        threshold = structural.get("anomalyThreshold") if isinstance(structural, dict) else None
        if threshold != package["anomalyThreshold"]:
            run_ok = False
            failures.append(
                f"anomalyThreshold mismatch: expected {package['anomalyThreshold']}, actual {threshold}"
            )

        expectations = target["structuralExpectations"]
        for key in (
            "eventCount",
            "warmupEvents",
            "labeledEvaluationEvents",
            "labeledBenignEvents",
            "labeledAnomalousEvents",
        ):
            actual = structural.get(key) if isinstance(structural, dict) else None
            if actual != expectations[key]:
                structural_ok = False
                failures.append(f"{key} mismatch: expected {expectations[key]}, actual {actual}")
        if detection.get("availability") != expectations["detectionLabeledAvailability"]:
            structural_ok = False
            failures.append(
                "detectionLabeledAvailability mismatch: "
                f"expected {expectations['detectionLabeledAvailability']}, "
                f"actual {detection.get('availability')}"
            )
        for key in ("truePositives", "trueNegatives", "falsePositives", "falseNegatives"):
            actual = detection_values.get(key)
            if actual != expectations[key]:
                structural_ok = False
                failures.append(f"{key} mismatch: expected {expectations[key]}, actual {actual}")
    else:
        run_ok = False
        structural_ok = False
        if "missing required artifact" not in " ".join(failures):
            failures.append(f"missing kit-evaluation-result.json for {target_id}")

    status = layer_status(input_ok and run_ok and artifact_ok and structural_ok)
    record = {
        "targetId": target_id,
        "status": status,
        "corpusId": target["corpusId"],
        "resultId": result_id or target["expectedResultId"],
        "evaluationRunId": evaluation_run_id or target["expectedEvaluationRunId"],
        "layers": {
            "input": layer_status(input_ok),
            "runIdentity": layer_status(run_ok),
            "artifactBytes": layer_status(artifact_ok),
            "structuralResult": layer_status(structural_ok),
        },
        "artifacts": artifact_records,
    }
    if failures:
        record["failures"] = failures
    return record


def write_result(path: Path, result: dict) -> None:
    # Deterministic serialization: sorted keys within objects already constructed in order;
    # use separators and ensure stable target order from caller.
    text = json.dumps(result, indent=2, sort_keys=False) + "\n"
    path.write_text(text, encoding="utf-8")


def verify(package_path: Path, results_dir: Path, repo_root: Path) -> int:
    try:
        package = load_json(package_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"ERROR: cannot read package manifest: {exc}", file=sys.stderr)
        return 1

    if not isinstance(package, dict):
        print("ERROR: package manifest root must be an object", file=sys.stderr)
        return 1

    package_root = package_path.parent
    targets = package.get("targets")
    if not isinstance(targets, list) or not targets:
        print("ERROR: package.targets must be a non-empty array", file=sys.stderr)
        return 1

    seen_ids: set[str] = set()
    target_records: list[dict] = []
    for target in targets:
        if not isinstance(target, dict):
            print("ERROR: each target must be an object", file=sys.stderr)
            return 1
        target_id = target.get("targetId")
        if not isinstance(target_id, str) or not target_id:
            print("ERROR: targetId is required", file=sys.stderr)
            return 1
        if target_id in seen_ids:
            print(f"ERROR: duplicate targetId: {target_id}", file=sys.stderr)
            return 1
        seen_ids.add(target_id)
        record = verify_target(repo_root, package_root, results_dir, package, target)
        target_records.append(record)

        print(f"TARGET {target_id}")
        for layer, status in record["layers"].items():
            print(f"  {layer}: {status}")
        print(f"  OVERALL: {record['status']}")
        for failure in record.get("failures", []):
            print(f"  FAIL: {failure}", file=sys.stderr)

    overall = "PASS" if all(t["status"] == "PASS" for t in target_records) else "FAIL"
    result = {
        "reproductionResultSchemaVersion": "1",
        "packageId": package["packageId"],
        "status": overall,
        "softwareVersion": package["softwareVersion"],
        "referenceConfiguration": package["referenceConfiguration"],
        "anomalyThreshold": package["anomalyThreshold"],
        "targets": target_records,
    }
    if results_dir.exists():
        write_result(results_dir / "reproduction-result.json", result)

    passed = sum(1 for t in target_records if t["status"] == "PASS")
    print()
    print("AI-Sentinel Evidence Reproduction Verification")
    print(f"Targets: {len(target_records)}")
    print(f"Passed: {passed}")
    print(f"Failed: {len(target_records) - passed}")
    for t in target_records:
        print(f"  {t['targetId']:<22} {t['status']}")
    print(f"OVERALL REPRODUCTION: {overall}")
    return 0 if overall == "PASS" else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Verify reproduced Evaluation Kit evidence against a reproduction package manifest."
    )
    parser.add_argument(
        "--manifest",
        required=True,
        type=Path,
        help="Path to reproduction-package manifest JSON",
    )
    parser.add_argument(
        "--results",
        required=True,
        type=Path,
        help="Directory containing per-target reproduced evidence",
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=None,
        help="Repository root used to resolve corpusPath locators (default: two levels above package when under evaluation/reproduction)",
    )
    args = parser.parse_args(argv)

    if not args.manifest.is_file():
        print(f"ERROR: manifest not found: {args.manifest}", file=sys.stderr)
        return 2
    if not args.results.exists():
        print(f"ERROR: results directory not found: {args.results}", file=sys.stderr)
        return 2
    if not args.results.is_dir():
        print(f"ERROR: results path is not a directory: {args.results}", file=sys.stderr)
        return 2

    manifest = args.manifest.resolve()
    results = args.results.resolve()
    if args.repo_root is not None:
        repo_root = args.repo_root.resolve()
    else:
        # evaluation/reproduction/reproduction-manifest.json -> repo root
        repo_root = manifest.parents[2] if len(manifest.parents) >= 3 else Path.cwd().resolve()

    return verify(manifest, results, repo_root)


if __name__ == "__main__":
    sys.exit(main())
