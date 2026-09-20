package dev.aisentinel.core.replay;

/**
 * Deterministic replay policy configuration.
 */
public record ReplayPolicyConfiguration(
    String policyId,
    String policyVersion,
    String evaluationMode,
    double moderateThreshold,
    double elevatedThreshold,
    double highThreshold,
    double criticalThreshold
) {
    public ReplayPolicyConfiguration {
        if (policyId == null || policyId.isBlank()) {
            throw new IllegalArgumentException("policyId is required");
        }
        policyVersion = policyVersion == null ? "" : policyVersion;
        evaluationMode = evaluationMode == null ? "" : evaluationMode;
        requireThreshold("moderateThreshold", moderateThreshold);
        requireThreshold("elevatedThreshold", elevatedThreshold);
        requireThreshold("highThreshold", highThreshold);
        requireThreshold("criticalThreshold", criticalThreshold);
    }

    public static ReplayPolicyConfiguration defaultThresholds() {
        return new ReplayPolicyConfiguration(
            "threshold-policy-default",
            "0.3.0",
            "MONITOR",
            0.2,
            0.4,
            0.6,
            0.8
        );
    }

    private static void requireThreshold(String field, double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be finite in [0,1]");
        }
    }
}
