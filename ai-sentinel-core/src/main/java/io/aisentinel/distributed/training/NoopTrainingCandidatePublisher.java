package io.aisentinel.distributed.training;

/**
 * Default publisher: no export (local buffer / trainer only).
 */
public final class NoopTrainingCandidatePublisher implements TrainingCandidatePublisher {

    public static final NoopTrainingCandidatePublisher INSTANCE = new NoopTrainingCandidatePublisher();

    private NoopTrainingCandidatePublisher() {
    }

    @Override
    public void publish(TrainingCandidateRecord candidate) {
        // no-op
    }
}
