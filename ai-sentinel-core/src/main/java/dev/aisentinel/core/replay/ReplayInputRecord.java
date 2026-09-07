package dev.aisentinel.core.replay;

import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.FeatureSnapshot;

import java.time.Instant;
import java.util.Objects;

/**
 * Replay-safe projection of one source event.
 */
public record ReplayInputRecord(
    int sequenceNumber,
    String eventId,
    Instant observedAt,
    String correlationId,
    String identityKey,
    String identityType,
    String endpointKey,
    String featureSchemaVersion,
    FeatureSnapshot features
) {
    public ReplayInputRecord {
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("sequenceNumber must be >= 1");
        }
        eventId = requireNotBlank("eventId", eventId);
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        correlationId = correlationId == null ? "" : correlationId;
        identityKey = requireNotBlank("identityKey", identityKey);
        identityType = identityType == null ? "" : identityType;
        endpointKey = requireNotBlank("endpointKey", endpointKey);
        featureSchemaVersion = FeatureSchema.requireSupportedVersion(requireNotBlank(
            "featureSchemaVersion", featureSchemaVersion));
        features = Objects.requireNonNull(features, "features");
        if (!featureSchemaVersion.equals(features.schemaVersion())) {
            throw new IllegalArgumentException("featureSchemaVersion must match features.schemaVersion");
        }
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
