#!/usr/bin/env bash
# Capture the Official Detection Reference Baseline (detection effectiveness evidence).
# Uses DetectionReferenceBaselineConfiguration.officialReference() → threshold 0.5.
# This is NOT a general evaluator default and is NOT the performance reference baseline.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$ROOT/ai-sentinel-core"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ai-sentinel-detection-baseline.XXXXXX")"
CP_FILE="$TMP_DIR/classpath.txt"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

OUTPUT_DIR="${1:-$ROOT/evaluation/detection-reference-baseline}"

cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"

mvn -q -pl ai-sentinel-core -DskipTests compile dependency:build-classpath -Dmdep.outputFile="$CP_FILE"

JAVA_CP="$MODULE/target/classes:$(cat "$CP_FILE")"
java -cp "$JAVA_CP" \
  dev.aisentinel.core.evaluation.ReferenceDetectionBaselineMain \
  --output "$OUTPUT_DIR"

echo "Official Detection Reference Baseline written to $OUTPUT_DIR"
echo "Reference classification threshold: 0.5 (baseline configuration only; not a general evaluator default)"
