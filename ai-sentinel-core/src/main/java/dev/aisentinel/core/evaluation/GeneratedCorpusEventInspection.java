package dev.aisentinel.core.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One event-level inspection row projecting authored ground truth beside runtime replay outcomes.
 * <p>
 * Ground-truth fields remain evaluation-layer labels. Runtime scores/statuses come only from replay.
 */
public record GeneratedCorpusEventInspection(
    String eventId,
    int sequenceNumber,
    Instant observedAt,
    String identityKey,
    String category,
    String expectedClass,
    String participation,
    boolean binaryMetricParticipant,
    Double anomalyScore,
    Boolean predictedAnomalous,
    String action,
    List<String> evaluationStatuses,
    String outcome
) {
    public static final String PARTICIPATION_WARMUP = "warmup";
    public static final String PARTICIPATION_BINARY_LABELED = "binary-labeled";
    public static final String PARTICIPATION_UNKNOWN_UNLABELED = "unknown-unlabeled";

    public GeneratedCorpusEventInspection {
        eventId = require("eventId", eventId);
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("sequenceNumber must be >= 1");
        }
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        identityKey = require("identityKey", identityKey);
        category = require("category", category);
        expectedClass = require("expectedClass", expectedClass);
        participation = require("participation", participation);
        if (anomalyScore != null && (!Double.isFinite(anomalyScore) || anomalyScore < 0.0 || anomalyScore > 1.0)) {
            throw new IllegalArgumentException("anomalyScore must be finite in [0,1] or null");
        }
        action = require("action", action);
        evaluationStatuses = evaluationStatuses == null ? List.of() : List.copyOf(evaluationStatuses);
        outcome = require("outcome", outcome);
    }

    private static String require(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
