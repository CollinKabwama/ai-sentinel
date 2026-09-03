package dev.aisentinel.core.contract;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.policy.EnforcementAction;

import java.util.List;
import java.util.Objects;

/**
 * Synthetic fail-open responses that are <em>not</em> successful evaluations.
 * Used when remote transport fails or the local bridge cannot produce a decision.
 */
public final class EvaluationFailureResponses {

    private EvaluationFailureResponses() {
    }

    /**
     * Remote transport / client failure: proceed=true, action=ALLOW, scores null,
     * status {@link EvaluationStatus#REMOTE_EVALUATION_FAILURE}.
     */
    public static EvaluationResponse remoteFailure(String correlationId) {
        String corr = Objects.requireNonNullElse(correlationId, "unknown");
        return new EvaluationResponse(
            EvaluationContract.CONTRACT_VERSION,
            corr,
            EnforcementAction.ALLOW,
            List.of(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name()),
            null,
            null,
            false,
            true,
            "",
            List.of(),
            null
        );
    }

    /**
     * Local hard fail-open when the bridge returns null (e.g. feature extraction abort).
     * Distinct from remote transport failure: empty statuses, ALLOW, proceed.
     */
    public static EvaluationResponse localHardFailOpen(String correlationId) {
        String corr = Objects.requireNonNullElse(correlationId, "unknown");
        return new EvaluationResponse(
            EvaluationContract.CONTRACT_VERSION,
            corr,
            EnforcementAction.ALLOW,
            List.of(),
            null,
            null,
            false,
            true,
            "",
            List.of(),
            null
        );
    }
}
