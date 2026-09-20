#!/bin/sh
# Thin launcher for the existing generated-corpus Evaluation Kit CLI.
# Uses exec so the Java process receives signals and the container exit code
# matches GeneratedCorpusEvaluationMain.
set -eu

CLASSPATH="/opt/ai-sentinel/ai-sentinel-core.jar"
for jar in /opt/ai-sentinel/lib/*.jar; do
  CLASSPATH="${CLASSPATH}:${jar}"
done

exec java -cp "${CLASSPATH}" \
  dev.aisentinel.core.evaluation.GeneratedCorpusEvaluationMain \
  "$@"
