#!/usr/bin/env bash
# Verify reference-performance baseline selection-analysis evidence integrity.
# Compares the tracked selection-analysis artifact against analysisSha256 in
# docs/performance/reference-baseline.json. Does not recapture benchmarks.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "${ROOT}/scripts/verify-reference-performance-baseline-evidence.py" "$@"
