package io.aisentinel.core.fusion;

/**
 * Fusion disabled: policy input remains the anomaly score; {@link #fuse} is only used if mis-invoked (pass-through).
 */
public enum NoopRequestRiskFusion implements RequestRiskFusion {
    INSTANCE;

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public FusedRisk fuse(double anomalyScoreClamped, double trustScoreClamped) {
        return new FusedRisk(anomalyScoreClamped, trustScoreClamped, anomalyScoreClamped, "fusion=disabled");
    }
}
