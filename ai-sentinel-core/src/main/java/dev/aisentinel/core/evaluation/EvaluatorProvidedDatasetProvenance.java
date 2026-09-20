package dev.aisentinel.core.evaluation;

import java.util.Objects;

/**
 * Provenance resolved from an evaluator-provided (BYO) dataset-manifest.
 * Does not carry generator seed or build identity.
 */
public record EvaluatorProvidedDatasetProvenance(
    String datasetId,
    String datasetSchemaVersion,
    String featureSchemaVersion,
    String evaluationEventSchemaVersion,
    String representationMode,
    String eventsSha256,
    String annotationsSha256,
    int eventCount,
    boolean labeled
) {
    public EvaluatorProvidedDatasetProvenance {
        datasetId = require("datasetId", datasetId);
        if (datasetId.startsWith("/") || datasetId.contains("://") || datasetId.contains("..")) {
            throw new IllegalArgumentException("datasetId must not be a host absolute path or URI");
        }
        datasetSchemaVersion = require("datasetSchemaVersion", datasetSchemaVersion);
        featureSchemaVersion = require("featureSchemaVersion", featureSchemaVersion);
        evaluationEventSchemaVersion = require("evaluationEventSchemaVersion", evaluationEventSchemaVersion);
        representationMode = require("representationMode", representationMode);
        eventsSha256 = requireSha("eventsSha256", eventsSha256);
        if (labeled) {
            annotationsSha256 = requireSha("annotationsSha256", annotationsSha256);
        } else {
            annotationsSha256 = annotationsSha256 == null ? "" : annotationsSha256;
            if (!annotationsSha256.isEmpty()) {
                annotationsSha256 = requireSha("annotationsSha256", annotationsSha256);
            }
        }
        if (eventCount < 1) {
            throw new IllegalArgumentException("eventCount must be >= 1");
        }
    }

    private static String require(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String requireSha(String field, String value) {
        Objects.requireNonNull(value, field);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be 64 lowercase hex characters");
        }
        return value;
    }
}
