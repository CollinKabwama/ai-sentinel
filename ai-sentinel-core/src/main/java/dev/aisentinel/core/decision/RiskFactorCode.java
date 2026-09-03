package dev.aisentinel.core.decision;

/**
 * Closed vocabulary of risk-factor codes. Factors may only be authored from this enum via
 * {@link RiskExplanationDeriver}; free-form invention is rejected.
 */
public enum RiskFactorCode {
    BEHAVIOR_DEVIATION,
    VELOCITY_ANOMALY,
    ENDPOINT_ACCESS_PATTERN,
    TRUST_DEGRADATION,
    IDENTITY_NEW_SESSION,
    IDENTITY_SPARSE_HISTORY,
    NETWORK_IP_DRIFT,
    NETWORK_UA_DRIFT,
    MODEL_FALLBACK,
    MODEL_UNAVAILABLE,
    INVALID_SCORE_SIGNAL,
    PIPELINE_DEGRADED,
    STATISTICAL_WARMUP,
    BASELINE_UPDATE_SKIPPED
}
