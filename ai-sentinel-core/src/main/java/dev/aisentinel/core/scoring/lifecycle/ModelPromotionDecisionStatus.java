package dev.aisentinel.core.scoring.lifecycle;

/**
 * Explicit governance decision state.
 * <p>
 * {@code APPROVED != PROMOTED}
 */
public enum ModelPromotionDecisionStatus {
    NOT_DECIDED,
    APPROVED,
    REJECTED
}
