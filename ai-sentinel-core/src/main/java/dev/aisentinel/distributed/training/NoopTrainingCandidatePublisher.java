package dev.aisentinel.distributed.training;

/**
 * Default publisher when training export is disabled: discards candidates.
 * Thread-safe singleton.
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
