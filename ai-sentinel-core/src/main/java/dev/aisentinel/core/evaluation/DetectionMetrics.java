package dev.aisentinel.core.evaluation;

import java.util.Objects;

/**
 * Deterministic ratio metrics derived from anomaly confusion-matrix counts.
 */
public record DetectionMetrics(
    DetectionMetricValue precision,
    DetectionMetricValue recall,
    DetectionMetricValue f1,
    DetectionMetricValue falsePositiveRate,
    DetectionMetricValue falseNegativeRate
) {
    public DetectionMetrics {
        precision = Objects.requireNonNull(precision, "precision");
        recall = Objects.requireNonNull(recall, "recall");
        f1 = Objects.requireNonNull(f1, "f1");
        falsePositiveRate = Objects.requireNonNull(falsePositiveRate, "falsePositiveRate");
        falseNegativeRate = Objects.requireNonNull(falseNegativeRate, "falseNegativeRate");
    }

    public static DetectionMetrics from(DetectionConfusionMatrix confusionMatrix) {
        DetectionConfusionMatrix safeMatrix = Objects.requireNonNull(confusionMatrix, "confusionMatrix");
        long tp = safeMatrix.truePositives();
        long tn = safeMatrix.trueNegatives();
        long fp = safeMatrix.falsePositives();
        long fn = safeMatrix.falseNegatives();
        return new DetectionMetrics(
            ratio(tp, safeMatrix.predictedPositiveCount()),
            ratio(tp, safeMatrix.actualPositiveCount()),
            f1(tp, fp, fn),
            ratio(fp, safeMatrix.actualNegativeCount()),
            ratio(fn, safeMatrix.actualPositiveCount())
        );
    }

    private static DetectionMetricValue ratio(long numerator, long denominator) {
        if (denominator == 0L) {
            return DetectionMetricValue.undefined();
        }
        return DetectionMetricValue.defined((double) numerator / (double) denominator);
    }

    private static DetectionMetricValue f1(long tp, long fp, long fn) {
        double denominator = (2.0 * tp) + fp + fn;
        if (denominator == 0.0) {
            return DetectionMetricValue.undefined();
        }
        return DetectionMetricValue.defined((2.0 * tp) / denominator);
    }
}
