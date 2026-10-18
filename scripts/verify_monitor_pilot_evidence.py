#!/usr/bin/env python3
"""Verify finalized MONITOR-mode pilot evidence artifacts."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

EXPECTED_FILES = {
    "observations.jsonl",
    "pilot-summary.json",
    "pilot-manifest.json",
}

PROHIBITED_FIELD_NAMES = {
    "authorization",
    "cookie",
    "rawheaders",
    "rawbody",
    "password",
    "accesstoken",
    "refreshtoken",
    "username",
    "email",
    "clientip",
    "remoteaddr",
    "pseudonymizationsecret",
    "pilotsecret",
}

PROHIBITED_VALUE_MARKERS = {
    "PILOT_SECRET_MARKER",
    "SECRET_TOKEN_VALUE",
    "Bearer ",
    "alice@example.com",
}


def fail(msg: str) -> None:
    print(f"FAIL: {msg}", file=sys.stderr)
    raise SystemExit(1)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        fail(f"{path.name} is not valid JSON: {exc}")


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("Usage: verify-monitor-pilot-evidence.sh <pilot-directory>", file=sys.stderr)
        return 2

    directory = Path(argv[1]).resolve()
    if not directory.is_dir():
        fail(f"not a directory: {directory}")

    names = {p.name for p in directory.iterdir() if p.is_file()}
    if names != EXPECTED_FILES:
        fail(f"expected exactly {sorted(EXPECTED_FILES)}, found {sorted(names)}")

    observations_path = directory / "observations.jsonl"
    summary_path = directory / "pilot-summary.json"
    manifest_path = directory / "pilot-manifest.json"

    obs_bytes = observations_path.read_bytes()
    summary_text = summary_path.read_text(encoding="utf-8")
    manifest = load_json(manifest_path)
    summary = load_json(summary_path)

    if manifest.get("runtimeMode") != "MONITOR":
        fail("manifest.runtimeMode must be MONITOR")

    session_id = manifest.get("pilotSessionId")
    if not session_id:
        fail("manifest.pilotSessionId missing")

    if summary.get("pilotSessionId") != session_id:
        fail("summary.pilotSessionId mismatch")

    lines = [ln for ln in observations_path.read_text(encoding="utf-8").splitlines() if ln.strip()]
    count = len(lines)
    if manifest.get("observationCount") != count:
        fail(f"manifest.observationCount={manifest.get('observationCount')} != lines={count}")

    obs_sha = sha256_bytes(obs_bytes)
    if manifest.get("observationsSha256") != obs_sha:
        fail("observationsSha256 mismatch")

    sum_sha = sha256_bytes(summary_text.encode("utf-8"))
    if manifest.get("summarySha256") != sum_sha:
        fail("summarySha256 mismatch")

    if summary.get("observations") != count:
        fail("summary.observations count mismatch")

    action_counts: dict[str, int] = {}
    status_counts: dict[str, int] = {}
    identities: set[str] = set()
    for line in lines:
        try:
            obj = json.loads(line)
        except json.JSONDecodeError as exc:
            fail(f"invalid observation JSONL line: {exc}")
        if obj.get("pilotSessionId") != session_id:
            fail("observation pilotSessionId mismatch")
        if obj.get("runtimeMode") != "MONITOR":
            fail("observation runtimeMode must be MONITOR")
        if obj.get("enforcementApplied") is not False:
            fail("observation.enforcementApplied must be false")
        if obj.get("requestOutcome") != "CONTINUED":
            fail("observation.requestOutcome must be CONTINUED")
        identities.add(obj.get("pseudonymousIdentity", ""))
        action = obj.get("riskDerivedAction", "")
        action_counts[action] = action_counts.get(action, 0) + 1
        for status in obj.get("evaluationStatuses") or []:
            status_counts[status] = status_counts.get(status, 0) + 1

        # Field-name scan (case-insensitive keys)
        def walk(node, path=""):
            if isinstance(node, dict):
                for k, v in node.items():
                    key = "".join(ch for ch in str(k).lower() if ch.isalnum())
                    if key in PROHIBITED_FIELD_NAMES:
                        fail(f"prohibited field name present: {k}")
                    walk(v, f"{path}.{k}")
            elif isinstance(node, list):
                for i, v in enumerate(node):
                    walk(v, f"{path}[{i}]")

        walk(obj)

        def scan_values(node):
            if isinstance(node, dict):
                for v in node.values():
                    scan_values(v)
            elif isinstance(node, list):
                for v in node:
                    scan_values(v)
            elif isinstance(node, str):
                for marker in PROHIBITED_VALUE_MARKERS:
                    if marker in node:
                        fail(f"prohibited synthetic secret marker found in observation value: {marker}")

        scan_values(obj)

    if summary.get("uniquePseudonymousIdentities") != len(identities):
        fail("uniquePseudonymousIdentities mismatch")

    summary_actions = {k: int(v) for k, v in (summary.get("riskDerivedActions") or {}).items()}
    if summary_actions != action_counts:
        fail(f"riskDerivedActions mismatch: summary={summary_actions} observations={action_counts}")

    # Full artifact text must not contain common prohibited field tokens as JSON keys.
    blob = (obs_bytes + summary_text.encode("utf-8") + manifest_path.read_bytes()).decode("utf-8", errors="replace")
    for token in (
        '"authorization"',
        '"cookie"',
        '"password"',
        '"accessToken"',
        '"refreshToken"',
        '"clientIp"',
        '"remoteAddr"',
        '"pseudonymizationSecret"',
    ):
        if token.lower() in blob.lower():
            fail(f"prohibited token found in artifacts: {token}")

    if manifest.get("evidenceClass") != "OPERATIONAL_OBSERVATION":
        fail("manifest.evidenceClass must be OPERATIONAL_OBSERVATION")

    print("OK: MONITOR pilot evidence verified")
    print(f"  sessionId={session_id}")
    print(f"  observations={count}")
    print(f"  observationsSha256={obs_sha}")
    print(f"  summarySha256={sum_sha}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
