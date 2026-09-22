#!/usr/bin/env bash
# Level-3 same-framework comparison: statistical vs offline Isolation Forest reference.
#
# Produces per-target statistical and isolation-forest-reference Evaluation Kit evidence
# plus comparison.json / comparison.html. Factual deltas only — not a winner ranking.
set -euo pipefail

CALLER_DIR="$(pwd)"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="${ROOT}/ai-sentinel-core"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ai-sentinel-compare-reference-detectors.XXXXXX")"
CP_FILE="${TMP_DIR}/classpath.txt"

cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

resolve_path() {
  case "$1" in
    /*) printf '%s' "$1" ;;
    *) printf '%s/%s' "${CALLER_DIR}" "$1" ;;
  esac
}

usage() {
  cat <<'EOF'
Usage:
  compare-reference-detectors.sh --output <new-directory>

Compares the statistical Evaluation Kit path against the offline Isolation Forest
reference scorer on kit.established-normal, kit.abrupt-burst, and kit.recovery.

Prerequisites:
  - JDK 21
  - Maven (mvn)
  - repository checkout with evaluation/kit-reference corpora

Same-origin disclosure:
  Both detectors are implemented in this repository. This demonstrates evaluation
  mechanics and algorithmic behavior; it is not an independent third-party benchmark.
EOF
}

OUTPUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --output)
      shift
      if [[ $# -eq 0 || "$1" == -* ]]; then
        echo "ERROR: --output requires a directory path" >&2
        exit 2
      fi
      OUTPUT="$(resolve_path "$1")"
      shift
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "${OUTPUT}" ]]; then
  echo "ERROR: --output is required" >&2
  usage >&2
  exit 2
fi

if [[ -e "${OUTPUT}" ]]; then
  echo "ERROR: output directory already exists (refusing overwrite): ${OUTPUT}" >&2
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: java not found on PATH. JDK 21 is required." >&2
  exit 2
fi

if ! java -version 2>&1 | head -n 1 | grep -Eq 'version "21(\.|")'; then
  echo "ERROR: JDK 21 is required. Detected: $(java -version 2>&1 | head -n 1)" >&2
  exit 2
fi

if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: mvn not found on PATH." >&2
  exit 2
fi

cd "${ROOT}"
mvn -q -pl ai-sentinel-core -DskipTests compile dependency:build-classpath -Dmdep.outputFile="${CP_FILE}"
JAVA_CP="${MODULE}/target/classes:$(cat "${CP_FILE}")"

java -cp "${JAVA_CP}" \
  dev.aisentinel.core.evaluation.SameFrameworkDetectorComparisonMain \
  --output "${OUTPUT}"
