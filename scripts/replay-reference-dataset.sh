#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$ROOT/ai-sentinel-core"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ai-sentinel-reference-replay.XXXXXX")"
CP_FILE="$TMP_DIR/classpath.txt"

cleanup() {
  if [[ "${KEEP_OUTPUT:-0}" != "1" ]]; then
    rm -rf "$TMP_DIR"
  fi
}
trap cleanup EXIT

OUTPUT_DIR="${1:-$TMP_DIR/output}"
if [[ "${2:-}" == "--keep-tmp" || "${1:-}" == "--keep-tmp" ]]; then
  KEEP_OUTPUT=1
fi

cd "$ROOT"

mvn -q -pl ai-sentinel-core -DskipTests compile dependency:build-classpath -Dmdep.outputFile="$CP_FILE"

JAVA_CP="$MODULE/target/classes:$(cat "$CP_FILE")"
java -cp "$JAVA_CP" dev.aisentinel.core.replay.ReferenceDatasetReplayMain "$OUTPUT_DIR"

echo "Replay artifacts written to $OUTPUT_DIR"
