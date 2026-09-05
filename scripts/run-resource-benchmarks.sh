#!/usr/bin/env bash
# Opt-in AI-Sentinel resource benchmark runner.
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

if [[ "$MODE" == "redis" || "$MODE" == "full" ]]; then
  docker info >/dev/null
fi

mvn -q -pl ai-sentinel-benchmark -am package -DskipTests

JAR="${ROOT}/ai-sentinel-benchmark/target/benchmarks.jar"
if [[ ! -f "${JAR}" ]]; then
  echo "Missing ${JAR}" >&2
  exit 1
fi

case "${MODE}" in
  smoke|in-process|remote|redis|full)
    exec java -Daisentinel.benchmark.resultsDir="${RESULTS_DIR}" \
      -cp "${JAR}" \
      dev.aisentinel.benchmark.deployment.ResourceBenchmarkMain \
      "${MODE}" "$@"
    ;;
  allocation)
    RUN_DIR="${RESULTS_DIR}/resources/$(python3 - <<'PY'
from datetime import datetime, timezone
print(datetime.now(timezone.utc).isoformat().replace(':', '').replace('.', '-'))
PY
)"
    mkdir -p "${RUN_DIR}"
    OUT="${RUN_DIR}/allocation-jmh.json"
    exec java -Daisentinel.benchmark.resultsDir="${RESULTS_DIR}" -jar "${JAR}" \
      -f 1 -wi 2 -i 3 -w 1s -r 1s \
      -prof gc \
      -rf json \
      -rff "${OUT}" \
      '.*(ScorerLatencyBenchmark|DecisionEngineBenchmark|FeatureExtractionBenchmark|PipelineBenchmark).*' \
      "$@"
    ;;
  *)
    echo "Usage: $0 {smoke|in-process|remote|redis|full|allocation}" >&2
    exit 2
    ;;
esac
