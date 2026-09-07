package dev.aisentinel.core.evaluation;

/**
 * Deterministic anomaly-classification confusion-matrix counts.
 */
public record DetectionConfusionMatrix(
    long truePositives,
    long trueNegatives,
    long falsePositives,
    long falseNegatives
) {
    public DetectionConfusionMatrix {
        requireNonNegative("truePositives", truePositives);
        requireNonNegative("trueNegatives", trueNegatives);
        requireNonNegative("falsePositives", falsePositives);
        requireNonNegative("falseNegatives", falseNegatives);
    }

    public long actualPositiveCount() {
        return Math.addExact(truePositives, falseNegatives);
    }

    public long actualNegativeCount() {
        return Math.addExact(trueNegatives, falsePositives);
    }

    public long predictedPositiveCount() {
        return Math.addExact(truePositives, falsePositives);
    }

    public long predictedNegativeCount() {
        return Math.addExact(trueNegatives, falseNegatives);
    }

    public long totalCount() {
        return Math.addExact(
            Math.addExact(truePositives, trueNegatives),
            Math.addExact(falsePositives, falseNegatives)
        );
    }

    private static void requireNonNegative(String field, long value) {
        if (value < 0L) {
            throw new IllegalArgumentException(field + " must be >= 0");
        }
    }
}
