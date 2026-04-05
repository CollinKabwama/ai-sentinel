package io.aisentinel.distributed.training;

/**
 * Default no-op publisher (disabled export).
 */
public final class NoopTrainingCandidatePublisher implements TrainingCandidatePublisher {

    public static final NoopTrainingCandidatePublisher INSTANCE = new NoopTrainingCandidatePublisher();

    private NoopTrainingCandidatePublisher() {
    }

    @Override
    public void publish(TrainingCandidatePublishRequest request) {
        // intentionally empty
    }
}
