#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"
if [[ -z "${JAVA_HOME}" ]]; then
  echo "JAVA_HOME not set and JDK 21 not found" >&2
  exit 2
fi

mvn -q -pl ai-sentinel-benchmark -am package -DskipTests

JAR="${ROOT}/ai-sentinel-benchmark/target/benchmarks.jar"
if [[ ! -f "${JAR}" ]]; then
  echo "Missing ${JAR}" >&2
  exit 2
fi

exec java -cp "${JAR}" dev.aisentinel.benchmark.compare.BenchmarkComparisonMain "$@"
