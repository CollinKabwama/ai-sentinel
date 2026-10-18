#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$ROOT/ai-sentinel-core"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ai-sentinel-kit-reference.XXXXXX")"
CP_FILE="$TMP_DIR/classpath.txt"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

cd "$ROOT"

mvn -q -pl ai-sentinel-core -DskipTests compile dependency:build-classpath -Dmdep.outputFile="$CP_FILE"

JAVA_CP="$MODULE/target/classes:$(cat "$CP_FILE")"

if [[ "${1:-}" == "--write" ]]; then
  java -cp "$JAVA_CP" dev.aisentinel.core.dataset.corpus.KitReferenceCorpusMain write "$ROOT"
else
  java -cp "$JAVA_CP" dev.aisentinel.core.dataset.corpus.KitReferenceCorpusMain verify "$ROOT"
fi
