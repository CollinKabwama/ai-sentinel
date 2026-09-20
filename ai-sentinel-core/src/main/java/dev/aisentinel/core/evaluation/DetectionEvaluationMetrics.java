package dev.aisentinel.core.evaluation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic dataset-level anomaly evaluation result plus scenario slices.
 */
public record DetectionEvaluationMetrics(
    String datasetId,
    String replayRunId,
    DetectionClassificationConfiguration classification,
    long totalObservationCount,
    long evaluablePredictionCount,
    long excludedPredictionCount,
    DetectionConfusionMatrix confusionMatrix,
    DetectionMetrics metrics,
    List<ScenarioDetectionMetrics> scenarios
) {
    public DetectionEvaluationMetrics {
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("datasetId is required");
        }
        if (replayRunId == null || replayRunId.isBlank()) {
            throw new IllegalArgumentException("replayRunId is required");
        }
        classification = Objects.requireNonNull(classification, "classification");
        if (totalObservationCount < 0L || evaluablePredictionCount < 0L || excludedPredictionCount < 0L) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
        confusionMatrix = Objects.requireNonNull(confusionMatrix, "confusionMatrix");
        metrics = Objects.requireNonNull(metrics, "metrics");
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        if (evaluablePredictionCount != confusionMatrix.totalCount()) {
            throw new IllegalArgumentException("evaluablePredictionCount must match confusion matrix total");
        }
        if (totalObservationCount != Math.addExact(evaluablePredictionCount, excludedPredictionCount)) {
            throw new IllegalArgumentException("totalObservationCount must equal evaluable + excluded");
        }
        if (!metrics.equals(DetectionMetrics.from(confusionMatrix))) {
            throw new IllegalArgumentException("metrics must match confusion matrix");
        }
        long scenarioTotal = 0L;
        long scenarioEvaluable = 0L;
        long scenarioExcluded = 0L;
        long scenarioTruePositives = 0L;
        long scenarioTrueNegatives = 0L;
        long scenarioFalsePositives = 0L;
        long scenarioFalseNegatives = 0L;
        Set<String> scenarioIds = new LinkedHashSet<>();
        for (ScenarioDetectionMetrics scenario : scenarios) {
            if (!scenarioIds.add(scenario.scenarioId())) {
                throw new IllegalArgumentException("duplicate scenarioId: " + scenario.scenarioId());
            }
            scenarioTotal = Math.addExact(scenarioTotal, scenario.totalObservationCount());
            scenarioEvaluable = Math.addExact(scenarioEvaluable, scenario.evaluablePredictionCount());
            scenarioExcluded = Math.addExact(scenarioExcluded, scenario.excludedPredictionCount());
            scenarioTruePositives = Math.addExact(scenarioTruePositives, scenario.confusionMatrix().truePositives());
            scenarioTrueNegatives = Math.addExact(scenarioTrueNegatives, scenario.confusionMatrix().trueNegatives());
            scenarioFalsePositives = Math.addExact(scenarioFalsePositives, scenario.confusionMatrix().falsePositives());
            scenarioFalseNegatives = Math.addExact(scenarioFalseNegatives, scenario.confusionMatrix().falseNegatives());
        }
        if (scenarioTotal != totalObservationCount
            || scenarioEvaluable != evaluablePredictionCount
            || scenarioExcluded != excludedPredictionCount) {
            throw new IllegalArgumentException("scenario counts must reconcile with dataset totals");
        }
        if (scenarioTruePositives != confusionMatrix.truePositives()
            || scenarioTrueNegatives != confusionMatrix.trueNegatives()
            || scenarioFalsePositives != confusionMatrix.falsePositives()
            || scenarioFalseNegatives != confusionMatrix.falseNegatives()) {
            throw new IllegalArgumentException("scenario confusion matrices must reconcile with dataset confusion matrix");
        }
    }
}
