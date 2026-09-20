#!/usr/bin/env bash
# Opt-in AI-Sentinel deployment/degradation benchmark runner.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"
if [[ -z "${JAVA_HOME}" ]]; then
  echo "JAVA_HOME not set and JDK 21 not found" >&2
  exit 1
fi

MODE="${1:-smoke}"
shift || true

RESULTS_DIR="${AISENTINEL_BENCHMARK_RESULTS_DIR:-${ROOT}/ai-sentinel-benchmark/results}"

mvn -q -pl ai-sentinel-benchmark -am package -DskipTests

JAR="${ROOT}/ai-sentinel-benchmark/target/benchmarks.jar"
if [[ ! -f "${JAR}" ]]; then
  echo "Missing ${JAR}" >&2
  exit 1
fi

case "${MODE}" in
  smoke|remote|redis|degradation|full)
    exec java -Daisentinel.benchmark.resultsDir="${RESULTS_DIR}" \
      -cp "${JAR}" \
      dev.aisentinel.benchmark.deployment.DeploymentBenchmarkMain \
      "${MODE}" "$@"
    ;;
  *)
    echo "Usage: $0 {smoke|remote|redis|degradation|full}" >&2
    exit 2
    ;;
esac
