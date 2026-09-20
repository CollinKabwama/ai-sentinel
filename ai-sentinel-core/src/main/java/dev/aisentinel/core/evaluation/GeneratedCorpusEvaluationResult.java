package dev.aisentinel.core.evaluation;

import java.util.List;
import java.util.Objects;

/**
 * Result of detection evaluation against a generated Evaluation Kit corpus.
 * <p>
 * Detection metrics remain specialized ({@link DetectionEvaluationRunner.DetectionEvaluationRun}).
 * Provenance and phase counts are generated-corpus evaluation-layer metadata.
 */
public record GeneratedCorpusEvaluationResult(
    GeneratedCorpusProvenance provenance,
    GeneratedCorpusPhaseCounts phaseCounts,
    DetectionEvaluationRunner.DetectionEvaluationRun detectionRun,
    List<String> limitations
) {
    public GeneratedCorpusEvaluationResult {
        provenance = Objects.requireNonNull(provenance, "provenance");
        phaseCounts = Objects.requireNonNull(phaseCounts, "phaseCounts");
        detectionRun = Objects.requireNonNull(detectionRun, "detectionRun");
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
