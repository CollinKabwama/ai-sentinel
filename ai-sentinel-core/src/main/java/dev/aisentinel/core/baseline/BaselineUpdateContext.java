package dev.aisentinel.core.baseline;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.policy.EnforcementAction;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable inputs for {@link BaselineUpdatePolicy#shouldUpdate(BaselineUpdateContext)}.
 * <p>
 * {@link #riskAction()} is the action after policy and trust adjustment, <strong>before</strong>
 * warmup enforcement override, startup grace, or quarantine presentation overrides.
 */
public record BaselineUpdateContext(
    double anomalyScore,
    double policyScore,
    EnforcementAction riskAction,
    Set<EvaluationStatus> evaluationStatuses
) {
    public BaselineUpdateContext {
        Objects.requireNonNull(riskAction, "riskAction");
        evaluationStatuses = evaluationStatuses == null
            ? Set.of()
            : Set.copyOf(evaluationStatuses);
    }
}
