#!/usr/bin/env bash
# Verify local evidence-artifact bytes against a tracked reference manifest.
# Does not fetch, upload, or execute artifact contents.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "${ROOT}/scripts/verify-evidence-artifact.py" "$@"
