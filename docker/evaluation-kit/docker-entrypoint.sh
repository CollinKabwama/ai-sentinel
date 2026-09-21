#!/bin/sh
# Thin launcher for Evaluation Kit evaluation and comparison CLIs.
# Uses exec so the Java process receives signals and the container exit code
# matches GeneratedCorpusEvaluationMain.
set -eu

CLASSPATH="/opt/ai-sentinel/ai-sentinel-core.jar"
for jar in /opt/ai-sentinel/lib/*.jar; do
  CLASSPATH="${CLASSPATH}:${jar}"
done

MAIN="dev.aisentinel.core.evaluation.GeneratedCorpusEvaluationMain"
for arg in "$@"; do
  if [ "${arg}" = "--baseline" ]; then
    MAIN="dev.aisentinel.core.evaluation.EvaluationComparisonMain"
    break
  fi
done

exec java -cp "${CLASSPATH}" "${MAIN}" "$@"
