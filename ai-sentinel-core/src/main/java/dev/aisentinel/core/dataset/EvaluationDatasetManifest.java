package dev.aisentinel.core.dataset;

import dev.aisentinel.core.contract.EvaluationEventSchemas;
import dev.aisentinel.core.model.FeatureSchema;

import java.time.Instant;
import java.util.Objects;

/**
 * Versioned manifest for a portable evaluation dataset export.
 */
public record EvaluationDatasetManifest(
    String datasetSchemaVersion,
    String datasetId,
    Instant createdAt,
    String aiSentinelVersion,
    String featureSchemaVersion,
    String evaluationEventSchemaVersion,
    long recordCount,
    String ordering,
    String sourceClassification,
    String transformationVersion,
    String eventsFile,
    String eventsSha256,
    String description,
    String scenario
) {
    public EvaluationDatasetManifest {
        datasetSchemaVersion = EvaluationDatasetSchemas.requireSupportedDatasetSchemaVersion(requireNotBlank(
            "datasetSchemaVersion", datasetSchemaVersion));
        datasetId = requireNotBlank("datasetId", datasetId);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        aiSentinelVersion = requireNotBlank("aiSentinelVersion", aiSentinelVersion);
        featureSchemaVersion = FeatureSchema.requireSupportedVersion(requireNotBlank(
            "featureSchemaVersion", featureSchemaVersion));
        evaluationEventSchemaVersion = EvaluationEventSchemas.requireSupported(requireNotBlank(
            "evaluationEventSchemaVersion", evaluationEventSchemaVersion));
        if (recordCount < 0L) {
            throw new IllegalArgumentException("recordCount must be >= 0");
        }
        ordering = EvaluationDatasetSchemas.requireSupportedOrdering(requireNotBlank("ordering", ordering));
        sourceClassification = requireNotBlank("sourceClassification", sourceClassification);
        transformationVersion = requireNotBlank("transformationVersion", transformationVersion);
        eventsFile = requireNotBlank("eventsFile", eventsFile);
        eventsSha256 = requireFixedSha256(eventsSha256);
        description = description == null ? "" : description;
        scenario = scenario == null ? "" : scenario;
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String requireFixedSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("eventsSha256 must be 64 lowercase hex characters");
        }
        return value;
    }
}
