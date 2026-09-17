package dev.aisentinel.core.evaluation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic engineering assessment of candidate evaluation metrics.
 * <p>
 * {@code EVALUATION RESULT != ACCEPTANCE DECISION}<br>
 * {@code ACCEPTANCE DECISION != PRODUCTION APPROVAL}
 */
public record CandidateEvaluationAcceptanceAssessment(
    CandidateEvaluationAcceptanceStatus status,
    CandidateEvaluationAcceptancePolicy policy,
    List<CandidateEvaluationAcceptanceIssue> issues
) {
    public CandidateEvaluationAcceptanceAssessment {
        status = Objects.requireNonNull(status, "status");
        issues = issues == null ? List.of() : List.copyOf(issues);
        for (CandidateEvaluationAcceptanceIssue issue : issues) {
            Objects.requireNonNull(issue, "issue");
        }
        switch (status) {
            case NOT_ASSESSED -> {
                if (!issues.isEmpty()) {
                    throw new IllegalArgumentException("NOT_ASSESSED acceptance must not carry issues");
                }
            }
            case ACCEPTED -> {
                Objects.requireNonNull(policy, "policy");
                if (!issues.isEmpty()) {
                    throw new IllegalArgumentException("ACCEPTED assessment must not carry issues");
                }
            }
            case REJECTED -> {
                Objects.requireNonNull(policy, "policy");
                if (issues.isEmpty()) {
                    throw new IllegalArgumentException("REJECTED assessment requires at least one issue");
                }
            }
        }
    }

    public Optional<CandidateEvaluationAcceptancePolicy> configuredPolicy() {
        return Optional.ofNullable(policy);
    }

    static CandidateEvaluationAcceptanceAssessment notAssessed(CandidateEvaluationAcceptancePolicy policy) {
        return new CandidateEvaluationAcceptanceAssessment(
            CandidateEvaluationAcceptanceStatus.NOT_ASSESSED,
            policy,
            List.of()
        );
    }

    static CandidateEvaluationAcceptanceAssessment accepted(CandidateEvaluationAcceptancePolicy policy) {
        return new CandidateEvaluationAcceptanceAssessment(
            CandidateEvaluationAcceptanceStatus.ACCEPTED,
            policy,
            List.of()
        );
    }

    static CandidateEvaluationAcceptanceAssessment rejected(
        CandidateEvaluationAcceptancePolicy policy,
        List<CandidateEvaluationAcceptanceIssue> issues
    ) {
        return new CandidateEvaluationAcceptanceAssessment(
            CandidateEvaluationAcceptanceStatus.REJECTED,
            policy,
            issues
        );
    }
}
