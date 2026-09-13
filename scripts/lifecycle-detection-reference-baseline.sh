#!/usr/bin/env bash
# Official Detection Reference Baseline lifecycle helper.
# Delegates to ReferenceDetectionBaselineLifecycleMain. Does not mutate via verify.
# No --force / --overwrite / threshold tuning.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$ROOT/ai-sentinel-core"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ai-sentinel-detection-baseline-lifecycle.XXXXXX")"
CP_FILE="$TMP_DIR/classpath.txt"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <create-candidate|approve-candidate|reject-candidate|promote-candidate|rollback|list-history> [args...]" >&2
  exit 1
fi

COMMAND="$1"
shift

cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"

mvn -q -pl ai-sentinel-core -DskipTests compile dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
JAVA_CP="$MODULE/target/classes:$(cat "$CP_FILE")"

set +e
java -cp "$JAVA_CP" \
  dev.aisentinel.core.evaluation.ReferenceDetectionBaselineLifecycleMain \
  "$COMMAND" \
  "$@"
STATUS=$?
set -e

echo "Official Detection Reference Baseline lifecycle finished with exit status $STATUS"
echo "Exit semantics: 0=success, 1=invalid usage, 2=lifecycle failure"
exit "$STATUS"
