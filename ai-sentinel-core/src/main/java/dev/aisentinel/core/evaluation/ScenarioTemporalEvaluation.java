package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioCategory;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic temporal evaluation for one scenario-ordered observation sequence.
 */
public record ScenarioTemporalEvaluation(
    String scenarioId,
    ReferenceDatasetScenarioCategory scenarioCategory,
    int observationCount,
    List<TemporalAnomalySegment> anomalySegments
) {
    public ScenarioTemporalEvaluation {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId is required");
        }
        scenarioCategory = Objects.requireNonNull(scenarioCategory, "scenarioCategory");
        if (observationCount < 0) {
            throw new IllegalArgumentException("observationCount must be >= 0");
        }
        anomalySegments = anomalySegments == null ? List.of() : List.copyOf(anomalySegments);
        for (int i = 0; i < anomalySegments.size(); i++) {
            TemporalAnomalySegment segment = Objects.requireNonNull(anomalySegments.get(i), "anomalySegment");
            if (segment.segmentIndex() != i) {
                throw new IllegalArgumentException("segmentIndex must be scenario-local and contiguous");
            }
            if (segment.anomalyObservationCount() > observationCount) {
                throw new IllegalArgumentException("anomalyObservationCount must not exceed scenario observationCount");
            }
        }
    }
}
