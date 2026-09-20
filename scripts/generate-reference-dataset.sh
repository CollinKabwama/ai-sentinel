#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$ROOT/ai-sentinel-core"
TRACKED_DIR="$ROOT/evaluation/reference"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ai-sentinel-reference-dataset.XXXXXX")"
CP_FILE="$TMP_DIR/classpath.txt"
GENERATED_DIR="$TMP_DIR/generated"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

cd "$ROOT"

mvn -q -pl ai-sentinel-core -DskipTests compile dependency:build-classpath -Dmdep.outputFile="$CP_FILE"

JAVA_CP="$MODULE/target/classes:$(cat "$CP_FILE")"
java -cp "$JAVA_CP" dev.aisentinel.core.dataset.reference.ReferenceDatasetGeneratorMain "$GENERATED_DIR"

if [[ "${1:-}" == "--write" ]]; then
  rm -rf "$TRACKED_DIR"
  mkdir -p "$TRACKED_DIR"
  cp "$GENERATED_DIR/manifest.json" "$TRACKED_DIR/manifest.json"
  cp "$GENERATED_DIR/events.jsonl" "$TRACKED_DIR/events.jsonl"
  cp "$GENERATED_DIR/annotations.json" "$TRACKED_DIR/annotations.json"
  echo "Reference dataset refreshed at $TRACKED_DIR"
else
  diff -ru "$TRACKED_DIR" "$GENERATED_DIR"
  echo "Reference dataset matches deterministic regeneration."
fi
