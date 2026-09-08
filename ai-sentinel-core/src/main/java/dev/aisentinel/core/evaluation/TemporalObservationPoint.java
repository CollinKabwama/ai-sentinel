package dev.aisentinel.core.evaluation;

import java.time.Instant;
import java.util.Objects;

/**
 * One ordered evaluation observation point used by temporal analysis.
 */
public record TemporalObservationPoint(
    String eventId,
    int sequenceNumber,
    Instant observedAt
) {
    public TemporalObservationPoint {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("sequenceNumber must be >= 1");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }

    static TemporalObservationPoint from(EvaluationObservation observation) {
        Objects.requireNonNull(observation, "observation");
        return new TemporalObservationPoint(
            observation.eventId(),
            observation.sequenceNumber(),
            observation.observedAt()
        );
    }
}
