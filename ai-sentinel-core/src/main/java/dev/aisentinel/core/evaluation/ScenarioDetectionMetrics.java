package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioCategory;

import java.util.Objects;

/**
 * Deterministic anomaly metrics for one scenario slice.
 */
public record ScenarioDetectionMetrics(
    String scenarioId,
    ReferenceDatasetScenarioCategory scenarioCategory,
    long totalObservationCount,
    long evaluablePredictionCount,
    long excludedPredictionCount,
    DetectionConfusionMatrix confusionMatrix,
    DetectionMetrics metrics
) {
    public ScenarioDetectionMetrics {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId is required");
        }
        scenarioCategory = Objects.requireNonNull(scenarioCategory, "scenarioCategory");
        if (totalObservationCount < 0L || evaluablePredictionCount < 0L || excludedPredictionCount < 0L) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
        confusionMatrix = Objects.requireNonNull(confusionMatrix, "confusionMatrix");
        metrics = Objects.requireNonNull(metrics, "metrics");
        if (evaluablePredictionCount != confusionMatrix.totalCount()) {
            throw new IllegalArgumentException("evaluablePredictionCount must match confusion matrix total");
        }
        if (totalObservationCount != Math.addExact(evaluablePredictionCount, excludedPredictionCount)) {
            throw new IllegalArgumentException("totalObservationCount must equal evaluable + excluded");
        }
        if (!metrics.equals(DetectionMetrics.from(confusionMatrix))) {
            throw new IllegalArgumentException("metrics must match confusion matrix");
        }
    }
}
