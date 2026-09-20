package dev.aisentinel.core.evaluation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic temporal detection evaluation for aligned replay observations.
 */
public record TemporalDetectionEvaluation(
    String datasetId,
    String replayRunId,
    DetectionClassificationConfiguration classification,
    List<ScenarioTemporalEvaluation> scenarios
) {
    public TemporalDetectionEvaluation {
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("datasetId is required");
        }
        if (replayRunId == null || replayRunId.isBlank()) {
            throw new IllegalArgumentException("replayRunId is required");
        }
        classification = Objects.requireNonNull(classification, "classification");
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        Set<String> scenarioIds = new LinkedHashSet<>();
        for (ScenarioTemporalEvaluation scenario : scenarios) {
            if (!scenarioIds.add(scenario.scenarioId())) {
                throw new IllegalArgumentException("duplicate scenarioId: " + scenario.scenarioId());
            }
        }
    }
}
