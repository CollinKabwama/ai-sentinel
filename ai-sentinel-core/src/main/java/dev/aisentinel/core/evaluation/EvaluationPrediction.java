package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.policy.EnforcementAction;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Replay-derived detector evidence used by later detection metrics.
 * <p>
 * This contract intentionally does not turn policy actions into binary detector predictions.
 * Metric code must apply an explicit evaluation classification rule to {@link #anomalyScore()}.
 */
public record EvaluationPrediction(
    EvaluationPredictionSource source,
    Double anomalyScore,
    Double policyScore,
    EnforcementAction action,
    List<EvaluationStatus> evaluationStatuses
) {
    private static final Comparator<EvaluationStatus> STATUS_ORDER = Comparator.comparing(Enum::name);

    public EvaluationPrediction {
        source = Objects.requireNonNull(source, "source");
        if (anomalyScore != null && (!Double.isFinite(anomalyScore) || anomalyScore < 0.0 || anomalyScore > 1.0)) {
            throw new IllegalArgumentException("anomalyScore must be finite in [0,1] or null");
        }
        if (policyScore != null && (!Double.isFinite(policyScore) || policyScore < 0.0 || policyScore > 1.0)) {
            throw new IllegalArgumentException("policyScore must be finite in [0,1] or null");
        }
        action = Objects.requireNonNull(action, "action");
        evaluationStatuses = evaluationStatuses == null ? List.of() : evaluationStatuses.stream()
            .peek(status -> Objects.requireNonNull(status, "evaluationStatus"))
            .distinct()
            .sorted(STATUS_ORDER)
            .toList();
    }

    public boolean hasValidDetectorScore() {
        return anomalyScore != null
            && !evaluationStatuses.contains(EvaluationStatus.INVALID_SCORE)
            && !evaluationStatuses.contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE);
    }
}
