package dev.aisentinel.core.fusion;

/**
 * Default when risk fusion is disabled: {@link #enabled()} is false and policy uses the anomaly score alone.
 * Thread-safe singleton.
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
