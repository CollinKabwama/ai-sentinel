package dev.aisentinel.core.evaluation;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Summary of a completed comparison. Detailed deltas are stored in the written JSON artifact.
 */
public record EvaluationComparisonResult(
    boolean compatible,
    boolean thresholdEqual,
    Path comparisonJson,
    Path comparisonHtml,
    int eventsCompared,
    int eventsWithChanges,
    int newFalsePositives,
    int newFalseNegatives
) {
    public EvaluationComparisonResult {
        comparisonJson = Objects.requireNonNull(comparisonJson, "comparisonJson");
        comparisonHtml = Objects.requireNonNull(comparisonHtml, "comparisonHtml");
        if (eventsCompared < 0 || eventsWithChanges < 0
            || newFalsePositives < 0 || newFalseNegatives < 0) {
            throw new IllegalArgumentException("comparison counts must be non-negative");
        }
    }
}
