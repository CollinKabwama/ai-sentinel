package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.replay.ReplayDataset;
import dev.aisentinel.core.replay.ReplayRunManifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Downstream-only construction of deterministic detection-evaluation evidence.
 */
public final class DetectionEvaluationEvidenceGenerator {

    static final String EVIDENCE_SCHEMA_VERSION = "1";
    static final String REPORT_KIND = "diagnostic-detection-evaluation";
    static final String THRESHOLD_BOUNDARY = "valid anomalyScore >= anomalyThreshold";

    public DetectionEvaluationEvidence generate(ReplayDataset dataset,
                                                ReferenceEvaluationAlignment alignment,
                                                ReplayRunManifest replayManifest,
                                                DetectionEvaluationMetrics metrics,
                                                TemporalDetectionEvaluation temporal) {
        ReplayDataset safeDataset = Objects.requireNonNull(dataset, "dataset");
        ReferenceEvaluationAlignment safeAlignment = Objects.requireNonNull(alignment, "alignment");
        ReplayRunManifest safeReplayManifest = Objects.requireNonNull(replayManifest, "replayManifest");
        DetectionEvaluationMetrics safeMetrics = Objects.requireNonNull(metrics, "metrics");
        TemporalDetectionEvaluation safeTemporal = Objects.requireNonNull(temporal, "temporal");

        requireConsistentIds(safeDataset, safeAlignment, safeReplayManifest, safeMetrics, safeTemporal);
        DetectionEvaluationEvidence.StructuralCounts counts = structuralCounts(safeAlignment, safeMetrics, safeTemporal);
        return new DetectionEvaluationEvidence(
            EVIDENCE_SCHEMA_VERSION,
            REPORT_KIND,
            new DetectionEvaluationEvidence.ReferenceProvenance(
                safeDataset.manifest().datasetId(),
                safeDataset.manifest().datasetSchemaVersion(),
                safeDataset.manifest().evaluationEventSchemaVersion(),
                safeDataset.manifest().featureSchemaVersion(),
                safeDataset.annotations().schemaVersion(),
                safeDataset.manifest().sourceClassification(),
                safeDataset.manifest().transformationVersion(),
                safeDataset.manifest().ordering(),
                safeDataset.eventsSha256()
            ),
            new DetectionEvaluationEvidence.ReplayProvenance(
                safeReplayManifest.replaySchemaVersion(),
                safeReplayManifest.replayRunId(),
                safeReplayManifest.datasetId(),
                safeReplayManifest.replayMode(),
                safeReplayManifest.scorerId(),
                safeReplayManifest.scorerVersion(),
                safeReplayManifest.policyId(),
                safeReplayManifest.policyVersion(),
                safeReplayManifest.configurationFingerprint(),
                safeReplayManifest.aiSentinelVersion(),
                safeReplayManifest.resultsSha256()
            ),
            new DetectionEvaluationEvidence.ClassificationProvenance(
                safeMetrics.classification().anomalyThreshold(),
                THRESHOLD_BOUNDARY
            ),
            counts,
            safeMetrics,
            safeTemporal,
            limitations(counts)
        );
    }

    private static void requireConsistentIds(ReplayDataset dataset,
                                             ReferenceEvaluationAlignment alignment,
                                             ReplayRunManifest replayManifest,
                                             DetectionEvaluationMetrics metrics,
                                             TemporalDetectionEvaluation temporal) {
        String datasetId = dataset.manifest().datasetId();
        String replayRunId = replayManifest.replayRunId();
        if (!datasetId.equals(alignment.datasetId())
            || !datasetId.equals(replayManifest.datasetId())
            || !datasetId.equals(metrics.datasetId())
            || !datasetId.equals(temporal.datasetId())) {
            throw new IllegalArgumentException("datasetId must reconcile across evaluation results");
        }
        if (!replayRunId.equals(alignment.replayRunId())
            || !replayRunId.equals(metrics.replayRunId())
            || !replayRunId.equals(temporal.replayRunId())) {
            throw new IllegalArgumentException("replayRunId must reconcile across evaluation results");
        }
        if (!dataset.eventsSha256().equals(replayManifest.datasetEventsSha256())) {
            throw new IllegalArgumentException("replay manifest dataset checksum must match replay dataset");
        }
        if (!dataset.manifest().datasetSchemaVersion().equals(replayManifest.datasetSchemaVersion())
            || !dataset.manifest().evaluationEventSchemaVersion().equals(replayManifest.evaluationEventSchemaVersion())
            || !dataset.manifest().featureSchemaVersion().equals(replayManifest.featureSchemaVersion())
            || !dataset.annotations().schemaVersion().equals(replayManifest.annotationSchemaVersion())) {
            throw new IllegalArgumentException("replay manifest schema provenance must match replay dataset");
        }
        if (!metrics.classification().equals(temporal.classification())) {
            throw new IllegalArgumentException("metrics and temporal classification must match");
        }
    }

    private static DetectionEvaluationEvidence.StructuralCounts structuralCounts(ReferenceEvaluationAlignment alignment,
                                                                                 DetectionEvaluationMetrics metrics,
                                                                                 TemporalDetectionEvaluation temporal) {
        long expectedNormal = alignment.observations().stream()
            .filter(observation -> !observation.truth().anomalousExpected())
            .count();
        long expectedAnomalous = alignment.observations().stream()
            .filter(observation -> observation.truth().anomalousExpected())
            .count();
        int anomalySegments = 0;
        int detectedSegments = 0;
        int undetectedSegments = 0;
        int recoveryWindows = 0;
        int stabilizedRecoveries = 0;
        int unstabilizedRecoveries = 0;
        for (ScenarioTemporalEvaluation scenario : temporal.scenarios()) {
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
        return new DetectionEvaluationEvidence.StructuralCounts(
            alignment.referenceEventCount(),
            alignment.scenarioCount(),
            alignment.evaluableObservationCount(),
            expectedNormal,
            expectedAnomalous,
            metrics.evaluablePredictionCount(),
            metrics.excludedPredictionCount(),
            anomalySegments,
            detectedSegments,
            undetectedSegments,
            recoveryWindows,
            stabilizedRecoveries,
            unstabilizedRecoveries
        );
    }

    private static List<String> limitations(DetectionEvaluationEvidence.StructuralCounts counts) {
        List<String> limitations = new ArrayList<>();
        limitations.add("REPORT != BASELINE. This artifact records deterministic diagnostic evaluation evidence only.");
        limitations.add("POLICY ACTION != DETECTOR PREDICTION. Detector classification remains thresholded anomaly-score evaluation.");
        limitations.add("DETECTION DELAY != REQUEST LATENCY. Temporal delay describes ordered evaluation observations, not application latency.");
        if (counts.recoveryWindowCount() == 0) {
            limitations.add("No observed recovery windows are present in this evaluation corpus.");
        }
        return List.copyOf(limitations);
    }
}
