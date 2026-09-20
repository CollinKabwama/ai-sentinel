package dev.aisentinel.core.replay;

import dev.aisentinel.core.contract.ContractRiskFactor;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.policy.EnforcementAction;

import java.util.List;
import java.util.Objects;

/**
 * Historical output captured in the source dataset. Not replay input.
 */
public record HistoricalReferenceOutput(
    String scorerId,
    String scorerVersion,
    Double anomalyScore,
    Double policyScore,
    EnforcementAction action,
    List<EvaluationStatus> evaluationStatuses,
    List<ContractRiskFactor> riskFactors,
    String policyId,
    String policyVersion,
    String evaluationMode
) {
    public HistoricalReferenceOutput {
        scorerId = requireNotBlank("scorerId", scorerId);
        scorerVersion = scorerVersion == null ? "" : scorerVersion;
        if (anomalyScore != null && (!Double.isFinite(anomalyScore) || anomalyScore < 0.0 || anomalyScore > 1.0)) {
            throw new IllegalArgumentException("anomalyScore must be finite in [0,1] or null");
        }
        if (policyScore != null && (!Double.isFinite(policyScore) || policyScore < 0.0 || policyScore > 1.0)) {
            throw new IllegalArgumentException("policyScore must be finite in [0,1] or null");
        }
        action = Objects.requireNonNull(action, "action");
        evaluationStatuses = evaluationStatuses == null ? List.of() : List.copyOf(evaluationStatuses);
        riskFactors = riskFactors == null ? List.of() : List.copyOf(riskFactors);
        policyId = policyId == null ? "" : policyId;
        policyVersion = policyVersion == null ? "" : policyVersion;
        evaluationMode = evaluationMode == null ? "" : evaluationMode;
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
