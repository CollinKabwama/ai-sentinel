package dev.aisentinel.core.evaluation;

/**
 * Explicit governance decision for a baseline candidate.
 * <p>
 * Distinct from promotion. Approval does not make a candidate official.
 */
public enum DetectionReferenceBaselineDecisionState {
    APPROVED,
    REJECTED
}
