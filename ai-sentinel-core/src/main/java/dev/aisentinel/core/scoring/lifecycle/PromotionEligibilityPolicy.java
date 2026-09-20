package dev.aisentinel.core.scoring.lifecycle;

import java.util.Objects;

/**
 * Explicit prerequisites for considering a challenger eligible for governance
 * approval. Eligibility is not approval or promotion.
 * <p>
 * {@code ELIGIBLE != APPROVED}
 */
public final class PromotionEligibilityPolicy {

    private final boolean requireAccepted;
    private final boolean requireComparableComparison;
    private final long minimumShadowValidComparisons;
    private final long maximumCandidateExecutionFailures;
    private final boolean requireShadowSummary;

    private PromotionEligibilityPolicy(
        boolean requireAccepted,
        boolean requireComparableComparison,
        long minimumShadowValidComparisons,
        long maximumCandidateExecutionFailures,
        boolean requireShadowSummary
    ) {
        if (minimumShadowValidComparisons < 0 || maximumCandidateExecutionFailures < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
        this.requireAccepted = requireAccepted;
        this.requireComparableComparison = requireComparableComparison;
        this.minimumShadowValidComparisons = minimumShadowValidComparisons;
        this.maximumCandidateExecutionFailures = maximumCandidateExecutionFailures;
        this.requireShadowSummary = requireShadowSummary;
    }

    public static PromotionEligibilityPolicy defaults() {
        return new PromotionEligibilityPolicy(true, true, 0L, Long.MAX_VALUE, false);
    }

    public static PromotionEligibilityPolicy of(
        boolean requireAccepted,
        boolean requireComparableComparison,
        long minimumShadowValidComparisons,
        long maximumCandidateExecutionFailures,
        boolean requireShadowSummary
    ) {
        return new PromotionEligibilityPolicy(
            requireAccepted,
            requireComparableComparison,
            minimumShadowValidComparisons,
            maximumCandidateExecutionFailures,
            requireShadowSummary
        );
    }

    public boolean requireAccepted() {
        return requireAccepted;
    }

    public boolean requireComparableComparison() {
        return requireComparableComparison;
    }

    public long minimumShadowValidComparisons() {
        return minimumShadowValidComparisons;
    }

    public long maximumCandidateExecutionFailures() {
        return maximumCandidateExecutionFailures;
    }

    public boolean requireShadowSummary() {
        return requireShadowSummary;
    }

    public PromotionEligibilityAssessment assess(ChampionChallengerComparison comparison) {
        Objects.requireNonNull(comparison, "comparison");
        return PromotionEligibilityAssessment.assess(this, comparison);
    }
}
