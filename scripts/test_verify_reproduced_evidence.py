#!/usr/bin/env python3
"""Focused tests for scripts/verify-reproduced-evidence.py.

Run: python3 scripts/test_verify_reproduced_evidence.py
"""

from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = Path(__file__).resolve().parent / "verify-reproduced-evidence.py"


def _load_module():
    spec = importlib.util.spec_from_file_location("verify_reproduced_evidence", MODULE_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"unable to load {MODULE_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


vre = _load_module()


class VerifyReproducedEvidenceTests(unittest.TestCase):
    def test_rejects_path_traversal_corpus_path(self) -> None:
        with self.assertRaises(ValueError):
            vre.resolve_under(ROOT, "../secrets", "corpusPath")

    def test_rejects_absolute_corpus_path(self) -> None:
        self.assertFalse(vre.is_safe_relative("/tmp/corpus"))
        self.assertFalse(vre.is_safe_relative("evaluation/../secrets"))

    def test_live_package_verify_pass_and_fail_cases(self) -> None:
        manifest = ROOT / "evaluation" / "reproduction" / "reproduction-manifest.json"
        self.assertTrue(manifest.is_file())

        with tempfile.TemporaryDirectory(prefix="ais-repro-test-") as tmp:
            tmp_path = Path(tmp)
            empty = tmp_path / "empty-results"
            empty.mkdir()
            # Verifier prints layered FAIL details by design; silence for unit tests.
            import contextlib
            import io

            buf = io.StringIO()
            with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
                status = vre.verify(manifest, empty, ROOT)
            self.assertEqual(status, 1)
            result_path = empty / "reproduction-result.json"
            self.assertTrue(result_path.is_file())
            result = json.loads(result_path.read_text(encoding="utf-8"))
            self.assertEqual(result["status"], "FAIL")
            self.assertEqual(result["packageId"], "kit-reference-level1-v1")
            self.assertNotIn("timestamp", result)
            self.assertNotIn("hostname", result)
            self.assertNotIn("duration", result)
            for key in result:
                self.assertFalse(str(key).lower().endswith("path") and key != "path")

    def test_wrong_evaluation_run_id_fails(self) -> None:
        manifest = ROOT / "evaluation" / "reproduction" / "reproduction-manifest.json"
        package = json.loads(manifest.read_text(encoding="utf-8"))
        target = dict(package["targets"][0])
        target["expectedEvaluationRunId"] = "evalrun.000000000000000000000000"

        with tempfile.TemporaryDirectory(prefix="ais-repro-runid-") as tmp:
            results = Path(tmp)
            target_dir = results / target["targetId"]
            target_dir.mkdir()
            # Minimal kit result with wrong identity; digests will also fail.
            kit = {
                "resultId": target["expectedResultId"],
                "evaluationRunId": "evalrun.ffffffffffffffffffffffff",
                "provenance": {
                    "corpusId": target["corpusId"],
                    "softwareVersion": package["softwareVersion"],
                    "aiSentinelVersion": package["referenceConfiguration"]["aiSentinelVersion"],
                    "scorerVersion": package["referenceConfiguration"]["scorerVersion"],
                    "policyVersion": package["referenceConfiguration"]["policyVersion"],
                },
                "metricFamilies": {
                    "structural": {
                        "values": {
                            "anomalyThreshold": package["anomalyThreshold"],
                            **{
                                k: target["structuralExpectations"][k]
                                for k in (
                                    "eventCount",
                                    "warmupEvents",
                                    "labeledEvaluationEvents",
                                    "labeledBenignEvents",
                                    "labeledAnomalousEvents",
                                )
                            },
                        }
                    },
                    "detectionLabeled": {
                        "availability": target["structuralExpectations"][
                            "detectionLabeledAvailability"
                        ],
                        "values": {
                            k: target["structuralExpectations"][k]
                            for k in (
                                "truePositives",
                                "trueNegatives",
                                "falsePositives",
                                "falseNegatives",
                            )
                        },
                    },
                },
            }
            (target_dir / "kit-evaluation-result.json").write_text(
                json.dumps(kit), encoding="utf-8"
            )
            for name in vre.REQUIRED_ARTIFACTS:
                if name == "kit-evaluation-result.json":
                    continue
                (target_dir / name).write_text("placeholder\n", encoding="utf-8")

            package_mut = dict(package)
            package_mut["targets"] = [target]
            mut_manifest = results / "manifest.json"
            mut_manifest.write_text(json.dumps(package_mut), encoding="utf-8")
            # Point digests ref into real package expected dir via absolute escape prevention:
            # rewrite expectedDigestsRef to copy digests beside mut manifest.
            digests_src = (
                ROOT
                / "evaluation"
                / "reproduction"
                / target["expectedDigestsRef"]
            )
            digests_dst = results / "expected"
            digests_dst.mkdir()
            shutil.copy(digests_src, digests_dst / Path(target["expectedDigestsRef"]).name)
            target["expectedDigestsRef"] = f"expected/{Path(target['expectedDigestsRef']).name}"
            package_mut["targets"] = [target]
            mut_manifest.write_text(json.dumps(package_mut), encoding="utf-8")

            record = vre.verify_target(ROOT, results, results, package_mut, target)
            self.assertEqual(record["layers"]["runIdentity"], "FAIL")
            self.assertEqual(record["status"], "FAIL")
            self.assertTrue(
                any("evaluationRunId mismatch" in f for f in record.get("failures", []))
            )

    def test_full_valid_reproduction_passes(self) -> None:
        """A target whose artifacts genuinely match declared digests must report PASS
        end-to-end through verify(), including a PASS reproduction-result.json — the
        existing tests only ever exercise failure paths."""
        manifest = ROOT / "evaluation" / "reproduction" / "reproduction-manifest.json"
        package = json.loads(manifest.read_text(encoding="utf-8"))
        target = dict(package["targets"][0])

        with tempfile.TemporaryDirectory(prefix="ais-repro-pass-") as tmp:
            tmp_path = Path(tmp)
            results = tmp_path / "results"
            target_dir = results / target["targetId"]
            target_dir.mkdir(parents=True)

            kit = {
                "resultId": target["expectedResultId"],
                "evaluationRunId": target["expectedEvaluationRunId"],
                "provenance": {
                    "corpusId": target["corpusId"],
                    "softwareVersion": package["softwareVersion"],
                    "aiSentinelVersion": package["referenceConfiguration"]["aiSentinelVersion"],
                    "scorerVersion": package["referenceConfiguration"]["scorerVersion"],
                    "policyVersion": package["referenceConfiguration"]["policyVersion"],
                },
                "metricFamilies": {
                    "structural": {
                        "values": {
                            "anomalyThreshold": package["anomalyThreshold"],
                            **{
                                k: target["structuralExpectations"][k]
                                for k in (
                                    "eventCount",
                                    "warmupEvents",
                                    "labeledEvaluationEvents",
                                    "labeledBenignEvents",
                                    "labeledAnomalousEvents",
                                )
                            },
                        }
                    },
                    "detectionLabeled": {
                        "availability": target["structuralExpectations"][
                            "detectionLabeledAvailability"
                        ],
                        "values": {
                            k: target["structuralExpectations"][k]
                            for k in (
                                "truePositives",
                                "trueNegatives",
                                "falsePositives",
                                "falseNegatives",
                            )
                        },
                    },
                },
            }

            artifact_bytes: dict[str, bytes] = {
                "kit-evaluation-result.json": json.dumps(kit).encode("utf-8")
            }
            for name in vre.REQUIRED_ARTIFACTS:
                if name == "kit-evaluation-result.json":
                    continue
                artifact_bytes[name] = f"placeholder for {name}\n".encode("utf-8")

            expected_digests = {
                "digestSchemaVersion": "1",
                "targetId": target["targetId"],
                "artifacts": [
                    {
                        "path": name,
                        "sha256": vre.sha256_bytes(data),
                        "sizeBytes": len(data),
                    }
                    for name, data in artifact_bytes.items()
                ],
            }
            for name, data in artifact_bytes.items():
                (target_dir / name).write_bytes(data)

            package_dir = tmp_path / "package"
            expected_dir = package_dir / "expected"
            expected_dir.mkdir(parents=True)
            digests_name = "established-normal.digests.json"
            (expected_dir / digests_name).write_text(
                json.dumps(expected_digests), encoding="utf-8"
            )
            target["expectedDigestsRef"] = f"expected/{digests_name}"
            package_mut = dict(package)
            package_mut["targets"] = [target]
            mut_manifest = package_dir / "manifest.json"
            mut_manifest.write_text(json.dumps(package_mut), encoding="utf-8")

            import contextlib
            import io

            buf = io.StringIO()
            with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
                status = vre.verify(mut_manifest, results, ROOT)

            self.assertEqual(status, 0, msg=buf.getvalue())
            result = json.loads(
                (results / "reproduction-result.json").read_text(encoding="utf-8")
            )
            self.assertEqual(result["status"], "PASS")
            self.assertEqual(len(result["targets"]), 1)
            target_record = result["targets"][0]
            self.assertEqual(target_record["status"], "PASS")
            for layer_status in target_record["layers"].values():
                self.assertEqual(layer_status, "PASS")
            self.assertNotIn("failures", target_record)

    def test_duplicate_target_id_rejected(self) -> None:
        manifest = ROOT / "evaluation" / "reproduction" / "reproduction-manifest.json"
        package = json.loads(manifest.read_text(encoding="utf-8"))
        target = dict(package["targets"][0])
        package_mut = dict(package)
        package_mut["targets"] = [target, dict(target)]

        with tempfile.TemporaryDirectory(prefix="ais-repro-dup-") as tmp:
            tmp_path = Path(tmp)
            mut_manifest = tmp_path / "manifest.json"
            mut_manifest.write_text(json.dumps(package_mut), encoding="utf-8")
            results = tmp_path / "results"
            results.mkdir()

            import contextlib
            import io

            buf = io.StringIO()
            with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
                status = vre.verify(mut_manifest, results, ROOT)

            self.assertEqual(status, 1)
            self.assertIn("duplicate targetId", buf.getvalue())
            self.assertFalse((results / "reproduction-result.json").exists())

    def test_result_record_omits_environment_fields(self) -> None:
        result = {
            "reproductionResultSchemaVersion": "1",
            "packageId": "x",
            "status": "PASS",
            "softwareVersion": "0.4.0",
            "referenceConfiguration": {
                "aiSentinelVersion": "0.3.0",
                "scorerVersion": "0.3.0",
                "policyVersion": "0.3.0",
            },
            "anomalyThreshold": 0.5,
            "targets": [],
        }
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "reproduction-result.json"
            vre.write_result(path, result)
            text = path.read_text(encoding="utf-8")
            for forbidden in ("timestamp", "hostname", "username", "durationMs", "/Users/"):
                self.assertNotIn(forbidden, text)


if __name__ == "__main__":
    unittest.main()
