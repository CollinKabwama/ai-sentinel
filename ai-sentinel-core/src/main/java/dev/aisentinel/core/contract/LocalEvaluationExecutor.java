package dev.aisentinel.core.contract;

import java.util.Objects;

/**
 * Local {@link EvaluationExecutor} over {@link LocalEvaluationBridge}.
 * Maps a null bridge result to an explicit fail-open {@link EvaluationResponse}.
 */
public final class LocalEvaluationExecutor implements EvaluationExecutor {

    private final LocalEvaluationBridge bridge;

    public LocalEvaluationExecutor(LocalEvaluationBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public EvaluationResponse evaluate(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        EvaluationResponse response = bridge.evaluate(request);
        if (response == null) {
            return EvaluationFailureResponses.localHardFailOpen(request.correlationId());
        }
        return response;
    }
}
