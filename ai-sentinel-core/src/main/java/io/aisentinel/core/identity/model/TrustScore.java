package io.aisentinel.core.identity.model;

/**
 * Session / identity trust level in {@code [0.0, 1.0]} where higher means more trusted.
 * Distinct from API anomaly risk scores; consumed in trust evaluation, optional risk fusion, and downstream policy inputs.
 */
public record TrustScore(double value, String reason) {
    public TrustScore {
        if (Double.isNaN(value)) {
            value = 0.0;
        }
        value = Math.max(0.0, Math.min(1.0, value));
        reason = reason != null ? reason : "";
    }

    /** Baseline trust when identity resolution does not attach behavioral degradation. */
    public static TrustScore fullyTrusted() {
        return new TrustScore(1.0, "baseline");
    }
}
