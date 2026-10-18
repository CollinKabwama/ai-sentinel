#!/usr/bin/env bash
set -euo pipefail

# Resolve user-supplied relative paths against the directory from which this script was invoked.
CALLER_DIR="$(pwd)"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$ROOT/ai-sentinel-core"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ai-sentinel-compare-evaluations.XXXXXX")"
CP_FILE="$TMP_DIR/classpath.txt"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

resolve_path() {
  case "$1" in
    /*) printf '%s' "$1" ;;
    *) printf '%s/%s' "$CALLER_DIR" "$1" ;;
  esac
}

ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --baseline|--candidate|--output)
      ARGS+=("$1")
      shift
      if [[ $# -gt 0 && "$1" != -* ]]; then
        ARGS+=("$(resolve_path "$1")")
        shift
      fi
      ;;
    *)
      ARGS+=("$1")
      shift
      ;;
  esac
done

cd "$ROOT"
mvn -q -pl ai-sentinel-core -DskipTests compile dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
JAVA_CP="$MODULE/target/classes:$(cat "$CP_FILE")"

if [[ ${#ARGS[@]} -eq 0 ]]; then
  java -cp "$JAVA_CP" dev.aisentinel.core.evaluation.EvaluationComparisonMain
else
  java -cp "$JAVA_CP" dev.aisentinel.core.evaluation.EvaluationComparisonMain "${ARGS[@]}"
fi
