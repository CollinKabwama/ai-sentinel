package io.aisentinel.autoconfigure.distributed.training;

import io.aisentinel.distributed.training.TrainingCandidateRecord;

/**
 * Serializes and sends a {@link TrainingCandidateRecord} to a destination (Kafka, log shipper, etc.).
 * <p>
 * Not called from the servlet thread: the async publisher invokes this from a <strong>bounded worker pool</strong>.
 * Implementations may block for I/O; failures should be reported via metrics and surfaced to callers as exceptions
 * where the worker handles them without affecting the HTTP response.
 */
public interface TrainingCandidateTransport {

    /**
     * Send one record. May block until the transport accepts or fails the send.
     *
     * @param record bounded, versioned payload (no raw PII)
     * @throws Exception on unrecoverable transport failure (worker records metrics; request path is unaffected)
     */
    void send(TrainingCandidateRecord record) throws Exception;
}
