package dev.aisentinel.core.replay;

import dev.aisentinel.core.contract.ContractRiskFactor;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.policy.EnforcementAction;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Replay-produced output for one input event.
 */
public record ReplayResult(
    String replaySchemaVersion,
    String replayRunId,
    ReplayResultStatus replayStatus,
    int sequenceNumber,
    String eventId,
    String correlationId,
    String identityKey,
    String identityType,
    Instant observedAt,
    String endpointKey,
    String featureSchemaVersion,
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
    private static final Comparator<EvaluationStatus> STATUS_ORDER = Comparator.comparing(Enum::name);

    public ReplayResult {
        replaySchemaVersion = ReplaySchemas.requireSupportedSchemaVersion(requireNotBlank(
            "replaySchemaVersion", replaySchemaVersion));
        replayRunId = requireNotBlank("replayRunId", replayRunId);
        replayStatus = Objects.requireNonNull(replayStatus, "replayStatus");
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("sequenceNumber must be >= 1");
        }
        eventId = requireNotBlank("eventId", eventId);
        correlationId = correlationId == null ? "" : correlationId;
        identityKey = requireNotBlank("identityKey", identityKey);
        identityType = identityType == null ? "" : identityType;
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        endpointKey = requireNotBlank("endpointKey", endpointKey);
        featureSchemaVersion = requireNotBlank("featureSchemaVersion", featureSchemaVersion);
        scorerId = requireNotBlank("scorerId", scorerId);
        scorerVersion = scorerVersion == null ? "" : scorerVersion;
        if (anomalyScore != null && (!Double.isFinite(anomalyScore) || anomalyScore < 0.0 || anomalyScore > 1.0)) {
            throw new IllegalArgumentException("anomalyScore must be finite in [0,1] or null");
        }
        if (policyScore != null && (!Double.isFinite(policyScore) || policyScore < 0.0 || policyScore > 1.0)) {
            throw new IllegalArgumentException("policyScore must be finite in [0,1] or null");
        }
        action = Objects.requireNonNull(action, "action");
        evaluationStatuses = normalizeStatuses(evaluationStatuses);
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

    private static List<EvaluationStatus> normalizeStatuses(List<EvaluationStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return statuses.stream()
            .peek(status -> Objects.requireNonNull(status, "evaluationStatus"))
            .distinct()
            .sorted(STATUS_ORDER)
            .toList();
    }
}
