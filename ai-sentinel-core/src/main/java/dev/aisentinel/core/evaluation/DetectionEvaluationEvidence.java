package dev.aisentinel.core.evaluation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic evidence for one completed offline detection evaluation run.
 */
public record DetectionEvaluationEvidence(
    String evidenceSchemaVersion,
    String reportKind,
    ReferenceProvenance reference,
    ReplayProvenance replay,
    ClassificationProvenance classification,
    StructuralCounts counts,
    DetectionEvaluationMetrics metrics,
    TemporalDetectionEvaluation temporal,
    List<String> limitations
) {
    public DetectionEvaluationEvidence {
        evidenceSchemaVersion = requireNotBlank("evidenceSchemaVersion", evidenceSchemaVersion);
        reportKind = requireNotBlank("reportKind", reportKind);
        reference = Objects.requireNonNull(reference, "reference");
        replay = Objects.requireNonNull(replay, "replay");
        classification = Objects.requireNonNull(classification, "classification");
        counts = Objects.requireNonNull(counts, "counts");
        metrics = Objects.requireNonNull(metrics, "metrics");
        temporal = Objects.requireNonNull(temporal, "temporal");
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        for (String limitation : limitations) {
            requireNotBlank("limitation", limitation);
        }
        if (!reference.datasetId().equals(metrics.datasetId())
            || !reference.datasetId().equals(temporal.datasetId())
            || !reference.datasetId().equals(replay.datasetId())) {
            throw new IllegalArgumentException("datasetId must reconcile across evidence sections");
        }
        if (!replay.replayRunId().equals(metrics.replayRunId())
            || !replay.replayRunId().equals(temporal.replayRunId())) {
            throw new IllegalArgumentException("replayRunId must reconcile across evidence sections");
        }
        if (Double.compare(classification.anomalyThreshold(), metrics.classification().anomalyThreshold()) != 0
            || Double.compare(classification.anomalyThreshold(), temporal.classification().anomalyThreshold()) != 0) {
            throw new IllegalArgumentException("classification threshold must reconcile across evidence sections");
        }
        if (counts.scenarioCount() != metrics.scenarios().size() || counts.scenarioCount() != temporal.scenarios().size()) {
            throw new IllegalArgumentException("scenarioCount must reconcile across evidence sections");
        }
        if (counts.alignedObservationCount() != metrics.totalObservationCount()) {
            throw new IllegalArgumentException("alignedObservationCount must match metrics totalObservationCount");
        }
        if (counts.evaluablePredictionCount() != metrics.evaluablePredictionCount()
            || counts.excludedPredictionCount() != metrics.excludedPredictionCount()) {
            throw new IllegalArgumentException("prediction counts must match metrics");
        }
        if (counts.alignedObservationCount() != Math.addExact(counts.expectedNormalObservationCount(), counts.expectedAnomalousObservationCount())) {
            throw new IllegalArgumentException("alignedObservationCount must equal expected normal + expected anomalous");
        }
        if (counts.referenceEventCount() < counts.alignedObservationCount()) {
            throw new IllegalArgumentException("referenceEventCount must be >= alignedObservationCount");
        }
        List<String> metricScenarioIds = metrics.scenarios().stream().map(ScenarioDetectionMetrics::scenarioId).toList();
        List<String> temporalScenarioIds = temporal.scenarios().stream().map(ScenarioTemporalEvaluation::scenarioId).toList();
        if (!metricScenarioIds.equals(temporalScenarioIds)) {
            throw new IllegalArgumentException("metric and temporal scenario ordering must match");
        }
        Set<String> scenarioIds = new LinkedHashSet<>();
        for (String scenarioId : metricScenarioIds) {
            if (!scenarioIds.add(scenarioId)) {
                throw new IllegalArgumentException("duplicate scenarioId in evidence");
            }
        }
        requireScenarioConsistency(metrics.scenarios(), temporal.scenarios());
        requireTemporalCounts(counts, temporal.scenarios());
    }

    /**
     * Provenance for the reference dataset and annotation material used by evaluation.
     */
    public record ReferenceProvenance(
        String datasetId,
        String datasetSchemaVersion,
        String evaluationEventSchemaVersion,
        String featureSchemaVersion,
        String annotationSchemaVersion,
        String sourceClassification,
        String transformationVersion,
        String ordering,
        String eventsSha256
    ) {
        public ReferenceProvenance {
            datasetId = requireNotBlank("datasetId", datasetId);
            datasetSchemaVersion = requireNotBlank("datasetSchemaVersion", datasetSchemaVersion);
            evaluationEventSchemaVersion = requireNotBlank("evaluationEventSchemaVersion", evaluationEventSchemaVersion);
            featureSchemaVersion = requireNotBlank("featureSchemaVersion", featureSchemaVersion);
            annotationSchemaVersion = requireNotBlank("annotationSchemaVersion", annotationSchemaVersion);
            sourceClassification = requireNotBlank("sourceClassification", sourceClassification);
            transformationVersion = requireNotBlank("transformationVersion", transformationVersion);
            ordering = requireNotBlank("ordering", ordering);
            eventsSha256 = requireSha256("eventsSha256", eventsSha256);
        }
    }

    /**
     * Provenance for deterministic replay output consumed by evaluation.
     */
    public record ReplayProvenance(
        String replaySchemaVersion,
        String replayRunId,
        String datasetId,
        String replayMode,
        String scorerId,
        String scorerVersion,
        String policyId,
        String policyVersion,
        String configurationFingerprint,
        String aiSentinelVersion,
        String resultsSha256
    ) {
        public ReplayProvenance {
            replaySchemaVersion = requireNotBlank("replaySchemaVersion", replaySchemaVersion);
            replayRunId = requireNotBlank("replayRunId", replayRunId);
            datasetId = requireNotBlank("datasetId", datasetId);
            replayMode = requireNotBlank("replayMode", replayMode);
            scorerId = requireNotBlank("scorerId", scorerId);
            scorerVersion = scorerVersion == null ? "" : scorerVersion;
            policyId = requireNotBlank("policyId", policyId);
            policyVersion = policyVersion == null ? "" : policyVersion;
            configurationFingerprint = requireSha256("configurationFingerprint", configurationFingerprint);
            aiSentinelVersion = requireNotBlank("aiSentinelVersion", aiSentinelVersion);
            resultsSha256 = requireSha256("resultsSha256", resultsSha256);
        }
    }

    /**
     * Explicit detector classification provenance for one evaluation run.
     */
    public record ClassificationProvenance(
        double anomalyThreshold,
        String thresholdBoundary
    ) {
        public ClassificationProvenance {
            if (!Double.isFinite(anomalyThreshold) || anomalyThreshold < 0.0 || anomalyThreshold > 1.0) {
                throw new IllegalArgumentException("anomalyThreshold must be finite in [0,1]");
            }
            thresholdBoundary = requireNotBlank("thresholdBoundary", thresholdBoundary);
        }
    }

    /**
     * Structural counts that summarize the evaluation evidence.
     */
    public record StructuralCounts(
        int referenceEventCount,
        int scenarioCount,
        int alignedObservationCount,
        long expectedNormalObservationCount,
        long expectedAnomalousObservationCount,
        long evaluablePredictionCount,
        long excludedPredictionCount,
        int anomalySegmentCount,
        int detectedSegmentCount,
        int undetectedSegmentCount,
        int recoveryWindowCount,
        int stabilizedRecoveryCount,
        int unstabilizedRecoveryCount
    ) {
        public StructuralCounts {
            if (referenceEventCount < 0
                || scenarioCount < 0
                || alignedObservationCount < 0
                || expectedNormalObservationCount < 0L
                || expectedAnomalousObservationCount < 0L
                || evaluablePredictionCount < 0L
                || excludedPredictionCount < 0L
                || anomalySegmentCount < 0
                || detectedSegmentCount < 0
                || undetectedSegmentCount < 0
                || recoveryWindowCount < 0
                || stabilizedRecoveryCount < 0
                || unstabilizedRecoveryCount < 0) {
                throw new IllegalArgumentException("structural counts must be >= 0");
            }
            if ((long) alignedObservationCount != Math.addExact(expectedNormalObservationCount, expectedAnomalousObservationCount)) {
                throw new IllegalArgumentException("alignedObservationCount must equal expected normal + expected anomalous");
            }
            if ((long) alignedObservationCount != Math.addExact(evaluablePredictionCount, excludedPredictionCount)) {
                throw new IllegalArgumentException("alignedObservationCount must equal evaluable + excluded");
            }
            if (anomalySegmentCount != Math.addExact(detectedSegmentCount, undetectedSegmentCount)) {
                throw new IllegalArgumentException("anomalySegmentCount must equal detected + undetected");
            }
            if (recoveryWindowCount != Math.addExact(stabilizedRecoveryCount, unstabilizedRecoveryCount)) {
                throw new IllegalArgumentException("recoveryWindowCount must equal stabilized + unstabilized");
            }
        }
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static void requireScenarioConsistency(List<ScenarioDetectionMetrics> metricScenarios,
                                                   List<ScenarioTemporalEvaluation> temporalScenarios) {
        for (int i = 0; i < metricScenarios.size(); i++) {
            ScenarioDetectionMetrics metricScenario = metricScenarios.get(i);
            ScenarioTemporalEvaluation temporalScenario = temporalScenarios.get(i);
            if (metricScenario.scenarioCategory() != temporalScenario.scenarioCategory()) {
                throw new IllegalArgumentException("scenario categories must match across metrics and temporal sections");
            }
            if (metricScenario.totalObservationCount() != temporalScenario.observationCount()) {
                throw new IllegalArgumentException("scenario observation counts must match across metrics and temporal sections");
            }
        }
    }

    private static void requireTemporalCounts(StructuralCounts counts, List<ScenarioTemporalEvaluation> scenarios) {
        int anomalySegments = 0;
        int detectedSegments = 0;
        int undetectedSegments = 0;
        int recoveryWindows = 0;
        int stabilizedRecoveries = 0;
        int unstabilizedRecoveries = 0;
        for (ScenarioTemporalEvaluation scenario : scenarios) {
            anomalySegments = Math.addExact(anomalySegments, scenario.anomalySegments().size());
            for (TemporalAnomalySegment segment : scenario.anomalySegments()) {
                if (segment.detected()) {
                    detectedSegments = Math.addExact(detectedSegments, 1);
                } else {
                    undetectedSegments = Math.addExact(undetectedSegments, 1);
                }
                if (segment.recovery() != null) {
                    recoveryWindows = Math.addExact(recoveryWindows, 1);
                    if (segment.recovery().stabilized()) {
                        stabilizedRecoveries = Math.addExact(stabilizedRecoveries, 1);
                    } else {
                        unstabilizedRecoveries = Math.addExact(unstabilizedRecoveries, 1);
                    }
                }
            }
        }
        if (counts.anomalySegmentCount() != anomalySegments
            || counts.detectedSegmentCount() != detectedSegments
            || counts.undetectedSegmentCount() != undetectedSegments) {
            throw new IllegalArgumentException("temporal segment counts must match temporal evidence");
        }
        if (counts.recoveryWindowCount() != recoveryWindows
            || counts.stabilizedRecoveryCount() != stabilizedRecoveries
            || counts.unstabilizedRecoveryCount() != unstabilizedRecoveries) {
            throw new IllegalArgumentException("recovery counts must match temporal evidence");
        }
    }

    private static String requireSha256(String field, String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be 64 lowercase hex characters");
        }
        return value;
    }
}
