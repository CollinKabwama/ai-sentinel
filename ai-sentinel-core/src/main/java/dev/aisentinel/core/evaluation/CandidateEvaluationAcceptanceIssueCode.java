package dev.aisentinel.core.evaluation;

/**
 * Durable reason that one configured candidate-acceptance criterion was not met.
 * <p>
 * Codes are ordered in the deterministic evaluation sequence used by
 * {@link CandidateEvaluationAcceptancePolicy}.
 */
public enum CandidateEvaluationAcceptanceIssueCode {
    MINIMUM_EVALUABLE_OBSERVATIONS_NOT_MET,
    MAXIMUM_EXCLUDED_OBSERVATIONS_EXCEEDED,
    MINIMUM_PRECISION_NOT_MET,
    MINIMUM_RECALL_NOT_MET,
    MINIMUM_F1_NOT_MET,
    MAXIMUM_FALSE_POSITIVE_RATE_EXCEEDED,
    MAXIMUM_FALSE_NEGATIVE_RATE_EXCEEDED
}
