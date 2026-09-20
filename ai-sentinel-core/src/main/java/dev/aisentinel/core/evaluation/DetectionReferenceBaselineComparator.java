package dev.aisentinel.core.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exact deterministic comparison of baseline vs current evaluation snapshots.
 */
final class DetectionReferenceBaselineComparator {

    private DetectionReferenceBaselineComparator() {
    }

    static List<DetectionReferenceBaselineDriftEntry> compare(
        DetectionReferenceBaselineManifest baselineManifest,
        DetectionReferenceBaselineComparisonSnapshot baseline,
        DetectionReferenceBaselineComparisonSnapshot current,
        String baselineAnnotationsSha256,
        String currentAnnotationsSha256,
        String baselineJsonSha256,
        String currentJsonSha256,
        String baselineMarkdownSha256,
        String currentMarkdownSha256,
        boolean jsonBytesEqual,
        boolean markdownBytesEqual
    ) {
        Objects.requireNonNull(baselineManifest, "baselineManifest");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(current, "current");
        List<DetectionReferenceBaselineDriftEntry> drifts = new ArrayList<>();

        compareDataset(baseline.reference(), current.reference(), drifts);
        compareEqual(
            DetectionReferenceBaselineDriftCategory.ANNOTATION_PROVENANCE,
            "annotationSchemaVersion",
            baseline.reference().annotationSchemaVersion(),
            current.reference().annotationSchemaVersion(),
            drifts
        );
        compareEqual(
            DetectionReferenceBaselineDriftCategory.ANNOTATION_PROVENANCE,
            "annotationsSha256",
            baselineAnnotationsSha256,
            currentAnnotationsSha256,
            drifts
        );

        compareReplay(baseline.replay(), current.replay(), drifts);
        compareEqual(
            DetectionReferenceBaselineDriftCategory.SCORER_PROVENANCE,
            "scorerId",
            baseline.replay().scorerId(),
            current.replay().scorerId(),
            drifts
        );
        compareEqual(
            DetectionReferenceBaselineDriftCategory.SCORER_PROVENANCE,
            "scorerVersion",
            blank(baseline.replay().scorerVersion()),
            blank(current.replay().scorerVersion()),
            drifts
        );
        compareEqual(
            DetectionReferenceBaselineDriftCategory.POLICY_PROVENANCE,
            "policyId",
            baseline.replay().policyId(),
            current.replay().policyId(),
            drifts
        );
        compareEqual(
            DetectionReferenceBaselineDriftCategory.POLICY_PROVENANCE,
            "policyVersion",
            blank(baseline.replay().policyVersion()),
            blank(current.replay().policyVersion()),
            drifts
        );

        compareEqual(
            DetectionReferenceBaselineDriftCategory.CLASSIFICATION_CONFIGURATION,
            "anomalyThreshold",
            Double.toString(baseline.classification().anomalyThreshold()),
            Double.toString(current.classification().anomalyThreshold()),
            drifts
        );
        compareEqual(
            DetectionReferenceBaselineDriftCategory.CLASSIFICATION_CONFIGURATION,
            "thresholdBoundary",
            baseline.classification().thresholdBoundary(),
            current.classification().thresholdBoundary(),
            drifts
        );

        compareStructure(baseline.structure(), current.structure(), drifts);
        compareMetrics(baseline, current, drifts);
        compareTemporal(baseline.temporal(), current.temporal(), "", drifts);
        compareScenarios(baseline.scenarios(), current.scenarios(), drifts);

        if (!jsonBytesEqual) {
            drifts.add(new DetectionReferenceBaselineDriftEntry(
                DetectionReferenceBaselineDriftCategory.GENERATED_EVIDENCE,
                "evaluation.json.sha256",
                baselineJsonSha256,
                currentJsonSha256
            ));
        }
        if (!markdownBytesEqual) {
            drifts.add(new DetectionReferenceBaselineDriftEntry(
                DetectionReferenceBaselineDriftCategory.GENERATED_EVIDENCE,
                "evaluation.md.sha256",
                baselineMarkdownSha256,
                currentMarkdownSha256
            ));
        }

        return DetectionReferenceBaselineVerificationResult.sorted(drifts);
    }

    private static void compareDataset(DetectionEvaluationEvidence.ReferenceProvenance baseline,
                                       DetectionEvaluationEvidence.ReferenceProvenance current,
                                       List<DetectionReferenceBaselineDriftEntry> drifts) {
        compareEqual(DetectionReferenceBaselineDriftCategory.DATASET_PROVENANCE,
            "datasetId", baseline.datasetId(), current.datasetId(), drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.DATASET_PROVENANCE,
            "datasetSchemaVersion", baseline.datasetSchemaVersion(), current.datasetSchemaVersion(), drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.DATASET_PROVENANCE,
            "evaluationEventSchemaVersion",
            baseline.evaluationEventSchemaVersion(),
            current.evaluationEventSchemaVersion(),
            drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.DATASET_PROVENANCE,
            "featureSchemaVersion", baseline.featureSchemaVersion(), current.featureSchemaVersion(), drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.DATASET_PROVENANCE,
            "sourceClassification", baseline.sourceClassification(), current.sourceClassification(), drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.DATASET_PROVENANCE,
            "transformationVersion", baseline.transformationVersion(), current.transformationVersion(), drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.DATASET_PROVENANCE,
            "ordering", baseline.ordering(), current.ordering(), drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.DATASET_PROVENANCE,
            "eventsSha256", baseline.eventsSha256(), current.eventsSha256(), drifts);
    }

    private static void compareReplay(DetectionEvaluationEvidence.ReplayProvenance baseline,
                                      DetectionEvaluationEvidence.ReplayProvenance current,
                                      List<DetectionReferenceBaselineDriftEntry> drifts) {
        compareEqual(DetectionReferenceBaselineDriftCategory.REPLAY_PROVENANCE,
            "replaySchemaVersion", baseline.replaySchemaVersion(), current.replaySchemaVersion(), drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.REPLAY_PROVENANCE,
            "replayMode", baseline.replayMode(), current.replayMode(), drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.REPLAY_PROVENANCE,
            "replayRunId", baseline.replayRunId(), current.replayRunId(), drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.REPLAY_PROVENANCE,
            "configurationFingerprint",
            baseline.configurationFingerprint(),
            current.configurationFingerprint(),
            drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.REPLAY_PROVENANCE,
            "resultsSha256", baseline.resultsSha256(), current.resultsSha256(), drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.REPLAY_PROVENANCE,
            "aiSentinelVersion", baseline.aiSentinelVersion(), current.aiSentinelVersion(), drifts);
    }

    private static void compareStructure(DetectionEvaluationEvidence.StructuralCounts baseline,
                                         DetectionEvaluationEvidence.StructuralCounts current,
                                         List<DetectionReferenceBaselineDriftEntry> drifts) {
        compareEqual(DetectionReferenceBaselineDriftCategory.STRUCTURAL_EVIDENCE,
            "referenceEventCount",
            Integer.toString(baseline.referenceEventCount()),
            Integer.toString(current.referenceEventCount()),
            drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.STRUCTURAL_EVIDENCE,
            "scenarioCount",
            Integer.toString(baseline.scenarioCount()),
            Integer.toString(current.scenarioCount()),
            drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.STRUCTURAL_EVIDENCE,
            "alignedObservationCount",
            Integer.toString(baseline.alignedObservationCount()),
            Integer.toString(current.alignedObservationCount()),
            drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.STRUCTURAL_EVIDENCE,
            "expectedNormalObservationCount",
            Long.toString(baseline.expectedNormalObservationCount()),
            Long.toString(current.expectedNormalObservationCount()),
            drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.STRUCTURAL_EVIDENCE,
            "expectedAnomalousObservationCount",
            Long.toString(baseline.expectedAnomalousObservationCount()),
            Long.toString(current.expectedAnomalousObservationCount()),
            drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.STRUCTURAL_EVIDENCE,
            "evaluablePredictionCount",
            Long.toString(baseline.evaluablePredictionCount()),
            Long.toString(current.evaluablePredictionCount()),
            drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.STRUCTURAL_EVIDENCE,
            "excludedPredictionCount",
            Long.toString(baseline.excludedPredictionCount()),
            Long.toString(current.excludedPredictionCount()),
            drifts);
    }

    private static void compareMetrics(DetectionReferenceBaselineComparisonSnapshot baseline,
                                       DetectionReferenceBaselineComparisonSnapshot current,
                                       List<DetectionReferenceBaselineDriftEntry> drifts) {
        DetectionConfusionMatrix b = baseline.confusionMatrix();
        DetectionConfusionMatrix c = current.confusionMatrix();
        compareEqual(DetectionReferenceBaselineDriftCategory.DETECTION_METRICS,
            "truePositives", Long.toString(b.truePositives()), Long.toString(c.truePositives()), drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.DETECTION_METRICS,
            "trueNegatives", Long.toString(b.trueNegatives()), Long.toString(c.trueNegatives()), drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.DETECTION_METRICS,
            "falsePositives", Long.toString(b.falsePositives()), Long.toString(c.falsePositives()), drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.DETECTION_METRICS,
            "falseNegatives", Long.toString(b.falseNegatives()), Long.toString(c.falseNegatives()), drifts);
        compareMetric("precision", baseline.metrics().precision(), current.metrics().precision(), drifts);
        compareMetric("recall", baseline.metrics().recall(), current.metrics().recall(), drifts);
        compareMetric("f1", baseline.metrics().f1(), current.metrics().f1(), drifts);
        compareMetric("falsePositiveRate",
            baseline.metrics().falsePositiveRate(), current.metrics().falsePositiveRate(), drifts);
        compareMetric("falseNegativeRate",
            baseline.metrics().falseNegativeRate(), current.metrics().falseNegativeRate(), drifts);
    }

    private static void compareScenarios(
        List<DetectionReferenceBaselineComparisonSnapshot.ScenarioSnapshot> baseline,
        List<DetectionReferenceBaselineComparisonSnapshot.ScenarioSnapshot> current,
        List<DetectionReferenceBaselineDriftEntry> drifts
    ) {
        int size = Math.max(baseline.size(), current.size());
        for (int i = 0; i < size; i++) {
            String prefix = "scenario[" + i + "].";
            if (i >= baseline.size()) {
                drifts.add(new DetectionReferenceBaselineDriftEntry(
                    DetectionReferenceBaselineDriftCategory.DETECTION_METRICS,
                    prefix + "scenarioId",
                    "",
                    current.get(i).scenarioId()
                ));
                continue;
            }
            if (i >= current.size()) {
                drifts.add(new DetectionReferenceBaselineDriftEntry(
                    DetectionReferenceBaselineDriftCategory.DETECTION_METRICS,
                    prefix + "scenarioId",
                    baseline.get(i).scenarioId(),
                    ""
                ));
                continue;
            }
            var b = baseline.get(i);
            var c = current.get(i);
            String idPrefix = "scenario." + b.scenarioId() + ".";
            compareEqual(DetectionReferenceBaselineDriftCategory.DETECTION_METRICS,
                idPrefix + "scenarioId", b.scenarioId(), c.scenarioId(), drifts);
            compareEqual(DetectionReferenceBaselineDriftCategory.DETECTION_METRICS,
                idPrefix + "scenarioCategory", b.scenarioCategory(), c.scenarioCategory(), drifts);
            compareEqual(DetectionReferenceBaselineDriftCategory.STRUCTURAL_EVIDENCE,
                idPrefix + "totalObservationCount",
                Long.toString(b.totalObservationCount()),
                Long.toString(c.totalObservationCount()),
                drifts);
            compareEqual(DetectionReferenceBaselineDriftCategory.DETECTION_METRICS,
                idPrefix + "truePositives",
                Long.toString(b.confusionMatrix().truePositives()),
                Long.toString(c.confusionMatrix().truePositives()),
                drifts);
            compareEqual(DetectionReferenceBaselineDriftCategory.DETECTION_METRICS,
                idPrefix + "trueNegatives",
                Long.toString(b.confusionMatrix().trueNegatives()),
                Long.toString(c.confusionMatrix().trueNegatives()),
                drifts);
            compareEqual(DetectionReferenceBaselineDriftCategory.DETECTION_METRICS,
                idPrefix + "falsePositives",
                Long.toString(b.confusionMatrix().falsePositives()),
                Long.toString(c.confusionMatrix().falsePositives()),
                drifts);
            compareEqual(DetectionReferenceBaselineDriftCategory.DETECTION_METRICS,
                idPrefix + "falseNegatives",
                Long.toString(b.confusionMatrix().falseNegatives()),
                Long.toString(c.confusionMatrix().falseNegatives()),
                drifts);
            compareMetric(idPrefix + "precision", b.metrics().precision(), c.metrics().precision(), drifts);
            compareMetric(idPrefix + "recall", b.metrics().recall(), c.metrics().recall(), drifts);
            compareMetric(idPrefix + "f1", b.metrics().f1(), c.metrics().f1(), drifts);
            compareTemporal(b.temporal(), c.temporal(), idPrefix, drifts);
        }
    }

    private static void compareTemporal(
        DetectionReferenceBaselineComparisonSnapshot.TemporalSummary baseline,
        DetectionReferenceBaselineComparisonSnapshot.TemporalSummary current,
        String prefix,
        List<DetectionReferenceBaselineDriftEntry> drifts
    ) {
        compareEqual(DetectionReferenceBaselineDriftCategory.TEMPORAL_EVIDENCE,
            prefix + "anomalySegmentCount",
            Integer.toString(baseline.anomalySegmentCount()),
            Integer.toString(current.anomalySegmentCount()),
            drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.TEMPORAL_EVIDENCE,
            prefix + "detectedSegmentCount",
            Integer.toString(baseline.detectedSegmentCount()),
            Integer.toString(current.detectedSegmentCount()),
            drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.TEMPORAL_EVIDENCE,
            prefix + "undetectedSegmentCount",
            Integer.toString(baseline.undetectedSegmentCount()),
            Integer.toString(current.undetectedSegmentCount()),
            drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.TEMPORAL_EVIDENCE,
            prefix + "immediateDetectionCount",
            Integer.toString(baseline.immediateDetectionCount()),
            Integer.toString(current.immediateDetectionCount()),
            drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.TEMPORAL_EVIDENCE,
            prefix + "delayedDetectionCount",
            Integer.toString(baseline.delayedDetectionCount()),
            Integer.toString(current.delayedDetectionCount()),
            drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.TEMPORAL_EVIDENCE,
            prefix + "unavailableObservationCount",
            Long.toString(baseline.unavailableObservationCount()),
            Long.toString(current.unavailableObservationCount()),
            drifts);
        compareEqual(DetectionReferenceBaselineDriftCategory.TEMPORAL_EVIDENCE,
            prefix + "recoveryWindowCount",
            Integer.toString(baseline.recoveryWindowCount()),
            Integer.toString(current.recoveryWindowCount()),
            drifts);
    }

    private static void compareMetric(String field,
                                      DetectionMetricValue baseline,
                                      DetectionMetricValue current,
                                      List<DetectionReferenceBaselineDriftEntry> drifts) {
        compareEqual(
            DetectionReferenceBaselineDriftCategory.DETECTION_METRICS,
            field,
            formatMetric(baseline),
            formatMetric(current),
            drifts
        );
    }

    private static String formatMetric(DetectionMetricValue value) {
        if (!value.defined()) {
            return "undefined";
        }
        return Double.toString(Objects.requireNonNull(value.value(), "value"));
    }

    private static void compareEqual(DetectionReferenceBaselineDriftCategory category,
                                     String field,
                                     String baselineValue,
                                     String currentValue,
                                     List<DetectionReferenceBaselineDriftEntry> drifts) {
        if (!Objects.equals(baselineValue, currentValue)) {
            drifts.add(new DetectionReferenceBaselineDriftEntry(category, field, baselineValue, currentValue));
        }
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
