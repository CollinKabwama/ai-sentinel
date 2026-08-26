package dev.aisentinel.core.decision;

/**
 * Coarse category for a {@link RiskFactor}. Only categories backed by current pipeline signals are defined.
 */
public enum RiskFactorCategory {
    /** Statistical / behavioral deviation signals. */
    BEHAVIOR,
    /** Identity lifecycle signals (e.g. new session, sparse history). */
    IDENTITY,
    /** Network / client fingerprint drift. */
    NETWORK,
    /** Trust-score degradation. */
    TRUST,
    /** Scorer / model availability and validity. */
    MODEL,
    /** Pipeline health / degradation (not proven malice). */
    SYSTEM
}
