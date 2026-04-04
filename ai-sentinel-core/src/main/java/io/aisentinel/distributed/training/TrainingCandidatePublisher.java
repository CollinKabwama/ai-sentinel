package io.aisentinel.distributed.training;

/**
 * Publishes training candidates off the hot path. Implementations must never block indefinitely and must
 * not throw to callers ( swallow or drop with metrics ).
 */
public interface TrainingCandidatePublisher {

    void publish(TrainingCandidateRecord candidate);
}
