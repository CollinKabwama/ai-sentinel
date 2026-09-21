#!/usr/bin/env bash
# Verify reproduced Evaluation Kit evidence against the tracked reproduction package.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "${ROOT}/scripts/verify-reproduced-evidence.py" "$@"
