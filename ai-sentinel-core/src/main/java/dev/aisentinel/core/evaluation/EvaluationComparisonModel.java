package dev.aisentinel.core.evaluation;

import java.util.List;
import java.util.Map;

record EvaluationComparisonModel(
    String comparisonId,
    String status,
    List<String> limitations,
    RunEvidence baseline,
    RunEvidence candidate,
    boolean thresholdEqual,
    List<MetricChange> metricChanges,
    List<CountChange> countChanges,
    Map<String, Integer> correctnessTransitions,
    List<EventChange> eventChanges,
    int eventsCompared,
    int newFalsePositives,
    int newFalseNegatives
) {
}

record RunEvidence(
    String datasetSource,
    String resultId,
    String evaluationRunId,
    String resultSchemaVersion,
    String featureSchemaVersion,
    String evaluationEventSchemaVersion,
    String representationMode,
    String corpusId,
    String corpusEventsSha256,
    String datasetId,
    String eventsSha256,
    String annotationsSha256,
    double anomalyThreshold,
    Map<String, MetricFamily> metricFamilies,
    Map<String, EventEvidence> events
) {
    boolean labeled() {
        MetricFamily family = metricFamilies.get("detectionLabeled");
        return annotationsSha256 != null || (family != null && "available".equals(family.availability()));
    }
}

record MetricFamily(String availability, Map<String, Double> values) {
}

record EventEvidence(
    String eventId,
    long sequenceNumber,
    Double anomalyScore,
    Boolean predictedAnomalous,
    String action,
    List<String> evaluationStatuses,
    String outcome,
    String expectedClass,
    String participation,
    boolean binaryMetricParticipant
) {
}

record MetricChange(
    String family,
    String metric,
    String baselineAvailability,
    String candidateAvailability,
    String availabilityChange,
    Double baselineValue,
    Double candidateValue,
    Double delta
) {
}

record CountChange(String name, long baseline, long candidate, long delta) {
}

record EventChange(
    String eventId,
    long sequenceNumber,
    List<String> changes,
    EventEvidence baseline,
    EventEvidence candidate,
    Double scoreDelta,
    String correctnessTransition
) {
}
