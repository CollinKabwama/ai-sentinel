package io.aisentinel.distributed.training;

/**
 * Exports training candidates asynchronously. Implementations must not block the request thread and must fail-open.
 */
public interface TrainingCandidatePublisher {

    /**
     * Invoked after enforcement has been applied. Must return quickly; heavy or I/O work belongs on a bounded worker.
     */
    void publish(TrainingCandidatePublishRequest request);
}
