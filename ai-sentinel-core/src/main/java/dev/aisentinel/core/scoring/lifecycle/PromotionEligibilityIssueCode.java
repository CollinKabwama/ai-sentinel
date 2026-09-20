package dev.aisentinel.core.scoring.lifecycle;

/**
 * Why a challenger is not eligible for governance approval.
 */
public enum PromotionEligibilityIssueCode {
    ACCEPTANCE_REQUIRED,
    COMPARISON_NOT_COMPARABLE,
    SHADOW_SUMMARY_REQUIRED,
    INSUFFICIENT_SHADOW_COMPARISONS,
    EXCESSIVE_CANDIDATE_EXECUTION_FAILURES,
    IDENTITY_MISMATCH
}
