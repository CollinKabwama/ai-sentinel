package dev.aisentinel.core.evaluation;

import java.util.List;
import java.util.Objects;

/**
 * Result of detection evaluation against a generated Evaluation Kit corpus.
 * <p>
 * Detection metrics remain specialized ({@link DetectionEvaluationRunner.DetectionEvaluationRun}).
 * Provenance, phase counts, and event inspections are generated-corpus evaluation-layer metadata.
 * Event inspections join authored ground truth with runtime replay outcomes for report consumers.
 */
public record GeneratedCorpusEvaluationResult(
    GeneratedCorpusProvenance provenance,
    GeneratedCorpusPhaseCounts phaseCounts,
    DetectionEvaluationRunner.DetectionEvaluationRun detectionRun,
    List<GeneratedCorpusEventInspection> eventInspections,
    List<String> limitations
) {
    public GeneratedCorpusEvaluationResult {
        provenance = Objects.requireNonNull(provenance, "provenance");
        phaseCounts = Objects.requireNonNull(phaseCounts, "phaseCounts");
        detectionRun = Objects.requireNonNull(detectionRun, "detectionRun");
        eventInspections = eventInspections == null ? List.of() : List.copyOf(eventInspections);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        if (eventInspections.size() != phaseCounts.totalEvents()) {
            throw new IllegalArgumentException("eventInspections size must match phaseCounts.totalEvents");
        }
        if (eventInspections.size() != detectionRun.alignment().referenceEventCount()) {
            throw new IllegalArgumentException("eventInspections size must match replay reference event count");
        }
    }
}
