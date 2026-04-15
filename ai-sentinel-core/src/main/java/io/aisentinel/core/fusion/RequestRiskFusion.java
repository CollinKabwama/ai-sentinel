package io.aisentinel.core.fusion;

/**
 * Phase 4 — combines clamped anomaly score with trust into a single policy input.
 */
public interface RequestRiskFusion {

    /** When false, pipeline must not apply fusion (policy uses anomaly score only). */
    boolean enabled();

    /**
     * Deterministic fusion of anomaly and trust into {@link FusedRisk#fusedScore()}.
     * Implementations should be side-effect free and bounded to {@code [0,1]} for {@code fusedScore}.
     */
    FusedRisk fuse(double anomalyScoreClamped, double trustScoreClamped);
}
