package dev.aisentinel.core.dataset.corpus;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Result of a deterministic corpus generation run.
 */
public record GeneratedCorpus(
    String corpusId,
    String scenarioId,
    String scenarioVersion,
    String seed,
    String generatorContractVersion,
    String generatorBuildId,
    String representationMode,
    Path outputDirectory,
    Path eventsPath,
    Path corpusManifestPath,
    Path annotationsPath,
    Path replayManifestPath,
    String eventsSha256,
    String annotationsSha256,
    String scenarioSha256,
    int eventCount
) {
    public GeneratedCorpus {
        corpusId = require("corpusId", corpusId);
        scenarioId = require("scenarioId", scenarioId);
        scenarioVersion = require("scenarioVersion", scenarioVersion);
        seed = require("seed", seed);
        generatorContractVersion = require("generatorContractVersion", generatorContractVersion);
        generatorBuildId = require("generatorBuildId", generatorBuildId);
        representationMode = require("representationMode", representationMode);
        outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
        eventsPath = Objects.requireNonNull(eventsPath, "eventsPath");
        corpusManifestPath = Objects.requireNonNull(corpusManifestPath, "corpusManifestPath");
        annotationsPath = Objects.requireNonNull(annotationsPath, "annotationsPath");
        replayManifestPath = Objects.requireNonNull(replayManifestPath, "replayManifestPath");
        eventsSha256 = requireSha("eventsSha256", eventsSha256);
        annotationsSha256 = requireSha("annotationsSha256", annotationsSha256);
        scenarioSha256 = requireSha("scenarioSha256", scenarioSha256);
        if (eventCount < 0) {
            throw new IllegalArgumentException("eventCount must be >= 0");
        }
    }

    private static String require(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String requireSha(String field, String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be 64 lowercase hex characters");
        }
        return value;
    }
}
