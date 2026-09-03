package dev.aisentinel.core.contract;

import dev.aisentinel.core.policy.EnforcementAction;

import java.util.List;
import java.util.Objects;

/**
 * Platform-neutral evaluation output. Free of servlet/Spring types and Java exceptions.
 * <p>
 * Scores use nullable {@link Double}: non-finite internal scores become {@code null}
 * (never {@code 0.0}/{@code 1.0} stand-ins). {@code INVALID_SCORE} appears in statuses.
 * Factors and advice are descriptive only and never select enforcement.
 * <p>
 * Wire clients must treat unknown additive JSON properties as ignorable for forward
 * compatibility; malformed known fields and contract-version mismatches still fail.
 *
 * @param contractVersion      {@link EvaluationContract#CONTRACT_VERSION}
 * @param correlationId        echo of request correlation id
 * @param action               finalized enforcement action
 * @param evaluationStatuses   sorted status names
 * @param anomalyScore         finite anomaly score, or {@code null} if unavailable/invalid
 * @param policyScore          finite policy score, or {@code null} if unavailable/invalid
 * @param startupGraceActive   whether startup grace forced MONITOR presentation
 * @param proceed              whether the request should proceed (pipeline allow-through)
 * @param endpoint             scored endpoint
 * @param factors              ordered risk factors (immutable)
 * @param advice               optional advisory; {@code null} means absent
 */
public record EvaluationResponse(
    int contractVersion,
    String correlationId,
    EnforcementAction action,
    List<String> evaluationStatuses,
    Double anomalyScore,
    Double policyScore,
    boolean startupGraceActive,
    boolean proceed,
    String endpoint,
    List<ContractRiskFactor> factors,
    ContractSecurityAdvice advice
) {
    public EvaluationResponse {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(action, "action");
        evaluationStatuses = evaluationStatuses == null
            ? List.of()
            : List.copyOf(evaluationStatuses);
        factors = factors == null ? List.of() : List.copyOf(factors);
        endpoint = endpoint == null ? "" : endpoint;
        if (anomalyScore != null && !Double.isFinite(anomalyScore)) {
            throw new EvaluationContractException("anomalyScore must be finite or null");
        }
        if (policyScore != null && !Double.isFinite(policyScore)) {
            throw new EvaluationContractException("policyScore must be finite or null");
        }
    }
}
