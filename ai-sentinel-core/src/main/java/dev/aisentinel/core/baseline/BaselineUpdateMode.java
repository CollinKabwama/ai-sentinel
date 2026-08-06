package dev.aisentinel.core.baseline;

/**
 * Strategy for whether a scored observation may update the statistical baseline.
 * <p>
 * Modes are mutually exclusive: pick one strategy; do not combine action gates with score gates.
 */
public enum BaselineUpdateMode {

    /** Every observation updates (previous production behavior). */
    ALWAYS,

    /** Update only when the risk-derived action is {@code ALLOW}. */
    ALLOW_ONLY,

    /**
     * Update when the risk-derived action is {@code ALLOW} or {@code MONITOR}.
     * Default production policy.
     */
    ALLOW_OR_MONITOR,

    /**
     * Update when {@link BaselineUpdateContext#policyScore()} is strictly below the configured threshold.
     * Threshold is ignored by other modes.
     */
    SCORE_BELOW_THRESHOLD
}
