package io.aisentinel.autoconfigure.distributed.training;

import io.aisentinel.distributed.training.TrainingCandidateRecord;

/**
 * Blocking send of a serialized candidate; invoked from a bounded worker thread only.
 */
public interface TrainingCandidateTransport {

    void send(TrainingCandidateRecord record) throws Exception;
}
