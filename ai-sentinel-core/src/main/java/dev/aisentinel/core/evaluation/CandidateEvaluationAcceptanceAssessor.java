package dev.aisentinel.core.evaluation;

/**
 * Applies an optional acceptance policy only after evaluation metrics exist.
 */
final class CandidateEvaluationAcceptanceAssessor {

    private CandidateEvaluationAcceptanceAssessor() {
    }

    static CandidateEvaluationAcceptanceAssessment assess(
        CandidateEvaluationAcceptancePolicy policy,
        DetectionEvaluationMetrics metrics
    ) {
        if (metrics == null || policy == null) {
            return CandidateEvaluationAcceptanceAssessment.notAssessed(policy);
        }
        return policy.assess(metrics);
    }
}
