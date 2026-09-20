#!/usr/bin/env bash
# Opt-in AI-Sentinel JMH runner. Does not run during normal mvn test/verify.
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

mvn -q -pl ai-sentinel-benchmark -am package -DskipTests

JAR="${ROOT}/ai-sentinel-benchmark/target/benchmarks.jar"
if [[ ! -f "${JAR}" ]]; then
  echo "Missing ${JAR}" >&2
  exit 1
fi

RESULTS_DIR="${AISENTINEL_BENCHMARK_RESULTS_DIR:-${ROOT}/ai-sentinel-benchmark/results}"
mkdir -p "${RESULTS_DIR}"
JAVA_ARGS=(-Daisentinel.benchmark.resultsDir="${RESULTS_DIR}")

# Official reference JMH settings (not smoke).
# forks=2, warmup 5×1s, measurement 5×1s — controlled but finite wall time.
REFERENCE_JMH_ARGS=(-f 2 -wi 5 -i 5 -w 1s -r 1s)

case "${MODE}" in
  smoke)
    exec java "${JAVA_ARGS[@]}" -jar "${JAR}" "$@"
    ;;
  full)
    exec java "${JAVA_ARGS[@]}" -jar "${JAR}" \
      -f 1 -wi 3 -i 5 -w 1s -r 1s \
      "$@"
    ;;
  reference)
    echo "Running REFERENCE capture profile into ${RESULTS_DIR}" >&2
    echo "JMH: ${REFERENCE_JMH_ARGS[*]} $*" >&2
    exec java "${JAVA_ARGS[@]}" -jar "${JAR}" \
      "${REFERENCE_JMH_ARGS[@]}" \
      "$@"
    ;;
  scorer)
    exec java "${JAVA_ARGS[@]}" -jar "${JAR}" ".*ScorerLatencyBenchmark.*" "$@"
    ;;
  engine)
    exec java "${JAVA_ARGS[@]}" -jar "${JAR}" ".*DecisionEngineBenchmark.*" "$@"
    ;;
  features)
    exec java "${JAVA_ARGS[@]}" -jar "${JAR}" ".*FeatureExtractionBenchmark.*" "$@"
    ;;
  pipeline)
    exec java "${JAVA_ARGS[@]}" -jar "${JAR}" ".*PipelineBenchmark.*" "$@"
    ;;
  cardinality)
    exec java "${JAVA_ARGS[@]}" -jar "${JAR}" ".*IdentityCardinalityBenchmark.*" "$@"
    ;;
  *)
    echo "Usage: $0 {smoke|full|reference|scorer|engine|features|pipeline|cardinality} [jmh-args...]" >&2
    exit 2
    ;;
esac
