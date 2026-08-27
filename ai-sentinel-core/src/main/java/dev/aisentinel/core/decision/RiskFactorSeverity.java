package dev.aisentinel.core.decision;

/**
 * Qualitative importance of a {@link RiskFactor}'s evidence.
 * <p>
 * Distinct from the numeric anomaly score, from a factor's contribution weight among peers,
 * and from {@link dev.aisentinel.core.policy.EnforcementAction}. High severity never alone selects enforcement.
 */
public enum RiskFactorSeverity {
    INFO,
    LOW,
    MEDIUM,
    HIGH
}
