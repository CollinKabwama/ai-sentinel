#!/usr/bin/env python3
"""Verify a local evidence artifact against a tracked evidence-artifact reference.

Reads sha256 + sizeBytes from the manifest and compares them to the supplied
local file. Does not fetch remote objects, upload archives, or execute payload
contents.

Exit codes:
  0 — verified
  1 — verification failure (mismatch, missing file, malformed manifest)
  2 — usage error
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

MUTABLE_TAGS = {"latest", "main", "master", "head"}


def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError("manifest root must be a JSON object")
    return data


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_manifest_shape(manifest: dict) -> list[str]:
    errors: list[str] = []
    for field in (
        "artifactSchemaVersion",
        "artifactId",
        "artifactKind",
        "sha256",
        "sizeBytes",
        "disclosure",
        "recoveryMode",
    ):
        if field not in manifest:
            errors.append(f"missing required field: {field}")

    sha = manifest.get("sha256")
    if isinstance(sha, str):
        if len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha):
            errors.append("sha256 must be 64 lowercase hex characters")
    elif "sha256" in manifest:
        errors.append("sha256 must be a string")

    size = manifest.get("sizeBytes")
    if "sizeBytes" in manifest and (not isinstance(size, int) or isinstance(size, bool) or size < 0):
        errors.append("sizeBytes must be a non-negative integer")

    location = manifest.get("location")
    if location is not None:
        if not isinstance(location, dict):
            errors.append("location must be an object when present")
        else:
            scheme = location.get("scheme")
            if scheme == "git-path":
                path = location.get("path", "")
                if not isinstance(path, str) or not path or path.startswith("/") or ".." in path.split("/"):
                    errors.append("git-path location.path must be a relative portable path")
            elif scheme == "github-release-asset":
                tag = str(location.get("tag", ""))
                if tag.lower() in MUTABLE_TAGS:
                    errors.append(f"github-release-asset tag must not be mutable alias: {tag}")
            elif scheme == "https-object":
                url = location.get("url", "")
                if not isinstance(url, str) or not url.startswith("https://"):
                    errors.append("https-object url must use https://")
                elif "@" in url.split("://", 1)[-1].split("/", 1)[0]:
                    errors.append("https-object url must not embed credentials")
            elif scheme is not None:
                errors.append(f"unsupported location.scheme: {scheme}")
    return errors


def verify(manifest_path: Path, artifact_path: Path) -> int:
    try:
        manifest = load_json(manifest_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"ERROR: cannot read manifest {manifest_path}: {exc}", file=sys.stderr)
        return 1

    shape_errors = validate_manifest_shape(manifest)
    if shape_errors:
        for err in shape_errors:
            print(f"ERROR: {err}", file=sys.stderr)
        return 1

    if not artifact_path.is_file():
        print(f"ERROR: artifact file not found: {artifact_path}", file=sys.stderr)
        return 1

    expected_sha = manifest["sha256"]
    expected_size = manifest["sizeBytes"]
    actual_size = artifact_path.stat().st_size
    actual_sha = sha256_file(artifact_path)

    failures: list[str] = []
    if actual_size != expected_size:
        failures.append(f"sizeBytes mismatch: expected {expected_size}, actual {actual_size}")
    if actual_sha != expected_sha:
        failures.append(
            f"sha256 mismatch: expected {expected_sha}, actual {actual_sha}"
        )

    if failures:
        for err in failures:
            print(f"FAIL: {err}", file=sys.stderr)
        print(
            f"artifactId={manifest.get('artifactId')} path={artifact_path}",
            file=sys.stderr,
        )
        return 1

    print(
        "VERIFIED "
        f"artifactId={manifest['artifactId']} "
        f"sha256={actual_sha} "
        f"sizeBytes={actual_size} "
        f"path={artifact_path}"
    )
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Verify local evidence artifact bytes against a tracked evidence-artifact manifest."
    )
    parser.add_argument(
        "--manifest",
        required=True,
        type=Path,
        help="Path to evidence-artifact reference JSON",
    )
    parser.add_argument(
        "--artifact",
        required=True,
        type=Path,
        help="Path to local artifact bytes to verify (no remote fetch)",
    )
    args = parser.parse_args(argv)

    if not args.manifest.is_file():
        print(f"ERROR: manifest not found: {args.manifest}", file=sys.stderr)
        return 2

    return verify(args.manifest.resolve(), args.artifact.resolve())


if __name__ == "__main__":
    sys.exit(main())
