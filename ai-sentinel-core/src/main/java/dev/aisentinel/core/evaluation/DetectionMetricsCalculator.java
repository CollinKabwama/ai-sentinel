package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioCategory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure deterministic anomaly-quality metric calculation over aligned evaluation observations.
 */
public final class DetectionMetricsCalculator {

    public DetectionEvaluationMetrics compute(ReferenceEvaluationAlignment alignment,
                                              DetectionClassificationConfiguration classification) {
        ReferenceEvaluationAlignment safeAlignment = Objects.requireNonNull(alignment, "alignment");
        DetectionClassificationConfiguration safeClassification = Objects.requireNonNull(classification, "classification");

        CountAccumulator datasetCounts = new CountAccumulator();
        Map<String, ScenarioAccumulator> scenarios = new LinkedHashMap<>();
        for (EvaluationObservation observation : safeAlignment.observations()) {
            datasetCounts.record(observation, safeClassification);
            scenarios.computeIfAbsent(
                    observation.scenarioId(),
                    ignored -> new ScenarioAccumulator(observation.scenarioId(), observation.scenarioCategory()))
                .record(observation, safeClassification);
        }

        List<ScenarioDetectionMetrics> scenarioMetrics = scenarios.values().stream()
            .map(ScenarioAccumulator::toMetrics)
            .toList();
        DetectionConfusionMatrix confusionMatrix = datasetCounts.toConfusionMatrix();
        return new DetectionEvaluationMetrics(
            safeAlignment.datasetId(),
            safeAlignment.replayRunId(),
            safeClassification,
            safeAlignment.evaluableObservationCount(),
            datasetCounts.evaluablePredictionCount,
            datasetCounts.excludedPredictionCount,
            confusionMatrix,
            DetectionMetrics.from(confusionMatrix),
            scenarioMetrics
        );
    }

    private static class CountAccumulator {
        protected long truePositives;
        protected long trueNegatives;
        protected long falsePositives;
        protected long falseNegatives;
        protected long evaluablePredictionCount;
        protected long excludedPredictionCount;

        void record(EvaluationObservation observation, DetectionClassificationConfiguration classification) {
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(classification, "classification");
            EvaluationPrediction prediction = observation.prediction();
            if (!prediction.hasValidDetectorScore()) {
                excludedPredictionCount++;
                return;
            }
            double anomalyScore = Objects.requireNonNull(prediction.anomalyScore(), "prediction.anomalyScore");
            boolean predictedAnomalous = classification.isPredictedAnomalous(anomalyScore);
            boolean expectedAnomalous = observation.truth().anomalousExpected();
            evaluablePredictionCount++;
            if (expectedAnomalous) {
                if (predictedAnomalous) {
                    truePositives++;
                } else {
                    falseNegatives++;
                }
            } else if (predictedAnomalous) {
                falsePositives++;
            } else {
                trueNegatives++;
            }
        }

        DetectionConfusionMatrix toConfusionMatrix() {
            return new DetectionConfusionMatrix(truePositives, trueNegatives, falsePositives, falseNegatives);
        }
    }

    private static final class ScenarioAccumulator extends CountAccumulator {
        private final String scenarioId;
        private final ReferenceDatasetScenarioCategory scenarioCategory;
        private long totalObservationCount;

        private ScenarioAccumulator(String scenarioId, ReferenceDatasetScenarioCategory scenarioCategory) {
            this.scenarioId = scenarioId;
            this.scenarioCategory = scenarioCategory;
        }

        @Override
        void record(EvaluationObservation observation, DetectionClassificationConfiguration classification) {
            if (observation.scenarioCategory() != scenarioCategory) {
                throw new IllegalArgumentException("scenarioId has conflicting categories: " + scenarioId);
            }
            totalObservationCount++;
            super.record(observation, classification);
        }

        ScenarioDetectionMetrics toMetrics() {
            DetectionConfusionMatrix confusionMatrix = toConfusionMatrix();
            return new ScenarioDetectionMetrics(
                scenarioId,
                scenarioCategory,
                totalObservationCount,
                evaluablePredictionCount,
                excludedPredictionCount,
                confusionMatrix,
                DetectionMetrics.from(confusionMatrix)
            );
        }
    }
}
