package dev.aisentinel.core.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Comparison view extracted from official baseline evidence or a fresh evaluation run.
 */
record DetectionReferenceBaselineComparisonSnapshot(
    DetectionEvaluationEvidence.ReferenceProvenance reference,
    DetectionEvaluationEvidence.ReplayProvenance replay,
    DetectionEvaluationEvidence.ClassificationProvenance classification,
    DetectionEvaluationEvidence.StructuralCounts structure,
    DetectionConfusionMatrix confusionMatrix,
    DetectionMetrics metrics,
    List<ScenarioSnapshot> scenarios,
    TemporalSummary temporal
) {
    DetectionReferenceBaselineComparisonSnapshot {
        reference = Objects.requireNonNull(reference, "reference");
        replay = Objects.requireNonNull(replay, "replay");
        classification = Objects.requireNonNull(classification, "classification");
        structure = Objects.requireNonNull(structure, "structure");
        confusionMatrix = Objects.requireNonNull(confusionMatrix, "confusionMatrix");
        metrics = Objects.requireNonNull(metrics, "metrics");
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        temporal = Objects.requireNonNull(temporal, "temporal");
    }

    record ScenarioSnapshot(
        String scenarioId,
        String scenarioCategory,
        long totalObservationCount,
        long evaluablePredictionCount,
        long excludedPredictionCount,
        DetectionConfusionMatrix confusionMatrix,
        DetectionMetrics metrics,
        TemporalSummary temporal
    ) {
        ScenarioSnapshot {
            if (scenarioId == null || scenarioId.isBlank()) {
                throw new IllegalArgumentException("scenarioId is required");
            }
            if (scenarioCategory == null || scenarioCategory.isBlank()) {
                throw new IllegalArgumentException("scenarioCategory is required");
            }
            confusionMatrix = Objects.requireNonNull(confusionMatrix, "confusionMatrix");
            metrics = Objects.requireNonNull(metrics, "metrics");
            temporal = Objects.requireNonNull(temporal, "temporal");
        }
    }

    record TemporalSummary(
        int anomalySegmentCount,
        int detectedSegmentCount,
        int undetectedSegmentCount,
        int immediateDetectionCount,
        int delayedDetectionCount,
        long unavailableObservationCount,
        int recoveryWindowCount
    ) {
        TemporalSummary {
            if (anomalySegmentCount < 0
                || detectedSegmentCount < 0
                || undetectedSegmentCount < 0
                || immediateDetectionCount < 0
                || delayedDetectionCount < 0
                || unavailableObservationCount < 0L
                || recoveryWindowCount < 0) {
                throw new IllegalArgumentException("temporal counts must be >= 0");
            }
        }
    }

    static DetectionReferenceBaselineComparisonSnapshot fromEvidence(DetectionEvaluationEvidence evidence) {
        DetectionEvaluationEvidence safe = Objects.requireNonNull(evidence, "evidence");
        List<ScenarioSnapshot> scenarios = new ArrayList<>();
        TemporalSummary overall = temporalSummary(safe.temporal());
        for (int i = 0; i < safe.metrics().scenarios().size(); i++) {
            ScenarioDetectionMetrics metricScenario = safe.metrics().scenarios().get(i);
            ScenarioTemporalEvaluation temporalScenario = safe.temporal().scenarios().get(i);
            scenarios.add(new ScenarioSnapshot(
                metricScenario.scenarioId(),
                metricScenario.scenarioCategory().name(),
                metricScenario.totalObservationCount(),
                metricScenario.evaluablePredictionCount(),
                metricScenario.excludedPredictionCount(),
                metricScenario.confusionMatrix(),
                metricScenario.metrics(),
                temporalSummary(List.of(temporalScenario))
            ));
        }
        return new DetectionReferenceBaselineComparisonSnapshot(
            safe.reference(),
            safe.replay(),
            safe.classification(),
            safe.counts(),
            safe.metrics().confusionMatrix(),
            safe.metrics().metrics(),
            scenarios,
            overall
        );
    }

    static DetectionReferenceBaselineComparisonSnapshot fromEvaluationJson(String jsonText) {
        Map<String, Object> root = DeterministicJson.requireObject(DeterministicJson.parse(jsonText), "evaluation.json");
        Map<String, Object> reference = DeterministicJson.requireObject(root.get("reference"), "reference");
        Map<String, Object> replay = DeterministicJson.requireObject(root.get("replay"), "replay");
        Map<String, Object> classification = DeterministicJson.requireObject(root.get("classification"), "classification");
        Map<String, Object> counts = DeterministicJson.requireObject(root.get("counts"), "counts");
        Map<String, Object> confusion = DeterministicJson.requireObject(root.get("confusionMatrix"), "confusionMatrix");
        Map<String, Object> metrics = DeterministicJson.requireObject(root.get("metrics"), "metrics");
        List<Object> scenarioMetrics = DeterministicJson.requireArray(root.get("scenarioMetrics"), "scenarioMetrics");
        Map<String, Object> temporal = DeterministicJson.requireObject(root.get("temporal"), "temporal");
        List<Object> temporalScenarios = DeterministicJson.requireArray(temporal.get("scenarios"), "temporal.scenarios");

        DetectionEvaluationEvidence.ReferenceProvenance referenceProvenance =
            new DetectionEvaluationEvidence.ReferenceProvenance(
                DeterministicJson.requireString(reference, "datasetId"),
                DeterministicJson.requireString(reference, "datasetSchemaVersion"),
                DeterministicJson.requireString(reference, "evaluationEventSchemaVersion"),
                DeterministicJson.requireString(reference, "featureSchemaVersion"),
                DeterministicJson.requireString(reference, "annotationSchemaVersion"),
                DeterministicJson.requireString(reference, "sourceClassification"),
                DeterministicJson.requireString(reference, "transformationVersion"),
                DeterministicJson.requireString(reference, "ordering"),
                DeterministicJson.requireString(reference, "eventsSha256")
            );
        DetectionEvaluationEvidence.ReplayProvenance replayProvenance =
            new DetectionEvaluationEvidence.ReplayProvenance(
                DeterministicJson.requireString(replay, "replaySchemaVersion"),
                DeterministicJson.requireString(replay, "replayRunId"),
                DeterministicJson.requireString(replay, "datasetId"),
                DeterministicJson.requireString(replay, "replayMode"),
                DeterministicJson.requireString(replay, "scorerId"),
                DeterministicJson.optionalString(replay, "scorerVersion", ""),
                DeterministicJson.requireString(replay, "policyId"),
                DeterministicJson.optionalString(replay, "policyVersion", ""),
                DeterministicJson.requireString(replay, "configurationFingerprint"),
                DeterministicJson.requireString(replay, "aiSentinelVersion"),
                DeterministicJson.requireString(replay, "resultsSha256")
            );
        DetectionEvaluationEvidence.ClassificationProvenance classificationProvenance =
            new DetectionEvaluationEvidence.ClassificationProvenance(
                DeterministicJson.requireDouble(classification, "anomalyThreshold"),
                DeterministicJson.requireString(classification, "thresholdBoundary")
            );
        DetectionEvaluationEvidence.StructuralCounts structure =
            new DetectionEvaluationEvidence.StructuralCounts(
                DeterministicJson.requireInt(counts, "referenceEventCount"),
                DeterministicJson.requireInt(counts, "scenarioCount"),
                DeterministicJson.requireInt(counts, "alignedObservationCount"),
                DeterministicJson.requireLong(counts, "expectedNormalObservationCount"),
                DeterministicJson.requireLong(counts, "expectedAnomalousObservationCount"),
                DeterministicJson.requireLong(counts, "evaluablePredictionCount"),
                DeterministicJson.requireLong(counts, "excludedPredictionCount"),
                DeterministicJson.requireInt(counts, "anomalySegmentCount"),
                DeterministicJson.requireInt(counts, "detectedSegmentCount"),
                DeterministicJson.requireInt(counts, "undetectedSegmentCount"),
                DeterministicJson.requireInt(counts, "recoveryWindowCount"),
                DeterministicJson.requireInt(counts, "stabilizedRecoveryCount"),
                DeterministicJson.requireInt(counts, "unstabilizedRecoveryCount")
            );
        DetectionConfusionMatrix confusionMatrix = readConfusion(confusion);
        DetectionMetrics detectionMetrics = readMetrics(metrics);

        List<ScenarioSnapshot> scenarios = new ArrayList<>();
        if (scenarioMetrics.size() != temporalScenarios.size()) {
            throw new IllegalArgumentException("scenarioMetrics and temporal scenarios size mismatch");
        }
        for (int i = 0; i < scenarioMetrics.size(); i++) {
            Map<String, Object> scenario =
                DeterministicJson.requireObject(scenarioMetrics.get(i), "scenarioMetrics[" + i + "]");
            Map<String, Object> temporalScenario =
                DeterministicJson.requireObject(temporalScenarios.get(i), "temporal.scenarios[" + i + "]");
            String scenarioId = DeterministicJson.requireString(scenario, "scenarioId");
            if (!scenarioId.equals(DeterministicJson.requireString(temporalScenario, "scenarioId"))) {
                throw new IllegalArgumentException("scenarioId ordering mismatch between metrics and temporal");
            }
            scenarios.add(new ScenarioSnapshot(
                scenarioId,
                DeterministicJson.requireString(scenario, "scenarioCategory"),
                DeterministicJson.requireLong(scenario, "totalObservationCount"),
                DeterministicJson.requireLong(scenario, "evaluablePredictionCount"),
                DeterministicJson.requireLong(scenario, "excludedPredictionCount"),
                readConfusion(DeterministicJson.requireObject(scenario.get("confusionMatrix"), "scenario.confusionMatrix")),
                readMetrics(DeterministicJson.requireObject(scenario.get("metrics"), "scenario.metrics")),
                temporalSummaryFromJson(temporalScenario)
            ));
        }

        TemporalSummary overallTemporal = aggregateTemporal(scenarios);
        return new DetectionReferenceBaselineComparisonSnapshot(
            referenceProvenance,
            replayProvenance,
            classificationProvenance,
            structure,
            confusionMatrix,
            detectionMetrics,
            scenarios,
            overallTemporal
        );
    }

    private static DetectionConfusionMatrix readConfusion(Map<String, Object> confusion) {
        return new DetectionConfusionMatrix(
            DeterministicJson.requireLong(confusion, "truePositives"),
            DeterministicJson.requireLong(confusion, "trueNegatives"),
            DeterministicJson.requireLong(confusion, "falsePositives"),
            DeterministicJson.requireLong(confusion, "falseNegatives")
        );
    }

    private static DetectionMetrics readMetrics(Map<String, Object> metrics) {
        return new DetectionMetrics(
            readMetricValue(DeterministicJson.requireObject(metrics.get("precision"), "precision")),
            readMetricValue(DeterministicJson.requireObject(metrics.get("recall"), "recall")),
            readMetricValue(DeterministicJson.requireObject(metrics.get("f1"), "f1")),
            readMetricValue(DeterministicJson.requireObject(metrics.get("falsePositiveRate"), "falsePositiveRate")),
            readMetricValue(DeterministicJson.requireObject(metrics.get("falseNegativeRate"), "falseNegativeRate"))
        );
    }

    private static DetectionMetricValue readMetricValue(Map<String, Object> value) {
        boolean defined = DeterministicJson.requireBoolean(value, "defined");
        if (!defined) {
            if (value.get("value") != null) {
                throw new IllegalArgumentException("undefined metric must have null value");
            }
            return DetectionMetricValue.undefined();
        }
        return DetectionMetricValue.defined(DeterministicJson.requireDouble(value, "value"));
    }

    private static TemporalSummary temporalSummary(TemporalDetectionEvaluation temporal) {
        return temporalSummary(temporal.scenarios());
    }

    private static TemporalSummary temporalSummary(List<ScenarioTemporalEvaluation> scenarios) {
        int anomaly = 0;
        int detected = 0;
        int undetected = 0;
        int immediate = 0;
        int delayed = 0;
        long unavailable = 0L;
        int recovery = 0;
        for (ScenarioTemporalEvaluation scenario : scenarios) {
            for (TemporalAnomalySegment segment : scenario.anomalySegments()) {
                anomaly++;
                unavailable = Math.addExact(unavailable, segment.unavailableObservationCount());
                if (segment.detected()) {
                    detected++;
                    if (segment.detectionObservationDelay() == 0) {
                        immediate++;
                    } else {
                        delayed++;
                    }
                } else {
                    undetected++;
                }
                if (segment.recovery() != null) {
                    recovery++;
                    unavailable = Math.addExact(unavailable, segment.recovery().unavailableObservationCount());
                }
            }
        }
        return new TemporalSummary(anomaly, detected, undetected, immediate, delayed, unavailable, recovery);
    }

    private static TemporalSummary temporalSummaryFromJson(Map<String, Object> scenario) {
        List<Object> segments = DeterministicJson.requireArray(scenario.get("anomalySegments"), "anomalySegments");
        int anomaly = segments.size();
        int detected = 0;
        int undetected = 0;
        int immediate = 0;
        int delayed = 0;
        long unavailable = 0L;
        int recovery = 0;
        for (Object raw : segments) {
            Map<String, Object> segment = DeterministicJson.requireObject(raw, "anomalySegment");
            unavailable = Math.addExact(
                unavailable,
                DeterministicJson.requireLong(segment, "unavailableObservationCount")
            );
            boolean isDetected = DeterministicJson.requireBoolean(segment, "detected");
            if (isDetected) {
                detected++;
                Object delay = segment.get("detectionObservationDelay");
                if (!(delay instanceof Number number)) {
                    throw new IllegalArgumentException("detected segment requires detectionObservationDelay");
                }
                if (number.intValue() == 0) {
                    immediate++;
                } else {
                    delayed++;
                }
            } else {
                undetected++;
            }
            if (segment.get("recovery") != null) {
                recovery++;
                Map<String, Object> recoveryObject =
                    DeterministicJson.requireObject(segment.get("recovery"), "recovery");
                unavailable = Math.addExact(
                    unavailable,
                    DeterministicJson.requireLong(recoveryObject, "unavailableObservationCount")
                );
            }
        }
        return new TemporalSummary(anomaly, detected, undetected, immediate, delayed, unavailable, recovery);
    }

    private static TemporalSummary aggregateTemporal(List<ScenarioSnapshot> scenarios) {
        int anomaly = 0;
        int detected = 0;
        int undetected = 0;
        int immediate = 0;
        int delayed = 0;
        long unavailable = 0L;
        int recovery = 0;
        for (ScenarioSnapshot scenario : scenarios) {
            TemporalSummary temporal = scenario.temporal();
            anomaly = Math.addExact(anomaly, temporal.anomalySegmentCount());
            detected = Math.addExact(detected, temporal.detectedSegmentCount());
            undetected = Math.addExact(undetected, temporal.undetectedSegmentCount());
            immediate = Math.addExact(immediate, temporal.immediateDetectionCount());
            delayed = Math.addExact(delayed, temporal.delayedDetectionCount());
            unavailable = Math.addExact(unavailable, temporal.unavailableObservationCount());
            recovery = Math.addExact(recovery, temporal.recoveryWindowCount());
        }
        return new TemporalSummary(anomaly, detected, undetected, immediate, delayed, unavailable, recovery);
    }
}
