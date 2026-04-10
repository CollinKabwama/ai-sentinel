package io.aisentinel.distributed.training;

/**
 * Offers training candidates for export after enforcement (Phase 5.5). Implementations must <strong>not</strong> block
 * the request thread; must use bounded queues and fail-open if overloaded or broken.
 */
public interface TrainingCandidatePublisher {

    /**
     * Called after enforcement completes. Must return quickly; serialization and transport run on a worker.
     * <p>
     * On failure, the HTTP response must already be committed; export failures are metrics-only.
     */
    void publish(TrainingCandidatePublishRequest request);
}
