#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RESULTS_DIR="${AISENTINEL_BENCHMARK_RESULTS_DIR:-${ROOT}/ai-sentinel-benchmark/results/regression-candidate}"
BASELINE="${1:-${ROOT}/docs/performance/reference-baseline.json}"

mkdir -p "${RESULTS_DIR}"
AISENTINEL_BENCHMARK_RESULTS_DIR="${RESULTS_DIR}" ./scripts/run-benchmarks.sh reference

CANDIDATE="$(python3 - <<PY
from pathlib import Path
root = Path(r"${RESULTS_DIR}")
files = sorted(root.glob("jmh-*.json"), key=lambda p: p.stat().st_mtime, reverse=True)
if not files:
    raise SystemExit(1)
print(files[0])
PY
)"

exec ./scripts/compare-benchmarks.sh \
  --family jmh \
  --baseline "${BASELINE}" \
  --candidate "${CANDIDATE}" \
  --output "${ROOT}/ai-sentinel-benchmark/results/comparisons/jmh-reference-comparison.json"
