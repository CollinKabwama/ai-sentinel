package dev.aisentinel.core.evaluation;

/**
 * Phase / label accounting for a generated corpus before binary detection metrics.
 */
public record GeneratedCorpusPhaseCounts(
    int totalEvents,
    int warmupEvents,
    int unknownOrUnlabeledEvents,
    int labeledEvaluationEvents,
    int labeledBenignEvents,
    int labeledAnomalousEvents
) {
    public GeneratedCorpusPhaseCounts {
        if (totalEvents < 0
            || warmupEvents < 0
            || unknownOrUnlabeledEvents < 0
            || labeledEvaluationEvents < 0
            || labeledBenignEvents < 0
            || labeledAnomalousEvents < 0) {
            throw new IllegalArgumentException("phase counts must be non-negative");
        }
        if (labeledBenignEvents + labeledAnomalousEvents != labeledEvaluationEvents) {
            throw new IllegalArgumentException("labeledEvaluationEvents must equal benign + anomalous");
        }
        if (warmupEvents + unknownOrUnlabeledEvents + labeledEvaluationEvents > totalEvents) {
            throw new IllegalArgumentException("phase partitions exceed totalEvents");
        }
    }
}
