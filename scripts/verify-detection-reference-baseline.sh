#!/usr/bin/env bash
# Verify current repository truth against the Official Detection Reference Baseline.
# Uses DetectionReferenceBaselineConfiguration.officialReference() → threshold 0.5.
# Does NOT modify official baseline artifacts. Drift means difference, not defect.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$ROOT/ai-sentinel-core"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ai-sentinel-detection-baseline-verify.XXXXXX")"
CP_FILE="$TMP_DIR/classpath.txt"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

BASELINE_DIR="${1:-$ROOT/evaluation/detection-reference-baseline}"
OUTPUT_DIR="${2:-}"

cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"

mvn -q -pl ai-sentinel-core -DskipTests compile dependency:build-classpath -Dmdep.outputFile="$CP_FILE"

JAVA_CP="$MODULE/target/classes:$(cat "$CP_FILE")"
ARGS=(--baseline "$BASELINE_DIR")
if [[ -n "$OUTPUT_DIR" ]]; then
  ARGS+=(--output "$OUTPUT_DIR")
fi

set +e
java -cp "$JAVA_CP" \
  dev.aisentinel.core.evaluation.ReferenceDetectionBaselineVerifyMain \
  "${ARGS[@]}"
STATUS=$?
set -e

echo "Official Detection Reference Baseline verification finished with exit status $STATUS"
echo "Exit semantics: 0=MATCH, 1=DRIFT_DETECTED, 2=BASELINE_INTEGRITY_FAILURE, 3=CURRENT_EVALUATION_FAILURE, 4=invalid usage"
exit "$STATUS"
