package dev.aisentinel.core.evaluation;

/**
 * Engineering assessment of already-produced candidate evaluation evidence.
 * <p>
 * {@code EVALUATION COMPLETED != ACCEPTED}<br>
 * {@code EVALUATION FAILED != ACCEPTANCE REJECTED}<br>
 * {@code ACCEPTED CANDIDATE != CHAMPION}<br>
 * {@code ACCEPTED CANDIDATE != PRODUCTION MODEL}<br>
 * {@code ACCEPTANCE POLICY != PRODUCTION POLICY}
 */
public enum CandidateEvaluationAcceptanceStatus {
    NOT_ASSESSED,
    ACCEPTED,
    REJECTED
}
