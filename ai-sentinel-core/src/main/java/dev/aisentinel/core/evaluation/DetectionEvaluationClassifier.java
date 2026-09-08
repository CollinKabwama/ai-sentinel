package dev.aisentinel.core.evaluation;

import java.util.Objects;

/**
 * Shared classification boundary for offline detection evaluation.
 */
final class DetectionEvaluationClassifier {

    ClassifiedPrediction classify(EvaluationPrediction prediction,
                                  DetectionClassificationConfiguration classification) {
        EvaluationPrediction safePrediction = Objects.requireNonNull(prediction, "prediction");
        DetectionClassificationConfiguration safeClassification = Objects.requireNonNull(classification, "classification");
        if (!safePrediction.hasValidDetectorScore()) {
            return ClassifiedPrediction.unavailable();
        }
        return ClassifiedPrediction.evaluable(
            safeClassification.isPredictedAnomalous(Objects.requireNonNull(safePrediction.anomalyScore(), "prediction.anomalyScore"))
        );
    }

    record ClassifiedPrediction(boolean evaluable, boolean predictedAnomalous) {
        ClassifiedPrediction {
            if (!evaluable && predictedAnomalous) {
                throw new IllegalArgumentException("unavailable prediction cannot be anomalous");
            }
        }

        static ClassifiedPrediction unavailable() {
            return new ClassifiedPrediction(false, false);
        }

        static ClassifiedPrediction evaluable(boolean predictedAnomalous) {
            return new ClassifiedPrediction(true, predictedAnomalous);
        }
    }
}
