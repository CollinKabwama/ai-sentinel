package dev.aisentinel.core.contract;

import dev.aisentinel.core.policy.EnforcementAction;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Client-side validation of remote {@link EvaluationResponse} payloads.
 * Failures are contract/transport errors, not risk classifications.
 */
public final class EvaluationResponseValidator {

    private static final Set<String> KNOWN_ACTIONS = new HashSet<>();

    static {
        for (EnforcementAction action : EnforcementAction.values()) {
            KNOWN_ACTIONS.add(action.name());
        }
    }

    private EvaluationResponseValidator() {
    }

    public static void validate(EvaluationResponse response, String expectedCorrelationId) {
        Objects.requireNonNull(response, "response");
        if (response.contractVersion() != EvaluationContract.CONTRACT_VERSION) {
            throw new EvaluationContractException(
                "unsupported response contractVersion: " + response.contractVersion());
        }
        if (expectedCorrelationId != null
            && !expectedCorrelationId.equals(response.correlationId())) {
            throw new EvaluationContractException("response correlationId mismatch");
        }
        if (response.correlationId() == null || response.correlationId().isBlank()) {
            throw new EvaluationContractException("response correlationId is required");
        }
        if (response.action() == null) {
            throw new EvaluationContractException("response action is required");
        }
        if (!KNOWN_ACTIONS.contains(response.action().name())) {
            throw new EvaluationContractException("unknown response action");
        }
        if (response.anomalyScore() != null && !Double.isFinite(response.anomalyScore())) {
            throw new EvaluationContractException("response anomalyScore must be finite or null");
        }
        if (response.policyScore() != null && !Double.isFinite(response.policyScore())) {
            throw new EvaluationContractException("response policyScore must be finite or null");
        }
        boolean proceedExpected = switch (response.action()) {
            case ALLOW, MONITOR -> true;
            case THROTTLE, BLOCK, QUARANTINE -> false;
        };
        boolean remoteFailure = response.evaluationStatuses().contains("REMOTE_EVALUATION_FAILURE");
        if (remoteFailure) {
            validateRemoteFailureShape(response);
        } else if (response.proceed() != proceedExpected) {
            throw new EvaluationContractException("response proceed inconsistent with action");
        }
        List<String> statuses = response.evaluationStatuses();
        if (statuses.size() > 64) {
            throw new EvaluationContractException("too many evaluationStatuses");
        }
        for (String status : statuses) {
            if (status == null || status.isBlank()) {
                throw new EvaluationContractException("evaluationStatuses entry must be non-blank");
            }
            if (status.length() > EvaluationContract.MAX_STRING_LENGTH) {
                throw new EvaluationContractException("evaluationStatuses entry exceeds max length");
            }
            if (!status.equals(status.toUpperCase(Locale.ROOT))) {
                throw new EvaluationContractException("evaluationStatuses must be uppercase codes");
            }
        }
        if (response.factors().size() > 64) {
            throw new EvaluationContractException("too many factors");
        }
        if (response.advice() != null) {
            ContractSecurityAdvice advice = response.advice();
            if (advice.code() == null || advice.code().isBlank()) {
                throw new EvaluationContractException("advice.code is required");
            }
            Set<String> present = new HashSet<>();
            for (ContractRiskFactor factor : response.factors()) {
                present.add(factor.code());
            }
            for (String linked : advice.linkedFactorCodes()) {
                if (!present.contains(linked)) {
                    throw new EvaluationContractException("advice linkedFactorCodes must reference present factors");
                }
            }
        }
    }

    private static void validateRemoteFailureShape(EvaluationResponse response) {
        if (response.action() != EnforcementAction.ALLOW || !response.proceed()
            || response.anomalyScore() != null || response.policyScore() != null
            || response.startupGraceActive() || !response.factors().isEmpty()
            || response.advice() != null) {
            throw new EvaluationContractException(
                "REMOTE_EVALUATION_FAILURE response must be fail-open ALLOW with no scores, factors, or advice");
        }
    }
}
