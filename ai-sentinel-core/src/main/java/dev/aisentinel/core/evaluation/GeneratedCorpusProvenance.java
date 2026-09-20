package dev.aisentinel.core.evaluation;

import java.util.Objects;

/**
 * Provenance resolved from a generated Evaluation Kit corpus-manifest.
 */
public record GeneratedCorpusProvenance(
    String corpusId,
    String scenarioId,
    String scenarioVersion,
    String scenarioSha256,
    String seed,
    String generatorContractVersion,
    String generatorBuildId,
    String featureSchemaVersion,
    String evaluationEventSchemaVersion,
    String representationMode,
    String eventsSha256,
    String annotationsSha256,
    int eventCount,
    int warmupDurationSeconds,
    int evaluationDurationSeconds
) {
    public GeneratedCorpusProvenance {
        corpusId = require("corpusId", corpusId);
        scenarioId = require("scenarioId", scenarioId);
        scenarioVersion = require("scenarioVersion", scenarioVersion);
        scenarioSha256 = requireSha("scenarioSha256", scenarioSha256);
        seed = require("seed", seed);
        generatorContractVersion = require("generatorContractVersion", generatorContractVersion);
        generatorBuildId = require("generatorBuildId", generatorBuildId);
        featureSchemaVersion = require("featureSchemaVersion", featureSchemaVersion);
        evaluationEventSchemaVersion = require("evaluationEventSchemaVersion", evaluationEventSchemaVersion);
        representationMode = require("representationMode", representationMode);
        eventsSha256 = requireSha("eventsSha256", eventsSha256);
        annotationsSha256 = requireSha("annotationsSha256", annotationsSha256);
        if (eventCount < 1) {
            throw new IllegalArgumentException("eventCount must be >= 1");
        }
        if (warmupDurationSeconds < 0) {
            throw new IllegalArgumentException("warmupDurationSeconds must be >= 0");
        }
        if (evaluationDurationSeconds < 1) {
            throw new IllegalArgumentException("evaluationDurationSeconds must be >= 1");
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
