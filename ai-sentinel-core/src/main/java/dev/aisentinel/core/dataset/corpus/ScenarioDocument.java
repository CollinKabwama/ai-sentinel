package dev.aisentinel.core.dataset.corpus;

import java.util.List;
import java.util.Objects;

/**
 * Parsed Evaluation Kit Scenario / Test Plan (contract-aligned subset for generation).
 */
record ScenarioDocument(
    String scenarioSchemaVersion,
    String scenarioId,
    String scenarioVersion,
    String featureSchemaVersion,
    String title,
    String description,
    int identityCount,
    String identityKeyPrefix,
    String normalBehaviorSummary,
    List<String> endpointKeys,
    int warmupDurationSeconds,
    int evaluationDurationSeconds,
    List<Transition> transitions,
    String family
) {
    ScenarioDocument {
        scenarioSchemaVersion = require("scenarioSchemaVersion", scenarioSchemaVersion);
        scenarioId = require("scenarioId", scenarioId);
        scenarioVersion = require("scenarioVersion", scenarioVersion);
        featureSchemaVersion = require("featureSchemaVersion", featureSchemaVersion);
        title = title == null ? "" : title;
        description = description == null ? "" : description;
        if (identityCount < 1) {
            throw new CorpusGeneratorException("population.identityCount must be >= 1");
        }
        identityKeyPrefix = identityKeyPrefix == null || identityKeyPrefix.isBlank()
            ? "id-"
            : identityKeyPrefix;
        normalBehaviorSummary = require("normalBehavior.summary", normalBehaviorSummary);
        endpointKeys = endpointKeys == null || endpointKeys.isEmpty()
            ? List.of("/api/a")
            : List.copyOf(endpointKeys);
        if (warmupDurationSeconds < 0) {
            throw new CorpusGeneratorException("timing.warmup.durationSeconds must be >= 0");
        }
        if (evaluationDurationSeconds < 1) {
            throw new CorpusGeneratorException("timing.evaluation.durationSeconds must be >= 1");
        }
        transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
        if (transitions.isEmpty()) {
            throw new CorpusGeneratorException("transitions must contain at least one entry");
        }
        family = family == null ? "" : family;
    }

    record Transition(
        String transitionId,
        String intent,
        String summary,
        int startsAfterWarmupSeconds
    ) {
        Transition {
            transitionId = require("transitionId", transitionId);
            intent = require("intent", intent);
            summary = require("summary", summary);
            if (startsAfterWarmupSeconds < 0) {
                throw new CorpusGeneratorException(
                    "transition.startsAfterWarmupSeconds must be >= 0 for " + transitionId);
            }
        }
    }

    private static String require(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new CorpusGeneratorException(field + " is required");
        }
        return value;
    }
}
