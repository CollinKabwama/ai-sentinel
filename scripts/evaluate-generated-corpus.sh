#!/usr/bin/env bash
set -euo pipefail

# Capture the caller's directory before switching to the repository root below, so relative
# --corpus/--dataset/--output paths are resolved against where the user actually ran this script from,
# not against the repository root the script itself needs to build/run from.
CALLER_DIR="$(pwd)"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$ROOT/ai-sentinel-core"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ai-sentinel-evaluate-generated-corpus.XXXXXX")"
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
    --corpus|--output|--dataset)
      ARGS+=("$1")
      shift
      # Only resolve a genuine value; a following "-"-prefixed token is left untouched so the
      # Java-side parser still reports "missing value" for it, exactly as it would without this
      # rewrite.
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

# Forward no arguments explicitly so the Java CLI handles usage consistently: expanding an empty
# array with "${ARGS[@]}" under `set -u` is treated as an unbound variable on bash versions before
# 4.4 (including the bash 3.2 shipped as /bin/bash on macOS).
#
# Invoke Java without replacing this shell (no `exec`) so the EXIT trap can remove TMP_DIR after
# the process returns. Java's exit status is preserved by the shell under `set -e`.
if [[ ${#ARGS[@]} -eq 0 ]]; then
  java -cp "$JAVA_CP" dev.aisentinel.core.evaluation.GeneratedCorpusEvaluationMain
else
  java -cp "$JAVA_CP" dev.aisentinel.core.evaluation.GeneratedCorpusEvaluationMain "${ARGS[@]}"
fi
