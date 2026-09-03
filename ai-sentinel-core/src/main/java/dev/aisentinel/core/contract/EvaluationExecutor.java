package dev.aisentinel.core.contract;

/**
 * Chooses how an application obtains an {@link EvaluationResponse} for an {@link EvaluationRequest}.
 * Implementations must not contain policy/scoring logic — they delegate to the local bridge or a remote client.
 */
@FunctionalInterface
public interface EvaluationExecutor {

    /**
     * Evaluate the request. Never returns {@code null}.
     * Transport or hard local failures yield an explicit fail-open response (see {@link EvaluationFailureResponses}).
     */
    EvaluationResponse evaluate(EvaluationRequest request);
}
