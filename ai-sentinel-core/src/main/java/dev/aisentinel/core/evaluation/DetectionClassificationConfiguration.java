package dev.aisentinel.core.evaluation;

/**
 * Explicit anomaly-score classification configuration for offline detection evaluation.
 */
public record DetectionClassificationConfiguration(double anomalyThreshold) {

    public DetectionClassificationConfiguration {
        if (!Double.isFinite(anomalyThreshold) || anomalyThreshold < 0.0 || anomalyThreshold > 1.0) {
            throw new IllegalArgumentException("anomalyThreshold must be finite in [0,1]");
        }
    }

    /**
     * Inclusive threshold boundary: scores equal to the threshold are classified anomalous.
     */
    public boolean isPredictedAnomalous(double anomalyScore) {
        if (!Double.isFinite(anomalyScore) || anomalyScore < 0.0 || anomalyScore > 1.0) {
            throw new IllegalArgumentException("anomalyScore must be finite in [0,1]");
        }
        return anomalyScore >= anomalyThreshold;
    }
}
