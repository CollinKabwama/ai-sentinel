package io.aisentinel.core.policy;

import io.aisentinel.core.model.RequestFeatures;

/**
 * Policy engine mapping score thresholds to actions.
 * Default thresholds: Low &lt;0.2, Moderate &lt;0.4, Elevated &lt;0.6, High &lt;0.8, Critical &gt;=0.8
 */
public final class ThresholdPolicyEngine implements PolicyEngine {

    private final double thresholdModerate;
    private final double thresholdElevated;
    private final double thresholdHigh;
    private final double thresholdCritical;

    public ThresholdPolicyEngine() {
        this(0.2, 0.4, 0.6, 0.8);
    }

    /**
     * @throws IllegalArgumentException if thresholds are NaN/infinite, out of [0,1], or not strictly ordered
     */
    public ThresholdPolicyEngine(double moderate, double elevated, double high, double critical) {
        validateThresholds(moderate, elevated, high, critical);
        this.thresholdModerate = moderate;
        this.thresholdElevated = elevated;
        this.thresholdHigh = high;
        this.thresholdCritical = critical;
    }

    /**
     * Validates {@code moderate < elevated < high < critical} and each value in {@code [0, 1]}.
     */
    public static void validateThresholds(double moderate, double elevated, double high, double critical) {
        double[] v = {moderate, elevated, high, critical};
        for (int i = 0; i < v.length; i++) {
            if (Double.isNaN(v[i]) || Double.isInfinite(v[i])) {
                throw new IllegalArgumentException(
                    "ai.sentinel policy thresholds must be finite numbers (NaN/infinity not allowed)");
            }
            if (v[i] < 0.0 || v[i] > 1.0) {
                throw new IllegalArgumentException(
                    "ai.sentinel policy thresholds must be within [0.0, 1.0]; got value at index " + i + "=" + v[i]);
            }
        }
        if (!(moderate < elevated && elevated < high && high < critical)) {
            throw new IllegalArgumentException(
                "ai.sentinel policy thresholds must satisfy threshold-moderate < threshold-elevated < threshold-high < threshold-critical; got moderate="
                    + moderate + ", elevated=" + elevated + ", high=" + high + ", critical=" + critical);
        }
    }

    @Override
    public EnforcementAction evaluate(double riskScore, RequestFeatures features, String endpoint) {
        if (riskScore >= thresholdCritical) return EnforcementAction.QUARANTINE;
        if (riskScore >= thresholdHigh) return EnforcementAction.BLOCK;
        if (riskScore >= thresholdElevated) return EnforcementAction.THROTTLE;
        if (riskScore >= thresholdModerate) return EnforcementAction.MONITOR;
        return EnforcementAction.ALLOW;
    }
}
