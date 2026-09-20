package dev.aisentinel.core.contract;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.FeatureSnapshot;
import dev.aisentinel.core.policy.EnforcementAction;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Privacy-safe, framework-independent record of one behavioral-risk evaluation.
 * <p>
 * This is a durable data/evidence contract, not an HTTP transport DTO and not a live
 * enforcement command. Event-schema and feature-schema versions are validated separately.
 */
public record EvaluationEvent(
    String eventSchemaVersion,
    String eventId,
    Instant observedAt,
    String correlationId,
    String identityKey,
    String identityType,
    String endpointKey,
    String featureSchemaVersion,
    FeatureSnapshot features,
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

    public EvaluationEvent {
        eventSchemaVersion = EvaluationEventSchemas.requireSupported(requireNotBlank(
            "eventSchemaVersion", eventSchemaVersion));
        eventId = requireNotBlank("eventId", eventId);
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        identityKey = requireNotBlank("identityKey", identityKey);
        endpointKey = requireNotBlank("endpointKey", endpointKey);
        requireNoQueryOrFragment("endpointKey", endpointKey);
        featureSchemaVersion = FeatureSchema.requireSupportedVersion(requireNotBlank(
            "featureSchemaVersion", featureSchemaVersion));
        features = Objects.requireNonNull(features, "features");
        if (!featureSchemaVersion.equals(features.schemaVersion())) {
            throw new EvaluationContractException("featureSchemaVersion must match features.schemaVersion");
        }
        scorerId = requireNotBlank("scorerId", scorerId);
        action = Objects.requireNonNull(action, "action");
        evaluationStatuses = normalizeStatuses(evaluationStatuses);
        riskFactors = riskFactors == null ? List.of() : List.copyOf(riskFactors);
        requireScore("anomalyScore", anomalyScore);
        requireScore("policyScore", policyScore);
        correlationId = normalizeOptional(correlationId);
        identityType = normalizeOptional(identityType);
        scorerVersion = normalizeOptional(scorerVersion);
        policyId = normalizeOptional(policyId);
        policyVersion = normalizeOptional(policyVersion);
        evaluationMode = normalizeOptional(evaluationMode);
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new EvaluationContractException(field + " is required");
        }
        return value;
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value;
    }

    private static void requireNoQueryOrFragment(String field, String value) {
        if (value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
            throw new EvaluationContractException(field + " must not contain query or fragment delimiters");
        }
    }

    private static void requireScore(String field, Double value) {
        if (value == null) {
            return;
        }
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new EvaluationContractException(field + " must be finite in [0,1] or null");
        }
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
