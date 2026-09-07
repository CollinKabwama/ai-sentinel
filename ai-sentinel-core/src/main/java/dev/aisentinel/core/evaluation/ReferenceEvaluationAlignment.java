package dev.aisentinel.core.evaluation;

import java.util.List;

/**
 * Deterministic aligned observation set for a replayed reference dataset.
 */
public record ReferenceEvaluationAlignment(
    String datasetId,
    String replayRunId,
    int referenceEventCount,
    int scenarioCount,
    int evaluableObservationCount,
    List<EvaluationObservation> observations
) {
    public ReferenceEvaluationAlignment {
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("datasetId is required");
        }
        if (replayRunId == null || replayRunId.isBlank()) {
            throw new IllegalArgumentException("replayRunId is required");
        }
        if (referenceEventCount < 0 || scenarioCount < 0 || evaluableObservationCount < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
        observations = observations == null ? List.of() : List.copyOf(observations);
        if (observations.size() != evaluableObservationCount) {
            throw new IllegalArgumentException("observation count mismatch");
        }
    }
}
