#!/usr/bin/env bash
# Capture three controlled AI-Sentinel 0.3.0 reference benchmark runs.
# Raw outputs stay under ai-sentinel-benchmark/results/reference-capture/ (gitignored).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"
BASE="${ROOT}/ai-sentinel-benchmark/results/reference-capture"
mkdir -p "${BASE}"

CAPTURE_NOTES="${BASE}/capture-notes.txt"
{
  echo "captureStartedUtc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "gitCommit=$(git rev-parse HEAD)"
  echo "sentinelVersion=0.3.0"
  echo "hostClass=developer-laptop"
  echo "cpu=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown)"
  echo "logicalCpus=$(sysctl -n hw.logicalcpu 2>/dev/null || echo unknown)"
  echo "memBytes=$(sysctl -n hw.memsize 2>/dev/null || echo unknown)"
  echo "power=$(pmset -g batt 2>/dev/null | head -1 | tr '\n' ' ' || echo unknown)"
  echo "java=$("${JAVA_HOME}/bin/java" -version 2>&1 | tr '\n' ' ')"
  echo "jmhProfile=reference (-f 2 -wi 5 -i 5 -w 1s -r 1s)"
  echo "preparation=minimized concurrent builds; no concurrent mvn verify; documented power state as measured"
} > "${CAPTURE_NOTES}"

for i in 01 02 03; do
  RUN_DIR="${BASE}/run-${i}"
  mkdir -p "${RUN_DIR}"
  echo "=== REFERENCE RUN ${i} -> ${RUN_DIR} ==="
  AISENTINEL_BENCHMARK_RESULTS_DIR="${RUN_DIR}" \
    "${ROOT}/scripts/run-benchmarks.sh" reference
  # Keep a stable copy name for the latest files in the run dir.
  latest_manifest=$(ls -t "${RUN_DIR}"/manifest-*.json | head -1)
  latest_jmh=$(ls -t "${RUN_DIR}"/jmh-*.json | head -1)
  cp "${latest_manifest}" "${RUN_DIR}/manifest.json"
  cp "${latest_jmh}" "${RUN_DIR}/jmh.json"
  echo "run-${i} complete: ${latest_jmh}"
done

{
  echo "captureFinishedUtc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} >> "${CAPTURE_NOTES}"

echo "All reference runs complete under ${BASE}"
