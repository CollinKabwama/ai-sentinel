package dev.aisentinel.core.scoring.lifecycle;

import dev.aisentinel.core.evaluation.CandidateEvaluationAcceptanceStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Eligibility assessment only. Does not approve or promote.
 * <p>
 * {@code ELIGIBLE != APPROVED}<br>
 * {@code ELIGIBLE != PROMOTED}
 */
public final class PromotionEligibilityAssessment {

    public enum Status {
        ELIGIBLE,
        NOT_ELIGIBLE
    }

    private final Status status;
    private final PromotionEligibilityPolicy policy;
    private final String comparisonSha256Hex;
    private final List<PromotionEligibilityIssue> issues;

    private PromotionEligibilityAssessment(
        Status status,
        PromotionEligibilityPolicy policy,
        String comparisonSha256Hex,
        List<PromotionEligibilityIssue> issues
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.comparisonSha256Hex = Objects.requireNonNull(comparisonSha256Hex, "comparisonSha256Hex");
        this.issues = List.copyOf(issues);
        if (status == Status.ELIGIBLE && !this.issues.isEmpty()) {
            throw new IllegalArgumentException("ELIGIBLE assessment must not carry issues");
        }
        if (status == Status.NOT_ELIGIBLE && this.issues.isEmpty()) {
            throw new IllegalArgumentException("NOT_ELIGIBLE assessment requires issues");
        }
    }

    static PromotionEligibilityAssessment assess(
        PromotionEligibilityPolicy policy,
        ChampionChallengerComparison comparison
    ) {
        List<PromotionEligibilityIssue> issues = new ArrayList<>();
        if (policy.requireComparableComparison()
            && comparison.status() != ChampionChallengerComparisonStatus.COMPARABLE) {
            issues.add(new PromotionEligibilityIssue(
                PromotionEligibilityIssueCode.COMPARISON_NOT_COMPARABLE,
                "comparison status=" + comparison.status()
            ));
        }
        if (policy.requireAccepted()
            && comparison.challengerEvidence().acceptanceStatus() != CandidateEvaluationAcceptanceStatus.ACCEPTED) {
            issues.add(new PromotionEligibilityIssue(
                PromotionEligibilityIssueCode.ACCEPTANCE_REQUIRED,
                "challenger acceptance=" + comparison.challengerEvidence().acceptanceStatus()
            ));
        }
        if (policy.requireShadowSummary() && comparison.shadowSummary().isEmpty()) {
            issues.add(new PromotionEligibilityIssue(
                PromotionEligibilityIssueCode.SHADOW_SUMMARY_REQUIRED,
                "shadow summary required by policy"
            ));
        }
        if (comparison.shadowSummary().isPresent()) {
            ShadowObservationSummary shadow = comparison.shadowSummary().orElseThrow();
            if (shadow.validComparisonCount() < policy.minimumShadowValidComparisons()) {
                issues.add(new PromotionEligibilityIssue(
                    PromotionEligibilityIssueCode.INSUFFICIENT_SHADOW_COMPARISONS,
                    "validComparisons=" + shadow.validComparisonCount()
                        + " minimum=" + policy.minimumShadowValidComparisons()
                ));
            }
            if (shadow.candidateExecutionFailureCount() > policy.maximumCandidateExecutionFailures()) {
                issues.add(new PromotionEligibilityIssue(
                    PromotionEligibilityIssueCode.EXCESSIVE_CANDIDATE_EXECUTION_FAILURES,
                    "failures=" + shadow.candidateExecutionFailureCount()
                        + " maximum=" + policy.maximumCandidateExecutionFailures()
                ));
            }
        } else {
            if (policy.minimumShadowValidComparisons() > 0) {
                issues.add(new PromotionEligibilityIssue(
                    PromotionEligibilityIssueCode.INSUFFICIENT_SHADOW_COMPARISONS,
                    "shadow summary absent but minimumShadowValidComparisons="
                        + policy.minimumShadowValidComparisons()
                ));
            }
            if (policy.maximumCandidateExecutionFailures() < Long.MAX_VALUE) {
                issues.add(new PromotionEligibilityIssue(
                    PromotionEligibilityIssueCode.SHADOW_SUMMARY_REQUIRED,
                    "shadow summary absent but maximumCandidateExecutionFailures="
                        + policy.maximumCandidateExecutionFailures()
                ));
            }
        }

        if (issues.isEmpty()) {
            return new PromotionEligibilityAssessment(
                Status.ELIGIBLE, policy, comparison.comparisonSha256Hex(), List.of());
        }
        return new PromotionEligibilityAssessment(
            Status.NOT_ELIGIBLE, policy, comparison.comparisonSha256Hex(), issues);
    }

    public Status status() {
        return status;
    }

    public PromotionEligibilityPolicy policy() {
        return policy;
    }

    public String comparisonSha256Hex() {
        return comparisonSha256Hex;
    }

    public List<PromotionEligibilityIssue> issues() {
        return issues;
    }

    public boolean eligible() {
        return status == Status.ELIGIBLE;
    }
}
