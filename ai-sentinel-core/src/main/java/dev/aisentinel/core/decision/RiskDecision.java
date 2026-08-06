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
 * @param anomalyScore       clamped raw scorer output in {@code [0, 1]} (NaN or negative becomes {@code 1.0})
 * @param policyScore        score handed to the {@link dev.aisentinel.core.policy.PolicyEngine} (fused when enabled)
 * @param features           features the decision was made from
 * @param context            snapshot <em>reference</em> to the per-request context (not a deep copy); callers must
 *                           treat it as read-only after the decision is published if they care about audit integrity
 * @param startupGraceActive whether startup grace forced {@link EnforcementAction#MONITOR}
 * @param evaluationStatuses  immutable lifecycle / degradation markers (never {@code null})
 */
public record RiskDecision(
    EnforcementAction action,
    double anomalyScore,
    double policyScore,
    RequestFeatures features,
    RequestContext context,
    boolean startupGraceActive,
    Set<EvaluationStatus> evaluationStatuses
) {
    public RiskDecision {
        evaluationStatuses = evaluationStatuses == null
            ? Set.of()
            : Set.copyOf(evaluationStatuses);
    }

    /**
     * Factory without evaluation statuses (empty set).
     */
    public static RiskDecision of(EnforcementAction action,
                                  double anomalyScore,
                                  double policyScore,
                                  RequestFeatures features,
                                  RequestContext context,
                                  boolean startupGraceActive) {
        return new RiskDecision(action, anomalyScore, policyScore, features, context, startupGraceActive, Set.of());
    }

    public boolean hasStatus(EvaluationStatus status) {
        return evaluationStatuses.contains(Objects.requireNonNull(status, "status"));
    }
}
