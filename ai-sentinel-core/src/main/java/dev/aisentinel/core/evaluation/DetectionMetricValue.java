package dev.aisentinel.core.evaluation;

/**
 * One ratio metric whose value may be undefined when its denominator is zero.
 */
public record DetectionMetricValue(boolean defined, Double value) {

    public DetectionMetricValue {
        if (defined) {
            if (value == null || !Double.isFinite(value) || value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException("defined metric value must be finite in [0,1]");
            }
        } else if (value != null) {
            throw new IllegalArgumentException("undefined metric value must be null");
        }
    }

    public static DetectionMetricValue defined(double value) {
        return new DetectionMetricValue(true, value);
    }

    public static DetectionMetricValue undefined() {
        return new DetectionMetricValue(false, null);
    }
}
