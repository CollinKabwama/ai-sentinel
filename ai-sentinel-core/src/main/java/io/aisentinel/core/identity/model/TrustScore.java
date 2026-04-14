package io.aisentinel.core.identity.model;

/**
 * Session / identity trust level in {@code [0.0, 1.0]} where higher means more trusted.
 * Distinct from API anomaly risk scores; used by the Identity arm for future adaptive decisions.
 */
public record TrustScore(double value, String reason) {
    public TrustScore {
        if (Double.isNaN(value)) {
            value = 0.0;
        }
        value = Math.max(0.0, Math.min(1.0, value));
        reason = reason != null ? reason : "";
    }

    /** Baseline for Phase 0: no behavioral trust degradation applied. */
    public static TrustScore fullyTrusted() {
        return new TrustScore(1.0, "baseline");
    }
}
