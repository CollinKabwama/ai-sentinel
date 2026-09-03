package dev.aisentinel.core.decision;

import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;

import java.util.Objects;
import java.util.Set;

/**
 * Outcome of {@link SentinelDecisionEngine#evaluate}: the action to enforce plus the scores that produced it.
 *
 * @param action             final action after policy, trust adjustment, warmup override, startup grace, and quarantine
 * @param anomalyScore       scorer output after range clamp for finite {@code ≥ 0} values ({@code [0, 1]});
 *                           when {@link EvaluationStatus#INVALID_SCORE} is present, may be {@code NaN} (not a risk level)
 * @param policyScore        score handed to the {@link dev.aisentinel.core.policy.PolicyEngine} (fused when enabled);
 *                           {@code NaN} when evaluation was {@link EvaluationStatus#INVALID_SCORE} (policy skipped)
 * @param features           features the decision was made from
 * @param context            snapshot <em>reference</em> to the per-request context (not a deep copy); callers must
 *                           treat it as read-only after the decision is published if they care about audit integrity
 * @param startupGraceActive whether startup grace forced {@link EnforcementAction#MONITOR}
 * @param evaluationStatuses  immutable lifecycle / degradation markers (never {@code null})
 * @param explanation        structured risk factors and optional advisory guidance; never selects enforcement
 */
public record RiskDecision(
    EnforcementAction action,
    double anomalyScore,
    double policyScore,
    RequestFeatures features,
    RequestContext context,
    boolean startupGraceActive,
    Set<EvaluationStatus> evaluationStatuses,
    RiskExplanation explanation
) {
    public RiskDecision {
        evaluationStatuses = evaluationStatuses == null
            ? Set.of()
            : Set.copyOf(evaluationStatuses);
        explanation = explanation == null ? RiskExplanation.empty() : explanation;
    }

    /**
     * Compatibility constructor without explanation (empty explanation).
     */
    public RiskDecision(EnforcementAction action,
                        double anomalyScore,
                        double policyScore,
                        RequestFeatures features,
                        RequestContext context,
                        boolean startupGraceActive,
                        Set<EvaluationStatus> evaluationStatuses) {
        this(action, anomalyScore, policyScore, features, context, startupGraceActive, evaluationStatuses,
            RiskExplanation.empty());
    }

    /**
     * Factory without evaluation statuses or explanation (empty set / empty explanation).
     */
    public static RiskDecision of(EnforcementAction action,
                                  double anomalyScore,
                                  double policyScore,
                                  RequestFeatures features,
                                  RequestContext context,
                                  boolean startupGraceActive) {
        return new RiskDecision(action, anomalyScore, policyScore, features, context, startupGraceActive, Set.of(),
            RiskExplanation.empty());
    }

    public boolean hasStatus(EvaluationStatus status) {
        return evaluationStatuses.contains(Objects.requireNonNull(status, "status"));
    }

    /** Copy with a replacement explanation; action and scores are unchanged. */
    public RiskDecision withExplanation(RiskExplanation newExplanation) {
        return new RiskDecision(action, anomalyScore, policyScore, features, context, startupGraceActive,
            evaluationStatuses, newExplanation == null ? RiskExplanation.empty() : newExplanation);
    }
}
