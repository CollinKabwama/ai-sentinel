package dev.aisentinel.autoconfigure.evaluation;

import dev.aisentinel.core.contract.EvaluationExecutor;
import dev.aisentinel.core.contract.EvaluationRequest;
import dev.aisentinel.core.contract.EvaluationResponse;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.metrics.SentinelMetrics;

import java.util.Objects;

/**
 * Tries remote evaluation first; on {@link EvaluationStatus#REMOTE_EVALUATION_FAILURE} runs local evaluation.
 * Does not double-apply enforcement — returns a single {@link EvaluationResponse}.
 */
public final class RemoteWithLocalFallbackExecutor implements EvaluationExecutor {

    private final EvaluationExecutor remote;
    private final EvaluationExecutor local;
    private final SentinelMetrics metrics;

    public RemoteWithLocalFallbackExecutor(EvaluationExecutor remote,
                                           EvaluationExecutor local,
                                           SentinelMetrics metrics) {
        this.remote = Objects.requireNonNull(remote, "remote");
        this.local = Objects.requireNonNull(local, "local");
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
    }

    @Override
    public EvaluationResponse evaluate(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        EvaluationResponse remoteResponse = remote.evaluate(request);
        if (remoteResponse.evaluationStatuses().contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name())) {
            metrics.recordRemoteLocalFallback();
            return local.evaluate(request);
        }
        return remoteResponse;
    }
}
