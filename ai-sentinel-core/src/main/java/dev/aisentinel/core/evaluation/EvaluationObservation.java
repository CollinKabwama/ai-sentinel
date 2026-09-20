package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioCategory;

import java.time.Instant;
import java.util.Objects;

/**
 * One aligned evaluation observation: context + independent truth + replay prediction.
 */
public record EvaluationObservation(
    String eventId,
    String scenarioId,
    ReferenceDatasetScenarioCategory scenarioCategory,
    int sequenceNumber,
    Instant observedAt,
    String identityKey,
    EvaluationTruth truth,
    EvaluationPrediction prediction
) {
    public EvaluationObservation {
        eventId = requireNotBlank("eventId", eventId);
        scenarioId = requireNotBlank("scenarioId", scenarioId);
        scenarioCategory = Objects.requireNonNull(scenarioCategory, "scenarioCategory");
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("sequenceNumber must be >= 1");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        identityKey = requireNotBlank("identityKey", identityKey);
        truth = Objects.requireNonNull(truth, "truth");
        prediction = Objects.requireNonNull(prediction, "prediction");
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
