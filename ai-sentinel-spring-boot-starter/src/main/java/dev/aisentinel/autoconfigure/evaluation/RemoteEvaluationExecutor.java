package dev.aisentinel.autoconfigure.evaluation;

import dev.aisentinel.core.contract.EvaluationExecutor;
import dev.aisentinel.core.contract.EvaluationFailureResponses;
import dev.aisentinel.core.contract.EvaluationRequest;
import dev.aisentinel.core.contract.EvaluationResponse;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.metrics.SentinelMetrics;

import java.util.Objects;

/**
 * Remote-only executor. Transport failures become explicit {@link EvaluationStatus#REMOTE_EVALUATION_FAILURE}.
 */
public final class RemoteEvaluationExecutor implements EvaluationExecutor {

    private final RemoteEvaluationClient client;

    public RemoteEvaluationExecutor(RemoteEvaluationClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public EvaluationResponse evaluate(EvaluationRequest request) {
        return client.evaluate(Objects.requireNonNull(request, "request"));
    }
}
